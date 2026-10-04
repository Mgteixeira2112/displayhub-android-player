package br.com.displayhub.player

import android.content.Context
import org.json.JSONObject

/**
 * Drives ProgramManifest updates through the safe STAGING -> ACTIVE pipeline.
 *
 * Transport is intentionally outside this class. A caller may obtain the manifest from the
 * DisplayHub backend and hand it to [offer]. The synchronizer never replaces a known-good ACTIVE
 * until ProgramSlotStore confirms every declared asset is READY and integrity-checked.
 */
class ProgramSynchronizer(context: Context) {
    private val cache = UniversalAssetCache(context)
    private val slots = ProgramSlotStore(context, cache)

    enum class Result {
        ALREADY_ACTIVE,
        ACTIVE_NEWER,
        DISPLAY_MISMATCH,
        STAGED,
        DOWNLOADING,
        ACTIVATED,
    }

    @Synchronized
    fun offer(manifest: ProgramManifest): Result {
        require(manifest.schemaVersion == ProgramManifest.CURRENT_SCHEMA_VERSION) {
            "unsupported program schema ${manifest.schemaVersion}"
        }

        val active = slots.active()
        if (active != null) {
            if (active.displayId != manifest.displayId) return Result.DISPLAY_MISMATCH
            if (active.programVersion == manifest.programVersion) return Result.ALREADY_ACTIVE
            if (active.programVersion > manifest.programVersion) return Result.ACTIVE_NEWER
        }

        val staging = slots.staging()
        if (staging == null || staging.displayId != manifest.displayId || staging.programVersion != manifest.programVersion) {
            slots.stage(manifest)
        } else {
            cache.prefetch(staging)
        }

        return if (slots.activateIfReady()) Result.ACTIVATED else Result.STAGED
    }

    @Synchronized
    fun tick(): Result? {
        val staging = slots.staging() ?: return null
        if (slots.activateIfReady()) return Result.ACTIVATED

        cache.prefetch(staging)
        val status = slots.stagingStatus()
        return if (status.optJSONObject("cache")?.optInt("downloading", 0) ?: 0 > 0) {
            Result.DOWNLOADING
        } else {
            Result.STAGED
        }
    }

    fun status(): JSONObject {
        val active = slots.active()
        val staging = slots.staging()
        val previous = slots.previous()
        return JSONObject()
            .put("activeVersion", active?.programVersion)
            .put("activeDisplayId", active?.displayId)
            .put("stagingVersion", staging?.programVersion)
            .put("stagingDisplayId", staging?.displayId)
            .put("previousVersion", previous?.programVersion)
            .put("staging", slots.stagingStatus())
    }
}
