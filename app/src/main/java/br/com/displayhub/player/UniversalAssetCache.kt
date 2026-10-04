package br.com.displayhub.player

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Content-addressed cache for assets declared by [ProgramManifest].
 *
 * Files are keyed by the expected SHA-256 instead of their remote URL. This makes cache identity
 * stable when URLs change and, more importantly, prevents a changed file at the same URL from
 * being accepted as the asset declared by a manifest.
 *
 * This foundation is intentionally not wired into playback yet. A3 will use it while staging a
 * complete program before an atomic activation.
 */
class UniversalAssetCache(
    context: Context,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    companion object {
        private const val TAG = "DisplayHubAssetCache"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val DEFAULT_MAX_BYTES = 2L * 1024L * 1024L * 1024L
    }

    private val directory = File(context.filesDir, "displayhub-assets").apply { mkdirs() }
    private val downloads = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, String>()
    private val executor = Executors.newFixedThreadPool(2)

    init {
        require(maxBytes > 0L) { "maxBytes must be positive" }
        removePartialDownloads()
    }

    fun status(asset: AssetManifest): AssetStatus {
        val target = targetFile(asset)
        if (target.exists()) {
            return if (validate(target, asset)) {
                target.setLastModified(System.currentTimeMillis())
                failures.remove(asset.sha256)
                AssetStatus.READY
            } else {
                target.delete()
                failures[asset.sha256] = "cached_file_validation_failed"
                AssetStatus.INVALID
            }
        }

        return when {
            downloads.contains(asset.sha256) -> AssetStatus.DOWNLOADING
            failures.containsKey(asset.sha256) -> AssetStatus.FAILED
            else -> AssetStatus.MISSING
        }
    }

    fun localFile(asset: AssetManifest): File? {
        val target = targetFile(asset)
        if (!target.exists() || !validate(target, asset)) return null
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    fun prefetch(manifest: ProgramManifest) {
        manifest.assets.forEach(::prefetch)
    }

    fun prefetch(asset: AssetManifest) {
        if (status(asset) == AssetStatus.READY) return
        val key = asset.sha256
        if (!downloads.add(key)) return
        failures.remove(key)
        Log.d(TAG, "asset_prefetch_queued id=${asset.id} sha=$key type=${asset.type.wireValue}")

        executor.execute {
            try {
                downloadAndCommit(asset)
                failures.remove(key)
                Log.d(TAG, "asset_prefetch_ready id=${asset.id} sha=$key bytes=${targetFile(asset).length()}")
            } catch (error: Throwable) {
                failures[key] = error.message ?: error.javaClass.simpleName
                Log.w(TAG, "asset_prefetch_failed id=${asset.id} sha=$key", error)
            } finally {
                downloads.remove(key)
            }
        }
    }

    fun snapshot(manifest: ProgramManifest): JSONObject {
        var ready = 0
        var downloading = 0
        var failed = 0
        var missing = 0
        var invalid = 0
        var readyBytes = 0L
        val items = JSONArray()

        manifest.assets.forEach { asset ->
            val state = status(asset)
            when (state) {
                AssetStatus.READY -> {
                    ready += 1
                    readyBytes += targetFile(asset).length()
                }
                AssetStatus.DOWNLOADING -> downloading += 1
                AssetStatus.FAILED -> failed += 1
                AssetStatus.INVALID -> invalid += 1
                AssetStatus.MISSING -> missing += 1
            }

            items.put(
                JSONObject()
                    .put("id", asset.id)
                    .put("type", asset.type.wireValue)
                    .put("sha256", asset.sha256)
                    .put("status", state.name.lowercase())
                    .put("error", failures[asset.sha256]),
            )
        }

        return JSONObject()
            .put("programVersion", manifest.programVersion)
            .put("total", manifest.assets.size)
            .put("ready", ready)
            .put("downloading", downloading)
            .put("failed", failed)
            .put("invalid", invalid)
            .put("missing", missing)
            .put("readyBytes", readyBytes)
            .put("items", items)
    }

    /**
     * Evicts least-recently-used assets until the configured budget is met.
     *
     * A3 will pass the active program's hashes in [pinnedSha256] so active content can never be
     * removed while old/orphaned assets are reclaimed.
     */
    fun trim(pinnedSha256: Set<String> = emptySet()) {
        val files = directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }
            .orEmpty()

        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return

        files
            .asSequence()
            .filterNot { pinnedSha256.contains(it.name) }
            .sortedBy { it.lastModified() }
            .forEach { file ->
                if (total <= maxBytes) return
                val size = file.length()
                if (file.delete()) total -= size
            }
    }

    private fun downloadAndCommit(asset: AssetManifest) {
        val target = targetFile(asset)
        if (target.exists() && validate(target, asset)) return
        if (target.exists()) target.delete()

        val temp = File(directory, "${asset.sha256}$PART_SUFFIX")
        temp.delete()

        val connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "DisplayHub-Android-Player/${BuildConfig.VERSION_NAME}",
            )
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("asset_download_http_${connection.responseCode}")
            }

            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                }
            }

            if (!validate(temp, asset)) {
                throw IllegalStateException("asset_integrity_mismatch")
            }

            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }

            if (!validate(target, asset)) {
                target.delete()
                throw IllegalStateException("asset_commit_validation_failed")
            }

            target.setLastModified(System.currentTimeMillis())
            trim()
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    private fun validate(file: File, asset: AssetManifest): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (asset.sizeBytes > 0L && file.length() != asset.sizeBytes) return false
        return sha256(file).equals(asset.sha256, ignoreCase = true)
    }

    private fun targetFile(asset: AssetManifest): File = File(directory, asset.sha256.lowercase())

    private fun removePartialDownloads() {
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(PART_SUFFIX) }
            ?.forEach { it.delete() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object Internal {
        private const val PART_SUFFIX = ".part"
    }
}
