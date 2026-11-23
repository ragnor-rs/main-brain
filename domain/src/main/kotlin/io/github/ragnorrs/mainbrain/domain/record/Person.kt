package io.github.ragnorrs.mainbrain.domain.record

import java.time.Instant

/**
 * A person **as one source presented them** — a profile, an account, a sender.
 *
 * Not a human being. One human appears as several of these, one per source and account that
 * ever showed them, and assembling those into a single contact with a permanent identifier is
 * the recogniser's work, not the store's. Keeping the two apart is what lets the assembly be
 * re-derived, corrected and reversed without touching what a source actually said.
 *
 * Which is why a person record carries no entity reference: that link is derived, it is
 * reversible, and a column on this row could express neither.
 *
 * Most person records are sparse — a participant offers a handle and nothing else. That is
 * ordinary, so nothing here is required beyond the key.
 */
data class Person(
    val externalId: ExternalId,
    val displayName: String? = null,
    val identifiers: List<Identifier> = emptyList(),
    val sourceTime: Instant? = null,
    /**
     * Whether this record is ours — **the account the source was read through**, rather than
     * somebody the corpus is about.
     *
     * The one fact here that is not about the person but about the reader's relationship to them,
     * and it is kept because without it the account whose archive this is is stored exactly like a
     * counterparty who sent one message. Telling what is owed by us from what is owed to us needs
     * it, and nothing else in the corpus marks it.
     *
     * **Not an [Identifier], and the distinction is load-bearing.** A claim is something a record
     * advertises about itself to help match it to another, and a shared claim is scored as evidence
     * in proportion to how rare the value is — so a claim carried by exactly our own accounts would
     * be the rarest signal there is, merging them at a weight nobody chose and for a reason that is
     * not evidence about who they are. It is derived by comparison against the account a session
     * authenticated as, and never stored as an identifier of its own.
     *
     * **`null` is *nothing is known*, which is not the same as `false`.** A source whose adapter
     * cannot report the account it authenticated as leaves this unset rather than guessing, because
     * the alternative is a corpus where every record claims not to be ours and nothing can tell
     * that from a corpus where the question was never asked. Nothing infers it from how much
     * anybody said.
     */
    val isSelf: Boolean? = null,
)

/**
 * A person record with its claims **and when each was seen** — what the matcher reads.
 *
 * A second shape rather than times added to [Person], because the two are read for opposite
 * purposes. [Person] is the write shape and what a source said; this is evidence, and the
 * observation pair on each claim is half of it. A number agreeing across a long gap is weaker
 * than one agreeing now, and only the claim's own last-seen can tell those apart — so a matcher
 * handed bare [Identifier]s would score a number abandoned years ago exactly as highly as a
 * current one.
 *
 * Read in bulk, because matching is pairwise over a batch: a claim read per record would be one
 * query per candidate, and the candidates are found by the claims themselves.
 */
data class ObservedPerson(
    val externalId: ExternalId,
    val displayName: String? = null,
    val claims: List<ObservedIdentifier> = emptyList(),
)
