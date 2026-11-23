package io.github.ragnorrs.mainbrain.bot.port

/**
 * One message to put in front of a person, already in their language.
 *
 * **Text, carrying the little markup this interface renders, already escaped.** Whatever did not
 * come out of the catalogue has been through `escaped` by the time it reaches here, so a body is
 * safe to parse as markup and unsafe to concatenate anything unescaped onto. Some of that markup
 * the renderer composed and some the catalogue's own entries carry; either way no emphasis is
 * nested inside another. The parse mode that reads it is the adapter's business, not this port's.
 *
 * **[footer] is a sentence about the [body] rather than part of it, and that is why it is a field.**
 * What it qualifies is the answer above it, so it has to arrive in the same message — a note
 * delivered on its own is a sentence about somebody else's sentence, which was measured on every
 * shape this build has. Separate from [body] because where the platform's limit falls is not known
 * here: whoever splits a long body is the only thing that can put this on the last piece rather
 * than letting it become a piece. Null on all but one reply of an answer, since it qualifies the
 * answer once.
 *
 * A reply carrying a generated file is additive — a second kind here, found by the compiler at
 * every renderer — and nothing emits one today: the wire format has no outcome that carries
 * bytes, so there is nothing for such a reply to be built from.
 *
 * **Several of these rather than one long one is the shape that matters.** Alternatives arrive as
 * alternatives, and each draft is its own message so that the one a person wants is the one they
 * forward, rather than a passage they have to cut out of a wall of text.
 */
internal data class Reply(val body: String, val footer: String? = null) {
    init {
        require(body.isNotBlank()) { "A reply with nothing in it is not one." }
        // A blank footer is a paragraph break and nothing after it, which reads as an answer that
        // was cut off. Absent is how *nothing to add* is said.
        require(footer == null || footer.isNotBlank()) {
            "A blank footer is not a sentence about the answer. Nothing to add carries none."
        }
    }
}

/**
 * The only way anything reaches the assistant's chat.
 *
 * Replies in the assistant's own conversation, and nothing else: there is no counterparty on the
 * other end of this and no method here that could reach one. Messaging a counterparty is
 * workstream III, and the absence of a seam for it is deliberate rather than pending — every
 * answer this build produces is a draft the person acts on.
 *
 * Implemented in `adapter/`, which is the only package that knows what a chat is. The core sends
 * through this, so the platform's client appears in exactly one file.
 */
internal interface MessagingPort {

    /** Put [reply] in [chat]. */
    suspend fun send(chat: ChatRef, reply: Reply)
}
