package io.github.ragnorrs.mainbrain.domain.index

import io.github.ragnorrs.mainbrain.domain.Embedding

/**
 * The corpus made findable when a question carries no scope.
 *
 * A question that names a thread, a contact or a topic already names the material to read and
 * needs nothing here. This exists for the other kind — *where did we ever say anything about
 * this* — which names no subset at all.
 *
 * As everywhere else, nothing here removes anything. Material a source has since dropped
 * stays indexed; whether a given request should surface it is a retrieval decision made
 * above this port, and it differs by request.
 */
interface IndexRepository {

    /**
     * Write [entries], replacing whatever was indexed for the same spans.
     *
     * Re-indexing a span is ordinary rather than exceptional: it is what a changed message or
     * a changed model produces, and it must leave one entry rather than two. The spans not
     * mentioned are untouched, so a model change that re-indexes the corpus in batches never
     * has the index empty.
     */
    suspend fun index(entries: List<IndexEntry>)

    /**
     * The [limit] spans closest to [query], lexical and vector fused into one ranking.
     *
     * Fused rather than either alone because the two fail in opposite places: a name, an
     * invoice reference or an amount has no useful semantic neighbourhood, while a question
     * asked in one language does not lexically match material written in another. A span
     * found by only one of the two still places.
     */
    suspend fun search(query: Embedding, limit: Int): List<Hit>

    /**
     * The spans either side of [span] in its own conversation: [before] earlier, [after] later.
     *
     * A message is indexed one-to-one, so a hit can be a bare "yes, agreed" that means nothing
     * alone. Expanding around it at read time uses the ordering the corpus already stores,
     * which is why indexing needs no second artifact and no windowing of its own.
     *
     * Returns them in conversation order, the hit included. Fewer than asked for at either end
     * of a conversation, and empty if the index holds no such span.
     */
    suspend fun neighbours(span: SpanId, before: Int, after: Int): List<Span>
}
