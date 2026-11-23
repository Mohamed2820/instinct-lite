package dev.momolite

/**
 * Where assistant replies come from. No provider is connected in v0.1,
 * so the app never pretends to answer. Add a real implementation later.
 */
interface ChatProvider {
    /** Returns the reply text, or null when no reply is available. */
    fun reply(history: List<Msg>): String?
}

object NoProvider : ChatProvider {
    override fun reply(history: List<Msg>): String? = null
}
