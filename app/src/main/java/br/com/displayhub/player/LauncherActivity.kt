package br.com.displayhub.player

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Uses the original app icon to expose diagnostics without relying on a second launcher icon. */
class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "DisplayHub Player"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Button(this).apply {
            text = "Abrir player normal"
            setOnClickListener {
                startActivity(Intent(this@LauncherActivity, MainActivity::class.java))
                finish()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Button(this).apply {
            text = "Abrir diagnóstico (sem cache)"
            setOnClickListener {
                startActivity(Intent(this@LauncherActivity, DiagnosticActivity::class.java))
                finish()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = "O diagnóstico usa a página já pareada. Pressione Voltar para encerrar o teste."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }
}
