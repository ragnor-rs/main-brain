package io.github.ragnorrs.mainbrain.bot.adapter

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Taking the credential out of what the client library writes.
 *
 * **This case is here because the live stack produced it.** The library logs the request URL on a
 * network failure, the Bot API puts the token in that URL, and a long poll fails often enough that
 * the container's log filled with the credential in clear text. Nothing in the unit suite could
 * have shown that — it took a deployed process and a real token.
 *
 * The token below is invented and has the shape of a real one, which is the whole of what the
 * pattern matches on.
 */
class LibraryLogTest {

    @Test
    fun `a token in a request url is removed`() {
        val said = redactTokens(
            "Request timeout has expired [url=https://api.telegram.org/bot$TOKEN/getUpdates, " +
                "request_timeout=30000 ms]",
        )

        assertFalse(said.contains(SECRET)) { said }
        assertFalse(said.contains(IDENTIFIER)) { said }
        // Only the credential goes: what the library was saying is kept whole, and how loudly this
        // process repeats it is [levelFor]'s question rather than this one's.
        assertTrue(said.contains("Request timeout has expired")) { said }
        assertTrue(said.contains("getUpdates")) { said }
    }

    @Test
    fun `an idle long poll is detail, not an error`() {
        // The client derives its request timeout from the poll timeout it asked the platform to hold
        // for, so a poll nothing interrupts expires on the client at the instant the platform answers
        // it. The library skips the exception and polls on, so nothing is missed — but its HTTP layer
        // has already written the line, which is a stack trace twice a minute for as long as nobody
        // is talking to the bot. Eighty-seven of them in three minutes on a live stack, with the one
        // thing worth seeing in a log being whatever is not the ordinary case.
        val idle = "Something web wrong\nio.ktor.client.plugins.HttpRequestTimeoutException: " +
            "Request timeout has expired [url=https://api.telegram.org/bot<token redacted>/getUpdates, " +
            "request_timeout=30000 ms]"

        assertEquals(Said.DETAIL, levelFor(LogLevel.ERROR, idle))
    }

    @Test
    fun `a platform that cannot be reached at all is still an error`() {
        // The half worth keeping. A refused connection and an address that does not resolve each
        // carry their own exception, so neither is caught by the idle-poll rule — and a timeout on
        // some other call is not an idle poll either.
        val unresolved = "Something web wrong\njava.net.UnknownHostException: api.telegram.org"
        val elsewhere = "io.ktor.client.plugins.HttpRequestTimeoutException: " +
            "Request timeout has expired [url=https://api.telegram.org/bot<token redacted>/sendMessage]"

        assertEquals(Said.ERROR, levelFor(LogLevel.ERROR, unresolved))
        assertEquals(Said.ERROR, levelFor(LogLevel.ERROR, elsewhere))
    }

    @Test
    fun `the library's own levels still decide everything else`() {
        assertEquals(Said.ERROR, levelFor(LogLevel.ASSERT, "an assertion failed"))
        assertEquals(Said.WARNING, levelFor(LogLevel.WARNING, "something odd"))
        assertEquals(Said.INFO, levelFor(LogLevel.INFO, "connected"))
        assertEquals(Said.DETAIL, levelFor(LogLevel.VERBOSE, "a prepared body"))
    }

    @Test
    fun `every occurrence goes, not only the first`() {
        val said = redactTokens("bot$TOKEN and again bot$TOKEN")

        assertFalse(said.contains(SECRET)) { said }
        assertEquals(2, Regex("token redacted").findAll(said).count())
    }

    @Test
    fun `a token this process never held is removed too`() {
        // Matched by shape rather than against the configured value, so one the library rebuilt
        // or one from another account is caught as well.
        val other = "bot999999999:ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ"
        assertFalse(redactTokens("something about $other").contains("ZZZZ"))
    }

    @Test
    fun `ordinary text with a colon in it is left alone`() {
        // The length floor is what keeps this from eating a word pair that happens to be punctuated
        // like a token.
        val ordinary = "connected: true, retries: 3, host: api.telegram.org"
        assertEquals(ordinary, redactTokens(ordinary))
    }

    @Test
    fun `a message with no token in it is unchanged`() {
        val ordinary = "Got exception answer for GetMe: unauthorized"
        assertEquals(ordinary, redactTokens(ordinary))
    }

    @Test
    fun `what reaches the log is redacted, not merely redactable`() {
        // The claim that matters, and a different one from the cases above: a correct redaction
        // that was never installed leaves the credential in the log. Asserted through the
        // library's own entry point, which is what the client writes to.
        val written = mutableListOf<String>()
        routeLibraryLogs { written += it }

        KSLog.default.performLog(
            LogLevel.ERROR,
            "KTgBot",
            "Something web wrong [url=https://api.telegram.org/bot$TOKEN/getUpdates]",
            null,
        )

        assertEquals(1, written.size)
        assertFalse(written.single().contains(SECRET)) { written.single() }
        assertTrue(written.single().contains("token redacted")) { written.single() }
        // The tag survives, because which part of the library spoke is worth keeping.
        assertTrue(written.single().contains("KTgBot")) { written.single() }
    }

    @Test
    fun `a token inside a thrown exception is redacted too`() {
        // The token is in the exception's own message as well as in the line about it, and an
        // exception's message cannot be rewritten — so the throwable is rendered and cleaned
        // rather than handed over.
        val written = mutableListOf<String>()
        routeLibraryLogs { written += it }

        KSLog.default.performLog(
            LogLevel.ERROR,
            "KTgBot",
            "failed",
            IllegalStateException("url=https://api.telegram.org/bot$TOKEN/getUpdates"),
        )

        assertFalse(written.single().contains(SECRET)) { written.single() }
        assertTrue(written.single().contains("token redacted")) { written.single() }
    }

    @Test
    fun `the adapter installs it, rather than leaving the library's own logger in place`() {
        // The remaining gap: a redaction that is correct and never installed leaves the credential
        // in the log. Asserted by identity rather than by capturing output, because what the
        // adapter installs writes to this process's log and not to a collector — and the two cases
        // above already say what it writes.
        routeLibraryLogs(null)
        val beforeAdapter = KSLog.default

        TelegramAdapter(token = "$TOKEN", scope = CoroutineScope(Dispatchers.Unconfined))

        assertNotSame(beforeAdapter, KSLog.default)
    }

    private companion object {

        /**
         * Invented, and shaped like the real thing — which is all the pattern reads.
         *
         * Deliberately not a real token, nor a redacted version of one. A fixture copied from a
         * log would put the credential in the repository, which is the thing this file exists to
         * prevent.
         */
        private const val IDENTIFIER = "1111111111"

        private const val SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        private const val TOKEN = "$IDENTIFIER:$SECRET"
    }
}
