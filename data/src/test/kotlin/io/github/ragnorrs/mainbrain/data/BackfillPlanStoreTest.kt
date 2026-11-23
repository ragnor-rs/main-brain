package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull

/**
 * The per-conversation backfill plan against a real Postgres.
 *
 * **One source, one table, every test in it.** The only `Source` in scope is Telegram, so
 * `outstanding` for Telegram returns units this test wrote alongside units every other test in
 * this class wrote. Nothing can be asserted about the *whole* of that list, and the tests below
 * never try: each mints its own units and then asserts about those — present, absent, or in a
 * given order relative to each other. Written this way on purpose, because the alternative is
 * either a second source invented for testing or an ordering between tests that nothing enforces.
 *
 * Only two assertions are about the list as a list, and both hold whatever else is in the table:
 * that a page of one is a page of one, and that a page is never longer than its limit.
 */
class BackfillPlanStoreTest {

    private val plan = JdbcBackfillPlanRepository(StoreRig.database)

    /** A unit no other test shares, so one test's leftovers cannot satisfy another's assertion. */
    private fun unit(name: String) = WorkUnitId("$name-${counter.incrementAndGet()}")

    private suspend fun outstanding(ceiling: Int = 3, limit: Int = PAGE) =
        plan.outstanding(Source.TELEGRAM, failureCeiling = ceiling, limit = limit)

    private suspend fun units(ceiling: Int = 3) = outstanding(ceiling).map { it.unit }

    @Test
    fun `an enumerated unit is outstanding, unreached and unfinished`(): Unit = runBlocking {
        val unit = unit("newly-enumerated")

        plan.enumerated(Source.TELEGRAM, listOf(unit))

        val item = assertNotNull(plan.planFor(Source.TELEGRAM, unit))
        assertNull(item.reached)
        assertFalse(item.finished)
        assertEquals(0, item.failures)
        assertTrue(unit in units())
    }

    @Test
    fun `a unit never enumerated has no plan`(): Unit = runBlocking {
        assertNull(plan.planFor(Source.TELEGRAM, unit("never-enumerated")))
    }

    /**
     * The property that makes an open work list cheap, and the one whose absence would be worst.
     *
     * Re-enumerating is what an adapter does every pass. Were this an upsert of the defaults, each
     * pass would reset every unit to unwalked, so backfill would re-read all of history forever
     * while every individual report looked correct.
     */
    @Test
    fun `re-enumerating a known unit leaves its progress alone`(): Unit = runBlocking {
        val unit = unit("already-walked")
        val reached = Instant.parse("2024-03-01T00:00:00Z")
        plan.enumerated(Source.TELEGRAM, listOf(unit))
        plan.reportReached(Source.TELEGRAM, unit, reached, finished = false)

        plan.enumerated(Source.TELEGRAM, listOf(unit))

        assertEquals(reached, assertNotNull(plan.planFor(Source.TELEGRAM, unit)).reached)
    }

    @Test
    fun `enumerating reports nothing for a unit that is absent from the list`(): Unit = runBlocking {
        val kept = unit("kept-across-enumerations")
        plan.enumerated(Source.TELEGRAM, listOf(kept))
        plan.reportReached(Source.TELEGRAM, kept, Instant.parse("2024-06-01T00:00:00Z"), finished = false)

        // A conversation missing from one enumeration has not been deleted — a failed page and a
        // lapsed permission both look like this, and losing its progress would re-walk it whole.
        plan.enumerated(Source.TELEGRAM, listOf(unit("some-other-conversation")))

        assertEquals(
            Instant.parse("2024-06-01T00:00:00Z"),
            assertNotNull(plan.planFor(Source.TELEGRAM, kept)).reached,
        )
    }

    @Test
    fun `a walk that reaches further back moves the reported time`(): Unit = runBlocking {
        val unit = unit("walking-backwards")
        plan.reportReached(Source.TELEGRAM, unit, Instant.parse("2025-01-01T00:00:00Z"), finished = false)

        plan.reportReached(Source.TELEGRAM, unit, Instant.parse("2023-01-01T00:00:00Z"), finished = false)

        assertEquals(
            Instant.parse("2023-01-01T00:00:00Z"),
            assertNotNull(plan.planFor(Source.TELEGRAM, unit)).reached,
        )
    }

    /**
     * Ground already captured is not given back by a report that claims less of it.
     *
     * Without this the plan would re-walk a stretch it already holds, and the only evidence would
     * be request budget spent twice — which nothing measures.
     */
    @Test
    fun `a report claiming less progress than is stored is refused`(): Unit = runBlocking {
        val unit = unit("no-going-forward")
        plan.reportReached(Source.TELEGRAM, unit, Instant.parse("2023-01-01T00:00:00Z"), finished = false)

        plan.reportReached(Source.TELEGRAM, unit, Instant.parse("2025-01-01T00:00:00Z"), finished = false)

        assertEquals(
            Instant.parse("2023-01-01T00:00:00Z"),
            assertNotNull(plan.planFor(Source.TELEGRAM, unit)).reached,
        )
    }

    @Test
    fun `a finished unit is no longer outstanding and keeps the time it reached`(): Unit = runBlocking {
        val unit = unit("walked-to-the-beginning")
        val reached = Instant.parse("2019-05-05T00:00:00Z")

        plan.reportReached(Source.TELEGRAM, unit, reached, finished = true)

        val item = assertNotNull(plan.planFor(Source.TELEGRAM, unit))
        assertTrue(item.finished)
        assertEquals(reached, item.reached)
        assertFalse(unit in units())
    }

    @Test
    fun `a failure against an unknown unit is still counted`(): Unit = runBlocking {
        val unit = unit("failed-before-enumeration")

        plan.reportFailure(Source.TELEGRAM, unit, "unreachable")

        assertEquals(1, assertNotNull(plan.planFor(Source.TELEGRAM, unit)).failures)
    }

    @Test
    fun `failures accumulate, and a unit at the ceiling is skipped rather than served`(): Unit = runBlocking {
        val unit = unit("keeps-failing")
        repeat(3) { plan.reportFailure(Source.TELEGRAM, unit, "still unreachable") }

        assertEquals(3, assertNotNull(plan.planFor(Source.TELEGRAM, unit)).failures)
        // Skipped, not removed: still readable above, and absent from the work list below.
        assertFalse(unit in units(ceiling = 3))
        // One short of the ceiling and it is served again, so the boundary is the ceiling itself.
        assertTrue(unit in units(ceiling = 4))
    }

    @Test
    fun `a success clears the failures a unit had accumulated`(): Unit = runBlocking {
        val unit = unit("recovered")
        repeat(2) { plan.reportFailure(Source.TELEGRAM, unit, "briefly unreachable") }

        plan.reportReached(Source.TELEGRAM, unit, Instant.parse("2024-01-01T00:00:00Z"), finished = false)

        assertEquals(0, assertNotNull(plan.planFor(Source.TELEGRAM, unit)).failures)
    }

    @Test
    fun `reopening a unit undoes its progress and keeps its row`(): Unit = runBlocking {
        val unit = unit("asked-for-again")
        plan.reportReached(Source.TELEGRAM, unit, Instant.parse("2020-01-01T00:00:00Z"), finished = true)

        plan.reopen(Source.TELEGRAM, unit)

        val item = assertNotNull(plan.planFor(Source.TELEGRAM, unit))
        assertNull(item.reached)
        assertFalse(item.finished)
        assertTrue(unit in units())
    }

    @Test
    fun `reopening a unit brings a skipped one back`(): Unit = runBlocking {
        val unit = unit("retired-then-retried")
        repeat(3) { plan.reportFailure(Source.TELEGRAM, unit, "unreachable") }

        plan.reopen(Source.TELEGRAM, unit)

        assertEquals(0, assertNotNull(plan.planFor(Source.TELEGRAM, unit)).failures)
        assertTrue(unit in units(ceiling = 3))
    }

    @Test
    fun `reopening a unit that was never enumerated is not an error`(): Unit = runBlocking {
        plan.reopen(Source.TELEGRAM, unit("nothing-to-reopen"))
    }

    @Test
    fun `reopening the whole source undoes every unit in it`(): Unit = runBlocking {
        val first = unit("source-wide-one")
        val second = unit("source-wide-two")
        plan.reportReached(Source.TELEGRAM, first, Instant.parse("2021-01-01T00:00:00Z"), finished = true)
        plan.reportReached(Source.TELEGRAM, second, Instant.parse("2022-01-01T00:00:00Z"), finished = true)

        plan.reopen(Source.TELEGRAM)

        listOf(first, second).forEach { unit ->
            val item = assertNotNull(plan.planFor(Source.TELEGRAM, unit))
            assertNull(item.reached)
            assertFalse(item.finished)
        }
    }

    /**
     * Least-failed first, and within that the longest-known first.
     *
     * The second half is what stops an open work list from starving its own backlog: ordering by
     * failure count alone would let every newly enumerated conversation overtake the ones already
     * waiting. Each unit is enumerated in its own call because `now()` is the transaction's time,
     * so units inserted together are indistinguishable by age.
     */
    @Test
    fun `the work list serves the least-failed and longest-known first`(): Unit = runBlocking {
        val oldest = unit("waiting-longest")
        val middle = unit("waiting-less")
        val newest = unit("just-arrived")
        plan.enumerated(Source.TELEGRAM, listOf(oldest))
        plan.enumerated(Source.TELEGRAM, listOf(middle))
        plan.enumerated(Source.TELEGRAM, listOf(newest))
        // The oldest has failed once, so it sorts behind two units that never have despite being
        // the one that has waited longest. Both halves of the ordering are therefore in play.
        plan.reportFailure(Source.TELEGRAM, oldest, "once")

        val mine = outstanding().map { it.unit }.filter { it in setOf(oldest, middle, newest) }

        assertEquals(listOf(middle, newest, oldest), mine)
    }

    @Test
    fun `a page is never longer than the limit it was asked for`(): Unit = runBlocking {
        plan.enumerated(Source.TELEGRAM, listOf(unit("page-filler-one"), unit("page-filler-two")))

        assertEquals(1, outstanding(limit = 1).size)
    }

    @Test
    fun `an enumeration of nothing is not a write`(): Unit = runBlocking {
        // The guard exists so a source reporting an empty list does not reach the driver with a
        // batch of no statements, which is a different failure depending on the driver.
        plan.enumerated(Source.TELEGRAM, emptyList())
    }

    @Test
    fun `a unit enumerated twice in one call is written once`(): Unit = runBlocking {
        val unit = unit("named-twice")

        plan.enumerated(Source.TELEGRAM, listOf(unit, unit))

        assertEquals(1, outstanding().count { it.unit == unit })
    }

    @Test
    fun `an enumerated unit has no front edge until one is reported`(): Unit = runBlocking {
        val unit = unit("front-unreported")

        plan.enumerated(Source.TELEGRAM, listOf(unit))

        assertNull(plan.planFor(Source.TELEGRAM, unit)!!.newest)
    }

    @Test
    fun `a front edge further forward moves the reported time`(): Unit = runBlocking {
        val unit = unit("front-advancing")
        plan.enumerated(Source.TELEGRAM, listOf(unit))

        plan.reportFront(Source.TELEGRAM, unit, Instant.parse("2026-03-01T00:00:00Z"))
        plan.reportFront(Source.TELEGRAM, unit, Instant.parse("2026-04-01T00:00:00Z"))

        assertEquals(Instant.parse("2026-04-01T00:00:00Z"), plan.planFor(Source.TELEGRAM, unit)!!.newest)
    }

    /**
     * The mirror of the rule that progress only goes back, and it fails the same way if dropped.
     *
     * An edge that could move backwards would re-open a stretch a catch-up had already closed, so
     * a restart would pay for pages it has read — and in the other direction a report naming an
     * older time is claiming less completeness than the corpus has.
     */
    @Test
    fun `a front edge claiming less completeness than is stored is refused`(): Unit = runBlocking {
        val unit = unit("front-receding")
        val stored = Instant.parse("2026-04-01T00:00:00Z")
        plan.enumerated(Source.TELEGRAM, listOf(unit))
        plan.reportFront(Source.TELEGRAM, unit, stored)

        plan.reportFront(Source.TELEGRAM, unit, Instant.parse("2026-03-01T00:00:00Z"))

        assertEquals(stored, plan.planFor(Source.TELEGRAM, unit)!!.newest)
    }

    @Test
    fun `a front edge is recorded for a unit nothing has enumerated`(): Unit = runBlocking {
        val unit = unit("front-before-enumeration")
        val newest = Instant.parse("2026-04-01T00:00:00Z")

        plan.reportFront(Source.TELEGRAM, unit, newest)

        assertEquals(newest, plan.planFor(Source.TELEGRAM, unit)!!.newest)
    }

    /**
     * A front report is not an attempt at a unit's history, so it clears no failures.
     *
     * The count is what retires an unreachable conversation from the walk. Clearing it here would
     * retire that decision on the evidence of a message arriving in the chat, which says nothing
     * about whether its history can be read.
     */
    @Test
    fun `a front edge leaves the failure count alone`(): Unit = runBlocking {
        val unit = unit("front-beside-failures")
        plan.enumerated(Source.TELEGRAM, listOf(unit))
        plan.reportFailure(Source.TELEGRAM, unit, "unreachable")

        plan.reportFront(Source.TELEGRAM, unit, Instant.parse("2026-04-01T00:00:00Z"))

        assertEquals(1, plan.planFor(Source.TELEGRAM, unit)!!.failures)
    }

    /** The edge survives a reopen, which is what keeps a re-read about the past alone. */
    @Test
    fun `reopening a unit keeps the front edge it had`(): Unit = runBlocking {
        val unit = unit("front-through-reopen")
        val newest = Instant.parse("2026-04-01T00:00:00Z")
        plan.enumerated(Source.TELEGRAM, listOf(unit))
        plan.reportFront(Source.TELEGRAM, unit, newest)

        plan.reopen(Source.TELEGRAM, unit)

        assertEquals(newest, plan.planFor(Source.TELEGRAM, unit)!!.newest)
    }

    @Test
    fun `a whole enumeration is read back at once, omitting what the plan never held`(): Unit = runBlocking {
        val known = unit("asked-about-known")
        val other = unit("asked-about-other")
        val stranger = unit("asked-about-stranger")
        plan.enumerated(Source.TELEGRAM, listOf(known, other))

        val read = plan.plansFor(Source.TELEGRAM, listOf(known, stranger, other))

        assertEquals(setOf(known, other), read.map { it.unit }.toSet())
    }

    @Test
    fun `asking about no units is not a query`(): Unit = runBlocking {
        // The guard exists so an empty list does not reach the driver as an empty array parameter,
        // for the same reason an enumeration of nothing is not a write.
        assertTrue(plan.plansFor(Source.TELEGRAM, emptyList()).isEmpty())
    }

    private companion object {
        /** Large enough that a unit this test wrote is on the first page beside every other's. */
        const val PAGE = 10_000

        val counter = AtomicInteger()
    }
}
