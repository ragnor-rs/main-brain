package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.Effort
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading free text and deciding which shape should answer it.
 *
 * **Routing is the one model call every free-text request pays for**, so the cases below are as much
 * about cost as about correctness: the lowest effort, a small ceiling, and nothing spent at all when
 * there is nothing to choose between.
 *
 * The other half is refusing to improvise. A model naming a shape that was not offered has not
 * classified anything, and the nearest registered identifier is not what it meant.
 */
class ClassifierTest {

    @Test
    fun `a reply naming a candidate selects it`(): Unit = runBlocking {
        val verdict = classify(reply = "status", among = candidates("status", "draft"))

        assertEquals(ShapeId("status"), verdict.shape)
    }

    @Test
    fun `surrounding whitespace in the reply is tolerated`(): Unit = runBlocking {
        val verdict = classify(reply = "  status\n", among = candidates("status"))

        assertEquals(ShapeId("status"), verdict.shape)
    }

    @Test
    fun `the agreed word for none of them classifies nothing`(): Unit = runBlocking {
        assertNull(classify(reply = "none", among = candidates("status")).shape)
    }

    @Test
    fun `a name that was not offered classifies nothing`(): Unit = runBlocking {
        // Accepting the nearest match would make routing depend on string similarity between a
        // hallucination and a real identifier.
        assertNull(classify(reply = "offer", among = candidates("status", "draft")).shape)
    }

    @Test
    fun `a sentence instead of an identifier classifies nothing`(): Unit = runBlocking {
        assertNull(
            classify(
                reply = "I think this is probably the status shape.",
                among = candidates("status"),
            ).shape,
        )
    }

    @Test
    fun `a truncated reply classifies nothing`(): Unit = runBlocking {
        // Three of the four outcomes decided nothing, and the fallback is what answers an undecided
        // request — which is also what it answers an unclassifiable one with.
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.truncated("sta")))

        assertNull(ModelClassifier(model).classify("anything", candidates("status")).shape)
    }

    @Test
    fun `a refusal classifies nothing`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.refused()))

        assertNull(ModelClassifier(model).classify("anything", candidates("status")).shape)
    }

    @Test
    fun `a paused turn classifies nothing`(): Unit = runBlocking {
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.paused()))

        assertNull(ModelClassifier(model).classify("anything", candidates("status")).shape)
    }

    @Test
    fun `what the call cost is reported, whatever it decided`(): Unit = runBlocking {
        // A bound that ignored routing would not be a bound, and a refusal is billed for what it
        // read.
        val spent = TokenUsage(inputTokens = 200, outputTokens = 9)
        val model = FakeModelProvider(listOf(FakeModelProvider.Reply.refused(usage = spent)))

        assertEquals(spent, ModelClassifier(model).classify("anything", candidates("status")).usage)
    }

    @Test
    fun `no candidates means no call at all`(): Unit = runBlocking {
        // A registry holding only a fallback has nothing to classify into, and a call that could
        // only answer *none* is spend with no decision in it.
        val model = FakeModelProvider()

        val verdict = ModelClassifier(model).classify("anything", emptyList())

        assertEquals(0, model.callCount)
        assertNull(verdict.shape)
        assertEquals(TokenUsage.NONE, verdict.usage)
    }

    @Test
    fun `routing is the cheapest call on the path`(): Unit = runBlocking {
        val model = FakeModelProvider()

        ModelClassifier(model).classify("anything", candidates("status"))

        val request = model.requests.single()
        assertEquals(Effort.LOW, request.effort)
        assertTrue(request.maxTokens < Sizing.ANSWERING.ceiling)
        // Never the web. Routing reads the request and nothing else.
        assertEquals(false, request.openWeb)
    }

    @Test
    fun `the candidates are named in the prompt rather than in the standing instruction`(): Unit = runBlocking {
        // The set is the registry's and changes with it, so a standing instruction naming shapes
        // would be a second registry able to disagree with the first.
        val model = FakeModelProvider()

        ModelClassifier(model).classify("where are we", candidates("status", "draft"))

        val request = model.requests.single()
        assertTrue(request.prompt.contains("status"))
        assertTrue(request.prompt.contains("draft"))
        assertTrue(request.prompt.contains("where are we"))
        assertTrue(!request.system.contains("status"))
    }

    @Test
    fun `each candidate reaches the prompt with what it answers beside it`(): Unit = runBlocking {
        // The defect this closes: a model given bare identifiers has to infer what each covers
        // from how its name reads, and asked to prefer no choice over a poor fit it declines —
        // sending a question the derived layer exists to answer to the fallback.
        val model = FakeModelProvider()

        ModelClassifier(model).classify(
            "what did we agree",
            listOf(
                Classifier.Candidate(ShapeId("status"), "what was agreed, and whether it was carried out"),
                Classifier.Candidate(ShapeId("draft"), "a message to send somebody"),
            ),
        )

        val prompt = model.requests.single().prompt
        assertTrue(prompt.contains("status: what was agreed, and whether it was carried out"), prompt)
        assertTrue(prompt.contains("draft: a message to send somebody"), prompt)
    }

    @Test
    fun `the standing instruction sends the model to the descriptions and not to the names`(): Unit = runBlocking {
        // Without this the identifier is still what carries the meaning, and a description in the
        // prompt is material the model was never told to weigh.
        val model = FakeModelProvider()

        ModelClassifier(model).classify("what did we agree", candidates("status"))

        // Flattened before matching, because the sentence wraps mid-phrase in the literal and a
        // quoted wording broken over a line is one nothing matches — so a re-wrap that changed no
        // meaning would redden this, and a fragment cut at the wrap would assert almost nothing.
        val system = flattened(model.requests.single().system)
        assertTrue(system.contains("listed with what it answers"), system)
        assertTrue(system.contains("choose on that description and not on how its identifier reads"), system)
    }

    @Test
    fun `a description mentioning another candidate's name does not select that one`(): Unit = runBlocking {
        // Line 1 is matched against the identifiers and nothing else. A description is prose that
        // may legitimately name a sibling, and matching the whole candidate line would let that
        // prose decide the route.
        //
        // **The ask is reversed so that the two implementations disagree.** Replying with the
        // first candidate's own identifier proves nothing: matching the line and matching the id
        // both return it. So the reply names `draft` while `status` is listed first and mentions
        // draft in its prose — id-matching answers `draft`, line-matching answers `status`.
        val verdict = classify(
            reply = "draft",
            among = listOf(
                Classifier.Candidate(ShapeId("status"), "where things stand, and not a draft of anything"),
                Classifier.Candidate(ShapeId("draft"), "a message to send"),
            ),
        )

        assertEquals(ShapeId("draft"), verdict.shape)
    }

    @Test
    fun `a reply echoing the line it was shown still names that shape`(): Unit = runBlocking {
        // The prompt shows `identifier: what it answers`, so a model mirroring what it was shown
        // writes the colon too. Read byte-exact that names no candidate, and the request falls to
        // the fallback — this card's defect reached through the parser, and silent.
        val verdict = classify(
            reply = "status: Where things stand with somebody, or across everything",
            among = candidates("status", "draft"),
        )

        assertEquals(ShapeId("status"), verdict.shape)
    }

    @Test
    fun `reading up to a colon is not a nearest match`(): Unit = runBlocking {
        // The tolerance drops a label the prompt displayed; it does not guess. A shape that was
        // never offered still names nothing, however much of it precedes the colon.
        assertNull(classify(reply = "offer: price this up", among = candidates("status")).shape)
    }

    @Test
    fun `a second line naming a subject the request contains carries it`(): Unit = runBlocking {
        // Free text arrives with no name separated out, because an interface cannot carve one out
        // of a sentence. Without this, a request about a named client routed to the shape that
        // accepts the inbox resolves to the inbox and answers about the wrong subject.
        val verdict = classify(
            reply = "status\nAcme Holdings",
            text = "where did we leave off with Acme Holdings",
            among = candidates("status"),
        )

        assertEquals(ShapeId("status"), verdict.shape)
        assertEquals(SubjectName.Given("Acme Holdings"), verdict.subjectName)
    }

    @Test
    fun `the agreed word for none on the second line names no subject`(): Unit = runBlocking {
        // Which is the daily question the inbox exists for, not a gap.
        val verdict = classify(
            reply = "status\nnone",
            text = "what is on today",
            among = candidates("status"),
        )

        assertEquals(ShapeId("status"), verdict.shape)
        assertEquals(SubjectName.None, verdict.subjectName)
    }

    @Test
    fun `the agreed word is read however the model rendered it`(): Unit = runBlocking {
        // Capitalised, quoted, with a full stop after it. Each is the same answer, and reading
        // only the byte-exact token sent every one of them through the copy check — which they
        // fail, because the request does not contain the word — and on to a refusal. The
        // question that refused is the daily one, so the strictness that used to end at the
        // right answer now costs it.
        for (rendering in listOf("None", "none.", "\"none\"", "- none", "NONE")) {
            val verdict = classify(
                reply = "status\n$rendering",
                text = "what is on today",
                among = candidates("status"),
            )

            assertEquals(SubjectName.None, verdict.subjectName, "rendered as $rendering")
        }
    }

    @Test
    fun `a second line with no letters in it names nothing`(): Unit = runBlocking {
        // A dash or a question mark is a line the parser cannot use, and such a line is a subject
        // not established. Allowed to fall through to the copy check it passes — the request
        // contains that punctuation — and the reader is then told nothing was found about a dash.
        // Every rendering appears in the request text, so each one passes the copy check and would
        // be read as a name without the clause under test — which is what makes the case bite. A
        // hyphen is not an em dash, so the text has to carry both for the pair to discriminate.
        for (rendering in listOf("—", "?", "...", "-")) {
            val verdict = classify(
                reply = "status\n$rendering",
                text = "что у нас — по проекту-2? ...",
                among = candidates("status"),
            )

            assertEquals(SubjectName.Unread, verdict.subjectName, "rendered as $rendering")
        }
    }

    @Test
    fun `a subject whose name merely begins with the agreed word is still a subject`(): Unit = runBlocking {
        // The reason the agreed word is matched by equality after trimming rather than by a
        // prefix: an organisation called Nonet is not nobody.
        val verdict = classify(
            reply = "status\nNonet Ltd",
            text = "where did we leave off with Nonet Ltd",
            among = candidates("status"),
        )

        assertEquals(SubjectName.Given("Nonet Ltd"), verdict.subjectName)
    }

    @Test
    fun `a reply of one line has not said what the request is about`(): Unit = runBlocking {
        // **The defect, at the place it begins.** This asserted *names no subject* — the same
        // value the line above produces — so a routing call that answered which pipeline and
        // never answered who was indistinguishable from one that said *nobody*. Downstream, the
        // shape accepting the inbox answered corpus-wide about a named person, and nothing but
        // the call's output token count could tell afterwards.
        //
        // Measured on the live stack: five output tokens is one line, twelve is two.
        val verdict = classify(reply = "status", text = "who is Acme", among = candidates("status"))

        assertEquals(ShapeId("status"), verdict.shape)
        assertEquals(SubjectName.Unread, verdict.subjectName)
    }

    @Test
    fun `a subject that is not in the request leaves it unestablished`(): Unit = runBlocking {
        // A name composed rather than copied would be searched for, match nothing, and report
        // that nothing is known about a subject nobody asked about.
        //
        // **Unestablished rather than nobody**, which is the same argument as the case above: the
        // model read a subject out of the request and wrote something unusable for it. Rounding
        // that down to *nobody* is what hands a shape accepting the inbox a question about one
        // person to answer across everything.
        val verdict = classify(
            reply = "status\nSomebody Else Entirely",
            text = "where did we leave off with Acme",
            among = candidates("status"),
        )

        assertEquals(SubjectName.Unread, verdict.subjectName)
        // The routing still stands; only the name it invented is dropped.
        assertEquals(ShapeId("status"), verdict.shape)
    }

    @Test
    fun `a subject recapitalised from the request is kept`(): Unit = runBlocking {
        // Reformatting is not invention: the request wrote it in lower case and the model wrote it
        // as a name.
        val verdict = classify(
            reply = "status\nAcme",
            text = "where did we leave off with acme",
            among = candidates("status"),
        )

        assertEquals(SubjectName.Given("Acme"), verdict.subjectName)
    }

    @Test
    fun `a second line that is a sentence rather than a name is discarded`(): Unit = runBlocking {
        val explaining = "The request appears to concern the ongoing discussion with the client " +
            "about the timetable, so the subject is most likely that client"
        val verdict = classify(
            reply = "status\n$explaining",
            text = "$explaining and nothing else",
            among = candidates("status"),
        )

        // Contained in the request, so the copy check passes; refused on length, because a whole
        // sentence handed to the resolver is a search that matches nothing.
        assertEquals(SubjectName.Unread, verdict.subjectName)
    }

    @Test
    fun `a subject named for a request the classifier could not route is still carried`(): Unit = runBlocking {
        // The fallback accepts no subject and will not be resolved for, so this is unused — but
        // discarding it here would make the field depend on which shape won.
        val verdict = classify(
            reply = "none\nAcme",
            text = "tell me something about Acme",
            among = candidates("status"),
        )

        assertNull(verdict.shape)
        assertEquals(SubjectName.Given("Acme"), verdict.subjectName)
    }

    @Test
    fun `a call that was never made establishes nothing rather than naming nobody`(): Unit = runBlocking {
        // No candidates means no call, so nothing read the text. Reporting *nobody named* would
        // have the one construction that reads nothing assert something about the request.
        val verdict = ModelClassifier(FakeModelProvider()).classify("who is Acme", emptyList())

        assertEquals(SubjectName.Unread, verdict.subjectName)
    }

    @Test
    fun `the standing instruction asks for the subject as well as the route`(): Unit = runBlocking {
        val model = FakeModelProvider()

        ModelClassifier(model).classify("where are we", candidates("status"))

        val system = model.requests.single().system
        assertTrue(system.contains("two lines"), system)
        // The instruction has to say *copied*, because that is the property the parser enforces
        // and a model asked merely to name one would compose it.
        assertTrue(system.contains("copied from the request"), system)
    }

    @Test
    fun `the standing instruction requires the second line even when it says none`(): Unit = runBlocking {
        // What the live failure was: the routing call runs at the lowest effort there is, which
        // is where a model does the least that satisfies the instruction — and *reply with
        // exactly two lines* left writing one looking like compliance. The parser now refuses to
        // read a missing line as *nobody*, and this is the half that stops it being missing.
        val model = FakeModelProvider()

        ModelClassifier(model).classify("where are we", candidates("status"))

        val system = model.requests.single().system
        assertTrue(system.contains("Write both lines every time"), system)
    }

    private companion object {

        /**
         * One scripted reply, classified.
         *
         * [text] defaults because most cases are about reading the reply rather than about what
         * was asked; the subject cases set it, since a name is only kept when the request
         * contains it.
         */
        suspend fun classify(
            reply: String,
            among: List<Classifier.Candidate>,
            text: String = "a question",
        ): Classifier.Verdict =
            ModelClassifier(FakeModelProvider(listOf(FakeModelProvider.Reply.of(reply))))
                .classify(text, among)

        /**
         * Candidates named by their identifiers, each with a description that does not contain one.
         *
         * Most cases here are about reading the reply, where what a candidate answers is beside the
         * point; the cases that are about the description write their own.
         *
         * **The description deliberately omits the identifier**, because a generated `"what $id
         * answers"` would satisfy the case asserting the identifier reaches the prompt — and that
         * case would then pass with the identifier dropped from the line altogether, which is the
         * one thing it exists to catch.
         */
        fun candidates(vararg ids: String): List<Classifier.Candidate> =
            ids.map { Classifier.Candidate(ShapeId(it), "a description with no identifier in it") }

        /** Runs of whitespace as one space, so a quoted sentence survives the literal's wrapping. */
        val WHITESPACE = Regex("\\s+")

        fun flattened(text: String): String = text.replace(WHITESPACE, " ")
    }
}
