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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.json.JSONTokener

/** One-shot diagnostic. Never changes pairing, video URLs or the normal player's cache. */
class DiagnosticActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var playerView: WebView? = null
    private var statusView: TextView? = null
    private var testing = false
    private var resultVisible = false
    private var startedAt = 0L
    private var pageState = "iniciando"
    private var lastResult = "Nenhuma consulta ao vídeo foi concluída."
    private var attempts = 0

    private val probe = object : Runnable {
        override fun run() {
            if (!testing) return
            val view = playerView ?: return
            attempts += 1
            lastResult = "Consulta $attempts iniciada; aguardando resposta do WebView."
            view.evaluateJavascript("""
                (function () {
                  try {
                    var videos = document.querySelectorAll('video');
                    var frames = document.querySelectorAll('iframe').length;
                    var canvas = document.querySelectorAll('canvas').length;
                    var state = document.readyState || 'indefinido';
                    if (!videos.length) return JSON.stringify({ok:true,page:state,count:0,frames:frames,canvas:canvas});
                    var selected = 0;
                    for (var i = 0; i < videos.length && i < 25; i++) {
                      if ((videos[i].error && videos[i].error.code) || videos[i].readyState === 0 || videos[i].videoWidth === 0) {
                        selected = i;
                        break;
                      }
                    }
                    var video = videos[selected];
                    var source = video.querySelector('source');
                    var url = video.currentSrc || video.src || (source && source.src) || '';
                    var kind = !url ? 'sem fonte' : /^blob:/i.test(url) ? 'blob' : /^https?:/i.test(url) ? 'http(s)' : 'outra';
                    var mime = video.getAttribute('type') || (source && source.getAttribute('type')) || 'não declarado';
                    return JSON.stringify({ok:true,page:state,count:videos.length,index:selected + 1,frames:frames,canvas:canvas,ready:video.readyState,network:video.networkState,error:video.error ? video.error.code : 0,width:video.videoWidth,height:video.videoHeight,seconds:Math.floor(video.currentTime || 0),paused:video.paused,source:kind,mime:mime});
                  } catch (e) {
                    return JSON.stringify({ok:false});
                  }
                })()
            """.trimIndent()) { encoded ->
                if (!testing) return@evaluateJavascript
                try {
                    val decoded = JSONTokener(encoded).nextValue()
                    val data = when (decoded) {
                        is String -> JSONObject(decoded)
                        is JSONObject -> decoded
                        else -> null
                    }
                    if (data == null || !data.optBoolean("ok", false)) {
                        lastResult = "Consulta $attempts: a página não retornou dados de mídia."
                    } else {
                        val count = data.optInt("count", 0)
                        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000
                        lastResult = if (count == 0) {
                            "Vídeos HTML: 0 | iframes: ${data.optInt("frames", 0)} | canvas: ${data.optInt("canvas", 0)} | documento: ${data.optString("page", "?")} | ${elapsed}s."
                        } else {
                            val error = data.optInt("error", 0)
                            val ready = data.optInt("ready", 0)
                            val width = data.optInt("width", 0)
                            val height = data.optInt("height", 0)
                            val cause = when {
                                error == 2 -> "ERRO DE REDE"
                                error == 3 -> "ERRO DE DECODIFICAÇÃO"
                                error == 4 -> "FONTE NÃO SUPORTADA"
                                error == 1 -> "REPRODUÇÃO INTERROMPIDA"
                                elapsed >= 10 && ready == 0 -> "SEM DADOS DE VÍDEO"
                                elapsed >= 10 && width == 0 -> "SEM DIMENSÕES DE VÍDEO"
                                else -> "estado observado; imagem não confirmada"
                            }
                            "$cause | vídeo ${data.optInt("index", 1)}/$count | erro=$error | pronto=$ready | rede=${data.optInt("network", 0)} | dimensão=${width}x$height | tempo=${data.optInt("seconds", 0)}s | pausado=${data.optBoolean("paused", false)} | fonte=${data.optString("source", "?")} | tipo=${data.optString("mime", "?").take(30)} | ${elapsed}s."
                        }
                    }
                    statusView?.text = "DIAGNÓSTICO ${BuildConfig.VERSION_NAME} | Consulta $attempts recebida. Pressione VOLTAR para ver o relatório."
                } catch (_: Exception) {
                    lastResult = "Consulta $attempts: resposta inválida do WebView."
                }
            }
            handler.postDelayed(this, 3_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PlayerPrefs(this)
        val url = prefs.playerUrl
        if (!prefs.activated || url.isNullOrBlank() || !url.startsWith("https://")) {
            showScreen("Diagnóstico indisponível", "O player precisa estar pareado antes de iniciar o teste.", null)
            return
        }
        showScreen(
            "DIAGNÓSTICO ${BuildConfig.VERSION_NAME}",
            "Este teste utiliza a página já pareada, sem cache nativo. O vídeo só começará após selecionar Iniciar. Pressione VOLTAR durante o teste para fechar a página e ver o relatório.",
            url,
        )
    }

    private fun showScreen(title: String, message: String, testUrl: String?) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 28f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.YELLOW)
            setTextColor(Color.BLACK)
            setPadding(14, 18, 14, 18)
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = message
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(12, 24, 12, 24)
        }, matchWrap())
        if (testUrl != null) root.addView(Button(this).apply {
            text = "Iniciar teste curto"
            setOnClickListener { startTest(testUrl) }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "Sair"
            setOnClickListener { finish() }
        }, matchWrap())
        setContentView(root)
    }

    private fun startTest(url: String) {
        testing = true
        resultVisible = false
        startedAt = SystemClock.elapsedRealtime()
        pageState = "iniciando"
        lastResult = "Nenhuma consulta ao vídeo foi concluída."
        attempts = 0
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
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
            // No native video bridge, request interception or injected playback script.
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
                    if (request?.isForMainFrame == true) pageState = "erro de página ${error?.errorCode ?: -1}"
                }
            }
        }
        playerView = view
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        statusView = TextView(this).apply {
            text = "DIAGNÓSTICO ${BuildConfig.VERSION_NAME} | Pressione VOLTAR para ver o relatório."
            setBackgroundColor(Color.YELLOW)
            setTextColor(Color.BLACK)
            textSize = 18f
            setPadding(16, 12, 16, 12)
            elevation = 32f
        }
        root.addView(statusView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
        view.loadUrl(url)
        handler.postDelayed(probe, 1_000)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!testing) {
            super.onBackPressed()
            return
        }
        showReport()
    }

    private fun showReport() {
        if (!testing || resultVisible) return
        testing = false
        resultVisible = true
        handler.removeCallbacks(probe)
        val report = "Página: $pageState | consultas iniciadas: $attempts\n\n$lastResult\n\nFotografe esta tela. O relatório não contém links nem tokens."
        val view = playerView
        playerView = null
        statusView = null
        view?.stopLoading()
        view?.loadUrl("about:blank")
        showScreen("RELATÓRIO ${BuildConfig.VERSION_NAME}", report, null)
        view?.destroy()
    }

    override fun onDestroy() {
        testing = false
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

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
