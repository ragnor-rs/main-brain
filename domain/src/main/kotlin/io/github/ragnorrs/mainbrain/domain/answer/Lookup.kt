package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.EmbeddingKind
import io.github.ragnorrs.mainbrain.domain.EmbeddingProvider
import io.github.ragnorrs.mainbrain.domain.index.IndexRepository
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.index.SpanId

/**
 * The index, read by a question or by a span already in hand, rather than by a subject.
 *
 * Two calls that always go together — embed the question, then search on it — and a shape that did
 * them itself would be choosing the embedding kind each time. Asking for the wrong one costs
 * accuracy silently, which is the kind of mistake worth removing from every shape that searches
 * rather than documenting at each.
 *
 * **[following] is the index read that embeds nothing, and it is here rather than beside the port
 * because of what it is for.** A shape holding a finding holds the span the finding was read out
 * of, so the question has already been answered and what remains is the thread around it. Handing
 * a shape the index port itself for that would give `shapes` two routes to one store — this class
 * and the port it already wraps — and the next shape wanting a neighbour would choose between
 * them.
 */
class Lookup(
    private val embeddings: EmbeddingProvider,
    private val index: IndexRepository,
) {

    /**
     * The [limit] spans the index ranks highest for [question].
     *
     * Blank text returns nothing rather than embedding it: there is no vector for an empty
     * question, and the sidecar would be asked for one per request that happened to carry none.
     *
     * Asked for as a [EmbeddingKind.QUERY], because that is what it is. The corpus was embedded as
     * passages, and a model that distinguishes the two is being asked the comparison it was trained
     * for.
     */
    suspend fun of(question: String, limit: Int): List<Span> {
        if (question.isBlank()) return emptyList()
        val embedded = embeddings.embed(EmbeddingKind.QUERY, listOf(question)).firstOrNull()
        return embedded?.let { index.search(it, limit).map { hit -> hit.span } }.orEmpty()
    }

    /**
     * The [limit] spans ranked highest for [question], each widened by its neighbours.
     *
     * A message is indexed one to one, so a hit can be a bare agreement that means nothing alone.
     * Expanding around it uses the ordering the corpus already stores, which is why indexing needs
     * no second artifact.
     *
     * The widened spans are returned in hit order, each followed by its own context. A shape that
     * sorted them by time instead would interleave three conversations into one apparent thread.
     */
    suspend fun widened(question: String, limit: Int, around: Int): List<Span> =
        clustered(question, limit, around).flatten()

    /**
     * The same read, with each hit's own context still gathered around it.
     *
     * **The boundaries [widened] flattens away, for a shape that has to reorder what it was
     * given.** A hit and its neighbours are one stretch of one conversation, so a caller may move
     * them as a unit — what it may not do is sort the spans themselves, which is the interleaving
     * [widened] warns about. Handing the stretches over is what makes that difference expressible:
     * the unit of order is the stretch.
     *
     * **A stretch is not promised to be whole once it leaves here.** Two hits close together in one
     * conversation come back as two overlapping stretches, and a caller that offers each turn once
     * trims one of them — so what this guarantees is where the boundaries are, not that a caller
     * keeps every turn inside them.
     *
     * Ordered by rank, exactly as [widened] is, which is why that one can be expressed in terms of
     * this rather than beside it: the two cannot come to disagree about what widening a hit means.
     *
     * **Never an empty stretch**, which callers that date one by its spans rely on: a hit with no
     * neighbours stands as itself rather than as nothing.
     */
    suspend fun clustered(question: String, limit: Int, around: Int): List<List<Span>> =
        of(question, limit).map { hit ->
            index.neighbours(hit.id, before = around, after = around).ifEmpty { listOf(hit) }
        }

    /**
     * Each of [spans], with the [after] turns that follow it in its own conversation.
     *
     * **What a claim cannot carry is how it was said, and this is where that is recoverable.** A
     * finding is one sentence restated to stand on its own — see `Extractor.REWRITE_SYSTEM`, which
     * asks for who owes what to whom — so a thing one party merely put to another arrives as a flat
     * assertion that both will do it, and nothing on the row says which it was. The turn it was read
     * out of says it in the speaker's own words, and the turns after it are where the other party
     * either took it up or turned it down.
     *
     * **Forward only, which is the asymmetry the question has.** What follows a proposal settles it;
     * what precedes it is the material the speaker was answering, which a reader asking where things
     * stand did not ask about and which the shape's own threads tail already reaches where it
     * matters. [widened] looks both ways because a ranked hit can be a bare reply whose antecedent
     * is behind it, and that is a different question from this one.
     *
     * Returned in one list, de-duplicated by span, because two findings read out of one thread widen
     * into each other and the caller gathers material by span anyway. A span the index does not hold
     * contributes nothing rather than failing: an unindexed message is one this read cannot reach,
     * and the claim still stands on its own.
     *
     * **One query per span, which is the one read on this path that scales with the finding count.**
     * The port's neighbour read is keyed by a single span, so a caller holding thirty findings makes
     * thirty round trips where every other read in a shape's query set is bundled. That is a bound
     * on the port rather than a decision here, and it is left as it is until a figure says it
     * matters: the alternative is a bulk form of [IndexRepository.neighbours] whose window has to be
     * applied per span anyway.
     */
    suspend fun following(spans: List<SpanId>, after: Int): List<Span> =
        spans.distinct()
            .flatMap { index.neighbours(it, before = 0, after = after) }
            .distinctBy { it.id }
}
