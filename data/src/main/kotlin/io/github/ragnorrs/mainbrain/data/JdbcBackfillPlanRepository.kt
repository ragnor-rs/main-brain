package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.BackfillPlanRepository
import io.github.ragnorrs.mainbrain.domain.WorkItem
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import java.sql.ResultSet
import java.time.Instant

/**
 * One row per conversation per source, in Postgres.
 *
 * Every function here but one is a single statement, so each goes through `query` rather than
 * `transaction`: nothing in this port has halves that could land separately.
 *
 * [enumerated] is the exception and takes a transaction. A JDBC batch is one round trip, which is
 * what makes re-enumerating thousands of conversations cheap enough to do on every pass — but it
 * is still many statements, and under autocommit each commits on its own, so a failure partway
 * leaves part of a work list registered. That is also the rule `query` states on itself: anything
 * writing more than one row's worth of meaning wants `transaction`.
 */
class JdbcBackfillPlanRepository(private val database: Database) : BackfillPlanRepository {

    /**
     * Insert what is new and leave what is known, which `DO NOTHING` is exactly.
     *
     * `DO UPDATE` with the defaults would be the same statement with the opposite meaning: it
     * would reset every unit's progress on every enumeration, so a pass would re-walk all of
     * history and the plan would never finish while appearing to work.
     */
    override suspend fun enumerated(source: Source, units: List<WorkUnitId>) {
        if (units.isEmpty()) return
        database.transaction { connection ->
            connection.prepareStatement(ENUMERATED).use { statement ->
                units.distinct().forEach { unit ->
                    statement.bind {
                        string(source.name)
                        string(unit.value)
                    }
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    /**
     * Progress only goes back, which `LEAST` is what enforces.
     *
     * The column means *how far back this walk has got*, so a report naming a more recent time
     * than the one stored is claiming less progress than was already made. Taking it verbatim
     * would silently discard captured ground and leave the plan re-walking a stretch it already
     * holds; refusing it makes [reopen] the one way to undo progress, which is what a re-read is
     * for. `LEAST` ignores nulls, so the first report sets the column rather than being compared
     * against nothing.
     *
     * The failure count is cleared rather than left standing. A unit that failed twice and then
     * succeeded is not two-thirds retired, and carrying the count would eventually abandon a
     * conversation that works.
     */
    override suspend fun reportReached(source: Source, unit: WorkUnitId, reached: Instant?, finished: Boolean) {
        database.query { connection ->
            connection.prepareStatement(REACHED).use { statement ->
                statement.bind {
                    string(source.name)
                    string(unit.value)
                    instant(reached)
                    boolean(finished)
                }
                statement.executeUpdate()
            }
        }
    }

    /**
     * The front edge only goes forward, which `GREATEST` is what enforces.
     *
     * The mirror of [reportReached]'s `LEAST`, and the asymmetry is the point: that column means
     * *how far back*, so the smaller value is the greater progress, while this one means *how far
     * forward* and the larger value is. A report naming an older time than the one stored claims
     * less completeness than was already reached, and writing it would buy a re-read of material
     * already here. `GREATEST` ignores nulls, so the first report sets the column.
     *
     * No failure count is touched. Reporting a front edge is not an attempt at a unit's history —
     * it says what the corpus now holds, and clearing a walk's failures on it would retire a
     * conversation's unreachability on the evidence of a message arriving in it.
     */
    override suspend fun reportFront(source: Source, unit: WorkUnitId, newest: Instant) {
        database.query { connection ->
            connection.prepareStatement(FRONT).use { statement ->
                statement.bind {
                    string(source.name)
                    string(unit.value)
                    instant(newest)
                }
                statement.executeUpdate()
            }
        }
    }

    /**
     * Counts upward from whatever is there, and inserts at one if the unit is unknown.
     *
     * The insert half matters: an adapter can fail on a conversation it has only just discovered,
     * before anything enumerated it, and an `UPDATE` would record that failure nowhere.
     */
    override suspend fun reportFailure(source: Source, unit: WorkUnitId, note: String?) {
        database.query { connection ->
            connection.prepareStatement(FAILURE).use { statement ->
                statement.bind {
                    string(source.name)
                    string(unit.value)
                    string(note)
                }
                statement.executeUpdate()
            }
        }
    }

    override suspend fun outstanding(source: Source, failureCeiling: Int, limit: Int): List<WorkItem> =
        database.query { connection ->
            connection.prepareStatement(OUTSTANDING).use { statement ->
                statement.bind {
                    string(source.name)
                    int(failureCeiling)
                    int(limit)
                }
                statement.executeQuery().use { rows -> rows.collect { it.workItem() } }
            }
        }

    override suspend fun planFor(source: Source, unit: WorkUnitId): WorkItem? = database.query { connection ->
        connection.prepareStatement(PLAN_FOR).use { statement ->
            statement.bind {
                string(source.name)
                string(unit.value)
            }
            statement.executeQuery().use { rows -> if (rows.next()) rows.workItem() else null }
        }
    }

    /**
     * One statement for a whole enumeration, rather than one per conversation.
     *
     * An array parameter and not an interpolated list: the units are the adapter's own strings,
     * and a query built by concatenation would put them in the statement text. A unit absent from
     * the plan is simply absent from the result, which is what the port promises — there is no row
     * to describe and no caller that wants a placeholder for one.
     */
    override suspend fun plansFor(source: Source, units: List<WorkUnitId>): List<WorkItem> {
        if (units.isEmpty()) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(PLANS_FOR).use { statement ->
                statement.bind {
                    string(source.name)
                    strings(units.map { it.value })
                }
                statement.executeQuery().use { rows -> rows.collect { it.workItem() } }
            }
        }
    }

    /**
     * Two statements rather than one with a nullable parameter.
     *
     * `WHERE unit = ? OR ? IS NULL` would bind the same value twice and read as though the
     * whole-source case were a special case of the scoped one. It is the other way round: these
     * are different instructions, and the whole-source form is the one that must not be reached
     * by accident from a unit that happens to be absent.
     *
     * The row survives either way. A skipped unit becomes outstanding again rather than
     * disappearing, which is what keeps a re-read non-destructive.
     */
    override suspend fun reopen(source: Source, unit: WorkUnitId?) {
        database.query { connection ->
            if (unit == null) {
                connection.prepareStatement(REOPEN_SOURCE).use { statement ->
                    statement.bind { string(source.name) }
                    statement.executeUpdate()
                }
            } else {
                connection.prepareStatement(REOPEN_UNIT).use { statement ->
                    statement.bind {
                        string(source.name)
                        string(unit.value)
                    }
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun ResultSet.workItem() = WorkItem(
        unit = WorkUnitId(getString("unit")),
        reached = instant("reached"),
        newest = instant("newest"),
        finished = getBoolean("finished"),
        failures = getInt("failures"),
        firstSeenAt = requireNotNull(instant("first_seen_at")),
    )

    private companion object {
        val ENUMERATED = """
            INSERT INTO backfill_plan (source, unit)
            VALUES (?, ?)
            ON CONFLICT (source, unit) DO NOTHING
        """.trimIndent()

        val REACHED = """
            INSERT INTO backfill_plan (source, unit, reached, finished)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (source, unit) DO UPDATE SET
                reached = LEAST(backfill_plan.reached, EXCLUDED.reached),
                finished = EXCLUDED.finished,
                failures = 0,
                failure_note = NULL
        """.trimIndent()

        val FRONT = """
            INSERT INTO backfill_plan (source, unit, newest)
            VALUES (?, ?, ?)
            ON CONFLICT (source, unit) DO UPDATE SET
                newest = GREATEST(backfill_plan.newest, EXCLUDED.newest)
        """.trimIndent()

        val FAILURE = """
            INSERT INTO backfill_plan (source, unit, failures, failure_note)
            VALUES (?, ?, 1, ?)
            ON CONFLICT (source, unit) DO UPDATE SET
                failures = backfill_plan.failures + 1,
                failure_note = EXCLUDED.failure_note
        """.trimIndent()

        val OUTSTANDING = """
            SELECT unit, reached, newest, finished, failures, first_seen_at
            FROM backfill_plan
            WHERE source = ? AND NOT finished AND failures < ?
            ORDER BY failures, first_seen_at, unit
            LIMIT ?
        """.trimIndent()

        val PLAN_FOR = """
            SELECT unit, reached, newest, finished, failures, first_seen_at
            FROM backfill_plan
            WHERE source = ? AND unit = ?
        """.trimIndent()

        val PLANS_FOR = """
            SELECT unit, reached, newest, finished, failures, first_seen_at
            FROM backfill_plan
            WHERE source = ? AND unit = ANY(?::text[])
        """.trimIndent()

        val REOPEN_SOURCE = """
            UPDATE backfill_plan
            SET reached = NULL, finished = false, failures = 0, failure_note = NULL
            WHERE source = ?
        """.trimIndent()

        val REOPEN_UNIT = """
            UPDATE backfill_plan
            SET reached = NULL, finished = false, failures = 0, failure_note = NULL
            WHERE source = ? AND unit = ?
        """.trimIndent()
    }
}
