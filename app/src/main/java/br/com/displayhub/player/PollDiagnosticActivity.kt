package br.com.displayhub.player

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

/**
 * Manual, read-only diagnostic for the native assignment polling path.
 * It never clears or rewrites pairing data, playerUrl, cache or kiosk settings.
 */
class PollDiagnosticActivity : AppCompatActivity() {
    private lateinit var prefs: PlayerPrefs
    private val api = DisplayHubApi()
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PlayerPrefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.BLACK)
        }

        root.addView(TextView(this).apply {
            text = "DisplayHub Diagnóstico"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }, matchWrap())

        root.addView(TextView(this).apply {
            val deviceSuffix = prefs.deviceId.takeLast(6)
            text = "Versão ${BuildConfig.VERSION_NAME}\nAtivado: ${if (prefs.activated) "sim" else "não"}\nURL salva: ${if (prefs.playerUrl.isNullOrBlank()) "não" else "sim"}\nDispositivo: …$deviceSuffix"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, 18, 0, 24)
        }, matchWrap())

        status = TextView(this).apply {
            text = "Nenhum teste executado."
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(12, 20, 12, 20)
        }
        root.addView(status, matchWrap())

        runButton = Button(this).apply {
            text = "Testar polling agora"
            setOnClickListener { runPollTest() }
        }
        root.addView(runButton, matchWrap())

        root.addView(Button(this).apply {
            text = "Abrir player normal"
            setOnClickListener {
                startActivity(Intent(this@PollDiagnosticActivity, MainActivity::class.java))
                finish()
            }
        }, matchWrap())

        root.addView(TextView(this).apply {
            text = "O teste usa as credenciais já armazenadas. Nenhum token ou segredo é exibido. Se der erro, fotografe apenas esta tela."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, 22, 0, 0)
        }, matchWrap())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
    }

    private fun runPollTest() {
        runButton.isEnabled = false
        status.text = "Consultando o servidor…"
        executor.execute {
            try {
                val assignment = api.pollAssignment(prefs.deviceId, prefs.deviceSecret)
                val safeResult = when (assignment.status) {
                    "assigned" -> "SUCESSO\nstatus=assigned\nURL recebida: ${if (assignment.playerUrl.isNullOrBlank()) "não" else "sim"}"
                    else -> "SUCESSO\nstatus=${assignment.status.take(40)}\nURL recebida: ${if (assignment.playerUrl.isNullOrBlank()) "não" else "sim"}"
                }
                runOnUiThread {
                    status.text = safeResult
                    runButton.isEnabled = true
                }
            } catch (error: Throwable) {
                val safeResult = safeFailure(error)
                runOnUiThread {
                    status.text = "FALHA\n$safeResult"
                    runButton.isEnabled = true
                }
            }
        }
    }

    private fun safeFailure(error: Throwable): String = when (error) {
        is UnknownHostException -> "categoria=DNS"
        is SocketTimeoutException -> "categoria=TIMEOUT"
        is SSLException -> "categoria=TLS"
        is ConnectException -> "categoria=CONEXAO"
        is JSONException -> "categoria=JSON"
        is SecurityException -> "categoria=PERMISSAO"
        is IllegalStateException -> {
            val code = Regex("^HTTP (\\d{3})").find(error.message.orEmpty())?.groupValues?.get(1)
            if (code != null) "categoria=HTTP\ncodigo=$code" else "categoria=RESPOSTA_INVALIDA"
        }
        else -> "categoria=OUTRA\ntipo=${error.javaClass.simpleName.take(80)}"
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
