// An architecture rule holds every reference to the provider's SDK in this one file, and the test
// asserting it fails on a second call site rather than warning. So this file cannot be split, and a
// per-file function cap is in permanent tension with that: each capability the adapter gains adds a
// mapping function here and nowhere else. One of them has already been bent into an extension
// property to stay under the cap, which costs a reader more than it buys. Suppressed here, named,
// rather than distorting another signature or weakening the cap for every file in the build.
@file:Suppress("TooManyFunctions")

package io.github.ragnorrs.mainbrain.data

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Usage
import com.anthropic.models.messages.WebSearchTool20260209
import com.anthropic.models.messages.batches.BatchCreateParams
import com.anthropic.models.messages.batches.MessageBatch
import com.anthropic.models.messages.batches.MessageBatchResult
import io.github.ragnorrs.mainbrain.domain.BatchOutcome
import io.github.ragnorrs.mainbrain.domain.Collected
import io.github.ragnorrs.mainbrain.domain.Effort
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.ModelReplyUnreadable
import io.github.ragnorrs.mainbrain.domain.ModelRequest
import io.github.ragnorrs.mainbrain.domain.ModelUnavailable
import io.github.ragnorrs.mainbrain.domain.SubmissionId
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * The one implementation of [ModelProvider], and the only file in this build that knows which
 * provider is behind it.
 *
 * Everything the provider is awkward about is handled here once, so no caller rediscovers it:
 *
 * **Which model this instance asks is a constructor property, and [ANSWERING] is the default.**
 * A process may hold more than one of these — the answer path and the bulk passes do not need the
 * same capability, and the cheaper model is most of what the bulk passes cost. Which model a given
 * provider asks is therefore a deployment decision, which is why it is a value here rather than a
 * constant everything reads. Build the second through [on], which shares this one's client: the
 * model travels on each request, so two models need two providers but only ever one connection.
 *
 * **Neither default model takes a date suffix.** The provider's other families do; these do not,
 * and a suffixed id is a 404 rather than a warning.
 *
 * **Thinking is on and has no token budget.** It is the default for both models, and it is set
 * explicitly anyway so the intent is on the page rather than inherited. The old fixed-budget
 * form is rejected outright, which is why [ModelRequest] offers no budget to set.
 *
 * **Thinking counts against `max_tokens`.** So the ceiling a caller asks for is shared between
 * reasoning and prose, and a value sized to the prose alone truncates the answer. That is
 * reported as [ModelOutcome.Truncated] rather than silently returned.
 *
 * **`temperature`, `top_p` and `top_k` are rejected.** None is sent, and the port offers no way
 * to ask for one — behaviour is steered by the prompt instead.
 *
 * **The stop reason is read before the content, and a reason this build cannot name is not
 * read at all.** [ModelOutcome] is sealed so no caller can skip the reason, and a reply whose
 * reason is absent, unrecognised or undeserialisable raises [ModelReplyUnreadable] rather than
 * surfacing a provider type. Response validation is on for the same reason: it moves a
 * malformed body from a lazy accessor — which would throw from outside the guarded call — into
 * the request itself.
 *
 * **The breakpoint goes at the end of the longest stretch two sibling calls have in common** —
 * after [ModelRequest.shared] where a request has one, and after [ModelRequest.system] otherwise.
 * That is the precondition for caching rather than a guarantee of it: below the model's minimum
 * cacheable prefix a breakpoint is silently ignored and the cache counters on [TokenUsage] stay
 * zero, with no error to notice. The minimum is the model's own and does not track price — 512
 * tokens on [ANSWERING] and twice that on [BULK], so the cheaper model is the harder one to cache
 * against, and it is the one the bulk passes use.
 *
 * **A breakpoint caches the prefix up to and including the block it sits on**, which is why a
 * short instruction can still be cached: placed after a shared region instead, the one breakpoint
 * covers the instruction and that region together and clears a minimum the instruction misses
 * alone. Of the bulk instructions only the classification clears it unaided, and by a thin enough
 * margin that shortening the prose would turn its caching off — which nothing here can detect, so
 * what reports it is the cache counters coming back at zero.
 *
 * **Entries are asked to live an hour rather than the default five minutes.** The write costs more
 * — twice the input rate instead of a quarter more — and recognition sweeps hourly, so at the
 * default every entry would have expired before the next sweep opened and every sweep would pay a
 * write that nothing ever read. A longer entry is the difference between paying for the cache and
 * using it.
 *
 * Construct through [of], which owns the credential. The SDK's own client is synchronous, so
 * every call hops to [Dispatchers.IO] — which is what lets [respond] be `suspend` and mean it.
 *
 * **The client is never closed.** The SDK deliberately does not make it [AutoCloseable],
 * because it is meant to outlive any one call; this is a process-lifetime singleton and the
 * socket pool goes when the process does. That is the one place this adapter departs from
 * `Database`'s shape, and it departs deliberately rather than by omission.
 *
 * **The decisions live in `internal` functions beside this class rather than inside it.**
 * [exchange], [outcome], [paramsFor] and their helpers are what is worth asserting — which
 * stop reason means what, which knobs go on the wire, and what a resumed turn adds up to —
 * and a private member would leave them reachable only through a live call. They are
 * `internal`, so every file in `:data` can call them and the test source set can assert them;
 * what no other file can do is *send* anything, because [AnthropicClient] enters this module
 * through [of] alone and `send` below is private to this class.
 */
class ClaudeProvider internal constructor(
    private val client: AnthropicClient,
    private val model: String = ANSWERING,
    private val continuations: Int = DEFAULT_CONTINUATIONS,
) : ModelProvider {

    init {
        require(model.isNotBlank()) { "A provider that names no model cannot ask one anything." }
    }

    /**
     * The model this instance asks, which is the whole of what a watermark needs from here.
     *
     * **So moving a derived pass to another model re-derives whatever that pass had settled**, which
     * is the watermark working rather than a cost to avoid: a different model reads a window
     * differently, and output filed under the old model's name would be stale in the one way nothing
     * downstream could detect. The bill is the horizon's worth of re-derivation, once, on the sweep
     * after the change.
     *
     * The continuation bound is not in it. It changes how much of a long answer this adapter is
     * willing to wait for, not what the model would say, and putting it here would re-derive the
     * corpus for a retry setting.
     */
    override fun version(): String = model

    /**
     * The same connection and credential, asking [model] instead.
     *
     * **This exists so that two models cost one client.** The model travels on each request, not on
     * the connection, so nothing is shared that should not be: a second [of] would mean a second
     * pool, a second copy of the credential and a second set of retry state, and a process holding
     * the key twice is one that can leak it twice. The continuation bound carries over, because it
     * is this adapter's patience rather than anything about the model.
     */
    fun on(model: String): ClaudeProvider = ClaudeProvider(client, model, continuations)

    /**
     * One exchange with the model, and a line saying what it cost.
     *
     * **Logged here because this is the only place every call passes through.** The derived
     * passes report their own spend and the answer path reported none, so the requests a person
     * waits on — against a large ceiling, doubled whenever a reply is retried, and on the open-web
     * shape at the highest effort this build asks for — were the ones invisible in operation.
     * Spend that cannot be attributed cannot be budgeted, and the first evidence of a credit
     * balance running out should not be a request failing.
     *
     * Cache tokens are named separately because they are priced separately: a prompt whose
     * prefix is read rather than written is the difference between a backfill that is affordable
     * and one that is not, and a total that folded them together would hide whether the
     * breakpoint is working at all.
     */
    override suspend fun respond(request: ModelRequest): ModelOutcome =
        try {
            withContext(Dispatchers.IO) { exchange(model, request, continuations, ::send) }
                .also { outcome -> report(request, outcome.usage, outcome::class.simpleName) }
        } catch (failure: ModelUnavailable) {
            // **A failed call is logged like any other, because it was billed like any other.**
            // Reported before the rethrow rather than left to whoever catches it: a caller
            // degrading on this knows that it failed and not what the attempt had cost, and the
            // spend that is hardest to find is the spend on the path nobody reads an answer from.
            report(request, failure.usage, failure::class.simpleName)
            throw failure
        } catch (failure: ModelReplyUnreadable) {
            report(request, failure.usage, failure::class.simpleName)
            throw failure
        }

    /**
     * What one [respond] cost, whether or not it produced anything.
     *
     * Not every call the adapter makes: a submission and its collection report their own lines, and
     * a batch-endpoint request that never lands reports none at all, because [unreachable] builds
     * those failures where the figure is not in scope.
     *
     * [ending] is which [ModelOutcome] case arrived, or the failure's own name where none did —
     * three of the four cases settle as insufficient evidence and are therefore spend that bought
     * nothing readable, and so is every failure. A run of either is the signature of a ceiling set
     * too low or a provider in trouble, which is otherwise indistinguishable from a corpus with no
     * answer in it.
     */
    private fun report(request: ModelRequest, usage: TokenUsage, ending: String?) {
        log.info(
            "model {} at {} effort: {} in / {} out, {} cache read / {} cache written, {}.",
            model,
            request.effort.name.lowercase(),
            usage.inputTokens,
            usage.outputTokens,
            usage.cacheReadTokens,
            usage.cacheWriteTokens,
            ending,
        )
    }

    /**
     * Hand the whole of [requests] over at once, and keep the identifier it comes back under.
     *
     * **Half the price of asking one at a time, which is the whole reason this exists.** The
     * recency horizon is set by the credit provisioned rather than by what would answer best, so
     * halving the unit price buys about twice the corpus for the same money.
     *
     * The guards are here rather than left to the caller because each one is a silent charge if it
     * is wrong. Two system prompts in one submission means neither is read from cache and the
     * submission pays twice over; a blank key means a reply that cannot be matched back to the work
     * that asked for it, which is a window paid for and discarded.
     *
     * The open-web shape is refused outright. A turn that pauses for a server-side tool is resumed
     * by a fresh request, and there is no way to resume one inside a submission — so a batched
     * search would stop half-done and be billed for it, every time.
     */
    override suspend fun submit(requests: Map<String, ModelRequest>): SubmissionId {
        require(requests.isNotEmpty()) { "A submission with no requests in it asks the model nothing." }
        require(requests.keys.none(String::isBlank)) {
            "A blank key cannot be matched back to the work that asked for it."
        }
        // Checked here rather than left to the 400, because the provider's complaint names a
        // position in the batch and not the key, and because a rejected submission is the whole
        // sweep's work lost rather than one window's.
        require(requests.keys.all { it.toByteArray(Charsets.UTF_8).size <= MAX_KEY_BYTES }) {
            val longest = requests.keys.maxBy { it.toByteArray(Charsets.UTF_8).size }
            "A key is encoded into the provider's 64-character identifier, so it may be at most " +
                "$MAX_KEY_BYTES bytes; '$longest' is " +
                "${longest.toByteArray(Charsets.UTF_8).size}."
        }
        require(requests.values.distinctBy { it.system }.size == 1) {
            // One submission is one request shape. Not a caching argument — the requests of one
            // submission are answered concurrently, so a prefix they share is not one any of them
            // can be relied on to read. What it buys is that a collection's results all mean the
            // same thing, so one caller reads them with one parser.
            "Every request in a submission shares a system prompt, so one collection is one shape. " +
                "Two shapes are two submissions."
        }
        require(requests.values.none { it.openWeb }) {
            "A turn that pauses for a server-side tool cannot be resumed inside a submission, so " +
                "the open-web shape is asked one call at a time."
        }
        val batch = withContext(Dispatchers.IO) { sendBatch(batchParams(model, requests)) }
        log.info(
            "model {}: submitted {} request(s) as batch {}.",
            model,
            requests.size,
            batch.id(),
        )
        return SubmissionId(batch.id())
    }

    /**
     * Ask whether [submission] has finished, and read it back when it has.
     *
     * Two calls to the provider and not one: the status first, then the results. Asking for results
     * before the batch has ended is the error case rather than an empty answer, so the status is
     * what [Collected.Waiting] is read from.
     *
     * **Results are keyed by the identifier the caller chose, never by position.** The provider
     * answers in whatever order the requests finished, so a list read positionally would attribute
     * one window's findings to another — the failure that looks like a working pass producing
     * nonsense.
     */
    override suspend fun collect(submission: SubmissionId): Collected =
        withContext(Dispatchers.IO) {
            val batch = retrieveBatch(submission)
            if (batch.processingStatus() != MessageBatch.ProcessingStatus.ENDED) {
                Collected.Waiting
            } else {
                val replies = readBatch(submission)
                log.info(
                    "model {}: batch {} ended with {} result(s), {} out.",
                    model,
                    submission.value,
                    replies.size,
                    replies.values.sumOf { it.usage.outputTokens },
                )
                Collected.Ready(replies)
            }
        }

    /** Submit one batch. Blocking, so only ever called from inside the dispatcher hop above. */
    private fun sendBatch(params: BatchCreateParams): MessageBatch =
        try {
            client.messages().batches().create(params)
        } catch (failure: AnthropicException) {
            throw unreachable(failure) ?: failure
        }

    /** Ask after one batch. Blocking, as above. */
    private fun retrieveBatch(submission: SubmissionId): MessageBatch =
        try {
            client.messages().batches().retrieve(submission.value)
        } catch (failure: AnthropicException) {
            throw unreachable(failure) ?: failure
        }

    /**
     * Every result of one ended batch, read back by the key it was submitted under.
     *
     * Streamed rather than listed, because a batch holds as many results as it held requests and
     * the provider offers them as a stream for that reason. The stream is closed whatever happens
     * to the reading, which is what `use` is for here.
     */
    private fun readBatch(submission: SubmissionId): Map<String, BatchOutcome> =
        try {
            client.messages().batches().resultsStreaming(submission.value).use { results ->
                results.stream().toList().associate { it.customId().fromCustomId() to it.result().batched }
            }
        } catch (failure: AnthropicException) {
            throw unreachable(failure) ?: failure
        }

    /** One request. Blocking, so only ever called from inside the dispatcher hop above. */
    private fun send(params: MessageCreateParams): Message =
        try {
            client.messages().create(params)
        } catch (failure: AnthropicInvalidDataException) {
            // Rethrown rather than classified here. A body that does not deserialise is noticed
            // either in `create` or in the accessors read after it, and one classifier for both
            // is what keeps the two from reporting the same failure differently.
            throw failure
        } catch (failure: AnthropicException) {
            throw unreachable(failure) ?: failure
        }

    companion object {

        /** This adapter's own log, for the one line per call that says what it cost. */
        private val log = LoggerFactory.getLogger(ClaudeProvider::class.java)

        /**
         * The model for the request a person is waiting on.
         *
         * No date suffix. This family's ids carry none, and a suffixed one is not an older
         * version of this model — it does not exist.
         */
        const val ANSWERING = "claude-opus-5"

        /**
         * And the model for the work nobody is waiting on: recognition's inference, and the
         * classification that routes free text.
         *
         * **The two jobs are opposites, which is the whole argument for two models.** Answering is
         * a dozen calls a day whose quality a person reads; the bulk passes are thousands of calls
         * producing mechanical output, and they are most of what this build costs. This one is
         * priced at three fifths of [ANSWERING] per input token and three fifths per output token.
         *
         * **It is this model rather than the cheapest one for a mechanical reason.** Every request
         * this adapter builds carries adaptive thinking and an `output_config` effort, and the
         * small-model family accepts neither: effort is rejected outright there, and thinking wants
         * the fixed-budget form that [ModelRequest] deliberately does not offer. Reaching further
         * down the price list therefore means this adapter translating its two knobs per model
         * family, which is a different change from naming another model — and extraction quality
         * below this tier is unmeasured, with hedged language and relative dates the two things
         * most likely to go first.
         */
        const val BULK = "claude-sonnet-5"

        /**
         * How many times a failed call is retried before the caller hears about it.
         *
         * The SDK owns the retrying and already distinguishes what is worth retrying from
         * what is not. The number is set here rather than left at the default so that it is
         * a decision on the page.
         */
        const val DEFAULT_RETRIES = 3

        /**
         * How many times a paused turn is resumed before the caller hears about it.
         *
         * Separate from [DEFAULT_RETRIES], and not the same kind of thing: a retry re-sends a
         * call that failed, a continuation carries on one that is working but has paused. Both
         * are bounded, because an unbounded one bills until someone notices.
         */
        const val DEFAULT_CONTINUATIONS = 3

        /**
         * The provider, holding [apiKey].
         *
         * The credential is a parameter rather than read from the environment here, so that
         * which process holds it stays a deployment decision and this class has no opinion.
         * Only the backend's container is given one.
         */
        fun of(
            apiKey: String,
            model: String = ANSWERING,
            retries: Int = DEFAULT_RETRIES,
            continuations: Int = DEFAULT_CONTINUATIONS,
        ): ClaudeProvider {
            require(apiKey.isNotBlank()) { "The model credential is missing." }
            val client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .maxRetries(retries)
                // Response validation is left off, which is the SDK's default.
                //
                // **On, it checks every field of every content block rather than the ones this
                // adapter reads.** A server-tool block carries a `caller` the SDK marks required
                // and the provider does not always send, so every answer from the open-web shape
                // failed on a field no code path here touches — measured against the live
                // provider, where the shape could not answer at all. Validating what nobody reads
                // turns the provider's additions into this build's outages.
                //
                // What it bought was the failure happening inside `create`, where one guarded
                // call could classify it. That is kept by putting the guard around the whole
                // exchange instead: with validation off a genuinely malformed body surfaces from
                // whichever accessor first touches it, and those are reached after `send` returns.
                .build()
            return ClaudeProvider(client, model, continuations)
        }
    }
}

/**
 * The whole exchange: one call, and another for each time the turn pauses.
 *
 * **Both the prose and the usage of every response are accumulated, not just the last one's.**
 * A resumed turn spans several responses, and each carries only the content it produced — the
 * provider does not repeat what it already sent. Reading the final response alone would hand
 * back a fragment of the answer labelled complete, which is the one failure
 * [ModelOutcome]'s sealing exists to prevent, arriving through a different door.
 *
 * [send] is a parameter rather than a method call so that the loop can be asserted without a
 * credential: the resumption path, the continuation bound and the accumulation are all only
 * reachable across more than one response.
 *
 * **Both failures this classifies carry what the exchange had cost.** The accumulated figure is
 * this function's alone — a resumption that never lands is raised by [send], which can see one
 * request and not the turn — so a failure leaving without it would be the one place the whole
 * exchange's spend is knowable and discarded.
 *
 * One way out still carries nothing: the failure [unreachable] declines to classify — a malformed
 * request or a rejected credential — propagates as the provider's own error, untouched, because
 * that is a defect in what was sent rather than something the model did. A resumed turn whose
 * later request is rejected therefore loses the earlier responses' figure. Wrapping it to carry one
 * would put this build's type on a failure that is deliberately the provider's.
 */
// Three, one per way an exchange can fail: a reply that cannot be read, a body that does not
// deserialise, and a provider that stopped answering part-way. They differ only in what they carry,
// and the loop reads better with each raised where its condition is noticed than with a helper per
// case between this and the accumulator all three report.
@Suppress("ThrowsCount")
internal fun exchange(
    model: String,
    request: ModelRequest,
    continuations: Int,
    send: (MessageCreateParams) -> Message,
): ModelOutcome {
    var params = paramsFor(model, request)
    var spent = TokenUsage.NONE
    val prose = StringBuilder()
    var resumed = 0

    // **The whole exchange, not the call alone.** A reply that arrived and does not deserialise
    // is noticed by whichever accessor first touches the missing field, and every one of them is
    // read after `send` has returned — so a guard around the call would let the SDK's exception
    // cross the port. Not the request being wrong, and not worth retrying, since the same body
    // comes back: it joins the unreadable cases rather than the unreachable ones.
    try {
        while (true) {
            val message = send(params)
            spent = spent.plus(message.usage())
            prose.append(message.prose())

            // Read the reason first, every time. A turn that paused is the one case worth
            // another request: a server-side tool ran long and the provider expects to be
            // asked to carry on, with no further input from us.
            //
            // Raised here rather than inside `outcome`, so the raw value is still in scope to
            // name. An operator who cannot see which value arrived cannot tell an empty reply
            // from a provider that has shipped something new, and only the second is fixed by
            // a build that knows it.
            val reason = message.stopReason().orElse(null)
            val stop = reason?.knownOrNull() ?: throw ModelReplyUnreadable(reason.unreadable, usage = spent)
            if (stop != StopReason.Known.PAUSE_TURN) {
                return outcome(stop, prose.toString(), message.refusalCategory(), spent)
            }

            resumed++
            if (resumed > continuations) {
                // Bounded, because a turn that keeps pausing would otherwise bill forever. The
                // caller is told the turn stopped short rather than handed the partial as whole.
                return ModelOutcome.Paused(prose.toString(), spent)
            }
            // Resuming is the prior turn re-sent with the paused reply appended, and nothing
            // else. An added "continue" instruction would be read as a user turn and change
            // what the model was doing.
            params = params.toBuilder().addMessage(message).build()
        }
    } catch (failure: AnthropicInvalidDataException) {
        throw ModelReplyUnreadable("The provider's reply could not be read.", failure, spent)
    } catch (failure: ModelUnavailable) {
        // Raised by `send`, which sees one request and not the turn, so it arrives carrying
        // nothing while the responses before it have been billed. Re-raised with the turn's own
        // figure; the failure's own is added rather than replaced, so a provider that starts
        // reporting usage on an exhausted call is not discarded here.
        throw if (spent == TokenUsage.NONE) {
            failure
        } else {
            // The original stands as the cause where it has none of its own, so the frames of the
            // request that actually failed are not lost to the re-raise.
            ModelUnavailable(failure.message.orEmpty(), failure.cause ?: failure, spent + failure.usage)
        }
    }
}

/**
 * [failure] as a [ModelUnavailable] when it is the model being out of reach, or `null` when it
 * is the request being wrong.
 *
 * The two are not the same failure and must not be handled alike. By the time anything reaches
 * here the SDK has already retried whatever was worth retrying — that is what
 * [ClaudeProvider.of] configures — so this only decides what an exhausted failure *means*. A
 * status the provider retries is the provider being unavailable, and saying so lets a caller
 * degrade. A malformed request or a rejected credential is a defect in what was sent: it is
 * handed back untouched, because wrapping it would invite a caller to retry a request that
 * fails identically every time and bills for each attempt.
 *
 * **The status is matched rather than the exception type.** The SDK gives only 429 and 5xx
 * their own classes; 408 and 409 it retries just as readily but surfaces through the same
 * catch-all type as a 400. Sorting by type would therefore hand a timeout back as though it
 * were a malformed request — the exact confusion this function exists to prevent.
 */
internal fun unreachable(failure: AnthropicException): ModelUnavailable? = when {
    failure is AnthropicIoException ->
        ModelUnavailable("The model could not be reached.", failure)

    failure is AnthropicRetryableException ->
        ModelUnavailable("The model reported a transient failure.", failure)

    failure is AnthropicServiceException && isRetryableStatus(failure.statusCode()) ->
        ModelUnavailable("The model provider returned ${failure.statusCode()}.", failure)

    else -> null
}

/**
 * The statuses the provider asks to be retried.
 *
 * Follows the SDK's own retry predicate without claiming to reproduce it. Two differences, both
 * harmless: the SDK treats every status at or above 500 as retryable where this stops at 599,
 * which no real status exceeds; and a response carrying `X-Should-Retry` is invisible here,
 * because the header is gone by the time an exception arrives — the SDK has already honoured it
 * while retrying, so the cost is a generic message rather than a misread failure.
 */
internal fun isRetryableStatus(status: Int): Boolean =
    status == REQUEST_TIMEOUT ||
        status == CONFLICT ||
        status == TOO_MANY_REQUESTS ||
        status in FIRST_SERVER_ERROR..LAST_SERVER_ERROR

private const val REQUEST_TIMEOUT = 408
private const val CONFLICT = 409
private const val TOO_MANY_REQUESTS = 429

/** Everything the server blames on itself, the overload status among them. */
private const val FIRST_SERVER_ERROR = 500
private const val LAST_SERVER_ERROR = 599

/**
 * What goes on the wire for [request].
 *
 * What is absent matters as much as what is present: no `temperature`, no `top_p`, no `top_k`,
 * and no thinking budget. All four are rejected by both models, and none is reachable from
 * [ModelRequest] — so adding one would take a deliberate edit here rather than a caller
 * passing something through.
 *
 * The system prompt is sent as a block carrying the cache breakpoint, and the question as the
 * user turn after it, so the per-call question sits after the breakpoint where it invalidates
 * nothing.
 *
 * **Three things bound what that buys.** Tools are rendered ahead of the system prompt, and the
 * web-search tool is conditional — so an open-web call and a corpus call have different
 * prefixes and can never read each other's entry; caching pays off within one value of
 * [ModelRequest.openWeb], not across both. A prefix shorter than the model's minimum is
 * silently not cached at all, whatever the breakpoint says. And an entry belongs to the model
 * that wrote it, so the two models in this build share nothing here either.
 *
 * [model] is a parameter rather than read from a constant, because which model a provider asks is
 * a deployment decision — see [ClaudeProvider.on].
 */
internal fun paramsFor(model: String, request: ModelRequest): MessageCreateParams {
    val builder = MessageCreateParams.builder()
        .model(model)
        .maxTokens(request.maxTokens.toLong())
        .systemOfTextBlockParams(systemBlocksFor(request))
        .thinking(ThinkingConfigAdaptive.builder().build())
        .outputConfig(OutputConfig.builder().effort(request.effort.wire()).build())

    val shared = sharedThenPrompt(request)
    if (shared == null) builder.addUserMessage(request.prompt) else builder.addUserMessageOfBlockParams(shared)

    if (request.openWeb) {
        // Declared as a tool on the call, which is how one request shape reads the open web
        // without any general outbound access being opened for the rest of the stack. The
        // search runs on the provider's side; nothing here fetches a URL.
        builder.addTool(WebSearchTool20260209.builder().build())
    }
    return builder.build()
}

/**
 * The caller's key, as a `custom_id` the provider accepts.
 *
 * **The provider constrains this and the corpus does not fit the constraint.** A `custom_id` must
 * match `^[a-zA-Z0-9_-]{1,64}$`, and the keys this build asks under are record identifiers from the
 * source: `-1001:2001:11` from a Telegram chat, `-1001234567890/12345678` from the archive. Both
 * carry a separator the pattern forbids, so submitting a key as it stands is a 400 for every record
 * this build holds — not an edge case, the ordinary one.
 *
 * Encoded rather than sanitised: replacing the offending characters would map two distinct records
 * onto one key, and a collision here attributes one window's findings to another — which reads as a
 * working pass producing nonsense. Base64url is reversible, and its alphabet is exactly the
 * pattern's.
 *
 * **Reversible without state, which is the requirement that rules out a hash.** A submission
 * outlives the sweep that made it and may be collected by a different process, so the decode cannot
 * depend on anything the encoder kept — the mapping has to travel in the identifier itself.
 *
 * Padding is stripped because `=` is not in the pattern; the decoder does not need it.
 */
internal fun String.asCustomId(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

/** The key behind a `custom_id` this build submitted. The inverse of [asCustomId]. */
internal fun String.fromCustomId(): String =
    String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)

/**
 * The longest key that still encodes inside the provider's 64 characters.
 *
 * Base64 turns three bytes into four characters, so 48 bytes is the most that fits. **The number
 * lives on the port and is read from there**, because the party that has to obey it is the caller:
 * held privately here it was a rule a caller could only discover by being refused, and a stage that
 * built its keys by concatenating two identifiers passed every test written against a double.
 * This file is what enforces it; [ModelProvider.MAX_KEY_BYTES] is what states it.
 */
private const val MAX_KEY_BYTES = ModelProvider.MAX_KEY_BYTES

/**
 * One submission's worth of requests, as the provider's batch endpoint takes them.
 *
 * Built from the same decisions as [paramsFor] and deliberately so: the model, the ceiling, the
 * adaptive thinking, the effort and the cache breakpoint at the end of the system prompt are what a
 * request *is* in this build, and a batched one that differed in any of them would read the corpus
 * differently from a synchronous one. What is absent is the web-search tool, because [submit]
 * refuses the shape that asks for it.
 *
 * The key each request is submitted under is the caller's, **encoded** — see [asCustomId]. What the
 * result comes back keyed by is decoded back to the caller's key before it leaves this file, so
 * nothing above has to know the provider constrains it.
 */
internal fun batchParams(model: String, requests: Map<String, ModelRequest>): BatchCreateParams {
    val builder = BatchCreateParams.builder()
    requests.forEach { (key, request) ->
        val params = BatchCreateParams.Request.Params.builder()
            .model(model)
            .maxTokens(request.maxTokens.toLong())
            .system(BatchCreateParams.Request.Params.System.ofTextBlockParams(systemBlocksFor(request)))
            .thinking(ThinkingConfigAdaptive.builder().build())
            .outputConfig(OutputConfig.builder().effort(request.effort.wire()).build())

        val shared = sharedThenPrompt(request)
        if (shared == null) params.addUserMessage(request.prompt) else params.addUserMessageOfBlockParams(shared)

        builder.addRequest(
            BatchCreateParams.Request.builder()
                .customId(key.asCustomId())
                .params(params.build())
                .build(),
        )
    }
    return builder.build()
}

/**
 * The system prompt, carrying the breakpoint only where nothing after it will.
 *
 * **One breakpoint and not two, placed at the end of the longest shared stretch.** A breakpoint
 * caches everything before it, so a second one earlier in the prefix buys nothing a later one does
 * not already cover — and where the earlier prefix is below the model's minimum it caches nothing
 * at all, which is the shape every instruction in this build but one has on its own. Put at the end
 * of the shared region instead, the same breakpoint covers the instruction *and* the shared body,
 * and clears a minimum neither reaches alone.
 */
internal fun systemBlocksFor(request: ModelRequest): List<TextBlockParam> = listOf(
    TextBlockParam.builder()
        .text(request.system)
        .apply { if (request.shared == null) cacheControl(anHour()) }
        .build(),
)

/**
 * The user turn as two blocks — what siblings share, then what distinguishes this call — or `null`
 * where there is nothing shared and the prompt can go as a plain string.
 *
 * The breakpoint sits on the first of the two, so the shared body is inside the cached prefix and
 * the discriminator is outside it. Reversed, the two blocks would still both be sent and the call
 * would still be correct, and nothing would ever be read from cache: a prefix is only shared up to
 * the first byte that differs.
 */
internal fun sharedThenPrompt(request: ModelRequest): List<ContentBlockParam>? =
    request.shared?.let { shared ->
        listOf(
            ContentBlockParam.ofText(
                TextBlockParam.builder().text(shared).cacheControl(anHour()).build(),
            ),
            ContentBlockParam.ofText(TextBlockParam.builder().text(request.prompt).build()),
        )
    }

/**
 * A cache entry asked to live an hour rather than the default five minutes.
 *
 * **The cadence is what decides this, not the size of the saving.** Recognition sweeps hourly, so
 * at the default lifetime every entry a sweep wrote would have expired before the next sweep could
 * read it — a write charged on every sweep, at a quarter above the input rate, that nothing ever
 * reads. The longer entry costs twice the input rate to write instead, which is the worse price on
 * a write and the only one that comes with a read.
 */
private fun anHour(): CacheControlEphemeral =
    CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build()

/**
 * One batch result as a caller reads it.
 *
 * **The succeeded case reuses [outcome] unchanged**, which is the point: a result carries its own
 * stop reason and its own usage, so what a reply means is the same question whether it arrived on
 * its own or among a thousand others. A stop reason this build cannot name is reported for that
 * request rather than thrown, because thrown it would take every other result with it.
 *
 * The other three are one case to a caller — no reply, ask again — and they are kept apart from the
 * unreadable one because asking again is exactly what clears them and exactly what does not clear
 * that. A turn that paused inside a submission lands here through [outcome] rather than in one of
 * those: there is nothing to resume it with, so it is reported as the stop short that it is.
 *
 * A property rather than a function, for the reason [StopReason.unreadable] is one: an architecture
 * rule holds every reference to the SDK in this one file, so it cannot be split, and detekt caps
 * how many functions one file may declare. The cap is the reason for the shape.
 */
internal val MessageBatchResult.batched: BatchOutcome get() = when {
    isSucceeded() -> asSucceeded().message().let { message ->
        val spent = TokenUsage.NONE.plus(message.usage())
        message.stopReason().orElse(null)?.knownOrNull()
            ?.let { BatchOutcome.Replied(outcome(it, message.prose(), message.refusalCategory(), spent)) }
            ?: BatchOutcome.Unreadable(message.stopReason().orElse(null).unreadable, spent)
    }

    isErrored() -> BatchOutcome.Unfinished(
        "The provider rejected or failed this request: ${asErrored().error().error()}.",
        TokenUsage.NONE,
    )

    isExpired() -> BatchOutcome.Unfinished(
        "The submission expired before it was collected, so this request was never answered.",
        TokenUsage.NONE,
    )

    isCanceled() -> BatchOutcome.Unfinished(
        "The submission was cancelled before this request was answered.",
        TokenUsage.NONE,
    )

    // A fifth result kind the provider has added and this build cannot name. Not retryable for the
    // same reason an unnameable stop reason is not: what clears it is a build that knows the value.
    else -> BatchOutcome.Unreadable(
        "The provider returned a batch result this build cannot name, so it cannot be read.",
        TokenUsage.NONE,
    )
}

/**
 * What the stop reason means to a caller.
 *
 * Takes the accumulated [prose] rather than reading one response, because a resumed turn spans
 * several and only the caller of [exchange] knows what the whole of it was.
 *
 * Every branch is named rather than collapsed into an else, because the whole point of the
 * sealed outcome is that a reason nobody thought about cannot be read as an answer.
 */
internal fun outcome(
    stop: StopReason.Known,
    prose: String,
    refusalCategory: String?,
    spent: TokenUsage,
): ModelOutcome = when (stop) {
    StopReason.Known.END_TURN -> ModelOutcome.Answered(prose, spent)

    // Both mean the same thing to a caller: there was no room left to finish in. They differ
    // in which ceiling was hit — the one this request asked for, or the model's own window.
    StopReason.Known.MAX_TOKENS,
    StopReason.Known.MODEL_CONTEXT_WINDOW_EXCEEDED,
    -> ModelOutcome.Truncated(prose, spent)

    StopReason.Known.REFUSAL -> ModelOutcome.Refused(refusalCategory, spent)

    // Unreachable as this adapter builds requests: no stop sequence is ever set, so none can
    // fire. If one does, the text is whole — nothing of ours cut it short.
    StopReason.Known.STOP_SEQUENCE -> ModelOutcome.Answered(prose, spent)

    // Also unreachable: the only tool declared here runs on the provider's side, so there is
    // no client-side call for the model to be waiting on. Were it to wait, it is waiting on
    // something this adapter will never supply — stopped short, not finished.
    StopReason.Known.TOOL_USE -> ModelOutcome.Paused(prose, spent)

    // Reached only when the continuation bound is spent; the exchange loop returns first
    // otherwise.
    StopReason.Known.PAUSE_TURN -> ModelOutcome.Paused(prose, spent)
}

/**
 * Why a reply cannot be read, naming the value when there was one.
 *
 * An extension rather than a function of its own: this file holds every reference to the SDK
 * by an architecture rule, so it cannot be split, and detekt caps how many functions one file
 * may declare. The cap is the reason for the shape.
 */
internal val StopReason?.unreadable: String
    get() = if (this == null) {
        "The provider returned no stop reason, so the response cannot be read as finished."
    } else {
        "The provider returned the stop reason `${asString()}`, which this build cannot name, " +
            "so the response cannot be read as finished."
    }

/**
 * The stop reason as an enum member, or `null` when this build has no name for it.
 *
 * The SDK's own `known()` throws on a value it does not recognise, and its documentation says
 * to read `value()` instead when the set may have grown. A provider that ships a new stop
 * reason before this build upgrades is the ordinary case, not an exceptional one, and letting
 * it throw would send a provider type through `respond` — where the caller has no reason to
 * expect one and no way to tell it from a transport fault. [ModelReplyUnreadable] says what
 * happened and says it is not worth retrying.
 *
 * This does not make the port provider-free in every path: `unreachable` deliberately lets a
 * malformed *request* propagate as the provider's own error, because that is a defect in the
 * caller and its own exception is the most informative thing to hand back.
 */
internal fun StopReason.knownOrNull(): StopReason.Known? =
    if (value() == StopReason.Value._UNKNOWN) null else known()

/**
 * The text blocks of one response, joined.
 *
 * Thinking blocks are left out. This model never returns the raw reasoning, and the summary it
 * can return is for a human watching rather than for a caller parsing — a stage that spliced
 * it into the answer would be reading commentary as content.
 */
internal fun Message.prose(): String =
    content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")

/** Whatever the provider named, which is sometimes nothing. */
internal fun Message.refusalCategory(): String? =
    stopDetails().orElse(null)?.category()?.orElse(null)?.asString()

internal fun Effort.wire(): OutputConfig.Effort = when (this) {
    Effort.LOW -> OutputConfig.Effort.LOW
    Effort.MEDIUM -> OutputConfig.Effort.MEDIUM
    Effort.HIGH -> OutputConfig.Effort.HIGH
    Effort.XHIGH -> OutputConfig.Effort.XHIGH
    Effort.MAX -> OutputConfig.Effort.MAX
}

/**
 * Two calls' usage, added.
 *
 * A resumed turn is billed per request, so a turn that paused once costs two responses' worth.
 * Reporting only the last would under-report exactly the calls that cost the most.
 */
internal fun TokenUsage.plus(other: Usage): TokenUsage = TokenUsage(
    inputTokens = inputTokens + other.inputTokens(),
    outputTokens = outputTokens + other.outputTokens(),
    cacheReadTokens = cacheReadTokens + other.cacheReadInputTokens().orElse(0L),
    cacheWriteTokens = cacheWriteTokens + other.cacheCreationInputTokens().orElse(0L),
)
