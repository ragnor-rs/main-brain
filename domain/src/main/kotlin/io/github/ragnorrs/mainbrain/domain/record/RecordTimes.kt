package io.github.ragnorrs.mainbrain.domain.record

import java.time.Instant

/**
 * The three time axes the store keeps for every record, read back.
 *
 * Only [sourceTime] is supplied by the adapter; the rest is the store's own bookkeeping, which
 * is why it is read through this type rather than carried on the records themselves. A record
 * is what a source said, and when the system happened to look is not something a source said.
 *
 * | Axis | Meaning |
 * |---|---|
 * | [sourceTime] | when the source says it happened |
 * | [addedAt], [lastSeenAt] | observation: first capture, and every pass that still finds it |
 * | [contentTime] | updated only when content actually changed |
 *
 * The distinction between the last two is the one that is expensive to get wrong. A full
 * resync that re-observes unchanged material must not make the whole corpus look freshly
 * modified, so content time is compared before writing and never stamped on every touch.
 *
 * **Recency filters read source time.** A backfill captures a five-year-old message today, so
 * filtering on observation time would make old history look current. Last-seen is the honest
 * weak form of removal: material unseen for several passes is a signal, never a fact.
 */
data class RecordTimes(
    val sourceTime: Instant?,
    val addedAt: Instant,
    val lastSeenAt: Instant,
    val contentTime: Instant,
)
