package io.github.ragnorrs.mainbrain.domain

/**
 * A [ModelProvider] that returns what a test tells it to, and records what it was asked.
 *
 * This ships with the port rather than with whichever caller needed a double first. Two
 * callers growing two fakes is how they come to disagree about what the port does — and the
 * disagreement surfaces as a passing test beside a failing integration, which is the
 * expensive way to find out.
 *
 * It lives in `:domain`'s test source set because that is where the callers are: recognition's
 * extractor and the answer path's stages are both domain code, so their tests compile against
 * this file directly. The real client is in `:data` and is tested against its own mapping.
 *
 * Driveable to every outcome and to both failures, which is the point — truncation, refusal and
 * a reply the adapter cannot read are exactly the paths a caller gets wrong, and none of them can
 * be exercised against a live provider on demand. Nothing here opens a socket or reads a
 * credential.
 */
class FakeModelProvider(
    /**
     * What to return, in order: the first call takes the first entry and so on.
     *
     * A queue rather than one fixed reply, so a test can drive a retry loop or a two-stage
     * pipeline. The last entry repeats once the queue runs down, which keeps a test that only
     * cares about one answer from having to count the calls that will ask for it.
     */
    replies: List<Reply> = listOf(Reply.of("")),
    /**
     * What to report as the model behind this.
     *
     * Settable because a caller putting it in a watermark has to be testable: asserting that a
     * changed model invalidates what it derived means changing the model, and nothing else about
     * the fake has to move for that.
     *
     * **Settable *after* construction for the case that needs it most: a version that moves while a
     * batch is with the provider.** A caller's version is built from this, so moving it is how a
     * test reproduces a deploy landing between a submission and its collection — the window in
     * which a stage writing under the version current then would stamp the retired wording's
     * answers as current. Done by moving the model rather than by rewording a prompt because the
     * version is opaque and compared only for equality: what a caller can see is that the string
     * differs, and which of its parts moved is a distinction nothing downstream makes.
     */
    var version: String = "fake-model",
    /**
     * Submissions that report themselves unfinished until they are removed from this.
     *
     * A submission is allowed an hour and sometimes most of a day, so a caller that treated an
     * unfinished one as a collection of nothing would settle work the provider is still doing and
     * has already been billed for. Nothing about a live provider makes that reachable on demand.
     *
     * Mutable, because a submission that is unfinished and then answered is the ordinary course of
     * one rather than a special case: a caller whose behaviour depends on what else is outstanding
     * at the moment a batch lands can only be driven to it by letting the batch land.
     */
    val waiting: MutableSet<String> = mutableSetOf(),
    /**
     * What [submit] raises instead of minting an identifier, or `null` to mint one.
     *
     * The port raises rather than returns for a call that never landed, and a caller on the batched
     * path reaches the provider only through [submit] and [collect] — so a scripted reply cannot
     * express this and a caller that swallowed the failure would look identical to one that read an
     * empty batch. Here so that the absence of a `catch` is a property a test can hold.
     */
    private val failingSubmit: Throwable? = null,

) : ModelProvider {

    /**
     * What every request of a submission comes back as, applied to whatever was submitted.
     *
     * Settable after construction because that is when a test knows it: a caller under test
     * submits keys it derived itself — a window's
     * anchor, say — so a test that had to predict them in order to script a reply would be
     * asserting the caller's key-making rather than its reading.
     *
     * **One function for every stage a caller submits, not for the first of them.** It is handed the
     * key, so a caller that asks more than one kind of question can be scripted by telling its keys
     * apart; the fake does not know what the stages are and deliberately does not try to.
     */
    var answering: ((String) -> BatchOutcome)? = null

    private val replies = replies.toList()
    private val _requests = mutableListOf<ModelRequest>()
    private val _submissions = mutableListOf<Submitted>()

    init {
        require(this.replies.isNotEmpty()) { "A fake needs at least one reply to hand back." }
    }

    /** Every request this fake was given through [respond], in order. */
    val requests: List<ModelRequest> get() = _requests.toList()

    /** How many times [respond] was called. */
    val callCount: Int get() = _requests.size

    /** Every submission this fake was handed, in order. */
    val submissions: List<Submitted> get() = _submissions.toList()

    override fun version(): String = version

    override suspend fun respond(request: ModelRequest): ModelOutcome {
        _requests += request
        // The last entry repeats rather than the queue being exhausted. Running off the end
        // of a list of replies would fail as an index error inside the fake, which reads as a
        // bug in the double rather than as the test asking for more calls than it scripted.
        return when (val reply = replies[minOf(_requests.size - 1, replies.size - 1)]) {
            is Reply.Returns -> reply.outcome
            is Reply.Fails -> throw reply.failure
        }
    }

    /**
     * Record the submission and hand back the next identifier.
     *
     * The port's guards are mostly the real provider's and are asserted against it, so they are not
     * re-checked here — a fake that enforced them too would let the two drift and be asserted twice.
     *
     * **The key length is the exception, and it is here because it is the caller's to get right.**
     * Every other guard describes a request the adapter will refuse; this one describes a key the
     * caller chose, and a caller cannot discover it from a double that accepts anything. A stage
     * that built its keys by concatenating two identifiers passed the whole of this suite and would
     * have been refused for every request it ever made. So the bound stated on the port is the one
     * thing this enforces — see [ModelProvider.MAX_KEY_BYTES].
     */
    override suspend fun submit(requests: Map<String, ModelRequest>): SubmissionId {
        // Before anything is recorded, because a call that never landed submitted nothing: a fake
        // that kept the request would let a caller be asserted against work the provider never took.
        failingSubmit?.let { throw it }
        requests.keys.forEach { key ->
            val size = key.toByteArray(Charsets.UTF_8).size
            require(size <= ModelProvider.MAX_KEY_BYTES) {
                "A key is encoded into the provider's identifier, so it may be at most " +
                    "${ModelProvider.MAX_KEY_BYTES} bytes; '$key' is $size. The real provider " +
                    "refuses this, and a fake that took it would let every request of this stage " +
                    "pass here and fail in production."
            }
        }
        // A fresh identifier each time, as a provider mints one: a caller that gave up on a
        // submission and submitted again would otherwise collide with its own abandoned one.
        val id = SubmissionId(
            if (_submissions.isEmpty()) Batched.SUBMISSION else "${Batched.SUBMISSION}-${_submissions.size + 1}",
        )
        _submissions += Submitted(id, requests.toMap())
        return id
    }

    /**
     * Whatever was scripted for [submission], or nothing at all.
     *
     * A submission with nothing scripted answers [Collected.Ready] with no replies rather than
     * failing: that is a collection whose results have gone, which a caller has to survive.
     */
    override suspend fun collect(submission: SubmissionId): Collected {
        val answer = answering
        return when {
            submission.value in waiting -> Collected.Waiting
            answer != null -> {
                val keys = _submissions.firstOrNull { it.id == submission }?.requests?.keys.orEmpty()
                Collected.Ready(keys.associateWith { key -> answer(key) })
            }
            else -> Collected.Ready(emptyMap())
        }
    }

    /** One submission this fake was handed: what it was called, and what was in it. */
    data class Submitted(val id: SubmissionId, val requests: Map<String, ModelRequest>)

    /**
     * One request's result inside a scripted collection.
     *
     * Apart from [Reply] because the two are not interchangeable: a single call either returns an
     * outcome or throws, while a result inside a submission is reported rather than thrown — one
     * reply nobody can read must not take the rest of the submission with it. A shared factory
     * would invite a test to script a throw into a collection, which the port cannot express.
     */
    object Batched {

        /**
         * The identifier this fake's first submission is made under.
         *
         * Later ones take it with an index appended, which is how a caller holding two submissions
         * at once — one per stage — can be driven to answer them differently.
         */
        const val SUBMISSION = "fake-submission"

        /** A complete answer for one request. */
        fun of(text: String, usage: TokenUsage = Reply.SAMPLE_USAGE): BatchOutcome =
            BatchOutcome.Replied(ModelOutcome.Answered(text, usage))

        /** One request that ran out of room. */
        fun truncated(partial: String, usage: TokenUsage = Reply.SAMPLE_USAGE): BatchOutcome =
            BatchOutcome.Replied(ModelOutcome.Truncated(partial, usage))

        /** One request the classifiers declined. */
        fun refused(category: String? = null, usage: TokenUsage = Reply.SAMPLE_USAGE): BatchOutcome =
            BatchOutcome.Replied(ModelOutcome.Refused(category, usage))

        /**
         * One request whose reply the adapter could not read.
         *
         * The failure a retry cannot clear and a deploy can, and the one a caller is most likely to
         * get wrong: inside a submission it is reported rather than thrown, so one unreadable reply
         * must cost its own request and nothing else in the batch.
         */
        fun unreadable(reason: String = "a stop reason this build has no name for"): BatchOutcome =
            BatchOutcome.Unreadable(reason, Reply.SAMPLE_USAGE)

        /**
         * One request the provider never finished.
         *
         * Carries no usage, which is the live provider's own shape: a result with no message on it
         * has nothing to read a bill from.
         */
        fun unfinished(reason: String = "the provider did not finish this request"): BatchOutcome =
            BatchOutcome.Unfinished(reason, TokenUsage.NONE)
    }

    /** One scripted response: an outcome to return, or a failure to raise. */
    sealed interface Reply {

        data class Returns(val outcome: ModelOutcome) : Reply

        data class Fails(val failure: Throwable) : Reply

        companion object {
            /** A complete answer. */
            fun of(text: String, usage: TokenUsage = SAMPLE_USAGE): Reply =
                Returns(ModelOutcome.Answered(text, usage))

            /** A call that ran out of room. */
            fun truncated(partial: String, usage: TokenUsage = SAMPLE_USAGE): Reply =
                Returns(ModelOutcome.Truncated(partial, usage))

            /** A call the classifiers declined. */
            fun refused(category: String? = null, usage: TokenUsage = SAMPLE_USAGE): Reply =
                Returns(ModelOutcome.Refused(category, usage))

            /** A turn that paused and did not finish within the adapter's continuations. */
            fun paused(partial: String = "", usage: TokenUsage = SAMPLE_USAGE): Reply =
                Returns(ModelOutcome.Paused(partial, usage))

            /**
             * The model could not be reached. The retryable failure, already exhausted.
             *
             * [usage] defaults to nothing because that is the ordinary shape of this one: a call
             * that never landed was not billed. A resumed turn whose later request never lands is
             * the case that carries a figure, and a caller charging for it needs this to say so.
             */
            fun unavailable(message: String = "overloaded", usage: TokenUsage = TokenUsage.NONE): Reply =
                Fails(ModelUnavailable(message, usage = usage))

            /**
             * The provider answered and the adapter could not read the answer.
             *
             * The other failure, and the one with the opposite retry semantics: re-sending gets
             * the same unreadable reply and is billed again, so a caller's degrade path must not
             * treat it as worth another attempt. A fake that could not produce it would leave
             * every caller's handling of it untested.
             *
             * **[usage] is billed by default, because this one always is.** The reply arrived and
             * only its stop reason was unreadable, so a fake handing back nothing would let a
             * caller that discards the figure pass — which is how the restatement stage came to
             * charge nothing for it.
             */
            fun unreadable(
                message: String = "a stop reason this build cannot name",
                usage: TokenUsage = SAMPLE_USAGE,
            ): Reply = Fails(ModelReplyUnreadable(message, usage = usage))

            /**
             * Usage that is non-zero and otherwise arbitrary.
             *
             * Non-zero on purpose: a caller that accumulates usage and is tested against zeroes
             * passes whether it accumulates or not.
             */
            val SAMPLE_USAGE = TokenUsage(
                inputTokens = 120,
                outputTokens = 40,
                cacheReadTokens = 80,
                cacheWriteTokens = 0,
            )
        }
    }
}
