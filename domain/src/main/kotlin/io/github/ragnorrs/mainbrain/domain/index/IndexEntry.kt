package io.github.ragnorrs.mainbrain.domain.index

import io.github.ragnorrs.mainbrain.domain.Embedding

/**
 * A corpus span together with the vectors that will find it.
 *
 * The two are written as one thing because a span stored without its embedding is invisible
 * to every search and reports no error — it would simply never be returned, which is the
 * hardest kind of indexing fault to notice.
 */
data class IndexEntry(val span: Span, val embedding: Embedding)
