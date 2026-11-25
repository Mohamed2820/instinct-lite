package dev.momolite

import java.net.URI

/**
 * Pure decision logic with no Android imports, so every rule here is
 * unit-tested on a plain JVM (locally and on the GitHub Actions runner).
 */

/** What can go wrong when adding a reminder. */
enum class ReminderError { EMPTY_TEXT, TIME_IN_PAST }

object ReminderRules {
    const val MAX_TEXT = 200

    /** Returns the problem with this reminder, or null when it can be saved. */
    fun validate(text: String, atMillis: Long, nowMillis: Long): ReminderError? = when {
        text.isBlank() -> ReminderError.EMPTY_TEXT
        atMillis <= nowMillis -> ReminderError.TIME_IN_PAST
        else -> null
    }

    fun clean(text: String): String = text.trim().take(MAX_TEXT)
}

object Titles {
    const val MAX_LEN = 40
    const val FALLBACK = "محادثة جديدة"

    /** Conversation title taken from its first message: trimmed, one line, capped. */
    fun derive(firstMessage: String): String {
        val t = firstMessage.trim().lineSequence().firstOrNull()?.trim().orEmpty()
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
    /** Receiver decision when an alarm fires: deliver only when a notification can actually be shown. */
    fun onFire(notificationsEnabled: Boolean): ReminderAction =
        if (notificationsEnabled) ReminderAction.DELIVER_NOW else ReminderAction.KEEP_PENDING

    /**
     * A reminder exactly at [nowMillis] counts as past due. A delivered reminder is
     * cleared rather than re-fired even when its time is past due, because a crash
     * can leave a delivered row behind; past-due alone never proves "never delivered".
     */
    fun decide(r: Reminder, nowMillis: Long, notificationsEnabled: Boolean): ReminderAction = when {
        r.state == REMINDER_DELIVERED -> ReminderAction.CLEAR_DELIVERED
        r.at > nowMillis -> ReminderAction.SCHEDULE
        notificationsEnabled -> ReminderAction.DELIVER_NOW
        else -> ReminderAction.KEEP_PENDING
    }
}

/** Providers the user can pick in Settings. */
enum class ProviderType {
    /** Any OpenAI-compatible service: base address + /chat/completions. */
    OPENAI_COMPATIBLE,
    /** Cloudflare Workers AI REST endpoint. */
    CLOUDFLARE,
    /** A full custom endpoint address that speaks the OpenAI chat format. */
    CUSTOM;

    companion object {
        fun parse(name: String?): ProviderType = values().firstOrNull { it.name == name } ?: OPENAI_COMPATIBLE
    }
}

/** What the user entered in Settings. The key lives only in encrypted storage. */
data class ProviderConfig(
    val type: ProviderType,
    val apiKey: String,
    val model: String,
    val accountId: String = "",
    val address: String = ""
) {
    /** Never prints the key: data-class toString would otherwise leak it into logs. */
    override fun toString(): String = "ProviderConfig(type=$type, model=$model, key=${if (apiKey.isBlank()) "none" else "set"})"
}

/** Fields that are required before a provider can be used. */
enum class MissingField { KEY, MODEL, ACCOUNT_ID, ADDRESS, ADDRESS_NOT_HTTPS }

object UrlRules {
    /** HTTPS only, a real host, and no credentials embedded in the address. */
    fun isSecureHttps(address: String): Boolean = try {
        val u = URI(address.trim())
        u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrBlank() && u.userInfo == null
    } catch (e: Exception) { false }
}

object ProviderChoice {
    const val DEFAULT_OPENAI_BASE = "https://api.openai.com/v1"
    const val DEFAULT_CF_MODEL = "@cf/meta/llama-3.1-8b-instruct"

    fun defaultModel(type: ProviderType): String = when (type) {
        ProviderType.CLOUDFLARE -> DEFAULT_CF_MODEL
        else -> ""
    }

    fun missing(c: ProviderConfig): List<MissingField> {
        val out = ArrayList<MissingField>()
        if (c.apiKey.isBlank()) out.add(MissingField.KEY)
        if (c.model.isBlank()) out.add(MissingField.MODEL)
        when (c.type) {
            ProviderType.CLOUDFLARE -> if (c.accountId.isBlank() || !c.accountId.all { it.isLetterOrDigit() }) out.add(MissingField.ACCOUNT_ID)
            ProviderType.OPENAI_COMPATIBLE, ProviderType.CUSTOM -> {
                if (c.address.isBlank()) out.add(MissingField.ADDRESS)
                else if (!UrlRules.isSecureHttps(c.address)) out.add(MissingField.ADDRESS_NOT_HTTPS)
            }
        }
        return out
    }
}

/** UI-facing state of the provider switch. */
enum class ProviderState { OFF, INCOMPLETE, READY }

object ProviderGate {
    fun state(enabled: Boolean, config: ProviderConfig?): ProviderState = when {
        !enabled -> ProviderState.OFF
        config == null || ProviderChoice.missing(config).isNotEmpty() -> ProviderState.INCOMPLETE
        else -> ProviderState.READY
    }
}

/** A fully built HTTPS request. Pure data, so tests can inspect it without a network. */
data class HttpRequestSpec(val url: String, val headers: Map<String, String>, val body: String)

object RequestBuilder {
    fun build(c: ProviderConfig, userText: String): HttpRequestSpec {
        val headers = linkedMapOf("Authorization" to "Bearer ${c.apiKey}", "Content-Type" to "application/json")
        return when (c.type) {
            ProviderType.CLOUDFLARE -> HttpRequestSpec(
                "https://api.cloudflare.com/client/v4/accounts/${c.accountId.trim()}/ai/run/${c.model.trim()}",
                headers, "{\"messages\":[{\"role\":\"user\",\"content\":${Json.quote(userText)}}]}")
            ProviderType.OPENAI_COMPATIBLE -> HttpRequestSpec(
                c.address.trim().trimEnd('/') + "/chat/completions", headers, openAiBody(c.model, userText))
            ProviderType.CUSTOM -> HttpRequestSpec(c.address.trim(), headers, openAiBody(c.model, userText))
        }
    }

    /** Body carries only the current message text: no history, no files, no metadata. */
    private fun openAiBody(model: String, text: String) =
        "{\"model\":${Json.quote(model.trim())},\"messages\":[{\"role\":\"user\",\"content\":${Json.quote(text)}}]}"
}

object Json {
    fun quote(s: String): String {
        val out = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
        }
        return out.append('"').toString()
    }
}

/** Why a live provider attempt failed; each kind maps to an honest, secret-free message. */
enum class ProviderError { NETWORK, TIMEOUT, AUTH, RATE_LIMIT, QUOTA, SERVER, BAD_RESPONSE, EMPTY, CANCELLED, BAD_CONFIG }

/** Outcome of a live provider attempt: either a reply or a classified failure. */
sealed class ProviderResult {
    data class Reply(val text: String) : ProviderResult()
    data class Failed(val kind: ProviderError) : ProviderResult()
}

object ProviderErrors {
    /**
     * HTTP status (+ body, inspected only to tell "free quota used up" from "slow down")
     * to an error kind. Response bodies are never shown to the user: they may echo request data.
     */
    fun fromHttp(code: Int, body: String = ""): ProviderError = when {
        code == 401 || code == 403 -> ProviderError.AUTH
        code == 402 -> ProviderError.QUOTA
        code == 429 -> if (looksLikeQuota(body)) ProviderError.QUOTA else ProviderError.RATE_LIMIT
        code in 500..599 -> ProviderError.SERVER
        else -> ProviderError.BAD_RESPONSE
    }

    private fun looksLikeQuota(body: String): Boolean {
        val b = body.lowercase()
        return "4006" in b || "insufficient_quota" in b || "free allocation" in b || "quota" in b || "billing" in b
    }
}
