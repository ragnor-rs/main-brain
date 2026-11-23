package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.Embedding
import io.github.ragnorrs.mainbrain.domain.EmbeddingKind
import io.github.ragnorrs.mainbrain.domain.EmbeddingProvider
import io.github.ragnorrs.mainbrain.domain.index.Hit
import io.github.ragnorrs.mainbrain.domain.index.IndexEntry
import io.github.ragnorrs.mainbrain.domain.index.IndexRepository
import io.github.ragnorrs.mainbrain.domain.index.Span
import io.github.ragnorrs.mainbrain.domain.index.SpanId

/**
 * The two ports [Lookup] composes, faked together.
 *
 * One file because the two are never useful apart: a lookup embeds a question and searches on the
 * result, so a test driving one has to drive both. Three of the five shapes read through [Lookup],
 * which is what makes this worth sharing rather than copying into each.
 */
class FakeEmbeddings : EmbeddingProvider {

    /** Every text this was asked to embed, with which side of the comparison it was asked for. */
    val embedded = mutableListOf<Pair<EmbeddingKind, String>>()

    override suspend fun version(): String = "fake-embeddings"

    override suspend fun embed(kind: EmbeddingKind, texts: List<String>): List<Embedding> {
        texts.forEach { embedded += kind to it }
        return texts.map { Embedding(dense = listOf(1.0f)) }
    }
}

/**
 * An index that returns fixed hits and records what it was asked.
 *
 * [neighbours] answers from [around] rather than from the hits, so a test can tell a shape that
 * widened a hit from one that merely returned it — the two produce the same spans when nothing
 * neighbours anything, which would make a missing widening invisible.
 */
class FakeIndex(
    private val hits: List<Span> = emptyList(),
    private val around: Map<SpanId, List<Span>> = emptyMap(),
) : IndexRepository {

    var searches = 0
        private set

    val widened = mutableListOf<SpanId>()

    /**
     * Every widening as it was asked, window included.
     *
     * Beside [widened] rather than replacing it: most cases ask only which spans were widened, and
     * the window is what two of them turn on — a read that looks forward only, and one that looks
     * both ways. A fake recording the span alone cannot tell those apart, so a shape asking for
     * the wrong window would pass every case about widening.
     */
    val asked = mutableListOf<Widening>()

    data class Widening(val span: SpanId, val before: Int, val after: Int)

    override suspend fun index(entries: List<IndexEntry>) =
        error("Nothing on the answer path writes to the index.")

    override suspend fun search(query: Embedding, limit: Int): List<Hit> {
        searches++
        return hits.take(limit).map { Hit(span = it, score = 1.0) }
    }

    override suspend fun neighbours(span: SpanId, before: Int, after: Int): List<Span> {
        widened += span
        asked += Widening(span, before, after)
        return around[span].orEmpty()
    }
}
