package io.github.ragnorrs.mainbrain.bot

import com.sun.net.httpserver.HttpServer
import io.github.ragnorrs.mainbrain.bot.adapter.TELEGRAM_MESSAGE_LIMIT
import io.github.ragnorrs.mainbrain.bot.adapter.messages
import io.github.ragnorrs.mainbrain.bot.client.HttpBackendClient
import io.github.ragnorrs.mainbrain.bot.config.BackendSettings
import io.github.ragnorrs.mainbrain.bot.core.AuthGuard
import io.github.ragnorrs.mainbrain.bot.core.Handler
import io.github.ragnorrs.mainbrain.bot.core.OutcomeRenderer
import io.github.ragnorrs.mainbrain.bot.i18n.BotKey
import io.github.ragnorrs.mainbrain.bot.i18n.Catalogue
import io.github.ragnorrs.mainbrain.bot.port.ChatRef
import io.github.ragnorrs.mainbrain.bot.port.IncomingMessage
import io.github.ragnorrs.mainbrain.bot.port.MessagingPort
import io.github.ragnorrs.mainbrain.bot.port.Reply
import io.github.ragnorrs.mainbrain.bot.port.SenderId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The whole bot-side path, over a real socket, on bytes the backend really serialised.
 *
 * **What this covers that nothing else does.** Every other case in this module hands the renderer
 * a `Outcome` it constructed in memory, so the wire is never crossed: the new part kinds are never
 * deserialised, the client is never asked, and a discriminator that did not match would show up
 * nowhere. Here the body is the one `ApiTest` captured from the real surface — six headings, six
 * prose parts, thirty-six claims — served by the JDK's own HTTP server, read by the **real**
 * `HttpBackendClient` at its deployed figures, rendered by the real renderer through the real
 * `Handler`, and cut by the real splitter at the platform's real limit. What the assertions read is
 * the list of strings `TelegramAdapter.send` would hand the platform.
 *
 * The one thing beyond it is the platform itself, which needs a deploy and an account.
 *
 * **The far side is a stub, and it has to be**: the backend's `api()` and this module's client are
 * internal to different modules, so no single test holds both real halves. What keeps the stub
 * honest is that its body was captured rather than written — see the capture case in `ApiTest`,
 * which fails if the shape it is capturing is not the shape under test.
 */
class LiveWirePathTest {

    @Test
    fun `a long sectioned answer reaches the platform formatted, in pieces that each carry content`() {
        val sent = deliver(wire())

        assertTrue(sent.size > 1) {
            "The reply did not split, so this says nothing about a long answer: ${sent.size} piece(s)"
        }
        // The marking survived the wire, which is the whole of this card's first half. Counted
        // against the body's own headings rather than merely found, so a single surviving one
        // cannot pass for all of them.
        val headings = headingsOnTheWire()
        assertTrue(headings.size >= 4) { "the captured body holds ${headings.size} headings" }
        headings.forEach { heading ->
            assertTrue(sent.any { it.contains("<b>$heading</b>") }) {
                "'$heading' crossed the wire marked and reached the platform unemphasised."
            }
        }
        // What the reply rests on, set back — one block for the whole of it however many claims
        // the body carried. Asserted per piece rather than as a total: a cut landing inside the
        // block closes the emphasis and reopens it on the next piece, so a total of two would be
        // the splitter working rather than a second block, and a fixture a few hundred characters
        // longer would red on correct behaviour. The calibration is the claim count above it — a
        // body carrying none would render no block and leave this holding vacuously.
        assertTrue(claimsOnTheWire() > 1) { "the body carries ${claimsOnTheWire()} claims" }
        assertTrue(sent.any { "<i>" in it }) { "nothing in the reply was set back: $sent" }
        sent.forEach { piece ->
            assertTrue(Regex("<i>").findAll(piece).count() <= 1) {
                "a piece carries more than one set-back run, so something other than the block " +
                    "the reply rests on was set back: $piece"
            }
        }
        sent.forEachIndexed { index, piece ->
            assertTrue(piece.length <= TELEGRAM_MESSAGE_LIMIT) { "piece ${index + 1} is ${piece.length}" }
            assertTrue(piece.replace(Regex("</?[bi]>"), "").isNotBlank()) {
                "piece ${index + 1} is markup with no text, which the platform refuses"
            }
            listOf("b", "i").forEach { tag ->
                assertEquals(
                    Regex("<$tag>").findAll(piece).count(),
                    Regex("</$tag>").findAll(piece).count(),
                ) { "piece ${index + 1} leaves <$tag> unbalanced, so the platform refuses it" }
            }
        }
    }

    @Test
    fun `the control - headings served as prose reach the platform unemphasised`() {
        // **Without this the case above proves only that something is bold.** The same body with
        // every part's kind rewritten to prose is what the old wire format could express, and it
        // must produce no emphasis at all — which is what establishes that the emphasis above came
        // from the marking rather than from anywhere else in the path.
        val flattened = wire().replace("\"type\":\"heading\"", "\"type\":\"prose\"")

        val sent = deliver(flattened)

        assertTrue(sent.isNotEmpty())
        assertEquals(0, sent.sumOf { Regex("<b>").findAll(it).count() }) {
            "a heading served as prose was emphasised anyway, so the case above discriminates nothing"
        }
        // The sources block is unaffected — the interface composes it itself — which is what says
        // the control changed only the one thing.
        assertTrue(sent.any { "<i>" in it }) { "the control lost the block the reply rests on: $sent" }
    }

    @Test
    fun `a corpus that is behind says so on the tail of the last piece, and a current one says nothing`() {
        val behind = wire().replace(
            "\"freshness\":{}",
            "\"freshness\":{\"completeThrough\":\"${NOW.minusSeconds(3 * 60 * 60)}\"}",
        )
        val catalogue = Catalogue.loaded()
        val note = catalogue.text(
            BotKey.CORPUS_COMPLETE_THROUGH,
            null,
            catalogue.text(BotKey.AGE_HOURS_FEW, null, "3"),
        )

        val lagging = deliver(behind)
        assertTrue(lagging.last().endsWith(note)) { "the note is not the tail of the last piece" }
        assertEquals(1, lagging.count { it.contains(note) })
        assertTrue(lagging.last().trim() != note) { "the last piece is nothing but the note" }

        // And current: the captured body carries an empty freshness, which is the commonest case
        // and the one the probes found a vacuous sentence on. Nothing *of the frame* may appear,
        // not merely this one filling of it — a sentence saying the material is current would
        // carry a different age and pass an assertion written against the whole note. The run in
        // front of the argument is what the frame is recognised by, read out of the catalogue
        // rather than spelled here, which is the rule for a line in a locale's own language.
        val opening = catalogue.text(BotKey.CORPUS_COMPLETE_THROUGH, null, MARKER).substringBefore(MARKER).trim()
        assertTrue(opening.isNotEmpty()) {
            "The freshness frame opens with its own argument, so there is no run to recognise it by."
        }
        val current = deliver(wire().replace("\"freshness\":{}", "\"freshness\":{\"completeThrough\":\"$NOW\"}"))
        assertEquals(0, current.count { it.contains(note) })
        assertTrue(current.none { it.contains(opening) }) {
            "something was said about a current corpus: ${current.last().takeLast(120)}"
        }
    }

    /**
     * Serve [body] on a real socket, ask through the real client, and return what the platform
     * would be handed.
     */
    private fun deliver(body: String): List<String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // **The two hops the client really makes**, which is the protocol rather than a detail: it
        // submits the question and is told what the work is called, then collects the answer by
        // that name. A stub serving the body at the submit route would never be read.
        server.createContext(ANSWER_ASYNC) { exchange ->
            val bytes = when (exchange.requestMethod) {
                "POST" -> """{"id":"$WORK_ID"}""".toByteArray()
                else -> body.toByteArray()
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (exchange.requestMethod == "POST") 202 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val catalogue = Catalogue.loaded()
            val port = Recording()
            val handler = Handler(
                guard = AuthGuard(setOf(SenderId(SENDER))),
                // The real client on a real socket, at the figures a deployment gets.
                backend = HttpBackendClient(
                    BackendSettings(url = "http://127.0.0.1:${server.address.port}", secret = "a-secret"),
                ),
                renderer = OutcomeRenderer(catalogue, Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC),
                catalogue = catalogue,
                messaging = port,
            )
            runBlocking {
                handler.handle(
                    IncomingMessage(
                        sender = SenderId(SENDER),
                        chat = ChatRef("1"),
                        text = "what was agreed",
                        locale = null,
                        directChat = true,
                    ),
                )
            }
            assertTrue(port.sent.isNotEmpty()) { "the handler sent nothing, so the client never answered" }
            // Exactly what the adapter does with each reply before it reaches the platform.
            return port.sent.flatMap { messages(it.body, it.footer, TELEGRAM_MESSAGE_LIMIT) }
        } finally {
            server.stop(0)
        }
    }

    /**
     * The committed body this case is driven by.
     *
     * On the classpath rather than at a path, so the case needs nothing to have run before it. It
     * is bytes the real surface serialised, and `:backend`'s `ApiTest` is what keeps it so: that
     * case produces the body again and fails naming this file if the two have parted.
     */
    private fun wire(): String =
        checkNotNull(javaClass.getResourceAsStream(WIRE)) {
            "No wire body at $WIRE on the test classpath. It is committed beside this module's " +
                "other test resources; `:backend`'s ApiTest is what keeps it current."
        }.use { it.readBytes().decodeToString() }

    /** The headings the captured body carries, read out of it rather than restated here. */
    private fun headingsOnTheWire(): List<String> =
        Regex("\\{\"type\":\"heading\",\"text\":\"([^\"]+)\"}").findAll(wire()).map { it.groupValues[1] }.toList()

    /** How many assertions the captured body carries, likewise read rather than restated. */
    private fun claimsOnTheWire(): Int = Regex("\"cites\":\\[").findAll(wire()).count()

    private class Recording : MessagingPort {
        val sent = mutableListOf<Reply>()
        override suspend fun send(chat: ChatRef, reply: Reply) {
            sent += reply
        }
    }

    private companion object {

        private const val WIRE = "/wire/long-sectioned-answer.json"

        private const val SENDER = "4815162342"

        /** The route both hops are on, which the client builds the collect path from. */
        private const val ANSWER_ASYNC = "/answer/async"

        /** What this stub calls the work, echoed back as the name to collect it by. */
        private const val WORK_ID = "a-piece-of-work"

        private val NOW: Instant = Instant.parse("2026-10-15T12:00:00Z")

        /**
         * Stands in for the age, so the run in front of it can be read off the filled sentence.
         *
         * A value no catalogue entry and no rendered reply can contain, which is what makes
         * splitting on it exact — the alternative is spelling the frame's own words here, which
         * is a locale's text in Kotlin source and goes stale on a rewording that broke nothing.
         */
        private const val MARKER = "\u0000"
    }
}
