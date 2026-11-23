package io.github.ragnorrs.mainbrain.domain

/**
 * Turns text into the vectors retrieval searches on.
 *
 * A port because the model behind it is not a settled thing. It runs in its own process today
 * — the model provider has no embeddings endpoint, so there is nothing to fold this into — and
 * a hosted model later is one more implementation in the outer layer rather than a change
 * here. Both callers go through it: the pass that indexes corpus spans, and the answer path
 * embedding a question.
 *
 * **Both halves of the fused read come out of one call.** Retrieval is lexical and vector
 * together, and a model that emits term weights alongside the dense vector gives the lexical
 * half from the same pass rather than from a second artifact. A model that emits no weights is
 * not excluded: it returns [Embedding.sparse] empty, and the lexical half has to come from
 * somewhere else.
 */
interface EmbeddingProvider {

    /**
     * What is doing the embedding, as a string that changes whenever the vectors would.
     *
     * This is the `version` a derivation mark carries — see [DerivationStateRepository]. It is
     * compared for staleness rather than keyed on, and everything that would make two runs
     * disagree belongs in it, because a change this string does not capture is a change nothing
     * re-derives.
     */
    suspend fun version(): String

    /**
     * Embed [texts], in order, returning one [Embedding] each.
     *
     * Batched rather than one call per text: the cost of a round trip dominates a short
     * message, and a backfill is millions of them.
     */
    suspend fun embed(kind: EmbeddingKind, texts: List<String>): List<Embedding>
}

/**
 * Which side of the comparison a text is on.
 *
 * Some models embed a question and the material answering it differently, and asking for the
 * wrong one costs accuracy silently. Models that make no distinction ignore this.
 */
enum class EmbeddingKind {
    PASSAGE,
    QUERY,
}

/**
 * One text's vectors: the dense one, and the term weights that carry the lexical half.
 *
 * [sparse] maps a term's position in the model's vocabulary to its weight, holding only the
 * terms that have one — most of a vocabulary is absent from any one text, and storing the
 * zeroes would be a quarter of a million floats per message.
 *
 * **It carries the strongest weights rather than every one.** A long text can produce more
 * terms than the index will hold, so a provider truncates to its own limit; the weights are
 * ranked by the model, so what goes is what contributed least.
 */
data class Embedding(
    val dense: List<Float>,
    val sparse: Map<Int, Float> = emptyMap(),
) {
    init {
        require(dense.isNotEmpty()) {
            "A dense vector cannot be empty: it is what vector search orders on, and an empty " +
                "one would store as a zero vector that matches everything equally."
        }
        require(sparse.keys.all { it >= 0 }) {
            "A term weight is keyed by a position in the model's vocabulary, which is never negative."
        }
    }
}

/**
 * Nothing could be embedded, because whatever is behind the port is not answering.
 *
 * Thrown rather than returned, for the reason [ModelUnavailable] is: it is not a property of the
 * text, so there is no per-text result to hand back. A caller that has to tell a person something
 * says *come back later* — the provider will answer again without anybody changing anything.
 *
 * **It lives here rather than beside an adapter so that a caller can name it.** Whoever has to
 * turn this into an answer is the interface, and an interface that had to catch the adapter's own
 * type would know which implementation of this port it was deployed against. The implementations
 * raise their own subtypes where they have something more specific to say.
 */
open class EmbeddingsUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
