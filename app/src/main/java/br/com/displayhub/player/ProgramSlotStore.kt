package br.com.displayhub.player

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Persists the last known good program and safely stages replacements.
 *
 * Program assets remain content-addressed in [UniversalAssetCache]. Only small manifest files are
 * switched here. The active manifest is replaced only after every staging asset is READY.
 *
 * This class is intentionally not wired into the renderer yet. A4 will consume the ACTIVE slot
 * during offline boot.
 */
class ProgramSlotStore(
    context: Context,
    private val cache: UniversalAssetCache,
) {
    companion object {
        private const val TAG = "DisplayHubProgramSlots"
        private const val ACTIVE_FILE = "active.json"
        private const val STAGING_FILE = "staging.json"
        private const val PREVIOUS_FILE = "previous.json"
        private const val TEMP_SUFFIX = ".tmp"
    }

    private val directory = File(context.filesDir, "displayhub-programs").apply { mkdirs() }

    init {
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) }
            ?.forEach { it.delete() }
    }

    fun active(): ProgramManifest? = readSlot(ACTIVE_FILE)

    fun staging(): ProgramManifest? = readSlot(STAGING_FILE)

    fun previous(): ProgramManifest? = readSlot(PREVIOUS_FILE)

    @Synchronized
    fun stage(manifest: ProgramManifest) {
        require(manifest.schemaVersion == ProgramManifest.CURRENT_SCHEMA_VERSION) {
            "unsupported program schema ${manifest.schemaVersion}"
        }

        val active = active()
        if (active != null) {
            require(active.displayId == manifest.displayId) {
                "staging displayId must match active displayId"
            }
        }

        writeSlot(STAGING_FILE, manifest)
        cache.prefetch(manifest)
        Log.i(TAG, "program_staged version=${manifest.programVersion} assets=${manifest.assets.size}")
    }

    fun stagingStatus(): JSONObject {
        val manifest = staging()
            ?: return JSONObject()
                .put("present", false)
                .put("activatable", false)

        val cacheSnapshot = cache.snapshot(manifest)
        return JSONObject()
            .put("present", true)
            .put("programVersion", manifest.programVersion)
            .put("displayId", manifest.displayId)
            .put("activatable", allAssetsReady(manifest))
            .put("cache", cacheSnapshot)
    }

    /**
     * Promotes STAGING to ACTIVE only when every declared asset has passed integrity validation.
     *
     * The old ACTIVE manifest is first written to PREVIOUS, then ACTIVE is atomically replaced.
     * If the process dies before the final ACTIVE replacement, the known-good ACTIVE remains.
     */
    @Synchronized
    fun activateIfReady(): Boolean {
        val candidate = staging() ?: return false
        if (!allAssetsReady(candidate)) return false

        val current = active()
        if (current != null) {
            writeSlot(PREVIOUS_FILE, current)
        } else {
            slotFile(PREVIOUS_FILE).delete()
        }

        writeSlot(ACTIVE_FILE, candidate)
        slotFile(STAGING_FILE).delete()
        pinAndTrim(candidate)

        Log.i(TAG, "program_activated version=${candidate.programVersion}")
        return true
    }

    /**
     * Restores PREVIOUS as ACTIVE when all of its assets are still valid in the cache.
     *
     * The manifest that was ACTIVE before rollback becomes the new PREVIOUS, enabling a safe
     * forward/back swap without deleting either program's metadata.
     */
    @Synchronized
    fun rollback(): Boolean {
        val rollbackTarget = previous() ?: return false
        if (!allAssetsReady(rollbackTarget)) return false

        val current = active()
        if (current != null) {
            writeSlot(PREVIOUS_FILE, current)
        } else {
            slotFile(PREVIOUS_FILE).delete()
        }

        writeSlot(ACTIVE_FILE, rollbackTarget)
        pinAndTrim(rollbackTarget)

        Log.w(TAG, "program_rollback version=${rollbackTarget.programVersion}")
        return true
    }

    fun pinnedSha256(): Set<String> = active()
        ?.assets
        ?.mapTo(linkedSetOf()) { it.sha256.lowercase() }
        .orEmpty()

    private fun allAssetsReady(manifest: ProgramManifest): Boolean = manifest.assets.all {
        cache.status(it) == AssetStatus.READY
    }

    private fun pinAndTrim(manifest: ProgramManifest) {
        val pinned = manifest.assets.mapTo(linkedSetOf()) { it.sha256.lowercase() }
        cache.trim(pinned)
    }

    private fun readSlot(name: String): ProgramManifest? {
        val file = slotFile(name)
        if (!file.exists() || !file.isFile || file.length() <= 0L) return null

        return runCatching {
            ProgramManifest.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        }.onFailure { error ->
            Log.e(TAG, "program_slot_invalid slot=$name", error)
        }.getOrNull()
    }

    private fun writeSlot(name: String, manifest: ProgramManifest) {
        val target = slotFile(name)
        val temp = File(directory, "$name$TEMP_SUFFIX")
        val bytes = manifest.toJson().toString().toByteArray(Charsets.UTF_8)

        temp.delete()
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }

        // Parse the exact bytes we are about to publish before replacing the slot pointer.
        ProgramManifest.fromJson(JSONObject(temp.readText(Charsets.UTF_8)))

        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }

        // Re-read after commit so a partial/corrupt replacement can never be silently accepted.
        val committed = readSlot(name)
            ?: throw IllegalStateException("program_slot_commit_failed:$name")
        check(committed.programVersion == manifest.programVersion) {
            "program slot version mismatch after commit"
        }
    }

    private fun slotFile(name: String): File = File(directory, name)
}
