package io.github.ragnorrs.mainbrain.backend.presentation

import io.github.ragnorrs.mainbrain.contract.AnswerAccepted
import io.github.ragnorrs.mainbrain.contract.Failure
import io.github.ragnorrs.mainbrain.contract.MessageKey
import io.github.ragnorrs.mainbrain.domain.answer.Answer
import io.github.ragnorrs.mainbrain.domain.answer.Runner
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Where an answer asked for separately is collected, with the work's own name on the end. */
internal const val ANSWER_ASYNC = "/answer/async"

/**
 * How long a piece of work is held, measured from when it was accepted.
 *
 * From acceptance and not from completion, which is the simpler rule and the one the structure can
 * actually compute: nothing here records when a caller last asked, so *nobody came back for it* is
 * not a property this can tell from *still being collected*. Twice the interface's own waiting
 * budget, so an answer is never dropped from under a caller that is still entitled to ask for it.
 *
 * Generous because the slot costs a reference once the work has finished. Bounded because an
 * interface that restarted mid-wait never comes back, and because what is held before then is a
 * running model call rather than a reference.
 */
private const val KEEP_MILLIS = 30 * 60 * 1000L

private val log = LoggerFactory.getLogger("io.github.ragnorrs.mainbrain.backend.presentation.inflight")

/**
 * The answers being worked on, each under a name the caller comes back with.
 *
 * **Why the work is held as a [Deferred] rather than as a result and a fault.** A deferred that
 * finished badly rethrows on collection, which is what lets the one route that collects it hand the
 * failure to the same status mapping every other route uses — rather than restating that mapping
 * here, where the two would drift apart and the asynchronous path would answer a dead store
 * differently from the synchronous one.
 *
 * **Nothing is persisted.** A backend that restarts has lost the work itself, not merely the record
 * of it, so a store that outlived the process would hand back names it could not honour. What that
 * costs is one request at a restart, which is the same thing a restart costs the synchronous route.
 *
 * [now] is a parameter so that keeping and discarding are testable without waiting for a clock.
 */
internal class InFlight(private val now: () -> Long = System::currentTimeMillis) {

    private data class Held(val work: Deferred<Answer>, val startedAt: Long)

    private val held = ConcurrentHashMap<String, Held>()

    /** Take [work] in hand and say what to come back with. */
    fun accept(work: Deferred<Answer>): String {
        discardStale()
        val id = UUID.randomUUID().toString()
        held[id] = Held(work, now())
        return id
    }

    /**
     * The work filed under [id], or null if nothing is.
     *
     * **Handing it over does not let go of it, and that is the point rather than an oversight.** The
     * obvious reading — the caller is collecting it, so the slot can go — drops the entry when the
     * response is handed to the server, not when the caller has it. A response lost in flight on a
     * finished answer would then find nothing on the retry, and the caller's allowance for a hop
     * that did not land exists for exactly that case; the answer this whole route protects would be
     * destroyed by the one mechanism meant to protect it. So collecting is repeatable and
     * [discardStale] is what reclaims the slot.
     */
    fun collect(id: String): Deferred<Answer>? = held[id]?.work

    /** How many pieces of work are held, which is what a test asserts a discard by. */
    fun size(): Int = held.size

    /**
     * Let go of anything held past [KEEP_MILLIS].
     *
     * Swept on arrival rather than on a timer of its own, because the only thing that grows this is
     * a new request. **What that costs is worth stating plainly: the sweep is eventual, not prompt.**
     * On a backend nobody is asking anything of, work the interface has long stopped waiting for is
     * neither dropped nor cancelled until some later request arrives. That is no worse than the
     * single-request route, where work abandoned by its caller also ran to completion — but it is
     * weaker than a timer, and a timer is what it would take to bound the spend rather than the
     * slot.
     *
     * Work still running is cancelled as it is dropped, since past the keeping period there is no
     * caller entitled to it and finishing would spend a model call into a result nobody reads.
     */
    private fun discardStale() {
        val cutoff = now() - KEEP_MILLIS
        val stale = held.filterValues { it.startedAt < cutoff }.keys
        stale.forEach { id ->
            held.remove(id)?.let {
                log.warn("Discarded answer {}, held past {} ms.", id, KEEP_MILLIS)
                it.work.cancel()
            }
        }
    }
}

/**
 * Take a request, start it, and say what to come back with.
 *
 * The same three refusals the synchronous route answers, before any work starts and through the
 * same function, so the two routes cannot come to disagree about which requests are answerable.
 * Everything that can go wrong *during* an answer is reported where it is collected, because that is
 * where there is a caller to tell.
 *
 * Refusing before the work starts matters more here than there, because this route answers before
 * the work runs: accepted and then refused is a caller holding an identifier for an answer that was
 * never going to come.
 *
 * **Launched in [answering] rather than in the call's own scope.** The point of handing an answer
 * over in two parts is that the work outlives the request that asked for it; a coroutine started in
 * the call would be cancelled the moment this responds, which is the defect this route exists to
 * fix rather than a detail of it.
 */
internal suspend fun RoutingContext.submitAnswer(
    runner: Runner?,
    answering: CoroutineScope,
    inFlight: InFlight,
) {
    val asking = accepted(runner) ?: return
    val command = asking.request.command ?: FREE_TEXT
    val work = answering.async {
        val answered = asking.runner.answer(asking.request)
        log.info("Answered {}: {}.", command, answered.asLogged())
        answered
    }
    val id = inFlight.accept(work)
    log.info("Accepted {} as {}.", command, id)
    call.respond(HttpStatusCode.Accepted, AnswerAccepted(id))
}

/**
 * Hand over an answer if it is ready, and say that it is not if it is not.
 *
 * **The reply a caller is waiting for is the one worth reading first.** A finished answer is the
 * response the synchronous route would have given, byte for byte. Work still running is *accepted
 * and not finished*, which the caller reads as *ask again shortly* — a status rather than a body,
 * because there is nothing to say yet and a body saying so would be a sentence from a process with
 * no catalogue. A name nothing is filed under, or work that was dropped while the caller was still
 * asking, is *come back later*: this process restarting is the ordinary way to reach either, and it
 * is a wait rather than a defect in the build, which is the line the message keys draw between
 * them.
 *
 * **A failure is rethrown rather than mapped here.** Collecting work that ended badly raises what
 * ended it, which the status pages already turn into the right status and key — so a dependency
 * that was down during an answer reads the same whichever route asked for it, and there is one
 * mapping rather than two.
 */
internal suspend fun RoutingContext.collectAnswer(inFlight: InFlight) {
    val id = call.parameters["id"]
    val work = id?.let { inFlight.collect(it) }
    when {
        // Unreachable through the route this is registered on, which cannot match without the
        // segment — kept because the alternative is asserting that with `!!`, and counted here
        // rather than described away.
        id == null -> call.respond(HttpStatusCode.BadRequest, Failure(MessageKey.REQUEST_MALFORMED))

        work == null -> {
            log.warn("Asked for answer {}, which nothing is filed under.", id)
            call.respond(HttpStatusCode.ServiceUnavailable, Failure(MessageKey.CANNOT_ANSWER_RIGHT_NOW))
        }

        !work.isCompleted -> call.respond(HttpStatusCode.Accepted)
        else -> handOver(work)
    }
}

/**
 * Hand over finished work, telling a failure apart from a dropping.
 *
 * **Not `isCancelled`, which is true of both.** A coroutine that failed is cancelled too, as far as
 * that flag is concerned, so branching on it reports a dependency being down and a defect in the
 * build alike as *come back later* — and the case that says an unanticipated failure is not a wait
 * is what catches it. What distinguishes them is which exception collecting raises.
 *
 * A failure is rethrown, so the status pages map it exactly as they do for the synchronous route. A
 * dropping is this process having stopped or the keeping period having passed, neither of which a
 * reader can act on beyond coming back.
 *
 * The guard before the response is the one the runner and the derived passes already spell: if it
 * is *this* call being cancelled rather than the work, that travels rather than being answered.
 */
private suspend fun RoutingContext.handOver(work: Deferred<Answer>) {
    val answered = try {
        work.await()
    } catch (dropped: CancellationException) {
        if (!currentCoroutineContext().isActive) throw dropped
        log.warn("Answer collected after the work behind it was dropped.", dropped)
        return call.respond(HttpStatusCode.ServiceUnavailable, Failure(MessageKey.CANNOT_ANSWER_RIGHT_NOW))
    }
    call.respond(answered.toResponse())
}

/**
 * Register both halves of the separately-collected answer.
 *
 * The two routes and the store they share are registered together because they are one feature and
 * neither is usable without the other — a submit nothing can collect from strands the work, and a
 * collect with no submit has nothing to find. It also keeps the composition root's route block to
 * one line per feature, which is what it is for.
 */
internal fun Route.answerAsync(runner: Runner?, answering: CoroutineScope) {
    val inFlight = InFlight()
    post(ANSWER_ASYNC) { submitAnswer(runner, answering, inFlight) }
    get("$ANSWER_ASYNC/{id}") { collectAnswer(inFlight) }
}
