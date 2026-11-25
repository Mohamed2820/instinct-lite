package dev.momolite

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Lets the UI abort an in-flight request. */
class CancelHandle {
    @Volatile var cancelled = false
        private set
    @Volatile private var action: (() -> Unit)? = null

    fun attach(onCancel: () -> Unit) { action = onCancel; if (cancelled) onCancel() }
    fun cancel() { cancelled = true; action?.invoke() }
}

class HttpResult(val code: Int, val body: String)

/** Network seam: the real one uses HttpURLConnection, tests use a fake. */
interface Transport {
    @Throws(IOException::class)
    fun post(spec: HttpRequestSpec, connectMs: Int, readMs: Int, cancel: CancelHandle): HttpResult
}

object UrlConnectionTransport : Transport {
    private const val MAX_BODY = 1_000_000

    override fun post(spec: HttpRequestSpec, connectMs: Int, readMs: Int, cancel: CancelHandle): HttpResult {
        val conn = URL(spec.url).openConnection() as HttpURLConnection
        cancel.attach { Thread { try { conn.disconnect() } catch (_: Exception) {} }.start() }
        try {
            conn.requestMethod = "POST"
            // Never follow redirects: the Authorization header must only go where the user pointed it.
            conn.instanceFollowRedirects = false
            spec.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.doOutput = true
            conn.outputStream.use { it.write(spec.body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { s -> s.readBytes().let { if (it.size > MAX_BODY) ByteArray(0) else it } }
                ?.toString(Charsets.UTF_8).orEmpty()
            return HttpResult(code, text)
        } finally {
            conn.disconnect()
        }
    }
}

/** Reads the reply text out of a provider response without ever echoing anything else. */
object ResponseParser {
    fun parse(type: ProviderType, body: String): ProviderResult {
        val text: String = try {
            val root = JSONObject(body)
            when (type) {
                ProviderType.CLOUDFLARE -> root.optJSONObject("result")?.optString("response", "") ?: return bad()
                else -> {
                    val choices = root.optJSONArray("choices") ?: return bad()
                    val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: return bad()
                    msg.optString("content", "")
                }
            }
        } catch (e: Exception) { return bad() }
        return if (text.isBlank() || text == "null") ProviderResult.Failed(ProviderError.EMPTY) else ProviderResult.Reply(text.trim())
    }

    private fun bad() = ProviderResult.Failed(ProviderError.BAD_RESPONSE)
}

/** Runs one provider call and classifies the outcome. Blocking: call it off the main thread. */
class ProviderClient(private val transport: Transport = UrlConnectionTransport) {
    fun send(config: ProviderConfig, userText: String, cancel: CancelHandle = CancelHandle()): ProviderResult {
        if (ProviderChoice.missing(config).isNotEmpty() || userText.isBlank()) return ProviderResult.Failed(ProviderError.BAD_CONFIG)
        if (cancel.cancelled) return ProviderResult.Failed(ProviderError.CANCELLED)
        val spec = RequestBuilder.build(config, userText)
        val res = try {
            transport.post(spec, CONNECT_MS, READ_MS, cancel)
        } catch (e: SocketTimeoutException) {
            return ProviderResult.Failed(if (cancel.cancelled) ProviderError.CANCELLED else ProviderError.TIMEOUT)
        } catch (e: Exception) {
            return ProviderResult.Failed(if (cancel.cancelled) ProviderError.CANCELLED else ProviderError.NETWORK)
        }
        if (cancel.cancelled) return ProviderResult.Failed(ProviderError.CANCELLED)
        if (res.code !in 200..299) return ProviderResult.Failed(ProviderErrors.fromHttp(res.code, res.body))
        return ResponseParser.parse(config.type, res.body)
    }

    companion object {
        const val CONNECT_MS = 15_000
        const val READ_MS = 45_000
        const val TEST_PROMPT = "ping"
    }
}
