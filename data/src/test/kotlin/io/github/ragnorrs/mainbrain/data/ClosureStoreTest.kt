package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.Closure
import io.github.ragnorrs.mainbrain.domain.entity.ClosureCandidate
import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.FindingKind
import io.github.ragnorrs.mainbrain.domain.entity.FindingPair
import io.github.ragnorrs.mainbrain.domain.entity.Weighed
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.recognition.Closer
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Closure selection against a real Postgres: who is owed an examination, and what stops being owed.
 *
 * **These are the claims only SQL can answer**, and all of them are about the verdict row standing
 * in for a derivation mark. That a commitment nothing has examined arrives with the stretch of its
 * own conversation that followed it. That a verdict closing nothing settles it only as far as it
 * read, so later material makes it owed again and the next window starts where the last one
 * stopped. That a verdict closing something settles it for good, whatever arrives afterwards. That
 * a moved version retires both kinds. That the horizon bounds the re-reading rather than
 * eligibility, so an old promise is still examined once. And that the floor on new material decides
 * whether a commitment comes back without deciding where its window resumes — in the selection and
 * in the count alike, since the driver reads the one against the other.
 *
 * **And what a collapse merge does to a verdict**, which is the same question asked across two
 * tables: that a closure the superseded reading found reaches the survivor and settles it, that a
 * verdict which found nothing is not carried, which of two verdicts survives, and that the carried
 * one keeps the watermark the survivor's own examination had reached.
 *
 * **The rig is shared and nothing is emptied between tests**, so every case works in a conversation
 * of its own and every assertion is made about that conversation's candidates. The count is read as
 * a difference for the same reason: other tests leave commitments owed, and an absolute figure here
 * would be a tally of the whole module.
 */
class ClosureStoreTest {

    private val conversations = JdbcConversationRepository(StoreRig.database)
    private val messages = JdbcMessageRepository(StoreRig.database)
    private val commitments = JdbcCommitmentRepository(StoreRig.database)
    private val collapse = JdbcCollapseRepository(StoreRig.database)
    private val closures = JdbcClosureRepository(StoreRig.database)

    /**
     * The answer path's read, used here to see what was written.
     *
     * The only reader of this table in the build, so a store test asserting through anything else
     * would be asserting against a path nothing takes.
     */
    private val retrieval = JdbcRetrieval(StoreRig.database)

    @Test
    fun `a commitment nothing has examined is owed, with the material that followed it`(): Unit = runBlocking {
        val where = aConversation("unexamined")
        val stating = aMessage(where, "I will send the quote", STATED)
        val after = aMessage(where, "thanks", STATED.plusSeconds(AN_HOUR))
        // Before the promise, so it is context the examination has no business reading: a closure
        // is something that happened afterwards.
        aMessage(where, "could you send the quote", STATED.minusSeconds(AN_HOUR))
        commitments.upsert(listOf(commitmentOf("The quote is sent.", where, stating)))

        val candidate = assertNotNull(owedIn(where).singleOrNull(), "exactly one candidate in its own thread")

        assertEquals("The quote is sent.", candidate.commitment.claim)
        assertEquals(
            OWED,
            candidate.commitment.obligedParty,
            "the obliged party did not survive the owed selection, so a candidate hands back a " +
                "commitment that understates what is recorded about it",
        )
        assertEquals(
            listOf(after.externalId),
            candidate.later.map { it.externalId },
            "the window is what followed the promise, in order and nothing before it",
        )
    }

    @Test
    fun `a verdict that closed nothing settles it, as far as it read`(): Unit = runBlocking {
        val where = aConversation("unclosed")
        val stating = aMessage(where, "I will send the quote", STATED)
        val weighed = aMessage(where, "noted", STATED.plusSeconds(AN_HOUR))
        val commitment = commitmentOf("The quote is sent.", where, stating)
        commitments.upsert(listOf(commitment))

        closures.record(listOf(unclosed(commitment, through = Weighed(weighed.at, weighed.externalId))))

        assertTrue(
            owedIn(where).isEmpty(),
            "a commitment examined to the end of its material is owed nothing more",
        )
        assertTrue(
            retrieval.closuresOf(listOf(commitment.id)).isEmpty(),
            "a verdict that closed nothing must not read as a closure",
        )
    }

    @Test
    fun `material the verdict never weighed makes it owed again, from the watermark`(): Unit = runBlocking {
        // The reason the watermark is a message instant: a verdict of nothing-closed is good only as
        // far as the pass had read, and what arrives after it has never been looked at.
        val where = aConversation("more material")
        val stating = aMessage(where, "I will send the quote", STATED)
        val weighed = aMessage(where, "noted", STATED.plusSeconds(AN_HOUR))
        val commitment = commitmentOf("The quote is sent.", where, stating)
        commitments.upsert(listOf(commitment))
        closures.record(listOf(unclosed(commitment, through = Weighed(weighed.at, weighed.externalId))))

        val arrived = aMessage(where, "sent it over", STATED.plusSeconds(2 * AN_HOUR))

        val candidate = assertNotNull(owedIn(where).singleOrNull(), "new material makes it owed again")
        assertEquals(
            listOf(arrived.externalId),
            candidate.later.map { it.externalId },
            "the next window starts at the watermark, so what was already weighed is not paid for twice",
        )
    }

    @Test
    fun `material below the floor leaves the commitment where its verdict left it`(): Unit = runBlocking {
        // The defect this floor exists for: an examination is billed for its instruction and the
        // promise whether it weighs one new message or twenty, so a conversation arriving a message
        // at a time bought a call per message and paid mostly for the fixed part of it.
        val where = aConversation("below the floor")
        val stating = aMessage(where, "I will send the quote", STATED)
        val weighed = aMessage(where, "noted", STATED.plusSeconds(AN_HOUR))
        val commitment = commitmentOf("The quote is sent.", where, stating)
        commitments.upsert(listOf(commitment))
        closures.record(listOf(unclosed(commitment, through = Weighed(weighed.at, weighed.externalId))))

        repeat(FLOOR - 1) { arrival ->
            aMessage(where, "a word about it", STATED.plusSeconds(2 * AN_HOUR + arrival))
        }

        assertTrue(
            owedIn(where, rearm = FLOOR, now = OPEN_HORIZON).isEmpty(),
            "${FLOOR - 1} new messages is under a floor of $FLOOR, so nothing is owed while more " +
                "material may still arrive",
        )
        // And the same material with no floor is owed, so the case above is the floor holding and
        // not the material failing to arrive. Without this the test would pass with the clause
        // deleted — one message past the watermark has to be owed at a floor of one.
        assertEquals(
            1,
            owedIn(where, rearm = 1, now = OPEN_HORIZON).size,
            "the same material with no floor has to be owed, or this asserts nothing",
        )
        // And the floor is a deferral: once the horizon has closed nothing more can arrive, so the
        // same under-the-floor remainder is read rather than abandoned.
        assertEquals(
            1,
            owedIn(where, rearm = FLOOR, now = NOW).size,
            "past the horizon the floor has lapsed, so what is left is owed however little it is",
        )
    }

    @Test
    fun `material at the floor re-arms it, and the window still resumes from the watermark`(): Unit = runBlocking {
        val where = aConversation("at the floor")
        val stating = aMessage(where, "I will send the quote", STATED)
        val weighed = aMessage(where, "noted", STATED.plusSeconds(AN_HOUR))
        val commitment = commitmentOf("The quote is sent.", where, stating)
        commitments.upsert(listOf(commitment))
        closures.record(listOf(unclosed(commitment, through = Weighed(weighed.at, weighed.externalId))))

        val arrived = List(FLOOR) { arrival ->
            aMessage(where, "a word about it", STATED.plusSeconds(2 * AN_HOUR + arrival))
        }

        val candidate = assertNotNull(
            owedIn(where, rearm = FLOOR, now = OPEN_HORIZON).singleOrNull(),
            "$FLOOR new messages reaches a floor of $FLOOR",
        )
        assertEquals(
            arrived.map { it.externalId },
            candidate.later.map { it.externalId },
            "the floor decides whether to re-examine and never where to resume: what was already " +
                "weighed must not be paid for twice",
        )
    }

    @Test
    fun `the floor does not apply to a commitment nothing has examined`(): Unit = runBlocking {
        // Otherwise a quiet conversation would have no verdict at all, which is the one outcome the
        // floor may not produce: the first reading is what gives a commitment a state.
        val where = aConversation("first reading")
        val stating = aMessage(where, "I will send the quote", STATED)
        val only = aMessage(where, "noted", STATED.plusSeconds(AN_HOUR))
        commitments.upsert(listOf(commitmentOf("The quote is sent.", where, stating)))

        val candidate = assertNotNull(
            owedIn(where, rearm = FLOOR, now = OPEN_HORIZON).singleOrNull(),
            "one message after the promise is under the floor, and a first examination ignores it",
        )
        assertEquals(listOf(only.externalId), candidate.later.map { it.externalId })
    }

    @Test
    fun `the count respects the floor, so the driver cannot read a stage that never drains`(): Unit = runBlocking {
        // The count and the selection share one definition, and the floor is in it. A count that
        // ignored the floor would report commitments the selection then declined to return, and the
        // driver reads a lag that does not fall as a stall.
        val where = aConversation("counted against the floor")
        val stating = aMessage(where, "I will send the quote", STATED)
        val weighed = aMessage(where, "noted", STATED.plusSeconds(AN_HOUR))
        val commitment = commitmentOf("The quote is sent.", where, stating)
        commitments.upsert(listOf(commitment))
        closures.record(listOf(unclosed(commitment, through = Weighed(weighed.at, weighed.externalId))))

        val settled = closures.owedCount(VERSION, EXAMINE, FLOOR, OPEN_HORIZON)
        repeat(FLOOR - 1) { arrival ->
            aMessage(where, "a word about it", STATED.plusSeconds(2 * AN_HOUR + arrival))
        }
        val under = closures.owedCount(VERSION, EXAMINE, FLOOR, OPEN_HORIZON)
        aMessage(where, "and one more", STATED.plusSeconds(3 * AN_HOUR))
        val reached = closures.owedCount(VERSION, EXAMINE, FLOOR, OPEN_HORIZON)

        assertEquals(settled, under, "material under the floor adds nothing to the count")
        assertEquals(settled + 1, reached, "reaching the floor is one more owed")
    }

    @Test
    fun `a window the limit cut short is finished, however little is left of it`(): Unit = runBlocking {
        // **The floor may defer a re-examination and may never drop material.** A window is bounded
        // by a count, so a conversation with more material than fits is read in instalments — and
        // the last instalment is whatever is left over, which is under the floor one time in every
        // `window`. Gated on the floor, that remainder is weighed by no sweep ever, which is the
        // outcome the watermark was made a pair to prevent: the closing message may be in it.
        val where = aConversation("cut short")
        val stating = aMessage(where, "I will send the quote", STATED)
        val material = List(WINDOW + FLOOR - 1) { arrival ->
            aMessage(where, "a word about it", STATED.plusSeconds(AN_HOUR + arrival))
        }
        val commitment = commitmentOf("The quote is sent.", where, stating)
        commitments.upsert(listOf(commitment))

        // The first examination is unconditional and takes a full window, leaving a remainder of
        // FLOOR - 1 — one short of what the floor would re-arm on.
        val first = assertNotNull(owedIn(where, rearm = FLOOR).singleOrNull())
        assertEquals(WINDOW, first.later.size, "the first instalment is a full window")
        closures.record(listOf(unclosed(commitment, through = first.weighed)))

        // Inside the horizon the remainder waits, which is the floor doing its job.
        assertTrue(
            owedIn(where, rearm = FLOOR, now = OPEN_HORIZON).isEmpty(),
            "a remainder under the floor waits while more material may still arrive",
        )

        val second = assertNotNull(
            owedIn(where, rearm = FLOOR, now = NOW).singleOrNull(),
            "the ${FLOOR - 1} message(s) the limit cut off are still owed once the horizon has " +
                "closed: a floor defers a re-examination and must never drop material the pass " +
                "has already been handed",
        )
        assertEquals(
            material.drop(WINDOW).map { it.externalId },
            second.later.map { it.externalId },
            "and the remainder is exactly what the first instalment did not reach",
        )
    }

    @Test
    fun `the shipped floor holds and lapses, at the figure a deployment gets by default`(): Unit = runBlocking {
        // **The one case pinned to `Closer.DEFAULT_REARM`.** Every other floor case uses a figure of
        // its own so that moving the default cannot redden them — which leaves the shipped value
        // itself asserted by nothing, and it is the value every deployment runs. Both halves, since
        // a default that deferred for ever would be the defect rather than the bound.
        val floor = Closer.DEFAULT_REARM
        val where = aConversation("shipped floor")
        val stating = aMessage(where, "I will send the quote", STATED)
        val weighed = aMessage(where, "noted", STATED.plusSeconds(AN_HOUR))
        val commitment = commitmentOf("The quote is sent.", where, stating)
        commitments.upsert(listOf(commitment))
        closures.record(listOf(unclosed(commitment, through = Weighed(weighed.at, weighed.externalId))))

        repeat(floor - 1) { arrival ->
            aMessage(where, "a word about it", STATED.plusSeconds(2 * AN_HOUR + arrival))
        }

        assertTrue(
            owedIn(where, rearm = floor, now = OPEN_HORIZON).isEmpty(),
            "${floor - 1} messages is under the shipped floor of $floor",
        )
        aMessage(where, "and the one that reaches it", STATED.plusSeconds(3 * AN_HOUR))
        assertEquals(
            1,
            owedIn(where, rearm = floor, now = OPEN_HORIZON).size,
            "and $floor reaches it",
        )
    }

    @Test
    fun `a commitment found closed stays closed whatever arrives afterwards`(): Unit = runBlocking {
        // The one asymmetry in the selection, and it is deliberate: a promise is discharged once,
        // and later material cannot undo it. Without it, every closed commitment would be re-examined
        // for as long as its conversation kept growing.
        val where = aConversation("closed")
        val stating = aMessage(where, "I will send the quote", STATED)
        val closing = aMessage(where, "sent", STATED.plusSeconds(AN_HOUR))
        val commitment = commitmentOf("The quote is sent.", where, stating)
        commitments.upsert(listOf(commitment))
        closures.record(
            listOf(
                Closure(
                    commitment = commitment.id,
                    closedBy = Evidence(span = spanOf(closing.externalId), sourceTime = closing.at),
                    weighed = Weighed(closing.at, closing.externalId),
                    decidedBy = VERSION,
                ),
            ),
        )

        aMessage(where, "and here is the next one", STATED.plusSeconds(2 * AN_HOUR))

        assertTrue(owedIn(where).isEmpty(), "a closure already found is never paid for again")
        val read = assertNotNull(retrieval.closuresOf(listOf(commitment.id)).singleOrNull())
        assertEquals(
            closing.externalId,
            read.by.externalId,
            "the closing message is what the verdict cites, so it can be looked up like any evidence",
        )
    }

    @Test
    fun `a verdict from another version is retired, closed or not`(): Unit = runBlocking {
        // What a better prompt buys, and the job the derivation table does for the passes that read
        // records. Both kinds, because retiring only the negative ones would leave every closure
        // this build ever found standing against an instruction that no longer agrees with it.
        val where = aConversation("moved version")
        val openStating = aMessage(where, "I will send the quote", STATED)
        val closedStating = aMessage(where, "I will call them", STATED.plusSeconds(60))
        val closing = aMessage(where, "called", STATED.plusSeconds(AN_HOUR))
        val open = commitmentOf("The quote is sent.", where, openStating)
        val closed = commitmentOf("They are called.", where, closedStating)
        commitments.upsert(listOf(open, closed))
        closures.record(
            listOf(
                unclosed(open, through = Weighed(closing.at, closing.externalId)).copy(decidedBy = OLD_VERSION),
                Closure(
                    commitment = closed.id,
                    closedBy = Evidence(span = spanOf(closing.externalId), sourceTime = closing.at),
                    weighed = Weighed(closing.at, closing.externalId),
                    decidedBy = OLD_VERSION,
                ),
            ),
        )

        val again = owedIn(where)

        assertEquals(
            setOf(open.id, closed.id),
            again.map { it.commitment.id }.toSet(),
            "a verdict another version reached is retired whichever way it went",
        )
        // **And it is re-read from the promise, not from the mark being retired.** This is the half
        // the first version of this case could not see, because its windows were empty either way.
        // Resuming at the old mark hands the new instruction a window with the closure cut out of
        // it — the mark stands at or after the closing message — so the only answer available is
        // that nothing closed the promise, and that answer would then replace a closure this build
        // had found and paid for.
        val reopened = assertNotNull(again.singleOrNull { it.commitment.id == closed.id })
        assertTrue(
            reopened.later.map { it.externalId }.contains(closing.externalId),
            "the retired closure's own message is not in the new window: ${reopened.later.map { it.externalId }}",
        )
        assertEquals(
            Weighed(closing.at, closing.externalId),
            assertNotNull(reopened.previously),
            "the mark being replaced is not carried, so the new verdict could claim to have read less",
        )
    }

    @Test
    fun `two messages sharing a second are both weighed, across sweeps`(): Unit = runBlocking {
        // **The window is ordered by instant and then by the record, so the mark has to be both.**
        // A source's instants are coarse enough that several messages of one conversation carry the
        // same second. Resuming on the instant alone steps over whatever else shared the last one,
        // and because the owed clause compares the same way, no later sweep comes back for them —
        // they are weighed by nothing, ever, the closing message among them.
        val where = aConversation("one second")
        val stating = aMessage(where, "I will send the quote", STATED)
        val together = STATED.plusSeconds(AN_HOUR)
        val first = aMessage(where, "one moment", together)
        val second = aMessage(where, "sent it over", together)
        val commitment = commitmentOf("The quote is sent.", where, stating)
        commitments.upsert(listOf(commitment))

        // One message at a time, so the limit falls inside the shared second.
        val oneAtATime = closures.owed(VERSION, LIMIT, 1, EXAMINE, REARM, NOW)
        val firstWindow = assertNotNull(oneAtATime.singleOrNull { it.commitment.conversation == where })
        closures.record(listOf(unclosed(commitment, through = firstWindow.weighed)))
        val nextWindow = owedIn(where).singleOrNull()

        assertEquals(
            listOf(first.externalId, second.externalId).sortedBy { it.nativeId },
            (firstWindow.later + (nextWindow?.later ?: emptyList())).map { it.externalId }.sortedBy { it.nativeId },
            "a message sharing the last weighed second is weighed by no sweep at all",
        )
    }

    @Test
    fun `the horizon bounds the re-reading and not which commitments are eligible`(): Unit = runBlocking {
        // The distinction the card turns on: the archive is backfilled, so most of the corpus is
        // older than any sane horizon. A horizon applied to eligibility would mean an old promise
        // was never examined at all; applied to the material, it is examined once and settled.
        val where = aConversation("old promise")
        val stating = aMessage(where, "I will send the quote", LONG_AGO)
        val soon = aMessage(where, "noted", LONG_AGO.plusSeconds(AN_HOUR))
        val late = aMessage(where, "that quote from a year ago", LONG_AGO.plus(Duration.ofDays(400)))
        commitments.upsert(listOf(commitmentOf("The quote is sent.", where, stating)))

        val candidate = assertNotNull(owedIn(where).singleOrNull(), "an old promise is still examined once")

        assertEquals(
            listOf(soon.externalId),
            candidate.later.map { it.externalId },
            "material past the horizon is not weighed, so $late is left out",
        )
    }

    @Test
    fun `a commitment the collapse merged away is not examined`(): Unit = runBlocking {
        // It is no longer a finding of its own: paying to decide whether something closed it would
        // buy a verdict about a row nothing reads.
        val where = aConversation("superseded")
        val earlier = aMessage(where, "I will send the quote", STATED)
        val later = aMessage(where, "I will get the quote over to you", STATED.plusSeconds(60))
        aMessage(where, "thanks", STATED.plusSeconds(AN_HOUR))
        val survivor = commitmentOf("The quote is sent.", where, earlier)
        val merged = commitmentOf("Someone sends the quote.", where, later)
        commitments.upsert(listOf(survivor, merged))

        collapse.merge(FindingPair.of(FindingKind.COMMITMENT, survivor, merged))

        assertEquals(
            listOf(survivor.id),
            owedIn(where).map { it.commitment.id },
            "only the survivor is owed an examination",
        )
    }

    @Test
    fun `a merge carries a verdict onto a survivor that had none`(): Unit = runBlocking {
        // The property the examination horizon is costed on: a closure already found is not paid
        // for again, and a merge is where it would otherwise be lost. The verdict sits on a row
        // every read filters out, so without the carry the survivor arrives unexamined.
        val where = aConversation("carried")
        val promise = aMessage(where, "I will send the quote", STATED)
        val restated = aMessage(where, "I will get the quote over to you", STATED.plusSeconds(60))
        val closing = aMessage(where, "sent", STATED.plusSeconds(AN_HOUR))
        val survivor = commitmentOf("The quote is sent.", where, promise)
        val merged = commitmentOf("Someone sends the quote.", where, restated)
        commitments.upsert(listOf(survivor, merged))
        closures.record(listOf(closed(merged, by = closing)))
        assertTrue(
            retrieval.closuresOf(listOf(survivor.id)).isEmpty(),
            "the survivor holds nothing before the merge, or this case would pass for the wrong reason",
        )

        collapse.merge(FindingPair.of(FindingKind.COMMITMENT, survivor, merged))

        val read = assertNotNull(
            retrieval.closuresOf(listOf(survivor.id)).singleOrNull(),
            "the surviving reading reports the closure its superseded reading found",
        )
        assertEquals(closing.externalId, read.by.externalId)
    }

    @Test
    fun `a survivor whose carried verdict found a closure is not examined again`(): Unit = runBlocking {
        // The other half of the same claim, and the one that costs money: the verdict has to be
        // visible to the selection and not merely to the read.
        val where = aConversation("carried settled")
        val promise = aMessage(where, "I will send the quote", STATED)
        val restated = aMessage(where, "I will get the quote over to you", STATED.plusSeconds(60))
        val closing = aMessage(where, "sent", STATED.plusSeconds(AN_HOUR))
        val survivor = commitmentOf("The quote is sent.", where, promise)
        val merged = commitmentOf("Someone sends the quote.", where, restated)
        commitments.upsert(listOf(survivor, merged))
        closures.record(listOf(closed(merged, by = closing)))

        collapse.merge(FindingPair.of(FindingKind.COMMITMENT, survivor, merged))
        // Arriving after the merge, which is what makes a commitment owed again where its verdict
        // closed nothing. A closure already found is not reopened by it.
        aMessage(where, "thanks", STATED.plusSeconds(2 * AN_HOUR))

        assertTrue(
            owedIn(where).isEmpty(),
            "the sweep that follows a merge spends nothing on re-examining what was already closed",
        )
    }

    @Test
    fun `a closure found outranks a verdict that found none`(): Unit = runBlocking {
        // Where both rows hold a verdict, the material behind the two readings overlaps — so one
        // examination catching a closing message the other missed is new evidence rather than a
        // contradiction, whichever row holds it.
        val where = aConversation("ranked")
        val promise = aMessage(where, "I will send the quote", STATED)
        val restated = aMessage(where, "I will get the quote over to you", STATED.plusSeconds(60))
        val noted = aMessage(where, "noted", STATED.plusSeconds(AN_HOUR))
        val closing = aMessage(where, "sent", STATED.plusSeconds(2 * AN_HOUR))
        val survivor = commitmentOf("The quote is sent.", where, promise)
        val merged = commitmentOf("Someone sends the quote.", where, restated)
        commitments.upsert(listOf(survivor, merged))
        closures.record(
            listOf(
                unclosed(survivor, through = Weighed(noted.at, noted.externalId)),
                closed(merged, by = closing),
            ),
        )

        collapse.merge(FindingPair.of(FindingKind.COMMITMENT, survivor, merged))

        val read = assertNotNull(retrieval.closuresOf(listOf(survivor.id)).singleOrNull())
        assertEquals(closing.externalId, read.by.externalId, "the found closure replaces the one that found none")
    }

    @Test
    fun `a closure the survivor found is not undone by a verdict that found none`(): Unit = runBlocking {
        // The same rule the other way round, and the direction a blind carry would get wrong: the
        // superseded reading's verdict is the later one written and says nothing closed the
        // promise, which must not overwrite a closure this build paid to find.
        val where = aConversation("ranked back")
        val promise = aMessage(where, "I will send the quote", STATED)
        val restated = aMessage(where, "I will get the quote over to you", STATED.plusSeconds(60))
        val closing = aMessage(where, "sent", STATED.plusSeconds(AN_HOUR))
        val noted = aMessage(where, "thanks", STATED.plusSeconds(2 * AN_HOUR))
        val survivor = commitmentOf("The quote is sent.", where, promise)
        val merged = commitmentOf("Someone sends the quote.", where, restated)
        commitments.upsert(listOf(survivor, merged))
        closures.record(
            listOf(
                closed(survivor, by = closing),
                unclosed(merged, through = Weighed(noted.at, noted.externalId)),
            ),
        )

        collapse.merge(FindingPair.of(FindingKind.COMMITMENT, survivor, merged))

        val read = assertNotNull(retrieval.closuresOf(listOf(survivor.id)).singleOrNull())
        assertEquals(closing.externalId, read.by.externalId, "the survivor keeps the closure it had")
        assertTrue(owedIn(where).isEmpty(), "and stays settled")
    }

    @Test
    fun `where both readings found a closure the survivor's own stands`(): Unit = runBlocking {
        // Its claim is the one every read returns, so its own evidence is what a reader can check
        // the closure against.
        val where = aConversation("both closed")
        val promise = aMessage(where, "I will send the quote", STATED)
        val restated = aMessage(where, "I will get the quote over to you", STATED.plusSeconds(60))
        val closing = aMessage(where, "sent", STATED.plusSeconds(AN_HOUR))
        val alsoClosing = aMessage(where, "the quote is with you", STATED.plusSeconds(2 * AN_HOUR))
        val survivor = commitmentOf("The quote is sent.", where, promise)
        val merged = commitmentOf("Someone sends the quote.", where, restated)
        commitments.upsert(listOf(survivor, merged))
        closures.record(listOf(closed(survivor, by = closing), closed(merged, by = alsoClosing)))

        collapse.merge(FindingPair.of(FindingKind.COMMITMENT, survivor, merged))

        val read = assertNotNull(retrieval.closuresOf(listOf(survivor.id)).singleOrNull())
        assertEquals(closing.externalId, read.by.externalId)
    }

    @Test
    fun `a verdict that found nothing is not carried at all`(): Unit = runBlocking {
        // **Because its mark would have to be the survivor's own promise**, and a row saying that is
        // a row saying what the absence of one says. The superseded reading was weighed forward from
        // its own promise, which is later than the survivor's, so its mark cannot travel: the
        // stretch between the two promises was read by neither.
        val where = aConversation("carried nothing")
        val promise = aMessage(where, "I will send the quote", STATED)
        val between = aMessage(where, "when do you think", STATED.plusSeconds(AN_HOUR))
        val restated = aMessage(where, "I will get the quote over to you", STATED.plusSeconds(2 * AN_HOUR))
        val noted = aMessage(where, "noted", STATED.plusSeconds(3 * AN_HOUR))
        val survivor = commitmentOf("The quote is sent.", where, promise)
        val merged = commitmentOf("Someone sends the quote.", where, restated)
        commitments.upsert(listOf(survivor, merged))
        closures.record(listOf(unclosed(merged, through = Weighed(noted.at, noted.externalId))))

        collapse.merge(FindingPair.of(FindingKind.COMMITMENT, survivor, merged))

        val candidate = assertNotNull(
            owedIn(where).singleOrNull(),
            "nothing closed the promise, so the survivor is still owed an examination",
        )
        assertNull(
            candidate.previously,
            "no verdict was carried, so there is no mark to claim: a carried one would read as " +
                "ground the survivor's own examination had covered",
        )
        assertEquals(
            listOf(between.externalId, restated.externalId, noted.externalId),
            candidate.later.map { it.externalId },
            "the window starts at the survivor's own promise, so material neither reading weighed " +
                "is read rather than settled unseen",
        )
    }

    @Test
    fun `a carried closure keeps the watermark the survivor had reached`(): Unit = runBlocking {
        // **The mark the survivor's own examination bought, which the carry must not hand back.**
        // `SELECT_OWED` projects it as what the next verdict may not claim less than, so resetting it
        // to the promise would buy the whole window again the first time a version move re-opens
        // this commitment — which is the case this reads it back through.
        val where = aConversation("carried keeps mark")
        val promise = aMessage(where, "I will send the quote", STATED)
        val restated = aMessage(where, "I will get the quote over to you", STATED.plusSeconds(60))
        val noted = aMessage(where, "noted", STATED.plusSeconds(AN_HOUR))
        val closing = aMessage(where, "sent", STATED.plusSeconds(2 * AN_HOUR))
        val survivor = commitmentOf("The quote is sent.", where, promise)
        val merged = commitmentOf("Someone sends the quote.", where, restated)
        commitments.upsert(listOf(survivor, merged))
        closures.record(
            listOf(
                // Both at the version a later move retires, so that the carried row is owed again
                // and the mark it kept is readable.
                unclosed(survivor, through = Weighed(noted.at, noted.externalId)).copy(decidedBy = OLD_VERSION),
                closed(merged, by = closing).copy(decidedBy = OLD_VERSION),
            ),
        )

        collapse.merge(FindingPair.of(FindingKind.COMMITMENT, survivor, merged))

        val candidate = assertNotNull(
            owedIn(where).singleOrNull(),
            "the carried verdict is the retired version's, so a move re-opens the survivor",
        )
        assertEquals(
            Weighed(noted.at, noted.externalId),
            assertNotNull(candidate.previously),
            "the survivor's own mark stands: the carry replaced its verdict, not the ground it read",
        )
    }

    @Test
    fun `a merge refused because the survivor was itself merged carries nothing`(): Unit = runBlocking {
        // The chain hazard, from the side that settles something. A pair is read, the survivor is
        // merged away by another pair before this write lands, and the merge is then refused — which
        // has to cost nothing, as it does for the spans. A verdict written anyway would settle a row
        // nothing reaches, where the superseded reading's own copy is still where the next pairing
        // can find it.
        val where = aConversation("refused")
        val first = aMessage(where, "I will send the quote", STATED)
        val second = aMessage(where, "I will get the quote over to you", STATED.plusSeconds(60))
        val third = aMessage(where, "the quote is going over today", STATED.plusSeconds(120))
        val closing = aMessage(where, "sent", STATED.plusSeconds(AN_HOUR))
        val earliest = commitmentOf("The quote is sent.", where, first)
        val middle = commitmentOf("Someone sends the quote.", where, second)
        val latest = commitmentOf("The quote goes over.", where, third)
        commitments.upsert(listOf(earliest, middle, latest))
        closures.record(listOf(closed(latest, by = closing)))
        // What makes the second merge's survivor a superseded row.
        collapse.merge(FindingPair.of(FindingKind.COMMITMENT, earliest, middle))

        collapse.merge(FindingPair.of(FindingKind.COMMITMENT, middle, latest))

        assertTrue(
            retrieval.closuresOf(listOf(middle.id)).isEmpty(),
            "the refused merge wrote nothing: a verdict here would sit on a reading every " +
                "commitment read filters out",
        )
        assertEquals(
            listOf(closing.externalId),
            retrieval.closuresOf(listOf(latest.id)).map { it.by.externalId },
            "and the reading that was examined keeps its own, so the next pairing can still carry it",
        )
    }

    @Test
    fun `the same merge applied twice leaves the carried verdict alone`(): Unit = runBlocking {
        // A collection re-read after a failure applies the same merge again, and this write is
        // inside it: repeated, it must not move the mark or the closure it carried.
        val where = aConversation("carried twice")
        val promise = aMessage(where, "I will send the quote", STATED)
        val restated = aMessage(where, "I will get the quote over to you", STATED.plusSeconds(60))
        val closing = aMessage(where, "sent", STATED.plusSeconds(AN_HOUR))
        val survivor = commitmentOf("The quote is sent.", where, promise)
        val merged = commitmentOf("Someone sends the quote.", where, restated)
        commitments.upsert(listOf(survivor, merged))
        closures.record(listOf(closed(merged, by = closing)))
        val pair = FindingPair.of(FindingKind.COMMITMENT, survivor, merged)

        collapse.merge(pair)
        collapse.merge(pair)

        val read = assertNotNull(retrieval.closuresOf(listOf(survivor.id)).singleOrNull(), "one verdict, not two")
        assertEquals(closing.externalId, read.by.externalId)
        assertTrue(owedIn(where).isEmpty())
    }

    @Test
    fun `a message with nothing to read is not material a promise can be closed by`(): Unit = runBlocking {
        // A sticker or a service notice closes nothing, and a window of them would be a call billed
        // for the instruction with no material in it. Withdrawn material is left out for the
        // stronger reason: quoting it back is what the withdrawal is recorded to prevent.
        val where = aConversation("unreadable material")
        val stating = aMessage(where, "I will send the quote", STATED)
        aMessage(where, null, STATED.plusSeconds(AN_HOUR))
        val withdrawn = aMessage(where, "sent", STATED.plusSeconds(2 * AN_HOUR))
        val readable = aMessage(where, "thanks", STATED.plusSeconds(3 * AN_HOUR))
        messages.markWithdrawn(listOf(withdrawn.externalId))
        commitments.upsert(listOf(commitmentOf("The quote is sent.", where, stating)))

        val candidate = assertNotNull(owedIn(where).singleOrNull())

        assertEquals(listOf(readable.externalId), candidate.later.map { it.externalId })
    }

    @Test
    fun `the count and the selection agree about what is owed`(): Unit = runBlocking {
        // They share one definition in the source, and this is what says they still do: the driver
        // reads a fall in the count as progress, so a count that disagreed with the selection would
        // stop a pass with work outstanding or never let one finish.
        val where = aConversation("counted")
        val stating = aMessage(where, "I will send the quote", STATED)
        val after = aMessage(where, "noted", STATED.plusSeconds(AN_HOUR))
        val commitment = commitmentOf("The quote is sent.", where, stating)

        val before = closures.owedCount(VERSION, EXAMINE, REARM, NOW)
        commitments.upsert(listOf(commitment))
        val withOne = closures.owedCount(VERSION, EXAMINE, REARM, NOW)
        closures.record(listOf(unclosed(commitment, through = Weighed(after.at, after.externalId))))
        val settled = closures.owedCount(VERSION, EXAMINE, REARM, NOW)

        assertEquals(before + 1, withOne, "a new commitment is one more owed")
        assertEquals(before, settled, "a verdict takes it back out again")
    }

    @Test
    fun `a verdict is replaced in place, and the commitment it qualifies is untouched`(): Unit = runBlocking {
        // The card's own requirement: a closure is a link, so removing it restores the prior reading
        // — which only holds if writing one never edited the finding.
        val where = aConversation("replaced")
        val stating = aMessage(where, "I will send the quote", STATED)
        val first = aMessage(where, "noted", STATED.plusSeconds(AN_HOUR))
        val closing = aMessage(where, "sent", STATED.plusSeconds(2 * AN_HOUR))
        val commitment = commitmentOf("The quote is sent.", where, stating, due = STATED.plus(Duration.ofDays(2)))
        commitments.upsert(listOf(commitment))

        closures.record(listOf(unclosed(commitment, through = Weighed(first.at, first.externalId))))
        closures.record(
            listOf(
                Closure(
                    commitment = commitment.id,
                    closedBy = Evidence(span = spanOf(closing.externalId), sourceTime = closing.at),
                    weighed = Weighed(closing.at, closing.externalId),
                    decidedBy = VERSION,
                ),
            ),
        )

        val read = assertNotNull(
            retrieval.closuresOf(listOf(commitment.id)).singleOrNull(),
            "one verdict, not a history",
        )
        assertEquals(closing.externalId, read.by.externalId)
        assertEquals(
            closing.at,
            read.by.sourceTime,
            "the closing span carries its own instant rather than the watermark",
        )

        val stored = assertNotNull(commitments.of(commitment.evidence.toList()).singleOrNull())
        assertEquals(commitment.claim, stored.claim)
        assertEquals(commitment.due, stored.due, "a closure must not reach into the finding's own row")
        assertFalse(stored.hedged)
    }

    @Test
    fun `a commitment with no verdict reads back as nothing rather than as open`(): Unit = runBlocking {
        val where = aConversation("unverdicted")
        val stating = aMessage(where, "I will send the quote", STATED)
        val commitment = commitmentOf("The quote is sent.", where, stating)
        commitments.upsert(listOf(commitment))

        assertTrue(
            retrieval.closuresOf(listOf(commitment.id)).isEmpty(),
            "the absence of a verdict is the absence of a row, which is not a verdict of open",
        )
    }

    // --- fixtures -------------------------------------------------------------------------

    /** This conversation's candidates, under a limit large enough that the shared rig cannot crowd it out. */
    private suspend fun owedIn(
        conversation: ExternalId,
        rearm: Int = REARM,
        now: Instant = NOW,
    ): List<ClosureCandidate> =
        closures.owed(VERSION, LIMIT, WINDOW, EXAMINE, rearm, now)
            .filter { it.commitment.conversation == conversation }

    private suspend fun aConversation(label: String): ExternalId {
        val externalId = StoreRig.externalId("closure-$label")
        conversations.upsert(Conversation(externalId = externalId, kind = ConversationKind.DIRECT, name = null))
        return externalId
    }

    private suspend fun aMessage(conversation: ExternalId, text: String?, at: Instant): Written {
        val message = Message(
            externalId = StoreRig.externalId("${conversation.nativeId}-msg"),
            sourceTime = at,
            author = null,
            text = text,
        )
        messages.upsert(conversation, listOf(message))
        return Written(message.externalId, at)
    }

    private fun commitmentOf(
        claim: String,
        conversation: ExternalId,
        stating: Written,
        due: Instant? = null,
    ) = Commitment(
        id = EntityId.mint(),
        claim = claim,
        conversation = conversation,
        evidence = setOf(Evidence(span = spanOf(stating.externalId), sourceTime = stating.at)),
        hedged = false,
        due = due,
        // Non-null in every fixture so that this read dropping the column fails a case rather than
        // passing one: the selection projects it and hands back a whole commitment, so a null here
        // would be a finding understating itself.
        obligedParty = OWED,
    )

    private fun unclosed(commitment: Commitment, through: Weighed) = Closure(
        commitment = commitment.id,
        closedBy = null,
        weighed = through,
        decidedBy = VERSION,
    )

    /**
     * A verdict that found [by] closed the promise, weighed as far as that message.
     *
     * The ordinary shape of a found closure: the examination stops reading once it has an answer, so
     * the mark and the closing span are the same message.
     */
    private fun closed(commitment: Commitment, by: Written) = Closure(
        commitment = commitment.id,
        closedBy = Evidence(span = spanOf(by.externalId), sourceTime = by.at),
        weighed = Weighed(by.at, by.externalId),
        decidedBy = VERSION,
    )

    private fun spanOf(record: ExternalId) = SpanId(kind = RecordKind.MESSAGE, record = record)

    /** A message as this test needs to refer back to it: its identifier and when it was sent. */
    private data class Written(val externalId: ExternalId, val at: Instant)

    private companion object {

        val STATED: Instant = Instant.parse("2026-03-02T09:00:00Z")

        /** The contact every fixture commitment obliges, carried through the owed selection. */
        val OWED: EntityId = EntityId.mint()

        /** Older than any horizon this pass would be configured with. */
        val LONG_AGO: Instant = Instant.parse("2024-01-05T09:00:00Z")

        const val AN_HOUR = 3600L

        val EXAMINE: Duration = Duration.ofDays(30)

        /** Large enough that commitments other tests left owed cannot push this one out of the page. */
        const val LIMIT = 500

        const val WINDOW = 20

        /**
         * One, so that the cases about *where* a re-examination resumes are not also cases about
         * *whether* it happens.
         *
         * A floor of one is the setting that puts no floor on anything, which is what those cases
         * were written against. The floor's own behaviour is asserted by the cases that pass [FLOOR]
         * explicitly, and one case pins `Closer.DEFAULT_REARM` so that the shipped figure is
         * covered by something — the rest must not depend on it, because moving it is a judgement
         * about spend and should not turn an unrelated case red.
         */
        const val REARM = 1

        /**
         * A floor to assert the floor with, and deliberately a different figure from the shipped
         * one, so that these cases hold whatever `Closer.DEFAULT_REARM` is moved to.
         */
        const val FLOOR = 4

        /**
         * A sweep instant long past every fixture's closure horizon, which is the ordinary case.
         *
         * The archive is backfilled, so a promise is nearly always older than the horizon by the
         * time anything examines it — and past the horizon the floor has lapsed, which is what
         * stops it dropping material a bounded window could not reach. A case that needs the floor
         * to be *holding* passes [OPEN_HORIZON] instead.
         */
        val NOW: Instant = Instant.parse("2027-01-01T00:00:00Z")

        /** A sweep instant inside [STATED]'s horizon, where the floor is still in force. */
        val OPEN_HORIZON: Instant = STATED.plusSeconds(2 * 86400)

        const val VERSION = "closure-1:test@1/MEDIUM"

        const val OLD_VERSION = "closure-1:test@0/MEDIUM"
    }
}
