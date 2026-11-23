package io.github.ragnorrs.mainbrain.domain

/**
 * Lets at most one process run a derived pass at a time.
 *
 * Both passes are resumable and idempotent, so two of them running at once corrupts nothing —
 * what it costs is money and request budget. Two processes inferring over the same backlog read
 * the same windows twice and pay for both, and two indexing it embed the same spans twice. So
 * this is a guard on spend rather than on correctness, which is why it is cheap and advisory
 * instead of a queue.
 *
 * **Keyed by [Deriver] rather than by a name.** A string would need hashing to reach whatever
 * the store locks on, and two different names colliding would silently stop one pass running
 * while the other held it — a fault that looks exactly like a pass with nothing to do. The
 * passes that exist are the values of an enum, so there is nothing to hash and nothing to
 * collide. Both recognition stages share [Deriver.RECOGNITION] and therefore one exclusion,
 * which is correct: they are one pass, run in order, and the second depends on the first.
 *
 * Nothing here waits. An implementation that queued would turn a cadence into a backlog of
 * sweeps, each one holding whatever the last had not finished — so a caller that cannot have the
 * exclusion is told so and comes back at its next cadence instead.
 */
interface Exclusion {

    /**
     * Run [work] while holding the exclusion for [deriver], and answer whether it ran.
     *
     * `false` means somebody else holds it, which is an ordinary answer rather than a failure:
     * another backend against the same store is running that pass. Whatever [work] throws is
     * thrown on to the caller, with the exclusion released first.
     *
     * The exclusion is held for the whole of [work] rather than per batch. Per batch, two
     * processes would alternate batches and both would be running the pass — which is the thing
     * this exists to prevent, and the expensive half of it is a model call.
     */
    suspend fun whileHolding(deriver: Deriver, work: suspend () -> Unit): Boolean
}
