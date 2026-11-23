package io.github.ragnorrs.mainbrain.domain.answer.shape.status

import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.answer.Assembled
import io.github.ragnorrs.mainbrain.domain.answer.Exchange
import io.github.ragnorrs.mainbrain.domain.answer.FakeEmbeddings
import io.github.ragnorrs.mainbrain.domain.answer.FakeGrouping
import io.github.ragnorrs.mainbrain.domain.answer.FakeIndex
import io.github.ragnorrs.mainbrain.domain.answer.FakeRetrieval
import io.github.ragnorrs.mainbrain.domain.answer.Lookup
import io.github.ragnorrs.mainbrain.domain.answer.Moment
import io.github.ragnorrs.mainbrain.domain.answer.Progress
import io.github.ragnorrs.mainbrain.domain.answer.Reach
import io.github.ragnorrs.mainbrain.domain.answer.Request
import io.github.ragnorrs.mainbrain.domain.answer.SubjectKind
import io.github.ragnorrs.mainbrain.domain.answer.contacts
import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.Origin
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The daily driver: where things stand, what is owed, what is next.
 *
 * Its own package's fixtures, as every shape has. What is worth asserting is the retrieval contract
 * rather than the prose: that the query set is fixed, that it is bounded, and that the inbox really
 * does read the unscoped form — the last of which is the difference between the shape answering at
 * two scopes and the shape quietly answering nothing when a request named nobody.
 *
 * **The cases about where things stand were calibrated by breaking the thing each one is about**,
 * one break at a time, each applied to a copy and the failing names read out of the result XML: the
 * computed order bypassed, the overdue figure measured to the raw deadline and then dropped, the age
 * clause dropped, age read from the span the finding is shown under, the direction marker dropped
 * from the rendering and then its party from the name read, and every clause this file pins taken
 * out of the instruction one by one — including the recency sentence the ordering rule replaced, put
 * back to check the polarity of the assertion that forbids it. Every one reddened exactly the case
 * it was aimed at and no other, against a suite that really ran. The exception is recorded on the
 * case it belongs to: the fold case's order is also the unsorted order, so it holds the fold and not
 * the sort.
 *
 * What none of that establishes is whether the model obeys any of it. These are prose pins and
 * material assertions; only reading answers on the archive can say more.
 */
class StatusShapeTest {

    @Test
    fun `it answers for every subject kind, the inbox included`() {
        // Asked naming nobody it answers from entities across everything, which is only reachable
        // if the inbox is an accepted kind rather than an absence the runner refuses. Every other
        // kind is here because this is the shape a question about anything is answered by: one a
        // body of work were missing from would resolve a name that picks out several strands of one
        // piece of work to a menu of them, which asks about a part of what was named.
        assertEquals(
            setOf(
                SubjectKind.CONTACT,
                SubjectKind.CLIENT,
                SubjectKind.BODY_OF_WORK,
                SubjectKind.CONVERSATION,
                SubjectKind.INBOX,
            ),
            shape(FakeRetrieval()).accepts,
        )
    }

    @Test
    fun `there is one account of where things stand, not a choice of three`() {
        assertEquals(1..1, shape(FakeRetrieval()).variants)
    }

    @Test
    fun `it answers from the corpus, so an empty corpus short-circuits before the model`() {
        assertTrue(shape(FakeRetrieval()).readsCorpus)
    }

    @Test
    fun `retrieval runs before assembly, and assembly before generation`() {
        // The stage list is the shape's whole contribution to the machinery, and an order that put
        // generation first would reach it with nothing assembled.
        val stages = shape(FakeRetrieval()).stages

        assertEquals(3, stages.size)
        assertTrue(stages.take(2).none { it.spendsModel })
        assertTrue(stages.last().spendsModel)
    }

    @Test
    fun `a named subject is read for commitments, events and the tail of its threads`(): Unit = runBlocking {
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "we agreed Friday")),
            commitments = listOf(FakeRetrieval.commitment("send the invoice", evidencedBy = "2")),
            ahead = listOf(FakeRetrieval.event("the review call", evidencedBy = "3")),
        )

        val retrieved = retrieve(retrieval, FakeRetrieval.aContact())

        // Three reads, and all three in the retrieved set — a shape that read commitments and then
        // dropped them would look identical to one that never asked.
        assertEquals(3, retrieved.size)
        assertTrue(retrieved.any { it.text.contains("we agreed Friday") })
        assertTrue(retrieved.any { it.text.contains("send the invoice") })
        assertTrue(retrieved.any { it.text.contains("the review call") })
    }

    @Test
    fun `an inferred commitment is marked as an inference rather than quoted as fact`(): Unit = runBlocking {
        val retrieval = FakeRetrieval(
            commitments = listOf(FakeRetrieval.commitment("send the invoice", evidencedBy = "2", hedged = true)),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact()).single().text

        assertTrue(text.contains("Inferred commitment"))
        assertTrue(text.contains("not stated firmly or not first hand"), text)
    }

    @Test
    fun `a hedge is marked as what the speaker did, not as how this system read it`(): Unit = runBlocking {
        // The word the rendered reply echoed. Marked `hedged`, the finding reached a reader through
        // a parenthetical on how cautiously the obligation had been read — a clause about the
        // extraction, in a sentence a person would not write. What the flag records is one of two
        // things about the speaker, so that is what the marker says.
        //
        // Asserted as an absence as well, because the wording is the whole of the change: a case
        // reading only for the new clause would pass with both in the text, which is the material
        // this was measured on plus a second thing to repeat.
        val retrieval = FakeRetrieval(
            commitments = listOf(FakeRetrieval.commitment("send the invoice", evidencedBy = "2", hedged = true)),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact()).single().text

        assertFalse(
            text.contains("hedged"),
            "the marker still names the extraction, which is the word that reached the reader: $text",
        )
    }

    @Test
    fun `a finding names the party that stated it, from the entity layer`(): Unit = runBlocking {
        // The point of the whole read: before this, the only marker of a party available to a reader
        // was whatever label the restating model happened to write into the claim prose — a real name
        // where the corpus named the author, and otherwise a label local to the window, which resolves
        // to nothing afterwards.
        val party = EntityId.mint()
        val retrieval = FakeRetrieval(
            commitments = listOf(
                FakeRetrieval.commitment("send the invoice", evidencedBy = "2", statedBy = party),
            ),
            names = mapOf(party to "A Named Person"),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact()).single().text

        assertTrue(text.contains("A Named Person"), "the party is not named beside the claim: $text")
        assertTrue(text.contains("stated by"), "who stated it is not who owes it, and the text must say which: $text")
    }

    @Test
    fun `a retrieved turn names whoever wrote it`(): Unit = runBlocking {
        // **A subject's threads are not all one-to-one.** This shape follows the rooms a person
        // writes in as well as the chat with them, so a stretch of material carries several
        // people's words — and a turn offered with no author leaves the subject and the sender as
        // the only agents a model can name. Nothing downstream notices: the grounding gate asks
        // whether a cited span was retrieved and not whose words it carried.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(
                FakeRetrieval.message("1", "we agreed on Friday"),
                FakeRetrieval.message("2", "I will handle the shipping"),
            ),
            turns = mapOf(
                FakeRetrieval.record("1") to FakeRetrieval.turn("The Counterpart"),
                FakeRetrieval.record("2") to FakeRetrieval.turn("Another Participant"),
            ),
        )

        val retrieved = retrieve(retrieval, FakeRetrieval.aContact()).associateBy { it.id.record.nativeId }

        assertEquals(
            "Sent by The Counterpart: we agreed on Friday",
            retrieved.getValue("1").text,
        )
        assertEquals(
            "Sent by Another Participant: I will handle the shipping",
            retrieved.getValue("2").text,
            "a room's other participant's words are offered as the subject's own",
        )
    }

    @Test
    fun `a turn whose author the corpus never named carries no name`(): Unit = runBlocking {
        // The convention a finding's absent party already follows: nothing rather than nobody. A
        // placeholder would be read as the author's name by the one thing that cannot check it.
        // One case for the three the store collapses: no author recorded, an author nothing has
        // reconciled, and an author neither named itself nor through its human.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "we agreed on Friday")),
            turns = emptyMap(),
        )

        assertEquals(
            "we agreed on Friday",
            retrieve(retrieval, FakeRetrieval.aContact()).single().text,
        )
    }

    @Test
    fun `the closing turn names who sent it too`(): Unit = runBlocking {
        // The second rendering of a message, and the second place an author can be dropped. It
        // already carries a marker and an instant, so the name joins those rather than leading —
        // and a turn labelled twice over would read as two labels.
        val commitment = FakeRetrieval.commitment("send the invoice", evidencedBy = "2")
        val retrieval = FakeRetrieval(
            commitments = listOf(commitment),
            closures = mapOf(commitment.id to FakeRetrieval.message("9", "invoice is away")),
            turns = mapOf(FakeRetrieval.record("9") to FakeRetrieval.turn("The Sender")),
        )

        val turn = retrieve(retrieval, FakeRetrieval.aContact()).single { it.text.contains("invoice is away") }

        assertTrue(
            turn.text.contains("closing a commitment"),
            "the closing turn lost its own marker: ${turn.text}",
        )
        assertTrue(
            turn.text.contains("sent by The Sender"),
            "nothing says who reported the promise as done: ${turn.text}",
        )
    }

    @Test
    fun `the author read asks about the turns in hand, once`(): Unit = runBlocking {
        // One read for the handful of turns a few threads reach, and the closing turns are in it
        // too: a closure's message is a message. Asked per turn it would be a round trip per line of
        // the prompt, and asked before the closures it would miss them.
        val commitment = FakeRetrieval.commitment("send the invoice", evidencedBy = "2")
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(
                FakeRetrieval.message("1", "one"),
                FakeRetrieval.message("2", "two"),
            ),
            commitments = listOf(commitment),
            closures = mapOf(commitment.id to FakeRetrieval.message("9", "invoice is away")),
        )

        retrieve(retrieval, FakeRetrieval.aContact())

        val asked = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Turns>().single()
        assertEquals(
            listOf("1", "2", "9").map(FakeRetrieval::record),
            asked.messages,
            "a turn the read did not ask about is one the prompt offers unattributed",
        )
    }

    @Test
    fun `a turn written by neither the subject nor the reader is marked as such`(): Unit = runBlocking {
        // **The defect this guards is a reply about one person carrying other people's business.**
        // Most of a subject's threads are rooms, so most of this tail is written by somebody the
        // question did not ask about — and a turn offered with a name and nothing else is composed
        // into a finding about where the subject stands. Nothing downstream sees it: the sentence
        // is fluent, the citation resolves, and the grounding gate asks whether a span was
        // retrieved rather than whose matter it recorded.
        //
        // **All four states in one fixture, because the marker is only worth anything if it is
        // absent from the rest.** A clause on every turn would say nothing. So the subject's own
        // turn and a turn the corpus attributes to nobody are asserted to carry no third-party
        // mark, and only the third party's carries it.
        //
        // **The asker's own turn now carries a mark of its own, which is what it did not.** It was
        // asserted bare here, on the reasoning that the asker's side is half of where things stand
        // and needs no label — and that left the shape unable to answer the half of its question
        // that asks what is waiting on the reader: it listed obligations by party and said the
        // material did not say who was asking. What this case still holds is the property it was
        // written for, that such a turn is not marked as a third party's.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(
                FakeRetrieval.message("1", "I will send the figures on Friday"),
                FakeRetrieval.message("2", "we cancelled the standup, I am away"),
                FakeRetrieval.message("3", "any word on those figures?"),
                FakeRetrieval.message("4", "something was said"),
            ),
            turns = mapOf(
                FakeRetrieval.record("1") to FakeRetrieval.turn("The Subject", anotherParty = false),
                FakeRetrieval.record("2") to FakeRetrieval.turn("Another Participant", anotherParty = true),
                FakeRetrieval.record("3") to
                    FakeRetrieval.turn("The Reader", isSelf = true, anotherParty = false),
                FakeRetrieval.record("4") to FakeRetrieval.turn(author = null, anotherParty = null),
            ),
        )

        val retrieved = retrieve(retrieval, FakeRetrieval.aContact()).associateBy { it.id.record.nativeId }

        assertEquals(
            "Sent by Another Participant, who is neither the subject nor the reader: " +
                "we cancelled the standup, I am away",
            retrieved.getValue("2").text,
            "a third party's turn is offered as the subject's own material, which is how a " +
                "meeting somebody else cancelled becomes where things stand with the subject",
        )
        assertEquals(
            "Sent by The Subject: I will send the figures on Friday",
            retrieved.getValue("1").text,
            "the subject's own words are denied, which would confine the answer to the thread's " +
                "context and leave it with nothing of theirs",
        )
        assertEquals(
            "Sent by The Reader (the person asking): any word on those figures?",
            retrieved.getValue("3").text,
            "the reader's own turn is marked as another participant's, and their side of the " +
                "relationship is half of where things stand",
        )
        assertEquals(
            "something was said",
            retrieved.getValue("4").text,
            "a turn the corpus names nobody for is marked as another participant's, which " +
                "asserts an author the record does not have",
        )
    }

    @Test
    fun `an unnamed third party's turn is still marked, leading the block`(): Unit = runBlocking {
        // The marker is resolved on the contact and the name is a separate read, so a turn can be
        // known not to be the subject's while the corpus names nobody for it. There is then no name
        // for the clause to join, which is why this fact carries a leading form as every other
        // clause in the material does — without one the marker would vanish for exactly the turns
        // an answer can say least about.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "we cancelled the standup")),
            turns = mapOf(FakeRetrieval.record("1") to FakeRetrieval.turn(author = null, anotherParty = true)),
        )

        assertEquals(
            "Said by neither the subject nor the reader: we cancelled the standup",
            retrieve(retrieval, FakeRetrieval.aContact()).single().text,
        )
    }

    @Test
    fun `a closing turn written by another party carries the mark too`(): Unit = runBlocking {
        // **Once the recent turns carry the mark, its absence says the subject or the reader wrote
        // the turn** — so a rendering that skips it does not merely lose a fact, it asserts the
        // wrong one. A promise is frequently reported done by somebody other than the party who
        // gave it, so this is the ordinary case rather than a corner.
        //
        // The closure's turn is also in the author read, so the answer is already in hand here; the
        // defect would be discarding it at the rendering.
        val commitment = FakeRetrieval.commitment("send the invoice", evidencedBy = "2")
        val retrieval = FakeRetrieval(
            commitments = listOf(commitment),
            closures = mapOf(commitment.id to FakeRetrieval.message("9", "invoice is away")),
            turns = mapOf(
                FakeRetrieval.record("9") to FakeRetrieval.turn("Another Participant", anotherParty = true),
            ),
        )

        val turn = retrieve(retrieval, FakeRetrieval.aContact()).single { it.text.contains("invoice is away") }

        assertTrue(
            turn.text.contains("closing a commitment"),
            "the closing turn lost its own marker: ${turn.text}",
        )
        assertTrue(
            turn.text.contains("neither the subject nor the reader"),
            "a third party reported the promise done and the turn says the subject or the reader " +
                "did, which is what an unmarked turn now means: ${turn.text}",
        )
    }

    @Test
    fun `a closing turn the corpus names nobody for carries no dangling clause`(): Unit = runBlocking {
        // The mark is a relative clause joining the name, and a closing turn leads with a label and
        // an instant rather than with the name — so with no name there is nothing for it to qualify
        // and it is left off. Asserted rather than assumed, because the alternative renders a
        // clause whose subject is the instant before it.
        val commitment = FakeRetrieval.commitment("send the invoice", evidencedBy = "2")
        val retrieval = FakeRetrieval(
            commitments = listOf(commitment),
            closures = mapOf(commitment.id to FakeRetrieval.message("9", "invoice is away")),
            turns = mapOf(
                FakeRetrieval.record("9") to FakeRetrieval.turn(author = null, anotherParty = true),
            ),
        )

        val turn = retrieve(retrieval, FakeRetrieval.aContact()).single { it.text.contains("invoice is away") }

        assertFalse(
            turn.text.contains("who is neither"),
            "a relative clause is rendered with no name in front of it, so it qualifies the " +
                "instant: ${turn.text}",
        )
    }

    @Test
    fun `the author read asks about the subject's own contacts`(): Unit = runBlocking {
        // Naming the author is half the fact; whether that author is the person asked about is the
        // other half, and the read cannot answer it without being told who to compare against. A
        // shape that forgot the argument gets nulls back and marks nothing, which reads exactly
        // like a corpus in which every turn is the subject's.
        val subject = FakeRetrieval.aContact()
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "one")),
        )

        retrieve(retrieval, subject)

        assertEquals(
            subject.contacts(),
            retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Turns>().single().subject,
            "the comparison is on the contact and resolves through the merge, so the contacts the " +
                "subject reaches are what the read has to be given",
        )
    }

    @Test
    fun `a subject with no contact behind it marks nothing`(): Unit = runBlocking {
        // A client is a reading of a conversation's label and has no person behind it, so there is
        // no author for a turn to be compared against — `contacts()` is empty and the read answers
        // nothing rather than denying. Marked as another participant's, every turn of a thread
        // asked about by its client would be demoted to context and the reply would have nothing
        // left to report.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "we agreed on Friday")),
            turns = mapOf(FakeRetrieval.record("1") to FakeRetrieval.turn("Another Participant", anotherParty = true)),
        )

        assertEquals(
            "Sent by Another Participant: we agreed on Friday",
            retrieve(retrieval, FakeRetrieval.aClient()).single().text,
            "a subject with nobody behind it denies every turn it reads",
        )
    }

    @Test
    fun `the instruction says what a marked message means and does not`(): Unit =
        runBlocking {
            // Prose pins, which are the weak kind of assertion and knowingly so: the marker is only
            // worth rendering while the model is told what follows from it.
            //
            // **Pinned on the operative clauses and not on the framing ones.** A case asserting
            // only "this mark exists" and "it is not where things stand" passes with the redirect
            // deleted — and the redirect is the only thing that makes the demotion obeyable, since
            // a rule that says where something does not go and never says where it does leaves the
            // model to choose. The bound against the inventory is pinned for the same reason and
            // one more: it exists because a live run produced the inventory, so it is the clause
            // most likely to be reverted by somebody who did not see that run.
            val system = assembled(FakeRetrieval()).collapsed()

            assertTrue(
                system.contains("The mark is about who wrote it and not about what it says"),
                "nothing states the marker's bound, so a model reads it as a claim about whose " +
                    "matter the message reports rather than about who wrote it: $system",
            )
            assertTrue(
                system.contains("it is not where things stand with the subject"),
                "nothing says what follows from the marker, so another participant's business is " +
                    "still reported as the subject's standing: $system",
            )
            assertTrue(
                system.contains(
                    "report it under what happened recently, say whose matter it is, and keep it " +
                        "out of the opening and out of what was undertaken and what to do next",
                ),
                "the redirect is gone, so the rule says where a finding may not go and never " +
                    "where it does, which a model resolves by choosing: $system",
            )
            assertTrue(
                system.contains("That is not a reason to leave material out"),
                "nothing guards the opposite failure, and an answer emptied of everything but " +
                    "the subject's own words reads as a broken answer path: $system",
            )
            assertTrue(
                system.contains("it is not licence to inventory the room"),
                "nothing bounds the other half, and a subject with little of their own material " +
                    "gets a reply that writes out every other participant's week: $system",
            )
            assertTrue(
                system.contains("What earns a place is a marked message bearing on the subject's own items"),
                "the bound is stated as a prohibition with no test for what passes it, which is " +
                    "the wording a live run replaced: $system",
            )
            assertTrue(
                system.contains("Such a line is an assertion about the material like any other"),
                "the summarising line is asked for without its citations, and an uncited claim " +
                    "line takes the whole alternative with it: $system",
            )
        }

    @Test
    fun `the instruction quotes the words the material actually renders`(): Unit = runBlocking {
        // **The rule has to key on the literal the material carries, or the two drift apart
        // silently.** The prompt describing one mark and the rendering writing another is a defect
        // nothing else here can see: the prompt reads as a rule about a marker that never appears,
        // and every case above still passes.
        //
        // Both rendered forms are asserted to carry the quoted phrase, because the joining form is
        // the one a named author gets — which is nearly every turn — and a pin on the leading form
        // alone would let that one be reworded away.
        val named = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "we cancelled the standup")),
            turns = mapOf(FakeRetrieval.record("1") to FakeRetrieval.turn("Another Participant", anotherParty = true)),
        )
        val unnamed = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "we cancelled the standup")),
            turns = mapOf(FakeRetrieval.record("1") to FakeRetrieval.turn(author = null, anotherParty = true)),
        )

        assertTrue(
            assembled(FakeRetrieval()).collapsed().contains("\"$MARK\""),
            "the instruction does not quote the marker's own words, so the rule and the rendering " +
                "can be reworded apart",
        )
        assertTrue(
            retrieve(named, FakeRetrieval.aContact()).single().text.contains(MARK),
            "the joining form no longer carries the words the instruction quotes",
        )
        assertTrue(
            retrieve(unnamed, FakeRetrieval.aContact()).single().text.contains(MARK),
            "the leading form no longer carries the words the instruction quotes",
        )
    }

    @Test
    fun `the instruction says to attribute a message to whoever is marked on it`(): Unit = runBlocking {
        // A prose pin, which is the weak kind of assertion, knowingly: the label on a turn is only
        // worth rendering while the model is told what it is for. Attribution is the one part of
        // this material a model will supply from context if the prompt leaves it open.
        val system = assembled(FakeRetrieval()).collapsed()

        assertTrue(
            system.contains("attribute a message to the person marked on it and to nobody else"),
            "nothing tells the model whose words a turn carries: $system",
        )
    }

    @Test
    fun `the instruction says a mark names who wrote a thing, not who did it`(): Unit = runBlocking {
        // **The rule above is right about whose words a turn holds and produces a misattribution
        // on its own.** A turn's sender and a finding's stated-by party both name who said
        // something. So a turn that reports work — a status, a summary, a handover — recounts
        // what several participants did, and read under the rule that a message belongs to
        // whoever is marked on it, every action in it is credited to the sender. The same holds
        // a finding's party, which is the author of the message it was read out of and not
        // always the participant whose words it restates.
        //
        // Nothing downstream sees it. The sentence is fluent, the citation resolves, and the
        // grounding gate asks whether a cited span was retrieved rather than whether that span
        // says what the sentence says — so the prompt is the only rail, which is why this is
        // pinned despite being the weak kind of assertion.
        val system = assembled(FakeRetrieval()).collapsed()

        assertTrue(
            system.contains("neither names who did a piece of work"),
            "nothing separates who wrote a turn from who did what it reports, so a status " +
                "written by one participant credits them with everyone's work: $system",
        )
        // **The exemption, and it is the half that keeps the prompt obeyable.** `obliges` is the
        // one marker that does name an actor, and the paragraph above tells the model to read
        // direction off it. Stated over every marker alike, this rule would deny that paragraph
        // and the one below it — which asks for the party each undertaking is placed on — and a
        // prompt holding a rule and its contradiction is resolved by the model picking one.
        assertTrue(
            system.contains("\"Obliges\" is the exception"),
            "the rule is stated over every marker, so it contradicts the direction rule above " +
                "it and the party rule below it: $system",
        )
        assertTrue(
            system.contains("who did work a message reports as already done"),
            "the rule does not say which actor nothing marks, so it reads as denying that any " +
                "marker names one: $system",
        )
        assertTrue(
            system.contains("for work reported as done, take the actor from what the sentence"),
            "the remedy is unscoped, so it reaches an undertaking too — where a marker does " +
                "name the party and the prompt asks for it: $system",
        )
        // The unnamed case, which is the one a rule stating only the happy path sends back to the
        // mark: a turn reporting that something was done without saying by whom is the ordinary
        // shape of a status line.
        assertTrue(
            system.contains("report the work without saying who did it"),
            "a turn naming no actor leaves the mark as the only name in front of the model, " +
                "which is the attribution this rule exists to refuse: $system",
        )
        // Compression, named separately: it is where the error survives a reading, because the
        // clauses that would have carried the second participant are what compressing drops.
        assertTrue(
            system.contains("each part of it keeps the participant its own message named"),
            "nothing holds attribution through the compression, so an episode two people " +
                "worked on becomes one person's as soon as it is written as one sentence: $system",
        )
        // The negative half, and the reason it is here rather than left implied: this rule and
        // the one above it are about the same markers, and a wording that denied the first would
        // read as a correction of it. Both have to be true at once — the mark is who wrote the
        // turn, and the actor is read from the sentence.
        assertTrue(
            system.contains("attribute a message to the person marked on it and to nobody else"),
            "the new rule displaced the one saying whose words a turn carries, so a room's " +
                "other participants' turns are readable as the subject's again: $system",
        )
        // The other two this rule sits between, asserted here rather than left to their own
        // cases: those assert the rules exist, and what is asserted here is that *this* rule did
        // not displace them. Both were contradicted by an earlier wording of it, and a prompt
        // carrying a rule and its denial is obeyed by whichever the model reaches for.
        assertTrue(
            system.contains("read which party the thing is on from \"obliges\" or from the claim"),
            "the direction rule is gone, so the marker naming an actor is unreachable: $system",
        )
        assertTrue(
            system.contains("Name the party each undertaking you report is placed on"),
            "the rule asking who an undertaking is on is gone, which is what an unscoped " +
                "actor rule displaces: $system",
        )
    }

    @Test
    fun `the instruction reports a question the material answers, rather than relaying it`(): Unit = runBlocking {
        // **A finding is a reading of one message and knows nothing of what came after it.** So a
        // finding whose own content is something to be worked out — who did a thing, which option
        // to take — is relayed as a question even where a later turn in the same thread settles
        // it, and the reader is sent to chase work the record shows is done.
        //
        // **The closure rule cannot reach this and never fires on it.** A closure is a marked
        // message reporting a promise done; nothing marks the turn that answers a question, so no
        // closure is recorded, the item is labelled as having no recorded outcome, and every rule
        // about outcomes passes it through intact. That is why this is stated in its own terms.
        val system = assembled(
            FakeRetrieval(commitments = listOf(FakeRetrieval.commitment("send the invoice", "2"))),
        ).collapsed()

        assertTrue(
            system.contains("Where a later message in the material settles it"),
            "nothing tells the model the thread may answer what a finding leaves open: $system",
        )
        assertTrue(
            system.contains("report what was settled and cite that message"),
            "the rule names the case and does not say what to write, so the answer is as likely " +
                "to be dropped as the question: $system",
        )
        // Told apart from the closure rule in the prompt itself, not only here: the two are a
        // paragraph apart and both are about something later settling something earlier, and a
        // model reading them together would look for a marker that is not there.
        assertTrue(
            system.contains("This is not the closing message a commitment carries"),
            "the two settling rules are not distinguished, so this one reads as asking for a " +
                "marker no such turn carries: $system",
        )
        // The enumeration this sits beside is closed — "there are two answers" — so the rule
        // has to say it adds no third, or the two are a contradiction the model resolves by
        // picking one.
        assertTrue(
            system.contains("leaves what the record holds about an outcome exactly as the two answers above put it"),
            "the rule adds a third disposition beside a closed enumeration of two, and says " +
                "nothing to reconcile them: $system",
        )
        // The other direction, and the uncertain case with it. A rule stating only the answered
        // case is an invitation: with nothing settling the question — or with the model merely
        // unsure whether anything does — what is reported is what was said.
        assertTrue(
            system.contains("where you cannot tell whether one does, report what was said"),
            "nothing bounds the rule, so a model that wrongly believes a turn settles " +
                "something reports an answer the record does not hold: $system",
        )
        // The negative half, against the shared rule this sits closest to. That one lets a
        // question a source itself calls open keep its source's word; asking here for a *state*
        // rather than for the answer would set the two against each other, which is a conflict
        // a prompt cannot be obeyed under.
        listOf("report it as open", "still to be answered", "awaiting an answer").forEach { asked ->
            assertFalse(
                system.contains(asked, ignoreCase = true),
                "the rule asks for \"$asked\", which gives an unsettled question a state the " +
                    "shared caution forbids: $system",
            )
        }
    }

    @Test
    fun `an event names its party too, not only a commitment`(): Unit = runBlocking {
        // Both kinds carry `statedBy` and both are rendered by the same object, so a fix applied to one
        // of them would pass a commitment-only test while leaving every event's party unreachable.
        val party = EntityId.mint()
        val retrieval = FakeRetrieval(
            ahead = listOf(FakeRetrieval.event("the review call", evidencedBy = "3", statedBy = party)),
            names = mapOf(party to "A Named Person"),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact()).single().text

        assertTrue(text.contains("Inferred event"))
        assertTrue(text.contains("A Named Person"), "an event's party is not named: $text")
    }

    @Test
    fun `a party the corpus never named is left unnamed rather than given a placeholder`(): Unit = runBlocking {
        // The other error direction, and the one a test asserting only the happy case would miss. A
        // placeholder would be read as the party's name by the one thing that cannot check it.
        val party = EntityId.mint()
        val retrieval = FakeRetrieval(
            commitments = listOf(
                FakeRetrieval.commitment("send the invoice", evidencedBy = "2", statedBy = party),
            ),
            names = emptyMap(),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact()).single().text

        assertTrue(text.contains("send the invoice"), "the claim itself must survive an unnamed party: $text")
        assertTrue(!text.contains("stated by"), "an unnamed party must say nothing, not say nobody: $text")
    }

    @Test
    fun `the party read asks only for the parties the findings reached`(): Unit = runBlocking {
        // A read that asked for more than the findings reached would be reading the corpus rather than
        // naming what is in hand, and the bound on this read is entirely that of its input.
        val stated = EntityId.mint()
        val upcoming = EntityId.mint()
        val retrieval = FakeRetrieval(
            commitments = listOf(
                FakeRetrieval.commitment("send the invoice", evidencedBy = "2", statedBy = stated),
                // A second finding by the same party: one name is wanted, not one per row.
                FakeRetrieval.commitment("chase the contract", evidencedBy = "4", statedBy = stated),
            ),
            ahead = listOf(FakeRetrieval.event("the review call", evidencedBy = "3", statedBy = upcoming)),
            // One finding with no party at all, which must not be asked about.
            past = listOf(FakeRetrieval.event("the first call", evidencedBy = "5", statedBy = null)),
        )

        retrieve(retrieval, FakeRetrieval.aContact())

        val asked = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Names>().single().parties
        assertEquals(setOf(stated, upcoming), asked.toSet(), "the wrong parties were asked about")
        assertEquals(asked.size, asked.distinct().size, "the same party was asked about twice")
    }

    @Test
    fun `an attribute is answerable at all`(): Unit = runBlocking {
        // The whole of the first finding: recognition writes statements and, before this, nothing
        // queried them — the predicate vocabulary, the validity windows and the contradiction
        // resolution that opens and closes them were all written and never read.
        val retrieval = FakeRetrieval(
            statements = listOf(FakeRetrieval.statement("works at", "Acme", evidencedBy = "7")),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact()).single().text

        assertTrue(text.contains("works at"), "the predicate is not in the material: $text")
        assertTrue(text.contains("Acme"), "the value is not in the material: $text")
    }

    @Test
    fun `an attribute carries the period it held over`(): Unit = runBlocking {
        // The defence against the one failure this read can produce: a value taken from the wrong
        // stretch of time is a real fact with real evidence, so it cites correctly and the grounding
        // check passes it. An answer fluent, specific and wrong about *when* is only preventable by
        // putting the window in front of the model.
        val opened = Instant.parse("2026-03-01T00:00:00Z")
        val closed = Instant.parse("2026-07-01T00:00:00Z")
        val retrieval = FakeRetrieval(
            statements = listOf(
                FakeRetrieval.statement("works at", "Acme", evidencedBy = "7", from = opened, until = closed),
            ),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact()).single().text

        assertTrue(text.contains("$opened"), "the window's start is not in the material: $text")
        assertTrue(text.contains("$closed"), "the window's end is not in the material: $text")
    }

    @Test
    fun `an open window is not reported as true now`(): Unit = runBlocking {
        // Nothing closes a window except a later contradicting statement, so a fact unmentioned for a
        // year still has an open one. Rendering that as the present tense would assert something the
        // store never claimed — the same error as the wrong window, in a smaller place.
        val retrieval = FakeRetrieval(
            statements = listOf(FakeRetrieval.statement("is based in", "Tbilisi", evidencedBy = "7")),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact()).single().text

        assertTrue(text.contains("not since contradicted"), "an open window overclaims: $text")
        assertTrue(text.contains("${FakeRetrieval.WHEN}"), "an open window still states where it began: $text")
    }

    @Test
    fun `an attribute's strength is its origin rather than a hedge`(): Unit = runBlocking {
        // A model's reading of hedged text is the weakest thing in the entity package and is never
        // shown as a bare fact; a profile field and a human's correction are stronger. One wording for
        // all three would let a stored field be reported exactly as confidently as a guess.
        val inferred = FakeRetrieval(
            statements = listOf(FakeRetrieval.statement("charges", "a rate", evidencedBy = "7")),
        )
        val confirmed = FakeRetrieval(
            statements = listOf(
                FakeRetrieval.statement("charges", "a rate", evidencedBy = "7", origin = Origin.CONFIRMED),
            ),
        )

        val weak = retrieve(inferred, FakeRetrieval.aContact()).single().text
        val strong = retrieve(confirmed, FakeRetrieval.aContact()).single().text

        assertTrue(weak.contains("Inferred"), "a model's reading must be marked as one: $weak")
        assertTrue(!strong.contains("Inferred"), "a confirmed fact must not read as inferred: $strong")
        assertTrue(weak != strong, "the three origins are rendered identically, so strength is lost")
    }

    @Test
    fun `an attribute is read without a clock, unlike an event`(): Unit = runBlocking {
        // The two event reads are filtered on a horizon; this one must not be. Filtered to what holds
        // now, the composition would get a bare value with no way to say as of when, and no question
        // about what used to hold could be answered at all.
        val retrieval = FakeRetrieval()

        retrieve(retrieval, FakeRetrieval.aContact())

        val asked = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Statements>().single()
        assertIs<Reach.ByContacts>(asked.reach, "a contact's attributes are reached by contact")
        assertEquals(
            2,
            retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Events>().size,
            "the event reads are the horizon-bounded ones and must stay that way",
        )
    }

    @Test
    fun `a conversation reaches no attribute, because an attribute is about a person`(): Unit = runBlocking {
        // A client is reached through its conversations, and every statement stated in a thread is
        // about whoever spoke rather than about the client the thread stands for. Read by conversation,
        // a question about a client would be answered with facts about its participants.
        val retrieval = FakeRetrieval(conversations = listOf(FakeRetrieval.message("1", "x").externalId))

        retrieve(retrieval, FakeRetrieval.aClient())

        val asked = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Statements>().single()
        assertIs<Reach.InConversations>(
            asked.reach,
            "a client must still reach the read, so the port is what declines it rather than the shape",
        )
    }

    @Test
    fun `an inferred thing cites the message it was read out of`(): Unit = runBlocking {
        // Which is the whole point of the derived layer carrying its evidence: the citation turns an
        // inference back into something the reader can check.
        val retrieval = FakeRetrieval(
            commitments = listOf(FakeRetrieval.commitment("send the invoice", evidencedBy = "88")),
        )

        assertEquals(
            FakeRetrieval.evidence("88").span,
            retrieve(retrieval, FakeRetrieval.aContact()).single().id,
        )
    }

    @Test
    fun `the inbox is the one subject that reads across everything`(): Unit = runBlocking {
        val retrieval = FakeRetrieval(
            commitments = listOf(FakeRetrieval.commitment("something outstanding", evidencedBy = "2")),
        )

        retrieve(retrieval, FakeRetrieval.theInbox())

        // Being unscoped has to be named. Nothing else may reach it.
        assertEquals(listOf(Reach.Everything), reaches(retrieval))
    }

    @Test
    fun `a client reads only what was stated in its own conversations`(): Unit = runBlocking {
        // The defect this replaces: a client has no contact behind it, and an empty contact list used
        // to mean the whole corpus — so one client's status was answered from every client's
        // commitments, each citation resolving correctly and the grounding check passing.
        val thread = FakeRetrieval.message("1", "x").externalId
        val retrieval = FakeRetrieval(conversations = listOf(thread))

        retrieve(retrieval, FakeRetrieval.aClient())

        assertEquals(listOf(Reach.InConversations(listOf(thread))), reaches(retrieval))
    }

    @Test
    fun `a conversation reads only what was stated in itself`(): Unit = runBlocking {
        val thread = FakeRetrieval.message("1", "x").externalId
        val retrieval = FakeRetrieval(conversations = listOf(thread))

        retrieve(retrieval, FakeRetrieval.aConversation())

        assertEquals(listOf(Reach.InConversations(listOf(thread))), reaches(retrieval))
    }

    @Test
    fun `a subject that reaches no conversation reads nothing, rather than everything`(): Unit = runBlocking {
        // A client whose thread holds no message must degrade to an empty answer, never widen to the
        // corpus. `Reach.InConversations(empty)` is what the port reads as nothing.
        val retrieval = FakeRetrieval(conversations = emptyList())

        retrieve(retrieval, FakeRetrieval.aClient())

        assertEquals(listOf(Reach.InConversations(emptyList())), reaches(retrieval))
    }

    @Test
    fun `a contact is read by who stated a thing`(): Unit = runBlocking {
        val subject = FakeRetrieval.aContact()
        val retrieval = FakeRetrieval(conversations = listOf(FakeRetrieval.message("1", "x").externalId))

        retrieve(retrieval, subject)

        assertEquals(listOf(Reach.ByContacts(subject.contacts())), reaches(retrieval))
    }

    @Test
    fun `events ahead and events past are read under separate bounds`(): Unit = runBlocking {
        // One read over a window spanning now would spend its whole limit on whichever side is
        // busier: ordered ascending, a full diary last month returns nothing upcoming at all.
        val retrieval = FakeRetrieval()

        retrieve(retrieval, FakeRetrieval.aContact())

        val events = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Events>()
        assertEquals(2, events.size)
        val ahead = events.single { it.ahead }
        val past = events.single { !it.ahead }
        assertEquals(NOW, ahead.horizon.from, "the upcoming read must not spend its limit on the past")
        assertTrue(ahead.horizon.until.isAfter(NOW))
        assertTrue(past.horizon.from.isBefore(NOW))
        assertEquals(NOW, past.horizon.until, "the past read must not reach into the future")
    }

    @Test
    fun `an event with no moment is read, and not left reachable through nothing`(): Unit = runBlocking {
        // Both dated reads select on when the event happens, so an event carrying no moment
        // satisfies neither bound: recognised, stored, evidenced, and in no answer. The sweep this
        // was filed off had such events.
        val retrieval = FakeRetrieval(
            undated = listOf(FakeRetrieval.event("the review call", evidencedBy = "3", occursAt = null)),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact()).single().text

        assertTrue(text.contains("the review call"), "an event with no moment reaches no answer: $text")
    }

    @Test
    fun `an event with no moment is read without a window of any kind`(): Unit = runBlocking {
        // Admitted to either dated window it would be offered as having happened inside it — a
        // wrong-window answer that cites correctly and so passes the grounding check. The port
        // signature is what makes a horizon unpassable here; what this adds is that the read is
        // bounded and that it is a third read rather than one of the two dated ones repurposed.
        val retrieval = FakeRetrieval()

        retrieve(retrieval, FakeRetrieval.aContact())

        val asked = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.UndatedEvents>().single()
        assertTrue(asked.limit > 0, "the undated read must be bounded like every other: $asked")
        assertEquals(
            2,
            retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Events>().size,
            "the undated read must be beside the two dated ones rather than replacing one of them",
        )
    }

    @Test
    fun `a commitment with no due date says so, and a dated one shows its date`(): Unit = runBlocking {
        // The same omission as for an event, on the kind this change makes undated far more often:
        // recognition refuses a date at the message's own instant, and one before it wherever
        // nothing establishes that another party owes the thing, so a span that silently carried
        // nothing would leave the model to place the promise by when it was mentioned. Both
        // directions, because a marker printed either way says nothing.
        val undated = FakeRetrieval(commitments = listOf(FakeRetrieval.commitment("send the invoice", "2")))
        val dated = FakeRetrieval(
            commitments = listOf(
                FakeRetrieval.commitment("send the invoice", "2", due = FakeRetrieval.WHEN.plusSeconds(86_400)),
            ),
        )

        val undatedText = retrieve(undated, FakeRetrieval.aContact()).single().text
        val datedText = retrieve(dated, FakeRetrieval.aContact()).single().text

        assertTrue(
            undatedText.contains("no date"),
            "a commitment nothing dated must say so rather than omitting the date: $undatedText",
        )
        assertTrue(datedText.contains("due"), "a dated commitment lost its due date: $datedText")
        assertFalse(
            datedText.contains("no date"),
            "a dated commitment is marked as undated, so the marker discriminates nothing: $datedText",
        )
    }

    @Test
    fun `an event with no moment says so, rather than carrying nothing where a date would be`(): Unit = runBlocking {
        // The failure this closes is by omission: an event span with no date reads as one whose date
        // was not worth repeating, and the model then places it by the only other date in front of
        // it — when it was mentioned.
        val undated = FakeRetrieval(
            undated = listOf(FakeRetrieval.event("the review call", evidencedBy = "3", occursAt = null)),
        )
        val dated = FakeRetrieval(
            ahead = listOf(FakeRetrieval.event("the review call", evidencedBy = "3")),
        )

        val undatedText = retrieve(undated, FakeRetrieval.aContact()).single().text
        val datedText = retrieve(dated, FakeRetrieval.aContact()).single().text

        assertTrue(
            undatedText.contains("no date"),
            "an event with no moment must say the date is absent, not leave the reader to supply " +
                "one: $undatedText",
        )
        assertFalse(
            datedText.contains("no date"),
            "a dated event must not be marked as undated, or the marker says nothing: $datedText",
        )
    }

    @Test
    fun `the instruction reads direction from its marker or the claim, not from the stater`(): Unit = runBlocking {
        // **The caution that stops the two parties being swapped**, and it was held by nothing. The
        // party rendered beside a claim is whoever stated the thing, which for an obligation one
        // participant states about another is not who owes it — so a model reading direction off
        // that marker is confidently wrong on exactly the material the distinction exists for.
        //
        // Quoted, because this rule and its reversal are the same words rearranged: naming both
        // halves is what makes the check fail for an instruction that tells the model to do the
        // swap. The absolute it must not claim is asserted too — direction is recorded in a column
        // now, and an instruction saying it is held nowhere else is false of the build even where
        // it is true of the material this shape is handed.
        //
        // **There are two markers to tell apart rather than a marker and a prose fallback**, which
        // is a sharper version of the same failure: the material names the party a claim obliges
        // beside the one that names who stated the thing, so a model reading direction off the
        // wrong one has a name to put on it and will. Both marker names are asserted, and so is
        // the fallback for a row carrying none — whether a row carries one is a property of
        // `obliged_party`, and an instruction that dropped the fallback would read as though
        // direction were always marked.
        val system = assembled(
            FakeRetrieval(commitments = listOf(FakeRetrieval.commitment("send the invoice", "2"))),
        ).collapsed()

        assertTrue(
            system.contains("Which party a commitment places the thing on is marked on it as \"obliges\""),
            "nothing tells the model which marker carries direction, so the two are swappable: $system",
        )
        assertTrue(
            system.contains("where it carries no such marker it is in the claim's own words"),
            "the rule reads as though direction were always marked, and on the ordinary row — " +
                "which carries none — it then has nothing to fall back to: $system",
        )
        assertTrue(
            system.contains("The party marked as \"stated by\" is whoever stated the thing"),
            "the other marker is unnamed, so the one carrying direction is identified by nothing " +
                "a model can see in the material: $system",
        )
        assertTrue(
            system.contains("never swap the two"),
            "the rule names the two parties and does not forbid exchanging them: $system",
        )
        // Scoped to this clause and not to the phrase, which the citation rule uses for something
        // else entirely — a bare search for it fails on an instruction that is working.
        assertFalse(
            system.contains("claim's own words and nowhere else"),
            "the instruction claims direction is recorded nowhere else, which the store now " +
                "contradicts: $system",
        )
    }

    @Test
    fun `this shape's prompt carries the caution, and asks for nothing it forbids`(): Unit = runBlocking {
        // The caution itself is `Reply.STATE` and reaches every shape, which is why `ShapesTest`
        // holds it over all five. What is held *here* is the half that is this shape's own: that
        // the sentence survives into the prompt this shape actually assembles, and that none of
        // this shape's own prose asks for the reading it forbids. `Retrieval.commitmentsOf`
        // documents that none of what it returns is known to be outstanding, recency being the
        // only proxy, so a prompt reaching the model without the caution puts every commitment in
        // front of it as a live obligation.
        //
        // **Read off the assembled system rather than the shape's constant, deliberately.** The
        // sentence moved to the shared format and this case went on passing — which is correct,
        // because what matters is what the model is handed, and `assembled()` is instruction plus
        // format. A case reading `INSTRUCTION` alone would now fail while the model was told
        // exactly what it needed to be.
        //
        // Quoted rather than paraphrased, because a rule and its reversal are the same words in a
        // summary: "report what is outstanding" and "do not report what is outstanding" both
        // mention state, and a check that only looked for the subject would pass either.
        val system = assembled(
            FakeRetrieval(commitments = listOf(FakeRetrieval.commitment("send the invoice", "2"))),
        )

        assertTrue(
            system.collapsed().contains("An outcome that is not recorded is not an outstanding commitment."),
            "the state caution is absent, so a commitment with no recorded outcome reads as open: $system",
        )
        // The negative half, and the reason it is here: the framing line asked for "what is
        // outstanding" while the caution forbids reporting anything as outstanding. Left in place,
        // the two are a conflict the model resolves by choosing one, which is how a prompt that
        // cannot be obeyed as written fails — it does not fail loudly.
        //
        // **Every wording the caution forbids, not the one it was first written against.** Keyed on
        // a single phrase, this passes an instruction that asks for the same reading in another
        // word — which is how the conflict came back, as a section asking for *what is owed* beside
        // a caution forbidding *still owed*. The list is the caution's own vocabulary, read against
        // the collapsed prompt so that a wording broken over a line is not a wording this misses.
        listOf(
            "what is outstanding",
            "what is owed",
            "what is still owed",
            "what is open",
            "what is pending",
        ).forEach { asked ->
            assertFalse(
                system.collapsed().contains(asked, ignoreCase = true),
                "the instruction asks for \"$asked\", which the caution beside it forbids reporting: $system",
            )
        }
    }

    @Test
    fun `a promise is offered with the turn it was read out of`(): Unit = runBlocking {
        // **The one fact a claim cannot carry is how the thing was said.** A claim is restated to
        // stand on its own, and the restating stage is asked for a sentence naming who owes what to
        // whom — so something one party merely put to another arrives here as a flat assertion that
        // both will do it. The A/B behind that is in the pull request.
        //
        // Asserted as the turn's own words reaching the material, not as a call having been made:
        // the shape could widen and drop the result, and the model reads the text.
        //
        // **By line count as well as by content**, because a finding is addressed by the span it
        // was read out of: the commitment's own block answers to that identifier, so a case that
        // only asked whether a block with that identifier existed, and whether it carried the
        // claim, would pass with the widening removed entirely and report that the turn was
        // missing.
        val party = EntityId.mint()
        val commitment = FakeRetrieval.commitment(
            "the parties will meet on Saturday",
            evidencedBy = "2",
            due = dueAhead(2),
        )
        val index = FakeIndex(
            around = mapOf(
                FakeRetrieval.evidence("2").span to listOf(span("2", "shall we meet on Saturday?")),
            ),
        )

        val retrieved = retrieve(
            FakeRetrieval(
                commitments = listOf(commitment),
                turns = mapOf(FakeRetrieval.record("2") to FakeRetrieval.turn(author = "A Named Person")),
                names = mapOf(party to "A Named Person"),
            ),
            FakeRetrieval.aContact(),
            index = index,
        )

        val block = assertNotNull(
            retrieved.singleOrNull { it.id == FakeRetrieval.evidence("2").span },
            "nothing in the material answers to the span the promise was read out of: $retrieved",
        ).text
        // The turn, then what was read out of it, in that order and as two lines of one block.
        // The count is what discriminates: with nothing widened the block is the claim alone.
        assertEquals(
            listOf(
                "Sent by A Named Person: shall we meet on Saturday?",
                "Inferred commitment (due 2026-10-07 Wed 00:00 Z — ahead), outcome not recorded, " +
                    "last stated 4 days ago: the parties will meet on Saturday",
            ),
            block.lines(),
            "the turn the promise was read out of is not offered in front of the claim, so " +
                "nothing says the thing was put as a question rather than undertaken",
        )
    }

    @Test
    fun `the turns after a promise are offered, so a proposal's answer is in the material`(): Unit = runBlocking {
        // **The half of this that is not about the speech act.** Something put to somebody is taken
        // up or turned down in the exchange straight after it, and the finding knows nothing of what
        // followed — so a reply carried the proposal and not the refusal, and a reader was told
        // about an arrangement that was called off within a day. The refusal has to be in the
        // material to be citable at all: the grounding gate asks whether a cited span was
        // retrieved, so a claim about it would be refused however right it was.
        val commitment = FakeRetrieval.commitment("the parties will meet on Saturday", evidencedBy = "2")
        val index = FakeIndex(
            around = mapOf(
                FakeRetrieval.evidence("2").span to listOf(
                    span("2", "shall we meet on Saturday?"),
                    span("3", "let me look at my week"),
                    span("4", "checking with the others"),
                    // Three turns out, which is what the window reaches and the one before it
                    // did not: the figure is set from how far the other party's answer usually is.
                    span("5", "I am away from Friday evening, Saturday is out"),
                ),
            ),
        )

        val retrieved = retrieve(
            FakeRetrieval(commitments = listOf(commitment)),
            FakeRetrieval.aContact(),
            index = index,
        )

        assertTrue(
            retrieved.any { it.text.contains("Saturday is out") },
            "the turn that answers the proposal is not in the material, so a reply reporting the " +
                "refusal could not cite it: $retrieved",
        )
        // Forward only, and the window asked for is the one the shape declares. A read looking both
        // ways would pull in what the speaker was answering, which is a different question and is
        // what the fallback's widening is for.
        // Written out rather than read off `FOLLOWING`, so a change to the bound reddens here and
        // is decided again rather than absorbed.
        assertEquals(
            listOf(FakeIndex.Widening(FakeRetrieval.evidence("2").span, before = 0, after = 3)),
            index.asked,
            "the widening is not the forward-only window this shape asks for",
        )
    }

    @Test
    fun `a promise the corpus closed is not widened`(): Unit = runBlocking {
        // **The bound that keeps this affordable, and it is not arbitrary.** A commitment recorded
        // as discharged already carries the turn that discharged it, so widening it would buy a
        // second account of a question the closure read has answered. Up to thirty promises are
        // retrieved and each widening is an index read, so the ones that need nothing are worth
        // not asking about.
        val closed = FakeRetrieval.commitment("send the invoice", evidencedBy = "2")
        val open = FakeRetrieval.commitment("confirm the figures", evidencedBy = "3")
        val index = FakeIndex()

        retrieve(
            FakeRetrieval(
                commitments = listOf(closed, open),
                closures = mapOf(closed.id to FakeRetrieval.message("9", "invoice is away")),
            ),
            FakeRetrieval.aContact(),
            index = index,
        )

        assertEquals(
            listOf(FakeRetrieval.evidence("3").span),
            index.widened,
            "the closed promise was widened too, or the open one was not",
        )
    }

    @Test
    fun `the widened turns name their authors, and one neither party wrote is marked`(): Unit = runBlocking {
        // **A widened turn is read as the subject's own words unless something says otherwise**, and
        // here that is the whole point: the turn that turns a proposal down is frequently written by
        // another participant of a room, so an unmarked one reads as the subject refusing their own
        // proposal. The name read has to be asked about these records as well as about the tail —
        // which is a thing a shape can forget, since the tail read is what used to decide its
        // argument.
        val commitment = FakeRetrieval.commitment("the parties will meet on Saturday", evidencedBy = "2")
        val refusal = span("4", "Saturday is out, I am away")
        val index = FakeIndex(around = mapOf(FakeRetrieval.evidence("2").span to listOf(refusal)))
        val retrieval = FakeRetrieval(
            commitments = listOf(commitment),
            turns = mapOf(
                FakeRetrieval.record("4") to FakeRetrieval.turn(author = "A Third Person", anotherParty = true),
            ),
        )

        val retrieved = retrieve(retrieval, FakeRetrieval.aContact(), index = index)

        val block = retrieved.single { it.text.contains("Saturday is out") }.text
        assertTrue(block.contains("Sent by A Third Person"), "a widened turn carries no author: $block")
        assertTrue(block.contains(MARK), "a widened turn by neither party is unmarked: $block")
        // Asserted on the read itself, because the rendering above would pass with the record asked
        // about by accident — the tail read's argument is a different list.
        assertTrue(
            retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Turns>()
                .any { FakeRetrieval.record("4") in it.messages },
            "the name read was not asked about the widened turns: ${retrieval.asked}",
        )
    }

    @Test
    fun `the inbox widens too, which is the one subject that reads no thread`(): Unit =
        runBlocking {
            // **The shape the defect was measured on, and the daily question.** Asked naming
            // nobody there is no thread to narrow to, so the tail read does not happen at all: a
            // message reaches this answer through a finding or not at all. A closing turn is the
            // other such route and reaches only a promise something discharged, so for a promise
            // nothing closed this is the one route its own words have.
            val commitment = FakeRetrieval.commitment("the parties will meet on Saturday", evidencedBy = "2")
            val index = FakeIndex(
                around = mapOf(
                    FakeRetrieval.evidence("2").span to listOf(span("2", "shall we meet on Saturday?")),
                ),
            )

            val retrieved = retrieve(
                FakeRetrieval(commitments = listOf(commitment)),
                FakeRetrieval.theInbox(),
                index = index,
            )

            assertTrue(
                retrieved.any { it.text.contains("shall we meet on Saturday?") },
                "the inbox reads no tail, so without the widening its material is claims alone: $retrieved",
            )
        }

    @Test
    fun `a turn the index does not hold leaves the claim standing alone`(): Unit = runBlocking {
        // The graceful half. An unindexed message is one this read cannot reach — a cold index, or
        // a record withdrawn since — and the claim still stands on its own, which is what it was
        // restated for. Asserted because the alternative a careless implementation reaches for is
        // to fail the request.
        val commitment = FakeRetrieval.commitment("send the invoice", evidencedBy = "2")

        val retrieved = retrieve(
            FakeRetrieval(commitments = listOf(commitment)),
            FakeRetrieval.aContact(),
            index = FakeIndex(),
        )

        assertEquals(
            "Inferred commitment (no date stated), outcome not recorded, last stated 4 days ago: send the invoice",
            retrieved.single().text,
            "an unreachable turn cost the claim its own block",
        )
    }

    @Test
    fun `a turn the tail already read keeps the live copy, not the index's`(): Unit = runBlocking {
        // **Two readings of one message are not two facts, and the index's is the worse copy.** The
        // tail reads the message row; the index holds whatever the last pass wrote, so an edited
        // message whose re-index has not landed differs between them. `Material.gathered` joins
        // texts that differ rather than choosing — which is right for a record and a finding read
        // out of it, and wrong here — so one identifier would carry a stale body and the current
        // one, and a claim citing it could be resolved to neither. `Material.distinct` is where
        // that reasoning lives.
        val live = FakeRetrieval.message("2", "the figures will be confirmed on Friday")
        val index = FakeIndex(
            around = mapOf(
                FakeRetrieval.evidence("2").span to listOf(
                    span("2", "the figures will be confirmed on Thursday"),
                    span("3", "Friday suits us better"),
                ),
            ),
        )
        val retrieval = FakeRetrieval(
            conversations = listOf(live.externalId),
            messages = listOf(live),
            commitments = listOf(FakeRetrieval.commitment("the figures will be confirmed", evidencedBy = "2")),
        )

        val retrieved = retrieve(retrieval, FakeRetrieval.aContact(), index = index)

        val block = retrieved.single { it.id == FakeRetrieval.evidence("2").span }.text
        assertTrue(block.contains("on Friday"), "the live body is gone: $block")
        assertFalse(
            block.contains("on Thursday"),
            "the index's stale body was joined to the live one, so one citable identifier carries " +
                "both: $block",
        )
        // The turns around it are still offered — what is dropped is the duplicate reading of a
        // record this stage had already read, not the widening.
        assertTrue(
            retrieved.any { it.text.contains("Friday suits us better") },
            "dropping the overlap dropped the window with it: $retrieved",
        )
    }

    @Test
    fun `a restated promise is widened at both ends, not only where it was first said`(): Unit = runBlocking {
        // **A finding carries every span that states it, and the two ends answer different
        // questions.** The first is where the thing was put; the last is where it was said again,
        // and a proposal restated is answered after the restatement rather than after the original.
        // `firstStated` is the span a finding is shown under, and its own reasoning for being the
        // only one is that a restatement is reached through its own message, which the answer path
        // retrieves on its own account — which is the tail read, and the inbox has none.
        //
        // Asserted on the asks as well as the material, because the span the finding is *shown*
        // under is the first one either way: a case reading only the rendered block could not tell
        // a shape that widened both ends from one that widened the first.
        val restated = FakeRetrieval.commitment("the figures will be confirmed", evidencedBy = "2").let {
            it.copy(
                evidence = setOf(
                    FakeRetrieval.evidence("2"),
                    Evidence(
                        span = FakeRetrieval.evidence("8").span,
                        sourceTime = FakeRetrieval.WHEN.plusSeconds(3600),
                    ),
                ),
            )
        }
        val index = FakeIndex(
            around = mapOf(
                FakeRetrieval.evidence("2").span to listOf(span("2", "can you confirm the figures?")),
                FakeRetrieval.evidence("8").span to listOf(
                    span("8", "still need the figures confirmed"),
                    span("9", "confirmed them this morning"),
                ),
            ),
        )

        val retrieved = retrieve(
            FakeRetrieval(commitments = listOf(restated)),
            FakeRetrieval.theInbox(),
            index = index,
        )

        assertEquals(
            listOf(FakeRetrieval.evidence("2").span, FakeRetrieval.evidence("8").span),
            index.widened,
            "the restatement's own span was not widened, so what answered it is unreachable",
        )
        assertTrue(
            retrieved.any { it.text.contains("confirmed them this morning") },
            "the turn answering the restatement is not in the material: $retrieved",
        )
    }

    @Test
    fun `two promises read out of one message widen it once`(): Unit = runBlocking {
        // Two obligations stated in one message are two commitments sharing one evidencing span —
        // see `Commitment`, where that is the reason the claim rather than the span is the content
        // key. So the spans this read is handed are not distinct, and asking the index twice for
        // one of them is a read bought and thrown away. Asserted on the asks rather than on the
        // material, because the fold downstream hides it: the second answer is identical, so the
        // prompt is right either way and only the count of reads moves.
        val index = FakeIndex(
            around = mapOf(FakeRetrieval.evidence("2").span to listOf(span("2", "I will send both"))),
        )
        val retrieval = FakeRetrieval(
            commitments = listOf(
                FakeRetrieval.commitment("the invoice will be sent", evidencedBy = "2"),
                FakeRetrieval.commitment("the figures will be confirmed", evidencedBy = "2"),
            ),
        )

        retrieve(retrieval, FakeRetrieval.aContact(), index = index)

        assertEquals(
            listOf(FakeRetrieval.evidence("2").span),
            index.widened,
            "one message evidencing two promises was widened twice",
        )
    }

    @Test
    fun `two promises in one thread widen into each other without offering a turn twice`(): Unit = runBlocking {
        // Promises cluster where the talking is, so two of them a few turns apart widen over the
        // same stretch, and one turn comes back from both reads. What keeps the prompt from
        // carrying it under two numbers — a reader meeting the same material twice, and a citation
        // with two homes — is that material is gathered by span.
        //
        // **What this does not hold is the read's own de-duplication, and that is measured rather
        // than assumed.** Removing the `distinctBy` in `Lookup.following` leaves this green,
        // because the fold collapses the pair afterwards either way. It is kept there so the read
        // answers honestly to any caller, and the rail under this assertion is the fold.
        val shared = span("3", "that works for me")
        val index = FakeIndex(
            around = mapOf(
                FakeRetrieval.evidence("2").span to listOf(span("2", "shall we meet?"), shared),
                FakeRetrieval.evidence("4").span to listOf(span("4", "and the figures?"), shared),
            ),
        )
        val retrieval = FakeRetrieval(
            commitments = listOf(
                FakeRetrieval.commitment("the parties will meet", evidencedBy = "2"),
                FakeRetrieval.commitment("the figures will be confirmed", evidencedBy = "4"),
            ),
        )

        val retrieved = retrieve(retrieval, FakeRetrieval.aContact(), index = index)

        assertEquals(
            1,
            retrieved.count { it.text.contains("that works for me") },
            "a turn two promises reach is offered twice: $retrieved",
        )
    }

    @Test
    fun `a commitment the corpus closed is reported as closed, with the turn that closed it`(): Unit = runBlocking {
        // The acceptance check the closure pass exists for, and the citation is half of it: an
        // answer saying a promise was reported as done has to be able to point at the turn
        // reporting it, which means that turn has to be in the material and not merely named.
        val commitment = FakeRetrieval.commitment("send the invoice", evidencedBy = "2")
        val closing = FakeRetrieval.message("9", "invoice is away")
        val retrieval = FakeRetrieval(
            commitments = listOf(commitment),
            closures = mapOf(commitment.id to closing),
        )

        val retrieved = retrieve(retrieval, FakeRetrieval.aContact())

        val claim = retrieved.single { it.text.contains("send the invoice") }.text
        assertTrue(claim.contains("closed by the message sent"), "the closure does not reach the material: $claim")
        assertFalse(
            claim.contains("outcome not recorded"),
            "a closed commitment is also marked as having no recorded outcome: $claim",
        )
        val turn = assertNotNull(
            retrieved.singleOrNull { it.id == FakeRetrieval.evidence("9").span },
            "the closing turn is not in the material, so nothing can cite it: $retrieved",
        )
        assertTrue(turn.text.contains("invoice is away"), "the closing turn lost its own words: ${turn.text}")
        // **The pair has to be matchable from either side.** The material holds up to forty recent
        // turns, so a label saying only *a later message* leaves the model choosing among them — and
        // a citation of the wrong one passes the grounding check, which asks whether a cited span
        // was retrieved rather than whether it was the right span. The instant is what pairs them.
        assertTrue(
            turn.text.contains("closing a commitment"),
            "the closing turn is not marked, so it cannot be found among the others: ${turn.text}",
        )
        // The closing turn's own time, named the same way on both sides. Taken from the fixture
        // rather than parsed back out of either string, since a rendered time carries colons of
        // its own.
        //
        // **Said as the correspondents read a clock, not as the instant**, which is what lets it
        // agree with the heading the same turn is dated under — see `Moment`, and the case in
        // `StagesTest` that holds the two against each other. Calibrated below by putting the raw
        // instant through the same assertion: a green here has to mean the rendering changed and
        // not that the pattern matches anything.
        val said = Moment.written(FakeRetrieval.WHEN, ZoneOffset.UTC)
        assertTrue(
            claim.contains(said),
            "the commitment does not name when it was closed, so nothing pairs it with a turn: $claim",
        )
        assertTrue(
            turn.text.contains(said),
            "the closing turn does not name its own time the way the commitment does: ${turn.text}",
        )
        assertFalse(
            claim.contains("${FakeRetrieval.WHEN}"),
            "the closing time reached the material as a raw instant, which is the second " +
                "convention this reconciles: $claim",
        )
    }

    @Test
    fun `a commitment nothing closed says its outcome is unrecorded, and never that it is open`(): Unit = runBlocking {
        // The ceiling the card states: a promise is often kept in silence, so the absence of a
        // closing message cannot distinguish still-owed from quietly-done. The material is where
        // this has to be said, because a model told one thing in an instruction and shown another
        // in the material follows what it is shown.
        val retrieval = FakeRetrieval(
            commitments = listOf(FakeRetrieval.commitment("send the invoice", evidencedBy = "2")),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact()).single().text

        assertTrue(text.contains("outcome not recorded"), "the absent outcome is not said at all: $text")
        listOf("open", "outstanding", "still owed", "pending").forEach { overclaim ->
            assertFalse(
                text.contains(overclaim, ignoreCase = true),
                "the material calls an unclosed commitment '$overclaim', which nothing establishes: $text",
            )
        }
    }

    @Test
    fun `a commitment with no recorded outcome says whether its date is ahead or past`(): Unit = runBlocking {
        // The cheap half of state, and the three groups have to be distinguishable from each other:
        // what somebody asking where things stand wants first is the promise that fell due and has
        // nothing recorded against it.
        val past = FakeRetrieval(
            commitments = listOf(FakeRetrieval.commitment("the overdue one", "2", due = NOW.minusSeconds(86_400))),
        )
        val ahead = FakeRetrieval(
            commitments = listOf(FakeRetrieval.commitment("the upcoming one", "2", due = NOW.plusSeconds(86_400))),
        )
        val undated = FakeRetrieval(commitments = listOf(FakeRetrieval.commitment("the undated one", "2")))

        val pastText = retrieve(past, FakeRetrieval.aContact()).single().text
        val aheadText = retrieve(ahead, FakeRetrieval.aContact()).single().text
        val undatedText = retrieve(undated, FakeRetrieval.aContact()).single().text

        assertTrue(pastText.contains("now past"), "a promise that fell due is not marked as past: $pastText")
        assertTrue(aheadText.contains("ahead"), "a promise still to fall due is not marked as ahead: $aheadText")
        assertTrue(undatedText.contains("no date stated"), "an undated promise is not marked: $undatedText")
        assertFalse(aheadText.contains("now past"), "a promise due next week reads as overdue: $aheadText")
        assertFalse(pastText.contains(", ahead"), "an overdue promise reads as upcoming: $pastText")
    }

    @Test
    fun `a promise due today is not reported as overdue`(): Unit = runBlocking {
        // **Dates resolve to the start of a day**, which the extraction instruction's own worked
        // example fixes: a promise made this afternoon for tomorrow is dated to tomorrow's first
        // instant. Compared against now, every promise due today therefore stands in the past — and
        // this is the one shape whose instruction says to lead with what has fallen due, so the
        // whole of today's work would be reported as overdue.
        val today = FakeRetrieval(
            commitments = listOf(
                FakeRetrieval.commitment("the one due today", "2", due = NOW.truncatedTo(ChronoUnit.DAYS)),
            ),
        )

        val text = retrieve(today, FakeRetrieval.aContact()).single().text

        assertTrue(text.contains("today"), "a promise due today is not marked as due today: $text")
        assertFalse(text.contains("now past"), "a promise due today is reported as overdue: $text")
    }

    @Test
    fun `a closed commitment is not grouped by its due date`(): Unit = runBlocking {
        // A promise discharged late is discharged. Grouped under *due and past* beside the ones
        // nothing has settled, finished work would be put back in front of the reader — which is
        // the one thing a list of what is owed must not contain.
        val commitment = FakeRetrieval.commitment("the late one", "2", due = NOW.minusSeconds(86_400))
        val retrieval = FakeRetrieval(
            commitments = listOf(commitment),
            closures = mapOf(commitment.id to FakeRetrieval.message("9", "done, sorry it was late")),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact()).single { it.text.contains("the late one") }.text

        assertTrue(text.contains("closed by the message sent"), text)
        assertFalse(text.contains("now past"), "a discharged promise is grouped with the overdue ones: $text")
        // **And it keeps its date.** Not grouping a discharged promise by its date is one thing;
        // leaving the date out of its text is another, and it is the failure the undated marker
        // exists to prevent — a span with no date reads as one whose date was not worth repeating,
        // so the model places the thing by the only other date in front of it.
        // Said the way every other time in the material is said, which is what lets a reader
        // compare it with the heading above it rather than converting between two calendars.
        assertTrue(
            text.contains("due ${Moment.written(NOW.minusSeconds(86_400), ZoneOffset.UTC)}"),
            "a closed commitment lost its due date, so the model will date it from a neighbour: $text",
        )
    }

    @Test
    fun `what is owed is ordered overdue first, and the discharged last`(): Unit = runBlocking {
        // **The ordering the requirement asks for, which the store cannot return.** It orders by
        // recency of evidence, and what fell due last week is routinely what was agreed months ago
        // — so the thing a person asking where things stand wants first was arriving anywhere in
        // the list, with the instruction asking the model to group a list its own neighbouring
        // sentence described as ordered some other way.
        //
        // Calibrated by the fixture rather than by deletion alone: the input order is deliberately
        // none of the orders this could produce, so returning the store's order, the reverse, or a
        // sort by date that ignored closure all fail. The discharged one is dated furthest past of
        // any of them, which is what makes the last position load-bearing — sorted by date alone it
        // would lead, putting finished work at the top of the answer.
        val discharged = FakeRetrieval.commitment("the discharged one", "5", due = duePast(20))
        val retrieval = FakeRetrieval(
            commitments = listOf(
                FakeRetrieval.commitment("the ahead one", "1", due = dueAhead(2)),
                FakeRetrieval.commitment("the undated one", "2"),
                discharged,
                FakeRetrieval.commitment("the less overdue one", "6", due = duePast(2)),
                FakeRetrieval.commitment("the one due today", "4", due = duePast(0)),
                FakeRetrieval.commitment("the overdue one", "3", due = duePast(10)),
            ),
            closures = mapOf(discharged.id to FakeRetrieval.message("9", "that one is done")),
        )

        assertEquals(
            listOf(
                "the overdue one",
                "the less overdue one",
                "the one due today",
                "the ahead one",
                "the undated one",
                "the discharged one",
            ),
            claims(retrieve(retrieval, FakeRetrieval.aContact())),
        )
    }

    @Test
    fun `the computed order is lost where a commitment shares a span with a retrieved turn`(): Unit =
        runBlocking {
            // **Why the instruction states the order as something to write in rather than as a fact
            // about the material.** A commitment is addressed by the span it was read out of, and a
            // shape offers its messages before its findings — so a commitment evidenced by a turn
            // still in the recent tail is folded into that turn's block and shown at its position,
            // whatever the sort returned. Here the overdue one is folded behind the one that is
            // merely ahead.
            //
            // So position carries this for almost nothing, and the widening narrows it further:
            // every unclosed promise now has its evidencing turn in the material whether or not
            // the tail reached it, and a promise whose turn fell inside an earlier promise's
            // forward window takes that earlier position rather than its own — which is enough to
            // interleave the standings. What carries the order is the standing on each item's own label,
            // asserted below: an instruction claiming the material arrives overdue-first would be
            // false, and a model trusting that claim would lead with the wrong item.
            //
            // **This case does not cover the sort, and that is measured rather than assumed.** The
            // order it asserts is also the order an unsorted read produces, so bypassing
            // `Material.owedFirst` entirely leaves it green — the case beside it is the one that
            // reddens. What this holds is the fold, which is a property of how material is gathered
            // and not of the ordering: if it ever goes red because the overdue one now leads, the
            // fold has stopped happening and the instruction may send the model to the order of
            // the material after all.
            val turn = FakeRetrieval.message("7", "we will confirm the figures next month")
            val retrieval = FakeRetrieval(
                conversations = listOf(turn.externalId),
                messages = listOf(turn),
                commitments = listOf(
                    FakeRetrieval.commitment("the ahead one", "7", due = dueAhead(2)),
                    FakeRetrieval.commitment("the overdue one", "8", due = duePast(10)),
                ),
            )

            val retrieved = retrieve(retrieval, FakeRetrieval.aContact())

            assertEquals(
                listOf("the ahead one", "the overdue one"),
                claims(retrieved),
                "the fold no longer puts a commitment at its turn's position — if the material " +
                    "really does arrive in the computed order now, the instruction may say so " +
                    "rather than sending the model to each item's own standing",
            )
            val folded = retrieved.single { it.text.contains("the overdue one") }.text
            assertTrue(
                folded.contains("now past"),
                "the standing is the only thing carrying this once position has been lost, and it " +
                    "is not on the item: $folded",
            )
        }

    @Test
    fun `an overdue commitment says how far past it is`(): Unit = runBlocking {
        // A promise two weeks past and one that fell due this morning carried the same label, so a
        // reader had to read every date and subtract to find the one to act on — which is the
        // arithmetic the issue behind this measured a model relaying wrongly when left to it.
        //
        // Both directions and the singular, because a figure printed on everything says nothing:
        // the one due today is the boundary case the day comparison exists for, and it must carry
        // no overdue figure at all rather than a figure of nought.
        //
        // Every deadline here lands on a day boundary, which is what this case can hold and the
        // limit of it: the figure of nought asserted below is reached through the today branch,
        // where the figure is never computed at all. The deadline carrying a time of day is the
        // case beside this one.
        val far = FakeRetrieval(
            commitments = listOf(FakeRetrieval.commitment("the old one", "2", due = duePast(14))),
        )
        val near = FakeRetrieval(
            commitments = listOf(FakeRetrieval.commitment("the fresh one", "2", due = duePast(1))),
        )
        val today = FakeRetrieval(
            commitments = listOf(FakeRetrieval.commitment("today's one", "2", due = duePast(0))),
        )

        val farText = retrieve(far, FakeRetrieval.aContact()).single().text
        val nearText = retrieve(near, FakeRetrieval.aContact()).single().text
        val todayText = retrieve(today, FakeRetrieval.aContact()).single().text

        assertTrue(farText.contains("now past by 14 days"), "how far past is not reported: $farText")
        assertTrue(nearText.contains("now past by 1 day"), "a single day is reported in the plural: $nearText")
        assertFalse(
            todayText.contains("by 0 days"),
            "a promise due today is reported as past by nothing, which is a figure that " +
                "discriminates nothing: $todayText",
        )
    }

    @Test
    fun `a deadline carrying a time of day is counted from its own date`(): Unit = runBlocking {
        // **The case the midnight-aligned fixture cannot see.** A deadline is a date where somebody
        // stated one and an instant inside a day where they stated a time, and both forms are
        // stored. Counted as whole periods from the raw instant to a truncated now, the second form
        // reports a day fewer than a reader counting dates arrives at — and no case built on a day
        // boundary can tell the two measurements apart, because on a boundary they agree.
        //
        // The second half is the figure of nought reached through the branch that computes one: a
        // deadline that fell yesterday evening is hours from the start of today, so counted to a
        // truncated now it is no whole periods past, and the measurement this corrects prints the
        // figure that says nothing on the item a reader is most likely to be acting on. The case
        // above cannot reach it — a deadline due today never has a figure computed at all.
        val stated = FakeRetrieval(
            commitments = listOf(FakeRetrieval.commitment("the timed one", "2", due = duePastAt(15, 18))),
        )
        val yesterday = FakeRetrieval(
            commitments = listOf(FakeRetrieval.commitment("yesterday evening's one", "2", due = duePastAt(1, 18))),
        )

        val statedText = retrieve(stated, FakeRetrieval.aContact()).single().text
        val yesterdayText = retrieve(yesterday, FakeRetrieval.aContact()).single().text

        assertTrue(
            statedText.contains("now past by 15 days"),
            "the figure is measured from the deadline's instant to a truncated now, so a deadline " +
                "carrying a time of day reports a day fewer than the dates between them: $statedText",
        )
        assertTrue(
            yesterdayText.contains("now past by 1 day"),
            "a deadline that fell yesterday evening reports as past by nothing, which is the one " +
                "figure that tells a reader nothing: $yesterdayText",
        )
    }

    @Test
    fun `a commitment with no recorded outcome says how long ago it was last stated`(): Unit = runBlocking {
        // **Absence and age are different questions, and the answer was giving only the first.**
        // That an outcome is not recorded says whether the field is filled; how long the thing has
        // sat unmentioned is what tells a reader whether to act, and an undertaking three weeks old
        // read identically to one from yesterday.
        //
        // The discharged half is the calibration: age on everything alike would tell a reader
        // nothing about which item to look at, and how long a kept promise sat before it was kept
        // is history rather than something to act on.
        val open = FakeRetrieval(commitments = listOf(FakeRetrieval.commitment("the waiting one", "2")))
        val settled = FakeRetrieval.commitment("the settled one", "3")
        val closed = FakeRetrieval(
            commitments = listOf(settled),
            closures = mapOf(settled.id to FakeRetrieval.message("9", "done")),
        )

        val openText = retrieve(open, FakeRetrieval.aContact()).single().text
        val closedText = retrieve(closed, FakeRetrieval.aContact())
            .single { it.text.contains("the settled one") }.text

        // Four days, which is the fixture instant against this shape's clock. Asserted as the
        // figure rather than as the clause alone: a clause that always said today would pass the
        // weaker form while reporting the one thing age exists to distinguish.
        assertTrue(
            openText.contains("last stated 4 days ago"),
            "an item with no recorded outcome does not say how old the record of it is: $openText",
        )
        assertFalse(
            closedText.contains("last stated"),
            "a discharged promise carries an age too, so the clause marks every item alike and " +
                "points at none: $closedText",
        )
    }

    @Test
    fun `age is read from the last span that stated a thing, not the one it is shown under`(): Unit =
        runBlocking {
            // A restated obligation is one finding with two pointers, shown under the first — so the
            // span the block is addressed by is the older one, and age read from it would report a
            // promise confirmed yesterday as three weeks stale. What the reader asked is when the
            // corpus last saw the thing.
            val restated = FakeRetrieval.commitment("the restated one", "2").let {
                it.copy(
                    evidence = it.evidence + Evidence(
                        span = SpanId(RecordKind.MESSAGE, FakeRetrieval.record("3")),
                        sourceTime = NOW.minusSeconds(DAY),
                    ),
                )
            }

            val span = retrieve(FakeRetrieval(commitments = listOf(restated)), FakeRetrieval.aContact())
                .single()

            assertTrue(
                span.text.contains("last stated 1 day ago"),
                "the age is read from the span the finding is shown under rather than from the " +
                    "last one that stated it, so a restatement does not refresh it: ${span.text}",
            )
            // And it is still shown under the first span, which is the property the age must not
            // have changed: the reader is sent to where the thing was first said.
            assertEquals(
                FakeRetrieval.evidence("2").span,
                span.id,
                "the finding moved to the span that refreshed its age, so a reader following the " +
                    "citation lands on the restatement rather than on where it was first said",
            )
        }

    @Test
    fun `the two figures diverge by the gap between the deadline and the message`(): Unit = runBlocking {
        // **The cover the card behind this found missing, and what it is missing against.** A
        // commitment past its date carries two day counts in one clause — how far past the deadline
        // is, and how long the record has gone unmentioned. A promise is ordinarily mentioned about
        // when it falls due, so on ordinary material the two figures sit close together and a figure
        // taken from the deadline cannot be told from the same figure taken from the message. How
        // close they really sit is read off `commitment.due_at` against its evidence's `source_time`
        // rather than stated here, and the reader who read the smaller of the pair as the
        // deadline's, miscomputed, had no way to tell otherwise.
        //
        // So it is constructed in both directions — a deadline stated well before the message that
        // evidences it, and one stated well after — which is the only shape of case where the two
        // figures diverge far enough to be checked against each other.
        //
        // Both arms also assert the figure the other clause would have carried is absent. That is
        // not cover against the two being interchanged, which the positive assertions catch on
        // their own; it is cover against one figure being printed under *both* labels, where each
        // clause reads a correct end and one of them reads the wrong one.
        val deadlineFirst = FakeRetrieval(
            commitments = listOf(
                // The deadline carries a time of day so that the invariance asserted below is
                // driven for this figure too, and not only for the age — see [duePastAt]. It does
                // not move the figure, both ends being read as the dates they fall on.
                statedAt(FakeRetrieval.commitment("promised long before", "2", due = duePastAt(60, 18)), 5, 20),
            ),
        )
        val messageFirst = FakeRetrieval(
            commitments = listOf(
                statedAt(FakeRetrieval.commitment("mentioned long before", "2", due = duePast(16)), 45, 20),
            ),
        )

        val early = retrieve(deadlineFirst, FakeRetrieval.aContact()).single().text
        val late = retrieve(messageFirst, FakeRetrieval.aContact()).single().text

        assertTrue(early.contains("now past by 60 days"), "the deadline's distance is not its own: $early")
        assertTrue(early.contains("last stated 5 days ago"), "the age is not the message's own: $early")
        assertFalse(
            early.contains("now past by 5 days") || early.contains("last stated 60 days ago"),
            "a figure is rendered under the other clause's label: $early",
        )

        assertTrue(late.contains("now past by 16 days"), "the deadline's distance is not its own: $late")
        assertTrue(late.contains("last stated 45 days ago"), "the age is not the message's own: $late")
        assertFalse(
            late.contains("now past by 45 days") || late.contains("last stated 16 days ago"),
            "a figure is rendered under the other clause's label: $late",
        )

        // **And neither figure moves with the hour the question is asked**, which is what makes each
        // one a property of the record rather than of when it was read. Each is counted from a day
        // boundary, so the same material renders identically whenever it is read; counted instead
        // from its own raw instant, either steps down a day as the clock passes that instant's time
        // of day, and one reader checking the arithmetic against a calendar finds it right where the
        // next finds it off by one. Both ends of this arm carry a time of day, so both figures are
        // driven — the deadline's at 18:00 and the message's at 20:00, either side of the hours
        // asked below.
        //
        // In this file's zone, which is UTC. The two figures are counted on one calendar at every
        // zone now — `Material.age` and `Material.overdue` both say so — so this arm is about the
        // counting being taken from a day boundary rather than about which calendar that is; the
        // cases below it are where the calendars are driven apart.
        //
        // **Calibration.** Three breaks were applied to a copy: the deadline's figure measured to
        // the message that evidences it, the age read from the deadline so that one figure prints
        // under both labels, and the age put back on whole elapsed periods. The first and third
        // redden pre-existing cases as well as this one — the first because two cases already pin a
        // deadline's figure, the third because one already pins an age. The second reddens this case
        // alone, and it is the one the figures had no cover against: each clause reads an end that
        // exists, so nothing that pins a single figure can see the pair collapse onto one of them.
        val today = NOW.truncatedTo(ChronoUnit.DAYS)
        for (hour in listOf(1L, 13L, 23L)) {
            assertEquals(
                early,
                retrieve(deadlineFirst, FakeRetrieval.aContact(), now = today.plus(hour, ChronoUnit.HOURS))
                    .single().text,
                "a figure moves with the hour the question is asked, so it is not a property " +
                    "of the record",
            )
        }
    }

    @Test
    fun `a deadline and the message evidencing it at one moment report one figure`(): Unit = runBlocking {
        // **The defect: one instant rendered as two figures a day apart.** A commitment whose
        // deadline and whose only mention were the very same moment reported a distance one
        // greater than its age. The deadline's distance was counted between UTC day boundaries
        // and the age between the correspondents' calendar days, so for the hours where the two
        // calendars disagree the pair disagreed with itself. The two
        // figures exist so a reader can weigh urgency against staleness, which is a comparison —
        // and a pair wrong by a day in one direction only, for a knowable subset of the material,
        // is the shape of defect a reader catches once and then discounts both numbers for good.
        //
        // How much material sits in that band is a property of the corpus, read off the source
        // times of its messages against their due dates rather than stated here.
        //
        // **The age is the half that was already right, and the deadline now agrees with it**
        // rather than the other way about: the age sits a line under a heading dating the same
        // message in the correspondents' zone, so that calendar is the one a reader checks it
        // against. The deadline had no heading to be checked against and was left on the UTC day.
        //
        // The zone is passed because a UTC deployment cannot tell this case from a passing one.
        val zone = ZoneOffset.ofHours(4)
        // Late in the UTC day, which is the small hours of the next day where the correspondents
        // are — so the two conventions disagree about the date and not merely about the wording.
        val atOnce = duePastAt(15, 20)
        val retrieval = FakeRetrieval(
            commitments = listOf(
                statedAt(FakeRetrieval.commitment("send the signed copy", "2", due = atOnce), 15, 20),
            ),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact(), zone = zone).single().text

        assertTrue(
            text.contains("now past by 14 days"),
            "the deadline's distance is not counted on the correspondents' calendar: $text",
        )
        assertTrue(text.contains("last stated 14 days ago"), "the age is not the message's own: $text")
        assertFalse(
            text.contains("now past by 15 days"),
            "the deadline's distance is still counted between UTC days, so one moment reports " +
                "two figures a day apart: $text",
        )
        // **Calibration.** The two calendars really do disagree about this instant's date, so a
        // green above is the counting having moved rather than the zones agreeing here.
        assertEquals(
            1L,
            ChronoUnit.DAYS.between(
                atOnce.atZone(ZoneOffset.UTC).toLocalDate(),
                atOnce.atZone(zone).toLocalDate(),
            ),
            "the chosen instant falls on one date in both calendars, so the case discriminates nothing",
        )
    }

    @Test
    fun `a deadline's date and an event's moment are said in the shared calendar`(): Unit = runBlocking {
        // **The half of the clause that is a date rather than a figure, and it has to move with
        // the figure.** A distance counted on the correspondents' calendar beside a date printed
        // on the instant's own is the same defect one field over: the reader checks the figure
        // against the date they are shown, finds it one out, and has no way to tell which of the
        // two is wrong. An event's moment is the same kind of value and carries no figure at all,
        // so the date is the whole of what it says.
        //
        // Both are absolute times rather than times of messages, which is the distinction the
        // calendar they are read on used to turn on — so they are pinned here rather than left to
        // the cases about an age or a heading.
        val zone = ZoneOffset.ofHours(4)
        // Late in the UTC day, so the instant's own date and the correspondents' differ.
        val crossing = Instant.parse("2026-10-01T20:00:00Z")

        val owedText = owed("send the signed copy", crossing, zone)
        val events = FakeRetrieval(ahead = listOf(FakeRetrieval.event("the review call", "3", occursAt = crossing)))
        val eventText = retrieve(events, FakeRetrieval.aContact(), zone = zone)
            .single { it.text.contains("the review call") }.text

        assertTrue(
            owedText.contains("due ${Moment.written(crossing, zone)}"),
            "a deadline's date is not said the way everything beside it is: $owedText",
        )
        assertFalse(
            owedText.contains("$crossing"),
            "a deadline's date reached the prompt as a raw instant, a calendar of its own: $owedText",
        )
        assertTrue(
            eventText.contains(Moment.written(crossing, zone)),
            "an event's moment is not said the way everything beside it is: $eventText",
        )
        assertFalse(
            eventText.contains("$crossing"),
            "an event's moment reached the prompt as a raw instant: $eventText",
        )
        // **Calibration.** The instant really does fall on one date in UTC and the next in this
        // zone, so neither assertion would pass against the rendering this replaced.
        assertEquals(
            1L,
            ChronoUnit.DAYS.between(
                crossing.atZone(ZoneOffset.UTC).toLocalDate(),
                crossing.atZone(zone).toLocalDate(),
            ),
            "the chosen instant falls on one date in both calendars, so the case discriminates nothing",
        )
    }

    @Test
    fun `a deadline's standing is decided on the calendar its date is read on`(): Unit = runBlocking {
        // **The half of the same defect that mislabels rather than miscounts.** The group a
        // commitment is offered under is decided by comparing its date against today, so a
        // boundary taken on the UTC day puts an item falling today where the correspondents are
        // into the overdue group, and one falling tomorrow there into today's. The label a reader
        // sees then contradicts the date printed beside it, and the standing is what tells them
        // where to look first.
        //
        // It travels with the figure rather than after it: a figure moved alone would put an item
        // labelled past at nought days past, which the case above this family forbids.
        val zone = ZoneOffset.ofHours(4)
        // Late on the fourth in UTC is the fifth where the correspondents are, which is the day
        // the question is asked on; late on the fifth is the sixth, which is the day after it.
        val todayThere = Instant.parse("2026-10-04T20:00:00Z")
        val tomorrowThere = Instant.parse("2026-10-05T20:00:00Z")
        val yesterdayThere = Instant.parse("2026-10-03T20:00:00Z")

        val falling = owed("the one due today", todayThere, zone)
        val next = owed("the one due tomorrow", tomorrowThere, zone)
        val past = owed("the one that fell due", yesterdayThere, zone)

        assertTrue(falling.contains(" — today"), "a promise falling due today reads as overdue: $falling")
        assertFalse(falling.contains("now past"), "a promise falling due today reads as overdue: $falling")
        assertTrue(next.contains(" — ahead"), "a promise falling due tomorrow is not marked ahead: $next")
        assertFalse(next.contains(" — today"), "a promise falling due tomorrow reads as due today: $next")
        // The invariant the two have to satisfy together rather than separately, asserted at a
        // zone because that is where moving one of them alone would break it.
        assertTrue(past.contains("now past"), "a promise that fell due yesterday is not marked past: $past")
        assertFalse(
            past.contains("by 0 days"),
            "a promise the standing groups as past reports as past by nothing: $past",
        )
        // **Calibration.** Each of the three falls on a different date in the two calendars, so
        // none of the assertions above would hold either way.
        listOf(todayThere, tomorrowThere, yesterdayThere).forEach {
            assertEquals(
                1L,
                ChronoUnit.DAYS.between(it.atZone(ZoneOffset.UTC).toLocalDate(), it.atZone(zone).toLocalDate()),
                "$it falls on one date in both calendars, so the case discriminates nothing",
            )
        }
    }

    @Test
    fun `the closing turn is named in the prompt the way its own heading dates it`(): Unit = runBlocking {
        // **One moment, three renderings, and they used to be two conventions.** The turn that
        // closed a promise is dated by its block heading in the correspondents' zone, named again
        // in its own text so it can be found among forty others, and named a third time in the
        // commitment's label so the pair can be matched. The label and the text carried the
        // instant, the heading carried the local time, and for the hours either side of local
        // midnight the two were a day apart — so a model matching them did zone arithmetic nobody
        // asked it for, and a reader was told two dates for one message.
        //
        // The instant chosen is in that window: late on the first in UTC is the small hours of the
        // second where the correspondents are, so the two conventions disagree about the date and
        // not merely about the wording. A UTC deployment could not tell this case from a passing
        // one, which is why the zone is passed.
        val zone = ZoneOffset.ofHours(4)
        val crossing = Instant.parse("2026-10-01T21:00:00Z")
        val commitment = FakeRetrieval.commitment("send the invoice", evidencedBy = "2")
        val retrieval = FakeRetrieval(
            commitments = listOf(commitment),
            closures = mapOf(commitment.id to FakeRetrieval.message("9", "invoice is away", at = crossing)),
        )

        val prompt = asked(retrieval, zone)

        // Three times: the heading over the closing turn, the turn's own text, and the
        // commitment's label. Counted rather than matched one at a time, because what has to hold
        // is that no fourth wording of the same moment is in there.
        val said = Moment.written(crossing, zone)
        // Split rather than matched, since a rendered offset carries characters a pattern reads.
        assertEquals(
            3,
            prompt.split(said).size - 1,
            "the closing moment is not said the same way in the heading, the turn and the " +
                "commitment that names it: $prompt",
        )
        assertFalse(
            prompt.contains("$crossing"),
            "the closing moment reached the prompt as a raw instant as well, which is the second " +
                "convention and the one a day out from the heading: $prompt",
        )
        // Calibration: the two conventions really do disagree here, so a green above is the
        // rendering having changed rather than the case being one where either would pass.
        assertFalse(
            said.contains("2026-10-01"),
            "the instant and the correspondents' calendar agree on this date, so the case " +
                "discriminates nothing: $said",
        )
    }

    @Test
    fun `the age counts the days a reader counts, not the periods that have elapsed`(): Unit = runBlocking {
        // **The defect, and it is the figure being checkable that is at stake.** The block heading
        // over an item dates it in the correspondents' calendar, so a reader verifies the age
        // beside it by counting dates. Counted as whole elapsed periods the figure is one short
        // for every message written later in the day than the hour the question is asked — which
        // is most of an afternoon's material and not a near-midnight corner — and the measured
        // case was a reply reporting a message its own heading placed seven days back as six days
        // old. Read on the fifteenth, a reader counting from the eighth gets seven and concludes
        // the arithmetic is broken.
        //
        // Three cases, because two of them are the ones a boundary can be got wrong in either
        // direction: the same calendar day however many hours have passed, a different day though
        // fewer than twenty-four have, and the figure the elapsed count renders one short.
        val cases = listOf(
            Instant.parse("2026-10-05T02:00:00Z") to "last stated today",
            Instant.parse("2026-10-04T22:00:00Z") to "last stated 1 day ago",
            Instant.parse("2026-09-28T21:30:00Z") to "last stated 7 days ago",
        )

        cases.forEach { (stated, expected) ->
            val commitment = FakeRetrieval.commitment("the waiting one", "2").let {
                it.copy(
                    evidence = setOf(
                        Evidence(span = FakeRetrieval.evidence("2").span, sourceTime = stated),
                    ),
                )
            }

            val span = retrieve(FakeRetrieval(commitments = listOf(commitment)), FakeRetrieval.aContact())
                .single()

            assertTrue(
                span.text.contains(expected),
                "a thing last stated at $stated, against a clock reading $NOW, is not reported " +
                    "as \"$expected\": ${span.text}",
            )
        }
    }

    @Test
    fun `a commitment names the party it obliges beside the one that stated it`(): Unit = runBlocking {
        // **The read that existed and reached nobody.** Which party a claim obliges has been on the
        // row since the card that separated direction from authorship, projected by this shape's
        // own query, carried onto the domain object and then dropped at the rendering — so an answer
        // about what was undertaken said who said a thing and never which party it was placed on.
        //
        // Two different people, named through the entity layer, with both markers asserted and the
        // swap asserted against: one name on both markers would pass a case that only looked for
        // the names, which is the failure the two labels exist to prevent.
        val stater = EntityId.mint()
        val owes = EntityId.mint()
        val retrieval = FakeRetrieval(
            commitments = listOf(
                FakeRetrieval.commitment("send the invoice", "2", statedBy = stater, obligedParty = owes),
            ),
            names = mapOf(stater to "The Relayer", owes to "The Debtor"),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact()).single().text

        assertTrue(text.contains("obliges The Debtor"), "the party the claim obliges is not named: $text")
        assertTrue(text.contains("stated by The Relayer"), "the party that stated it is not named: $text")
        assertFalse(
            text.contains("obliges The Relayer") || text.contains("stated by The Debtor"),
            "the two parties are rendered under each other's markers: $text",
        )
        // And it was asked for, which is what makes the name available at all: the read is keyed by
        // identifier, so a shape that never put this one in it would be handed nothing and render
        // the unnamed form, which is indistinguishable from a corpus that never named the person.
        val asked = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Names>().single()
        assertTrue(owes in asked.parties, "the obliged party is not in the name read: ${asked.parties}")
    }

    @Test
    fun `a commitment the record places no obliged party on names none`(): Unit = runBlocking {
        // Nothing rather than nobody, as everywhere else in this material: a row carries no obliged
        // party wherever the restating stage could not answer it as one of its window's labels, so
        // the absent case is an ordinary one, and a placeholder would be read as a name by the one
        // thing that cannot check it. The claim's own prose is the fallback, and the instruction is
        // what says so.
        val stater = EntityId.mint()
        val retrieval = FakeRetrieval(
            commitments = listOf(FakeRetrieval.commitment("send the invoice", "2", statedBy = stater)),
            names = mapOf(stater to "The Relayer"),
        )

        val text = retrieve(retrieval, FakeRetrieval.aContact()).single().text

        assertFalse(text.contains("obliges"), "a row with no obliged party still carries the marker: $text")
        assertTrue(text.contains("stated by The Relayer"), "the party that stated it was lost with it: $text")
    }

    @Test
    fun `the instruction says what a status answer is for and not only what to put in it`(): Unit =
        runBlocking {
            // **What this pins is the absence that produced the defect**, and it is the same absence
            // the drafted reply had: the instruction said what to report and never what a report is
            // for. Every rule the prompt carries about an individual line — cite each item, date it, never
            // call an unclosed promise open, no narrative — is satisfied in full by a recap of the
            // conversation, which is what came back: cited, dated, honest about state, and not one
            // line of it a statement of where anything stands. The rules a recap does break are the
            // ones about the whole — the parts in order, what is past leading — and being asked for
            // was not enough to get them, which is the other case beside this one.
            //
            // Prose pins, which are the weak kind, knowingly: they catch a clause being dropped
            // rather than a sentence elsewhere working against it, and only reading answers on the
            // archive can say whether the model obeys them.
            val system = assembled(
                FakeRetrieval(commitments = listOf(FakeRetrieval.commitment("send the invoice", "2"))),
            ).collapsed()

            listOf(
                "What this answers is whether anything needs doing and what" to
                    "nothing says what the answer is for, so a chronicle satisfies what is left",
                "A recap of the conversation is not a status" to
                    "the failure is unnamed, and a cited, dated list arrives at it by default",
                "the one or two lines saying where things stand and what most needs attention" to
                    "nothing asks for a bottom line, so whether anything needs doing is learnable " +
                    "only by reading the whole answer",
                "An item the record places on nobody and gives no date is not a next action" to
                    "an item nothing places is presentable as something to do, so the list reads " +
                    "as actionable while none of it is",
                "Say once what the record does not hold, rather than once for every item" to
                    "each absence is restated per item, and at that density what a reader learns " +
                    "is that the system does not know",
                // **Read off a reply that came back in the wrong language entirely.** This shape is
                // reached by a command and a name as often as by a sentence, and a name carries no
                // language — so asking for the question's language asks for something that is not
                // there, and the reply's language is then whatever the model settles on. A sibling
                // shape answered a Russian corpus in English on exactly that input. The drafting
                // shape never had the hole, because it names the thread rather than the question.
                // **Read off a real reply, and both halves are pinned.** Asked what is happening
                // *and what is expected of me*, the shape had no way to know which party was
                // asking: it listed the obligations by party and said outright that the material
                // did not say who wanted to know. The mark now reaches its material, and this is
                // the ask that uses it — with the arm for an unmarked turn beside it, which is this
                // prompt's convention for every marker it reads and what keeps the rule from
                // licensing a guess about who is reading.
                "A turn marked as the asker's own" to
                    "nothing says what the asker's mark means, so a question about what is " +
                    "expected of the reader is answered by party and not by them",
                // **Read off the reply the mark's own fix produced.** Told which turns were the
                // asker's, the answer named them in the third person in one breath and addressed
                // them as *you* in the next — so a reader met their own name listed beside the
                // people the answer was about and had to work out which was which.
                "Write about them in the second person" to
                    "nothing says how to address the person asking, so an answer names them " +
                    "beside the parties it is about and reads as a report written for somebody else",
                "the material does not say who is asking" to
                    "the rule is stated as a universal, so an unmarked turn means the wrong thing " +
                    "and the answer guesses whose item it is",
                "answer in the language of" to
                    "a request that is a command and a name carries no language, so nothing says " +
                    "what the reply's language should be and the material's is ignorable",
                "Write each item as a whole sentence that says what follows from it" to
                    "the answer is a list of fragments, which drops the words that carried the " +
                    "reasoning",
            ).forEach { (clause, complaint) ->
                assertTrue(system.contains(clause), "$complaint: $system")
            }
            // **The negative half, and it is the sentence this change could most easily have
            // broken.** Comments outside this shape lean on the clause admitting a list and
            // refusing a narrative to explain why a reply carrying no prose at all is still an
            // answer — `Reply.read`, `Answer` and `Runner`, none of them quoting it word for word.
            // Reworded here into a demand for prose, it would make that reasoning false and a
            // claims-only reply would start reading as nothing to answer from.
            assertTrue(
                system.contains("A list of cited items, dated where the material dates them, is the answer"),
                "the clause `Reply.read`, `Answer` and `Runner` all lean on is gone, so a reply " +
                    "that answers entirely in claim lines may now be read as having answered " +
                    "nothing: $system",
            )
        }

    @Test
    fun `the instruction says to write what is owed overdue first`(): Unit = runBlocking {
        // The ordering, stated to the model as well as computed — because the computed order does
        // not survive a commitment sharing a span with a retrieved turn, and because the sentence
        // that used to sit here described the material as ordered by recency, which the model was
        // being asked to group some other way in the next breath.
        val system = assembled(
            FakeRetrieval(commitments = listOf(FakeRetrieval.commitment("send the invoice", "2"))),
        ).collapsed()

        assertTrue(
            system.contains("the ones now past first and the furthest past of those before the rest"),
            "nothing says what order to write what is owed in: $system",
        )
        assertTrue(
            system.contains("the standing is marked on each item, so read it from there"),
            "the model is sent to the material's order, which the fold defeats for any finding " +
                "whose turn is still in the window: $system",
        )
        // **Read on the archive before this clause existed.** Asked for the order and given no rule
        // about labelling it, the answer gathered the overdue items, one still ahead and the whole
        // undated group under a single heading that said the deadlines had passed. Every item's own
        // line was right and the heading was not, which is the half a skimmer reads.
        assertTrue(
            system.contains("A label you put over a group of them has to be true of everything under it"),
            "nothing says a group's label must cover what is under it, so an item still ahead can " +
                "be gathered under a heading calling it overdue: $system",
        )
        // The sentence this replaced, which said the opposite of what the paragraph beside it
        // asked for. Left in place the two are a conflict the model resolves by picking one.
        assertFalse(
            system.contains("ordered by what was evidenced most recently"),
            "the instruction still describes the commitments as recency-ordered while asking for " +
                "them overdue first, which cannot both be obeyed: $system",
        )
    }

    @Test
    fun `an absence is said once in the answer, not for a group and again on every item`(): Unit =
        runBlocking {
            // **Asking for an absence once was obeyed twice over, which is the reading that helps
            // nobody.** A read of the rendered reply found a section opening with a line saying the
            // agreements below carried no deadline and no recorded outcome, above items that each
            // said exactly that — asking for it once is satisfied by the group sentence and
            // satisfied again by the per-item clause, and nothing said which.
            val system = assembled(
                FakeRetrieval(commitments = listOf(FakeRetrieval.commitment("send the invoice", "2"))),
            ).collapsed()

            // **Both halves, because the direction the rule runs is the operative one.** Pinned on
            // the first clause alone, the sentence could be reworded to point anywhere — and where
            // it points is the whole of what makes the prohibition below obeyable.
            assertTrue(
                system.contains("Once means once in the whole answer, and the opening is where it goes"),
                "saying it for the group and on every item under it both satisfy the ask, so the " +
                    "reader gets the absence twice: $system",
            )
            assertTrue(
                system.contains("Never open a section with a line summarising the items beneath it"),
                "nothing forbids the preamble, so each section opens with a sentence restating " +
                    "what its own items say: $system",
            )
            // **This clause was written by a live run, and the wording it replaced is why.** An
            // A/B probe against the answering model — the shape's own assembled prompt, the same
            // material in both arms, the card's paragraphs the only difference — came back with
            // the preamble in the arm that *had* the prohibition and not in the arm without it.
            // The model had written "three undertakings sit under this body of work, all of them
            // stating no date ..." under each heading: a summary, but not one that states *only*
            // what the items state, because it also counts them. So the count is named here, and
            // the clause no longer turns on the word "only".
            assertTrue(
                system.contains("neither the labels they all carry nor how many of them there are"),
                "the prohibition turns on the summary saying nothing new, which a line that also " +
                    "counts the items evades — as a live run showed it does: $system",
            )
            // The premise the once-means-once clause qualifies. Reworded out, that clause is left
            // conditional on an ask that no longer says once — see the pin on it in the case about
            // what a status answer is for.
            assertTrue(
                system.contains("Say once what the record does not hold, rather than once for every item"),
                "the ask the once-means-once clause qualifies is gone, so that clause now " +
                    "qualifies nothing: $system",
            )
            // **The two exemptions, pinned because a reviewer read the prohibition as swallowing
            // them.** A section's heading and an item's own standing are both lines about what a
            // group holds, and the group-label rule further down requires one or the other — so a
            // prohibition that reached them would forbid what another rule demands.
            assertTrue(
                system.contains("A heading naming what its section is about is not that line"),
                "the prohibition reaches a section's own heading, which the sectioning format " +
                    "requires: $system",
            )
            assertTrue(
                system.contains("neither is a standing written on an item of its own"),
                "the prohibition reaches a per-item standing, which the group-label rule offers " +
                    "as the alternative to a group label: $system",
            )
        }

    @Test
    fun `the material keeps its labels, which are not the redundancy the answer rule removes`(): Unit =
        runBlocking {
            // **The negative half, and the one this change could most plausibly have got wrong.**
            // The fix is a rule about the answer's prose; the labels on the material are deliberate
            // and stay, because a model told in prose that an absence is not an open state and then
            // shown an unlabelled item follows what it is shown — `Material.state` carries that
            // reasoning. Moved into the rendering instead, this card would have taken the labels and
            // left the model guessing.
            val retrieval = FakeRetrieval(
                commitments = listOf(FakeRetrieval.commitment("send the invoice", "2")),
            )

            val text = retrieve(retrieval, FakeRetrieval.aContact())
                .single { it.text.contains("Inferred commitment") }
                .text

            assertTrue(
                text.contains("(no date stated)"),
                "the undated label was dropped from the material, so the model has to infer the " +
                    "absence from a gap rather than read it: $text",
            )
            assertTrue(
                text.contains("outcome not recorded"),
                "the outcome label was dropped from the material, which is the one wording that " +
                    "keeps an unrecorded outcome from being read as an open state: $text",
            )
        }

    @Test
    fun `the instruction asks for one copy of a finding and for a lead that is not an inventory`(): Unit =
        runBlocking {
            // Both read off the same rendered reply. One agreement was named in the opening and then
            // written out again as the first finding of its section, with more detail and the same
            // citation; and what opened the reply was a summary of the labels further down — nothing
            // overdue, every agreement undated with no recorded outcome, when they were last
            // discussed — which is the material's bookkeeping rather than where anything stands.
            //
            // Prose pins, which are the weak kind: they catch a clause being dropped, not a model
            // disobeying it. Only reading a rendered reply and counting can say more, which is what
            // the card's own acceptance check asks for.
            val system = assembled(
                FakeRetrieval(commitments = listOf(FakeRetrieval.commitment("send the invoice", "2"))),
            ).collapsed()

            assertTrue(
                system.contains("Report each finding once"),
                "nothing says a finding appears once, so an item is written out under two " +
                    "headings and counted twice: $system",
            )
            assertTrue(
                system.contains("does not appear under two headings"),
                "a finding bearing on two sections may be written under both, which presents one " +
                    "item as two: $system",
            )
            assertTrue(
                system.contains("What opens the answer says what the answer is"),
                "nothing says an inventory of absences is not a lead, so a reply with nothing " +
                    "pressing opens with the labels it can see: $system",
            )
            assertTrue(
                system.contains("what it may not do is stand in for that"),
                "the rule is pinned by its framing sentence alone, so the clause that carries it " +
                    "— the inventory may not replace the answer — could be dropped silently: $system",
            )
            // **Two halves this must not have forbidden, and a reviewer read it as forbidding
            // both.** The card names the first: a sentence saying what the answer *is* remains
            // wanted. The second is the opening naming what most needs attention, which the
            // bottom-line ask and the ordering rules both require — a bar on it would strip the
            // leading item of the standing, age and citation those rules put on every item, and
            // leave its group with a hole. So the rule is scoped to a second copy, and both the
            // ask and the exemption are pinned.
            assertTrue(
                system.contains("the one or two lines saying where things stand and what most needs attention"),
                "the lead was forbidden along with the commentary, so the answer now opens with " +
                    "its first finding: $system",
            )
            assertTrue(
                system.contains("Naming in the opening what most needs attention is not a second copy"),
                "the finding-once rule reaches the lead itself, so the opening can no longer name " +
                    "the item that most needs attention, or that item leaves its section: $system",
            )
        }

    @Test
    fun `the closure read asks about the commitments retrieved and nothing else`(): Unit = runBlocking {
        // Its bound is the commitment read's, as the party read's is. A read that asked more widely
        // would be reading the corpus rather than qualifying what is in hand.
        val one = FakeRetrieval.commitment("send the invoice", evidencedBy = "2")
        val other = FakeRetrieval.commitment("chase the contract", evidencedBy = "4")
        val retrieval = FakeRetrieval(commitments = listOf(one, other))

        retrieve(retrieval, FakeRetrieval.aContact())

        val asked = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Closures>().single()
        assertEquals(listOf(one.id, other.id), asked.commitments)
    }

    @Test
    fun `the instruction says a closure may be reported and cited`(): Unit = runBlocking {
        // The other half of the state caution: without this the model is told what it may not say
        // and never told what it may, so a closure the pass paid for would reach the material and
        // be rendered as nothing in particular.
        val system = assembled(FakeRetrieval()).collapsed()

        assertTrue(
            system.contains("reports the thing as done: say it was reported as done and cite that message"),
            "nothing tells the model what to do with a closure it is shown: $system",
        )
    }

    @Test
    fun `the caution about a hedged finding names the marker the material carries`(): Unit =
        runBlocking {
            // **The orphan this change could have left, asserted as a function of the two rather
            // than as a pair of literals.** The instruction forbids presenting such a finding as
            // settled, and it can only do that by naming what the material says — so the phrase
            // is read off the rendered material and looked for in the prompt. Reword the marker
            // in `Material` and leave the instruction alone, and this reddens; a check pinning
            // both wordings separately would pass with the two out of step, which is how the old
            // wording survived in two instructions after the label stopped using it.
            val retrieval = FakeRetrieval(
                commitments = listOf(
                    FakeRetrieval.commitment("send the invoice", evidencedBy = "2", hedged = true),
                ),
            )
            val marker = retrieve(retrieval, FakeRetrieval.aContact()).single().text
                .substringAfter("Inferred commitment, ")
                .substringBefore(" (")

            assertTrue(marker.isNotBlank(), "the hedge marker rendered nothing to look for")
            assertTrue(
                assembled(retrieval).collapsed().contains(marker),
                "the instruction cautions about a marker the material does not carry: $marker",
            )
        }

    @Test
    fun `the instruction says to report one undertaking read twice once, as done`(): Unit =
        runBlocking {
            // **Read on the archive: one obligation rendered as both overdue and discharged in one
            // reply.** A restatement is rewritten on its own, so one obligation stood as two rows —
            // one carrying a deadline with no verdict against it, the other carrying a closure with
            // evidence. Asked for the groups this instruction asks for, the model composed the pair
            // into an overdue section and a closed section, and the reader was told both. Every half
            // cites a span that exists, so the grounding gate passes it: the prompt is the only
            // place a reply composed from such a pair can be made to read as one answer.
            val system = assembled(FakeRetrieval()).collapsed()

            assertTrue(
                system.contains("two readings of one undertaking whose outcomes do not agree"),
                "nothing tells the model the material can hold one undertaking twice with outcomes " +
                    "that do not agree, so it will compose the pair into two sections: $system",
            )
            // **The antecedent, pinned on its own.** It is the clause that stops the rule reaching
            // any two items that merely read alike, and the consequence clauses below all survive
            // its widening — so a pin on them alone would stay green while the rule grew teeth it
            // must not have.
            assertTrue(
                system.contains("Where you can see that two items are the same undertaking"),
                "the pairing's antecedent is gone or reworded, so the merge rule may now reach two " +
                    "items that merely resemble each other: $system",
            )
            assertTrue(
                system.contains("report it once, as reported as done, and cite both the closing message"),
                "nothing says how to reconcile the pair, so the model is left to pick a half or to " +
                    "report both: $system",
            )
            // **Reporting it is not enough on its own: placement is what the reader sees.** The
            // contradiction was visible because the two halves sat under two labels. A position the
            // model has to invent is read by a skimmer as a label, so the rule names one — and it is
            // the discharged group, because that is what the reconciliation concluded.
            assertTrue(
                system.contains("Report it where the ones reported as done go"),
                "the reconciled item has no stated position, so the model invents one and a skimming " +
                    "reader takes it for a label: $system",
            )
            // **Why it reconciles that way rather than reporting a disagreement.** This is the
            // build's own judgement, recorded on `CollapseRepository.merge`: a found closure
            // outranks a verdict that found none. Pinned because an edit that dropped it would
            // leave the instruction asserting a closure with no reason given, which is the shape a
            // later reviewer removes as unexplained.
            assertTrue(
                system.contains("evidence the other reading missed rather than a second opinion"),
                "the reason a found closure outranks a verdict that found none is gone, so the " +
                    "instruction asserts the closure without saying why it may: $system",
            )
            // **The rule is additive and must stay so.** Read as replacing the groups it would cost
            // the sections themselves — a genuinely overdue undertaking belongs under overdue and a
            // genuinely discharged one under discharged, and the pair is the exception. Both
            // sentences are pinned in cases of their own; asserted here too, because this is the
            // edit that could quietly take either of them with it.
            assertTrue(
                system.contains("the ones now past first and the furthest past of those before the rest"),
                "the group order went with the conflict rule, so the answer no longer has the " +
                    "sections the pair is an exception to: $system",
            )
            assertTrue(
                system.contains("reports the thing as done: say it was reported as done and cite that message"),
                "the rule for an ordinary closure went with the conflict rule, so a discharged " +
                    "promise is no longer reportable as discharged: $system",
            )
        }

    @Test
    fun `the lead is asked for as one sentence, not only as the first line`(): Unit = runBlocking {
        // Leading with the outcome was already obeyed on the real archive and the lead was still
        // unreadable: one long sentence packing a restoration, a service, the artefacts it
        // covered, a mechanism and a figure, every fact of it true and cited. What was missing is
        // the budget, so what is asked for is the sentence rather than the line. The reply it was
        // read off is in the pull request, which is where the measurement belongs.
        val system = assembled(FakeRetrieval()).collapsed()

        // **The antecedent, pinned on its own, because the consequence clauses survive its
        // widening.** The budget is on the *first* of the opening lines; widened to "the opening"
        // it would bound the whole lead and ask for the supporting facts to be dropped, with both
        // clauses below still green. A pin on consequences alone is the gap prior passes found
        // three times over.
        assertTrue(
            system.contains("Write the first of them as one short sentence"),
            "the budget's antecedent is gone or widened, so the rule may now bound the whole " +
                "opening rather than its first sentence: $system",
        )
        assertTrue(
            system.contains("one short sentence a reader takes in once"),
            "nothing bounds the lead to a sentence, so packing the whole outcome into one is " +
                "within the rules: $system",
        )
        assertTrue(
            system.contains("follows in sentences of its own"),
            "the lead is bounded with nowhere for the supporting facts to go, which is a budget " +
                "the model can only meet by dropping them: $system",
        )
    }

    @Test
    fun `the instruction bounds the pairing to one undertaking read twice`(): Unit = runBlocking {
        // **The half that keeps the rule from costing more than it saves, and the costs are not
        // symmetrical.** Told only to join what looks alike, a model folds two genuinely different
        // undertakings into one and reports it as done — so an undertaking nothing records as done
        // is reported as discharged and then lost, with nothing in the reply showing it happened.
        // Leaving a real pair unpaired is the defect this card describes, and a reader can see it.
        // So the ask is bounded where it is made, and the fallback under doubt is to report
        // separately.
        val system = assembled(FakeRetrieval()).collapsed()

        assertTrue(
            system.contains("not about two that resemble each other"),
            "the pairing is unbounded, so two different undertakings by one party may be folded " +
                "into one item and one of them lost: $system",
        )
        assertTrue(
            system.contains("Where you cannot tell which of the two you are looking at, report them separately"),
            "nothing says what to do under doubt, and the costly direction to guess in is joining: $system",
        )
        // The asymmetry itself, pinned: a bound stated without it reads as a tie-break, and the
        // reason the fallback runs this way rather than the other is the whole of why it is safe.
        assertTrue(
            system.contains("reports an undertaking as done that nothing records as done"),
            "the reason joining wrongly is the costlier error is gone, so the fallback under doubt " +
                "reads as arbitrary and may be reversed: $system",
        )
    }

    @Test
    fun `a count is asked for after its list or not at all`(): Unit = runBlocking {
        // The framing that announced one thing needing attention above a list that held more,
        // several of them independently actionable. Requiring the number to be right would need it
        // before the list is written; forbidding a count outright loses a framing that is useful
        // when it is true. So both ways out are stated and neither is the one that cannot be
        // obeyed.
        val system = assembled(FakeRetrieval()).collapsed()

        assertTrue(
            system.contains("Do not announce a number and then list a different one"),
            "a counted framing is ungoverned, and a reader who notices one miscount discounts " +
                "the next count: $system",
        )
        // **The antecedent is the announcing, not the counting**, and the two ways out below stay
        // green if it widens. Widened to forbid a number anywhere, the rule would take the
        // after-the-list form it offers in the same breath, which is the one way out that can be
        // obeyed — so what has to hold is that only announcing ahead of the list is forbidden.
        assertTrue(
            system.contains("and then list a different one"),
            "the prohibition no longer turns on announcing a number the list then contradicts, " +
                "so it may now forbid a count the rule itself offers: $system",
        )
        assertTrue(
            system.contains("name no number") && system.contains("say how many are there"),
            "the rule forbids without saying what to do instead, which is the shape of prompt " +
                "that gets resolved by picking one: $system",
        )
    }

    @Test
    fun `two readings of one undertaking reach the prompt with opposite outcomes`(): Unit = runBlocking {
        // **The condition the rule above exists for, asserted on the material rather than assumed.**
        // Both rows are live, both are inside the horizon, and the rendering is faithful to each: one
        // carries a deadline now past with nothing recorded against it, the other carries a closure
        // with evidence. Nothing here is a defect in the rendering, which is why the pair can only be
        // reconciled by whatever reads both — and why the instruction is the seat.
        //
        // **The measured pair differed by language and this fixture differs by wording.** The source
        // rule keeps the corpus's own script out of Kotlin outside the recognition stages, so the
        // two claims are two English wordings; what the case needs them to be is unequal, which is
        // what defeats a comparison on the claim text either way.
        //
        // **Neither row carries a party, which is the discriminating shape rather than a gap.** Most
        // rows carry none — the restating stage can place one only as a label of its own window — so
        // a pair recognisable only through a shared party would be inert on the ordinary row. The
        // rule says as much, and the case beside this one holds it.
        val restated = FakeRetrieval.commitment("send the signed copy over", "2", due = duePast(14))
        val discharged = FakeRetrieval.commitment("the signed copy is to be sent", "5")
        // A third row, placed after the discharged one in the input so that the order asserted below
        // is none of the orders a bypassed sort would produce. Without it the fixture arrives in the
        // order it is asserted in, and an identity ordering passes.
        val alsoOverdue = FakeRetrieval.commitment("confirm the delivery window", "6", due = duePast(2))
        val retrieval = FakeRetrieval(
            commitments = listOf(restated, discharged, alsoOverdue),
            closures = mapOf(discharged.id to FakeRetrieval.message("9", "signed copy sent")),
        )

        val retrieved = retrieve(retrieval, FakeRetrieval.aContact())
        val past = retrieved.single { it.text.contains("send the signed copy over") }.text
        val closed = retrieved.single { it.text.contains("the signed copy is to be sent") }.text

        assertTrue(past.contains("now past"), "the unclosed reading is not shown as overdue: $past")
        assertTrue(past.contains("outcome not recorded"), "the unclosed reading claims an outcome: $past")
        assertTrue(closed.contains("closed by the message sent"), "the closed reading is not shown closed: $closed")
        assertFalse(closed.contains("outcome not recorded"), "the closed reading denies its closure: $closed")
        // And the computed order separates the two halves: both unclosed readings lead, furthest past
        // first, and the discharged one is last — so an unrelated finding sits between the pair.
        //
        // **What that asserts is the sort and not where the model will read them.** Position carries
        // the order only for findings whose turns fell outside the message window, which this fixture
        // arranges by offering no messages at all; `Material.gathered` folds a commitment into its
        // own turn's block wherever that turn is still in the window, and on a real archive the two
        // halves are further apart again, up to the whole commitment bound. The separation is why
        // pairing them is hard, not evidence that the answer will show them apart.
        assertEquals(
            listOf("send the signed copy over", "confirm the delivery window", "the signed copy is to be sent"),
            claims(retrieved),
            "the pair does not arrive in two groups, so this is no longer the case the rule is for",
        )
    }

    @Test
    fun `a conclusion states its basis, and today is named as today`(): Unit = runBlocking {
        // Both halves of one measured sentence: a finding concluded that everything turned on a
        // weekday, correctly, without saying why that day — and the weekday was the day the reply
        // was composed, which it also did not say.
        val system = assembled(FakeRetrieval()).collapsed()

        // Both antecedents pinned beside their consequences, for the reason the two cases above
        // give: a conclusion *you draw*, and a date *you name* that matches the printed one. Widen
        // either — to any sentence, or to any date in the material — and both clauses below stay
        // green while the rule reaches material it was never measured on.
        assertTrue(
            system.contains("A conclusion you draw"),
            "the basis rule's antecedent is gone or widened, so it may now reach any sentence " +
                "rather than a conclusion the reply itself drew: $system",
        )
        assertTrue(
            system.contains("states what it rests on in the same breath"),
            "a conclusion may be drawn with its basis left for the reader to reconstruct: $system",
        )
        assertTrue(
            system.contains("A date or a weekday you name"),
            "the today rule's antecedent is gone or widened, so it may now reach a date the " +
                "material carries rather than one the reply names: $system",
        )
        assertTrue(
            system.contains("is the one printed there is today"),
            "nothing tells the model that a date matching the present is today, so the one date a " +
                "reader needs placed is the one left unplaced: $system",
        )
    }

    @Test
    fun `the present moment reaches the model, which is what lets a day be named as today`(): Unit =
        runBlocking {
            // The operand the rule above needs. Nothing in the prompt stated the present, so a
            // weekday could be named correctly and not identified — and this shape asks for it
            // through the same clock its retrieval sorts deadlines against, which is what makes
            // the two agree about which day it is.
            //
            // In the request rather than in the system block, which the stage's own cases hold:
            // asserted here on the prompt so that a line moved into the cached prefix fails
            // somewhere.
            val asked = asked(FakeRetrieval())

            assertTrue(
                asked.contains("Now: 2026-10-05 Mon 12:00 Z"),
                "the present moment never reaches the model: $asked",
            )
        }

    @Test
    fun `every read is bounded`(): Unit = runBlocking {
        // A prompt holds a bounded amount and a conversation does not. An unbounded read here is a
        // request that works on a test fixture and fails on a real archive.
        val retrieval = FakeRetrieval()

        retrieve(retrieval, FakeRetrieval.aContact())

        retrieval.asked.forEach { ask ->
            val limit = when (ask) {
                is FakeRetrieval.Ask.Commitments -> ask.limit
                is FakeRetrieval.Ask.Events -> ask.limit
                is FakeRetrieval.Ask.UndatedEvents -> ask.limit
                is FakeRetrieval.Ask.Statements -> ask.limit
                is FakeRetrieval.Ask.Messages -> ask.perConversation
                is FakeRetrieval.Ask.Conversations -> ask.threads
                // Carries no limit of its own, and must not invent one: it asks for the parties of
                // findings the three reads above already bounded, so its bound is theirs. Asserted
                // below against what a prompt can carry rather than against a constant it does not
                // have.
                is FakeRetrieval.Ask.Names -> null
                // The same: it asks about the commitments the read above already bounded, so its
                // bound is theirs and inventing one here would be a second limit to keep in step.
                is FakeRetrieval.Ask.Closures -> null
                // And the same again: it asks about the turns the message read bounded, plus the
                // closing turns the read above bounded.
                is FakeRetrieval.Ask.Turns -> null
            }
            if (limit != null) {
                assertTrue(limit in 1..MAX_SENSIBLE, "$ask is not bounded to anything a prompt can carry")
            }
        }

        val names = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Names>().single()
        assertTrue(
            names.parties.size <= MAX_SENSIBLE,
            "the party read must be bounded by the findings that reached it, not by the corpus",
        )
    }

    @Test
    fun `a message with no text is not material an answer can rest on`(): Unit = runBlocking {
        // A photograph or a service notice is a record and not something to compose from or cite.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", null).externalId),
            messages = listOf(FakeRetrieval.message("1", null), FakeRetrieval.message("2", "real text")),
        )

        assertEquals(listOf("real text"), retrieve(retrieval, FakeRetrieval.aContact()).map { it.text })
    }

    @Test
    fun `a commitment and the message it was read out of are gathered, not one dropped`(): Unit = runBlocking {
        // A commitment is addressed by the span it was read out of, so it arrives under the identifier
        // its own message already holds. Keeping one and discarding the other leaves the model told to
        // cite a turn it was never shown; keeping both separately would print one identifier over two
        // bodies, so a claim citing it could resolve to neither.
        val turn = FakeRetrieval.message("7", "I will send the invoice on Friday")
        val retrieval = FakeRetrieval(
            conversations = listOf(turn.externalId),
            messages = listOf(turn),
            commitments = listOf(FakeRetrieval.commitment("send the invoice", evidencedBy = "7")),
        )

        val retrieved = retrieve(retrieval, FakeRetrieval.aContact()).single()

        assertEquals(FakeRetrieval.evidence("7").span, retrieved.id)
        assertTrue(retrieved.text.contains("I will send the invoice on Friday"), "the turn was dropped")
        // The label and not the claim alone: a claim is usually a few words of its own turn, so
        // asserting the bare words passes on a dropped inference — the turn carries them.
        assertTrue(retrieved.text.contains("send the invoice"), "the inference was dropped")
        assertTrue(retrieved.text.contains("Inferred commitment"), "the inference is not marked as one")
        // The turn reads before what was inferred from it, which is why the shape orders messages first.
        assertTrue(
            retrieved.text.indexOf("I will send") < retrieved.text.indexOf("Inferred commitment"),
            "the inference reads before the turn it was read out of",
        )
    }

    @Test
    fun `a commitment and an event read out of one message are gathered too`(): Unit = runBlocking {
        // `commitment` and `event` hold at most one row per span each, but they are separate tables, so
        // one turn can carry one of each — and then three texts meet under one identifier.
        val turn = FakeRetrieval.message("9", "we will ship on Friday and review on Monday")
        val retrieval = FakeRetrieval(
            conversations = listOf(turn.externalId),
            messages = listOf(turn),
            commitments = listOf(FakeRetrieval.commitment("ship on Friday", evidencedBy = "9")),
            ahead = listOf(FakeRetrieval.event("the Monday review", evidencedBy = "9")),
        )

        val retrieved = retrieve(retrieval, FakeRetrieval.aContact()).single()

        // Asserted as three lines rather than three `contains`: both claims are phrases of the turn
        // itself, so a `contains` on either passes with that inference dropped and the count is the
        // only thing that notices.
        val lines = retrieved.text.lines()
        assertEquals(3, lines.size, "three texts met under one identifier: $lines")
        assertEquals("we will ship on Friday and review on Monday", lines[0])
        // The marker for a commitment nothing dated travels with it, as it does for an event: a
        // span that simply omits the date leaves the model to place the thing by the only other
        // date in front of it. The age clause is on it because this shape asks for one — see the
        // case about reporting age rather than absence — and it is asserted here as part of the
        // whole line rather than separately, since what this case is about is the line being whole.
        assertEquals(
            "Inferred commitment (no date stated), outcome not recorded, last stated 4 days ago: " +
                "ship on Friday",
            lines[1],
        )
        assertTrue(lines[2].startsWith("Inferred event"), lines[2])
        assertTrue(lines[2].endsWith("the Monday review"), lines[2])
    }

    @Test
    fun `an attribute read out of the same message is gathered with it`(): Unit = runBlocking {
        // The third kind of finding entering the same collision, which nothing exercised before: an
        // attribute, a commitment and the turn they were both read out of share one identifier,
        // because nothing numbers findings within a span. Reachable rather than theoretical — one
        // message can state a promise and describe its sender at once.
        val turn = FakeRetrieval.message("13", "I work at Acme and will send the invoice")
        val retrieval = FakeRetrieval(
            conversations = listOf(turn.externalId),
            messages = listOf(turn),
            commitments = listOf(FakeRetrieval.commitment("send the invoice", evidencedBy = "13")),
            statements = listOf(FakeRetrieval.statement("works at", "Acme", evidencedBy = "13")),
        )

        val retrieved = retrieve(retrieval, FakeRetrieval.aContact()).single()

        // By line count for the reason the commitment-and-event case gives: both findings are phrases
        // of the turn itself, so a `contains` on either passes with that finding dropped entirely.
        val lines = retrieved.text.lines()
        assertEquals(3, lines.size, "three texts met under one identifier: $lines")
        assertEquals("I work at Acme and will send the invoice", lines[0])
        assertEquals(
            "Inferred commitment (no date stated), outcome not recorded, last stated 4 days ago: " +
                "send the invoice",
            lines[1],
        )
        assertTrue(lines[2].startsWith("Inferred attribute"), lines[2])
        assertTrue(lines[2].endsWith("works at — Acme"), lines[2])
    }

    @Test
    fun `the same message reached twice is carried once`(): Unit = runBlocking {
        // The other collision, and the one that is a plain duplicate: identical texts collapse rather
        // than being joined to themselves.
        val turn = FakeRetrieval.message("11", "the same words")
        val retrieval = FakeRetrieval(
            conversations = listOf(turn.externalId),
            messages = listOf(turn, turn),
        )

        assertEquals("the same words", retrieve(retrieval, FakeRetrieval.aContact()).single().text)
    }

    // --- doubles and fixtures -------------------------------------------------------------

    private companion object {

        val NOW: Instant = Instant.parse("2026-10-05T12:00:00Z")

        /** One day in seconds, so a fixture's dates read as the distances the cases are about. */
        const val DAY = 86_400L

        /**
         * A due date [days] before today, at the start of its day.
         *
         * **Aligned rather than offset from this instant, so a fixture date is the date it names.**
         * The figure is counted between two day boundaries, so a deadline offset by hours from
         * midday counts the same as this one and the alignment buys no arithmetic — what it buys is
         * that the date in a failure message is the one the case meant, and that a case exercising a
         * deadline with a time of day does so by asking for one rather than by inheriting it from
         * the fixture. [duePastAt] is how a case asks.
         *
         * **A day boundary is not the only shape a stored deadline has**, which is why this is not
         * the only fixture: a date somebody stated resolves to the start of a day, and a time
         * somebody stated is an instant inside one — both forms are in `commitment.due_at` and the
         * proportions are read off those rows rather than stated here. [duePastAt] is the second
         * form, and the case using it is what holds the figure against a deadline carrying a time
         * of day. The figure counts whole days between the two dates, so a deadline at six this
         * evening is past by nothing until tomorrow.
         */
        fun duePast(days: Long): Instant = NOW.truncatedTo(ChronoUnit.DAYS).minus(days, ChronoUnit.DAYS)

        /**
         * A deadline [days] before today but at [hour], which is the form a stated time of day
         * takes.
         *
         * **The form the midnight-aligned fixture cannot discriminate.** Measured from the raw
         * instant to a truncated now, a deadline carrying a time of day reports a day fewer than a
         * reader counting dates arrives at, and every case built on a day boundary passes either
         * way — so the only fixture that can see it is one where the two ends start on different
         * footings.
         */
        fun duePastAt(days: Long, hour: Long): Instant = duePast(days).plus(hour, ChronoUnit.HOURS)

        /**
         * [commitment] evidenced by one message sent [days] before today at [hour].
         *
         * **The fixture the case below it could not be written from.** `FakeRetrieval.commitment`
         * pins every evidence instant to one constant, which is the right default for a case about
         * anything else and flattens exactly what an age clause exists to distinguish — so a case
         * about how the deadline's distance and the record's age are counted against each other
         * has to place the message itself.
         *
         * **[hour] has to fall later in the day than the hour the question is asked, and that is a
         * narrower condition than [duePastAt]'s.** A deadline's figure was measured from a raw
         * instant to a truncated now, so any hour off the boundary separated the two ends; an age
         * is measured raw to raw, so the two agree for every hour up to the one being asked at and
         * differ only beyond it. An hour earlier in the day discriminates nothing, whatever the
         * figure.
         *
         * [single] rather than taking the first of the set: this replaces the evidence rather than
         * adding to it, so handed a commitment that carries two spans it would silently drop one,
         * and the restated fixture a few cases up is exactly such a commitment.
         */
        fun statedAt(commitment: Commitment, days: Long, hour: Long): Commitment = commitment.copy(
            evidence = setOf(
                Evidence(
                    span = commitment.evidence.single().span,
                    sourceTime = duePast(days).plus(hour, ChronoUnit.HOURS),
                ),
            ),
        )

        /** A due date [days] ahead, aligned for the reason [duePast] gives. */
        fun dueAhead(days: Long): Instant = NOW.truncatedTo(ChronoUnit.DAYS).plus(days, ChronoUnit.DAYS)

        /** A limit large enough to be wrong and small enough to be a limit. */
        const val MAX_SENSIBLE = 500

        /**
         * The claim of every commitment in [retrieved], in the order the material offers them.
         *
         * Cut at the last `": "` rather than parsed: every label this rendering puts before the
         * claim is a clause joined with a comma, and the instants inside them carry colons with no
         * space after. Taken from the span's text rather than from the fixture, which is the point
         * — what an ordering case has to read is what the model will be shown.
         */
        fun claims(retrieved: List<Span>): List<String> =
            retrieved
                .filter { it.text.contains("Inferred commitment") }
                .map { it.text.substringAfterLast(": ") }

        /**
         * The shape under test, dating its material in [zone], under this file's fixture clock or
         * under [now] where a case is about the instant the question is asked.
         *
         * UTC by default, which is what every case that is not about a calendar wants: the fixture
         * instants then read as themselves. A case about which day something falls on passes a
         * zone of its own, since a deployment in UTC cannot tell a heading that follows the
         * correspondents from one that follows the instant.
         *
         * [now] is separate from the clock a case is written against because a figure that moves
         * with the hour a question is asked is not a property of the record — so a case asserting
         * one does not move has to ask twice.
         *
         * [index] is what a promise is widened through, and it is empty unless a case scripts it.
         * Empty is the right default for every case that is not about the widening: nothing
         * neighbours anything, so the material is the findings and the tail, which is what the
         * cases either side of those assert. A case about the widening passes an index whose
         * `around` holds the turns, and the fake records which spans were asked about — which is
         * how a case tells a shape that widened from one that merely retrieved.
         */
        fun shape(
            retrieval: FakeRetrieval,
            zone: ZoneId = ZoneOffset.UTC,
            now: Instant = NOW,
            index: FakeIndex = FakeIndex(),
        ) = StatusShape(
            model = FakeModelProvider(),
            retrieval = retrieval,
            lookup = Lookup(FakeEmbeddings(), index),
            grouping = FakeGrouping(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            zone = zone,
        )

        /**
         * One indexed turn, addressed the way a commitment's evidence addresses its own message.
         *
         * Built through [FakeRetrieval.evidence] rather than from a `SpanId` of its own, so a case
         * scripting the widening of a promise cannot address the turn one way and the promise
         * another — which would leave the widening returning a span nothing folds into.
         */
        fun span(nativeId: String, text: String): Span = Span(
            id = FakeRetrieval.evidence(nativeId).span,
            text = text,
            sourceTime = FakeRetrieval.WHEN,
        )

        /** Every reach a run asked for, which must be one and the same for all three entity reads. */
        fun reaches(retrieval: FakeRetrieval): List<Reach> =
            retrieval.asked.mapNotNull {
                when (it) {
                    is FakeRetrieval.Ask.Commitments -> it.reach
                    is FakeRetrieval.Ask.Events -> it.reach
                    else -> null
                }
            }.distinct()

        /** What the shape's retrieval stage put in the exchange. */
        suspend fun retrieve(
            retrieval: FakeRetrieval,
            subject: io.github.ragnorrs.mainbrain.domain.answer.Subject,
            now: Instant = NOW,
            zone: ZoneId = ZoneOffset.UTC,
            index: FakeIndex = FakeIndex(),
        ) =
            assertIs<Progress.Carried>(
                shape(retrieval, zone = zone, now = now, index = index).stages.first().apply(
                    Exchange(Request(text = "where did we leave off"), subject = subject),
                ),
            ).exchange.retrieved

        /**
         * The rendered label of one commitment [claim] falling due at [due], dated in [zone].
         *
         * Three arms of the standing case read the same two lines, and a case about which day a
         * date falls on is clearer as three one-line reads than as three copies of a retrieval.
         */
        suspend fun owed(claim: String, due: Instant, zone: ZoneId): String =
            retrieve(
                FakeRetrieval(commitments = listOf(FakeRetrieval.commitment(claim, "2", due = due))),
                FakeRetrieval.aContact(),
                zone = zone,
            ).single().text

        /**
         * [this] with every run of whitespace reduced to one space.
         *
         * A quoted sentence spans a line break in the source and the prompt keeps it, so an
         * assertion matching the raw text is really asserting where the paragraph was wrapped —
         * which turns a rewrap into a red and invites reflowing the prompt to suit the test.
         */
        /**
         * The words the marker and the instruction have to share.
         *
         * One constant for both halves of the coupling test, so the two assertions cannot be
         * reworded apart from each other — which is the drift they exist to catch.
         */
        const val MARK = "neither the subject nor the reader"

        fun String.collapsed(): String = replace(Regex("\\s+"), " ")

        /**
         * The system prompt the shape assembles, which is where its instruction becomes assertable.
         *
         * Through the stages rather than by reading the constant: the instruction is private to the
         * shape, and a test that reached around that would assert the text exists rather than that
         * it reaches the model.
         */
        suspend fun assembled(retrieval: FakeRetrieval): String =
            assemble(retrieval).system

        /**
         * The request half of the same prompt, which is where a per-request fact is assertable.
         *
         * Separate from [assembled] rather than a flag on it: what the two hold apart is the cache
         * prefix from the thing that changes every call, and a case reading one string could not
         * tell a line that moved between them.
         */
        suspend fun asked(retrieval: FakeRetrieval, zone: ZoneId = ZoneOffset.UTC): String =
            assemble(retrieval, zone).prompt

        private suspend fun assemble(retrieval: FakeRetrieval, zone: ZoneId = ZoneOffset.UTC): Assembled {
            val stages = shape(retrieval, zone).stages
            val retrieved = assertIs<Progress.Carried>(
                stages[0].apply(
                    Exchange(Request(text = "where did we leave off"), subject = FakeRetrieval.aContact()),
                ),
            ).exchange
            return assertIs<Progress.Carried>(stages[1].apply(retrieved)).exchange.assembled!!
        }
    }
}
