package io.github.ragnorrs.mainbrain.bot

import io.github.ragnorrs.mainbrain.bot.config.BotConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The bot process: read the configuration, assemble the interface, receive messages.
 *
 * One mode. Nothing here needs a person at a console — the credential it is given is a bot token
 * from the environment, not a session somebody signs in to. That is the whole difference between
 * this process and ingestion, which reads the same platform as a user.
 *
 * **It connects out and publishes nothing.** Long polling means no inbound port, no public
 * address and no hole in the deployment for an interface to be reached through, which is why this
 * container exposes none.
 *
 * **Supervised, so one unanswerable message cannot take the interface down.** Each message is
 * handled in its own child of this scope; a failure escaping one is logged where it happened and
 * its siblings go on. The order on the way out is the one thing worth stating: the work stops,
 * then the client the work was using closes — the other way round would have a request in flight
 * fail on a closed client rather than finish.
 */
fun main() {
    val config = BotConfig.fromEnv()
    val work = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val container = BotContainer.of(config, work)
    // A hook rather than `use`, because this process is stopped by a signal: the virtual machine
    // ends on one without unwinding anything, so a `finally` would never run.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { work.coroutineContext.job.cancelAndJoin() }
            container.close()
        },
    )
    runBlocking {
        // Before any polling, so an unusable token is a process that stops and says so rather
        // than one that stays up logging refusals and answering nothing.
        container.identify()
        // Receiving runs inside the same scope as the handling it causes, so cancelling the scope
        // stops both — rather than leaving the poll alive over work already cancelled.
        work.launch { container.receive() }.join()
    }
}
