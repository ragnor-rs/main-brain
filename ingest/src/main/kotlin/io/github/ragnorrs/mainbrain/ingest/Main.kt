package io.github.ragnorrs.mainbrain.ingest

import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import io.github.ragnorrs.mainbrain.ingest.config.IngestConfig
import io.github.ragnorrs.mainbrain.ingest.source.telegram.signInOnConsole
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The ingest process: read the configuration, then read the source until stopped.
 *
 * Three modes, and neither of the two that exit is a convenience. Signing in is the one thing this
 * source needs a human for: a code arrives in somebody's Telegram and a session file is a
 * logged-in account, so it is minted once, on the machine, out of band — never by the service,
 * which refuses every prompt. Asking for a re-read is the other, and it is here because this is
 * the route the architecture gives it: the scheduler is handed no way to trigger one, it is not
 * operable from the chat, and the backend would not rescue it. Every mode reads the same
 * configuration and the same session directory, so each leaves behind exactly what the others
 * need.
 *
 * The service is a container that stays up even when it cannot read anything. An expired session
 * is reported as needing a person and answered with nothing, because a process that exited on it
 * would take the report with it.
 */
fun main(args: Array<String>) {
    // Refused rather than ignored. These are things a person types by hand on a machine, from a
    // document, so the likely wrong argument is a mistyped one — and starting a long-running
    // service in answer to it would look like a command that silently did nothing.
    require(args.isEmpty() || args.first() == SIGN_IN && args.size == 1 || args.first() == REREAD && args.size <= 2) {
        "Unexpected arguments ${args.toList()}. `$SIGN_IN` signs in on the console and exits. " +
            "`$REREAD` asks for the whole source to be read again, and `$REREAD <unit>` for one " +
            "conversation; both record the request and exit. With no arguments, the process reads " +
            "the source until it is stopped."
    }
    val config = IngestConfig.fromEnv()
    if (args.firstOrNull() == SIGN_IN) {
        signInOnConsole(config.session)
        return
    }
    if (args.firstOrNull() == REREAD) {
        // The unit is the adapter's own name for a conversation, and an unknown one is refused
        // rather than accepted: the one thing that must not happen is a re-read reported as
        // requested and never performed.
        val unit = args.drop(1).singleOrNull()?.let(::WorkUnitId)
        IngestContainer.of(config).use { container -> runBlocking { container.reread(unit) } }
        return
    }
    val container = IngestContainer.of(config)
    runBlocking {
        val loop = launch { container.run() }
        // A hook rather than `use`, because this process is stopped by a signal: the virtual
        // machine ends on one without unwinding anything, so a `finally` would never run.
        //
        // It stops the loop before closing what the loop is using. Closing underneath it would
        // take the session out from under a request in flight and the pool out from under a
        // half-written batch — and the one thing that must happen on the way out is the client
        // library writing its own store, which holds the incremental position this process
        // deliberately keeps no copy of.
        Runtime.getRuntime().addShutdownHook(
            Thread {
                loop.cancel()
                runBlocking { loop.join() }
                container.close()
            },
        )
        loop.join()
    }
}

/**
 * The argument that signs in instead of running.
 *
 * A positional argument and not a variable: it is a thing a person does once, by hand, on the
 * machine — `login` beside the image's own entry point — and an environment variable that
 * switched a container between reading a source and asking for a telephone code would be a
 * deployment away from doing it unattended.
 */
private const val SIGN_IN = "login"

/**
 * The argument that asks for a re-read instead of running.
 *
 * Positional for the same reason, and the reason bites harder here: a re-read is the one operation
 * that spends request budget on material already captured, and a variable left set would spend it
 * again on every restart — which is the crash loop turning into a re-backfill loop that the
 * decision forbidding an automatic re-read exists to prevent.
 */
private const val REREAD = "reread"
