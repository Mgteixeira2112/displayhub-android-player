package br.com.displayhub.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LocalVideoCache(context: Context) {
    companion object {
        private const val TAG = "DisplayHubVideoCache"
    }

    private val directory = File(context.filesDir, "displayhub-videos").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Any>()
    private val downloads = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap.newKeySet<String>()
    private val downloader = Executors.newSingleThreadExecutor()

    fun localUrl(remoteUrl: String): String {
        if (!isRemoteVideoUrl(remoteUrl)) return remoteUrl
        if (cachedFile(remoteUrl) != null) return localUrlFor(remoteUrl)
        prefetch(remoteUrl)
        // Keep the visible video on the original network URL until the cache is ready.
        // Switching to displayhub.local too early turns a healthy remote stream into a 503.
        return remoteUrl
    }

    fun prefetchAll(remoteUrls: Collection<String>) {
        remoteUrls
            .asSequence()
            .map { it.trim() }
            .filter(::isRemoteVideoUrl)
            .distinct()
            .forEach(::prefetch)
    }

    fun status(remoteUrl: String): String {
        if (!isRemoteVideoUrl(remoteUrl)) return "unsupported"
        if (cachedFile(remoteUrl) != null) return "ready"
        val key = sha256(remoteUrl)
        return when {
            downloads.contains(key) -> "downloading"
            failures.contains(key) -> "failed"
            else -> "missing"
        }
    }

    fun snapshot(remoteUrls: Collection<String>): JSONObject {
        val urls = remoteUrls
            .asSequence()
            .map { it.trim() }
            .filter(::isRemoteVideoUrl)
            .distinct()
            .toList()

        var ready = 0
        var downloading = 0
        var failed = 0
        var missing = 0
        val items = JSONArray()

        urls.forEach { url ->
            val state = status(url)
            when (state) {
                "ready" -> ready += 1
                "downloading" -> downloading += 1
                "failed" -> failed += 1
                else -> missing += 1
            }
            items.put(JSONObject().put("url", url).put("status", state))
        }

        return JSONObject()
            .put("total", urls.size)
            .put("ready", ready)
            .put("downloading", downloading)
            .put("failed", failed)
            .put("missing", missing)
            .put("items", items)
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.host == "displayhub.local" && uri.path.orEmpty().startsWith("/video/")) {
            val encoded = uri.lastPathSegment ?: return unavailableResponse()
            val remoteUrl = runCatching {
                String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            }.getOrNull() ?: return unavailableResponse()
            val file = cachedFile(remoteUrl) ?: return unavailableResponse()
            return serveFile(request, remoteUrl, file)
        }

        val remoteUrl = uri.toString()
        if (!isRemoteVideoUrl(remoteUrl)) return null
        val file = cachedFile(remoteUrl) ?: return null
        return serveFile(request, remoteUrl, file)
    }

    private fun isRemoteVideoUrl(remoteUrl: String): Boolean {
        if (!remoteUrl.startsWith("https://") && !remoteUrl.startsWith("http://")) return false
        val path = runCatching { Uri.parse(remoteUrl).path.orEmpty().lowercase() }.getOrDefault("")
        return path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm")
    }

    private fun localUrlFor(remoteUrl: String): String {
        val encoded = Base64.encodeToString(
            remoteUrl.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "https://displayhub.local/video/$encoded"
    }

    private fun cachedFile(remoteUrl: String): File? {
        val target = File(directory, sha256(remoteUrl))
        if (!target.exists() || target.length() <= 0) return null
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    private fun prefetch(remoteUrl: String) {
        val key = sha256(remoteUrl)
        if (cachedFile(remoteUrl) != null || !downloads.add(key)) return
        failures.remove(key)
        Log.d(TAG, "prefetch_queued key=$key")
        downloader.execute {
            try {
                val file = getOrDownload(remoteUrl)
                if (file != null) {
                    failures.remove(key)
                    Log.d(TAG, "prefetch_ready key=$key bytes=${file.length()}")
                } else {
                    failures.add(key)
                    Log.w(TAG, "prefetch_failed key=$key")
                }
            } catch (error: Throwable) {
                failures.add(key)
                Log.w(TAG, "prefetch_failed key=$key", error)
            } finally {
                downloads.remove(key)
            }
        }
    }

    private fun getOrDownload(remoteUrl: String): File? {
        val key = sha256(remoteUrl)
        val target = File(directory, key)
        if (target.exists() && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        val lock = locks.getOrPut(key) { Any() }
        synchronized(lock) {
            if (target.exists() && target.length() > 0) return target
            val temp = File(directory, "$key.part")
            return try {
                val connection = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                }
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    connection.disconnect()
                    return null
                }
                connection.inputStream.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output, 1024 * 256) }
                }
                connection.disconnect()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                trimCache()
                target.takeIf { it.exists() && it.length() > 0 }
            } catch (_: Throwable) {
                temp.delete()
                null
            } finally {
                locks.remove(key)
            }
        }
    }

    private fun unavailableResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        503,
        "Video not ready",
        mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun serveFile(request: WebResourceRequest, remoteUrl: String, file: File): WebResourceResponse {
        val total = file.length()
        val range = parseRange(request.requestHeaders["Range"], total)
        val mime = mimeType(remoteUrl)
        val headers = mutableMapOf(
            "Accept-Ranges" to "bytes",
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "public, max-age=31536000",
        )
        if (request.method.equals("HEAD", ignoreCase = true)) {
            headers["Content-Length"] = total.toString()
            return WebResourceResponse(mime, null, 200, "OK", headers, ByteArrayInputStream(ByteArray(0)))
        }
        if (range == null) {
            headers["Content-Length"] = total.toString()
            return WebResourceResponse(mime, null, 200, "OK", headers, FileInputStream(file))
        }
        val (start, end) = range
        val length = end - start + 1
        val input = FileInputStream(file)
        var skipped = 0L
        while (skipped < start) {
            val step = input.skip(start - skipped)
            if (step <= 0) break
            skipped += step
        }
        headers["Content-Length"] = length.toString()
        headers["Content-Range"] = "bytes $start-$end/$total"
        return WebResourceResponse(mime, null, 206, "Partial Content", headers, LimitedInputStream(input, length))
    }

    private fun parseRange(value: String?, total: Long): Pair<Long, Long>? {
        if (value.isNullOrBlank() || !value.startsWith("bytes=")) return null
        val spec = value.removePrefix("bytes=").substringBefore(',')
        val parts = spec.split('-', limit = 2)
        val start = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val end = parts.getOrNull(1)?.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
        if (start < 0 || start >= total || end < start) return null
        return start to end
    }

    private fun mimeType(remoteUrl: String): String {
        val path = runCatching { Uri.parse(remoteUrl).path.orEmpty().lowercase() }.getOrDefault(remoteUrl.lowercase())
        return when {
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".m4v") -> "video/x-m4v"
            else -> "video/mp4"
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun trimCache() {
        val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        val limit = 750L * 1024L * 1024L
        if (total <= limit) return
        for (file in files.sortedBy { it.lastModified() }) {
            total -= file.length()
            file.delete()
            if (total <= limit) break
        }
    }

    private class LimitedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = super.read()
            if (value >= 0) remaining -= 1
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val allowed = minOf(length.toLong(), remaining).toInt()
            val count = super.read(buffer, offset, allowed)
            if (count > 0) remaining -= count.toLong()
            return count
        }
    }
}

class DisplayHubVideoBridge(private val cache: LocalVideoCache) {
    @JavascriptInterface
    fun localizeVideo(url: String): String = cache.localUrl(url)

    @JavascriptInterface
    fun prefetchVideos(urlsJson: String): String {
        val urls = parseUrls(urlsJson)
        cache.prefetchAll(urls)
        return cache.snapshot(urls).toString()
    }

    @JavascriptInterface
    fun videoCacheStatus(urlsJson: String): String {
        val urls = parseUrls(urlsJson)
        return cache.snapshot(urls).toString()
    }

    private fun parseUrls(urlsJson: String): List<String> = runCatching {
        val array = JSONArray(urlsJson)
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index)?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.getOrDefault(emptyList())
}
