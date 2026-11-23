package io.github.ragnorrs.mainbrain.domain

import java.time.Instant

/**
 * What a pass has been billed over a span of time, and where a charge goes that no submission holds.
 *
 * **A reader over what is already recorded rather than a tally of its own.** Every batch the
 * provider answers is already billed against its submission, on all four counters and keyed by the
 * pass that made it — see [SubmissionRepository]. Totalling those rows is most of what a spend
 * figure is, and a second counter kept beside them would be a number that could disagree with the
 * bill.
 *
 * **What those rows cannot hold is a billed call that is not a submission**, and every kind of one
 * is recorded through [charge] and totalled with the rest: a call that warms a shared prefix before
 * the submission that reads it, an attempt that failed before the provider minted a reference, and
 * a pass whose work is short enough that it calls the provider directly rather than batching at
 * all. Nothing can land in both places, so this is one accounting and not two.
 *
 * **The span is asked for and not computed here, because a calendar day is not a storage notion.**
 * Which instants bound a day depends on a zone and on a clock, and `:domain` holds neither; a store
 * left to decide would answer the same question differently depending on which process asked. So
 * the caller that owns the clock names the range, and this totals what falls in it.
 */
interface SpendLedger {

    /**
     * What [deriver] was billed for everything collected in `[from, until)`.
     *
     * Half-open so that consecutive spans neither double-count a collection nor drop one: a
     * submission collected exactly at a boundary belongs to one span and not to both. Closed at
     * both ends, the instant a day ends would be charged to that day and to the next, and a bound
     * read twice would refuse a stage that had room.
     *
     * [TokenUsage.NONE] where nothing was collected, which is the honest answer for a day that has
     * spent nothing and the one a fresh deployment gets.
     *
     * Totals collections *and* the charges [charge] recorded, because both are money the provider
     * took and neither is in the other's record.
     */
    suspend fun spentBetween(deriver: Deriver, from: Instant, until: Instant): TokenUsage

    /**
     * Record that the provider billed [usage] for a call no submission holds.
     *
     * **The charges a submission row cannot carry.** A batch that was answered is billed against
     * the submission it was answered for; what this is for is every call that has no such row —
     * one that was read, billed, and then failed before the provider's own reference came back, and
     * one a pass made directly because its work is a short question per record rather than a batch.
     * Either way there is nothing to settle it against and no reference to invent one under. It is
     * totalled with the collections by [spentBetween] rather than kept apart, because a bound that
     * ignored it would let a stage spend without ever being counted.
     *
     * Charged to the moment it is recorded, which is the clock the store already keeps for a
     * collection. A failure that cost nothing is still worth recording as nothing: zero is what a
     * call that never landed was billed, and a row saying so is how a reader tells that from a
     * failure nobody accounted for.
     *
     * **Keyed on the pass and not on a stage.** The pass is the granularity [spentBetween] is read
     * at, so nothing finer would be used — and the callers could not supply anything finer that
     * meant the same thing: the driver catching a failure knows only which pass was sweeping, and a
     * pass charging its own call knows the stage by a name of its own that nothing else reads.
     */
    suspend fun charge(deriver: Deriver, usage: TokenUsage)
}
