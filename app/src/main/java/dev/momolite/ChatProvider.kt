package dev.momolite

/**
 * Where assistant replies come from. The contract is asynchronous so a
 * network provider can plug in later without changing the UI. NoProvider
 * calls back immediately with null: the app never pretends to answer.
 */
interface ChatProvider {
    /** Calls [onReply] with the reply text, or null when no reply is available. */
    fun reply(history: List<Msg>, onReply: (String?) -> Unit)

    /**
     * Detailed contract: reports classified failures so the UI can be honest about
     * what went wrong. Default bridges to [reply] and treats null as a network failure.
     */
    fun replyDetailed(history: List<Msg>, onReply: (ProviderResult) -> Unit) {
        reply(history) { r -> onReply(if (r != null) ProviderResult.Reply(r) else ProviderResult.Failed(ProviderError.NETWORK)) }
    }

    /** Synchronous convenience bridge for callers that cannot take a callback. */
    fun reply(history: List<Msg>): String? {
        var r: String? = null
        reply(history) { r = it }
        return r
    }
}

object NoProvider : ChatProvider {
    override fun reply(history: List<Msg>, onReply: (String?) -> Unit) = onReply(null)
}
