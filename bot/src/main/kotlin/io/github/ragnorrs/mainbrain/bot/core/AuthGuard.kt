package io.github.ragnorrs.mainbrain.bot.core

import io.github.ragnorrs.mainbrain.bot.port.IncomingMessage
import io.github.ragnorrs.mainbrain.bot.port.SenderId

/**
 * Who gets work done on their behalf.
 *
 * **At the edge, before anything else.** A message that does not pass this costs one comparison
 * against a set in memory: no backend call, no model call, no retrieval, nothing read from the
 * corpus. That ordering is the acceptance this exists for — an unknown chat is not a request that
 * gets refused late, it is a request that never happens.
 *
 * **A group conversation is refused however familiar the sender.** The assistant answers from one
 * person's archive, so answering in a room shows it to whoever else is in that room — and a chat
 * this process was added to by someone who knows the owner is exactly the case that looks safe.
 * Membership is not a property anything here can read, so the kind of chat is what decides it.
 *
 * Pure, and the whole of the decision. The reply to a message this refuses is silence, which is
 * the caller's business rather than this one's: saying *you are not allowed* to a stranger
 * confirms what they found, and there is no one on the other side of that sentence worth telling.
 */
internal class AuthGuard(private val allowed: Set<SenderId>) {

    init {
        require(allowed.isNotEmpty()) {
            "A guard admitting nobody would make every message a refusal, which is a " +
                "misconfiguration rather than a posture. The configuration refuses an empty list."
        }
    }

    /** Whether [message] gets work done on its behalf. */
    fun admits(message: IncomingMessage): Boolean =
        message.directChat && message.sender in allowed
}
