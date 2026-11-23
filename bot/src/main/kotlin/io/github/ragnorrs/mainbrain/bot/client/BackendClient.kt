package io.github.ragnorrs.mainbrain.bot.client

import io.github.ragnorrs.mainbrain.contract.AnswerRequest
import io.github.ragnorrs.mainbrain.contract.AnswerResponse
import io.github.ragnorrs.mainbrain.contract.MessageKey

/**
 * What came of asking.
 *
 * Cases rather than an exception, because every way this can go ends as something said to a
 * person: a refusal is a message key like any other, and a transport failure has to become one
 * too. A client that threw would make its caller decide what an exception means, which is the
 * adapter's knowledge rather than the caller's.
 */
internal sealed interface Reached {

    /** The backend answered. */
    data class Answered(val response: AnswerResponse) : Reached

    /** It did not, and this is what to say about that. */
    data class Refused(val key: MessageKey) : Reached

    /**
     * The work was taken, every hop was answered, and this client stopped waiting for it.
     *
     * **Not a [Refused], because nothing refused anything.** Every other case here is the backend
     * answering — with a reply, with a refusal, or by not being there, which asks the same thing of
     * the person who asked as a backend that is up and busy does. This one is the opposite: the
     * service was reached and was working, and what ran out was *this* client's patience. One case
     * for both would tell a reader the service is unavailable while it is composing their answer,
     * and send them back to retry a question that will overrun again at the same cost.
     *
     * **Every hop having landed is part of what this case means, not an incidental.** Whoever
     * renders it says the service was there and the work was in progress, so a wait that gave up
     * with any hop unanswered is a [Refused] — see where the two are chosen between.
     *
     * **It carries no key, and that is the point of it being its own case.** A key here could only
     * be a wire key, and the backend never says this: from the far side the work was accepted and
     * is being done. So the fact travels and the words do not, for the reason the rendering
     * interface's own vocabulary records.
     */
    data object Overran : Reached
}

/**
 * The only way this process reaches anything.
 *
 * No database, no model, no retrieval, no call to the ingest worker — one question on one service.
 * Everything the frontend knows, it knows from here.
 *
 * **A seam declared in wire types and in facts about the call itself, which is why it is
 * platform-free.** It speaks `:contract`, says the rest in no language at all, and has never heard
 * of a chat — so it is the one collaborator a second interface would reuse unchanged.
 * [HttpBackendClient] is the only implementation that reaches a network; a test implements this
 * directly and needs no socket.
 *
 * **Nothing here throws.** Every status the surface can answer with becomes something to say, and
 * so does every way the call can fail to happen at all, and so does this client running out of
 * patience — so a caller renders a reply rather than handling an error.
 */
internal interface BackendClient {

    /** Ask, and come back with something to say either way. */
    suspend fun answer(request: AnswerRequest): Reached
}
