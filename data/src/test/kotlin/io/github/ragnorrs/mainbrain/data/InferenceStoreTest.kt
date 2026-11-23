package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Event
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.Stored
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertNull

/**
 * What a model read out of the corpus, against a real Postgres.
 *
 * The claims held here are the schema's: that a finding is identified by what it says within the
 * conversation it was said in, so one obligation restated later is one row carrying two pointers;
 * that two findings read out of one message are both kept, which a key built from the evidencing
 * span could not express; and that a re-derivation replaces the reading and not the identifier, so
 * a better prompt costs tokens rather than renumbering everything downstream.
 *
 * **The rig is shared and nothing is emptied between tests**, so every case works with a freshly
 * minted span and its own conversation.
 */
class InferenceStoreTest {

    private val commitments = JdbcCommitmentRepository(StoreRig.database)
    private val events = JdbcEventRepository(StoreRig.database)

    @Test
    fun `a commitment round-trips with everything that qualifies it`(): Unit = runBlocking {
        val evidence = evidence("round-trip")
        val commitment = Commitment(
            id = EntityId.mint(),
            claim = "The supplier sends the revised quote.",
            conversation = conversation("round-trip"),
            evidence = setOf(evidence),
            hedged = true,
            due = DUE,
            statedBy = EntityId.mint(),
            // A different contact from the author, which is the case the column exists for: the
            // party who stated an obligation is frequently not the party who owes it.
            obligedParty = EntityId.mint(),
        )

        commitments.upsert(listOf(commitment))

        assertEquals(listOf(commitment), commitments.of(listOf(evidence)))
    }

    @Test
    fun `a re-derivation replaces the reading and keeps the identifier`(): Unit = runBlocking {
        // What makes improving the extractor possible. The pass mints an identifier for every
        // proposal because it cannot know which are new; if the store took the offered one, every
        // re-derivation would renumber every commitment and nothing could hold onto one. If it
        // took none of the rest, a better prompt would improve nothing already read.
        //
        // The claim is the key, so this is a re-derivation that read the same sentence again. One
        // that *rewords* it is a different key and forks — recognising a paraphrase is the
        // resolution stage's to do, with a comparison that is not an equality on text.
        val where = conversation("re-derived")
        val evidence = evidence("re-derived")
        val claim = "The quote gets sent this week."
        val first = Commitment(EntityId.mint(), claim, where, setOf(evidence), hedged = false)
        commitments.upsert(listOf(first))

        commitments.upsert(
            listOf(Commitment(EntityId.mint(), claim, where, setOf(evidence), hedged = true, due = DUE)),
        )

        val stored = commitments.of(listOf(evidence)).single()
        assertEquals(first.id, stored.id, "A re-derivation renumbered the commitment.")
        assertEquals(true, stored.hedged, "A better prompt left the first reading in place.")
        assertEquals(DUE, stored.due)
    }

    @Test
    fun `one obligation restated later in a conversation is one commitment`(): Unit = runBlocking {
        // The defect this schema exists to fix. Keyed by its evidencing span, the restatement was a
        // second row attributed to whoever relayed it; keyed by what it says, it is the same
        // obligation with a second pointer.
        val where = conversation("restated")
        val claim = "The quote gets sent."
        val promised = evidence("restated-promise", STATED)
        val relayed = evidence("restated-relay", LATER)
        val promiser = EntityId.mint()
        val owed = EntityId.mint()

        commitments.upsert(
            listOf(
                Commitment(
                    EntityId.mint(),
                    claim,
                    where,
                    setOf(promised),
                    hedged = false,
                    statedBy = promiser,
                    obligedParty = owed,
                ),
            ),
        )
        commitments.upsert(
            listOf(
                Commitment(
                    EntityId.mint(),
                    claim,
                    where,
                    setOf(relayed),
                    hedged = true,
                    statedBy = EntityId.mint(),
                    obligedParty = EntityId.mint(),
                ),
            ),
        )

        val stored = commitments.of(listOf(promised, relayed)).single()
        assertEquals(setOf(promised, relayed), stored.evidence, "The restatement did not attach as evidence.")
        assertEquals(promiser, stored.statedBy, "The relaying author replaced the one who promised.")
        assertEquals(false, stored.hedged, "A second-hand hedge overwrote the first-hand promise.")
        assertEquals(
            owed,
            stored.obligedParty,
            "A later reading of the same obligation moved who owes it. Every column describing " +
                "the reading follows the earliest one, and direction is the column where a " +
                "second-hand restatement is least to be trusted.",
        )
    }

    @Test
    fun `two commitments stated in one message are both kept`(): Unit = runBlocking {
        // A message is one span at ordinal zero, so a key built from the evidence reduced to the
        // message and the second of these silently replaced the first — with no error and no count,
        // the sweep reporting both as written.
        val where = conversation("two-findings")
        val evidence = evidence("two-findings")

        commitments.upsert(
            listOf(
                Commitment(EntityId.mint(), "The quote gets sent.", where, setOf(evidence), hedged = false),
                Commitment(EntityId.mint(), "The deck gets reviewed.", where, setOf(evidence), hedged = false),
            ),
        )

        assertEquals(
            listOf("The deck gets reviewed.", "The quote gets sent."),
            commitments.of(listOf(evidence)).map { it.claim }.sorted(),
            "One message's second finding overwrote its first.",
        )
    }

    @Test
    fun `the same claim in two conversations stays two commitments`(): Unit = runBlocking {
        // The key is scoped to a conversation because a claim names its party only where the corpus
        // names that person. Two identical unattributed claims from different chats are not known
        // to be one obligation, and fusing them would attribute one of them to the other's author.
        val claim = "The report gets sent on Friday."
        val here = evidence("scoped-here")
        val there = evidence("scoped-there")

        commitments.upsert(
            listOf(
                Commitment(EntityId.mint(), claim, conversation("scoped-here"), setOf(here), hedged = false),
                Commitment(EntityId.mint(), claim, conversation("scoped-there"), setOf(there), hedged = false),
            ),
        )

        assertEquals(2, commitments.of(listOf(here, there)).size)
    }

    @Test
    fun `a commitment with no date and nobody recognised behind it still stores`(): Unit = runBlocking {
        // Both are ordinary rather than exceptional: most promises carry no date, and identity is
        // a separate pass, so a message whose author has no contact yet must not hold up the one
        // that reads it.
        val evidence = evidence("sparse")

        commitments.upsert(
            listOf(
                Commitment(
                    EntityId.mint(),
                    "Something gets done.",
                    conversation("sparse"),
                    setOf(evidence),
                    hedged = false,
                ),
            ),
        )

        val stored = commitments.of(listOf(evidence)).single()
        assertNull(stored.due)
        assertNull(stored.statedBy, "An unrecognised author was stored as something rather than as nobody.")
    }

    @Test
    fun `an event round-trips, and one message can hold both an event and a commitment`(): Unit = runBlocking {
        // Two tables keyed the same way, so a message stating a promise and naming a date is one
        // row in each rather than a collision between them.
        val where = conversation("both")
        val evidence = evidence("both")
        val event = Event(
            EntityId.mint(),
            "A review happens on 1 April.",
            where,
            setOf(evidence),
            hedged = false,
            occursAt = DUE,
        )
        val commitment = Commitment(
            EntityId.mint(),
            "The deck is ready by then.",
            where,
            setOf(evidence),
            hedged = false,
        )

        events.upsert(listOf(event))
        commitments.upsert(listOf(commitment))

        assertEquals(listOf(event), events.of(listOf(evidence)))
        assertEquals(listOf(commitment), commitments.of(listOf(evidence)))
    }

    @Test
    fun `nothing is read back for a span that was not asked about`(): Unit = runBlocking {
        val evidence = evidence("unasked")
        commitments.upsert(
            listOf(Commitment(EntityId.mint(), "A claim.", conversation("unasked"), setOf(evidence), hedged = false)),
        )

        assertEquals(
            emptyList<Commitment>(),
            commitments.of(listOf(evidence("unrelated"))),
            "A read scoped to one span returned another's. The rig is shared, so this is the " +
                "assertion that the join filters rather than decorating.",
        )
    }

    @Test
    fun `an ordinal is part of the address, not decoration`(): Unit = runBlocking {
        // The ordinal is what tells two spans of one record apart, and a join that dropped it
        // would answer for the wrong one. A message is one span and always ordinal zero, so this
        // uses two ordinals of one message: the kind that will really carry several is a
        // document, which the schema does not admit here until something reads one.
        val where = conversation("ordinal")
        val record = StoreRig.externalId("ordinal")
        val first = Evidence(SpanId(RecordKind.MESSAGE, record, ordinal = 0), STATED)
        val second = Evidence(SpanId(RecordKind.MESSAGE, record, ordinal = 1), STATED)
        commitments.upsert(
            listOf(
                Commitment(EntityId.mint(), "Read out of the first page.", where, setOf(first), hedged = false),
                Commitment(EntityId.mint(), "Read out of the second.", where, setOf(second), hedged = false),
            ),
        )

        assertEquals(
            listOf("Read out of the second."),
            commitments.of(listOf(second)).map { it.claim },
            "The ordinal was ignored, so both spans of one document answer as the same address.",
        )
    }

    @Test
    fun `a read by one span returns every span the finding holds`(): Unit = runBlocking {
        // A finding carrying two pointers is one row of the derived layer, so handing back only the
        // span that was asked for would make the entity say something false about itself — and the
        // answer path decides which pointer to show from the whole set.
        val where = conversation("whole")
        val promised = evidence("whole-promise", STATED)
        val relayed = evidence("whole-relay", LATER)
        val claim = "The invoice gets raised."

        commitments.upsert(listOf(Commitment(EntityId.mint(), claim, where, setOf(promised), hedged = false)))
        commitments.upsert(listOf(Commitment(EntityId.mint(), claim, where, setOf(relayed), hedged = false)))

        assertEquals(
            setOf(promised, relayed),
            commitments.of(listOf(relayed)).single().evidence,
            "Asked about one span, the store returned a finding missing the rest of its evidence.",
        )
    }

    @Test
    fun `a write says which rows it caused and which it replaced`(): Unit = runBlocking {
        // **The figure the pass reports as written, and it has to come from here.** The caller
        // offers an identifier per reading because it cannot know which are new, so it cannot count
        // its own inserts either. Read off the row rather than by comparing the returned identifier
        // against the offered one, so the answer does not depend on the caller having minted a
        // fresh one.
        val where = conversation("counted")
        val claim = "The counted quote gets sent."
        val first = Commitment(EntityId.mint(), claim, where, setOf(evidence("counted-first")), hedged = false)

        val fresh = commitments.upsert(listOf(first))
        val again = commitments.upsert(
            listOf(Commitment(EntityId.mint(), claim, where, setOf(evidence("counted-again")), hedged = false)),
        )

        assertEquals(Stored(written = 1, overwritten = 0), fresh, "A row this write caused was not reported as caused.")
        assertEquals(
            Stored(written = 0, overwritten = 1),
            again,
            "A reading that replaced one already held was reported as a row, so re-reading a " +
                "window reads exactly like reading a new one.",
        )
    }

    @Test
    fun `one claim offered twice in a batch is one row and one overwrite`(): Unit = runBlocking {
        // Counted per write rather than over the argument as a set, because that is what the
        // statement does: an upsert per reading, so the second finds the first's row.
        val where = conversation("twice")
        val claim = "The quote gets sent, twice over."

        val stored = commitments.upsert(
            listOf(
                Commitment(EntityId.mint(), claim, where, setOf(evidence("twice-one")), hedged = false),
                Commitment(EntityId.mint(), claim, where, setOf(evidence("twice-other")), hedged = false),
            ),
        )

        assertEquals(Stored(written = 1, overwritten = 1), stored)
    }

    @Test
    fun `nothing offered is nothing done, and nothing claimed`(): Unit = runBlocking {
        assertEquals(Stored.NOTHING, commitments.upsert(emptyList()))
        assertEquals(Stored.NOTHING, events.upsert(emptyList()))
    }

    @Test
    fun `a finding never supersedes through its own write`(): Unit = runBlocking {
        // The figure that stays zero on this port, and not by accident: one claim replacing another
        // is the collapse's doing and goes through its own port, so a non-zero figure here would
        // mean an upsert had removed a row — which is the one thing this layer forbids.
        val where = conversation("never-supersedes")
        val stored = events.upsert(
            listOf(
                Event(EntityId.mint(), "The review happens.", where, setOf(evidence("ev-one")), hedged = false),
                Event(EntityId.mint(), "The handover happens.", where, setOf(evidence("ev-other")), hedged = false),
            ),
        )

        assertEquals(Stored(written = 2, overwritten = 0, superseded = 0), stored)
    }

    private fun evidence(label: String, at: Instant = STATED) = Evidence(
        span = SpanId(kind = RecordKind.MESSAGE, record = StoreRig.externalId(label)),
        sourceTime = at,
    )

    private fun conversation(label: String) = StoreRig.externalId("conversation-$label")

    private companion object {
        val STATED: Instant = Instant.parse("2026-03-02T10:00:00Z")
        val LATER: Instant = Instant.parse("2026-03-05T10:00:00Z")
        val DUE: Instant = Instant.parse("2026-04-01T09:00:00Z")
    }
}
