package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.BackfillProgress
import io.github.ragnorrs.mainbrain.domain.FailureKind
import io.github.ragnorrs.mainbrain.domain.Freshness
import io.github.ragnorrs.mainbrain.domain.Health
import io.github.ragnorrs.mainbrain.domain.IngestStateRepository
import io.github.ragnorrs.mainbrain.domain.Pass
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

/**
 * The one state row per source, in Postgres.
 *
 * Each write is a single statement, so each goes through `query` rather than `transaction`:
 * there is nothing here whose halves could land separately. Three statements rather than one
 * because the three groups of columns are reported by different callers at different moments,
 * and a single write would make every report overwrite the other two with whatever its caller
 * happened to know.
 */
class JdbcIngestStateRepository(private val database: Database) : IngestStateRepository {

    override suspend fun reportFreshness(source: Source, completeThrough: Instant?, lagging: List<WorkUnitId>) {
        database.query { connection ->
            connection.prepareStatement(FRESHNESS).use { statement ->
                statement.bind {
                    string(source.name)
                    instant(completeThrough)
                    strings(lagging.map { it.value })
                }
                statement.executeUpdate()
            }
        }
    }

    override suspend fun reportBackfill(source: Source, complete: Boolean, reached: Instant?) {
        database.query { connection ->
            connection.prepareStatement(BACKFILL).use { statement ->
                statement.bind {
                    string(source.name)
                    boolean(complete)
                    instant(reached)
                }
                statement.executeUpdate()
            }
        }
    }

    /**
     * The store stamps the time, as it does for every observation it makes itself.
     *
     * A caller-supplied one would be a clock this process does not own, and the useful ordering
     * -- which of success and failure came last -- is only sound if both came from the same one.
     */
    override suspend fun recordPass(source: Source, pass: Pass) {
        database.query { connection ->
            when (pass) {
                Pass.Succeeded -> connection.prepareStatement(SUCCESS).use { statement ->
                    statement.bind { string(source.name) }
                    statement.executeUpdate()
                }

                is Pass.Failed -> connection.prepareStatement(FAILURE).use { statement ->
                    statement.bind {
                        string(source.name)
                        string(pass.kind.name)
                        string(pass.note)
                    }
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun freshnessOf(source: Source): Freshness? = database.query { connection ->
        connection.readRow(READ_FRESHNESS, source) { rows ->
            Freshness(completeThrough = rows.instant("complete_through"), lagging = rows.workUnits("lagging_units"))
        }
    }

    /**
     * `null` until a pass has been recorded, which both times being absent is what establishes.
     *
     * The guard is needed because the three groups share one row: whichever report arrives first
     * creates it, so finding a row says nothing about whether a pass was ever recorded. Without
     * this, a source that has only reported freshness reads back as `Health(null, null, null)` —
     * whose `blockedOnAPerson` is `false`, which is *nothing is wrong* standing in for *nothing
     * is known*. Every `recordPass` stamps one of the two times, so the predicate is exact.
     */
    override suspend fun healthOf(source: Source): Health? = database.query { connection ->
        connection.readRow(READ_HEALTH, source) { rows ->
            val lastSuccessAt = rows.instant("last_success_at")
            val lastFailureAt = rows.instant("last_failure_at")
            if (lastSuccessAt == null && lastFailureAt == null) {
                null
            } else {
                Health(
                    lastSuccessAt = lastSuccessAt,
                    lastFailureAt = lastFailureAt,
                    lastFailureKind = rows.getString("last_failure_kind")?.let { FailureKind.valueOf(it) },
                    lastFailureNote = rows.getString("last_failure_note"),
                )
            }
        }
    }

    /**
     * `null` when no enumeration has reported, which the time is what distinguishes.
     *
     * Reading the completion flag alone could not: an adapter that has never enumerated and one
     * that enumerated and found work outstanding both read as not complete, and only the second
     * is a measurement.
     */
    override suspend fun backfillOf(source: Source): BackfillProgress? = database.query { connection ->
        connection.readRow(READ_BACKFILL, source) { rows ->
            rows.instant("backfill_enumerated_at")?.let { enumeratedAt ->
                BackfillProgress(
                    complete = rows.getBoolean("backfill_complete"),
                    reached = rows.instant("backfill_reached"),
                    enumeratedAt = enumeratedAt,
                )
            }
        }
    }

    /** The state row for one source, mapped by [row], or `null` when the source has none. */
    private fun <T> Connection.readRow(sql: String, source: Source, row: (ResultSet) -> T?): T? =
        prepareStatement(sql).use { statement ->
            statement.bind { string(source.name) }
            statement.executeQuery().use { rows -> if (rows.next()) row(rows) else null }
        }

    private companion object {
        val FRESHNESS = """
            INSERT INTO ingest_state (source, complete_through, lagging_units)
            VALUES (?, ?, ?)
            ON CONFLICT (source) DO UPDATE SET
                complete_through = EXCLUDED.complete_through,
                lagging_units = EXCLUDED.lagging_units
        """.trimIndent()

        val BACKFILL = """
            INSERT INTO ingest_state (source, backfill_complete, backfill_reached, backfill_enumerated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (source) DO UPDATE SET
                backfill_complete = EXCLUDED.backfill_complete,
                backfill_reached = EXCLUDED.backfill_reached,
                backfill_enumerated_at = now()
        """.trimIndent()

        val SUCCESS = """
            INSERT INTO ingest_state (source, last_success_at)
            VALUES (?, now())
            ON CONFLICT (source) DO UPDATE SET last_success_at = now()
        """.trimIndent()

        val FAILURE = """
            INSERT INTO ingest_state (source, last_failure_at, last_failure_kind, last_failure_note)
            VALUES (?, now(), ?, ?)
            ON CONFLICT (source) DO UPDATE SET
                last_failure_at = now(),
                last_failure_kind = EXCLUDED.last_failure_kind,
                last_failure_note = EXCLUDED.last_failure_note
        """.trimIndent()

        const val READ_FRESHNESS = "SELECT complete_through, lagging_units FROM ingest_state WHERE source = ?"

        val READ_HEALTH = """
            SELECT last_success_at, last_failure_at, last_failure_kind, last_failure_note
            FROM ingest_state
            WHERE source = ?
        """.trimIndent()

        val READ_BACKFILL = """
            SELECT backfill_complete, backfill_reached, backfill_enumerated_at
            FROM ingest_state
            WHERE source = ?
        """.trimIndent()
    }
}

/**
 * A `text[]` column as work units.
 *
 * Nothing here is lenient, and that is the point. A unit silently missing from the report is a
 * source reading as less behind than it is, and a re-read scoped to fewer units than were
 * reported is the one outcome the port asking for one must never produce — so an element that is
 * not a string fails rather than being dropped, and a column that is not an array of them fails
 * rather than reading back as nothing lagging. A checked cast on the column would have been the
 * larger version of the same defect: it reports zero units for a whole column instead of losing
 * one of them.
 *
 * A genuinely absent array is still empty rather than an error: `NULL` is what a row written
 * before this column existed holds. Nothing in this build can write a bad element —
 * [Binder.strings] takes a list of strings — so the strictness guards hand-edited rows and
 * whatever a later migration does. The element half of it is asserted by a store test that
 * writes such a row by hand; the column half is not, and cannot be from outside this file,
 * because the driver answers a `text[]` with a string array or throws on its own.
 */
private fun ResultSet.workUnits(column: String): List<WorkUnitId> {
    val array = getArray(column) ?: return emptyList()
    return (array.array as Array<*>).map { WorkUnitId(it as String) }
}
