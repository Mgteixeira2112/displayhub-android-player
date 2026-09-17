package br.com.displayhub.player

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONTokener

/** One-shot diagnostic: reuse pairing; never install the native video bridge, interceptor or playback script. */
class DiagnosticActivity : AppCompatActivity() {
    private var playerView: WebView? = null
    private var failureView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private val probe = object : Runnable {
        override fun run() {
            val view = playerView ?: return
            // Read-only probe: never change a video's source, playback, or attributes.
            view.evaluateJavascript("""
                (() => {
                  const videos = Array.from(document.querySelectorAll('video'));
                  if (!videos.length) return JSON.stringify({count:0});
                  const v = videos[0];
                  const source = v.currentSrc || v.src || v.querySelector('source')?.src || '';
                  const mime = v.currentType || v.querySelector('source')?.type || '';
                  return JSON.stringify({count:videos.length,ready:v.readyState,network:v.networkState,error:v.error?.code || 0,mime:mime,extension:(source.split('?')[0].match(/\\.([a-z0-9]+)$/i)||[])[1] || '',paused:v.paused});
                })()
            """.trimIndent()) { encoded ->
                if (isFinishing || isDestroyed) return@evaluateJavascript
                try {
                    val inner = JSONTokener(encoded).nextValue() as? String ?: return@evaluateJavascript
                    val data = org.json.JSONObject(inner)
                    val count = data.optInt("count", 0)
                    if (count == 0) {
                        if (android.os.SystemClock.elapsedRealtime() - startedAt > 12_000) {
                            failureView?.text = "Diagnóstico: nenhum elemento de vídeo encontrado na página."
                        }
                    } else {
                        val error = data.optInt("error", 0)
                        val ready = data.optInt("ready", 0)
                        val network = data.optInt("network", 0)
                        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                        if (error != 0 || (elapsed > 12_000 && ready == 0)) {
                            val meaning = when (error) {
                                1 -> "reprodução interrompida"
                                2 -> "falha de rede"
                                3 -> "falha de decodificação"
                                4 -> "formato ou fonte não suportados"
                                else -> "vídeo não carregou"
                            }
                            failureView?.text = "Falha de vídeo: $meaning | código=$error | readyState=$ready | networkState=$network | tipo=${data.optString("mime").take(48)} | extensão=${data.optString("extension").take(12)} | vídeos=$count. Fotografe esta mensagem."
                        } else if (ready > 0) {
                            failureView?.text = ""
                        }
                    }
                } catch (_: Exception) {
                    // A broken JS probe must never interfere with playback.
                }
            }
            handler.postDelayed(this, 4_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PlayerPrefs(this)
        val url = prefs.playerUrl
        if (!prefs.activated || url.isNullOrBlank() || !url.startsWith("https://")) {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.BLACK)
                setPadding(32, 32, 32, 32)
            }
            root.addView(TextView(this).apply {
                text = "Diagnóstico indisponível. Faça o pareamento no DisplayHub Player antes de abrir esta opção."
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
            })
            setContentView(root)
            return
        }

        val view = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // Deliberately NO DisplayHubAndroid bridge, request interception, or injected playback script.
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        failureView?.text = "Falha ao abrir página: ${error?.errorCode ?: -1}. Fotografe esta mensagem."
                    }
                }
            }
            loadUrl(url)
        }
        playerView = view
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "DIAGNÓSTICO: página direta, sem cache nativo. Pressione Voltar para sair."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(12, 8, 12, 8)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        failureView = TextView(this).apply {
            setTextColor(Color.YELLOW)
            textSize = 15f
            setPadding(12, 4, 12, 4)
        }
        root.addView(failureView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        startedAt = android.os.SystemClock.elapsedRealtime()
        handler.postDelayed(probe, 4_000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(probe)
        failureView = null
        playerView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        playerView = null
        super.onDestroy()
    }
}
