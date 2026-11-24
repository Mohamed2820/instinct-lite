package dev.momolite

/**
 * Pure decision logic, kept free of Android imports so it can be
 * unit-tested on any JVM (including the GitHub Actions runner).
 */

/** What can go wrong when adding a reminder. */
enum class ReminderError { EMPTY_TEXT, TIME_IN_PAST }

object ReminderRules {
    /** Returns the problem with this reminder, or null when it can be saved. */
    fun validate(text: String, atMillis: Long, nowMillis: Long): ReminderError? = when {
        text.isBlank() -> ReminderError.EMPTY_TEXT
        atMillis <= nowMillis -> ReminderError.TIME_IN_PAST
        else -> null
    }
}

object Titles {
    const val MAX_LEN = 40
    const val FALLBACK = "محادثة جديدة"

    /** Conversation title taken from its first message: trimmed and capped. */
    fun derive(firstMessage: String): String {
        val t = firstMessage.trim()
        return if (t.isEmpty()) FALLBACK else t.take(MAX_LEN)
    }
}

/** What boot/app-open recovery does with one saved reminder. */
enum class ReminderAction {
    /** Future reminder: (re)arm its alarm. */
    SCHEDULE,
    /** Past-due pending reminder and notifications can be shown: fire it now. */
    DELIVER_NOW,
    /** Past-due pending reminder but notifications are blocked: keep it, never drop it silently. */
    KEEP_PENDING,
    /** Already delivered (possibly left over by a crash): clear it, never fire it again. */
    CLEAR_DELIVERED
}

/** Decides reminder recovery from saved state, not from guesses about the past. */
object ReminderPlan {
    /** Kept so older callers compile; recovery decisions use [decide]. */
    fun split(reminders: List<Reminder>, nowMillis: Long): Pair<List<Reminder>, List<Reminder>> =
        reminders.partition { it.at > nowMillis }

    /**
     * A reminder exactly at [nowMillis] counts as past due. A delivered reminder is
     * cleared rather than re-fired even when its time is past due, because a crash
     * can leave a delivered row behind; past-due alone never proves "never delivered".
     */
    /** Receiver decision when an alarm fires: deliver only when a notification can actually be shown. */
    fun onFire(notificationsEnabled: Boolean): ReminderAction =
        if (notificationsEnabled) ReminderAction.DELIVER_NOW else ReminderAction.KEEP_PENDING

    fun decide(r: Reminder, nowMillis: Long, notificationsEnabled: Boolean): ReminderAction = when {
        r.state == REMINDER_DELIVERED -> ReminderAction.CLEAR_DELIVERED
        r.at > nowMillis -> ReminderAction.SCHEDULE
        notificationsEnabled -> ReminderAction.DELIVER_NOW
        else -> ReminderAction.KEEP_PENDING
    }
}
