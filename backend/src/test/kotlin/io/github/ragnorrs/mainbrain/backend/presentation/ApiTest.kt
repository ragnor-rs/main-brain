package io.github.ragnorrs.mainbrain.backend.presentation

import io.github.ragnorrs.mainbrain.contract.AnswerAccepted
import io.github.ragnorrs.mainbrain.contract.AnswerRequest
import io.github.ragnorrs.mainbrain.contract.AnswerResponse
import io.github.ragnorrs.mainbrain.contract.Block
import io.github.ragnorrs.mainbrain.contract.Cited
import io.github.ragnorrs.mainbrain.contract.Claimed
import io.github.ragnorrs.mainbrain.contract.Failure
import io.github.ragnorrs.mainbrain.contract.MenuOption
import io.github.ragnorrs.mainbrain.contract.MessageKey
import io.github.ragnorrs.mainbrain.contract.Outcome
import io.github.ragnorrs.mainbrain.contract.SubjectKind
import io.github.ragnorrs.mainbrain.contract.SubjectRef
import io.github.ragnorrs.mainbrain.contract.UnresolvedReport
import io.github.ragnorrs.mainbrain.data.SidecarUnavailable
import io.github.ragnorrs.mainbrain.domain.BackfillProgress
import io.github.ragnorrs.mainbrain.domain.Freshness
import io.github.ragnorrs.mainbrain.domain.Health
import io.github.ragnorrs.mainbrain.domain.IngestStateRepository
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.ModelReplyUnreadable
import io.github.ragnorrs.mainbrain.domain.ModelUnavailable
import io.github.ragnorrs.mainbrain.domain.Pass
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.answer.Answer
import io.github.ragnorrs.mainbrain.domain.answer.Candidate
import io.github.ragnorrs.mainbrain.domain.answer.Channel
import io.github.ragnorrs.mainbrain.domain.answer.CitationCheck
import io.github.ragnorrs.mainbrain.domain.answer.Claim
import io.github.ragnorrs.mainbrain.domain.answer.Exchange
import io.github.ragnorrs.mainbrain.domain.answer.Fault
import io.github.ragnorrs.mainbrain.domain.answer.Horizon
import io.github.ragnorrs.mainbrain.domain.answer.Piece
import io.github.ragnorrs.mainbrain.domain.answer.Progress
import io.github.ragnorrs.mainbrain.domain.answer.Reach
import io.github.ragnorrs.mainbrain.domain.answer.Referent
import io.github.ragnorrs.mainbrain.domain.answer.Retrieval
import io.github.ragnorrs.mainbrain.domain.answer.Runner
import io.github.ragnorrs.mainbrain.domain.answer.ScopeResolver
import io.github.ragnorrs.mainbrain.domain.answer.Shape
import io.github.ragnorrs.mainbrain.domain.answer.ShapeId
import io.github.ragnorrs.mainbrain.domain.answer.ShapeRegistry
import io.github.ragnorrs.mainbrain.domain.answer.Stage
import io.github.ragnorrs.mainbrain.domain.answer.Subject
import io.github.ragnorrs.mainbrain.domain.answer.SubjectSearch
import io.github.ragnorrs.mainbrain.domain.answer.Turn
import io.github.ragnorrs.mainbrain.domain.answer.Variant
import io.github.ragnorrs.mainbrain.domain.entity.CandidateMerge
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Signal
import io.github.ragnorrs.mainbrain.domain.entity.SignalKind
import io.github.ragnorrs.mainbrain.domain.entity.UnresolvedPair
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import io.github.ragnorrs.mainbrain.domain.answer.SubjectKind as DomainSubjectKind

/**
 * The surface, served in-process against fakes.
 *
 * What is worth asserting here is this module's own work and nothing below it: the refusal at the
 * edge, the status code a caller acts on, the error body carrying a key rather than a sentence,
 * and the mapping from each domain answer onto the wire. Whether an answer is any good is not a
 * question an HTTP test can ask, and the runner's own behaviour is tested where the runner is.
 *
 * No database, no model and no socket, which is what lets all of it run in CI.
 */
class ApiTest {

    @Test
    fun `a request with no secret is refused`(): Unit = testApplication {
        serving(runner = runnerSettling(Answer.InsufficientEvidence()))

        val response = jsonClient().post(ANSWER) {
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "anything"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(MessageKey.REQUEST_UNAUTHORISED, response.body<Failure>().key)
    }

    @Test
    fun `a request with the wrong secret is refused`(): Unit = testApplication {
        serving(runner = runnerSettling(Answer.InsufficientEvidence()))

        val response = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, "not-the-secret")
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "anything"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(MessageKey.REQUEST_UNAUTHORISED, response.body<Failure>().key)
    }

    @Test
    fun `nothing is asked on behalf of an unauthorised request`(): Unit = testApplication {
        // The acceptance this file exists for is *rejected before any work is done on its
        // behalf*, which a status code alone does not establish: a surface that answered the
        // question and then refused to return it would look identical from outside. So the
        // runner records whether it was reached.
        var asked = false
        serving(
            runner = runnerSettling(Answer.InsufficientEvidence(), onAsk = { asked = true }),
        )

        jsonClient().post(ANSWER) {
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "anything"))
        }

        assertFalse(asked) { "The answer path ran for a request that carried no secret." }
    }

    @Test
    fun `health is open, because whatever reads it is establishing that the hop works`(): Unit = testApplication {
        serving(runner = null)

        val response = jsonClient().get(HEALTH)

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `health means serving rather than alive`(): Unit = testApplication {
        // The process is up — it is answering this call — and the store is not. A health check
        // that reported this healthy is the one the production deploy would read as a successful
        // release.
        serving(runner = null, serving = false)

        val response = jsonClient().get(HEALTH)

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `health carries no prose`(): Unit = testApplication {
        // The backend has no catalogue and no locale, so a sentence here would be display text
        // from the one process that composes none. The readers are a container healthcheck and a
        // deploy, and both act on the status.
        serving(runner = null)

        assertEquals("", jsonClient().get(HEALTH).bodyAsText())
    }

    @Test
    fun `a blank ask is the caller's mistake rather than a failure`(): Unit = testApplication {
        // The domain type refuses a blank request with its own `require`. Letting that escape
        // would answer 500 for something the caller got wrong, with nothing in the reply to say
        // what.
        serving(runner = runnerSettling(Answer.InsufficientEvidence()))

        val response = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "   "))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(MessageKey.REQUEST_MALFORMED, response.body<Failure>().key)
    }

    @Test
    fun `a command naming no shape is the caller's mistake rather than an answer`(): Unit = testApplication {
        // Answered through the fallback — which is what binding does with a word it does not know —
        // this costs an answering model call at the costliest path, ranks the corpus against text
        // carrying a word that means nothing, and is reported in the log under a shape name there
        // is no shape for. None of that is visible in the reply, which reads as a composed answer
        // about whatever followed the word. So it is refused where a status code can say so.
        var started = false
        serving(runner = runnerSettling(Answer.InsufficientEvidence(), onAsk = { started = true }))

        val response = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "/stats Acme", command = "stats"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(MessageKey.REQUEST_MALFORMED, response.body<Failure>().key)
        // The half the status cannot say, and the whole point of the refusal: nothing was spent.
        assertFalse(started, "A command naming no shape must not reach the runner.")
    }

    @Test
    fun `a command naming any registered shape is answered, the fallback included`(): Unit = testApplication {
        // The other direction, and the half that would otherwise rot: a guard refusing every
        // command makes the answer path unreachable by command at all, and no case above would
        // notice — they ask as free text.
        //
        // **Both words, because they are the two the check must not conflate.** An ordinary
        // registered shape is what a command list offers; the fallback's own identifier is a word
        // no list offers and the surface still answers, which is how the evaluation set binds it.
        // A guard narrowed to the declared commands would refuse the second and red here rather
        // than in a four-dollar run.
        serving(runner = runnerSettling(Answer.InsufficientEvidence()))

        listOf(REGISTERED_SHAPE, THE_FALLBACK).forEach { named ->
            val response = jsonClient().post(ANSWER) {
                header(SECRET_HEADER, SECRET)
                contentType(ContentType.Application.Json)
                setBody(AnswerRequest(text = "what happened", command = named))
            }

            assertEquals(HttpStatusCode.OK, response.status) {
                "The surface refused $named, which names a shape this build answers."
            }
        }
    }

    @Test
    fun `an emptied command is answered rather than refused as naming no shape`(): Unit = testApplication {
        // The counterpart of the emptied-subject case below, and the half the refusal above would
        // otherwise swallow: a caller that is not the interface shipped here may send the field
        // empty meaning *no command*, which is the reading the domain already takes of one. Without
        // the normalisation at the boundary, the blank word reaches the check that refuses a word
        // naming no shape and is refused — so a request nobody got wrong comes back a 400. Measured:
        // drop the `takeIf` and this is the only case that reds.
        serving(runner = runnerSettling(Answer.InsufficientEvidence()))

        val response = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "what happened", command = "   "))
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `a body that is not a request is the caller's mistake too`(): Unit = testApplication {
        serving(runner = runnerSettling(Answer.InsufficientEvidence()))

        val response = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody("{\"nothing\":\"the backend reads\"}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(MessageKey.REQUEST_MALFORMED, response.body<Failure>().key)
    }

    @Test
    fun `a body sent as the wrong type is refused with a key, like every other refusal`(): Unit = testApplication {
        // Ktor's content negotiation refuses a type it holds no converter for by itself, with
        // 415 and an empty body. The status is right and is kept; what it lacked was a key, which
        // made this the one failure a caller could meet that carried nothing to render. `curl -d`
        // sends form-urlencoded by default, so it is the first thing anyone reaching for this hop
        // by hand will hit.
        serving(runner = runnerSettling(Answer.InsufficientEvidence()))

        val response = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("text=what+happened")
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertEquals(MessageKey.REQUEST_MALFORMED, response.body<Failure>().key)
    }

    @Test
    fun `an ambiguity with nothing to choose between claims nothing was searched`(): Unit = testApplication {
        // The runner cannot produce this — it builds the case from a resolution holding more than
        // one option — but a stage may settle with it. What must not happen is the reply offering
        // the kind of what *matched* as the kind that was *searched*, which would tell the reader
        // a kind was looked through on the evidence that something of it was found.
        serving(
            runner = runnerSettling(
                Answer.SubjectUnclear(listOf(candidate("the only one", DomainSubjectKind.CLIENT))),
            ),
        )

        val status = assertIs<Outcome.SendStatus>(answerTo("status").outcome)

        assertEquals(MessageKey.SUBJECT_NOT_FOUND, status.key)
        assertTrue(status.searched.isEmpty()) { "Reported ${status.searched} as searched; those matched." }
        assertNull(status.subjectName)
    }

    @Test
    fun `a backend configured to answer nothing says so rather than failing`(): Unit = testApplication {
        // What a credential-free stack serves, which is how the store and ingestion are exercised
        // locally. A refusal naming it is what keeps that deployment usable rather than broken.
        serving(runner = null)

        val response = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "anything"))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(MessageKey.CANNOT_ANSWER_RIGHT_NOW, response.body<Failure>().key)
    }

    /**
     * A refusing dependency is *come back later*, not a defect in this build.
     *
     * Thrown deliberately rather than returned — a transport fault is not something the provider
     * did — so nothing on the answer path converts it and the surface is where it has to be
     * claimed. Unclaimed it reaches the catch-all and reports a 500, which is the one thing
     * [MessageKey.UNEXPECTED_FAILURE] says it is not: something nobody anticipated.
     *
     * The sidecar's own type rather than the port's, because that is what a deployed stack
     * actually throws. It arrives here as an `EmbeddingsUnavailable`, and this is what notices if
     * it stops being one.
     */
    @Test
    fun `a sidecar that is not answering is come back later, not an unexpected failure`(): Unit = testApplication {
        serving(
            runner = runnerSettling(
                Answer.InsufficientEvidence(),
                onAsk = { throw SidecarUnavailable("embed returned 503 Service Unavailable") },
            ),
        )

        val response = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "what happened"))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(MessageKey.CANNOT_ANSWER_RIGHT_NOW, response.body<Failure>().key)
    }

    @Test
    fun `a model that cannot be reached is come back later too`(): Unit = testApplication {
        serving(
            runner = runnerSettling(
                Answer.InsufficientEvidence(),
                onAsk = { throw ModelUnavailable("The model could not be reached.") },
            ),
        )

        val response = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "what happened"))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(MessageKey.CANNOT_ANSWER_RIGHT_NOW, response.body<Failure>().key)
    }

    /**
     * And the boundary, which is the half of the distinction worth pinning.
     *
     * An unreadable reply is not worth another attempt — what clears it is a build that knows the
     * value, which is a deploy rather than a wait. Reported as *come back later* it would be a
     * lie, so this one stays a 500 and a handler that swept up every provider failure alike would
     * be caught here.
     */
    @Test
    fun `a reply this build cannot read is not come back later`(): Unit = testApplication {
        serving(
            runner = runnerSettling(
                Answer.InsufficientEvidence(),
                onAsk = { throw ModelReplyUnreadable("The provider's reply could not be read.") },
            ),
        )

        val response = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "what happened"))
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(MessageKey.UNEXPECTED_FAILURE, response.body<Failure>().key)
    }

    @Test
    fun `an unexpected failure carries a key rather than a sentence`(): Unit = testApplication {
        // Ktor's own default for an escaped exception is a plain-text line, which is prose from a
        // process with no catalogue — and on some configurations a stack trace, which is worse.
        serving(runner = runnerSettling(Answer.InsufficientEvidence(), onAsk = { error("a seam gave way") }))

        val response = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "anything"))
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(MessageKey.UNEXPECTED_FAILURE, response.body<Failure>().key)
    }

    @Test
    fun `one variant sends text`(): Unit = testApplication {
        serving(runner = runnerSettling(Answer.Composed(listOf(Variant("the single draft")))))

        val outcome = answerTo("draft something").outcome

        assertEquals(Outcome.SendText(listOf(Block.Prose("the single draft"))), outcome)
    }

    private fun aSpan(n: Int) = SpanId(
        kind = RecordKind.MESSAGE,
        record = ExternalId(Source.TELEGRAM, n.toString()),
    )

    private fun aRetrievedSpan(n: Int) = Span(
        id = aSpan(n),
        text = "the material the claim rests on",
        sourceTime = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `the assertions reach the reader, not only the prose around them`(): Unit = testApplication {
        // **Most of an answer is its claim lines, and sending the prose alone discarded them.**
        // The reply format separates claims from prose so the grounding gate has something to
        // check — an assertion about the corpus cites spans, composed prose does not — and that
        // separation is about checking rather than about what to show. Measured on one real
        // question against the live index: two lines of prose and six claim lines, so the reader
        // was getting under a fifth of the answer, as a summary with every specific stripped out.
        // A grounded answer that reads as a hedge is the worst possible report of retrieval that
        // is working.
        val answer = Answer.Composed(
            listOf(
                Variant(
                    prose = "the summary",
                    claims = listOf(
                        Claim("the first thing that was agreed", setOf(aSpan(1))),
                        Claim("the second thing that was agreed", setOf(aSpan(2))),
                    ),
                ),
            ),
        )
        serving(runner = runnerSettling(answer, retrieved = listOf(aRetrievedSpan(1), aRetrievedSpan(2))))

        val outcome = answerTo("what was agreed").outcome

        val sent = assertIs<Outcome.SendText>(outcome).prose
        assertTrue(sent.contains("the summary"), sent)
        assertTrue(sent.contains("the first thing that was agreed"), sent)
        assertTrue(sent.contains("the second thing that was agreed"), sent)
        // The identifiers stay out. Validation has already read them, and to a reader they are a
        // line of machine text per assertion with nothing to be done about it.
        assertFalse(sent.contains("MESSAGE/"), sent)
    }

    @Test
    fun `the identifiers travel beside the prose, addressable rather than printed`(): Unit = testApplication {
        // **The other half of the decision above.** Keeping identifiers out of what a reader sees
        // is not the same as throwing them away: an interface that means to link an assertion to
        // the message it rests on needs them, and so does any check that the mapping delivered
        // what the runner validated. That check is invisible in every other dimension — grounding
        // passed, the prose reads fine, and a claim nobody can get to looks exactly like one they
        // can.
        val answer = Answer.Composed(
            listOf(
                Variant(
                    prose = "the summary",
                    claims = listOf(Claim("the price was agreed", setOf(aSpan(2), aSpan(1)))),
                ),
            ),
        )
        serving(
            runner = runnerSettling(
                answer,
                retrieved = listOf(aRetrievedSpan(1), aRetrievedSpan(2)),
                // Named for one of the two and not the other, which is both renderable states of a
                // citation in one answer. A fixture naming every author would pass with the absent
                // case unimplemented, and the corpus holds no name for a turn often enough to care.
                turns = mapOf(
                    ExternalId(Source.TELEGRAM, "1") to
                        Turn(
                            ExternalId(Source.TELEGRAM, "a thread"),
                            Channel.OneToOne,
                            author = "who wrote it",
                        ),
                ),
            ),
        )

        val outcome = answerTo("what was agreed").outcome

        assertEquals(
            listOf(
                Claimed(
                    text = "the price was agreed",
                    // Sorted on the identifier, because a set's iteration order is an accident of
                    // how it was built: two identical answers would otherwise serialise two ways.
                    // Cited here in the reverse order to make that sort observable rather than
                    // incidental.
                    cites = listOf(
                        // The identifier resolves and the two beside it are what an interface shows
                        // in its place. Without them a reader is handed machine text or nothing,
                        // which is what this answer arrived as for as long as the field was alone.
                        Cited(
                            span = "MESSAGE/TELEGRAM/1#0",
                            sourceTime = "2026-01-01T00:00:00Z",
                            author = "who wrote it",
                        ),
                        Cited(span = "MESSAGE/TELEGRAM/2#0", sourceTime = "2026-01-01T00:00:00Z"),
                    ),
                ),
            ),
            assertIs<Outcome.SendText>(outcome).claims,
        )
    }

    @Test
    fun `an answer that asserts nothing sends no claims`(): Unit = testApplication {
        // The drafting case. A composed reply is content rather than an assertion about the
        // corpus, so there is nothing in it to cite and an empty list is the honest answer.
        serving(runner = runnerSettling(Answer.Composed(listOf(Variant("Dear sir,")))))

        val outcome = answerTo("draft a reply").outcome

        assertEquals(emptyList<Claimed>(), assertIs<Outcome.SendText>(outcome).claims)
    }

    @Test
    fun `an assertion is sent under the heading it was written below`(): Unit = testApplication {
        // Measured on a real reply: the shape that answers in sections wrote a heading, the
        // findings under it, then the next heading — and because every finding was an assertion,
        // the reader got three headings with nothing beneath them and the findings in a block at
        // the end. Asserted by position rather than by presence: every one of those findings was
        // *present* in the broken reply too, just nowhere near the heading it belonged to.
        val first = Claim("the migration was agreed", setOf(aSpan(1)))
        val second = Claim("nobody owns the rollout", setOf(aSpan(2)))
        val answer = Answer.Composed(
            listOf(
                Variant(
                    prose = "Where things stand:\n\nStill open:",
                    claims = listOf(first, second),
                    body = listOf(
                        Piece.Heading("Where things stand:"),
                        Piece.Cited(first),
                        Piece.Prose(""),
                        Piece.Heading("Still open:"),
                        Piece.Cited(second),
                    ),
                ),
            ),
        )
        serving(runner = runnerSettling(answer, retrieved = listOf(aRetrievedSpan(1), aRetrievedSpan(2))))

        val sent = assertIs<Outcome.SendText>(answerTo("what was agreed").outcome).prose

        assertTrue(sent.indexOf("Where things stand:") < sent.indexOf(first.text), sent)
        assertTrue(sent.indexOf(first.text) < sent.indexOf("Still open:"), sent)
        assertTrue(sent.indexOf("Still open:") < sent.indexOf(second.text), sent)
        assertFalse(sent.contains("MESSAGE/"), sent)
    }

    @Test
    fun `the bot's live-wire body is the one this surface serialises`(): Unit = testApplication {
        // **Not an assertion about the surface but about a fixture, and the seam it closes is one
        // no single test can span.** `api()` and `HttpBackendClient` are internal to different
        // modules, so the far side of the bot's live-wire test has to be a stub — and a stub
        // nobody checked against the real serialiser is a test of an assumption. The bot's case
        // is driven by a committed body, and this is what keeps that body the one this surface
        // really produces: a change to the wire shape fails here, naming the file to refresh,
        // rather than leaving the bot passing against a format nothing emits any more.
        //
        // Six sections of six findings. The shape the probes measured is six sections of roughly
        // twenty, which no longer reaches the platform's limit now that what a reply rests on is
        // one block; sized past the limit deliberately, because a body that does not split says
        // nothing about splitting.
        val sections = listOf(
            "Where things stand",
            "Still open",
            "Agreed and settled",
            "Waiting on the other side",
            "Costs and scope",
            "Next steps",
        )
        val claims = mutableListOf<Claim>()
        val body = mutableListOf<Piece>()
        sections.forEachIndexed { section, heading ->
            body += Piece.Heading(heading)
            (1..6).forEach { finding ->
                val claim = Claim(
                    "the point settled in section ${section + 1}.$finding, recorded against the " +
                        "thread it was agreed in and carrying the figure that was quoted",
                    setOf(aSpan(section * 10 + finding)),
                )
                claims += claim
                body += Piece.Cited(claim)
            }
            // The blank piece the model writes between a section and the next heading.
            if (section < sections.lastIndex) body += Piece.Prose("")
        }
        val answer = Answer.Composed(
            listOf(Variant(prose = sections.joinToString("\n\n"), claims = claims, body = body)),
        )
        serving(
            runner = runnerSettling(
                answer,
                retrieved = claims.flatMap { it.cites }.map { span ->
                    Span(
                        id = span,
                        text = "the material the claim rests on",
                        sourceTime = Instant.parse("2026-10-12T09:00:00Z"),
                    )
                },
            ),
        )

        val wire = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "what was agreed"))
        }.bodyAsText()

        // Proof the body is of the shape under test rather than of whatever came back.
        assertTrue(wire.contains("\"type\":\"heading\""), wire.take(200))
        assertTrue(wire.contains("\"type\":\"prose\""), wire.take(200))

        assertIsTheCommittedBody(wire)
    }

    /**
     * Hold [wire] to the body `:bot`'s live-wire case is driven by, naming the file when it parts.
     *
     * The path comes from the build rather than from here: a path from this file into another
     * module's resources would be this file holding a layout decision it has no way to keep true.
     */
    private fun assertIsTheCommittedBody(wire: String) {
        val named = checkNotNull(System.getProperty(WIRE_FIXTURE)) {
            "No $WIRE_FIXTURE. The build hands this case the path of the body `:bot` is driven " +
                "by, so run it through Gradle rather than from an IDE's own test runner."
        }
        val fixture = File(named)
        if (wire == fixture.readText()) return
        val refreshed = File.createTempFile("wire-", ".json").apply { writeText(wire) }
        fail<Unit>(
            "The wire body this surface produces is no longer the one `:bot`'s LiveWirePathTest " +
                "is driven by, so that test is passing against a format nothing emits. Copy " +
                "$refreshed over ${fixture.path} and read the diff before trusting it.",
        )
    }

    @Test
    fun `a heading crosses to the wire as a heading rather than as the words in it`(): Unit = testApplication {
        // **The cause of this card's formatting half.** The marking exists all the way up: the
        // model is instructed to mark a heading, the domain parses it into a kind of its own
        // because the grounding gate has to tell them apart, and then this mapping flattened the
        // body to one string and threw the distinction away. No interface could get it back, so
        // every heading reached six measured replies as a short line of ordinary text.
        val first = Claim("the migration was agreed", setOf(aSpan(1)))
        val second = Claim("nobody owns the rollout", setOf(aSpan(2)))
        val answer = Answer.Composed(
            listOf(
                Variant(
                    prose = "Where things stand:\n\nStill open:",
                    claims = listOf(first, second),
                    body = listOf(
                        Piece.Heading("Where things stand:"),
                        Piece.Cited(first),
                        Piece.Heading("Still open:"),
                        Piece.Cited(second),
                    ),
                ),
            ),
        )
        serving(runner = runnerSettling(answer, retrieved = listOf(aRetrievedSpan(1), aRetrievedSpan(2))))

        val body = assertIs<Outcome.SendText>(answerTo("what was agreed").outcome).body

        assertEquals(
            listOf(
                Block.Heading("Where things stand:"),
                Block.Prose("• ${first.text}"),
                Block.Heading("Still open:"),
                Block.Prose("• ${second.text}"),
            ),
            body,
        ) { "the heading marking did not survive the crossing: $body" }
    }

    @Test
    fun `prose and the findings beside it arrive as one part rather than one per line`(): Unit = testApplication {
        // A section is read together, so splitting it per line here would be this mapping deciding
        // a layout — which is the interface's. What the wire owes is the one distinction an
        // interface cannot recover, and nothing finer.
        val claim = Claim("the migration was agreed", setOf(aSpan(1)))
        val answer = Answer.Composed(
            listOf(
                Variant(
                    prose = "Here is where it stands.",
                    claims = listOf(claim),
                    body = listOf(
                        Piece.Prose("Here is where it stands."),
                        Piece.Cited(claim),
                        Piece.Prose("That is all of it."),
                    ),
                ),
            ),
        )
        serving(runner = runnerSettling(answer, retrieved = listOf(aRetrievedSpan(1))))

        val body = assertIs<Outcome.SendText>(answerTo("what was agreed").outcome).body

        assertEquals(
            listOf(Block.Prose("Here is where it stands.\n• ${claim.text}\nThat is all of it.")),
            body,
        ) { "an unsectioned answer is one part: $body" }
    }

    @Test
    fun `a heading whose only finding is dropped is not sent standing alone`(): Unit = testApplication {
        // The grounding gate removes a claim citing material nothing retrieved, and the heading
        // that finding was written under has nothing else beneath it. A reader is then shown a
        // label for a section that is not there, which reads as an answer that lost something
        // rather than as one that never had it — and the heading is the half that cannot be
        // checked, so it asserts a body of work with no finding to support it.
        val first = Claim("the migration was agreed", setOf(aSpan(1)))
        val second = Claim("nobody owns the rollout", setOf(aSpan(2)))
        val answer = Answer.Composed(
            listOf(
                Variant(
                    prose = "Where things stand:\n\nStill open:",
                    claims = listOf(first, second),
                    body = listOf(
                        Piece.Heading("Where things stand:"),
                        Piece.Cited(first),
                        Piece.Prose(""),
                        Piece.Heading("Still open:"),
                        Piece.Cited(second),
                    ),
                ),
            ),
        )
        // Only the first finding's span was retrieved, so the second is dropped and the heading
        // above it is left with nothing.
        serving(runner = runnerSettling(answer, retrieved = listOf(aRetrievedSpan(1))))

        val sent = assertIs<Outcome.SendText>(answerTo("what was agreed").outcome).prose

        assertTrue(sent.contains(first.text), sent)
        assertFalse(sent.contains(second.text), sent)
        assertFalse(sent.contains("Still open:"), sent)
    }

    @Test
    fun `a heading orphaned mid-answer is not sent above the next heading`(): Unit = testApplication {
        // The interior case, which is worse than a heading left at the end: the collapse closes
        // the blank line the removal left, so the orphan ends up directly above the next heading
        // and the reader is shown two labels in a row with one section between them. Asserted
        // separately because the trailing case is reached by a different arm of the collapse.
        val first = Claim("the migration was agreed", setOf(aSpan(1)))
        val second = Claim("nobody owns the rollout", setOf(aSpan(2)))
        val third = Claim("the review is booked", setOf(aSpan(3)))
        val answer = Answer.Composed(
            listOf(
                Variant(
                    prose = "Where things stand:\n\nStill open:\n\nNext:",
                    claims = listOf(first, second, third),
                    body = listOf(
                        Piece.Heading("Where things stand:"),
                        Piece.Cited(first),
                        Piece.Prose(""),
                        Piece.Heading("Still open:"),
                        Piece.Cited(second),
                        Piece.Prose(""),
                        Piece.Heading("Next:"),
                        Piece.Cited(third),
                    ),
                ),
            ),
        )
        // The middle section's only finding is the one nothing retrieved.
        serving(runner = runnerSettling(answer, retrieved = listOf(aRetrievedSpan(1), aRetrievedSpan(3))))

        val sent = assertIs<Outcome.SendText>(answerTo("what was agreed").outcome).prose

        assertFalse(sent.contains("Still open:"), sent)
        assertTrue(sent.indexOf("Where things stand:") < sent.indexOf(first.text), sent)
        assertTrue(sent.indexOf(first.text) < sent.indexOf("Next:"), sent)
        assertTrue(sent.indexOf("Next:") < sent.indexOf(third.text), sent)
    }

    @Test
    fun `a draft carries no assertions and is sent exactly as composed`(): Unit = testApplication {
        // The other half: a drafted reply is prose to be copied, so a renderer that decorated it
        // would hand back something that cannot be sent as written. What this case does *not*
        // establish is that the same holds on a menu — the variant here carries no claims, so both
        // forms of it are the same string. The case below is the one that separates them.
        serving(runner = runnerSettling(Answer.Composed(listOf(Variant("a reply to send as it is")))))

        val outcome = answerTo("draft something").outcome

        assertEquals(Outcome.SendText(listOf(Block.Prose("a reply to send as it is"))), outcome)
    }

    @Test
    fun `several variants serialise as alternatives rather than as one reply`(): Unit = testApplication {
        // The acceptance point this whole wire format turns on. Concatenating them would make the
        // interface unable to tell them apart, and one long message is what a reader gets.
        serving(
            runner = runnerSettling(
                Answer.Composed(listOf(Variant("the first draft"), Variant("the second"), Variant("the third"))),
            ),
        )

        val outcome = answerTo("draft something")

        val menu = assertIs<Outcome.SendMenu>(outcome.outcome)
        assertEquals(MessageKey.CHOOSE_A_DRAFT, menu.prompt)
        assertEquals(
            listOf("the first draft", "the second", "the third"),
            menu.options.map { assertIs<MenuOption.Draft>(it).prose },
        )
    }

    @Test
    fun `an alternative carries none of the claims the model wrote beside it`(): Unit = testApplication {
        // **The defect this exists to catch reached a reader.** Every draft arrived with the model's
        // claim lines inside the same message, and the assertions are in the third person about the
        // person being written to — so copying the message, which is precisely what the menu's
        // prompt tells the reader to do, sent them a note reporting what they themselves had said.
        //
        // **The arrangement is the ordinary one and not a contrived input.** The drafting shape
        // instructs the model to keep assertions out of the draft and put them in separate cited
        // lines outside it, so a variant carrying prose and claims together is what complying with
        // the prompt produces. The claims-free variants the two cases above use cannot see this at
        // all: with no claims the reading order and the prose are the same string.
        //
        // Calibrated by folding them back in — building the option from the variant's body, which
        // is what this did, fails on the claim text below.
        val aside = Claim("the date was moved at their request", setOf(aSpan(1)))
        val answer = Answer.Composed(
            listOf(
                Variant(prose = "Dear sir, the fifth suits us.", claims = listOf(aside)),
                Variant(prose = "Dear sir, let us say the fifth.", claims = listOf(aside)),
            ),
        )
        serving(runner = runnerSettling(answer, retrieved = listOf(aRetrievedSpan(1))))

        val outcome = answerTo("draft something").outcome

        val menu = assertIs<Outcome.SendMenu>(outcome)
        val drafts = menu.options.map { assertIs<MenuOption.Draft>(it).prose }
        assertEquals(listOf("Dear sir, the fifth suits us.", "Dear sir, let us say the fifth."), drafts)
        drafts.forEach { draft ->
            // The assertion itself, and the marker that would have set it off. Both, because the
            // text is what gets sent to the wrong person and the marker is what makes a sendable
            // message read as a report.
            assertFalse(draft.contains(aside.text), draft)
            assertFalse(draft.contains("•"), draft)
        }
    }

    @Test
    fun `the subjects that matched and lost come back with the answer`(): Unit = testApplication {
        // An answer about the wrong subject is the failure that is invisible afterwards, so the
        // alternative has to reach the reader for it to be recoverable in one turn.
        serving(
            runner = runnerSettling(
                Answer.Composed(listOf(Variant("the draft"))),
                alternatives = listOf(candidate("the other client", DomainSubjectKind.CLIENT)),
            ),
        )

        val response = answerTo("draft something")

        assertEquals(1, response.notUsed.size)
        assertEquals("the other client", response.notUsed.single().label)
        assertEquals(SubjectKind.CLIENT, response.notUsed.single().kind)
    }

    @Test
    fun `an empty corpus is an answer rather than an error`(): Unit = testApplication {
        serving(runner = runnerSettling(Answer.InsufficientEvidence()))

        val response = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "what happened"))
        }

        // Not a 4xx and not a 5xx: nothing went wrong, and the honest reply is that there was
        // nothing to answer from.
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            Outcome.SendStatus(MessageKey.NOTHING_TO_ANSWER_FROM),
            response.body<AnswerResponse>().outcome,
        )
    }

    @Test
    fun `an answer nobody could read is not reported as an empty corpus`(): Unit = testApplication {
        // **The two statements are about different things, and one key for both made the wrong one.**
        // An empty corpus is a fact about the material; a request that spent two calls and could not
        // read the answer back is a fact about this service. Rendered as an absence it sends whoever
        // asked looking for a gap that is not there, and the thing to do instead — ask again — is
        // not what an absence calls for. Nothing here retrieves anything, deliberately: a shape that
        // declares it does not read the corpus reaches the model on an empty set as a matter of
        // course, so the key may not rest on retrieval.
        serving(runner = runnerGivingUpUnreadable())

        val response = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "what happened"))
        }

        // Still an answer rather than an error: the request was served, and what could not be read
        // is reported in the reply rather than as a status code the interface renders as a fault.
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            Outcome.SendStatus(MessageKey.ANSWER_UNREADABLE),
            response.body<AnswerResponse>().outcome,
        )
    }

    @Test
    fun `an ambiguous name asks which was meant`(): Unit = testApplication {
        serving(
            runner = runnerSettling(
                Answer.SubjectUnclear(
                    listOf(
                        candidate("one of two", DomainSubjectKind.CONTACT),
                        candidate("the other", DomainSubjectKind.CLIENT),
                    ),
                ),
            ),
        )

        val menu = assertIs<Outcome.SendMenu>(answerTo("status").outcome)

        assertEquals(MessageKey.SUBJECT_AMBIGUOUS, menu.prompt)
        assertEquals(
            listOf("one of two", "the other"),
            menu.options.map { assertIs<MenuOption.Subject>(it).subject.label },
        )
    }

    @Test
    fun `what is logged about an answer says how much of it rested on the corpus`() {
        // The count is the reason the line exists: a request that spends no model call at all left
        // no trace whatsoever, which is what made an ambiguous name indistinguishable in the log
        // from a request that never arrived. Nought claims is the ordinary reading for a shape
        // whose deliverable is composed text, which is what this asserts; a shape whose answer is
        // its assertions never reaches this line with nought, because the runner refuses that
        // reply.
        assertEquals(
            "1 variant(s), 2 claim(s)",
            Answer.Composed(listOf(variant(claims = 2))).asLogged(),
        )
        assertEquals(
            "1 variant(s), 0 claim(s)",
            Answer.Composed(listOf(variant(claims = 0))).asLogged(),
        )
        assertEquals("nothing to answer from", Answer.InsufficientEvidence().asLogged())
        // Apart from the line above, because what separates them is the two model calls that were
        // paid for. Conflated here, a request that spent its ceiling twice over would read in the
        // log as the cheapest answer the system produces.
        assertEquals(
            "gave up on a reply that could not be read, after two model calls",
            Answer.Unreadable().asLogged(),
        )
        assertEquals(
            "subject unclear, 2 reading(s) offered",
            Answer.SubjectUnclear(
                listOf(
                    candidate("one", DomainSubjectKind.CONTACT),
                    candidate("another", DomainSubjectKind.CLIENT),
                ),
            ).asLogged(),
        )
    }

    @Test
    fun `an option's handle travels, because it is what the reader answers with`(): Unit = testApplication {
        // A label is not a key and a handle is: it is the only thing on a menu that resolves to one
        // of two people who share a display name. Dropped on the wire, the menu lists the same name
        // twice and every answer to it returns the same menu.
        serving(
            runner = runnerSettling(
                Answer.SubjectUnclear(
                    listOf(
                        candidate("one name, two people", DomainSubjectKind.CONTACT, handle = "the-one"),
                        candidate("one name, two people", DomainSubjectKind.CONTACT, handle = "the-other"),
                    ),
                ),
            ),
        )

        val menu = assertIs<Outcome.SendMenu>(answerTo("status").outcome)

        assertEquals(
            listOf("the-one", "the-other"),
            menu.options.map { assertIs<MenuOption.Subject>(it).subject.handle },
        )
    }

    @Test
    fun `a choice nobody could make is said rather than offered`(): Unit = testApplication {
        // Two people of one display name with no handle between them. A menu is answered by naming
        // the option that was meant, so this one is answered only by the name that was already
        // ambiguous — and the reader is returned the same menu for as long as they try. Saying so is
        // the honest reply; offering the choice reads as progress and is not.
        serving(
            runner = runnerSettling(
                Answer.SubjectUnclear(
                    listOf(
                        candidate("the same name", DomainSubjectKind.CONTACT),
                        candidate("the same name", DomainSubjectKind.CONTACT),
                    ),
                ),
            ),
        )

        val status = assertIs<Outcome.SendStatus>(answerTo("status").outcome)

        assertEquals(MessageKey.SUBJECT_INDISTINGUISHABLE, status.key)
    }

    @Test
    fun `two options carrying one handle cannot be told apart by it`(): Unit = testApplication {
        // A handle is the stronger name and still not a unique one: a platform releases one and the
        // next person takes it, and the corpus keeps every claim it ever saw. Treated as nameable
        // because it is merely present, both options pass the guard, the menu is offered, and naming
        // the handle returns the same menu — the loop this reply kind exists to end, escaping through
        // the check meant to catch it.
        serving(
            runner = runnerSettling(
                Answer.SubjectUnclear(
                    listOf(
                        candidate("one name", DomainSubjectKind.CONTACT, handle = "reused"),
                        candidate("another name", DomainSubjectKind.CONTACT, handle = "reused"),
                    ),
                ),
            ),
        )

        val status = assertIs<Outcome.SendStatus>(answerTo("status").outcome)

        assertEquals(MessageKey.SUBJECT_INDISTINGUISHABLE, status.key)
    }

    @Test
    fun `labels differing only in case are not two names a reader could choose between`(): Unit =
        testApplication {
            // The read these names go back through lowercases and collapses whitespace, so these are
            // one name to it. Compared raw they are two, and the menu is then offered on the strength
            // of a difference naming either one cannot express.
            serving(
                runner = runnerSettling(
                    Answer.SubjectUnclear(
                        listOf(
                            candidate("ANNA", DomainSubjectKind.CONTACT),
                            candidate("Anna", DomainSubjectKind.CONTACT),
                        ),
                    ),
                ),
            )

            val status = assertIs<Outcome.SendStatus>(answerTo("status").outcome)

            assertEquals(MessageKey.SUBJECT_INDISTINGUISHABLE, status.key)
        }

    @Test
    fun `a handle and a label that read the same are one name, not two`(): Unit = testApplication {
        // Naming it searches display names and handles alike, so a handle on one option and a label
        // on another that read identically are not something a reader can choose between.
        serving(
            runner = runnerSettling(
                Answer.SubjectUnclear(
                    listOf(
                        candidate("a display name", DomainSubjectKind.CONTACT, handle = "overlap"),
                        candidate("overlap", DomainSubjectKind.CONVERSATION),
                    ),
                ),
            ),
        )

        val status = assertIs<Outcome.SendStatus>(answerTo("status").outcome)

        assertEquals(MessageKey.SUBJECT_INDISTINGUISHABLE, status.key)
    }

    @Test
    fun `a menu still comes when one option can be named, even if another cannot`(): Unit = testApplication {
        // The weaker half of the rule, and deliberate: an option nothing distinguishes does not
        // deny the reader the ones that work. Refusing the whole menu would withdraw a choice that
        // could have been made over one that could not.
        serving(
            runner = runnerSettling(
                Answer.SubjectUnclear(
                    listOf(
                        candidate("shared", DomainSubjectKind.CONTACT, handle = "nameable"),
                        candidate("shared", DomainSubjectKind.CONVERSATION),
                    ),
                ),
            ),
        )

        val menu = assertIs<Outcome.SendMenu>(answerTo("status").outcome)

        assertEquals(MessageKey.SUBJECT_AMBIGUOUS, menu.prompt)
    }

    @Test
    fun `a name that matched nothing says what was looked through`(): Unit = testApplication {
        // Which is what lets a reader tell a misspelling from a kind the shape never searches.
        serving(
            runner = runnerSettling(
                Answer.SubjectUnknown(
                    // Inserted in the order the sort has to undo. A set has no order and
                    // iterates in insertion order, so a set built the other way round would
                    // assert nothing about the sort — it would pass whether the mapping sorted
                    // or not, which is the shape of a check that only ever saw its author's
                    // example.
                    searched = setOf(DomainSubjectKind.CLIENT, DomainSubjectKind.CONTACT),
                    name = "a name nobody has",
                ),
            ),
        )

        val status = assertIs<Outcome.SendStatus>(answerTo("status").outcome)

        assertEquals(MessageKey.SUBJECT_NOT_FOUND, status.key)
        assertEquals("a name nobody has", status.subjectName)
        assertEquals(listOf(SubjectKind.CONTACT, SubjectKind.CLIENT), status.searched)
    }

    @Test
    fun `an emptied subject name is answered rather than failing the request`(): Unit = testApplication {
        // The domain type refuses a blank name, because which absence a request means decides
        // whether a shape sweeps the inbox or asks who was meant. On the wire the field is a plain
        // optional string, so a caller that is not the interface shipped here may send it empty
        // meaning *no argument* — and that caller got nothing wrong, so it is normalised here
        // rather than raised at them as a malformed request or a server error.
        //
        // **What this pins is that the request survives, not which absence it became.** The fake
        // shape accepts no subject, so resolution is skipped and nothing here can see the reading.
        // Dropping the normalisation makes the domain's `require` escape as a 500, which is the
        // half worth holding at this seam; which absence an emptied field means is settled where
        // the states are, in `:domain`.
        serving(runner = runnerSettling(Answer.InsufficientEvidence()))

        val outcome = answerTo("where did we leave off", subjectName = "   ").outcome

        // Answered rather than refused as malformed, and no exception escaped as a 500.
        val status = assertIs<Outcome.SendStatus>(outcome)
        assertEquals(MessageKey.NOTHING_TO_ANSWER_FROM, status.key)
    }

    @Test
    fun `a name the corpus spells differently carries the spellings, not an absence`(): Unit = testApplication {
        // The three replies a failed resolution can make are told apart by their key, and this is
        // the one the pair before it stopped short of: the name arrived, nothing matched it, and the
        // corpus holds a name spelled nearly like it. Rendered as nothing-found it tells a reader to
        // stop looking; carried as spellings it is one more question.
        serving(
            runner = runnerSettling(
                Answer.SubjectNearest(
                    nearest = listOf(
                        candidate("Arbour Warden", DomainSubjectKind.CONTACT, handle = "arbour"),
                        candidate("Arbitrage Desk", DomainSubjectKind.CLIENT),
                    ),
                    // Inserted in the order the sort has to undo, for the reason the sibling case
                    // gives: a set iterating in insertion order would otherwise assert nothing.
                    searched = setOf(DomainSubjectKind.CLIENT, DomainSubjectKind.CONTACT),
                    name = "Arbitrage",
                ),
            ),
        )

        val status = assertIs<Outcome.SendStatus>(answerTo("status Arbitrage").outcome)

        assertEquals(MessageKey.SUBJECT_NEAREST_NAMES, status.key)
        assertEquals("Arbitrage", status.subjectName)
        // The near forms travel in their own field. Filled from `searched` they would tell a reader
        // a kind was looked through on the evidence that something of that kind was found, which is
        // the confusion the sibling arm's note refuses.
        assertEquals(listOf("Arbour Warden", "Arbitrage Desk"), status.nearest.map { it.label })
        assertEquals(listOf(SubjectKind.CONTACT, SubjectKind.CLIENT), status.nearest.map { it.kind })
        assertEquals("arbour", status.nearest.first().handle)
        assertEquals(listOf(SubjectKind.CONTACT, SubjectKind.CLIENT), status.searched)
    }

    @Test
    fun `a reply reporting near spellings and carrying none is refused`() {
        // The key and the list are one statement, so the type refuses them disagreeing rather than
        // leaving an interface to render a near-miss wording above an empty list — which reads as an
        // absence dressed as a near miss, and is the defect this key exists to remove.
        assertFailsWith<IllegalArgumentException> {
            Outcome.SendStatus(key = MessageKey.SUBJECT_NEAREST_NAMES, subjectName = "a name")
        }
        // And the other direction: spellings under a key that says nothing about them are forms no
        // reader is ever told why they are looking at.
        assertFailsWith<IllegalArgumentException> {
            Outcome.SendStatus(
                key = MessageKey.SUBJECT_NOT_FOUND,
                nearest = listOf(SubjectRef(kind = SubjectKind.CONTACT, label = "a near name")),
            )
        }
    }

    @Test
    fun `a subject that was never read is not reported as a name nothing matched`(): Unit = testApplication {
        // The two are one message if they share a key, and it is wrong half the time: nothing
        // matched sends the reader looking for a gap in the material, where what is missing is
        // the name itself. Neither field is filled, because nothing was searched and no name
        // arrived — either one would report work that did not happen.
        serving(runner = runnerSettling(Answer.SubjectUnread()))

        val status = assertIs<Outcome.SendStatus>(answerTo("status").outcome)

        assertEquals(MessageKey.SUBJECT_NOT_READ, status.key)
        assertNull(status.subjectName)
        assertEquals(emptyList<SubjectKind>(), status.searched)
    }

    @Test
    fun `a command given no subject is not reported as a search that found nothing`(): Unit = testApplication {
        // The defect: a bare command reached the nothing-found key, so four words told the reader
        // the corpus held nothing — about a search that never ran, for a subject they never named.
        // Neither field is filled here for the reason the case above gives, and the key is its own
        // because the reader's next action is to supply the argument rather than to rephrase.
        serving(runner = runnerSettling(Answer.SubjectMissing()))

        val status = assertIs<Outcome.SendStatus>(answerTo("draft").outcome)

        assertEquals(MessageKey.SUBJECT_MISSING, status.key)
        assertNull(status.subjectName)
        assertEquals(emptyList<SubjectKind>(), status.searched)
    }

    @Test
    fun `no two answers about a missing subject reach the same key`() {
        // Each says something different about the request and asks something different of the
        // reader: supply the subject, rephrase the question, or look elsewhere because the name is
        // absent from the corpus. Sharing a key makes one of them wrong every time it is rendered,
        // and they are asserted together so that a later case cannot quietly reuse one.
        //
        // Named as a property rather than a count, which is what keeps a fourth such answer from
        // staling the case instead of being added to it.
        //
        // Over the mapping directly rather than through the server, because the question is which
        // key each answer produces and standing the application up three times to ask it would be
        // three applications rather than three answers.
        val keys = listOf(
            Answer.SubjectMissing(),
            Answer.SubjectUnread(),
            Answer.SubjectUnknown(searched = setOf(DomainSubjectKind.CONTACT), name = "a name nobody has"),
        ).map { assertIs<Outcome.SendStatus>(it.toResponse().outcome).key }

        assertEquals(keys.distinct(), keys, "Answers about a missing subject render as one message: $keys")
    }

    @Test
    fun `how current the corpus was comes back with every answer`(): Unit = testApplication {
        // Including the ones that found nothing. *Nothing was found* is the reply that misleads
        // hardest while ingestion is behind, so an unstamped one is the worst of them to let
        // through.
        serving(
            runner = runnerSettling(
                Answer.InsufficientEvidence(),
                freshness = Freshness(
                    completeThrough = Instant.parse("2026-10-01T09:00:00Z"),
                    lagging = listOf(WorkUnitId("a-conversation")),
                ),
            ),
        )

        val freshness = answerTo("what happened").freshness

        assertEquals("2026-10-01T09:00:00Z", freshness.completeThrough)
        // The source, not its work units: a unit is the adapter's own name for a piece of its
        // work and means nothing to an interface.
        assertEquals(listOf("TELEGRAM"), freshness.lagging)
    }

    @Test
    fun `no response body carries an internal identifier`(): Unit = testApplication {
        // The wire format is read by a person once the interface renders it. An entity identifier
        // or a span's is this system's own bookkeeping, and the one thing worse than prose from
        // the backend is a UUID in front of somebody.
        val entity = EntityId(java.util.UUID.randomUUID())
        serving(
            runner = runnerSettling(
                Answer.Composed(listOf(Variant("the draft"))),
                alternatives = listOf(
                    Candidate(referent = Referent.OfContact(entity), name = "a contact", score = 0.5),
                ),
            ),
        )

        val body = jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "draft something"))
        }.bodyAsText()

        assertFalse(entity.value.toString() in body) { "An entity identifier reached the wire: $body" }
    }

    // --- the advisory read of unresolved pairs ------------------------------------------------

    @Test
    fun `the unresolved read is behind the secret like everything but health`(): Unit = testApplication {
        serving(runner = null)

        val response = jsonClient().get(UNRESOLVED)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(MessageKey.REQUEST_UNAUTHORISED, response.body<Failure>().key)
    }

    @Test
    fun `unresolved pairs are reported with the names that make them judgeable`(): Unit = testApplication {
        // A pair of identifiers tells nobody whether two accounts are one human, and judging that
        // is the whole purpose of the surface.
        serving(runner = null, unresolved = listOf(pairScoring(0.35, "One Name", "One Name")))

        val report = jsonClient().get(UNRESOLVED) { header(SECRET_HEADER, SECRET) }.body<UnresolvedReport>()

        val pair = report.pairs.single()
        assertEquals("One Name", pair.oneName)
        assertEquals("One Name", pair.otherName)
        assertEquals(0.35, pair.score)
        assertEquals(listOf("NAME"), pair.signals.map { it.kind })
    }

    @Test
    fun `the read applies the floor it was given when the caller names none`(): Unit = testApplication {
        val asked = mutableListOf<Pair<Double, Int>>()
        serving(runner = null, asked = asked)

        jsonClient().get(UNRESOLVED) { header(SECRET_HEADER, SECRET) }

        assertEquals(SAMPLING_FLOOR, asked.single().first)
    }

    @Test
    fun `a floor the caller names is the one used, and comes back with the rows`(): Unit = testApplication {
        val asked = mutableListOf<Pair<Double, Int>>()
        serving(
            runner = null,
            unresolved = listOf(pairScoring(0.6, "A Name", "A Name"), pairScoring(0.2, "B Name", "B Name")),
            asked = asked,
        )

        val report = jsonClient().get("$UNRESOLVED?floor=0.5") {
            header(SECRET_HEADER, SECRET)
        }.body<UnresolvedReport>()

        assertEquals(0.5, asked.single().first)
        assertEquals(0.5, report.floor, "The sampling rule did not come back with the sample.")
        assertEquals(listOf(0.6), report.pairs.map { it.score })
    }

    @Test
    fun `a truncated report says there are more`(): Unit = testApplication {
        // The distinction a silent cap destroys: *that is all of them* reads identically to *that
        // is as many as you asked for*, and a reader calibrating a threshold would take the first
        // for the second.
        serving(
            runner = null,
            unresolved = (1..3).map { pairScoring(0.5, "Name $it", "Name $it") },
        )

        val report = jsonClient().get("$UNRESOLVED?limit=2") {
            header(SECRET_HEADER, SECRET)
        }.body<UnresolvedReport>()

        assertEquals(2, report.pairs.size)
        assertTrue(report.more) { "Three pairs were truncated to two and the report read as complete." }
    }

    @Test
    fun `a complete report says there are no more`(): Unit = testApplication {
        serving(runner = null, unresolved = listOf(pairScoring(0.5, "A Name", "A Name")))

        val report = jsonClient().get("$UNRESOLVED?limit=2") {
            header(SECRET_HEADER, SECRET)
        }.body<UnresolvedReport>()

        assertEquals(1, report.pairs.size)
        assertFalse(report.more) { "A complete report claimed there was more to read." }
    }

    @Test
    fun `a malformed bound is refused rather than quietly defaulted`(): Unit = testApplication {
        // Answering with the standard floor would return a sample drawn by a rule the caller would
        // read as theirs, and a calibration taken from it would be wrong with nothing saying so.
        serving(runner = null)

        listOf("$UNRESOLVED?floor=soon", "$UNRESOLVED?limit=plenty", "$UNRESOLVED?limit=0").forEach { url ->
            val response = jsonClient().get(url) { header(SECRET_HEADER, SECRET) }
            assertEquals(HttpStatusCode.BadRequest, response.status, "$url was accepted.")
            assertEquals(MessageKey.REQUEST_MALFORMED, response.body<Failure>().key)
        }
    }

    @Test
    fun `a bound that parses but cannot be answered is refused the same way`(): Unit = testApplication {
        // `toDoubleOrNull` accepts all three of these, so a check that only asked whether the
        // parameter parsed would pass them on. None can be answered: the wire format refuses to
        // encode a non-finite number, so the reply would be an unexplained failure rather than the
        // keyed refusal the route promises — and `NaN` orders above every score in Postgres, so it
        // would match nothing while reading as a floor that did. `limit` at the integer ceiling is
        // the same shape: `limit + 1` wraps negative and the store reads nothing from it, so an
        // empty report would come back claiming to be complete.
        serving(runner = null, unresolved = listOf(pairScoring(0.5, "A Name", "A Name")))

        listOf(
            "$UNRESOLVED?floor=NaN",
            "$UNRESOLVED?floor=Infinity",
            "$UNRESOLVED?floor=1e400",
            "$UNRESOLVED?limit=2147483647",
        ).forEach { url ->
            val response = jsonClient().get(url) { header(SECRET_HEADER, SECRET) }
            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "$url was answered with ${response.status} rather than refused.",
            )
            assertEquals(MessageKey.REQUEST_MALFORMED, response.body<Failure>().key)
        }
    }

    @Test
    fun `the read answers on a backend that cannot answer a question`(): Unit = testApplication {
        // Identity needs neither the model credential nor the sidecar, so a credential-free stack
        // recognises contacts and has pairs to report while answering nothing. A surface that
        // refused this alongside `/answer` would hide the one stack where it is cheap to read.
        serving(runner = null, unresolved = listOf(pairScoring(0.35, "A Name", "A Name")))

        val response = jsonClient().get(UNRESOLVED) { header(SECRET_HEADER, SECRET) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, response.body<UnresolvedReport>().pairs.size)
    }

    /**
     * A kept pair scoring [score] on a shared name, between two contacts named as given.
     *
     * The names are positional and are not matched to a particular identifier: `CandidateMerge.of`
     * orders the pair by identifier, which is random here, so nothing may depend on which side a
     * given name lands. Where a case asserts a name it gives both sides the same one.
     */
    private fun pairScoring(score: Double, oneName: String, otherName: String): UnresolvedPair {
        val one = EntityId(java.util.UUID.randomUUID())
        val other = EntityId(java.util.UUID.randomUUID())
        return UnresolvedPair(
            pair = CandidateMerge.of(one, other, score, listOf(Signal(SignalKind.NAME, score))),
            oneName = oneName,
            otherName = otherName,
        )
    }

    // --- an answer asked for and collected separately ----------------------------------------

    /**
     * The property this route exists for, and the one the single-request route cannot have.
     *
     * An answer is accepted, the request that asked for it ends, and the answer is still there to
     * collect afterwards. The shape that searches the open web runs for minutes, so a caller that
     * had to hold one request open for it would have to choose between abandoning a finished answer
     * and waiting that long on a backend that had gone silent. Here it chooses neither.
     *
     * Blocked on a latch rather than on a clock, so the case asserts the ordering it is about —
     * accepted before finished, collectable after — rather than racing a duration.
     */
    @Test
    fun `an answer outlives the request that asked for it`(): Unit = testApplication {
        val release = CompletableDeferred<Unit>()
        serving(runner = runnerOver(stageAwaiting(release, Answer.InsufficientEvidence())))

        val accepted = submitAsync()
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        val id = accepted.body<AnswerAccepted>().id
        assertTrue(id.isNotBlank(), "Accepted work must be named, or nothing can collect it.")
        // Still running, which is a status and deliberately not a body: there is nothing to say yet.
        assertEquals(HttpStatusCode.Accepted, collectAsync(id).status)
        assertEquals("", collectAsync(id).bodyAsText())

        release.complete(Unit)

        val collected = untilSettled(id)
        assertEquals(HttpStatusCode.OK, collected.status)
        assertIs<Outcome.SendStatus>(collected.body<AnswerResponse>().outcome)
    }

    /**
     * Collecting is repeatable, which is what makes a lost response survivable.
     *
     * The tempting rule is to let the slot go once the answer has been handed over. But it is
     * handed to the server, not to the reader: if that response is lost in flight the caller
     * retries, and under the tempting rule the retry finds nothing — destroying a finished answer
     * by way of the very allowance that exists to protect it. So the slot is reclaimed by the
     * keeping period instead, and asking twice answers twice.
     */
    @Test
    fun `an answer already collected can be collected again`(): Unit = testApplication {
        serving(runner = runnerSettling(Answer.InsufficientEvidence()))

        val id = submitAsync().body<AnswerAccepted>().id
        assertEquals(HttpStatusCode.OK, untilSettled(id).status)

        val again = collectAsync(id)
        assertEquals(HttpStatusCode.OK, again.status)
        assertIs<Outcome.SendStatus>(again.body<AnswerResponse>().outcome)
    }

    /**
     * A name nothing is filed under is a wait, not a defect in the build.
     *
     * Two things matter in the key. *Not finished* is answered by asking again and this is not, so
     * it must not read as work still in progress — a caller told to keep waiting would spend its
     * whole budget on an answer that no longer exists. And this process restarting is the ordinary
     * way to reach it, which is something anticipated and recoverable by coming back; reporting it
     * as a failure nobody anticipated would send a reader to the service log over a redeploy.
     */
    @Test
    fun `a name nothing is filed under is come back later`(): Unit = testApplication {
        serving(runner = runnerSettling(Answer.InsufficientEvidence()))

        val response = collectAsync("not-a-name-this-backend-minted")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(MessageKey.CANNOT_ANSWER_RIGHT_NOW, response.body<Failure>().key)
    }

    /** The edge refusals happen before any work starts, as they do on the single-request route. */
    @Test
    fun `a blank ask is refused without starting work`(): Unit = testApplication {
        var started = false
        serving(runner = runnerSettling(Answer.InsufficientEvidence(), onAsk = { started = true }))

        val response = jsonClient().post(ANSWER_ASYNC) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "   "))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(MessageKey.REQUEST_MALFORMED, response.body<Failure>().key)
        assertFalse(started, "A malformed request must not reach the runner.")
    }

    @Test
    fun `a command naming no shape is refused without starting work either`(): Unit = testApplication {
        // Asserted on this route too, and not on the strength of the other one: the rule is one
        // function but it is called twice, and this is the route the interface shipped here uses.
        // It matters more here, because this one answers before the work runs — accepted and then
        // refused leaves a caller holding an identifier for an answer that was never coming.
        var started = false
        serving(runner = runnerSettling(Answer.InsufficientEvidence(), onAsk = { started = true }))

        val response = jsonClient().post(ANSWER_ASYNC) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "/stats Acme", command = "stats"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(MessageKey.REQUEST_MALFORMED, response.body<Failure>().key)
        assertFalse(started, "A command naming no shape must not reach the runner.")
    }

    @Test
    fun `a command naming a registered shape is taken for work`(): Unit = testApplication {
        // The acceptance half on this route too, and not on the strength of the sync one: the
        // argument for checking the refusal twice — one function, called twice, and this is the
        // route the interface shipped here uses — applies to this direction unchanged. Without it,
        // making the shared check refuse every non-null command reds only the sync case.
        serving(runner = runnerSettling(Answer.InsufficientEvidence()))

        val response = jsonClient().post(ANSWER_ASYNC) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "what happened", command = REGISTERED_SHAPE))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `a deployment with no answer path will not take work`(): Unit = testApplication {
        serving(runner = null)

        val response = submitAsync()

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(MessageKey.CANNOT_ANSWER_RIGHT_NOW, response.body<Failure>().key)
    }

    /**
     * A failure during an answer is reported where the answer is collected, with the same key.
     *
     * **This is what keeps one status mapping rather than two.** The work no longer runs inside the
     * request that asked for it, so nothing it raises passes through the status pages on the way
     * out; collecting it raises it instead, on a call that does. A route that mapped failures for
     * itself would answer a dependency that is down differently depending on which way it was
     * asked, and the drift would show up as an operator chasing the wrong cause.
     */
    @Test
    fun `a dependency down during an answer is come back later where collected`(): Unit = testApplication {
        serving(
            runner = runnerSettling(
                Answer.InsufficientEvidence(),
                onAsk = { throw ModelUnavailable("The model could not be reached.") },
            ),
        )

        val id = submitAsync().body<AnswerAccepted>().id
        val collected = untilSettled(id)

        assertEquals(HttpStatusCode.ServiceUnavailable, collected.status)
        assertEquals(MessageKey.CANNOT_ANSWER_RIGHT_NOW, collected.body<Failure>().key)
    }

    /**
     * An answer that failed at its last step maps the same way where it is collected.
     *
     * The other half of the mapping claim above: a key the *answer* carries travels in the answer
     * body on a 200, where a dependency being down travels as a status. Both routes have to agree
     * about which is which, or a caller reading one would have to know which way it had asked.
     */
    @Test
    fun `a reply nobody could read carries its key in the collected answer`(): Unit = testApplication {
        serving(runner = runnerGivingUpUnreadable())

        val id = submitAsync().body<AnswerAccepted>().id
        val collected = untilSettled(id)

        assertEquals(HttpStatusCode.OK, collected.status)
        val outcome = assertIs<Outcome.SendStatus>(collected.body<AnswerResponse>().outcome)
        assertEquals(MessageKey.ANSWER_UNREADABLE, outcome.key)
    }

    /**
     * And the catch-all, which is the one that would have been silently lost.
     *
     * Work running outside the request that asked for it is work whose failures nothing is obliged
     * to notice. Anything unanticipated has to reach the caller as an unexpected failure rather than
     * as a name that never settles — a caller waiting out its whole budget on a piece of work that
     * died in its first second is the worst of the failure modes here, because it looks like
     * patience.
     */
    @Test
    fun `work that failed unexpectedly is a failure rather than a wait`(): Unit = testApplication {
        serving(
            runner = runnerSettling(
                Answer.InsufficientEvidence(),
                onAsk = { error("something nobody anticipated") },
            ),
        )

        val id = submitAsync().body<AnswerAccepted>().id
        val collected = untilSettled(id)

        assertEquals(HttpStatusCode.InternalServerError, collected.status)
        assertEquals(MessageKey.UNEXPECTED_FAILURE, collected.body<Failure>().key)
    }

    /** Both routes are behind the secret, which the pipeline guard gives them rather than a route. */
    @Test
    fun `asking separately without the secret is refused`(): Unit = testApplication {
        serving(runner = runnerSettling(Answer.InsufficientEvidence()))

        val asked = jsonClient().post(ANSWER_ASYNC) {
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = "anything"))
        }
        val collected = jsonClient().get("$ANSWER_ASYNC/any-name")

        assertEquals(HttpStatusCode.Unauthorized, asked.status)
        assertEquals(HttpStatusCode.Unauthorized, collected.status)
    }

    /**
     * Work dropped while a caller was still asking is a wait, not an unmapped failure.
     *
     * Reachable two ways, and a stop is the ordinary one: the work is parented to the application,
     * so stopping the server cancels every answer in flight. Collecting a cancelled piece of work
     * must not simply await it — that raises the cancellation out of the route, which the status
     * pages do not map, and the caller sees a dropped connection instead of something to say.
     */
    @Test
    fun `work dropped while a caller was still asking is come back later`(): Unit = testApplication {
        serving(
            runner = runnerSettling(
                Answer.InsufficientEvidence(),
                onAsk = { throw CancellationException("the server is stopping") },
            ),
        )

        val id = submitAsync().body<AnswerAccepted>().id
        val collected = untilSettled(id)

        assertEquals(HttpStatusCode.ServiceUnavailable, collected.status)
        assertEquals(MessageKey.CANNOT_ANSWER_RIGHT_NOW, collected.body<Failure>().key)
    }

    /** Hand work over, with the secret. */
    private suspend fun ApplicationTestBuilder.submitAsync(text: String = "what happened") =
        jsonClient().post(ANSWER_ASYNC) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = text))
        }

    /** Come back for the work named [id], once. */
    private suspend fun ApplicationTestBuilder.collectAsync(id: String) =
        jsonClient().get("$ANSWER_ASYNC/$id") { header(SECRET_HEADER, SECRET) }

    /**
     * Come back for [id] until it is no longer *accepted and unfinished*.
     *
     * The work runs in a coroutine of the application's own, so *released* and *finished* are not
     * the same instant and a single read after the latch would be a race. Bounded so that a route
     * which never settles fails the case rather than hanging the suite.
     */
    private suspend fun ApplicationTestBuilder.untilSettled(id: String): HttpResponse {
        repeat(SETTLE_READS) {
            val response = collectAsync(id)
            if (response.status != HttpStatusCode.Accepted) return response
            delay(SETTLE_WAIT_MILLIS)
        }
        error("The work named $id never settled.")
    }

    /** A stage that settles with [answer], but not until [release] is completed. */
    private fun stageAwaiting(release: CompletableDeferred<Unit>, answer: Answer) = object : Stage {
        override suspend fun apply(exchange: Exchange): Progress {
            release.await()
            return Progress.Settled(answer, exchange)
        }
    }

    // --- the application under test, and the fakes it is given -------------------------------

    /**
     * Install the surface with [runner] behind it and [serving] as the store's answer.
     *
     * [unresolved] stands in for the store's kept pairs. It records what floor and limit the route
     * asked for, because those are the route's own decisions — a default applied, a bound passed
     * through — and a fake that only returned rows could not tell a defaulted floor from a
     * misread one.
     */
    private fun ApplicationTestBuilder.serving(
        runner: Runner?,
        serving: Boolean = true,
        unresolved: List<UnresolvedPair> = emptyList(),
        asked: MutableList<Pair<Double, Int>> = mutableListOf(),
    ) {
        application {
            api(
                secret = SECRET,
                runner = runner,
                serving = { serving },
                samplingFloor = SAMPLING_FLOOR,
                unresolved = { floor, limit ->
                    asked += floor to limit
                    unresolved.filter { it.pair.score >= floor }.take(limit)
                },
            )
        }
    }

    /** A client that speaks the wire format, which the default test client does not. */
    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json() }
    }

    /** Ask, with the secret, and read the answer. */
    private suspend fun ApplicationTestBuilder.answerTo(
        text: String,
        subjectName: String? = null,
    ): AnswerResponse =
        jsonClient().post(ANSWER) {
            header(SECRET_HEADER, SECRET)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(text = text, subjectName = subjectName))
        }.body()

    /**
     * A runner that settles every request with [answer].
     *
     * A real [Runner] over a fake shape rather than a stand-in for the runner itself, so the
     * mapping is exercised against answers that have been through the machinery that stamps
     * them. The shape accepts no subject, which is what lets this stand up without a subject
     * search — the real one reads the store, and nothing here opens one.
     *
     * [onAsk] runs when the shape is reached, which is how the refusal test establishes that
     * nothing ran.
     */
    @Suppress("LongParameterList") // One fixture per thing a settling stage can have been handed.
    private fun runnerSettling(
        answer: Answer,
        alternatives: List<Candidate> = emptyList(),
        freshness: Freshness? = null,
        // What the settling stage is to have retrieved. Empty for every case that settles with
        // something other than a grounded answer; a claim's citations are checked against this,
        // so an answer carrying claims needs the spans they cite to be here.
        retrieved: List<Span> = emptyList(),
        // What the corpus is to hold about each cited message. Absent for a record nothing names,
        // which is what an answer citing material with no author behind it renders from.
        turns: Map<ExternalId, Turn> = emptyMap(),
        onAsk: () -> Unit = {},
    ): Runner {
        val settling = object : Stage {
            override suspend fun apply(exchange: Exchange): Progress {
                onAsk()
                return Progress.Settled(
                    answer,
                    exchange.copy(alternatives = alternatives, retrieved = retrieved),
                )
            }
        }
        return runnerOver(settling, freshness, turns)
    }

    /**
     * A runner that gives up on a complete reply nobody could read, the way the path reaches it.
     *
     * **Not `runnerSettling(Answer.Unreadable())`, which the domain refuses outright.** That answer
     * is the runner's own account of having rolled again and failed again, so no stage may settle
     * with one — which leaves the honest fixture, and a better one: a model-spending stage
     * reporting a reply the provider called complete and the reader rejected, on both attempts. So
     * what the mapping is asserted against here is an answer that really came out of two rejected
     * attempts rather than one handed in at the seam.
     */
    private fun runnerGivingUpUnreadable(): Runner = runnerOver(
        object : Stage {
            override val spendsModel: Boolean get() = true

            override suspend fun apply(exchange: Exchange): Progress = Progress.Settled(
                Answer.InsufficientEvidence(),
                exchange,
                Fault.Unreadable(
                    ModelOutcome.Answered(text = "no markers anywhere", usage = TokenUsage.NONE),
                ),
            )
        },
    )

    /** A real runner over a fake shape whose only stage is [stage]. */
    private fun runnerOver(
        stage: Stage,
        freshness: Freshness? = null,
        turns: Map<ExternalId, Turn> = emptyMap(),
    ): Runner {
        val shape = object : Shape {
            override val id = ShapeId(THE_FALLBACK)
            override val description = "what this shape is for, which this file never routes on"
            override val accepts = emptySet<DomainSubjectKind>()
            override val variants = 1..3
            override val readsCorpus = false

            // False, so that a stage settling with prose alone reaches the mapping this file is
            // about. What a reply asserting nothing costs a shape whose answer is its assertions is
            // the runner's question and is asserted there.
            override val assertsFindings = false
            override val stages = listOf(stage)
        }
        return Runner(
            // **A shape registered beside the fallback, which is what lets the surface's command
            // check be discriminated.** With the fallback alone, what is answerable is a
            // one-element set and the only word any case could send is the fallback's own — so a
            // check reading `setOf(fallback.id.value)`, which in production would refuse every
            // real command outright, would satisfy every case here. It answers through the same
            // stage, so no case that does not name it is affected.
            shapes = ShapeRegistry(shapes = listOf(registered(REGISTERED_SHAPE, stage)), fallback = shape),
            scope = ScopeResolver(RefusingSearch),
            citations = CitationCheck(),
            retrieval = Attributing(turns),
            ingestState = StateReporting(freshness),
        )
    }

    /**
     * A shape registered under [id], answering through [stage] like the fallback does.
     *
     * Exists only so that what the registry answers is more than the fallback alone — see the
     * note at its construction. It is never routed into by classification, which this file does
     * not configure; the one way to reach it is to name it as a command.
     */
    private fun registered(id: String, stage: Stage): Shape = object : Shape {
        override val id = ShapeId(id)
        override val description = "what this registered shape answers, which this file never routes on"
        override val accepts = emptySet<DomainSubjectKind>()
        override val variants = 1..3
        override val readsCorpus = false
        override val assertsFindings = false
        override val stages = listOf(stage)
    }

    /** A variant carrying [claims] assertions, each cited so the type will accept it. */
    private fun variant(claims: Int) = Variant(
        prose = "what was composed",
        claims = (1..claims).map {
            Claim(
                text = "an assertion",
                cites = setOf(SpanId(RecordKind.MESSAGE, ExternalId(Source.TELEGRAM, "$it"))),
            )
        },
    )

    private fun candidate(name: String, kind: DomainSubjectKind, handle: String? = null) = Candidate(
        referent = when (kind) {
            DomainSubjectKind.CONTACT -> Referent.OfContact(EntityId(java.util.UUID.randomUUID()))
            DomainSubjectKind.CLIENT -> Referent.OfClient(EntityId(java.util.UUID.randomUUID()))
            DomainSubjectKind.BODY_OF_WORK -> Referent.OfBodyOfWork(name)
            // Its own case rather than the inbox's, so a candidate cannot claim one kind here and
            // arrive on the wire as another — which would make a test about kinds assert nothing.
            DomainSubjectKind.CONVERSATION ->
                Referent.OfConversation(ExternalId(Source.TELEGRAM, java.util.UUID.randomUUID().toString()))

            DomainSubjectKind.INBOX -> Referent.Inbox
        },
        name = name,
        score = 0.9,
        handle = handle,
    )

    /**
     * The corpus, as much of it as the runner reads to attribute an answer's citations.
     *
     * One read of the port's several, and the rest refuse rather than return nothing: the runner
     * resolves who wrote a cited message and asks the corpus nothing else, so a case that made it
     * here through any other read would be exercising a path this does not have.
     */
    private class Attributing(private val turns: Map<ExternalId, Turn>) : Retrieval {
        override suspend fun turnsOf(messages: List<ExternalId>, subject: List<EntityId>) =
            turns.filterKeys { it in messages }

        override suspend fun conversationsOf(subject: Subject, threads: Int) = refuse()

        override suspend fun recentMessages(conversations: List<ExternalId>, perConversation: Int) = refuse()

        override suspend fun commitmentsOf(reach: Reach, limit: Int) = refuse()

        override suspend fun eventsAhead(reach: Reach, horizon: Horizon, limit: Int) = refuse()

        override suspend fun eventsPast(reach: Reach, horizon: Horizon, limit: Int) = refuse()

        override suspend fun eventsUndated(reach: Reach, limit: Int) = refuse()

        override suspend fun statementsOf(reach: Reach, limit: Int) = refuse()

        override suspend fun namesOf(parties: List<EntityId>) = refuse()

        override suspend fun closuresOf(commitments: List<EntityId>) = refuse()

        private fun refuse(): Nothing =
            error("The runner reads one thing of the corpus, which is who wrote a cited message.")
    }

    /**
     * The search the resolver is given, which must never be reached.
     *
     * The shape above accepts no subject, so the runner skips resolution entirely. Failing loudly
     * rather than returning nothing, because a silent empty result would make a test pass for the
     * wrong reason.
     */
    private object RefusingSearch : SubjectSearch {
        override val covers = setOf(DomainSubjectKind.CONTACT)

        override suspend fun matching(name: String) =
            error("Scope was resolved for a shape that accepts no subject.")

        override suspend fun nearest(name: String) =
            error("Near names were read for a shape that accepts no subject.")

        override suspend fun within(referent: Referent) =
            error("Scope was traversed for a shape that accepts no subject.")
    }

    /** Ingestion state, as much of it as the runner reads to stamp an answer. */
    private class StateReporting(private val freshness: Freshness?) : IngestStateRepository {
        override suspend fun freshnessOf(source: Source) = freshness

        override suspend fun reportFreshness(source: Source, completeThrough: Instant?, lagging: List<WorkUnitId>) =
            error("The surface does not report ingestion state.")

        override suspend fun reportBackfill(source: Source, complete: Boolean, reached: Instant?) =
            error("The surface does not report backfill.")

        override suspend fun recordPass(source: Source, pass: Pass) =
            error("The surface does not record a pass.")

        override suspend fun healthOf(source: Source): Health? = null

        override suspend fun backfillOf(source: Source): BackfillProgress? = null
    }

    private companion object {
        /** Not a realistic secret, and deliberately not a plausible one either. */
        const val SECRET = "the-secret-for-this-test"

        /**
         * Where the body `:bot` is driven by lives, handed over by the build rather than spelled.
         *
         * A path from here into another module's resources would be this file holding a layout
         * decision it has no way to keep true, and would break the moment either module moved.
         * The build declares the file as an input of this task as well, so a hand edit to the
         * fixture re-runs the case that holds it to the surface.
         */
        const val WIRE_FIXTURE = "mainbrain.wireFixture"

        /**
         * The floor the container would derive from the matching weights.
         *
         * A literal rather than the derivation, because what this suite tests is that the route
         * applies the floor it was given — not what the weights make of it. Computing it here
         * would pass whatever the weights happened to say, including nothing.
         */
        const val SAMPLING_FLOOR = 0.175

        /**
         * The identifier of the shape this suite's runner registers beside the fallback.
         *
         * Named rather than spelled twice, because the accepting cases ask the surface to take it
         * as a command and the fixture is what makes that true — written out in both places, a
         * rename would turn an accepting case into a second refusing one and read as green.
         */
        const val REGISTERED_SHAPE = "a-registered-shape"

        /**
         * And the fallback's own, which is a word no command list offers and the surface accepts.
         *
         * Its own constant because the two are what the surface's check must tell apart from a
         * word naming nothing: a caller may ask for the fallback by name, which is what the
         * evaluation set does, so refusing it would be the guard overreaching.
         */
        const val THE_FALLBACK = "a-shape"

        /** How many times a case comes back for work before calling it stuck, and how long between. */
        const val SETTLE_READS = 100

        const val SETTLE_WAIT_MILLIS = 20L
    }
}

/**
 * Every part of a sent answer as one string, for the cases that are about what reaches a reader.
 *
 * The order the parts are in and whether a given line is among them are what most of these cases
 * assert, and neither is about which part is a heading. Written here rather than on the wire type
 * so that nothing in the build can flatten an answer by accident: the one place that used to is
 * what [Block] exists to stop.
 */
private val Outcome.SendText.prose: String get() = body.joinToString("\n") { it.text }
