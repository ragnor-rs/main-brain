package io.github.ragnorrs.mainbrain.ingest

import io.github.ragnorrs.mainbrain.domain.BackfillPlanRepository
import io.github.ragnorrs.mainbrain.domain.WorkItem
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import java.time.Instant

/**
 * The backfill plan in a map, keeping the promises an adapter depends on.
 *
 * A fake and not a log, unlike the corpus fixtures beside it, because an adapter *reads this back*
 * — it asks what is outstanding and where a unit had got to, and a fixture that answered nothing
 * would exercise none of the resuming, skipping or re-reading this port exists for.
 *
 * The promises the adapter's correctness rests on are modelled rather than simplified away:
 * registering a known unit disturbs nothing, progress only ever goes back, the front edge only
 * ever goes forward, a reopen keeps that edge, and a unit at the failure ceiling is not offered.
 * That they hold of the actual store is asserted in `:data`, against Postgres; what is asserted
 * here is that the adapter behaves correctly *given* them.
 *
 * The two directions are modelled rather than simplified into one assignment on purpose. A fake
 * that took either report verbatim would agree with an adapter that walked a gap backwards past
 * its own edge, or reported a back edge that gave up captured ground — the two mistakes these
 * columns exist to refuse.
 *
 * `firstSeenAt` is a counter rather than a clock. The ordering it feeds is what stops a newly
 * enumerated conversation jumping the backlog, and a counter makes that ordering exact instead of
 * dependent on how fast a test runs.
 */
internal class PlanInMemory : BackfillPlanRepository {

    private val rows = linkedMapOf<Pair<Source, WorkUnitId>, WorkItem>()

    private var enumerations = 0L

    /**
     * Thrown by the next failure report, once.
     *
     * The plan and the corpus are the same Postgres, so a write here can be refused like any
     * other — and this is the one write an adapter makes *after* it has decided what to hand
     * over, which makes it the way to drive a pass that reads the source and then dies.
     */
    var faultOnReportFailure: Exception? = null

    /**
     * Thrown by the next bulk read, once.
     *
     * The read a start-up comparison is made from. A store that refuses it is the way to drive the
     * case where the comparison did not happen, which must not be mistaken for one that found
     * nothing.
     */
    var faultOnPlansFor: Exception? = null

    override suspend fun enumerated(source: Source, units: List<WorkUnitId>) {
        units.distinct().forEach { unit ->
            rows.getOrPut(source to unit) {
                WorkItem(
                    unit = unit,
                    reached = null,
                    newest = null,
                    finished = false,
                    failures = 0,
                    firstSeenAt = Instant.EPOCH.plusSeconds(enumerations++),
                )
            }
        }
    }

    override suspend fun reportReached(source: Source, unit: WorkUnitId, reached: Instant?, finished: Boolean) {
        val existing = rows[source to unit]
        val before = existing?.reached
        rows[source to unit] = WorkItem(
            unit = unit,
            // The store's `LEAST`, which ignores nulls: a report with no time in it claims no new
            // ground and leaves what is there, and the first report sets the column outright.
            reached = listOfNotNull(before, reached).minOrNull(),
            newest = existing?.newest,
            finished = finished,
            failures = 0,
            firstSeenAt = existing?.firstSeenAt ?: Instant.EPOCH.plusSeconds(enumerations++),
        )
    }

    /**
     * The store's `GREATEST`, and the failure count deliberately untouched.
     *
     * Both halves are promises the adapter leans on. An edge that could move backwards would let
     * a catch-up re-open a stretch it had already closed, and a front report that cleared failures
     * would retire a conversation's unreachability on the evidence of a message arriving in it.
     */
    override suspend fun reportFront(source: Source, unit: WorkUnitId, newest: Instant) {
        val existing = rows[source to unit]
        rows[source to unit] = WorkItem(
            unit = unit,
            reached = existing?.reached,
            newest = listOfNotNull(existing?.newest, newest).maxOrNull(),
            finished = existing?.finished ?: false,
            failures = existing?.failures ?: 0,
            firstSeenAt = existing?.firstSeenAt ?: Instant.EPOCH.plusSeconds(enumerations++),
        )
    }

    override suspend fun reportFailure(source: Source, unit: WorkUnitId, note: String?) {
        faultOnReportFailure?.let {
            faultOnReportFailure = null
            throw it
        }
        val existing = rows[source to unit]
        rows[source to unit] = WorkItem(
            unit = unit,
            reached = existing?.reached,
            newest = existing?.newest,
            finished = existing?.finished ?: false,
            failures = (existing?.failures ?: 0) + 1,
            firstSeenAt = existing?.firstSeenAt ?: Instant.EPOCH.plusSeconds(enumerations++),
        )
    }

    override suspend fun outstanding(source: Source, failureCeiling: Int, limit: Int): List<WorkItem> = rows
        .filterKeys { (rowSource, _) -> rowSource == source }
        .values
        .filter { !it.finished && it.failures < failureCeiling }
        .sortedWith(compareBy({ it.failures }, { it.firstSeenAt }, { it.unit.value }))
        .take(limit)

    override suspend fun planFor(source: Source, unit: WorkUnitId): WorkItem? = rows[source to unit]

    override suspend fun plansFor(source: Source, units: List<WorkUnitId>): List<WorkItem> {
        faultOnPlansFor?.let {
            faultOnPlansFor = null
            throw it
        }
        return units.distinct().mapNotNull { rows[source to it] }
    }

    /** The front edge survives, which is the port's promise and the one a catch-up relies on. */
    override suspend fun reopen(source: Source, unit: WorkUnitId?) {
        rows.keys
            .filter { (rowSource, rowUnit) -> rowSource == source && (unit == null || rowUnit == unit) }
            .forEach { key ->
                rows[key] = rows.getValue(key).copy(reached = null, finished = false, failures = 0)
            }
    }
}
