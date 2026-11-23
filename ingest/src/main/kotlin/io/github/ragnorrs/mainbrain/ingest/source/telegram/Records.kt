package io.github.ragnorrs.mainbrain.ingest.source.telegram

import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.IdentifierType
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.Person
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId

// Platform shapes to records. Pure functions, no client and no store, which is what lets the
// rules below be tested by calling them rather than by standing a session up.

/**
 * The work unit a conversation is backfilled as.
 *
 * The same string as the conversation's native identifier, deliberately. A work unit names the
 * thing a re-read can be scoped to, and the thing a re-read is scoped to is a conversation — two
 * spellings of one identity would mean a translation table, and a translation table is the second
 * copy of an identity this build keeps refusing to hold.
 */
internal fun TelegramConversation.workUnit() = WorkUnitId(nativeId())

internal fun TelegramConversation.externalId() = ExternalId(Source.TELEGRAM, nativeId())

/**
 * A conversation's identifier: the chat, and the topic inside it where there is one.
 *
 * A topic is a conversation in its own right and shares its container's chat identifier, so the
 * chat alone would collapse every topic in a forum into one conversation — the one place the
 * client's own taxonomy lives, lost to a naming choice.
 */
private fun TelegramConversation.nativeId() =
    if (topicId == null) chatId.toString() else "$chatId/$topicId"

/**
 * A message's identifier, **qualified by its chat**.
 *
 * This is the one mapping rule whose absence corrupts the corpus rather than merely impoverishing
 * it. The platform numbers messages per chat, so the bare number recurs in every conversation;
 * handing it over unqualified would make the second chat's message collide with the first's. The
 * store refuses that collision rather than accepting it — see `MessageInAnotherConversation` —
 * so the failure is loud, but only because this function exists to make it unnecessary.
 *
 * Qualified by the **chat** and not by the topic. A supergroup numbers messages across the whole
 * group rather than per topic, so including the topic would give one message two identifiers as
 * it moved, and moving a message between topics is something the platform allows.
 */
internal fun TelegramMessage.externalId() = messageId(chatId, messageId)

/**
 * The same identifier, from the two numbers alone.
 *
 * A removal arrives as identifiers with no message behind them, so the rule above has to be
 * reachable without one. Two spellings of it would be two rules, and the second would be the one
 * nobody checked — so the message-shaped function delegates here rather than repeating the format.
 */
internal fun messageId(chatId: Long, messageId: Long) = ExternalId(Source.TELEGRAM, "$chatId/$messageId")

/** The person record an author points at, which is a different table from a conversation's. */
internal fun personId(userId: Long) = ExternalId(Source.TELEGRAM, userId.toString())

internal fun TelegramConversation.toRecord() = Conversation(
    externalId = externalId(),
    kind = when (kind) {
        TelegramConversationKind.DIRECT -> ConversationKind.DIRECT
        TelegramConversationKind.GROUP -> ConversationKind.GROUP
        TelegramConversationKind.CHANNEL -> ConversationKind.CHANNEL
        TelegramConversationKind.TOPIC -> ConversationKind.TOPIC
    },
    name = title,
    // Held as an identifier rather than resolved, because a topic can be captured before the
    // supergroup containing it and an unresolvable parent is an ordinary state.
    parent = topicOf?.let { ExternalId(Source.TELEGRAM, it.toString()) },
    identifiers = listOfNotNull(handle?.let { Identifier.of(IdentifierType.HANDLE, it) }),
    sourceTime = lastMessageAt,
)

/**
 * A message as a record.
 *
 * The author is held as an identifier and not resolved here, for the reason the type gives: the
 * person may not have been captured yet, and a batch is not ordered.
 */
internal fun TelegramMessage.toRecord() = Message(
    externalId = externalId(),
    sourceTime = sentAt,
    author = authorId?.let(::personId),
    text = text,
)

/**
 * A profile as a record, told whether it is the account the session reads through.
 *
 * Every claim goes through the normaliser and anything it refuses is dropped rather than stored
 * raw. A profile field is whatever a person typed, so unusable values are ordinary input — and a
 * claim that cannot match is worse than no claim, because it is counted as evidence by whatever
 * reconciles these later.
 *
 * No source time. A profile is a current state the platform does not timestamp, and inventing
 * the moment of capture would make every profile look as though it were authored today.
 *
 * **[account] is the session's own account, or `null` when the adapter could not learn it**, and
 * the distinction carries straight through to the record: unknown leaves the mark unset, while a
 * known account marks one record true and every other false. Compared by identifier rather than by
 * anything about the profile — the account that reads an archive authors most of it and appears
 * everywhere in it, so every softer test would be a guess dressed as a reading.
 */
internal fun TelegramPerson.toRecord(account: Long?) = Person(
    externalId = personId(userId),
    displayName = displayName,
    identifiers = handles.mapNotNull { Identifier.of(IdentifierType.HANDLE, it) } +
        listOfNotNull(phone?.let { Identifier.of(IdentifierType.PHONE, it) }),
    isSelf = account?.let { it == userId },
)

/**
 * The conversations this build does not read, by either of the two ways of saying it.
 *
 * **[chatIds] is what is kept out.** Two are excluded by requirement — the assistant's own chat
 * and the project coordination group — because both hold the system's own output rather than
 * material about anyone's business, and ingesting them would let the assistant cite itself.
 *
 * **[onlyChatIds] is what is let in, and it is empty almost always.** Naming it inverts the rule:
 * every conversation not in it is out of scope. That exists for a bounded first run — a handful of
 * conversations, read end to end, rather than an archive — because the alternative way to get a
 * small run is to list everything one does *not* want, which is a list nobody can keep correct.
 * An exclusion still wins over it, so the two can be set together without either having to know
 * about the other.
 *
 * Both are chat identifiers rather than titles. A title is renameable by anyone in the group, so a
 * rule written against one is a rule a rename silently disables; and the exclusion that matters
 * most is the one that must not lapse quietly.
 *
 * **Both are by chat, so each takes every topic inside it.** A coordination group that grew a
 * forum would otherwise re-enter the corpus one topic at a time, each with an identifier the
 * excluded set does not contain.
 */
internal class ExcludedConversations(
    private val chatIds: Set<Long>,
    private val onlyChatIds: Set<Long> = emptySet(),
) {

    operator fun contains(conversation: TelegramConversation) = excludesChat(conversation.chatId)

    operator fun contains(message: TelegramMessage) = excludesChat(message.chatId)

    /**
     * Whether a chat is out of scope, named by its number alone.
     *
     * Needed for a removal, which arrives as identifiers with no message behind them. Without it,
     * an out-of-scope chat's removals would be recorded against records that were never ingested
     * — writing a statement about material the corpus does not hold.
     */
    fun excludesChat(chatId: Long) =
        chatId in chatIds || onlyChatIds.isNotEmpty() && chatId !in onlyChatIds

    /**
     * Whether the chat behind this work unit is out of scope, for a plan read back from the store.
     *
     * A unit that does not parse as a chat is **not** excluded while only exclusions are set.
     * Nothing in this build writes such a unit, so the case is a hand-edited row or whatever a
     * later migration leaves behind — and reading it as excluded would quietly stop reading a
     * conversation, which is the failure that looks like success.
     *
     * With an allowlist in force the answer flips, for the same reason read the other way: a unit
     * nobody can match against the list is not one of the few that were asked for, and reading it
     * would quietly widen a run that was meant to be bounded.
     */
    fun excludes(unit: WorkUnitId): Boolean {
        val chatId = unit.chatId() ?: return onlyChatIds.isNotEmpty()
        return excludesChat(chatId)
    }

    /**
     * Which named conversations an enumeration did not contain, for whoever has to notice.
     *
     * An allowlist is written by hand, from identifiers that came from somewhere else, and the
     * platform marks a group's identifier differently from a bare one — so a mistyped or
     * unconverted entry is likely, and it looks exactly like a quiet conversation. Empty while no
     * allowlist is set, because then nothing was named and nothing can be missing.
     */
    fun namedButNotListed(listed: Set<Long>) = onlyChatIds - listed
}
