package io.github.ragnorrs.mainbrain.domain.entity

import io.github.ragnorrs.mainbrain.domain.index.SpanId
import java.time.Instant

/**
 * The piece of the corpus a derived fact was read out of.
 *
 * **It cannot dangle, and that is structural rather than careful.** Nothing in the corpus is ever
 * deleted — a withdrawn message keeps its row and moves only its last-seen — so a pointer written
 * today still resolves years later. That is what lets every derived fact carry one, and why
 * citation here is a property of the schema instead of a thing the answer path has to police.
 *
 * **[span] is a [SpanId] rather than an address of its own**, which is the one decision in this
 * file. A citation is checked by comparing what an answer pointed at against what retrieval
 * returned, and that comparison means nothing if the two sides are different kinds of pointer
 * with a lookup in between. Sharing the type makes the check an equality on values. The address
 * is also stable under re-derivation — it is built from the source's own identifier, not from a
 * row number — so re-indexing under another embedding model leaves every statement's evidence
 * pointing where it pointed.
 *
 * [sourceTime] is the span's own, carried here rather than read back through the record. Conflicts
 * between statements resolve by the source time of their evidence, so this is the field that
 * decides which of two readings is the later one; a resolution that had to fetch the record to
 * find out would read the corpus once per pair it ordered. It is also the only honest axis for
 * the job: a backfill reads 2021 after it read yesterday, so when the system extracted something
 * carries no information about when it was said.
 */
data class Evidence(val span: SpanId, val sourceTime: Instant)

/**
 * The order two pieces of evidence are read in: when each was said, then the span's own address.
 *
 * The address breaks a tie, and it has to break it the same way on every run — an ordering that
 * depended on the order a store returned rows would let one run pick a different reading of a fact
 * than the next, and nothing about the corpus would have changed.
 */
val EVIDENCE_ORDER: Comparator<Evidence> = compareBy(
    { it.sourceTime },
    { it.span.record.nativeId },
    { it.span.ordinal },
)

/**
 * The span a finding is shown under, which is the first one that stated it.
 *
 * A finding carries every span that states it, and one of them has to be the one a reader is sent
 * to. The earliest is chosen because it is where the thing was first said; a restatement is
 * reached through its own message, which the answer path retrieves on its own account.
 */
val Set<Evidence>.firstStated: Evidence get() = minWith(EVIDENCE_ORDER)

/**
 * The last span that stated it, which is when the corpus last saw the thing at all.
 *
 * **Not the span a finding is shown under, and the two answer different questions.** [firstStated]
 * is where a reader is sent, because that is where the thing was first said. This is how old the
 * record of it is: an undertaking given three weeks ago and never mentioned since is a different
 * thing from one restated yesterday, and a finding carrying one span has both answers the same.
 *
 * Ordered by [EVIDENCE_ORDER] rather than by `maxOf { it.sourceTime }`, so that two spans said at
 * one instant resolve the same way here as they do there.
 */
val Set<Evidence>.lastStated: Evidence get() = maxWith(EVIDENCE_ORDER)
