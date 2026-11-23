package io.github.ragnorrs.mainbrain.domain.record

/**
 * Messages in the corpus, always reached through the conversation holding them.
 *
 * As with every record kind, nothing here removes anything. Writes are upserts, with one
 * exception that is not a removal either: a source can say a message is gone, and [markWithdrawn]
 * records that it said so.
 */
interface MessageRepository {

    /**
     * Write [messages] into [conversation].
     *
     * The conversation is a parameter rather than a field on [Message] so that a message
     * without one cannot be expressed. It must already be stored — this does not create it,
     * and writing into a conversation the store has never seen fails rather than inventing a
     * header no source ever sent.
     */
    suspend fun upsert(conversation: ExternalId, messages: List<Message>)

    /** Every message captured in this conversation, oldest source time first. */
    suspend fun messagesIn(conversation: ExternalId): List<Message>

    /**
     * The newest [limit] messages of this conversation, oldest source time first.
     *
     * **Beside [messagesIn] rather than left to a caller taking the tail of it, because the cost is
     * the whole point.** A conversation in a real archive runs to tens of thousands of messages,
     * and a caller that wanted the last few of them would read all of them to find out — once per
     * conversation, to put a sample the size of a screen in front of a model. The bound belongs in
     * the statement.
     *
     * Ordered as [messagesIn] is, so a caller rendering either reads one shape: newest *selected*,
     * oldest *first*. Returning them newest-first would make the sample read backwards, and a
     * caller reversing it is the sort of detail that is right in one place and forgotten in the
     * next.
     *
     * **A withdrawn message is included, as it is in [messagesIn] and [messages].** The rule that
     * excludes one belongs to the reads that hand a body to a *reader* — what a source withdrew
     * must not be quoted back — and this is not one of them: what a sample is for is judging what a
     * conversation is about, and a message somebody later deleted is evidence of that like any
     * other. Filtering it here would also make two reads of one conversation disagree about what is
     * in it, which is the kind of difference nothing records.
     */
    suspend fun latestIn(conversation: ExternalId, limit: Int): List<Message>

    /**
     * The messages with these identifiers, oldest source time first.
     *
     * For a caller holding identifiers and no conversation — a derived pass works from a list
     * of records to revisit, and which conversations they fall in is not its question. An
     * identifier the store does not hold is absent from the result rather than an error: a
     * pass reading a watermark can name a record that has since been written again.
     */
    suspend fun messages(externalIds: List<ExternalId>): List<Message>

    /**
     * The conversation each of [messages] belongs to; absent ones are absent.
     *
     * The other direction of [messagesIn], and the one a derived pass needs. A message is written
     * through its conversation and carries none of its own — see [Message] — so a pass holding a
     * watermark's worth of message identifiers has no way to find the stretch of conversation
     * around them without asking.
     *
     * In bulk because that is how the question arises: a batch is tens of messages scattered
     * across a handful of conversations, and one query per message would be a round trip per
     * record to learn something the store can answer in one.
     */
    suspend fun conversationsOf(messages: List<ExternalId>): Map<ExternalId, ExternalId>

    /**
     * Record that the source said these messages are gone, touching nothing else.
     *
     * The one write here that is not an upsert of content, and it exists for the one event a
     * source reports with no content attached: a removal. The corpus removes nothing, so what is
     * stored is the statement itself — *the source said so, at this moment* — and nothing is
     * taken away.
     *
     * **Its own axis, and not last-seen.** Last-seen means a pass still found the record, and a
     * pass that is told the record is gone did not. Folding the two together would make the one
     * message the source explicitly withdrew look like the freshest in the corpus, and would
     * leave the weak signal — material unseen for several passes — firing for everything *except*
     * the case where removal is a fact. Content time must not move either, because nothing about
     * the content was restated.
     *
     * **The first statement is the one kept.** A re-delivered batch says the same thing again,
     * and a withdrawal that moved every time it was repeated would be a clock rather than a fact.
     *
     * *Removed* would be the wrong word for a store that keeps everything, and it is the one word
     * this port must never offer: what is recorded is that a source stopped offering the record,
     * which is why retrieval can decline to quote it while a question about history still finds
     * it.
     *
     * An identifier the store does not hold is skipped rather than an error, for the same reason
     * [messages] skips one: a source can report a removal for material never captured, and an
     * incomplete capture is indistinguishable from it.
     */
    suspend fun markWithdrawn(externalIds: List<ExternalId>)

    /** The store's own time bookkeeping for this message, or `null` if it holds none. */
    suspend fun timesOf(externalId: ExternalId): RecordTimes?

    /** This message's claims, each with when it was first and last seen. */
    suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier>

    /**
     * The pairs among [authors] that both wrote in one conversation.
     *
     * The hard negative, read from the corpus: two person records appearing as distinct
     * participants of the same conversation are two humans, and no accumulation of weak
     * agreement may outvote that.
     *
     * **It cannot tell that case from one human posting under two of their own accounts in one
     * chat**, and that pair becomes a hard negative no evidence can overturn. The trade is
     * deliberate: refusing to merge two accounts that are really one leaves a duplicate contact,
     * while merging two humans produces a fused one, and a duplicate is visible where a fusion is
     * not. Unmerging is not the remedy either — there is nothing to unmerge — so the remedy is a
     * correction interface, which is not built.
     *
     * Computed on every pass rather than stored, because the corpus is where it is visible and a
     * stored copy could only go stale. Returned as pairs rather than as each author's
     * conversations: the question is which pairs are forbidden, and a participant of five
     * hundred chats would otherwise be read back in full to answer it.
     *
     * Each pair appears once, in one order. A caller checking a pair it holds either way round
     * has to look both ways or normalise first.
     */
    suspend fun sharingAConversation(authors: List<ExternalId>): List<Pair<ExternalId, ExternalId>>
}

/**
 * Thrown when a message's native identifier is already stored under a different conversation.
 *
 * A native identifier is unique within its source — see [ExternalId] — and honouring that is
 * the adapter's job, not something the store can check in advance. What the store can do is
 * refuse the one consequence of getting it wrong: a platform that numbers messages per chat
 * yields the same bare number in every chat, and accepting the second one would move a stored
 * message into another conversation. That is corpus corruption which reads as ordinary data,
 * so it fails here instead, and the adapter's qualifying of the identifier is what fixes it.
 */
// `messageId` rather than `message`, which would hide `Throwable.message`.
class MessageInAnotherConversation(val messageId: ExternalId) : IllegalArgumentException(
    "A message ${messageId.source}/${messageId.nativeId} is already stored under a different " +
        "conversation. A native identifier has to be unique within its source: a platform that " +
        "numbers messages per chat must qualify the identifier by its chat, or two unrelated " +
        "messages share one and the corpus fuses them.",
)

/**
 * Thrown when messages are written into a conversation the store has never seen.
 *
 * Part of the port's contract rather than an implementation detail, because the alternative
 * behaviours are both worse and a caller has to know which it gets. Creating the conversation
 * would invent a header no source ever sent, and skipping the messages would lose material
 * while reporting success.
 */
class UnknownConversation(val conversation: ExternalId) : IllegalArgumentException(
    "No conversation ${conversation.source}/${conversation.nativeId} is stored. A message is " +
        "written through its conversation, and the conversation header has to be written first.",
)
