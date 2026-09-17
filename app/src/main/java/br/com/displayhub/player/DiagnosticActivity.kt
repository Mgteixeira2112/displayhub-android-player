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
import android.widget.ScrollView
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
            val sequence = attempts
            if (sequence == 1) lastResult = "Consulta iniciada; aguardando resposta do WebView."
            view.evaluateJavascript("""
                (function () {
                  try {
                    var videos = document.querySelectorAll('video');
                    var results = [];
                    for (var i = 0; i < videos.length && i < 4; i++) {
                      var video = videos[i];
                      var rect = video.getBoundingClientRect();
                      var visible = rect.width > 1 && rect.height > 1 && rect.right > 0 && rect.bottom > 0 && rect.left < window.innerWidth && rect.top < window.innerHeight;
                      var hiddenParent = false;
                      var node = video.parentElement;
                      while (node && node !== document.documentElement) {
                        if (node.getAttribute('aria-hidden') === 'true') hiddenParent = true;
                        var style = window.getComputedStyle(node);
                        if (style && (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0')) visible = false;
                        node = node.parentElement;
                      }
                      var ownStyle = window.getComputedStyle(video);
                      if (ownStyle && (ownStyle.display === 'none' || ownStyle.visibility === 'hidden' || ownStyle.opacity === '0')) visible = false;
                      if (!video.__dhPassiveDiagnostics) {
                        var stats = {last:'nenhum',waiting:0,stalled:0,progress:0,loadeddata:0,playing:0,error:0,suspend:0,canplay:0};
                        video.__dhPassiveDiagnostics = stats;
                        ['waiting','stalled','progress','loadeddata','playing','error','suspend','canplay','emptied','abort'].forEach(function (eventName) {
                          video.addEventListener(eventName, function () {
                            stats.last = eventName;
                            if (typeof stats[eventName] === 'number') stats[eventName] += 1;
                          });
                        });
                      }
                      var history = video.__dhPassiveDiagnostics;
                      var source = video.querySelector('source');
                      var url = video.currentSrc || video.src || (source && source.src) || '';
                      var kind = !url ? 'sem fonte' : /^blob:/i.test(url) ? 'blob' : /^https?:/i.test(url) ? 'http(s)' : 'outra';
                      var clean = url.split('?')[0].split('#')[0].toLowerCase();
                      var format = clean.slice(-5) === '.webm' ? 'webm' : clean.slice(-4) === '.mp4' ? 'mp4' : clean.slice(-5) === '.m3u8' ? 'hls' : 'indefinido';
                      var buffered = video.buffered;
                      var ranges = buffered.length;
                      var bufferEnd = ranges ? buffered.end(ranges - 1) : -1;
                      var seekable = video.seekable.length;
                      var quality = typeof video.getVideoPlaybackQuality === 'function' ? video.getVideoPlaybackQuality() : null;
                      results.push({index:i+1,role:visible ? 'na tela' : (hiddenParent ? 'pré-carregamento' : 'fora da tela'),ready:video.readyState,network:video.networkState,error:video.error ? video.error.code : 0,width:video.videoWidth,height:video.videoHeight,seconds:Number(video.currentTime.toFixed(2)),paused:video.paused,source:kind,format:format,rangeCount:ranges,bufferEnd:Number(bufferEnd.toFixed(2)),seekable:seekable,duration:Number.isFinite(video.duration) ? Number(video.duration.toFixed(2)) : -1,frames:quality ? quality.totalVideoFrames : -1,lastEvent:history.last,waiting:history.waiting,stalled:history.stalled,progress:history.progress,loadeddata:history.loadeddata,playing:history.playing,suspend:history.suspend});
                    }
                    return JSON.stringify({ok:true,page:document.readyState || 'indefinido',count:videos.length,frames:document.querySelectorAll('iframe').length,videos:results});
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
                        lastResult = "Consulta $sequence: a página não retornou dados de mídia."
                    } else {
                        val count = data.optInt("count", 0)
                        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000
                        val rows = data.optJSONArray("videos")
                        val report = StringBuilder("Vídeos HTML: $count | iframes: ${data.optInt("frames", 0)} | documento: ${data.optString("page", "?")} | ${elapsed}s.")
                        if (count == 0) report.append(" Nenhum vídeo encontrado neste documento.")
                        if (rows != null) {
                            for (index in 0 until rows.length()) {
                                val video = rows.optJSONObject(index) ?: continue
                                val error = video.optInt("error", 0)
                                val ready = video.optInt("ready", 0)
                                val network = video.optInt("network", 0)
                                val role = video.optString("role", "?")
                                val condition = when {
                                    error == 2 -> "ERRO DE REDE"
                                    error == 3 -> "ERRO DE DECODIFICAÇÃO"
                                    error == 4 -> "FONTE NÃO SUPORTADA"
                                    error == 1 -> "REPRODUÇÃO INTERROMPIDA"
                                    ready == 0 -> "SEM METADADOS"
                                    video.optBoolean("paused", false) && role == "na tela" -> "VÍDEO VISÍVEL PAUSADO"
                                    else -> "estado observado"
                                }
                                val readyName = when (ready) { 0 -> "sem dados"; 1 -> "metadados"; 2 -> "quadro atual"; 3 -> "dados futuros"; 4 -> "suficiente"; else -> "?" }
                                val networkName = when (network) { 0 -> "vazio"; 1 -> "ocioso"; 2 -> "carregando"; 3 -> "sem fonte"; else -> "?" }
                                val frames = video.optInt("frames", -1)
                                val framesLabel = if (frames < 0) "indisponível" else frames.toString()
                                report.append("\nVídeo ${video.optInt("index", index + 1)} [$role]: $condition | erro=$error | pronto=$ready ($readyName) | rede=$network ($networkName) | ${video.optInt("width", 0)}x${video.optInt("height", 0)}.")
                                report.append("\n  Tempo=${video.optDouble("seconds", 0.0)}s | pausado=${video.optBoolean("paused", false)} | fonte=${video.optString("source", "?")} | formato=${video.optString("format", "?")}.")
                                report.append("\n  Buffer: ${video.optInt("rangeCount", 0)} faixas, último fim=${video.optDouble("bufferEnd", -1.0)}s | seekable=${video.optInt("seekable", 0)} | duração=${video.optDouble("duration", -1.0)}s | quadros=$framesLabel.")
                                report.append("\n  Evento=${video.optString("lastEvent", "?")} | esperando=${video.optInt("waiting", 0)} | travou=${video.optInt("stalled", 0)} | progresso=${video.optInt("progress", 0)} | dados=${video.optInt("loadeddata", 0)} | reproduzindo=${video.optInt("playing", 0)} | suspenso=${video.optInt("suspend", 0)}.")
                            }
                        }
                        if (count > 4) report.append("\nSomente os 4 primeiros vídeos foram inspecionados.")
                        lastResult = report.toString()
                    }
                    statusView?.text = "DIAGNÓSTICO ${BuildConfig.VERSION_NAME} | Consulta $sequence recebida. Pressione VOLTAR para ver o relatório."
                } catch (_: Exception) {
                    lastResult = "Consulta $sequence: resposta inválida do WebView."
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
            textSize = if (title.startsWith("RELATÓRIO")) 17f else 20f
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
        val scroll = ScrollView(this).apply { fillViewport = true; addView(root) }
        setContentView(scroll)
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
