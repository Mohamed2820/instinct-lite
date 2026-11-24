package dev.momolite

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloudflare Workers AI provider. Pluggable but inert until the user enters
 * their own credentials in Settings AND turns the provider on; MainActivity
 * only selects it then. Sends the current message text only — no history,
 * no files, no metadata beyond the required Authorization header.
 * The token is used for the request header only: never logged, never stored here.
 */
class CloudflareWorkersAi(private val accountId: String, private val token: String) : ChatProvider {
    override fun reply(history: List<Msg>, onReply: (String?) -> Unit) {
        val text = history.lastOrNull { it.role == "user" }?.text
        if (text.isNullOrBlank()) { onReply(null); return }
        Thread {
            onReply(try { post(text) } catch (e: Exception) { null })
        }.start()
    }

    private fun post(userText: String): String? {
        val conn = URL(CloudflareRequest.endpoint(accountId)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.doOutput = true
            conn.outputStream.use { it.write(CloudflareRequest.payload(userText).toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().readText()
            val result = JSONObject(body).optJSONObject("result") ?: return null
            return result.optString("response").ifBlank { null }
        } finally {
            conn.disconnect()
        }
    }
}
