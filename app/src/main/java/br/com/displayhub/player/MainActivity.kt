package br.com.displayhub.player

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: PlayerPrefs
    private lateinit var videoCache: LocalVideoCache
    private lateinit var autoUpdater: AutoUpdater
    private val api = DisplayHubApi()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor: ScheduledExecutorService? = null
    private var pairingExecutor: ScheduledExecutorService? = null
    private var webView: WebView? = null
    private var statusView: TextView? = null
    private var currentUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PlayerPrefs(this)
        videoCache = LocalVideoCache(this)
        autoUpdater = AutoUpdater(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (prefs.kioskEnabled) enterImmersiveMode()

        if (prefs.activated) showPlayerWaiting()
        else showActivationScreen()
        autoUpdater.checkIfDue()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.kioskEnabled) enterImmersiveMode()
        if (prefs.activated) startManagedLoop()
        autoUpdater.checkIfDue()
    }

    override fun onPause() {
        super.onPause()
        stopManagedLoop()
    }

    override fun onDestroy() {
        stopManagedLoop()
        stopPairingLoop()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun showActivationScreen() {
        stopManagedLoop()
        stopPairingLoop()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.rgb(8, 15, 30))
        }

        root.addView(TextView(this).apply {
            text = "DisplayHub Player"
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
        }, linearMatchWrap())

        root.addView(TextView(this).apply {
            text = "No DisplayHub, abra Ativar Player por código e informe o código abaixo"
            setTextColor(Color.LTGRAY)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 20)
        }, linearMatchWrap())

        val codeView = TextView(this).apply {
            text = "------"
            setTextColor(Color.WHITE)
            textSize = 42f
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 18)
        }
        root.addView(codeView, linearMatchWrap())

        val feedback = TextView(this).apply {
            text = "Gerando código..."
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 16)
        }
        root.addView(feedback, linearMatchWrap())

        val refresh = Button(this).apply {
            text = "Gerar novo código"
            isEnabled = false
        }
        root.addView(refresh, linearMatchWrap())

        val fallback = Button(this).apply {
            text = "Usar token de instalação"
            setOnClickListener { showTokenActivationScreen() }
        }
        root.addView(fallback, linearMatchWrap())

        setContentView(root)

        fun requestCode() {
            stopPairingLoop()
            refresh.isEnabled = false
            codeView.text = "------"
            feedback.text = "Gerando código..."
            Executors.newSingleThreadExecutor().execute {
                try {
                    val metrics = resources.displayMetrics
                    val label = prefs.deviceLabel ?: "Android ${Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).takeLast(6)}"
                    val session = api.createAndroidPairing(
                        deviceId = prefs.deviceId,
                        deviceSecret = prefs.deviceSecret,
                        deviceLabel = label,
                        width = metrics.widthPixels,
                        height = metrics.heightPixels,
                    )
                    prefs.deviceLabel = label
                    mainHandler.post {
                        codeView.text = session.code
                        feedback.text = "Código válido por 10 minutos. Aguardando ativação no DisplayHub..."
                        refresh.isEnabled = true
                        startPairingLoop(session.pairingId, feedback)
                    }
                } catch (error: Throwable) {
                    mainHandler.post {
                        refresh.isEnabled = true
                        feedback.text = error.message ?: "Não foi possível gerar o código."
                    }
                }
            }
        }

        refresh.setOnClickListener { requestCode() }
        requestCode()
    }

    private fun showTokenActivationScreen() {
        stopPairingLoop()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.rgb(8, 15, 30))
        }

        root.addView(TextView(this).apply {
            text = "DisplayHub Player"
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
        }, linearMatchWrap())

        root.addView(TextView(this).apply {
            text = "Ativação avançada por token"
            setTextColor(Color.LTGRAY)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 24)
        }, linearMatchWrap())

        val tokenInput = EditText(this).apply {
            hint = "Token de instalação"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        root.addView(tokenInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 12
        })

        val labelInput = EditText(this).apply {
            hint = "Nome do aparelho (ex.: TV Recepção)"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setText(prefs.deviceLabel.orEmpty())
        }
        root.addView(labelInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 20
        })

        val feedback = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 10)
        }

        val activate = Button(this)
        activate.text = "Ativar Player"
        activate.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            if (token.length < 32) {
                feedback.text = "Token inválido. Gere um novo token no DisplayHub."
                return@setOnClickListener
            }
            activate.isEnabled = false
            feedback.text = "Ativando..."
            Executors.newSingleThreadExecutor().execute {
                try {
                    val metrics = resources.displayMetrics
                    val label = labelInput.text.toString().trim().ifBlank {
                        "Android ${Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).takeLast(6)}"
                    }
                    api.activateInstallation(
                        token = token,
                        deviceId = prefs.deviceId,
                        deviceSecret = prefs.deviceSecret,
                        deviceLabel = label,
                        width = metrics.widthPixels,
                        height = metrics.heightPixels,
                    )
                    prefs.deviceLabel = label
                    prefs.activated = true
                    mainHandler.post {
                        showPlayerWaiting()
                        startManagedLoop()
                    }
                } catch (error: Throwable) {
                    mainHandler.post {
                        activate.isEnabled = true
                        feedback.text = error.message ?: "Falha ao ativar o player."
                    }
                }
            }
        }
        root.addView(activate, linearMatchWrap())

        root.addView(Button(this).apply {
            text = "Voltar para ativação por código"
            setOnClickListener { showActivationScreen() }
        }, linearMatchWrap())

        root.addView(feedback, linearMatchWrap())
        setContentView(root)
    }

    private fun startPairingLoop(pairingId: String, feedback: TextView) {
        stopPairingLoop()
        pairingExecutor = Executors.newSingleThreadScheduledExecutor().also { scheduler ->
            scheduler.scheduleWithFixedDelay({
                try {
                    val result = api.pollPairing(pairingId, prefs.deviceId, prefs.deviceSecret)
                    if (result.status == "claimed" && !result.playerUrl.isNullOrBlank()) {
                        prefs.activated = true
                        prefs.playerUrl = result.playerUrl
                        stopPairingLoop()
                        mainHandler.post {
                            showWebPlayer(result.playerUrl)
                            startManagedLoop()
                        }
                    } else if (result.status == "expired") {
                        stopPairingLoop()
                        mainHandler.post {
                            feedback.text = "Código expirado. Gere um novo código."
                        }
                    }
                } catch (_: Throwable) {
                    mainHandler.post {
                        feedback.text = "Sem conexão. Tentando novamente automaticamente..."
                    }
                }
            }, 0, 2, TimeUnit.SECONDS)
        }
    }

    private fun stopPairingLoop() {
        pairingExecutor?.shutdownNow()
        pairingExecutor = null
    }

    private fun showPlayerWaiting() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(42, 42, 42, 42)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "DisplayHub Player"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        }, linearMatchWrap())

        statusView = TextView(this).apply {
            text = "Conectando ao DisplayHub..."
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 0)
        }
        root.addView(statusView, linearMatchWrap())
        setContentView(root)
    }

    private fun showWebPlayer(url: String) {
        if (currentUrl == url && webView != null) return
        currentUrl = url
        prefs.playerUrl = url

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
            addJavascriptInterface(DisplayHubVideoBridge(videoCache), "DisplayHubAndroid")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                    return videoCache.intercept(request) ?: super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectLocalVideoPlayback(view)
                }
            }
            loadUrl(url)
        }
        webView = view
        setContentView(view)
        if (prefs.kioskEnabled) enterImmersiveMode()
    }

    private fun injectLocalVideoPlayback(view: WebView?) {
        val script = """
            (() => {
              if (window.__displayHubLocalVideoReady) return;
              window.__displayHubLocalVideoReady = true;
              const localize = (video) => {
                if (!video || video.dataset.dhLocalizing === 'true') return;
                const src = video.dataset.dhRemoteSrc || video.currentSrc || video.src || '';
                if (!/^https?:\/\//i.test(src) || src.includes('displayhub.local')) return;
                try {
                  const local = window.DisplayHubAndroid && window.DisplayHubAndroid.localizeVideo(src);
                  if (!local || local === src) return;
                  const time = Number.isFinite(video.currentTime) ? video.currentTime : 0;
                  const resume = !video.paused;
                  video.dataset.dhLocalizing = 'true';
                  video.dataset.dhRemoteSrc = src;
                  video.src = local;
                  video.load();
                  video.addEventListener('loadedmetadata', () => {
                    if (time > 0 && Number.isFinite(video.duration)) video.currentTime = Math.min(time, Math.max(0, video.duration - 0.05));
                    if (resume) video.play().catch(() => {});
                    video.dataset.dhLocalizing = 'false';
                  }, { once: true });
                } catch (_) {
                  video.dataset.dhLocalizing = 'false';
                }
              };
              const scan = (root) => {
                if (!root) return;
                if (root.matches && root.matches('video')) localize(root);
                if (root.querySelectorAll) root.querySelectorAll('video').forEach(localize);
              };
              scan(document);
              new MutationObserver((mutations) => {
                mutations.forEach((mutation) => {
                  mutation.addedNodes.forEach(scan);
                  if (mutation.type === 'attributes' && mutation.target instanceof HTMLVideoElement) localize(mutation.target);
                });
              }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private fun startManagedLoop() {
        if (executor?.isShutdown == false) return
        executor = Executors.newSingleThreadScheduledExecutor().also { scheduler ->
            scheduler.scheduleWithFixedDelay({ managedTick() }, 0, 5, TimeUnit.SECONDS)
        }
    }

    private fun stopManagedLoop() {
        executor?.shutdownNow()
        executor = null
    }

    private fun managedTick() {
        try {
            val assignment = api.pollAssignment(prefs.deviceId, prefs.deviceSecret)
            if (assignment.status == "assigned" && !assignment.playerUrl.isNullOrBlank()) {
                mainHandler.post { showWebPlayer(assignment.playerUrl) }
            } else {
                mainHandler.post {
                    if (webView == null) statusView?.text = "Aguardando playlist ser associada no DisplayHub..."
                }
            }
        } catch (_: Throwable) {
            mainHandler.post {
                if (webView == null) statusView?.text = "Sem conexão. Tentando novamente automaticamente..."
            }
        }

        try {
            val command = api.pollCommand(prefs.deviceId, prefs.deviceSecret) ?: return
            executeRemoteCommand(command)
        } catch (_: Throwable) {
        }
    }

    private fun executeRemoteCommand(command: DisplayHubApi.RemoteCommand) {
        when (command.command) {
            "reload_displays" -> mainHandler.post {
                webView?.reload()
                completeCommand(command, true, "android:webview_reloaded")
            }
            "restart_player" -> mainHandler.post {
                completeCommand(command, true, "android:activity_restarted")
                val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finish()
            }
            "enter_kiosk" -> mainHandler.post {
                prefs.kioskEnabled = true
                val locked = tryStartLockTask()
                enterImmersiveMode()
                completeCommand(command, true, if (locked) "android:lock_task" else "android:immersive")
            }
            "exit_kiosk" -> mainHandler.post {
                prefs.kioskEnabled = false
                tryStopLockTask()
                exitImmersiveMode()
                completeCommand(command, true, "android:kiosk_exited")
            }
            "reboot_device" -> mainHandler.post {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (dpm.isDeviceOwnerApp(packageName)) {
                    val admin = ComponentName(this, PlayerDeviceAdminReceiver::class.java)
                    completeCommand(command, true, "android:device_owner_reboot")
                    dpm.reboot(admin)
                } else {
                    completeCommand(command, false, "android:reboot_requires_device_owner")
                }
            }
            else -> completeCommand(command, false, "android:unsupported_command:${command.command}")
        }
    }

    private fun completeCommand(command: DisplayHubApi.RemoteCommand, success: Boolean, result: String) {
        Executors.newSingleThreadExecutor().execute {
            try {
                api.completeCommand(prefs.deviceId, prefs.deviceSecret, command.id, success, result)
            } catch (_: Throwable) {
            }
        }
    }

    private fun tryStartLockTask(): Boolean {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun tryStopLockTask() {
        try {
            stopLockTask()
        } catch (_: Throwable) {
        }
    }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun exitImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun linearMatchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
