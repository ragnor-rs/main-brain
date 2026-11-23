package io.github.ragnorrs.mainbrain.domain.index

import io.github.ragnorrs.mainbrain.domain.Derivation
import io.github.ragnorrs.mainbrain.domain.DerivationStateRepository
import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.EmbeddingKind
import io.github.ragnorrs.mainbrain.domain.EmbeddingProvider
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.MessageRepository
import io.github.ragnorrs.mainbrain.domain.record.RecordKind

/**
 * The pass that turns captured messages into retrievable spans.
 *
 * A plain class composing ports, with no interface: it inverts no I/O of its own, and
 * everything it touches is already behind one. What schedules it — a loop, a lock, a cadence
 * — is the containing process's concern, so this does exactly one batch and says how much it
 * did. A caller runs it until it reports nothing left.
 *
 * **It is bounded by nothing but the corpus.** Indexing is local compute, so the index covers
 * everything back to the first backfilled message while inference stops at the recency
 * horizon. There is no horizon here and there should not be one.
 */
class Indexer(
    private val embeddings: EmbeddingProvider,
    private val derivations: DerivationStateRepository,
    private val messages: MessageRepository,
    private val index: IndexRepository,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {

    init {
        require(batchSize > 0) { "A batch of $batchSize records is not a batch." }
    }

    /**
     * Index one batch, returning how many spans were written.
     *
     * **Zero does not mean there is nothing left to do.** A batch made entirely of messages
     * with no text to embed writes no spans and still makes progress, so a caller that looped
     * `while (indexBatch() > 0)` would stop on the first such batch with work still pending.
     * Loop until [owed] is zero instead.
     */
    suspend fun indexBatch(): Int {
        // Asked once per batch rather than cached for the life of the object: a sidecar
        // restarted onto another model mid-run would otherwise have its spans filed under the
        // version this pass started with, and nothing would ever re-derive them.
        val derivation = Derivation(Deriver.INDEX, embeddings.version())
        // No horizon: indexing is local compute and covers the corpus back to the first
        // backfilled message. Stated rather than defaulted, because the other pass must not be
        // able to get this by forgetting to say.
        val pending = derivations.pending(derivation, RecordKind.MESSAGE, batchSize, notBefore = null)
        if (pending.records.isEmpty()) return 0

        val records = messages.messages(pending.records)
        val embeddable = records.filter { !it.text.isNullOrBlank() }
        val vectors = embeddings.embed(EmbeddingKind.PASSAGE, embeddable.map { it.text.orEmpty() })
        // Checked rather than zipped blind. `zip` truncates to the shorter list, so a provider
        // returning fewer vectors than texts would leave the tail unembedded while the mark
        // below still recorded every record as done — the one failure a re-run cannot heal.
        // The port says one embedding per text; this is where that is relied upon, so this is
        // where it is enforced.
        check(vectors.size == embeddable.size) {
            "Asked for ${embeddable.size} embeddings and got ${vectors.size}. Pairing them " +
                "positionally would file one span's vectors under another, and marking the " +
                "batch would settle records that were never embedded."
        }
        val entries = embeddable.zip(vectors) { message, embedding ->
            IndexEntry(span = message.toSpan(), embedding = embedding)
        }

        // Write the spans before the marks, never the other way round. A failure between the
        // two leaves spans that nothing has recorded as derived, and the next run indexes them
        // again over the top — which is free, because indexing a span replaces it. The
        // reverse order leaves records marked as derived that were never embedded, and no
        // re-run ever revisits them: the corpus would have a hole that reads as complete.
        index.index(entries)

        // Every record that was looked at, including the ones with nothing to embed. A
        // message that is a sticker or an image with no caption has no text and never will;
        // leaving it unmarked would hand it back on every pass for ever, and a batch of them
        // would stop the pass advancing at all.
        // Stamped with when the work was *selected*, not with now. A record written again
        // while this batch was in flight has a content time later than that instant, so it
        // stays pending and is derived again. Stamping the finishing time would settle it
        // against a body this pass never saw.
        derivations.mark(derivation, RecordKind.MESSAGE, records.map { it.externalId }, pending.asOf)
        return entries.size
    }

    /**
     * How many messages are still owed to the current model.
     *
     * The whole backlog rather than a page of it, because the two callers of this both need the
     * number and not merely whether it is non-zero: a driver tells a batch that made progress
     * from one that stalled by watching this fall, and a reader tells a pass that has stopped
     * from a corpus with nothing new in it the same way.
     *
     * Asked of the model's version, like the batch is, so a sidecar restarted onto another model
     * reports the whole corpus owed — which it is.
     */
    suspend fun owed(): Int =
        derivations.owed(Derivation(Deriver.INDEX, embeddings.version()), RecordKind.MESSAGE, notBefore = null)

    private fun Message.toSpan() = Span(
        // Ordinal zero: a message is indexed whole. Only a document is split, and that
        // arrives with the record kind that needs it.
        id = SpanId(kind = RecordKind.MESSAGE, record = externalId, ordinal = 0),
        text = text.orEmpty(),
        sourceTime = sourceTime,
    )

    companion object {
        /**
         * Messages per batch.
         *
         * Large enough that the round trip to the sidecar is amortised over real work, small
         * enough that an interrupted run loses little and that one request's worth of text
         * fits comfortably in memory.
         */
        const val DEFAULT_BATCH_SIZE = 128
    }
}
