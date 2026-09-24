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
        if (!ready) return null
        val view = webView ?: return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<DisplayHubApi.Assignment?>(null)

        val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/rpc/poll_registered_device_assignment"
        val payload = JSONObject()
            .put("p_device_id", deviceId)
            .put("p_device_secret", deviceSecret)
            .toString()

        val script = """
            (async () => {
              try {
                const response = await fetch(${JSONObject.quote(endpoint)}, {
                  method: 'POST',
                  headers: {
                    'Content-Type': 'application/json',
                    'apikey': ${JSONObject.quote(BuildConfig.SUPABASE_ANON_KEY)}
                  },
                  body: ${JSONObject.quote(payload)}
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

                    if (!envelope.optBoolean("transportOk", false) || !envelope.optBoolean("ok", false)) {
                        return@evaluateJavascript
                    }

                    val body = envelope.optString("body")
                    if (body.isBlank()) return@evaluateJavascript
                    val json = JSONObject(body)
                    result.set(
                        DisplayHubApi.Assignment(
                            status = json.optString("status", "waiting"),
                            playerUrl = json.optString("player_url").takeIf { it.isNotBlank() },
                        ),
                    )
                } catch (_: Throwable) {
                    // Keep the original native error visible if the isolated browser fallback fails.
                } finally {
                    latch.countDown()
                }
            }
        }

        if (!latch.await(12, TimeUnit.SECONDS)) return null
        return result.get()
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
