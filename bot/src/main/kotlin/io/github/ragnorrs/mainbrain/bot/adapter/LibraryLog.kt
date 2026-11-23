package io.github.ragnorrs.mainbrain.bot.adapter

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.LogLevel
import org.slf4j.LoggerFactory

/**
 * The client library's own logging, with the credential taken out of it.
 *
 * **The Bot API puts the token in the request path**, so every message the library writes about a
 * failed call carries it — and a network error is the most ordinary thing that happens to a long
 * poll. Left alone, the container's log accumulates the credential in clear text, where anything
 * that can read a log can read the account: a copied log line, a mounted log file, a support
 * paste. The deployment is careful about which process holds this token and that care is undone by
 * printing it.
 *
 * So the library's logger is replaced rather than silenced wholesale: a platform this process cannot
 * reach is exactly what an operator needs to see, and the message is kept with the token removed
 * from it.
 *
 * **One line is an exception, and it is silenced rather than quietened.** An idle long poll — see
 * [isIdlePoll] — is routed to [Said.DETAIL], and this process ships `slf4j-simple` with no
 * configuration, so its default level is info and a debug line is never emitted at all. That is the
 * intended trade and not a demotion: the line arrives twice a minute for as long as nobody is
 * talking to the bot, and what it cannot be told apart from is a platform that accepts the
 * connection and says nothing. Every other failure, including one that cannot reach the platform,
 * still carries its own exception and still reports as an error.
 *
 * **Matched by shape rather than against the configured value.** A pattern catches a token this
 * process never held: one the library rebuilt, one from a second account, one differing by
 * surrounding whitespace. Comparing against the value read from the environment would miss all
 * three while looking thorough, and it would mean passing the credential to the logging code,
 * which is the opposite of what this is for.
 */
internal fun redactTokens(text: String): String = BOT_TOKEN.replace(text, REDACTED)

/**
 * Send the library's log through this process's own, redacted.
 *
 * Called once, before a client exists to log anything. The levels are mapped rather than passed
 * through because the library's own two extremes are not this process's: an assertion failure in a
 * chat client is an error here, and its tracing is detail nobody deploying this asked for.
 *
 * [sink] exists so that a test can assert what *reaches* the log rather than only what
 * [redactTokens] returns. The two are different claims, and the gap between them is the whole
 * defect: a correct redaction that was never installed leaves the credential in the log, and a
 * live log that happens to carry no failures looks identical to one that is being cleaned.
 */
internal fun routeLibraryLogs(sink: ((String) -> Unit)? = null) {
    KSLog.default = RedactingLog(sink)
}

private class RedactingLog(private val sink: ((String) -> Unit)?) : KSLog {

    override fun performLog(level: LogLevel, tag: String?, message: Any, throwable: Throwable?) {
        val said = redactTokens(message.toString())
        // Rendered to text and redacted, rather than handed over as a throwable. The token is in
        // the exception's own message as well as in the line about it, and an exception's message
        // cannot be rewritten — so what reaches the log is a string this function has cleaned.
        val failure = throwable?.let { redactTokens(it.stackTraceToString()) }
        val line = if (failure == null) said else "$said\n$failure"
        val named = tag?.let { "[$it] " }.orEmpty()
        if (sink != null) {
            sink.invoke(named + line)
            return
        }
        when (levelFor(level, line)) {
            Said.ERROR -> log.error("{}{}", named, line)
            Said.WARNING -> log.warn("{}{}", named, line)
            Said.INFO -> log.info("{}{}", named, line)
            Said.DETAIL -> log.debug("{}{}", named, line)
        }
    }

    private companion object {

        private val log = LoggerFactory.getLogger("io.github.ragnorrs.mainbrain.bot.telegram")
    }
}

/**
 * How loudly this process says what the library said.
 *
 * Its own four rather than the library's levels, because the mapping is not the identity: the
 * library's two extremes are one thing here — an assertion failure in a chat client is an error
 * — and its tracing is detail nobody deploying this asked for. A named result is also what lets a
 * test state which of the four a line reaches, rather than asserting on a log nothing can read.
 */
internal enum class Said { ERROR, WARNING, INFO, DETAIL }

/**
 * Which of [Said] a library line at [level] belongs at.
 *
 * The one judgement in this file beyond redaction, and the reason it is a function is that the
 * interesting case is not the level the library chose. See [isIdlePoll].
 */
internal fun levelFor(level: LogLevel, line: String): Said = when {
    isIdlePoll(line) -> Said.DETAIL
    level == LogLevel.ERROR || level == LogLevel.ASSERT -> Said.ERROR
    level == LogLevel.WARNING -> Said.WARNING
    level == LogLevel.INFO -> Said.INFO
    else -> Said.DETAIL
}

/**
 * Whether [line] is a long poll that waited and was told nothing, which is this interface at rest.
 *
 * **It is reported as an error and it is not one.** The client derives its request timeout from the
 * poll timeout it asked the platform to hold for, one for one, so a poll that nothing interrupts
 * expires on the client at the same instant the platform answers it — and the answer loses that
 * race every time. The library knows: it skips the exception and polls again, so no update is
 * missed. What it does not skip is the line its HTTP layer already wrote, which is a stack trace
 * twice a minute for as long as nobody is talking to the bot. A log that fills with the ordinary
 * case is one nothing unusual can be found in.
 *
 * **A platform that cannot be reached at all is not caught by this**, and that is the half worth
 * keeping. A refused connection, an address that does not resolve and a failure mid-request each
 * carry their own exception, under none of which this matches — so they still arrive as errors.
 * What is indistinguishable from an idle poll is a platform that accepts the connection and says
 * nothing, which is also what a working bot nobody is messaging looks like.
 *
 * Matched on shape for the reason [redactTokens] is: it is what the library hands over, and the
 * exception type together with the method the request was for is the whole of what identifies this.
 */
private fun isIdlePoll(line: String): Boolean =
    IDLE_POLL_FAILURE in line && UPDATES_METHOD in line

private const val IDLE_POLL_FAILURE = "HttpRequestTimeoutException"

private const val UPDATES_METHOD = "getUpdates"

/**
 * What a Bot API token looks like: an identifier, a colon, then a long opaque run.
 *
 * The length floor is what keeps this from matching an ordinary word pair that happens to contain
 * a colon. Deliberately not anchored, because the token appears inside a URL rather than alone.
 */
private val BOT_TOKEN = Regex("""bot\d{5,}:[A-Za-z0-9_-]{20,}""")

private const val REDACTED = "bot<token redacted>"
