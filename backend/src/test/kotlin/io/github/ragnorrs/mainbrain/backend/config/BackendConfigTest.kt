package io.github.ragnorrs.mainbrain.backend.config

import io.github.ragnorrs.mainbrain.data.ClaudeProvider
import io.github.ragnorrs.mainbrain.domain.Effort
import io.github.ragnorrs.mainbrain.domain.answer.Budget
import io.github.ragnorrs.mainbrain.domain.answer.Sizing
import io.github.ragnorrs.mainbrain.domain.recognition.Closer
import io.github.ragnorrs.mainbrain.domain.recognition.Inferrer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * What this process refuses to start without, and what it merely goes without.
 *
 * The required half is the shared secret above all: Compose deliberately does not demand that
 * one, so the process refusing it is the only thing standing between an unset `.env` line and a
 * surface anything on the network satisfies.
 *
 * The optional half is the sidecar address, the model credential, the passes' cadences, the
 * horizon and the two daily spend allowances, and what every one of them has in common is that getting
 * it wrong must not stop the process — bar a value that parses as nothing at all, which stops it
 * naming the variable. A
 * backend that refused to start without a model key would take the corpus and ingestion down with
 * the derived layer, which is how the store is exercised in every worktree.
 *
 * The answer path is the case that needs a test rather than a reading, because absent is a working
 * configuration: what is asserted is that a missing credential does not become a startup failure,
 * and that the process can still say which variable it wanted.
 */
class BackendConfigTest {

    /**
     * The required values and nothing else.
     *
     * **The answer path's two variables are deliberately absent from this map**, which two tests
     * below depend on: both loop over its keys asserting that each one is required. An optional
     * value added here would be asserted to be required, and would fail for being what it is.
     */
    private val complete = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://postgres:5432/mainbrain",
        "DATABASE_USER" to "mainbrain",
        "DATABASE_PASSWORD" to "a password",
        "BACKEND_SECRET" to "a shared secret",
    )

    /** The two the answer path needs, which are read as optional. */
    private val answering = mapOf(
        "ANTHROPIC_API_KEY" to "a model credential",
        "EMBEDDINGS_URL" to "http://embeddings:8000",
    )

    private fun read(environment: Map<String, String?>) = BackendConfig.fromEnv { environment[it] }

    @Test
    fun `the required values are read`() {
        val config = read(complete)

        assertEquals("jdbc:postgresql://postgres:5432/mainbrain", config.store.url)
        assertEquals("mainbrain", config.store.user)
        assertEquals("a password", config.store.password)
        assertEquals("a shared secret", config.secret)
    }

    @Test
    fun `every required value is required, and the message names which`() {
        complete.keys.forEach { name ->
            val failure = assertFailsWith<IllegalStateException>("$name was not required") {
                read(complete - name)
            }

            assertTrue(name in failure.message.orEmpty()) {
                "The failure for an absent $name did not name it: ${failure.message}"
            }
        }
    }

    /**
     * Compose interpolates an unset variable to an empty string, so this is the ordinary case
     * rather than the exotic one: a `.env` with the line present and nothing after the `=`.
     *
     * It matters most for the secret. A blank one taken as a value would mean the guard compares
     * every caller's header against nothing and the deployment looks configured.
     */
    @Test
    fun `a blank value is missing, for the secret above all`() {
        complete.keys.forEach { name ->
            val failure = assertFailsWith<IllegalStateException>("a blank $name was accepted") {
                read(complete + (name to "   "))
            }

            assertTrue(name in failure.message.orEmpty()) {
                "The failure for a blank $name did not name it: ${failure.message}"
            }
        }
    }

    /**
     * The state every worktree runs in: a store, a secret, and neither a sidecar nor a credential.
     *
     * Reading as absent rather than as the empty string is what lets the container decide which
     * passes it can run. An empty address taken as a value would schedule indexing against
     * nothing, and a blank credential would have inference fail at the provider — each sweep, for
     * ever, with the real cause two layers down.
     */
    @Test
    fun `without a sidecar or a credential the process still reads a configuration`() {
        val config = read(complete)

        assertNull(config.embeddingsUrl)
        assertNull(config.modelCredential)
    }

    @Test
    fun `a blank sidecar address or credential is absent, not empty`() {
        val config = read(complete + mapOf("EMBEDDINGS_URL" to "", "ANTHROPIC_API_KEY" to "   "))

        assertNull(config.embeddingsUrl, "An empty EMBEDDINGS_URL was taken as an address.")
        assertNull(config.modelCredential, "A blank ANTHROPIC_API_KEY was taken as a credential.")
    }

    @Test
    fun `the sidecar and the credential are read when they are set`() {
        val config = read(
            complete + mapOf("EMBEDDINGS_URL" to "http://embeddings:8000", "ANTHROPIC_API_KEY" to "a key"),
        )

        assertEquals("http://embeddings:8000", config.embeddingsUrl)
        assertEquals("a key", config.modelCredential)
    }

    /**
     * The two cadences are one knob each, and the defaults are two orders of magnitude apart.
     *
     * That gap is the whole reason there are two of them: indexing is local compute and the cost
     * of sweeping it often is a count against the store, while inference spends the model budget
     * per window. A single cadence would have to be one of the two and would be wrong for the
     * other.
     */
    @Test
    fun `the cadences default apart, and recognition is the slower`() {
        val passes = read(complete).passes

        assertTrue(passes.indexing < passes.recognition) {
            "Indexing (${passes.indexing}) does not sweep more often than recognition " +
                "(${passes.recognition}). Indexing is local compute; recognition is the model budget."
        }
        assertTrue(passes.horizon.isPositive(), "The inference horizon reaches back no distance.")
    }

    /**
     * The horizon default is bounded by the budget, not by what would answer best.
     *
     * Inference is the only thing in this build that spends the model credit, the credit is small,
     * and a sweep drains to completion — so a default wide enough to be interesting is wide enough
     * to spend the whole provision on a first sweep over a real archive. Asserted with a ceiling
     * rather than an exact value, because the number is meant to be raised deliberately while
     * watching what a sweep reports it cost; what must not happen is it drifting upward quietly.
     */
    @Test
    fun `the horizon defaults to a span the provisioned credit survives`() {
        val horizon = read(complete).passes.horizon

        assertTrue(horizon <= 30.days) {
            "The inference horizon defaults to $horizon. Every day of it is more model spend on " +
                "a first sweep, which drains to completion — so widening the default is a " +
                "decision to spend the credit, and it belongs in a deployment's own variable."
        }
    }

    /**
     * The defaults admit a backfill and refuse a re-reading of the whole horizon.
     *
     * **Two bounds in one case, because the pair is what has to hold.** Sized from a record's
     * measured cost — about 3,300 input tokens and 900 output over a full cycle against a real
     * archive, already halved by the batch endpoint — the defaults have to be large enough that a
     * backlog drains in a few days and small enough that re-reading the horizon after a prompt or
     * model change cannot finish inside one day. A re-reading is the one event that empties the
     * credit in an afternoon, and it is also the one this card exists to bound.
     *
     * Stated in records rather than in tokens so it stays meaningful as the defaults are tuned, and
     * as bounds rather than exact numbers: raising them is a deliberate decision to backfill
     * faster, and what must not happen is their drifting upward unnoticed until one unattended day
     * can again empty the balance.
     */
    @Test
    fun `the daily allowances admit a backfill and refuse a whole re-reading`() {
        val passes = read(complete).passes
        // What a record measurably costs, on each axis, at the batched rate.
        val byInput = passes.inputTokensPerDay / 3_300L
        val byOutput = passes.outputTokensPerDay / 900L
        val records = minOf(byInput, byOutput)

        assertTrue(records >= 300L) {
            "A day admits only $records record(s) by default — $byInput by the input allowance " +
                "and $byOutput by the output one. A backfill of a thousand-odd records would take " +
                "more days than the horizon is wide, so the corpus inside it would never be read."
        }
        assertTrue(records <= 600L) {
            "A day admits $records record(s) by default, which is a re-reading of the whole " +
                "horizon in one unattended run. A sweep drains until nothing is owed, so this is " +
                "what stands between a version change and an exhausted balance — and an exhausted " +
                "balance reaches the reader as an unexplained failure to answer."
        }
    }

    /**
     * Neither allowance is the other, which a single shared reader would hide.
     *
     * The two are parsed the same way and bound different axes, so a reader wired to the wrong
     * variable would be invisible: both would still be numbers, and the one somebody set would
     * silently bound the other thing.
     */
    @Test
    fun `the two allowances are read from their own variables`() {
        val passes = read(
            complete + mapOf(
                "RECOGNITION_INPUT_TOKENS_PER_DAY" to "123456",
                "RECOGNITION_OUTPUT_TOKENS_PER_DAY" to "7890",
            ),
        ).passes

        assertEquals(123_456L, passes.inputTokensPerDay)
        assertEquals(7_890L, passes.outputTokensPerDay)
    }

    @Test
    fun `each cadence, the horizon and the allowances are separately configurable`() {
        val passes = read(
            complete + mapOf(
                "INDEXING_SECONDS_BETWEEN_SWEEPS" to "30",
                "RECOGNITION_SECONDS_BETWEEN_SWEEPS" to "900",
                "RECOGNITION_HORIZON_DAYS" to "7",
                "RECOGNITION_INPUT_TOKENS_PER_DAY" to "400000",
                "RECOGNITION_OUTPUT_TOKENS_PER_DAY" to "40000",
            ),
        ).passes

        assertEquals(30.seconds, passes.indexing)
        assertEquals(900.seconds, passes.recognition)
        assertEquals(7.days, passes.horizon)
        assertEquals(400_000L, passes.inputTokensPerDay)
        assertEquals(40_000L, passes.outputTokensPerDay)
    }

    /**
     * A malformed pass setting stops the process rather than reading as unset.
     *
     * Read leniently, a typo would silently restore the default — so the one value somebody could
     * be sure was not in force would be the one they had set. The same reasoning `:ingest` records
     * for its own readers.
     */
    @Test
    fun `a pass setting that is not a number is refused, and the message names the variable`() {
        listOf(
            "INDEXING_SECONDS_BETWEEN_SWEEPS",
            "RECOGNITION_SECONDS_BETWEEN_SWEEPS",
            "RECOGNITION_HORIZON_DAYS",
            "RECOGNITION_CLOSURE_DAYS",
            "RECOGNITION_CLOSURE_REARM_MESSAGES",
            "RECOGNITION_MESSAGES_PER_SWEEP",
            "RECOGNITION_INPUT_TOKENS_PER_DAY",
            "RECOGNITION_OUTPUT_TOKENS_PER_DAY",
        ).forEach { name ->
            val failure = assertFailsWith<IllegalStateException>("a malformed $name was accepted") {
                read(complete + (name to "every minute"))
            }

            assertTrue(name in failure.message.orEmpty()) {
                "The failure for a malformed $name did not name it: ${failure.message}"
            }
        }
    }

    /**
     * The acceptance point the answer path's optionality exists for.
     *
     * A credential-free stack is how the store and ingestion are exercised locally. If reading a
     * configuration without those two threw, that deployment would stop existing and every one of
     * those runs would need a model credential to bring a database up.
     *
     * The report is asserted as well as the absence, because the two are what the container needs:
     * empty means assemble, and anything else is both the refusal and the reason for it.
     */
    @Test
    fun `a process given no answer path still reads its configuration, and says what it wanted`() {
        val config = read(complete)

        assertEquals("a shared secret", config.secret)
        assertEquals(listOf("ANTHROPIC_API_KEY", "EMBEDDINGS_URL"), config.answerPathMissing())
    }

    @Test
    fun `both values present leaves nothing missing, which is what an answer path needs`() {
        val config = read(complete + answering)

        assertEquals("a model credential", config.modelCredential)
        assertEquals("http://embeddings:8000", config.embeddingsUrl)
        assertTrue(config.answerPathMissing().isEmpty()) {
            "Both values were set and the answer path still reported ${config.answerPathMissing()} missing."
        }
    }

    /**
     * Each one alone is not half an answer path, and the report names the half that is absent.
     *
     * Asserted per variable rather than for the pair, because the thing a deployment needs is the
     * name of the one it forgot — a report naming both when one is set is as unhelpful as none.
     *
     * This is also the invariant that makes the list safe to report: it is never empty while
     * either value is absent, so a container that logs it can never say it cannot answer and
     * decline to say why.
     */
    @Test
    fun `one value without the other leaves no answer path, and names which is missing`() {
        answering.keys.forEach { absent ->
            val missing = read(complete + (answering - absent)).answerPathMissing()

            assertEquals(listOf(absent), missing) {
                "With only $absent unset, the report named $missing."
            }
        }
    }

    /**
     * And a zero, which parses: a cadence of none sweeps without pause, a horizon of none reaches
     * back no distance, a sweep of no messages selects nothing, and an allowance of none leaves
     * inference nothing to spend.
     *
     * Refused here rather than where it is used, because what a reader of a container log needs is
     * the name of the variable that stopped the process — not a stack trace out of a loop.
     */
    @Test
    fun `a cadence, horizon or allowance of zero is refused`() {
        listOf(
            "INDEXING_SECONDS_BETWEEN_SWEEPS",
            "RECOGNITION_SECONDS_BETWEEN_SWEEPS",
            "RECOGNITION_HORIZON_DAYS",
            "RECOGNITION_CLOSURE_DAYS",
            "RECOGNITION_CLOSURE_REARM_MESSAGES",
            "RECOGNITION_MESSAGES_PER_SWEEP",
            "RECOGNITION_INPUT_TOKENS_PER_DAY",
            "RECOGNITION_OUTPUT_TOKENS_PER_DAY",
        ).forEach { name ->
            assertFailsWith<IllegalArgumentException>("a zero $name was accepted") {
                read(complete + (name to "0"))
            }
        }
    }

    /**
     * How many messages a sweep selects defaults to the pass's own figure, and moves on its own.
     *
     * Restated nowhere, for the reason the closure bounds are not: a second default here is the one
     * a deployment gets by leaving the variable unset, and nothing would notice the two drifting
     * apart. Asserted against the horizon because the two are the pair easiest to read as one dial
     * — the horizon decides how much work exists and this decides how quickly it is taken — and a
     * deployment draining a backlog against a deadline turns the second without meaning to widen
     * the first, which would cost money rather than time.
     */
    @Test
    fun `the messages a sweep selects default to the pass's own figure`() {
        assertEquals(Inferrer.DEFAULT_BATCH_SIZE, read(complete).passes.messagesPerSweep)

        val faster = read(complete + ("RECOGNITION_MESSAGES_PER_SWEEP" to "48")).passes

        assertEquals(48, faster.messagesPerSweep)
        assertEquals(
            read(complete).passes.horizon,
            faster.horizon,
            "the message count moved the horizon with it",
        )
    }

    /**
     * The closure stage's two bounds default to the pass's own figures, and from one place.
     *
     * Both are restated nowhere: a second default in the configuration would be the one a deployment
     * got by leaving the variable unset, and the two would drift apart without anything noticing.
     */
    @Test
    fun `the closure bounds default to the stage's own figures`() {
        val passes = read(complete).passes

        assertEquals(Closer.DEFAULT_EXAMINE.toKotlinDuration(), passes.closure)
        assertEquals(Closer.DEFAULT_REARM, passes.closureRearm)
    }

    @Test
    fun `the closure horizon and its floor move independently of each other`() {
        // Two judgements and not one: how long a closure is worth looking for, and how much new
        // material makes looking again worth a call. Set together they would read as one dial.
        val horizonOnly = read(complete + mapOf("RECOGNITION_CLOSURE_DAYS" to "90")).passes
        val floorOnly = read(complete + mapOf("RECOGNITION_CLOSURE_REARM_MESSAGES" to "12")).passes

        assertEquals(90.days, horizonOnly.closure)
        assertEquals(Closer.DEFAULT_REARM, horizonOnly.closureRearm, "the horizon moved the floor with it")
        assertEquals(12, floorOnly.closureRearm)
        assertEquals(
            Closer.DEFAULT_EXAMINE.toKotlinDuration(),
            floorOnly.closure,
            "the floor moved the horizon with it",
        )
    }

    @Test
    fun `a negative floor is refused as plainly as a zero one`() {
        // Both mean *no floor*, and a negative one is what a deployment types meaning to disable a
        // bound it has read about. Refused rather than treated as off, because off is the behaviour
        // the bound exists to prevent.
        assertFailsWith<IllegalArgumentException>("a negative floor was accepted") {
            read(complete + ("RECOGNITION_CLOSURE_REARM_MESSAGES" to "-1"))
        }
    }

    /**
     * A blank one of the two reaches the report, which is how Compose passes an unset variable.
     *
     * Covered separately from the absence above because blank and unset arrive by different
     * routes and only one of them is exotic: a `.env` line with nothing after the `=` is the
     * ordinary case, and a blank taken as a value would have the container assemble a path around
     * an empty address.
     */
    @Test
    fun `a blank value reaches the report, not the answer path`() {
        answering.keys.forEach { name ->
            val missing = read(complete + answering + (name to "   ")).answerPathMissing()

            assertEquals(listOf(name), missing) {
                "With $name blank, the report named $missing."
            }
        }
    }

    // ---- whether the paid half of recognition runs at all ----------------------------------

    @Test
    fun `the corpus zone is UTC unless a deployment names one`() {
        // Which has to be exactly what the material was dated in before this setting existed: an
        // unset variable may not change what a deployed backend sends the model.
        assertEquals(ZoneOffset.UTC, read(complete).zone)
        assertEquals(ZoneId.of("Asia/Tokyo"), read(complete + ("CORPUS_TIME_ZONE" to "Asia/Tokyo")).zone)
    }

    @Test
    fun `a zone that names nothing is refused rather than read as unset`() {
        // The symptom of quietly restoring UTC is a date in an answer being a day out on some
        // material and not on other, which reads as the model getting it wrong rather than as a
        // typo in a variable. Blank stays unset, because that is how Compose passes one nobody set.
        listOf("Europe/Lisboa", "GMT+25", "tokyo", "Asia/Tokio").forEach { value ->
            val config = runCatching { read(complete + ("CORPUS_TIME_ZONE" to value)) }
            assertTrue(config.isFailure) { "'$value' was accepted as a zone" }
        }
        assertEquals(ZoneOffset.UTC, read(complete + ("CORPUS_TIME_ZONE" to "")).zone)
    }

    @Test
    fun `a zone may be named as an offset, for a deployment that wants one`() {
        // The parser is ZoneId's, so an offset is accepted as well as a region — including the
        // bare `+7` shorthand. Worth a case because the reason to prefer a region — summer time
        // across an archive reaching back years — is a recommendation and not a restriction, and
        // a later reader tightening this to regions only would break a working deployment.
        //
        // Seven for the reason ELSEWHERE in StagesTest is nine: a figure that is nobody's real
        // offset here, so the case cannot be read as a fact about a deployment.
        assertEquals(ZoneOffset.ofHours(7), read(complete + ("CORPUS_TIME_ZONE" to "+07:00")).zone)
        assertEquals(ZoneOffset.ofHours(7), read(complete + ("CORPUS_TIME_ZONE" to "+7")).zone)
    }

    @Test
    fun `UTC named explicitly dates material the same as UTC left unset`() {
        // `ZoneId.of("UTC")` is a region and `ZoneOffset.UTC` is an offset, so the two are not
        // equal objects however much they agree — which is a trap for a case asserting identity.
        // What has to hold is that they date a message alike, so that is what is asserted.
        val named = read(complete + ("CORPUS_TIME_ZONE" to "UTC")).zone
        val unset = read(complete).zone
        val instant = java.time.Instant.parse("2026-10-01T22:30:00Z")

        assertNotEquals(named, unset)
        assertEquals(instant.atZone(unset).toLocalDateTime(), instant.atZone(named).toLocalDateTime())
        assertEquals(ZoneOffset.UTC, instant.atZone(named).offset)
    }

    @Test
    fun `inference is off unless a deployment says otherwise`() {
        // The one default in this configuration chosen against what the build would like. A
        // backend that is up sweeps on its own schedule, so the cost of being wrong only runs one
        // way: the first evidence of a sweep nobody meant to leave running is the balance.
        assertEquals(false, read(complete).passes.inference)
        assertEquals(false, read(complete + answering).passes.inference)
    }

    @Test
    fun `inference runs when a deployment says exactly true`() {
        assertEquals(true, read(complete + ("RECOGNITION_INFERENCE_ENABLED" to "true")).passes.inference)
        assertEquals(true, read(complete + ("RECOGNITION_INFERENCE_ENABLED" to "TRUE")).passes.inference)
        assertEquals(false, read(complete + ("RECOGNITION_INFERENCE_ENABLED" to "false")).passes.inference)
    }

    @Test
    fun `the admission pass is off unless a deployment says otherwise`() {
        // The other stage that spends, and off for the same reason — plus one of its own. It pays
        // only where a source mixes bodies of work with thematic tracks; where every named
        // conversation is already work, every call returns what identity had already read off the
        // label. Nothing downstream waits for it, so off is the corpus as this build otherwise
        // reads it rather than a stage of the answer path missing.
        assertEquals(false, read(complete).passes.admissionEnabled)
        assertEquals(false, read(complete + answering).passes.admissionEnabled)
        assertEquals(true, read(complete + ("ADMISSION_ENABLED" to "true")).passes.admissionEnabled)
        assertEquals(false, read(complete + ("ADMISSION_ENABLED" to "false")).passes.admissionEnabled)
    }

    @Test
    fun `the admission cadence is the slowest, because the pass converges`() {
        // A conversation carries a verdict for as long as the instruction that reached it stands,
        // so there is nothing to do between a new conversation appearing and a reword being
        // deployed. Swept at recognition's pace it would buy a store query an hour and no judgement.
        val passes = read(complete).passes

        assertTrue(passes.recognition < passes.admission) {
            "Admission (${passes.admission}) sweeps at least as often as recognition " +
                "(${passes.recognition}), and it is paced by conversations arriving rather than " +
                "by messages."
        }
        assertEquals(60.minutes, read(complete + ("ADMISSION_SECONDS_BETWEEN_SWEEPS" to "3600")).passes.admission)
    }

    @Test
    fun `anything that is not plainly true or false is refused`() {
        // Deliberately narrower than the values somebody might mean by yes. A reader that accepted
        // `1` and `on` would teach that it accepts the rest, and the one value this gates spends
        // money — so near-agreement must not read as agreement.
        listOf("yes", "1", "on", "enabled", "y", "").forEach { value ->
            val config = runCatching { read(complete + ("RECOGNITION_INFERENCE_ENABLED" to value)) }
            if (value.isEmpty()) {
                // Blank is how Compose passes a variable nobody set, so it reads as unset.
                assertEquals(false, config.getOrThrow().passes.inference)
            } else {
                assertTrue(config.isFailure) { "'$value' was accepted as a flag" }
            }
        }
    }

    // ---- what one question is allowed to cost ----------------------------------------------

    @Test
    fun `the two models default apart, and the bulk one is the cheaper`() {
        // The whole argument for two: answering is a dozen calls a day whose quality a person
        // reads, and the bulk passes are thousands of calls producing mechanical output. A single
        // default for both is what made scanning short messages cost the same as composing an
        // answer.
        val models = read(complete).models

        assertEquals(ClaudeProvider.ANSWERING, models.answering)
        assertEquals(ClaudeProvider.BULK, models.bulk)
        assertNotEquals(models.answering, models.bulk)
    }

    @Test
    fun `either model is configurable without the other moving`() {
        // Independently, because the two changes have different costs: moving the answering model
        // affects the next question, where moving the bulk one re-derives the horizon on the next
        // sweep. A deployment making the cheap change must not be made to make the expensive one.
        val answerOnly = read(complete + ("ANSWER_MODEL" to "claude-fable-5")).models
        assertEquals("claude-fable-5", answerOnly.answering)
        assertEquals(ClaudeProvider.BULK, answerOnly.bulk)

        val bulkOnly = read(complete + ("BULK_MODEL" to "claude-haiku-4-5")).models
        assertEquals(ClaudeProvider.ANSWERING, bulkOnly.answering)
        assertEquals("claude-haiku-4-5", bulkOnly.bulk)
    }

    @Test
    fun `the answer path defaults below the top of the effort range, and research above it`() {
        // Medium on the four shapes that are handed what to read, because that is the setting this
        // path shipped too high at — it was the most expensive dial in the build and it ran on
        // every question. The top of the range stays on the one path that searches inside the
        // call, which is what confines it to one shape instead of five.
        val answer = read(complete).answer

        assertEquals(Effort.MEDIUM, answer.answering.effort)
        assertEquals(Effort.XHIGH, answer.researching.effort)
        assertTrue(answer.researching.ceiling > answer.answering.ceiling)
    }

    @Test
    fun `each effort is separately configurable, and the ceilings are not`() {
        // Effort is the dial a deployment is meant to turn: it trades depth against spend and
        // nothing else. A ceiling is not, because lowering one buys truncations that this build
        // reports as having nothing to answer from — so no variable sets one.
        val config = read(
            complete + mapOf("ANSWER_EFFORT" to "low", "RESEARCH_EFFORT" to "high"),
        ).answer

        assertEquals(Effort.LOW, config.answering.effort)
        assertEquals(Effort.HIGH, config.researching.effort)
        assertEquals(Sizing.ANSWERING.ceiling, config.answering.ceiling)
        assertEquals(Sizing.RESEARCHING.ceiling, config.researching.ceiling)
    }

    @Test
    fun `an effort is read in any case, because a variable is typed by hand`() {
        assertEquals(Effort.XHIGH, read(complete + ("ANSWER_EFFORT" to "XHIGH")).answer.answering.effort)
        assertEquals(Effort.MAX, read(complete + ("ANSWER_EFFORT" to "Max")).answer.answering.effort)
    }

    @Test
    fun `an effort that is not a level is refused, and the message lists the ones that are`() {
        // Refused rather than read as unset, and this matters more than for a cadence: the levels
        // are an enum on the provider's side too, so a misspelling is not something the next call
        // would reject. It is a variable somebody set to buy a cheaper answer that silently went
        // on costing the default.
        listOf("ANSWER_EFFORT", "RESEARCH_EFFORT").forEach { name ->
            val failure = assertFailsWith<IllegalStateException>("a misspelt $name was accepted") {
                read(complete + (name to "mediumm"))
            }
            assertTrue(failure.message!!.contains(name)) { "The message did not name $name." }
            assertTrue(failure.message!!.contains("medium")) {
                "The message did not list the levels: ${failure.message}"
            }
        }
    }

    @Test
    fun `the per-request ceiling defaults to room for more than one full call`() {
        // The two multipliers are what it exists for: the adapter resumes a paused turn, and the
        // runner re-rolls a rejected reply once. A ceiling that fitted one call would make the
        // retry impossible rather than bounded.
        val answer = read(complete).answer

        assertEquals(Budget.DEFAULT_CEILING, answer.perRequest)
        assertTrue(answer.perRequest > answer.researching.ceiling)
    }

    @Test
    fun `the per-request ceiling is configurable, and is the one bound on a runaway question`() {
        assertEquals(
            120_000,
            read(complete + ("ANSWER_MAX_TOKENS_PER_REQUEST" to "120000")).answer.perRequest,
        )
    }

    @Test
    fun `a per-request ceiling below one call's is refused rather than quietly truncating`() {
        // Under the widest call ceiling, every call is sized to the request's remainder instead —
        // so a shape asks for less room than it was built to need and truncates. That reads to
        // whoever asked as the corpus having nothing to say, which is the failure this build is
        // least able to tell apart from a real one.
        val failure = assertFailsWith<IllegalArgumentException> {
            read(complete + ("ANSWER_MAX_TOKENS_PER_REQUEST" to "1024"))
        }
        assertTrue(failure.message!!.contains("ANSWER_MAX_TOKENS_PER_REQUEST"))
    }

    @Test
    fun `a per-request ceiling that is not a positive number is refused`() {
        listOf("nonsense", "0", "-1").forEach { value ->
            assertFailsWith<IllegalStateException>("'$value' was accepted as a ceiling") {
                read(complete + ("ANSWER_MAX_TOKENS_PER_REQUEST" to value))
            }
        }
    }

    @Test
    fun `a blank model or effort reads as unset rather than as a value`() {
        // Which is how Compose passes a variable nobody set. A blank model would name nothing on
        // the wire, and a blank effort would have to be refused as a level that does not exist —
        // both of them stopping a process over a `.env` line with nothing after the `=`.
        val config = read(
            complete + mapOf(
                "ANSWER_MODEL" to "  ",
                "BULK_MODEL" to "",
                "ANSWER_EFFORT" to "   ",
                "ANSWER_MAX_TOKENS_PER_REQUEST" to "",
            ),
        )

        assertEquals(ClaudeProvider.ANSWERING, config.models.answering)
        assertEquals(ClaudeProvider.BULK, config.models.bulk)
        assertEquals(Effort.MEDIUM, config.answer.answering.effort)
        assertEquals(Budget.DEFAULT_CEILING, config.answer.perRequest)
    }
}
