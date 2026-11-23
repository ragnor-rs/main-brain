package io.github.ragnorrs.mainbrain.bot.core

import io.github.ragnorrs.mainbrain.bot.port.ChatRef
import io.github.ragnorrs.mainbrain.bot.port.IncomingMessage
import io.github.ragnorrs.mainbrain.bot.port.SenderId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/** Who gets work done on their behalf, and the two ways of not being that person. */
class AuthGuardTest {

    @Test
    fun `an admitted sender in their own chat is served`() {
        assertTrue(guard().admits(message()))
    }

    @Test
    fun `a sender who is not on the list is refused`() {
        assertFalse(guard().admits(message(sender = "999")))
    }

    @Test
    fun `an admitted sender in a group conversation is refused`() {
        // The one case that looks safe: somebody who is allowed, writing from a room. Answering
        // there would show one person's archive to whoever else is in it.
        assertFalse(guard().admits(message(directChat = false)))
    }

    @Test
    fun `an identifier is compared exactly rather than loosely`() {
        // The allowlist is parsed into normalised strings, so nothing here trims or re-reads a
        // number. A sender whose identifier merely starts the same way is a different person.
        assertFalse(guard().admits(message(sender = "4211")))
        assertFalse(guard().admits(message(sender = " 421")))
    }

    @Test
    fun `a guard admitting nobody is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { AuthGuard(emptySet()) }
    }

    private fun guard() = AuthGuard(setOf(SenderId("421"), SenderId("7")))

    private fun message(
        sender: String = "421",
        directChat: Boolean = true,
    ) = IncomingMessage(
        sender = SenderId(sender),
        chat = ChatRef("421"),
        text = "anything",
        locale = null,
        directChat = directChat,
    )
}
