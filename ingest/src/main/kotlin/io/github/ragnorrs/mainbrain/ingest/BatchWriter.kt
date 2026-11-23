package io.github.ragnorrs.mainbrain.ingest

import io.github.ragnorrs.mainbrain.domain.record.Batch
import io.github.ragnorrs.mainbrain.domain.record.ConversationRepository
import io.github.ragnorrs.mainbrain.domain.record.DocumentRepository
import io.github.ragnorrs.mainbrain.domain.record.MessageRepository
import io.github.ragnorrs.mainbrain.domain.record.PersonRepository

/**
 * One batch onto the corpus, through the record ports and nothing else.
 *
 * **Order matters twice, and for the same reason both times.** A conversation's header is written
 * before its messages, because a message is written through its conversation and the store
 * refuses one it has never seen rather than inventing a header no source ever sent. Nesting in
 * [Batch] is what makes that order expressible: the header and its messages arrive together, so
 * there is no sequencing for this to get wrong. Withdrawals come last — see the comment on that
 * call — because a message withdrawn in the batch that carried it has to exist before anything
 * can be recorded about it. Persons and documents depend on nothing, so they sit between the two.
 *
 * **This is deliberately not one transaction, which is why it is not in `:data`.** A writer that
 * needs one belongs beside the repositories it composes, because the transaction seam is
 * `internal` there; this one needs none. Nothing is deleted and every write is either an upsert or
 * a statement recorded once, so delivery need only be at-least-once: a batch half-written is a
 * batch re-delivered, re-delivering it leaves content time where it was while advancing last-seen,
 * and a withdrawal repeated is a withdrawal ignored, because the store keeps the first. The
 * guarantee exactly-once would buy is already provided by the storage model, and buying it twice
 * would cost a transaction spanning a whole batch of an archive's history.
 */
class BatchWriter(
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val persons: PersonRepository,
    private val documents: DocumentRepository,
) {

    suspend fun write(batch: Batch) {
        batch.conversations.forEach { update ->
            conversations.upsert(update.conversation)
            // Guarded here because the port promises nothing about an empty list and the store
            // is free to answer either way. A header with nothing nested is what a renamed
            // conversation looks like when its chat has gone quiet, so this is reached rather than
            // theoretical — and the header above has already been written in this same iteration,
            // so the guard is about the empty call and not about ordering.
            if (update.messages.isNotEmpty()) {
                messages.upsert(update.conversation.externalId, update.messages)
            }
        }
        batch.persons.forEach { persons.upsert(it) }
        batch.documents.forEach { documents.upsert(it) }
        // Last, and unconditionally last: a message withdrawn in the same batch that carried it
        // must be stored before the withdrawal is recorded, or the write matches no row and the
        // statement is lost. The reverse order would be wrong only sometimes, which is worse.
        if (batch.withdrawn.isNotEmpty()) {
            messages.markWithdrawn(batch.withdrawn)
        }
    }
}
