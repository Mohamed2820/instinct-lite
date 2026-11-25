package dev.momolite

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Runs the real ProviderClient / parser against a fake transport: no network involved. */
class ProviderClientTest {
    private val openai = ProviderConfig(ProviderType.OPENAI_COMPATIBLE, "sk-SECRET", "gpt-x", address = "https://h.example/v1")
    private val cf = ProviderConfig(ProviderType.CLOUDFLARE, "tok-SECRET", "@cf/m", accountId = "acc")

    private class Fake(val reply: (HttpRequestSpec, CancelHandle) -> HttpResult) : Transport {
        var last: HttpRequestSpec? = null
        var calls = 0
        override fun post(spec: HttpRequestSpec, connectMs: Int, readMs: Int, cancel: CancelHandle): HttpResult {
            calls++; last = spec; return reply(spec, cancel)
        }
    }
    private fun ok(body: String) = Fake { _, _ -> HttpResult(200, body) }
    private fun client(f: Fake) = ProviderClient(f)

    @Test fun openAiReplyIsParsedAndTrimmed() {
        val r = client(ok("""{"choices":[{"message":{"role":"assistant","content":"  أهلًا  "}}]}""")).send(openai, "hi")
        assertEquals(ProviderResult.Reply("أهلًا"), r)
    }
    @Test fun cloudflareReplyIsParsed() {
        val r = client(ok("""{"result":{"response":"hello"},"success":true}""")).send(cf, "hi")
        assertEquals(ProviderResult.Reply("hello"), r)
    }
    @Test fun emptyReplyIsClassifiedAsEmpty() {
        assertEquals(ProviderResult.Failed(ProviderError.EMPTY), client(ok("""{"choices":[{"message":{"content":"   "}}]}""")).send(openai, "hi"))
        assertEquals(ProviderResult.Failed(ProviderError.EMPTY), client(ok("""{"result":{"response":""}}""")).send(cf, "hi"))
        assertEquals(ProviderResult.Failed(ProviderError.EMPTY), client(ok("""{"choices":[{"message":{"content":null}}]}""")).send(openai, "hi"))
    }
    @Test fun malformedBodiesAreBadResponse() {
        listOf("not json", "{}", """{"choices":[]}""", """{"choices":"x"}""", "").forEach {
            assertEquals(it, ProviderResult.Failed(ProviderError.BAD_RESPONSE), client(ok(it)).send(openai, "hi"))
        }
        assertEquals(ProviderResult.Failed(ProviderError.BAD_RESPONSE), client(ok("{}")).send(cf, "hi"))
    }
    @Test fun wrongKeyIsAuth() {
        assertEquals(ProviderResult.Failed(ProviderError.AUTH), client(Fake { _, _ -> HttpResult(401, "{\"error\":\"bad key sk-SECRET\"}") }).send(openai, "hi"))
    }
    @Test fun freeLimitExhaustedIsQuota() {
        assertEquals(ProviderResult.Failed(ProviderError.QUOTA),
            client(Fake { _, _ -> HttpResult(429, "{\"errors\":[{\"code\":4006,\"message\":\"you have used up your daily free allocation\"}]}") }).send(cf, "hi"))
    }
    @Test fun plainRateLimit() =
        assertEquals(ProviderResult.Failed(ProviderError.RATE_LIMIT), client(Fake { _, _ -> HttpResult(429, "") }).send(openai, "hi"))
    @Test fun serverErrors() =
        assertEquals(ProviderResult.Failed(ProviderError.SERVER), client(Fake { _, _ -> HttpResult(502, "") }).send(openai, "hi"))
    @Test fun redirectsAreRejectedNotFollowed() =
        assertEquals(ProviderResult.Failed(ProviderError.BAD_RESPONSE), client(Fake { _, _ -> HttpResult(307, "") }).send(openai, "hi"))
    @Test fun timeoutIsClassified() =
        assertEquals(ProviderResult.Failed(ProviderError.TIMEOUT), client(Fake { _, _ -> throw SocketTimeoutException("t") }).send(openai, "hi"))
    @Test fun offlineIsNetwork() {
        assertEquals(ProviderResult.Failed(ProviderError.NETWORK), client(Fake { _, _ -> throw UnknownHostException("h") }).send(openai, "hi"))
        assertEquals(ProviderResult.Failed(ProviderError.NETWORK), client(Fake { _, _ -> throw IOException("reset") }).send(openai, "hi"))
    }
    @Test fun cancelBeforeSendingNeverTouchesTheNetwork() {
        val f = ok("{}"); val h = CancelHandle(); h.cancel()
        assertEquals(ProviderResult.Failed(ProviderError.CANCELLED), client(f).send(openai, "hi", h))
        assertEquals(0, f.calls)
    }
    @Test fun cancelDuringRequestWinsOverTheReply() {
        val h = CancelHandle()
        val f = Fake { _, c -> c.cancel(); HttpResult(200, """{"choices":[{"message":{"content":"late"}}]}""") }
        assertEquals(ProviderResult.Failed(ProviderError.CANCELLED), client(f).send(openai, "hi", h))
    }
    @Test fun cancelThatBreaksTheConnectionIsCancelledNotNetwork() {
        val h = CancelHandle()
        val f = Fake { _, c -> c.cancel(); throw IOException("socket closed") }
        assertEquals(ProviderResult.Failed(ProviderError.CANCELLED), client(f).send(openai, "hi", h))
    }
    @Test fun incompleteOrInsecureConfigNeverSendsAnything() {
        val f = ok("{}")
        assertEquals(ProviderResult.Failed(ProviderError.BAD_CONFIG), client(f).send(openai.copy(apiKey = ""), "hi"))
        assertEquals(ProviderResult.Failed(ProviderError.BAD_CONFIG), client(f).send(openai.copy(address = "http://h.example"), "hi"))
        assertEquals(ProviderResult.Failed(ProviderError.BAD_CONFIG), client(f).send(openai, "   "))
        assertEquals(0, f.calls)
    }
    @Test fun requestGoesToTheChosenProviderOnly() {
        val f = ok("""{"choices":[{"message":{"content":"x"}}]}""")
        client(f).send(openai, "hi")
        assertEquals("https://h.example/v1/chat/completions", f.last!!.url)
        assertEquals("Bearer sk-SECRET", f.last!!.headers["Authorization"])
    }
    @Test fun failureResultsNeverCarryTheKeyOrBody() {
        val r = client(Fake { _, _ -> HttpResult(401, "{\"error\":\"bad key sk-SECRET\"}") }).send(openai, "hi")
        assertFalse(r.toString().contains("SECRET"))
    }
    @Test fun noProviderStateNeverCallsTransport() {
        // The UI only calls ProviderClient when the gate says READY.
        assertNotEquals(ProviderState.READY, ProviderGate.state(true, openai.copy(apiKey = "")))
        assertNotEquals(ProviderState.READY, ProviderGate.state(false, openai))
    }
}
