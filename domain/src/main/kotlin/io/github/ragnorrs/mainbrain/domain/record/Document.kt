package io.github.ragnorrs.mainbrain.domain.record

import java.time.Instant

/**
 * A file, as its source presented it.
 *
 * Documents are top-level in a batch rather than nested under a conversation, because a
 * document-only source has no conversations at all. An attachment instead carries
 * [messageRef], a reference to the message it arrived on, which the batch containing both
 * resolves.
 *
 * [text] is whatever the source already hands over. Extracting text from a file that does not
 * volunteer it is a separate concern, and arrives with the sources that need it.
 */
data class Document(
    val externalId: ExternalId,
    val name: String? = null,
    val mediaType: String? = null,
    val text: String? = null,
    val messageRef: ExternalId? = null,
    val identifiers: List<Identifier> = emptyList(),
    val sourceTime: Instant? = null,
)
