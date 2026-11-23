package io.github.ragnorrs.mainbrain.domain.index

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import java.time.Instant

/**
 * Where a span is, in terms that survive a re-index.
 *
 * Built from the source's own identifier rather than a row number, which is what makes it
 * stable: re-indexing under a new model rewrites every vector and every surrogate key behind
 * them, and a citation written last week still has to resolve. It is also checkable outside
 * the database, which is what grounding needs — a cited identifier is compared against the
 * retrieved set, and that comparison cannot require a lookup to mean anything.
 *
 * [ordinal] distinguishes spans within one record. A message is indexed whole and always has
 * `0`; the only record kind that needs more than one span is a document, which is split
 * because a file is too large for a single embedding.
 */
data class SpanId(
    val kind: RecordKind,
    val record: ExternalId,
    val ordinal: Int = 0,
) {
    init {
        require(ordinal >= 0) { "A span's ordinal is a position within its record and is never negative." }
    }
}

/**
 * A retrievable piece of the corpus, and the text that was embedded to find it.
 *
 * [text] is held rather than re-read because it is what the answer path quotes and what a
 * citation is checked against. [sourceTime] travels with it for the same reason recency
 * filters read source time everywhere else: when the material is from, not when it was
 * captured.
 */
data class Span(
    val id: SpanId,
    val text: String,
    val sourceTime: Instant,
) {
    init {
        require(text.isNotBlank()) {
            "A span with no text is not retrievable: there was nothing to embed and nothing to cite."
        }
    }
}

/**
 * A span the index returned, with where it placed it.
 *
 * [score] orders a single result set and means nothing between two of them. The fused read
 * combines a lexical and a vector ranking, and a combination of ranks has no scale — a span
 * scoring higher than another in one search says nothing about a span from a different one.
 */
data class Hit(val span: Span, val score: Double)
