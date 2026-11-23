package io.github.ragnorrs.mainbrain.domain.record

/**
 * The only way source material enters the system: one adapter per source, drained by the
 * scheduler.
 *
 * What arrives through an interface is not a source, so nothing behind a chat, a mail client or
 * a web form implements this. An adapter yields batches and owns everything else about how it
 * reads — which is what makes a second source an implementation rather than a change to the
 * pipeline.
 *
 * **The position is not here**, and neither is the backfill plan. For one source the position is
 * the protocol's own, held by a client library in its own store; for another it is an opaque
 * token. A second copy kept by the scheduler would be two authorities on one fact, free to
 * disagree after a crash. So a rejected position — a difference too long to serve, an expired
 * token — is an internal matter the adapter resolves by resyncing itself, not a failure the
 * scheduler sees.
 *
 * **The scheduler decides when to ask; the adapter decides what is safe to answer.** Pacing
 * cannot be the adapter's alone in a system whose reason for existing is request limits, and it
 * cannot belong anywhere that would have to tell a wait with a stated duration from a refused
 * request from a change feed's expired page token.
 */
interface SourceStream {

    /** Which source this adapter reads. One adapter per source, so this identifies it. */
    val source: Source

    /**
     * The next batch, or `null` when nothing is available right now.
     *
     * `null` does not mean finished: an adapter is never finished. It stays current on its
     * change feed and spends whatever request budget is left walking history backwards, so a
     * stream that has nothing this second may have something in a minute.
     *
     * **Asking again is the acknowledgement.** The port carries no other, and the scheduler
     * holds a batch until the corpus has it — re-trying the same batch rather than asking for
     * another. So an adapter advances its own position when it is next asked, not when it hands
     * a batch over, which is what makes "commit the batch, then advance the position" the actual
     * order of events. Delivery is therefore at-least-once: an interruption in between
     * re-delivers, and re-delivery changes nothing, because every write is an upsert and nothing
     * is ever deleted.
     */
    suspend fun next(): Batch?
}
