package dev.momolite

import org.junit.Assert.assertEquals
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

    @Test fun reminderPlanSplitsFutureAndPastDue() {
        val future = Reminder(1, "صلاة الفجر", now + 60_000)
        val past = Reminder(2, "مكالمة فاتت", now - 60_000)
        val (toSchedule, toFire) = ReminderPlan.split(listOf(future, past), now)
        assertEquals(listOf(future), toSchedule)
        assertEquals(listOf(past), toFire)
    }

    @Test fun reminderPlanBoundaryIsPastDue() {
        val edge = Reminder(3, "دلوقتي", now)
        val (toSchedule, toFire) = ReminderPlan.split(listOf(edge), now)
        assertEquals(emptyList<Reminder>(), toSchedule)
        assertEquals(listOf(edge), toFire)
    }

    @Test fun reminderPlanHandlesEmptyList() {
        val (toSchedule, toFire) = ReminderPlan.split(emptyList(), now)
        assertEquals(0, toSchedule.size)
        assertEquals(0, toFire.size)
    }
}
