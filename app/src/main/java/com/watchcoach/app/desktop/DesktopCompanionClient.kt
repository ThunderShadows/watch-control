package com.watchcoach.app.desktop

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopCompanionClient {

    suspend fun ping(host: String, port: Int, token: String): Result<String> = post(
        host, port, token, JSONObject().put("type", "ping"),
    )

    suspend fun key(host: String, port: Int, token: String, key: String): Result<String> = post(
        host, port, token, JSONObject().put("type", "key").put("key", key),
    )

    suspend fun click(host: String, port: Int, token: String, button: String = "left"): Result<String> = post(
        host, port, token, JSONObject().put("type", "click").put("button", button),
    )

    suspend fun mouse(host: String, port: Int, token: String, dx: Int, dy: Int): Result<String> = post(
        host, port, token, JSONObject().put("type", "mouse").put("dx", dx).put("dy", dy),
    )

    private suspend fun post(host: String, port: Int, token: String, body: JSONObject): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(host.isNotBlank()) { "Set the laptop IP in Gesture lab first." }
                val url = URL("http://${host.trim()}:$port/event")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3_000
                    readTimeout = 3_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body.toString()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                conn.disconnect()
                check(code in 200..299) { "Companion HTTP $code $text" }
                text.ifBlank { "ok" }
            }
        }
}
