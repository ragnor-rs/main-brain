package io.github.ragnorrs.mainbrain.bot.client

import io.github.ragnorrs.mainbrain.bot.config.BackendSettings
import io.github.ragnorrs.mainbrain.contract.AnswerAccepted
import io.github.ragnorrs.mainbrain.contract.AnswerRequest
import io.github.ragnorrs.mainbrain.contract.Failure
import io.github.ragnorrs.mainbrain.contract.MessageKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * The backend over HTTP, which is the only thing in this process that reaches a network.
 *
 * The shared secret on every request is what says this is the interface that service serves. The
 * engine is a parameter so that the status-to-key mapping below is testable from a script instead
 * of a socket, and therefore in CI.
 *
 * [interval] and [budget] are parameters for the same reason, and only that reason. Collecting an
 * answer is a wait, so a suite driving it at the deployed figures would either sleep for the real
 * period on every case or never reach the end of the budget at all — which is where two of the
 * behaviours worth asserting live. Nothing configures these; a deployment gets the defaults.
 */
internal class HttpBackendClient(
    private val settings: BackendSettings,
    engine: HttpClientEngine = OkHttp.create(),
    private val interval: Long = POLL_INTERVAL_MILLIS,
    private val budget: Long = ANSWER_BUDGET_MILLIS,
) : BackendClient, AutoCloseable {

    private val http = HttpClient(engine) {
        // Failures are this client's own business, so a non-2xx is a response to read rather than
        // an exception to catch. The default would throw on exactly the statuses whose bodies
        // carry the key that has to be rendered.
        expectSuccess = false
        install(ContentNegotiation) {
            // The same two settings the surface configures on its side. Unknown keys are ignored
            // so that a backend serving a newer contract does not take this interface down, and
            // absent nulls are what that surface actually writes.
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
        install(HttpTimeout) {
            // Seconds, not minutes, because no hop here waits for an answer to be composed: one
            // asks and one collects, and both return as soon as the backend has read a map. That
            // is what lets the bound serve its original purpose properly — a backend which has
            // stopped answering is noticed within a hop instead of holding a coroutine for as long
            // as the longest answer this build can produce. How long the work itself may take is
            // [budget], which bounds waiting rather than a request.
            requestTimeoutMillis = HOP_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = HOP_TIMEOUT_MILLIS
        }
    }

    /**
     * Ask, then keep coming back until there is something to say.
     *
     * **Two hops rather than one, because one shape runs for minutes.** The shape that searches the
     * open web is a tool loop on the provider's side, and a single request held open for it has to
     * be given a ceiling wide enough to cover the longest one — which is then also how long a dead
     * backend may hold this process. Asking and collecting separately removes the choice: the work
     * is bounded by [budget] and each hop by [HOP_TIMEOUT_MILLIS], so waiting a long
     * time for an answer and waiting on nothing no longer look alike.
     *
     * It also means this interface needs no opinion about which shape will answer. The shape is
     * settled on the far side — a command binds one directly, free text is classified — so a
     * ceiling chosen here per command would be wrong for exactly the requests that are routed
     * rather than commanded.
     */
    override suspend fun answer(request: AnswerRequest): Reached {
        val accepted = submit(request) ?: return Reached.Refused(MessageKey.CANNOT_ANSWER_RIGHT_NOW)
        return when (accepted) {
            is Submitted.Refused -> Reached.Refused(accepted.key)
            is Submitted.Accepted -> collect(accepted.id)
        }
    }

    /** What came of asking, before anything has been composed. */
    private sealed interface Submitted {

        /** The backend took the work, under this name. */
        data class Accepted(val id: String) : Submitted

        /** It would not start, and this is what to say about that. */
        data class Refused(val key: MessageKey) : Submitted
    }

    /**
     * Hand the request over and come back with the name of the work, or null if asking failed.
     *
     * Null is kept apart from [Submitted.Refused] because the two are different facts: a refusal is
     * the backend answering, and null is it not being there to answer. Null renders as the backend
     * being unreachable, which is what it is; a refusal renders whatever key it carried.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun submit(request: AnswerRequest): Submitted? = try {
        val response = http.post("${settings.url.trimEnd('/')}$ANSWER_ASYNC") {
            header(SECRET_HEADER, settings.secret)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status == HttpStatusCode.Accepted) {
            Submitted.Accepted(response.body<AnswerAccepted>().id)
        } else {
            Submitted.Refused(keyOf(response))
        }
    } catch (failure: Exception) {
        // The backend could not be reached at all — it is starting, it is down, the network is not
        // there. What a reader is told about that is the caller's to choose, which is why this
        // reports the fact and returns no key; see the KDoc above for the distinction it keeps.
        log.warn("The backend did not take this request, so it cannot be served.", failure)
        null
    }

    /**
     * Come back for the work named [id] until it is ready, the backend refuses, or waiting runs out.
     *
     * **A hop that fails is retried, a few times, and then is not.** A single blip must not throw
     * away an answer that exists and is nearly finished, which is the whole point of this route. But
     * a backend that has gone away answers nothing, and retrying it until the budget expired would
     * reintroduce the long hold this design removes — so the allowance is small and consecutive, and
     * any hop that succeeds clears it.
     *
     * **[Reached.Overran] is returned only where every hop landed**, which is the condition that
     * message is true under. A blip therefore costs a true overrun its own wording and not the
     * other way round; the reasoning for that direction is where the two are chosen between.
     */
    private suspend fun collect(id: String): Reached {
        val giveUpAt = System.nanoTime() + budget * NANOS_PER_MILLI
        var consecutiveFailures = 0
        var hopsThatFailed = 0
        while (System.nanoTime() < giveUpAt) {
            when (val collected = collectOnce(id)) {
                is Collected.Ready -> return collected.reached
                Collected.Waiting -> consecutiveFailures = 0
                Collected.HopFailed -> {
                    consecutiveFailures++
                    hopsThatFailed++
                }
            }
            if (consecutiveFailures > HOP_FAILURES_ALLOWED) break
            // Asked before waiting, not after. Four of the five shapes compose in under a second,
            // and sleeping first would put the whole interval in front of every one of them —
            // turning a change made for the slow shape into a delay the fast ones pay visibly.
            delay(interval)
        }
        // **Which bound ran out is what an operator needs; whether every hop landed is what the
        // reader needs. They are different questions and this answers them separately.**
        //
        // The allowance running out is a backend that has gone away, and the clock running out
        // while every hop was answered is an answer that overran a healthy service. Only the
        // second may be told as an overrun, because that message asserts the service was there
        // and the work was still being composed, and it tells the asker not to come back — which
        // is sound for an answer that will overrun again and wrong for a service that is down.
        //
        // **The consecutive count cannot carry that, which is why there are two of them.** Any
        // answered hop resets it, so a backend flapping below the allowance, or dying with less
        // than the allowance left on the clock, leaves the loop on the clock with it low. Keying
        // the reply on it would tell a reader whose backend had just died that the service was
        // fine and that coming back would not help — this card's own defect with the polarity
        // reversed, which is worse than the generic refusal rather than better. So any hop that
        // did not land falls back to that refusal: being vague about a cause costs the asker a
        // wasted retry, and being wrong about it costs them the one action that would have worked.
        if (consecutiveFailures > HOP_FAILURES_ALLOWED) {
            log.warn(
                "Gave up collecting answer {} after {} hop(s) that did not land, {} of them in a row.",
                id,
                hopsThatFailed,
                consecutiveFailures,
            )
        } else {
            log.warn(
                "Answer {} was not ready within {} ms, with {} hop(s) that did not land.",
                id,
                budget,
                hopsThatFailed,
            )
        }
        return if (hopsThatFailed > 0) Reached.Refused(MessageKey.CANNOT_ANSWER_RIGHT_NOW) else Reached.Overran
    }

    /** What one attempt at collecting came back with. */
    private sealed interface Collected {

        /** There is something to say, whichever way it went. */
        data class Ready(val reached: Reached) : Collected

        /** The work is not finished, which is neither something to say nor a failure. */
        data object Waiting : Collected

        /** The hop itself did not land, which says nothing about the work. */
        data object HopFailed : Collected
    }

    /** One attempt at collecting the work named [id]. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun collectOnce(id: String): Collected = try {
        val response = http.get("${settings.url.trimEnd('/')}$ANSWER_ASYNC/$id") {
            header(SECRET_HEADER, settings.secret)
        }
        when (response.status) {
            HttpStatusCode.OK -> Collected.Ready(Reached.Answered(response.body()))
            HttpStatusCode.Accepted -> Collected.Waiting
            else -> Collected.Ready(Reached.Refused(keyOf(response)))
        }
    } catch (failure: Exception) {
        rethrowIfCancelled(failure)
        log.warn("A hop collecting answer {} did not land.", id, failure)
        Collected.HopFailed
    }

    /**
     * The key a refusal carries.
     *
     * Read from the body, because the surface puts one in every failure it serves. A body that
     * does not parse is something other than that surface answering — a proxy, a gateway, an error
     * page — and there is no key in it to read, so this says so rather than guessing which
     * refusal it was.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun keyOf(response: HttpResponse): MessageKey = try {
        response.body<Failure>().key
    } catch (failure: Exception) {
        log.warn("The backend answered {} with a body this client could not read.", response.status, failure)
        MessageKey.UNEXPECTED_FAILURE
    }

    override fun close() = http.close()

    private companion object {

        private val log = LoggerFactory.getLogger(HttpBackendClient::class.java)

        /** Where this interface asks, and where it collects, named as the surface names them. */
        private const val ANSWER_ASYNC = "/answer/async"

        /** And how it proves it is the interface that surface serves. */
        private const val SECRET_HEADER = "X-Main-Brain-Secret"

        /**
         * How long one hop may take.
         *
         * Neither hop waits for an answer to be composed — one files the work and one reads whether
         * it is done — so this is sized for a network and a map lookup, with room for a backend
         * busy serving others. It is what notices a backend that has stopped answering, which is
         * the job the old single ceiling was doing at six times the length.
         */
        private const val HOP_TIMEOUT_MILLIS = 30_000L

        private const val CONNECT_TIMEOUT_MILLIS = 10_000L

        /**
         * How long an answer may take before this interface stops waiting for it.
         *
         * **Matched to the figure the evaluation set already derived**, which reads the same answer
         * path and settled on fifteen minutes after measuring a research answer cut off at five.
         * Deliberately the same number rather than an independent guess: both are this build
         * waiting on work it asked for, and two different ceilings on one shape would mean a
         * question the gate scores as answered is one the interface cannot deliver.
         *
         * **What it does and does not cover, since the arithmetic is the point.** The longest
         * research answer on record ran six and a half minutes, and a re-roll on a reply that could
         * not be read repeats the generation — so a question like that one, re-rolled, is about
         * thirteen minutes and fits, with the margin a good deal thinner than the figure suggests.
         * A question a third again as slow, re-rolled, does not. So this bounds the measured case
         * with its re-roll and not the worst imaginable one, and an answer past it is refused
         * exactly as before.
         *
         * **The spread is the part worth knowing, and it is wide.** The same question asked twice
         * has come back in six and a half minutes and in one, so this shape's cost is not a figure
         * with a small variance around it and no number of measurements will make it one. What
         * would still be needed to do better is a figure re-derived on both sides at once, since
         * the gate carries the same blind spot — and a third reader now depends on it, the rendered
         * end-to-end case, which waits past this bound rather than on it so that the refusal
         * composed here is what it reports.
         *
         * It is a bound on waiting and not on a request, so it does not hold a connection or a
         * coroutine doing nothing — which is why it can be this wide without giving back what the
         * old ceiling was protecting.
         */
        private const val ANSWER_BUDGET_MILLIS = 900_000L

        /**
         * How long between attempts at collecting.
         *
         * Short enough that a finished answer is not left sitting, long enough that the longest
         * wait is a few hundred cheap requests rather than thousands. The platform's own typing
         * indicator refreshes on a similar period, so nothing about this is visible to a reader.
         */
        private const val POLL_INTERVAL_MILLIS = 3_000L

        /**
         * How many hops in a row may fail before the answer is given up on.
         *
         * Not nought, because an answer that exists must not be thrown away by one blip — that is
         * the defect this route exists to fix, and reintroducing it at the last hop would be a poor
         * trade. Small, because a backend that has gone away fails every hop, and this is what
         * bounds how long it may be waited on: giving up on the hop *after* the allowance, the
         * bound is one more than this many hop ceilings — two minutes at the figures here, which
         * is still inside the three the single ceiling it replaces allowed.
         */
        private const val HOP_FAILURES_ALLOWED = 3

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Let a real cancellation travel, as the runner and the derived passes already spell it.
 *
 * The collecting loop's own `delay` sits outside the `try` this is called from, so a cancelled
 * scope ends the wait whether this rethrows or not. What rethrowing buys is that a shutdown is not
 * logged as a hop that did not land, nor reported to a reader as a refusal once the hop allowance
 * runs out.
 */
private suspend fun rethrowIfCancelled(failure: Throwable) {
    if (failure is CancellationException && !currentCoroutineContext().isActive) throw failure
}
