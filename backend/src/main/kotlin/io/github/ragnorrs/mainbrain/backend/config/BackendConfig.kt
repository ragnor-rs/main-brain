package io.github.ragnorrs.mainbrain.backend.config

import io.github.ragnorrs.mainbrain.data.ClaudeProvider
import io.github.ragnorrs.mainbrain.domain.Effort
import io.github.ragnorrs.mainbrain.domain.answer.Budget
import io.github.ragnorrs.mainbrain.domain.answer.Sizing
import io.github.ragnorrs.mainbrain.domain.recognition.Closer
import io.github.ragnorrs.mainbrain.domain.recognition.Inferrer
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * Everything this process is told, read once, at the start.
 *
 * **Fail-fast, and blank counts as missing.** Compose interpolates an unset variable to an empty
 * string, so a service that treated `""` as a value would serve the one hop it has with an empty
 * shared secret — which is a surface anything on the network satisfies. A process that cannot be
 * configured says which variable it wanted and stops.
 *
 * **The whole of what this process reads is read here and nowhere else**, which is the property
 * worth stating rather than a list of what that currently is. Every value arrives through [fromEnv]
 * and lands in one of the types below, so the declarations are the inventory; a second one in prose
 * beside them is a tally that goes stale the next time a value is added, silently, because nothing
 * reads it.
 *
 * **The sidecar and the credential are optional, and what they switch off is work rather than the
 * process.** Neither is required because a credential-free stack is how the store and ingestion
 * are exercised — locally, and in every worktree — and a backend that refused to start without a
 * model key would take the corpus down with the derived layer.
 *
 * What each absence costs differs by the work asking, which is why they are two values here and
 * not one state. **A pass loses whichever half it needs:** absent the sidecar address nothing is
 * indexed, absent the credential nothing is inferred, and identity needs neither and runs
 * regardless — which is why a stack with no credential at all still recognises contacts.
 * **The answer path loses both or neither**, because there is no half-configured path to
 * assemble: `shapes()` takes a `Lookup` and not a nullable one, and a `Lookup` needs somewhere to
 * embed a question. So [answerPathMissing] names what an absent one wanted, down to the variable.
 *
 * That last claim is narrower than it looks, and deliberately not widened. Two of the five shapes
 * retrieve over the corpus alone and would answer with no sidecar at all; serving those while
 * refusing the rest is a partial answer path, and nothing has asked for one.
 *
 * The model credential's presence is worth saying separately, because the backend is the one
 * process that may hold one. Deployment is what withholds it from the others.
 */
internal data class BackendConfig(
    val store: StoreConfig,
    val secret: String,
    val embeddingsUrl: String?,
    val modelCredential: String?,
    val passes: PassConfig,
    val models: ModelConfig,
    val answer: AnswerConfig,
    /**
     * The zone the archive's correspondents write in, which is what their material is dated in.
     *
     * **A deployment's to set, because it is a fact about whose archive this is and nothing can
     * read it.** An instant carries no zone, the platform reports a reader's language but never
     * their zone, and the remaining route — inferring it from the hours people happen to write in
     * — was measured against this build's own corpus and pins no single zone. So it is asked for
     * rather than guessed at.
     *
     * **UTC when unset, which is the zone the material was always dated in.** The alternative is
     * refusing to start, and that would take down a stack over a value that costs a day's
     * accuracy on a little of the material rather than the ability to answer. What it costs is
     * stated where it bites: see `Assemble.written`.
     */
    val zone: ZoneId,
) {

    /**
     * Which of the answer path's two variables are unset, named as a deployer would set them.
     *
     * Empty exactly when this process holds both, which is the decision the container assembles
     * on — so this is the whole of *whether* it can answer as well as the report of why it
     * cannot. A list of names rather than a sentence, because the one reader is whoever deploys
     * this and the actionable thing is the variable; and derived from the two values rather than
     * recorded beside them, so it cannot disagree with what was read.
     *
     * A function on the configuration rather than a state assembled in the container, because
     * this is the half of the decision worth testing and `:backend` holds no store to test it
     * through. It is pure, so all four combinations are a unit test.
     */
    fun answerPathMissing(): List<String> = listOfNotNull(
        CREDENTIAL.takeIf { modelCredential == null },
        SIDECAR.takeIf { embeddingsUrl == null },
    )

    companion object {

        /** The model credential. Passed to this process and to no other. */
        private const val CREDENTIAL = "ANTHROPIC_API_KEY"

        /** Where the embeddings sidecar answers. */
        private const val SIDECAR = "EMBEDDINGS_URL"

        /**
         * Read the configuration, or say what is missing.
         *
         * The reader is a parameter so that this can be exercised without an environment, which
         * is also why nothing here calls `System.getenv` directly.
         */
        fun fromEnv(env: (String) -> String? = System::getenv) = BackendConfig(
            store = StoreConfig(
                url = env.required("DATABASE_URL"),
                user = env.required("DATABASE_USER"),
                password = env.required("DATABASE_PASSWORD"),
            ),
            // The one hop this process serves. Required rather than defaulted, and there is no
            // development default on purpose: a fallback value is a published value, and the
            // surface it guards answers questions about somebody's whole archive.
            secret = env.required("BACKEND_SECRET"),
            // A service name on the Compose network rather than something an installation sets,
            // so this is read and not configured anywhere but in `docker-compose.yml`. Optional
            // all the same: a stack brought up without the sidecar indexes nothing and serves
            // everything else.
            embeddingsUrl = env.optional(SIDECAR),
            modelCredential = env.optional(CREDENTIAL),
            passes = passes(env),
            models = ModelConfig(
                answering = env.optional("ANSWER_MODEL") ?: ClaudeProvider.ANSWERING,
                bulk = env.optional("BULK_MODEL") ?: ClaudeProvider.BULK,
            ),
            // The ceilings are the code's and the efforts are the deployment's, which is the whole
            // of the split: effort trades depth against spend, where a lowered ceiling buys
            // truncations that this build reports as having nothing to answer from.
            answer = AnswerConfig(
                answering = Sizing.ANSWERING.copy(
                    effort = env.effort("ANSWER_EFFORT") ?: Sizing.ANSWERING.effort,
                ),
                researching = Sizing.RESEARCHING.copy(
                    effort = env.effort("RESEARCH_EFFORT") ?: Sizing.RESEARCHING.effort,
                ),
                perRequest = env.tokens("ANSWER_MAX_TOKENS_PER_REQUEST") ?: Budget.DEFAULT_CEILING,
            ),
            zone = env.zone("CORPUS_TIME_ZONE"),
        )

        /**
         * What the scheduled work is paced and bounded by.
         *
         * **Lifted out of [fromEnv] because the reader is one branch per value and there are now
         * nine of them**, which took that function past what the complexity gate allows. Every
         * other group here is a handful of values and stays inline; this one is the group that
         * grows, because every pass added to the build adds at least a cadence to it.
         */
        private fun passes(env: (String) -> String?) = PassConfig(
            indexing = env.seconds("INDEXING_SECONDS_BETWEEN_SWEEPS") ?: DEFAULT_INDEXING_CADENCE,
            recognition = env.seconds("RECOGNITION_SECONDS_BETWEEN_SWEEPS") ?: DEFAULT_RECOGNITION_CADENCE,
            horizon = env.days("RECOGNITION_HORIZON_DAYS") ?: DEFAULT_HORIZON,
            // The pass's own default rather than a second one here, as below and for the same
            // reason. Read as messages because that is what inference selects — `RecordKind.MESSAGE`
            // and nothing else — so the quantity and the reader naming it agree.
            messagesPerSweep = env.messages("RECOGNITION_MESSAGES_PER_SWEEP") ?: Inferrer.DEFAULT_BATCH_SIZE,
            // The pass's own default converted rather than restated, so the figure lives in
            // one place: a second default here would be the one a deployment got by leaving the
            // variable unset, and the two would drift silently apart.
            closure = env.days("RECOGNITION_CLOSURE_DAYS") ?: Closer.DEFAULT_EXAMINE.toKotlinDuration(),
            // The pass's own default again, for the same reason.
            closureRearm = env.messages("RECOGNITION_CLOSURE_REARM_MESSAGES") ?: Closer.DEFAULT_REARM,
            inference = env.flag("RECOGNITION_INFERENCE_ENABLED") ?: DEFAULT_INFERENCE,
            admission = env.seconds("ADMISSION_SECONDS_BETWEEN_SWEEPS") ?: DEFAULT_ADMISSION_CADENCE,
            admissionEnabled = env.flag("ADMISSION_ENABLED") ?: DEFAULT_ADMISSION,
            inputTokensPerDay = env.tokensPerDay("RECOGNITION_INPUT_TOKENS_PER_DAY")
                ?: DEFAULT_INPUT_TOKENS_PER_DAY,
            outputTokensPerDay = env.tokensPerDay("RECOGNITION_OUTPUT_TOKENS_PER_DAY")
                ?: DEFAULT_OUTPUT_TOKENS_PER_DAY,
        )

        /**
         * How long to leave between sweeps of the indexing pass.
         *
         * A minute, which is the ingest scheduler's own idle and for the same reason: it decides
         * how soon a captured message becomes retrievable, and the cost of asking is one count
         * against the store when there is nothing to do. Indexing is local compute against a
         * sidecar on the same host, so there is no budget here to protect.
         */
        private val DEFAULT_INDEXING_CADENCE: Duration = 1.minutes

        /**
         * And between sweeps of recognition, which is two orders of magnitude slower.
         *
         * An hour, because inference spends the model budget per window and that budget is the
         * credit provisioned for this build. A sweep drains whatever is owed when it starts, so
         * this is how long a newly captured message waits to be read rather than a limit on how
         * much is read — which is the right shape for a knob whose cost is money.
         *
         * It paces the identity stage too, which needs no pacing at all: it costs nothing and
         * shares this cadence only because it shares recognition's watermark key and exclusion.
         * Were identity ever the thing holding the derived layer back, it would want splitting
         * out rather than this number lowering.
         */
        private val DEFAULT_RECOGNITION_CADENCE: Duration = 1.hours

        /**
         * How far back inference reads, as a duration behind the moment each sweep starts.
         *
         * **This is the one setting in this build that spends money, and the default is bounded
         * by the budget rather than by what would answer best.** Inference is one model call per
         * window plus one per finding, windows are twenty messages at a stride of ten, and a
         * sweep drains to completion — so a first sweep over a wide horizon spends what it is
         * going to spend in one go, with no natural place to stop and look. Against the credit
         * provisioned for this build, a horizon of some months over a real archive can plausibly
         * exhaust it before anybody reads a log line.
         *
         * Fourteen days is therefore a number the budget survives, not a number the request
         * shapes asked for. **Widening it is cheap and safe to do deliberately** — a deploy and a
         * re-derivation inside the horizon, spending model tokens rather than touching a source —
         * so the right way to find the real value is to raise this while watching what a sweep
         * reports it cost. Overspending is the direction that does not reverse.
         *
         * Capture and indexing are deliberately not bounded by it, because neither spends the
         * credit: the corpus and the index reach back as far as the archive allows regardless.
         *
         * A duration rather than an instant, because the horizon has to move: a process left
         * running for a week with a fixed cut-off would read its first sweep's idea of recent for
         * ever. The instant is computed per sweep, which is also why the pass takes one and holds
         * no clock of its own.
         */
        private val DEFAULT_HORIZON: Duration = 14.days

        /**
         * Whether the half of recognition that spends the model budget runs at all.
         *
         * **Off, so that bringing the stack up cannot spend anything.** Every other value here
         * paces or bounds the work; this one decides whether it happens, and it is the only
         * default in this file chosen against what the build would like rather than against what
         * it costs. A sweep drains whatever is owed the moment a container is healthy, at a model
         * call per window — so a deployment that nobody meant to leave running spends the credit
         * on its own schedule, and the first evidence is the balance. It has happened.
         *
         * Identity is unaffected and still runs: it is identifier matching and label reading, it
         * costs nothing, and it is what has to run ahead of inference anyway.
         *
         * Turning it on is one variable, which is the point — the spend becomes something
         * somebody chose rather than something a `compose up` did.
         */
        private const val DEFAULT_INFERENCE: Boolean = false

        /**
         * The most recognition may spend on input tokens in a calendar day.
         *
         * **The horizon above bounds what is read; this and its output counterpart bound what
         * recognition pays to read it, and the two are not substitutes.** A sweep drains until
         * nothing is owed, so the cadence says nothing about cost: a backlog inside even a narrow
         * horizon is paid for in one unattended run. A day rather than a sweep because a bound per
         * sweep is granted afresh at every cadence, so a night costs as many ceilings as it has
         * hours, and because a figure held in the store is one a restart cannot reset.
         *
         * **Sized from what a record measurably costs, and set to admit a backfill while refusing
         * a re-derivation.** A record costs about three and a third thousand input tokens and nine
         * hundred output, measured over a full cycle and already halved by the batch endpoint. So
         * standing still at the rate new material arrives is a fraction of this; draining a backlog
         * of a thousand-odd records takes about three days at it; and re-reading the whole horizon
         * after the version moves — which costs more per record than a steady sweep, because every
         * window is read again — does not fit inside one day and stops with a date on it instead of
         * emptying the credit.
         *
         * **Separate from the output allowance because the provider prices the two an order of
         * magnitude apart.** Output costs five times input, so one allowance over their sum would
         * be set by whichever axis moved last and would misstate both. Raise either to backfill
         * faster, watching what each sweep reports it cost.
         */
        private const val DEFAULT_INPUT_TOKENS_PER_DAY: Long = 1_300_000

        /**
         * The most recognition may spend on output tokens in a calendar day.
         *
         * The companion to [DEFAULT_INPUT_TOKENS_PER_DAY] and sized from the same measurement, at
         * the ratio the two axes were actually observed in rather than at the ratio they are priced
         * in. Either one exhausting stops the stage that spends, so the pair has to be set
         * together: an output allowance that binds first makes the input one decoration, and the
         * reverse hides whichever axis a change to the prompts moves.
         */
        private const val DEFAULT_OUTPUT_TOKENS_PER_DAY: Long = 360_000

        /**
         * How long between sweeps of the pass that judges which named conversations are work.
         *
         * A day, which is the longest cadence in this file and is right because the pass converges:
         * a conversation carries a verdict for as long as the instruction that reached it stands, so
         * there is nothing to do between a new conversation appearing and a reword being deployed.
         * A shorter cadence would buy a store query per sweep and no judgement.
         */
        private val DEFAULT_ADMISSION_CADENCE: Duration = 1.days

        /**
         * Whether that pass runs at all.
         *
         * **Off, for the reason [DEFAULT_INFERENCE] is off and one more of its own.** It spends the
         * model budget, so a stack nobody meant to leave running must not start paying on its own
         * schedule. And it pays only where the source mixes bodies of work with thematic tracks:
         * where every named conversation is already work, every call returns the verdict identity
         * had already read off the label and the tokens buy no change in output.
         *
         * Nothing downstream waits for it. A conversation with no verdict is a client exactly as it
         * was, so leaving this off is the corpus as this build otherwise reads it rather than a
         * stage of the answer path missing.
         */
        private const val DEFAULT_ADMISSION: Boolean = false
    }
}

/**
 * The typed readers every value in this file arrives through.
 *
 * **Top-level private rather than members of [BackendConfig]'s companion**, which is what keeps
 * that object under `TooManyFunctions` without a suppression: the companion holds the entry point
 * and the pass group it reads, and the parsing lives out here beside [zone] — which was the first
 * of these to move, for exactly this reason. They are extensions on the reader function rather
 * than free functions so that a call site reads as asking the environment for a kind of value.
 *
 * Each refuses a malformed value rather than reading it as unset. A typo'd figure taken as absent
 * would silently restore the default, so the one value somebody could be sure was not in force
 * would be the one they had set.
 */

private fun ((String) -> String?).required(name: String): String =
    this(name)?.takeIf { it.isNotBlank() }
        ?: error("$name is not set. The backend reads it at startup and cannot run without it.")

private fun ((String) -> String?).optional(name: String): String? =
    this(name)?.takeIf { it.isNotBlank() }

/**
 * A whole number of seconds, refusing anything else rather than reading as unset.
 *
 * The same reader `:ingest` uses, and the same reason: a typo'd cadence read as absent
 * would silently restore the default, so the value somebody set would be the one value
 * they could be sure was not in force.
 */
private fun ((String) -> String?).seconds(name: String): Duration? = optional(name)?.let { value ->
    value.toIntOrNull()?.seconds ?: error("$name must be a whole number of seconds; '$value' is not one.")
}

/** A whole number of days, refused in the same way and for the same reason. */
private fun ((String) -> String?).days(name: String): Duration? = optional(name)?.let { value ->
    value.toIntOrNull()?.days ?: error("$name must be a whole number of days; '$value' is not one.")
}

/**
 * A plain true or false, refused rather than guessed at.
 *
 * Narrower than the usual parsing on purpose: `yes`, `1` and `on` are all things somebody
 * means by true, and a reader accepting some of them teaches that it accepts the rest.
 * The one value this gates costs money, so the variable it is read from should say exactly
 * what it means and nothing near it should be taken as agreement.
 */
private fun ((String) -> String?).flag(name: String): Boolean? = optional(name)?.let { value ->
    when (value.lowercase()) {
        "true" -> true
        "false" -> false
        else -> error("$name must be true or false; '$value' is neither.")
    }
}

/**
 * A whole number of output tokens for one request, refused the same way again.
 *
 * Positive rather than merely numeric, because zero and below are the two values that read
 * as a deployment having thought about this and chosen nothing: a ceiling of zero refuses
 * every question, and the refusal looks exactly like a corpus with no answer in it.
 */
private fun ((String) -> String?).tokens(name: String): Int? = optional(name)?.let { value ->
    val count = value.toIntOrNull()
        ?: error("$name must be a whole number of tokens; '$value' is not one.")
    count.takeIf { it > 0 }
        ?: error("$name must be above zero; at $count no request could be answered at all.")
}

/**
 * A whole number of messages, refused rather than read as unset.
 *
 * Its own reader rather than [tokens]'s, which parses the same shape but is not the same
 * quantity: a reader named for tokens would read as though a count of messages were a count
 * of them. It parses and nothing more, as [tokensPerDay] does and unlike [tokens], which
 * refuses a figure at or below zero itself — the usable range is [PassConfig]'s here, so
 * that what stops the process is the message saying what the figure would have done.
 */
private fun ((String) -> String?).messages(name: String): Int? = optional(name)?.let { value ->
    value.toIntOrNull() ?: error("$name must be a whole number of messages; '$value' is not one.")
}

/**
 * A whole number of tokens for a whole day, refused in the same way again.
 *
 * **A `Long` and not an `Int`, and separate from [tokens] for that reason rather than for
 * tidiness.** These are counted against what the provider reports, and those figures are
 * `Long` the whole way through. An allowance nobody would set this high is still better read
 * than silently truncated — and truncating one would land on zero, which is the single value
 * the reader above exists to refuse.
 *
 * Not named for either axis, because both are read through it: input and output are bounded
 * separately but parsed the same way, and a reader named for one of them would read as
 * though the other were a different kind of number.
 */
private fun ((String) -> String?).tokensPerDay(name: String): Long? = optional(name)?.let { value ->
    value.toLongOrNull() ?: error("$name must be a whole number of tokens; '$value' is not one.")
}

/**
 * One of the effort levels the model offers, by name and in any case.
 *
 * **Refused rather than read as unset, which matters more here than for a cadence.** The
 * levels are an enum on the model's side too, so a misspelling is not a value the provider
 * would reject on the next call — it is a variable somebody set to buy a cheaper answer
 * that silently kept costing the default. The valid names are listed back, because the one
 * reader of this message is whoever typed the wrong one.
 */
private fun ((String) -> String?).effort(name: String): Effort? = optional(name)?.let { value ->
    Effort.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: error(
            "$name must be one of ${Effort.entries.joinToString { it.name.lowercase() }}; " +
                "'$value' is not one of them.",
        )
}

/**
 * The zone material is dated in, or UTC.
 *
 * **The one reader that carries its own default rather than returning null for its caller to
 * resolve**: the rest answer *was this set*, and a caller decides what absence means. Absence
 * means one thing here and the meaning is not the caller's to vary — UTC is the zone the material
 * has always been dated in, so it is the behaviour being kept rather than a fallback being chosen.
 *
 * **It sits outside the companion, as every reader above it now does**, which is what keeps that
 * object under `TooManyFunctions` without a suppression — the companion holds the entry point and
 * the pass group it reads, and nothing else. This one moved out first, when it was the only one
 * that had to; the rest followed when the object next needed a function. Moving any of them back
 * in reddens detekt, and said plainly because the distinction above would otherwise read as the
 * whole reason and invite exactly that tidy-up.
 *
 * **A region is the right thing to name, and an offset is still accepted.** The corpus reaches
 * back years, and a fixed offset dates every message wrongly for half of them wherever summer time
 * is observed — in the direction that moves a day boundary, which is the one thing this exists to
 * get right. `ZoneId.of` takes either, so a deployment that knows it wants an offset may say so.
 *
 * Refused on a typo for the reason a cadence is, and the symptom is worse: a misread zone restores
 * UTC silently, and what a reader sees is a date that is a day out on some material and not on
 * other, which reads as the model getting it wrong rather than as a variable being misspelt.
 */
private fun ((String) -> String?).zone(name: String): ZoneId {
    val value = this(name)?.takeIf { it.isNotBlank() } ?: return ZoneOffset.UTC
    return runCatching { ZoneId.of(value) }.getOrNull()
        ?: error("$name must be a time zone such as Europe/Lisbon or UTC; '$value' is not one.")
}

/** How to reach the store. The same three values every process in this stack is given. */
internal data class StoreConfig(
    val url: String,
    val user: String,
    val password: String,
)

/**
 * What the derived passes are told: how often each runs, how far back inference reads, and what
 * recognition may spend in a day reading it.
 *
 * Two cadences rather than one, because the passes differ in what a batch costs and in nothing
 * else. Grouped into their own type so that the thing a reader of this process asks — what does
 * it do when nobody is talking to it — is answered in one place.
 *
 * Every value here but the cadences bounds spending, and they bound different parts of it: how
 * much work a sweep may find, how often and how long a promise is re-read for, whether any of it
 * runs at all, and what a day may pay before the rest is left owed. The allowances are a pair
 * rather than a figure because the provider prices input and output an order of magnitude apart,
 * and either one exhausting is enough to stop the spending.
 */
internal data class PassConfig(
    val indexing: Duration,
    val recognition: Duration,
    val horizon: Duration,
    /**
     * How many messages one sweep of inference selects.
     *
     * **Configuration because it is the only lever on how fast a horizon drains, and the cadence is
     * not one.** A stage holds a single submission in flight, so a sweep's rate is this figure over
     * the provider's batch turnaround; a shorter cadence only shortens the wait before a finished
     * batch is read, which is seconds against the minutes a batch takes. A deployment with a
     * backlog and a deadline in front of it has nothing else to turn.
     *
     * Messages rather than records, which is what the pass bounds: inference selects one record
     * kind and only that one, so naming the kind says more here than the word the bound uses.
     *
     * What a message costs is unchanged by how many it is read alongside — each is its own
     * classification window and its own restatements — so this moves when a day's work happens and
     * not what it comes to. What a day may spend is [inputTokensPerDay]'s to bound.
     *
     * Outside the extractor's version, unlike the reading bounds beside it: how many messages were
     * selected together does not change how any one of them was read, so moving this re-derives
     * nothing already stored.
     */
    val messagesPerSweep: Int,
    /**
     * How far past a promise the closure pass keeps looking for what discharged it.
     *
     * **Configuration rather than a constant, because what it should be is a judgement about the
     * work and not about the code**: how long after a promise its closure is still worth paying to
     * find. It is also the bound that makes the stage affordable — an unclosed promise is otherwise
     * re-examined for as long as its conversation keeps growing, which on a live source is for
     * ever.
     *
     * Unlike [horizon] it is measured from each commitment rather than from the sweep, so it does
     * not decide which commitments are eligible: a promise older than this is still examined once,
     * which matters on a backfilled archive where most of the corpus is older than any sane bound.
     */
    val closure: Duration,
    /**
     * How much new material brings a commitment back for another examination.
     *
     * **Configuration beside [closure] because it is the other half of the same judgement**, and the
     * half shaped like material rather than like time: the horizon says how long a closure is worth
     * looking for, this says how much has to have arrived before looking again is worth a call. An
     * examination is billed for its instruction and its claim whether it weighs one new message or
     * twenty, so with no floor a conversation arriving a message at a time buys a call per message
     * and pays mostly for the fixed part.
     *
     * Raising it spends less and finds a closure later; lowering it the reverse, and at one the
     * stage is back to re-examining for a single message. **What it cannot do is cost a finding.**
     * A first reading ignores it, and it lapses as [closure] closes — so material a bounded window
     * could not reach is read before the commitment is settled, however little of it there is.
     */
    val closureRearm: Int,
    val inference: Boolean,
    /**
     * How long between sweeps of the admission pass.
     *
     * Its own cadence rather than recognition's, because the two are paced by different things: a
     * sweep of recognition reads whatever messages arrived, and a sweep of this reads whatever
     * conversations arrived — which on any real source is almost never.
     */
    val admission: Duration,
    /**
     * Whether the admission pass runs.
     *
     * Beside [inference] and for the same reason: it is the other stage that spends, and whether a
     * deployment wants to pay for it is a decision rather than a consequence of holding a
     * credential. Unlike inference, what it buys depends on the source — on a taxonomy where every
     * named conversation is already a body of work it rejects nothing.
     */
    val admissionEnabled: Boolean,
    val inputTokensPerDay: Long,
    val outputTokensPerDay: Long,
) {
    init {
        require(indexing.isPositive()) { "An indexing cadence of $indexing would sweep without pause." }
        require(recognition.isPositive()) { "A recognition cadence of $recognition would sweep without pause." }
        require(admission.isPositive()) { "An admission cadence of $admission would sweep without pause." }
        require(horizon.isPositive()) { "A horizon of $horizon reaches back no distance, so nothing is inferred." }
        require(messagesPerSweep > 0) {
            "A sweep of $messagesPerSweep messages selects nothing, so no horizon would ever drain."
        }
        require(closure.isPositive()) {
            "A closure horizon of $closure past a promise would weigh nothing after it, so no " +
                "closure could ever be recognised."
        }
        require(closureRearm > 0) {
            "A re-arm floor of $closureRearm messages puts no floor on re-examination, so a " +
                "commitment would be read again for a single new message."
        }
        require(inputTokensPerDay > 0) {
            "An allowance of $inputTokensPerDay input tokens a day leaves inference nothing to " +
                "spend, so nothing would ever be inferred."
        }
        require(outputTokensPerDay > 0) {
            "An allowance of $outputTokensPerDay output tokens a day leaves inference nothing to " +
                "spend, so nothing would ever be inferred."
        }
    }
}

/**
 * Which model each kind of work asks.
 *
 * **Two, because the two kinds of work are opposites.** Answering is a dozen calls a day whose
 * quality a person reads; the bulk passes — recognition's inference, and the classification that
 * routes free text — are thousands of calls producing mechanical output, and they are most of what
 * this build costs. One model for both meant scanning short messages for who promised what at the
 * price of composing an answer.
 *
 * Both are settable, and [bulk] is the one worth being able to set without a rebuild: an
 * extractor's watermark carries the model it read with, so moving this re-derives the horizon on
 * the next sweep — which is the right behaviour and a real bill, and either direction of that
 * change should be reachable from a deployment.
 */
internal data class ModelConfig(
    val answering: String,
    val bulk: String,
) {
    init {
        require(answering.isNotBlank()) { "ANSWER_MODEL names no model, so nothing could answer." }
        require(bulk.isNotBlank()) { "BULK_MODEL names no model, so nothing could be inferred." }
    }
}

/**
 * What one question may spend.
 *
 * [answering] and [researching] size the individual calls — see [Sizing], which is also where the
 * reason the ceilings are not configuration lives. [perRequest] is the bound over all of them
 * together, and it is the only thing standing between a pathological question and the provider's
 * patience: a shape's ceiling bounds one call, where a turn may pause and be resumed and the runner
 * re-rolls a rejected reply once. Lowering it costs an answer; leaving it unset costs whatever
 * three full calls cost.
 */
internal data class AnswerConfig(
    val answering: Sizing,
    val researching: Sizing,
    val perRequest: Int,
) {
    init {
        // Against the larger of the two, which is research's: a request ceiling under the ceiling
        // of the call it has to serve sizes that call to the remainder instead, so the shape asks
        // for less room than it was built to need and truncates rather than costing less.
        val widest = maxOf(answering.ceiling, researching.ceiling)
        require(perRequest >= widest) {
            "ANSWER_MAX_TOKENS_PER_REQUEST is $perRequest, below the $widest one call may ask for. " +
                "Every call would then be sized to the request's remainder, which truncates " +
                "answers rather than saving money."
        }
    }
}

/**
 * The port the surface listens on.
 *
 * A constant rather than a setting. The bot reaches it by service name on the Compose network, so
 * the number is a property of the deployment rather than of an installation — and
 * `docker-compose.yml` names the same one in the bot's `BACKEND_URL`.
 *
 * The serving backend publishes no host port, and this being a constant is part of why it does not
 * need to: nothing outside the network has a reason to know it. The evaluation set is the one
 * caller that does, and it reaches a **second** backend under the `eval` profile, which maps this
 * same port out to the loopback address. So the suite reads the answer path through the route a
 * request uses without the serving stack growing a surface for it.
 */
internal const val PORT = 8080
