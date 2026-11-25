package dev.momolite

import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    private val now = 1_000_000L

    // ---- reminders ----
    @Test fun blankReminderIsRejected() = assertEquals(ReminderError.EMPTY_TEXT, ReminderRules.validate("   ", now + 1, now))
    @Test fun pastReminderIsRejected() = assertEquals(ReminderError.TIME_IN_PAST, ReminderRules.validate("x", now - 1, now))
    @Test fun reminderAtExactlyNowIsPast() = assertEquals(ReminderError.TIME_IN_PAST, ReminderRules.validate("x", now, now))
    @Test fun futureReminderIsAccepted() = assertNull(ReminderRules.validate("x", now + 1, now))
    @Test fun reminderTextIsTrimmedAndCapped() {
        assertEquals("a", ReminderRules.clean("  a  "))
        assertEquals(ReminderRules.MAX_TEXT, ReminderRules.clean("x".repeat(500)).length)
    }

    @Test fun futurePendingReminderIsScheduled() =
        assertEquals(ReminderAction.SCHEDULE, ReminderPlan.decide(Reminder(1, "a", now + 5), now, true))
    @Test fun futureReminderIsScheduledEvenWhenNotificationsBlocked() =
        assertEquals(ReminderAction.SCHEDULE, ReminderPlan.decide(Reminder(1, "a", now + 5), now, false))
    @Test fun pastDuePendingFiresWhenNotificationsWork() =
        assertEquals(ReminderAction.DELIVER_NOW, ReminderPlan.decide(Reminder(1, "a", now - 5), now, true))
    @Test fun pastDuePendingIsKeptWhenNotificationsBlocked() =
        assertEquals(ReminderAction.KEEP_PENDING, ReminderPlan.decide(Reminder(1, "a", now - 5), now, false))
    @Test fun boundaryReminderCountsAsPastDue() =
        assertEquals(ReminderAction.DELIVER_NOW, ReminderPlan.decide(Reminder(1, "a", now), now, true))
    @Test fun deliveredReminderIsClearedNotRefired() {
        assertEquals(ReminderAction.CLEAR_DELIVERED, ReminderPlan.decide(Reminder(1, "a", now - 5, REMINDER_DELIVERED), now, true))
        assertEquals(ReminderAction.CLEAR_DELIVERED, ReminderPlan.decide(Reminder(1, "a", now + 5, REMINDER_DELIVERED), now, false))
    }
    @Test fun receiverDeliversOnlyWhenNotificationsWork() {
        assertEquals(ReminderAction.DELIVER_NOW, ReminderPlan.onFire(true))
        assertEquals(ReminderAction.KEEP_PENDING, ReminderPlan.onFire(false))
    }
    @Test fun bootRecoveryMixedBatchIsDecidedPerReminder() {
        val batch = listOf(
            Reminder(1, "future", now + 100), Reminder(2, "missed", now - 100),
            Reminder(3, "done", now - 50, REMINDER_DELIVERED), Reminder(4, "future2", now + 1))
        val granted = batch.map { ReminderPlan.decide(it, now, true) }
        assertEquals(listOf(ReminderAction.SCHEDULE, ReminderAction.DELIVER_NOW, ReminderAction.CLEAR_DELIVERED, ReminderAction.SCHEDULE), granted)
        val denied = batch.map { ReminderPlan.decide(it, now, false) }
        assertEquals(listOf(ReminderAction.SCHEDULE, ReminderAction.KEEP_PENDING, ReminderAction.CLEAR_DELIVERED, ReminderAction.SCHEDULE), denied)
    }

    // ---- titles ----
    @Test fun titleComesFromFirstLineTrimmed() = assertEquals("hello", Titles.derive("  hello \nsecond line"))
    @Test fun longTitleIsCapped() = assertEquals(Titles.MAX_LEN, Titles.derive("x".repeat(200)).length)
    @Test fun emptyTitleFallsBack() { assertEquals(Titles.FALLBACK, Titles.derive("   ")); assertEquals(Titles.FALLBACK, Titles.derive("\n\n")) }

    // ---- url rules ----
    @Test fun onlyHttpsHostsAreAccepted() {
        assertTrue(UrlRules.isSecureHttps("https://api.example.com/v1"))
        assertFalse(UrlRules.isSecureHttps("http://api.example.com/v1"))
        assertFalse(UrlRules.isSecureHttps("ftp://x.com"))
        assertFalse(UrlRules.isSecureHttps("https://"))
        assertFalse(UrlRules.isSecureHttps("not a url"))
        assertFalse(UrlRules.isSecureHttps(""))
    }
    @Test fun addressesWithEmbeddedCredentialsAreRejected() = assertFalse(UrlRules.isSecureHttps("https://user:pw@evil.example.com/"))

    // ---- config completeness and the provider gate ----
    private fun openai(key: String = "k", model: String = "m", addr: String = "https://api.openai.com/v1") =
        ProviderConfig(ProviderType.OPENAI_COMPATIBLE, key, model, address = addr)

    @Test fun completeOpenAiConfigHasNothingMissing() = assertTrue(ProviderChoice.missing(openai()).isEmpty())
    @Test fun missingKeyAndModelAreReported() =
        assertEquals(listOf(MissingField.KEY, MissingField.MODEL), ProviderChoice.missing(openai(key = " ", model = "")))
    @Test fun httpAddressIsReportedAsNotHttps() =
        assertEquals(listOf(MissingField.ADDRESS_NOT_HTTPS), ProviderChoice.missing(openai(addr = "http://x.com")))
    @Test fun cloudflareNeedsAccountIdAlphanumeric() {
        val ok = ProviderConfig(ProviderType.CLOUDFLARE, "k", ProviderChoice.DEFAULT_CF_MODEL, accountId = "abc123")
        assertTrue(ProviderChoice.missing(ok).isEmpty())
        assertEquals(listOf(MissingField.ACCOUNT_ID), ProviderChoice.missing(ok.copy(accountId = "")))
        assertEquals(listOf(MissingField.ACCOUNT_ID), ProviderChoice.missing(ok.copy(accountId = "../evil")))
    }
    @Test fun providerStaysOffWithoutOptIn() {
        assertEquals(ProviderState.OFF, ProviderGate.state(false, openai()))
        assertEquals(ProviderState.OFF, ProviderGate.state(false, null))
    }
    @Test fun enabledWithoutCompleteConfigIsIncomplete() {
        assertEquals(ProviderState.INCOMPLETE, ProviderGate.state(true, null))
        assertEquals(ProviderState.INCOMPLETE, ProviderGate.state(true, openai(key = "")))
    }
    @Test fun enabledAndCompleteIsReady() = assertEquals(ProviderState.READY, ProviderGate.state(true, openai()))
    @Test fun configToStringNeverContainsTheKey() {
        val s = openai(key = "sk-SECRET-123").toString()
        assertFalse(s.contains("SECRET"))
        assertTrue(s.contains("set"))
    }
    @Test fun providerTypeParsingFallsBackSafely() {
        assertEquals(ProviderType.CLOUDFLARE, ProviderType.parse("CLOUDFLARE"))
        assertEquals(ProviderType.OPENAI_COMPATIBLE, ProviderType.parse("junk"))
        assertEquals(ProviderType.OPENAI_COMPATIBLE, ProviderType.parse(null))
    }

    // ---- request builder ----
    @Test fun openAiRequestShape() {
        val r = RequestBuilder.build(openai(addr = "https://host.example/v1/"), "مرحبا \"x\"\n")
        assertEquals("https://host.example/v1/chat/completions", r.url)
        assertEquals("Bearer k", r.headers["Authorization"])
        assertEquals("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"مرحبا \\\"x\\\"\\n\"}]}", r.body)
    }
    @Test fun customAddressIsUsedAsIs() {
        val c = ProviderConfig(ProviderType.CUSTOM, "k", "m", address = "https://host.example/custom/endpoint")
        assertEquals("https://host.example/custom/endpoint", RequestBuilder.build(c, "hi").url)
    }
    @Test fun cloudflareRequestShape() {
        val c = ProviderConfig(ProviderType.CLOUDFLARE, "k", "@cf/meta/llama-3.1-8b-instruct", accountId = "acc1")
        val r = RequestBuilder.build(c, "hi")
        assertEquals("https://api.cloudflare.com/client/v4/accounts/acc1/ai/run/@cf/meta/llama-3.1-8b-instruct", r.url)
        assertEquals("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", r.body)
    }
    @Test fun payloadCarriesOnlyTheCurrentMessage() {
        val body = RequestBuilder.build(openai(), "only this").body
        assertEquals(1, Regex("\"role\"").findAll(body).count())
    }
    @Test fun controlCharactersAreEscaped() = assertEquals("\"\\u0001\"", Json.quote("\u0001"))

    // ---- http error mapping ----
    @Test fun httpMappingIsHonest() {
        assertEquals(ProviderError.AUTH, ProviderErrors.fromHttp(401))
        assertEquals(ProviderError.AUTH, ProviderErrors.fromHttp(403))
        assertEquals(ProviderError.QUOTA, ProviderErrors.fromHttp(402))
        assertEquals(ProviderError.RATE_LIMIT, ProviderErrors.fromHttp(429, "{\"error\":\"slow down\"}"))
        assertEquals(ProviderError.QUOTA, ProviderErrors.fromHttp(429, "{\"errors\":[{\"code\":4006}]}"))
        assertEquals(ProviderError.QUOTA, ProviderErrors.fromHttp(429, "{\"error\":{\"code\":\"insufficient_quota\"}}"))
        assertEquals(ProviderError.SERVER, ProviderErrors.fromHttp(503))
        assertEquals(ProviderError.BAD_RESPONSE, ProviderErrors.fromHttp(404))
        assertEquals(ProviderError.BAD_RESPONSE, ProviderErrors.fromHttp(302))
    }
}
