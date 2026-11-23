package io.github.ragnorrs.mainbrain.ingest.source.telegram

import java.time.Instant

/**
 * The platform as a script: a chat list, a history per chat, a feed, and some profiles.
 *
 * This is the fixture the issue asks for, and it sits at the seam the adapter was shaped around —
 * so a recorded pass is four maps rather than a protocol capture. Recording below this line would
 * record the library's own types, which is the one layer no fixture can cover; what *is* covered
 * is everything above it, where the rules are.
 *
 * Two behaviours here are the platform's and are modelled rather than simplified, because the
 * adapter's paging depends on both: history comes back newest-first, bounded by a message to walk
 * back from, and the feed hands each event over exactly once.
 */
internal class ScriptedTelegramClient(
    var chats: List<TelegramConversation> = emptyList(),
    /**
     * Conversations the platform will describe but no list offers — archived beyond the
     * enumeration, or whatever the enumeration's own bound left out.
     */
    var unlisted: List<TelegramConversation> = emptyList(),
    /** Every message the session can reach, per chat, in any order. */
    var archive: Map<Long, List<TelegramMessage>> = emptyMap(),
    var profiles: Map<Long, TelegramPerson> = emptyMap(),
    /**
     * Who the session is signed in as.
     *
     * A real session always has one — it cannot read an archive otherwise — so this is not
     * nullable. What a test expresses instead is a session that will not *say*, which is
     * [faultOnAccount], because that is the state the adapter has to leave a mark unset for.
     */
    var account: Long = SIGNED_IN_AS,
) : TelegramClient {

    private val feed = mutableListOf<TelegramChange>()

    /** Every history request, as `(chat, walked back from, asked for)`. */
    val historyAsks = mutableListOf<Triple<Long, Long?, Int>>()

    /** Every resume, which is the call a restart makes and a continuing walk does not. */
    val positionAsks = mutableListOf<Pair<Long, Instant>>()

    val peopleAsks = mutableListOf<Set<Long>>()

    /** Every time the adapter asked which account the session reads through. */
    var accountAsks = 0
        private set

    /** Thrown by the next question about the account, once. */
    var faultOnAccount: Exception? = null

    /** Every chat asked for by identifier, which is how a test sees one resolved on demand. */
    val conversationAsks = mutableListOf<Long>()

    /** Thrown by the next chat lookup, once. */
    var faultOnConversation: Exception? = null

    var drains = 0
        private set

    /** Thrown by the next history request, once. */
    var faultOnHistory: Exception? = null

    /** Thrown by the next profile lookup, once. */
    var faultOnPeople: Exception? = null

    /** Thrown by every chat-list request while set, as a session that is not signed in does. */
    var faultOnChats: Exception? = null

    /** Put events on the feed, as the platform does between asks. */
    fun feed(vararg changes: TelegramChange) {
        feed += changes
    }

    override suspend fun conversations(): List<TelegramConversation> {
        faultOnChats?.let { throw it }
        return chats
    }

    /**
     * A chat the enumeration did not offer, which is what [unlisted] holds.
     *
     * Separate from [chats] on purpose: the seam exists because a message can arrive for a
     * conversation no list contains, so a fixture whose two sources were the same collection
     * could not express that case at all.
     */
    override suspend fun conversation(chatId: Long): TelegramConversation? {
        conversationAsks += chatId
        faultOnConversation?.let {
            faultOnConversation = null
            throw it
        }
        return (chats + unlisted).firstOrNull { it.chatId == chatId && it.topicId == null }
    }

    override suspend fun history(chatId: Long, before: Long?, limit: Int): List<TelegramMessage> {
        historyAsks += Triple(chatId, before, limit)
        faultOnHistory?.let {
            faultOnHistory = null
            throw it
        }
        return archive[chatId].orEmpty()
            .sortedByDescending { it.messageId }
            .filter { before == null || it.messageId < before }
            .take(limit)
    }

    /**
     * The last message sent no later than [reached], which is what the protocol answers.
     *
     * Modelled on the platform rather than on what the caller wants, because the two differ and
     * the difference decides whether a resume skips a message: [history] returns what is
     * *strictly older* than the message it is given, so the position has to be the message the
     * previous walk ended on — at or before the time reached, never after it.
     */
    override suspend fun positionAt(chatId: Long, reached: Instant): Long? {
        positionAsks += chatId to reached
        return archive[chatId].orEmpty()
            .filterNot { it.sentAt.isAfter(reached) }
            .maxByOrNull { it.messageId }
            ?.messageId
    }

    override suspend fun people(userIds: Set<Long>): List<TelegramPerson> {
        peopleAsks += userIds
        faultOnPeople?.let {
            faultOnPeople = null
            throw it
        }
        return userIds.mapNotNull { profiles[it] }
    }

    override suspend fun account(): Long {
        accountAsks++
        faultOnAccount?.let {
            faultOnAccount = null
            throw it
        }
        return account
    }

    override suspend fun changesPending(): Boolean = feed.isNotEmpty()

    /** Takes events off the feed, because the platform hands each over once. */
    override suspend fun changes(limit: Int): List<TelegramChange> {
        drains++
        val taken = feed.take(limit)
        repeat(taken.size) { feed.removeAt(0) }
        return taken
    }

    internal companion object {
        /** The account a scripted session reads through, unless a test says otherwise. */
        const val SIGNED_IN_AS = 900L
    }
}
