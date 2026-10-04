package br.com.displayhub.player

import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable description of one publishable program version.
 *
 * A ProgramManifest is intentionally not wired into playback yet. It is the contract used by
 * the reliability layer to stage, validate and later atomically activate a complete program.
 */
data class ProgramManifest(
    val schemaVersion: Int,
    val programVersion: Long,
    val displayId: String,
    val generatedAt: String,
    val assets: List<AssetManifest>,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(programVersion > 0) { "programVersion must be positive" }
        require(displayId.isNotBlank()) { "displayId must not be blank" }
        require(generatedAt.isNotBlank()) { "generatedAt must not be blank" }
        require(assets.map { it.id }.distinct().size == assets.size) { "asset ids must be unique" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("programVersion", programVersion)
        .put("displayId", displayId)
        .put("generatedAt", generatedAt)
        .put("assets", JSONArray().apply { assets.forEach { put(it.toJson()) } })

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun fromJson(json: JSONObject): ProgramManifest {
            val assetsJson = json.optJSONArray("assets") ?: JSONArray()
            val assets = buildList {
                for (index in 0 until assetsJson.length()) {
                    add(AssetManifest.fromJson(assetsJson.getJSONObject(index)))
                }
            }
            return ProgramManifest(
                schemaVersion = json.getInt("schemaVersion"),
                programVersion = json.getLong("programVersion"),
                displayId = json.getString("displayId").trim(),
                generatedAt = json.getString("generatedAt").trim(),
                assets = assets,
            )
        }
    }
}

data class AssetManifest(
    val id: String,
    val type: AssetType,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val version: String,
) {
    init {
        require(id.isNotBlank()) { "asset id must not be blank" }
        require(url.startsWith("https://")) { "asset url must use https" }
        require(SHA256_REGEX.matches(sha256.lowercase())) { "asset sha256 must contain 64 hex characters" }
        require(sizeBytes >= 0) { "asset sizeBytes must not be negative" }
        require(version.isNotBlank()) { "asset version must not be blank" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.wireValue)
        .put("url", url)
        .put("sha256", sha256.lowercase())
        .put("sizeBytes", sizeBytes)
        .put("version", version)

    companion object {
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

        fun fromJson(json: JSONObject): AssetManifest = AssetManifest(
            id = json.getString("id").trim(),
            type = AssetType.fromWireValue(json.getString("type")),
            url = json.getString("url").trim(),
            sha256 = json.getString("sha256").trim().lowercase(),
            sizeBytes = json.getLong("sizeBytes"),
            version = json.getString("version").trim(),
        )
    }
}

enum class AssetType(val wireValue: String) {
    VIDEO("video"),
    IMAGE("image"),
    FONT("font"),
    JSON("json"),
    THUMBNAIL("thumbnail"),
    LAYOUT("layout"),
    SMART_CONTENT("smart_content");

    companion object {
        fun fromWireValue(value: String): AssetType = entries.firstOrNull {
            it.wireValue == value.trim().lowercase()
        } ?: throw IllegalArgumentException("unsupported asset type: $value")
    }
}

enum class AssetStatus {
    MISSING,
    DOWNLOADING,
    READY,
    FAILED,
    INVALID,
}
