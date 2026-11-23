package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Event
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.FindingKind
import io.github.ragnorrs.mainbrain.domain.entity.FindingPair
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.recognition.SAME_FACT
import io.github.ragnorrs.mainbrain.domain.recognition.WORTH_ASKING
import io.github.ragnorrs.mainbrain.domain.recognition.runningVectorsVersion
import io.github.ragnorrs.mainbrain.domain.recognition.vectorsVersion
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The collapse against a real Postgres: which pairs the store offers, and the two ways one is
 * settled.
 *
 * The claims held here are the ones only SQL can answer. That the candidate query offers a pair the
 * content key could not collapse — two survivors of one conversation, worded differently — and
 * refuses one it has already been paid to decide. That a merge leaves both rows in place and moves
 * the evidence, so a finding keeps every span it was ever read from. And that applying the same
 * merge twice changes nothing, which is what a collection re-read after a failure does.
 *
 * The other thing a merge moves — a commitment's closure verdict — is asserted in `ClosureStoreTest`
 * instead, because judging it needs the conversation and the messages behind the window, which this
 * case's fixtures do not write.
 *
 * **The rig is shared and nothing is emptied between tests**, so every case works in a conversation
 * of its own — which is also what keeps the candidate query's results to the case that wrote them.
 */
class CollapseStoreTest {

    private val commitments = JdbcCommitmentRepository(StoreRig.database)
    private val events = JdbcEventRepository(StoreRig.database)
    private val collapse = JdbcCollapseRepository(StoreRig.database)

    /**
     * The acceptance check the content key cannot meet on its own.
     *
     * Two readings of one obligation in different words are two rows — the key is the claim — and
     * this is the query that has to notice them in order for anything to put them back together.
     */
    @Test
    fun `two readings worded differently are offered as a pair`(): Unit = runBlocking {
        val where = conversation("reworded")
        val earlier = commitment("The quote is sent on Friday.", where, STATED)
        val later = commitment("Someone undertook to send the quote.", where, STATED.plusSeconds(AN_HOUR))
        commitments.upsert(listOf(earlier, later))

        val offered = collapse.candidates(LIMIT, Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY).inOnly(where)

        val pair = assertNotNull(offered.singleOrNull(), "exactly one pair, offered once: $offered")
        assertEquals(FindingKind.COMMITMENT, pair.kind)
        assertEquals(
            earlier.id,
            pair.survivor.id,
            "the earlier reading survives, so merging keeps the obligation's own instant",
        )
        assertEquals(later.id, pair.superseded.id)
        assertEquals(
            OWED,
            (pair.survivor as Commitment).obligedParty,
            "the obliged party did not survive the candidate read, which hands back whole findings " +
                "for a model to compare",
        )
    }

    @Test
    fun `two readings further apart than the window are not a pair`(): Unit = runBlocking {
        val where = conversation("far-apart")
        commitments.upsert(
            listOf(
                commitment("The quote is sent.", where, STATED),
                commitment("Someone sends the quote.", where, STATED.plusSeconds(A_WEEK * 2)),
            ),
        )

        assertEquals(
            emptyList(),
            collapse.candidates(LIMIT, Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY).inOnly(where),
            "the window bounds the candidate set, or a year of a conversation is compared against itself",
        )
    }

    @Test
    fun `two readings in different conversations are not a pair`(): Unit = runBlocking {
        val here = conversation("one-chat")
        val there = conversation("other-chat")
        // The identical claim, which is what makes this discriminate: within one conversation these
        // would not even be two rows.
        commitments.upsert(
            listOf(
                commitment("The quote is sent.", here, STATED),
                commitment("The quote is sent.", there, STATED.plusSeconds(AN_HOUR)),
            ),
        )

        assertEquals(
            emptyList(),
            collapse.candidates(LIMIT, Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY).inOnly(here, there),
        )
    }

    /** A commitment and an event are never one finding, whatever the two claims say. */
    @Test
    fun `a commitment and an event are not a pair`(): Unit = runBlocking {
        val where = conversation("across-kinds")
        val claim = "The review happens on Friday."
        commitments.upsert(listOf(commitment(claim, where, STATED)))
        events.upsert(listOf(event(claim, where, STATED.plusSeconds(AN_HOUR))))

        assertEquals(
            emptyList(),
            collapse.candidates(LIMIT, Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY).inOnly(where),
        )
    }

    @Test
    fun `a merge moves the evidence and leaves both rows`(): Unit = runBlocking {
        val where = conversation("merging")
        val first = evidence("merge-first", STATED)
        val second = evidence("merge-second", STATED.plusSeconds(AN_HOUR))
        val earlier = commitment("The quote is sent.", where, STATED, first)
        val later = commitment("Someone sends the quote.", where, STATED.plusSeconds(AN_HOUR), second)
        commitments.upsert(listOf(earlier, later))

        collapse.merge(FindingPair.of(FindingKind.COMMITMENT, earlier, later))

        val survivor = assertNotNull(commitments.of(listOf(first)).singleOrNull())
        assertEquals(earlier.id, survivor.id, "the identifier the survivor already had")
        assertEquals(
            setOf(first, second),
            survivor.evidence,
            "the survivor carries the evidence of both, so a citation on either message still resolves",
        )
        assertEquals(
            listOf(earlier.id),
            commitments.of(listOf(second)).map { it.id },
            "the superseded reading's own span now reaches the survivor and nothing else",
        )
    }

    /**
     * A collection re-read after a failure applies the same merge again, so it has to be safe to
     * repeat — otherwise a submission would be uncollectable for good.
     */
    @Test
    fun `the same merge applied twice changes nothing`(): Unit = runBlocking {
        val where = conversation("twice")
        val first = evidence("twice-first", STATED)
        val second = evidence("twice-second", STATED.plusSeconds(AN_HOUR))
        val earlier = commitment("The quote is sent.", where, STATED, first)
        val later = commitment("Someone sends the quote.", where, STATED.plusSeconds(AN_HOUR), second)
        commitments.upsert(listOf(earlier, later))
        val pair = FindingPair.of(FindingKind.COMMITMENT, earlier, later)

        collapse.merge(pair)
        collapse.merge(pair)

        assertEquals(setOf(first, second), commitments.of(listOf(first)).single().evidence)
    }

    @Test
    fun `a merged reading is never offered as a candidate again`(): Unit = runBlocking {
        val where = conversation("merged-away")
        val earlier = commitment("The quote is sent.", where, STATED)
        val later = commitment("Someone sends the quote.", where, STATED.plusSeconds(AN_HOUR))
        commitments.upsert(listOf(earlier, later))

        collapse.merge(FindingPair.of(FindingKind.COMMITMENT, earlier, later))

        assertEquals(
            emptyList(),
            collapse.candidates(LIMIT, Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY).inOnly(where),
            "a superseded reading is part of another finding, and comparing it again would merge " +
                "a row onto one that had itself been merged away",
        )
    }

    /**
     * The whole reason the verdict is written down: without it the pair stays a candidate by every
     * test the store can make, and the next idle sweep pays to decide it again.
     */
    @Test
    fun `a pair the model was paid to keep apart is never offered again`(): Unit = runBlocking {
        val where = conversation("kept-apart")
        val earlier = commitment("The quote is sent.", where, STATED)
        val later = commitment("The contract is signed.", where, STATED.plusSeconds(AN_HOUR))
        commitments.upsert(listOf(earlier, later))
        assertTrue(
            collapse.candidates(LIMIT, Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY).inOnly(where).isNotEmpty(),
            "the pair is a candidate before the verdict, or this case would pass for the wrong reason",
        )

        collapse.keepApart(FindingKind.COMMITMENT, earlier.id, later.id, ADJUDICATED_BY)

        assertEquals(
            emptyList(),
            collapse.candidates(LIMIT, Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY).inOnly(where),
        )
    }

    /**
     * The acceptance check for the adjudication version: rewording the comparison makes what the
     * old wording decided owed again.
     *
     * Without this the stage's instruction sits outside every version, so editing it ships a
     * behavioural change that invalidates nothing — the pairs it kept apart stay kept apart under
     * wording that no longer exists, and nothing says they are stale.
     *
     * The pair is asserted to come back **and** to be excluded again under the version that decided
     * it, because only the second half says the exclusion still works: a query that had stopped
     * honouring verdicts altogether would pass the first assertion alone.
     */
    @Test
    fun `a pair kept apart under a reworded comparison is offered again`(): Unit = runBlocking {
        val where = conversation("reworded-comparison")
        val earlier = commitment("The quote is sent.", where, STATED)
        val later = commitment("The contract is signed.", where, STATED.plusSeconds(AN_HOUR))
        commitments.upsert(listOf(earlier, later))
        // Counted before the verdict and compared against the counts after, because the rig is
        // shared: a count is over every conversation any case has written, so only its own
        // movement is this case's to assert.
        val currentBefore = collapse.candidateCount(Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY)
        val rewordedBefore = collapse.candidateCount(Duration.ofDays(7), COMPARED_BY, REWORDED)

        collapse.keepApart(FindingKind.COMMITMENT, earlier.id, later.id, ADJUDICATED_BY)

        assertEquals(
            emptyList(),
            collapse.candidates(LIMIT, Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY).inOnly(where),
            "the verdict holds under the version that bought it, or this case proves nothing",
        )
        val owed = collapse.candidates(LIMIT, Duration.ofDays(7), COMPARED_BY, REWORDED).inOnly(where)
        val pair = assertNotNull(owed.singleOrNull(), "the pair is owed a verdict again: $owed")
        assertEquals(earlier.id, pair.survivor.id)
        assertEquals(later.id, pair.superseded.id)
        assertEquals(
            currentBefore - 1,
            collapse.candidateCount(Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY),
            "the verdict takes the pair out of the set the running version is offered",
        )
        assertEquals(
            rewordedBefore,
            collapse.candidateCount(Duration.ofDays(7), COMPARED_BY, REWORDED),
            "and leaves the reworded one where it was — the count moves with the selection, or the " +
                "driver reads a stall where there is work",
        )
    }

    /**
     * A cheap rule-out is retired by its own version and not by the paid tier's.
     *
     * The two answers share one column, so a query honouring the wrong one would either re-buy
     * every vector judgement whenever the comparison was reworded, or keep one for ever after a
     * better embedding model arrived.
     */
    @Test
    fun `a vector rule-out survives a reworded comparison`(): Unit = runBlocking {
        val where = conversation("cheap-rule-out")
        val earlier = commitment("The quote is sent.", where, STATED)
        val later = commitment("The contract is signed.", where, STATED.plusSeconds(AN_HOUR))
        commitments.upsert(listOf(earlier, later))
        collapse.keepApart(FindingKind.COMMITMENT, earlier.id, later.id, COMPARED_BY)

        assertEquals(
            emptyList(),
            collapse.candidates(LIMIT, Duration.ofDays(7), COMPARED_BY, REWORDED).inOnly(where),
            "a reworded comparison says nothing about what the vectors ruled out",
        )
        assertEquals(
            1,
            collapse.candidates(LIMIT, Duration.ofDays(7), BETTER_EMBEDDINGS, REWORDED)
                .inOnly(where).size,
            "and a better embedding version is what retires it",
        )
    }

    /**
     * The acceptance check for this card: move a similarity bound, and the pairs the old arithmetic
     * ruled out are owed again.
     *
     * **Shown rather than asserted, and shown through the real query.** What decides a cheap
     * rule-out is the embedding version together with the two bounds that read its vectors, so a
     * version naming only the sidecar leaves a bound outside every version — and moving one would
     * ship a behavioural change that invalidated nothing: no pair becomes owed, nothing is
     * re-compared, and the store keeps rule-outs produced by arithmetic the build no longer
     * performs.
     *
     * Both directions, because only the pair of them says the exclusion still works: a query that
     * had stopped honouring cheap rule-outs altogether would pass the first assertion alone. The
     * count is asserted beside the selection for the reason the paid tier's case gives — the driver
     * reads a fall in it as progress, so a count honouring a rule-out the selection would re-offer
     * reports the stage caught up with pairs it is about to be handed.
     *
     * It is written over [WORTH_ASKING] and holds for a bound added later without being edited,
     * since what it compares is two values of the production function.
     */
    @Test
    fun `a pair ruled out under a moved similarity bound is offered again`(): Unit = runBlocking {
        assertNotEquals(
            COMPARED_BY,
            BOUND_MOVED,
            "moving a bound has to move the version, or this case would pass on two equal strings " +
                "and assert nothing: $COMPARED_BY",
        )
        val where = conversation("moved-bound")
        val earlier = commitment("The quote is sent.", where, STATED)
        val later = commitment("The contract is signed.", where, STATED.plusSeconds(AN_HOUR))
        commitments.upsert(listOf(earlier, later))
        // Counted before the rule-out and compared against the counts after, because the rig is
        // shared: a count is over every conversation any case has written, so only its own movement
        // is this case's to assert.
        val runningBefore = collapse.candidateCount(Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY)
        val movedBefore = collapse.candidateCount(Duration.ofDays(7), BOUND_MOVED, ADJUDICATED_BY)

        collapse.keepApart(FindingKind.COMMITMENT, earlier.id, later.id, COMPARED_BY)

        assertEquals(
            emptyList(),
            collapse.candidates(LIMIT, Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY).inOnly(where),
            "the rule-out holds under the bounds that made it, or this case proves nothing",
        )
        val owed = collapse.candidates(LIMIT, Duration.ofDays(7), BOUND_MOVED, ADJUDICATED_BY).inOnly(where)
        val pair = assertNotNull(owed.singleOrNull(), "the pair is owed a comparison again: $owed")
        assertEquals(earlier.id, pair.survivor.id)
        assertEquals(later.id, pair.superseded.id)
        assertEquals(
            runningBefore - 1,
            collapse.candidateCount(Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY),
            "the rule-out takes the pair out of the set the running bounds are offered",
        )
        assertEquals(
            movedBefore,
            collapse.candidateCount(Duration.ofDays(7), BOUND_MOVED, ADJUDICATED_BY),
            "and leaves the moved ones where they were — the count moves with the selection, or the " +
                "driver reads a stall where there is work",
        )
    }

    /**
     * The two tiers stay independent across a bound move, which is what the collapse's own
     * documentation claims of them.
     *
     * The answers share one column, so the cheap tier gaining a bound in its version must not have
     * made the paid tier's verdicts retirable by a threshold nobody paid for. The reverse is
     * covered by `a vector rule-out survives a reworded comparison`.
     */
    @Test
    fun `a paid verdict survives a moved similarity bound`(): Unit = runBlocking {
        val where = conversation("paid-outlives-bound")
        val earlier = commitment("The quote is sent.", where, STATED)
        val later = commitment("The contract is signed.", where, STATED.plusSeconds(AN_HOUR))
        commitments.upsert(listOf(earlier, later))

        collapse.keepApart(FindingKind.COMMITMENT, earlier.id, later.id, ADJUDICATED_BY)

        assertEquals(
            emptyList(),
            collapse.candidates(LIMIT, Duration.ofDays(7), BOUND_MOVED, ADJUDICATED_BY).inOnly(where),
            "a moved bound says nothing about what the model was paid to decide",
        )
        assertEquals(
            1,
            collapse.candidates(LIMIT, Duration.ofDays(7), BOUND_MOVED, REWORDED).inOnly(where).size,
            "and a reworded comparison is what retires it",
        )
    }

    /**
     * **One unordered pair is one row**, whichever way round it is offered.
     *
     * Which of two readings was stated first can move — evidence accumulates — so a verdict stored
     * under the order it was compared in could be written twice and found by neither asking.
     */
    @Test
    fun `a verdict is found whichever way round the pair is given`(): Unit = runBlocking {
        val where = conversation("either-way")
        val earlier = commitment("The quote is sent.", where, STATED)
        val later = commitment("The contract is signed.", where, STATED.plusSeconds(AN_HOUR))
        commitments.upsert(listOf(earlier, later))

        collapse.keepApart(FindingKind.COMMITMENT, later.id, earlier.id, ADJUDICATED_BY)
        // Recorded the other way round too, which must not be a second row and must not fail.
        collapse.keepApart(FindingKind.COMMITMENT, earlier.id, later.id, ADJUDICATED_BY)

        assertEquals(
            emptyList(),
            collapse.candidates(LIMIT, Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY).inOnly(where),
        )
    }

    @Test
    fun `a pair read back by identifier comes back ordered by what the store now holds`(): Unit =
        runBlocking {
            val where = conversation("read-back")
            val earlier = commitment("The quote is sent.", where, STATED)
            val later = commitment("Someone sends the quote.", where, STATED.plusSeconds(AN_HOUR))
            commitments.upsert(listOf(earlier, later))

            // Offered in the order a key happens to carry, which the store must not trust.
            val pair = assertNotNull(collapse.pair(FindingKind.COMMITMENT, later.id, earlier.id))

            assertEquals(earlier.id, pair.survivor.id)
            assertEquals(later.id, pair.superseded.id)
        }

    @Test
    fun `a pair whose reading has been merged away reads back as nothing`(): Unit = runBlocking {
        val where = conversation("gone")
        val earlier = commitment("The quote is sent.", where, STATED)
        val later = commitment("Someone sends the quote.", where, STATED.plusSeconds(AN_HOUR))
        commitments.upsert(listOf(earlier, later))
        collapse.merge(FindingPair.of(FindingKind.COMMITMENT, earlier, later))

        assertNull(
            collapse.pair(FindingKind.COMMITMENT, earlier.id, later.id),
            "the collection that asked about this pair must not merge onto a superseded row",
        )
    }

    @Test
    fun `an event collapses by the same rule as a commitment`(): Unit = runBlocking {
        val where = conversation("event-merge")
        val first = evidence("event-first", STATED)
        val second = evidence("event-second", STATED.plusSeconds(AN_HOUR))
        val earlier = event("The review happens on Friday.", where, STATED, first)
        val later = event("A review is on Friday.", where, STATED.plusSeconds(AN_HOUR), second)
        events.upsert(listOf(earlier, later))

        val pair = assertNotNull(
            collapse.candidates(LIMIT, Duration.ofDays(7), COMPARED_BY, ADJUDICATED_BY).inOnly(where).singleOrNull(),
        )
        assertEquals(FindingKind.EVENT, pair.kind)
        collapse.merge(pair)

        assertEquals(setOf(first, second), events.of(listOf(first)).single().evidence)
    }

    /**
     * The candidate query reads whatever is in the store, so a case asserting an absence has to
     * narrow to its own conversations — the rig is shared and every other case's findings are in
     * there too.
     */
    private fun List<FindingPair>.inOnly(vararg conversations: ExternalId) =
        filter { it.survivor.conversation in conversations.toSet() }

    private fun commitment(
        claim: String,
        conversation: ExternalId,
        at: Instant,
        evidence: Evidence = evidence(claim, at),
    ) = Commitment(
        id = EntityId.mint(),
        claim = claim,
        conversation = conversation,
        evidence = setOf(evidence),
        hedged = false,
        // Non-null in every fixture so that this read dropping the column fails a case rather than
        // passing one: the candidate query projects it per kind, the event half having no such
        // column at all.
        obligedParty = OWED,
    )

    private fun event(
        claim: String,
        conversation: ExternalId,
        at: Instant,
        evidence: Evidence = evidence(claim, at),
    ) = Event(
        id = EntityId.mint(),
        claim = claim,
        conversation = conversation,
        evidence = setOf(evidence),
        hedged = false,
    )

    private fun evidence(label: String, at: Instant = STATED) = Evidence(
        span = SpanId(kind = RecordKind.MESSAGE, record = StoreRig.externalId(label)),
        sourceTime = at,
    )

    private fun conversation(label: String) = StoreRig.externalId("collapse-$label")

    private companion object {
        val STATED: Instant = Instant.parse("2026-03-02T10:00:00Z")

        /** The contact every fixture commitment obliges, carried through the candidate read. */
        val OWED: EntityId = EntityId.mint()
        const val AN_HOUR = 60L * 60
        const val A_WEEK = 60L * 60 * 24 * 7

        /** Larger than any one case writes, so a case asserting a pair is not bounded out of it. */
        const val LIMIT = 512

        /** The sidecar version behind the vector versions below; its content carries nothing. */
        const val EMBEDDINGS = "fake-embeddings-1"

        /**
         * The vector version a case compares under; any cheap rule-out is recorded against it.
         *
         * **Built by the production function rather than written out**, because what this file has
         * to show is that moving a bound makes the pairs ruled out under the old one owed again —
         * and a hand-written string could only show that two different strings behave differently.
         * The cases that use [BOUND_MOVED] assert the two differ before relying on it, so the
         * function ceasing to vary fails them rather than passing them.
         */
        val COMPARED_BY = runningVectorsVersion(EMBEDDINGS)

        /**
         * The same sidecar and stage with one similarity bound moved, which is what a cheap rule-out
         * has to be retired by.
         *
         * [WORTH_ASKING] rather than the merge bound because it is the one a deployment has an active
         * reason to move: it sets what the stage costs. Either would do, and the domain's own suite
         * covers both.
         */
        val BOUND_MOVED = vectorsVersion(EMBEDDINGS, sameFact = SAME_FACT, worthAsking = WORTH_ASKING - 0.05)

        /** A better sidecar model under the bounds that are running, the other way a rule-out goes. */
        val BETTER_EMBEDDINGS = runningVectorsVersion("fake-embeddings-2")

        /** The adjudication version a case buys verdicts under; a paid one is recorded against it. */
        const val ADJUDICATED_BY = "adjudication-1:fake-model@1/MEDIUM"

        /**
         * The same stage under a reworded comparison, which is what a verdict has to be retired by.
         *
         * Spelled out rather than derived from [ADJUDICATED_BY], because what the case is about is
         * two different strings reaching the store — building the second from the first would pass
         * if the production version function stopped varying at all.
         */
        const val REWORDED = "adjudication-1:fake-model@2/MEDIUM"
    }
}
