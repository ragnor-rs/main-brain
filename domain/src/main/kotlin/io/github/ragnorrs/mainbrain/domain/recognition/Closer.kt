package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.BatchOutcome
import io.github.ragnorrs.mainbrain.domain.Collected
import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.Effort
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.ModelRequest
import io.github.ragnorrs.mainbrain.domain.Submission
import io.github.ragnorrs.mainbrain.domain.SubmissionRepository
import io.github.ragnorrs.mainbrain.domain.SubmissionStage
import io.github.ragnorrs.mainbrain.domain.SubmittedWindow
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.entity.Closure
import io.github.ragnorrs.mainbrain.domain.entity.ClosureCandidate
import io.github.ragnorrs.mainbrain.domain.entity.ClosureRepository
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.Weighed
import io.github.ragnorrs.mainbrain.domain.entity.firstStated
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.PersonRepository
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Which later message closed a commitment, where one did.
 *
 * **The stage that gives a commitment a state, and the only honest half of one.** A promise is
 * frequently kept in silence, so what this can establish is closure evidenced against closure not
 * evidenced — never that something is still owed. It subtracts from an outstanding list and can
 * never confirm what remains on one, which is why the answer path carries that caution whether or
 * not this pass has ever run.
 *
 * **One call per commitment over a window, and not a comparison per pair.** The collapse is cheap
 * because a paraphrase sits near what it paraphrases, so vectors decide most pairs for nothing. A
 * closure is not near its commitment that way: the common ones are short acknowledgements whose
 * force comes from position rather than content, and a similarity threshold loose enough to admit
 * *done* would admit everything in the conversation. So the cheap filter here is positional — the
 * store hands over the messages that followed, already bounded — and the model is asked once per
 * commitment, which is the shape the classification stage already uses.
 *
 * **The negative is recorded as well as the positive**, for the reason the collapse records the
 * pairs it kept apart: a commitment nothing closed stays a candidate by every test the store can
 * make, so without the verdict written down every sweep pays to reach the same answer over the same
 * material.
 *
 * **Two bounds govern the re-asking, and they bound different things.** [ClosureBounds.examine] is
 * a horizon per commitment rather than a global one — once a conversation has been weighed that far
 * past a promise, the commitment is settled whether or not anything closed it.
 * [ClosureBounds.rearm] is a floor on how much new material brings one back inside that horizon, so
 * that what a re-examination costs is set by how much there is to weigh rather than by how often
 * the sweep comes round. The floor lapses as the horizon closes, which is what keeps it a deferral
 * rather than a loss — the material a bounded window could not reach is read before the commitment
 * is settled for good. Neither bounds the first examination: a commitment is always read once.
 *
 * **Its version is its own and deliberately not [Extractor]'s.** A closure prompt moving must
 * re-examine the commitments this pass decided about; it must *not* re-derive the recency horizon,
 * which is what sharing `Extractor.version()` would buy — every edit here would re-read the corpus
 * at a measured premium to produce findings no word of this prompt affects.
 */
class Closer(
    private val closures: ClosureRepository,
    private val persons: PersonRepository,
    private val submissions: SubmissionRepository,
    private val model: ModelProvider,
    private val bounds: ClosureBounds,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {

    /**
     * What identifies this pass's output, so that a better prompt retires what an older one decided.
     *
     * The shape, the model, the instruction and the effort: everything that would make two runs
     * disagree about the same material. Carried on every verdict rather than in a watermark table,
     * for the reason [ClosureRepository] gives.
     */
    fun version(): String = "$DERIVATION:${model.version()}@${CLOSURE_SYSTEM.hashCode()}/$CLOSURE_EFFORT"

    /**
     * How many commitments are owed an examination, in full rather than up to a limit.
     *
     * Counts what is with the provider too, because nothing is decided about a commitment until the
     * reply is read: a count that fell to zero with a submission outstanding would have the driver
     * report the pass caught up and never collect what it had paid for.
     */
    suspend fun owed(): Int = closures.owedCount(version(), bounds.examine, bounds.rearm, bounds.now)

    /**
     * One batch: read back a submission, or ask about the next commitments.
     *
     * **Collecting happens whether or not the day has room to spend, and that is not the same as
     * doing nothing.** A collection asks the provider for nothing — the reply is already paid for —
     * and what it does is write verdicts. Skipped while the allowance is gone, those verdicts would
     * sit bought and unwritten, and a submission left in flight long enough is abandoned: the work
     * lost *and* its cost recorded as nothing, which understates the very day that stopped it.
     *
     * One slot, because this stage depends on no other and nothing depends on it within a sweep.
     * What it asks about is commitments already written, so it neither reads nor settles a record —
     * see [SubmissionStage.CLOSURE].
     */
    suspend fun closeBatch(maySpend: Boolean = true): Closing {
        val outstanding = submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.CLOSURE)
        return when {
            outstanding != null -> collect(outstanding)
            maySpend -> ask()
            else -> Closing.NOTHING
        }
    }

    /** Read back what [outstanding] bought, or report that the provider has not finished with it. */
    private suspend fun collect(outstanding: Submission): Closing =
        when (val collected = model.collect(outstanding.id)) {
            is Collected.Waiting -> waitOrAbandon(outstanding)
            is Collected.Ready -> apply(outstanding, collected.replies)
        }

    /**
     * Keep waiting, or give up on a submission that will never be answered.
     *
     * Without the second half, *in flight* and *in flight for ever* are the same state and the one
     * slot is held for good. Abandoning counts every request as unread rather than as a verdict:
     * nothing was decided, so every commitment in it is owed again, which is what the absence of a
     * verdict row already says.
     */
    private suspend fun waitOrAbandon(outstanding: Submission): Closing {
        val waited = Duration.between(outstanding.submittedAt, bounds.now)
        if (waited <= bounds.patience) return Closing(awaiting = outstanding.requests.size)
        submissions.abandon(outstanding.id)
        return Closing(unread = outstanding.requests.size, abandoned = waited)
    }

    /**
     * Turn [replies] into verdicts and settle the submission.
     *
     * **Every reply is read against the window the request was submitted with, not against the
     * conversation as it stands now.** The material a commitment is examined against grows between
     * the asking and the reading, and the answer names one of the messages that were *sent* — so a
     * window re-read here could hold a message the model never saw, and the watermark taken from it
     * would settle material nothing weighed. The window travels in the request's payload for
     * exactly this reason.
     *
     * **And under the version the submission was made with, for the same reason the window
     * travels with it.** The instruction may have been reworded since the batch was handed over;
     * the answers in front of this were produced by the one that was deployed then, and a verdict
     * naming the new wording is one the reword does not bring back — selection excludes it until
     * fresh material or the closing horizon re-arms it, neither of which is the reword.
     *
     * A request whose payload this build cannot read leaves its commitment owed, which is the
     * settled answer for a stored row written by a build that keyed things differently: a throw
     * would hold the slot and throw again on every sweep after it, with nothing able to clear it.
     *
     * The submission is settled last and with the whole bill of the collection, so a failure before
     * it leaves the submission in flight and the next sweep reads it again — the provider keeps a
     * result for far longer than a cadence, which costs a re-read and no money.
     */
    private suspend fun apply(outstanding: Submission, replies: Map<String, BatchOutcome>): Closing {
        val verdicts = mutableListOf<Closure>()
        var closed = 0
        var unclosed = 0
        var unread = 0
        var unreadable = 0
        val declined = mutableListOf<String>()
        var spent = TokenUsage.NONE
        // The instruction that produced these answers, not the one deployed now. A batch is allowed
        // most of a day and a reword arrives by deploy, so the two coincide on exactly the sweep
        // after a prompt change — and this stage marks no record, so the version on the verdict is
        // the only thing the reword can retire it by. See [Submission.version].
        val decidedBy = outstanding.version ?: version()
        outstanding.requests.forEach { request ->
            val reply = replies.replyTo(request.key)
            // Before anything is looked up, because the reply was billed whether or not anything
            // can be done with it.
            spent += reply.usage
            val asked = Asked.read(request)
            if (asked == null) {
                unreadable++
                return@forEach
            }
            when (val verdict = verdictOf(reply, asked)) {
                is Verdict.Closed -> {
                    closed++
                    verdicts += Closure(
                        commitment = asked.commitment,
                        closedBy = verdict.evidence,
                        weighed = asked.weighed,
                        decidedBy = decidedBy,
                    )
                }

                // **Both settle the window, and they are counted apart.** One is the model saying
                // the material holds nothing that closes the promise. The other is the provider's
                // classifiers declining to read it, so nothing was learnt about the promise at all
                // — but a refusal of the same input is deterministic, and leaving no row would buy
                // the same refusal on every sweep for ever. Kept out of `unclosed`, because a
                // provider that had started refusing everything would otherwise read exactly like
                // a corpus closing its promises in silence, which is the one reading this stage
                // must never produce.
                Verdict.Nothing, is Verdict.Declined -> {
                    if (verdict is Verdict.Declined) declined += verdict.reason else unclosed++
                    verdicts += Closure(
                        commitment = asked.commitment,
                        closedBy = null,
                        weighed = asked.weighed,
                        decidedBy = decidedBy,
                    )
                }

                // Nothing written, so the commitment stays owed and comes back on a later sweep.
                // The call is lost either way; what separates this from a verdict is that no answer
                // was reached rather than that the answer was *nothing closed it*.
                Verdict.Unanswered -> unread++
            }
        }
        // Written before the submission is settled, never after. A failure between the two re-reads
        // a reply that is already paid for; the reverse order would settle a submission whose
        // verdicts were never stored, and no later sweep would revisit it.
        if (verdicts.isNotEmpty()) closures.record(verdicts)
        submissions.settle(outstanding.id, spent)
        return Closing(
            // Verdicts and not attempts: a collection whose every reply was unreadable settles
            // nothing, and reporting it as the number of commitments examined would be the figure
            // that looks like work. The requests it could not use are counted below, by kind.
            examined = closed + unclosed + declined.size,
            closed = closed,
            unclosed = unclosed,
            unread = unread,
            unreadable = unreadable,
            declined = declined,
            usage = spent,
        )
    }

    /**
     * What a reply says about the commitment it was asked about.
     *
     * **An identifier that is not one of the messages that followed is not a verdict.** The legal
     * answers are the window's later lines and the refusal word — not every identifier in front of
     * the model, because the first line is the promise itself and the instruction forbids answering
     * with it. Anything else is a reply that was read and cannot be used: a model writing an
     * identifier it inferred rather than copied, or naming the promise as its own closure. Treated
     * as *nothing closed it*, either would write a verdict off a misreading and settle the
     * commitment on the strength of it.
     *
     * **What it costs is a window re-bought.** Nothing is written, so the commitment stays owed and
     * the next sweep pays for it again; a model that answered this way persistently would spend
     * without ever settling anything. That is visible rather than silent — the count of replies
     * that bought no answer is reported beside the spend — and it is the affordable direction,
     * because the alternative writes a verdict nobody could stand behind.
     *
     * **A refusal is not a verdict of nothing, and it is not a retry either.** The provider's
     * classifiers declined the request, so no judgement was reached about the promise — reported as
     * *nothing closed it*, a provider refusing every window would read as a corpus closing its
     * promises in silence. It is still final, because a refusal of the same input is deterministic,
     * so it settles the window rather than being asked again for ever; what separates it is the
     * figure it lands in and the reason it carries out. The same division the reading stages make.
     *
     * A truncation, a pause and an unfinished batch are none of those: they are calls that bought
     * nothing, and asking again is exactly what clears them.
     */
    private fun verdictOf(reply: BatchOutcome, asked: Asked): Verdict = when (reply) {
        is BatchOutcome.Replied -> when (val outcome = reply.outcome) {
            is ModelOutcome.Answered -> namedIn(outcome.text, asked)
            is ModelOutcome.Truncated -> Verdict.Unanswered
            is ModelOutcome.Paused -> Verdict.Unanswered
            is ModelOutcome.Refused -> Verdict.Declined(
                "the examination was declined" + outcome.category?.let { " as $it" }.orEmpty(),
            )
        }

        is BatchOutcome.Unfinished -> Verdict.Unanswered
        is BatchOutcome.Unreadable -> Verdict.Unanswered
    }

    /**
     * Ask about the next commitments owed an examination.
     *
     * A candidate with nothing left to weigh is settled here without a call: the material inside its
     * horizon has been read, and asking about an empty window would be a request billed for the
     * instruction and the claim with nothing in front of them. That is a verdict of nothing closed
     * it, which is what the corpus says.
     */
    private suspend fun ask(): Closing {
        // No early return for an empty selection: a partition of nothing leaves both halves empty,
        // the free settle writes nothing and the clause below answers with a batch that did nothing,
        // which is what a caught-up corpus should report.
        val candidates =
            closures.owed(
                version(),
                bounds.commitments,
                bounds.window,
                bounds.examine,
                bounds.rearm,
                bounds.now,
            )
        val (askable, exhausted) = candidates.partition { it.later.isNotEmpty() }
        val settled = exhausted.map { candidate ->
            Closure(
                commitment = candidate.commitment.id,
                closedBy = null,
                weighed = candidate.weighed,
                decidedBy = version(),
            )
        }
        if (settled.isNotEmpty()) closures.record(settled)
        if (askable.isEmpty()) return Closing(examined = settled.size, unclosed = settled.size)

        val names = namesOf(askable)
        val asking = askable.associateBy { it.commitment.id.value.toString() }
        model.submit(asking.mapValues { (_, candidate) -> request(candidate, names) }).let { id ->
            submissions.record(
                deriver = Deriver.RECOGNITION,
                stage = SubmissionStage.CLOSURE,
                // The kind the pass would settle if it settled any, as the adjudication stage does
                // and for the same reason: the column is not nullable and the one clause that reads
                // it is driven by record rows, which this submission has none of.
                kind = RecordKind.MESSAGE,
                id = id,
                requests = asking.map { (key, candidate) -> Asked.of(key, candidate) },
                // A closure examines a commitment and settles no record. The messages it reads were
                // settled by the classification that read them.
                records = emptyList(),
                // Read by nothing on this stage, which marks nothing. The sweep's own instant rather
                // than a second clock read, so one sweep is one moment.
                selectedAt = bounds.now,
                // The wording that weighed these windows, so that the verdicts name it when they
                // are written a sweep or more later. This stage marks no record, so the version on
                // the verdict row is the only thing a reword can retire it by — stamped with an
                // instruction that did not produce it, the verdict outlives the one that did, and
                // only fresh material inside the horizon would bring the commitment back.
                version = version(),
            )
            return Closing(
                examined = settled.size,
                unclosed = settled.size,
                awaiting = asking.size,
            )
        }
    }

    /** What the corpus calls the authors of everything these windows hold, where it names them. */
    private suspend fun namesOf(candidates: List<ClosureCandidate>): Map<ExternalId, String> =
        persons.persons(candidates.flatMap { candidate -> candidate.later.mapNotNull { it.author } }.distinct())
            .mapNotNull { person -> person.displayName?.let { person.externalId to it } }
            .toMap()

    /**
     * One examination: the window, then the claim it is read against.
     *
     * **Both in the prompt, and `shared` deliberately unset.** A shared region is where the adapter
     * puts the one cache breakpoint, taking it off the instruction — which is worth doing only when
     * sibling requests have that region in common. Here none do: a request is one commitment and
     * one conversation, so a breakpoint at the end of its window caches a prefix no other request
     * will ever match, and every call would pay the write premium for an entry nothing reads. Unset,
     * the breakpoint sits on the instruction, which is the shape the rest of this build has.
     *
     * **So this stage caches nothing, and that is measured rather than assumed.** [CLOSURE_SYSTEM]
     * is 941 tokens — counted through the provider's own token endpoint, net of a request's fixed
     * overhead — against a minimum cacheable prefix of 1024 on the bulk model. A prefix under the
     * minimum is not an error: the breakpoint is silently ignored, nothing is written, and both
     * cache counters come back at zero on every submission, which is what the store records.
     *
     * **The margin is thin enough to be worth stating, and it runs the unusual way.** Another
     * paragraph of instruction would carry the prefix over the minimum, at which point the
     * breakpoint starts taking and this stage silently begins caching — an improvement, but one
     * that makes every sentence above false and is reported by nothing, since a breakpoint that
     * begins to take looks from outside exactly like one that never did. Whoever lengthens this
     * instruction re-counts it; nothing mechanical is watching the figure.
     *
     * **Nothing can be done about it here, and a warming call is not the thing that is missing.**
     * Warming is what makes a shared prefix pay elsewhere in this build, but it writes an entry by
     * making an ordinary call of the same shape — and a sub-minimum prefix writes no entry however
     * it is called, so there is nothing to warm and no write premium to weigh against anything. The
     * only way to clear the minimum would be to give the breakpoint more prefix, and there is none
     * to give: the instruction is the whole of what two of this stage's requests share. Padding
     * prose to reach a provider's threshold would buy the caching by spending the instruction.
     *
     * So the instruction is paid for in full on every call, and that is recorded rather than fixed.
     *
     * The claim travels as the restated sentence rather than as the message that stated it, because
     * that sentence is what the commitment *is*: it was rewritten to stand on its own precisely so
     * that it could be read away from the turn it came from. It comes last because the instruction
     * says the last thing given is the promise being decided about.
     */
    private fun request(candidate: ClosureCandidate, names: Map<ExternalId, String>) = ModelRequest(
        system = CLOSURE_SYSTEM,
        prompt = window(candidate, names).rendered() + "\n\n" +
            candidate.commitment.claim.lines().joinToString(" ") { it.trim() },
        maxTokens = maxTokens,
        effort = CLOSURE_EFFORT,
    )

    /**
     * What a reply may say about a window, as the request remembers it.
     *
     * **The window is remembered rather than re-read, and that is the one thing in this stage that
     * must not be got wrong.** A reply names a message by the identifier it was shown; the material
     * after a commitment grows while the submission is outstanding, so a window rebuilt at
     * collection could offer an identifier the model never saw — and the watermark read off it would
     * settle material nothing weighed. A position in the window would be worse again: it is a label
     * computed from the window rather than from the record, so it moves when the context grows and
     * one number means two different messages a submission apart.
     */
    internal data class Asked(
        val commitment: EntityId,
        val conversation: ExternalId,
        /**
         * Every message weighed, by the identifier the model was shown, with the instant it carries.
         *
         * The legal answers, and the whole of them: the promise's own line is in front of the model
         * and is not here, because the instruction forbids answering with it.
         */
        val window: Map<String, Instant>,
    ) {

        /**
         * The last message weighed, which is where the next examination resumes.
         *
         * **Ordered the way the window was read** — by instant, then by the record's own identifier
         * — so the mark is the exact point the reading stopped. Taking the latest instant alone
         * would leave whatever else shared that second on the wrong side of the next resume, and
         * nothing would ever weigh it.
         */
        val weighed: Weighed
            get() = window.entries
                .maxWith(compareBy({ it.value }, { it.key }))
                .let { Weighed(it.value, ExternalId(conversation.source, it.key)) }

        /** The identifier [native] names, as a whole external one. */
        fun conversationOf(native: String): ExternalId = ExternalId(conversation.source, native)

        companion object {

            /**
             * The request to submit for [candidate], keyed by [key].
             *
             * The payload is the window as the model will see it, one line per message: the
             * identifier it may answer with and the instant that identifier carries. Encoded rather
             * than left to be re-read for the reason the type's own documentation gives.
             */
            fun of(key: String, candidate: ClosureCandidate) = SubmittedWindow(
                key = key,
                conversation = candidate.commitment.conversation,
                payload = candidate.later.joinToString("\n") {
                    "${it.externalId.nativeId}$PAYLOAD_FIELD${it.sourceTime}"
                },
            )

            /**
             * What [request] asked about, or `null` where this build cannot read it.
             *
             * Null rather than a throw: a stored row another build wrote leaves the commitment owed,
             * where a throw would hold the one slot and fail again on every sweep after it.
             */
            fun read(request: SubmittedWindow): Asked? {
                val commitment = runCatching { EntityId(UUID.fromString(request.key)) }.getOrNull()
                val conversation = request.windowIn()
                val weighed = request.payload?.lines()?.mapNotNull { line ->
                    val fields = line.split(PAYLOAD_FIELD)
                    if (fields.size != PAYLOAD_FIELDS) {
                        null
                    } else {
                        runCatching { Instant.parse(fields[1]) }.getOrNull()?.let { fields[0] to it }
                    }
                }?.toMap()
                return if (commitment == null || conversation == null || weighed.isNullOrEmpty()) {
                    null
                } else {
                    Asked(commitment, conversation, weighed)
                }
            }
        }
    }

    /** What a reply came to: a closing message, nothing closing it, or no answer at all. */
    internal sealed interface Verdict {

        data class Closed(val evidence: Evidence) : Verdict

        /** The corpus holds nothing that closes it, as far as this window was read. */
        data object Nothing : Verdict

        /** The provider would not read the window. Settled, and counted apart from a verdict. */
        data class Declined(val reason: String) : Verdict

        /** A call that bought no answer. The commitment stays owed and nothing is written. */
        data object Unanswered : Verdict
    }

    companion object {

        /**
         * What names this pass in a verdict, before the model and the prompt.
         *
         * Numbered like the inference and identity versions, so that a change of shape — a second
         * window per commitment, a different unit — is distinguishable from a change of model,
         * prompt or effort within the same shape. The rest of the version carries those.
         */
        const val DERIVATION = "closure-1"

        /** How many commitments one batch asks about. */
        const val DEFAULT_COMMITMENTS = 16

        /**
         * How many messages after a commitment one examination weighs.
         *
         * A count and not a span, because what a prompt holds is messages rather than hours: a busy
         * thread puts hundreds into a week and a quiet one puts none, so a bound shaped like a span
         * sends a window of unpredictable size to a stage that is costed per call. Several times the
         * context the classification stage reads either side of its target, because this one reads in
         * a single direction and a closure sits much further from its promise than a restatement
         * sits from its turn.
         *
         * **Sized to reach the declaration and not merely the work**, which is what a smaller bound
         * got wrong. The sequence a settled undertaking leaves in a busy thread is long: the promise,
         * then whatever else the room is talking about, then the work reported over a run of
         * messages, then somebody saying the matter is finished. The last of those is the strongest
         * closing signal such a conversation holds — and it is the furthest from the promise, so a
         * window that stops short of it weighs the whole exchange and misses the one line that
         * settles it. Measured on a busy incident thread, every undertaking in it had that
         * declaration beyond the previous bound and inside this one.
         *
         * **A window too small costs more rather than less.** What does not fit is read in
         * instalments, and an instalment is a call that pays for the instruction and the claim again
         * — so halving the window nearly doubles the fixed cost of reading the same stretch, and
         * defers the message that settles the promise behind a sweep that may not come while anybody
         * is still asking. The per-call ceiling absorbs the wider window: [DEFAULT_MAX_TOKENS] is
         * sized for the reading stages' shape, which is this one.
         */
        const val DEFAULT_WINDOW = 40

        /**
         * How long after a promise its closure is still worth paying to find.
         *
         * **The bound that makes this stage affordable at all**, and a horizon per commitment rather
         * than a global one: once a conversation has been weighed this far past a promise, the
         * commitment is settled whether or not anything closed it. Without it, every unclosed
         * commitment is re-examined for as long as its conversation keeps growing, which on a live
         * source is for ever.
         *
         * Thirty days, which is a guess rather than a measurement and is configuration precisely so
         * that it can be moved without a build. What it trades is a promise closed late — a quarter
         * after it was made, say — against re-reading every conversation for a month after every
         * promise in it.
         */
        val DEFAULT_EXAMINE: Duration = Duration.ofDays(30)

        /**
         * How much new material it takes to re-arm a commitment a verdict already covers.
         *
         * **The bound that makes re-examination cost what the material costs**, where
         * [DEFAULT_WINDOW] bounds one call and [DEFAULT_EXAMINE] bounds the whole series. Without it
         * a single message past the watermark is enough, so a conversation arriving a message at a
         * time buys one call per message — and the instruction and the claim, which a call pays for
         * whether it weighs one message or twenty, are then nearly the whole bill.
         *
         * Five, which is a guess at where that curve bends rather than a measurement of it, and is
         * configuration precisely so that it can be moved without a build. A busy thread reaches it
         * within the hour and a quiet one may not reach it inside the horizon at all, which is the
         * asymmetry it is meant to exploit: the conversations worth re-reading often are the ones
         * that produce material fast.
         *
         * **It defers and never drops, which is the half that is not a judgement.** A window is
         * bounded by a count, so a conversation holding more material than fits is read in
         * instalments and the last is the remainder — under any floor one time in every
         * [DEFAULT_WINDOW]. So the floor holds only while the horizon is open and more material may
         * yet arrive; past it nothing can, and one message is enough to buy the last examination.
         * Gated on the floor alone, that remainder would be weighed by no sweep ever and a closing
         * message could sit in it. What this bound costs is latency, never a finding.
         *
         * **It floors re-examination and not examination.** A commitment nothing has read, and one
         * whose version moved, is examined whatever followed the promise — a floor on the first
         * reading would leave a quiet conversation with no verdict at all.
         */
        const val DEFAULT_REARM = 5

        /**
         * How long a submission may stay with the provider before it is given up on.
         *
         * The same figure the reading stages use and for the same reason: a provider that will never
         * answer leaves the slot held for good.
         */
        val DEFAULT_PATIENCE: Duration = Inferrer.DEFAULT_PATIENCE

        /**
         * Cheaper than the restatement and dearer than nothing.
         *
         * What it asks is whether one of a handful of short lines takes up a promise, which is a
         * judgement over text already in front of the model — the same shape as the classification
         * and priced the same way. The effort is in [version], so moving it re-examines what it
         * would now decide differently.
         */
        val CLOSURE_EFFORT = Effort.MEDIUM

        /**
         * The ceiling on a call, which is not the ceiling on its reply.
         *
         * **Thinking is on by default and counts against this**, so a figure sized to the answer —
         * one identifier or one word — is the mistake that buys a truncation. The judgement asked
         * for here is over a window of up to [DEFAULT_WINDOW] messages and the claim beside them,
         * which is the extraction stage's shape, so this is its figure rather than the smaller one
         * a routing decision over a single short text can afford.
         *
         * **A truncation here is reported as a reply that bought no answer**, which leaves the
         * commitment owed and shows up only as a count. So a ceiling set too low does not fail
         * loudly: it produces a stage that is billed on every sweep, writes no verdict, and reads
         * from outside exactly like a corpus that closes its promises in silence.
         */
        const val DEFAULT_MAX_TOKENS = 4000

        private const val PAYLOAD_FIELD = "|"

        private const val PAYLOAD_FIELDS = 2

        /**
         * The stage's standing instructions, byte-stable for the reason the other stages' are: the
         * cache breakpoint sits at the end of it, and anything interpolated per call would mean no
         * two requests ever shared a prefix. **Byte-stable is the precondition for caching and not
         * a guarantee of it**, and this instruction is the build's example of the difference: it
         * meets that one and misses the length minimum, so the breakpoint takes nothing and the
         * prefix is paid for on every call. [request] records the measured figure and why there is
         * nothing to be done about it here.
         *
         * **It asks for an identifier copied from the window, never a position in it.** A position
         * is a label computed from the window, and the window grows while the submission is
         * outstanding; an identifier belongs to the record and means the same thing whenever the
         * reply is read. The parser refuses anything the window did not carry, so a model answering
         * with a number has produced no verdict rather than a wrong one.
         *
         * **And it says what closure is not.** The failure to guard against is a model reading any
         * later activity as discharge — an acknowledgement that a promise was heard, a plan to do it
         * later, or somebody else doing something adjacent. Those are the lines that follow a
         * promise most often, so a definition admitting them would close almost every commitment in
         * the corpus and the stage would report a wholly settled archive.
         *
         * **It also says what closure is, in the two shapes that carry no announcement**, because
         * the guard above was doing damage on its own: asked for a message *stating* that the thing
         * was done, a reader finds neither of the ways a conversation ordinarily finishes an
         * undertaking. A promise to look, to check or to find out is discharged by delivering what
         * it asked for — the findings are the report, and nothing says *I looked*. And an exchange
         * that works something out together ends with somebody saying the matter is closed, which
         * discharges the undertakings that matter was about and is the plainest closing signal such
         * a conversation holds. Both were measured unrecorded against a thread that settled every
         * one of its undertakings.
         *
         * **The widening is bounded on the side that would cost precision.** Delivery is of what was
         * promised and not a step towards it, so partial work stays progress; and a matter called
         * finished because it was dropped or taken over is withdrawal, which this stage answers with
         * [Extractor.NOTHING] as it did before. Both bounds are stated in the instruction rather
         * than left to be inferred from the exclusions above them.
         */
        val CLOSURE_SYSTEM = """
            You decide whether a promise made in a business conversation was reported as carried out,
            and if so which message reports it.

            You are given the conversation first, one message per line as its identifier, when it was
            sent, who sent it, and its text. The first line is the promise as it was made, and its
            text is the promise restated to stand on its own; that line carries no sender. Every line
            after it is what followed in the same conversation, in order, and for those the third
            field is empty only where the sender has no name on record. The last thing you are given
            is not a message: it is that same promise again, which is what you are deciding about.

            Answer with exactly one line and nothing else: either the identifier of the message that
            reports the promise as carried out, copied exactly from the first field of its line, or
            the single word ${Extractor.NOTHING}.

            Copy an identifier; never write a position, a number of your own, or anything not in the
            first field of one of the lines you were given. Never answer with the first line's
            identifier: that line is the promise, and a promise does not close itself.

            Answer ${Extractor.NOTHING} unless a message states that the thing promised has been
            done. These are not closure, and each of them is more common after a promise than closure
            is:

            - acknowledging the promise, agreeing to it, or thanking somebody for it;
            - saying it will be done, or when it will be done, or asking when it will be done;
            - starting it, making progress on it, or saying it is under way;
            - somebody doing something else that happens to be related;
            - the conversation moving on to another subject.

            Two kinds of message do report a promise as carried out without announcing it in those
            words, and both are easy to pass over. Look for them before you settle on
            ${Extractor.NOTHING}:

            - the thing promised, delivered. Where what was promised was to look at something, to
              check it, to find out something, or to send something, then the message carrying the
              findings, the answer, or the thing itself is the report that it was done. Nobody has to
              say that they did it: handing over what was asked for is the saying. This does not
              cover a step along the way — a partial answer, or work described as still going on, is
              progress and stays excluded above.
            - the matter declared finished. Where a participant says that the matter this promise
              belongs to is settled, resolved or closed, that message reports the promises that
              matter was about as carried out. In a conversation that works something out together
              this is the plainest statement of closure anybody makes, and it comes at the end of the
              exchange rather than near the promise.

            A matter called finished because it was dropped, because it stopped mattering, or because
            somebody else took it over is not that: it is withdrawal and not completion.

            A promise is often kept without anybody saying so, so ${Extractor.NOTHING} is the
            ordinary answer and the one to give whenever the messages leave it open. Nothing is lost
            by answering ${Extractor.NOTHING}: it records that these messages do not report the thing
            as done, which is all that was asked.

            Where the promise was abandoned, cancelled or withdrawn rather than carried out, answer
            ${Extractor.NOTHING}. The question is whether it was done.

            Where more than one message reports it as done, answer with the first of them.
        """.trimIndent()
    }
}

/**
 * What one closure batch did, as the driver and a log need it.
 *
 * **Several numbers rather than one, because the quiet failures are counts here and not
 * exceptions.** A pass reporting only its progress would hide the replies it could not read, the
 * windows a provider would not look at, and the requests it abandoned behind a figure that looked
 * like work.
 *
 * @property examined commitments a verdict was reached about, whether or not anything closed them.
 * @property closed verdicts naming a closing message.
 * @property unclosed verdicts recording that nothing in the material weighed closes the promise.
 *   **Not a count of open commitments** — nothing here establishes that.
 * @property unread replies that bought no answer, leaving their commitments owed.
 * @property unreadable requests whose stored payload this build cannot read, which says a build
 *   changed underneath a submission rather than that a model misbehaved.
 * @property declined the windows the provider would not read, carried out whole rather than
 *   counted. Each settles its commitment, and none of them is a judgement about the promise — kept
 *   out of [unclosed] so that a provider refusing everything cannot read as a corpus closing its
 *   promises in silence.
 * @property awaiting requests left with the provider, so the driver reads a pass that is waiting
 *   rather than one that has stalled.
 * @property abandoned how long a submission had been outstanding when it was given up on, where one
 *   was.
 * @property usage what the collection was billed. **Reported always, including at zero**, because
 *   the container log is the only place this build's model spend is observable and this is the
 *   second stage that spends from the day's allowance. A batch that cost nothing has to be
 *   distinguishable from one whose cost nobody recorded.
 */
data class Closing(
    val examined: Int = 0,
    val closed: Int = 0,
    val unclosed: Int = 0,
    val unread: Int = 0,
    val unreadable: Int = 0,
    val declined: List<String> = emptyList(),
    val awaiting: Int = 0,
    val abandoned: Duration? = null,
    val usage: TokenUsage = TokenUsage.NONE,
) {
    companion object {
        /** A batch with nothing to do, which is what a caught-up corpus produces. */
        val NOTHING = Closing()
    }
}

/**
 * What one sweep of the closure pass may read and spend.
 *
 * [now] is the sweep's own instant, read once by whoever starts the sweep: this class holds no clock,
 * so a second reading here would let one sweep hold two moments.
 */
data class ClosureBounds(
    val now: Instant,
    val examine: Duration = Closer.DEFAULT_EXAMINE,
    val window: Int = Closer.DEFAULT_WINDOW,
    val commitments: Int = Closer.DEFAULT_COMMITMENTS,
    val patience: Duration = Closer.DEFAULT_PATIENCE,
    val rearm: Int = Closer.DEFAULT_REARM,
) {
    init {
        require(commitments > 0) { "A batch of $commitments commitments examines nothing." }
        require(window > 0) { "A window of $window messages after a promise holds no material." }
        require(!examine.isNegative && !examine.isZero) {
            "An examination horizon of $examine past a promise would weigh nothing after it."
        }
        // Zero and below both mean *no floor*, which is the behaviour this bound exists to stop:
        // one message would re-arm a commitment and the stage would pay its fixed cost to weigh it.
        // One is the lowest honest setting and says so explicitly.
        require(rearm > 0) {
            "A re-arm floor of $rearm messages puts no floor on re-examination, so a commitment " +
                "would be read again for a single message."
        }
        // **Not bounded against [window], deliberately.** A floor above a window's worth of
        // messages delays a re-examination by more than one instalment, which is a dial set badly
        // rather than a dial set dangerously: the floor lapses at the horizon, so the material is
        // read before the commitment is settled whatever this says relative to the window.
    }
}

/**
 * The reply to [key], or the outcome of there being none.
 *
 * A key the provider answered nothing for is a request it never finished, which is what it is.
 * Nothing is written for one, so the commitment stays owed — the absence of a verdict row is
 * already what says so.
 */
private fun Map<String, BatchOutcome>.replyTo(key: String): BatchOutcome =
    this[key] ?: BatchOutcome.Unfinished(
        "the provider returned no result for this request",
        TokenUsage.NONE,
    )

/**
 * The commitment's own turn and everything the store handed over after it, as one window.
 *
 * **The stating message is the target even though nothing is read out of it here**, because a
 * window is named by the message it reads and this examination is about what that message
 * promised. It also puts the promise in front of the model in its original wording beside the
 * restated claim, which is what a short acknowledgement has to be read against.
 *
 * Where the stating message carries no text — a window whose target is a sticker — the later
 * material stands on its own: a target is required to be *in* the window and not to be readable,
 * and [Window.rendered] leaves out every line with nothing on it.
 */
private fun window(candidate: ClosureCandidate, names: Map<ExternalId, String>): Window {
    val stated = candidate.commitment.evidence.firstStated.span.record
    val messages = candidate.later.filterNot { it.externalId == stated }
    return Window(
        conversation = candidate.commitment.conversation,
        messages = listOf(statedTurn(candidate, stated)) + messages,
        target = stated,
        names = names,
    )
}

/**
 * The turn that stated the commitment, as the window has to carry it.
 *
 * A candidate's material is what followed the promise, so the stating turn is not among it and
 * the store is not asked for it again: what a window needs of its target is the identifier and
 * the instant, both of which the finding's own evidence carries. The text is the restated claim,
 * which is the only wording of the promise this pass is given — and it is what the model is
 * being asked about, so a line carrying it is not a line carrying nothing.
 */
private fun statedTurn(candidate: ClosureCandidate, stated: ExternalId) = Message(
    externalId = stated,
    author = null,
    text = candidate.commitment.claim,
    sourceTime = candidate.commitment.evidence.firstStated.sourceTime,
)

/**
 * The message [text] names, as evidence, or that it names none.
 *
 * At file level because it reads no port and holds no state: what it decides is entirely in front
 * of it, which is the property that makes the identifier guard checkable.
 */
private fun namedIn(text: String, asked: Closer.Asked): Closer.Verdict {
    val answer = text.trim().lines().firstOrNull()?.trim().orEmpty()
    val weighed = asked.window[answer]
    return when {
        // **A blank body is a reply nobody received, not a verdict of nothing.** The instruction
        // asks for one identifier or one word, so no word at all is a call that bought nothing —
        // which is the reading the adjudication stage already takes of a blank. Read as
        // nothing-closed it would settle the commitment for its whole window on an empty string.
        answer.isEmpty() -> Closer.Verdict.Unanswered
        answer.equals(Extractor.NOTHING, ignoreCase = true) -> Closer.Verdict.Nothing
        weighed == null -> Closer.Verdict.Unanswered
        // Ordinal zero: a message is one span, as the index holds it and as a finding's own evidence
        // names it. So a closing message is cited by the same value a retrieved hit carries, which
        // is what lets the citation check be an equality.
        else -> Closer.Verdict.Closed(
            Evidence(SpanId(kind = RecordKind.MESSAGE, record = asked.conversationOf(answer)), weighed),
        )
    }
}
