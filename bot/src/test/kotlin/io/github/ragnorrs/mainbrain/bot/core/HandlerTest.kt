package io.github.ragnorrs.mainbrain.bot.core

import io.github.ragnorrs.mainbrain.bot.client.BackendClient
import io.github.ragnorrs.mainbrain.bot.client.Reached
import io.github.ragnorrs.mainbrain.bot.i18n.BotKey
import io.github.ragnorrs.mainbrain.bot.i18n.Catalogue
import io.github.ragnorrs.mainbrain.bot.port.ChatRef
import io.github.ragnorrs.mainbrain.bot.port.IncomingMessage
import io.github.ragnorrs.mainbrain.bot.port.MessagingPort
import io.github.ragnorrs.mainbrain.bot.port.Reply
import io.github.ragnorrs.mainbrain.bot.port.SenderId
import io.github.ragnorrs.mainbrain.contract.AnswerRequest
import io.github.ragnorrs.mainbrain.contract.AnswerResponse
import io.github.ragnorrs.mainbrain.contract.Block
import io.github.ragnorrs.mainbrain.contract.Command
import io.github.ragnorrs.mainbrain.contract.Freshness
import io.github.ragnorrs.mainbrain.contract.MenuOption
import io.github.ragnorrs.mainbrain.contract.MessageKey
import io.github.ragnorrs.mainbrain.contract.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset

/**
 * One message, from the edge to the replies.
 *
 * Against a scripted backend and a recording port, so the whole loop runs with no chat, no socket
 * and no model. The cases that matter most are the refusals — the sender the guard will not admit,
 * and the command this interface does not offer. What each asserts is not only what comes back but
 * that *nothing happened*: the backend was never called.
 */
class HandlerTest {

    @Test
    fun `an admitted message is asked of the backend and answered into its own chat`() {
        val backend = ScriptedBackend(Reached.Answered(answerOf(anAnswer)))
        val messaging = RecordingMessaging()
        runBlocking { handler(backend, messaging).handle(message("/status Acme")) }

        assertEquals(1, backend.asked.size)
        assertEquals("status", backend.asked.single().command)
        assertEquals("Acme", backend.asked.single().subjectName)
        assertEquals(listOf(CHAT), messaging.sent.map { it.first.value })
        assertEquals(listOf("an answer"), messaging.sent.map { it.second.body })
    }

    @Test
    fun `a message from a chat that is not admitted has no work done on its behalf`() {
        val backend = ScriptedBackend(Reached.Answered(answerOf(anAnswer)))
        val messaging = RecordingMessaging()
        runBlocking { handler(backend, messaging).handle(message("/status Acme", sender = "999")) }

        // The acceptance this exists for: not a refusal served late, but a request that never
        // happened. No backend call, and therefore no retrieval and no model call either.
        assertTrue(backend.asked.isEmpty()) { "The backend was asked ${backend.asked.size} time(s)." }
        // And no reply, because saying *you are not allowed* confirms to a stranger what they
        // found, and there is nobody worth telling.
        assertTrue(messaging.sent.isEmpty()) { "It answered: ${messaging.sent.map { it.second.body }}" }
    }

    @Test
    fun `a group conversation gets the same silence however familiar the sender`() {
        val backend = ScriptedBackend(Reached.Answered(answerOf(anAnswer)))
        val messaging = RecordingMessaging()
        runBlocking { handler(backend, messaging).handle(message("/status", directChat = false)) }

        assertTrue(backend.asked.isEmpty())
        assertTrue(messaging.sent.isEmpty())
    }

    @Test
    fun `the command list is answered without reaching the backend`() {
        val backend = ScriptedBackend(Reached.Answered(answerOf(anAnswer)))
        val messaging = RecordingMessaging()
        runBlocking { handler(backend, messaging).handle(message("/help")) }

        assertTrue(backend.asked.isEmpty()) { "The command list is this interface's own furniture." }
        assertEquals(catalogue.text(BotKey.HELP, null), messaging.sent.single().second.body)
    }

    @Test
    fun `an unknown command is answered here, in one message, and spends nothing`() {
        val backend = ScriptedBackend(Reached.Answered(answerOf(anAnswer)))
        val messaging = RecordingMessaging()
        runBlocking { handler(backend, messaging).handle(message("/stats Acme")) }

        // The acceptance this case exists for, and the half nothing else can assert: not that the
        // reply is right but that no request was made. Forwarded, this spends an answering model
        // call and a corpus read before anybody reads a word of the result.
        assertTrue(backend.asked.isEmpty()) {
            "The backend was asked ${backend.asked.size} time(s) for a command that does not exist."
        }
        // One message, because what a mistyped command is told and the list it is told to read are
        // one thing to say — and a second message carrying the list arrives as a fragment.
        assertEquals(1, messaging.sent.size) { "Delivered: ${messaging.sent.map { it.second.body }}" }
        assertEquals(
            catalogue.text(BotKey.UNKNOWN_COMMAND, null, listOf(BotKey.HELP)),
            messaging.sent.single().second.body,
        )
    }

    @Test
    fun `an unknown command's reply is said in the reader's locale and names every command`() {
        // Two things, and they are resolved separately on purpose. The frame comes back in the
        // reader's locale, which the partial bundle translates. The list does not, because that
        // bundle omits it — so what arrives is the floor's list under this locale's sentence,
        // which is the per-key fallback working rather than a fault. What must hold of the list
        // either way is that it names every command, whichever locale it landed in.
        //
        // That the argument resolves in the reader's locale where the locale *has* it is
        // `CatalogueTest`'s, which is the only place a bundle translating both exists.
        val backend = ScriptedBackend(Reached.Answered(answerOf(anAnswer)))
        val messaging = RecordingMessaging()
        val localised = Handler(
            guard = AuthGuard(setOf(SenderId(SENDER))),
            backend = backend,
            renderer = OutcomeRenderer(partial, Clock.systemUTC(), ZoneOffset.UTC),
            catalogue = partial,
            messaging = messaging,
        )
        runBlocking { localised.handle(message("/stats", locale = "xx")) }

        val delivered = messaging.sent.single().second.body
        assertTrue(delivered.startsWith(NO_SUCH_COMMAND)) {
            "The refusal was not said in the reader's locale. Delivered: $delivered"
        }
        val words = Command.entries.map { it.word } + Commands.atTheEdge.keys
        assertTrue(words.isNotEmpty()) {
            "No command is declared anywhere, so this iterates nothing and passes vacuously."
        }
        words.forEach { word ->
            assertTrue("/$word" in delivered) {
                "The reply to a mistyped command does not name /$word, which a reader may type. " +
                    "This is the one message somebody who has just got a command wrong reads, so " +
                    "a command missing from it is one they have no way to find. Delivered: $delivered"
            }
        }
    }

    @Test
    fun `no reply answered at the edge nests emphasis, across the seam no bundle check can see`() {
        // **The hazard is the composition, not the entry.** `markupFaults` refuses an entry that
        // nests, and it reads one entry at a time — so an entry filled with another entry nests at
        // a seam no check over the bundle is looking at. Both halves can be balanced and depth-one
        // on their own and the result be depth two: a frame writing its argument as emphasised,
        // filled with the command list, which sets off six commands of its own. The splitter
        // reserves room for one closing tag and refuses a piece that left more open, so such a
        // reply is delivered until one is long enough to cut and then not at all.
        //
        // The same rule `OutcomeRendererTest` holds over the shapes, at the other composition
        // seam. This one is reached without the renderer — the handler builds the reply from the
        // catalogue directly — so neither that case nor the bundle check covers it.
        //
        // Over every locale the fixtures offer, because an entry reaching a reader is whichever
        // one resolved for them, and a partial bundle translating only the frame is exactly the
        // configuration where a nesting frame would meet a list it did not write.
        listOf(catalogue, partial).forEach { loaded ->
            listOf(null, "xx", Catalogue.FLOOR).forEach { locale ->
                val backend = ScriptedBackend(Reached.Answered(answerOf(anAnswer)))
                val messaging = RecordingMessaging()
                val handler = Handler(
                    guard = AuthGuard(setOf(SenderId(SENDER))),
                    backend = backend,
                    renderer = OutcomeRenderer(loaded, Clock.systemUTC(), ZoneOffset.UTC),
                    catalogue = loaded,
                    messaging = messaging,
                )
                // Both edge-answered replies: the command list, and the one filled with it. Driven
                // through `Commands.read` rather than composed here, so what is checked is the
                // pairing the interface actually makes rather than one restated in a test.
                listOf("/help", "/not-a-command").forEach { typed ->
                    runBlocking { handler.handle(message(typed, locale = locale)) }
                }
                messaging.sent.map { it.second.body }.forEach { body ->
                    assertTrue(markupFaults(body).isEmpty()) {
                        "A reply answered at the edge carries markup the platform would not render " +
                            "as written: ${markupFaults(body)}. Body: $body"
                    }
                    assertEquals(0, nestingOf(body).second) {
                        "Emphasis does not balance in an edge-answered reply: $body"
                    }
                    assertTrue(nestingOf(body).first <= 1) {
                        "An edge-answered reply nests emphasis ${nestingOf(body).first} deep. Both " +
                            "entries can be depth-one alone and compose to this, which the bundle " +
                            "check cannot see. Body: $body"
                    }
                }
            }
        }
    }

    /**
     * How deep [body]'s emphasis nests, and what it leaves open — read the way the splitter counts.
     *
     * Over the whole vocabulary and not one tag, which is what makes that claim true: the splitter
     * balances everything [MARKUP] holds, so a counter reading only the first emphasis would score
     * one emphasis wrapped in the other as depth one and miss the fault entirely.
     */
    private fun nestingOf(body: String): Pair<Int, Int> {
        var depth = 0
        var deepest = 0
        var index = 0
        while (index < body.length) {
            val tag = tags.firstOrNull { body.startsWith(it, index) }
            when {
                tag == null -> index++

                tag in MARKUP -> {
                    depth++
                    deepest = maxOf(deepest, depth)
                    index += tag.length
                }

                else -> {
                    depth--
                    index += tag.length
                }
            }
        }
        return deepest to depth
    }

    /** Longest first, so a closer is never read as the opener it contains. */
    private val tags = (MARKUP.keys + MARKUP.values).sortedByDescending { it.length }

    @Test
    fun `a refusal from the backend is said rather than dropped`() {
        val backend = ScriptedBackend(Reached.Refused(MessageKey.CANNOT_ANSWER_RIGHT_NOW))
        val messaging = RecordingMessaging()
        runBlocking { handler(backend, messaging).handle(message("anything at all")) }

        assertEquals(
            catalogue.text(MessageKey.CANNOT_ANSWER_RIGHT_NOW, null),
            messaging.sent.single().second.body,
        )
    }

    /**
     * An overrun is said in this interface's own words, because the backend never says it.
     *
     * The client reports the fact and carries no key for it: from the far side the work was taken
     * and is being composed, so there is nothing for the wire format to say. What a reader is told
     * is therefore resolved here, out of this interface's own vocabulary.
     */
    @Test
    fun `an answer this interface stopped waiting for is said in its own words`() {
        val backend = ScriptedBackend(Reached.Overran)
        val messaging = RecordingMessaging()
        runBlocking { handler(backend, messaging).handle(message("anything at all")) }

        assertEquals(catalogue.text(BotKey.ANSWER_OVERRAN, null), messaging.sent.single().second.body)
    }

    /**
     * And the two are different messages, which is the whole of what this card is for.
     *
     * **Asserted on the rendered text and not on the keys.** Two keys resolving to one string is
     * exactly the failure the separation exists to prevent, and it is invisible to a case that
     * compares keys — a translator can reach it without touching a line of Kotlin. What a reader
     * sees is the only place the distinction is real.
     */
    @Test
    fun `an overrun and an unreachable backend do not read alike`() {
        val overran = RecordingMessaging()
        val unreachable = RecordingMessaging()
        runBlocking {
            handler(ScriptedBackend(Reached.Overran), overran).handle(message("anything at all"))
            handler(
                ScriptedBackend(Reached.Refused(MessageKey.CANNOT_ANSWER_RIGHT_NOW)),
                unreachable,
            ).handle(message("anything at all"))
        }

        val said = overran.sent.single().second.body
        val saidWhenDown = unreachable.sent.single().second.body
        assertTrue(said != saidWhenDown) {
            "Both failures render as the same text, so the reader cannot tell them apart: $said"
        }
    }

    @Test
    fun `several drafts are sent as several messages`() {
        val menu = Outcome.SendMenu(
            prompt = MessageKey.CHOOSE_A_DRAFT,
            options = listOf(MenuOption.Draft("one"), MenuOption.Draft("two")),
        )
        val backend = ScriptedBackend(Reached.Answered(answerOf(menu)))
        val messaging = RecordingMessaging()
        runBlocking { handler(backend, messaging).handle(message("/draft Acme")) }

        // The prompt and one message per draft, in order, all to the same chat.
        assertEquals(3, messaging.sent.size)
        assertTrue(messaging.sent.all { it.first.value == CHAT })
        assertTrue(messaging.sent[1].second.body.contains("one"))
        assertTrue(messaging.sent[2].second.body.contains("two"))
    }

    @Test
    fun `the reader's own locale decides the words`() {
        val backend = ScriptedBackend(Reached.Refused(MessageKey.CHOOSE_A_DRAFT))
        val messaging = RecordingMessaging()
        val localised = Handler(
            guard = AuthGuard(setOf(SenderId(SENDER))),
            backend = backend,
            renderer = OutcomeRenderer(partial, Clock.systemUTC(), ZoneOffset.UTC),
            catalogue = partial,
            messaging = messaging,
        )
        runBlocking { localised.handle(message("anything", locale = "xx")) }

        assertEquals("Pick one.", messaging.sent.single().second.body)
    }

    private val catalogue = Catalogue.loaded()

    private val partial = Catalogue.loaded(listOf(Catalogue.FLOOR, "xx"))

    private fun handler(backend: BackendClient, messaging: MessagingPort) = Handler(
        guard = AuthGuard(setOf(SenderId(SENDER))),
        backend = backend,
        // Whatever clock and whatever zone: nothing here answers with a corpus time, so neither
        // is read. That wording is `OutcomeRendererTest`'s, where both are fixed.
        renderer = OutcomeRenderer(catalogue, Clock.systemUTC(), ZoneOffset.UTC),
        catalogue = catalogue,
        messaging = messaging,
    )

    private fun message(
        text: String,
        sender: String = SENDER,
        locale: String? = null,
        directChat: Boolean = true,
    ) = IncomingMessage(
        sender = SenderId(sender),
        chat = ChatRef(CHAT),
        text = text,
        locale = locale,
        directChat = directChat,
    )

    private fun answerOf(outcome: Outcome) = AnswerResponse(outcome = outcome, freshness = Freshness())

    /** Whatever came back, for the cases that are about the loop rather than the rendering. */
    private val anAnswer = Outcome.SendText(listOf(Block.Prose("an answer")))

    /** Answers from a script, and records what it was asked. */
    private class ScriptedBackend(private val reached: Reached) : BackendClient {

        val asked = mutableListOf<AnswerRequest>()

        override suspend fun answer(request: AnswerRequest): Reached {
            asked += request
            return reached
        }
    }

    /** Keeps what was sent and where, which is the whole of what the port does. */
    private class RecordingMessaging : MessagingPort {

        val sent = mutableListOf<Pair<ChatRef, Reply>>()

        override suspend fun send(chat: ChatRef, reply: Reply) {
            sent += chat to reply
        }
    }

    private companion object {

        private const val SENDER = "421"

        private const val CHAT = "421"

        /**
         * How the partial test locale opens its unknown-command reply.
         *
         * In the fixture's own Latin, so that asserting on it keeps display text out of Kotlin —
         * the floor's wording is Russian and is read by key, never written. Named rather than
         * inlined so that what the assertion is about is the fixture's entry and not a literal
         * that happens to match it.
         */
        private const val NO_SUCH_COMMAND = "No such command."
    }
}
