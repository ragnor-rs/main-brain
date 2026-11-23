package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.Origin
import io.github.ragnorrs.mainbrain.domain.entity.Predicate
import io.github.ragnorrs.mainbrain.domain.entity.Statement
import io.github.ragnorrs.mainbrain.domain.entity.Validity
import io.github.ragnorrs.mainbrain.domain.entity.ValueSynonym
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The resolution stage: which reading contradicts which, and over what period each held.
 *
 * Every case here is one of the rules the architecture states about conflicts, because the
 * failures they prevent are invisible in the data afterwards. A fact overwritten by a backfill
 * reading older history looks exactly like a fact that was always that way; two windows covering
 * one instant answer a question two ways with nothing to say which is right.
 */
class ResolutionTest {

    @Test
    fun `one reading is one window, still open`() {
        val resolved = resolved(listOf(reading("a rate", "m1", MARCH)))

        val statement = resolved.single()
        assertEquals(MARCH, statement.validity.from)
        assertNull(
            statement.validity.until,
            "A fact nothing has contradicted is still holding. Closing it would mean a fact going " +
                "unmentioned was evidence that it stopped being true.",
        )
    }

    @Test
    fun `a changed value closes the window before it and keeps both`() {
        val resolved = resolved(
            listOf(reading("one rate", "m1", MARCH), reading("another rate", "m2", JUNE)),
        )

        assertEquals(2, resolved.size, "A contradiction removed a reading.")
        val earlier = resolved.single { it.value == "one rate" }
        val later = resolved.single { it.value == "another rate" }
        assertEquals(Validity(MARCH, JUNE), earlier.validity)
        assertEquals(Validity(JUNE), later.validity)
        assertEquals(
            listOf(earlier),
            resolved.filter { it.heldAt(APRIL) },
            "April is covered by exactly one window, or the question has two answers.",
        )
    }

    @Test
    fun `the same value restated is corroboration and not a second window`() {
        val resolved = resolved(
            listOf(reading("a rate", "m1", MARCH), reading("a rate", "m2", JUNE)),
        )

        val statement = resolved.single()
        assertEquals(MARCH, statement.validity.from, "The window begins when the fact was first said.")
        assertNull(statement.validity.until)
        assertEquals(
            2,
            statement.evidence.size,
            "Two spans saying the same thing became one pointer or two facts. It is one fact, and " +
                "the evidence accumulates on it.",
        )
    }

    @Test
    fun `a value that holds, is contradicted and holds again gets a second window`() {
        // Why the window's start is part of a statement's key: these two agree on everything else.
        val resolved = resolved(
            listOf(
                reading("a rate", "m1", MARCH),
                reading("another rate", "m2", JUNE),
                reading("a rate", "m3", SEPTEMBER),
            ),
        )

        assertEquals(3, resolved.size)
        assertEquals(
            setOf(Validity(MARCH, JUNE), Validity(SEPTEMBER)),
            resolved.filter { it.value == "a rate" }.map { it.validity }.toSet(),
            "The restated value either reopened the first window or collapsed into it. It held " +
                "over two periods, which is two statements.",
        )
    }

    @Test
    fun `two readings of one instant stay open rather than closing each other`() {
        // Two sources disagreeing about the same moment. Neither can close the other — a window
        // closed where it opened covers no instant at all — so both are kept and preferring
        // between them is the answer path's to do.
        val resolved = resolved(
            listOf(reading("what one said", "m1", MARCH), reading("what the other said", "m2", MARCH)),
        )

        assertEquals(2, resolved.size)
        assertTrue(resolved.all { it.validity.until == null }, "A window was closed at its own start.")
        assertEquals(2, resolved.count { it.heldAt(APRIL) }, "Both readings cover April, which is the disagreement.")
    }

    @Test
    fun `a later disagreement closes both sides of an earlier one`() {
        val resolved = resolved(
            listOf(
                reading("what one said", "m1", MARCH),
                reading("what the other said", "m2", MARCH),
                reading("what settled it", "m3", JUNE),
            ),
        )

        assertEquals(
            setOf(Validity(MARCH, JUNE)),
            resolved.filter { it.validity.from == MARCH }.map { it.validity }.toSet(),
            "A reading that came after both sides of a disagreement has to close both of them.",
        )
        assertEquals(1, resolved.count { it.heldAt(JUNE) })
    }

    @Test
    fun `extraction order carries no meaning`() {
        // The rule this exists for: a backfill reads 2021 after it read yesterday, so ordering by
        // when the system learnt something would let old history close windows over current facts
        // as it arrived.
        val readings = listOf(
            reading("a rate", "m1", MARCH),
            reading("another rate", "m2", JUNE),
            reading("a third rate", "m3", SEPTEMBER),
        )

        assertEquals(
            resolved(readings).toSet(),
            resolved(readings.reversed()).toSet(),
            "Reversing the order the readings arrived in changed the windows, so a backfill " +
                "would rewrite current facts with stale ones.",
        )
    }

    @Test
    fun `re-resolving its own output changes nothing`() {
        // What makes a re-run free. Every window is recomputed from the evidence rather than read
        // off the input, so the pass writes what is already stored.
        val once = resolved(
            listOf(
                reading("a rate", "m1", MARCH),
                reading("a rate", "m2", APRIL),
                reading("another rate", "m3", JUNE),
            ),
        )

        assertEquals(once.toSet(), resolved(once).toSet())
    }

    @Test
    fun `the strongest origin in a window decides how it may be phrased`() {
        val resolved = resolved(
            listOf(
                reading("a rate", "m1", MARCH, Origin.INFERRED),
                reading("a rate", "m2", APRIL, Origin.LABELLED),
            ),
        )

        assertEquals(
            Origin.LABELLED,
            resolved.single().origin,
            "A fact a model read and a source also stated is the stronger of the two: a profile " +
                "field outranks a passing mention, and the weaker origin decides the phrasing.",
        )
    }

    @Test
    fun `another predicate and another subject are other questions`() {
        val other = EntityId.mint()
        val resolved = resolved(
            listOf(
                reading("a rate", "m1", MARCH),
                // Through `copy` rather than more parameters on the helper: what differs is one
                // field, and a helper taking every field is one nobody reads at the call site.
                reading("an address", "m2", JUNE).copy(predicate = Predicate.of("based in")),
                reading("a rate", "m3", JUNE).copy(subject = other),
            ),
        )

        assertEquals(3, resolved.size, "Two facts about different things closed each other's windows.")
        assertTrue(
            resolved.all { it.validity.until == null },
            "Nothing here contradicts anything else, so no window should have been closed.",
        )
    }

    @Test
    fun `nothing resolves to nothing`() {
        assertEquals(emptyList(), resolved(emptyList()))
    }

    // ---- two wordings of one value ---------------------------------------------------------

    /**
     * The acceptance check for the statement half of the collapse, and the failure it replaces is
     * worse than a duplicate.
     *
     * A re-derivation under a moved prompt version rewords the value. Compared for equality that is
     * a *changed* value, so the earlier window closes where the later opens and the fact reads as
     * having changed when nothing changed but the words.
     */
    @Test
    fun `a reworded value is one fact over one window, not a change of fact`() {
        val readings = listOf(
            reading("five thousand a month", "m1", MARCH),
            reading("5000 per month", "m2", JUNE),
        )

        val resolved = resolved(readings, listOf(synonym("five thousand a month", "5000 per month")))

        val fact = assertNotNull(resolved.singleOrNull(), "one fact, not two: $resolved")
        assertEquals("five thousand a month", fact.value, "kept in the words it was first stated in")
        assertEquals(MARCH, fact.validity.from)
        assertNull(fact.validity.until, "nothing contradicted it, so no window was closed")
        assertEquals(2, fact.evidence.size, "both spans state it, so both are its evidence")
    }

    /**
     * The case that must keep working, and the reason nothing infers a synonym from text or
     * vectors: two values of one relation are usually a deliberate contradiction, and two short
     * ones close in wording are the pair a comparison is least able to tell from a paraphrase.
     */
    @Test
    fun `a rate that was revised is still two windows`() {
        val readings = listOf(
            reading("5000 per month", "m1", MARCH),
            reading("5500 per month", "m2", JUNE),
        )

        // No synonym recorded, which is what an unadjudicated pair looks like however alike the
        // two strings are.
        val resolved = resolved(readings)

        assertEquals(2, resolved.size, "a revision is history and not a duplicate")
        val earlier = resolved.single { it.value == "5000 per month" }
        assertEquals(JUNE, earlier.validity.until, "the earlier window closes where the later opens")
    }

    @Test
    fun `a synonym for another subject changes nothing`() {
        val readings = listOf(
            reading("five thousand a month", "m1", MARCH),
            reading("5000 per month", "m2", JUNE),
        )

        val elsewhere = ValueSynonym(
            subject = EntityId.mint(),
            predicate = Predicate.of("charges"),
            one = "five thousand a month",
            other = "5000 per month",
        )

        assertEquals(
            2,
            resolved(readings, listOf(elsewhere)).size,
            "a verdict is about one subject's values: two people may word one rate alike and mean " +
                "different things",
        )
    }

    @Test
    fun `a synonym for another predicate changes nothing`() {
        val readings = listOf(
            reading("five thousand a month", "m1", MARCH),
            reading("5000 per month", "m2", JUNE),
        )

        val elsewhere = ValueSynonym(
            subject = SUBJECT,
            predicate = Predicate.of("works at"),
            one = "five thousand a month",
            other = "5000 per month",
        )

        assertEquals(2, resolved(readings, listOf(elsewhere)).size)
    }

    /**
     * Three wordings and two verdicts: the third must not reopen what the first two settled, which
     * is why the groups are walked rather than the pairs applied one at a time.
     */
    @Test
    fun `synonymy carries across a chain of verdicts`() {
        val readings = listOf(
            reading("five thousand a month", "m1", MARCH),
            reading("5000 per month", "m2", JUNE),
            reading("5k monthly", "m3", SEPTEMBER),
        )

        val resolved = resolved(
            readings,
            listOf(
                synonym("five thousand a month", "5000 per month"),
                synonym("5000 per month", "5k monthly"),
            ),
        )

        val fact = assertNotNull(resolved.singleOrNull(), "three wordings of one fact are one fact: $resolved")
        assertEquals("five thousand a month", fact.value)
        assertEquals(3, fact.evidence.size)
    }

    /**
     * A reworded value that really was contradicted keeps the contradiction.
     *
     * The collapse must not swallow a change: two wordings of the old rate and then a new one is
     * one window closed by another, not one fact.
     */
    @Test
    fun `collapsing two wordings still leaves a later contradiction standing`() {
        val readings = listOf(
            reading("five thousand a month", "m1", MARCH),
            reading("5000 per month", "m2", APRIL),
            reading("6000 per month", "m3", JUNE),
        )

        val resolved = resolved(readings, listOf(synonym("five thousand a month", "5000 per month")))

        assertEquals(2, resolved.size)
        val earlier = resolved.single { it.value == "five thousand a month" }
        assertEquals(MARCH, earlier.validity.from)
        assertEquals(JUNE, earlier.validity.until, "the revision still closes it")
        assertEquals(2, earlier.evidence.size, "and it carries both wordings' evidence")
    }

    /**
     * Idempotence is the property the pass relies on: a sweep that re-derives a subject writes what
     * is already stored. A canonicalisation that moved the value on every run would rewrite the
     * derived layer at every sweep.
     */
    @Test
    fun `re-resolving a collapsed fact returns it unchanged`() {
        val synonyms = listOf(synonym("five thousand a month", "5000 per month"))
        val once = resolved(
            listOf(
                reading("five thousand a month", "m1", MARCH),
                reading("5000 per month", "m2", JUNE),
            ),
            synonyms,
        )

        assertEquals(once, resolved(once, synonyms))
    }

    private fun synonym(one: String, other: String) = ValueSynonym(
        subject = SUBJECT,
        predicate = Predicate.of("charges"),
        one = one,
        other = other,
    )

    private fun reading(
        value: String,
        message: String,
        at: Instant,
        origin: Origin = Origin.INFERRED,
    ) = Statement(
        subject = SUBJECT,
        predicate = Predicate.of("charges"),
        value = value,
        validity = Validity(from = at),
        origin = origin,
        evidence = setOf(
            Evidence(
                span = SpanId(kind = RecordKind.MESSAGE, record = ExternalId(Source.TELEGRAM, message)),
                sourceTime = at,
            ),
        ),
    )

    private companion object {
        val SUBJECT = EntityId.mint()
        val MARCH: Instant = Instant.parse("2026-03-01T00:00:00Z")
        val APRIL: Instant = Instant.parse("2026-04-01T00:00:00Z")
        val JUNE: Instant = Instant.parse("2026-06-01T00:00:00Z")
        val SEPTEMBER: Instant = Instant.parse("2026-09-01T00:00:00Z")
    }
}
