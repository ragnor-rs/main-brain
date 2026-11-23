package io.github.ragnorrs.mainbrain.bot.core

import io.github.ragnorrs.mainbrain.bot.client.BackendClient
import io.github.ragnorrs.mainbrain.bot.client.Reached
import io.github.ragnorrs.mainbrain.bot.i18n.BotKey
import io.github.ragnorrs.mainbrain.bot.i18n.Catalogue
import io.github.ragnorrs.mainbrain.bot.port.IncomingMessage
import io.github.ragnorrs.mainbrain.bot.port.MessagingPort
import io.github.ragnorrs.mainbrain.bot.port.Reply
import org.slf4j.LoggerFactory

/**
 * What happens to one message: admit it, read it, ask, say what came back.
 *
 * **Called rather than looping.** However messages arrive is the adapter's business — long polling
 * here, something posted elsewhere — and this is a function over one of them. A handler that drove
 * the platform would make the arrival mechanism its own concern and would not survive an interface
 * whose platform pushes instead of answering a poll.
 *
 * **Nothing happens on behalf of a message the guard refuses, including a reply.** Silence rather
 * than a refusal: *you are not allowed* confirms to a stranger that they found something, and
 * there is nobody on the other side of that sentence worth telling. The log is where a refusal is
 * visible, which is the only place it should be.
 */
internal class Handler(
    private val guard: AuthGuard,
    private val backend: BackendClient,
    private val renderer: OutcomeRenderer,
    private val catalogue: Catalogue,
    private val messaging: MessagingPort,
) {

    /** Deal with [message], whatever it turns out to be. */
    suspend fun handle(message: IncomingMessage) {
        if (!guard.admits(message)) {
            log.warn(
                "A message from {} in a chat that is not admitted was ignored, direct={}.",
                message.sender.value,
                message.directChat,
            )
            return
        }
        replies(message).forEach { messaging.send(message.chat, it) }
    }

    /**
     * Everything to say in answer to [message].
     *
     * Built whole before anything is sent, which is what makes this testable as a value. The two
     * branches are the two things a message can be: this interface's own furniture, answered out
     * of the catalogue and reaching nothing, or a question for the backend.
     */
    private suspend fun replies(message: IncomingMessage): List<Reply> =
        when (val asked = Commands.read(message.text)) {
            // One message, however many entries it is made of: what a mistyped command is told and
            // the list it is told to read are one thing to say, and a second message carrying the
            // list arrives as a fragment a reader scrolls past.
            is Asked.AtTheEdge -> listOf(Reply(catalogue.text(asked.key, message.locale, asked.arguments)))

            is Asked.OfBackend -> when (val reached = backend.answer(asked.request)) {
                is Reached.Answered -> renderer.render(reached.response, message.locale)
                // A refusal is a key like any other, so it renders the same way everything this
                // interface says about itself does.
                is Reached.Refused -> listOf(Reply(catalogue.text(reached.key, message.locale)))
                // And the one failure that is this interface's own rather than the backend's: it
                // stopped waiting for work that was still being composed. The key is therefore
                // from this interface's own vocabulary, because the backend never says this and
                // another interface waiting differently would word it differently.
                Reached.Overran -> listOf(Reply(catalogue.text(BotKey.ANSWER_OVERRAN, message.locale)))
            }
        }

    private companion object {

        private val log = LoggerFactory.getLogger(Handler::class.java)
    }
}
