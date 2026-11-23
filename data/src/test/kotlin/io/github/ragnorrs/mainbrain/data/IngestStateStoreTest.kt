package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.FailureKind
import io.github.ragnorrs.mainbrain.domain.Pass
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertNotNull

/**
 * Per-source ingestion state against a real Postgres: the three reported things, kept apart.
 *
 * **These tests share one row.** The table is keyed by source and one source is in scope, so the
 * isolation the corpus tests get from minting a fresh identifier per test is not available here
 * — there is nothing to mint. What keeps them mostly independent is that each report owns its own
 * columns and each test reads only the columns it wrote, which is also the property the table
 * exists to have: three reports that do not overwrite each other.
 *
 * **Two assertions depend on order rather than on construction, and are written where that
 * dependency is visible.** Reading backfill and health as unreported is only possible before
 * anything reports them, so each sits at the top of the test that writes those columns — the
 * backfill one on its first line, the health one after a freshness report, because that
 * assertion is only worth making once some report has created the row. No test in this module
 * other than those two calls `reportBackfill` or `recordPass` for this source, and the row is
 * shared across every store test in the module, so a test added anywhere in `:data` that calls
 * either before them would make them fail while naming the wrong defect. Nothing mechanical
 * prevents that, which is why it is written here rather than assumed.
 */
class IngestStateStoreTest {

    private val state = JdbcIngestStateRepository(StoreRig.database)

    @Test
    fun `freshness round-trips, the units behind it and all`(): Unit = runBlocking {
        val completeThrough = Instant.parse("2026-09-29T18:42:00Z")
        val lagging = listOf(WorkUnitId("a-supergroup-topic"), WorkUnitId("a-direct-chat"))

        state.reportFreshness(Source.TELEGRAM, completeThrough, lagging)

        val freshness = assertNotNull(state.freshnessOf(Source.TELEGRAM))
        assertEquals(completeThrough, freshness.completeThrough)
        assertEquals(lagging, freshness.lagging)
    }

    @Test
    fun `a later report replaces the units that were lagging rather than accumulating them`(): Unit = runBlocking {
        state.reportFreshness(Source.TELEGRAM, null, listOf(WorkUnitId("one-that-caught-up")))
        state.reportFreshness(Source.TELEGRAM, null, emptyList())

        // Current state, not history. A unit that has caught up is not a claim being withdrawn —
        // the no-withdrawal rule is about what a source said, and this row is about how the
        // reading is going.
        assertEquals(emptyList<WorkUnitId>(), assertNotNull(state.freshnessOf(Source.TELEGRAM)).lagging)
    }

    @Test
    fun `backfill reads as nothing until an enumeration reports, and then carries when it did`(): Unit = runBlocking {
        // Whatever else is in the row, backfill is unreported until it is reported: not complete
        // and never enumerated are different states, and only the second is a measurement.
        assertNull(state.backfillOf(Source.TELEGRAM))

        val reached = Instant.parse("2019-01-01T00:00:00Z")
        state.reportBackfill(Source.TELEGRAM, complete = false, reached = reached)

        val progress = assertNotNull(state.backfillOf(Source.TELEGRAM))
        assertFalse(progress.complete)
        assertEquals(reached, progress.reached)

        state.reportBackfill(Source.TELEGRAM, complete = true, reached = reached)
        val completed = assertNotNull(state.backfillOf(Source.TELEGRAM))
        assertTrue(completed.complete)
        assertTrue(completed.enumeratedAt >= progress.enumeratedAt) {
            "Each report stamps its own enumeration, so completeness is readable as of a time."
        }
    }

    @Test
    fun `a lagging unit that is not a string fails the read rather than vanishing from it`(): Unit = runBlocking {
        // Written by hand, because nothing in this build can write such an element — the binder
        // takes a list of strings. That is the point: the strictness guards hand-edited rows and
        // whatever a later migration does, and a unit silently missing from the report is a
        // source reading as less behind than it is.
        state.reportFreshness(Source.TELEGRAM, null, listOf(WorkUnitId("a-topic")))
        StoreRig.database.query { connection ->
            connection.prepareStatement(
                "UPDATE ingest_state SET lagging_units = ARRAY['a-topic', NULL]::text[] WHERE source = ?",
            ).use { statement ->
                statement.setString(1, Source.TELEGRAM.name)
                statement.executeUpdate()
            }
        }

        assertNotNull(runCatching { state.freshnessOf(Source.TELEGRAM) }.exceptionOrNull()) {
            "A null element read back as a shorter list would under-report what is behind."
        }

        // Left as the other tests expect to find it, since the row is shared.
        state.reportFreshness(Source.TELEGRAM, null, emptyList())
    }

    @Test
    fun `health keeps the last success and the last failure apart, and a person-blocking failure stands`(): Unit =
        runBlocking {
            // A report that is not a pass is not health. The row already exists by now — any of
            // the three reports creates it — so finding one says nothing about whether a pass was
            // ever recorded, and "nothing is wrong" must not stand in for "nothing is known".
            state.reportFreshness(Source.TELEGRAM, null, emptyList())
            assertNull(state.healthOf(Source.TELEGRAM))

            state.recordPass(Source.TELEGRAM, Pass.Failed(FailureKind.NEEDS_A_PERSON, "the session needs signing in"))

            val blocked = assertNotNull(state.healthOf(Source.TELEGRAM))
            assertEquals(FailureKind.NEEDS_A_PERSON, blocked.lastFailureKind)
            assertEquals("the session needs signing in", blocked.lastFailureNote)
            assertTrue(blocked.blockedOnAPerson) {
                "Re-authentication is cleared by a person on the machine, and nobody clears it without being told."
            }

            state.recordPass(Source.TELEGRAM, Pass.Succeeded)

            val cleared = assertNotNull(state.healthOf(Source.TELEGRAM))
            assertFalse(cleared.blockedOnAPerson) { "A pass that went through is what clears it." }
            // The failure itself is still readable: a success does not erase what went wrong,
            // only the conclusion that someone has to act on it.
            assertEquals(FailureKind.NEEDS_A_PERSON, cleared.lastFailureKind)
            assertNotNull(cleared.lastFailureAt)
            // Strictly after, not at-or-after: equal times count as blocked by the rule above,
            // so asserting only `>=` would admit the state the line above asserts against.
            assertTrue(assertNotNull(cleared.lastSuccessAt).isAfter(assertNotNull(cleared.lastFailureAt))) {
                "Both times come from the store's own clock in separate statements, so the " +
                    "success is strictly later and the two can be ordered."
            }
        }
}
