package br.com.displayhub.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AutoUpdater(private val activity: Activity) {
    private val executor = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val prefs = activity.getSharedPreferences("displayhub_updater", Activity.MODE_PRIVATE)

    init {
        scheduler.scheduleWithFixedDelay(
            { checkIfDue() },
            CHECK_INTERVAL_MS,
            CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
    )

    fun checkIfDue(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong("last_check", 0L)
        if (!force && now - lastCheck < CHECK_INTERVAL_MS) return
        prefs.edit().putLong("last_check", now).apply()

        executor.execute {
            try {
                val update = fetchUpdateInfo() ?: return@execute
                val installed = currentVersionCode()
                if (update.versionCode <= installed) return@execute
                val apk = downloadUpdate(update)
                activity.runOnUiThread { requestInstall(apk) }
            } catch (_: Throwable) {
            }
        }
    }

    private fun fetchUpdateInfo(): UpdateInfo? {
        val connection = URL(BuildConfig.UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.useCaches = false
        val status = connection.responseCode
        if (status !in 200..299) {
            connection.disconnect()
            return null
        }
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val json = JSONObject(text)
        val versionCode = json.optLong("versionCode", 0L)
        val versionName = json.optString("versionName", "nova versão")
        val apkUrl = json.optString("apkUrl", "")
        val sha256 = json.optString("sha256", "").lowercase()
        if (versionCode <= 0 || apkUrl.isBlank() || sha256.length != 64) return null
        return UpdateInfo(versionCode, versionName, apkUrl, sha256)
    }

    private fun currentVersionCode(): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun downloadUpdate(update: UpdateInfo): File {
        val dir = File(activity.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, "displayhub-player-${update.versionCode}.apk")
        if (target.exists() && sha256(target) == update.sha256) return target

        dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
        val temp = File(dir, "download.tmp")
        val connection = URL(update.apkUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        val status = connection.responseCode
        if (status !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("update_download_$status")
        }
        connection.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
        connection.disconnect()
        if (sha256(temp) != update.sha256) {
            temp.delete()
            throw IllegalStateException("update_checksum_mismatch")
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    private fun requestInstall(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            activity.startActivity(intent)
            prefs.edit().putLong("last_check", 0L).apply()
            return
        }

        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 15L * 60L * 1000L
    }
}
