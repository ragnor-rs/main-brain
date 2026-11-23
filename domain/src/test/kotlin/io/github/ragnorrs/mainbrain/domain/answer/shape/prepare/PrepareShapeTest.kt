package io.github.ragnorrs.mainbrain.domain.answer.shape.prepare

import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.answer.Exchange
import io.github.ragnorrs.mainbrain.domain.answer.FakeGrouping
import io.github.ragnorrs.mainbrain.domain.answer.FakeRetrieval
import io.github.ragnorrs.mainbrain.domain.answer.Material
import io.github.ragnorrs.mainbrain.domain.answer.Progress
import io.github.ragnorrs.mainbrain.domain.answer.Request
import io.github.ragnorrs.mainbrain.domain.answer.SubjectKind
import io.github.ragnorrs.mainbrain.domain.answer.contacts
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.index.Span
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Call preparation: the account of where things stand, and what to ask.
 *
 * **The preparation half only.** The debrief reads a recording, recordings arrive with a later
 * workstream, and the test that matters most here is the negative one — that nothing in this shape
 * reaches for audio or pretends to summarise a call that has happened.
 */
class PrepareShapeTest {

    @Test
    fun `a call is with a person, so only a contact is accepted`() {
        assertEquals(setOf(SubjectKind.CONTACT), shape(FakeRetrieval()).accepts)
    }

    @Test
    fun `a script is not a thing to offer three framings of`() {
        assertEquals(1..1, shape(FakeRetrieval()).variants)
    }

    @Test
    fun `contact history and what was promised are both retrieved`(): Unit = runBlocking {
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "let us discuss on the call")),
            commitments = listOf(FakeRetrieval.commitment("send the revised scope", evidencedBy = "2")),
        )

        val retrieved = retrieve(retrieval)

        assertTrue(retrieved.any { it.text.contains("let us discuss on the call") })
        assertTrue(retrieved.any { it.text.contains("send the revised scope") })
    }

    @Test
    fun `a commitment names the party that stated it`(): Unit = runBlocking {
        // The second shape that renders findings, so the second place the party can go missing. Wiring
        // the status shape alone would leave every commitment in a call preparation unattributed.
        val party = EntityId.mint()
        val retrieval = FakeRetrieval(
            commitments = listOf(
                FakeRetrieval.commitment("send the revised scope", evidencedBy = "2", statedBy = party),
            ),
            names = mapOf(party to "A Named Person"),
        )

        val text = retrieve(retrieval).single { it.text.contains("send the revised scope") }.text

        assertTrue(text.contains("A Named Person"), "the party is not named beside the claim: $text")
    }

    @Test
    fun `a retrieved turn names whoever wrote it`(): Unit = runBlocking {
        // The third shape that renders a turn, so the third place the author can go missing. A
        // script built from a room's history has other participants' words in it, and a question
        // put into this person's mouth from somebody else's sentence is worse than no question.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(
                FakeRetrieval.message("1", "the scope is still open"),
                FakeRetrieval.message("2", "we never priced the second phase"),
            ),
            turns = mapOf(
                FakeRetrieval.record("1") to FakeRetrieval.turn("The Counterpart"),
                FakeRetrieval.record("2") to FakeRetrieval.turn("Another Participant"),
            ),
        )

        val retrieved = retrieve(retrieval).associateBy { it.id.record.nativeId }

        assertEquals("Sent by The Counterpart: the scope is still open", retrieved.getValue("1").text)
        assertEquals(
            "Sent by Another Participant: we never priced the second phase",
            retrieved.getValue("2").text,
            "a room's other participant's words are offered as the counterpart's own",
        )
    }

    @Test
    fun `a turn whose author the corpus never named carries no name`(): Unit = runBlocking {
        // Nothing rather than nobody, as for a finding's party.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "the scope is still open")),
        )

        assertEquals("the scope is still open", retrieve(retrieval).single().text)
    }

    @Test
    fun `the closing turn names who sent it too`(): Unit = runBlocking {
        // The author read happens after the closures for this: a closure's message is a message, and
        // asked before them its authors would be missing from the one turn an answer points at to
        // say a promise was discharged.
        val done = FakeRetrieval.commitment("send the invoice", evidencedBy = "3")
        val retrieval = FakeRetrieval(
            commitments = listOf(done),
            closures = mapOf(done.id to FakeRetrieval.message("9", "invoice is away")),
            turns = mapOf(FakeRetrieval.record("9") to FakeRetrieval.turn("The Sender")),
        )

        val turn = retrieve(retrieval).single { it.text.contains("invoice is away") }

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
    fun `the turns the brief's own reader wrote are marked as theirs`(): Unit = runBlocking {
        // **The one fact a brief cannot work out for itself.** Every conversation in this corpus
        // has the archive owner as a party, and in a one-to-one they are *the* other party — so
        // without this the shape has no account at all of which turns are the reader's, and the
        // questions it proposes include ones only the reader can answer.
        //
        // The counterpart's turn is the calibration: a mark on every turn alike marks none.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(
                FakeRetrieval.message("1", "I sent the money on Tuesday"),
                FakeRetrieval.message("2", "let us go over it on the call"),
            ),
            turns = mapOf(
                FakeRetrieval.record("1") to FakeRetrieval.turn("The Owner", isSelf = true),
                FakeRetrieval.record("2") to FakeRetrieval.turn("The Counterpart"),
            ),
        )

        val retrieved = retrieve(retrieval).associateBy { it.id.record.nativeId }

        assertEquals(
            "Sent by The Owner (the person this brief is for): I sent the money on Tuesday",
            retrieved.getValue("1").text,
        )
        assertEquals(
            "Sent by The Counterpart: let us go over it on the call",
            retrieved.getValue("2").text,
            "the counterpart's turn carries the reader's mark, so the mark distinguishes nobody",
        )
    }

    @Test
    fun `a turn written by neither the person the call is with nor the reader is marked`(): Unit = runBlocking {
        // **The defect this guards is a brief about one person carrying other people's business.**
        // Most of a subject's threads are rooms, and this shape reads a longer tail than the status
        // one, so proportionally more of this material is written by somebody the call is not with
        // — and a turn offered with a name and nothing else is composed into a question put to the
        // person on the call. Nothing downstream sees it: the sentence is fluent, the citation
        // resolves, and the grounding gate asks whether a span was retrieved rather than whose
        // matter it recorded.
        //
        // **All four states in one fixture, because the marker is only worth anything if it is
        // absent from the rest.** A clause on every turn would say nothing. The reader's own turn
        // carries the label the case above pins and not this mark: the two are separate reads of
        // one turn, and a rendering that confused them would answer the wrong question.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(
                FakeRetrieval.message("1", "I will send the figures on Friday"),
                FakeRetrieval.message("2", "we cancelled the standup, I am away"),
                FakeRetrieval.message("3", "any word on those figures?"),
                FakeRetrieval.message("4", "something was said"),
            ),
            turns = mapOf(
                FakeRetrieval.record("1") to FakeRetrieval.turn("The Counterpart", anotherParty = false),
                FakeRetrieval.record("2") to FakeRetrieval.turn("Another Participant", anotherParty = true),
                FakeRetrieval.record("3") to
                    FakeRetrieval.turn("The Reader", isSelf = true, anotherParty = false),
                FakeRetrieval.record("4") to FakeRetrieval.turn(author = null, anotherParty = null),
            ),
        )

        val retrieved = retrieve(retrieval).associateBy { it.id.record.nativeId }

        assertEquals(
            "Sent by Another Participant, who is neither the subject nor the reader: " +
                "we cancelled the standup, I am away",
            retrieved.getValue("2").text,
            "a third party's turn is offered as material about the person the call is with, which " +
                "is how a standup somebody else cancelled becomes a question asked on this call",
        )
        assertEquals(
            "Sent by The Counterpart: I will send the figures on Friday",
            retrieved.getValue("1").text,
            "the words of the person the call is with are denied, which would leave the brief " +
                "with nothing of theirs to prepare from",
        )
        assertEquals(
            "Sent by The Reader (the person this brief is for): any word on those figures?",
            retrieved.getValue("3").text,
            "the reader's own turn is marked as another participant's, and what the reader said " +
                "is the other half of the relationship the call is being prepared for",
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
        // known not to be the counterpart's while the corpus names nobody for it. There is then no
        // name for the clause to join, which is why the fact carries a leading form — without one
        // the marker would vanish for exactly the turns a brief can say least about.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "we cancelled the standup")),
            turns = mapOf(FakeRetrieval.record("1") to FakeRetrieval.turn(author = null, anotherParty = true)),
        )

        assertEquals(
            "Said by neither the subject nor the reader: we cancelled the standup",
            retrieve(retrieval).single().text,
        )
    }

    @Test
    fun `a closing turn written by another party carries the mark too`(): Unit = runBlocking {
        // **Once the recent turns carry the mark, its absence says the counterpart or the reader
        // wrote the turn** — so a rendering that skips it does not merely lose a fact, it asserts
        // the wrong one. A promise is frequently reported done by somebody other than the party who
        // gave it, so this is the ordinary case rather than a corner. The closure's turn is in the
        // author read already, so the defect would be discarding the answer at the rendering.
        val commitment = FakeRetrieval.commitment("send the invoice", evidencedBy = "2")
        val retrieval = FakeRetrieval(
            commitments = listOf(commitment),
            closures = mapOf(commitment.id to FakeRetrieval.message("9", "invoice is away")),
            turns = mapOf(
                FakeRetrieval.record("9") to FakeRetrieval.turn("Another Participant", anotherParty = true),
            ),
        )

        val turn = retrieve(retrieval).single { it.text.contains("invoice is away") }

        assertTrue(
            turn.text.contains("closing a commitment"),
            "the closing turn lost its own marker: ${turn.text}",
        )
        assertTrue(
            turn.text.contains("neither the subject nor the reader"),
            "a third party reported the promise done and the turn says the counterpart or the " +
                "reader did, which is what an unmarked turn now means: ${turn.text}",
        )
    }

    @Test
    fun `a closing turn the corpus names nobody for carries no dangling clause`(): Unit = runBlocking {
        // The mark is a relative clause joining the name, and a closing turn leads with a label and
        // an instant rather than with a name — so with no name there is nothing for it to qualify
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

        val turn = retrieve(retrieval).single { it.text.contains("invoice is away") }

        assertFalse(
            turn.text.contains("who is neither"),
            "a relative clause is rendered with no name in front of it, so it qualifies the " +
                "instant: ${turn.text}",
        )
    }

    @Test
    fun `the author read asks about the contacts of the person the call is with`(): Unit = runBlocking {
        // Naming the author is half the fact; whether that author is the person being called is the
        // other half, and the read cannot answer it without being told who to compare against. A
        // shape that forgot the argument gets nulls back and marks nothing, which reads exactly
        // like a corpus in which every turn is the counterpart's.
        //
        // This shape accepts a contact and nothing else, so the comparison always resolves and
        // there is no arm of it where the marker is inert.
        val subject = FakeRetrieval.aContact()
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "one")),
        )

        assertIs<Progress.Carried>(
            shape(retrieval).stages.first().apply(
                Exchange(Request(text = "prepare me for the call"), subject = subject),
            ),
        )

        assertEquals(
            subject.contacts(),
            retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Turns>().single().subject,
            "the comparison is on the contact and resolves through the merge, so the contacts the " +
                "subject reaches are what the read has to be given",
        )
    }

    @Test
    fun `a turn the reader wrote that closed a promise is marked too`(): Unit = runBlocking {
        // A closing message is a turn of the thread like any other. Marked on one route into the
        // material and not the other, the material would say the reader wrote their recent turns
        // and somebody else reported the promise done — which is exactly the attribution this
        // shape's instruction then reasons from.
        val done = FakeRetrieval.commitment("send the invoice", evidencedBy = "3")
        val retrieval = FakeRetrieval(
            commitments = listOf(done),
            closures = mapOf(done.id to FakeRetrieval.message("9", "invoice is away")),
            turns = mapOf(FakeRetrieval.record("9") to FakeRetrieval.turn("The Owner", isSelf = true)),
        )

        val turn = retrieve(retrieval).single { it.text.contains("invoice is away") }.text

        assertTrue(
            turn.contains("sent by The Owner (the person this brief is for)"),
            "the closing turn is the reader's own and nothing says so: $turn",
        )
    }

    @Test
    fun `a turn the corpus cannot place as the reader's is left unmarked`(): Unit = runBlocking {
        // The port collapses *not ours* and *nothing is known* into one false, so an unmarked turn
        // says the mark could not be established. Left unmarked the instruction's rules do not
        // fire on it, which costs a question that should not have been asked; marked wrongly, the
        // reader's own words are handed to the person they are calling.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "the scope is still open")),
            turns = mapOf(FakeRetrieval.record("1") to FakeRetrieval.turn("Somebody")),
        )

        assertEquals("Sent by Somebody: the scope is still open", retrieve(retrieval).single().text)
    }

    @Test
    fun `the instruction names the reader's mark, in the words the material carries it`(): Unit =
        runBlocking {
            // **Read off the rendering rather than restated here**, so the instruction and the
            // material cannot describe one mark in two strings that agree only until one is
            // edited. What this catches is the paragraph being dropped or the marker reworded
            // without it — after which the mark is rendered and the model is told nothing about
            // what it means, which is this build's recorded shape for a fact that reaches the
            // model and never the reader.
            val marked = requireNotNull(
                Material.of(
                    FakeRetrieval.message("1", "body"),
                    "The Owner",
                    own = Material.OwnTurn.READER,
                ),
            ).text
            val marker = marked.removePrefix("Sent by The Owner").removeSuffix(": body")
                .trim(' ', '(', ')')

            val system = assembledSystem()

            assertTrue(
                system.contains(marker),
                "the material marks the reader's own turns and the instruction never says so: $system",
            )
        }

    /**
     * The proposed questions are asked for as a list, and the marker is part of what is written.
     *
     * **Cheap on purpose, and the alternative is recorded as a card.** A question asserts nothing
     * about the material, so it carries no citation, so nothing in the reply marks it — the marker a
     * reader sees in front of a finding is the presentation layer's, and it is put there because the
     * line is a cited claim. Giving the interface a kind that means *an item of a list* independently
     * of what it cites is the durable fix and is blocked behind a refactor of the reader; asking for
     * the dash in the prose costs one clause and reaches a reader as the characters the model typed,
     * which is what every line of an answer does.
     *
     * A prose pin, and knowingly weak: it holds that the ask is still there, not that it is obeyed.
     * An en dash rather than the bullet a claim carries, so that a line nothing checked does not
     * arrive looking like one the grounding gate passed.
     */
    @Test
    fun `the questions are asked for as a list, marked unlike a cited claim`(): Unit = runBlocking {
        val system = assembledSystem()

        assertTrue(
            system.contains("open it with an en dash"),
            "nothing asks for the questions to be marked, so they arrive as paragraphs beside " +
                "marked findings: $system",
        )
        assertFalse(
            system.contains("open it with a bullet"),
            "the questions are asked for behind the mark a cited claim carries, so a line nothing " +
                "checked arrives looking like one that passed the grounding gate: $system",
        )
    }

    /**
     * A shared absence names the items it covers, which is what makes the line placeable at all.
     *
     * **Two rules here cannot both be obeyed while the sentence only summarises.** The say-once rule
     * puts it at the head of the account, and the next sentence forbids opening a section with a
     * line about the items beneath it. A brief read on the real archive took the placement and broke
     * the prohibition: its first line said none of the undertakings below carried a recorded
     * outcome, about items the reader had not reached. Naming them is what makes the line about
     * something a reader can pick out.
     */
    @Test
    fun `a shared absence names the items it covers`(): Unit = runBlocking {
        val system = assembledSystem()

        assertTrue(
            system.contains("name the items it covers"),
            "the group statement is asked for without being asked to identify its group, so it " +
                "opens a section by summarising items the reader has not reached: $system",
        )
    }

    /**
     * The language a brief comes back in, read off a reply that came back in the wrong one.
     *
     * **A request for this shape is a command and a name far more often than it is a sentence, and
     * a name carries no language.** So an instruction asking for the question's language asks for
     * something that is not there, and what the reply is written in is then whatever the model
     * settles on — which on a Russian corpus, asked through a Latin-script name, was English. The
     * same question asked in Russian came back in Russian, so the first sentence does work where
     * there is a language to read; what was missing is the case where there is none.
     *
     * A prose pin, and the weak kind knowingly: nothing deterministic can see the language of
     * generated prose, because the backend holds no locale by design — it emits keys and the
     * interface resolves them. This clause is the whole of what decides it.
     */
    @Test
    fun `the instruction names a language to answer in where the question carries none`(): Unit = runBlocking {
        val system = assembledSystem()

        assertTrue(
            system.contains("answer in the language of"),
            "a request that is a command and a name carries no language, so nothing says what " +
                "the brief should be written in: $system",
        )
    }

    @Test
    fun `the instruction keeps a question to what the other party can answer`(): Unit = runBlocking {
        // **The measured defect: three of six proposed questions were ones the reader could answer
        // without the call** — whether a payment they were the recipient of had been made, what
        // came of a meeting they sat in, whether anybody replied to something they sent. A prose
        // pin, the weak kind of assertion and knowingly so: the mark on the turns is only worth
        // rendering while the model is told what follows from it.
        val system = assembledSystem()

        assertTrue(
            system.contains("a question you propose is one its answer is not already with the reader"),
            "nothing stops the brief asking the other side about what the reader is holding: $system",
        )
        // **The arm for an unmarked turn, pinned beside the rule it bounds**, which is this
        // prompt's own convention for every marker it reads and the one a sibling rule's pin was
        // once found missing. The port collapses *not ours* and *nothing is known* into one
        // false, so a turn the reader wrote can arrive unmarked — and a rule stated as a
        // universal would then license reading every unmarked turn as somebody else's.
        assertTrue(
            system.contains("Where no turn carries it, the material does not say which party is reading"),
            "the reader's mark is stated as a universal, so its absence means the wrong thing: $system",
        )
        // **The marker is a label and not a name**, which only the rendering makes hazardous: it
        // is glued to the author's name, and this is the one shape that names parties in the
        // third person — so without this the parenthesis reaches the reader inside a finding.
        assertTrue(
            system.contains("no part of anybody's name: never write it into the brief"),
            "the mark on a turn can be written out as part of the party's name: $system",
        )
        // **And the rules stop at the two parties to the call.** The history includes rooms, so
        // collapsing everyone who is not the reader into the person being called would drop a
        // question only a third participant could answer — the fix suppressing what it was
        // written to protect.
        assertTrue(
            system.contains("None of that reaches a third person"),
            "a question only a third participant can answer is suppressed with the rest: $system",
        )
        // **The half that survived the first wording**, found by reading the live replies rather
        // than reasoned about: an item both of them were party to carries a legitimate forward
        // question — when shall we do it — and the model fused that with the half the reader
        // holds, asking whether it had happened at all in the same sentence. Naming the case was
        // not enough; the sentence has to say which half is askable.
        assertTrue(
            system.contains("what is left to ask about such an item is what comes next"),
            "a question may still ask whether a thing the reader was party to happened: $system",
        )
        // The findings half, which is the same fault in the other direction: the corpus's silence
        // about something the reader was present for is a gap in the record, and reported as an
        // unknown outcome it reads as the system not knowing who it is talking to.
        assertTrue(
            system.contains("the gap is in the record and not in what they know"),
            "the corpus's silence is still rendered as the reader's ignorance: $system",
        )
        assertTrue(
            system.contains("Do not report it as a finding about the person they are calling"),
            "an absence of reply to the reader's own message is still a finding: $system",
        )
        // **The guard that keeps the fix from emptying the brief.** Every rule above takes
        // something away, and a thin record under all three is a reply with nothing in it — which
        // is what the no-material answer looks like, and reads as a broken answer path rather than
        // as a quiet conversation.
        assertTrue(
            system.contains("None of this takes an item out of the brief"),
            "the rules above can suppress items until nothing is left: $system",
        )
        // **And which rules may take one out, because several now remove things for different
        // reasons.** The length bound drops whole items by the precedence stated with it, the
        // finished-matter rule by there being nothing left to act on, and the third-party rule by
        // whose matter it is; the question rules drop only the question. Left unscoped, the guard
        // above reads as overriding them, and the set resolves to whichever the model reads last.
        // Pinned as the enumeration rather than as one of its members, so that a further rule
        // removing an item without naming it here is what reds this — which is how the
        // finished-matter rule came to be named, and why the count is asserted beside the members.
        assertTrue(
            system.contains("Three rules leave an item out and no others"),
            "the no-suppression guard reads as overriding the rules that do remove an item: $system",
        )
        assertTrue(
            system.contains("the rule after this one about a matter belonging to somebody else"),
            "the enumeration omits the third-party rule, so that rule and this guard contradict " +
                "each other and the model picks one: $system",
        )
        // The third member, which is what the count above was written to catch the arrival of.
        assertTrue(
            system.contains("the rule above about a matter the record reports as done"),
            "the enumeration omits the finished-matter rule, so that rule and this guard " +
                "contradict each other: $system",
        )
        // **And the item goes back where it belongs, which only a live reading found.** Two
        // failures, one sentence apart. Told only that the account stays, the model wrote the
        // withheld items up among the questions as a sentence explaining why it was not asking
        // about them — a line in the questions list that is not a question. Told additionally
        // not to report its own reasoning, it emitted that reasoning as a cited claim line
        // instead, a citation hung off commentary. Both are answered by saying where the remark
        // belongs rather than by forbidding it: on the item, in what is reported about it.
        assertTrue(
            system.contains("as part of what you report about it rather than as a question or as a remark of its own"),
            "an item with no question left is written up as a question or as a remark: $system",
        )
        // **Where the two rules about what a question may assume meet.** The card about
        // presupposed outcomes says to ask what came of an undertaking rather than how it went.
        // On an item the reader was a party to, asking what came of it is this card's defect —
        // the reader holds that answer. So the exception is stated where a reader of the prompt
        // meets it, and pinned, because a later edit to either rule is exactly when one silently
        // swallows the other.
        assertTrue(
            system.contains("the rule below against presupposing an outcome does not apply"),
            "the two rules about what a question may assume are left to collide: $system",
        )
    }

    @Test
    fun `the instruction says to attribute a message to whoever is marked on it`(): Unit = runBlocking {
        // A prose pin, which is the weak kind of assertion, knowingly: the label on a turn is only
        // worth rendering while the model is told what it is for. Pinned per shape rather than once,
        // because each states the rule in its own words and one shape's pin cannot stand in for
        // another's — a paragraph deleted from this prompt would otherwise leave the suite green.
        val system = assembledSystem()

        assertTrue(
            system.contains("attribute a message to the person marked on it and to nobody else"),
            "nothing tells the model whose words a turn carries: $system",
        )
        // And that the rule is about messages, not about a finding's party. A commitment's block
        // carries a party and no sender, so a rule written over "the material" tells the model to
        // discard the party this shape reads a second time to supply.
        assertTrue(
            system.contains("This is about messages only"),
            "the attribution rule is not scoped to messages, so it reaches a finding's party: $system",
        )
    }

    @Test
    fun `the instruction says what a marked message means and does not`(): Unit = runBlocking {
        // Prose pins, which are the weak kind of assertion and knowingly so: the marker is only
        // worth rendering while the model is told what follows from it.
        //
        // **Pinned on the operative clauses and not on the framing ones.** A case asserting only
        // "this mark exists" passes with the redirect deleted — and the redirect is the only thing
        // that makes the demotion obeyable, since a rule that says where something does not go and
        // never says where it does leaves the model to choose.
        //
        // This case covers what the mark *means*; what the marked material may *become* is the
        // case below. Two cases rather than one because they fail for different reasons and a
        // single name could only describe one of them.
        val system = assembledSystem()

        assertTrue(
            system.contains("The mark is about who wrote it and not about what it says"),
            "nothing states the marker's bound, so a model reads it as a claim about whose matter " +
                "the message reports rather than about who wrote it: $system",
        )
        assertTrue(
            system.contains("it is not what this call turns on"),
            "nothing says what follows from the marker, so another participant's business is " +
                "still prepared as the counterpart's own: $system",
        )
        assertTrue(
            system.contains(
                "report it in the standing-in line below rather than as an item of its own, saying " +
                    "whose matter it is",
            ),
            "the redirect names no destination, so the rule says where a finding may not go and " +
                "never where it does, which a model resolves by choosing: $system",
        )
        assertTrue(
            system.contains("asks them to account for what was never theirs"),
            "nothing says why a question is the worst place for it, which is the one failure read " +
                "aloud to the other party: $system",
        )
        // The sibling rule above says an undertaking somebody else gave is answerable by whoever
        // gave it. Read alone that licenses putting it to this person, so the two are composed
        // explicitly rather than left to the model to reconcile.
        assertTrue(
            system.contains("is not a reason to put it to this person"),
            "the third-party rule and the rule about who can answer an undertaking are left to " +
                "contradict each other: $system",
        )
        // An unmarked turn may be the counterpart's, the reader's, or one the record cannot
        // place: the material marks only the settled yes. Without this arm the rule reads as a
        // universal and absence becomes a claim, which is the same fault as reading the reader
        // label's collapsed negative as one.
        assertTrue(
            system.contains("A message arriving without that mark is not thereby this person's"),
            "the rule has no arm for an unmarked turn, so absence of the mark is read as a claim " +
                "that the person this call is with wrote it: $system",
        )
    }

    @Test
    fun `the instruction bounds what a marked message may become in the brief`(): Unit = runBlocking {
        // The other half of the rule, and the half that was measured rather than reasoned: an
        // inventory of the room is what the bound's absence produces, so these are the clauses
        // least safe to drop as redundant. Both failure directions are pinned, because a rule
        // carrying only one of them trades a noisy brief for an empty one or the reverse.
        val system = assembledSystem()

        assertTrue(
            system.contains("That is not a reason to leave material out"),
            "nothing guards the opposite failure, and a brief emptied of everything but the " +
                "counterpart's own words reads as a broken answer path: $system",
        )
        assertTrue(
            system.contains("it is not licence to inventory the room"),
            "nothing bounds the other half, and a subject with little of their own material gets " +
                "a brief that writes out every other participant's week: $system",
        )
        assertTrue(
            system.contains(
                "What earns an item of its own, against the bound the length rule below sets, is " +
                    "a marked message bearing on that person's own items",
            ),
            "the bound is stated as a prohibition with no test for what passes it, or its forward " +
                "reference to the length rule is orphaned: $system",
        )
        assertTrue(
            system.contains("That standing-in line is an assertion about the material like any other"),
            "the summarising line is asked for without its citations, and an uncited claim line " +
                "takes the whole alternative with it: $system",
        )
        // The standing-in line is mandatory and carries citations, so whether it spends one of the
        // five decides whether collapsing the room buys anything at all. The bound names exactly
        // one other exemption, the lead, so silence here is a question the model has to resolve.
        assertTrue(
            system.contains("it does not count against the length bound"),
            "the line standing for what is left out is neither counted nor exempted, so it may " +
                "consume the very slots collapsing the room was meant to free: $system",
        )
        // The brief has no recent-events section to demote a matter into, so its alternative is a
        // line standing for what is left out — which the say-once rule forbids in the shape of a
        // line summarising the items beneath a heading. Without this clause the two rules read as
        // contradicting each other, and a model resolving that picks one.
        assertTrue(
            system.contains("it is not the summarising line forbidden above"),
            "the line standing for what is left out is not told apart from the summarising line " +
                "the say-once rule forbids, so the two rules contradict: $system",
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
            assembledSystem().contains("\"$MARK\""),
            "the instruction does not quote the marker's own words, so the rule and the rendering " +
                "can be reworded apart",
        )
        assertTrue(
            retrieve(named).single().text.contains(MARK),
            "the joining form stopped carrying the words the instruction quotes",
        )
        assertTrue(
            retrieve(unnamed).single().text.contains(MARK),
            "the leading form stopped carrying the words the instruction quotes",
        )
    }

    @Test
    fun `no events are read, because preparation is about what is unresolved`(): Unit = runBlocking {
        // The status shape reads what is coming; this one reads what is open. Asking for both would
        // make the two shapes the same retrieval with two prompts.
        val retrieval = FakeRetrieval()

        retrieve(retrieval)

        assertTrue(retrieval.asked.none { it is FakeRetrieval.Ask.Events })
    }

    @Test
    fun `the debrief half is not built, so nothing here is asked for a recording`(): Unit = runBlocking {
        // The port offers no audio and this shape asks for none: the two reads it makes are the
        // preparation half's, and the debrief arrives as a shape of its own.
        val retrieval = FakeRetrieval()

        retrieve(retrieval)

        val preparationReads = retrieval.asked.count {
            it is FakeRetrieval.Ask.Commitments || it is FakeRetrieval.Ask.Messages
        }
        assertEquals(2, preparationReads)
    }

    @Test
    fun `nothing is retrieved for a request with no subject`(): Unit = runBlocking {
        val progress = shape(FakeRetrieval()).stages.first().apply(Exchange(Request(text = "prepare me")))

        assertTrue(assertIs<Progress.Carried>(progress).exchange.retrieved.isEmpty())
    }

    @Test
    fun `a promise already reported as done is not offered as something to chase`(): Unit = runBlocking {
        // **The one thing this shape must not do**, and it is why it reads closures at all despite
        // being about what to ask on a call rather than about state. It is read immediately before
        // somebody picks up the phone, so a promise the corpus records as discharged appearing in
        // the same list as the ones nothing has settled is an opening question about finished work.
        val chase = FakeRetrieval.commitment("chase the contract", evidencedBy = "2")
        val done = FakeRetrieval.commitment("send the invoice", evidencedBy = "3")
        val retrieval = FakeRetrieval(
            commitments = listOf(chase, done),
            closures = mapOf(done.id to FakeRetrieval.message("9", "invoice is away")),
        )

        val retrieved = retrieve(retrieval)

        val closed = retrieved.single { it.text.contains("send the invoice") }.text
        val open = retrieved.single { it.text.contains("chase the contract") }.text
        assertTrue(closed.contains("closed by the message sent"), "the closure does not reach the material: $closed")
        assertTrue(open.contains("outcome not recorded"), "the unclosed promise is not marked: $open")
        assertTrue(
            retrieved.any { it.text.contains("invoice is away") },
            "the closing turn is not in the material, so nothing can cite it: $retrieved",
        )
        // **And the turn reads before what was inferred from it.** A closing message frequently
        // states a promise of its own — "done, I'll invoice tomorrow" — so its span collides with
        // that commitment's, and the folding rule lets the first occurrence set the position.
        // Appended after the inferences, the inference would win the position and the turn would
        // read under it.
        val both = retrieved.single { it.text.contains("invoice is away") }.text
        if (both.contains("Inferred commitment")) {
            assertTrue(
                both.indexOf("invoice is away") < both.indexOf("Inferred commitment"),
                "the inference reads before the turn it was read out of: $both",
            )
        }
    }

    @Test
    fun `what is owed leads the material, and what is finished comes last`(): Unit = runBlocking {
        // **The one ordering a brief read in the minute before a call is for.** The store orders by
        // recency of evidence, which has no relationship to what fell due: a thing agreed a
        // fortnight ago for last week is exactly the item that goes unnoticed, and it was arriving
        // behind an exchange from this morning.
        //
        // The fixture is deliberately handed to the shape in the wrong order — discharged first,
        // overdue last — so an unsorted read cannot produce the expected list by accident. That is
        // what makes this the case that reddens if `Material.owedFirst` is not called, which the
        // fold case below cannot hold.
        //
        // Evidenced by messages outside the retrieved tail, because a commitment read out of a turn
        // in it is folded to that turn's position whatever the sort returned — see the case after.
        // Held in a val, because the fixture mints an identity per call: built inline in both
        // places, the closure would key on a commitment that is not the one in the list and the
        // discharged item would arrive with no outcome at all.
        val discharged = FakeRetrieval.commitment("the discharged one", "2")
        val retrieval = FakeRetrieval(
            commitments = listOf(
                discharged,
                FakeRetrieval.commitment("the undated one", "3"),
                FakeRetrieval.commitment("the ahead one", "4", due = dueAhead(5)),
                FakeRetrieval.commitment("the one due today", "5", due = dueAhead(0)),
                FakeRetrieval.commitment("the less overdue one", "6", due = duePast(2)),
                FakeRetrieval.commitment("the overdue one", "7", due = duePast(9)),
            ),
            closures = mapOf(discharged.id to FakeRetrieval.message("9", "done")),
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
            claims(retrieve(retrieval)),
        )
    }

    @Test
    fun `the computed order is lost where a commitment shares a span with a retrieved turn`(): Unit =
        runBlocking {
            // **Why this shape's instruction states the order as something to write in rather than
            // as a fact about the material**, which is the same reason the status shape's does. A
            // commitment is addressed by the span it was read out of and this shape offers its
            // messages before its findings, so one evidenced by a turn still in the recent tail is
            // folded into that turn's block and shown at its position whatever the sort returned.
            //
            // How much of the material that reaches is a property of the message bound against the
            // corpus rather than a constant, and position carries the order for the rest. What
            // carries it either way is the standing on each item's own label, asserted below: an
            // instruction claiming the material arrives overdue-first would be false exactly here.
            //
            // **This case does not cover the sort, and that is stated rather than assumed.** The
            // order it asserts is also the order an unsorted read produces, so bypassing
            // `Material.owedFirst` leaves it green — the case above is the one that reddens.
            val turn = FakeRetrieval.message("7", "we will confirm the figures next month")
            val retrieval = FakeRetrieval(
                conversations = listOf(turn.externalId),
                messages = listOf(turn),
                commitments = listOf(
                    FakeRetrieval.commitment("the ahead one", "7", due = dueAhead(2)),
                    FakeRetrieval.commitment("the overdue one", "8", due = duePast(10)),
                ),
            )

            val retrieved = retrieve(retrieval)

            assertEquals(
                listOf("the ahead one", "the overdue one"),
                claims(retrieved),
                "the fold no longer puts a commitment at its turn's position — if the material " +
                    "really does arrive in the computed order now, this shape's instruction may " +
                    "say so rather than sending the model to each item's own standing",
            )
            val folded = retrieved.single { it.text.contains("the overdue one") }.text
            assertTrue(
                folded.contains("now past"),
                "the standing is the only thing carrying this once position has been lost, and " +
                    "it is not on the item: $folded",
            )
        }

    @Test
    fun `a commitment says which party it obliges, where the store places one`(): Unit = runBlocking {
        // **A different fact from the party that stated it, and not a better-sourced version of
        // it.** A brief decides whether to chase a thing or to report on it, which is a question
        // about who it is on — and the only account of that was whatever the restating model wrote
        // into the claim's prose. The obliged party is read in the same name read as the stater, so
        // the case that asserts the most is the one where the two differ.
        val stater = EntityId.mint()
        val obliged = EntityId.mint()
        val retrieval = FakeRetrieval(
            commitments = listOf(
                FakeRetrieval.commitment(
                    "the revised scope is with the client by Friday",
                    evidencedBy = "2",
                    statedBy = stater,
                    obligedParty = obliged,
                ),
            ),
            names = mapOf(stater to "The Relayer", obliged to "The Other Party"),
        )

        val text = retrieve(retrieval).single { it.text.contains("revised scope") }.text

        assertTrue(
            text.contains("obliges The Other Party"),
            "nothing says which party the promise is on, so a brief cannot tell chasing from " +
                "reporting: $text",
        )
        assertTrue(
            text.contains("stated by The Relayer"),
            "the stater is lost, so the two parties cannot be told apart: $text",
        )
    }

    @Test
    fun `a commitment the store places on nobody carries no direction marker`(): Unit = runBlocking {
        // Nothing rather than nobody, as for a turn's author. An absent marker is read as this
        // layer having no account of direction — the claim's prose is the fallback, and the
        // instruction is what says so. A placeholder here would be a name as far as a reader is
        // concerned. How often a row carries no obliged party is a property of that column beside
        // `stated_by` and is read off the two rather than asserted here — but it is common enough
        // that this is the ordinary case rather than the edge.
        val stater = EntityId.mint()
        val retrieval = FakeRetrieval(
            commitments = listOf(
                FakeRetrieval.commitment("send the revised scope", evidencedBy = "2", statedBy = stater),
            ),
            names = mapOf(stater to "A Named Person"),
        )

        val text = retrieve(retrieval).single { it.text.contains("send the revised scope") }.text

        assertFalse(text.contains("obliges"), "a row placing the thing on nobody invents a party: $text")
    }

    @Test
    fun `a commitment with no recorded outcome says how long ago it was last stated`(): Unit = runBlocking {
        // **Five items ending in the same pair of labels is a list a reader cannot rank.** That an
        // outcome is not recorded says whether the field is filled; how long the thing has sat
        // unmentioned is what decides whether it is worth a minute of the call. An item with no
        // recorded outcome and no date carries no other distinguishing clause at all, so this is
        // the only thing separating one from the next.
        //
        // The discharged half is the calibration: a clause on every item alike points at none, and
        // how long a kept promise sat before it was kept is history rather than something to raise.
        val settled = FakeRetrieval.commitment("the settled one", "3")
        val open = FakeRetrieval(commitments = listOf(FakeRetrieval.commitment("the waiting one", "2")))
        val closed = FakeRetrieval(
            commitments = listOf(settled),
            closures = mapOf(settled.id to FakeRetrieval.message("9", "done")),
        )

        val openText = retrieve(open).single().text
        val closedText = retrieve(closed).single { it.text.contains("the settled one") }.text

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
    fun `the age counts the days a reader counts, not the periods that have elapsed`(): Unit = runBlocking {
        // The same property the status shape is held to, asserted here because this shape asks for
        // the clause separately and a change to one caller's rendering would not move the other.
        // The brief is read minutes before a call, where the figure deciding what to raise sits
        // beside a heading dating the same message: counted as elapsed periods it is a day short
        // of the reader's own count for anything written later in the day than the hour they ask.
        val lateYesterday = Instant.parse("2026-10-04T22:00:00Z")
        val commitment = FakeRetrieval.commitment("the waiting one", "2").let {
            it.copy(
                evidence = setOf(
                    Evidence(span = FakeRetrieval.evidence("2").span, sourceTime = lateYesterday),
                ),
            )
        }

        val text = retrieve(FakeRetrieval(commitments = listOf(commitment))).single().text

        assertTrue(
            text.contains("last stated 1 day ago"),
            "a thing last stated at $lateYesterday, against a clock reading $NOW, is not " +
                "reported as having been stated a day ago: $text",
        )
    }

    @Test
    fun `the instruction sends the model to each item's own standing for the order`(): Unit = runBlocking {
        // A prose pin, the weak kind of assertion and knowingly so: the standing and age markers
        // are only worth rendering while the model is told what to do with them, and position
        // carries the order for most of the material but not for a commitment folded into its
        // turn's block. Pinned on this shape rather than once, because the status shape's pin
        // cannot stand in for it — a paragraph deleted from this prompt would leave the suite green.
        val system = assembledSystem()

        assertTrue(
            system.contains("Write them in that order"),
            "nothing tells the model which end of the list to lead with: $system",
        )
        assertTrue(
            system.contains("read it from there rather than from where the item sits"),
            "the model is sent to the order of the material, which a folded commitment makes " +
                "false: $system",
        )
        // A label over a group, which is this instruction's own hazard once it asks for four
        // groups in one order: asked for the order and given no rule about labelling it, a reply
        // gathers the overdue items, one still ahead and the undated group under one heading
        // saying the deadlines have passed. A reader skimming takes the label for the items.
        assertTrue(
            system.contains("has to be true of everything under it"),
            "the order is asked for with no rule about labelling the groups it creates: $system",
        )
        // The undated guard, which the age clause is what makes reachable: an item with no date
        // now carries how long ago it was mentioned, and nothing otherwise stops that instant
        // being read as the date.
        assertTrue(
            system.contains("do not treat it as recent, imminent or over"),
            "an undated item can be placed by when it was mentioned: $system",
        )
        assertTrue(
            system.contains("how long ago it was last stated"),
            "the age clause is rendered and nothing says what it is for: $system",
        )
        // **Reading the marker is not reporting it.** Without this the whole direction half can
        // stay inside the prompt: the fact reaches the model and never the reader, which is the
        // defect shape a citation that never arrives already cost this build once.
        assertTrue(
            system.contains("Name the party each undertaking you report is placed on"),
            "the model is told where to read direction from and never asked to report it: $system",
        )
        assertTrue(
            system.contains("marked on it as \"obliges\""),
            "the direction marker is rendered and nothing tells the model to read it: $system",
        )
        // **The fallback half, pinned beside the marker half.** The marker reaches only the rows
        // that carry an obliged party; on the rest the claim's prose is the only account there is,
        // and an instruction naming the marker alone would leave the model nothing to read on
        // them. Deleting this clause would otherwise leave the suite green.
        assertTrue(
            system.contains("where it carries no such marker it is in the claim's own words"),
            "nothing says where direction is read from on a row that places nobody: $system",
        )
    }

    @Test
    fun `the instruction refuses a recap, an absence said twice, and an item left unfinished`(): Unit =
        runBlocking {
            // **Three rules the status shape already carried, measured again on a rendered brief.**
            // Its two lead items narrated the exchange turn by turn; five items ended in the same
            // pair of clauses saying no date was stated and no outcome recorded; one closed in a
            // dash and a bare word with no sentence around it. A rule seated in one shape says
            // nothing about another, which is how all three arrived back in this one — so they are
            // pinned here rather than left to that shape's own case, which would stay green with
            // this prompt empty of them.
            //
            // **The fragment is a writing fault and not a leak, which the store was read to
            // settle.** Nothing stored carries that shape, and a bare particle is an ordinary thing
            // for a turn to say — so the composer quoted one and left it unattached. A prose rule is
            // therefore the whole fix; see the instruction's own note.
            //
            // Prose pins, the weak kind and knowingly so: they catch a clause being dropped, not a
            // sentence elsewhere working against it, and only a brief read cold says whether the
            // model obeys them.
            val system = assembledSystem()

            assertTrue(
                system.contains("A recap of the conversation is not an account of where things stand"),
                "the failure is unnamed, so a cited, dated walk through the exchange satisfies " +
                    "every other rule in the prompt: $system",
            )
            assertTrue(
                system.contains("do not walk the reader back through the exchange in the order it happened"),
                "the recap is named and the order it arrives in is not, which is the form it " +
                    "took: $system",
            )
            assertTrue(
                system.contains("Say once what the record does not hold, rather than once for every item"),
                "each absence is restated per item, which on a brief costs a clause an item to " +
                    "tell the reader that this system does not know: $system",
            )
            // **The half that makes it obeyable, which the status shape had to be measured twice
            // to find.** Asked for once and told no place, that shape said the absence in its
            // opening *and* under every heading — both readings satisfy "once". So the place is
            // named; and it is a different place here, because this shape's lead is bounded to two
            // single facts and cannot take a third.
            assertTrue(
                system.contains("Once means once in the whole brief, and the head of the account is where it goes"),
                "the rule asks for once and names no place, which is the wording the status " +
                    "shape obeyed in two directions at the same time: $system",
            )
            assertTrue(
                system.contains("Never open a section with a line summarising the items beneath it"),
                "nothing forbids the per-section summary, which is the second place the same " +
                    "absence reappears once a brief has sections: $system",
            )
            assertTrue(
                system.contains("Write each item as a whole sentence that says what follows from it"),
                "nothing says an item is a sentence, so one may trail off in a word: $system",
            )
            // The fragment's shape, pinned beside the rule: *a whole sentence* alone is satisfiable
            // by a line that reads as a sentence and still ends in a relayed label, which is what
            // reached the reader.
            assertTrue(
                system.contains("a dash and a word, a label with no sentence around it"),
                "the rule names no form, so the item that trails off in a relayed label is not " +
                    "what the model reads it against: $system",
            )
        }

    @Test
    fun `the instruction never asks for the state its own caution forbids`(): Unit = runBlocking {
        // **A prompt that cannot be obeyed as written does not fail loudly — the model picks a
        // side.** This instruction forbids calling a commitment open, still owed or outstanding,
        // because a promise kept in silence produces no closing message and so none of those is a
        // state this build can establish. An instruction that then asks for *what is owed* is the
        // same claim in the imperative, and the model is as entitled to obey that half.
        //
        // **Every wording the caution forbids, not the one it was first written against**, read
        // against the collapsed prompt so a phrase broken over a line is not a phrase this misses.
        // Kept per shape deliberately: the status shape carries an identical guard over its own
        // instruction, and a guard keyed to one prompt says nothing about another — which is
        // exactly how this came back here, in a shape its sibling's list could not see.
        //
        // **The caution itself is the shared format's now, and this reads the assembled prompt, so
        // what it holds is unchanged: that the rule reaches the model this shape sends to.** What
        // moved is the literal. A closed list of adjectives was the defect in it — an answer called
        // an undertaking an unclosed task, which no such list named — so the shared wording states
        // what cannot be known and gives the vocabulary as illustration. `ShapesTest` holds that
        // over all five; the asking-phrase half below stays here, because a shape's own prose is
        // the thing a sibling's guard cannot see.
        val system = assembledSystem()

        assertTrue(
            system.contains("give it no state beyond that"),
            "the state caution is absent, so a commitment with no recorded outcome reads as open: $system",
        )
        // **The other end of the same caution, which this shape is the one to be held to.** Forbidden
        // to call an undertaking open, the model called one done — inside a proposed question's
        // premise, which is a sentence somebody reads out loud to the party it is about. The shared
        // format now says the rule binds both ways; `ShapesTest` holds that over all five. What is
        // asserted here is that the prompt reaches the model this shape sends to, as the clause above
        // is, since a caution that never arrived is a caution in a file.
        assertTrue(
            system.contains("That holds in both directions"),
            "the caution binds one way only, so a promise nothing closed reads as carried out: $system",
        )
        listOf(
            "what is outstanding",
            "what is owed",
            "what is still owed",
            "what is open",
            "what is pending",
            "raise what is owed",
        ).forEach { asked ->
            assertFalse(
                system.contains(asked, ignoreCase = true),
                "the instruction asks for \"$asked\", which the caution beside it forbids reporting: $system",
            )
        }
    }

    @Test
    fun `the brief opens with a lead, which stands before any section`(): Unit = runBlocking {
        // **A brief whose first line is a section heading has no bottom line**, and nothing
        // downstream can see that: every claim under the heading resolves, so the grounding gate
        // passes a brief the reader has to assemble a verdict out of themselves. The sibling shape
        // answering where things stand carries the same rule in its own words, and a rule written
        // over one prompt says nothing about another — which is how this shape came to render its
        // items under headings with no sentence anywhere saying where the relationship stood.
        val system = assembledSystem()

        assertTrue(
            system.contains("Open with the lead"),
            "nothing asks for a lead, so the brief opens on whatever the first section is: $system",
        )
        // Inside the answer's own block, because everything before the first marker is discarded
        // by the reader — and the lead carries claims, so a lead written above it loses them
        // without any fault being raised.
        assertTrue(
            system.contains("inside the answer's own block"),
            "the lead is not placed inside the variant, so it can be written above the marker: $system",
        )
        // And that the lead outranks the sectioning, which is the arm that actually failed: the
        // material sections by body of work, so a lead not placed above the first heading is a
        // lead the reader meets after one.
        assertTrue(
            system.contains("the lead stands before the first of them and speaks for the whole brief"),
            "the lead is not placed above the sections, so a sectioned brief still opens on a heading: $system",
        )
        // Two sentences, one per fact it has to carry. A budget stated as "one is often enough"
        // against two required contents cannot hold with the one-fact-a-sentence rule beside it,
        // and the model resolved that collision by writing a third.
        assertTrue(
            system.contains("one sentence saying where the relationship stands and one saying what the call turns on"),
            "the lead has no sentence budget, so it can arrive as one run-on: $system",
        )
        assertTrue(
            system.contains("a third belongs in the account rather than the lead"),
            "nothing rules out a third sentence, which is what an unbounded budget is read as allowing: $system",
        )
        // The sibling shape's measured hazard is named as a property, not as its figures: the
        // count of facts a bad lead carried is in that card's pull request, and a prompt literal
        // repeating it costs input tokens on every request and drifts from the card that owns it.
        assertFalse(
            system.contains("forty-five words"),
            "the lead rule carries a measured figure, which belongs in the pull request: $system",
        )
    }

    @Test
    fun `the bound is a number, over the whole brief rather than each section`(): Unit = runBlocking {
        // **Brevity asked for as a judgement is what this replaced**, and the prompt loses that
        // ask: everything else in the instruction wants more material and is obeyed. A count is
        // the one form of it a reader can check against what arrived. Pinned as the number rather
        // than as the sentence around it, because the number is the part a reflow cannot move and
        // a loosening would have to come through here.
        val system = assembledSystem()

        assertTrue(
            system.contains("at most five items in the account and at most three questions"),
            "the length ask is not a number, so brevity is left to judgement: $system",
        )
        // Over the whole brief. The fault was items spread across two sections, which a
        // per-section reading of the same number would permit almost entirely.
        assertTrue(
            system.contains("five items across every section, not five under each"),
            "the bound is not scoped to the whole brief, so sectioning licenses the length: $system",
        )
        // The lead is additional, or the budget is spent on it and the account loses an item.
        assertTrue(
            system.contains("The lead is not one of the five"),
            "nothing says whether the lead counts against the bound: $system",
        )
    }

    @Test
    fun `fitting the bound costs no citation and merges no item`(): Unit = runBlocking {
        // **A cap invites three ways of meeting it that are each worse than the length was.**
        // Dropping citations defeats the one thing that makes a brief checkable; folding two
        // items into one sentence manufactures an assertion the material does not carry, which
        // is the defect measured in the sibling shape; and letting the surviving items read as
        // everything retrieved is the sampling fault a card of its own is about. So the
        // instruction names all three rather than leaving the model to pick a way of complying.
        val system = assembledSystem()

        assertTrue(
            system.contains("do not drop the citations from an item to make room"),
            "nothing stops the bound being met by dropping citations: $system",
        )
        assertTrue(
            system.contains("do not fold several items into one sentence"),
            "nothing stops the bound being met by compressing two items into one: $system",
        )
        // Written as a claim line with a citation, because it is an assertion about the material
        // like any other: the shared format rejects an uncited one, and prose saying it is the
        // half no check reads.
        assertTrue(
            system.contains("Write one claim line saying the material carries more than the brief lists"),
            "a bounded account reads as everything retrieved: $system",
        )
        assertTrue(
            system.contains("citing what you read it against"),
            "the more-exists claim is asked for uncited, which the format rejects: $system",
        )
    }

    @Test
    fun `the cut is keyed on each item's standing and not on where it sits`(): Unit = runBlocking {
        // **The antecedent is pinned here, not only the consequence.** A cut told to take items
        // from the end of the order would read position as carrying it, and position does not: a
        // commitment evidenced by a turn inside the message window shares that turn's span and is
        // shown there, which the case above this one measures. So the cut is sent to the same
        // per-item label the write-order rule is, and a reword that drops that pointer is a red.
        val system = assembledSystem()

        assertTrue(
            system.contains("Where the material carries more than five, leave whole items out"),
            "the bound states no cut, so what goes over five is the model's choice: $system",
        )
        assertTrue(
            system.contains("read that standing from the label on each item"),
            "the cut does not say where standing is read from, so it keys on position: $system",
        )
        // And that the groups of the cut are a precedence rather than a set of absolutes. Stated as
        // "what goes first", because a rule saying drop-from-the-end and also keep every
        // undertaking with no recorded outcome cannot be obeyed once more than five of them exist,
        // and an unobeyable rule is resolved by the model picking a side.
        //
        // **This first group is a narrow population now and not a dead one.** The finished-matter
        // rule leaves most of what it would have caught out of the brief altogether, so what the
        // group still decides is the one such item that rule keeps — and that one carries its own
        // exemption, asserted where the rule is.
        assertTrue(
            system.contains("What goes first is anything reported as done"),
            "the cut names no precedence, so its groups can contradict: $system",
        )
        assertFalse(
            system.contains("keep the undertakings the record shows no outcome for"),
            "the cut carries an absolute keep, which collides with dropping anything at all: $system",
        )
    }

    @Test
    fun `the questions half is told a premise asserts, and what to ask in place of one`(): Unit =
        runBlocking {
            // **The measured failures were all in the questions half, which is why this is pinned
            // apart from the state caution above.** The shared format states the rule over anything
            // the model writes; a question is where it was broken, and a question is the one output
            // here that is spoken aloud to the person it is about — so a false premise inside one is
            // said to their face, and they either correct the reader or go along with it.
            //
            // **The replacement is pinned beside the prohibition, for the reason the shared rule
            // gives for pinning its own.** Told only that it may not presuppose an outcome, a model
            // writing the questions half has nothing to put where the question was; told to name the
            // undertaking and ask what came of it, it has the shape.
            //
            // **And the age clause, which is a second premise fault rather than a restatement of the
            // first.** One measured question rested on somebody's own account of their own progress,
            // true on the day it was written and a presupposition by the time the brief relayed it.
            // Nothing in the state caution reaches it — the material records no undertaking as done
            // and the model asserted none — so what is wanted is the instruction to weigh how old a
            // premise is, over markers this shape already renders. Scoped as the material is: the
            // age marker is rendered only where no outcome is recorded, which the case above pins.
            //
            // A prose pin, the weak kind of assertion and knowingly so: what it holds is that the
            // paragraphs reach the model, not that they are obeyed. A rendered brief is where that
            // is read, and a probe against the live model is what measured it.
            val system = assembledSystem()

            assertTrue(
                system.contains("A question presupposes everything it does not ask"),
                "nothing tells the model that a question asserts what it takes for granted, which " +
                    "is the half of an answer no gate reads: $system",
            )
            assertTrue(
                system.contains("ask what came of it"),
                "the model is told what a question may not presuppose and not what to ask instead, " +
                    "so the prohibition is answered with the nearest question outside it: $system",
            )
            // **All three forms, not the first of them.** A pin on the opening clause alone leaves
            // the rest of the sentence deletable with the suite green, which is what it is here to
            // prevent: each form is one a measured question took, and the one asking what a thing
            // produced is the form the shared rule's own example names.
            assertTrue(
                system.contains("Do not ask how it went, what it produced or what is left to do on it"),
                "the forms the measured questions took are not named in full, so a premise stated " +
                    "as a question about findings is still available: $system",
            )
            assertTrue(
                system.contains("Check how old what a question rests on is"),
                "nothing weighs a premise against the age of the material behind it, so a stale " +
                    "progress report reads as a current fact: $system",
            )
            // **The clause the live run added, and it is a fault this card nearly introduced.** Told
            // to say inside a question that the record holds nothing after the undertaking, the model
            // obeyed the shared format too: a statement about the material is a claim, so questions
            // came back as cited claim lines, which renders a question as a finding with a source
            // under it. **Stated as form and not as meaning**, which is the second thing the run
            // taught: a question that asserts nothing is a sentence the paragraph above contradicts,
            // and it would license a finding phrased as a question into the uncited half.
            assertTrue(
                system.contains("A question is not a claim line and carries no citations"),
                "the questions half is not told a question is not a claim line, so a question that " +
                    "reports what the record holds is written as a cited finding: $system",
            )
        }

    @Test
    fun `what the call is about is read from what was asked, with an arm for it being unsaid`(): Unit =
        runBlocking {
            // **The shape takes a contact and nothing else, so a brief covering the whole
            // relationship is a defensible answer to it** — which is why the rule is not a
            // judgement about which half of a relationship a call concerns. The words the reader
            // typed reach the model under *Asked*, and where they name a matter beside the person
            // that is the only statement of purpose anything in the request holds.
            //
            // Both arms, because an instruction reading a thing that is frequently not there is
            // obeyed in whatever direction the model picks when it is absent — which is how a brief
            // came to cover a working relationship and a private one at once, with two of its
            // proposed questions drawn from the second.
            //
            // And the no-empty-brief arm beside them: a purpose the material carries little about
            // must not be answered by handing back nothing, which is the failure a rule bounding
            // what may be reported invites. Said of the material rather than of the corpus, since
            // what this request retrieved is not the whole of what is held.
            val system = assembledSystem()

            assertTrue(
                system.contains("What was asked is the only thing that can say what this call is about"),
                "nothing sends the model to the request's own words for the call's purpose, so a " +
                    "stated one is ignored and the brief covers the relationship: $system",
            )
            assertTrue(
                system.contains("every question comes from there"),
                "a stated purpose orders the account and says nothing about the questions, which " +
                    "is the half the measured brief drew from the part the call was not about: $system",
            )
            // **It orders and does not remove, which the enumeration below depends on.** A purpose
            // arm written as *report what bears on it* is a fourth rule that drops an item, and the
            // sentence saying three rules do and no others would be false the moment this landed —
            // the hazard that sentence's own pin exists to catch. So the cut is left to drop, keyed
            // on the same fact.
            assertTrue(
                system.contains("the cut below is the only thing that drops such an item"),
                "the purpose arm reads as removing what the call is not about, which makes the " +
                    "enumeration of what leaves an item out false: $system",
            )
            // And the enumeration's own side of that, which is where a reader of the prompt meets
            // it: the count is three, and what makes the count exhaustive of every rule here
            // rather than of three named ones is this clause.
            assertTrue(
                system.contains("nothing else here drops an item at all"),
                "the enumeration counts three without saying that nothing else drops an item, so " +
                    "this arm reads as a fourth: $system",
            )
            assertTrue(
                system.contains("Where what was asked names the person and nothing else"),
                "the rule has no arm for a request that says nothing about the call, which is " +
                    "most of them: $system",
            )
            assertTrue(
                system.contains("say that of the material and report what it does carry"),
                "a purpose the material is thin on leaves the model to hand back an empty brief, " +
                    "and the absence is not scoped to what this request retrieved: $system",
            )
            // **A rule told to a model that writes cited findings comes back as one**, which a
            // live run produced here: asked to decide the brief by whether the request named a
            // matter, it wrote that nothing had named one as a claim line citing the whole
            // material. It passes the grounding gate — the spans resolve — and spends an item of
            // a bounded few on how the brief was assembled.
            assertTrue(
                system.contains("that nothing said what the call was about, are not findings about the record"),
                "nothing says the rule itself is not reportable, so the brief spends an item " +
                    "saying what the request did not name: $system",
            )
        }

    @Test
    fun `a private matter is confined and seeds no question, and a record of only those still briefs`(): Unit =
        runBlocking {
            // **Confined rather than refused, which is the half an acceptance check turns on.** A
            // rule that merely dropped the private material would empty the brief for a counterpart
            // the reader knows privately and not at work, which is an ordinary kind of counterpart
            // to be asked about — so the escape is pinned beside the rule, as every other arm in
            // this prompt is.
            //
            // **Pinned as a position and a question source, with the section question answered in
            // the prompt rather than left to follow.** What a section is headed by is the shared
            // format's to say and it admits a body of work or a person, refusing a topic outright;
            // a kind asked for as a heading would be a second grouping over the same material, and
            // two groupings from two places leave the model to pick one. The material's own labels
            // cannot stand in for the kind either — a one-to-one carries a person's label whatever
            // the two of them talk about. So three clauses are pinned that a reader might think
            // followed on their own: where the items go, that they head nothing, and that the
            // write-order applies inside a kind and not across the two.
            val system = assembledSystem()

            assertTrue(
                system.contains("Report those last within the part of the brief they fall in"),
                "nothing puts the material a working call is not about where a reader can stop " +
                    "before it: $system",
            )
            // The clause that keeps this an ordering rather than a section, which is what lets it
            // compose with the format's own grouping instead of competing with it.
            assertTrue(
                system.contains("They take no heading of their own"),
                "the kind rule reads as asking for a section of its own, which collides with the " +
                    "format's grouping by body of work: $system",
            )
            // And the precedence against the write-order, which reaches the same items: an overdue
            // private matter is named by both sentences, and with no order between them the model
            // picks. Stated in the prompt, because a KDoc asserting the outcome reaches nobody.
            assertTrue(
                system.contains("the write-order below holds within each of the two kinds"),
                "the kind order and the standing order both reach one item with nothing saying " +
                    "which wins: $system",
            )
            assertTrue(
                system.contains("draw no question from one"),
                "the private material is placed and still allowed to seed a question, which is " +
                    "the measured fault: $system",
            )
            // **The bound is reached through the cut and not claimed here.** A clause saying these
            // are the first items to leave out competes with the cut's own first member, which is
            // anything reported as done — two sentences, one slot, and the model picks.
            assertTrue(
                system.contains("The cut below is what leaves one out"),
                "the kind is not wired to the bound at all, so a brief with five working items " +
                    "still spends one of them on a social invitation: $system",
            )
            assertFalse(
                system.contains("they are the first items to leave out"),
                "the kind rule claims the cut's first place, which the cut gives to something " +
                    "else: $system",
            )
            assertTrue(
                system.contains("Where the record holds nothing but those, that is what the call is about"),
                "the rule has no escape, so a counterpart known privately and not at work gets " +
                    "an empty brief: $system",
            )
            // And the cut's own precedence, which is where the bound reads the kind from.
            assertTrue(
                system.contains("then whatever the call is not about"),
                "the cut names no place for the material the call is not about, so the bound " +
                    "drops by standing alone: $system",
            )
        }

    @Test
    fun `a matter the record reports as done is not an item of its own`(): Unit = runBlocking {
        // **The closure read makes one of its two shapes visible, and it is read so that nothing
        // finished is chased** — a brief that reports such a thing so as to say it is finished
        // spends an item on the one thing the reader has nothing to do about. The measured item was
        // of the other shape: a matter the messages themselves carry the end of, with no closure
        // marker on anything, because nothing upstream read it as an undertaking.
        //
        // **In the build's own vocabulary and not a stronger word.** What a later message evidences
        // is that a thing was *reported as done* — see `Commitment` — so a rule written over what
        // the record *settles* would ask the answering model for a determination nothing upstream
        // makes, and the consequence of a wrong reading here is a silent drop that carries neither
        // a claim nor a citation for anything downstream to catch.
        //
        // **Composed with the sentence that enumerates what may leave an item out rather than
        // written against it.** That sentence is pinned above as the whole of what drops an item,
        // so a further rule that drops one makes it false — and a prompt carrying both as written
        // leaves the model to pick. So this rule is named there as the third member, which is
        // asserted in that case rather than here: the enumeration is one sentence and belongs to
        // one pin.
        val system = assembledSystem()

        assertTrue(
            system.contains("do not raise it as an item of its own either"),
            "a finished matter is only barred from being chased, so it still arrives as an item " +
                "whose whole content is that nothing follows from it: $system",
        )
        assertTrue(
            system.contains("Report it where something the record shows no outcome for turns on it"),
            "the rule says when a finished matter goes and never when it stays, which discards " +
                "the closure that discharges half of what the call is about: $system",
        )
        // **And that the shared format's positive half is named where it is narrowed.** That block
        // is spliced into every shape's system block and says outright that where the records do
        // report an outcome, it is reported as they have it. A rule telling this shape to leave such
        // an item out, with nothing reconciling the two, is a prompt asking for a reading in one
        // line and forbidding it in another — which the model resolves by picking a side, silently.
        assertTrue(
            system.contains("it is not a reason to report one"),
            "the shared permission to report a recorded outcome is left standing against this " +
                "rule with nothing saying which governs: $system",
        )
        // **What is dropped is stood in for, rather than vanishing.** The sibling rule about
        // another participant's matters established the convention and the reason: a rule that
        // only removes trades a noisy brief for one that reads as having found nothing. The
        // more-exists claim line cannot serve here, since it fires only above five items and these
        // drops can take the count under it.
        assertTrue(
            system.contains("the line that stands in for what the brief does not write out"),
            "the rule removes items and nothing stands in for them, so a brief with several " +
                "finished matters reads as a record holding nothing: $system",
        )
        // **The marker is not the antecedent, and a live probe is what settled that.** Seated on a
        // commitment the material marks as closed, the rule missed the measured item entirely —
        // the kind the messages themselves close carries no closure marker. The probe kept it as
        // an item on the first pass and dropped it once the rule said *however the record says so*.
        assertTrue(
            system.contains("A marker is not the only way the record reports a thing done"),
            "the rule reaches only a commitment carrying a closure marker, which the measured " +
                "item did not: $system",
        )
        assertTrue(
            system.contains("which chooses by there being nothing left to act on"),
            "the enumeration of what leaves an item out names this rule without saying what it " +
                "chooses by, so it reads as a second standing rather than as its own test: $system",
        )
        // **The precedence, which a live run is what found wanting.** A settled private matter is
        // reached by two rules at once — the kinds above place it last, this leaves it out — and a
        // prompt carrying both without an order between them was obeyed on the placing side, which
        // is the side that keeps the item. The second half is the same fault from the other
        // direction: under the bound there is room, and room reads as a reason to fill it.
        assertTrue(
            system.contains("it decides an item before the kinds above do"),
            "the kinds rule and this one both reach a settled private matter with no order " +
                "between them, so it is reported last instead of left out: $system",
        )
        assertTrue(
            system.contains("Being short of items is not a reason to keep one"),
            "a record under the bound leaves room, and nothing says room is not a reason to " +
                "spend it on an item with nothing to act on: $system",
        )
        // **And the one item this rule keeps is exempted from the cut's first group**, which it
        // otherwise falls into: an item kept because something with no recorded outcome turns on
        // it still carries the done label, and the cut takes the done ones first — so the branch
        // the rule exists to preserve would be the first thing the bound discarded.
        assertTrue(
            system.contains("apart from one kept because something the record shows no outcome for turns on it"),
            "the cut discards the finished item this rule deliberately kept, and takes it before " +
                "anything else: $system",
        )
    }

    // --- doubles and fixtures -------------------------------------------------------------

    private companion object {

        /** Fixed, so a promise's due date is ahead or behind by the fixture's choice and not the day's. */
        val NOW: Instant = Instant.parse("2026-10-05T12:00:00Z")

        /**
         * Runs of whitespace as one space, so a re-wrapped paragraph still reads as its sentence.
         *
         * An assertion matching the raw prompt would really be asserting where the paragraph was
         * wrapped, which turns a rewrap into a red and invites reflowing the prompt to suit a test.
         */
        val WHITESPACE = Regex("\\s+")

        /**
         * The words both the rendered mark and the rule about it have to carry.
         *
         * Held in one place *for this shape*, so that a case cannot assert the instruction and the
         * rendering against two different spellings and pass. The status shape's suite holds the
         * same literal for its own prompt, which is the sibling's precedent and not a duplicate to
         * fold: a constant shared between the two would make either suite's red depend on the other
         * shape's wording. The spelling belongs to the material rather than to either shape, and is
         * private there, so a test reaches it as the rendered text and not as the constant.
         */
        const val MARK = "neither the subject nor the reader"

        fun shape(retrieval: FakeRetrieval) = PrepareShape(
            FakeModelProvider(),
            retrieval,
            FakeGrouping(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            ZoneOffset.UTC,
        )

        suspend fun retrieve(retrieval: FakeRetrieval) = assertIs<Progress.Carried>(
            shape(retrieval).stages.first().apply(
                Exchange(Request(text = "prepare me for the call"), subject = FakeRetrieval.aContact()),
            ),
        ).exchange.retrieved

        /**
         * The system prompt this shape assembles, as one line.
         *
         * Taken by running the first two stages rather than by reading the constant: what a prose
         * pin has to assert is what the model is actually sent, and the assembling stage is between
         * the two.
         */
        suspend fun assembledSystem(): String {
            val stages = shape(FakeRetrieval()).stages
            val retrieved = assertIs<Progress.Carried>(
                stages[0].apply(
                    Exchange(Request(text = "prepare me"), subject = FakeRetrieval.aContact()),
                ),
            ).exchange
            return requireNotNull(
                assertIs<Progress.Carried>(stages[1].apply(retrieved)).exchange.assembled,
            ).system.replace(WHITESPACE, " ")
        }

        /**
         * A deadline [days] before today, aligned to a day boundary.
         *
         * **The alignment buys no arithmetic here**, and saying what it does buy keeps the next
         * case from reading it as a guarantee: a day boundary is not the only shape a stored
         * deadline has — most dated commitments in the corpus carry a time of day — so a case that
         * turned on how far past a deadline is would need the offset form instead. These cases turn
         * on the *order* the groups arrive in, which the time of day cannot change, since
         * `Material.standing` compares against a truncated today either way.
         */
        fun duePast(days: Long): Instant = NOW.truncatedTo(ChronoUnit.DAYS).minus(days, ChronoUnit.DAYS)

        /** A due date [days] ahead, aligned for the reason [duePast] gives. */
        fun dueAhead(days: Long): Instant = NOW.truncatedTo(ChronoUnit.DAYS).plus(days, ChronoUnit.DAYS)

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
    }
}
