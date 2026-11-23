package io.github.ragnorrs.mainbrain.bot.adapter

import com.sun.net.httpserver.HttpServer
import io.github.ragnorrs.mainbrain.bot.port.ChatRef
import io.github.ragnorrs.mainbrain.bot.port.Reply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * What the platform is really handed, read off the requests this adapter makes.
 *
 * **The one seam every other suite re-implements instead of exercising.** `SplittingTest` asks
 * what `messages` does with a body, and `LongReplyTest` and `LiveWirePathTest` both call it
 * themselves and then assert over the result — each of them a copy of the single line in [send]
 * that decides how a reply becomes messages. So the reply could stop being split, or the footer
 * stop being attached, and all three would stay green on their own arithmetic while a reader got
 * neither. That is not hypothetical: the formatting this card is about survived a green suite for
 * the same reason, every case reading something other than what was sent.
 *
 * Driven against a server of its own rather than the platform, which costs nothing and needs no
 * credential. What it records is the request body the library builds, so the assertions are over
 * the bytes that would have left the process.
 */
class TelegramAdapterSendTest {

    @Test
    fun `a long reply leaves as several messages, with the footer on the last of them`() {
        val sent = sending(Reply(body = aBodyLongerThanTheLimit(), footer = NOTE))

        assertTrue(sent.size > 1) {
            "The adapter sent ${sent.size} message(s) for a body over the platform's limit, so it " +
                "is not splitting at all and the platform would refuse what it sent."
        }
        sent.forEach { piece ->
            assertTrue(piece.length <= TELEGRAM_MESSAGE_LIMIT) {
                "The adapter sent a message of ${piece.length} characters, which the platform refuses."
            }
        }
        assertTrue(sent.last().endsWith(NOTE)) {
            "The note is not on the end of the last message: ${sent.last().takeLast(120)}"
        }
        assertEquals(1, sent.count { NOTE in it }) { "The note was sent more than once: $sent" }
        assertTrue(sent.none { it.trim() == NOTE }) {
            "A message is nothing but the note, which is the defect this card was filed for."
        }
    }

    @Test
    fun `a reply with no footer sends the body and nothing else`() {
        // The calibration for the case above: without it, an adapter appending the note to every
        // message would fail there and an adapter appending it to none would not be distinguished
        // from one attaching it correctly to a single-message reply.
        val sent = sending(Reply(body = "a short answer", footer = null))

        assertEquals(listOf("a short answer"), sent)
    }

    /** Drive [reply] through a real adapter pointed at a server of this test's own. */
    private fun sending(reply: Reply): List<String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = mutableListOf<String>()
        server.createContext("/") { exchange ->
            bodies += exchange.requestBody.use { it.readBytes().decodeToString() }
            val bytes = MESSAGE.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val adapter = TelegramAdapter(
                token = TOKEN,
                scope = CoroutineScope(Dispatchers.Unconfined),
                apiUrl = "http://127.0.0.1:${server.address.port}",
            )
            runBlocking { adapter.send(ChatRef("1"), reply) }
        } finally {
            server.stop(0)
        }
        return bodies.map { text(it) }
    }

    /**
     * The text one recorded request carries.
     *
     * Parsed out of the serialised request rather than read from a field, because what this case
     * is about is the bytes: the library decides the encoding and a test holding an opinion about
     * it would pass on a request the platform never saw.
     */
    private fun text(body: String): String {
        val opens = body.indexOf(FIELD)
        require(opens >= 0) { "No text in a request the adapter made: ${body.take(200)}" }
        // Walked rather than matched. A pattern for *a quote that is not escaped* backtracks over
        // a value this long until the stack gives out, which is the whole point of the case: the
        // body under test is four thousand characters.
        val said = StringBuilder()
        var index = opens + FIELD.length
        while (index < body.length && body[index] != '"') {
            if (body[index] != '\\') {
                said.append(body[index])
                index++
                continue
            }
            index++
            said.append(if (index < body.length && body[index] == 'n') '\n' else body[index])
            index++
        }
        return said.toString()
    }

    /** Six paragraphs of prose, comfortably past the platform's limit once joined. */
    private fun aBodyLongerThanTheLimit(): String =
        (1..60).joinToString("\n\n") { "paragraph $it, ${"filler ".repeat(10)}".trim() }

    private companion object {

        /** Not a credential: the library only needs a token-shaped string to build a path from. */
        private const val TOKEN = "1234567890:not-a-real-token"

        private const val NOTE = "the corpus is three hours behind"

        /** Where the text a request carries begins. */
        private const val FIELD = "\"text\":\""

        /**
         * The least a reply can be and still deserialise as a sent message.
         *
         * Enough for the library to return rather than throw; nothing here is asserted on, since
         * what this case reads is the request and not the answer to it.
         */
        private val MESSAGE = """
            {"ok":true,"result":{"message_id":1,"date":1760000000,
            "chat":{"id":1,"type":"private","first_name":"a reader"},"text":"sent"}}
        """.trimIndent().replace("\n", "")
    }
}
