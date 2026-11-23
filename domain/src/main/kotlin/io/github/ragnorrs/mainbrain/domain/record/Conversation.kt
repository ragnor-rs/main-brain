package io.github.ragnorrs.mainbrain.domain.record

import java.time.Instant

/**
 * A thread of messages, as its source presented it.
 *
 * The parent is not decoration. A topic sits inside a supergroup, and the taxonomy a body of work
 * is organised by lives in the *names* of the conversations people share — one topic per major
 * client alongside thematic tracks where a source carries topics, and named groups where it does
 * not. A name that never enters the corpus cannot be recovered by re-deriving, only by re-reading
 * the source, so a header is carried whenever the source says something about it — with its
 * messages, or on its own when the source has renamed it.
 *
 * The parent is held as an [ExternalId] rather than as a reference the store resolves, because
 * a batch is not ordered and a topic can arrive before the supergroup containing it. An
 * unresolvable parent is a conversation whose container has not been captured yet, which is an
 * ordinary state and not an error.
 */
data class Conversation(
    val externalId: ExternalId,
    val kind: ConversationKind,
    val name: String?,
    val parent: ExternalId? = null,
    val identifiers: List<Identifier> = emptyList(),
    val sourceTime: Instant? = null,
)

/**
 * What kind of container a conversation is.
 *
 * [TOPIC] is the one whose parent is always set; the others are flat. Every kind but [DIRECT] can
 * carry a name somebody chose, and so can stand for a body of work — what a two-person exchange
 * displays is the counterparty, which nobody assigned.
 */
enum class ConversationKind {
    DIRECT,
    GROUP,
    CHANNEL,
    TOPIC,
}
