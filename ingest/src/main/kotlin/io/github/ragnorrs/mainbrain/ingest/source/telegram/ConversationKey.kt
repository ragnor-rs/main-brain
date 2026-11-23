package io.github.ragnorrs.mainbrain.ingest.source.telegram

import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId

// What a pass needs to say *which conversation* while it is still holding platform shapes. Apart
// from `Records.kt`, which turns those shapes into records: this is the vocabulary used before
// anything is a record, and nothing here appears in a batch.

/**
 * What identifies a conversation while a pass is in flight: the chat, and the topic within it.
 *
 * The pair rather than the native identifier string, because this puts a message under the header
 * it belongs to and both sides of that are platform shapes. Formatting them into a string to
 * compare them would make a parse out of a comparison.
 */
internal data class ConversationKey(val chatId: Long, val topicId: Long?)

internal fun TelegramConversation.key() = ConversationKey(chatId, topicId)

internal fun TelegramMessage.key() = ConversationKey(chatId, topicId)

/**
 * The chat a work unit names, or `null` when the unit does not parse as one.
 *
 * A unit is the conversation's own identifier, so the chat is its leading number — and a topic's
 * unit names the chat it sits inside, which is what a walk through history is scoped by, because
 * the platform pages a chat and not a topic.
 */
internal fun WorkUnitId.chatId(): Long? = value.substringBefore('/').toLongOrNull()
