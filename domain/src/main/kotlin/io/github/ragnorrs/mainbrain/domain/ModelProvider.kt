package io.github.ragnorrs.mainbrain.domain

/**
 * The only route to the model.
 *
 * Every call that spends the model budget goes through here: recognition's extraction stage
 * and the answer path's stages alike. One interface rather than a client each means the
 * provider's quirks are handled once, the credential is held in one place, and swapping or
 * benchmarking a second provider is a new implementation rather than a refactor.
 *
 * The port names nothing from any SDK, which is not a style preference — `:domain` depends on
 * nothing, so a provider type in one of these signatures is a layering failure the build
 * catches. What crosses this boundary is the request and [ModelOutcome], both declared here.
 */
interface ModelProvider {

    /**
     * Which model is behind this, as a string that changes whenever its output would.
     *
     * Half of what a derived pass puts in its watermark — see [DerivationStateRepository] — and
     * the half the pass cannot know for itself. Recognition's extractor is invalidated by a
     * changed prompt *and* by a changed model, and without this it would re-derive for the first
     * and silently keep stale output for the second.
     *
     * Not `suspend`, unlike [EmbeddingProvider.version]: that one asks a sidecar which model it
     * loaded, because the sidecar can restart onto another one. This is configuration of the
     * process, known without asking anybody.
     */
    fun version(): String

    /**
     * Send [request] and return what the model actually did.
     *
     * `suspend` because this is a network call that takes seconds: a plain function would
     * block whichever thread asked.
     */
    suspend fun respond(request: ModelRequest): ModelOutcome

    /**
     * Hand over several requests at once, and return the identifier they can be read back by.
     *
     * **Two functions rather than one, because the waiting is the caller's to own.** A single call
     * that blocked until every reply was in would hold a coroutine for as long as the provider
     * takes — allowed an hour and sometimes most of a day — and would lose the submission if the
     * process died, while the provider billed for it regardless. Returning an identifier puts the
     * waiting where the caller already keeps its progress, so a submission outlives the sweep that
     * made it.
     *
     * [requests] is keyed by whatever the caller will recognise its own work by, and those keys
     * come back on [collect]. The provider returns results in no particular order, so a caller
     * matching by position would attribute one piece of work's reply to another; a key the caller
     * chose is what makes that impossible rather than merely unlikely.
     *
     * **A key is bounded, and the bound belongs here rather than beside an implementation** — see
     * [MAX_KEY_BYTES]. It is a constraint on what a caller may ask, so a caller that cannot see it
     * can only discover it by being refused: an adapter holding the number alone is a rule the one
     * party who has to obey it has no way to check.
     *
     * **Every request in one submission shares a [ModelRequest.system], and that is required
     * rather than advised.** One submission is then one request shape, so every reply in a
     * collection means the same thing and one caller reads them all with one parser. A caller with
     * two shapes makes two submissions.
     *
     * **A shared prefix is not read from cache within one submission, however, so a caller must
     * not count on it.** The requests of a submission are answered concurrently and in any order,
     * and an entry becomes readable only once the first response using it has begun — so requests
     * that share a prefix here cannot be relied on to read what one of them writes. What makes a
     * shared prefix pay is an entry written before the submission is handed over, by an ordinary
     * call of the same shape.
     *
     * Submitting is not sending: nothing has been answered when this returns, and [collect] is how
     * a caller finds out whether anything has.
     */
    suspend fun submit(requests: Map<String, ModelRequest>): SubmissionId

    /**
     * Read back what [submission] has produced, or that it has not finished.
     *
     * Safe to call as often as a caller likes: asking is not billed, and a submission that has not
     * finished answers [Collected.Waiting] rather than blocking. Calling it after the process that
     * submitted has died is the case it exists for — the identifier is all that is needed, so a
     * restart collects what the previous run paid for.
     */
    suspend fun collect(submission: SubmissionId): Collected

    companion object {

        /**
         * The longest key [submit] will carry, in UTF-8 bytes.
         *
         * **A caller's constraint, which is why it is on the port.** The provider identifies a
         * batched request by a short token in a narrow alphabet, so a key is encoded on the way
         * out — base64url, three bytes to four characters, which is where the number comes from.
         * An adapter can refuse a key that does not fit, and refusing is all it can do: by then the
         * caller has already decided what to ask under.
         *
         * **What it rules out is a key that carries its own data.** Two identifiers and a label do
         * not fit here, so a request about more than one stored thing names them in the payload
         * travelling beside it and is keyed by something short — see
         * [SubmittedWindow.payload]. A key built by concatenating what a request is about looks
         * right, passes every test written against a double, and is refused by the provider for
         * every request the pass makes.
         */
        const val MAX_KEY_BYTES = 48
    }
}

/**
 * What a submission is called, for as long as it takes to answer.
 *
 * A value of `:domain`'s own rather than the provider's identifier type, because the port names
 * nothing from any SDK and the build enforces it. What it wraps is opaque: it is the provider's to
 * mint and this build's only to keep and hand back.
 *
 * **Unlike the string a [ModelOutcome] is read out of, this is worth persisting.** A submission is
 * billed whether or not anybody is still waiting for it, so an identifier held only in memory is a
 * charge nobody can collect against after a restart.
 */
@JvmInline
value class SubmissionId(val value: String) {
    init {
        require(value.isNotBlank()) { "A submission with no identifier cannot be read back." }
    }
}

/**
 * What came of asking after a submission.
 *
 * Sealed for the same reason [ModelOutcome] is: the two cases need opposite handling, and a caller
 * that read results without first asking whether there are any would treat an unfinished
 * submission as one that answered nothing — settling work the provider is still doing and has
 * already charged for.
 */
sealed interface Collected {

    /** The provider is still working. Nothing has been answered, and asking again later is how. */
    data object Waiting : Collected

    /**
     * The submission has finished, and [replies] holds one entry per request that was submitted.
     *
     * Keyed by the keys the caller chose on [ModelProvider.submit], because the provider answers in
     * no particular order. **One entry per request is what the provider undertakes, not something
     * this guarantees** — it reports whatever the result stream held, so a caller has to survive a
     * key it submitted and did not get back.
     */
    data class Ready(val replies: Map<String, BatchOutcome>) : Collected
}

/**
 * One request's result inside a submission.
 *
 * **A layer above [ModelOutcome] rather than a case added to it, because a batch has a failure a
 * single call does not: one reply nobody can read, among many that are fine.** On its own, an
 * unreadable reply is [ModelReplyUnreadable] and is thrown — the call landed, was billed, and
 * nothing can say what the model did, so stopping is right. Thrown out of a collection it would
 * take every other window's reply with it, and those windows would be read and paid for again. So
 * it is reported per request here, and the caller decides what one bad reply costs.
 *
 * Every case carries [usage], including the unreadable one: it was billed like any other.
 */
sealed interface BatchOutcome {

    val usage: TokenUsage

    /** The reply was read, and [outcome] is what the model did with that request. */
    data class Replied(val outcome: ModelOutcome) : BatchOutcome {
        override val usage: TokenUsage get() = outcome.usage
    }

    /**
     * The reply cannot be read: a stop reason this build has no name for, or a body that is not
     * the shape the provider documents.
     *
     * The same condition [ModelReplyUnreadable] names, and with the same semantics — a retry gets
     * the same unreadable answer and bills again, so what clears it is a build that knows the
     * value. [reason] exists so the log can say which value that was.
     */
    data class Unreadable(val reason: String, override val usage: TokenUsage) : BatchOutcome

    /**
     * The provider never produced a reply for this request.
     *
     * A submission that expired before it was collected, one that was cancelled, or a single
     * request the provider rejected or failed on. **Separate from [Unreadable] because the retry
     * semantics are opposite**, which is the same distinction [ModelUnavailable] and
     * [ModelReplyUnreadable] draw for a single call: an unreadable reply needs a new build, while
     * this needs only another attempt. Collapsing the two would make a submission that expired
     * look like one this build is too old to read, and the work would be given up instead of
     * asked again.
     *
     * [usage] is on the case for symmetry with the others and is nothing in practice: the provider
     * reports no usage on a result that carries no message, so there is none to read. It is a field
     * rather than a constant so that a provider which starts reporting one can be read without the
     * shape changing.
     */
    data class Unfinished(val reason: String, override val usage: TokenUsage) : BatchOutcome
}

/**
 * One call's worth of input.
 *
 * Single-turn on purpose. Recognition's extractor and each answer-path stage ask one question
 * and read one reply, so a conversation parameter would be a field every caller passed empty.
 * A caller that needs history — a retrieval loop driving a tool this system implements, which is
 * what the free-form fallback would take to be agentic — can have a multi-turn entry point when it
 * exists, rather than everyone carrying the cost now.
 *
 * **There is no temperature, no top-p, no top-k and no thinking budget, and that is the
 * shape rather than an omission.** The provider rejects all four outright, so a knob here
 * would exist only to produce a 400 at run time. [effort] is the one dial that works, and it
 * controls depth and spend together.
 */
data class ModelRequest(
    /**
     * Standing instructions.
     *
     * First in what the provider reads, so it is the start of every cacheable prefix. A value that
     * is byte-stable across calls of the same kind is billed at read rates after the first;
     * interpolating anything per call — a timestamp, an identifier — moves the prefix's contents
     * and costs the saving rather than reducing it.
     */
    val system: String,
    /**
     * What this call repeats from its siblings, if anything.
     *
     * **A region of its own because caching matches a prefix, so what repeats has to come before
     * what varies.** The adapter puts the breakpoint at the end of this when it is set and at the
     * end of [system] otherwise, which is always the end of the longest stretch two sibling calls
     * have in common. A caller with a large shared body and a short discriminator puts the body
     * here and the discriminator in [prompt]; written the other way round the two calls share no
     * prefix at all and the breakpoint matches nothing, whichever end it sits at.
     *
     * **It also decides whether a short [system] caches, which is the non-obvious part.** A
     * breakpoint caches everything before it rather than the one field it sits on, so a system
     * prompt too short to reach the provider's minimum on its own reaches it with this added — and
     * a prefix below that minimum caches nothing and reports nothing, with no error to notice.
     *
     * `null` for a call with no sibling to share anything with, which is every shape but one.
     */
    val shared: String? = null,
    /** This call's question, and the one part two sibling calls are not expected to share. */
    val prompt: String,
    /**
     * The ceiling on one request's output.
     *
     * Thinking counts against it, so a value sized to the prose alone truncates the answer
     * instead of the reasoning. [ModelOutcome.Truncated] is what a caller sees when it does.
     *
     * **Per request, not per call.** A turn that pauses is resumed, and each resumption is a
     * fresh request with this same ceiling — so a returned text can exceed it, by as many
     * times as the adapter is willing to resume. The bound is the adapter's, not this
     * field's; what this caps is any single response.
     */
    val maxTokens: Int,
    val effort: Effort = Effort.HIGH,
    /**
     * Whether the model may search the open web on this call.
     *
     * Off for everything that reads the corpus. The one request shape that reads the open web
     * turns it on, which is how that shape reaches the internet without any general outbound
     * access being opened for the rest of the system.
     */
    val openWeb: Boolean = false,
) {
    init {
        require(maxTokens > 0) { "maxTokens must be positive." }
        require(prompt.isNotBlank()) { "A request carries a prompt." }
        // Set-and-blank is the one state that reads as a caller having meant something by it. An
        // empty shared region moves the breakpoint past nothing, so the call is billed as though it
        // were cached against a prefix no sibling shares — a saving reported and never taken.
        require(shared == null || shared.isNotBlank()) {
            "A shared region is either absent or has something in it; blank moves the breakpoint " +
                "without giving two calls anything in common."
        }
    }
}

/**
 * How much reasoning to spend.
 *
 * Named levels rather than a token count: the provider removed fixed thinking budgets, and a
 * level is what replaced them. Higher costs more and takes longer.
 */
enum class Effort { LOW, MEDIUM, HIGH, XHIGH, MAX }

/**
 * What the model did — a complete answer, or one of the three ways a call ends without one.
 *
 * **Sealed so that a caller cannot read text without first saying which case it is reading.**
 * The provider reports a refusal, a tool-use pause and a max-tokens truncation as ordinary
 * successful responses: HTTP 200, with the reason in a field beside the content. Code that
 * reaches for the text first treats all three as finished answers and is wrong in the way
 * that is hardest to notice — a truncated analysis reads like a complete one. A sealed
 * hierarchy makes checking the reason a compile-time obligation instead of a convention
 * somebody has to remember.
 *
 * Every case carries its [usage], including the ones that produced nothing worth reading. A
 * refusal is billed for what it read, so dropping usage on the failure paths would
 * under-report the monthly cost by exactly the calls nobody looks at.
 */
sealed interface ModelOutcome {

    val usage: TokenUsage

    /** The model finished what it was asked. [text] is complete. */
    data class Answered(val text: String, override val usage: TokenUsage) : ModelOutcome

    /**
     * The model ran out of room before finishing.
     *
     * [partial] is kept rather than discarded because it is sometimes diagnostic — it shows
     * what the call was spending its tokens on. It is not an answer, and the type is what
     * stops it being rendered as one.
     */
    data class Truncated(val partial: String, override val usage: TokenUsage) : ModelOutcome

    /**
     * The provider's safety classifiers declined the request.
     *
     * [category] is whatever the provider named, and it is nullable because the provider
     * sometimes declines without naming one. A refusal is not a transport failure and not
     * retryable: the same request refuses again.
     */
    data class Refused(val category: String?, override val usage: TokenUsage) : ModelOutcome

    /**
     * The turn stopped mid-flight and no further work was possible.
     *
     * Two things produce it. The ordinary one needs [ModelRequest.openWeb]: a server-side tool
     * that runs long pauses the turn and expects to be resumed, which the adapter does for it,
     * and this case is what a caller sees when resuming still did not finish. The other is a
     * turn that stopped to wait on a tool the adapter never offered, which cannot happen as
     * requests are built today and is reported here rather than guessed at if it ever does.
     *
     * Either way [partial] is what was produced before the stop, and the type is what stops it
     * being rendered as a whole answer.
     */
    data class Paused(val partial: String, override val usage: TokenUsage) : ModelOutcome
}

/**
 * The model could not be reached, after the adapter had already retried as often as it will.
 *
 * Thrown rather than returned, because it is not something the model did — it is the call
 * never landing. The four [ModelOutcome] cases are all answers to "what did the model do?";
 * this is the absence of one, and giving it a case of its own would put a transport fault in
 * the same `when` as a refusal and invite callers to treat them alike.
 *
 * Only ever raised for the failures worth retrying — an overload, a timeout, a 5xx — once the
 * bound is spent. A malformed request is a defect in the caller and propagates as the
 * provider's own error, unretried: retrying it would turn one bug into several identical
 * ones and bill for each.
 *
 * [usage] is what the attempt had already cost when it gave up, and it is on the failure for the
 * same reason it is on every [ModelOutcome] case: the provider bills for what it read whether or
 * not anything readable came back. A turn that paused and was resumed has been billed once per
 * resumption, so a failure on the third carries the first two — which a caller counting only
 * what it was handed would have no way to learn. Nothing when the call never landed at all, which
 * is the default because it is also the common case.
 */
class ModelUnavailable(
    message: String,
    cause: Throwable? = null,
    val usage: TokenUsage = TokenUsage.NONE,
) : RuntimeException(message, cause)

/**
 * The provider answered, but the answer cannot be read.
 *
 * A reply carrying no stop reason, one newer than this build can name, or a body that does not
 * deserialise. The call landed and was billed; what is missing is any way to say what the model
 * did, and guessing that it finished is the one thing not to do.
 *
 * **Separate from [ModelUnavailable] because the retry semantics are opposite, and sharing a
 * type would invite the exact mistake that one warns about.** An overload clears; a reply this
 * build has no name for does not. Re-sending gets the same unreadable answer and bills for it
 * again, so a caller's degrade path must not treat this as worth another attempt. What clears
 * it is a build that knows the new value — a deploy, not a retry.
 *
 * [usage] is what the call cost. This is raised on a reply that arrived, so there is a charge to
 * report — with one exception, and it is narrower than *a malformed body*: each field is read
 * through its own accessor, so a first response whose *usage* is the unreadable field carries
 * nothing, there being nothing to read it off. One whose content or stop reason is the unreadable
 * field carries the whole figure, because the usage was read before either. See
 * [ModelUnavailable.usage] for why a failure carries one at all.
 */
class ModelReplyUnreadable(
    message: String,
    cause: Throwable? = null,
    val usage: TokenUsage = TokenUsage.NONE,
) : RuntimeException(message, cause)

/**
 * What one call cost, in tokens.
 *
 * Reported on every outcome so the monthly spend is measured rather than estimated. Cached
 * reads are counted separately from fresh input because they are billed at a fraction of the
 * price — collapsing them into one number would make a caching regression invisible, since
 * the total barely moves while the bill does.
 */
data class TokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
) {

    /**
     * Two calls' cost, added.
     *
     * **Here rather than only in the adapter, because the thing that needs to add these up is a
     * pass rather than a provider.** One call's usage is of no interest on its own; what a budget
     * is spent against is a batch, a sweep, a day. A caller that could not add them would report
     * the last call it made and call it the cost of the run.
     */
    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
    )

    companion object {
        val NONE = TokenUsage(inputTokens = 0, outputTokens = 0)
    }
}
