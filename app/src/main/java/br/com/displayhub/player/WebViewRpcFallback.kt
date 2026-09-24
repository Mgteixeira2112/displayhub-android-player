package br.com.displayhub.player

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Browser-network fallback used only when the native RPC transport fails.
 *
 * The WebView contains only app-owned inline HTML. No remote page scripts are loaded,
 * no JavaScript interface is exposed, and device credentials are never rendered.
 */
class WebViewRpcFallback(private val activity: AppCompatActivity) {
    @Volatile
    private var ready = false
    private var webView: WebView? = null

    fun attach(container: FrameLayout) {
        destroy()
        val view = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            visibility = View.INVISIBLE
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    ready = true
                }
            }
        }
        webView = view
        container.addView(view, FrameLayout.LayoutParams(1, 1))
        view.loadDataWithBaseURL(
            TRUSTED_ORIGIN,
            "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>",
            "text/html",
            "UTF-8",
            TRUSTED_ORIGIN,
        )
    }

    fun pollAssignment(deviceId: String, deviceSecret: String): DisplayHubApi.Assignment? {
        val text = rpc(
            "poll_registered_device_assignment",
            JSONObject()
                .put("p_device_id", deviceId)
                .put("p_device_secret", deviceSecret),
        ) ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return DisplayHubApi.Assignment(
            status = json.optString("status", "waiting"),
            playerUrl = json.optString("player_url").takeIf { it.isNotBlank() },
        )
    }

    fun pollCommand(deviceId: String, deviceSecret: String): DisplayHubApi.RemoteCommand? {
        val text = rpc(
            "poll_windows_player_command",
            JSONObject()
                .put("p_device_id", deviceId)
                .put("p_device_secret", deviceSecret),
        )?.trim() ?: return null
        if (text.isEmpty() || text == "null") return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val id = json.optString("id")
        val command = json.optString("command")
        if (id.isBlank() || command.isBlank()) return null
        return DisplayHubApi.RemoteCommand(id, command)
    }

    fun completeCommand(
        deviceId: String,
        deviceSecret: String,
        commandId: String,
        success: Boolean,
        result: String,
    ): Boolean {
        val text = rpc(
            "complete_windows_player_command",
            JSONObject()
                .put("p_device_id", deviceId)
                .put("p_device_secret", deviceSecret)
                .put("p_command_id", commandId)
                .put("p_success", success)
                .put("p_result", result.take(500)),
        )?.trim() ?: return false
        return text == "true"
    }

    private fun rpc(name: String, body: JSONObject): String? {
        if (!ready) return null
        val view = webView ?: return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)

        val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/rpc/" + name
        val script = """
            (async () => {
              try {
                const response = await fetch(${JSONObject.quote(endpoint)}, {
                  method: 'POST',
                  headers: {
                    'Content-Type': 'application/json',
                    'apikey': ${JSONObject.quote(BuildConfig.SUPABASE_ANON_KEY)}
                  },
                  body: ${JSONObject.quote(body.toString())}
                });
                const body = await response.text();
                return JSON.stringify({
                  transportOk: true,
                  http: response.status,
                  ok: response.ok,
                  body: body
                });
              } catch (error) {
                return JSON.stringify({
                  transportOk: false,
                  error: error && error.name ? String(error.name) : 'fetch'
                });
              }
            })();
        """.trimIndent()

        activity.runOnUiThread {
            if (!ready || webView !== view) {
                latch.countDown()
                return@runOnUiThread
            }
            view.evaluateJavascript(script) { encoded ->
                try {
                    val decoded = JSONTokener(encoded).nextValue()
                    val envelope = when (decoded) {
                        is String -> JSONObject(decoded)
                        is JSONObject -> decoded
                        else -> null
                    } ?: return@evaluateJavascript
                    if (envelope.optBoolean("transportOk", false) && envelope.optBoolean("ok", false)) {
                        result.set(envelope.optString("body"))
                    }
                } catch (_: Throwable) {
                    // Keep native-path error handling if the isolated browser fallback also fails.
                } finally {
                    latch.countDown()
                }
            }
        }

        return try {
            if (!latch.await(12, TimeUnit.SECONDS)) null else result.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    fun destroy() {
        ready = false
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
    }

    companion object {
        private const val TRUSTED_ORIGIN = "https://mgteixeira2112.github.io/displayhub/"
    }
}
