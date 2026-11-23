package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.Derivation
import io.github.ragnorrs.mainbrain.domain.DerivationStateRepository
import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.Effort
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.ModelRequest
import io.github.ragnorrs.mainbrain.domain.Pending
import io.github.ragnorrs.mainbrain.domain.SpendLedger
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.entity.Admission
import io.github.ragnorrs.mainbrain.domain.entity.AdmissionRepository
import io.github.ragnorrs.mainbrain.domain.entity.Standing
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ConversationRepository
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.MessageRepository
import io.github.ragnorrs.mainbrain.domain.record.RecordKind

/**
 * Which named conversations stand for a body of work, and which only collect material about a
 * theme.
 *
 * **The pass that narrows what identity admits, and it cannot be a stage of identity.** A client is
 * read off any shared conversation's name, which is right where every named conversation is a body
 * of work and wrong where a source mixes client threads with thematic tracks — referrals, partner
 * programmes, banks, hiring. Telling the two apart means knowing what the names mean, which needs
 * the model; reading a label does not, and identity therefore spends nothing and runs over
 * everything. Keeping the judgement here is what leaves that property intact.
 *
 * **Nothing downstream depends on this having run.** A conversation with no verdict is a client
 * exactly as it was before the pass existed, so the pass may lag the corpus arbitrarily, be
 * switched off, or never have run at all — see [io.github.ragnorrs.mainbrain.domain.entity.Admission]. What
 * it rejects is withheld where clients are read and is never deleted, which is both the no-removal
 * rule and what makes a wrong verdict recoverable by rewording the instruction rather than by
 * re-deriving anything.
 *
 * **One call per conversation, judged on the name together with a bounded recent sample.** A name
 * alone does not separate the two: the same word is a client in one archive and a category in
 * another, and what settles it is whether the messages are people carrying out a particular piece
 * of work or people filing a kind of thing. One call rather than one for the whole batch, because
 * a reply covering several conversations would have to be matched back to them on content the model
 * emits — a join that fails silently where this fails per conversation.
 *
 * **Synchronous rather than batched, unlike the reading stages.** What bounds the cost here is the
 * conversation table rather than the message table: a batch is tens of short calls and the pass
 * converges, where inference reads every message inside its horizon and goes on reading as the
 * corpus grows. The spending is still bounded — each call is charged to the day as it is made, and
 * the driver stops the stage when the day's allowance is gone.
 *
 * **No recency horizon, deliberately.** A body of work discussed years ago is still one, and the
 * conversations of an archive are a handful against its messages. The pass is bounded by its batch
 * size and by the day's allowance, and once every conversation carries a verdict it spends nothing
 * until a new conversation arrives or the instruction is reworded.
 */
// Past the parameter cap, as every pass in this package is: each one is the ports it composes, and
// grouping them into a holder would move the list one type across without shortening it. `Derived`
// exists in the sibling pass because the three stores it wraps are read together; nothing here is.
@Suppress("LongParameterList")
class Admitter(
    private val derivations: DerivationStateRepository,
    private val conversations: ConversationRepository,
    /**
     * Read for one thing: the recent sample a judgement is made on.
     *
     * A bounded read, which is why it is this port's [MessageRepository.latestIn] and not
     * [MessageRepository.messagesIn] — a conversation of an archive runs to tens of thousands of
     * messages and the sample is the size of a screen.
     */
    private val messages: MessageRepository,
    private val admissions: AdmissionRepository,
    private val model: ModelProvider,
    /**
     * Where this pass's calls are charged.
     *
     * **Every call goes through here, because none of them is a submission.** A day's spend is
     * totalled from the submissions the batch endpoint answered plus the charges recorded beside
     * them; a pass calling the provider directly and charging nothing would spend against a bound
     * that could not see it.
     */
    private val spend: SpendLedger,
    private val bounds: AdmissionBounds = AdmissionBounds(),
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {

    /**
     * What this pass's verdicts depend on, so that a better instruction re-judges them.
     *
     * The model, the instruction, the effort and how much of a conversation is sampled: everything
     * that would make two runs disagree about one conversation. The sample size is in it for the
     * reason inference's context reach is in its own — a conversation judged on more or less of
     * itself is judged differently, and two such readings keyed alike would leave the corpus holding
     * both.
     */
    fun version(): String =
        "$DERIVATION:${model.version()}@${JUDGE_SYSTEM.hashCode()}/$JUDGE_EFFORT/${bounds.sample}"

    /** How many conversations are owed a judgement, in full rather than up to a limit. */
    suspend fun owed(): Int = derivations.owed(derivation(), RecordKind.CONVERSATION, notBefore = null)

    /**
     * One batch: judge the next conversations, and settle the ones there is nothing to judge.
     *
     * **A conversation nothing can read a client from is marked without a call.** A two-person
     * exchange displays the counterparty rather than a name anybody chose, and a conversation with
     * no name is not a client and will not be one unless the source names it — identity excludes
     * both, so there is nothing here to narrow. Marking them is what keeps them from being offered
     * on every sweep for ever; judging them would be paying to answer a question nobody asked.
     *
     * **That free half still runs when the day's allowance is gone**, which is the only thing this
     * stage can do without spending — and it is the whole of what this stage can do for nothing,
     * which is worth being plain about. Those conversations drain first and once, so an exhausted
     * day's batch settles nothing from then on and the driver reports a stall beside the line
     * saying the day stopped it. That is the driver reading what it is given — a lag that did not
     * fall — and not a state to paper over here: the alternative is a batch reporting progress it
     * did not make, which is the reading that whole mechanism exists to prevent. The stage that
     * spends has the same shape for the same reason.
     *
     * **Each conversation is written and marked on its own, rather than the batch being flushed at
     * the end.** A transport failure part-way through a batch would otherwise discard every
     * judgement already paid for, and the next sweep would pay for them again. The order within one
     * conversation is the one the watermark requires: the verdict first, the mark after, so a crash
     * between them re-judges a conversation rather than settling one nothing decided.
     */
    suspend fun admitBatch(maySpend: Boolean = true): Admissions {
        val derivation = derivation()
        val pending = derivations.pending(derivation, RecordKind.CONVERSATION, bounds.batchSize, notBefore = null)
        if (pending.records.isEmpty()) return Admissions.NOTHING

        val batch = conversations.conversations(pending.records)
        val (named, unnameable) = batch.partition { it.isNamed() }
        if (unnameable.isNotEmpty()) {
            derivations.mark(derivation, RecordKind.CONVERSATION, unnameable.map { it.externalId }, pending.asOf)
        }
        // The free half first and whatever the day allows after it, folded together, so that a
        // batch which could not spend still reports the progress it made.
        return named
            .takeIf { maySpend }
            .orEmpty()
            .fold(Admissions(settled = unnameable.size)) { done, conversation ->
                done + judge(conversation, derivation, pending)
            }
    }

    /**
     * Ask about one conversation, record what came back, and settle the record where the answer
     * settles it.
     *
     * **A refusal settles the conversation and a truncation does not**, which is the same split the
     * reading stages make: the provider refuses the same request again, so asking twice buys the
     * same nothing, while a reply that ran out of room is answerable by a build that asks for more.
     * A settled refusal carries no verdict, which reads as admitted — the direction that leaves the
     * corpus exactly as it was.
     */
    private suspend fun judge(
        conversation: Conversation,
        derivation: Derivation,
        pending: Pending,
    ): Admissions {
        val sample = messages.latestIn(conversation.externalId, bounds.sample)
        val outcome = model.respond(request(conversation, sample))
        // Charged as the call is made rather than totalled at the end of the batch, so a failure
        // later in the batch leaves what was already billed accounted for.
        spend.charge(Deriver.ADMISSION, outcome.usage)
        return when (outcome) {
            is ModelOutcome.Answered -> when (val standing = standing(outcome.text)) {
                null -> Admissions(unreadable = 1, usage = outcome.usage)
                else -> {
                    admissions.record(listOf(Admission(conversation.externalId, standing, derivation.version)))
                    settle(conversation, derivation, pending)
                    Admissions(
                        settled = 1,
                        judged = 1,
                        rejected = if (standing == Standing.THEMATIC_TRACK) 1 else 0,
                        usage = outcome.usage,
                    )
                }
            }

            is ModelOutcome.Refused -> {
                settle(conversation, derivation, pending)
                Admissions(
                    settled = 1,
                    declined = listOf(outcome.category ?: NO_CATEGORY),
                    usage = outcome.usage,
                )
            }

            is ModelOutcome.Truncated -> Admissions(unread = 1, usage = outcome.usage)
            is ModelOutcome.Paused -> Admissions(unread = 1, usage = outcome.usage)
        }
    }

    private suspend fun settle(conversation: Conversation, derivation: Derivation, pending: Pending) =
        derivations.mark(derivation, RecordKind.CONVERSATION, listOf(conversation.externalId), pending.asOf)

    private fun derivation() = Derivation(Deriver.ADMISSION, version())

    /**
     * The one request shape this pass makes.
     *
     * The instruction is the whole of [ModelRequest.system] and carries nothing per call, so it is
     * the prefix every sibling call shares. What varies is the name and the sample, which is the
     * prompt.
     *
     * **No shared region, and the prefix measurably does not cache**: the instruction is well under
     * the provider's minimum cacheable length, so a batch of judgements reports nothing read from
     * or written to cache and each call pays for the instruction fresh. That is left alone rather
     * than padded, because the volume is a handful of calls per archive and the pass converges —
     * what caching would buy here is a fraction of a figure already smaller than one sweep of the
     * reading stages.
     */
    private fun request(conversation: Conversation, sample: List<Message>) =
        ModelRequest(
            system = JUDGE_SYSTEM,
            prompt = asked(conversation, sample),
            maxTokens = maxTokens,
            effort = JUDGE_EFFORT,
        )

    /**
     * The name, and what was last said under it.
     *
     * Lines with nothing on them are left out: a sticker or a photograph contributes no evidence
     * about what a conversation is for, and a run of blank lines spends the prefix's budget on
     * nothing. A conversation with no readable material is asked about by name alone, which the
     * instruction's default answer covers.
     *
     * **No author and no time on a line, unlike the window the reading stages render.** Those
     * stages have to name a party and resolve a relative date, so a line without its author offers
     * nothing to name one with. This judgement is about what the messages are *doing* — carrying
     * out one engagement, or filing a kind of thing — which the text says and the speaker does not;
     * and leaving them out keeps a person's record out of a prompt that has no use for it. Measured
     * on real material: every named conversation of the developer's own archive was judged
     * correctly from text alone, alongside tracks of the kind this pass exists to reject.
     */
    private fun asked(conversation: Conversation, sample: List<Message>): String =
        buildString {
            appendLine("$NAME_FIELD ${conversation.name}")
            appendLine(SAMPLE_FIELD)
            val lines = sample.mapNotNull { it.text?.takeIf(String::isNotBlank) }
            if (lines.isEmpty()) appendLine(NOTHING_SAID) else lines.forEach { appendLine("- $it") }
        }

    /**
     * The verdict in [reply], or `null` where it does not carry exactly one.
     *
     * **Read by which of the two words is present rather than by position.** The instruction asks
     * for one of them alone on a line, and a reply that adds a sentence of reasoning around the
     * right word is an answer this build should keep rather than throw away and pay for again. What
     * it will not do is guess: a reply naming both, or neither, is no answer, and the conversation
     * stays owed.
     *
     * **A negation is not detected, and the direction it falls in is why that is tolerable.** A
     * reply saying a conversation is *not* a body of work names one word and is read as that
     * verdict — which admits, leaving the corpus as it was. Detecting it would mean reading the
     * model's prose for a qualifier, which is the thing the two-word answer exists to avoid; what
     * makes the reply this shape at all is the instruction, and an instruction obeyed does not
     * produce one.
     *
     * **An unreadable reply costs a call per sweep for as long as it lasts, and that is the price
     * of not guessing.** Unlike a truncation, this is stable for a given conversation and a given wording
     * — so the same conversation is asked again on the next sweep and answered the same way, bounded
     * only by the cadence and the day. It is reported as [Admissions.unreadable] for exactly that
     * reason: what clears it is a better instruction, and a figure nobody can see is a reword nobody
     * makes. Settling it instead would mark a conversation against an answer nothing read.
     */
    private fun standing(reply: String): Standing? {
        val said = reply.uppercase()
        val found = Standing.entries.filter { it.name in said }
        return found.singleOrNull()
    }

    private fun Conversation.isNamed(): Boolean = kind != ConversationKind.DIRECT && !name.isNullOrBlank()

    internal companion object {

        /** How many conversations one batch judges. See [AdmissionBounds.batchSize]. */
        const val DEFAULT_BATCH_SIZE = 32

        /** How many of a conversation's newest messages go in front of the model. */
        const val DEFAULT_SAMPLE = 40

        /**
         * The ceiling on one judgement's reply.
         *
         * The same figure the reading stages use, and for the reason stated there: thinking counts
         * against it, so a ceiling sized to the one word this asks for would buy a truncation
         * instead of a cheaper call. What trades depth against spend is the effort.
         */
        const val DEFAULT_MAX_TOKENS = 4000

        /**
         * What a verdict names its stage as, so that it can never be read as another stage's.
         *
         * The shape [Closer.version] uses, and for the same reason: two stages' versions share no
         * column here, but a figure that identifies itself only by a model and a hash is one nobody
         * reading a row can place.
         */
        private const val DERIVATION = "admission"

        private val JUDGE_EFFORT = Effort.MEDIUM

        /** What a refusal is reported as where the provider named no category for it. */
        private const val NO_CATEGORY = "no category given"

        private const val NAME_FIELD = "NAME:"
        private const val SAMPLE_FIELD = "MOST RECENT MESSAGES, OLDEST FIRST:"
        private const val NOTHING_SAID = "- (nothing readable has been said under this name)"

        /**
         * What the model is asked to decide.
         *
         * **The default is to admit, and it is stated as a rule rather than left to the model's
         * judgement.** The two mistakes are not symmetrical: a track wrongly admitted offers
         * material under a label nobody would ask about, while a body of work wrongly rejected can
         * no longer be asked about at all. So an unsettled sample answers the way that changes
         * nothing.
         *
         * **It describes what each kind *is* rather than listing names to match.** A word list is
         * what this pass exists to replace: the same label is a client in one archive and a category
         * in another. The examples are there to show the shape of each kind and are marked as
         * examples.
         */
        val JUDGE_SYSTEM = """
            You decide what one named group conversation in a work archive is for. You are given the
            name it carries and the most recent things said under it.

            Answer with exactly one of these two words, alone on a line, and nothing else:

            BODY_OF_WORK — the conversation is where work for one organisation or one project is
            carried on. Its name identifies that work: a customer, a client, a product, a project,
            or a team doing one of those. The messages are people getting a particular piece of work
            done with or for a named counterparty.

            THEMATIC_TRACK — the conversation collects material about a theme instead of carrying
            out work for anybody. Its name identifies a function, a category, a stage of a pipeline,
            or a shelf of things. By way of example only: incoming referrals, partner or affiliate
            programmes, banking and payments, hiring, bookkeeping, tooling, reading material,
            announcements, or purely social talk. The messages are people filing, forwarding and
            discussing a kind of thing rather than delivering one engagement.

            Judge the name and the messages together. A name on its own is frequently ambiguous,
            because the same word names a client in one archive and a category in another. What
            settles it is what the messages are doing.

            Answer BODY_OF_WORK whenever the material does not settle the question, including when
            nothing readable has been said. The two mistakes cost differently: material admitted
            under a label nobody asks about is ignored, while work that is rejected cannot be asked
            about at all.
        """.trimIndent()
    }
}

/**
 * What one sweep of the admission pass may read.
 *
 * No instant and no horizon, unlike every other pass's bounds: this one reads no clock and reaches
 * back over the whole corpus, because a body of work discussed years ago is still one.
 */
data class AdmissionBounds(
    /**
     * How many conversations one batch judges.
     *
     * A bound on one batch rather than on a sweep: the driver drains a stage until nothing is owed,
     * and what stops it before that is the day's allowance. What this decides is how much of the
     * day's figure a single batch can overshoot by, since nothing prices a batch before it runs.
     */
    val batchSize: Int = Admitter.DEFAULT_BATCH_SIZE,
    /**
     * How many of a conversation's newest messages are shown.
     *
     * In [Admitter.version], so widening it re-judges what a narrower sample decided. Newest rather
     * than a spread, because what a conversation is for is best evidenced by what is currently going
     * on in it — and because the alternative is a second decision about how to spread a sample that
     * nothing in this build needs.
     */
    val sample: Int = Admitter.DEFAULT_SAMPLE,
) {
    init {
        require(batchSize > 0) { "A batch of $batchSize conversations judges nothing." }
        require(sample > 0) {
            "A sample of $sample messages shows the model a name and no material, which is the one " +
                "thing a name cannot be judged on."
        }
    }
}

/**
 * What one batch of the admission pass did, and what it cost.
 *
 * **[settled] and [judged] are two figures, and the difference is what the pass marked without
 * asking anybody.** A two-person exchange and an unnamed conversation are settled and not judged;
 * so is a conversation the provider refused. Reported as one number, a sweep that paid for nothing
 * and a sweep that judged everything read alike.
 *
 * [rejected] is the figure the pass exists for — conversations that will no longer be offered as
 * clients — and it is reported even at zero, because zero is the expected answer on a source where
 * every named conversation is a body of work, and a reader has to be able to tell that from a pass
 * that is not running.
 *
 * [unreadable] is a reply that named both verdicts or neither; [unread] is one that ran out of room
 * or stopped mid-flight. Both leave the conversation owed and both are the build's problem rather
 * than the corpus's. [declined] is what the provider would not look at, carried out whole rather
 * than counted, since the category is the only thing that says why.
 */
data class Admissions(
    val settled: Int = 0,
    val judged: Int = 0,
    val rejected: Int = 0,
    val unreadable: Int = 0,
    val unread: Int = 0,
    val declined: List<String> = emptyList(),
    val usage: TokenUsage = TokenUsage.NONE,
) {

    /** Two batches' work, added — which is how one conversation at a time comes to one batch. */
    operator fun plus(other: Admissions) = Admissions(
        settled = settled + other.settled,
        judged = judged + other.judged,
        rejected = rejected + other.rejected,
        unreadable = unreadable + other.unreadable,
        unread = unread + other.unread,
        declined = declined + other.declined,
        usage = usage + other.usage,
    )

    companion object {
        /** A batch with nothing to do, which is what a judged corpus produces. */
        val NOTHING = Admissions()
    }
}
