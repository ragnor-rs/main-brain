package io.github.ragnorrs.mainbrain.backend

import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.Exclusion
import io.github.ragnorrs.mainbrain.domain.ModelReplyUnreadable
import io.github.ragnorrs.mainbrain.domain.ModelUnavailable
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The driver, run against scripted stages.
 *
 * What is asserted here is the loop and nothing else: how many batches a sweep does, what stops
 * it, what happens when the exclusion is held, and that one pass's trouble stays its own. The
 * passes themselves are asserted in `:domain` against their own ports, and the exclusion against
 * a real Postgres in `:data`; the three meet at the two functions a [PassStage] is made of.
 *
 * Every stage here is scripted on what the driver reads: how many records a batch settles, and
 * whether it is a stage that can spend. Whether to go round again is decided by the lag falling and
 * by what the day's allowance has left. The text a batch returns is a line for the log and is
 * asserted nowhere.
 */
class DerivedPassesTest {

    private val quick = 1.milliseconds

    @Test
    fun `a sweep does batches until nothing is owed`(): Unit = runBlocking {
        // Five owed and two settled a batch, so three batches: the last has one record left to do
        // and the loop has to go round for it rather than stopping on a short batch.
        val stage = Scripted(owed = 5, settles = 2, spends = false)
        val pass = pass(stage)

        assertTrue(driver(pass).sweep(pass))

        assertEquals(0, stage.owed)
        assertEquals(3, stage.batches, "A sweep stopped before the backlog was drained.")
    }

    @Test
    fun `a stage that settles nothing is left until the next sweep`(): Unit = runBlocking {
        // The failure this guards against costs money. A window the model never finished reading
        // leaves its messages owed, so work still remains and the batch settled none of it — and
        // a loop that only asked whether work remained would re-select the same window, pay for
        // it again, and do so for as long as the sweep lasted.
        val stage = Scripted(owed = 5, settles = 0)
        val pass = pass(stage)

        // Under a timeout, because the defect this guards against does not return: a drain that
        // went round again on a stage settling nothing would spend the model budget until
        // something else stopped it, and a test asserting the batch count alone would hang
        // rather than fail.
        assertEquals(true, withTimeoutOrNull(PATIENCE) { driver(pass).sweep(pass) }) {
            "A sweep over a stage that settles nothing never returned. The guard is the lag " +
                "failing to fall; without it this is an unbounded loop paying per turn."
        }

        assertEquals(1, stage.batches) {
            "A stage that settled nothing was asked again inside the same sweep. The cadence is " +
                "the backoff for this case: a model truncating every window will still be doing " +
                "so a second later."
        }
        assertEquals(5, stage.owed)
    }

    @Test
    fun `a stage with nothing owed does no batch at all`(): Unit = runBlocking {
        val stage = Scripted(owed = 0, settles = 1, spends = false)
        val pass = pass(stage)

        assertTrue(driver(pass).sweep(pass))

        assertEquals(0, stage.batches, "A pass with a caught-up watermark still did a batch.")
    }

    @Test
    fun `a pass whose exclusion is held does nothing and says so`(): Unit = runBlocking {
        // The acceptance the lock exists for: a second backend against one store must not run the
        // same pass. It also must not wait for ever — this answers and returns, and the cadence is
        // when it next looks.
        val stage = Scripted(owed = 5, settles = 2, spends = false)
        val pass = pass(stage)
        val exclusion = HeldExclusion(setOf(Deriver.INDEX))

        assertFalse(DerivedPasses(exclusion, listOf(pass), FakeSpendLedger(), fixed()).sweep(pass))

        assertEquals(0, stage.batches, "A pass ran while another process held its exclusion.")
        assertEquals(listOf(Deriver.INDEX), exclusion.asked)
    }

    @Test
    fun `the stages of one pass run in the order they were given`(): Unit = runBlocking {
        // Recognition is why this matters. Identity assigns the contacts inference writes its
        // findings against, so inference running first loses every attribute whose subject has no
        // contact yet — counted as lost, and nothing re-offers it.
        val order = mutableListOf<String>()
        val note: (String, Int) -> Unit = { label, _ -> order.add(label) }
        val identity = Scripted(owed = 2, settles = 2, spends = false, label = "identity", onBatch = note)
        val inference = Scripted(owed = 2, settles = 2, spends = false, label = "inference", onBatch = note)
        val pass = DerivedPass(
            deriver = Deriver.RECOGNITION,
            cadence = quick,
            allowance = UNBOUNDED,
            stages = { listOf(identity.stage(), inference.stage()) },
        )

        driver(pass).sweep(pass)

        assertEquals(listOf("identity", "inference"), order)
    }

    @Test
    fun `the stages are built once a sweep, so a dated stage keeps one date throughout`(): Unit = runBlocking {
        // What rests on this is money. Inference's stage closes over the recency horizon as an
        // instant, and the driver decides whether a batch made progress by comparing what is owed
        // before it against what is owed after. Rebuild the stage between those two counts and the
        // cut-off moves, so the oldest records drop out of the window and their disappearance reads
        // as work the batch settled — which is the stall guard defeated, and the loop re-paying for
        // windows the model never finished.
        val stage = Scripted(owed = 5, settles = 2, spends = false)
        var built = 0
        val pass = DerivedPass(Deriver.INDEX, quick, UNBOUNDED) {
            built++
            listOf(stage.stage())
        }
        // One already, from the constructor's check that there is a stage at all.
        val atConstruction = built

        driver(pass).sweep(pass)

        assertEquals(3, stage.batches, "The sweep did not drain, so this asserts nothing about it.")
        assertEquals(atConstruction + 1, built) {
            "One sweep built its stage list ${built - atConstruction} time(s). It must be once: a " +
                "stage holding an instant has to hold the same one for every batch and every count " +
                "of that sweep."
        }
    }

    @Test
    fun `whatever a batch throws reaches the caller of a sweep`(): Unit = runBlocking {
        // So that the loop is the only thing deciding what a failure costs, and so that the
        // exclusion is released on the way out rather than held by a sweep nobody is running.
        val stage = Scripted(owed = 5, settles = 2, fails = Fails(IllegalStateException("the sidecar is down")))
        val pass = pass(stage)

        val failure = assertFailsWith<IllegalStateException> { driver(pass).sweep(pass) }

        assertEquals("the sidecar is down", failure.message)
    }

    @Test
    fun `a pass that fails every sweep does not stop the other one`(): Unit = runBlocking {
        // The two run as siblings, so what this proves is that a sidecar being down leaves
        // recognition running — and that the failing pass keeps trying rather than ending.
        val broken = Scripted(owed = 5, settles = 1, fails = Fails(IllegalStateException("the sidecar is down")))
        val working = Scripted(owed = 1_000, settles = 1, spends = false)
        val driver = DerivedPasses(
            FreeExclusion(),
            listOf(
                DerivedPass(Deriver.INDEX, quick, UNBOUNDED) { listOf(broken.stage()) },
                DerivedPass(Deriver.RECOGNITION, quick, UNBOUNDED) { listOf(working.stage()) },
            ),
            FakeSpendLedger(),
            fixed(),
        )

        val loops = launch { driver.run() }
        val both = withTimeoutOrNull(PATIENCE) {
            while (working.batches < 3 || broken.batches < 3) delay(1)
            true
        }
        loops.cancelAndJoin()

        assertTrue(both == true) {
            "One of the two passes stopped: the working one did ${working.batches} batches and " +
                "the failing one ${broken.batches}. A failure is caught per sweep so that a pass " +
                "which cannot run leaves the other alone."
        }
    }

    @Test
    fun `a pass that lets an Error out does not take the other pass with it`(): Unit = runBlocking {
        // The half the loop's own `catch` cannot cover. It catches `Exception`, which is every
        // failure this build expects; an `Error` — a conversation large enough to exhaust the heap
        // is the plausible one — goes past it and out of the coroutine. Under an unsupervised scope
        // that cancels the sibling on its way out, so both passes end and the process goes on
        // serving and reporting healthy with nothing being derived at all.
        val broken = Scripted(owed = 5, settles = 1, fails = Fails(ScriptedError()))
        val working = Scripted(owed = 1_000, settles = 1, spends = false)
        val driver = DerivedPasses(
            FreeExclusion(),
            listOf(
                DerivedPass(Deriver.INDEX, quick, UNBOUNDED) { listOf(broken.stage()) },
                DerivedPass(Deriver.RECOGNITION, quick, UNBOUNDED) { listOf(working.stage()) },
            ),
            FakeSpendLedger(),
            fixed(),
        )
        // Supervised here for the same reason `Main` supervises: without it the escaping `Error`
        // would end this test rather than be reported by it.
        val sweeps = CoroutineScope(coroutineContext + SupervisorJob())

        sweeps.launch { driver.run() }
        val survived = withTimeoutOrNull(PATIENCE) {
            while (working.batches < 3) delay(1)
            true
        }
        sweeps.cancel()

        assertTrue(survived == true) {
            "The working pass did ${working.batches} batches after the other let an Error out. " +
                "One pass's Error must not cancel the other, which is what the driver's scope " +
                "being supervised buys."
        }
    }

    @Test
    fun `a cancellation that is not the driver's own is a failure the loop survives`(): Unit = runBlocking {
        // A client library raising its own timeout throws `CancellationException` too. Rethrown,
        // it would end this pass for the life of the process with nothing logged — the loop simply
        // gone, the lag climbing, and the health route still green. So the guard asks whether this
        // coroutine was cancelled rather than trusting the exception's type, and anything else is
        // an ordinary failure.
        val stage = Scripted(
            owed = 1_000,
            settles = 1,
            fails = Fails(CancellationException("the HTTP client timed out")),
        )
        val driver = driver(pass(stage))

        val loops = launch { driver.run() }
        val kept = withTimeoutOrNull(PATIENCE) {
            while (stage.batches < 3) delay(1)
            true
        }
        loops.cancelAndJoin()

        assertTrue(kept == true) {
            "The loop stopped after ${stage.batches} batch(es) raised a CancellationException " +
                "that was not this driver's. Only a cancellation of this coroutine ends the loop."
        }
    }

    @Test
    fun `cancelling the driver ends every loop`(): Unit = runBlocking {
        // What the shutdown hook rests on. A loop that swallowed its own cancellation would hold
        // the process open and, worse, hold an advisory lock on a pinned connection while the
        // pool was being closed underneath it.
        val stage = Scripted(owed = 1_000_000, settles = 1)
        val driver = driver(pass(stage))

        val loops = launch { driver.run() }
        withTimeoutOrNull(PATIENCE) { while (stage.batches < 2) delay(1) }
        loops.cancelAndJoin()

        assertTrue(loops.isCompleted, "The driver did not stop when it was cancelled.")
    }

    @Test
    fun `an exhausted day stops the stage that spends and leaves the rest owed`(): Unit = runBlocking {
        val stopped = Scripted(owed = 10, settles = 1, spends = true)
        val ledger = FakeSpendLedger()
        ledger.place(Deriver.INDEX, DAY_FROM, DAY_UNTIL, TokenUsage(inputTokens = 500, outputTokens = 100))

        sweepOnce(pass(stopped, ALLOWANCE), ledger)

        assertEquals(listOf(false), stopped.allowed) {
            "An exhausted day stops the spending, not the stage: the batch runs once with the " +
                "spending withheld, so a stage can still write back what a submission already " +
                "bought — and runs only once, because a stage holds at most one submission."
        }
        assertEquals(9, stopped.owed) {
            "The collecting batch settles what it was already paid for; the rest is left owed and " +
                "unmarked, so the next day resumes from the same watermark."
        }
    }

    @Test
    fun `a stage that spends nothing drains its whole backlog on an exhausted day`(): Unit = runBlocking {
        // The behaviour this reverses deliberately: a bound that refused every stage would cost the
        // corpus its retrievability to save money that indexing was never going to spend.
        val free = Scripted(owed = 4, settles = 1, spends = false)
        val ledger = FakeSpendLedger(everywhere = TokenUsage(inputTokens = 10_000, outputTokens = 10_000))

        sweepOnce(pass(free, ALLOWANCE), ledger)

        assertEquals(0, free.owed) {
            "Indexing embeds against a sidecar and identity matches identifiers in a store. " +
                "Neither draws on the model credit, so neither should be held back by a day that " +
                "has."
        }
        assertEquals(0, ledger.reads) {
            "A stage that cannot spend should not even read the day: the read is a query, and a " +
                "pass of free stages would make one per batch for nothing."
        }
    }

    @Test
    fun `exhausting the input allowance alone stops the stage`(): Unit = runBlocking {
        val stage = Scripted(owed = 5, settles = 1, spends = true)
        val ledger = FakeSpendLedger(
            // Input gone, output barely touched. A bound needing both would not fire.
            everywhere = TokenUsage(inputTokens = 1_000, outputTokens = 1),
        )

        sweepOnce(pass(stage, ALLOWANCE), ledger)

        assertEquals(listOf(false), stage.allowed) {
            "Either axis running out is enough to withhold the spending. A longer instruction " +
                "moves input a long way without touching output, so a bound needing both would be " +
                "set by whichever the change did not move."
        }
    }

    @Test
    fun `exhausting the output allowance alone stops the stage`(): Unit = runBlocking {
        val stage = Scripted(owed = 5, settles = 1, spends = true)
        val ledger = FakeSpendLedger(everywhere = TokenUsage(inputTokens = 1, outputTokens = 100))

        sweepOnce(pass(stage, ALLOWANCE), ledger)

        assertEquals(listOf(false), stage.allowed) {
            "The output axis alone is also enough to withhold the spending."
        }
    }

    @Test
    fun `an allowance exactly spent is spent`(): Unit = runBlocking {
        val stage = Scripted(owed = 5, settles = 1, spends = true)
        val ledger = FakeSpendLedger(everywhere = TokenUsage(inputTokens = 1_000, outputTokens = 100))

        sweepOnce(pass(stage, ALLOWANCE), ledger)

        assertEquals(listOf(false), stage.allowed) {
            "At or above rather than above: an allowance exactly spent is spent, so the batch " +
                "that follows it may not spend."
        }
    }

    @Test
    fun `the day is read before every batch, so a drain that crosses the line stops`(): Unit = runBlocking {
        val ledger = FakeSpendLedger()
        // Clear for the first two batches; spent by the third. A gate read once per stage would
        // let the whole backlog through, which is the shape a backfill has.
        val stage = Scripted(owed = 10, settles = 1, spends = true, onBatch = { _, batches ->
            if (batches == 2) {
                ledger.place(
                    Deriver.INDEX,
                    DAY_FROM,
                    DAY_UNTIL,
                    TokenUsage(inputTokens = 1_000, outputTokens = 100),
                )
            }
        })

        sweepOnce(pass(stage, ALLOWANCE), ledger)

        assertEquals(listOf(true, true, false), stage.allowed) {
            "Read once per stage, a drain running for an hour could spend arbitrarily past the " +
                "allowance it checked at the start. Read per batch, two batches spend and the " +
                "third is withheld — so the overshoot is bounded at the batch that crossed."
        }
        assertEquals(7, stage.owed) { "The rest is left owed for the next day." }
    }

    @Test
    fun `a new driver reads the same day, so a restart grants no fresh allowance`(): Unit = runBlocking {
        val ledger = FakeSpendLedger(everywhere = TokenUsage(inputTokens = 1_000, outputTokens = 100))
        val first = Scripted(owed = 3, settles = 1, spends = true)
        val second = Scripted(owed = 3, settles = 1, spends = true)

        sweepOnce(pass(first, ALLOWANCE), ledger)
        // A different driver object over the same ledger is what a restarted container is: the
        // figure is in the store, so nothing about the process carries it.
        sweepOnce(pass(second, ALLOWANCE), ledger)

        assertEquals(listOf(false), first.allowed)
        assertEquals(listOf(false), second.allowed) {
            "A container started after an allowance is gone must spend nothing. A bound held in a " +
                "process's memory would hand the whole allowance back on restart, and the second " +
                "sweep would be allowed to spend."
        }
    }

    @Test
    fun `a fresh day starts clear and resumes from the watermark`(): Unit = runBlocking {
        val ledger = FakeSpendLedger()
        ledger.place(Deriver.INDEX, DAY_FROM, DAY_UNTIL, TokenUsage(inputTokens = 1_000, outputTokens = 100))
        val today = Scripted(owed = 3, settles = 1, spends = true)
        val tomorrow = Scripted(owed = 3, settles = 1, spends = true)

        sweepOnce(pass(today, ALLOWANCE), ledger)
        // The next day is a different span, which nothing has been placed in.
        DerivedPasses(
            FreeExclusion(),
            emptyList(),
            ledger,
            Clock.fixed(MIDDAY.plus(1, ChronoUnit.DAYS), ZoneOffset.UTC),
        ).sweep(pass(tomorrow, ALLOWANCE))

        assertEquals(listOf(false), today.allowed) { "Today's allowance was spent." }
        assertEquals(0, tomorrow.owed) {
            "Tomorrow is a different day, so the stage resumes from the same watermark with its " +
                "whole allowance."
        }
    }

    @Test
    fun `what a failed batch had already spent is charged to the day`(): Unit = runBlocking {
        val billed = TokenUsage(inputTokens = 900, outputTokens = 40)
        val stage = Scripted(
            owed = 2,
            settles = 1,
            spends = true,
            fails = Fails(ModelUnavailable("gave up", usage = billed)),
        )
        val ledger = FakeSpendLedger()

        assertFailsWith<ModelUnavailable> { runBlocking { sweepOnce(pass(stage), ledger) } }

        assertEquals(listOf(Deriver.INDEX to billed), ledger.charges) {
            "A failing batch returns nothing, so the figure the failure carries is the only " +
                "record of what the provider billed. Charged to the day, it binds every sweep of " +
                "that day rather than being held in a process."
        }
    }

    @Test
    fun `a failure's charge carries both axes and not the output half`(): Unit = runBlocking {
        val billed = TokenUsage(inputTokens = 5_000, outputTokens = 12)
        val stage = Scripted(
            owed = 2,
            settles = 1,
            spends = false,
            fails = Fails(ModelReplyUnreadable("unreadable", usage = billed)),
        )

        val ledger = FakeSpendLedger()
        assertFailsWith<ModelReplyUnreadable> { runBlocking { sweepOnce(pass(stage), ledger) } }

        assertEquals(5_000, ledger.charges.single().second.inputTokens) {
            "Input is the larger half of what a reading costs, so a charge carrying only output " +
                "would understate a failed attempt by most of its bill."
        }
    }

    @Test
    fun `a failure that cost nothing is still recorded as nothing`(): Unit = runBlocking {
        // A call that never landed was never billed, and zero is the honest figure. The row is
        // what tells that apart from a failure nobody accounted for.
        val stage = Scripted(
            owed = 2,
            settles = 1,
            spends = false,
            fails = Fails(IllegalStateException("the store is down")),
        )
        val ledger = FakeSpendLedger()

        assertFailsWith<IllegalStateException> { runBlocking { sweepOnce(pass(stage), ledger) } }

        assertEquals(listOf(Deriver.INDEX to TokenUsage.NONE), ledger.charges) {
            "A store that is down costs nothing, and reading a cost off it would be inventing one."
        }
    }

    @Test
    fun `a figure one layer down is still the figure`(): Unit = runBlocking {
        val billed = TokenUsage(inputTokens = 700, outputTokens = 30)
        val wrapped = IllegalStateException("the pass could not handle it", ModelUnavailable("gave up", usage = billed))
        val stage = Scripted(owed = 2, settles = 1, spends = false, fails = Fails(wrapped))
        val ledger = FakeSpendLedger()

        assertFailsWith<IllegalStateException> { runBlocking { sweepOnce(pass(stage), ledger) } }

        assertEquals(billed, ledger.charges.single().second) {
            "A pass is entitled to wrap what it could not handle, and a cost one level down is " +
                "still what the provider billed."
        }
    }

    @Test
    fun `two failures in a row are both charged`(): Unit = runBlocking {
        val billed = TokenUsage(inputTokens = 100, outputTokens = 10)
        val ledger = FakeSpendLedger()
        repeat(2) {
            val stage = Scripted(
                owed = 2,
                settles = 1,
                spends = false,
                fails = Fails(ModelUnavailable("gave up", usage = billed)),
            )
            assertFailsWith<ModelUnavailable> { runBlocking { sweepOnce(pass(stage), ledger) } }
        }

        assertEquals(2, ledger.charges.size) {
            "A charge written to the day accumulates there, unlike one held for the next sweep " +
                "and replaced by it. So a pass failing at the same cost every cadence spends the " +
                "day down and then stops for it."
        }
    }

    @Test
    fun `a sweep refused its exclusion charges nothing`(): Unit = runBlocking {
        val stage = Scripted(owed = 3, settles = 1, spends = true)
        val ledger = FakeSpendLedger()
        val held = HeldExclusion(setOf(Deriver.INDEX))

        val ran = DerivedPasses(held, emptyList(), ledger, fixed()).sweep(pass(stage, ALLOWANCE))

        assertFalse(ran)
        assertEquals(0, stage.batches)
        assertEquals(emptyList<Pair<Deriver, TokenUsage>>(), ledger.charges) {
            "Nothing ran and nothing was billed, so there is nothing to charge. A second backend " +
                "holding the exclusion is an ordinary state rather than a failure."
        }
    }

    @Test
    fun `an allowance of nothing is refused`() {
        assertFailsWith<IllegalArgumentException> { DayAllowance(inputTokens = 0, outputTokens = 1) }
        assertFailsWith<IllegalArgumentException> { DayAllowance(inputTokens = 1, outputTokens = 0) }
    }

    @Test
    fun `a day covering no time is refused`() {
        assertFailsWith<IllegalArgumentException> { Day(MIDDAY, MIDDAY) }
    }

    @Test
    fun `the day a sweep is charged to is the UTC day it began in`() {
        val day = Day.around(Instant.parse("2026-05-20T23:59:59Z"))

        assertEquals(Instant.parse("2026-05-20T00:00:00Z"), day.from)
        assertEquals(Instant.parse("2026-05-21T00:00:00Z"), day.until) {
            "Half-open, so a collection landing exactly at midnight is charged to one day and not " +
                "to both."
        }
    }

    @Test
    fun `a pass with no stage is refused rather than sweeping for ever doing nothing`() {
        assertFailsWith<IllegalArgumentException> {
            DerivedPass(Deriver.INDEX, quick, UNBOUNDED) { emptyList() }
        }
    }

    @Test
    fun `a cadence of nothing is refused`() {
        assertFailsWith<IllegalArgumentException> {
            DerivedPass(Deriver.INDEX, Duration.ZERO, UNBOUNDED) {
                listOf(Scripted(owed = 0, settles = 1, spends = false).stage())
            }
        }
    }

    @Test
    fun `no pass at all is a driver that returns rather than one that waits`(): Unit = runBlocking {
        // A guard on this class's contract rather than a stack anybody can bring up: the
        // composition root always schedules recognition, because identity needs no credential. What
        // it buys is that a driver with nothing to drive says so instead of sitting in a scope that
        // completes at once and looks like one that works.
        val returned = withTimeoutOrNull(PATIENCE) {
            DerivedPasses(FreeExclusion(), emptyList(), FakeSpendLedger(), fixed()).run()
            true
        }

        assertTrue(returned == true, "A driver with no pass to run did not return.")
    }

    /** One sweep of [pass] against [ledger], on the fixed day every case here runs in. */
    private suspend fun sweepOnce(pass: DerivedPass, ledger: FakeSpendLedger): Boolean =
        DerivedPasses(FreeExclusion(), emptyList(), ledger, fixed()).sweep(pass)

    private fun fixed(): Clock = Clock.fixed(MIDDAY, ZoneOffset.UTC)

    private fun driver(
        vararg passes: DerivedPass,
        ledger: FakeSpendLedger = FakeSpendLedger(),
        clock: Clock = Clock.fixed(MIDDAY, ZoneOffset.UTC),
    ) = DerivedPasses(FreeExclusion(), passes.toList(), ledger, clock)

    /**
     * A one-stage pass, allowed more than anything in this file can spend unless a test says
     * otherwise.
     *
     * [UNBOUNDED] rather than no allowance at all, because every pass has one. The tests taking
     * this default assert what a drain does when money is not the thing stopping it, so the
     * allowance has to be out of reach for them to keep asserting exactly that.
     */
    private fun pass(stage: Scripted, allowance: DayAllowance = UNBOUNDED) =
        DerivedPass(Deriver.INDEX, quick, allowance) { listOf(stage.stage()) }

    /**
     * A stage that settles a stated number of records per batch, and costs a stated amount.
     *
     * [settles] of zero is the case that matters most: a batch that did work and settled nothing,
     * which is what an unread window leaves behind. The counters live here rather than in the
     * [PassStage] it hands out, so one of these can be read after the driver has run it.
     *
     * [spends] says whether the driver should gate this stage on the day, which is the one thing
     * the driver knows about a stage. False is the faithful default: two of the real stages cannot
     * spend at all. What a batch *costs* is not here, because a batch does not report one — the
     * bill reaches the bound through the store, which [FakeSpendLedger] stands in for.
     */
    private class Scripted(
        var owed: Int,
        private val settles: Int,
        private val label: String = "scripted",
        private val spends: Boolean = false,
        private val fails: Fails? = null,
        /**
         * Called after each batch with this stage's label and how many it has done.
         *
         * One hook rather than two, which is also what keeps this under the parameter cap: the
         * cases that record the order the stages ran in and the cases that move the day's spend
         * part-way through a drain both want the same thing — to be told a batch happened.
         */
        private val onBatch: (String, Int) -> Unit = { _, _ -> },
    ) {

        var batches = 0
            private set

        /** What the driver allowed on each batch, in order — false once a day is spent. */
        val allowed = mutableListOf<Boolean>()

        fun stage() = PassStage(
            label = label,
            batch = { maySpend ->
                allowed += maySpend
                // **Both of these really suspend, and the tests above rest on it.** A lambda
                // that only looks suspending never reaches a suspension point, so a driver
                // looping over it cannot be cancelled and no timeout around it can fire — which
                // would turn the two assertions written against an unbounded loop into a build
                // that hangs instead of one that reports. Every real stage does I/O per batch,
                // so yielding is the faithful shape as well as the testable one.
                yield()
                batches++
                onBatch(label, batches)
                fails?.takeIf { batches >= it.onBatch }?.let { throw it.with }
                val settled = minOf(settles, owed)
                owed -= settled
                Batched(said = "$settled settled")
            },
            owed = {
                yield()
                owed
            },
            spends = spends,
        )
    }

    /**
     * An `Error` thrown on purpose, named so that a stack trace in the build log reads as scripted.
     *
     * The real one this stands for is an exhausted heap, which a single very large conversation
     * read into memory can plausibly cause.
     */
    /**
     * What a stage throws, and which of its batches throws it.
     *
     * One value rather than two parameters because a throwable with no batch to throw it on says
     * nothing. The batch number counts from one and defaults to the first: a failure on batch one is a
     * stage with no paid batches behind it, which is exactly the shape that cannot see whether the
     * ones behind it were charged for — so the case that matters most needs saying explicitly.
     *
     * A `Throwable` and not an `Exception`, so that a stage can also throw the thing the loop's own
     * catch does not cover.
     */
    private class Fails(val with: Throwable, val onBatch: Int = 1)

    private class ScriptedError : Error("a scripted Error: the sibling pass must keep sweeping")

    /** Nothing else is running anything, which is the ordinary case. */
    private class FreeExclusion : Exclusion {
        override suspend fun whileHolding(deriver: Deriver, work: suspend () -> Unit): Boolean {
            work()
            return true
        }
    }

    /** Somebody else holds these, so a caller is refused rather than queued. */
    private class HeldExclusion(private val held: Set<Deriver>) : Exclusion {

        val asked = mutableListOf<Deriver>()

        override suspend fun whileHolding(deriver: Deriver, work: suspend () -> Unit): Boolean {
            asked += deriver
            if (deriver in held) return false
            work()
            return true
        }
    }

    private companion object {
        /**
         * How long a loop test waits for the batches it is counting.
         *
         * Generously, because what it is waiting on is coroutine scheduling under a shared
         * machine rather than anything this code does — a tight bound here would be a test that
         * fails on a busy continuous-integration runner and nowhere else.
         */
        val PATIENCE = 5.seconds

        /**
         * An allowance no test in this file approaches by accident.
         *
         * For the cases that assert something other than the bound. Naming it is what keeps a
         * reader from mistaking the number for part of what they assert.
         */
        val UNBOUNDED = DayAllowance(inputTokens = 1_000_000, outputTokens = 1_000_000)

        /** A fixed instant well inside a UTC day, so a sweep cannot straddle midnight. */
        val MIDDAY: Instant = Instant.parse("2026-05-20T12:00:00Z")

        /** The day [MIDDAY] falls in, as the driver cuts it. */
        val DAY_FROM: Instant = Instant.parse("2026-05-20T00:00:00Z")
        val DAY_UNTIL: Instant = Instant.parse("2026-05-21T00:00:00Z")

        /**
         * An allowance small enough for a case to exhaust in one placed figure.
         *
         * A thousand input and a hundred output, so a case can spend one axis and leave the other
         * untouched — which is what the two single-axis cases rest on.
         */
        val ALLOWANCE = DayAllowance(inputTokens = 1_000, outputTokens = 100)
    }
}
