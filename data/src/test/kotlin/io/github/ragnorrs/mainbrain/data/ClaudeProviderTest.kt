package io.github.ragnorrs.mainbrain.data

import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.models.ErrorObject
import com.anthropic.models.ErrorResponse
import com.anthropic.models.InvalidRequestError
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RefusalStopDetails
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlock
import com.anthropic.models.messages.ThinkingBlock
import com.anthropic.models.messages.Usage
import com.anthropic.models.messages.batches.MessageBatchCanceledResult
import com.anthropic.models.messages.batches.MessageBatchErroredResult
import com.anthropic.models.messages.batches.MessageBatchExpiredResult
import com.anthropic.models.messages.batches.MessageBatchResult
import com.anthropic.models.messages.batches.MessageBatchSucceededResult
import io.github.ragnorrs.mainbrain.domain.BatchOutcome
import io.github.ragnorrs.mainbrain.domain.Effort
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.ModelReplyUnreadable
import io.github.ragnorrs.mainbrain.domain.ModelRequest
import io.github.ragnorrs.mainbrain.domain.ModelUnavailable
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows

/**
 * What a stop reason means, what a resumed turn adds up to, and what goes on the wire.
 *
 * None of it needs a credential: responses are built here, and the exchange loop takes its
 * sender as a parameter. No test opens a socket. The acceptance that matters is negative — a
 * truncation, a refusal and a pause must never arrive as a finished answer — and that is
 * exactly what cannot be provoked on demand against a live provider.
 */
class ClaudeProviderTest {

    private val spent = TokenUsage(inputTokens = 10, outputTokens = 5)

    /** A response carrying [text], stopped for [reason] — or for nothing, when it is null. */
    private fun reply(
        reason: StopReason?,
        text: String = "an answer",
        details: RefusalStopDetails? = null,
        usage: Usage = usage(),
    ): Message =
        Message.builder()
            .id("msg_test")
            .model(ClaudeProvider.ANSWERING)
            .addContent(TextBlock.builder().text(text).citations(listOf()).build())
            .stopReason(reason)
            .stopSequence(null)
            .stopDetails(details)
            .container(null)
            .usage(usage)
            .build()

    /**
     * The SDK's builders demand every field the wire format declares, nullable ones included —
     * so the ones this build does not read are set to null explicitly rather than omitted.
     */
    private fun usage(input: Long = 100, output: Long = 20, cacheRead: Long = 0, cacheWrite: Long = 0): Usage =
        Usage.builder()
            .inputTokens(input)
            .outputTokens(output)
            .cacheReadInputTokens(cacheRead)
            .cacheCreationInputTokens(cacheWrite)
            .cacheCreation(null)
            .inferenceGeo(null)
            .outputTokensDetails(null)
            .serverToolUse(null)
            .serviceTier(null)
            .build()

    private fun Message.mapped() =
        outcome(stopReason().get().knownOrNull()!!, prose(), refusalCategory(), spent)

    private fun request(effort: Effort = Effort.HIGH, openWeb: Boolean = false) =
        ModelRequest(
            system = "standing instructions",
            prompt = "a question",
            maxTokens = 4096,
            effort = effort,
            openWeb = openWeb,
        )

    // ---- the stop reason, and what it means ------------------------------------------------

    @Test
    fun `a finished turn is an answer`() {
        val outcome = reply(StopReason.END_TURN, "the whole thing").mapped()
        assertEquals(ModelOutcome.Answered("the whole thing", spent), outcome)
    }

    @Test
    fun `running out of room is a truncation, not an answer`() {
        // The case this whole sealed hierarchy exists for. The provider returns HTTP 200 with
        // prose that reads as complete; only the stop reason says it was cut off.
        val outcome = reply(StopReason.MAX_TOKENS, "half of an ans").mapped()
        assertEquals(ModelOutcome.Truncated("half of an ans", spent), outcome)
    }

    @Test
    fun `exhausting the context window is also a truncation`() {
        val outcome = reply(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED, "as far as it got").mapped()
        assertEquals(ModelOutcome.Truncated("as far as it got", spent), outcome)
    }

    @Test
    fun `a refusal carries the category the provider named`() {
        val details = RefusalStopDetails.builder()
            .category(RefusalStopDetails.Category.CYBER)
            .explanation(null)
            .build()
        assertEquals(ModelOutcome.Refused("cyber", spent), reply(StopReason.REFUSAL, "", details).mapped())
    }

    @Test
    fun `a refusal without a category is still a refusal`() {
        // The provider sometimes declines without naming one, so the category is nullable and
        // its absence must not be mistaken for "not a refusal".
        val outcome = reply(StopReason.REFUSAL, "").mapped()
        assertTrue(outcome is ModelOutcome.Refused)
        assertNull((outcome as ModelOutcome.Refused).category)
    }

    @Test
    fun `a turn still waiting on a tool is not an answer`() {
        assertTrue(reply(StopReason.TOOL_USE).mapped() is ModelOutcome.Paused)
        assertTrue(reply(StopReason.PAUSE_TURN).mapped() is ModelOutcome.Paused)
    }

    @Test
    fun `an absent stop reason is unreadable rather than guessed at`() {
        // No stop reason means the response cannot be certified as finished. Guessing that it
        // was is the one failure mode this adapter exists to prevent.
        val failure = assertThrows<ModelReplyUnreadable> {
            exchange(ClaudeProvider.ANSWERING, request(), continuations = 0) {
                reply(reason = null, text = "something")
            }
        }
        assertTrue(failure.message!!.contains("no stop reason"))
    }

    @Test
    fun `an undeserialisable reply is unreadable wherever it is noticed`() {
        // **Measured against the live provider, which is the only place it shows.** With the
        // SDK's response validation on, every field of every content block is checked rather
        // than the ones this adapter touches — and a server-tool block carries a `caller` the
        // provider does not always send, so every open-web answer failed on a field nothing
        // here reads. Validation is off now, which is the SDK's default, and the guard sits
        // around the whole exchange instead of around the call alone: with validation off a
        // genuinely malformed body surfaces from whichever accessor first touches it, and
        // those are reached after `send` has returned.
        val failure = assertThrows<ModelReplyUnreadable> {
            exchange(ClaudeProvider.ANSWERING, request(), continuations = 0) {
                throw AnthropicInvalidDataException("`caller` is not set")
            }
        }
        assertTrue(failure.cause is AnthropicInvalidDataException)
    }

    @Test
    fun `a stop reason newer than this build names the value it could not read`() {
        // The SDK's own `known()` throws on a value it does not recognise, and a provider that
        // ships a new stop reason before this build upgrades is ordinary rather than
        // exceptional. Letting it throw would put a provider type through `respond`.
        val invented = StopReason.of("some_reason_from_the_future")
        assertNull(invented.knownOrNull())

        val failure = assertThrows<ModelReplyUnreadable> {
            exchange(ClaudeProvider.ANSWERING, request(), continuations = 0) { reply(invented, "partial") }
        }
        // Naming the value is the difference between an operator knowing what to add and
        // knowing only that something went wrong. The absent case must not say the same thing.
        assertTrue(failure.message!!.contains("some_reason_from_the_future"))
    }

    @Test
    fun `an unreadable reply carries what the turn had already cost`() {
        // **The reply arrived, so it was billed.** A failure handed back with no figure on it is a
        // charge nothing downstream can count: the sweep that drove the batch reads the cost off
        // the failure, and reading zero there is how a pass that fails late every time spends a
        // batch's worth per cadence unseen.
        val failure = assertThrows<ModelReplyUnreadable> {
            exchange(ClaudeProvider.ANSWERING, request(openWeb = true), continuations = 3) { params ->
                if (params.messages().size == 1) {
                    reply(StopReason.PAUSE_TURN, "a", usage = usage(100, 20))
                } else {
                    reply(reason = null, text = "b", usage = usage(50, 10))
                }
            }
        }

        // Both requests, not the last one: the resumption is what makes the figure the exchange's
        // own rather than any one response's.
        assertEquals(TokenUsage(inputTokens = 150, outputTokens = 30), failure.usage)
    }

    @Test
    fun `an unreachable provider mid-resumption carries what the turn had already cost`() {
        // `send` raises this and can see one request, not the turn — so the responses already
        // billed are the exchange's to report, and it is the only place they are knowable.
        val failure = assertThrows<ModelUnavailable> {
            exchange(ClaudeProvider.ANSWERING, request(openWeb = true), continuations = 3) { params ->
                if (params.messages().size == 1) {
                    reply(StopReason.PAUSE_TURN, "a", usage = usage(100, 20))
                } else {
                    throw ModelUnavailable("the model could not be reached")
                }
            }
        }

        assertEquals(TokenUsage(inputTokens = 100, outputTokens = 20), failure.usage)
        assertTrue(
            failure.message!!.contains("could not be reached"),
            "Re-raised with the turn's figure, so what it says must still be what `send` said.",
        )
        // The original stands as the cause, so the frames of the request that actually failed are
        // not lost to the re-raise. Dropped, this is a failure that says where it was caught and
        // not where it happened.
        assertInstanceOf<ModelUnavailable>(failure.cause)
    }

    @Test
    fun `a call that never landed carries nothing, because nothing was billed`() {
        // The honest figure, and the reason the default is nothing rather than a guess: a request
        // that failed before any reply arrived cost what the provider charges for it, which is
        // nothing it reports. Inventing one here would charge a sweep for a call that never ran.
        val failure = assertThrows<ModelUnavailable> {
            exchange(ClaudeProvider.ANSWERING, request(), continuations = 0) {
                throw ModelUnavailable("the model could not be reached")
            }
        }

        assertEquals(TokenUsage.NONE, failure.usage)
    }

    @Test
    fun `the two unreadable cases do not report the same thing`() {
        // An operator who cannot tell "the provider sent nothing" from "the provider sent
        // something newer than this build" cannot tell which one a deploy would fix. That the
        // type differs from ModelUnavailable needs no test — the compiler settles it, and the
        // retry semantics are opposite, which is why they are separate types at all.
        assertTrue(null.unreadable.contains("no stop reason"))
        assertTrue(StopReason.of("brand_new").unreadable.contains("brand_new"))
        assertNotEquals(null.unreadable, StopReason.of("brand_new").unreadable)
    }

    @Test
    fun `thinking is not read as part of the answer`() {
        // The summary the model can return is commentary for a human watching, not content.
        // A stage that spliced it into the answer would be parsing the wrong thing.
        val message = Message.builder()
            .id("msg_test")
            .model(ClaudeProvider.ANSWERING)
            .addContent(ThinkingBlock.builder().thinking("deliberating").signature("sig").build())
            .addContent(TextBlock.builder().text("the answer").citations(listOf()).build())
            .stopReason(StopReason.END_TURN)
            .stopSequence(null)
            .stopDetails(null)
            .container(null)
            .usage(usage())
            .build()
        assertEquals("the answer", message.prose())
    }

    // ---- the exchange, across more than one response ---------------------------------------

    @Test
    fun `a resumed turn keeps the prose produced before the pause`() {
        // The defect this exists for: the provider does not repeat what it already sent, so
        // reading only the final response hands back a fragment labelled complete — the
        // failure the sealed hierarchy exists to prevent, arriving through another door.
        val sent = mutableListOf<MessageCreateParams>()
        val outcome = exchange(ClaudeProvider.ANSWERING, request(openWeb = true), continuations = 3) { params ->
            sent += params
            if (sent.size == 1) {
                reply(StopReason.PAUSE_TURN, "first half ")
            } else {
                reply(StopReason.END_TURN, "second half")
            }
        }

        assertEquals(ModelOutcome.Answered("first half second half", TokenUsage(200, 40)), outcome)
        assertEquals(2, sent.size)
    }

    @Test
    fun `a resumed turn bills for every request it made`() {
        val outcome = exchange(ClaudeProvider.ANSWERING, request(openWeb = true), continuations = 3) { params ->
            if (params.messages().size == 1) {
                reply(StopReason.PAUSE_TURN, "a", usage = usage(100, 20, cacheRead = 7, cacheWrite = 3))
            } else {
                reply(StopReason.END_TURN, "b", usage = usage(50, 10, cacheRead = 1, cacheWrite = 0))
            }
        }

        assertEquals(
            TokenUsage(inputTokens = 150, outputTokens = 30, cacheReadTokens = 8, cacheWriteTokens = 3),
            outcome.usage,
        )
    }

    @Test
    fun `a turn that keeps pausing stops, and reports stopping short`() {
        var calls = 0
        val outcome = exchange(ClaudeProvider.ANSWERING, request(openWeb = true), continuations = 2) {
            calls++
            reply(StopReason.PAUSE_TURN, "x")
        }

        // One call, then two resumptions, then it gives up rather than billing forever.
        assertEquals(3, calls)
        assertEquals(ModelOutcome.Paused("xxx", TokenUsage(300, 60)), outcome)
    }

    @Test
    fun `a turn that pauses then runs out of room keeps both segments`() {
        // The branch where the accumulated prose is most load-bearing: a truncation after a
        // pause. Last-only would hand back a fragment of a fragment.
        val outcome = exchange(ClaudeProvider.ANSWERING, request(openWeb = true), continuations = 3) { params ->
            if (params.messages().size == 1) {
                reply(StopReason.PAUSE_TURN, "searched and found ")
            } else {
                reply(StopReason.MAX_TOKENS, "began to answer but ran")
            }
        }
        assertEquals(ModelOutcome.Truncated("searched and found began to answer but ran", TokenUsage(200, 40)), outcome)
    }

    @Test
    fun `a turn that pauses then refuses reports the refusal and keeps no prose`() {
        // Deliberate: `Refused` carries no text, so prose produced before the decline is
        // dropped rather than presented as part of an answer. Asserted so the choice is
        // visible rather than incidental.
        val outcome = exchange(ClaudeProvider.ANSWERING, request(openWeb = true), continuations = 3) { params ->
            if (params.messages().size == 1) {
                reply(StopReason.PAUSE_TURN, "searched ")
            } else {
                reply(
                    StopReason.REFUSAL,
                    "",
                    RefusalStopDetails.builder().category(RefusalStopDetails.Category.CYBER).explanation(null).build(),
                )
            }
        }
        assertEquals(ModelOutcome.Refused("cyber", TokenUsage(200, 40)), outcome)
    }

    @Test
    fun `a turn that does not pause is one request`() {
        var calls = 0
        val outcome = exchange(ClaudeProvider.ANSWERING, request(), continuations = 3) {
            calls++
            reply(StopReason.END_TURN, "done")
        }
        assertEquals(1, calls)
        assertEquals(ModelOutcome.Answered("done", TokenUsage(100, 20)), outcome)
    }

    @Test
    fun `resuming re-sends the prior turn with the paused reply appended`() {
        // No synthetic "continue" turn: that would read as a user instruction and change what
        // the model was doing.
        val sent = mutableListOf<MessageCreateParams>()
        exchange(ClaudeProvider.ANSWERING, request(openWeb = true), continuations = 1) { params ->
            sent += params
            if (sent.size == 1) reply(StopReason.PAUSE_TURN, "x") else reply(StopReason.END_TURN, "y")
        }

        assertEquals(1, sent[0].messages().size)
        assertEquals(2, sent[1].messages().size)
    }

    // ---- what reaches the wire -------------------------------------------------------------

    @Test
    fun `neither model takes a date suffix`() {
        assertEquals("claude-opus-5", ClaudeProvider.ANSWERING)
        assertEquals("claude-sonnet-5", ClaudeProvider.BULK)
        // A suffixed id is not an older version of either model; it does not exist.
        assertFalse(Regex("\\d{8}").containsMatchIn(ClaudeProvider.ANSWERING))
        assertFalse(Regex("\\d{8}").containsMatchIn(ClaudeProvider.BULK))
    }

    @Test
    fun `the request names whichever model it was built for`() {
        // The model is a property of the provider rather than a constant everything reads, which
        // is what lets one process answer on one model and infer on another. A `paramsFor` that
        // ignored its argument would send every call to the same model while the log line, the
        // watermark and the configuration all claimed otherwise — and the bill would be the only
        // place it showed.
        assertEquals(
            "claude-opus-5",
            paramsFor(ClaudeProvider.ANSWERING, request()).model().toString(),
        )
        assertEquals(
            "claude-sonnet-5",
            paramsFor(ClaudeProvider.BULK, request()).model().toString(),
        )
    }

    @Test
    fun `a provider reports the model it asks as its version`() {
        // A derivation mark carries this, so a provider that reported a model it does not ask
        // would file one model's output under another's name — stale in the one way nothing
        // downstream can detect, because staleness is decided by comparing exactly this string.
        // Built through the factory rather than around a double: constructing a client sends
        // nothing, and this module holds that mocking a collaborator proves only that the mock
        // behaves like the mock.
        val answering = ClaudeProvider.of(apiKey = "test-key")
        assertEquals(ClaudeProvider.ANSWERING, answering.version())
        assertEquals(ClaudeProvider.BULK, answering.on(ClaudeProvider.BULK).version())
        // And the one it came from is unchanged, so deriving the second cannot move the first.
        assertEquals(ClaudeProvider.ANSWERING, answering.version())
    }

    @Test
    fun `a provider naming no model is refused where it is built`() {
        // Rather than on the first call, which is a request billed for nothing and a failure
        // reported from whichever pass happened to ask first.
        assertThrows<IllegalArgumentException> { ClaudeProvider.of(apiKey = "test-key", model = " ") }
        assertThrows<IllegalArgumentException> { ClaudeProvider.of(apiKey = "test-key").on(" ") }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `the request sends no sampling knob`() {
        // All three are rejected by this model. The port offers none of them, so the only way
        // one could appear is here — which is what this asserts against.
        //
        // The accessors are deprecated for precisely the reason this test exists: the SDK marks
        // them as rejected by anything newer than Opus 4.6. Reading one to prove it was never
        // set is the one legitimate use left, so the deprecation is suppressed here and nowhere
        // else in this build.
        val params = paramsFor(ClaudeProvider.ANSWERING, request())
        assertTrue(params.temperature().isEmpty)
        assertTrue(params.topP().isEmpty)
        assertTrue(params.topK().isEmpty)
    }

    @Test
    fun `the system prompt carries the cache breakpoint`() {
        // Without a breakpoint nothing is ever cached, and the cache counters on TokenUsage
        // would read zero on every call — unable to show the regression they exist for.
        val blocks = paramsFor(ClaudeProvider.ANSWERING, request()).system().get().asTextBlockParams()
        assertEquals(1, blocks.size)
        assertTrue(blocks.single().cacheControl().isPresent)
        assertEquals("standing instructions", blocks.single().text())
    }

    @Test
    fun `effort reaches the wire`() {
        assertEquals(
            OutputConfig.Effort.XHIGH,
            paramsFor(ClaudeProvider.ANSWERING, request(effort = Effort.XHIGH)).outputConfig().get().effort().get(),
        )
        assertEquals(
            OutputConfig.Effort.LOW,
            paramsFor(ClaudeProvider.ANSWERING, request(effort = Effort.LOW)).outputConfig().get().effort().get(),
        )
    }

    @Test
    fun `web search is declared only when the request asks for the open web`() {
        // The one shape that reads the open web turns this on. Everything that reads the corpus
        // leaves it off, and a tool declared by default would give every call web access.
        assertTrue(paramsFor(ClaudeProvider.ANSWERING, request()).tools().isEmpty)

        val tools = paramsFor(ClaudeProvider.ANSWERING, request(openWeb = true)).tools().get()
        assertEquals(1, tools.size)
        assertTrue(tools.single().webSearchTool20260209().isPresent)
    }

    // ---- which failures a caller may retry -------------------------------------------------

    @Test
    fun `an unreachable provider and a bad request are not the same failure`() {
        // The distinction decides whether a caller may try again. Treating a malformed request
        // as retryable turns one defect into several identical ones and bills for each.
        assertTrue(unreachable(AnthropicIoException("connection reset")) is ModelUnavailable)
        assertTrue(unreachable(AnthropicRetryableException("transient")) is ModelUnavailable)
        assertNull(unreachable(AnthropicException("the request was malformed")))
    }

    @Test
    fun `every status the provider retries counts as unreachable`() {
        // Sorting by exception type would miss two of these: the SDK gives only 429 and 5xx
        // their own classes, and surfaces 408 and 409 through the same catch-all type as a
        // 400 — so a literal timeout would be handed back as though it were a bad request.
        listOf(408, 409, 429, 500, 503, 529).forEach {
            assertTrue(isRetryableStatus(it)) { "$it is retried by the provider" }
        }
        listOf(400, 401, 403, 404, 422).forEach {
            assertFalse(isRetryableStatus(it)) { "$it is a defect in the request, not a retry" }
        }
    }

    @Test
    fun `an unreachable failure keeps the cause it came from`() {
        // Dropping the cause would leave an operator with "the model could not be reached" and
        // no way to find out which of the several reasons it was.
        val cause = AnthropicIoException("connection reset")
        assertEquals(cause, unreachable(cause)?.cause)
    }

    @Test
    fun `the credential cannot be blank`() {
        // Fails here rather than as an authentication error on the first real call, which is
        // the difference between a startup failure and a mystery at run time.
        assertThrows<IllegalArgumentException> { ClaudeProvider.of("  ") }
    }

    // ---- a submission, and what one result of it means -------------------------------------

    @Test
    fun `a batched request carries the same decisions as a single one`() {
        // The two paths build different provider types from the same intent, so nothing in the
        // language stops them drifting. A batched call that asked at a different effort, or
        // without the cache breakpoint, would read the corpus differently from a synchronous
        // one while looking identical from the outside.
        val params = batchParams(ClaudeProvider.BULK, mapOf("w1" to request(Effort.MEDIUM)))
        val request = params.requests().single()
        // Decoded, because what goes on the wire is the encoded form: the provider's identifier
        // takes a narrower alphabet than a record key uses. The key test below covers that.
        assertEquals("w1", request.customId().fromCustomId())
        val body = request.params()
        assertEquals(ClaudeProvider.BULK, body.model().toString())
        assertEquals(4096L, body.maxTokens())
        assertEquals(OutputConfig.Effort.MEDIUM, body.outputConfig().get().effort().get())
        assertTrue(body.thinking().get().adaptive().isPresent) { "thinking is on, as on the single-call path" }
        // The breakpoint is what makes a shared prefix billable at read rates, and it is the one
        // part of the request a reader cannot infer from the prompt text.
        val system = body.system().get().textBlockParams().get().single()
        assertEquals("standing instructions", system.text())
        assertTrue(system.cacheControl().isPresent) { "the cache breakpoint sits at the end of the system prompt" }
    }

    @Test
    fun `every request in a submission keeps its own key`() {
        // The key is how a reply is matched back to the work that asked for it. Submitting three
        // and finding two keys would mean a window silently paid for and discarded.
        //
        // **Keyed as the corpus keys things, not as a test would.** The provider accepts only
        // `^[a-zA-Z0-9_-]{1,64}$` as a custom_id, and every identifier this build asks under
        // carries a separator outside it — so a case written with tidy keys like `w1` passes
        // against an adapter that submits raw and is rejected for every real record.
        val keys = listOf("-1001:2001:11", "-1001234567890/12345678", "-1001:2001:12")
        val params = batchParams(ClaudeProvider.BULK, keys.associateWith { request() })

        val submitted = params.requests().map { it.customId() }
        val accepted = Regex("^[a-zA-Z0-9_-]{1,64}$")
        submitted.forEach { id ->
            assertTrue(accepted.matches(id), "The provider rejects '$id' as a custom_id.")
        }
        assertEquals(
            keys,
            submitted.map { it.fromCustomId() },
            "A key is encoded on the way out and decoded on the way back, so what a caller asked " +
                "under is what it reads the reply under.",
        )
        assertEquals(keys.size, submitted.distinct().size, "Distinct keys stay distinct.")
    }

    @Test
    fun `forty-eight bytes is the longest key the provider's identifier can carry`() {
        // The arithmetic behind the cap `submit` enforces. Base64 turns three bytes into four
        // characters, so the boundary is exact rather than a margin someone chose — and a cap
        // asserted nowhere is a number that drifts from the limit it was derived from.
        assertEquals(64, "x".repeat(48).asCustomId().length)
        assertTrue(
            "x".repeat(49).asCustomId().length > 64,
            "One byte more does not fit, which is what makes 48 the cap rather than a guess.",
        )
        // And the keys actually submitted sit far enough inside it that the cap is tolerable.
        assertTrue("-1001234567890/12345678".asCustomId().length < 64)
    }

    @Test
    fun `a succeeded result is read exactly as a single reply is`() {
        val result = MessageBatchResult.ofSucceeded(
            MessageBatchSucceededResult.builder().message(reply(StopReason.END_TURN, "the whole thing")).build(),
        )
        val outcome = assertInstanceOf<BatchOutcome.Replied>(result.batched).outcome
        assertEquals("the whole thing", assertInstanceOf<ModelOutcome.Answered>(outcome).text)
        // Read off the result rather than assumed: a batch is billed per request, and a zero
        // taken on trust would under-report by the whole submission.
        assertEquals(20, outcome.usage.outputTokens)
    }

    @Test
    fun `a truncation and a refusal inside a batch are not answers either`() {
        // The acceptance that matters, and the reason the succeeded case reuses `outcome`: three
        // of the stop reasons are spend that bought nothing readable, and a window whose reply is
        // one of them must stay owed rather than be settled.
        val truncated = MessageBatchResult.ofSucceeded(
            MessageBatchSucceededResult.builder().message(reply(StopReason.MAX_TOKENS, "half of an ans")).build(),
        )
        assertTrue(assertInstanceOf<BatchOutcome.Replied>(truncated.batched).outcome is ModelOutcome.Truncated)

        val refused = MessageBatchResult.ofSucceeded(
            MessageBatchSucceededResult.builder().message(reply(StopReason.REFUSAL, "")).build(),
        )
        assertTrue(assertInstanceOf<BatchOutcome.Replied>(refused.batched).outcome is ModelOutcome.Refused)
    }

    @Test
    fun `one unreadable reply is reported for its own request and not thrown`() {
        // Thrown, it would take every other result in the submission with it — and those windows
        // would be read and paid for a second time. The single-call path throws for the same
        // condition, and that asymmetry is the whole reason BatchOutcome exists.
        val invented = StopReason.of("some_reason_from_the_future")
        val result = MessageBatchResult.ofSucceeded(
            MessageBatchSucceededResult.builder().message(reply(invented, "unreadable")).build(),
        )
        val unreadable = assertInstanceOf<BatchOutcome.Unreadable>(result.batched)
        assertTrue(unreadable.reason.contains("some_reason_from_the_future")) {
            "the log has to name the value it could not read: ${unreadable.reason}"
        }
        // Billed, like any other reply that arrived.
        assertEquals(20, unreadable.usage.outputTokens)
    }

    @Test
    fun `a submission that expired is unfinished rather than unreadable`() {
        // The distinction decides whether the work is asked again or given up. Collapsing the two
        // would make an expiry — which another attempt clears — look like something only a new
        // build can read, and the windows behind it would be abandoned.
        val expired = MessageBatchResult.ofExpired(MessageBatchExpiredResult.builder().build()).batched
        assertInstanceOf<BatchOutcome.Unfinished>(expired)

        val canceled = MessageBatchResult.ofCanceled(MessageBatchCanceledResult.builder().build()).batched
        assertInstanceOf<BatchOutcome.Unfinished>(canceled)

        val errored = MessageBatchResult.ofErrored(
            MessageBatchErroredResult.builder()
                .error(
                    ErrorResponse.builder()
                        .error(
                            ErrorObject.ofInvalidRequestError(
                                InvalidRequestError.builder().message("the prompt was empty").build(),
                            ),
                        )
                        .requestId(null)
                        .build(),
                )
                .build(),
        ).batched
        assertInstanceOf<BatchOutcome.Unfinished>(errored)
    }
}
