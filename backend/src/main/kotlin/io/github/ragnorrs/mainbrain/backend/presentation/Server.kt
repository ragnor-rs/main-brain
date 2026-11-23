package io.github.ragnorrs.mainbrain.backend.presentation

import io.github.ragnorrs.mainbrain.contract.AnswerRequest
import io.github.ragnorrs.mainbrain.contract.Failure
import io.github.ragnorrs.mainbrain.contract.MessageKey
import io.github.ragnorrs.mainbrain.contract.UnresolvedReport
import io.github.ragnorrs.mainbrain.domain.EmbeddingsUnavailable
import io.github.ragnorrs.mainbrain.domain.ModelUnavailable
import io.github.ragnorrs.mainbrain.domain.answer.Answer
import io.github.ragnorrs.mainbrain.domain.answer.Request
import io.github.ragnorrs.mainbrain.domain.answer.Runner
import io.github.ragnorrs.mainbrain.domain.entity.UnresolvedPair
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelinePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/** The header the one authorised caller proves itself with. */
internal const val SECRET_HEADER = "X-Main-Brain-Secret"

/** The only open route. */
internal const val HEALTH = "/health"

/**
 * A whole answer in one request.
 *
 * Kept as it is because it is what the evaluation set reads, and what any caller willing to hold a
 * request for the duration wants. The interface shipped here is not such a caller and asks at
 * [ANSWER_ASYNC] instead — the same work in two parts, for the reason `AnswerAccepted` gives.
 */
internal const val ANSWER = "/answer"

/**
 * The advisory read of pairs that scored without merging.
 *
 * Behind the same secret as [ANSWER] and open to no one else. It is not part of what the interface
 * asks for — see `UnresolvedReport` for why it is served at all rather than rendered by the bot.
 */
internal const val UNRESOLVED = "/unresolved"

/**
 * How many kept pairs one read returns when the caller names no bound.
 *
 * A page size and nothing more: enough to read in one sitting, and a caller wanting the rest asks
 * for it. Presentation's to choose, unlike the floor, which is a judgement about evidence and
 * comes from the weights.
 */
private const val UNRESOLVED_PAGE = 50

/**
 * The largest page a caller may ask for.
 *
 * A bound on the arithmetic as much as on the read: one row more than asked for is fetched, and at
 * `Int.MAX_VALUE` that addition wraps negative — which the store reads as *nothing*, so an empty
 * report would come back claiming to be complete. Refusing the request says what happened instead.
 * It also keeps a single read off the whole table, which no reader sampling pairs by hand wants.
 */
private const val UNRESOLVED_PAGE_MAX = 1_000

private val log = LoggerFactory.getLogger("io.github.ragnorrs.mainbrain.backend.presentation")

/**
 * The surface the bot reaches, and the whole of what this process serves.
 *
 * Collaborators are parameters rather than read from a container, which is what lets the suite
 * serve this application in-process against fakes — no socket, no database, no model call, and
 * therefore in CI.
 *
 * [runner] is nullable, and the null is a real state rather than a convenience for tests. A
 * deployment holding no model credential assembles no answer path, which is a configuration this
 * build supports: the store and ingestion are exercised against a credential-free stack. Reported
 * as *cannot answer right now* instead of a surface that is absent — the hop, its secret and its
 * error contract are all testable against a backend that answers nothing, and an interface built
 * against it gets a status code rather than a connection refused.
 *
 * [serving] is what the health route asks. A function rather than a flag, because health is a
 * question with a current answer — a process that was able to reach the store when it started
 * says nothing about now.
 *
 * [samplingFloor] and [unresolved] are the advisory read, and both are passed in for the reason the
 * rest are: the floor is a judgement derived from the matching weights, which belong to the domain
 * and not to a route, and the reader is a port. A deployment with no store to read them from is not
 * a state this build has — the store is the one dependency without which the backend does nothing —
 * so neither is nullable.
 */
internal fun Application.api(
    secret: String,
    runner: Runner?,
    serving: suspend () -> Boolean,
    samplingFloor: Double,
    unresolved: suspend (Double, Int) -> List<UnresolvedPair>,
) {
    install(ContentNegotiation) {
        json(
            Json {
                // The two processes are versioned together and deployed separately, so a field
                // added on one side must not break a reader that has not been rebuilt.
                ignoreUnknownKeys = true
                // A null field is absent rather than written as `null`. What keeps the empty
                // lists beside it off the wire is a different setting — `encodeDefaults`, which
                // is already false — so anyone turning that on should not read this line as a
                // reason they need not.
                explicitNulls = false
            },
        )
    }

    install(StatusPages) {
        // A body the caller can read without a human in the loop. Ktor's own default is a
        // plain-text line, which is a sentence composed by the one process that has no catalogue
        // — so every escape is caught here and answered with a key instead.
        exception<BadRequestException> { call, cause ->
            log.info("Rejected a malformed request to {}: {}", call.request.path(), cause.message)
            call.respond(HttpStatusCode.BadRequest, Failure(MessageKey.REQUEST_MALFORMED))
        }
        // The caller's other way of getting the body wrong, and the one path that answers without
        // being asked to. A declared content type the negotiation plugin holds no converter for is
        // refused by the plugin itself, with the right status and **an empty body** — so this was
        // the only failure a caller could meet that carried no key, which left the error contract
        // holding everywhere but here.
        //
        // Answered as a status rather than an exception because no exception reaches here: the
        // plugin responds rather than throwing. That is also why the status is kept as it is —
        // 415 is already the actionable thing to say, and what was missing was saying it in the
        // shape everything else uses.
        status(HttpStatusCode.UnsupportedMediaType) { call, status ->
            log.info(
                "Rejected a request to {} sent as {}",
                call.request.path(),
                call.request.headers[HttpHeaders.ContentType],
            )
            call.respond(status, Failure(MessageKey.REQUEST_MALFORMED))
        }
        // A dependency the answer path needs is not answering, which is the state
        // [MessageKey.CANNOT_ANSWER_RIGHT_NOW] names alongside the store being unreachable. Both
        // are raised rather than returned, because neither is something the model or the sidecar
        // *did* — so without this they would reach the catch-all below and be reported as a defect
        // in this build. The embeddings sidecar makes that the ordinary case rather than the rare
        // one: it refuses every call for as long as it takes to load its weights, and the health
        // route deliberately does not wait for it.
        //
        // Caught as the two port-level types, never as an adapter's own. `SidecarUnavailable` is a
        // subtype of the first and arrives here as one, which is what keeps this file from naming
        // which implementation of a port the process was deployed against.
        exception<EmbeddingsUnavailable> { call, cause ->
            log.warn("Could not answer {}: the embeddings provider is not answering.", call.request.path(), cause)
            call.respond(HttpStatusCode.ServiceUnavailable, Failure(MessageKey.CANNOT_ANSWER_RIGHT_NOW))
        }
        exception<ModelUnavailable> { call, cause ->
            log.warn("Could not answer {}: the model is not reachable.", call.request.path(), cause)
            call.respond(HttpStatusCode.ServiceUnavailable, Failure(MessageKey.CANNOT_ANSWER_RIGHT_NOW))
        }
        exception<Throwable> { call, cause ->
            // Logged in full here because this is the only place it is seen: the reply carries a
            // key, so the detail has nowhere else to go.
            log.error("Unhandled failure answering {}", call.request.path(), cause)
            call.respond(HttpStatusCode.InternalServerError, Failure(MessageKey.UNEXPECTED_FAILURE))
        }
    }

    guardWithSecret(secret)

    // Supervised and parented to the application, for the work an answer asked for separately runs
    // in: a failure there must not cancel the server, and a server that stops must cancel the work
    // still running rather than leave it to finish into nothing.
    val answering = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))

    routing {
        /**
         * Whether the backend is serving, which is not whether the process is alive.
         *
         * Open, and the only route that is: whatever reads it is the thing establishing that the
         * hop works, so requiring the secret would make an unconfigured deployment look like a
         * broken one.
         *
         * **The store, and deliberately not the embeddings sidecar.** A cold sidecar spends
         * minutes loading weights while refusing every call, and a backend that reported itself
         * unhealthy throughout would hold up everything that waits on it — for a dependency that
         * only some of its work needs. The store is the one thing without which it can do none.
         *
         * No body. The reader is a container healthcheck and a deploy, both of which act on the
         * status code; prose here would be a sentence from a process that composes none.
         */
        get(HEALTH) {
            call.respond(if (serving()) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
        }

        post(ANSWER) {
            val asking = accepted(runner) ?: return@post
            val answered = asking.runner.answer(asking.request)
            // The word, which the acceptance above has established names a shape. Without that it
            // reports whatever the caller sent under the name of the shape that answered, and a
            // mistyped command reads in the log as a pipeline this build does not have.
            log.info("Answered {}: {}.", asking.request.command ?: FREE_TEXT, answered.asLogged())
            call.respond(answered.toResponse())
        }

        // The same answer, asked for and collected separately — see `AnswerAccepted` for why one
        // shape needs that.
        answerAsync(runner, answering)

        get(UNRESOLVED) { reportUnresolved(samplingFloor, unresolved) }
    }
}

/**
 * A request to answer and the runner that will answer it.
 *
 * The pair exists so that the acceptance check can hand back a runner the caller may use without a
 * second null check: both routes hold a nullable one, and establishing it is part of accepting a
 * request rather than something each route repeats afterwards.
 */
internal data class Asking(val request: Request, val runner: Runner)

/**
 * The request both answering routes may act on, or `null` having already said why not.
 *
 * **The three caller-side refusals in one place, because there are two routes and one contract.**
 * A request carrying nothing to ask is malformed; a deployment holding no model credential cannot
 * answer at all; and a command naming no shape this build answers is the caller having got a wire
 * field wrong. Written once rather than per route, so a third route added later has the acceptance
 * to hand rather than the choice of which parts of it to make.
 *
 * **Why the command is checked at all.** Binding refuses nothing — a shape has to answer whatever
 * reaches it — so a word naming no shape is answered through the fallback: an answering model call
 * at the costliest path, a corpus ranked against text carrying a word that means nothing, and a log
 * line reporting the request under a shape name there is no shape for. None of it is visible in the
 * reply, which reads as a composed answer about whatever followed the word. The interface shipped
 * here refuses such a command at its own edge and never sends one, which is where the cost is
 * actually saved; this is the half that holds for a caller that is not it.
 *
 * **A word naming the fallback is accepted.** The registry holds that shape and a caller may ask
 * for it by name, which the evaluation set does. What is true of the fallback is that no command
 * list offers it, not that nothing answers it — so the set read here is what may be accepted rather
 * than what any interface should offer.
 *
 * Refused with the status and key the surface already uses for a malformed request: there is one
 * error contract here, and an unanswerable command is not a new kind of failure. Logged with the
 * word, because the word is the whole of what the caller got wrong.
 */
// One return per refusal, which is what a list of guard clauses is. Collapsed into a single exit
// the three would have to be computed as a value and then acted on, which means either asserting
// the two nullable locals are not null after the fact or a result type whose only reader is the
// line below it — both further from *these three things are refused, in this order* than the
// clauses are.
@Suppress("ReturnCount")
internal suspend fun RoutingContext.accepted(runner: Runner?): Asking? {
    val asked = call.receive<AnswerRequest>().toDomain()
    if (asked == null) {
        call.respond(HttpStatusCode.BadRequest, Failure(MessageKey.REQUEST_MALFORMED))
        return null
    }
    if (runner == null) {
        call.respond(HttpStatusCode.ServiceUnavailable, Failure(MessageKey.CANNOT_ANSWER_RIGHT_NOW))
        return null
    }
    if (asked.command != null && asked.command !in runner.answerable) {
        log.info("Refused the command {}, which names no shape this build answers.", asked.command)
        call.respond(HttpStatusCode.BadRequest, Failure(MessageKey.REQUEST_MALFORMED))
        return null
    }
    return Asking(asked, runner)
}

/**
 * The pairs that scored and were not merged, strongest first.
 *
 * A function of its own rather than a block inside [api], which is at the length detekt allows —
 * and this is the one route here that reads parameters, so it is also the one with anything to say.
 *
 * **A malformed bound is refused rather than defaulted.** Answering `floor=soon` with the standard
 * floor would return a sample the caller did not ask for, under a rule they would read as theirs,
 * and a calibration drawn from it would be wrong in a way nothing reported. The error contract is
 * the same keyed body every other refusal here uses.
 *
 * **Not a number and not finite are both malformed**, which `toDoubleOrNull` does not say: it
 * parses `NaN` and `Infinity` happily, and `1e400` rounds to the latter. Neither can be answered —
 * the wire format refuses to encode either, so the reply would be an unexplained failure instead
 * of the keyed refusal promised above — and `NaN` additionally orders above every score in
 * Postgres, so it would match nothing while reading as a floor that did.
 *
 * **The page is capped**, so that one row more than asked for cannot overflow. `limit + 1` at
 * `Int.MAX_VALUE` wraps negative, the store reads nothing from a limit below one, and the reply
 * would be an empty report claiming completeness — the precise confusion
 * `UnresolvedReport.more` exists to prevent, arrived at from a request that looked valid.
 *
 * One row more than asked for is read, so that `UnresolvedReport.more` can tell a truncated result
 * from a complete one without a second count.
 */
/**
 * The floor a caller asked for, [standard] when they named none, or `null` when it cannot be used.
 *
 * `toDoubleOrNull` parses `NaN` and `Infinity`, and `1e400` rounds to the latter, so *it parsed* is
 * not the same question as *it can be answered*. Neither can: the wire format refuses to encode a
 * non-finite number, and `NaN` orders above every score in Postgres, so it would match nothing
 * while reading as a floor that did.
 */
private fun floorAsked(raw: String?, standard: Double): Double? =
    if (raw == null) standard else raw.toDoubleOrNull()?.takeIf { it.isFinite() }

/**
 * The page size a caller asked for, the default when they named none, or `null` when it is refused.
 *
 * Capped at both ends. Below one there is nothing to read; above [UNRESOLVED_PAGE_MAX] the `limit +
 * 1` the caller's request turns into would wrap negative at the integer ceiling, which the store
 * reads as nothing — so the reply would be an empty report claiming completeness, from a request
 * that looked valid.
 */
private fun limitAsked(raw: String?): Int? =
    if (raw == null) UNRESOLVED_PAGE else raw.toIntOrNull()?.takeIf { it in 1..UNRESOLVED_PAGE_MAX }

private suspend fun RoutingContext.reportUnresolved(
    samplingFloor: Double,
    unresolved: suspend (Double, Int) -> List<UnresolvedPair>,
) {
    val wantedFloor = floorAsked(call.request.queryParameters["floor"], samplingFloor)
    val wantedLimit = limitAsked(call.request.queryParameters["limit"])
    if (wantedFloor == null || wantedLimit == null) {
        return call.respond(HttpStatusCode.BadRequest, Failure(MessageKey.REQUEST_MALFORMED))
    }
    val read = unresolved(wantedFloor, wantedLimit + 1)
    log.info("Reported {} unresolved pair(s) at or above {}.", read.size.coerceAtMost(wantedLimit), wantedFloor)
    call.respond(
        UnresolvedReport(
            floor = wantedFloor,
            limit = wantedLimit,
            more = read.size > wantedLimit,
            pairs = read.take(wantedLimit).map { it.toView() },
        ),
    )
}

/** What a request with no command is called in the log. */
internal const val FREE_TEXT = "free text"

/**
 * One line per answered request, which is the only trace a request that worked leaves.
 *
 * **Nothing logged a successful answer, and that made the ordinary failures the hardest to see.**
 * The adapter reports what a model call cost, so a request that spends nothing — an ambiguous name,
 * an empty corpus — passed through in silence, indistinguishable in the log from one that never
 * arrived. A reply somebody calls useless is then investigated by reading source rather than by
 * reading what happened.
 *
 * **The claim count is the half worth having, and what it is worth depends on the shape.** Where a
 * shape declares that its answer is the assertions it cites, a reply carrying none is refused
 * before it reaches here — see `Shape.assertsFindings` — so the count on such a line is never
 * nought and the line reads *nothing to answer from* instead. Where a shape's deliverable is text
 * the model composed, nought is the ordinary reading and this is the only place it is visible.
 * Which the shape is, is not derivable from whether it reads the corpus: the drafting shape reads
 * it and composes.
 *
 * **No subject established is the one line here that replaces an inference.** Until it existed, a
 * request whose routing call reported no name was answered for the inbox and logged as an ordinary
 * composed answer, so telling it from a corpus-wide sweep somebody asked for meant reading the
 * routing call's output token count off the line above and guessing how many lines it had written.
 * That guess does not work — thinking counts against output tokens, so a two-line reply has
 * measured at both five tokens and fifty-six — which is the argument for saying it here instead of
 * leaving it to be reconstructed.
 *
 * **It says no subject was established rather than that one was named and lost**, because which of
 * those happened is exactly what is not known: a routing reply of one line may have been about
 * somebody or about nobody. Naming the half that cannot be checked is what sends a reader looking
 * for a name that was never there.
 *
 * Exhaustive, so a new answer kind is described here rather than reaching a log as a class name.
 */
internal fun Answer.asLogged(): String = when (this) {
    is Answer.Composed ->
        "${variants.size} variant(s), ${variants.sumOf { it.claims.size }} claim(s)"

    is Answer.InsufficientEvidence -> "nothing to answer from"

    // Said apart from the line above, because what distinguishes them is the two model calls this
    // one paid for. Not *two unreadable replies*: the first attempt may have been rejected for
    // something perfectly readable and the second is the one that could not be read. Which were
    // which, the fault lines say — there is one per attempt.
    is Answer.Unreadable -> "gave up on a reply that could not be read, after two model calls"
    is Answer.SubjectUnclear -> "subject unclear, ${options.size} reading(s) offered"
    is Answer.SubjectUnknown -> "subject not found among ${searched.joinToString { it.name }}"

    // The count and not the names. A log line is read to tell a defect from an ordinary miss, and
    // how many near forms were offered is what says which; the names themselves are a reader's
    // material and belong in the reply rather than on an operator's console.
    is Answer.SubjectNearest ->
        "no exact match among ${searched.joinToString { it.name }}, ${nearest.size} near name(s) offered"

    is Answer.SubjectUnread -> "no subject established, nothing searched"

    // Said apart from the line above, because the two are the difference between a request that
    // named nobody and one whose subject nobody could establish — and reading a log to tell a bare
    // command from an unreadable routing reply is exactly the reconstruction this function exists to
    // replace.
    is Answer.SubjectMissing -> "no subject given, nothing searched"
}

/**
 * Refuse anything that does not carry the shared secret, before it reaches a route.
 *
 * **At the pipeline rather than inside each handler**, because the property wanted is that
 * nothing is reachable without it — and a check written once per route is a check the next route
 * omits. The health route is exempt by name, which is the one exemption and is stated in one
 * place.
 *
 * Compared with the platform's own constant-time check rather than with `==`. Equality on a
 * string stops at the first differing byte, which is a timing signal; the difference is not
 * measurable across the network this secret is sent over, but the version of this written
 * without thinking about it is the one that gets copied somewhere it is.
 */
private fun Application.guardWithSecret(secret: String) {
    val expected = secret.toByteArray()
    val phase = PipelinePhase("SharedSecret")
    insertPhaseBefore(ApplicationCallPipeline.Call, phase)
    intercept(phase) {
        // `context` rather than `call`: the pipeline's subject is what is being intercepted, and
        // the call is the context it runs in.
        val incoming = context
        if (incoming.request.path() == HEALTH) return@intercept
        val offered = incoming.request.headers[SECRET_HEADER]
        if (offered == null || !MessageDigest.isEqual(offered.toByteArray(), expected)) {
            // Not logged with the offered value. A near-miss is worth seeing and the value is
            // somebody's attempt at a credential, which does not belong in a log file.
            log.warn(
                "Refused a request to {} carrying {} shared secret.",
                incoming.request.path(),
                if (offered == null) "no" else "the wrong",
            )
            incoming.respond(HttpStatusCode.Unauthorized, Failure(MessageKey.REQUEST_UNAUTHORISED))
            finish()
        }
    }
}
