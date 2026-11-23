package io.github.ragnorrs.mainbrain.domain.answer.shape.freeform

import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.answer.Assemble
import io.github.ragnorrs.mainbrain.domain.answer.Exchange
import io.github.ragnorrs.mainbrain.domain.answer.FakeEmbeddings
import io.github.ragnorrs.mainbrain.domain.answer.FakeGrouping
import io.github.ragnorrs.mainbrain.domain.answer.FakeIndex
import io.github.ragnorrs.mainbrain.domain.answer.FakeRetrieval
import io.github.ragnorrs.mainbrain.domain.answer.Lookup
import io.github.ragnorrs.mainbrain.domain.answer.Progress
import io.github.ragnorrs.mainbrain.domain.answer.Reply
import io.github.ragnorrs.mainbrain.domain.answer.Request
import io.github.ragnorrs.mainbrain.domain.index.Span
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The fallback: whatever did not match a shape.
 *
 * **A pipeline like the others, which is the property worth asserting.** What makes it the fallback
 * is that the registry names it separately, not that it is built differently — so it has stages, it
 * registers the same way, and the machinery has no branch for it.
 *
 * It must answer rather than refuse. A fallback that short-circuited on an empty corpus would decline
 * exactly the general questions it exists to catch.
 */
class FreeformShapeTest {

    @Test
    fun `free text may name nothing, so no subject is required`() {
        assertTrue(shape().accepts.isEmpty())
    }

    @Test
    fun `an empty corpus does not refuse it`() {
        // Refusing because no span ranked highly would make the fallback useless at the questions it
        // catches. The grounding check still holds: prose is permitted, an uncited claim is not.
        assertEquals(false, shape().readsCorpus)
    }

    @Test
    fun `it is a pipeline like any other, with the same three stages`() {
        val stages = shape().stages

        assertEquals(3, stages.size)
        assertTrue(stages.take(2).none { it.spendsModel })
        assertTrue(stages.last().spendsModel)
    }

    @Test
    fun `the corpus is ranked against the question as written`(): Unit = runBlocking {
        val embeddings = FakeEmbeddings()
        val hit = Span(FakeRetrieval.evidence("5").span, "the rate was agreed at the June meeting", FakeRetrieval.WHEN)

        val retrieved = retrieve(FakeIndex(hits = listOf(hit)), embeddings)

        assertEquals("what rate did we agree", embeddings.embedded.single().second)
        assertTrue(retrieved.any { it.text.contains("the rate was agreed") })
    }

    @Test
    fun `each hit is widened, since a message indexed alone can be a bare reply`(): Unit = runBlocking {
        val hit = Span(FakeRetrieval.evidence("5").span, "yes, agreed", FakeRetrieval.WHEN)
        val question = Span(FakeRetrieval.evidence("4").span, "shall we say 12%", FakeRetrieval.WHEN)
        val index = FakeIndex(hits = listOf(hit), around = mapOf(hit.id to listOf(question, hit)))

        val retrieved = retrieve(index)

        assertEquals(listOf(hit.id), index.widened)
        assertTrue(retrieved.any { it.text.contains("shall we say 12%") })
    }

    @Test
    fun `it searches more broadly than a scoped shape, because nothing narrowed it`(): Unit = runBlocking {
        // The ranking is the only thing between the question and the whole corpus, and a short list
        // from a broad search misses more than a long list from a narrow one.
        val many = (1..40).map { Span(FakeRetrieval.evidence("$it").span, "span $it", FakeRetrieval.WHEN) }

        assertTrue(retrieve(FakeIndex(hits = many)).size > SCOPED_SHAPE_HITS)
    }

    @Test
    fun `a question the corpus cannot rank still reaches the model`(): Unit = runBlocking {
        assertTrue(retrieve(FakeIndex(hits = emptyList())).isEmpty())
    }

    @Test
    fun `a ranked turn names whoever wrote it`(): Unit = runBlocking {
        // **Nothing narrowed this search, which is why it matters most here.** The material spans
        // conversations and people with nothing to do with each other, so a turn offered with nobody
        // attached lets a question about what somebody said be answered from whatever ranked
        // highest, whoever wrote it — with every citation resolving and the grounding gate passing
        // it clean. The index stores no author, so the name is a second read or it is nothing.
        val retrieval = FakeRetrieval(
            turns = mapOf(
                FakeRetrieval.record("7") to FakeRetrieval.turn("One Person"),
                FakeRetrieval.record("8") to FakeRetrieval.turn("Another Person"),
            ),
        )
        val index = FakeIndex(
            hits = listOf(span("7", "we agreed eight percent"), span("8", "we never agreed that")),
        )

        val retrieved = retrieve(index, retrieval = retrieval).associateBy { it.id.record.nativeId }

        assertEquals("Sent by One Person: we agreed eight percent", retrieved.getValue("7").text)
        assertEquals("Sent by Another Person: we never agreed that", retrieved.getValue("8").text)
    }

    @Test
    fun `a ranked turn whose author the corpus never named carries no name`(): Unit = runBlocking {
        // Nothing rather than nobody, as everywhere else: a placeholder would be read as the
        // author's name by the one thing that cannot check it.
        assertEquals(
            "we agreed eight percent",
            retrieve(FakeIndex(hits = listOf(span("7", "we agreed eight percent")))).single().text,
        )
    }

    @Test
    fun `the author read asks about the records that ranked, once and folded`(): Unit = runBlocking {
        // Folded first, so a record two of whose spans ranked is asked about once. Asked per span it
        // would be a round trip per line of the prompt, on the shape with the widest read of all.
        val retrieval = FakeRetrieval()
        val index = FakeIndex(hits = listOf(span("7", "one"), span("7", "one"), span("8", "two")))

        retrieve(index, retrieval = retrieval)

        val asked = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Turns>().single()
        assertEquals(listOf("7", "8").map(FakeRetrieval::record), asked.messages)
    }

    @Test
    fun `the stretches are offered newest first, so a position carries recency`(): Unit = runBlocking {
        // The broadest reply came back as seven sections in no legible order. The ranking decides
        // which material is here; what a position carries is the order the answer is read in, and
        // asking for it in prose alone is what the status shape already found insufficient.
        val older = span("7", "agreed in September", at = "2026-09-25T09:00:00Z")
        val newest = span("8", "agreed last week", at = "2026-10-14T09:00:00Z")
        val middle = span("9", "agreed at the start of October", at = "2026-10-06T09:00:00Z")

        val retrieved = retrieve(FakeIndex(hits = listOf(older, newest, middle)))

        assertEquals(listOf("8", "9", "7"), retrieved.map { it.id.record.nativeId })
    }

    @Test
    fun `a hit moves with its own context rather than being sorted among other conversations`(): Unit =
        runBlocking {
            // Sorting the spans themselves would interleave several conversations into one apparent
            // thread, which is the failure `Lookup.widened` names. The stretch is the unit of order,
            // so a hit's neighbours stay beside it however they are dated.
            val hit = span("5", "yes, agreed", at = "2026-09-25T09:00:00Z")
            val before = span("4", "shall we say 12%", at = "2026-09-25T08:59:00Z")
            val newer = span("9", "unrelated, and newer", at = "2026-10-14T09:00:00Z")
            val index = FakeIndex(
                hits = listOf(hit, newer),
                around = mapOf(hit.id to listOf(before, hit), newer.id to listOf(newer)),
            )

            val retrieved = retrieve(index)

            // The newer stretch leads, and the older hit keeps the question it answers in front of
            // it — where a flat sort by time would have reversed that pair, putting the agreement
            // ahead of what it agrees to.
            assertEquals(listOf("9", "4", "5"), retrieved.map { it.id.record.nativeId })
        }

    @Test
    fun `a stretch is dated by its newest turn and not by the hit inside it`(): Unit = runBlocking {
        // The hit is wherever the ranking landed, which is routinely not the newest turn of the
        // stretch it widened to. Keyed on the hit, this stretch would sort last on a September
        // instant while carrying the most recent turn in the material.
        val hit = span("5", "what did we settle on", at = "2026-09-01T09:00:00Z")
        val after = span("6", "settled it this morning", at = "2026-10-14T09:00:00Z")
        val other = span("9", "another thread, a fortnight back", at = "2026-10-01T09:00:00Z")
        val index = FakeIndex(
            hits = listOf(hit, other),
            around = mapOf(hit.id to listOf(hit, after), other.id to listOf(other)),
        )

        val retrieved = retrieve(index)

        assertEquals(listOf("5", "6", "9"), retrieved.map { it.id.record.nativeId })
    }

    @Test
    fun `overlapping stretches fold before the sort, so each is dated by what it still holds`(): Unit =
        runBlocking {
            // Two hits a couple of turns apart in one conversation is ordinary — nine of twenty
            // stretches overlapped another on the real archive. Folded after the sort, the lower-ranked
            // stretch is dated by the October turn it shares with the other and then loses it, so the
            // September turn left in it is offered ahead of a thread a fortnight old, under a date it is
            // not from. Folded first, it is dated by what it still carries.
            val early = span("1", "where are we on this", at = "2026-09-20T09:00:00Z")
            val shared = span("2", "waiting on them", at = "2026-10-10T09:00:00Z")
            val late = span("3", "they came back today", at = "2026-10-14T09:00:00Z")
            val elsewhere = span("9", "another thread, a fortnight back", at = "2026-10-01T09:00:00Z")
            val index = FakeIndex(
                // The newer hit ranks first, so it is the one that keeps the turn they share.
                hits = listOf(late, early, elsewhere),
                around = mapOf(
                    late.id to listOf(shared, late),
                    early.id to listOf(early, shared),
                    elsewhere.id to listOf(elsewhere),
                ),
            )

            val retrieved = retrieve(index)

            // The newer stretch leads with the shared turn; the remainder of the lower-ranked one is
            // offered at its own September position, behind the unrelated October thread.
            assertEquals(listOf("2", "3", "9", "1"), retrieved.map { it.id.record.nativeId })
        }

    @Test
    fun `the instruction asks for a lead saying what the answer is`(): Unit = runBlocking {
        // A prose pin, the weak kind knowingly. The findings arrived under a heading each with
        // nothing above them: the reader asked an open question and got a list with no answer at the
        // top of it. Asked for positively, because told only what not to write the model writes
        // nothing there.
        val system = system()

        assertTrue(
            system.contains("Open with what the answer is"),
            "nothing asks the broadest reply for a lead: $system",
        )
        assertTrue(
            system.contains("do not open by saying what kind of material was found"),
            "a lead is asked for without refusing the restatement that fills its place: $system",
        )
    }

    @Test
    fun `the instruction states the order of the sections and of the items in one`(): Unit = runBlocking {
        // Both levels, because the defect was measured at both: the sections ran in neither recency
        // nor any inferable order, and a single section's findings ascended for a run and then jumped
        // back weeks. `Reply.sectioning` delegates the order within a section to this instruction in
        // as many words, so this is the prose that delegation points at.
        val system = system()

        assertTrue(
            system.contains("Order the answer by recency, newest first"),
            "nothing orders the broadest reply: $system",
        )
        assertTrue(
            system.contains("the section holding the most recent material comes first"),
            "the sections are left in no stated order: $system",
        )
        assertTrue(
            system.contains("within a section, the most recent item comes first"),
            "the items within a section are left in no stated order: $system",
        )
        assertTrue(
            system.contains("Take that order from the date on each block rather than from where"),
            "the order is stated as a fact about the material, which it is not within a stretch: " +
                "$system",
        )
    }

    @Test
    fun `the lead and the order are this shape's own, not the shared format's`(): Unit = runBlocking {
        // The seating decision, which the four cases above are blind to: they read the instruction
        // concatenated with the shared format, so a later card lifting these two paragraphs into
        // `Reply.format` would leave them all green while handing both rules to the two shapes that
        // section nothing — a drafted message and an answer about the open world, neither of which
        // has a section to order or an answer about the corpus to lead with.
        //
        // Three sectioning shapes now ask for a lead, which is the condition `Reply.format` says it
        // carries a rule under; moving it is a change to three prompts measured one at a time, and
        // this case is what makes the seat deliberate rather than assumed.
        val format = Reply.format(1..1, sections = true)

        assertFalse(
            format.contains("Open with what the answer is"),
            "the lead rule has moved into the shared format, where two shapes that section nothing " +
                "would be asked for one: $format",
        )
        assertFalse(
            format.contains("Order the answer by recency"),
            "the order rule has moved into the shared format, where a shape with no sections would " +
                "be told how to order them: $format",
        )
    }

    @Test
    fun `the instruction says to attribute a message to whoever is marked on it`() {
        // A prose pin, which is the weak kind of assertion, knowingly: the label is only worth
        // rendering while the model is told what it is for. Carrying a statement from one of these
        // people over to another is the specific failure here, because nothing relates them.
        val system = system()

        assertTrue(
            system.contains("attribute a message to the person marked on it and to nobody else"),
            "nothing tells the model whose words a turn carries: $system",
        )
    }

    @Test
    fun `the instruction says a turn carrying nothing is not a finding, and counts as well`(): Unit =
        runBlocking {
            // A prose pin, which is the weak kind of assertion, knowingly. Anchored on the
            // operative half rather than on the subject it governs: pinning *a turn whose whole
            // content is an acknowledgement* would stay green through a rewording that kept the
            // antecedent and dropped the prohibition, which is the half that does the work.
            //
            // Both clauses are pinned because the shape the defect takes is the second one: not a
            // line per interjection but several of them gathered into one finding, which the
            // prohibition alone reads as permitted. The count is named with it for the reason the
            // shape reporting where things stand names one in its own absence rule — a prohibition
            // on a line that says nothing new is obeyed by writing how many there were.
            val system = assembled().system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains("is not a finding: leave it out rather than report that it was written"),
                "nothing tells the fallback that a turn carrying nothing is not a finding, so the " +
                    "ranking rule it reads against is left to be obeyed against topic alone: $system",
            )
            assertTrue(
                system.contains("into one finding or into a count of them"),
                "the prohibition covers one interjection at a time and not the roll-up, which is " +
                    "the form it was measured in: $system",
            )
        }

    @Test
    fun `the substance rule states its own scope rather than leaning on where it sits`(): Unit =
        runBlocking {
            // **This case replaces an adjacency assertion, and the replacement is the point.** The
            // paragraph first opened by naming *irrelevant* back at the ranking sentence before it,
            // which made the rule depend on nothing coming between the two; that was asserted as an
            // order, and a sibling card promptly inserted a paragraph there and reddened it. So the
            // sentence was rewritten to carry its own subject instead, which is this codebase's own
            // convention — a rule is named by what it does, not by where it sits — and what is held
            // here is that it stays self-contained.
            //
            // A prose pin, which is the weak kind of assertion, knowingly: it cannot see a rule
            // that goes positional by some other wording. What it does catch is this sentence
            // losing the clause that lets it sit anywhere in the instruction.
            val system = assembled().system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains("bears on the question is a matter of substance and not only of topic"),
                "the substance rule no longer says what it is about, so it reads against whichever " +
                    "sentence happens to precede it: $system",
            )
        }

    @Test
    fun `the instruction names where an accepting turn belongs, not only what it forbids`(): Unit =
        runBlocking {
            // A prose pin, which is the weak kind of assertion, knowingly. The half that keeps the
            // prohibition obeyable: a turn that accepted something is evidence of the acceptance,
            // so forbidding it as a finding with nowhere else to be said leaves the model to pick a
            // reading — and the reading it picks is to report it anyway.
            //
            // The second clause is pinned with it because the verb is the load-bearing part. The
            // closure pass refuses to read acknowledging, agreeing or thanking as a conclusion, so
            // a rule inviting this shape to report that something was *settled* on the evidence of
            // one word would have the answer path assert what the derived layer declines to.
            val system = assembled().system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains("what was accepted is the finding and the turn is cited under it"),
                "the rule forbids reporting an accepting turn and names nowhere for the " +
                    "acceptance to be stated, so it is unobeyable as written: $system",
            )
            assertTrue(
                system.contains("not on its own evidence that anything was concluded"),
                "an acknowledgement is offered as evidence that something was concluded, which is " +
                    "the reading the closure stage refuses: $system",
            )
            // The loophole the place itself opened, found by measuring and not by reading. Given
            // somewhere legal to report an acceptance, the model reported acknowledging a *status
            // update* as one — the defect again, wearing the exemption. So the exemption is bounded
            // to a turn that answers something asked: noting what you were told is not accepting a
            // proposal, and there is nothing in it for a reader.
            assertTrue(
                system.contains("Noting a statement is not accepting a proposal"),
                "the acceptance exemption covers a turn that merely acknowledges a statement, " +
                    "which is the defect readmitted through the clause that exempts it: $system",
            )
        }

    @Test
    fun `a short turn that answers something is still reported, with what it answers`(): Unit =
        runBlocking {
            // A prose pin, which is the weak kind of assertion, knowingly. The opposite error, and
            // the worse one: a confirmation that answers a question is often the most useful turn
            // in a thread, and a rule read as *drop what is short* would discard it. Pinned
            // separately from the prohibition because the two fail apart — a rewording that
            // tightened the prohibition and dropped this would pass the case above.
            //
            // The second clause is the branch for material the widening did not reach. Without it
            // the only reading left for a one-word answer whose question is absent is the
            // prohibition, and a rule that states one branch of a pair is obeyed against whichever
            // the model picks — which is why the shared subject rule states both of its own.
            val system = assembled().system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains("report it together with the question it answers"),
                "nothing keeps a one-word answer that settles something in the reply, so the " +
                    "prohibition it qualifies reads as a rule against short turns: $system",
            )
            assertTrue(
                system.contains("there is nothing for it to settle and it is left out with the rest"),
                "the rule says what to do when the answered question is in the material and not " +
                    "when it is absent, so the model picks a branch: $system",
            )
        }

    @Test
    fun `a thin record is reported as thin without an account of what was left out`(): Unit =
        runBlocking {
            // A prose pin, which is the weak kind of assertion, knowingly. The residue a draft
            // without this clause left, which is why it is pinned at all: told only that
            // interjections are not findings, the model answered a question about what was new by
            // reporting that the rest of the material was short replies, with the date of the last
            // of them — the defect again, one line further on.
            //
            // Anchored on the prohibition and not on its subject. Pinning *an account of what you
            // left out* alone would stay green through a reversal saying such an account is exactly
            // what makes a thin answer honest, which is the opposite rule under the same words.
            //
            // **This clause is absolute about the account on purpose, and the alternative was
            // measured rather than argued.** A reviewed draft narrowed it — the account barred as
            // a *finding* and the thin record given a named place to be said in, which is what the
            // rest of this prompt's rules do and what it looked like this one should. Run over the
            // same material it scored a reply worse rather than better, so the absolute form
            // stayed. One reply is inside the noise this probe can resolve, which is why the
            // claim here is that naming the place bought nothing, not that it did harm.
            //
            // So the account is left with nowhere legal to be said, which is the shape this file
            // otherwise avoids. What a reader loses by it is nothing visible: in both arms the
            // model still opens a thin reply with an uncited clause saying little of substance was
            // found, which no rule here asks for and none forbids.
            val system = assembled().system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains("an account of what you left out is no part of saying so"),
                "a question the material barely answers can be answered by describing the turns " +
                    "that were left out, which is the finding this rule removes: $system",
            )
            assertTrue(
                system.contains("not how many there were, not when the last of them was"),
                "the clause forbids the account in general and not the two forms it was measured " +
                    "taking, a count and the date of the last one: $system",
            )
        }

    @Test
    fun `the substance rule is this shape's own, and reddens if it is lifted into the shared format`() {
        // The seating decision, blind to every case above for the reason the date rule's own
        // seating case is: they read the instruction concatenated with the shared format, so a
        // later card moving this paragraph into `Reply.format` would leave every one of them green
        // while handing the rule to the shape that composes a reply — which reads a thread's tail
        // acknowledgements and all, on purpose, because that is where the register is learned.
        //
        // **Flattened, which the date rule's twin gets away with not doing.** Its pinned sentence
        // happens to sit on one source line; this paragraph's wraps, so a verbatim paste into
        // `Reply.format` would carry the newline and `contains` would miss it — the guard would
        // stay silent for exactly the move it exists to catch. Calibrated by appending the
        // paragraph to the format and confirming this case reddens, which is the mutation itself
        // rather than a phrase standing in for it.
        //
        // **Reddening is the point and deleting it may be the right answer.** The seat rests on
        // one shape having produced the defect, not on no other shape being able to: a later card
        // measuring the same thing on the open-web shape or the call brief would meet the format's
        // membership rule, and then this case is what makes moving the rule a decision somebody
        // took rather than a drift nobody noticed.
        val format = Reply.format(1..1, sections = true).replace(WHITESPACE, " ")

        assertFalse(
            format.contains("is not a finding: leave it out rather than report that it was written"),
            "the substance rule has moved into the shared format, where the drafting shape would " +
                "be told to drop the turns its material exists to show it: $format",
        )
    }

    @Test
    fun `the instruction says the ranking may have left material behind, not only that it brought too much`() {
        // The asymmetry this shape was reported on: warned about precision and not about recall, a
        // model summarises what it was told is the relevant part of the corpus as the corpus. Both
        // halves are pinned together, because the defect is not either sentence's absence — it is
        // one of them standing alone, which is what the instruction read like when it was measured.
        val system = system()

        assertTrue(
            system.contains("some of what you were given may be irrelevant"),
            "nothing warns that the ranked material may carry what the question did not ask for: $system",
        )
        assertTrue(
            system.contains("the corpus may hold more that no ranking put in front of you"),
            "nothing tells the model that the ranking may have left material behind: $system",
        )
    }

    @Test
    fun `the request states when now is, so an old date has something to place it`(): Unit = runBlocking {
        // The operand half, and the one a prose pin cannot stand in for. A date resolved correctly
        // out of old material read as broken arithmetic beside a recent one, because nothing in the
        // prompt said when now was and the model had nothing to place it with. The read is in the
        // pull request; what belongs here is the line.
        assertTrue(
            assembled().prompt.contains("Now: 2026-10-15 Thu 09:00 Z"),
            "the fallback states no present, so a date it reports cannot be placed",
        )
    }

    @Test
    fun `the material may not be presented as exhaustive, and an absence is scoped to what was read`() {
        // The two statements measured on a delivered reply: that what it carried was everything the
        // material held about somebody, and that they appeared in a single body of work. Both are
        // claims about a whole this shape never read, and no citation can evidence either — the
        // grounding gate asks whether a cited span was retrieved, never whether the retrieved set
        // was the corpus. So the prompt is the only rail, and this is a prose pin: weak, knowingly.
        //
        // The exhaustiveness pin reads the "not as everything in the material" arm specifically.
        // That is the arm the measured reply actually used, and the one a rule phrased against the
        // corpus alone would leave open, since this prompt's own word for the sample is "material".
        val system = system()

        assertTrue(
            system.contains("Do not present it as exhaustive"),
            "nothing forbids offering the sample as all there is: $system",
        )
        assertTrue(
            system.contains("not as everything in the material"),
            "the rule reaches a claim about the corpus but not the wording the reply used: $system",
        )
        assertTrue(
            system.contains("do not turn what you can see into a count of what the corpus holds"),
            "nothing forbids counting the sample as though it were the corpus: $system",
        )
        assertTrue(
            system.contains("rather than that the corpus holds nothing about it"),
            "an absence may still be reported of the whole corpus rather than of what was read: $system",
        )
    }

    @Test
    fun `the two sentences the rest of the prompt asks for stay allowed`() {
        // The prohibition's own boundary, and the reason it is drawn: both of these arrive in the
        // same model call as the rule, from the shared format, and a uniqueness rule written
        // without the boundary would have the model choose which instruction to obey.
        // `Reply.sectioning` asks for two labels naming one body of work to be reported as one
        // section, which the evaluation set scores. `Reply.format`'s rule on a commitment's
        // outcome asks for an undertaking with nothing recorded after it to be reported as exactly
        // that, which reads as an absence unless the line is drawn.
        val system = system()

        assertTrue(
            system.contains("two threads you were given are one piece of work"),
            "the rule against a uniqueness claim also suppresses the grouping the answer is asked " +
                "for: $system",
        )
        assertTrue(
            system.contains("an undertaking you can see has no outcome recorded after it"),
            "the rule reaches the shared format's own requirement to report an unrecorded " +
                "outcome, which it would then be read as forbidding: $system",
        )
    }

    @Test
    fun `stating the scope is bounded and uncited, so a thin answer is not a paragraph about the search`() {
        // Two failures a prohibition produces on its own. Told only not to claim the whole, a model
        // qualifies every line it writes — so the scope is stated once. And an absence cites
        // nothing, while the rule above it asks every assertion about the material to carry a
        // number, so the prompt has to say which of the two these sentences are or the model picks.
        val system = system()

        assertTrue(
            system.contains("state it once"),
            "the scope may be restated on every line, which is what hedging the whole reply looks " +
                "like: $system",
        )
        assertTrue(
            system.contains("ordinary prose and carry no citation"),
            "an absence and a scope are left owing a citation that nothing can evidence: $system",
        )
    }

    @Test
    fun `the sample rule is seated in this shape and not in the shared reply format`() {
        // The negative half of the decision the instruction's own KDoc argues at length, and
        // without it nothing would notice the rule moving. The pins above read the instruction
        // concatenated with the shared format, so a later card that lifted these paragraphs into
        // `Reply.format` — the seat this one rejected, because a shape that asserts its findings is
        // told to write such a sentence as a cited claim line instead — would leave every one of
        // them green.
        //
        // Calibrated by pointing the same assertion at a phrase the shared format really carries,
        // which fails as it should.
        val shared = Reply.format(variants = 1..1, sections = true, asserts = false)
            .replace(WHITESPACE, " ")

        listOf(
            "the corpus may hold more that no ranking put in front of you",
            "may be a part of the corpus rather than all of it",
            "Do not present it as exhaustive",
            "not as everything in the material",
            "a count of what the corpus holds",
            "two threads you were given are one piece of work",
            "rather than that the corpus holds nothing about it",
            "state it once",
            "ordinary prose and carry no citation",
        ).forEach { pinned ->
            assertFalse(
                shared.contains(pinned),
                "the shared format carries this shape's rule, so every pin above would survive " +
                    "moving it there: $pinned",
            )
        }
    }

    @Test
    fun `the instruction asks how far behind the present a date is, not merely that it is`(): Unit =
        runBlocking {
            // A prose pin, which is the weak kind of assertion, knowingly. Both clauses are pinned
            // because it is the second that answers the reader's question: a date some weeks behind
            // and one some months behind are what has to be told apart, and *past* alone leaves a
            // correct old date looking exactly like a miscount.
            val system = assembled().system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains("A calendar date you report from the material is read against it"),
                "nothing tells the fallback to read a date against the present: $system",
            )
            assertTrue(
                system.contains("how many months behind the present it is"),
                "the fallback is told a date is past but not by how far, so months and weeks " +
                    "read alike: $system",
            )
        }

    @Test
    fun `the rule names the line it reads against, and excludes a computed standing`(): Unit =
        runBlocking {
            // Both halves, for the reason the shared relative-time rule pins both of its own: the
            // model has three kinds of time in front of it — a block's heading, the standings this
            // build writes into a block, and this line — and a rule that names none of them is
            // obeyed against whichever is nearest. Naming the line gives it an antecedent; naming
            // what it is not keeps it off the standings, which arrive already measured against the
            // present and which re-dating would throw out by months.
            val system = assembled().system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains("The line at the head of the request beginning Now"),
                "the rule does not say which of the prompt's times it reads against: $system",
            )
            assertTrue(
                system.contains("not a standing this system wrote into a block"),
                "the rule reaches the computed standings too, so it re-dates them: $system",
            )
        }

    @Test
    fun `the gap is asked for at a month and no finer, because nothing checks the arithmetic`(): Unit =
        runBlocking {
            // The gap is the model's own arithmetic over a date it has just read, and this shape
            // derives no figure to hold it against — unlike the shapes built on `Material`. A gap
            // in months survives being a day out; one in days is exactly the size of that error.
            // The bound is the rule's, so a later rewording that asks for days has to come past
            // this case.
            val system = assembled().system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains("A date nearer than that needs no such figure"),
                "the rule asks for a gap below a month, which two calendars cannot agree on: " +
                    system,
            )
        }

    @Test
    fun `the date rule is this shape's own, and reddens if it is lifted into the shared format`() {
        // The seating decision for the date paragraphs, which every case reading the composed
        // prompt is blind to: they read the instruction concatenated with the shared format, so a
        // later card moving these paragraphs into `Reply.format` — the seat this one rejects,
        // because one shape produced the defect — would leave all of them green while handing the
        // rule to four shapes that never asked for it, two of which are given no present to read a
        // date against at all.
        //
        // Calibrated by pointing the same assertion at a phrase the shared format really carries,
        // which fails as it should.
        val format = Reply.format(1..1, sections = true)

        assertFalse(
            format.contains("A calendar date you report from the material is read against it"),
            "the date rule has moved into the shared format, where two shapes that state no " +
                "present would be told to read a date against one: $format",
        )
    }

    // --- doubles and fixtures -------------------------------------------------------------

    private companion object {

        /**
         * A fixed present, which is what makes the line stating it assertable at all.
         *
         * The instant is what matters and this clock's own zone is never read: `Assemble` takes
         * `clock.instant()` and prints it in the zone it was given, which this fixture leaves at
         * the default. The value matches the instant the stage's own cases and the status shape's
         * pin, so a reader comparing the three finds one figure — and because the default is UTC,
         * the printed form matches too.
         */
        val NOW: Clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)

        /** What the research shape carries, which is the smallest of the ranked reads. */
        const val SCOPED_SHAPE_HITS = 10

        /**
         * Runs of whitespace as one space, so a re-wrapped paragraph still reads as its sentence.
         *
         * An assertion matching the raw prompt would really be asserting where the paragraph was
         * wrapped, which turns a rewrap into a red and invites reflowing the prompt to suit a test.
         */
        val WHITESPACE = Regex("\\s+")

        /**
         * What the assembly stage built, which is where both the stated present and the standing
         * instruction can be read.
         *
         * Run over an exchange with nothing retrieved, because neither of those is a function of
         * the material: the instruction is the shape's own and the present is the clock's.
         *
         * The stage is found by type rather than by position, as the registry's and the stage's own
         * cases find it: an index reads whatever the list happens to hold at that slot, and nothing
         * stops a shape reordering its stages.
         */
        suspend fun assembled() = requireNotNull(
            assertIs<Progress.Carried>(
                shape().stages.filterIsInstance<Assemble>().single()
                    .apply(Exchange(Request(text = "what rate did we agree"))),
            ).exchange.assembled,
        )

        /**
         * The standing half of the prompt as a request would carry it, runs of whitespace folded,
         * which is what a prose pin is read against.
         *
         * Read through the assembly stage rather than off the constant, so that what is pinned is
         * the instruction as concatenated with the shared reply format — a rule stated there would
         * make one stated here a duplicate rather than an addition, which is what the seating cases
         * read this for.
         *
         * **The per-request block is not in this and the pins do not reach it.** Assembly puts the
         * subject, the question, the sectioning rule and the material in `prompt` rather than in
         * `system`, and this fixture retrieves nothing, so neither the material nor the sectioning
         * rule is here to read.
         *
         * Not `suspend`, so a case that asserts nothing about the pipeline needs no coroutine of
         * its own; one already inside a `runBlocking` can call it just the same.
         */
        fun system(): String = runBlocking { assembled().system.replace(WHITESPACE, " ") }

        /**
         * One indexed span, which is all a ranked read has of a record: an identifier and a body.
         *
         * [at] is defaulted because most cases here are about what a span carries rather than about
         * when it is from; the ordering cases state it, since a shared instant cannot discriminate
         * an order at all.
         */
        fun span(nativeId: String, text: String, at: String? = null) = Span(
            FakeRetrieval.evidence(nativeId).span,
            text,
            at?.let(Instant::parse) ?: FakeRetrieval.WHEN,
        )

        fun shape(
            index: FakeIndex = FakeIndex(),
            embeddings: FakeEmbeddings = FakeEmbeddings(),
            retrieval: FakeRetrieval = FakeRetrieval(),
        ) = FreeformShape(
            FakeModelProvider(),
            Lookup(embeddings, index),
            retrieval,
            FakeGrouping(),
            ZoneOffset.UTC,
            NOW,
        )

        suspend fun retrieve(
            index: FakeIndex,
            embeddings: FakeEmbeddings = FakeEmbeddings(),
            retrieval: FakeRetrieval = FakeRetrieval(),
        ) = assertIs<Progress.Carried>(
            shape(index, embeddings, retrieval).stages.first().apply(
                Exchange(Request(text = "what rate did we agree")),
            ),
        ).exchange.retrieved
    }
}
