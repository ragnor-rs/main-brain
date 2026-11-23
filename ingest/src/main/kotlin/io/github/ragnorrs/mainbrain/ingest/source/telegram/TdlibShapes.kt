package io.github.ragnorrs.mainbrain.ingest.source.telegram

import it.tdlight.jni.TdApi
import java.time.Instant

// The library's types to the four shapes the seam crosses on.
//
// This is the layer `TelegramClient` names as the one no fixture covers: a recording taken below
// it would be the library's types again. So what happens here is kept to what cannot be got
// subtly wrong — reading a field, converting a unit, choosing between cases the protocol names
// explicitly — and every rule that *could* be got wrong lives above it, in `Records.kt`, where a
// test reaches it by calling a function.
//
// Two conversions are worth reading rather than skimming: a protocol time is seconds and a
// missing one is zero rather than absent, and a chat kind the protocol adds later must not be
// guessed at.

/** Seconds, which is how the protocol carries every time. Zero means absent, not the epoch. */
private fun Int.asInstant(): Instant? = takeIf { it > 0 }?.toLong()?.let(Instant::ofEpochSecond)

/**
 * A chat as the seam sees it, or `null` for a kind this build does not read.
 *
 * `null` rather than a fallback kind. The protocol gains chat kinds between releases, and a
 * secret chat — the one the protocol already has and this does not take — holds messages the
 * session cannot re-read anyway. Mapping an unknown kind onto `DIRECT` would put material in the
 * corpus under a shape nobody chose.
 *
 * No handle, deliberately. A chat's own username is not on this object: it belongs to the user or
 * the supergroup behind it, so filling it in would be a request per chat on every enumeration —
 * and the handle that matters for matching is the one on the person record, which is fetched.
 *
 * No topic either. Forum topics are not enumerated by this client, which is the one limitation
 * worth stating here rather than in a note somewhere: the mapping and the adapter both carry
 * topics, so a forum's messages are placed under the chat containing them until the enumeration
 * learns to ask for its topics. Nothing is lost by that; the client's own taxonomy is.
 */
internal fun TdApi.Chat.toShape(): TelegramConversation? {
    val kind = when (val chatType = type) {
        is TdApi.ChatTypePrivate -> TelegramConversationKind.DIRECT
        is TdApi.ChatTypeBasicGroup -> TelegramConversationKind.GROUP
        is TdApi.ChatTypeSupergroup -> if (chatType.isChannel) {
            TelegramConversationKind.CHANNEL
        } else {
            TelegramConversationKind.GROUP
        }
        else -> return null
    }
    return TelegramConversation(
        chatId = id,
        kind = kind,
        title = title?.takeIf { it.isNotBlank() },
        lastMessageAt = lastMessage?.date?.asInstant(),
    )
}

/**
 * A message as the seam sees it.
 *
 * The topic is read from the protocol's own topic field and not from the message thread, which
 * carries a different fact: every reply in a group has a thread, so threads would split an
 * ordinary conversation into one per reply chain. Only a forum topic is a conversation of its own.
 *
 * A message sent by a chat rather than a person — a channel post — has no author, which is an
 * ordinary state and not a lookup that failed.
 */
internal fun TdApi.Message.toShape() = TelegramMessage(
    chatId = chatId,
    messageId = id,
    topicId = (topicId as? TdApi.MessageTopicForum)?.forumTopicId,
    authorId = (senderId as? TdApi.MessageSenderUser)?.userId,
    sentAt = Instant.ofEpochSecond(date.toLong()),
    editedAt = editDate.asInstant(),
    text = content.text(),
)

/**
 * The words in a message, whatever carried them.
 *
 * A caption is text a person wrote, so a photograph with a caption is material and a photograph
 * without one is not. The media itself is out of scope for this build — nothing transcribes or
 * reads a file — so a content kind that is not listed here yields no text rather than a
 * placeholder, and the message is still stored: the record of who said something to whom at what
 * time is worth keeping without its attachment.
 */
private fun TdApi.MessageContent.text(): String? = when (this) {
    is TdApi.MessageText -> text.text
    is TdApi.MessagePhoto -> caption.text
    is TdApi.MessageVideo -> caption.text
    is TdApi.MessageAnimation -> caption.text
    is TdApi.MessageAudio -> caption.text
    is TdApi.MessageVoiceNote -> caption.text
    is TdApi.MessageDocument -> caption.text
    else -> null
}?.takeIf { it.isNotBlank() }

/**
 * A profile as the seam sees it.
 *
 * The number gains its `+`. The protocol carries a phone number as international digits with the
 * sigil stripped, and the normaliser above this refuses anything not in international form —
 * rightly, since guessing a country produces a number that matches a different person. Restoring
 * a sigil the protocol removed is a format conversion and not a guess, which is the whole reason
 * it is done here and not there.
 *
 * Both names are joined because one of them is routinely absent, and the display name is a label
 * rather than a key: nothing matches on it, and a trailing space would be the only thing a naive
 * join produced on half the profiles in an archive.
 */
internal fun TdApi.User.toShape() = TelegramPerson(
    userId = id,
    displayName = listOfNotNull(firstName, lastName)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .takeIf { it.isNotBlank() },
    handles = usernames?.activeUsernames?.toList().orEmpty(),
    phone = phoneNumber?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("+")) it else "+$it" },
)
