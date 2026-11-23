package io.github.ragnorrs.mainbrain.backend

import io.github.ragnorrs.mainbrain.backend.config.BackendConfig
import io.github.ragnorrs.mainbrain.backend.config.PORT
import io.github.ragnorrs.mainbrain.backend.presentation.api
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The backend process: read the configuration, open the store, start the passes, serve the
 * interface.
 *
 * One mode, unlike ingestion. Nothing here needs a person at a console: the credential it is
 * given is a shared secret from the environment rather than a session somebody signs in to.
 *
 * **Two things run at once, and the server owns the thread.** The derived passes are launched
 * into a scope of their own before the server takes the main thread, which is what makes bringing
 * the stack up enough for a captured message to become retrievable — nothing has to be called by
 * hand. A scope rather than a coroutine per pass, because what the shutdown needs is one handle
 * to cancel; the driver launches one child per pass inside it.
 *
 * The server owns the shutdown too. Its grace period is what lets a request in flight finish
 * rather than being cut off mid-answer, which matters because an answer asked for in one request
 * can be a model call several seconds long. **An answer asked for separately is not covered by it
 * and is not meant to be**: that work is deliberately parented to the application, so a stop
 * cancels it outright rather than holding the process open for however long the longest shape
 * runs. What the caller sees then is the same thing it sees for any other restart — a name this
 * process no longer knows, answered as a wait. The order on the way out is the one thing here
 * worth stating: the surface
 * stops, then the passes, then the store — a pass is mid-batch when the signal arrives, and
 * closing the pool underneath it would turn an orderly stop into a failed write. Stopping it
 * first instead costs the rest of that batch and loses nothing: every pass writes its output
 * before marking it, so the next start re-derives the batch that did not finish.
 */
fun main() {
    val config = BackendConfig.fromEnv()
    val container = BackendContainer.of(config)
    val server = embeddedServer(Netty, port = PORT) {
        api(
            secret = config.secret,
            runner = container.runner,
            serving = container::serving,
            samplingFloor = container.samplingFloor,
            unresolved = container::unresolved,
        )
    }
    // Supervised so that a failure escaping the driver cannot cancel the server with it. Keeping
    // the two passes out of each other's way is the driver's own business and is done inside it,
    // where the siblings are: this scope has one child and could not tell them apart.
    val sweeps = CoroutineScope(Dispatchers.Default + SupervisorJob())
    sweeps.launch { container.passes.run() }
    // A hook rather than `use`, because this process is stopped by a signal: the virtual machine
    // ends on one without unwinding anything, so a `finally` would never run.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS)
            // Joined rather than merely cancelled, and before the store closes. A cancelled sweep
            // is still unwinding — releasing its advisory lock on the connection it pinned — and
            // a pool closed underneath that would leave the release to the session's own death
            // instead of to this process.
            runBlocking { sweeps.coroutineContext.job.cancelAndJoin() }
            container.close()
        },
    )
    server.start(wait = true)
}

/** How long a request already in flight is given to finish. */
private const val STOP_GRACE_MILLIS = 5_000L

/** And how long the whole stop may take before it is abandoned. */
private const val STOP_TIMEOUT_MILLIS = 10_000L
