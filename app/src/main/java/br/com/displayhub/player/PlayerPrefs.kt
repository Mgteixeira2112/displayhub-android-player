package br.com.displayhub.player

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

class PlayerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("displayhub_player", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    val deviceSecret: String
        get() = prefs.getString("device_secret", null) ?: randomHex(32).also {
            prefs.edit().putString("device_secret", it).apply()
        }

    var activated: Boolean
        get() = prefs.getBoolean("activated", false)
        set(value) = prefs.edit().putBoolean("activated", value).apply()

    var playerUrl: String?
        get() = prefs.getString("player_url", null)
        set(value) = prefs.edit().putString("player_url", value).apply()

    var deviceLabel: String?
        get() = prefs.getString("device_label", null)
        set(value) = prefs.edit().putString("device_label", value).apply()

    var kioskEnabled: Boolean
        get() = prefs.getBoolean("kiosk_enabled", true)
        set(value) = prefs.edit().putBoolean("kiosk_enabled", value).apply()

    private fun randomHex(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return data.joinToString("") { "%02x".format(it) }
    }
}
