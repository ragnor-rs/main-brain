package io.github.ragnorrs.mainbrain.bot.core

import io.github.ragnorrs.mainbrain.bot.i18n.BotKey
import io.github.ragnorrs.mainbrain.contract.AnswerRequest
import io.github.ragnorrs.mainbrain.contract.Command

/**
 * What a message turns out to be asking for.
 *
 * Two kinds, because two things can happen to a message and only one of them is a question. What
 * this interface answers out of its own catalogue — the command list, and a command it does not
 * offer — is its own furniture, and the backend has never heard of any of it; everything else is a
 * request the backend answers.
 */
internal sealed interface Asked {

    /** A question, in the shape the backend takes one. */
    data class OfBackend(val request: AnswerRequest) : Asked

    /**
     * Something this interface answers out of its own catalogue, reaching nothing.
     *
     * [arguments] are what fills [key]'s own, in order — keys rather than text, because what they
     * resolve to is the reader's locale's business and nothing here holds one. Empty for a reply
     * that is a sentence on its own, which the command list is.
     */
    data class AtTheEdge(val key: BotKey, val arguments: List<BotKey> = emptyList()) : Asked
}

/**
 * Reads a message into either a request or a thing this interface answers itself.
 *
 * **An explicit command binds a shape directly and free text is classified**, which is the whole
 * of why the wire format carries the two separately: collapsing them would make a slash command
 * indistinguishable from somebody typing the same word.
 *
 * **A command this interface does not offer is refused here, and reaches nothing.** What it offers
 * is the commands declared on the wire and the two it answers itself, so a mistyped one is answered
 * from the catalogue with the command list — for no model call and no retrieval. Forwarded instead,
 * it binds the free-form shape, which spends an answering call at the costliest path, ranks the
 * corpus against text carrying a word that means nothing, and leaves the reader with a plausible
 * answer and no indication they mistyped. A friendlier reply is the argument for forwarding and it
 * rests on the pass-through being free, which it is not.
 *
 * **This is not an opinion about which shapes exist.** Which words are commands is [Command]'s to
 * state and the registry's to change; what happens here is a reading of that declaration. The
 * registry stays free to hold a shape no command list offers — the fallback is one — and such a
 * shape is reached by free text rather than by being typed.
 *
 * Over text, not over the platform's entity model. Every chat platform delivers a message as a
 * string, and a parser written against one platform's annotations would be a parser the next
 * interface cannot use — which is also why this is testable without a chat at all.
 */
internal object Commands {

    /**
     * What this interface answers itself, by the word that asks for it.
     *
     * The backend declares the commands it answers and has never heard of these, so this is the
     * other half of what a reader may type — and the half only this interface can list.
     *
     * `internal` for the tests that hold these words to the texts a reader sees — the resource
     * bundles cannot read this, so nothing else can. A command answered here but missing from the
     * help text is one nothing tells anybody about; so is one missing from the reply a mistyped
     * command gets. The visibility is for those checks rather than for the texts.
     */
    internal val atTheEdge = mapOf(
        "help" to BotKey.HELP,
        // The same answer as `/help`. A platform sends this when a conversation opens, and what
        // somebody needs at that moment is what the thing does — which is what `/help` says.
        "start" to BotKey.HELP,
    )

    /**
     * Read [text], which is whatever a person sent.
     *
     * The request carries the message as typed. The backend uses [AnswerRequest.command] to bind
     * the shape and [AnswerRequest.subjectName] to resolve the subject, so the text is what
     * generation reads rather than something parsing depends on — and passing it verbatim is what
     * keeps this from having to decide what part of a sentence was the question.
     */
    fun read(text: String): Asked {
        val trimmed = text.trim()
        val command = commandIn(trimmed)
        val edge = command?.let { atTheEdge[it] }
        return when {
            edge != null -> Asked.AtTheEdge(edge)

            // Free text, with no subject separated out. Finding a name in a sentence is work
            // nothing here can do well, and the classifier is what decides which shape this is.
            command == null -> Asked.OfBackend(AnswerRequest(text = trimmed))

            Command.named(command) == null -> Asked.AtTheEdge(
                key = BotKey.UNKNOWN_COMMAND,
                // The command list, named rather than written into the entry a second time. It
                // already covers both halves of what a reader may type and is already held to the
                // declaration, so there is nothing here that can fall out of step with it.
                arguments = listOf(BotKey.HELP),
            )

            else -> Asked.OfBackend(
                AnswerRequest(
                    text = trimmed,
                    command = command,
                    // Absent rather than empty, which is a request the backend reads as *about no
                    // particular subject* — and for the shape that accepts the inbox, that is the
                    // daily question rather than a missing argument.
                    subjectName = argumentIn(trimmed),
                ),
            )
        }
    }

    /**
     * The command word, or null when this is not a command at all.
     *
     * A prefix with no word after it counts as not one: passing it on would have the backend bind
     * a shape to the empty identifier.
     */
    private fun commandIn(trimmed: String): String? {
        if (!trimmed.startsWith(COMMAND_PREFIX)) return null
        // A platform may address a command to a particular recipient, which arrives attached to
        // the word. Stripped rather than left on, so the same command works wherever it is typed.
        val word = trimmed.drop(1)
            .substringBefore(' ')
            .substringBefore(RECIPIENT_SEPARATOR)
            .lowercase()
        return word.takeIf { it.isNotBlank() }
    }

    /** Whatever followed the command word, which is the name to resolve a subject from. */
    private fun argumentIn(trimmed: String): String? = trimmed.drop(1)
        .substringAfter(' ', missingDelimiterValue = "")
        .trim()
        .takeIf { it.isNotBlank() }

    private const val COMMAND_PREFIX = "/"

    private const val RECIPIENT_SEPARATOR = '@'
}
