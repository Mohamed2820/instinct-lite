package dev.momolite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LogicTest {
    private val now = 1_000_000L

    @Test fun blankReminderIsRejected() {
        assertEquals(ReminderError.EMPTY_TEXT, ReminderRules.validate("", now + 60_000, now))
        assertEquals(ReminderError.EMPTY_TEXT, ReminderRules.validate("   ", now + 60_000, now))
    }

    @Test fun pastReminderIsRejected() {
        assertEquals(ReminderError.TIME_IN_PAST, ReminderRules.validate("مذاكرة", now - 1, now))
        assertEquals(ReminderError.TIME_IN_PAST, ReminderRules.validate("مذاكرة", now, now))
    }

    @Test fun futureReminderIsAccepted() {
        assertNull(ReminderRules.validate("مذاكرة", now + 60_000, now))
    }

    @Test fun titleComesFromFirstMessageTrimmed() {
        assertEquals("أهلا", Titles.derive("  أهلا  "))
    }

    @Test fun longTitleIsCapped() {
        assertEquals(Titles.MAX_LEN, Titles.derive("ا".repeat(200)).length)
    }

    @Test fun emptyTitleFallsBack() {
        assertEquals(Titles.FALLBACK, Titles.derive("   "))
    }

    @Test fun noProviderCallsBackWithNull() {
        var seen: String? = "unset"
        NoProvider.reply(listOf(Msg(1, "user", "أهلا", 0))) { seen = it }
        assertNull(seen)
    }

    @Test fun futurePendingReminderIsScheduled() {
        val future = Reminder(1, "a", 2_000L)
        assertEquals(ReminderAction.SCHEDULE, ReminderPlan.decide(future, 1_000L, notificationsEnabled = true))
        assertEquals(ReminderAction.SCHEDULE, ReminderPlan.decide(future, 1_000L, notificationsEnabled = false))
    }

    @Test fun pastDuePendingReminderFiresWhenNotificationsWork() {
        val past = Reminder(2, "b", 500L)
        assertEquals(ReminderAction.DELIVER_NOW, ReminderPlan.decide(past, 1_000L, notificationsEnabled = true))
    }

    @Test fun pastDuePendingReminderIsKeptWhenNotificationsBlocked() {
        val past = Reminder(2, "b", 500L)
        // The row must survive: blocked notifications never silently delete a reminder.
        assertEquals(ReminderAction.KEEP_PENDING, ReminderPlan.decide(past, 1_000L, notificationsEnabled = false))
    }

    @Test fun boundaryReminderCountsAsPastDue() {
        val edge = Reminder(3, "c", 1_000L)
        assertEquals(ReminderAction.DELIVER_NOW, ReminderPlan.decide(edge, 1_000L, notificationsEnabled = true))
    }

    @Test fun deliveredReminderIsClearedNotRefired() {
        // Crash left a delivered row behind: past-due alone never proves "never delivered".
        val leftover = Reminder(4, "d", 500L, state = REMINDER_DELIVERED)
        assertEquals(ReminderAction.CLEAR_DELIVERED, ReminderPlan.decide(leftover, 1_000L, notificationsEnabled = true))
        assertEquals(ReminderAction.CLEAR_DELIVERED, ReminderPlan.decide(leftover, 1_000L, notificationsEnabled = false))
    }

    @Test fun cloudflareEndpointCarriesAccountAndModel() {
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/acc123/ai/run/@cf/meta/llama-3.1-8b-instruct",
            CloudflareRequest.endpoint("acc123"))
    }

    @Test fun cloudflarePayloadCarriesOnlyCurrentMessage() {
        val p = CloudflareRequest.payload("قولّي النهارده إيه")
        assertEquals("{\"messages\":[{\"role\":\"user\",\"content\":\"قولّي النهارده إيه\"}]}", p)
    }

    @Test fun cloudflarePayloadEscapesQuotesAndNewlines() {
        val p = CloudflareRequest.payload("a\"b\nc\\d")
        assertEquals("{\"messages\":[{\"role\":\"user\",\"content\":\"a\\\"b\\nc\\\\d\"}]}", p)
    }


    @Test fun providerStaysOffWithoutExplicitOptIn() {
        // Credentials alone never enable the provider.
        assertFalse(ProviderChoice.useCloudflare(enabled = false, hasToken = true, hasAccountId = true))
        assertFalse(ProviderChoice.useCloudflare(enabled = true, hasToken = false, hasAccountId = true))
        assertFalse(ProviderChoice.useCloudflare(enabled = true, hasToken = true, hasAccountId = false))
        assertTrue(ProviderChoice.useCloudflare(enabled = true, hasToken = true, hasAccountId = true))
    }

    @Test fun receiverDeliversOnlyWhenNotificationsWork() {
        assertEquals(ReminderAction.DELIVER_NOW, ReminderPlan.onFire(notificationsEnabled = true))
        assertEquals(ReminderAction.KEEP_PENDING, ReminderPlan.onFire(notificationsEnabled = false))
    }

    @Test fun deliveredReminderIsClearedEvenWhenItsTimeIsStillFuture() {
        val weird = Reminder(5, "e", 9_000L, state = REMINDER_DELIVERED)
        assertEquals(ReminderAction.CLEAR_DELIVERED, ReminderPlan.decide(weird, 1_000L, notificationsEnabled = true))
    }
}
