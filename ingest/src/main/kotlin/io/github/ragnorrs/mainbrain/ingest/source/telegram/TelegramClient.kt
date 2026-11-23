package io.github.ragnorrs.mainbrain.ingest.source.telegram

import java.time.Instant
import kotlin.time.Duration

/**
 * The slice of a Telegram client this adapter uses, in shapes of its own.
 *
 * **Why these types exist rather than the library's.** The library's own types reach the whole
 * protocol — thousands of generated classes, `Object` fields, and a message whose content is a
 * sealed hierarchy of forty-odd kinds. Mapping straight from them to records would put protocol
 * decoding and corpus decisions in one function, and would make every test construct protocol
 * objects to exercise a naming rule. So the protocol stops at the one class that
 * implements this interface against the library, these four shapes are what crosses, and the
 * mapping to records is a pure function over them.
 *
 * The cost is honest and worth stating: the conversion from library types into these is the one
 * layer no fixture covers, because a fixture recorded below this line would be the library's
 * types again. What that layer can get wrong is narrow — it reads fields and converts units —
 * and what sits above it, where the identifier and nesting rules live, is covered.
 *
 * Nothing here names a library type, so this file would satisfy the rule keeping Telegram types
 * inside `source/` even if it sat elsewhere. It sits here because it is this adapter's business
 * and no other module's.
 */
internal interface TelegramClient {

    /**
     * Every conversation the session can see, as of now.
     *
     * The whole list rather than a page, because this is what the work list is re-enumerated
     * from and an open work list needs the whole of it to notice what is new. A session's chat
     * list is thousands of entries at worst, which is a different order of magnitude from its
     * message history.
     *
     * **Including the ones a person has put away.** An archived conversation is still an
     * archive, and in a personal account it is where the old material is — so a list that
     * stopped at the ones on screen would quietly never backfill the part this build most
     * wants.
     */
    suspend fun conversations(): List<TelegramConversation>

    /**
     * The conversation behind [chatId], or `null` when it is not one this build reads.
     *
     * Enumeration cannot be the only way a conversation is learnt about. A message arrives for
     * a chat no list contains — put away, past whatever bound the enumeration has, or of a kind
     * the mapping refuses — and a message whose conversation is unknown cannot be stored at all,
     * because the store writes a message through its header. Without this the adapter's only
     * options would be to hold such a message forever or to drop it silently, and it is asked
     * for exactly then: once per chat, per pass.
     *
     * `null` is not a failure. It means the platform describes a conversation this build does
     * not read, which is the same answer as an exclusion and is treated as one.
     */
    suspend fun conversation(chatId: Long): TelegramConversation?

    /**
     * Messages in [chatId] older than [before], newest first, at most [limit] of them.
     *
     * Walking backwards is what backfill does, so the cursor is an upper bound rather than a
     * page number: `null` starts from the newest. Returning fewer than [limit] does not mean the
     * history is exhausted — the library pages against its own cache — and an empty result does.
     */
    suspend fun history(chatId: Long, before: Long?, limit: Int): List<TelegramMessage>

    /**
     * The message to walk back from, for a chat whose history has already been read to [reached].
     *
     * The walk's own cursor is a message, and a message identifier is not what survives a restart
     * — the backfill plan holds a source *time*, because that is the one fact about progress a
     * reader can act on. So resuming needs the platform to turn the time back into a position,
     * which is a thing only it can do, and this is the one call a pass makes at most once per
     * chat per process.
     *
     * `null` means no position older than [reached] could be identified. The adapter's answer to
     * that is to walk from the newest again, which costs request budget and loses nothing;
     * reading it as *finished* would abandon the rest of a history on the strength of a lookup
     * that failed.
     */
    suspend fun positionAt(chatId: Long, reached: Instant): Long?

    /** The profiles behind [userIds], omitting any the session cannot see. */
    suspend fun people(userIds: Set<Long>): List<TelegramPerson>

    /**
     * The account this session is signed in as — which of the people in the corpus is the one it
     * was read through.
     *
     * **Asked here rather than kept from the sign-in that already asks it.** `signInOnConsole` puts
     * the same question to the library and throws the answer away, using it only as the probe for
     * whether authorisation has finished — but that runs by hand, once, on the host, and a session
     * minted before this existed would carry nothing for a service to read. Asking spends nothing
     * against the budget that matters: this is not a sign-in attempt, and the library answers it
     * from the session it already holds.
     *
     * A refusal is a [TelegramRequestFailed] like any other, which is the adapter's cue to leave the
     * mark unset rather than guess at it.
     */
    suspend fun account(): Long

    /**
     * Whatever has happened since this was last called, at most [limit] events.
     *
     * New messages, edits and removals arrive on this one stream, which is why they are one
     * sealed type rather than three calls: they are already ordered with respect to each other
     * and splitting them would lose that.
     */
    suspend fun changes(limit: Int): List<TelegramChange>

    /**
     * Whether anything the platform has announced is still waiting to be handed over.
     *
     * **A short answer from [changes] does not mean the feed is empty**, which is why this exists
     * separately: that call also comes back short when one event cannot be resolved and is put
     * back. Freshness is a claim that nothing older than a moment is outstanding, and the only
     * honest basis for it is this question, asked after the drain.
     */
    suspend fun changesPending(): Boolean
}

/**
 * A conversation as the platform presents it.
 *
 * [topicOf] is set only for a forum topic, and is the chat the topic sits inside. It is the one
 * piece of structure that is not flat, and it carries the client's own taxonomy, so it is read
 * rather than flattened away.
 */
internal data class TelegramConversation(
    val chatId: Long,
    val topicId: Long? = null,
    val topicOf: Long? = null,
    val kind: TelegramConversationKind,
    val title: String? = null,
    val handle: String? = null,
    val lastMessageAt: Instant? = null,
)

internal enum class TelegramConversationKind { DIRECT, GROUP, CHANNEL, TOPIC }

/**
 * A message as the platform presents it.
 *
 * [messageId] is numbered **per chat** and is not unique on its own. Nothing here fixes that —
 * qualifying it is the mapping's job, and leaving the raw number in this type is what keeps the
 * two concerns apart rather than hiding one inside the other.
 *
 * [editedAt] is carried and never stored. Whether an edit moves content time is the store's
 * decision, taken by comparing what it holds against what arrives; an adapter that also sent an
 * edit time would be offering a second opinion on a question already answered.
 */
internal data class TelegramMessage(
    val chatId: Long,
    val messageId: Long,
    val topicId: Long? = null,
    val authorId: Long? = null,
    val sentAt: Instant,
    val editedAt: Instant? = null,
    val text: String? = null,
)

internal data class TelegramPerson(
    val userId: Long,
    val displayName: String? = null,
    val handles: List<String> = emptyList(),
    val phone: String? = null,
)

/** One event from the change feed. */
internal sealed interface TelegramChange {

    /** A message arrived or was edited. Both are the same instruction to a store that upserts. */
    data class Upserted(val message: TelegramMessage) : TelegramChange

    /**
     * Messages were removed at the source.
     *
     * Carries identifiers and no content, because the platform no longer has any to give. What
     * an adapter can do with that is the narrow thing the corpus allows: record that the source
     * said so, at this moment, and destroy nothing.
     */
    data class Removed(val chatId: Long, val messageIds: List<Long>) : TelegramChange
}

/**
 * The platform asked for a wait of a stated length.
 *
 * Part of the seam rather than the implementation, because it is the one failure the adapter acts
 * on differently from every other: the whole reason this system exists is that its predecessor
 * died on request limits, and a wait with a duration in it is an instruction, not a fault. The
 * adapter answers nothing until it has elapsed, which is what makes a limit cost one quiet pass
 * instead of a retry storm.
 *
 * The duration crosses as a duration. A client translating it into a deadline of its own would be
 * holding the clock the adapter is already holding, and the two would be free to disagree.
 */
internal class TelegramFloodWait(val retryAfter: Duration) : Exception(
    "The platform asked for a wait of $retryAfter before the next request.",
)

/**
 * One request was refused, for a reason that is about that request.
 *
 * The third of the three failures this seam declares, and the only one the adapter counts against
 * the conversation it was reading: a chat it has lost access to, a history it cannot page, a
 * message the platform will not return. A ceiling of those retires one conversation, which is
 * what keeps a single unreachable chat from stalling a plan.
 *
 * Anything *not* one of these three is a fault nobody classified and is left to propagate. That
 * is deliberate: counting an unclassified fault against a conversation would retire it for a bug
 * in this codebase, and the scheduler's own report says *unexpected* rather than claiming the
 * source misbehaved.
 */
internal open class TelegramRequestFailed(reason: String? = null, cause: Throwable? = null) :
    Exception(reason ?: "The platform refused the request.", cause)

/**
 * The session is not signed in, and no amount of retrying will sign it in.
 *
 * Distinct from every other failure for the one reason that matters operationally: a person has
 * to act, on the machine, and nobody does that without being told. It is reported as such rather
 * than retried, because a session file is a logged-in account and re-authentication is the one
 * thing this source needs a human for.
 */
internal class TelegramSignInNeeded(reason: String? = null) : Exception(
    "The Telegram session is not signed in" + (reason?.let { ": $it" } ?: "."),
)
