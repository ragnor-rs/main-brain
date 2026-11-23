package io.github.ragnorrs.mainbrain.domain.answer.shape.draft

import io.github.ragnorrs.mainbrain.domain.EmbeddingKind
import io.github.ragnorrs.mainbrain.domain.FakeModelProvider
import io.github.ragnorrs.mainbrain.domain.answer.Channel
import io.github.ragnorrs.mainbrain.domain.answer.Exchange
import io.github.ragnorrs.mainbrain.domain.answer.FakeEmbeddings
import io.github.ragnorrs.mainbrain.domain.answer.FakeGrouping
import io.github.ragnorrs.mainbrain.domain.answer.FakeIndex
import io.github.ragnorrs.mainbrain.domain.answer.FakeRetrieval
import io.github.ragnorrs.mainbrain.domain.answer.Lookup
import io.github.ragnorrs.mainbrain.domain.answer.Material
import io.github.ragnorrs.mainbrain.domain.answer.Progress
import io.github.ragnorrs.mainbrain.domain.answer.Request
import io.github.ragnorrs.mainbrain.domain.answer.SubjectKind
import io.github.ragnorrs.mainbrain.domain.index.Span
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A reply to a person, in two or three drafts.
 *
 * The contracts worth asserting are the ones an interface cannot see broken. The multi-variant one,
 * because it renders what it is given and one draft looks exactly like a shape that never offered a
 * choice. The register lookup — the read that makes the drafts sound like the sender rather than like
 * a model — which has to be asked with the thread's own words and not with the request's. Which side
 * of the thread the draft is composed from: a reply in the counterpart's voice renders as fluent,
 * correctly attributed prose that nobody can send. What the draft is aimed at, since one assembled
 * out of the sender's own earlier words satisfies every other rule the instruction carries. What has
 * to be the same in all of them and what may differ, because drafts that divide the facts between
 * them are each well-formed and the defect is visible only by reading them side by side — and with
 * it that they are aimed at one thing, a draft aimed at a side thread being a second reply rather
 * than an alternative to the others. What a
 * draft may undertake, since a promise it originates is well-formed by every other rule here and is
 * read off nothing downstream — the prose carries no claims, so the grounding check never sees it.
 * Whether a fact the material once supported is still written as current, since the material carries
 * the reading and the turn that superseded it alike and only the dates tell them apart. And the
 * shape of the material the rule for that reads — several threads and the ranked hits concatenated,
 * ordered by relevance rather than by time — because a rule that assumed otherwise would pick the
 * wrong turn with nothing downstream able to tell.
 */
class DraftShapeTest {

    @Test
    fun `a reply goes to a person, so only a contact is accepted`() {
        // Asked for a client, scope resolution traverses to that client's contacts rather than this
        // shape objecting that an organisation cannot receive a message.
        assertEquals(setOf(SubjectKind.CONTACT), shape().accepts)
    }

    @Test
    fun `two is a choice and one is not`() {
        assertEquals(2..3, shape().variants)
    }

    @Test
    fun `it answers from the corpus`() {
        assertTrue(shape().readsCorpus)
    }

    @Test
    fun `the thread's history and comparable past replies are both retrieved`(): Unit = runBlocking {
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "can you send it by Friday")),
        )
        val comparable = span("90", "I will have it with you by then")

        val retrieved = retrieve(retrieval, FakeIndex(hits = listOf(comparable)))

        assertTrue(retrieved.any { it.text.contains("can you send it by Friday") })
        assertTrue(retrieved.any { it.text.contains("I will have it with you by then") })
    }

    @Test
    fun `the register lookup asks with the thread's words, not the request's`(): Unit = runBlocking {
        // What a comparable exchange resembles is what was said in the conversation. The request is
        // an instruction to answer it and resembles nothing in the corpus.
        val embeddings = FakeEmbeddings()
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "can you send it by Friday")),
        )

        retrieve(retrieval, FakeIndex(), embeddings)

        val asked = embeddings.embedded.single()
        assertEquals(EmbeddingKind.QUERY, asked.first)
        assertTrue(asked.second.contains("can you send it by Friday"))
        assertTrue(!asked.second.contains("draft a reply"))
    }

    @Test
    fun `a comparable hit is widened, because a reply without its question models no register`(): Unit =
        runBlocking {
            val hit = span("90", "I will have it with you by then")
            val index = FakeIndex(
                hits = listOf(hit),
                around = mapOf(hit.id to listOf(span("89", "when can you deliver"), hit)),
            )

            val retrieved = retrieve(withHistory(), index)

            assertEquals(listOf(hit.id), index.widened)
            assertTrue(retrieved.any { it.text.contains("when can you deliver") })
        }

    @Test
    fun `the register lookup asks with the latest words, not with the last thread's`(): Unit = runBlocking {
        // The reads come back grouped by conversation in the order they were asked for, so position in
        // the list is relevance and not time. Sampling the tail asks the index to find a register for
        // the least relevant thread this subject appears in, and sampling the front asks with the
        // oldest words of the most relevant one. Neither is what is being replied to.
        val embeddings = FakeEmbeddings()
        val current = (1..12).map {
            FakeRetrieval.message("r$it", "the live exchange $it", at = FakeRetrieval.WHEN.plusSeconds(it * 60L))
        }
        val stale = FakeRetrieval.message(
            "stale",
            "an aside from years ago",
            at = FakeRetrieval.WHEN.minusSeconds(SPAN),
        )
        val retrieval = FakeRetrieval(
            conversations = listOf(current.first().externalId),
            messages = current + stale,
        )

        retrieve(retrieval, FakeIndex(), embeddings)

        val asked = embeddings.embedded.single().second
        assertTrue(asked.contains("the live exchange 12"), "The newest words were not asked with: $asked")
        assertTrue(!asked.contains("an aside from years ago"), "The quiet thread's tail was asked with: $asked")
    }

    @Test
    fun `a message reached through both the thread and the index keeps the live body`(): Unit = runBlocking {
        // The two reads here are two readings of one record: the thread reads the record itself, the
        // index whatever the last pass wrote into it. Between an edit and the next pass those differ,
        // and joining them would print the stale body beside the current one under one identifier. The
        // record is read first, so it wins.
        val index = FakeIndex(hits = listOf(span("1", "can you send it by Thursday")))

        val retrieved = retrieve(withHistory(), index).single()

        // No turn is retrieved for it, so nothing places it in a thread and it carries no
        // latest-exchange mark — see the case that asserts exactly that.
        assertEquals("can you send it by Friday", retrieved.text)
    }

    @Test
    fun `a turn from a room names whoever wrote it, not the person being replied to`(): Unit = runBlocking {
        // **The shape this was measured on, and the reason it reads more threads than any other.**
        // The threads past the first are rooms the counterpart happens to write in, so an
        // unattributed stretch of one of them is read as their own words — and the draft replies to
        // things they never said, cited correctly, passed clean by the grounding gate.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(
                FakeRetrieval.message("1", "can you send it by Friday"),
                FakeRetrieval.message("2", "the warehouse is closed that week"),
            ),
            turns = mapOf(
                FakeRetrieval.record("1") to FakeRetrieval.turn("The Counterpart", channel = ROOM),
                FakeRetrieval.record("2") to
                    FakeRetrieval.turn("Another Participant", channel = ROOM),
            ),
        )

        val retrieved = retrieve(retrieval, FakeIndex()).associateBy { it.id.record.nativeId }

        assertTrue(
            retrieved.getValue("1").text.startsWith("Sent by The Counterpart,"),
            "the turn is unattributed: ${retrieved.getValue("1").text}",
        )
        assertTrue(
            retrieved.getValue("2").text.startsWith("Sent by Another Participant,"),
            "a room's other participant's words are offered as the counterpart's own: " +
                retrieved.getValue("2").text,
        )
    }

    @Test
    fun `a turn from a shared conversation says so, and names the one a reply would go to`(): Unit =
        runBlocking {
            // **The defect, and it is the channel rather than the content.** Asked for a reply to a
            // counterpart whose whole record is a four-person room, the drafts came back composed as
            // private messages to one person — second person singular throughout, nothing saying the
            // only place they could be sent was a room the client's own side reads. On this archive
            // that is the majority case rather than the odd one: most counterparts have no direct
            // thread with the owner at all.
            //
            // Nothing downstream can see it. The prose is correct, every citation resolves, and the
            // grounding gate passes a draft that carries no claims by design — so the material is
            // where who-else-is-there has to be said, and the name is what makes the draft placeable
            // rather than merely correct.
            val retrieval = FakeRetrieval(
                conversations = listOf(FakeRetrieval.CONVERSATION),
                messages = listOf(FakeRetrieval.message("1", "can you send it by Friday")),
                turns = mapOf(
                    FakeRetrieval.record("1") to
                        FakeRetrieval.turn("The Counterpart", channel = ROOM),
                ),
            )

            assertEquals(
                "Sent by The Counterpart, said in the shared conversation named The Project Room, " +
                    "which the other people in it read$LATEST_ALSO: can you send it by Friday",
                retrieve(retrieval, FakeIndex()).single().text,
                "nothing in the material says the only place this reply can go is a room",
            )
        }

    @Test
    fun `the name still leads a room's turn, which is what the attribution rule reads`(): Unit =
        runBlocking {
            // **The regression this ordering exists to prevent, and it is not cosmetic.** The
            // instruction states the attribution rule and the sender-voice rule over the literal
            // leading mark: a turn carrying it was written by the person named, and a turn without
            // it by somebody the corpus does not name. Rooms are most of this corpus, so a
            // rendering that demoted the name behind the room clause would tell the model that most
            // of its material was written by nobody — and the sender's own marker, which is carried
            // on the name, would be inside a clause the rule no longer recognises. That is the
            // voice failure again, reached through the channel fix.
            val stages = shape(withHistory(), FakeIndex()).stages
            val system = requireNotNull(
                assertIs<Progress.Carried>(
                    stages[1].apply(Exchange(Request(text = "draft a reply"))),
                ).exchange.assembled,
            ).system.replace(WHITESPACE, " ")
            val lead = system.substringAfter("marked with who sent it, as \"").substringBefore("\"")

            val retrieval = FakeRetrieval(
                conversations = listOf(FakeRetrieval.CONVERSATION),
                messages = listOf(FakeRetrieval.message("1", "can you send it by Friday")),
                turns = mapOf(
                    FakeRetrieval.record("1") to
                        FakeRetrieval.turn("The Counterpart", channel = ROOM),
                ),
            )

            // The literal is read out of the instruction rather than written here, so the pin holds
            // whichever of the two is edited.
            assertTrue(
                retrieve(retrieval, FakeIndex()).single().text.startsWith("$lead The Counterpart"),
                "a room's turn does not open with the mark the attribution rule is written over, " +
                    "so every turn in a room reads as one the corpus names nobody for",
            )
        }

    @Test
    fun `a one-to-one turn carries no room, which is what the absence means`(): Unit = runBlocking {
        // The private case is the one the rendering says nothing about, and the instruction is what
        // says so. A clause on every turn of a sixty-message thread would be a line of prompt per
        // message to carry a fact the model is told once — and the two cases have to stay
        // distinguishable, which is the whole of why the absence is given a meaning in prose.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.CONVERSATION),
            messages = listOf(FakeRetrieval.message("1", "can you send it by Friday")),
            turns = mapOf(FakeRetrieval.record("1") to FakeRetrieval.turn("The Counterpart")),
        )

        assertEquals(
            "Sent by The Counterpart$LATEST_ALSO: can you send it by Friday",
            retrieve(retrieval, FakeIndex()).single().text,
        )
    }

    @Test
    fun `a room survives a turn the corpus names no author for`(): Unit = runBlocking {
        // **Why the room is a clause beside the name rather than part of it.** A turn the corpus
        // names nobody for carries no name clause at all, so a room folded into one would be
        // dropped for exactly the turns an answer can say least about — and dropped to the reading
        // that composes the draft privately, in front of whoever else is in the room. So the clause
        // has two forms, and this is the one that opens the block.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.CONVERSATION),
            messages = listOf(FakeRetrieval.message("1", "can you send it by Friday")),
            turns = mapOf(FakeRetrieval.record("1") to FakeRetrieval.turn(channel = ROOM)),
        )

        assertEquals(
            "Said in the shared conversation named The Project Room, which the other people in it " +
                "read$LATEST_ALSO: can you send it by Friday",
            retrieve(retrieval, FakeIndex()).single().text,
        )
    }

    @Test
    fun `a room the corpus holds no name for is still a room`(): Unit = runBlocking {
        // The distinction the channel type keeps apart, held to the end: rendered as nothing, an
        // unnamed room would arrive at the model as a one-to-one thread, which is the defect by a
        // second route. What is lost is which conversation to send it to, and that is said rather
        // than guessed at.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.CONVERSATION),
            messages = listOf(FakeRetrieval.message("1", "can you send it by Friday")),
            turns = mapOf(
                FakeRetrieval.record("1") to
                    FakeRetrieval.turn("The Counterpart", channel = Channel.Room()),
            ),
        )

        assertEquals(
            "Sent by The Counterpart, said in a shared conversation, which the other people in it " +
                "read$LATEST_ALSO: can you send it by Friday",
            retrieve(retrieval, FakeIndex()).single().text,
        )
    }

    @Test
    fun `the sender's own turn in a room carries both the mark and the room`(): Unit = runBlocking {
        // The sender writes in the rooms too, so a channel mark that survived only the private
        // thread would say a room was private whenever the owner's own turn was the one in hand.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.CONVERSATION),
            messages = listOf(FakeRetrieval.message("1", "I will send the figures on Friday")),
            turns = mapOf(
                FakeRetrieval.record("1") to
                    FakeRetrieval.turn("The Owner", isSelf = true, channel = ROOM),
            ),
        )

        assertEquals(
            "Sent by The Owner (the sender you are drafting for), said in the shared conversation " +
                "named The Project Room, which the other people in it read$LATEST_ALSO: " +
                "I will send the figures on Friday",
            retrieve(retrieval, FakeIndex()).single().text,
        )
    }

    @Test
    fun `a room reached through the ranking carries its room, as the windowed route does`(): Unit =
        runBlocking {
            // **The code travelled this route and no case did**, which is the gap a prior card on
            // this same pair of lines had to close for the sender's mark. Every other channel case
            // runs with no index hits, so deleting the channel argument from the ranked branch left
            // the whole suite green — and the branch is not a rare one: the lookup's question is the
            // thread's own recent words, so its nearest neighbours are routinely this counterpart's
            // older turns from the same rooms, the ones the window left behind.
            val thread = FakeRetrieval.CONVERSATION
            val retrieval = FakeRetrieval(
                conversations = listOf(thread),
                messages = listOf(FakeRetrieval.message("1", "can you send it by Friday")),
                turns = mapOf(
                    FakeRetrieval.record("1") to FakeRetrieval.turn("The Counterpart", thread, channel = ROOM),
                    FakeRetrieval.record("90") to
                        FakeRetrieval.turn("The Owner", thread, isSelf = true, channel = ROOM),
                ),
            )
            val index = FakeIndex(hits = listOf(span("90", "I will have it with you by then")))

            val retrieved = retrieve(retrieval, index).associateBy { it.id.record.nativeId }

            assertEquals(
                "Sent by The Owner (the sender you are drafting for), said in the shared " +
                    "conversation named The Project Room, which the other people in it read: " +
                    "I will have it with you by then",
                retrieved.getValue("90").text,
                "a turn reached by ranking loses the room it was said in, so a draft composed " +
                    "from it is composed for a private thread that does not exist",
            )
        }

    @Test
    fun `the instruction composes for the conversation the draft will be sent in`(): Unit =
        runBlocking {
            // Prose pins, the weak kind, knowingly: they catch a clause being dropped rather than a
            // sentence elsewhere working against it, and only reading drafts can say whether the
            // model obeys them. What they hold is that the rule is stated at all — the shape's own
            // documentation said a reply goes to a person, and the instruction said nothing about
            // where, so a private reply satisfied every rule it carried.
            val stages = shape(FakeRetrieval(), FakeIndex()).stages
            val carried = assertIs<Progress.Carried>(
                stages[1].apply(Exchange(Request(text = "draft a reply"))),
            )
            val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

            // **The marker is read off the rendering rather than restated here**, which is the
            // convention the sender's-mark pin already follows and for the same reason: a second
            // copy of it in the test would let the instruction and the material describe one mark
            // in two strings that agree only until one of them is edited.
            // The clause after the name is what both forms share — a room the corpus names and one
            // it does not — so it is the part an instruction can quote and the part worth pinning.
            val room = requireNotNull(
                Material.of(FakeRetrieval.message("1", "body"), null, channel = ROOM),
            ).text.removeSuffix(": body")
            val marker = room.substringAfter("The Project Room").removePrefix(", ")

            assertTrue(
                system.contains(marker),
                "the material marks a turn as said in front of other people and the instruction " +
                    "never says what that mark means: $marker not in $system",
            )
            assertTrue(
                system.contains("compose it for the conversation it will be sent in"),
                "nothing says a draft is sent somewhere, so it is composed for a channel the " +
                    "material never named: $system",
            )
            assertTrue(
                system.contains("with no such mark was said in a one-to-one thread"),
                "the absent mark is given no meaning, so an unmarked turn is as readily a room " +
                    "the read could not place: $system",
            )
            assertTrue(
                system.contains("A register example carries no such mark either"),
                "the no-mark convention reaches the register examples, which are unmarked by " +
                    "decision and come from rooms as readily as from private threads: $system",
            )
            assertTrue(
                system.contains("say in a claim line which conversation it is for, citing a turn"),
                "the draft is placeable by the model and not by the reader, who is the one who " +
                    "has to send it — and an uncited claim line drops the whole variant: $system",
            )
            assertTrue(
                system.contains("say that it is a shared one whose name the material does not give"),
                "the rule asks for a name the material need not hold, so on a nameless room it " +
                    "asks the model to invent one: $system",
            )
            assertTrue(
                system.contains("Do not conclude anything about conversations you were not shown"),
                "naming the channel invites a count of them, which is a claim about a corpus the " +
                    "shape read five threads of: $system",
            )
            assertTrue(
                system.contains("What belongs to one person does not go into a draft for a shared"),
                "one participant's own access is composed into text whose purpose is to be sent " +
                    "into a room the others read: $system",
            )
            assertTrue(
                system.contains("repeating it discloses nothing that is not already there"),
                "the rule refuses the ordinary case of answering about material the room itself " +
                    "carries: $system",
            )
        }

    @Test
    fun `the instruction weighs a later turn against the fact it supersedes`(): Unit =
        runBlocking {
            // Prose pins, the weak kind, for the reason the channel case above gives. What makes
            // them worth having here is which half of the rule they hold: measured on this shape's
            // own assembled prompt, the drafts dated the observation wherever a turn called the
            // thing lost in so many words, and carried it over as present fact wherever the only
            // evidence was a rebuild and a list of what came back. So a rule keyed on the wording
            // of a loss would have landed on the half that already held, and these assert the
            // condition is stated as a condition — the first three for the stale premise, the
            // fourth for the rounding-up rule beside it, and the last two for where the note goes
            // and which way out is refused.
            val stages = shape(FakeRetrieval(), FakeIndex()).stages
            val carried = assertIs<Progress.Carried>(
                stages[1].apply(Exchange(Request(text = "draft a reply"))),
            )
            val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains("A fact is only as current as the thing it was observed on"),
                "nothing ties a fact's standing to what the thread says happened to the thing it " +
                    "was observed on, so a reading from before a rebuild reads as the present: " +
                    "$system",
            )
            assertTrue(
                system.contains("nothing has to say outright that the old state was lost"),
                "the rule would reach only the case the thread states in so many words, which is " +
                    "the half that already worked: $system",
            )
            // The verbs are illustration and the condition is the rule, which is the shape
            // `Reply.STATE` settled: a prohibition satisfied by reaching for the word nobody
            // listed is not one. Pinned on the condition, so narrowing it back to a closed list
            // of verbs reds rather than passing on the four that happen to be written down.
            assertTrue(
                system.contains("are ways that happens and not the test"),
                "the trigger reads as a closed list of verbs, so a thread that says wiped or " +
                    "reimaged falls outside a rule meant to cover it: $system",
            )
            assertTrue(
                system.contains("Do not write the premise as current fact and hedge only what"),
                "the measured failure hedged the consequence while asserting the premise, which a " +
                    "rule asking merely for caution reads as satisfied: $system",
            )
            assertTrue(
                system.contains("parts brought back one at a time are not everything working"),
                "nothing refuses the rounder statement the material suggests, so a list of " +
                    "restored parts reads back as everything working: $system",
            )
            // **Where the note goes, which the rule has to say and not only this file.** *Nothing
            // since has confirmed it* is a remark about what the record holds, and the rule above
            // sends those to a cited claim line outside the draft — where this one cannot be cited,
            // and an uncited claim line costs the whole variant. So the prose names its
            // destination, and that is pinned rather than left to the comment.
            assertTrue(
                system.contains("in the message itself and in the sender's own words"),
                "the note has no stated destination, so the rule above routes it to a claim line " +
                    "it cannot cite and the variant is dropped: $system",
            )
            // **Both halves of the axis, because the negative alone rots.** Offering to go and
            // check is the obvious third way out and the rule against originating an undertaking
            // forbids it, so the two would pull against each other on one line. The positive pin
            // is what survives a rewording: `assertFalse` on one phrasing passes the moment
            // somebody writes *offer to verify it* instead.
            assertTrue(
                system.contains("Say when it was observed, or that nothing since has confirmed it"),
                "the two ways out of a stale fact are gone, so the rule states the fault and asks " +
                    "for nothing in its place: $system",
            )
            assertFalse(
                system.contains("offer to check"),
                "the way out of a stale fact is an undertaking the sender never made, which the " +
                    "rule above forbids in the same instruction: $system",
            )
        }

    @Test
    fun `the sender's own turns are marked, so the draft is not composed in the other voice`(): Unit =
        runBlocking {
            // **The shape this was measured on, against the real archive.** Asked for a reply to a
            // counterpart, the drafts came back composed as that counterpart writing to the archive's
            // owner: the owner's own undertakings read as things the draft promised, and the
            // counterpart's questions read as things it asked. Three fluent drafts, in the right
            // language and register, that nobody could send.
            //
            // Nothing hermetic caught it. The shape answered, the drafts differed from one another,
            // and the grounding gate passed because a draft carries no claims by design — so the
            // material is the only place a check can bite, and what it has to show is which side of
            // the thread the sender is on.
            //
            // The fixture is the arrangement that inverted: the thread's last message is the
            // counterpart's, and the owner has undertaken something earlier in it. That ordering is
            // why there is a reply to write at all, and it is what a shape with no mark reads as the
            // reply being owed the other way.
            val thread = FakeRetrieval.message("1", "x").externalId
            val retrieval = FakeRetrieval(
                conversations = listOf(thread),
                messages = listOf(
                    FakeRetrieval.message("1", "I will send the figures on Friday"),
                    FakeRetrieval.message("2", "any word on those figures?"),
                ),
                turns = mapOf(
                    FakeRetrieval.record("1") to
                        FakeRetrieval.turn("The Owner", thread, isSelf = true),
                    FakeRetrieval.record("2") to FakeRetrieval.turn("The Counterpart", thread),
                ),
            )

            val retrieved = retrieve(retrieval, FakeIndex()).associateBy { it.id.record.nativeId }

            assertEquals(
                "Sent by The Owner (the sender you are drafting for): I will send the figures on Friday",
                retrieved.getValue("1").text,
                "the sender's own turn is unmarked, so nothing in the material says which side " +
                    "the draft is written from",
            )
            assertEquals(
                "Sent by The Counterpart$LATEST_ALSO: any word on those figures?",
                retrieved.getValue("2").text,
                "the counterpart's turn is marked as the sender's, which inverts the draft the " +
                    "other way",
            )
        }

    @Test
    fun `the instruction says what a draft is for and not only how to write it`(): Unit = runBlocking {
        // **What this pins is the absence that produced the defect.** The instruction said how to
        // write and never what to write about, and a summary of the sender's own earlier turns
        // satisfies every rule it did carry — including the voice rule, which is why the card that
        // added that one did not catch this: a restatement in the sender's own voice is exactly
        // what came back, correctly attributed, every citation checking out, and nothing in it new
        // to the person receiving it.
        //
        // Prose pins, which are the weak kind, knowingly: they catch a clause being dropped rather
        // than a sentence elsewhere working against it, and only reading drafts can say whether the
        // model obeys them. The date rule is here for what it still covers — everything the
        // latest-exchange mark does not settle, a model placing a fact by its position in the
        // prompt being the positional reading in a second guise — and the no-mark fallback because
        // it is the case a prior card had to add twice.
        val stages = shape(FakeRetrieval(), FakeIndex()).stages
        val carried = assertIs<Progress.Carried>(
            stages[1].apply(Exchange(Request(text = "draft a reply"))),
        )
        val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

        assertTrue(
            system.contains("A reply answers what the other party has left open"),
            "nothing says what a draft is aimed at, so a summary of the sender's own position " +
                "satisfies every other rule the instruction carries: $system",
        )
        assertTrue(
            system.contains("what this recipient has already been told"),
            "nothing tells the model to establish what the recipient already has before " +
                "composing, so the rule below it has nothing to be applied against: $system",
        )
        assertTrue(
            system.contains("do not manufacture something to answer"),
            "a thread with nothing open is left to the rule that drafts must genuinely differ, " +
                "which is satisfied most easily by re-opening what was settled: $system",
        )
        assertTrue(
            system.contains("says when it was said, and that is what to go by"),
            "nothing sends the model to the dates, so whatever the latest-exchange mark does not " +
                "settle falls back to the order the material is offered in, which is relevance " +
                "and not time: $system",
        )
        assertTrue(
            system.contains("Where no turn carries the sender's mark"),
            "the rule is stated as though the mark were always there, so on material carrying " +
                "none it asks for a comparison against turns that are not offered: $system",
        )
    }

    @Test
    fun `the sender's own turns are register and also what was already delivered`(): Unit = runBlocking {
        // **Naming only the first use is what makes a restatement the easiest draft to write.**
        // The same turns answer two different questions — how the sender writes, and what the
        // sender has already said — and the instruction offers them for the first in the
        // paragraph about register. A register example carries an explicit rule bounding it to its
        // wording; with no matching rule, the sender's own turns are wording to imitate and
        // content to compose from at once, and that content is precisely what the other party has.
        //
        // **The second use is pinned on the conversation and not on a universal, which is the
        // weaker claim on purpose.** Only the contact route through `conversationsOf` selects on
        // authorship, so a thread reached through a client or a named conversation may be one the
        // counterpart never wrote in, and a register example comes from outside the followed
        // threads entirely. An instruction asserting the counterpart was there for everything
        // marked as the sender's own would be false on those routes — and false in the direction
        // that withholds material its readers never got, which is this card's defect inverted.
        //
        // Nothing here asserts a property of the retrieval: the thread-membership half lives in
        // `CONTACT_CONVERSATIONS`'s SQL and is unreachable from a fake whose conversations are an
        // input. What is pinned is that the instruction keys the rule on the conversation, because
        // that is the form the model can apply per block.
        //
        // Prose pins, the weak kind, knowingly — they catch a clause being dropped, and only
        // reading drafts can say whether the model obeys them.
        val stages = shape(FakeRetrieval(), FakeIndex()).stages
        val carried = assertIs<Progress.Carried>(
            stages[1].apply(Exchange(Request(text = "draft a reply"))),
        )
        val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

        assertTrue(
            system.contains("two things at once"),
            "the sender's own turns are offered for their wording alone, so composing a draft " +
                "out of their content breaks no rule the instruction carries: $system",
        )
        assertTrue(
            system.contains("weigh what they say as delivered to those people already"),
            "nothing says what the second use is for, so the turns read as material to compose " +
                "from rather than as the thing a draft is measured against: $system",
        )
        assertTrue(
            system.contains("the people in the conversation it was said in"),
            "the already-delivered set is stated over the material rather than per conversation, " +
                "which is false wherever a thread was reached through a client or a named " +
                "conversation and withholds material the readers never got: $system",
        )
        assertFalse(
            system.contains("as a register example does"),
            "a sender-marked turn is equated with a register example, which carries rules " +
                "forbidding it to be replied to, stated as fact or cited — none of which a " +
                "citable turn of the sender's may inherit: $system",
        )
    }

    @Test
    fun `a mention is allowed and a restatement of the detail is not`(): Unit =
        runBlocking {
            // **A reply has to be able to name the thing it is about.** So a flat ban on
            // mentioning anything already said is one the correct draft breaks, and a rule the
            // correct answer breaks is discounted wholesale rather than obeyed narrowly. The line
            // is therefore drawn at the content rather than at the mention.
            //
            // **Where the carve-out is pinned to its wording, and why.** Against the live model,
            // permitting *a short confirmation* without saying what a confirmation may contain was
            // read as permission to confirm by re-listing what had been restored — the measured
            // defect, reached through its own fix, on a request the unchanged instruction answered
            // without the list. So what the carve-out excludes is the half that has to survive a
            // rewording: the content, the list and the detail, named.
            //
            // The positive half is pinned beside it because a prohibition cannot carry it: told
            // only what not to send, a model sends the nearest thing outside the prohibition,
            // which is the same reasoning the vocabulary rule for unrecorded outcomes records.
            val stages = shape(FakeRetrieval(), FakeIndex()).stages
            val carried = assertIs<Progress.Carried>(
                stages[1].apply(Exchange(Request(text = "draft a reply"))),
            )
            val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains("None of it is delivered again"),
                "the prohibition itself is gone, leaving the identification step above it with " +
                    "nothing to forbid: $system",
            )
            assertTrue(
                system.contains("You may refer to such a thing where a sentence needs it to stand up"),
                "the rule reads as an absolute ban on naming anything already said, which the " +
                    "right reply breaks, so it is discounted rather than narrowed: $system",
            )
            assertTrue(
                system.contains("never restating its content, its list or its detail"),
                "the carve-out permits a repeat without bounding what it may contain, which is " +
                    "read as permission to confirm by re-listing the detail: $system",
            )
            assertTrue(
                system.contains("each draft is built out of what this recipient does not have"),
                "the rule is stated only as a prohibition, so the model satisfies it with the " +
                    "nearest thing outside it rather than with something to deliver: $system",
            )
            // **The hand-off to the nothing-open rule has to carry this bound with it.** That rule
            // bounds a confirmation's length — short, and nothing beyond it — and says nothing
            // about what may be inside one. So a bare pointer would switch the content bound off
            // on exactly the thread the restatement arises on, which is the one with nothing
            // obviously open. The measured defect was a confirmation that re-listed.
            assertTrue(
                system.contains("a confirmation names what it confirms and does not restate it"),
                "the nothing-open case is handed off without the content bound, and the rule it " +
                    "is handed to bounds only length: $system",
            )
            // The exception is pinned because without it the rule silences the one restatement
            // that is load-bearing — a fact the thread has since put back in question, which is
            // the case the superseded-fact card is about from the other side.
            assertTrue(
                system.contains("put back in question is the exception, and is stated afresh"),
                "the rule admits no exception, so a fact something since has unsettled is " +
                    "withheld as already delivered: $system",
            )
            // **Scoped to the draft's audience, because most threads here are rooms.** A draft is
            // composed for one conversation, and what the sender said in a different thread was
            // not read by this one's readers — so an already-delivered set taken over the whole
            // material withholds from a room what only the counterpart ever saw.
            assertTrue(
                system.contains("what the sender said in a thread its readers were not in"),
                "the already-delivered set is not scoped to the conversation the draft is for, " +
                    "so material a room never saw is withheld from it: $system",
            )
        }

    @Test
    fun `the drafts carry the same facts, and the rule says what may differ instead`(): Unit =
        runBlocking {
            // **Told the difference must be real and not told what has to stay the same, a model
            // divides the facts up.** Measured on the archive: across one set of drafts, one
            // carried a list of restored items, another a lifted restriction and an access grant
            // the first omitted, and a third neither — so choosing any of them dropped a fact and
            // the reader had to write a further message out of the set. That is the one thing
            // nothing downstream can see: complete-looking messages, every other rule obeyed by
            // each of them, and the defect visible only by reading them side by side.
            //
            // **The positive half is pinned beside the prohibition it reconciles.** The paragraph
            // above forbids variations of one sentence; a rule demanding identical facts, read
            // beside it, is a prompt that forbids a reading and then asks for it, which a model
            // resolves by picking a side. So what is pinned is the axes being named, not only the
            // facts being held.
            //
            // **The explaining axis is pinned on its own, because it is the one reading that
            // survives the invariant.** *How much they explain* reads equally as how much is
            // explained and as how much there is to explain, and the second is the defect — so it
            // is pinned resolved rather than left to be inferred.
            //
            // No count is pinned, and none is written: the range is two or three, so a pin naming
            // a number of drafts states what the shape contradicts whenever it writes two.
            //
            // What conditions all of this is the case below; the debt it reaches is the case after
            // that. Prose pins, the weak kind: they catch a clause being dropped, not a model
            // disobeying it. What a model does with them is measured against the live model.
            val stages = shape(FakeRetrieval(), FakeIndex()).stages
            val carried = assertIs<Progress.Carried>(
                stages[1].apply(Exchange(Request(text = "draft a reply"))),
            )
            val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains(
                    "Everything the reply owes is in every one of them, so the sender can send " +
                        "whichever reads best and lose nothing another one had",
                ),
                "the invariant does not say what it is for, so a draft missing what another " +
                    "carries is not what it forbids: $system",
            )
            assertTrue(
                system.contains(
                    "that asks for more than a re-worded sentence, since the same facts can be put " +
                        "briefly or at length, plainly or warmly, with more explanation around " +
                        "them or less",
                ),
                "the rule holds the facts identical and never says what may differ, so it reads " +
                    "against the paragraph above forbidding variations of one sentence and the " +
                    "model picks one of the two: $system",
            )
            assertTrue(
                system.contains("A draft that explains more does so in more words about the same things"),
                "the explaining axis is left unresolved, so differing in how much they explain " +
                    "reads as differing in how much there is to explain: $system",
            )
            assertTrue(
                system.contains(
                    "a draft leaving out what another carries is no alternative to it, because " +
                        "choosing between them is choosing which fact to drop",
                ),
                "the failure is unnamed, so a partition reads as drafts that genuinely differ, " +
                    "which is what the rule above asks for: $system",
            )
            assertTrue(
                system.contains("the sender is left writing one more message out of what you gave them"),
                "what the partition costs the reader is unstated, and it is the cost rather than " +
                    "the form that makes it wrong: $system",
            )
        }

    @Test
    fun `the completeness rule is conditioned on the thread leaving something open`(): Unit =
        runBlocking {
            // **One clause disclaiming the owed half is not enough to hold this rule off the
            // settled thread.** That case's honest answer is a bare acknowledgement against one
            // adding what has changed, which is a difference in content — so any sentence of the
            // rule left standing over the drafts at large forbids it, and the ones about dividing
            // the material are exactly those. So the condition opens the rule and the close
            // repeats it, and both are pinned: between them nothing of it is unconditioned, and
            // what a draft says on a settled thread stays settled in one place rather than two,
            // which is what keeps a model from resolving the prompt by picking a half of it.
            //
            // The rule it hands off to is asserted by the case below, which pins the alternatives
            // that case names — so a hand-off to a rule that had gone would redden there.
            val stages = shape(FakeRetrieval(), FakeIndex()).stages
            val carried = assertIs<Progress.Carried>(
                stages[1].apply(Exchange(Request(text = "draft a reply"))),
            )
            val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains(
                    "Where the thread leaves something open, the drafts differ in how they say it " +
                        "and not in how much of it they say",
                ),
                "the rule is stated unconditioned, so it reaches the settled thread, whose " +
                    "honest answer is a difference in content: $system",
            )
            assertTrue(
                system.contains(
                    "Where the thread has left nothing open none of this applies, and what to " +
                        "write is settled below",
                ),
                "the rule does not close by releasing the settled thread, so the sentences about " +
                    "dividing the material still reach it: $system",
            )
        }

    @Test
    fun `what the drafts owe reaches past the exchange being answered`(): Unit =
        runBlocking {
            // **A request further back that nothing has met is still owed**, which the clause
            // bounding the marked turns is there to say and the one-reply case below pins. So an
            // invariant keyed on the marked exchange alone would leave a partition across exactly
            // the material that clause admits — one draft carrying the older request and the
            // others not, which is the measured defect in the one case the sibling rule
            // introduces. The debt is keyed on the reply instead, and names both sources.
            //
            // The disjunction is pinned here too, told what it is rather than reworded: its kinds
            // offered with *or* read as a choice to make per draft, and the enumeration is what
            // makes that sentence usable. It carries the condition for a reason of its own —
            // *what has changed since* is one of the kinds, and it is precisely what the
            // nothing-open rule leaves a draft free to add or to omit.
            val stages = shape(FakeRetrieval(), FakeIndex()).stages
            val carried = assertIs<Progress.Carried>(
                stages[1].apply(Exchange(Request(text = "draft a reply"))),
            )
            val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains(
                    "Whatever the reply owes is owed by each draft alike, whether it was the " +
                        "exchange being answered that left it open or something further back",
                ),
                "the debt is keyed on the marked exchange alone, so material owed from further " +
                    "back — which the clause bounding the mark keeps in scope — may still be " +
                    "divided between the drafts: $system",
            )
            assertTrue(
                system.contains("That list is the kinds of thing to look for and not a choice between them"),
                "the kinds a draft is built out of are offered in a disjunction, which reads " +
                    "as one to pick per draft and is the partition the rule above forbids: $system",
            )
            assertTrue(
                system.contains(
                    "where the exchange leaves any of it still owed, what one draft carries of it " +
                        "they all carry",
                ),
                "the disjunction is told it is not a choice without being told what to do with " +
                    "it, or is told unconditionally — where it requires what has changed since of " +
                    "every draft on a thread the nothing-open rule governs: $system",
            )
        }

    @Test
    fun `all of the drafts are aimed at one thing, so a side thread is no alternative`(): Unit =
        runBlocking {
            // **The other half of the measured defect.** One draft answered a side question older
            // than the exchange the others addressed, so it was no alternative to them but a
            // second reply, offered as a choice.
            //
            // **What permitted it holds, so the bound is pinned beside it rather than in place of
            // it.** The clause keeping the rest of the material in bounds is what makes a request
            // nothing has met still owed. What it does not say is where such a request goes, and
            // the answer is inside the reply to the marked turns rather than instead of it — so
            // the permission is asserted present here as well as on the mark's own case, since a
            // bound whose permission has gone narrows nothing and this one would then read as
            // excluding the older request outright.
            //
            // **The arm for the marked turns leaving nothing open is pinned**, because without it
            // the rule forbids the only sendable reply on a thread whose live end asks nothing and
            // whose older request is still owed — and a rule the correct answer breaks is
            // discounted wholesale rather than obeyed narrowly.
            //
            // The cost is pinned with the rule because this is the failure that reads as success:
            // well-formed drafts, each sendable, some answering one thing and one another, which a
            // reader notices only by asking what each is a reply to.
            val stages = shape(FakeRetrieval(), FakeIndex()).stages
            val carried = assertIs<Progress.Carried>(
                stages[1].apply(Exchange(Request(text = "draft a reply"))),
            )
            val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains(
                    "All of the drafts are aimed at the same thing, and where the marked turns " +
                        "leave something open that is what they are aimed at",
                ),
                "nothing holds the drafts to one reply, so a side thread's question is offered " +
                    "as one draft's subject while the others answer the marked turns: $system",
            )
            assertTrue(
                system.contains("What the mark does not do is put the rest of the material out of bounds"),
                "the permission this rule bounds is gone, so the bound narrows nothing and a " +
                    "request further back that nothing met is excluded outright: $system",
            )
            assertTrue(
                system.contains(
                    "carried inside the reply to it rather than taken as one draft's subject instead",
                ),
                "an older request that is still owed is left without a place, so the draft that " +
                    "carries it carries it instead of the exchange rather than within it: $system",
            )
            assertTrue(
                system.contains("no alternative to the others but a second reply"),
                "what a draft aimed elsewhere is wrong about is unnamed, so it reads as the " +
                    "genuine difference the count asks for: $system",
            )
            assertTrue(
                system.contains(
                    "Where the marked turns leave nothing open and something further back is " +
                        "still owed, that is what every draft answers",
                ),
                "the rule is unconditional, so on a thread whose live end asks nothing it forbids " +
                    "answering the one thing still owed — which is the whole of the reply " +
                    "there: $system",
            )
        }

    @Test
    fun `the instruction bounds what a draft may undertake, and where it reaches for one`(): Unit =
        runBlocking {
            // **The one thing in a draft that nothing downstream can see.** The prose carries no
            // claims by construction, so the grounding check never reads it; and an offer is about
            // what will be done rather than about the corpus, so the rule against stating what the
            // material does not show does not reach one either. A draft closing with an offer the
            // sender never made is well-formed by every other rule here, and the sender approves it
            // rather than checking it against a thread they were present for.
            //
            // The axis is pinned from both sides, because only one of them rots. An axis telling
            // the drafts to differ in what they commit to asks for exactly the thing the rule
            // forbids, so a rule forbidding it elsewhere would argue with the instruction rather
            // than add to it — but the negative alone passes on any rewording of that axis, so
            // the axis that is there is pinned too. It names how much the drafts explain and not
            // how much of what is open they take up: the latter is not a choice to offer, since
            // the rule above requires all of it of every draft.
            //
            // The last two cover where the bound has to reach. The end of a message is the
            // position the measured cases shared, and the close that asks for a detail and says
            // what will be done once it arrives is the one form the rest of the bound leaves
            // standing. The mark arm is here for the reason the case above gives: a rule reading
            // the sender's mark is vacuous on material carrying none, and this file has had to
            // add that arm twice.
            //
            // Prose pins, the weak kind, for the reason the test above gives: they catch a clause
            // being dropped, not a model disobeying it.
            val stages = shape(FakeRetrieval(), FakeIndex()).stages
            val carried = assertIs<Progress.Carried>(
                stages[1].apply(Exchange(Request(text = "draft a reply"))),
            )
            val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains("An undertaking is not yours to make"),
                "nothing bounds what the draft may promise, and the fact rule does not reach a " +
                    "promise, which is about what will be done and not about the material: $system",
            )
            assertFalse(
                system.contains("in what they commit to"),
                "the drafts are told to differ by committing to different things, which asks for " +
                    "the undertaking the rule above forbids: $system",
            )
            assertTrue(
                system.contains("in register, in length, or in how much they explain"),
                "the axis is reworded, and the wordings that invite the forbidden thing are not " +
                    "one phrase: an axis varying what a draft promises asks for an originated " +
                    "undertaking, and one varying how much of what is open it takes up offers a " +
                    "choice the rule requiring all of it does not leave open: $system",
            )
            assertTrue(
                system.contains("Watch the end of the message"),
                "the bound is unplaced, and the position it has to cover is the closing, where " +
                    "the register itself reaches for an offer nobody made: $system",
            )
            assertTrue(
                system.contains("the ask ends where the ask ends"),
                "the one close the bound does not otherwise reach is a request for a detail with " +
                    "what will be done once it arrives appended to it, which is the form the " +
                    "undertaking survived in: $system",
            )
            assertTrue(
                system.contains("Where no turn carries the sender's mark there is nothing to tell"),
                "the rule reads the sender's mark to tell their undertakings from the other " +
                    "party's, and says nothing about material carrying none, where it either " +
                    "carries the counterpart's as theirs or collapses to carrying none: $system",
            )
        }

    @Test
    fun `nothing-open turns on what the exchange asks and not on who wrote last`(): Unit =
        runBlocking {
            // **The rule was gated on a conjunction whose first half is false in the ordinary
            // case.** It applied where the sender wrote last *and* nothing after that asked
            // anything of them, so the common thread — the other party wrote last, and what they
            // wrote asked for nothing — satisfied the second half, failed the first, and fell
            // through to the rule that the drafts must genuinely differ. That one is satisfied most
            // cheaply by raising something, which is the measured defect: three substantive
            // messages out of material the recipient already had.
            //
            // **The old conjunction is asserted gone, not only the new condition present.** A pin
            // on the new wording alone passes with both in the prompt, and two rules over one case
            // is how a model is left to resolve a prompt by picking a half of it.
            //
            // **Both halves of the condition are pinned, because each one alone inverts it.**
            // Without *still waiting on the sender* a question the sender already answered reads as
            // one still asked, and the settled thread the rule exists for stops qualifying; without
            // the rest of it, an undertaking the sender has not reported on is silenced on a thread
            // where the other party asked for nothing, which is the reading the voice rule forbids.
            //
            // The addressee clause is here because it is the other half of what the proxy stood in
            // for: rooms are most of this corpus's threads, so *waiting on the sender* is only
            // decidable if a question put to somebody else in the room is not read as one put to
            // them. It deliberately names no channel mark — that mark is defined further down the
            // instruction, and an addressee is read off the words — so there is no marker here to
            // read off the rendered material, which is what the channel pin below does instead.
            //
            // What the honest answer is told to write is the case below, which is the other half
            // of this rule and is asserted apart from what makes it fire.
            val stages = shape(FakeRetrieval(), FakeIndex()).stages
            val carried = assertIs<Progress.Carried>(
                stages[1].apply(Exchange(Request(text = "draft a reply"))),
            )
            val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

            assertFalse(
                system.contains("the sender wrote last, and nothing after that asks anything of them"),
                "the rule is still gated on the sender having written last, which is false on an " +
                    "active thread and takes the rule with it: $system",
            )
            assertTrue(
                system.contains(
                    "Nothing is left open when nothing in that exchange is still waiting on the " +
                        "sender",
                ),
                "the condition is not stated as what the exchange still leaves waiting, so what " +
                    "makes it fire is left to whatever the prompt said before: $system",
            )
            assertTrue(
                system.contains(
                    "no question from the other party the sender has not already answered, " +
                        "nothing they asked for that the sender has not met, and nothing the " +
                        "sender undertook and has not reported on",
                ),
                "the condition names no exclusions, so a question already answered still reads as " +
                    "one asked and an undertaking nobody re-asked about is silenced: $system",
            )
            assertTrue(
                system.contains("Which party wrote last does not decide it"),
                "who wrote last is not demoted from the test to one way of satisfying it, so the " +
                    "ordinary case — the other party wrote last, asking nothing — still falls " +
                    "through: $system",
            )
            assertTrue(
                system.contains(
                    "A turn of the other party's that answers what the sender asked earlier, or " +
                        "reports where something of their own stands, asks for nothing by itself",
                ),
                "the arm that makes the ordinary case decidable is unstated, so the condition is " +
                    "left to be read off who sent the turn: $system",
            )
            assertTrue(
                system.contains(
                    "Where other people are in the thread and a question is put to one of them, " +
                        "it is not put to the sender: go by who a turn addresses, not by who sent " +
                        "it",
                ),
                "nothing says a question put to a third participant is not put to the sender, so " +
                    "in a room the condition cannot be read off the turns: $system",
            )
        }

    @Test
    fun `the honest answer is told what its alternatives are and reports why it is short`(): Unit =
        runBlocking {
            // **The count is the reason this half exists.** Two or three is the shape's own range
            // and the runner holds every reply to it, so the case whose honest answer is one
            // confirmation cannot be given a count of its own — it can only be told what its
            // alternatives are. Told nothing, the third draft is the one that finds a difference by
            // raising something, which is the defect the rule is for.
            //
            // The claim line is pinned on its citation clause rather than its wording, because an
            // uncited claim line is dropped and takes its variant with it — so what has to survive
            // is the instruction to cite, not the sentence around it.
            val stages = shape(FakeRetrieval(), FakeIndex()).stages
            val carried = assertIs<Progress.Carried>(
                stages[1].apply(Exchange(Request(text = "draft a reply"))),
            )
            val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains("a bare acknowledgement against one that adds what has changed"),
                "the honest answer is left without its alternatives, and the count is mechanical " +
                    "— so the difference gets invented, which is the defect: $system",
            )
            assertTrue(
                system.contains(
                    "a draft that opens a subject so as to have something to say is that invention",
                ),
                "the warning names no form, so raising a subject to fill the count is not what it " +
                    "forbids: $system",
            )
            assertTrue(
                system.contains("citing the turn it ends in"),
                "nothing carries the fact that the exchange asked nothing to the reader, so a " +
                    "short pair of drafts reads as a thin reply rather than as the answer: $system",
            )
        }

    @Test
    fun `the material is not one run of time, which is what the mark and the dates both work around`(): Unit =
        runBlocking {
            // **Why neither the exchange nor a date can be read off the order.** Position in this
            // list is relevance, not time, in two ways that compound: the shape reads several
            // conversations and they arrive concatenated closest-first with nothing rendered
            // between them, and the ranked hits are appended after the whole window although they
            // reach further back than it. So the last block of a draft's prompt is routinely the
            // oldest thing in it.
            //
            // An instruction saying to read the material forward would find its last unanswered
            // question in a quieter thread, or in a turn the window had already left behind, and
            // draft a reply to something settled long ago. That is why the exchange is selected in
            // the retrieval and marked, and why the date rule that remains is scoped to what the
            // mark does not settle. The fixture is the arrangement the shape actually produces, not
            // one conversation: with a single thread and no ranked hit, position and time agree and
            // every reading passes alike.
            //
            // What a check can hold is that the material is still offered this way, so both rules
            // working around it stay true. If this ever becomes one run of time, the mark and the
            // date rule are both what to revisit.
            val recent = FakeRetrieval.record("recent-thread")
            val quiet = FakeRetrieval.record("quiet-thread")
            val lastSpring = FakeRetrieval.WHEN.minusSeconds(SPAN)
            val longBefore = FakeRetrieval.WHEN.minusSeconds(SPAN * 2)
            val retrieval = FakeRetrieval(
                conversations = listOf(recent, quiet),
                messages = listOf(
                    FakeRetrieval.message("1", "any word on those figures?", FakeRetrieval.WHEN),
                    FakeRetrieval.message("2", "we settled the terms back then", lastSpring),
                ),
                turns = mapOf(
                    FakeRetrieval.record("1") to FakeRetrieval.turn("The Counterpart", recent),
                    FakeRetrieval.record("2") to FakeRetrieval.turn("The Counterpart", quiet),
                    // In a thread this request read, so it is offered as that thread's own turn
                    // rather than as a register example — which is what puts material older than
                    // the window at the end of the prompt wearing no marker.
                    FakeRetrieval.record("90") to FakeRetrieval.turn("The Counterpart", recent),
                ),
            )
            val index = FakeIndex(hits = listOf(span("90", "confirming the old rate", longBefore)))

            val prompt = assemble(retrieval, index)

            // Matched on the date and the minute rather than on the instant's own `toString`:
            // a block is dated in the correspondents' zone, which this fixture leaves at UTC, and
            // the offset it carries is not part of what this case claims. What it claims is that
            // every block says when it was said.
            listOf(FakeRetrieval.WHEN, lastSpring, longBefore).forEach { at ->
                assertTrue(
                    prompt.contains(WHEN_WRITTEN.format(at)),
                    "a block reaches the model without saying when it was said, so the rule that " +
                        "reads the dates has nothing to read on it: $prompt",
                )
            }
            listOf(
                "we settled the terms back then" to "a quieter thread's older turns",
                "confirming the old rate" to "ranked material reaching behind the window",
            ).forEach { (text, what) ->
                assertTrue(
                    prompt.indexOf(text) > prompt.indexOf("any word on those figures?"),
                    "$what no longer follows newer material, so this may be one run of time now " +
                        "— if it is, the rule sending the model to the dates rather than to the " +
                        "order is what needs revisiting: $prompt",
                )
            }
        }

    @Test
    fun `the latest exchange is marked, and nothing older carries the mark`(): Unit = runBlocking {
        // **The arrangement the defect needs, which is what this fixture is for.** The drafts
        // replied to an older request the thread had since moved past, while the turns that
        // overtook it sat in the window and were cited either side of — so nothing was missing and
        // what failed was finding them. The instruction sent the model to the instant on each
        // block, and the block it had to find was neither first nor last.
        //
        // So the exchange is selected here instead, and the fixture discriminates: an unanswered
        // older request, the sender's own later turn, and then the turns that arrived since — a
        // status summary, a third participant's question, and the counterpart answering it
        // themselves. A second thread and a ranked hit follow all of it and are older than it,
        // which is what makes position no guide to time. A reading that took the first, the last,
        // or the whole thread would fail on this fixture and pass on a single-thread one.
        val live = FakeRetrieval.record("live-thread")
        val quiet = FakeRetrieval.record("quiet-thread")
        val retrieval = FakeRetrieval(
            conversations = listOf(live, quiet),
            messages = listOf(
                FakeRetrieval.message("stale-ask", "can you send the figures?", DAYS_BEFORE),
                FakeRetrieval.message("own", "looking at it now", minutesBefore(150)),
                FakeRetrieval.message("summary", "it is all back up, here is what I restored", minutesBefore(74)),
                FakeRetrieval.message("third", "tell me when the access route is ready", minutesBefore(72)),
                FakeRetrieval.message("answered", "it is ready, go ahead", minutesBefore(64)),
                FakeRetrieval.message("aside", "we settled the terms back then", LONG_BEFORE),
            ),
            turns = mapOf(
                FakeRetrieval.record("stale-ask") to FakeRetrieval.turn("The Counterpart", live),
                FakeRetrieval.record("own") to FakeRetrieval.turn("The Owner", live, isSelf = true),
                FakeRetrieval.record("summary") to FakeRetrieval.turn("The Counterpart", live),
                FakeRetrieval.record("third") to FakeRetrieval.turn("Another Participant", live),
                FakeRetrieval.record("answered") to FakeRetrieval.turn("The Counterpart", live),
                FakeRetrieval.record("aside") to FakeRetrieval.turn("The Counterpart", quiet),
                FakeRetrieval.record("90") to FakeRetrieval.turn("The Counterpart", live),
            ),
        )
        val index = FakeIndex(hits = listOf(span("90", "confirming the old rate", LONG_BEFORE)))

        val marked = marked(retrieve(retrieval, index))

        assertEquals(
            setOf("summary", "third", "answered"),
            marked,
            "the turns a reply has to answer are not the ones marked as the latest exchange, so " +
                "finding them is arithmetic over every heading again",
        )
    }

    @Test
    fun `the mark runs from after the sender's own previous turn, which is what left open means`(): Unit =
        runBlocking {
            // Everything said since the sender last wrote is what they have not answered, and the
            // boundary is read off the marks on the record rather than off a gap in the clock —
            // there is no tolerance here to be wrong about. The sender's own previous turn is
            // outside the exchange it closed; a turn of theirs that is itself the newest is not,
            // which is the case below.
            val thread = FakeRetrieval.record("the-thread")
            val retrieval = FakeRetrieval(
                conversations = listOf(thread),
                messages = listOf(
                    FakeRetrieval.message("earlier-ask", "and the second batch?", minutesBefore(300)),
                    FakeRetrieval.message("own", "both are with you now", minutesBefore(240)),
                    FakeRetrieval.message("asked", "can you look at the third?", minutesBefore(60)),
                ),
                turns = mapOf(
                    FakeRetrieval.record("earlier-ask") to FakeRetrieval.turn("The Counterpart", thread),
                    FakeRetrieval.record("own") to FakeRetrieval.turn("The Owner", thread, isSelf = true),
                    FakeRetrieval.record("asked") to FakeRetrieval.turn("The Counterpart", thread),
                ),
            )

            assertEquals(
                setOf("asked"),
                marked(retrieve(retrieval, FakeIndex())),
                "the exchange the sender already closed is marked as the live one, so a draft " +
                    "answering it satisfies the rule",
            )
        }

    @Test
    fun `where the sender wrote last, their own turn carries the mark`(): Unit = runBlocking {
        // **The signal the nothing-open rule needs, and it has to be inside the mark to be read.**
        // That the sender wrote last is what says there may be nothing to reply to, and a mark that
        // stopped at the counterpart's turns would show the exchange with its last turn missing —
        // so a thread the sender has already answered would read as one still waiting on them.
        val thread = FakeRetrieval.record("the-thread")
        val retrieval = FakeRetrieval(
            conversations = listOf(thread),
            messages = listOf(
                FakeRetrieval.message("own-earlier", "I will check tonight", minutesBefore(300)),
                FakeRetrieval.message("asked", "any word on those figures?", minutesBefore(120)),
                FakeRetrieval.message("own-last", "sent them over just now", minutesBefore(60)),
            ),
            turns = mapOf(
                FakeRetrieval.record("own-earlier") to
                    FakeRetrieval.turn("The Owner", thread, isSelf = true),
                FakeRetrieval.record("asked") to FakeRetrieval.turn("The Counterpart", thread),
                FakeRetrieval.record("own-last") to
                    FakeRetrieval.turn("The Owner", thread, isSelf = true),
            ),
        )

        val retrieved = retrieve(retrieval, FakeIndex()).associateBy { it.id.record.nativeId }

        assertEquals(setOf("asked", "own-last"), marked(retrieved.values.toList()))
        assertTrue(
            retrieved.getValue("own-last").text.contains(IS_THE_SENDER) &&
                retrieved.getValue("own-last").text.contains(LATEST_ALSO),
            "the newest marked turn does not say it is the sender's own, so nothing in the " +
                "material says they wrote last: ${retrieved.getValue("own-last").text}",
        )
    }

    @Test
    fun `the mark is bounded, so it still tells one stretch of the material from the rest`(): Unit =
        runBlocking {
            // A clause distinguishes a stretch of the material from the rest of it, and one carried
            // by a sizeable share of the window distinguishes nothing — which is the heading
            // problem again in a second wording. A counterpart who writes a great many turns with
            // nothing from the sender is what it bites on, and the newest of them are kept.
            //
            // The bound itself is not named here: what this holds is that there is one and that it
            // keeps the newest end, so a change to the figure is a judgement rather than a red.
            val thread = FakeRetrieval.record("the-thread")
            val run = (1..RUN).map {
                FakeRetrieval.message("turn-$it", "another thought $it", minutesBefore(RUN - it + 1))
            }
            val retrieval = FakeRetrieval(
                conversations = listOf(thread),
                messages = listOf(FakeRetrieval.message("own", "noted", minutesBefore(RUN + 60))) + run,
                turns = run.associate {
                    it.externalId to FakeRetrieval.turn("The Counterpart", thread)
                } + (
                    FakeRetrieval.record("own") to
                        FakeRetrieval.turn("The Owner", thread, isSelf = true)
                    ),
            )

            val marked = marked(retrieve(retrieval, FakeIndex()))

            assertTrue(
                marked.size < RUN,
                "the mark reached every turn since the sender last wrote, so it no longer tells " +
                    "the latest exchange from the window: ${marked.size} of $RUN",
            )
            assertTrue(
                "turn-$RUN" in marked && "turn-1" !in marked,
                "the bound dropped the newest end of the run rather than the oldest: $marked",
            )
        }

    @Test
    fun `a run of the sender's own closing turns keeps the exchange it answers`(): Unit = runBlocking {
        // **A sender who signs off in more than one message is ordinary**, and a boundary taken at
        // their previous turn would mark the last of the run alone — dropping the turn it answers
        // while the instruction aims the reply at what is marked. So the boundary is taken before
        // the newest turn that is *not* theirs, and the whole run comes with it.
        val thread = FakeRetrieval.record("the-thread")
        val retrieval = FakeRetrieval(
            conversations = listOf(thread),
            messages = listOf(
                FakeRetrieval.message("own-earlier", "will look tonight", minutesBefore(300)),
                FakeRetrieval.message("asked", "can you send the figures?", minutesBefore(180)),
                FakeRetrieval.message("own-1", "sent them over", minutesBefore(60)),
                FakeRetrieval.message("own-2", "and the second batch is in the same folder", minutesBefore(55)),
            ),
            turns = mapOf(
                FakeRetrieval.record("own-earlier") to
                    FakeRetrieval.turn("The Owner", thread, isSelf = true),
                FakeRetrieval.record("asked") to FakeRetrieval.turn("The Counterpart", thread),
                FakeRetrieval.record("own-1") to FakeRetrieval.turn("The Owner", thread, isSelf = true),
                FakeRetrieval.record("own-2") to FakeRetrieval.turn("The Owner", thread, isSelf = true),
            ),
        )

        assertEquals(
            setOf("asked", "own-1", "own-2"),
            marked(retrieve(retrieval, FakeIndex())),
            "the sender's closing run is marked without the turn it answers, so the exchange is " +
                "shown with the thing being replied to missing",
        )
    }

    @Test
    fun `a blank turn of the sender's still closes the exchange it answered`(): Unit = runBlocking {
        // **The one place a blank turn has to count.** A sender who answers with the file itself
        // has answered, and a boundary blind to that turn would mark the request they just met as
        // part of the live exchange while showing no turn of theirs — which reads as the request
        // being unanswered and the sender silent, both wrong. It is still kept out of the mark,
        // because a captionless file is not something a reply can answer.
        val thread = FakeRetrieval.record("the-thread")
        val retrieval = FakeRetrieval(
            conversations = listOf(thread),
            messages = listOf(
                FakeRetrieval.message("asked", "can you send the figures?", minutesBefore(180)),
                FakeRetrieval.message("own-file", null, minutesBefore(120)),
                FakeRetrieval.message("thanks", "got them, thanks", minutesBefore(60)),
            ),
            turns = mapOf(
                FakeRetrieval.record("asked") to FakeRetrieval.turn("The Counterpart", thread),
                FakeRetrieval.record("own-file") to
                    FakeRetrieval.turn("The Owner", thread, isSelf = true),
                FakeRetrieval.record("thanks") to FakeRetrieval.turn("The Counterpart", thread),
            ),
        )

        assertEquals(
            setOf("thanks"),
            marked(retrieve(retrieval, FakeIndex())),
            "the request the sender answered with a file is marked as still open, and no turn of " +
                "theirs is marked, so the exchange reads as unanswered and the sender as silent",
        )
    }

    @Test
    fun `a newest turn the read could not place marks nothing`(): Unit = runBlocking {
        // The window inner-joins the conversation, so this cannot arise from it — and marking such
        // a turn would assert a thread for the channel rule to compose for while carrying no
        // channel, which that rule reads as the positive *said one to one*. That is a private draft
        // composed for a thread nothing placed, which is the defect a prior card closed.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.CONVERSATION),
            messages = listOf(
                FakeRetrieval.message("older", "the first question", minutesBefore(120)),
                FakeRetrieval.message("newest", "and the second", minutesBefore(60)),
            ),
        )

        assertEquals(emptySet(), marked(retrieve(retrieval, FakeIndex())))
    }

    @Test
    fun `a blank turn arriving last does not stand as the whole exchange`(): Unit = runBlocking {
        // A photograph or a service notice is a record and not something a reply can answer, so the
        // rendering drops it — and the selection has to drop it for the same reason, or the newest
        // thing said is a block that never reaches the model and the mark lands nowhere.
        val thread = FakeRetrieval.record("the-thread")
        val retrieval = FakeRetrieval(
            conversations = listOf(thread),
            messages = listOf(
                FakeRetrieval.message("asked", "can you send the figures?", minutesBefore(120)),
                FakeRetrieval.message("photograph", null, minutesBefore(60)),
            ),
            turns = mapOf(
                FakeRetrieval.record("asked") to FakeRetrieval.turn("The Counterpart", thread),
                FakeRetrieval.record("photograph") to FakeRetrieval.turn("The Counterpart", thread),
            ),
        )

        assertEquals(setOf("asked"), marked(retrieve(retrieval, FakeIndex())))
    }

    @Test
    fun `the instruction reads the mark the material carries, in the words it carries it`(): Unit =
        runBlocking {
            // **Read off the rendering rather than restated here**, which is the convention the
            // sender's-mark and room pins follow and for the same reason: a second copy of the
            // wording in the instruction would describe one mark in two strings that agree only
            // until one of them is edited. What this catches is the paragraph being dropped or the
            // clause reworded without it.
            val marked = requireNotNull(
                Material.of(FakeRetrieval.message("1", "body"), "The Counterpart", latest = true),
            ).text
            val marker = marked.substringAfter("The Counterpart, ").removeSuffix(": body")

            val stages = shape(FakeRetrieval(), FakeIndex()).stages
            val carried = assertIs<Progress.Carried>(
                stages[1].apply(Exchange(Request(text = "draft a reply"))),
            )
            val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains(marker),
                "the material marks the turns of the latest exchange and the instruction never " +
                    "says what the mark means: $marker not in $system",
            )
            assertTrue(
                system.contains("That is where the reply is aimed"),
                "the mark is rendered and nothing says what to do with it, so the model may still " +
                    "read any of the window as the exchange it answers: $system",
            )
            assertTrue(
                system.contains("An older request is not what this reply answers where a marked turn has since"),
                "nothing says a request a later turn has overtaken is settled, which is the " +
                    "measured failure rather than a refinement of it: $system",
            )
            assertTrue(
                system.contains("the sender has not since answered"),
                "the marked set deliberately holds the sender's own answering turn, so without " +
                    "this the one request they just answered is the one the reply addresses: $system",
            )
            assertTrue(
                system.contains("What the mark does not do is put the rest of the material out of bounds"),
                "the mark reads as the only material the reply may answer, so a request further " +
                    "back that nothing met is excluded by the bound on it: $system",
            )
            assertTrue(
                system.contains("Where a marked turn carries the sender's own mark, the sender wrote last"),
                "the nothing-open rule's own precondition is left to be worked out from the " +
                    "marks, which is the arithmetic this card removed: $system",
            )
            assertTrue(
                system.contains("the marked turns are all in"),
                "the channel rule still asks which conversation the latest exchange was in while " +
                    "the material now says so: $system",
            )
        }

    @Test
    fun `a turn whose author the corpus never named carries no name`(): Unit = runBlocking {
        // Nothing rather than nobody, as for a finding's party: a placeholder would be read as the
        // author's name by the one thing that cannot check it.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "can you send it by Friday")),
        )

        assertEquals("can you send it by Friday", retrieve(retrieval, FakeIndex()).single().text)
    }

    @Test
    fun `a comparable reply is offered as register material and the thread's own turn is not`(): Unit =
        runBlocking {
            // **The other half of the measured failure, and it is not attribution.** The lookup ranks
            // the whole corpus for wording, so what it returns is other people's exchanges about other
            // things. Offered indistinguishably from the thread, they are read as facts about this
            // person — and naming an author alone would not help, because a correctly attributed
            // message from an unrelated exchange is still not evidence about them. The two labels say
            // different things and a register example carries both: the marker denies that it is
            // evidence, and the name says whose wording is being shown.
            val retrieval = FakeRetrieval(
                conversations = listOf(FakeRetrieval.message("1", "x").externalId),
                messages = listOf(FakeRetrieval.message("1", "can you send it by Friday")),
                turns = mapOf(
                    FakeRetrieval.record("1") to FakeRetrieval.turn("The Counterpart"),
                    FakeRetrieval.record("90") to FakeRetrieval.turn("Somebody Else Entirely"),
                ),
            )
            val index = FakeIndex(hits = listOf(span("90", "I will have it with you by then")))

            val retrieved = retrieve(retrieval, index).associateBy { it.id.record.nativeId }

            assertEquals(
                "Register example, not evidence about the subject, sent by Somebody Else Entirely: " +
                    "I will have it with you by then",
                retrieved.getValue("90").text,
            )
            assertEquals(
                "Sent by The Counterpart$LATEST_ALSO: can you send it by Friday",
                retrieved.getValue("1").text,
                "the thread's own turn is material about this person and must not be marked otherwise",
            )
        }

    @Test
    fun `a ranked hit from one of this subject's own threads is not register material`(): Unit =
        runBlocking {
            // **The lookup's question is this thread's own recent words, so its nearest neighbours are
            // frequently this same counterpart's older turns** — the ones the message read's window
            // left behind, which on the real archive is every thread longer than it reads. Marked as
            // register they are offered under an instruction that forbids stating them as fact or
            // citing them, which suppresses the subject's own history while it sits in the prompt. The
            // identifier overlap `distinct` folds covers the window itself and nothing beyond it, so
            // the two are told apart by where each turn was said.
            val thread = FakeRetrieval.record("the-thread")
            val retrieval = FakeRetrieval(
                conversations = listOf(thread),
                messages = listOf(FakeRetrieval.message("1", "can you send it by Friday")),
                turns = mapOf(
                    FakeRetrieval.record("1") to FakeRetrieval.turn("The Counterpart", thread),
                    // Older than the window this shape reads, and in the same thread.
                    FakeRetrieval.record("90") to FakeRetrieval.turn("The Counterpart", thread),
                    // Somebody else's exchange entirely, which is what the marker is for.
                    FakeRetrieval.record("91") to FakeRetrieval.turn("Somebody Else Entirely"),
                ),
            )
            val index = FakeIndex(
                hits = listOf(
                    span("90", "we agreed the rate back then"),
                    span("91", "an unrelated exchange"),
                ),
            )

            val retrieved = retrieve(retrieval, index).associateBy { it.id.record.nativeId }

            assertEquals(
                "Sent by The Counterpart: we agreed the rate back then",
                retrieved.getValue("90").text,
                "the subject's own older turn was marked as not being evidence about them",
            )
            assertEquals(
                "Register example, not evidence about the subject, sent by Somebody Else Entirely: " +
                    "an unrelated exchange",
                retrieved.getValue("91").text,
            )
        }

    @Test
    fun `the register lookup is asked with what was said, not with the labels put on it`(): Unit = runBlocking {
        // The question is the thread's own recent words, and an author label is this layer's
        // addition to them. Asked with the labels, the index would be ranking the corpus against a
        // list of names — which is a different search, and one that finds whoever is mentioned
        // most rather than the exchanges this one resembles.
        val embeddings = FakeEmbeddings()
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "can you send it by Friday")),
            turns = mapOf(FakeRetrieval.record("1") to FakeRetrieval.turn("The Counterpart")),
        )

        retrieve(retrieval, FakeIndex(), embeddings)

        val asked = embeddings.embedded.single().second
        assertTrue(asked.contains("can you send it by Friday"), "the thread's words were not asked with: $asked")
        assertTrue(!asked.contains("The Counterpart"), "the author labels were asked with: $asked")
    }

    @Test
    fun `the author read covers the ranked hits as well as the thread, in one ask`(): Unit = runBlocking {
        // **Both kinds of material in one read, and the ranked half is the reason it is keyed by the
        // record.** A hit is a record identifier and a body: the index stores no author, so a shape
        // that asked only about the messages it fetched would offer every comparable reply with
        // nobody attached. Asked per turn it would also be a round trip per line of a prompt this
        // shape fills with several threads' tails.
        val retrieval = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(
                FakeRetrieval.message("1", "one"),
                FakeRetrieval.message("2", "two"),
            ),
        )
        val index = FakeIndex(hits = listOf(span("90", "a comparable reply")))

        retrieve(retrieval, index)

        val asked = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Turns>().single()
        assertEquals(
            listOf("1", "2", "90").map(FakeRetrieval::record),
            asked.messages,
            "a turn the read did not ask about is one the prompt offers unattributed",
        )
    }

    @Test
    fun `the sender's mark survives the ranked route, not only the windowed one`(): Unit =
        runBlocking {
            // The ranked read reaches the sender's *older* turns — the ones the window left behind,
            // which is most of what there is to learn a register from. Marked on one route and not
            // the other, a draft learns the sender's voice from whichever half the window happened
            // to cover.
            //
            // The hit is in a thread this request read, which is what makes it the subject's
            // material rather than a register example: the register branch is deliberately left
            // unmarked, so a fixture whose hit fell outside the threads would assert nothing about
            // this.
            val thread = FakeRetrieval.message("1", "x").externalId
            val retrieval = FakeRetrieval(
                conversations = listOf(thread),
                messages = listOf(FakeRetrieval.message("1", "any word on those figures?")),
                turns = mapOf(
                    FakeRetrieval.record("1") to FakeRetrieval.turn("The Counterpart", thread),
                    FakeRetrieval.record("90") to
                        FakeRetrieval.turn("The Owner", thread, isSelf = true),
                ),
            )
            val index = FakeIndex(hits = listOf(span("90", "I will send the figures on Friday")))

            val retrieved = retrieve(retrieval, index).associateBy { it.id.record.nativeId }

            assertEquals(
                "Sent by The Owner (the sender you are drafting for): I will send the figures on Friday",
                retrieved.getValue("90").text,
                "a turn reached by ranking loses the sender's mark, so the register the lookup " +
                    "exists to supply is not the sender's as far as the model can tell",
            )
        }

    @Test
    fun `the instruction names the mark the material carries, in the words it carries it`(): Unit =
        runBlocking {
            // **Read off the rendering rather than restated here**, because a second copy of the
            // marker in the test would let the instruction and the material describe one mark in two
            // strings that agree only until one is edited. What this catches is the paragraph being
            // dropped or the marker reworded without it.
            // The one-to-one channel is named rather than left out, so what this reads off the
            // rendering is the sender's marker alone: the shape composing a message always carries
            // a channel, and withholding it here would fold the room clause into the marker.
            val marked = requireNotNull(
                Material.of(
                    FakeRetrieval.message("1", "body"),
                    "The Owner",
                    own = Material.OwnTurn.SENDER,
                    channel = Channel.OneToOne,
                ),
            ).text
            val marker = marked.removePrefix("Sent by The Owner").removeSuffix(": body")
                .trim(' ', '(', ')')

            val stages = shape(FakeRetrieval(), FakeIndex()).stages
            val carried = assertIs<Progress.Carried>(
                stages[1].apply(Exchange(Request(text = "draft a reply"))),
            )
            val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

            assertTrue(
                system.contains(marker),
                "the material marks a turn as the sender's and the instruction never says so: $system",
            )
            assertTrue(
                system.contains("composing as the sender, to the person the request names"),
                "nothing tells the model which side of the thread it writes from, which is how a " +
                    "draft comes back in the counterpart's voice: $system",
            )
        }

    @Test
    fun `the instruction says what an attributed turn and a register example each are`(): Unit = runBlocking {
        // Prose pins, which are the weak kind of assertion, knowingly: both markers are only worth
        // rendering while the model is told what they mean, and this is the shape where neither
        // existed. What they catch is the clause being dropped, not a sentence elsewhere overriding
        // it.
        val stages = shape(FakeRetrieval(), FakeIndex()).stages
        val carried = assertIs<Progress.Carried>(
            stages[1].apply(Exchange(Request(text = "draft a reply"))),
        )
        val system = requireNotNull(carried.exchange.assembled).system.replace(WHITESPACE, " ")

        assertTrue(
            system.contains("attribute a message to the person marked on it and to nobody else"),
            "nothing tells the model whose words a turn carries: $system",
        )
        assertTrue(
            system.contains("Material marked as a register example"),
            "the register marker reaches the material and nothing explains it: $system",
        )
    }

    @Test
    fun `a contact with no history looks for no comparable replies either`(): Unit = runBlocking {
        // There is no question to ask the index with: the lookup is asked in the thread's own words,
        // and a thread with no words is a request that short-circuits before any model call rather
        // than one that searches the corpus for a register to imitate.
        val index = FakeIndex(hits = listOf(span("90", "anything")))

        val retrieved = retrieve(FakeRetrieval(), index)

        assertEquals(0, index.searches)
        assertTrue(retrieved.isEmpty())
    }

    @Test
    fun `it follows more threads than a status report, because register crosses them`(): Unit = runBlocking {
        val retrieval = FakeRetrieval()

        retrieve(retrieval, FakeIndex())

        val threads = retrieval.asked.filterIsInstance<FakeRetrieval.Ask.Conversations>().single().threads
        assertTrue(threads > 1)
    }

    @Test
    fun `nothing is retrieved for a request with no subject`(): Unit = runBlocking {
        // The runner resolves a subject for every shape that accepts one, so a missing subject here
        // is the machinery having skipped resolution rather than something to improvise around.
        val progress = shape().stages.first().apply(Exchange(Request(text = "draft a reply")))

        assertTrue(assertIs<Progress.Carried>(progress).exchange.retrieved.isEmpty())
    }

    // --- doubles and fixtures -------------------------------------------------------------

    private companion object {

        fun shape(
            retrieval: FakeRetrieval = FakeRetrieval(),
            index: FakeIndex = FakeIndex(),
            embeddings: FakeEmbeddings = FakeEmbeddings(),
        ) = DraftShape(
            model = FakeModelProvider(),
            retrieval = retrieval,
            lookup = Lookup(embeddings, index),
            grouping = FakeGrouping(),
            zone = ZoneOffset.UTC,
        )

        /** Long enough that a message offset by it is plainly from another era of the archive. */
        const val SPAN = 365L * 24 * 60 * 60

        /**
         * A thread with other people in it, named the way the corpus names one.
         *
         * Named rather than anonymous because the name is what a reply needs in order to be
         * sendable, and because an unnamed room is a case of its own with its own rendering — a
         * fixture that could not tell them apart would pass with the two collapsed.
         */
        val ROOM = Channel.Room("The Project Room")

        /**
         * The date, weekday and minute a block is headed with, in the zone this fixture leaves
         * unset.
         *
         * Stops short of the offset deliberately: what the case above claims is that a block says
         * when it was said, and pinning the suffix too would make it fail on a fixture that named
         * a zone, which is a different question entirely.
         *
         * [Locale.ROOT] for the reason `Assemble.WRITTEN` is pinned to it — a case reading the
         * weekday in the host's locale would pass or fail on where it ran.
         */
        val WHEN_WRITTEN: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd EEE HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC)

        /**
         * Runs of whitespace as one space, so a re-wrapped paragraph still reads as its sentence.
         *
         * An assertion matching the raw prompt would really be asserting where the paragraph was
         * wrapped, which turns a rewrap into a red and invites reflowing the prompt to suit a test.
         */
        val WHITESPACE = Regex("\\s+")

        /**
         * The clause a turn of the latest exchange carries, in the two forms the rendering has.
         *
         * Restated here as the block pins already restate the name and the room clause: what these
         * cases assert is a whole block's text, so the literal is what they are about. The pin that
         * must not restate it is the instruction's — the model is told what the mark means, and a
         * second copy of the wording there would let the two drift.
         */
        const val LATEST = "In the latest exchange in this person's threads"
        const val LATEST_ALSO = ", in the latest exchange in this person's threads"

        /** What marks a turn as the sender's own, which the nothing-open signal is read off. */
        const val IS_THE_SENDER = "(the sender you are drafting for)"

        /** Turns in one run, more than the mark may cover, so the bound is what the case reads. */
        const val RUN = 40

        /** Far enough back that a turn carrying it is plainly not the live exchange. */
        val DAYS_BEFORE: Instant = FakeRetrieval.WHEN.minusSeconds(2 * 24 * 60 * 60)

        /** Plainly another era of the archive, for material offered after everything newer. */
        val LONG_BEFORE: Instant = FakeRetrieval.WHEN.minusSeconds(SPAN)

        /** An instant [minutes] before the newest the fixtures reach, which stands for the request. */
        fun minutesBefore(minutes: Int): Instant = FakeRetrieval.WHEN.minusSeconds(minutes * 60L)

        /**
         * Which of [retrieved] carry the latest-exchange mark, by the record each is addressed by.
         *
         * A set rather than a list, because what every case here asserts is which turns were marked
         * and not what order the material was offered in — that is a separate case's claim.
         */
        fun marked(retrieved: List<Span>): Set<String> = retrieved
            .filter { it.text.contains(LATEST) || it.text.contains(LATEST_ALSO) }
            .mapTo(mutableSetOf()) { it.id.record.nativeId }

        /** A contact with something in its history, which is what the register lookup needs. */
        fun withHistory() = FakeRetrieval(
            conversations = listOf(FakeRetrieval.message("1", "x").externalId),
            messages = listOf(FakeRetrieval.message("1", "can you send it by Friday")),
        )

        fun span(nativeId: String, text: String, at: Instant = FakeRetrieval.WHEN) = Span(
            id = FakeRetrieval.evidence(nativeId).span,
            text = text,
            sourceTime = at,
        )

        suspend fun retrieve(
            retrieval: FakeRetrieval,
            index: FakeIndex,
            embeddings: FakeEmbeddings = FakeEmbeddings(),
        ) = assertIs<Progress.Carried>(
            shape(retrieval, index, embeddings).stages.first().apply(
                Exchange(Request(text = "draft a reply"), subject = FakeRetrieval.aContact()),
            ),
        ).exchange.retrieved

        /**
         * The prompt as the model receives it, material and all.
         *
         * Both stages rather than [retrieve]'s list, because the order the blocks are written in and
         * the instant on each of them are decided by assembly.
         */
        suspend fun assemble(retrieval: FakeRetrieval, index: FakeIndex): String {
            val stages = shape(retrieval, index).stages
            val retrieved = assertIs<Progress.Carried>(
                stages.first().apply(
                    Exchange(Request(text = "draft a reply"), subject = FakeRetrieval.aContact()),
                ),
            ).exchange
            val assembled = assertIs<Progress.Carried>(stages[1].apply(retrieved)).exchange.assembled
            return requireNotNull(assembled).prompt
        }
    }
}
