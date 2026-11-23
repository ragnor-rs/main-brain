package io.github.ragnorrs.mainbrain.bot.client

import io.github.ragnorrs.mainbrain.bot.config.BackendSettings
import io.github.ragnorrs.mainbrain.contract.AnswerAccepted
import io.github.ragnorrs.mainbrain.contract.AnswerRequest
import io.github.ragnorrs.mainbrain.contract.AnswerResponse
import io.github.ragnorrs.mainbrain.contract.Block
import io.github.ragnorrs.mainbrain.contract.Failure
import io.github.ragnorrs.mainbrain.contract.Freshness
import io.github.ragnorrs.mainbrain.contract.MessageKey
import io.github.ragnorrs.mainbrain.contract.Outcome
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

/**
 * The two requests this interface sends, and what it makes of every answer.
 *
 * Driven from a script rather than a socket, so the status-to-key table runs in CI. That table is
 * the thing worth asserting: every refusal the surface can serve has to become something said to
 * a person, and so does every way the call can fail to happen at all.
 *
 * **An answer is asked for and collected separately**, so a refusal can arrive at either hop and
 * both are covered here. The cases that matter most are the ones only this shape has: that waiting
 * is waiting rather than failing, that a hop which does not land is not the same thing as an
 * answer that does not exist, and that the two ways of running out of either are told apart —
 * a backend gone away and an answer still being composed are different things to be told.
 */
class HttpBackendClientTest {

    @Test
    fun `the request carries the secret, the route and the declared type`() {
        val seen = mutableListOf<HttpRequestData>()
        val client = clientCollecting(seen = seen) { answered() }
        runBlocking { client.answer(AnswerRequest(text = "a question")) }

        val submit = seen.first()
        assertEquals(HttpMethod.Post, submit.method)
        assertEquals("/answer/async", submit.url.encodedPath)
        assertEquals(SECRET, submit.headers["X-Main-Brain-Secret"])
        assertEquals(ContentType.Application.Json, submit.body.contentType)
    }

    /** And the second hop, which carries the name the backend minted rather than the request. */
    @Test
    fun `the answer is collected by the name the backend gave it`() {
        val seen = mutableListOf<HttpRequestData>()
        val client = clientCollecting(seen = seen) { answered() }
        runBlocking { client.answer(AnswerRequest(text = "a question")) }

        val collect = seen.last()
        assertEquals(HttpMethod.Get, collect.method)
        assertEquals("/answer/async/$WORK_ID", collect.url.encodedPath)
        assertEquals(SECRET, collect.headers["X-Main-Brain-Secret"])
    }

    @Test
    fun `a trailing slash on the configured address does not double up`() {
        val seen = mutableListOf<HttpRequestData>()
        val client = clientCollecting(url = "http://backend:8080/", seen = seen) { answered() }
        runBlocking { client.answer(AnswerRequest(text = "a question")) }

        assertEquals("/answer/async", seen.first().url.encodedPath)
        assertEquals("/answer/async/$WORK_ID", seen.last().url.encodedPath)
    }

    @Test
    fun `an answer comes back whole`() {
        val response = AnswerResponse(
            outcome = Outcome.SendStatus(MessageKey.NOTHING_TO_ANSWER_FROM),
            freshness = Freshness(completeThrough = "2026-10-05T12:00:00Z", lagging = listOf("TELEGRAM")),
        )
        val client = clientCollecting { answered(response) }
        val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }

        assertEquals(response, assertIs<Reached.Answered>(reached).response)
    }

    /**
     * The property the whole two-hop shape exists for.
     *
     * Work that is not finished is answered as *accepted and unfinished*, and this interface keeps
     * coming back rather than treating it as a failure. Before this, an answer that took longer
     * than the one ceiling was discarded with the reader told the service was unavailable — while
     * the backend went on and finished it.
     */
    @Test
    fun `work still running is waited for rather than given up on`() {
        var collects = 0
        val client = clientCollecting {
            collects++
            if (collects < 3) respond(content = "", status = HttpStatusCode.Accepted) else answered()
        }
        val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }

        assertIs<Reached.Answered>(reached)
        assertEquals(3, collects, "It should have come back until there was something to collect.")
    }

    /**
     * A hop that does not land is retried, because an answer may exist on the other side of it.
     *
     * The distinction this asserts is between the *transport* failing and the *work* failing. One
     * dropped request says nothing about whether the answer is ready, and throwing the request away
     * on it would reintroduce the defect this route fixes in a narrower form.
     */
    @Test
    fun `a hop that does not land does not throw the answer away`() {
        var collects = 0
        val client = clientCollecting {
            collects++
            if (collects == 1) throw java.io.IOException("connection reset") else answered()
        }
        val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }

        assertIs<Reached.Answered>(reached)
        assertEquals(2, collects)
    }

    /**
     * And the other side of it: a backend that has gone away is not waited on for the whole budget.
     *
     * This is the bound that lets the budget be wide. Were every hop retried until the budget ran
     * out, a dead backend would hold this process for the length of the longest answer — which is
     * exactly the hold the two-hop shape was adopted to remove.
     */
    @Test
    fun `hops that keep failing give up well inside the budget`() {
        var collects = 0
        val client = clientCollecting(budget = HUGE_BUDGET_MILLIS) {
            collects++
            throw java.io.IOException("connection refused")
        }
        val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }

        // A backend that has gone away, which keeps the wording it had: the state passes, so
        // coming back later is sound advice, and there is something for an operator to do.
        assertEquals(MessageKey.CANNOT_ANSWER_RIGHT_NOW, assertIs<Reached.Refused>(reached).key)
        // Exactly, not at most: a lower bound would be satisfied by a client that gave up on the
        // first failure, which is the behaviour the hop allowance exists to rule out.
        assertEquals(FAILURES_BEFORE_GIVING_UP, collects)
    }

    /**
     * Running out of patience is this client's own fact, not the backend being unavailable.
     *
     * **The distinction the reply turns on.** Every hop here was answered — the work was accepted
     * and was still being composed when the clock ran out — so a reader told the service is
     * unavailable is told something false, and sent to retry a question that will overrun again
     * at the same cost.
     *
     * The hop count is asserted as well as the outcome, because a budget this small can be spent
     * before the first hop is attempted: the overrun would then be reported having observed
     * nothing, and the case would pass while asserting none of what it is named for.
     */
    @Test
    fun `an answer that never becomes ready is the interface's own overrun`() {
        var collects = 0
        val client = clientCollecting(budget = 1L) {
            collects++
            respond(content = "", status = HttpStatusCode.Accepted)
        }
        val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }

        assertIs<Reached.Overran>(reached)
        assertTrue(collects > 0) { "No hop was attempted, so nothing here observed a backend at all." }
    }

    /**
     * And a hop that did not land is never reported as an overrun, whichever bound ended the wait.
     *
     * **What the overrun message asserts is what this protects.** It tells the reader the service
     * was there and the work was still being composed, and tells them not to come back — so it may
     * only be said where every hop landed. The consecutive-failure count cannot establish that,
     * because an answered hop resets it: a backend flapping below the allowance, or dying with less
     * than the allowance left on the clock, leaves the loop on the clock with it low. A reply keyed
     * on which bound ran out would then tell somebody whose backend had just died that the service
     * was fine and that returning would not help — this interface's original defect reversed, and
     * worse than the generic refusal rather than better.
     *
     * Driven so that only the clock can end it: the first hop fails and the rest are answered, so
     * the allowance is reset and never approached however many hops run.
     */
    @Test
    fun `a hop that did not land is not reported as an overrun`() {
        var collects = 0
        val client = clientCollecting(budget = 1L) {
            collects++
            if (collects == 1) throw java.io.IOException("connection reset")
            respond(content = "", status = HttpStatusCode.Accepted)
        }
        val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }

        assertEquals(MessageKey.CANNOT_ANSWER_RIGHT_NOW, assertIs<Reached.Refused>(reached).key)
        assertTrue(collects in 1 until FAILURES_BEFORE_GIVING_UP) {
            "It collected $collects time(s); the allowance rather than the clock ended this wait."
        }
    }

    @Test
    fun `every refusal the surface serves when asked becomes the key in its body`() {
        refusals.forEach { (status, key) ->
            val client = clientSubmitting {
                respond(
                    content = json.encodeToString(Failure(key)),
                    status = status,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
            val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }
            assertEquals(key, assertIs<Reached.Refused>(reached).key) { "asking, on $status" }
        }
    }

    /**
     * And every refusal it serves where the answer is collected.
     *
     * Work now fails somewhere the request that asked for it has already ended, so this is the hop
     * that reports a dependency which was down while the answer ran. A client that read only the
     * first hop's status would report those as answers.
     */
    @Test
    fun `every refusal the surface serves when collected becomes the key in its body`() {
        refusals.forEach { (status, key) ->
            val client = clientCollecting {
                respond(
                    content = json.encodeToString(Failure(key)),
                    status = status,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
            val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }
            assertEquals(key, assertIs<Reached.Refused>(reached).key) { "collecting, on $status" }
        }
    }

    @Test
    fun `something other than the surface answering is not read as a refusal it recognises`() {
        // A proxy, a gateway or an error page. There is no key in the body to read, so this says
        // so rather than guessing which refusal it was.
        val client = clientCollecting { respondError(HttpStatusCode.BadGateway, content = "<html>no</html>") }
        val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }

        assertEquals(MessageKey.UNEXPECTED_FAILURE, assertIs<Reached.Refused>(reached).key)
    }

    @Test
    fun `a backend that cannot be reached at all is come back later`() {
        val client = clientSubmitting { throw java.io.IOException("connection refused") }
        val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }

        // The same thing the surface says when it is up and cannot answer. The two differ in what
        // an operator must do, not in what the person who asked can do.
        assertEquals(MessageKey.CANNOT_ANSWER_RIGHT_NOW, assertIs<Reached.Refused>(reached).key)
    }

    /** A name this interface cannot read is not a name it can collect by, so it says so. */
    @Test
    fun `an acceptance this interface cannot read is come back later`() {
        val client = clientSubmitting {
            respond(
                content = """{"notTheField":"nor this"}""",
                status = HttpStatusCode.Accepted,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }

        assertEquals(MessageKey.CANNOT_ANSWER_RIGHT_NOW, assertIs<Reached.Refused>(reached).key)
    }

    @Test
    fun `a body carrying more than this contract knows is still an answer`() {
        // A backend serving a newer contract must not take this interface down.
        val body = """[{"type":"prose","text":"fine"}]"""
        val client = clientCollecting {
            respond(
                content = """{"outcome":{"type":"text","body":$body},"freshness":{},"somethingNew":7}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }

        val outcome = assertIs<Outcome.SendText>(assertIs<Reached.Answered>(reached).response.outcome)
        assertEquals(listOf(Block.Prose("fine")), outcome.body)
    }

    @Test
    fun `an answer's parts are spelled on the wire as heading and prose`() {
        // **The one published fact about the new shape, pinned to the literal spelling.** Which
        // part is a heading is what crosses, and the discriminator is how a reader tells them
        // apart — so the spelling is an interface promise rather than an implementation detail.
        // The evaluation harness reads these keys out of the same body in Python, where no
        // compiler checks them against this declaration: a rename here would leave it scoring
        // every sectioned answer as silence, which is a four-dollar run reporting a retrieval
        // failure that did not happen. Asserted in both directions so a rename cannot pass.
        val body = """[{"type":"heading","text":"Where things stand"},{"type":"prose","text":"a finding"}]"""
        val response = """{"outcome":{"type":"text","body":$body},"freshness":{}}"""
        val client = clientCollecting {
            respond(
                content = response,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }

        val outcome = assertIs<Outcome.SendText>(assertIs<Reached.Answered>(reached).response.outcome)
        assertEquals(
            listOf(Block.Heading("Where things stand"), Block.Prose("a finding")),
            outcome.body,
        ) { "the wire spelling of a part moved, and every other reader of it is spelled by hand" }

        // And the other direction, so the spelling is pinned rather than merely accepted: what
        // this build serialises is what the body above says it does.
        assertEquals(body, json.encodeToString(outcome.body))
    }

    /**
     * An answer already finished is not kept waiting for the poll period.
     *
     * Four of the five shapes compose in well under a second, so asking only after sleeping would
     * put the whole interval in front of every one of them — a change made for the slow shape, paid
     * visibly by the fast ones. Driven with an interval far longer than the case can afford, so a
     * client that slept first would fail on the clock rather than on the assertion.
     */
    @Test
    fun `an answer that is already finished is collected without waiting`() {
        val client = client(
            url = "http://backend:8080",
            budget = GENEROUS_BUDGET_MILLIS,
            interval = SLOW_POLL_MILLIS,
        ) { request ->
            if (request.method == HttpMethod.Post) {
                respond(
                    content = json.encodeToString(AnswerAccepted(WORK_ID)),
                    status = HttpStatusCode.Accepted,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            } else {
                answered()
            }
        }

        val started = System.nanoTime()
        val reached = runBlocking { client.answer(AnswerRequest(text = "a question")) }
        val tookMillis = (System.nanoTime() - started) / 1_000_000

        assertIs<Reached.Answered>(reached)
        assertTrue(tookMillis < SLOW_POLL_MILLIS) { "took ${tookMillis}ms, so a poll period was waited out first" }
    }

    /** A finished answer, as the surface serves one. */
    private fun MockRequestHandleScope.answered(
        response: AnswerResponse = AnswerResponse(Outcome.SendText(listOf(Block.Prose("fine"))), Freshness()),
    ) = respond(
        content = json.encodeToString(response),
        status = HttpStatusCode.OK,
        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
    )

    /**
     * A client whose asking always succeeds, so [collect] scripts only the collecting hop.
     *
     * Most cases are about what collecting makes of a reply, and having each of them restate the
     * acceptance would put the shape of the first hop into every one of them.
     */
    private fun clientCollecting(
        url: String = "http://backend:8080",
        budget: Long = GENEROUS_BUDGET_MILLIS,
        seen: MutableList<HttpRequestData>? = null,
        collect: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpBackendClient = client(url, budget) { request ->
        seen?.add(request)
        if (request.method == HttpMethod.Post) {
            respond(
                content = json.encodeToString(AnswerAccepted(WORK_ID)),
                status = HttpStatusCode.Accepted,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        } else {
            collect(request)
        }
    }

    /** And one where asking is what is being scripted; nothing should reach the second hop. */
    private fun clientSubmitting(
        submit: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpBackendClient = client(url = "http://backend:8080", budget = GENEROUS_BUDGET_MILLIS) { request ->
        if (request.method == HttpMethod.Post) {
            submit(request)
        } else {
            error("Nothing should be collected when asking did not succeed.")
        }
    }

    private fun client(
        url: String,
        budget: Long,
        // Driven at a period a suite can afford. The deployed figures are the defaults and are
        // asserted nowhere here: what they should be is a judgement about one shape's duration,
        // not a property of this mapping. One case raises it, to catch a wait that should not
        // happen at all.
        interval: Long = 1L,
        handle: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpBackendClient = HttpBackendClient(
        settings = BackendSettings(url = url, secret = SECRET),
        engine = MockEngine { request -> handle(request) },
        interval = interval,
        budget = budget,
    )

    private companion object {

        private const val SECRET = "a-shared-secret"

        /** What the surface is scripted to call the work, so a case can assert the route it builds. */
        private const val WORK_ID = "a-piece-of-work"

        /** Long enough that no case here ends by running out of it. */
        private const val GENEROUS_BUDGET_MILLIS = 60_000L

        /**
         * And long enough that a case ending early proves it was not the budget that ended it.
         *
         * The giving-up case needs the two bounds told apart: if the budget were small, a refusal
         * would be consistent with either the hop allowance or the clock.
         */
        private const val HUGE_BUDGET_MILLIS = 3_600_000L

        /** The allowance plus the hop that exceeds it, which is when giving up is correct. */
        private const val FAILURES_BEFORE_GIVING_UP = 4

        /** Long enough that waiting even once would be unmistakable in the elapsed time. */
        private const val SLOW_POLL_MILLIS = 10_000L

        /** Every refusal the surface can serve, and the key each carries. */
        private val refusals = mapOf(
            HttpStatusCode.BadRequest to MessageKey.REQUEST_MALFORMED,
            HttpStatusCode.Unauthorized to MessageKey.REQUEST_UNAUTHORISED,
            HttpStatusCode.UnsupportedMediaType to MessageKey.REQUEST_MALFORMED,
            HttpStatusCode.ServiceUnavailable to MessageKey.CANNOT_ANSWER_RIGHT_NOW,
            HttpStatusCode.InternalServerError to MessageKey.UNEXPECTED_FAILURE,
        )

        /** Configured as the surface configures its own, so these bodies are the real shapes. */
        private val json = Json { explicitNulls = false }
    }
}
