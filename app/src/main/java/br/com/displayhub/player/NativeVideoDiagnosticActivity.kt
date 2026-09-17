package br.com.displayhub.player

import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.json.JSONTokener

/** Test-only. Finds the current playlist video, destroys the WebView and plays that URL natively. */
class NativeVideoDiagnosticActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var browser: WebView? = null
    private var video: VideoView? = null
    private var videoUrl: String? = null
    private var status: TextView? = null
    private var discovering = false
    private var playing = false
    private var startedAt = 0L
    private var prepared = false
    private var firstFrame = false
    private var nativeError = "nenhum"
    private var lastPosition = 0
    private var duration = 0

    private val scan = object : Runnable {
        override fun run() {
            if (!discovering) return
            if (SystemClock.elapsedRealtime() - startedAt >= 20_000L) {
                stopDiscovery()
                showScreen("VÍDEO NÃO LOCALIZADO", "Não apareceu um vídeo Pexels visível em 20 segundos. Nenhum teste nativo foi iniciado.")
                return
            }
            val view = browser ?: return
            view.evaluateJavascript("""
                (function() {
                  try {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                      var v = videos[i], r = v.getBoundingClientRect();
                      var visible = r.width > 1 && r.height > 1 && r.right > 0 && r.bottom > 0 && r.left < innerWidth && r.top < innerHeight;
                      var node = v;
                      while (node && visible) {
                        var s = getComputedStyle(node);
                        if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') visible = false;
                        node = node.parentElement;
                      }
                      if (!visible) continue;
                      var source = v.querySelector('source');
                      var url = v.currentSrc || v.src || (source && source.src) || '';
                      if (/^https:\/\//i.test(url)) return JSON.stringify({url:url});
                    }
                    return JSON.stringify({url:''});
                  } catch(e) { return JSON.stringify({url:''}); }
                })()
            """.trimIndent()) { encoded ->
                if (!discovering) return@evaluateJavascript
                val found = try {
                    when (val result = JSONTokener(encoded).nextValue()) {
                        is String -> JSONObject(result).optString("url", "")
                        is JSONObject -> result.optString("url", "")
                        else -> ""
                    }
                } catch (_: Exception) { "" }
                if (allowed(found)) {
                    videoUrl = found
                    stopDiscovery()
                    showScreen("VÍDEO DA PLAYLIST LOCALIZADO", "A página foi fechada. O teste usa exatamente o vídeo que estava na tela, sem alterar o pareamento ou a playlist.", canPlay = true)
                } else {
                    handler.postDelayed(this, 1_000L)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PlayerPrefs(this)
        val pairedUrl = prefs.playerUrl
        if (!prefs.activated || pairedUrl.isNullOrBlank() || !pairedUrl.startsWith("https://")) {
            showScreen("TESTE INDISPONÍVEL", "O player precisa estar pareado; não limpe os dados.")
            return
        }
        showScreen("TESTE DE VÍDEO NATIVO", "Primeiro localizamos o vídeo na playlist pareada. Depois, fechamos a página e oferecemos a reprodução no Android nativo. Nada começa automaticamente.", discoverUrl = pairedUrl)
    }

    private fun allowed(src: String): Boolean = try {
        val uri = Uri.parse(src)
        uri.scheme.equals("https", true) && uri.userInfo == null && uri.host?.equals("videos.pexels.com", true) == true
    } catch (_: Exception) { false }

    private fun showScreen(title: String, message: String, canPlay: Boolean = false, discoverUrl: String? = null) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.YELLOW)
            setPadding(12, 14, 12, 14)
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = message
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(12, 22, 12, 22)
        }, matchWrap())
        if (discoverUrl != null) root.addView(Button(this).apply {
            text = "Localizar vídeo da playlist"
            setOnClickListener { discover(discoverUrl) }
        }, matchWrap())
        if (canPlay) root.addView(Button(this).apply {
            text = "Testar no Android nativo (até 15 segundos)"
            setOnClickListener { startNative() }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "Sair"
            setOnClickListener { finish() }
        }, matchWrap())
        setContentView(root)
    }

    private fun discover(url: String) {
        stopDiscovery()
        videoUrl = null
        discovering = true
        startedAt = SystemClock.elapsedRealtime()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            setBackgroundColor(Color.BLACK)
        }
        browser = view
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        status = TextView(this).apply {
            text = "LOCALIZANDO VÍDEO | até 20s. VOLTAR cancela."
            textSize = 18f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.YELLOW)
            setPadding(14, 14, 14, 14)
            elevation = 30f
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
        view.loadUrl(url)
        handler.postDelayed(scan, 1_000L)
    }

    private fun stopDiscovery() {
        discovering = false
        handler.removeCallbacks(scan)
        val view = browser
        browser = null
        status = null
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        }
    }

    private fun startNative() {
        val src = videoUrl
        if (src == null || !allowed(src)) {
            showScreen("VÍDEO INDISPONÍVEL", "Não foi possível validar o endereço. Nenhum teste foi iniciado.")
            return
        }
        playing = true
        prepared = false
        firstFrame = false
        nativeError = "nenhum"
        lastPosition = 0
        duration = 0
        startedAt = SystemClock.elapsedRealtime()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply {
            text = "ANDROID NATIVO | Preparando mídia..."
            textSize = 18f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.YELLOW)
            setPadding(16, 12, 16, 12)
        }
        root.addView(status, matchWrap())
        val view = VideoView(this)
        video = view
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply {
            text = "Encerrar teste e mostrar resultado"
            setOnClickListener { finishNative() }
        }, matchWrap())
        setContentView(root)
        view.setOnPreparedListener { player ->
            if (!playing) return@setOnPreparedListener
            prepared = true
            duration = player.duration.coerceAtLeast(0)
            player.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) firstFrame = true
                if (playing) updateStatus()
                false
            }
            view.start()
            updateStatus()
        }
        view.setOnErrorListener { _, what, extra ->
            nativeError = "erro Android $what / detalhe $extra"
            finishNative()
            true
        }
        view.setOnCompletionListener { finishNative() }
        view.setVideoURI(Uri.parse(src))
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!playing) return
                if (SystemClock.elapsedRealtime() - startedAt >= 15_000L) {
                    finishNative()
                    return
                }
                updateStatus()
                handler.postDelayed(this, 1_000L)
            }
        }, 1_000L)
    }

    private fun updateStatus() {
        val view = video ?: return
        lastPosition = try { view.currentPosition.coerceAtLeast(lastPosition) } catch (_: Exception) { lastPosition }
        status?.text = "ANDROID NATIVO | preparado=$prepared | primeiro quadro=$firstFrame | posição=${lastPosition}ms | duração=${duration}ms | erro=$nativeError | VOLTAR encerra."
    }

    private fun finishNative() {
        if (!playing) return
        updateStatus()
        playing = false
        val result = "Preparado: $prepared | primeiro quadro: $firstFrame | posição: ${lastPosition}ms | duração: ${duration}ms | erro: $nativeError.\n\nVocê visualizou imagens em movimento? Fotografe esta tela e informe sim ou não. Não há links nem tokens no relatório."
        video?.stopPlayback()
        video = null
        status = null
        showScreen("RESULTADO PLAYER NATIVO", result)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            playing -> finishNative()
            discovering -> { stopDiscovery(); showScreen("TESTE CANCELADO", "A busca foi interrompida sem alterar o pareamento.") }
            else -> super.onBackPressed()
        }
    }

    override fun onStop() {
        super.onStop()
        if (playing) finishNative()
        if (discovering) stopDiscovery()
    }

    override fun onDestroy() {
        stopDiscovery()
        video?.stopPlayback()
        video = null
        playing = false
        super.onDestroy()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
