package io.github.ragnorrs.mainbrain.domain.record

/**
 * Conversations in the corpus.
 *
 * **Every write is an upsert and nothing removes a record.** There is no delete here and there
 * is no delete under it: sources report removals differently or not at all, and an archive
 * cannot report one at all, because absence is indistinguishable from a capture that came back
 * incomplete. A removal is never an instruction.
 *
 * Every function suspends because the implementation reaches Postgres through the one seam
 * that hops to the I/O dispatcher. A plain function would have no way to, so it would block
 * whichever thread called it.
 */
interface ConversationRepository {

    /**
     * Write [conversation], inserting it or updating the record with the same external
     * identifier.
     *
     * Re-delivering an unchanged conversation advances last-seen and leaves content time where
     * it was. Identifiers accumulate: a claim already present has its last-seen advanced, and
     * none is ever withdrawn.
     */
    suspend fun upsert(conversation: Conversation)

    /** The conversation with this external identifier, with its claims, or `null`. */
    suspend fun find(externalId: ExternalId): Conversation?

    /** The store's own time bookkeeping for this conversation, or `null` if it holds none. */
    suspend fun timesOf(externalId: ExternalId): RecordTimes?

    /** This conversation's claims, each with when it was first and last seen. */
    suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier>

    /**
     * The conversations with these identifiers.
     *
     * For a derived pass working from a list of records to revisit, as the message and person
     * reads are. One the store does not hold is absent rather than an error.
     */
    suspend fun conversations(externalIds: List<ExternalId>): List<Conversation>
}
