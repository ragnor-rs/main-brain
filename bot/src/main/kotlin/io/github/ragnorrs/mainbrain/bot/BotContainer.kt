package io.github.ragnorrs.mainbrain.bot

import io.github.ragnorrs.mainbrain.bot.adapter.TelegramAdapter
import io.github.ragnorrs.mainbrain.bot.client.HttpBackendClient
import io.github.ragnorrs.mainbrain.bot.config.BotConfig
import io.github.ragnorrs.mainbrain.bot.core.AuthGuard
import io.github.ragnorrs.mainbrain.bot.core.Handler
import io.github.ragnorrs.mainbrain.bot.core.OutcomeRenderer
import io.github.ragnorrs.mainbrain.bot.i18n.Catalogue
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * What this process is made of, wired in one place by hand.
 *
 * The only file here that knows every part. One of them per process, because a second would be a
 * second answer to what this process is — a rule the build asserts rather than a convention.
 *
 * **The catalogue is built first, and a hole in it stops the process here.** Every key must
 * resolve in the floor locale, which is checked as it loads; so a missing translation is a startup
 * failure naming what is missing, rather than a raw code in somebody's chat weeks later. This is
 * the right place for it to bite: before the interface is listening.
 *
 * **One catalogue for two readers**, the renderer and the handler. The renderer resolves what the
 * backend said; the handler resolves what this interface says on its own account. One instance
 * because the bundles are read once, and two would be two copies of the same files.
 */
internal class BotContainer private constructor(
    private val backend: HttpBackendClient,
    private val adapter: TelegramAdapter,
    private val handler: Handler,
) : AutoCloseable {

    /**
     * Prove the token works, and say which bot this is.
     *
     * Separate from [receive] and called before it, so that an unusable token stops the process
     * rather than being logged inside a polling loop that carries on — a container that is up,
     * reports nothing wrong and answers no message is the state hardest to tell from a working
     * one. The name is logged because a deployment pointed at the wrong bot is otherwise
     * indistinguishable from one pointed at the right bot that nobody is messaging.
     */
    suspend fun identify() = log.info("Connected to Telegram as @{}.", adapter.identify())

    /**
     * Receive messages until the scope that owns this is cancelled.
     *
     * Held here rather than started in a constructor: a container says what a process is made of,
     * and a loop started from a constructor would run before anything could decide whether it
     * should.
     */
    suspend fun receive() = adapter.receive(handler::handle)

    override fun close() = backend.close()

    companion object {

        private val log = LoggerFactory.getLogger(BotContainer::class.java)

        /**
         * Assemble what this process is, given what it was told.
         *
         * [scope] is where a message being handled lives — one coroutine each, so an answer
         * waiting on a model call does not hold the next question behind it. Owned by the caller
         * rather than made here, because whoever can cancel it is whoever stops the process.
         */
        fun of(config: BotConfig, scope: CoroutineScope): BotContainer {
            val catalogue = Catalogue.loaded()
            val backend = HttpBackendClient(config.backend)
            val adapter = TelegramAdapter(config.telegram.token, scope)
            // The count rather than the identifiers. Whoever reads this wants to know the list was
            // not empty — which configuration already refuses — and a log carrying the
            // identifiers of whoever may use the assistant is a log carrying its allowlist.
            log.info(
                "The interface is configured for {} admitted sender(s), reaching the backend at {}.",
                config.telegram.allowed.size,
                config.backend.url,
            )
            return BotContainer(
                backend = backend,
                adapter = adapter,
                handler = Handler(
                    guard = AuthGuard(config.telegram.allowed),
                    backend = backend,
                    renderer = OutcomeRenderer(catalogue, Clock.systemUTC(), config.zone),
                    catalogue = catalogue,
                    messaging = adapter,
                ),
            )
        }
    }
}
