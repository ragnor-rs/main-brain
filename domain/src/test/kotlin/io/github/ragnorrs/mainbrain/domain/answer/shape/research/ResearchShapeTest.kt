package io.github.ragnorrs.mainbrain.domain.answer.shape.research

import io.github.ragnorrs.mainbrain.domain.Effort
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.answer.Assemble
import io.github.ragnorrs.mainbrain.domain.answer.Assembled
import io.github.ragnorrs.mainbrain.domain.answer.Budget
import io.github.ragnorrs.mainbrain.domain.answer.Exchange
import io.github.ragnorrs.mainbrain.domain.answer.FakeEmbeddings
import io.github.ragnorrs.mainbrain.domain.answer.FakeGrouping
import io.github.ragnorrs.mainbrain.domain.answer.FakeIndex
import io.github.ragnorrs.mainbrain.domain.answer.FakeRetrieval
import io.github.ragnorrs.mainbrain.domain.answer.Lookup
import io.github.ragnorrs.mainbrain.domain.answer.Offer
import io.github.ragnorrs.mainbrain.domain.answer.Progress
import io.github.ragnorrs.mainbrain.domain.answer.Reply
import io.github.ragnorrs.mainbrain.domain.answer.Request
import io.github.ragnorrs.mainbrain.domain.answer.Sizing
import io.github.ragnorrs.mainbrain.domain.index.Span
import kotlinx.coroutines.runBlocking
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Research against the open web, with a source against every claim.
 *
 * **The one shape that reads the world rather than the corpus**, and the only one whose retrieval is
 * genuinely a loop — the provider runs the search on its own side, so the model decides what to look
 * for next inside the one call. What is worth asserting is that the web tool is actually declared,
 * that the corpus read supplements rather than scopes, and that an empty corpus does not refuse the
 * request.
 */
class ResearchShapeTest {

    @Test
    fun `it is scoped by its own question, not by a name`() {
        // Scoping it by a subject would be the wrong operation: the question is about something the
        // corpus may never have heard of. An empty accepted set is what makes the runner skip
        // resolution entirely.
        assertTrue(shape().accepts.isEmpty())
    }

    @Test
    fun `an empty corpus does not refuse it`() {
        // The short-circuit exists to stop a model call with nothing to answer from, and this
        // shape's material comes from the web. A corpus that knows nothing about the subject is the
        // ordinary case.
        assertEquals(false, shape().readsCorpus)
    }

    @Test
    fun `the web search tool is declared, and the effort is the highest this path uses`(): Unit = runBlocking {
        val model = FakeModelProvider()

        generate(model)

        val request = model.requests.single()
        assertTrue(request.openWeb)
        assertEquals(Effort.XHIGH, request.effort)
    }

    @Test
    fun `it asks for more room than a fixed-retrieval shape, because it reads inside the call`(): Unit =
        runBlocking {
            val model = FakeModelProvider()

            generate(model, budget = Budget(ceiling = Int.MAX_VALUE))

            assertTrue(model.requests.single().maxTokens > Sizing.ANSWERING.ceiling)
        }

    @Test
    fun `the per-request budget still bounds it`(): Unit = runBlocking {
        // A turn that pauses and resumes is charged for each resumption, so the shape's own ceiling
        // cannot be the last word.
        val model = FakeModelProvider()

        generate(model, budget = Budget(ceiling = 10_000))

        assertEquals(10_000, model.requests.single().maxTokens)
    }

    @Test
    fun `held material is retrieved as a supplement, with the question as written`(): Unit = runBlocking {
        val embeddings = FakeEmbeddings()
        val known = Span(FakeRetrieval.evidence("5").span, "we spoke to them in June", FakeRetrieval.WHEN)

        val retrieved = retrieve(FakeIndex(hits = listOf(known)), embeddings)

        assertTrue(retrieved.any { it.text.contains("we spoke to them in June") })
        // As written, unlike the draft shape: there is no thread whose words could stand in for the
        // question, and the question is what the corpus is being asked about.
        assertEquals("what do we know about this bank", embeddings.embedded.single().second)
    }

    @Test
    fun `held material names whoever wrote it`(): Unit = runBlocking {
        // **The point of this material is who is already known and what was said before**, so a turn
        // with nobody attached answers half of that — and the half it answers is the one a web
        // answer is most likely to put in the wrong mouth, since the names it has read are the only
        // ones in front of it. The index stores no author, so this is a second read or it is
        // nothing.
        val retrieval = FakeRetrieval(turns = mapOf(FakeRetrieval.record("5") to FakeRetrieval.turn("A Known Contact")))
        val known = Span(FakeRetrieval.evidence("5").span, "we spoke to them in June", FakeRetrieval.WHEN)

        val retrieved = retrieve(FakeIndex(hits = listOf(known)), retrieval = retrieval).single()

        assertEquals("Sent by A Known Contact: we spoke to them in June", retrieved.text)
    }

    @Test
    fun `held material whose author the corpus never named carries no name`(): Unit = runBlocking {
        val known = Span(FakeRetrieval.evidence("5").span, "we spoke to them in June", FakeRetrieval.WHEN)

        assertEquals("we spoke to them in June", retrieve(FakeIndex(hits = listOf(known))).single().text)
    }

    @Test
    fun `the author read asks about the records it held, once`(): Unit = runBlocking {
        val retrieval = FakeRetrieval()
        val known = (5..6).map { Span(FakeRetrieval.evidence("$it").span, "span $it", FakeRetrieval.WHEN) }

        retrieve(FakeIndex(hits = known), retrieval = retrieval)

        val asked = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Turns>().single()
        assertEquals(listOf("5", "6").map(FakeRetrieval::record), asked.messages)
    }

    @Test
    fun `the instruction says to attribute a message to whoever is marked on it`() {
        // A prose pin, which is the weak kind of assertion, knowingly: the label on a turn is only
        // worth rendering while the model is told what it is for. Pinned per shape rather than
        // once, because each states the rule in its own words — this one's names the specific
        // failure of attributing held material to somebody a web source named — so no other
        // shape's pin can stand in for it.
        val system = system()

        assertTrue(
            system.contains("attribute it to the person marked on it and to nobody else"),
            "nothing tells the model whose words a held turn carries: $system",
        )
    }

    @Test
    fun `the held material is offered as a ranked sample rather than as what the organisation holds`() {
        // **Measured through the interface, which is why this is here and not in the fallback's test
        // alone.** Asked about a person, this shape reported that they appeared in the corpus
        // exactly once and were named in no other message — off the ranked supplement alone, about
        // somebody the store holds far more of than it carried. Every citation resolved, so the
        // grounding gate passed it: what the gate asks is whether a cited span was retrieved, never
        // whether the retrieved set was the corpus. The read is in the pull request.
        //
        // Its own words rather than the fallback's, because what it answers from is the web and the
        // corpus is the supplement. The licence was the sentence calling that supplement what the
        // organisation holds, which is what this pins.
        val system = system()

        assertTrue(
            system.contains("a sample found by ranking the corpus against the question"),
            "the held material is still offered as the whole of what is held: $system",
        )
        assertTrue(
            system.contains("and not the whole of what is held"),
            "nothing denies that the supplement is everything the organisation has: $system",
        )
    }

    @Test
    fun `a count, a uniqueness claim and an absence over what is held are all refused by name`() {
        // A case of its own, because the first failing assertion in a case masks the rest: the
        // wordings the measured reply used are the operative half of this rule, and a pin on the
        // framing sentence above would survive their deletion.
        val system = system()

        assertTrue(
            system.contains("Do not use it to say how much is held"),
            "nothing forbids reading a quantity off the sample: $system",
        )
        assertTrue(
            system.contains("appears once, or in one place, or nowhere else"),
            "the rule names no wording, and these are the ones the measured reply used: $system",
        )
    }

    @Test
    fun `an absence is reported of the sample, while a web absence stays this shape's to report`() {
        // The split, and both halves, because the rule is wrong in either direction: an absence in
        // the corpus is not this shape's to report, having searched nothing, and an absence on the
        // web is, having run that search — and a prohibition that swept up the second would cost
        // the useful half of what this shape answers.
        val system = system()

        assertTrue(
            system.contains("rather than that the organisation does not hold it"),
            "an absence in the sample may still be reported of the organisation: $system",
        )
        assertTrue(
            system.contains("An absence there is yours to report, because you ran that search"),
            "the web half of the split is gone, so a found-nothing search has nothing to say: " +
                system,
        )
    }

    @Test
    fun `reporting somebody as new is given a place rather than forbidden`() {
        // The boundary, and the reason it is drawn: avoiding a known contact being presented as a
        // new discovery is what the supplement is offered for, so a rule refusing "not known here"
        // without naming where that goes would leave the model nothing to write on the commonest
        // question this shape is asked.
        val system = system()

        assertTrue(
            system.contains("this ranked sample of what is held carries nothing about them"),
            "nothing says what to write where the sample is silent about the subject: $system",
        )
    }

    @Test
    fun `a web source goes in the prose, and is told twice over not to carry a number`() {
        // **The one rule of this shape with a consequence nothing downstream can see.** A citation
        // is a small number and this shape offers held material under exactly the numbers a model
        // habitually footnotes web sources with, so a web finding written as a claim line
        // *resolves* — to whichever held span took that number — and passes the grounding gate as
        // an assertion about the corpus that nothing in the corpus supports. It used to fail
        // loudly, when a citation was a long span identifier a URL could not resemble; the
        // interface renders no list of sources for a figure to point into either, so a reader gets
        // a digit leading nowhere. `Reply.FORBIDDEN` says in its own words that this clause is the
        // one a shape owns and nothing catches.
        //
        // So it is pinned where it is stated. A prose pin is the weak kind of assertion and this is
        // the weak half of a pair: it keeps the sentences from being deleted silently, and says
        // nothing about the model having obeyed them. The rendered end-to-end tier reads the other
        // half — that a delivered answer carries its sources and no bracketed figure — and names
        // this pin as what covers the part it cannot read.
        val system = system()

        assertTrue(
            system.contains("Every finding carries its source inline"),
            "nothing tells the model to put a source beside the finding it supports: $system",
        )
        assertTrue(
            system.contains("Do not number your sources"),
            "the habit of footnoting a web source is not refused, and a figure resolves to held " +
                "material: $system",
        )
        assertTrue(
            system.contains("never a bracketed figure standing for one"),
            "the form the prohibition is about is unnamed, so only the general rule stands: " +
                system,
        )
        assertTrue(
            system.contains("Web findings never go in a claim line, and never carry a number"),
            "nothing keeps a web finding out of the one line that is checked against the corpus: " +
                system,
        )
    }

    @Test
    fun `the sample rule is this shape's own, and reddens if it is lifted into the shared format`() {
        // The negative half, which every pin above is blind to: they read the instruction
        // concatenated with the shared format, so a later card moving these sentences into
        // `Reply.format` — the seat the fallback's companion rejects, because a shape that asserts
        // its findings is told to write such a sentence as a cited claim line instead — would leave
        // all of them green.
        //
        // Calibrated by pointing the same assertion at a phrase the shared format really carries,
        // which fails as it should.
        val shared = Reply.format(variants = 1..1, sections = false, asserts = false)
            .replace(WHITESPACE, " ")

        listOf(
            "a sample found by ranking the corpus against the question",
            "Do not use it to say how much is held",
            "rather than that the organisation does not hold it",
            "this ranked sample of what is held carries nothing about them",
            // The source-placement rules are this shape's own for the same reason: the shared
            // format forbids a bracketed reference to every shape, but where a web source goes
            // instead is a sentence only a shape that reads the web has any use for.
            "Every finding carries its source inline",
            "Do not number your sources",
            "never a bracketed figure standing for one",
            "Web findings never go in a claim line, and never carry a number",
        ).forEach { pinned ->
            assertFalse(
                shared.contains(pinned),
                "the shared format carries this shape's rule, so every pin above would survive " +
                    "moving it there: $pinned",
            )
        }
    }

    @Test
    fun `a corpus with nothing to add still reaches the model`(): Unit = runBlocking {
        val retrieved = retrieve(FakeIndex(hits = emptyList()))

        assertTrue(retrieved.isEmpty())
    }

    @Test
    fun `it carries less held material than a corpus-reading shape`(): Unit = runBlocking {
        // It is context for a web answer rather than the material the answer is made of, and a long
        // tail of weak matches would crowd out the part of the prompt that matters.
        val many = (1..50).map { Span(FakeRetrieval.evidence("$it").span, "span $it", FakeRetrieval.WHEN) }

        assertTrue(retrieve(FakeIndex(hits = many)).size < many.size)
    }

    // --- doubles and fixtures -------------------------------------------------------------

    private companion object {

        /**
         * Runs of whitespace as one space, so a re-wrapped paragraph still reads as its sentence.
         *
         * An assertion matching the raw prompt would really be asserting where the paragraph was
         * wrapped, which turns a rewrap into a red and invites reflowing the prompt to suit a test.
         */
        val WHITESPACE = Regex("\\s+")

        fun shape(
            index: FakeIndex = FakeIndex(),
            embeddings: FakeEmbeddings = FakeEmbeddings(),
            model: FakeModelProvider = FakeModelProvider(),
            retrieval: FakeRetrieval = FakeRetrieval(),
        ) = ResearchShape(model, Lookup(embeddings, index), retrieval, FakeGrouping(), ZoneOffset.UTC)

        suspend fun retrieve(
            index: FakeIndex,
            embeddings: FakeEmbeddings = FakeEmbeddings(),
            retrieval: FakeRetrieval = FakeRetrieval(),
        ) = assertIs<Progress.Carried>(
            shape(index, embeddings, retrieval = retrieval).stages.first().apply(
                Exchange(Request(text = "what do we know about this bank")),
            ),
        ).exchange.retrieved

        /**
         * The standing half of the prompt as a request would carry it, runs of whitespace folded.
         *
         * Read through the assembly stage rather than off the constant, so that what is pinned is
         * the instruction as concatenated with the shared reply format — a rule stated there would
         * make one stated here a duplicate rather than an addition, which is what the seating case
         * reads this for. The stage is found by type rather than by position: an index reads
         * whatever the list happens to hold at that slot, and nothing stops a shape reordering its
         * stages.
         */
        fun system(): String = runBlocking {
            val shape = shape()
            val retrieved = assertIs<Progress.Carried>(
                shape.stages.first().apply(Exchange(Request(text = "what do we know about this bank"))),
            ).exchange
            requireNotNull(
                assertIs<Progress.Carried>(
                    shape.stages.filterIsInstance<Assemble>().single().apply(retrieved),
                ).exchange.assembled,
            ).system.replace(WHITESPACE, " ")
        }

        /** Drive the generation stage alone, which is where the shape's model choices show. */
        suspend fun generate(model: FakeModelProvider, budget: Budget = Budget(Budget.DEFAULT_CEILING)) {
            shape(model = model).stages.last().apply(
                Exchange(
                    request = Request(text = "research this"),
                    assembled = Assembled(system = "s", prompt = "p", offer = Offer.NONE),
                    budget = budget,
                ),
            )
        }
    }
}
