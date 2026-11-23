package io.github.ragnorrs.mainbrain.domain.record

/**
 * Person records in the corpus — one per account a source showed, never one per human.
 *
 * As with every record kind: writes are upserts, and nothing here removes anything. Merging
 * two of these into one contact is the recogniser's work and happens in the derived layer,
 * where it is a reversible link rather than a rewrite of what a source said.
 */
interface PersonRepository {

    /** Write [person], inserting it or updating the record with the same external identifier. */
    suspend fun upsert(person: Person)

    /** The person record with this external identifier, with its claims, or `null`. */
    suspend fun find(externalId: ExternalId): Person?

    /** The store's own time bookkeeping for this person record, or `null` if it holds none. */
    suspend fun timesOf(externalId: ExternalId): RecordTimes?

    /**
     * This person record's claims, each with when it was first and last seen.
     *
     * The read the scored matcher is built on — and the reason the store constrains
     * `(record, type, value)` rather than `(type, value)`. Two person records claiming one
     * phone number is evidence that they are one human, which is for the recogniser to weigh
     * and not for the store to refuse.
     */
    suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier>

    /**
     * These person records with their claims and the times those were seen.
     *
     * For a derived pass working from a list of records to revisit. An identifier the store does
     * not hold is absent from the result rather than an error, as with the other bulk reads: a
     * pass reading a watermark can name a record that has since changed underneath it.
     */
    suspend fun persons(externalIds: List<ExternalId>): List<ObservedPerson>

    /**
     * Every person record claiming any of [claims], with all of its own claims.
     *
     * One of the matcher's two directions, and the read the store's missing uniqueness constraint
     * on `(type, value)` exists to serve: two records claiming one number is the evidence being
     * looked for, not a conflict. Comparing a record against the whole corpus is not an option, so
     * the claims it carries select records it could match — and [namesakes] selects the rest, which
     * is what reaches a pair whose accounts share no identifier and never will.
     *
     * The result includes the records that supplied [claims], because a record claims its own
     * claims. A caller matching a batch against the corpus therefore gets its own members back
     * and must not match a record against itself.
     */
    suspend fun claimants(claims: List<Identifier>): List<ObservedPerson>

    /**
     * Every person record whose name key is one of [names], where the name is rare enough to be
     * worth comparing — at most [carriedByAtMost] records carry it across the corpus.
     *
     * The matcher's other direction, and the one a single-source corpus depends on. A handle is
     * unique per account and a number is fixed when an account is registered, so two accounts of
     * one human share no identifier and never will: selecting only the records that share a claim
     * would never bring such a pair together, and a pair that is never assembled is never scored.
     *
     * **Bounded because a common name is not evidence, and pulling it is not free.** Rarity
     * already decides what a shared name *scores*; this decides whether it is worth scoring at
     * all. Without the bound a name a crowd carries would pull a batch-sized set of records per
     * batch member, to produce pairs whose score rounds to nothing.
     *
     * The bound is measured over the whole corpus rather than over what this query returns, so it
     * means the same thing whatever the batch happens to hold. A name carried by more than
     * [carriedByAtMost] records yields no rows at all — not a truncated set of them, which would
     * make the result depend on which records the store reached first.
     *
     * Names are matched in the normalised form [names] is asked in, the same form
     * [nameFrequency] counts: the two have to agree about what makes two names one name, and a
     * reader comparing normalised names against raw ones would match nothing.
     */
    suspend fun namesakes(names: List<String>, carriedByAtMost: Int): List<ObservedPerson>

    /**
     * How many person records carry each of these names, keyed by the name as given.
     *
     * The denominator rarity is scaled by, and the reason the weakest signal behaves correctly
     * rather than uniformly: a name half the corpus carries is evidence of nothing, and one
     * carried twice is meaningful. Measured rather than judged, which is what makes it the only
     * part of the scoring that real data supplies on its own.
     *
     * Names are counted in the normalised form the caller asks in — two names differing only in
     * case or spacing are one name. A store counting raw strings while the matcher compared
     * normalised ones would find no duplicates at all, so every name would read as unique and
     * the weakest signal would silently become one of the strongest.
     */
    suspend fun nameFrequency(names: List<String>): Map<String, Int>
}
