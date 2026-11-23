package io.github.ragnorrs.mainbrain.domain

import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import java.time.Instant

/**
 * What each source reports about itself: freshness, health, and how far backfill has reached.
 *
 * Per-source operational state — neither corpus nor entity. It sits at the domain root rather
 * than in `record` for that reason: none of it is something a source said, it is how the reading
 * of a source is going. Hiding the position costs no visibility, because a protocol sequence
 * number answers none of the three questions below; these are the readable things, and they are
 * kept apart because they are cleared by different people at different times.
 *
 * - [freshnessOf] — an answer about what happens next is confidently wrong if the corpus stops
 *   two days ago. The assistant may answer while ingestion is behind; it may not imply a
 *   currency it lacks.
 * - [healthOf] — re-authentication is cleared by a person on the machine, and nobody clears it
 *   without being told.
 * - [backfillOf] — until it finishes, the corpus is partial in an unknown way, which makes any
 *   measurement of retrieval quality meaningless.
 *
 * **The adapter reports; the store carries it to the reader.** This is written by the thing
 * doing the reading and read by the backend when it answers, which is how ingestion state
 * reaches a reply through the store rather than by a call into the ingest process. The scheduler
 * writes only what it alone observes — that a pass went through, or that one failed in a way the
 * adapter did not report itself.
 *
 * Writes take the facts and reads hand back a summary, which is why the two sides are not
 * mirror images: [recordPass] records one pass, while [healthOf] answers what the passes so far
 * amount to.
 *
 * [healthOf] and [backfillOf] are `null` until each has something to report, because *nothing is
 * known yet* and *nothing is wrong* are different states and only the first is honest before a
 * first run: a health with no failure in it reads as healthy, and an unfinished backfill reads
 * the same whether or not anyone looked. Freshness needs no such guard, because its own value
 * carries the distinction — nothing believed complete is what an unreported source and a source
 * with nothing complete both amount to — so an implementation is free to answer either way.
 *
 * As everywhere, every function suspends: the implementation reaches Postgres through the one
 * seam that hops to the I/O dispatcher.
 */
interface IngestStateRepository {

    /**
     * Report how current this source is believed to be.
     *
     * [completeThrough] is a **source** time, not an observation time: a backfill captures a
     * five-year-old message today, and reporting when the capture happened would make old
     * history look current. `null` means nothing is believed complete yet.
     *
     * There is one case where a clock reading is the honest answer and it is worth naming, because
     * it looks like the mistake above. A source read through a change feed is complete up to the
     * moment the feed was last drained empty: nothing older than that is outstanding, which is a
     * statement about source time that happens to coincide with an observation. The mistake the
     * rule exists to prevent is the opposite one — stamping the hour a *backfill* ran onto history
     * it captured — and an adapter reporting the newest message it has seen instead would make a
     * quiet week read as a week behind.
     *
     * [lagging] names the units known to be behind — the exception to [completeThrough] rather
     * than a second version of it. Reported as work units because those are the names a re-read
     * can be scoped to, so what is reported here is actionable without translation.
     */
    suspend fun reportFreshness(source: Source, completeThrough: Instant?, lagging: List<WorkUnitId>)

    /**
     * Report how far backfill has reached as of the last enumeration.
     *
     * [complete] is never permanent: the work list is open rather than a queue that drains, so
     * new conversations appear and a source that was complete is not. [reached] is the source
     * time walked back to, and `null` means nothing has been walked back to yet.
     *
     * The store stamps when this was reported, which is what makes the claim legible — complete
     * *as of* an enumeration is a statement with a date on it, and complete with no date is not.
     */
    suspend fun reportBackfill(source: Source, complete: Boolean, reached: Instant?)

    /** Record the outcome of one pass over this source. */
    suspend fun recordPass(source: Source, pass: Pass)

    /**
     * How current this source is believed to be, or `null`.
     *
     * Both answers mean the same thing for a source that has never reported: nothing is
     * believed complete and nothing is known to be lagging. A caller therefore handles both and
     * acts the same way on either — the store behind this returns `null` while it holds no row
     * for the source, and a `Freshness` with nothing in it once some other report has made one.
     */
    suspend fun freshnessOf(source: Source): Freshness?

    /** What this source's passes so far amount to, or `null` if none has been recorded. */
    suspend fun healthOf(source: Source): Health?

    /** How far backfill has reached, or `null` if it has never been reported. */
    suspend fun backfillOf(source: Source): BackfillProgress?
}

/**
 * How current a source is believed to be.
 *
 * @property completeThrough the source time up to which this source is believed complete.
 * @property lagging units known to be behind that time.
 */
data class Freshness(
    val completeThrough: Instant?,
    val lagging: List<WorkUnitId> = emptyList(),
)

/**
 * How far backfill has reached, and when that was last established.
 *
 * Progress as a fraction exists only here. Neither a difference feed nor a change feed reports
 * how many events remain, so incremental sync has no denominator and "how far behind" is not a
 * number — which is why freshness is a time and not a percentage.
 *
 * @property complete whether the work list was exhausted as of [enumeratedAt].
 * @property reached the source time history has been walked back to.
 * @property enumeratedAt when the adapter last said either of the above.
 */
data class BackfillProgress(
    val complete: Boolean,
    val reached: Instant?,
    val enumeratedAt: Instant,
)

/** The outcome of one pass over a source. */
sealed interface Pass {

    /** The pass went through: material was read, or there was none to read. */
    data object Succeeded : Pass

    /** The pass did not go through. */
    data class Failed(val kind: FailureKind, val note: String? = null) : Pass
}

/**
 * What kind of failure a pass hit, which is the part that decides who does something about it.
 *
 * Three values rather than a taxonomy, because the only distinction that changes anyone's
 * behaviour is whether waiting fixes it, and whether a person has to act.
 */
enum class FailureKind {

    /** A wait, a refused request, an unreachable network. It clears by itself. */
    TEMPORARY,

    /**
     * Only a person can clear it, on the machine itself.
     *
     * Re-authentication is the case that matters: a user session expires and no amount of
     * retrying renews it, so this is the one state that has to be reported loudly enough to
     * reach whoever can act on it.
     */
    NEEDS_A_PERSON,

    /**
     * A fault nobody classified — what the scheduler records when an adapter fails in a way it
     * did not report itself.
     *
     * Kept separate from [TEMPORARY] because "this will clear" is a claim, and a fault of
     * unknown origin does not support it.
     */
    UNEXPECTED,
}

/**
 * What a source's passes so far amount to.
 *
 * @property lastSuccessAt when a pass last went through.
 * @property lastFailureAt when a pass last did not.
 * @property lastFailureKind what that failure was.
 * @property lastFailureNote whatever detail came with it, for whoever has to read it.
 */
data class Health(
    val lastSuccessAt: Instant?,
    val lastFailureAt: Instant?,
    val lastFailureKind: FailureKind?,
    val lastFailureNote: String? = null,
) {

    /**
     * Whether this source is waiting on a person, which is the one question a reader acts on.
     *
     * Derived rather than stored, so the rule exists once however many stores implement the
     * port. A success after the failure clears it; simultaneous times count as blocked, because
     * when the two cannot be ordered the safe error is sending someone to look at a source that
     * turns out to be fine, rather than leaving an expired session uncleared because a clock
     * could not separate two statements.
     */
    val blockedOnAPerson: Boolean
        get() = lastFailureKind == FailureKind.NEEDS_A_PERSON &&
            lastFailureAt != null &&
            (lastSuccessAt == null || !lastFailureAt.isBefore(lastSuccessAt))
}
