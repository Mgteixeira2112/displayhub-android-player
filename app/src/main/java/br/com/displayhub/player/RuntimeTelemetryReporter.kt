package br.com.displayhub.player

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Reports bounded native runtime state for registered Android players.
 *
 * Telemetry is best-effort: failures never affect assignment polling or playback. The payload
 * intentionally contains only program/cache state and no player URL, device secret or media URL.
 */
class RuntimeTelemetryReporter(context: Context) {
    companion object {
        private const val TAG = "DisplayHubTelemetry"
        private const val INITIAL_DELAY_SECONDS = 15L
        private const val REPORT_INTERVAL_SECONDS = 30L
    }

    private val prefs = PlayerPrefs(context)
    private val api = DisplayHubApi()
    private val programSynchronizer = ProgramSynchronizer(context)
    private val activeProgramRuntime = ActiveProgramRuntime(context)
    private var executor: ScheduledExecutorService? = null

    @Synchronized
    fun start() {
        if (executor?.isShutdown == false) return
        executor = Executors.newSingleThreadScheduledExecutor().also { scheduler ->
            scheduler.scheduleWithFixedDelay(
                { reportOnce() },
                INITIAL_DELAY_SECONDS,
                REPORT_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
        }
    }

    @Synchronized
    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    private fun reportOnce() {
        if (!prefs.activated) return

        val payload = JSONObject()
            .put("schemaVersion", 1)
            .put("programSync", programSynchronizer.status())
            .put("activeRuntime", activeProgramRuntime.snapshot())

        runCatching {
            api.reportRuntime(
                deviceId = prefs.deviceId,
                deviceSecret = prefs.deviceSecret,
                appVersion = BuildConfig.VERSION_NAME,
                kioskMode = prefs.kioskEnabled,
                runtimeStatus = payload,
            )
        }.onFailure { error ->
            Log.d(TAG, "runtime_telemetry_failed:${error.javaClass.simpleName}")
        }
    }
}
