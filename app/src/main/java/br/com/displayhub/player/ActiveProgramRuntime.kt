package br.com.displayhub.player

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URLConnection

/**
 * Read-only runtime view of the last known-good ACTIVE program.
 *
 * A request is served from the universal cache only when every asset declared by ACTIVE is still
 * integrity-valid. This keeps activation atomic at playback time: the WebView never receives a
 * mixture of assets from a partially available program.
 */
class ActiveProgramRuntime(context: Context) {
    private val cache = UniversalAssetCache(context)
    private val slots = ProgramSlotStore(context, cache)

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val active = readyActive() ?: return null
        val requestKey = canonicalAssetKey(request.url.toString())
        val asset = active.assets.firstOrNull { canonicalAssetKey(it.url) == requestKey } ?: return null
        val file = cache.localFile(asset) ?: return null
        return serveFile(request, asset, file)
    }

    fun snapshot(): JSONObject {
        val active = slots.active()
            ?: return JSONObject()
                .put("present", false)
                .put("ready", false)

        val cacheSnapshot = cache.snapshot(active)
        val ready = active.assets.all { cache.status(it) == AssetStatus.READY }
        return JSONObject()
            .put("present", true)
            .put("ready", ready)
            .put("programVersion", active.programVersion)
            .put("displayId", active.displayId)
            .put("cache", cacheSnapshot)
    }

    private fun readyActive(): ProgramManifest? {
        val active = slots.active() ?: return null
        return active.takeIf { manifest ->
            manifest.assets.all { cache.status(it) == AssetStatus.READY }
        }
    }

    /**
     * Supabase signed URLs change their query token over time. The storage object path remains
     * stable, so ACTIVE matching deliberately ignores query/fragment while preserving scheme,
     * host and path. This lets the WebView request a freshly signed URL and still receive the
     * integrity-checked local asset cached from the manifest.
     */
    private fun canonicalAssetKey(rawUrl: String): String = runCatching {
        val uri = Uri.parse(rawUrl)
        uri.buildUpon()
            .clearQuery()
            .fragment(null)
            .build()
            .toString()
    }.getOrElse {
        rawUrl.substringBefore('?').substringBefore('#')
    }

    private fun serveFile(
        request: WebResourceRequest,
        asset: AssetManifest,
        file: File,
    ): WebResourceResponse {
        val total = file.length()
        val range = parseRange(request.requestHeaders["Range"], total)
        val mime = mimeType(asset)
        val encoding = if (isTextMime(mime)) "utf-8" else null
        val headers = mutableMapOf(
            "Accept-Ranges" to "bytes",
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "public, max-age=31536000, immutable",
            "X-DisplayHub-Program-Version" to slots.active()?.programVersion?.toString().orEmpty(),
        )

        if (request.method.equals("HEAD", ignoreCase = true)) {
            headers["Content-Length"] = total.toString()
            return WebResourceResponse(
                mime,
                encoding,
                200,
                "OK",
                headers,
                ByteArrayInputStream(ByteArray(0)),
            )
        }

        if (range == null) {
            headers["Content-Length"] = total.toString()
            return WebResourceResponse(mime, encoding, 200, "OK", headers, FileInputStream(file))
        }

        val (start, end) = range
        val length = end - start + 1
        val input = FileInputStream(file)
        var skipped = 0L
        while (skipped < start) {
            val step = input.skip(start - skipped)
            if (step <= 0L) break
            skipped += step
        }
        headers["Content-Length"] = length.toString()
        headers["Content-Range"] = "bytes $start-$end/$total"
        return WebResourceResponse(
            mime,
            encoding,
            206,
            "Partial Content",
            headers,
            LimitedInputStream(input, length),
        )
    }

    private fun parseRange(value: String?, total: Long): Pair<Long, Long>? {
        if (total <= 0L || value.isNullOrBlank() || !value.startsWith("bytes=")) return null
        val spec = value.removePrefix("bytes=").substringBefore(',')
        val parts = spec.split('-', limit = 2)
        val start = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val end = parts.getOrNull(1)?.toLongOrNull()?.coerceAtMost(total - 1L) ?: (total - 1L)
        if (start < 0L || start >= total || end < start) return null
        return start to end
    }

    private fun mimeType(asset: AssetManifest): String {
        URLConnection.guessContentTypeFromName(Uri.parse(asset.url).lastPathSegment.orEmpty())?.let {
            return it
        }

        val path = Uri.parse(asset.url).path.orEmpty().lowercase()
        return when {
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".m4v") -> "video/x-m4v"
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".ttf") -> "font/ttf"
            path.endsWith(".otf") -> "font/otf"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".html") || path.endsWith(".htm") -> "text/html"
            else -> when (asset.type) {
                AssetType.VIDEO -> "video/mp4"
                AssetType.IMAGE, AssetType.THUMBNAIL -> "application/octet-stream"
                AssetType.FONT -> "application/octet-stream"
                AssetType.JSON, AssetType.SMART_CONTENT -> "application/json"
                AssetType.LAYOUT -> "text/html"
            }
        }
    }

    private fun isTextMime(mime: String): Boolean =
        mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/javascript" ||
            mime == "image/svg+xml"

    private class LimitedInputStream(
        input: InputStream,
        private var remaining: Long,
    ) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = super.read()
            if (value >= 0) remaining -= 1L
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val allowed = minOf(length.toLong(), remaining).toInt()
            val count = super.read(buffer, offset, allowed)
            if (count > 0) remaining -= count.toLong()
            return count
        }
    }
}