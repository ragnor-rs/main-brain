package io.github.ragnorrs.mainbrain.domain.record

/**
 * A bundle of record updates, as one source adapter hands it over.
 *
 * Messages are nested inside their conversation; persons and documents are top-level. A person
 * spans conversations and one source yields profiles with no conversation anywhere near them,
 * while a document-only source has no conversations at all — so only messages have a container
 * they can never be without.
 *
 * A batch carrying a conversation's messages carries that conversation's header, whether the
 * conversation is new or years old.
 *
 * **What an adapter may not do is decide from what it believes was already persisted** — that is
 * wrong after a restore, wrong after a re-run, and untestable without a populated database. What
 * it may do is notice that the *source* now describes a conversation differently, which is new
 * information and the only route a rename has to the corpus once a conversation's history is
 * finished and its chat has gone quiet. The restore case is answered by a restart instead: an
 * adapter asserts every header it can see on its first pass, which is a property of the process
 * rather than a model of the store.
 *
 * **[withdrawn] carries identifiers where the others carry records**, because that is all a
 * source has left to give. A platform reporting a removal names what it removed and offers no
 * content, and the corpus removes nothing — so what reaches the store is the narrowest possible
 * statement: the source said these records are gone, at this moment. It is a list and not a record kind
 * for that reason; a `Message` would need a source time and a body this adapter cannot know
 * without reading back what it wrote, and an adapter never reads the corpus.
 */
data class Batch(
    val conversations: List<ConversationUpdate> = emptyList(),
    val persons: List<Person> = emptyList(),
    val documents: List<Document> = emptyList(),
    val withdrawn: List<ExternalId> = emptyList(),
)

/** A conversation's header together with whichever of its messages this batch carries. */
data class ConversationUpdate(
    val conversation: Conversation,
    val messages: List<Message> = emptyList(),
)
