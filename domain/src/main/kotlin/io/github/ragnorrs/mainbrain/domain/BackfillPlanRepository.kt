package io.github.ragnorrs.mainbrain.domain

import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import java.time.Instant

/**
 * How far each conversation has been read in both directions, and which ones are still worth
 * asking about.
 *
 * Per-source operational state like [IngestStateRepository], and kept apart from it for the
 * reason that port gives for its own three groups: these are written at a different moment by a
 * different caller. [IngestStateRepository.reportBackfill] answers *is this source finished* in
 * one row; this answers *where has each conversation got to* in one row per conversation, and
 * the first is a summary a reader acts on while the second is the plan the adapter works from.
 * A single table would make every progress report rewrite the summary, and the summary is read
 * by the backend while the plan is not.
 *
 * **Why the plan is here rather than inside the client library.** The library owns the
 * incremental position, and nothing in this build keeps a second copy of it. The plan is a
 * different fact: how far a conversation has been read in either direction is something no
 * protocol sequence number says, and holding it where the corpus is held is what gives the two
 * one restore point. A plan inside a library's own store would restore separately from the records
 * it describes, and an adapter would believe itself further ahead than the corpus is — which is
 * what the front edge makes measurable, by standing against what the source offers rather than
 * against anything the library remembers.
 *
 * **The work list is open.** It is not a queue that drains. New conversations appear, so an
 * adapter re-enumerates, and [enumerated] adds what is new without disturbing what is known —
 * finished as of the last enumeration is the only kind of finished there is.
 *
 * As everywhere, every function suspends: the implementation reaches Postgres through the one
 * seam that hops to the I/O dispatcher.
 */
interface BackfillPlanRepository {

    /**
     * Register the work list as it now stands, adding units not seen before.
     *
     * Known units are left exactly as they are — their progress, their failures and their
     * finished flag all survive. That is what makes re-enumerating cheap enough to do on every
     * pass, which is what an open work list needs: an adapter that had to diff the list itself
     * would be keeping a second copy of it to diff against.
     *
     * A unit absent from [units] is **not** forgotten. A conversation can vanish from an
     * enumeration for reasons that are not deletion — a failed page, a permission that lapsed —
     * and dropping its progress on that evidence would re-walk its whole history the next time
     * it reappeared.
     */
    suspend fun enumerated(source: Source, units: List<WorkUnitId>)

    /**
     * Report how far forward one unit's records are complete, which is its front edge.
     *
     * A **source** time, like every other progress this port holds: the newest message of that
     * conversation the corpus has been offered, not when it was offered. It is the half
     * [reportReached] cannot express — that one records how far back a walk has got, and a unit
     * finished at the back can still fall behind at the front while the process is not running.
     *
     * **Only forward**, for the mirror of the reason progress only goes back: a report naming an
     * older time than the one stored claims less completeness than was already reached, and
     * acting on it would re-read material that is already here. The stored time stays.
     *
     * Written on acknowledgement like the rest of the plan, so a crash before then leaves this
     * edge behind the corpus rather than ahead of it. Behind costs a re-read of material that
     * upserts onto itself; ahead is a hole nothing would ever look for again.
     */
    suspend fun reportFront(source: Source, unit: WorkUnitId, newest: Instant)

    /**
     * Report how far back one unit has been walked, and whether it is done.
     *
     * [reached] is a **source** time, as it is everywhere ingestion reports one: it is the point
     * in that conversation's own history the walk has got back to, not when the walking
     * happened. `null` means nothing has been walked back to yet.
     *
     * **Progress only goes back.** A report naming a more recent time than the one already stored
     * is claiming less progress than was already made, and it is refused rather than written —
     * the stored time stays. An adapter cannot give ground back by reporting it, which leaves
     * [reopen] as the one way to undo progress, which is what a re-read is for.
     *
     * A successful report clears the failure count. A unit that failed twice and then succeeded
     * is not two-thirds of the way to being abandoned, and leaving the count standing would
     * retire a conversation that works for having once been unreachable.
     */
    suspend fun reportReached(source: Source, unit: WorkUnitId, reached: Instant?, finished: Boolean)

    /**
     * Count one failed attempt at a unit, keeping [note] for whoever has to read it.
     *
     * Counting rather than flagging, because the decision this feeds is not the store's to make:
     * how many failures retire a conversation is configuration, and [outstanding] takes it as a
     * parameter rather than reading a constant from here.
     */
    suspend fun reportFailure(source: Source, unit: WorkUnitId, note: String? = null)

    /**
     * The units still worth asking about: unfinished, and under [failureCeiling] failures.
     *
     * The ceiling is the caller's rather than this port's, because *a work item that fails every
     * pass is marked and skipped* is a policy with a number in it, and the number belongs with
     * the rest of the adapter's configuration. At or above it a unit is skipped and stays in the
     * table — skipped is not removed, so it is still readable, and a later [reopen] brings it
     * back.
     *
     * Ordered least-failed first and, within that, longest-known first. Both halves matter: a
     * conversation that has never failed should not wait behind one that keeps failing, and
     * ordering only by failure count would let a newly enumerated conversation jump ahead of the
     * backlog forever, which is how a work list that is open starves the units it started with.
     *
     * [limit] bounds one pass rather than the plan. An adapter spends what request budget it has
     * left on history, so how much of the list it takes is a decision it makes each time.
     */
    suspend fun outstanding(source: Source, failureCeiling: Int, limit: Int): List<WorkItem>

    /** One unit's place in the plan, or `null` if it has never been enumerated. */
    suspend fun planFor(source: Source, unit: WorkUnitId): WorkItem?

    /**
     * Where each of [units] stands, omitting any this source never enumerated.
     *
     * For the one caller that has to ask about a whole enumeration at once: deciding which
     * conversations fell behind while the process was down is a question about every unit the
     * source just listed, and asking it one unit at a time is a query per conversation on an
     * account of any size.
     */
    suspend fun plansFor(source: Source, units: List<WorkUnitId>): List<WorkItem>

    /**
     * Put a unit back to unwalked, or the whole source when [unit] is `null`.
     *
     * This is what a re-read needs from the plan, and the reason the port asking for one is
     * scoped the same way. It resets progress and the failure count and keeps the row, so a unit
     * that was skipped becomes outstanding again rather than disappearing — nothing here removes
     * anything, and a re-read upserts onto the records already stored rather than replacing
     * them.
     *
     * **The front edge is left where it is.** A re-read reopens a unit's history, which is
     * everything behind that edge; what the corpus holds at the front is not what it questions.
     * Clearing it would also read back as a conversation complete to no time, which is the one
     * state no catch-up can bound — so the unit would lose the measurement that finds a hole in
     * front of it, and gain nothing.
     *
     * Deliberately unable to tell a caller whether the unit existed. A re-read of something
     * never enumerated is a no-op and not an error: the adapter re-enumerates anyway, and the
     * alternative is a caller that has to know what units a source has before it can ask.
     */
    suspend fun reopen(source: Source, unit: WorkUnitId? = null)
}

/**
 * One conversation's place in a backfill plan.
 *
 * Two edges, not one. [reached] is the back: how far into its past the walk has got, which is
 * what backfill owes. [newest] is the front: how far into its present the corpus is complete,
 * which is what an outage costs. A unit can be finished at the back and behind at the front, and
 * that combination is invisible to a plan holding only the first.
 *
 * @property unit the conversation, named as the adapter reports it.
 * @property reached the source time its history has been walked back to, or `null`.
 * @property newest the source time its records are complete up to, or `null` if none have been
 *   reported yet.
 * @property finished whether its history has been walked as far as it goes.
 * @property failures consecutive failed attempts, reset by any success.
 * @property firstSeenAt when this unit first entered the work list.
 */
data class WorkItem(
    val unit: WorkUnitId,
    val reached: Instant?,
    val newest: Instant?,
    val finished: Boolean,
    val failures: Int,
    val firstSeenAt: Instant,
)
