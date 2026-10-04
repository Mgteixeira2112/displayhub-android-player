package br.com.displayhub.player

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Reports bounded native runtime state for registered Android players.
 *
 * Telemetry is best-effort: failures never affect assignment polling or playback. The snapshot is
 * deliberately read-only and lightweight: final cache files only appear after integrity-checked
 * commit, so the reporter checks committed file presence/size instead of re-hashing every asset on
 * each interval. No player URL, device secret or media URL is included.
 */
class RuntimeTelemetryReporter(context: Context) {
    companion object {
        private const val TAG = "DisplayHubTelemetry"
        private const val INITIAL_DELAY_SECONDS = 15L
        private const val REPORT_INTERVAL_SECONDS = 30L
    }

    private val appContext = context.applicationContext
    private val prefs = PlayerPrefs(appContext)
    private val api = DisplayHubApi()
    private val programDirectory = File(appContext.filesDir, "displayhub-programs")
    private val assetDirectory = File(appContext.filesDir, "displayhub-assets")
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

        val active = readManifest("active.json")
        val staging = readManifest("staging.json")
        val previous = readManifest("previous.json")
        val activeCache = cacheSummary(active)
        val stagingCache = cacheSummary(staging)

        val programSync = JSONObject()
            .put("activeVersion", active?.programVersion)
            .put("activeDisplayId", active?.displayId)
            .put("stagingVersion", staging?.programVersion)
            .put("stagingDisplayId", staging?.displayId)
            .put("previousVersion", previous?.programVersion)
            .put(
                "staging",
                JSONObject()
                    .put("present", staging != null)
                    .put("programVersion", staging?.programVersion)
                    .put("displayId", staging?.displayId)
                    .put("activatable", staging != null && stagingCache.optInt("missing", 0) == 0)
                    .put("cache", stagingCache),
            )

        val activeRuntime = JSONObject()
            .put("present", active != null)
            .put("ready", active != null && activeCache.optInt("missing", 0) == 0)
            .put("programVersion", active?.programVersion)
            .put("displayId", active?.displayId)
            .put("cache", activeCache)

        val payload = JSONObject()
            .put("schemaVersion", 1)
            .put("programSync", programSync)
            .put("activeRuntime", activeRuntime)

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

    private fun readManifest(name: String): ProgramManifest? {
        val file = File(programDirectory, name)
        if (!file.exists() || !file.isFile || file.length() <= 0L) return null
        return runCatching {
            ProgramManifest.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        }.getOrNull()
    }

    private fun cacheSummary(manifest: ProgramManifest?): JSONObject {
        if (manifest == null) {
            return JSONObject()
                .put("total", 0)
                .put("ready", 0)
                .put("missing", 0)
                .put("readyBytes", 0L)
        }

        var ready = 0
        var missing = 0
        var readyBytes = 0L

        manifest.assets.forEach { asset ->
            val file = File(assetDirectory, asset.sha256.lowercase())
            val committed = file.exists() && file.isFile &&
                (asset.sizeBytes <= 0L || file.length() == asset.sizeBytes)
            if (committed) {
                ready += 1
                readyBytes += file.length()
            } else {
                missing += 1
            }
        }

        return JSONObject()
            .put("programVersion", manifest.programVersion)
            .put("total", manifest.assets.size)
            .put("ready", ready)
            .put("missing", missing)
            .put("readyBytes", readyBytes)
    }
}
