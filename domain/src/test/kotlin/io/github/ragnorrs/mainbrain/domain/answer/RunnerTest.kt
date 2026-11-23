package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.BackfillProgress
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.Freshness
import io.github.ragnorrs.mainbrain.domain.Health
import io.github.ragnorrs.mainbrain.domain.IngestStateRepository
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.Pass
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The runner, against fake stages.
 *
 * Fakes rather than real shapes because what this class does is entirely ordering and
 * gating: which stage runs, what stops before the model is spent, what is re-run after a
 * failure, and what it stamps on the way out. A real pipeline would make all of those harder
 * to see, and none of them is about what any particular shape asks.
 *
 * The two cases worth stating up front are the ones that cost real money if they regress. A
 * short-circuit that stopped working would spend a model call on an empty corpus for every
 * request, reporting nothing unusual; a retry that restarted the pipeline instead of resuming
 * would re-run retrieval on every ungrounded generation. Both are asserted by counting what
 * ran rather than by reading the answer, because the answer is the same either way.
 */
class RunnerTest {

    @Test
    fun `an empty corpus settles before the model is spent`(): Unit = runBlocking {
        val retrieve = stage { Progress.Carried(it) }
        val generate = stage(spendsModel = true) { Progress.Settled(composed("should never run"), it) }

        val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

        assertIs<Answer.InsufficientEvidence>(answer)
        assertEquals(1, retrieve.runs)
        assertEquals(
            0,
            generate.runs,
            "The model was called with nothing to answer from. Insufficient evidence is " +
                "decided before any model call, which is what makes it the cheapest answer " +
                "the system can produce.",
        )
    }

    @Test
    fun `a shape that does not read the corpus still reaches the model on an empty one`(): Unit = runBlocking {
        // The one shape that reads the open world is agentic by construction: its retrieval
        // *is* the model call. Short-circuiting it would refuse the request that needs no
        // corpus at all.
        val generate = stage(spendsModel = true) { Progress.Settled(composed("read from the open web"), it) }
        val outward = shape(accepts = emptySet(), readsCorpus = false, stages = listOf(generate))

        val answer = runner(outward).answer(ask())

        assertIs<Answer.Composed>(answer)
        assertEquals(1, generate.runs)
    }

    @Test
    fun `a stage that retrieves and settles in one step grounds its own citations`(): Unit = runBlocking {
        // The agentic shape's retrieval is its model call, so it adds material and settles
        // together. Checking its citations against the exchange as it stood entering the stage
        // finds every one of them ungrounded, and the request degrades for ever with nothing
        // in the failure to say why.
        val found = span("a page it read")
        val agentic = stage(spendsModel = true) { exchange ->
            Progress.Settled(
                Answer.Composed(listOf(Variant("what it found", listOf(Claim("it says", setOf(found)))))),
                exchange.copy(retrieved = listOf(span(found))),
            )
        }

        val answer = runner(
            shape(accepts = emptySet(), readsCorpus = false, stages = listOf(agentic)),
        ).answer(ask())

        assertIs<Answer.Composed>(answer)
        assertEquals(1, agentic.runs, "The citation read as ungrounded and the stage was retried.")
    }

    @Test
    fun `a grounded answer is stamped with freshness and the subjects that lost`(): Unit = runBlocking {
        val span = span("a message")
        val loser = Candidate(Referent.OfConversation(ExternalId(Source.TELEGRAM, "a thread")), "a thread", 0.2)
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(listOf(Variant("what was agreed", listOf(Claim("they agreed", setOf(span)))))),
                it,
            )
        }

        val answer = runner(
            shape = shape(stages = listOf(retrieve, generate)),
            search = FakeSearch(listOf(contact("a lead"), loser)),
            freshness = Freshness(COMPLETE_THROUGH, lagging = listOf(WorkUnitId("a lagging thread"))),
        ).answer(ask())

        val composed = assertIs<Answer.Composed>(answer)
        assertEquals(COMPLETE_THROUGH, composed.freshness.completeThrough)
        assertEquals(
            mapOf(Source.TELEGRAM to listOf(WorkUnitId("a lagging thread"))),
            composed.freshness.lagging,
            "A lagging unit is the adapter's own name for its work, so it has to stay under " +
                "the source that issued it or it cannot be acted on without translation.",
        )
        assertEquals(
            listOf(loser),
            composed.alternatives,
            "The losing subject did not reach the answer. The reader cannot then see what was " +
                "answered against, so a wrong subject is invisible rather than recoverable.",
        )
    }

    @Test
    fun `each cited span is attributed to whoever wrote its material, and when`(): Unit = runBlocking {
        // What a reader is given in place of an identifier. Resolved here rather than in a shape,
        // so this is where the resolving is asserted — five shapes cite material and a rule in one
        // of them would attribute one answer's claims.
        val cited = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(cited)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(listOf(Variant("what was agreed", listOf(Claim("they agreed", setOf(cited)))))),
                it,
            )
        }
        val corpus = FakeRetrieval(
            turns = mapOf(
                cited.record to Turn(
                    ExternalId(Source.TELEGRAM, "a thread"),
                    Channel.OneToOne,
                    author = "who wrote it",
                ),
            ),
        )

        val answer = runner(shape(stages = listOf(retrieve, generate)), retrieval = corpus).answer(ask())

        val composed = assertIs<Answer.Composed>(answer)
        assertEquals(
            mapOf(cited to Attribution(sourceTime = COMPLETE_THROUGH, author = "who wrote it")),
            composed.attribution,
            "A claim reached the answer with nothing saying who wrote the material behind it or " +
                "when, which leaves an interface the identifier and nothing a reader can act on.",
        )
    }

    @Test
    fun `only the records a surviving claim cites are asked about`(): Unit = runBlocking {
        // **Keyed on the citations rather than on everything retrieved**, which is the difference
        // between a handful of records and whatever a ranked read returned. Asserted through what
        // the port was asked, because the returned map is filtered either way — so keying this
        // wrongly would cost a request real work and change no answer.
        val cited = span("a message a claim rests on")
        val spare = span("a message nothing cited")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(cited), span(spare)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(listOf(Variant("what was agreed", listOf(Claim("they agreed", setOf(cited)))))),
                it,
            )
        }
        val corpus = FakeRetrieval()

        runner(shape(stages = listOf(retrieve, generate)), retrieval = corpus).answer(ask())

        assertEquals(
            listOf(FakeRetrieval.Ask.Turns(listOf(cited.record))),
            corpus.asked.filterIsInstance<FakeRetrieval.Ask.Turns>(),
            "The attribution read asked about material no claim cited, which is a round trip per " +
                "retrieved record on a path somebody is waiting on.",
        )
    }

    @Test
    fun `a corpus that cannot be read costs the names rather than the answer`(): Unit = runBlocking {
        // **The one read on this path that happens after the model is paid.** Every other corpus
        // read sits in an assembly stage, where a failure loses nothing bought; this one runs after
        // both calls and after grounding accepted what they produced. Throwing would discard a
        // complete, checked answer over a dropped connection — and the retry would buy the
        // generation again — so the failure costs what it was for and nothing else.
        val cited = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(cited)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(listOf(Variant("what was agreed", listOf(Claim("they agreed", setOf(cited)))))),
                it,
            )
        }

        val answer = runner(
            shape(stages = listOf(retrieve, generate)),
            retrieval = FakeRetrieval(turnsFault = IllegalStateException("the connection went away")),
        ).answer(ask())

        val composed = assertIs<Answer.Composed>(answer)
        assertEquals(
            listOf("what was agreed"),
            composed.variants.map { it.prose },
            "A store blip after the answer was generated and grounded threw the answer away.",
        )
        assertEquals(
            mapOf(cited to Attribution(sourceTime = COMPLETE_THROUGH, author = null)),
            composed.attribution,
            "A failed read costs the names and nothing more: when the material was written came " +
                "off the retrieved material already in hand rather than out of that read, so a " +
                "reader still gets how old each finding is and loses only who wrote it.",
        )
    }

    @Test
    fun `an answer carrying no prose is stamped too`(): Unit = runBlocking {
        // *Nothing was found* is the reply that misleads hardest while ingestion is behind, so
        // an unstamped one is the worst of them to let through. A stage may settle with it,
        // and it cannot read ingestion state for itself.
        val giveUp = stage { Progress.Settled(Answer.InsufficientEvidence(), it) }

        val answer = runner(shape(readsCorpus = false, stages = listOf(giveUp))).answer(ask())

        val settled = assertIs<Answer.InsufficientEvidence>(answer)
        assertEquals(COMPLETE_THROUGH, settled.freshness.completeThrough)
    }

    @Test
    fun `an ungrounded citation is retried from the model stage, not from the top`(): Unit = runBlocking {
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(Variant("invented", listOf(Claim("they agreed", setOf(span("never retrieved")))))),
                ),
                it,
            )
        }

        val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

        assertIs<Answer.InsufficientEvidence>(answer)
        assertEquals(2, generate.runs, "A failure retries once, then degrades.")
        assertEquals(
            1,
            retrieve.runs,
            "Retrieval ran again. Everything before the first model call is deterministic, so " +
                "re-running it spends reads to arrive at the same exchange.",
        )
    }

    @Test
    fun `one bad citation among several costs that claim and not the answer`(): Unit = runBlocking {
        // One mis-copied identifier among several costs the finding it was on and nothing else. Why
        // that is worth a verdict of its own is stated on `Fault.fatal` rather than repeated here.
        val real = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "what was agreed",
                            claims = listOf(
                                Claim("they agreed", setOf(real)),
                                Claim("they also agreed", setOf(span("mistyped"))),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }
        val noticed = Noticed()

        val answer = runner(shape(stages = listOf(retrieve, generate)), faults = noticed).answer(ask())

        val composed = assertIs<Answer.Composed>(answer)
        assertEquals(
            listOf("they agreed"),
            composed.variants.single().claims.map { it.text },
            "The claim that could be checked did not reach the reader.",
        )
        assertEquals(1, generate.runs, "The answer held, so nothing should have been re-rolled.")
        val fault = assertIs<Fault.ClaimsDropped>(noticed.reasons.single().second)
        assertEquals(1, fault.dropped)
        assertEquals(2, fault.claims)
        assertEquals(listOf("MESSAGE/TELEGRAM/mistyped#0"), fault.cited)
    }

    @Test
    fun `a number nobody offered costs its claim and not the answer`(): Unit = runBlocking {
        // The citation resolved to no span at all, so nothing it named reaches the check that
        // compares identifiers — and were the gate to read that check alone, this claim would pass as
        // clean and reach the reader asserting something on evidence that does not exist.
        val real = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "what was agreed",
                            claims = listOf(
                                Claim("they agreed", setOf(real)),
                                Claim("they also agreed", setOf(real), unoffered = setOf(9)),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }
        val noticed = Noticed()

        val answer = runner(shape(stages = listOf(retrieve, generate)), faults = noticed).answer(ask())

        assertEquals(
            listOf("they agreed"),
            assertIs<Answer.Composed>(answer).variants.single().claims.map { it.text },
            "The claim resting on a number nobody offered reached the reader.",
        )
        assertEquals(1, generate.runs, "The answer held, so nothing should have been re-rolled.")
        val fault = assertIs<Fault.ClaimsDropped>(noticed.reasons.single().second)
        assertEquals(1, fault.dropped)
        // Reported as the number, because there is no span behind it to name. Everything else in
        // this list is a span identifier, for the reason stated on `Fault.Ungrounded`.
        assertEquals(listOf("[9]"), fault.cited)
    }

    @Test
    fun `a citation nothing could read costs its claim and not the answer`(): Unit = runBlocking {
        // The third way a citation fails, held to the same terms as the other two. It never resolved
        // far enough to be looked up, so neither the identifier check nor the offer has anything to
        // say about it — and were the gate to read those two alone, this claim would pass as clean
        // and reach the reader asserting something on support nothing ever compared.
        val real = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "what was agreed",
                            claims = listOf(
                                Claim("they agreed", setOf(real)),
                                Claim("they also agreed", setOf(real), unreadable = setOf("MESSAGE/TELEGRAM/43")),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }
        val noticed = Noticed()

        val answer = runner(shape(stages = listOf(retrieve, generate)), faults = noticed).answer(ask())

        assertEquals(
            listOf("they agreed"),
            assertIs<Answer.Composed>(answer).variants.single().claims.map { it.text },
            "The claim resting on a citation nothing could read reached the reader.",
        )
        assertEquals(1, generate.runs, "The answer held, so nothing should have been re-rolled.")
        val fault = assertIs<Fault.ClaimsDropped>(noticed.reasons.single().second)
        assertEquals(1, fault.dropped)
        // Named apart from the identifiers, for the reason `Fault.Ungrounded.unreadable` gives.
        assertEquals(listOf("MESSAGE/TELEGRAM/43"), fault.unreadable)
        assertEquals(emptyList(), fault.cited, "Text that is not an identifier was reported as one.")
    }

    @Test
    fun `an unreadable citation is bounded before it reaches a report`(): Unit = runBlocking {
        // **A citation that read as nothing is model output, and two sinks print it.** The fault line
        // and the answer trace both read this list, so a model that wrote a sentence where a citation
        // belongs would put its prose in a log file and in the trace the evaluation set parses — which
        // is what the port that carries it is specified never to do. Bounded here, at the one place
        // both sinks read from, so neither has to remember to.
        val real = span("a message")
        val sentence = "the thread where the renewal was discussed at some length with the finance lead"
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "what was agreed",
                            claims = listOf(
                                Claim("they agreed", setOf(real)),
                                Claim("they also agreed", setOf(real), unreadable = setOf(sentence)),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }
        val noticed = Noticed()
        val reported = RecordingTrace()

        runner(shape(stages = listOf(retrieve, generate)), faults = noticed, trace = reported).answer(ask())

        val fault = assertIs<Fault.ClaimsDropped>(noticed.reasons.single().second)
        assertTrue(
            fault.unreadable.single().length < sentence.length,
            "A sentence the model wrote reached the fault report whole: ${fault.unreadable}",
        )
        assertTrue(
            sentence.startsWith(fault.unreadable.single()),
            "What was reported is not the opening of what the model wrote: ${fault.unreadable}",
        )
        // **Pinned from both sides, because a bound asserted only as "shorter than the input" is
        // satisfied by any value down to one character.** What the constant has to be long enough for
        // is a citation that legitimately *is* long: the identifier form a model reverts to runs to
        // about forty characters, and cutting that would report a real citation as a mangled one.
        val identifier = "MESSAGE/TELEGRAM/-1234567890/123456789012#0"
        assertTrue(
            fault.unreadable.single().length >= identifier.length,
            "The bound cuts a citation the model could legitimately have written: ${fault.unreadable}",
        )
        assertEquals(
            fault.unreadable,
            reported.attempts.single().ungrounded,
            "The trace carries a different bound from the fault, so one of the two sinks is unguarded.",
        )
    }

    @Test
    fun `two unreadable citations alike to the bound are reported once`(): Unit = runBlocking {
        // **Cutting a set to prefixes can make its members equal again.** What the claims carry is a
        // set, so one bad citation repeated across an answer is already one entry; truncating each to
        // the same opening undoes that for two that differ only past the cut. Printed twice it reads
        // as the model repeating a single mistake, and the fault's own guard counts two where a
        // reader is shown one thing — so the list is deduplicated after the cut, not before.
        val real = span("a message")
        val finance = "the thread where the renewal was discussed at some length with the finance lead"
        val legal = "the thread where the renewal was discussed at some length with the legal lead"
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "what was agreed",
                            claims = listOf(
                                Claim("they agreed", setOf(real)),
                                Claim("they also agreed", setOf(real), unreadable = setOf(finance, legal)),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }
        val noticed = Noticed()

        runner(shape(stages = listOf(retrieve, generate)), faults = noticed).answer(ask())

        val fault = assertIs<Fault.ClaimsDropped>(noticed.reasons.single().second)
        assertEquals(
            1,
            fault.unreadable.size,
            "Two citations that differ only past the bound were reported as two: ${fault.unreadable}",
        )
    }

    @Test
    fun `an answer whose every citation is unreadable degrades rather than arriving as prose`(): Unit = runBlocking {
        // The clause the lenient reader made unreachable: it used to drop such a variant at the
        // parser, which fell short of the promised count and reported the format as misread. The
        // answer is the same either way — there is nothing to stand behind — but what is reported is
        // now what actually happened, and the prose is not offered on its own.
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            "what was agreed",
                            listOf(Claim("they agreed", emptySet(), unreadable = setOf("not a number"))),
                        ),
                    ),
                ),
                it,
            )
        }
        val noticed = Noticed()

        val answer = runner(shape(stages = listOf(retrieve, generate)), faults = noticed).answer(ask())

        assertIs<Answer.InsufficientEvidence>(answer)
        assertEquals(2, generate.runs, "A fatal fault is re-rolled once before it degrades.")
        val fault = assertIs<Fault.NothingGrounded>(noticed.reasons.first().second)
        assertEquals(listOf("not a number"), fault.unreadable)
        assertEquals(emptyList(), fault.cited)
    }

    @Test
    fun `an answer whose every claim cites a number nobody offered is refused`(): Unit = runBlocking {
        // The same rule the ungrounded case is held to: a reply resting entirely on material that
        // does not exist was not answered from the material, and its prose alone would be a hedge
        // offered as a finding.
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(Variant("what was agreed", listOf(Claim("they agreed", emptySet(), unoffered = setOf(9))))),
                ),
                it,
            )
        }

        val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

        assertIs<Answer.InsufficientEvidence>(answer)
        assertEquals(2, generate.runs, "A fatal fault is re-rolled once before it degrades.")
    }

    @Test
    fun `the retry is offered the same numbers the first attempt was`(): Unit = runBlocking {
        // **The one thing a per-request number costs that an identifier did not.** A number means
        // nothing without the assignment that made it, so the retry has to read its reply against the
        // assignment the first attempt was shown. The retry resumes at the first model-spending stage
        // for that reason: re-running the pipeline would number whatever retrieval returned the second
        // time, and a reply citing 1 would then be resolved against a different span than the one the
        // prompt offered under 1.
        //
        // **Retrieval returns the same two spans in the other order when it is run again**, which is
        // what makes this falsifiable. Against a resume that re-ran the pipeline, the second prompt
        // would number them the other way round and the surviving claim would rest on the wrong span —
        // and with retrieval that returned one span, or the same order twice, every implementation
        // produces the same prompt and the case cannot fail at all.
        val first = span("numbered one for the first attempt")
        val second = span("numbered two for the first attempt")
        var retrievals = 0
        val retrieve = stage {
            retrievals++
            val order = if (retrievals == 1) listOf(first, second) else listOf(second, first)
            Progress.Carried(it.copy(retrieved = order.map { id -> span(id) }))
        }
        val model = FakeModelProvider(
            listOf(
                // Every claim resting on a number nobody offered, which is fatal and re-rolls.
                FakeModelProvider.Reply.of("[VARIANT]\nThe account.\n[CLAIM] they agreed << 9"),
                FakeModelProvider.Reply.of("[VARIANT]\nThe account.\n[CLAIM] they agreed << 1"),
            ),
        )
        val shape = shape(stages = listOf(retrieve, Assemble("Instruction.", 1..1, FakeGrouping()), Generate(model)))

        val answer = runner(shape).answer(ask())

        assertEquals(2, model.callCount, "The first attempt was fatal, so there should be a retry.")
        assertEquals(
            model.requests.first().prompt,
            model.requests.last().prompt,
            "The retry was sent different material, so the numbers it was offered are not the ones " +
                "its reply is read against.",
        )
        assertEquals(
            setOf(first),
            assertIs<Answer.Composed>(answer).variants.single().claims.single().cites,
            "The retry cited 1 and it resolved to the span that was offered 2 on the attempt this " +
                "one resumed from, so the reply was read against a numbering it was never shown.",
        )
    }

    @Test
    fun `a claim resting on two spans needs both, and goes whole when one is missing`(): Unit = runBlocking {
        // **Not stripped of the citation that was wrong and kept.** What the sentence asserts may rest
        // on the span that is not there — a claim about what two people settled between them cites
        // both sides of it — so keeping the text and dropping the identifier would turn an
        // unverifiable assertion into prose, which is the half nothing checks.
        val real = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "what was agreed",
                            claims = listOf(
                                Claim("they agreed", setOf(real)),
                                Claim("and settled it between them", setOf(real, span("mistyped"))),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }

        val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

        val variant = assertIs<Answer.Composed>(answer).variants.single()
        assertEquals(
            listOf("they agreed"),
            variant.claims.map { it.text },
            "The half-grounded claim stayed among the assertions. One of the two spans it rests " +
                "on was never retrieved, so what it asserts cannot be checked.",
        )
        // **Asserted on the body as well, because the body is what gets rendered.** The two halves
        // dropping different claims is worse than dropping none: the check reports the claim gone
        // while the reader is shown it anyway, with nothing to say it was never verified.
        assertEquals(
            variant.claims.map(Piece::Cited),
            variant.body.filterIsInstance<Piece.Cited>(),
            "The reading order still carries an assertion the checked list does not.",
        )
    }

    @Test
    fun `a heading left with nothing under it goes with the claim that was under it`(): Unit = runBlocking {
        // A heading asserts that there is material here and carries no citation of its own, so one
        // whose only finding was dropped reaches the reader as a label for a section that is not
        // there. Both positions, because they are reached by different arms of the collapse: the
        // middle one ends up directly above the next heading, and the last one stands at the end.
        val real = span("a message")
        val kept = Claim("they agreed", setOf(real))
        val mistyped = Claim("they also agreed", setOf(span("mistyped")))
        val alsoMistyped = Claim("and a third time", setOf(span("mistyped twice")))
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "what was agreed",
                            claims = listOf(kept, mistyped, alsoMistyped),
                            body = listOf(
                                Piece.Heading("The first body of work"),
                                Piece.Cited(kept),
                                Piece.Prose(""),
                                Piece.Heading("The second"),
                                Piece.Cited(mistyped),
                                Piece.Prose(""),
                                Piece.Heading("The third"),
                                Piece.Cited(alsoMistyped),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }

        val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

        assertEquals(
            listOf(Piece.Heading("The first body of work"), Piece.Cited(kept)),
            assertIs<Answer.Composed>(answer).variants.single().body,
            "A heading whose findings were all dropped was left standing, so the reader is shown " +
                "a section that is not there and cannot tell the answer lost it.",
        )
    }

    @Test
    fun `a heading whose only finding cited nothing readable goes with it`(): Unit = runBlocking {
        // The two rules meet here: what the gate drops now includes a claim whose citation read as no
        // number at all, and a heading left with nothing under it goes with what was under it. The
        // case above pins the second against a claim citing a span nothing retrieved, which says
        // nothing about whether the first arm reaches it — and both are decided in the one `when`
        // branch, where keeping one rule's behaviour beside the other's wording costs nothing that
        // compiles.
        val real = span("a message")
        val kept = Claim("they agreed", setOf(real))
        val mangled = Claim("they also agreed", setOf(real), unreadable = setOf("two"))
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "what was agreed",
                            claims = listOf(kept, mangled),
                            body = listOf(
                                Piece.Heading("The first body of work"),
                                Piece.Cited(kept),
                                Piece.Prose(""),
                                Piece.Heading("The second"),
                                Piece.Cited(mangled),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }

        val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

        assertEquals(
            listOf(Piece.Heading("The first body of work"), Piece.Cited(kept)),
            assertIs<Answer.Composed>(answer).variants.single().body,
            "A section whose one finding was dropped for a citation nobody could read kept its " +
                "heading, so the reader is told there is material here and shown none.",
        )
    }

    @Test
    fun `a heading keeps its place when prose survives under it`(): Unit = runBlocking {
        // The negative half, and the reason the rule is emptiness rather than the absence of
        // findings: a section whose claim went but whose composed lines remain still says
        // something, and taking its heading would remove the only thing saying what those lines
        // are about. Without this the rule would delete prose the model wrote on purpose.
        val real = span("a message")
        val kept = Claim("they agreed", setOf(real))
        val mistyped = Claim("they also agreed", setOf(span("mistyped")))
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "what was agreed",
                            claims = listOf(kept, mistyped),
                            body = listOf(
                                Piece.Heading("The first body of work"),
                                Piece.Cited(kept),
                                Piece.Prose(""),
                                Piece.Heading("The second"),
                                Piece.Prose("Nothing was settled here, but it was discussed."),
                                Piece.Cited(mistyped),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }

        val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

        assertEquals(
            listOf(
                Piece.Heading("The first body of work"),
                Piece.Cited(kept),
                Piece.Prose(""),
                Piece.Heading("The second"),
                Piece.Prose("Nothing was settled here, but it was discussed."),
            ),
            assertIs<Answer.Composed>(answer).variants.single().body,
            "The second section still carries prose, so removing its heading took away the only " +
                "thing that said what those lines were about.",
        )
    }

    @Test
    fun `a dropped claim leaves no hole where it was written`(): Unit = runBlocking {
        // The format asks the model to set each assertion off with a blank line either side, so
        // taking one out of the middle leaves those two adjacent.
        val real = span("a message")
        val kept = Claim("they agreed", setOf(real))
        val mistyped = Claim("they also agreed", setOf(span("mistyped")))
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "what was agreed",
                            claims = listOf(kept, mistyped),
                            body = listOf(
                                Piece.Prose("What was agreed."),
                                Piece.Prose(""),
                                Piece.Cited(kept),
                                Piece.Prose(""),
                                Piece.Cited(mistyped),
                                Piece.Prose(""),
                                Piece.Prose("And that is all."),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }

        val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

        val body = assertIs<Answer.Composed>(answer).variants.single().body
        assertEquals(
            listOf(
                Piece.Prose("What was agreed."),
                Piece.Prose(""),
                Piece.Cited(kept),
                Piece.Prose(""),
                Piece.Prose("And that is all."),
            ),
            body,
            "Removing the assertion left the blank lines either side of it adjacent, which reads " +
                "as a hole in the middle of the reply that nobody wrote.",
        )
    }

    @Test
    fun `a claim dropped from either end leaves no blank line hanging there`(): Unit = runBlocking {
        // The ends are the same artefact as the middle with nothing on the far side: a reply that
        // opens or closes on an empty line is spacing the removal invented.
        val real = span("a message")
        val kept = Claim("they agreed", setOf(real))
        val opening = Claim("a note before any of it", setOf(span("mistyped")))
        val closing = Claim("and a note after it", setOf(span("mistyped twice")))
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "what was agreed",
                            claims = listOf(opening, kept, closing),
                            body = listOf(
                                Piece.Cited(opening),
                                Piece.Prose(""),
                                Piece.Prose("What was agreed."),
                                Piece.Prose(""),
                                Piece.Cited(kept),
                                Piece.Prose(""),
                                Piece.Cited(closing),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }

        val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

        assertEquals(
            listOf(
                Piece.Prose("What was agreed."),
                Piece.Prose(""),
                Piece.Cited(kept),
            ),
            assertIs<Answer.Composed>(answer).variants.single().body,
            "A blank line was left where the removed assertion had been, at an end where there " +
                "is nothing for it to separate.",
        )
    }

    @Test
    fun `a survivable citation fault that took all of an answer or none of it is not constructible`() {
        // The report is the only record these claims were made, so a count that cannot be read is
        // worse than no line at all. Dropping every claim is the fatal case and has its own verdict.
        val one = listOf("MESSAGE/TELEGRAM/42#0")
        assertFailsWith<IllegalArgumentException> {
            Fault.ClaimsDropped(cited = one, of = 1, claims = 2, dropped = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            Fault.ClaimsDropped(cited = one, of = 1, claims = 2, dropped = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            Fault.ClaimsDropped(cited = emptyList(), of = 1, claims = 2, dropped = 1)
        }
    }

    @Test
    fun `a fatal citation fault on an answer that cited nothing is not constructible`() {
        assertFailsWith<IllegalArgumentException> {
            Fault.NothingGrounded(cited = emptyList(), of = 1, claims = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            Fault.NothingGrounded(cited = listOf("MESSAGE/TELEGRAM/42#0"), of = 1, claims = 0)
        }
    }

    @Test
    fun `a citation fault carrying prose, or the same citation twice, is not constructible`() {
        // **Both sinks print this list, so the two invariants belong to the type and not to the one
        // caller that currently fills it.** They were stated in three contracts and provided by a
        // single expression in the runner, which made them a property of the wiring: a second caller
        // would have had to read all three and remember. The house form is the guard, as it already
        // is for every other way these faults can be built nonsensically.
        val prose = "x".repeat(Fault.Ungrounded.CITATION_EXCERPT + 1)
        assertFailsWith<IllegalArgumentException> {
            Fault.NothingGrounded(cited = emptyList(), of = 1, claims = 1, unreadable = listOf(prose))
        }
        assertFailsWith<IllegalArgumentException> {
            Fault.ClaimsDropped(
                cited = emptyList(),
                of = 2,
                claims = 2,
                dropped = 1,
                unreadable = listOf("two", "two"),
            )
        }
        // The boundary holds in the other direction: a citation exactly at the ceiling is a citation,
        // not prose, so the guard must not refuse the longest legitimate one.
        Fault.NothingGrounded(
            cited = emptyList(),
            of = 1,
            claims = 1,
            unreadable = listOf("x".repeat(Fault.Ungrounded.CITATION_EXCERPT)),
        )
    }

    @Test
    fun `a citation fault naming only what could not be read is a fault all the same`() {
        // **The half of the widened rule that has to hold either way.** Both faults require that
        // something was named, and what is named is one list or the other: an answer whose only bad
        // citation was unreadable names nothing in the identifier list, and a rule still reading that
        // list alone would raise out of the runner on the answer path rather than report the fault.
        val text = listOf("not a number")
        Fault.ClaimsDropped(cited = emptyList(), of = 1, claims = 2, dropped = 1, unreadable = text)
        Fault.NothingGrounded(cited = emptyList(), of = 1, claims = 1, unreadable = text)

        // And the other half: naming nothing in either list is still not a fault.
        assertFailsWith<IllegalArgumentException> {
            Fault.ClaimsDropped(
                cited = emptyList(),
                of = 1,
                claims = 2,
                dropped = 1,
                unreadable = emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Fault.NothingGrounded(cited = emptyList(), of = 1, claims = 1, unreadable = emptyList())
        }
    }

    @Test
    fun `an answer whose every claim is ungrounded is still refused`(): Unit = runBlocking {
        // Not a worse version of the case above but a different one: a reply resting entirely on
        // material that was never retrieved was not answered from the material, and offering its
        // prose alone would present a hedge as a finding.
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "invented",
                            claims = listOf(
                                Claim("they agreed", setOf(span("never retrieved"))),
                                Claim("they also agreed", setOf(span("nor this"))),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }
        val noticed = Noticed()

        val answer = runner(shape(stages = listOf(retrieve, generate)), faults = noticed).answer(ask())

        assertIs<Answer.InsufficientEvidence>(answer)
        assertEquals(2, generate.runs, "A fatal fault retries once, then degrades.")
        assertIs<Fault.NothingGrounded>(noticed.reasons.first().second)
    }

    @Test
    fun `an answer of assertions alone, none of them grounded, is answered not thrown`(): Unit = runBlocking {
        // **The one state the relaxed variant rule made reachable, and it threw out of the runner.**
        // A variant carrying no prose loses everything when its every claim is dropped, and building
        // that variant anyway fails `Variant`'s own initialiser — from inside the check that was
        // deciding *which* citation fault this was, so a mistyped identifier in a list of cited items
        // came back as a server error rather than as the answer the runner promises on every path.
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(Variant(prose = "", claims = listOf(Claim("they agreed", setOf(span("mistyped")))))),
                ),
                it,
            )
        }
        val noticed = Noticed()

        val answer = runner(shape(stages = listOf(retrieve, generate)), faults = noticed).answer(ask())

        assertIs<Answer.InsufficientEvidence>(answer)
        assertIs<Fault.NothingGrounded>(
            noticed.reasons.first().second,
            "Nothing survived, so this is the fatal citation fault rather than the survivable one.",
        )
    }

    @Test
    fun `a shape whose answer is its findings refuses a reply that asserts nothing`(): Unit = runBlocking {
        // **The reply the claims-only scope of the gate let through.** Every assertion in it is about
        // the corpus and none of it is marked, so the claim count and the surviving count are both
        // nought, the proportion that decides which citation fault applies is never reached, and
        // every citation check reports clean. What the shape declares is that its answer *is* those
        // assertions, which makes a reply carrying none a fault with nothing cited to name.
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(Variant(prose = "The material holds nothing about anyone by that name.")),
                ),
                it,
            )
        }
        val noticed = Noticed()

        val answer = runner(
            shape(assertsFindings = true, stages = listOf(retrieve, generate)),
            faults = noticed,
        ).answer(ask())

        assertIs<Answer.InsufficientEvidence>(answer)
        assertEquals(2, generate.runs, "A fatal fault retries once, then degrades.")
        val fault = assertIs<Fault.NothingAsserted>(
            noticed.reasons.first().second,
            "A reply that cited nothing at all was reported as a citation fault or as nothing. " +
                "There is no offender to name here, which is why it is a fault of its own.",
        )
        assertEquals(
            1,
            fault.of,
            "The fault does not say how much material the reply had while asserting none of it, " +
                "which is the only proportion available to whoever reads the line.",
        )
    }

    @Test
    fun `a shape whose deliverable is composed text keeps a reply that asserts nothing`(): Unit = runBlocking {
        // The other half, and the reason the rule is declared per shape rather than applied to every
        // answer: a drafted message carries no citations by instruction, so a rule reaching it would
        // refuse every draft — which is the failure the claims-only scope exists to prevent.
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(Answer.Composed(listOf(Variant(prose = "A message, ready to send."))), it)
        }
        val noticed = Noticed()

        val answer = runner(shape(stages = listOf(retrieve, generate)), faults = noticed).answer(ask())

        val composed = assertIs<Answer.Composed>(answer)
        assertEquals("A message, ready to send.", composed.variants.single().prose)
        assertEquals(1, generate.runs, "Nothing was wrong with it, so nothing should have re-rolled.")
        assertTrue(noticed.reasons.isEmpty(), "A complete draft was reported as a fault: ${noticed.reasons}")
    }

    @Test
    fun `an alternative with nothing to send is dropped and the rest are offered`(): Unit = runBlocking {
        // A reader picks one alternative and that is the whole of what they get, so one carrying
        // only assertions is not a thing being offered: pasted into a conversation it is a list of
        // third-person notes about the person being written to. The variant is legal — assertions
        // can carry one where the findings are the answer — which is why this is checked against
        // what the shape promised rather than refused by the type.
        //
        // **Dropped rather than refused, because two sendable alternatives are an answer.** The
        // shape offers two or three; taking the unusable one out leaves two, which is what was
        // promised. Discarding all three would spend a second model call to arrive at *nothing to
        // answer from* for an answer that was there.
        val real = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(prose = "Dear sir, the fifth suits us."),
                        Variant(prose = "Dear sir, let us say the fifth."),
                        Variant(prose = "", claims = listOf(Claim("the date was moved", setOf(real)))),
                    ),
                ),
                it,
            )
        }
        val noticed = Noticed()

        val answer = runner(
            shape(variants = 2..3, stages = listOf(retrieve, generate)),
            faults = noticed,
        ).answer(ask())

        val composed = assertIs<Answer.Composed>(answer)
        assertEquals(
            listOf("Dear sir, the fifth suits us.", "Dear sir, let us say the fifth."),
            composed.variants.map { it.prose },
            "The alternative with nothing to send is still in the answer, or the sendable ones went with it.",
        )
        assertEquals(1, generate.runs, "The answer was there, so nothing should have re-rolled.")
        val fault = assertIs<Fault.AlternativesDropped>(
            noticed.reasons.first().second,
            "Dropping an alternative went unreported, or was reported as something else. The " +
                "citation check cannot see it: a variant carrying claims and no prose grounds " +
                "exactly as well as any other.",
        )
        assertEquals(3, fault.offered, "The fault does not say how many alternatives it chose from.")
        assertEquals(1, fault.dropped, "The fault does not say how many had nothing to send.")
    }

    @Test
    fun `an alternative the citation repair removes is not reported as one with nothing to send`(): Unit = runBlocking {
        // **Which set the two counts are taken over, asserted where they are decided.** An
        // alternative that is a single assertion resting on nothing retrieved has nothing left once
        // that assertion goes, and the citation repair removes it — that is a citation fault and is
        // reported as one. Counted over the blocks the model wrote instead, the same reply reads as
        // a model that typed three alternatives and left one empty, which is a different event with
        // a different thing to go and look at.
        //
        // Two sendable alternatives remain either way, so the answer is the same and only the
        // report distinguishes them. That is the whole reason this is asserted on the fault.
        val real = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "Dear sir, the fifth suits us.",
                            claims = listOf(Claim("they agreed", setOf(real))),
                        ),
                        Variant(
                            prose = "Dear sir, let us say the fifth.",
                            claims = listOf(Claim("as agreed", setOf(real))),
                        ),
                        Variant(prose = "", claims = listOf(Claim("all of it invented", setOf(span("mistyped"))))),
                    ),
                ),
                it,
            )
        }
        val noticed = Noticed()

        val answer = runner(
            shape(variants = 2..3, stages = listOf(retrieve, generate)),
            faults = noticed,
        ).answer(ask())

        val composed = assertIs<Answer.Composed>(answer)
        assertEquals(
            listOf("Dear sir, the fifth suits us.", "Dear sir, let us say the fifth."),
            composed.variants.map { it.prose },
            "The alternative whose only assertion rested on nothing retrieved is still in the answer.",
        )
        assertIs<Fault.ClaimsDropped>(
            noticed.reasons.first().second,
            "An alternative the citation repair removed was reported as one carrying no text to " +
                "send. Both counts on that fault are over what survived the repair, so a reply " +
                "losing alternatives to citations has to be reported by the citation fault — " +
                "otherwise the line says one of three was empty where the other two had gone.",
        )
    }

    @Test
    fun `a drafting shape is refused when too few alternatives are left to offer`(): Unit = runBlocking {
        // **The case the count has to be re-asked to see, and the one the whole menu branch rests
        // on.** Two alternatives, one of them nothing but an assertion citing material nobody
        // retrieved. The citation repair drops that variant outright — it has no prose to stand on
        // once its only claim fails — which leaves one. Checked against the reply as the model wrote
        // it, the count passed; so without asking again the answer would be given as a single
        // variant, and a single variant is rendered in the reading order with its assertions folded
        // back into the prose. That is this card's defect, reached by the repair path.
        //
        // Refused rather than offered as one draft, because the shape promised a choice: what a
        // reader is handed is the promise, and one alternative is not two.
        val real = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "Dear sir, the fifth suits us.",
                            claims = listOf(Claim("they agreed", setOf(real))),
                        ),
                        Variant(prose = "", claims = listOf(Claim("all of it invented", setOf(span("mistyped"))))),
                    ),
                ),
                it,
            )
        }
        val noticed = Noticed()

        val answer = runner(
            shape(variants = 2..3, stages = listOf(retrieve, generate)),
            faults = noticed,
        ).answer(ask())

        assertIs<Answer.InsufficientEvidence>(answer)
        assertEquals(2, generate.runs, "A fatal fault retries once, then degrades.")
        val fault = assertIs<Fault.WrongVariantCount>(
            noticed.reasons.first().second,
            "An answer left with one alternative where two were promised was reported as a " +
                "citation fault, or as nothing. It is the count that is wrong, read of what a " +
                "reader would be offered rather than of what the model wrote.",
        )
        assertEquals(1, fault.got, "The fault does not say how many alternatives were left to offer.")
    }

    @Test
    fun `a shape offering one variant keeps a reply whose assertions are the whole of it`(): Unit = runBlocking {
        // **The scoping, which is the whole of the rule's correctness.** Read across every shape
        // the check above would refuse this reply — and `Variant` was deliberately widened to allow
        // it, because requiring prose rejected an answer the material fully supported and reached a
        // reader as *nothing to answer from*. A shape promising one variant renders it in the order
        // it was written, assertions included; only an alternative somebody picks has to stand
        // alone.
        val real = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(Variant(prose = "", claims = listOf(Claim("they agreed on the fifth", setOf(real))))),
                ),
                it,
            )
        }
        val noticed = Noticed()

        val answer = runner(
            shape(assertsFindings = true, stages = listOf(retrieve, generate)),
            faults = noticed,
        ).answer(ask())

        val composed = assertIs<Answer.Composed>(answer)
        assertEquals("", composed.variants.single().prose)
        assertEquals(1, generate.runs, "Nothing was wrong with it, so nothing should have re-rolled.")
        assertTrue(
            noticed.reasons.isEmpty(),
            "A rule written for alternatives reached the shape whose answer is its findings: ${noticed.reasons}",
        )
    }

    @Test
    fun `one assertion is enough for a shape whose answer is its findings`(): Unit = runBlocking {
        // The boundary: what is refused is a reply that asserts *nothing*, not one that asserts
        // little. A subject with one thing to say about it is answered with one claim, and a rule
        // that asked for more would be a judgement about how much an answer should contain.
        val real = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(listOf(Variant(prose = "", claims = listOf(Claim("they agreed", setOf(real)))))),
                it,
            )
        }
        val noticed = Noticed()

        val answer = runner(
            shape(assertsFindings = true, stages = listOf(retrieve, generate)),
            faults = noticed,
        ).answer(ask())

        assertIs<Answer.Composed>(answer)
        assertTrue(noticed.reasons.isEmpty(), "An answer that asserted something was refused: ${noticed.reasons}")
    }

    @Test
    fun `an asserting shape that retrieved nothing is answered as it stands`(): Unit = runBlocking {
        // **A reply asserting nothing about no material is not the fault**, because there was
        // nothing it could have asserted — so refusing it would refuse the honest answer. Not
        // reachable for either shape that declares this today: both read the corpus, and an empty
        // read settles before the model is called. It is the rule for one that does not, and it is
        // what makes the count the fault carries mean something.
        val generate = stage(spendsModel = true) {
            Progress.Settled(composed("nothing was retrieved and nothing is asserted"), it)
        }
        val noticed = Noticed()

        val answer = runner(
            shape(
                accepts = emptySet(),
                readsCorpus = false,
                assertsFindings = true,
                stages = listOf(generate),
            ),
            faults = noticed,
        ).answer(ask())

        assertIs<Answer.Composed>(answer)
        assertEquals(1, generate.runs)
        assertTrue(
            noticed.reasons.isEmpty(),
            "An answer that had nothing to assert was refused for not asserting it: ${noticed.reasons}",
        )
    }

    @Test
    fun `a fault for asserting nothing about no material is not constructible`() {
        // The invariant above, on the type rather than only in the runner that keeps it: the line
        // this fault builds says how much material was there, and nought reads as a fault nobody
        // could have avoided.
        assertFailsWith<IllegalArgumentException> { Fault.NothingAsserted(of = 0) }
    }

    @Test
    fun `an asserting shape's reply with the wrong count is reported as the count`(): Unit = runBlocking {
        // Precedence, pinned for the reason the citation clause's is: a reply failing two clauses
        // has to be reported as one of them, and the format having been misread is what to repair
        // first — a variant dropped for carrying an uncitable claim line is how this count falls
        // short, and reporting it as an answer that asserted nothing would send the next reader to
        // the shape's instructions instead.
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(listOf(Variant(prose = "one"), Variant(prose = "two"))),
                it,
            )
        }
        val noticed = Noticed()

        runner(
            shape(variants = 1..1, assertsFindings = true, stages = listOf(retrieve, generate)),
            faults = noticed,
        ).answer(ask())

        assertIs<Fault.WrongVariantCount>(noticed.reasons.first().second)
    }

    @Test
    fun `a variant left with nothing is dropped, and the rest of the answer is given`(): Unit = runBlocking {
        // The multi-variant half of the case above. Something survives overall, so the fault is
        // survivable and the repair runs — and the repair must remove the variant that has nothing
        // behind it rather than render it empty or refuse the whole answer.
        val real = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(prose = "", claims = listOf(Claim("all of it invented", setOf(span("mistyped"))))),
                        Variant(prose = "a draft", claims = listOf(Claim("they agreed", setOf(real)))),
                    ),
                ),
                it,
            )
        }

        val answer = runner(shape(variants = 1..2, stages = listOf(retrieve, generate))).answer(ask())

        val composed = assertIs<Answer.Composed>(answer)
        assertEquals(
            listOf("a draft"),
            composed.variants.map { it.prose },
            "The variant that lost every assertion it had is still in the answer, with nothing in it.",
        )
    }

    @Test
    fun `a variant that keeps some of its assertions survives without its prose`(): Unit = runBlocking {
        // The boundary on the other side: a claims-only variant is only dropped when *nothing* is
        // left. One surviving assertion is still a list of cited items, which is an answer.
        val real = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(real)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "",
                            claims = listOf(
                                Claim("they agreed", setOf(real)),
                                Claim("and this was invented", setOf(span("mistyped"))),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }

        val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

        val variant = assertIs<Answer.Composed>(answer).variants.single()
        assertEquals(listOf("they agreed"), variant.claims.map { it.text })
        assertEquals("", variant.prose, "A list of cited items carries no prose, and did not acquire any.")
    }

    @Test
    fun `a hand-built variant with no prose opens on its first assertion`() {
        // The body a blank-prose variant composes by default, which the relaxed rule made reachable.
        // Blank prose still splits into one empty line, so the obvious composition put that *and* the
        // paragraph break in front of the first finding. The one consumer joins the body and trims it,
        // so nothing would have shown it — the invariant was being held a layer away by accident.
        val variant = Variant(prose = "", claims = listOf(Claim("they agreed", setOf(span("a message")))))

        assertEquals(
            listOf<Piece>(Piece.Cited(Claim("they agreed", setOf(span("a message"))))),
            variant.body,
            "The body opens on blank prose, which is two empty lines before the first finding.",
        )
    }

    @Test
    fun `an unreadable reply cannot be reported for a reply that reads perfectly well`() {
        // The house convention: a fault that refuses to be built nonsensically. This one asserts that
        // nothing readable came back, and the reader is in the same package, so the claim is checkable
        // rather than merely written down.
        assertFailsWith<IllegalArgumentException> {
            Fault.Unreadable(
                ModelOutcome.Answered(text = "[VARIANT]\nA perfectly good answer.", usage = TokenUsage.NONE),
            )
        }
    }

    @Test
    fun `a stage cannot report a fault on an answer the runner checks for itself`() {
        // A rail rather than a precedence rule. Allowed, the stage's word would replace the variant
        // count and the grounding check on an answer that has both, and the report would read as
        // though the machinery had agreed with it.
        assertFailsWith<IllegalArgumentException> {
            Progress.Settled(
                composed("an answer"),
                Exchange(Request(text = "a question")),
                Fault.Unreadable(ModelOutcome.Refused(category = null, usage = TokenUsage.NONE)),
            )
        }
    }

    @Test
    fun `a stage cannot settle with the answer a request is given up as`() {
        // The sibling rail to the one above, in the other direction. `Answer.Unreadable` asserts
        // that a re-roll was spent and that the request still ended on an unreadable reply, which
        // is a fact about the whole request — a stage sees one attempt. Allowed, it would
        // reach a reader after a single call, with no re-roll attempted and no fault line behind
        // it: the one answer saying everything was tried, from a stage that tried once.
        assertFailsWith<IllegalArgumentException> {
            Progress.Settled(Answer.Unreadable(), Exchange(Request(text = "a question")))
        }
    }

    @Test
    fun `a stage's own report of an unreadable reply reaches the seam`(): Unit = runBlocking {
        // The one fault the runner cannot work out for itself. The answer is insufficient evidence
        // either way, so a report that stopped at the stage would leave a request that spent a model
        // call indistinguishable from one that found an empty corpus — which is what the four
        // separate causes of this reply looked like from outside before it was carried.
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
        val refused = ModelOutcome.Refused(category = "some_category", usage = TokenUsage.NONE)
        val generate = stage(spendsModel = true) {
            Progress.Settled(Answer.InsufficientEvidence(), it, Fault.Unreadable(refused))
        }
        val noticed = Noticed()

        val answer = runner(shape(stages = listOf(retrieve, generate)), faults = noticed).answer(ask())

        assertIs<Answer.InsufficientEvidence>(answer)
        assertEquals(refused, assertIs<Fault.Unreadable>(noticed.reasons.single().second).outcome)
    }

    @Test
    fun `a complete reply the reader rejected is re-rolled, and the second attempt answers`(): Unit =
        runBlocking {
            // **The asymmetry this removes.** Every other fatal fault was rejected and rolled again
            // while this one — the only one raised on a reply the provider reported complete — was
            // answered on the first attempt. Not re-rolling was argued from a format the reader rejects being
            // a shape's instructions disagreeing with the format; the reply that falsified it came
            // from a shape whose instructions and format agree and whose evaluation case passes on
            // that same format, so a second call on the same prompt is the one thing likely to read.
            val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
            var attempt = 0
            val generate = stage(spendsModel = true) {
                if (attempt++ == 0) {
                    Progress.Settled(Answer.InsufficientEvidence(), it, Fault.Unreadable(unparsed()))
                } else {
                    Progress.Settled(composed("the answer the first call also wrote"), it)
                }
            }

            val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

            assertIs<Answer.Composed>(answer)
            assertEquals(2, generate.runs, "The reply was complete and unread, which is a roll worth taking.")
        }

    @Test
    fun `a call that came back with nothing readable is answered as it stands`(): Unit = runBlocking {
        // **The half of the rule that is kept, and the reasons it rests on hold only here.** A
        // refusal refuses again, a truncation has no more room, and a pause stopped mid-flight —
        // none carries anything a reader could have rejected, so a second call buys the same failure
        // at the same price. Rolled again for every unreadable cause, every request that cannot be
        // answered would cost two calls out of the budget this build has least of.
        val outcomes = listOf(
            ModelOutcome.Refused(category = "some_category", usage = TokenUsage.NONE),
            ModelOutcome.Truncated(partial = "[VARIANT]\nHalf an ans", usage = TokenUsage.NONE),
            ModelOutcome.Paused(partial = "[VARIANT]\nmid-flight", usage = TokenUsage.NONE),
        )

        outcomes.forEach { outcome ->
            val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
            val generate = stage(spendsModel = true) {
                Progress.Settled(Answer.InsufficientEvidence(), it, Fault.Unreadable(outcome))
            }

            val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

            // The answer surviving the fault is the half that would crash rather than fail: repairing
            // one means taking ungrounded claims out, and this answer is not the kind that has any.
            assertIs<Answer.InsufficientEvidence>(answer)
            assertEquals(
                1,
                generate.runs,
                "${outcome::class.simpleName} was re-rolled, and a second call buys the same failure.",
            )
        }
    }

    @Test
    fun `a request given up on a reply nobody could read does not report an empty corpus`(): Unit =
        runBlocking {
            // **The most expensive failure this path can produce, and it reached the reader as the
            // cheapest.** Two full answers at the highest effort, discarded, reported in the words an
            // empty retrieval is reported in — which sends whoever asked looking for a gap in
            // material that answered them. The conflation was argued for a truncation and for a reply
            // that could not be grounded; it was never argued for this.
            val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
            val generate = stage(spendsModel = true) {
                Progress.Settled(Answer.InsufficientEvidence(), it, Fault.Unreadable(unparsed()))
            }

            val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

            assertIs<Answer.Unreadable>(answer)
            assertEquals(2, generate.runs)
            assertEquals(COMPLETE_THROUGH, answer.freshness.completeThrough, "Every answer is stamped.")
        }

    @Test
    fun `a re-roll the budget cannot afford is insufficient evidence`(): Unit = runBlocking {
        // **The documented residue of the narrowed rule, asserted rather than described.** The
        // second attempt stops before the call, carries no fault of its own, and is finished as what
        // it settled — which is the honest reading of a budget with nothing left in it, and is not
        // the answer saying a second call was made and came back unreadable too. Nearest to
        // reachable on the open-web shape, whose own ceiling is half the per-request one.
        val generate = stage(spendsModel = true) { exchange ->
            Progress.Settled(
                Answer.InsufficientEvidence(),
                exchange.spending(TokenUsage(inputTokens = 0, outputTokens = 9_999)),
                Fault.Unreadable(unparsed()),
            )
        }

        val answer = runner(
            shape = shape(readsCorpus = false, stages = listOf(generate)),
            ceiling = 10_000,
        ).answer(ask())

        assertIs<Answer.InsufficientEvidence>(answer)
        assertEquals(1, generate.runs, "The second call was made on a budget that could not pay.")
    }

    @Test
    fun `a fault with nothing to re-roll is insufficient evidence, whatever it was`(): Unit =
        runBlocking {
            // The answer saying a reply could not be read asserts that a second call was made, so
            // the branch where there was nothing to re-roll may not give it — that would claim of
            // the request exactly what `Progress.Settled` refuses to let a stage claim. Reached by
            // a stage that reports the fault without declaring that it spends the model, which is
            // the only way to have no resume point and a fault at once.
            val generate = stage {
                Progress.Settled(Answer.InsufficientEvidence(), it, Fault.Unreadable(unparsed()))
            }
            val answer = runner(shape(readsCorpus = false, stages = listOf(generate))).answer(ask())

            assertIs<Answer.InsufficientEvidence>(answer)
            assertEquals(1, generate.runs)
        }

    @Test
    fun `a request given up on a second attempt that could not be grounded says so instead`(): Unit =
        runBlocking {
            // **The fault that ended the request decides, not whichever happened first.** By the end
            // of this one there was an answer and it rested on nothing retrieved, which is the case
            // *nothing to answer from* was argued for — so an unreadable first attempt does not
            // carry its own wording through to a request that finished some other way.
            val retrieved = span("a message")
            val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(retrieved)))) }
            var attempt = 0
            val generate = stage(spendsModel = true) {
                if (attempt++ == 0) {
                    Progress.Settled(Answer.InsufficientEvidence(), it, Fault.Unreadable(unparsed()))
                } else {
                    Progress.Settled(
                        Answer.Composed(
                            listOf(Variant("invented", listOf(Claim("they agreed", setOf(span("never retrieved")))))),
                        ),
                        it,
                    )
                }
            }

            val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

            assertIs<Answer.InsufficientEvidence>(answer)
            assertEquals(2, generate.runs)
        }

    @Test
    fun `a retry that comes back grounded is the answer`(): Unit = runBlocking {
        val span = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span)))) }
        var attempt = 0
        val generate = stage(spendsModel = true) {
            val cited = if (attempt++ == 0) span("never retrieved") else span
            Progress.Settled(
                Answer.Composed(listOf(Variant("a claim", listOf(Claim("they agreed", setOf(cited)))))),
                it,
            )
        }

        val answer = runner(shape(stages = listOf(retrieve, generate))).answer(ask())

        assertIs<Answer.Composed>(answer)
        assertEquals(2, generate.runs)
    }

    @Test
    fun `a rejection says which citations were not retrieved, and out of how much`(): Unit = runBlocking {
        // **Both attempts are reported, because the request's own outcome cannot say any of this.**
        // Two model calls on substantial answers degrade to the same reply an empty corpus gets, so
        // without this a working retrieval and a question nobody could answer read identically.
        val retrieved = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(retrieved)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(Variant("invented", listOf(Claim("they agreed", setOf(span("never retrieved")))))),
                ),
                it,
            )
        }
        val noticed = Noticed()

        runner(shape(stages = listOf(retrieve, generate)), faults = noticed).answer(ask())

        assertEquals(listOf(1, 2), noticed.reasons.map { (attempt, _) -> attempt })
        val reason = assertIs<Fault.NothingGrounded>(noticed.reasons.first().second)
        assertEquals(1, reason.claims, "The proportion is what tells a slip from a fabrication.")
        assertEquals(1, reason.of)
        // The written form the prompt shows, not a span identifier's own `toString` — the question
        // a reader has is how what the model wrote differs from what it was shown.
        assertEquals(listOf("MESSAGE/TELEGRAM/never retrieved#0"), reason.cited)
    }

    @Test
    fun `a rejection for the wrong variant count says so, and is not read as a bad citation`(): Unit =
        runBlocking {
            // The two are repaired in different places: this one means the format was not read as
            // written, which is also where a dropped claim surfaces.
            val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
            val generate = stage(spendsModel = true) { Progress.Settled(composed("the only draft"), it) }
            val noticed = Noticed()

            runner(shape(variants = 2..3, stages = listOf(retrieve, generate)), faults = noticed)
                .answer(ask())

            val reason = assertIs<Fault.WrongVariantCount>(noticed.reasons.first().second)
            assertEquals(1, reason.got)
            assertEquals(2..3, reason.wanted)
        }

    @Test
    fun `an answer failing both clauses is reported as the wrong count, not the bad citation`(): Unit =
        runBlocking {
            // **The case that fixes the precedence.** Either clause alone is reported by the two
            // cases above whichever order they are checked in, so neither pins it: only an answer
            // that fails both can. The count comes first because a reply whose format was misread
            // has no citations worth reporting — the variant carrying them was already dropped, so
            // naming what survived would send a reader after the wrong defect.
            val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
            val generate = stage(spendsModel = true) {
                Progress.Settled(
                    Answer.Composed(
                        listOf(Variant("one of three", listOf(Claim("they agreed", setOf(span("invented")))))),
                    ),
                    it,
                )
            }
            val noticed = Noticed()

            runner(shape(variants = 2..3, stages = listOf(retrieve, generate)), faults = noticed)
                .answer(ask())

            assertIs<Fault.WrongVariantCount>(noticed.reasons.first().second)
        }

    @Test
    fun `an answer that holds is reported as nothing`(): Unit = runBlocking {
        val span = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(listOf(Variant("a claim", listOf(Claim("they agreed", setOf(span)))))),
                it,
            )
        }
        val noticed = Noticed()

        runner(shape(stages = listOf(retrieve, generate)), faults = noticed).answer(ask())

        assertTrue(noticed.reasons.isEmpty(), "Reported ${noticed.reasons} for an answer that held.")
    }

    @Test
    fun `a variant count outside the shape's contract does not pass`(): Unit = runBlocking {
        // A shape promising selectable alternatives and returning one is a defect the interface
        // cannot see: it renders what it is given, and one variant looks exactly like a shape
        // that never offered a choice.
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
        val generate = stage(spendsModel = true) { Progress.Settled(composed("the only draft"), it) }

        val answer = runner(shape(variants = 2..3, stages = listOf(retrieve, generate))).answer(ask())

        assertIs<Answer.InsufficientEvidence>(answer)
        assertEquals(2, generate.runs)
    }

    @Test
    fun `an ambiguous name is a question, and no stage runs`(): Unit = runBlocking {
        val retrieve = stage { Progress.Carried(it) }
        val search = FakeSearch(listOf(client("one organisation"), client("another organisation")))

        val answer = runner(
            shape(accepts = setOf(SubjectKind.CLIENT), stages = listOf(retrieve)),
            search = search,
        ).answer(ask())

        val unclear = assertIs<Answer.SubjectUnclear>(answer)
        assertEquals(2, unclear.options.size)
        assertEquals(COMPLETE_THROUGH, unclear.freshness.completeThrough)
        assertEquals(0, retrieve.runs, "A pipeline ran for a subject that was never settled.")
    }

    @Test
    fun `an unmatched name reports what the search covers, not what the shape accepts`(): Unit = runBlocking {
        val search = FakeSearch()

        val answer = runner(shape(accepts = setOf(SubjectKind.CONTACT)), search = search).answer(ask())

        val unknown = assertIs<Answer.SubjectUnknown>(answer)
        // The shape accepts one kind and the search covers three, so this is the field's meaning
        // asserted rather than an overlap between the two sets passing for it. What reaches a reader
        // is where their name was looked for, which is the search's business and not the shape's.
        assertEquals(search.covers, unknown.searched)
        assertEquals("a lead", unknown.name)
        assertEquals(COMPLETE_THROUGH, unknown.freshness.completeThrough)
    }

    @Test
    fun `a command that takes a subject and was given none asks for one`(): Unit = runBlocking {
        // **The measured defect.** A bare command at a shape that cannot answer for the inbox —
        // the one that drafts a reply, the one that prepares for a call — resolved to the
        // nothing-found verdict, so the reader was told nothing suitable was found about a search
        // that never ran. Nothing was looked up because no name was given, and the reply that
        // reports an absence from the corpus sends them after a gap in the material rather than
        // after the argument they left off.
        val search = FakeSearch(listOf(contact("a lead")))
        val retrieve = stage { Progress.Carried(it) }
        val runner = runner(shape(accepts = setOf(SubjectKind.CONTACT), stages = listOf(retrieve)), search = search)

        val answer = runner.answer(Request(text = "/draft", command = "draft"))

        assertIs<Answer.SubjectMissing>(
            answer,
            "A command with its argument missing was answered as a search that found nothing.",
        )
        assertEquals(COMPLETE_THROUGH, answer.freshness.completeThrough)
        assertTrue(
            search.namesAsked.isEmpty(),
            "There was no name to search for; it asked for ${search.namesAsked}.",
        )
        assertEquals(0, retrieve.runs, "A pipeline ran for a request whose subject was never settled.")
    }

    @Test
    fun `free text the routing call read as naming nobody asks for a subject too`(): Unit = runBlocking {
        // **The second producer of the same state, which the first wording presumed away.** A
        // command is not the only way to name nobody: the routing call reads free text and can
        // report that it named no particular subject, and a shape that cannot sweep then has a
        // request with its subject missing just the same. A reply that told this reader to add a
        // name after their command would be describing something they never typed — which is worse
        // than vagueness, because it is checkably untrue.
        val search = FakeSearch(listOf(contact("a lead")))
        val runner = runner(
            shape = shape(accepts = setOf(SubjectKind.CONTACT), stages = listOf(stage { Progress.Carried(it) })),
            search = search,
            classifier = NamingClassifier(SubjectName.None),
        )

        val answer = runner.answer(Request(text = "набросай ответ"))

        assertIs<Answer.SubjectMissing>(
            answer,
            "Free text that named nobody at a shape that cannot sweep is the same request with its " +
                "subject missing, and must reach the same answer as a bare command.",
        )
        assertTrue(search.namesAsked.isEmpty(), "There was no name to search for; it asked for ${search.namesAsked}.")
    }

    @Test
    fun `a command given no subject still reaches the inbox where the shape accepts it`(): Unit = runBlocking {
        // The other half of the same branch, and what keeps the fix narrow: a bare command at the
        // shape that sweeps is the daily question rather than a request missing its argument.
        val search = FakeSearch()
        val runner = runner(
            shape = shape(
                accepts = setOf(SubjectKind.CONTACT, SubjectKind.INBOX),
                stages = listOf(stage { Progress.Settled(composed("what is outstanding"), it) }),
            ),
            search = search,
        )

        val answer = runner.answer(Request(text = "/status", command = "status"))

        assertIs<Answer.Composed>(answer)
        assertTrue(search.namesAsked.isEmpty(), "it searched for ${search.namesAsked}")
    }

    @Test
    fun `a name the corpus spells differently is answered with the spellings, not as unknown`(): Unit = runBlocking {
        // The runner's own arm, which is where the resolution verdict becomes the reply somebody
        // reads. Asserted as the type, because the two verdicts carry nearly the same fields and a
        // runner that mapped this one onto the unknown case would still produce a well-formed answer
        // — one that tells a reader their subject is absent from a corpus that holds it.
        val search = FakeSearch(matches = emptyList(), near = listOf(contact("Arbour Warden")))

        val answer = runner(shape(accepts = setOf(SubjectKind.CONTACT)), search = search).answer(ask())

        val near = assertIs<Answer.SubjectNearest>(answer)
        assertEquals(listOf("Arbour Warden"), near.nearest.map { it.name })
        assertEquals("a lead", near.name)
        assertEquals(search.covers, near.searched)
        // Stamped like every other reply, including the ones that found nothing — which is where it
        // matters most, since a near miss while ingestion is behind may be a name not yet indexed.
        assertEquals(COMPLETE_THROUGH, near.freshness.completeThrough)
    }

    @Test
    fun `a reply offering no near spellings is refused`() {
        // The sibling of the resolver's guard, at the type anything composing an answer can reach:
        // the key a reader is shown and the list beneath it are one statement, and an empty list
        // under it is an absence worded as a near miss.
        assertFailsWith<IllegalArgumentException> {
            Answer.SubjectNearest(nearest = emptyList(), searched = setOf(SubjectKind.CONTACT), name = "a lead")
        }
        assertFailsWith<IllegalArgumentException> {
            Answer.SubjectNearest(
                nearest = listOf(contact("a near name")),
                searched = setOf(SubjectKind.CONTACT, SubjectKind.INBOX),
                name = "a lead",
            )
        }
    }

    @Test
    fun `an answer claiming the inbox was searched is refused`() {
        // The sibling of the resolver's own guard. This type is reachable by anything that composes
        // an answer, not only by the one path that carries a resolution into it, so the claim is
        // refused where it would be published rather than only where it is produced today.
        assertFailsWith<IllegalArgumentException> {
            Answer.SubjectUnknown(searched = setOf(SubjectKind.CONTACT, SubjectKind.INBOX), name = "a lead")
        }
    }

    @Test
    fun `a shape that accepts no subject is never resolved for`(): Unit = runBlocking {
        val generate = stage(spendsModel = true) { Progress.Settled(composed("answered from the text"), it) }
        val search = FakeSearch()

        runner(
            shape(accepts = emptySet(), readsCorpus = false, stages = listOf(generate)),
            search = search,
        ).answer(ask())

        assertTrue(
            search.namesAsked.isEmpty(),
            "Scope was resolved for a shape that declared it needs none, which the resolver " +
                "refuses outright — so this would be a failure rather than a wasted read.",
        )
    }

    @Test
    fun `a stage list that never settles is a defect rather than an empty answer`(): Unit = runBlocking {
        val carryOn = stage { Progress.Carried(it) }

        assertFailsWith<IllegalStateException> {
            runner(shape(readsCorpus = false, stages = listOf(carryOn))).answer(ask())
        }
    }

    @Test
    fun `a source that has never reported leaves the corpus complete through nothing`(): Unit = runBlocking {
        // Nothing is known about what it holds, so there is no mark to claim. The multi-source
        // minimum this guards cannot be exercised while one source is in scope — the merge is
        // written for the arithmetic rather than for the current enum.
        val generate = stage(spendsModel = true) { Progress.Settled(composed("an answer"), it) }

        val answer = runner(
            shape = shape(accepts = emptySet(), readsCorpus = false, stages = listOf(generate)),
            freshness = null,
        ).answer(ask())

        val composed = assertIs<Answer.Composed>(answer)
        assertEquals(null, composed.freshness.completeThrough)
        assertTrue(composed.freshness.lagging.isEmpty())
    }

    @Test
    fun `a source reporting nothing lagging is absent rather than present and empty`(): Unit = runBlocking {
        val generate = stage(spendsModel = true) { Progress.Settled(composed("an answer"), it) }

        val answer = runner(
            shape = shape(accepts = emptySet(), readsCorpus = false, stages = listOf(generate)),
            freshness = Freshness(COMPLETE_THROUGH, lagging = emptyList()),
        ).answer(ask())

        assertEquals(emptyMap(), assertIs<Answer.Composed>(answer).freshness.lagging)
    }

    @Test
    fun `the request carries a budget into the first stage`(): Unit = runBlocking {
        // A stage sizes its call against what the request has left, so a budget that never arrived
        // would leave every shape sizing against its own ceiling alone.
        var seen: Budget? = null
        val generate = stage(spendsModel = true) { exchange ->
            seen = exchange.budget
            Progress.Settled(composed("an answer"), exchange)
        }

        runner(
            shape = shape(readsCorpus = false, stages = listOf(generate)),
            ceiling = 50_000,
        ).answer(ask())

        assertEquals(50_000, seen?.remaining)
    }

    @Test
    fun `the retry draws on what the first attempt left, not a fresh budget`(): Unit = runBlocking {
        // The resume point holds the exchange as it stood *before* generation, which remembers a
        // spend of nothing. Resuming from it verbatim would make the ceiling per attempt rather than
        // per request — the doubling a per-request bound exists to prevent.
        val remaining = mutableListOf<Int>()
        val generate = stage(spendsModel = true) { exchange ->
            remaining += exchange.budget.remaining
            // Ungrounded, so the runner retries: a claim citing something nothing retrieved.
            Progress.Settled(
                Answer.Composed(listOf(Variant("x", listOf(Claim("invented", setOf(span("404"))))))),
                exchange.spending(TokenUsage(inputTokens = 0, outputTokens = 10_000)),
            )
        }

        runner(
            shape = shape(readsCorpus = false, stages = listOf(generate)),
            ceiling = 50_000,
        ).answer(ask())

        assertEquals(listOf(50_000, 40_000), remaining)
    }

    @Test
    fun `a request that has spent its ceiling is not sent to the model`(): Unit = runBlocking {
        // Stopping before the call is the cheaper of the two routes to the same answer: a call given
        // what is left of an exhausted budget spends it thinking and returns a truncation.
        val first = stage(spendsModel = true) { exchange ->
            Progress.Carried(exchange.spending(TokenUsage(inputTokens = 0, outputTokens = 9_999)))
        }
        val second = stage(spendsModel = true) { exchange ->
            Progress.Settled(composed("should never run"), exchange)
        }

        val answer = runner(
            shape = shape(readsCorpus = false, stages = listOf(first, second)),
            ceiling = 10_000,
        ).answer(ask())

        assertIs<Answer.InsufficientEvidence>(answer)
        assertEquals(1, first.runs)
        assertEquals(0, second.runs)
    }

    @Test
    fun `an exhausted budget degrades the same way an empty corpus does`(): Unit = runBlocking {
        // Deliberately the same answer: in both cases there is nothing the system can stand behind,
        // and a distinct case would invite a caller to render one of them as a fault.
        val generate = stage(spendsModel = true) { exchange ->
            Progress.Settled(composed("unreachable"), exchange)
        }

        val answer = runner(
            shape = shape(readsCorpus = false, stages = listOf(generate)),
            ceiling = Budget.FLOOR - 1,
        ).answer(ask())

        assertIs<Answer.InsufficientEvidence>(answer)
        assertEquals(0, generate.runs)
    }

    @Test
    fun `the budget is stamped before routing is charged for`(): Unit = runBlocking {
        // A free-text request has already spent something by the time a shape is chosen, so a budget
        // that started counting at generation would under-report every one of them.
        var seen: Budget? = null
        val fallback = shape(
            accepts = emptySet(),
            readsCorpus = false,
            stages = listOf(
                stage(spendsModel = true) { exchange ->
                    seen = exchange.budget
                    Progress.Settled(composed("an answer"), exchange)
                },
            ),
        )
        val runner = Runner(
            shapes = ShapeRegistry(
                shapes = emptyList(),
                fallback = fallback,
                classifier = SpendingClassifier(TokenUsage(inputTokens = 100, outputTokens = 500)),
            ),
            scope = ScopeResolver(FakeSearch()),
            citations = CitationCheck(),
            retrieval = FakeRetrieval(),
            ingestState = FakeIngestState(Freshness(COMPLETE_THROUGH)),
            ceiling = 20_000,
        )

        runner.answer(Request(text = "free text with no command"))

        assertEquals(19_500, seen?.remaining)
    }

    @Test
    fun `a subject read out of free text is what the answer is scoped to`(): Unit = runBlocking {
        // The failure this prevents: free text about a named subject, arriving with no name
        // separated out, resolving to whatever the shape accepts when asked about nothing. For
        // the shape that accepts the inbox that is the inbox — a confident answer about the wrong
        // subject, which is the one failure mode nothing downstream can see.
        val search = FakeSearch(listOf(contact("a lead")))
        val runner = runner(
            shape = shape(stages = listOf(stage { Progress.Settled(composed("an answer"), it) })),
            search = search,
            classifier = NamingClassifier("a lead"),
        )

        runner.answer(Request(text = "where did we leave off with a lead"))

        assertEquals(listOf("a lead"), search.namesAsked)
    }

    @Test
    fun `a name on the request wins over one read out of the text`(): Unit = runBlocking {
        // A name somebody typed as an argument is what they meant; a name read out of prose is an
        // inference about what they meant.
        val search = FakeSearch(listOf(contact("a lead")))
        val runner = runner(
            shape = shape(stages = listOf(stage { Progress.Settled(composed("an answer"), it) })),
            search = search,
            classifier = NamingClassifier("somebody else entirely"),
        )

        runner.answer(Request(text = "a question", subjectName = "a lead"))

        assertEquals(listOf("a lead"), search.namesAsked)
    }

    @Test
    fun `free text naming nothing still reaches the inbox without a search`(): Unit = runBlocking {
        // The inbox is reached by a request having named nobody rather than by matching one, and it is the
        // daily question. Reading a subject out of the text must not have turned that into a
        // search for something nobody named.
        val search = FakeSearch()
        val runner = runner(
            shape = shape(
                accepts = setOf(SubjectKind.CONTACT, SubjectKind.INBOX),
                stages = listOf(stage { Progress.Settled(composed("an answer"), it) }),
            ),
            search = search,
            classifier = NamingClassifier(SubjectName.None),
        )

        val answer = runner.answer(Request(text = "what is on today"))

        assertTrue(search.namesAsked.isEmpty(), "it searched for ${search.namesAsked}")
        assertIs<Answer.Composed>(answer)
    }

    @Test
    fun `free text whose subject the routing call never read is not answered for the inbox`(): Unit = runBlocking {
        // **The measured defect, end to end.** Asked as free text who a person the corpus holds
        // is, the routing call returned one line — a shape and no name. That reached the resolver
        // as *no name*, which for the shape accepting the inbox means the inbox, so the request
        // was composed an answer about the whole corpus: it stated that nothing mentions anyone by
        // that name and then listed ten other participants, while the person was present on three
        // contacts and a direct conversation of their own.
        //
        // Nothing afterwards could see it. The answer was composed rather than refused, carried no
        // claims so the grounding gate read clean, and the only distinguishing evidence was the
        // routing call's output token count.
        val search = FakeSearch(listOf(contact("a lead")))
        val runner = runner(
            shape = shape(
                accepts = setOf(SubjectKind.CONTACT, SubjectKind.INBOX),
                stages = listOf(stage { Progress.Settled(composed("an answer about everything"), it) }),
            ),
            search = search,
            classifier = NamingClassifier(SubjectName.Unread),
        )

        val answer = runner.answer(Request(text = "who is a lead"))

        assertIs<Answer.SubjectUnread>(
            answer,
            "A question about a named person was answered as though no name had been given.",
        )
        assertTrue(
            search.namesAsked.isEmpty(),
            "There was no name to search for; it asked for ${search.namesAsked}.",
        )
    }

    @Test
    fun `a blank name cannot reach the runner to shadow the one read out of the text`() {
        // The same wrong answer by the other route. Only the interface this build ships omits the
        // field where there is no argument; any other caller may send it empty, and taken as
        // present it beat the name the routing call had read and resolved to the inbox.
        //
        // Refused by the request rather than stepped over here, which is where the bound belongs:
        // one expression in one consumer is the arrangement where several contracts describe a
        // bound that a single caller provides. The wire turns an emptied field into the absence it
        // meant — see `AnswerRequest.toDomain`.
        assertFailsWith<IllegalArgumentException> {
            Request(text = "where did we leave off with a lead", subjectName = "   ")
        }
    }

    @Test
    fun `a name on the request is what the answer is scoped to, over the classified one`(): Unit = runBlocking {
        // The surviving half of the case above: a present, non-blank argument wins, and the
        // classified name does not get a look in.
        val search = FakeSearch(listOf(contact("a lead")))
        val runner = runner(
            shape = shape(
                accepts = setOf(SubjectKind.CONTACT, SubjectKind.INBOX),
                stages = listOf(stage { Progress.Settled(composed("an answer"), it) }),
            ),
            search = search,
            classifier = NamingClassifier("somebody else"),
        )

        runner.answer(Request(text = "where did we leave off", subjectName = "a lead"))

        assertEquals(listOf("a lead"), search.namesAsked)
    }

    @Test
    fun `an answer reports what it retrieved and what it cited`(): Unit = runBlocking {
        val cited = span("the message it rests on")
        val retrieve = stage {
            Progress.Carried(it.copy(retrieved = listOf(span(cited), span(span("retrieved and unused")))))
        }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(listOf(Variant("what was agreed", listOf(Claim("they agreed", setOf(cited)))))),
                it,
            )
        }
        val reported = RecordingTrace()

        runner(shape(stages = listOf(retrieve, generate)), trace = reported).answer(ask())

        // Literal strings rather than `Citation.render`, because this is the form the evaluation
        // set compares against: a test that rendered its own expectation would agree with any
        // change to the format, including one that broke the gate.
        assertEquals(
            listOf(
                Attempted(
                    shape = ShapeId("under test"),
                    ordinal = 1,
                    retrieved = listOf(
                        "MESSAGE/TELEGRAM/retrieved and unused#0",
                        "MESSAGE/TELEGRAM/the message it rests on#0",
                    ),
                    cited = listOf("MESSAGE/TELEGRAM/the message it rests on#0"),
                    ungrounded = emptyList(),
                    survived = listOf("MESSAGE/TELEGRAM/the message it rests on#0"),
                ),
            ),
            reported.attempts,
            "Retrieval and grounding are scored from this and from nothing else, so what is " +
                "reported has to be the retrieved set rather than the cited one — an answer " +
                "citing only what it used must still show what it was given and did not.",
        )
    }

    @Test
    fun `what survived excludes a dropped claim's good citation, which no subtraction could`(): Unit = runBlocking {
        // **The reason the trace reports this rather than leaving it to be derived.** A claim is
        // dropped whole rather than stripped of the citation that failed, so one resting on a span
        // that was retrieved and one that was not takes the good identifier out of the answer with
        // it. `cited` is flat, so nothing downstream can tell which of its entries shared a claim —
        // and `cited` minus `ungrounded` therefore keeps that good identifier, which is a citation
        // the reader was never shown. Whoever compares that against the wire reads a repair the
        // runner made deliberately as the presentation mapping having lost something.
        val kept = span("the claim that stands")
        val alsoCited = span("retrieved, but cited beside something absent")
        val retrieve = stage {
            Progress.Carried(it.copy(retrieved = listOf(span(kept), span(alsoCited))))
        }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "what was agreed",
                            claims = listOf(
                                Claim("this one holds", setOf(kept)),
                                Claim("this one rests on both", setOf(alsoCited, span("never retrieved"))),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }
        val reported = RecordingTrace()

        runner(shape(stages = listOf(retrieve, generate)), trace = reported).answer(ask())

        val attempt = reported.attempts.single()
        assertEquals(
            listOf("MESSAGE/TELEGRAM/the claim that stands#0"),
            attempt.survived,
            "The citation of a claim that was dropped whole was reported as having survived.",
        )
        assertTrue(
            "MESSAGE/TELEGRAM/retrieved, but cited beside something absent#0" in attempt.cited,
            "It was cited, so it belongs in `cited` — that half reports what the model wrote.",
        )
    }

    @Test
    fun `the trace reports an unreadable citation as ungrounded, not as nothing at all`(): Unit = runBlocking {
        // **A trace that reported a shrunken `survived` and nothing ungrounded would contradict
        // itself**, and the evaluation set scores a clean attempt off exactly that pair — so an
        // attempt that dropped a finding for a citation nobody could read would be graded as one
        // that cited perfectly well. The fault keeps the two kinds apart for the log; this line is
        // the question of whether any citation failed, where they are one answer.
        val kept = span("the claim that stands")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(kept)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            prose = "what was agreed",
                            claims = listOf(
                                Claim("this one holds", setOf(kept)),
                                Claim("this one cited a word", setOf(kept), unreadable = setOf("two")),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }
        val reported = RecordingTrace()

        runner(shape(stages = listOf(retrieve, generate)), trace = reported).answer(ask())

        assertEquals(
            listOf("two"),
            reported.attempts.single().ungrounded,
            "An attempt that dropped a claim for a citation nothing could read reported nothing " +
                "ungrounded, which scores as a clean attempt.",
        )
    }

    @Test
    fun `an answer discarded whole survived nothing`(): Unit = runBlocking {
        // The fatal case. Reporting what it cited as having survived would say the reader was shown
        // findings that were thrown away, on the attempt that produced no answer at all.
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(Variant("invented", listOf(Claim("they agreed", setOf(span("never retrieved")))))),
                ),
                it,
            )
        }
        val reported = RecordingTrace()

        runner(shape(stages = listOf(retrieve, generate)), trace = reported).answer(ask())

        assertTrue(
            reported.attempts.all { it.survived.isEmpty() },
            "A discarded answer reported survivors: ${reported.attempts.map { it.survived }}",
        )
    }

    @Test
    fun `both attempts are reported, and the first says why it did not hold`(): Unit = runBlocking {
        val retrieved = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(retrieved)))) }
        var attempt = 0
        val generate = stage(spendsModel = true) {
            val cited = if (attempt++ == 0) span("never retrieved") else retrieved
            Progress.Settled(
                Answer.Composed(listOf(Variant("a claim", listOf(Claim("they agreed", setOf(cited)))))),
                it,
            )
        }
        val reported = RecordingTrace()

        runner(shape(stages = listOf(retrieve, generate)), trace = reported).answer(ask())

        assertEquals(listOf(1, 2), reported.attempts.map { it.ordinal })
        assertEquals(
            listOf("MESSAGE/TELEGRAM/never retrieved#0"),
            reported.attempts.first().ungrounded,
            "A retry that came back grounded is the answer, so keeping only the attempt that " +
                "held would show a clean run every time the retry saved one.",
        )
        assertEquals(emptyList(), reported.attempts.last().ungrounded)
    }

    @Test
    fun `a variant count failure is reported as a retry with nothing ungrounded`(): Unit = runBlocking {
        // The two ways an answer fails to hold are scored differently, and this is what tells
        // them apart: a retry whose first attempt cited nothing invented is the shape
        // disagreeing with its own contract rather than a claim the corpus does not support.
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(span("a message"))))) }
        val generate = stage(spendsModel = true) { Progress.Settled(composed("the only draft"), it) }
        val reported = RecordingTrace()

        runner(shape(variants = 2..3, stages = listOf(retrieve, generate)), trace = reported).answer(ask())

        assertEquals(listOf(1, 2), reported.attempts.map { it.ordinal })
        assertTrue(reported.attempts.all { it.ungrounded.isEmpty() && it.cited.isEmpty() })
    }

    @Test
    fun `a claim dropped from an answer that still holds is reported as ungrounded`(): Unit = runBlocking {
        // The survivable fault, which is the one the answer hides. The reader is given the
        // findings that could be checked and nothing says the others were made — so an attempt
        // the runner let through still has to report what it took out, or the gate scores a
        // partially invented answer as clean.
        val retrieved = span("a message")
        val retrieve = stage { Progress.Carried(it.copy(retrieved = listOf(span(retrieved)))) }
        val generate = stage(spendsModel = true) {
            Progress.Settled(
                Answer.Composed(
                    listOf(
                        Variant(
                            "two findings",
                            listOf(
                                Claim("they agreed", setOf(retrieved)),
                                Claim("and something else", setOf(span("mistyped"))),
                            ),
                        ),
                    ),
                ),
                it,
            )
        }
        val reported = RecordingTrace()

        val answer = runner(shape(stages = listOf(retrieve, generate)), trace = reported).answer(ask())

        assertIs<Answer.Composed>(answer)
        assertEquals(1, reported.attempts.size, "The answer held, so nothing should have been re-rolled.")
        assertEquals(listOf("MESSAGE/TELEGRAM/mistyped#0"), reported.attempts.single().ungrounded)
    }

    @Test
    fun `a request that retrieved nothing is reported, rather than going unreported`(): Unit = runBlocking {
        // The confusion the report exists to remove. Nothing retrieved and nothing reported are
        // the same observation from outside, so a short-circuit that said nothing would make an
        // unwired trace indistinguishable from an empty corpus.
        val retrieve = stage { Progress.Carried(it) }
        val generate = stage(spendsModel = true) { Progress.Settled(composed("should never run"), it) }
        val reported = RecordingTrace()

        runner(shape(stages = listOf(retrieve, generate)), trace = reported).answer(ask())

        assertEquals(1, reported.attempts.size)
        assertEquals(emptyList(), reported.attempts.single().retrieved)
    }

    @Test
    fun `a name that resolves to nothing reports no attempt, because none was made`(): Unit = runBlocking {
        val generate = stage(spendsModel = true) { Progress.Settled(composed("should never run"), it) }
        val reported = RecordingTrace()

        runner(
            shape(stages = listOf(generate)),
            search = FakeSearch(emptyList()),
            trace = reported,
        ).answer(ask())

        assertEquals(
            emptyList(),
            reported.attempts,
            "Resolution settles before any stage runs, so there is no attempt to score. What " +
                "happened instead is on the answer, which the caller already has.",
        )
    }

    // --- doubles and fixtures -------------------------------------------------------------

    @Suppress("LongParameterList")
    private fun runner(
        shape: Shape,
        search: FakeSearch = FakeSearch(listOf(contact("a lead"))),
        freshness: Freshness? = Freshness(COMPLETE_THROUGH),
        ceiling: Int = Budget.DEFAULT_CEILING,
        classifier: Classifier? = null,
        faults: Faults = Faults.SILENT,
        trace: AnswerTrace = AnswerTrace.UNTRACED,
        retrieval: Retrieval = FakeRetrieval(),
    ) = Runner(
        shapes = ShapeRegistry(shapes = emptyList(), fallback = shape, classifier = classifier),
        scope = ScopeResolver(search),
        citations = CitationCheck(),
        retrieval = retrieval,
        ingestState = FakeIngestState(freshness),
        ceiling = ceiling,
        faults = faults,
        trace = trace,
    )

    /** Every attempt the runner reported, in the order it reported them. */
    private class RecordingTrace : AnswerTrace {

        val attempts = mutableListOf<Attempted>()

        override fun attempted(attempted: Attempted) {
            attempts += attempted
        }
    }

    /** Everything the runner found wrong, in the order it did. */
    private class Noticed : Faults {
        val reasons = mutableListOf<Pair<Int, Fault>>()

        override fun noticed(shape: ShapeId, attempt: Int, reason: Fault) {
            reasons += attempt to reason
        }
    }

    /** A classifier that places nothing and bills for having tried. */
    private class SpendingClassifier(private val usage: TokenUsage) : Classifier {

        override suspend fun classify(text: String, among: List<Classifier.Candidate>): Classifier.Verdict =
            Classifier.Verdict(shape = null, usage = usage)
    }

    /** A classifier that reads a subject out of the text and places no shape. */
    private class NamingClassifier(private val subjectName: SubjectName) : Classifier {

        /** Named rather than unestablished, which is the ordinary case these tests are about. */
        constructor(name: String) : this(SubjectName.Given(name))

        override suspend fun classify(text: String, among: List<Classifier.Candidate>): Classifier.Verdict =
            Classifier.Verdict(shape = null, subjectName = subjectName)
    }

    /** A stage that counts its runs and does whatever the test told it to. */
    private class CountingStage(
        override val spendsModel: Boolean,
        private val step: (Exchange) -> Progress,
    ) : Stage {

        var runs = 0

        override suspend fun apply(exchange: Exchange): Progress {
            runs++
            return step(exchange)
        }
    }

    private class FakeSearch(
        private val matches: List<Candidate> = emptyList(),
        private val near: List<Candidate> = emptyList(),
    ) : SubjectSearch {

        // What the store's query unions. The inbox is not among them: it is reached by having
        // named nobody, so no name is matched against it.
        override val covers = setOf(SubjectKind.CONTACT, SubjectKind.CLIENT, SubjectKind.CONVERSATION)

        val namesAsked = mutableListOf<String>()

        override suspend fun matching(name: String): List<Candidate> {
            namesAsked += name
            return matches
        }

        override suspend fun nearest(name: String) = near

        // Nothing here drives the traverse rule, which is asserted against the resolver itself.
        override suspend fun within(referent: Referent): List<Candidate> = emptyList()
    }

    /**
     * Ingestion state, as the runner reads it.
     *
     * Only freshness is answered. The other three reads are what an operator looks at and
     * nothing on the answer path touches them — a fake returning something would suggest
     * otherwise.
     */
    private class FakeIngestState(private val freshness: Freshness?) : IngestStateRepository {

        override suspend fun freshnessOf(source: Source): Freshness? = freshness

        override suspend fun reportFreshness(
            source: Source,
            completeThrough: Instant?,
            lagging: List<WorkUnitId>,
        ) = Unit

        override suspend fun reportBackfill(source: Source, complete: Boolean, reached: Instant?) = Unit

        override suspend fun recordPass(source: Source, pass: Pass) = Unit

        override suspend fun healthOf(source: Source): Health? = null

        override suspend fun backfillOf(source: Source): BackfillProgress? = null
    }

    private companion object {

        val COMPLETE_THROUGH: Instant = Instant.parse("2026-10-01T09:00:00Z")

        fun stage(spendsModel: Boolean = false, step: (Exchange) -> Progress) = CountingStage(spendsModel, step)

        // Defaulted to the composing case, which is what most of this file asserts against: a
        // shape whose answer is its assertions refuses a reply that makes none, so a fixture
        // answering with prose alone would be refused everywhere rather than where that is the
        // subject.
        @Suppress("LongParameterList")
        fun shape(
            accepts: Set<SubjectKind> = setOf(SubjectKind.CONTACT),
            variants: IntRange = 1..1,
            readsCorpus: Boolean = true,
            assertsFindings: Boolean = false,
            stages: List<Stage> = emptyList(),
        ): Shape {
            val declared = stages
            val accepted = accepts
            val range = variants
            val corpus = readsCorpus
            val asserting = assertsFindings
            return object : Shape {
                override val id = ShapeId("under test")
                override val description = "what this shape is for, which binding never reads"
                override val accepts = accepted
                override val variants = range
                override val readsCorpus = corpus
                override val assertsFindings = asserting
                override val stages = declared
            }
        }

        fun ask() = Request(text = "a question", subjectName = "a lead")

        fun composed(prose: String) = Answer.Composed(listOf(Variant(prose)))

        /**
         * A reply the provider reported complete and the reader rejected — the one unreadable
         * outcome that is re-rolled.
         *
         * Markerless rather than merely malformed: [Fault.Unreadable] refuses to be built on a reply
         * that parses, so a fixture carrying a marker would fail at construction instead of
         * exercising this.
         */
        fun unparsed() = ModelOutcome.Answered(text = "no markers anywhere", usage = TokenUsage.NONE)

        fun span(nativeId: String) = SpanId(RecordKind.MESSAGE, ExternalId(Source.TELEGRAM, nativeId))

        fun span(id: SpanId) = Span(id = id, text = "some text", sourceTime = COMPLETE_THROUGH)

        fun contact(name: String) = Candidate(Referent.OfContact(EntityId.mint()), name, score = 1.0)

        fun client(name: String) = Candidate(Referent.OfClient(EntityId.mint()), name, score = 1.0)
    }
}
