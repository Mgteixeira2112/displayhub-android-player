package br.com.displayhub.player

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class DisplayHubApi {
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    data class Assignment(val status: String, val playerUrl: String?)
    data class RemoteCommand(val id: String, val command: String)

    fun activateInstallation(
        token: String,
        deviceId: String,
        deviceSecret: String,
        deviceLabel: String,
        width: Int,
        height: Int,
    ): JSONObject = rpc(
        "activate_device_installation",
        JSONObject()
            .put("p_token", token.trim())
            .put("p_device_id", deviceId)
            .put("p_device_secret", deviceSecret)
            .put("p_device_label", deviceLabel.trim().ifBlank { "DisplayHub Android" })
            .put("p_platform", "android")
            .put("p_screen_width", width)
            .put("p_screen_height", height),
    )

    fun pollAssignment(deviceId: String, deviceSecret: String): Assignment {
        val result = rpc(
            "poll_registered_device_assignment",
            JSONObject()
                .put("p_device_id", deviceId)
                .put("p_device_secret", deviceSecret),
        )
        return Assignment(
            status = result.optString("status", "waiting"),
            playerUrl = result.optString("player_url").takeIf { it.isNotBlank() },
        )
    }

    fun pollCommand(deviceId: String, deviceSecret: String): RemoteCommand? {
        val result = rpcNullable(
            "poll_windows_player_command",
            JSONObject()
                .put("p_device_id", deviceId)
                .put("p_device_secret", deviceSecret),
        ) ?: return null
        val id = result.optString("id")
        val command = result.optString("command")
        if (id.isBlank() || command.isBlank()) return null
        return RemoteCommand(id, command)
    }

    fun completeCommand(
        deviceId: String,
        deviceSecret: String,
        commandId: String,
        success: Boolean,
        result: String,
    ) {
        rpcRaw(
            "complete_windows_player_command",
            JSONObject()
                .put("p_device_id", deviceId)
                .put("p_device_secret", deviceSecret)
                .put("p_command_id", commandId)
                .put("p_success", success)
                .put("p_result", result.take(500)),
        )
    }

    private fun rpc(name: String, body: JSONObject): JSONObject {
        val text = rpcRaw(name, body)
        return JSONObject(text)
    }

    private fun rpcNullable(name: String, body: JSONObject): JSONObject? {
        val text = rpcRaw(name, body).trim()
        if (text.isEmpty() || text == "null") return null
        return JSONObject(text)
    }

    private fun rpcRaw(name: String, body: JSONObject): String {
        check(anonKey.isNotBlank()) { "DISPLAYHUB_SUPABASE_ANON_KEY não configurada" }
        val connection = URL("$baseUrl/rest/v1/rpc/$name").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("apikey", anonKey)
        connection.setRequestProperty("Authorization", "Bearer $anonKey")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = BufferedReader(InputStreamReader(stream ?: return "")).use { it.readText() }
        connection.disconnect()
        if (status !in 200..299) throw IllegalStateException("HTTP $status: $response")
        return response
    }
}
