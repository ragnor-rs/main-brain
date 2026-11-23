package io.github.ragnorrs.mainbrain.domain.record

/**
 * The four record shapes every source yields.
 *
 * Needed wherever something has to name a record it does not hold — the derivation watermark
 * is the case that forces it. An [ExternalId] is unique within its source *and kind*, not
 * across kinds: nothing stops a conversation and a message carrying the same native
 * identifier, so a pointer at "the record with this identifier" is ambiguous without this.
 */
enum class RecordKind {
    CONVERSATION,
    MESSAGE,
    PERSON,
    DOCUMENT,
}
