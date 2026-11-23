package io.github.ragnorrs.mainbrain.bot.core

import io.github.ragnorrs.mainbrain.bot.i18n.BotKey
import io.github.ragnorrs.mainbrain.contract.Command
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * What a message turns out to be asking for.
 *
 * The commands asserted here are read off [Command], the backend's own declaration, so a rename on
 * that side fails here rather than surfacing as a command silently refused at the edge.
 *
 * **What this holds in both directions is the accepted set.** Every declared command is forwarded,
 * and a leading slash-word that is neither declared nor answered here is refused — which together
 * say that what this interface accepts is exactly what it offers. One half alone is not worth
 * having: forwarding everything is the defect, and refusing everything would be it inverted.
 */
class CommandsTest {

    @Test
    fun `a command binds a shape and its argument becomes the subject`() {
        val asked = assertIs<Asked.OfBackend>(Commands.read("/status Acme"))
        assertEquals("status", asked.request.command)
        assertEquals("Acme", asked.request.subjectName)
        // The text is the message as typed. The backend binds on the command and resolves on the
        // name, so nothing downstream depends on this having been carved up.
        assertEquals("/status Acme", asked.request.text)
    }

    @Test
    fun `a subject of several words arrives whole`() {
        val asked = assertIs<Asked.OfBackend>(Commands.read("/draft Acme Holdings Limited"))
        assertEquals("draft", asked.request.command)
        assertEquals("Acme Holdings Limited", asked.request.subjectName)
    }

    @Test
    fun `a command with no argument carries no subject`() {
        // Absent rather than empty, which the backend reads as *about no particular subject* —
        // and for the shape that accepts the inbox, that is the daily question rather than a
        // missing argument.
        val asked = assertIs<Asked.OfBackend>(Commands.read("/status"))
        assertEquals("status", asked.request.command)
        assertNull(asked.request.subjectName)
    }

    @Test
    fun `every command the backend declares is forwarded to it`() {
        assertFalse(Command.entries.isEmpty(), "This asserts nothing unless some command is declared.")
        // Read off the declaration rather than listed here, which is the half that would otherwise
        // rot: with the words written out, a command declared and never added to this list would
        // be refused at the edge and nothing would say so — the defect this card fixes, reached
        // from the other side.
        Command.entries.forEach { command ->
            val asked = assertIs<Asked.OfBackend>(
                Commands.read("/${command.word}"),
                "/${command.word} is declared on the wire and is not forwarded to the backend.",
            )
            assertEquals(command.word, asked.request.command)
        }
    }

    @Test
    fun `a command addressed to a recipient is still the command`() {
        val asked = assertIs<Asked.OfBackend>(Commands.read("/status@some_bot Acme"))
        assertEquals("status", asked.request.command)
        assertEquals("Acme", asked.request.subjectName)
    }

    @Test
    fun `a command is read without regard to case`() {
        val asked = assertIs<Asked.OfBackend>(Commands.read("/STATUS Acme"))
        assertEquals("status", asked.request.command)
    }

    @Test
    fun `a command this interface does not offer is refused with the command list`() {
        // Forwarded, this binds the free-form shape: an answering model call at the costliest
        // path, a corpus ranked against text carrying a word that means nothing, and a composed
        // reply that tells the reader nothing about the mistake.
        val asked = assertIs<Asked.AtTheEdge>(Commands.read("/stats Acme"))
        assertEquals(BotKey.UNKNOWN_COMMAND, asked.key)
        // The list, named rather than restated. What the reader is sent to is the one text that
        // already covers both halves of what they may type.
        assertEquals(listOf(BotKey.HELP), asked.arguments)
    }

    @Test
    fun `an unknown command is refused with or without an argument`() {
        // Both, because the two reach the fallback differently: with an argument there is a
        // subject to compose an answer about, and the reply reads as an answer to a question
        // nobody asked; without one there is not, and the reply reads as a refusal for the wrong
        // reason. Neither says a command was mistyped.
        listOf("/stats", "/stats Acme", "/stats@some_bot", "/stats@some_bot Acme", "/STATS Acme").forEach { typed ->
            assertEquals(
                BotKey.UNKNOWN_COMMAND,
                assertIs<Asked.AtTheEdge>(Commands.read(typed), "$typed was not refused.").key,
                "$typed was not answered as an unknown command.",
            )
        }
    }

    @Test
    fun `the fallback is not offered as a command, though the registry holds it`() {
        // The one word that names a shape this build answers and that no command list offers.
        // Typing it asks for the shape a reader already gets when nothing else matched, so it is
        // refused like any other word the list does not carry — and refused here rather than being
        // allowed through on the grounds that something would answer it, which is what would make
        // the accepted set differ from the offered one.
        assertEquals(
            BotKey.UNKNOWN_COMMAND,
            assertIs<Asked.AtTheEdge>(Commands.read("/ask what happened")).key,
        )
    }

    @Test
    fun `text that merely contains a slash is a question like any other`() {
        // The slash has to lead for this to be a command attempt. A question carrying one inside
        // it is prose, and refusing it would take away the ordinary way of asking anything.
        listOf(
            "what did we agree on 12/05",
            "is the rate per unit or per km/h",
            "Acme/Beta merger, where did we leave it",
        ).forEach { typed ->
            val asked = assertIs<Asked.OfBackend>(Commands.read(typed), "$typed was not answered as a question.")
            assertNull(asked.request.command, "$typed was read as a command.")
            assertEquals(typed, asked.request.text)
        }
    }

    @Test
    fun `free text carries no command and no subject`() {
        val asked = assertIs<Asked.OfBackend>(Commands.read("what did we agree with Acme"))
        assertNull(asked.request.command)
        // Finding a name inside a sentence is not attempted. The classifier is what decides which
        // shape this is, and a wrongly guessed name would scope an answer to the wrong subject.
        assertNull(asked.request.subjectName)
        assertEquals("what did we agree with Acme", asked.request.text)
    }

    @Test
    fun `help and start are answered here rather than sent on`() {
        assertEquals(BotKey.HELP, assertIs<Asked.AtTheEdge>(Commands.read("/help")).key)
        assertEquals(BotKey.HELP, assertIs<Asked.AtTheEdge>(Commands.read("/start")).key)
    }

    @Test
    fun `a prefix with no word after it is text rather than a command`() {
        val asked = assertIs<Asked.OfBackend>(Commands.read("/"))
        // An empty identifier would have the backend bind a shape to the empty string.
        assertNull(asked.request.command)
    }

    @Test
    fun `surrounding space changes nothing`() {
        val asked = assertIs<Asked.OfBackend>(Commands.read("   /status   Acme   "))
        assertEquals("status", asked.request.command)
        assertEquals("Acme", asked.request.subjectName)
    }
}
