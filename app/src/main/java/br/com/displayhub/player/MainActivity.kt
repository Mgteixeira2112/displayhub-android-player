package br.com.displayhub.player

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
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
    private val api = DisplayHubApi()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor: ScheduledExecutorService? = null
    private var webView: WebView? = null
    private var statusView: TextView? = null
    private var currentUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PlayerPrefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (prefs.kioskEnabled) enterImmersiveMode()

        if (prefs.activated) showPlayerWaiting()
        else showActivationScreen()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.kioskEnabled) enterImmersiveMode()
        if (prefs.activated) startManagedLoop()
    }

    override fun onPause() {
        super.onPause()
        stopManagedLoop()
    }

    override fun onDestroy() {
        stopManagedLoop()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun showActivationScreen() {
        stopManagedLoop()
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
            text = "Informe o token de instalação gerado no DisplayHub"
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

        val activate = Button(this).apply {
            text = "Ativar Player"
            setOnClickListener {
                val token = tokenInput.text.toString().trim()
                if (token.length < 32) {
                    feedback.text = "Token inválido. Gere um novo token no DisplayHub."
                    return@setOnClickListener
                }
                isEnabled = false
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
        }
        root.addView(activate, linearMatchWrap())
        root.addView(feedback, linearMatchWrap())
        setContentView(root)
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
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            loadUrl(url)
        }
        webView = view
        setContentView(view)
        if (prefs.kioskEnabled) enterImmersiveMode()
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
        } catch (error: Throwable) {
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
                    completeCommand(command, true, "android:device_owner_reboot")
                    dpm.reboot(null)
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
