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
