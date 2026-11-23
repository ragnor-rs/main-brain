package io.github.ragnorrs.mainbrain.domain.record

/**
 * Documents in the corpus.
 *
 * As with every record kind: writes are upserts, and nothing here removes anything.
 */
interface DocumentRepository {

    /**
     * Write [document], inserting it or updating the record with the same external identifier.
     *
     * A [Document.messageRef] is stored as the source gave it and is never looked up: the store
     * keeps the identifier pair, not a resolved reference. So a reference to a message that was
     * never captured is written and read back unchanged rather than refused or dropped — an
     * attachment is meant to arrive in the same batch as its message, but a document-only source
     * has no messages at all, and a reference the corpus cannot yet follow is not a reason to
     * lose the file or what it said about itself.
     */
    suspend fun upsert(document: Document)

    /** The document with this external identifier, with its claims, or `null`. */
    suspend fun find(externalId: ExternalId): Document?

    /** The store's own time bookkeeping for this document, or `null` if it holds none. */
    suspend fun timesOf(externalId: ExternalId): RecordTimes?

    /** This document's claims, each with when it was first and last seen. */
    suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier>
}
