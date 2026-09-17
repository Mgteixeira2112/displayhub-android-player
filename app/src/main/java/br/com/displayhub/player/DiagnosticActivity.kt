package br.com.displayhub.player

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import org.json.JSONObject
import org.json.JSONTokener

/** Reuses pairing; never installs the native video bridge, request interceptor or playback script. */
class DiagnosticActivity : AppCompatActivity() {
    private var playerView: WebView? = null
    private var statusView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var pageState = "iniciando"
    private var probeSequence = 0
    private var lastResponseSequence = 0

    private val probe = object : Runnable {
        override fun run() {
            val view = playerView ?: return
            val sequence = ++probeSequence
            // Display a status *before* evaluateJavascript: even a broken/old WebView must not leave a blank panel.
            val responseStatus = if (lastResponseSequence < sequence - 1) "sem resposta do WebView" else "consultando WebView"
            statusView?.text = "Diagnóstico ${BuildConfig.VERSION_NAME} | Página: $pageState | Consulta $sequence: $responseStatus. Fotografe esta linha."
            // ES5 syntax deliberately avoids optional chaining/modern JS unsupported by older TV WebViews.
            view.evaluateJavascript("""
                (function () {
                  try {
                    var videos = document.querySelectorAll('video');
                    var frames = document.querySelectorAll('iframe').length;
                    var canvases = document.querySelectorAll('canvas').length;
                    var state = document.readyState || 'indefinido';
                    if (!videos.length) return JSON.stringify({ok:true,page:state,count:0,iframes:frames,canvases:canvases});
                    var selected = 0;
                    for (var i = 0; i < videos.length && i < 25; i++) {
                      if ((videos[i].error && videos[i].error.code) || videos[i].readyState === 0 || videos[i].videoWidth === 0) {
                        selected = i;
                        break;
                      }
                    }
                    var v = videos[selected];
                    var source = v.querySelector('source');
                    var url = v.currentSrc || v.src || (source && source.src) || '';
                    var kind = !url ? 'sem fonte' : /^blob:/i.test(url) ? 'blob' : /^https?:/i.test(url) ? 'http(s)' : 'outra';
                    var mime = v.getAttribute('type') || (source && source.getAttribute('type')) || 'não declarado';
                    return JSON.stringify({ok:true,page:state,count:videos.length,index:selected + 1,iframes:frames,canvases:canvases,ready:v.readyState,network:v.networkState,error:v.error ? v.error.code : 0,width:v.videoWidth,height:v.videoHeight,seconds:Math.floor(v.currentTime || 0),paused:v.paused,source:kind,mime:mime});
                  } catch (e) {
                    return JSON.stringify({ok:false,reason:'falha ao consultar elementos da página'});
                  }
                })()
            """.trimIndent()) { encoded ->
                if (isFinishing || isDestroyed || sequence < lastResponseSequence) return@evaluateJavascript
                lastResponseSequence = sequence
                try {
                    // evaluateJavascript returns a JSON-encoded string when JS returns JSON.stringify(...).
                    val value = JSONTokener(encoded).nextValue()
                    val data = when (value) {
                        is String -> JSONObject(value)
                        is JSONObject -> value
                        else -> null
                    }
                    if (data == null) {
                        statusView?.text = "Diagnóstico ${BuildConfig.VERSION_NAME} | Página: $pageState | Consulta $sequence: retorno vazio/inválido do WebView. Fotografe."
                        return@evaluateJavascript
                    }
                    if (!data.optBoolean("ok", false)) {
                        statusView?.text = "Diagnóstico ${BuildConfig.VERSION_NAME} | Página: $pageState | Consulta $sequence: ${data.optString("reason", "erro na consulta")}. Fotografe."
                        return@evaluateJavascript
                    }
                    val count = data.optInt("count", 0)
                    val page = data.optString("page", "?")
                    val frameCount = data.optInt("iframes", 0)
                    val canvasCount = data.optInt("canvases", 0)
                    val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000
                    if (count == 0) {
                        statusView?.text = "Diagnóstico ${BuildConfig.VERSION_NAME} | Página: $pageState/$page | Consulta $sequence OK. Vídeos: 0 | iframes: $frameCount | canvas: $canvasCount | ${if (elapsed >= 12) "nenhum vídeo HTML encontrado" else "verificando carregamento"}. Fotografe."
                    } else {
                        val error = data.optInt("error", 0)
                        val ready = data.optInt("ready", 0)
                        val width = data.optInt("width", 0)
                        val height = data.optInt("height", 0)
                        val condition = when {
                            error == 2 -> "ERRO DE REDE"
                            error == 3 -> "ERRO DE DECODIFICAÇÃO"
                            error == 4 -> "FONTE NÃO SUPORTADA"
                            error == 1 -> "REPRODUÇÃO INTERROMPIDA"
                            elapsed >= 12 && ready == 0 -> "SEM DADOS DE VÍDEO"
                            elapsed >= 12 && width == 0 -> "SEM DIMENSÕES DE VÍDEO"
                            else -> "estado observado (imagem não confirmada)"
                        }
                        statusView?.text = "Diagnóstico ${BuildConfig.VERSION_NAME} | Página: $pageState/$page | Consulta $sequence OK | $condition\nVídeo ${data.optInt("index", 1)}/$count | erro=$error | ready=$ready | rede=${data.optInt("network", 0)} | imagem=${width}x$height | tempo=${data.optInt("seconds", 0)}s | pausado=${data.optBoolean("paused", false)} | fonte=${data.optString("source", "?")} | tipo=${data.optString("mime", "?").take(32)}. Fotografe."
                    }
                } catch (_: Exception) {
                    statusView?.text = "Diagnóstico ${BuildConfig.VERSION_NAME} | Página: $pageState | Consulta $sequence: resposta inválida do WebView. Fotografe."
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
        statusView = TextView(this).apply {
            text = "Diagnóstico ${BuildConfig.VERSION_NAME} ativo | Página: iniciando | Verificação pendente. Fotografe este painel."
            setTextColor(Color.YELLOW)
            textSize = 15f
            setPadding(12, 4, 12, 8)
        }
        root.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    pageState = "carregando"
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pageState = "carregada"
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        pageState = "erro ${error?.errorCode ?: -1}"
                        statusView?.text = "Diagnóstico ${BuildConfig.VERSION_NAME} | Erro ao abrir página: ${error?.errorCode ?: -1}. Fotografe."
                    }
                }
            }
        }
        playerView = view
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        startedAt = SystemClock.elapsedRealtime()
        view.loadUrl(url)
        handler.postDelayed(probe, 1_000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(probe)
        statusView = null
        playerView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        playerView = null
        super.onDestroy()
    }
}
