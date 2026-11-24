package dev.momolite

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloudflare Workers AI provider. Pluggable but inert until the user enters
 * their own credentials in Settings AND turns the provider on; MainActivity
 * only selects it then. Sends the current message text only — no history,
 * no files, no metadata beyond the required Authorization header.
 * The token is used for the request header only: never logged, never stored
 * here, and never copied into any error message or reply bubble.
 */
class CloudflareWorkersAi(private val accountId: String, private val token: String) : ChatProvider {
    override fun replyDetailed(history: List<Msg>, onReply: (ProviderResult) -> Unit) {
        val text = history.lastOrNull { it.role == "user" }?.text
        if (text.isNullOrBlank()) { onReply(ProviderResult.Failed(ProviderError.BAD_RESPONSE)); return }
        Thread {
            onReply(try { post(text) } catch (e: Exception) { ProviderResult.Failed(ProviderError.NETWORK) })
        }.start()
    }

    override fun reply(history: List<Msg>, onReply: (String?) -> Unit) =
        replyDetailed(history) { r -> onReply(if (r is ProviderResult.Reply) r.text else null) }

    private fun post(userText: String): ProviderResult {
        val conn = URL(CloudflareRequest.endpoint(accountId)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.doOutput = true
            conn.outputStream.use { it.write(CloudflareRequest.payload(userText).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) return ProviderResult.Failed(ProviderErrors.fromHttpCode(code))
            val body = conn.inputStream.bufferedReader().readText()
            val result = try { JSONObject(body).optJSONObject("result") } catch (e: Exception) { null }
                ?: return ProviderResult.Failed(ProviderError.BAD_RESPONSE)
            val reply = result.optString("response")
            return if (reply.isBlank()) ProviderResult.Failed(ProviderError.BAD_RESPONSE) else ProviderResult.Reply(reply)
        } finally {
            conn.disconnect()
        }
    }
}
