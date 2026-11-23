package io.github.ragnorrs.mainbrain.domain.record

import java.time.Instant

/**
 * One message, as its source presented it.
 *
 * **A message carries no conversation of its own.** It is written through the conversation it
 * belongs to — see [MessageRepository.upsert] — and read back by querying that conversation.
 * Binding it positionally rather than by a field is what makes a message without its
 * conversation unrepresentable, instead of an ordering mistake every write path has to
 * remember to avoid.
 *
 * The author is an [ExternalId] pointing at a person record, held rather than resolved for the
 * same reason a conversation's parent is: the person may not have been captured yet.
 *
 * [sourceTime] is the only one of the three axes a message cannot lack. Recency filters read
 * it, and a message is the one record kind whose source always timestamps it.
 */
data class Message(
    val externalId: ExternalId,
    val sourceTime: Instant,
    val author: ExternalId? = null,
    val text: String? = null,
    val identifiers: List<Identifier> = emptyList(),
)
