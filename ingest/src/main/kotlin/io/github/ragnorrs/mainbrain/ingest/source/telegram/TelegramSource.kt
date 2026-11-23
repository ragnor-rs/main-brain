package io.github.ragnorrs.mainbrain.ingest.source.telegram

import io.github.ragnorrs.mainbrain.domain.BackfillPlanRepository
import io.github.ragnorrs.mainbrain.domain.FailureKind
import io.github.ragnorrs.mainbrain.domain.IngestStateRepository
import io.github.ragnorrs.mainbrain.domain.Pass
import io.github.ragnorrs.mainbrain.domain.record.Batch
import io.github.ragnorrs.mainbrain.domain.record.ConversationUpdate
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Person
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.SourceReread
import io.github.ragnorrs.mainbrain.domain.record.SourceStream
import io.github.ragnorrs.mainbrain.domain.record.UnknownWorkUnit
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * The Telegram adapter: one pass stays current on the change feed and walks a little more history.
 *
 * Backfill is not a phase that finishes before incremental sync begins. The assistant needs
 * recent material on the first day, so every pass drains whatever the feed has and then spends
 * what pacing allows on one page of history — which is why there is no mode flag here and no
 * "backfilling" state to be stuck in.
 *
 * **What this owns and what it does not.** The position on the change feed belongs to the client
 * library, which is why no field here holds one; how far back each conversation has been walked
 * belongs to the backfill plan in Postgres, so it shares a restore point with the corpus it
 * describes. What is left for this class is the trade between the two, the pace, and the mapping
 * — and the mapping is in `Records.kt`, as pure functions, so the rules can be read without a
 * session.
 *
 * **Two walks, and what separates them is how deep each goes.** Both page backwards, because that
 * is the only direction the platform offers, and both run unasked. Backfill resumes at the ground
 * it has already taken and is never finished until a history is exhausted; a catch-up starts at the
 * newest message and is finished as soon as it meets what the corpus already holds, which is the
 * gap an outage leaves. So they are a page apart at the front and a history apart at the back,
 * which is why neither may read the other's position. The operation that never starts by itself is
 * neither of them: it is the re-read, which a person asks for.
 *
 * **Nothing is acknowledged until the next ask.** The port says an adapter advances its position
 * when it is next asked rather than when it hands a batch over, and everything that would record
 * progress is deferred to exactly that moment: the plan report, the history cursor and the
 * profiles believed fetched. Reporting progress at the moment of handing over would, after a
 * crash in between, leave the plan claiming ground the corpus never received — a gap nothing
 * detects, which only a re-read could close.
 *
 * **It answers nothing rather than retrying.** A wait with a stated duration is an instruction,
 * so it is honoured by returning `null` until it elapses; a session needing a person is reported
 * as such and then also answers nothing, because retrying never signs anyone in. Both are states
 * the scheduler already knows how to sit through, and neither is a failure it should see.
 */
@Suppress("TooManyFunctions")
internal class TelegramSource(
    /**
     * The session, opened on first use rather than on construction.
     *
     * Deferred for the same reason the composition root defers the store: one of this class's
     * operations needs no session at all. A re-read is a change to the plan, and opening a session
     * to perform one is not merely wasteful — the library starts consuming the account's
     * difference the moment it is built, into a queue a one-shot process then throws away, and
     * that difference is material no second copy of this build's position can recover. So the
     * command that exists to repair lost records would lose some.
     */
    client: Lazy<TelegramClient>,
    private val plan: BackfillPlanRepository,
    private val state: IngestStateRepository,
    private val excluded: ExcludedConversations,
    private val limits: TelegramLimits = TelegramLimits(),
    private val timing: Timing = Timing(),
) : SourceStream, SourceReread {

    /**
     * Unwrapped once here so the rest of this class reads as though it were handed a session.
     *
     * Every use below is on a path that has one by definition — a pass reads the source — so the
     * laziness is a fact about construction and not something each call site should restate.
     */
    private val client: TelegramClient by client

    override val source: Source = Source.TELEGRAM

    /** Author profiles already asked for in this process, so a quiet author is asked for once. */
    private val fetched = mutableSetOf<Long>()

    /**
     * The account this session reads through, once the library has said, and `null` until then.
     *
     * Held for the life of the process because it cannot change within one: the session directory
     * is one account's, and a different one means a different session and a different process. It
     * is not acknowledged like the things above — it decides nothing about what to send next, only
     * what a profile record says about itself, so there is no half-advanced state for a failed
     * batch to leave behind.
     */
    private var account: Long? = null

    /** Work units this process has already registered with the plan, for the same reason. */
    private val planned = mutableSetOf<WorkUnitId>()

    /** Whether an allowlist has been checked against an enumeration, which happens once. */
    private var namedChatsChecked = false

    /**
     * Conversations as the last enumeration offered them, or `null` before the first one.
     *
     * **This is a reading of the source, not a belief about the store**, and the distinction is
     * the one the batch port draws. An adapter may not decide what to send from what it thinks is
     * already persisted — that is wrong after a restore and wrong after a re-run. It may notice
     * that *the source itself has changed*, which is new information and the only way a rename
     * ever reaches the corpus: a conversation whose history is finished and whose chat has gone
     * quiet carries no messages for a header to travel with.
     *
     * `null` rather than empty before the first enumeration, so that the first pass of every
     * process asserts every header it can see. That is what makes a restore recoverable by a
     * restart, which is what the port asks for.
     *
     * **Moved on acknowledgement, like everything else that decides what to send.** Written at
     * hand-over instead, a rename carried in a batch the store then refused would be recorded as
     * offered and never sent again — and on a first pass that would falsify the guarantee above
     * for the life of the process.
     */
    private var offered: Map<ConversationKey, TelegramConversation>? = null

    /**
     * Where the walk back through each chat's history has got to, as a message to walk back from.
     *
     * Keyed by chat and not by work unit, because the platform's history is the chat's: one walk
     * covers every topic inside it, and a second cursor per topic would re-read the same pages.
     * In memory only — the durable form of this is the source time in the plan, which is what a
     * restart resumes from through [TelegramClient.positionAt].
     */
    private val cursors = mutableMapOf<Long, Long>()

    /**
     * Chats the corpus has fallen behind, and how far back closing each one has to read.
     *
     * The gap an outage leaves. A conversation finished at the back is never offered to the walk
     * again, so nothing else would ever look at it — and what arrived while this process was not
     * listening is stored as a conversation that went quiet.
     *
     * **Its own cursor, deliberately not [cursors].** Both walks page backwards from a position,
     * and these two positions are nowhere near each other — this one a page below the newest
     * message, that one as deep as the walk has reached. A shared cursor would hand each walk the
     * other's depth. One chat can need both at once — a forum topic enumerated after its siblings
     * finished is unfinished beside them — and the cost runs one way or the other depending on
     * which wrote last: a backfill resuming near the present re-reads everything between, and a
     * gap measured from a page deep in history closes without reading what it was about.
     *
     * Keyed by chat for the reason [cursors] is: one page covers every topic in it.
     */
    private val gaps = mutableMapOf<Long, FrontGap>()

    /**
     * Whether the comparison that fills [gaps] has been made, which happens once.
     *
     * Once per process and not once per pass, because a difference measured mid-run is as likely
     * to be material the feed is about to deliver as a hole — and acting on that would spend a
     * history page every pass to discover it. Before the first pass has drained anything, the only
     * thing a difference can be is the previous process's.
     */
    private var frontChecked = false

    /**
     * What the platform has handed over and the corpus has not yet been offered.
     *
     * The feed hands an event over once. Nothing can un-drain it, so between the drain and a batch
     * leaving this class, a failure anywhere — a profile lookup, a mapping fault — would otherwise
     * take the drained material with it. Buffering it the moment it arrives makes that a repeated
     * attempt instead of a loss, and is why the buffers are emptied at the very end of a pass,
     * after everything that can throw has run.
     *
     * The same buffer carries a message whose chat the platform would not answer about this pass,
     * which is the other way a message cannot be handed on yet. A chat the platform *does* answer
     * about and this build does not read is a different case and is dropped. In memory, so a
     * restart drops what is held — the same exposure as any unacknowledged batch, and the cure for
     * both is the same: neither moved a front edge, so the next start measures the gap they left
     * and closes it.
     */
    private val held = mutableListOf<TelegramMessage>()

    /** Withdrawals waiting on the same thing, and for the same reason. */
    private val heldWithdrawals = mutableListOf<ExternalId>()

    /** Progress to report once the batch carrying its material has been acknowledged. */
    private var unacknowledged: HandedOver? = null

    /** When the platform's stated wait elapses, before which this adapter answers nothing. */
    private var waitingUntil: Instant? = null

    /** When a history page was last read, which is what [TelegramLimits.betweenPages] bounds. */
    private var lastPageAt: Instant? = null

    /** When the feed was last drained to empty, which is all freshness may be claimed through. */
    private var lastDrainAt: Instant? = null

    /** When freshness and backfill completion were last reported. */
    private var lastReportAt: Instant? = null

    /**
     * For what this class decides without anyone asking: a refusal it swallowed, a chat it held
     * back, an allowlist entry that reached nothing.
     *
     * The process has a logging binding because the client library needs one, which makes this
     * the first place in the build where such a decision can be said out loud instead of being
     * described in a comment nobody reads at the time.
     */
    private val log = LoggerFactory.getLogger(TelegramSource::class.java)

    override suspend fun next(): Batch? = try {
        acknowledge()
        // Before anything is consumed, because the change feed cannot be un-drained and a report
        // thrown after the drain would take the arrivals with it. The report swallows what it can,
        // so the ordering is what covers the rest: a cancellation, and the plan read that feeds
        // it.
        reportState()
        if (waiting()) null else pass()
    } catch (wait: TelegramFloodWait) {
        // The deadline is set before the report, so a store that cannot accept the note does not
        // also cost us the wait. If the report does throw, the scheduler records the pass as
        // unclassified — a worse label on a pass that is already being sat out.
        waitingUntil = timing.clock.instant() + wait.retryAfter.toJavaDuration()
        state.recordPass(source, Pass.Failed(FailureKind.TEMPORARY, wait.message))
        null
    } catch (signIn: TelegramSignInNeeded) {
        state.recordPass(source, Pass.Failed(FailureKind.NEEDS_A_PERSON, signIn.message))
        null
    }

    /**
     * Read [unit] again from its newest message back, or the whole source when it is `null`.
     *
     * The plan is what makes a unit known, not this process's memory of one: a re-read is usually
     * asked for after a restart, and refusing a conversation this instance has not happened to
     * enumerate yet would refuse almost every real request.
     *
     * **A gap being caught up is dropped rather than left to run beside this.** A reopened unit is
     * outstanding again, so the walk takes its whole history from the newest — which covers
     * everything a catch-up was going to read and more. Two walks down one chat would pay twice
     * for the same pages, and the gap's own cursor would be measuring a front edge that this is
     * about to make meaningless.
     *
     * Progress pending acknowledgement for a re-read unit is dropped whole rather than edited. It
     * covers one page and the units of one chat, so dropping it costs that page's ground — a
     * re-walk of a hundred messages — while applying it after a re-read had undone it would move
     * the plan past material that is now expected to arrive again.
     *
     * **Everything this process remembers about what it has already sent goes too.** A re-read
     * exists for the case where a field was never captured and is now wanted, so an adapter that
     * kept believing it had already fetched every profile would re-send the records and none of
     * the claims on them. Re-sending more than strictly necessary costs upserts; re-sending less
     * than asked for is a re-read that did not happen.
     */
    override suspend fun reread(unit: WorkUnitId?) {
        // Refused before anything is forgotten: a request this adapter cannot honour must leave
        // it exactly as it was.
        if (unit != null && plan.planFor(source, unit) == null) throw UnknownWorkUnit(unit)
        fetched.clear()
        // And the headers, for the same reason the profiles go: a re-read asked for because a
        // header field was never captured would otherwise skip every conversation whose history is
        // finished and whose chat is quiet, which is most of them.
        offered = null
        // What an unacknowledged batch would license us to stop sending goes with them. Clearing
        // only what is already believed would let the pending acknowledgement reinstate the belief
        // one ask later — the same hole one field along.
        unacknowledged = unacknowledged?.copy(people = emptySet(), offered = emptyMap())
        if (unit == null) {
            plan.reopen(source)
            cursors.clear()
            gaps.clear()
            unacknowledged = unacknowledged?.copy(progress = null, front = null)
            return
        }
        plan.reopen(source, unit)
        unit.chatId()?.let {
            cursors -= it
            gaps -= it
        }
        // Both halves are dropped on the **chat**, not on the unit, because both of the things
        // they would reinstate are the chat's. A gap is, and so is the page that would close it.
        // So is the walk's cursor: a pending page for a sibling unit of this chat — one the
        // enumeration no longer lists, which this adapter supports — would put the cursor back
        // after the re-read cleared it, and the reopened unit would resume below it with
        // everything above never read again. That is a re-read reported and not performed, which
        // is the one outcome the refusal above exists to prevent.
        //
        // The front edge those pages carry goes with them, which costs nothing: a reopened unit
        // is walked from the newest again and that page reports the edge afresh. What the plan
        // already holds stays, for the reason the port gives — a re-read asks about the past.
        if (unacknowledged?.progress?.chatId == unit.chatId()) {
            unacknowledged = unacknowledged?.copy(progress = null)
        }
        if (unacknowledged?.front?.chatId == unit.chatId()) {
            unacknowledged = unacknowledged?.copy(front = null)
        }
    }

    /**
     * One pass: a page of history, whatever the feed has, and both as one batch.
     *
     * The order is the whole of this function's correctness. The enumeration is first because
     * everything else is placed against it — a message is stored through its conversation, so a
     * header nobody has seen is a message that cannot be written — and because the work list is
     * open: new conversations appear, and the plan learns of them here rather than from a queue
     * somebody has to refill. The gap comparison follows it and precedes both walks, because it
     * reads the enumeration against the plan and must do so before this process has consumed
     * anything: afterwards, a difference could be material the pass itself is about to deliver.
     * History comes before the drain so that a wait the platform states costs a pass with nothing
     * consumed in it. The buffers are emptied last, when nothing left can throw.
     */
    private suspend fun pass(): Batch? {
        // **First, because learning the account is what makes its own profile worth asking for
        // again**, and the author set below is computed from what this process believes it has
        // already fetched. Asked here rather than inside the profile lookup for that reason: a
        // pass with no new author would never reach it, so a session that answered a moment late
        // would leave its own account unmarked for the life of the process. Ahead of the drain as
        // well, so that a stated wait costs a pass with nothing consumed in it.
        val self = selfAccount()
        // Mutable, because placement may learn of a conversation this enumeration did not list.
        val listed = enumerate().toMutableMap()
        // Before either walk and after the enumeration, which is the only moment both halves of
        // the comparison are in hand and nothing has been consumed yet.
        findFrontGaps(listed)
        // A gap first, when there is one. It is the bounded work and the material an answer reads.
        val gap = catchUp()
        val walked = if (gap == null) readHistory(listed) else walkGap(gap.first, gap.second)
        drain()
        // Unfiltered, unlike the feed's arrivals: a walk is only ever scoped to a unit the
        // exclusions allow, so every message in a page comes from a chat already admitted. A
        // filter here would be a guard nothing can reach and no test could fail on.
        held += walked.messages

        val placed = place(held.latest(), listed)
        val carried = placed.carried
        val updates = listed.values
            .filter { it.key() in carried || it.headerIsNews() }
            .map { conversation ->
                ConversationUpdate(
                    conversation = conversation.toRecord(),
                    messages = carried[conversation.key()].orEmpty().map { it.toRecord() },
                )
            }
        val ground = walked.groundReached(carried)
        // A page the platform would not describe a conversation for earns no ground — and, left at
        // that, nothing else: the plan offers the least-failed unit first, so the same unit would
        // be chosen, the same page read, and the same page dropped, for the life of the process,
        // with no other conversation ever walked. Counting it is what lets the ceiling retire the
        // one conversation and the walk move on.
        //
        // Only for a page that was *dropped*. A page held back because a lookup was refused is
        // coming round again by design — counting that would retire a conversation for five
        // transient refusals, which is the opposite of what holding the message is for.
        if (walked.progress != null && ground == null && placed.dropped) {
            plan.reportFailure(source, walked.progress.units.first(), "a page of history could not be placed")
        }
        // The same hazard on the other walk, and it needs its own answer because the plan's
        // failure ceiling cannot reach a gap: a gap has priority over the backfill while it is
        // open, so a page that cannot be placed would be re-read every pass with no other
        // conversation ever walked. The gap is given up for this process instead — the next start
        // measures again, from a plan this pass has left exactly where it was.
        val front = walked.frontReached(carried)
        if (walked.front != null && front == null) {
            log.warn(
                "A page of {} could not be placed while catching it up, so its gap is left for " +
                    "the next start rather than read again every pass.",
                walked.front.chatId,
            )
            gaps -= walked.front.chatId
        }
        // The authors this pass carried, **and the account it reads through whether it wrote here or
        // not**. That one profile is the record the mark exists to reach, and an archive whose pages
        // happen to be somebody else's would otherwise hold the reader unmarked until they next
        // wrote — which, on a walk that has finished and a chat that has gone quiet, is never. It is
        // asked for once like any other profile: [fetched] is what stops a second ask.
        val wanted = (carried.values.flatten().mapNotNull { it.authorId } + listOfNotNull(self)).toSet()
        val profiles = profiles(wanted - fetched, self)
        // A withdrawal whose message is still waiting waits with it. The two buffers were emptied
        // together while nothing was ever kept, and keeping one without the other loses the
        // statement: the message is not in the batch, so the store matches no row, and when the
        // message finally lands it lands as though the source had never withdrawn it — a deleted
        // message indistinguishable from a live one, which is what a draft would then quote.
        val waiting = placed.keptForLater.map { it.externalId() }.toSet()
        val (keptWithdrawals, sentWithdrawals) = heldWithdrawals.distinct().partition { it in waiting }
        val batch = Batch(
            conversations = updates,
            persons = profiles.records,
            withdrawn = sentWithdrawals,
        )

        held.clear()
        held += placed.keptForLater
        heldWithdrawals.clear()
        heldWithdrawals += keptWithdrawals
        unacknowledged = HandedOver(
            progress = ground,
            front = front,
            people = profiles.asked,
            offered = listed,
        )
        // **Profiles count towards a batch being worth sending.** A pass that carried no message
        // can still have something to say — the account this session reads through is asked for
        // whether it wrote here or not — and answering nothing would drop that record while the
        // next ask recorded it as fetched, which loses it for the life of the process.
        return batch.takeIf {
            it.conversations.isNotEmpty() || it.withdrawn.isNotEmpty() || it.persons.isNotEmpty()
        }
    }

    /**
     * Every message under the conversation it belongs to, asking the platform where it must.
     *
     * The enumeration is not the only way a conversation becomes known: a message can arrive for
     * a chat no list contains, and a message whose conversation is unknown cannot be stored at
     * all, because the store writes a message through its header. So an unplaced message is the
     * one case worth a request — once per chat, per pass — and whatever comes back joins [listed]
     * so its header travels in the same batch as its messages.
     *
     * A chat the platform describes as a kind this build does not read is the same answer as an
     * exclusion, and is treated as one: the message is dropped. Holding it instead would be an
     * in-memory list that grows for the life of the process and is never stored — which reads
     * as ingestion working.
     */
    private suspend fun place(
        messages: List<TelegramMessage>,
        listed: MutableMap<ConversationKey, TelegramConversation>,
    ): Placed {
        val carried = mutableMapOf<ConversationKey, MutableList<TelegramMessage>>()
        val keptForLater = mutableListOf<TelegramMessage>()
        val notDescribed = mutableSetOf<Long>()
        val unreachable = mutableSetOf<Long>()
        messages.forEach { message ->
            val known = placement(message, listed)
            if (known != null) {
                carried.getOrPut(known) { mutableListOf() } += message
                return@forEach
            }
            if (message.chatId in notDescribed) return@forEach
            if (message.chatId in unreachable) {
                keptForLater += message
                return@forEach
            }
            if (keptForLater.size >= limits.heldCeiling) {
                // A chat whose lookup keeps failing would otherwise accumulate every message the
                // feed delivers for it, for the life of the process, re-grouped on every pass —
                // the unbounded hold this class refuses elsewhere. Past the ceiling the material
                // is dropped and said out loud, because a buffer nobody can see growing reads as
                // ingestion working.
                log.warn(
                    "Dropping a message for chat {}: {} are already waiting on chats the platform " +
                        "will not answer about, which is as many as this adapter holds.",
                    message.chatId,
                    keptForLater.size,
                )
                return@forEach
            }
            val resolved = try {
                resolve(message.chatId, listed)
            } catch (refusal: TelegramRequestFailed) {
                // **A refused lookup is not an answer about the chat**, and the difference decides
                // whether a message is dropped or kept. The platform saying it will not describe a
                // conversation is final; a request that failed says nothing, and the ones that
                // fail this way — a chat the session has just lost access to, a server that
                // answered badly — are the ones where a message is worth keeping for the next
                // pass. Letting the refusal out of here instead would end the pass, leave the
                // message buffered, and end every later pass on the same message: ingestion
                // stopped for good by one chat, with the buffer growing behind it.
                log.warn("Could not ask about chat {}; its messages wait for the next pass.", message.chatId, refusal)
                unreachable += message.chatId
                keptForLater += message
                return@forEach
            }
            if (resolved == null) {
                notDescribed += message.chatId
            } else {
                carried.getOrPut(resolved) { mutableListOf() } += message
            }
        }
        return Placed(carried, keptForLater, dropped = notDescribed.isNotEmpty())
    }

    /**
     * What one pass could place, and what it could not place yet.
     *
     * The second list is the whole reason this is a type: a message whose chat the platform would
     * not answer about is neither carried nor dropped, and the buffer has to get it back.
     */
    private data class Placed(
        val carried: Map<ConversationKey, List<TelegramMessage>>,
        val keptForLater: List<TelegramMessage>,
        /** Whether the platform answered about a chat and the answer was *not one this build reads*. */
        val dropped: Boolean,
    )

    /**
     * Learn one conversation the enumeration did not list, or refuse it.
     *
     * No exclusion check here, and that is not an omission: every message that reaches this point
     * came either off the feed, which filtered the exclusions already, or out of a history page
     * for a unit the exclusions allowed — and the chat asked about is the message's own. A second
     * check would be a guard nothing can reach and no test could fail on, which is the same thing
     * removed from the history page for the same reason.
     */
    private suspend fun resolve(
        chatId: Long,
        listed: MutableMap<ConversationKey, TelegramConversation>,
    ): ConversationKey? {
        val conversation = client.conversation(chatId) ?: return null
        listed[conversation.key()] = conversation
        return conversation.key()
    }

    /**
     * Whether this conversation's header is something the source has not said before.
     *
     * True for every conversation on the first enumeration of a process, and after that for one
     * the source now describes differently in any respect. Most often that is simply a new last
     * message, which costs a header upsert that was travelling with those messages anyway; what
     * it is *for* is a rename, a new topic or a parent that has appeared. Both halves matter: the
     * first re-asserts a corpus somebody restored, and the second is the only route a rename has
     * to the store once a conversation's history is finished and its chat has gone quiet.
     */
    private fun TelegramConversation.headerIsNews(): Boolean {
        val lastOffered = offered ?: return true
        return lastOffered[key()] != this
    }

    /**
     * The latest version of each message the buffer holds.
     *
     * A message can be in it twice: a page re-read after a failed pass arrives again, and an edit
     * can arrive on the feed for a message the same pass fetched from history. Keeping whichever
     * copy happens to be last in the list would be deciding by the order two unrelated calls were
     * made in — and the stale one wins that race, because history is read before the feed is
     * drained. The platform time is what settles it: the copy with the later edit, or the later
     * arrival where neither was edited.
     */
    private fun List<TelegramMessage>.latest(): List<TelegramMessage> =
        groupBy { it.chatId to it.messageId }
            .map { (_, copies) -> copies.maxBy { it.editedAt ?: it.sentAt } }

    /**
     * Take whatever the change feed has, into the buffers and no further.
     *
     * New messages, edits and removals arrive together and in order, which is why this is one
     * call: splitting them into three would lose the order they already have. An edit is an
     * arrival like any other — whether it moves content time is the store's decision, taken by
     * comparing what it holds against what arrives.
     */
    private suspend fun drain() {
        val changes = client.changes(limits.changePage)
        // Asked rather than inferred from the count. A short answer can also mean one event could
        // not be resolved and went back on the queue, so counting would claim an empty feed while
        // its head is stuck — and freshness is precisely the claim that nothing older than this
        // moment is outstanding.
        if (!client.changesPending()) lastDrainAt = timing.clock.instant()
        held += changes.arrivals().filterNot { it in excluded }
        heldWithdrawals += changes.withdrawals()
    }

    /**
     * Apply what the batch just acknowledged entitles us to record.
     *
     * Being asked again is the acknowledgement the port carries, so this is the only place any of
     * it moves. A failure here leaves every part of it pending and fails the ask: the scheduler
     * records that, asks again later, and nothing has been half-advanced in between.
     */
    private suspend fun acknowledge() {
        val handedOver = unacknowledged ?: return
        handedOver.progress?.let { applyProgress(it) }
        handedOver.front?.let { applyGapProgress(it) }
        fetched += handedOver.people
        offered = handedOver.offered
        unacknowledged = null
    }

    /**
     * Record what one page of history earned: the ground, the position, and the front where it has
     * one.
     *
     * The front edge travels with the ground rather than beside it, because the same page proves
     * both and the same guard has to cover both — a page the corpus was never offered must move
     * neither. It is reported for every unit of the chat, like the ground, and for the same
     * reason: the page is the chat's and nothing of that chat exists above it.
     */
    private suspend fun applyProgress(progress: Progress) {
        progress.units.forEach { plan.reportReached(source, it, progress.reached, progress.finished) }
        progress.front?.let { front -> progress.units.forEach { plan.reportFront(source, it, front) } }
        progress.cursor?.let { cursors[progress.chatId] = it }
    }

    /**
     * Record what one page of a gap earned: the edge if it closed, the next position if not.
     *
     * A closed gap moves the units this page covered — the ones found lagging, plus the chat's
     * currently listed ones, which is what [walkGap] collects — to what the source offered, and
     * then stops being a gap, in that order. A unit of the chat that is neither is left alone,
     * because nothing here read it against the source. One left open keeps its edge and takes the
     * position the page ended at, so the next pass carries on down rather than reading the same
     * stretch again.
     */
    private suspend fun applyGapProgress(front: FrontProgress) {
        if (front.closed) {
            front.units.forEach { plan.reportFront(source, it, front.offered) }
            gaps -= front.chatId
        } else {
            val gap = gaps[front.chatId]
            if (gap != null && front.cursor != null) gaps[front.chatId] = gap.at(front.cursor)
        }
    }

    /**
     * Whether the platform's stated wait is still running, which is answered with nothing.
     *
     * Clears the deadline as it passes, so the wait is read once rather than compared against the
     * clock for the rest of the process's life.
     */
    private fun waiting(): Boolean {
        val running = waitingUntil?.isAfter(timing.clock.instant()) == true
        if (!running) waitingUntil = null
        return running
    }

    /**
     * Every conversation the session lists, minus the excluded chats, registered with the plan.
     *
     * Re-enumerating on every pass is what an open work list needs — new conversations appear, and
     * nothing else would notice — and the port is built for it: it adds what is new and leaves
     * known units exactly as they are, so this cannot reset anyone's progress.
     *
     * Only units this process has not already registered are sent. Sending the whole list would be
     * the same instruction and the same result, and it would write one statement per conversation
     * every pass for the life of the process; the units already sent are a fact about the store and
     * not about any batch, so this needs no acknowledgement. A restart re-sends everything once,
     * which the port absorbs.
     *
     * Excluded chats are filtered before the plan sees them, so an exclusion is not merely unread
     * — it is never planned.
     */
    private suspend fun enumerate(): Map<ConversationKey, TelegramConversation> {
        val offered = client.conversations()
        reportAnythingNamedButNotOffered(offered)
        val listed = offered.filterNot { it in excluded }
        val fresh = listed.map { it.workUnit() }.filterNot { it in planned }
        if (fresh.isNotEmpty()) {
            plan.enumerated(source, fresh)
            planned += fresh
        }
        return listed.associateBy { it.key() }
    }

    /**
     * Find the conversations that moved while this process was not running.
     *
     * Once, on the first enumeration. The comparison is free of requests: an enumeration already
     * names each conversation's newest message, and the plan already records the source time its
     * records are complete to.
     *
     * **A shared second counts as a gap, which is why the test is not a strict one.** This
     * source's times are whole seconds and several messages of one conversation routinely share
     * one, so an edge standing at the same second as what the source now offers cannot be read as
     * level: a message that arrived in that second and never reached the corpus would be
     * invisible for ever, and nothing looks at a conversation's front twice. Treating equality as
     * a gap costs one page, which re-reads that second and upserts what is already there — the
     * cheap direction of a comparison that cannot be made exact, because the chat list offers a
     * time and no identifier, and the plan deliberately holds a source time rather than a
     * position.
     *
     * **Only a unit the backfill will not cover**, which means one the walk has a position in or
     * has finished. Those are the states where it cannot reach the front: it resumes below the
     * ground already taken and carries on away from the present, or it is never offered the unit
     * again. A unit that is unfinished and unwalked is the opposite case — the walk takes it from
     * the newest message, covering the front itself and reporting the edge from that page, so a
     * catch-up beside it would pay a second time for the same pages.
     *
     * **An unknown front edge is not a gap.** A walk needs something to stop at, and `null` gives
     * it nothing — so acting on one would read a whole history back under another name, which is
     * the thing the deliberate re-read exists to keep deliberate. This is also what keeps a cold
     * corpus quiet, since nothing in one has been reported complete to any time yet. It is said
     * out loud when the source has material for such a unit, because that pair is a conversation
     * nothing here can measure and only a re-read can fill.
     */
    private suspend fun findFrontGaps(listed: Map<ConversationKey, TelegramConversation>) {
        if (frontChecked) return
        val byUnit = listed.values.associateBy { it.workUnit() }
        val plans = plan.plansFor(source, byUnit.keys.toList())
        // Only a comparison that was actually made spends the one chance to make it. Marking it
        // before the read above would turn one refused query at startup into a process that never
        // looks for a gap again — and the material behind one is not looked for anywhere else.
        frontChecked = true
        val measurable = plans.filter { it.reached != null || it.finished }
        val unbounded = measurable.filter { it.newest == null && byUnit[it.unit]?.lastMessageAt != null }
        if (unbounded.isNotEmpty()) {
            log.warn(
                "{} conversations are complete to no time, so a catch-up has nothing to read back " +
                    "to and none is started for them: {}. The source has material for each, and a " +
                    "re-read is the deliberate way to fill one.",
                unbounded.size,
                unbounded.map { it.unit.value }.sorted(),
            )
        }
        measurable.forEach { item ->
            val conversation = byUnit[item.unit] ?: return@forEach
            val offered = conversation.lastMessageAt ?: return@forEach
            val edge = item.newest ?: return@forEach
            if (offered.isBefore(edge)) return@forEach
            val chatId = conversation.chatId
            // One gap per chat, holding the oldest edge among its lagging topics: the page covers
            // the whole chat, so a walk stopping at the newest of them would leave the others
            // short of their own. Taking the oldest reads one stretch for all of them.
            val existing = gaps[chatId]
            gaps[chatId] = FrontGap(
                reachBackTo = minOf(edge, existing?.reachBackTo ?: edge),
                offered = maxOf(offered, existing?.offered ?: offered),
                units = (existing?.units.orEmpty() + item.unit).distinct(),
            )
        }
        if (gaps.isNotEmpty()) {
            log.info(
                "{} conversations moved while nothing was reading them and are being caught up: {}.",
                gaps.size,
                gaps.values.flatMap { gap -> gap.units.map { it.value } }.sorted(),
            )
        }
    }

    /**
     * One page of a gap, walked down from the newest message rather than up from the oldest.
     *
     * Ahead of the backfill walk when both have work, because this one is bounded and that one is
     * not: a gap closes after the pages that cover it, while history always has another page. A
     * corpus provably missing recent material is also the worse of the two to leave standing —
     * every answer reads the front.
     *
     * The stop is the front edge, the configured depth, or an exhausted history; whichever comes
     * first closes the gap, and the units are then recorded complete to what the enumeration
     * offered. **That last part is what makes this converge**: a chat whose newest message is one
     * this build does not store would otherwise never reach its own edge, and would be walked
     * again on every start for the life of the deployment.
     *
     * A refused page drops the gap for this process rather than counting a failure against the
     * unit. The failure count is the walk's ceiling and it retires a conversation from backfill;
     * spending it here would retire one from *history* because it was briefly unreachable at the
     * front. The next start measures again.
     */
    private fun catchUp(): Pair<Long, FrontGap>? = gaps.entries.firstOrNull()?.let { it.key to it.value }

    /**
     * One gap's page, and what the plan will record of it once the batch is acknowledged.
     *
     * **It stops at the configured depth as well as at the edge**, because a front edge can sit
     * below that floor — a conversation whose history was captured before the bound was set, or
     * one dormant since before it, which then becomes active. Without the floor this walk would
     * fetch the stretch the deployment configured out, which is depth deciding itself inside an
     * adapter rather than being configuration.
     */
    private suspend fun walkGap(chatId: Long, gap: FrontGap): Walked {
        waitForThePace()
        lastPageAt = timing.clock.instant()
        val page = try {
            client.history(chatId, gap.cursor, limits.historyPage)
        } catch (failure: TelegramRequestFailed) {
            log.warn(
                "Could not read a page while catching {} up, so its gap is left for the next " +
                    "start rather than counted against its history: {}.",
                chatId,
                failure.message,
            )
            gaps -= chatId
            return Walked()
        }
        val oldest = page.minByOrNull { it.sentAt }?.sentAt
        val reachedEdge = oldest != null && !oldest.isAfter(gap.reachBackTo)
        val reachedDepth = oldest != null && limits.reachedDepth(oldest)
        return Walked(
            messages = page,
            front = FrontProgress(
                chatId = chatId,
                // Only the units this gap was opened for. The page is the chat's, so a unit that
                // was level is level over the same stretch and `GREATEST` would make the write a
                // no-op — but a unit the comparison *skipped*, for having no edge of its own, is
                // one this walk never read down to. Handing it an edge would be the plan claiming
                // material nothing has looked for.
                units = gap.units,
                cursor = page.minByOrNull { it.messageId }?.messageId,
                closed = page.isEmpty() || reachedEdge || reachedDepth,
                offered = gap.offered,
            ),
        )
    }

    /**
     * Say once if an allowlist names conversations the platform never offered.
     *
     * An allowlist is typed by hand from identifiers that came from somewhere else, and the
     * platform marks a group's identifier differently from a bare one — so an entry that reaches
     * nothing is the likely mistake, and it is indistinguishable from a conversation that has
     * simply gone quiet. Once per process: the answer cannot change without a restart, since the
     * list is read at startup.
     */
    private fun reportAnythingNamedButNotOffered(offered: List<TelegramConversation>) {
        if (namedChatsChecked) return
        namedChatsChecked = true
        val missing = excluded.namedButNotListed(offered.map { it.chatId }.toSet())
        if (missing.isNotEmpty()) {
            log.warn(
                "TELEGRAM_ONLY_CHATS names {} conversations the session does not list: {}. " +
                    "Nothing will be read for them — check the identifiers, remembering that a " +
                    "group's is negative and a supergroup's begins -100.",
                missing.size,
                missing.sorted(),
            )
        }
    }

    /**
     * One page of history for the unit with the best claim on it, or nothing.
     *
     * Three reasons to read nothing, and all are ordinary: the plan has no outstanding unit, no
     * outstanding unit names a chat, or the one page asked for was refused. The second is the
     * reason this picks the first unit that *parses* rather than the first unit outright — a row
     * naming no chat would otherwise sit at the head of the list and stall the walk behind it. The
     * pace is not among them: it is waited out rather than answered with nothing, which is what
     * makes it the pace at all.
     *
     * A failure on the page is counted against the unit and the pass carries on with whatever the
     * feed gave it. That is what retires an unreachable conversation instead of retrying it
     * forever, and it is why one bad conversation cannot stop incremental sync.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun readHistory(listed: Map<ConversationKey, TelegramConversation>): Walked {
        val (item, chatId) = plan.outstanding(source, limits.failureCeiling, limits.outstandingPage)
            .filterNot { excluded.excludes(it.unit) }
            .firstNotNullOfOrNull { item -> item.unit.chatId()?.let { item to it } }
            ?: return Walked()
        waitForThePace()
        lastPageAt = timing.clock.instant()
        // Whether this page was taken from the newest message, which is the one case where a walk
        // proves something about the front: there is nothing above the newest, so a page starting
        // there leaves the corpus complete down from it. Read off the position actually used
        // rather than inferred from the plan, because a stored time the platform can place no
        // message at or before also starts from the newest.
        var fromNewest = false
        val page = try {
            // The cursor this process already holds, or the plan's source time turned back into a
            // position by the platform. The second is the resume after a restart, and it is why
            // the durable half of this walk can be a time rather than a message number.
            val before = cursors[chatId] ?: item.reached?.let { client.positionAt(chatId, it) }
            fromNewest = before == null
            client.history(chatId, before, limits.historyPage)
        } catch (failure: TelegramRequestFailed) {
            // Only this one is counted against the conversation. A stated wait and a session
            // needing a person are the whole source's state, not this chat's fault, and a
            // conversation retired for the platform having been busy is one nothing re-reads.
            plan.reportFailure(source, item.unit, failure.message)
            return Walked()
        }
        // Every unit of this chat, because one walk covers the whole of its history: the platform
        // pages a chat and not a topic, so the siblings of the unit asked for are as far back as
        // it is. Recording progress for them as well is what stops a forum's topics each paying
        // for the same pages. The unit asked for is in the list whether or not the session still
        // lists its chat, so a conversation that has left the chat list records its own progress
        // rather than being walked over the same page forever.
        val units = (listOf(item.unit) + listed.values.filter { it.chatId == chatId }.map { it.workUnit() }).distinct()
        val oldest = page.minByOrNull { it.sentAt }?.sentAt
        return Walked(
            messages = page,
            progress = Progress(
                units = units,
                chatId = chatId,
                // Null when the page is empty, which the store reads as *no new ground* and leaves
                // the stored time standing. Reporting the request's own cursor instead would be
                // inventing a source time out of a position.
                reached = oldest,
                // Exhausted, or as deep as configuration asks for. The page that reaches the
                // depth is kept whole rather than trimmed: it has already been fetched, which is
                // the cost depth exists to bound, and storing what came with it is local compute.
                finished = page.isEmpty() || oldest != null && limits.reachedDepth(oldest),
                cursor = page.minByOrNull { it.messageId }?.messageId,
                // Only from a page that started at the newest message, and then its own newest:
                // nothing exists above it, so everything from there down to this page's oldest is
                // now held. An empty one proves nothing and says so with a null — a conversation
                // the platform offers no message for is complete to no time, not to now.
                front = if (fromNewest) page.maxOfOrNull { it.sentAt } else null,
            ),
        )
    }

    /**
     * Hold until the pace allows another history request.
     *
     * **Waiting rather than skipping, and the difference is thirty-fold.** The scheduler ends a
     * sweep as soon as a round stores nothing and then waits out its own idle interval — so an
     * adapter that answered *nothing right now* because its pace had not elapsed got one page per
     * idle interval, whatever the pace said. A minute per hundred messages, where two seconds was
     * configured. Holding the turn instead keeps material flowing every round, which keeps the
     * sweep alive, which makes this interval the actual pace: exactly what the port means by the
     * scheduler deciding when to ask and the adapter deciding what is safe to answer.
     *
     * Only ever reached with a unit to walk, so this never holds a turn for nothing: a source
     * with no outstanding work answers nothing immediately and lets the sweep end.
     *
     * The wait is a parameter rather than a call to `delay`, so that a test can assert the pace
     * instead of living through it.
     */
    private suspend fun waitForThePace() {
        val earliest = lastPageAt?.plus(limits.betweenPages.toJavaDuration()) ?: return
        val remaining = java.time.Duration.between(timing.clock.instant(), earliest)
        if (!remaining.isNegative && !remaining.isZero) timing.pause(remaining.toKotlinDuration())
    }

    /**
     * The profiles behind authors seen for the first time.
     *
     * Asking is recorded whether or not a profile came back, which [acknowledge] does with the
     * identifiers rather than the records. A user the session cannot see would otherwise be asked
     * for on every pass for as long as their messages keep arriving, which is the retry storm
     * this adapter exists to avoid in miniature.
     *
     * **A refused lookup costs the profiles and never the messages.** An author whose record has
     * not arrived is an ordinary state — a message points at a person by identifier and the store
     * resolves it later — while messages waiting on a profile lookup that keeps failing would
     * wait indefinitely.
     *
     * A stated wait or a session needing a person is a different matter and is left to propagate:
     * those say the source itself is unavailable, so there is nothing to be gained by handing over
     * part of a pass, and the buffer is what makes stopping safe.
     *
     * The refusal is dropped rather than reported, because at this seam it says the same thing as
     * the ordinary case the port already describes: the session cannot see that profile. Nothing
     * is marked as asked for, so the next pass tries again — and the pass's own outcome is the
     * scheduler's to record, which is why there is no second report of it here.
     */
    private suspend fun profiles(authors: Set<Long>, self: Long?): Profiles {
        if (authors.isEmpty()) return Profiles()
        return try {
            Profiles(client.people(authors).map { it.toRecord(self) }, authors)
        } catch (ignored: TelegramRequestFailed) {
            Profiles()
        }
    }

    /**
     * The account this session reads through, asked for at most once per pass.
     *
     * **Asked in a pass rather than at startup**, because a pass is where a refusal is already an
     * ordinary outcome: the composition root would otherwise have to decide what to do about a
     * session that is up and not yet answering, which is exactly the state the scheduler exists to
     * sit through.
     *
     * **A refusal leaves the mark unset and is asked again next pass.** Nothing is remembered about
     * having failed, which is the opposite of how a profile lookup is remembered — and for the
     * reason that makes the retry storm this adapter avoids impossible here: the question is the
     * library's own session rather than a request about somebody, it is asked once per pass at
     * most, and the only state it clears by is one a person has to fix. Marking it as asked would
     * instead mean a session that answered a moment too late read its whole archive as *not known
     * to be anybody's*.
     *
     * A stated wait or a session needing a person propagates, as it does everywhere else in this
     * class: both say the source itself is unavailable, and the pass is not worth continuing.
     */
    private suspend fun selfAccount(): Long? {
        account?.let { return it }
        account = try {
            client.account()
        } catch (refused: TelegramRequestFailed) {
            log.warn("The session did not say which account it reads through; profiles are left unmarked.", refused)
            null
        }
        // **The account's own profile is forgotten the moment the account is learnt.** A pass whose
        // question was refused has already sent that profile unmarked and recorded it as fetched —
        // and a profile believed fetched is never asked for again in this process, so the mark
        // would wait for a restart nothing is going to ask for. Forgetting the one identifier costs
        // a single lookup, and it is why the caller asks this before it works out which authors are
        // new: the record is then re-sent by the very pass that learnt who it belongs to.
        account?.let { fetched -= it }
        return account
    }

    /**
     * Report how current this source is and how far backfill has reached.
     *
     * Both are read off the plan, in one scan, with no failure ceiling: a unit skipped for failing
     * is still a unit whose history is missing, so counting it as complete would report a partial
     * corpus as a whole one. `complete` with no time reached means the walk is finished and
     * nothing bounds it; the same `null` with `complete` false means nothing has been walked yet,
     * and the pair of fields is what tells those apart.
     *
     * Nothing is reported until something has been enumerated, which is what the first line
     * enforces: before that the plan is empty, so the backfill report would say *complete* of a
     * source nobody has read. Freshness waits for more — a feed drained to empty — and the two
     * are deliberately not one condition: how far a walk has got is a fact about the plan, and
     * tying it to the feed left a busy account's catch-up reporting nothing at all, which reads
     * as a store that cannot be written rather than as a source still arriving.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun reportState() {
        if (planned.isEmpty()) return
        val now = timing.clock.instant()
        if (lastReportAt?.plus(limits.reportEvery.toJavaDuration())?.isAfter(now) == true) return
        try {
            val outstanding = plan.outstanding(source, failureCeiling = Int.MAX_VALUE, limit = Int.MAX_VALUE)
                .filterNot { excluded.excludes(it.unit) }
            val frontiers = outstanding.map { it.reached }
            state.reportBackfill(
                source = source,
                complete = outstanding.isEmpty(),
                // The most recent frontier among the unfinished units, because history is complete
                // everywhere only as far back as the least-walked conversation. One unit with
                // nothing walked leaves the whole source unbounded.
                reached = if (frontiers.any { it == null }) null else frontiers.filterNotNull().maxOrNull(),
            )
            // Freshness only once the feed has been drained to empty at least once in this
            // process. Backfill above does not wait for that — how far a walk has got is a fact
            // about the plan, and tying it to the feed meant a busy account's catch-up reported
            // nothing at all, which reads as a store that cannot be written.
            // A unit with an open gap is lagging as surely as one still being walked, and it is
            // absent from the list above because that one is the backfill's. Leaving it out would
            // report the source current while this adapter is paging through the hole it found —
            // which is exactly the state the new edge exists to make sayable.
            lastDrainAt?.let { drained ->
                val lagging = (outstanding.map { it.unit } + gaps.values.flatMap { it.units })
                    .distinct()
                    .filterNot { excluded.excludes(it) }
                state.reportFreshness(source, completeThrough = drained, lagging = lagging)
            }
            // Only a report that landed spends the window. Marking it before the writes would turn
            // one failure into a reporting silence for however long the interval is — and marking
            // it nowhere, which is what an assignment after a `throw` amounts to, makes every pass
            // scan the whole plan and write both rows again.
            lastReportAt = now
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // Best-effort, for the reason the scheduler gives for its own reports: the state row
            // and the corpus are the same Postgres, so a store that cannot take a note about the
            // reading is usually one that cannot take the material either — and letting the note
            // fail the pass would stop the reading to protect a report about it.
            log.warn("Could not report how current {} is; the reading continues.", source, failure)
        }
    }

    /**
     * The conversation a message belongs under, or `null` when the session lists none.
     *
     * The topic first and the chat second, which is not a guess about the platform but the two
     * shapes a message arrives in: a message in a forum names the topic it sits in, while the
     * same chat's messages outside any topic name only the chat. Trying the topic alone would
     * leave those unplaceable; trying the chat alone would collapse a forum into one conversation.
     */
    private fun placement(
        message: TelegramMessage,
        listed: Map<ConversationKey, TelegramConversation>,
    ): ConversationKey? = message.key().takeIf { it in listed }
        ?: ConversationKey(message.chatId, topicId = null).takeIf { it in listed }

    private fun List<TelegramChange>.arrivals(): List<TelegramMessage> =
        filterIsInstance<TelegramChange.Upserted>().map { it.message }

    /**
     * Removals as identifiers, which is all a removal has to give.
     *
     * Filtered by chat rather than by [ExcludedConversations.contains], because a removal carries
     * no message to ask about — and an excluded chat's withdrawal must not be recorded against a
     * record that was never ingested.
     */
    private fun List<TelegramChange>.withdrawals(): List<ExternalId> =
        filterIsInstance<TelegramChange.Removed>()
            .filterNot { excluded.excludesChat(it.chatId) }
            .flatMap { removal -> removal.messageIds.map { messageId(removal.chatId, it) } }

    /**
     * What one pass handed over and may record once it is acknowledged.
     *
     * One object rather than loose fields, so that *handed over and not yet acknowledged* is a
     * single state. Independent fields would allow the combination where the cursor moved and the
     * profiles did not, which is a half-acknowledged batch — exactly what this contract exists to
     * make unrepresentable.
     */
    private data class HandedOver(
        val progress: Progress?,
        val front: FrontProgress?,
        val people: Set<Long>,
        val offered: Map<ConversationKey, TelegramConversation>,
    )

    /**
     * A chat the corpus has fallen behind on, as this process is working through it.
     *
     * @property reachBackTo the front edge the walk has to meet, which is where the corpus's own
     *   newest material for this chat begins.
     * @property offered the newest message the source named for it, which is what its units are
     *   recorded complete to once the gap closes.
     * @property units the lagging conversations in this chat — several when it is a forum.
     * @property cursor the message the next page comes down from, or `null` to start at the newest.
     */
    private data class FrontGap(
        val reachBackTo: Instant,
        val offered: Instant,
        val units: List<WorkUnitId>,
        val cursor: Long? = null,
    ) {
        fun at(cursor: Long) = copy(cursor = cursor)
    }

    /** One page of a gap, as the plan and the gap's own cursor will record it. */
    private data class FrontProgress(
        val chatId: Long,
        val units: List<WorkUnitId>,
        val cursor: Long?,
        val closed: Boolean,
        val offered: Instant,
    )

    /** Profiles fetched in one pass, and the identifiers the asking may be recorded for. */
    private data class Profiles(
        val records: List<Person> = emptyList(),
        val asked: Set<Long> = emptySet(),
    )

    /**
     * What a history read produced: the messages, and the ground to record once they are stored.
     *
     * The progress travels with the page rather than being written where it was computed, because
     * recording it is the next ask's business. Returning it is what keeps that rule visible at the
     * one call site instead of leaving a field to be set from two places.
     */
    private data class Walked(
        val messages: List<TelegramMessage> = emptyList(),
        val progress: Progress? = null,
        val front: FrontProgress? = null,
    ) {

        /**
         * The ground this page earns, which is none unless the batch carries all of it.
         *
         * The last guard on the rule the whole class is shaped around. A page whose messages did
         * not all reach the batch — one of them for a conversation the platform would not
         * describe — would otherwise have its progress recorded anyway, and the plan would claim
         * history the corpus never received. The page is re-read instead, which costs a request
         * and loses nothing.
         */
        fun groundReached(carried: Map<ConversationKey, List<TelegramMessage>>): Progress? =
            progress?.takeIf { allCarried(carried) }

        /**
         * The same guard over a gap's page, and it matters more here than above.
         *
         * A page held back costs the backfill walk a re-read of one page. Closing a gap over a
         * page that did not reach the batch costs the messages themselves: the edge moves past
         * them, the gap is gone, and nothing looks at a finished conversation's front again.
         */
        fun frontReached(carried: Map<ConversationKey, List<TelegramMessage>>): FrontProgress? =
            front?.takeIf { allCarried(carried) }

        private fun allCarried(carried: Map<ConversationKey, List<TelegramMessage>>): Boolean {
            val handedOn = carried.values.flatten().map { it.chatId to it.messageId }.toSet()
            return messages.all { message -> (message.chatId to message.messageId) in handedOn }
        }
    }

    /**
     * One page's worth of ground, as the plan and the cursor will record it.
     *
     * @property front the source time the corpus is complete to once this page is stored, which
     *   only a page taken from the newest message can establish and which is `null` otherwise.
     */
    private data class Progress(
        val units: List<WorkUnitId>,
        val chatId: Long,
        val reached: Instant?,
        val finished: Boolean,
        val cursor: Long?,
        val front: Instant? = null,
    )
}

/**
 * How this adapter reads the clock and how it spends time.
 *
 * One parameter rather than two, because they are the same concern seen twice: a pace is read off
 * the first and taken out of the second. Keeping them together is also what keeps a test honest —
 * one that paused without moving the clock would be measuring a clock that never advances, and
 * would agree with an adapter that never waited at all.
 *
 * @property clock where every time this adapter reports comes from.
 * @property pause how it holds a turn. `delay` in production, and in a test whatever lets the pace
 *   be asserted rather than lived through.
 */
internal data class Timing(
    val clock: Clock = Clock.systemUTC(),
    val pause: suspend (Duration) -> Unit = { delay(it) },
)

/**
 * What this adapter may spend, how fast, and how far back.
 *
 * None of it is the adapter's own policy: the scheduler decides when to ask, and these decide
 * what is safe to answer. The defaults are deliberately unambitious — the system exists because
 * its predecessor died on request limits, so the cost of being slow is a longer backfill while
 * the cost of being fast is an account that stops answering.
 *
 * Two of these are settable from the environment, and they are the two whose right value depends
 * on the account rather than on the code: the pace, which a flood wait is the feedback on, and
 * the depth. The rest are constants until something asks for them to be otherwise — a knob
 * nobody has needed is a value in two places and a question in every review.
 *
 * @property historyPage how many messages one history request asks for.
 * @property changePage how many feed events one drain takes.
 * @property betweenPages the least time between two history requests, which is what keeps a sweep
 *   with material every time it asks from walking history as fast as the store can write.
 * @property failureCeiling how many consecutive failures retire a conversation from the plan.
 * @property outstandingPage how much of the work list one pass reads to choose a unit from.
 * @property reportEvery how often freshness and backfill completion are written.
 * @property heldCeiling how many messages may wait on chats the platform will not answer about.
 *   A bound rather than a policy: the material is worth keeping while a refusal might be
 *   transient, and worth dropping loudly rather than accumulating for the life of the process.
 * @property reachBackTo the source time to stop walking at, or `null` for as far as the archive
 *   allows. **This is depth, not the recency horizon**: what it bounds is request budget, because
 *   indexing whatever a walk returns is local compute while fetching it is not. The horizon
 *   bounds what inference later spends the model budget on, and the two numbers answer to
 *   different costs.
 */
internal data class TelegramLimits(
    val historyPage: Int = 100,
    val changePage: Int = 100,
    val betweenPages: Duration = 2.seconds,
    val failureCeiling: Int = 5,
    val outstandingPage: Int = 16,
    val reportEvery: Duration = 1.minutes,
    val heldCeiling: Int = 1_000,
    val reachBackTo: Instant? = null,
) {

    /** Whether a walk that has got back to [reached] has gone as deep as it was asked to. */
    fun reachedDepth(reached: Instant) = reachBackTo?.let { !reached.isAfter(it) } == true
}
