package io.github.ragnorrs.mainbrain.backend

import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.Exclusion
import io.github.ragnorrs.mainbrain.domain.ModelReplyUnreadable
import io.github.ragnorrs.mainbrain.domain.ModelUnavailable
import io.github.ragnorrs.mainbrain.domain.SpendLedger
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration

/**
 * Runs the derived passes, each at its own cadence and under its own exclusion.
 *
 * **One driver for every pass, because they differ only in what a batch does.** Every batch class
 * in `:domain` has the same two-function shape — do one batch, say how much is still owed — so
 * there is one loop here and the passes arrive as [DerivedPass] values. What they do not all share
 * is a watermark: the stages reading corpus records resume from the derivation table, and closure
 * resumes from the verdict it last wrote, because a commitment has no record identity to mark.
 * This loop needs neither, since what it reads from a stage is that stage's own count of what is
 * owed. What differs between them is their economics, and economics is
 * a cadence rather than a mechanism: indexing is local compute against a sidecar and can run
 * every minute, while inference spends the model budget per window and cannot.
 *
 * **Nothing here knows what a pass does.** A stage is a label and two functions, so this file
 * holds no reference to [io.github.ragnorrs.mainbrain.domain.index.Indexer] or to either recognition
 * class. That is the other half of those classes' claim to schedule nothing: they do not know
 * they are scheduled, and this does not know what it is running.
 *
 * **A sweep drains, rather than doing one batch per cadence.** A backfill lands hundreds of
 * thousands of messages at once, and a pass doing 128 of them a minute would never catch up. So
 * a sweep runs batches until nothing is owed, and the cadence governs how long it waits before
 * looking again.
 *
 * **So the spending is bounded per calendar day, because the cadence does not bound it.** A drain
 * runs until nothing is owed, which makes the interval between sweeps a statement about when one
 * starts and no statement at all about what it costs — and a single sweep over a real backlog can
 * spend the whole credit provisioned for this build, after which the answer path fails on an empty
 * balance. The answer path is what a person waits on, so it is the one that must not be starved.
 * What bounds the spending is [DerivedPass.allowance] against what the [SpendLedger] says the pass
 * has already been billed today, read before every batch of a stage that spends.
 *
 * **A day rather than a sweep, which is what makes the bound hold overnight.** A per-sweep ceiling
 * is granted afresh at every cadence, so a night costs as many ceilings as it has hours and the
 * knob that bounds the night is the cadence rather than the ceiling. A day's total is also read
 * from the store, so a restarted container does not get a fresh allowance — and it can be read
 * without that container, which is what makes an exhausted day a pause with a date on it.
 *
 * **Only a stage that spends is stopped, and it says which stage that was.** Indexing embeds
 * against a sidecar and identity matches identifiers in the store, so neither draws on the model
 * credit and neither should be held back by a day that has: [PassStage.spends] is what tells them
 * apart. It is a property of a stage rather than of a pass because recognition has one of each.
 *
 * **What protects the answer path is the pool and the allowances, each against a different claim.**
 * The store is opened with a pinned connection and a working one per pass, on top of the share
 * serving draws from, and the model provider's own limits pace inference — that is the pool's half,
 * and it bounds what a sweep takes from the store. Nothing there bounds what a sweep takes from the
 * credit, which is the allowances' half and the reason they exist. The answer path itself consults
 * no day: it is bounded per request, and still answers on a day whose allowance is spent.
 */
internal class DerivedPasses(
    private val exclusion: Exclusion,
    private val passes: List<DerivedPass>,
    private val ledger: SpendLedger,
    /**
     * The clock the day is cut from.
     *
     * **The driver owns it, because a calendar day is a scheduling notion.** The store holds
     * instants and `:domain` holds no clock, so somebody has to say which instants bound today;
     * doing it here keeps the answer the same for every stage of one sweep. Injected so a test can
     * place a sweep inside a chosen day rather than wait for one.
     */
    private val clock: Clock = Clock.systemUTC(),
) {

    private val log = LoggerFactory.getLogger(DerivedPasses::class.java)

    /**
     * Every pass, each on its own schedule, until cancelled.
     *
     * One coroutine per pass rather than one loop over all of them, because their cadences are
     * minutes apart: a shared loop would either run recognition at indexing's pace or the reverse.
     *
     * **Supervised, so the passes are siblings and not each other's hostages.** [loop] already
     * catches every `Exception` a sweep throws, which covers everything this build expects; what it
     * does not catch is an `Error`, and under a plain scope one of those escaping a child would
     * cancel the sibling on its way out. Both passes would then be dead in a process that still
     * serves and still reports healthy. Supervision is what keeps that to the one pass it happened
     * in.
     *
     * Returns immediately when given no pass to run. The composition root always schedules
     * recognition, because its identity stage needs no credential, so this is a guard on this
     * class's own contract rather than a configuration anybody can produce — a driver with nothing
     * to drive would otherwise sit in a scope that completes at once and look like one that works.
     * The caller keeps the process alive; this does not.
     */
    suspend fun run() {
        if (passes.isEmpty()) {
            log.warn(
                "No derived pass was given to the driver, so nothing will be indexed, " +
                    "recognised or judged. Indexing needs EMBEDDINGS_URL; inference needs " +
                    "ANTHROPIC_API_KEY, and the pass that judges which named conversations are " +
                    "bodies of work needs that and ADMISSION_ENABLED; the identity stage needs " +
                    "neither and runs whenever recognition is scheduled.",
            )
            return
        }
        log.info("Scheduling {}.", passes.joinToString { "${it.deriver} every ${it.cadence}" })
        supervisorScope {
            passes.forEach { pass -> launch { loop(pass) } }
        }
    }

    /**
     * Sweep, wait, sweep again, until cancelled.
     *
     * **Every failure is caught here and none is rethrown**, which is what keeps one pass's
     * trouble out of the other's way: a sidecar that is down stops indexing and leaves identity
     * and inference running. A failure costs the rest of that sweep — the watermark is already
     * advanced for whatever the sweep did finish, so the next one carries on from there — and what
     * it had already cost is charged to the day on the way past, which [sweep] does.
     *
     * Consecutive failures are counted and said, because the thing that must not happen quietly
     * is a pass failing every time: the corpus stops gaining derived material, which from outside
     * looks exactly like a corpus with nothing new in it. The count is what separates the two in
     * the log, and the lag [sweep] reports is what separates them in the store.
     *
     * **Nothing is carried between sweeps here, which is the difference a day makes.** A charge
     * written to the day binds every sweep of that day without being held anywhere: the next sweep
     * reads the same total and finds it that much higher. So a pass failing at the same cost every
     * cadence spends the day down and then stops for it, rather than being granted a fresh ceiling
     * less one deduction at every cadence — and a repeatedly-failing pass cannot ratchet itself out
     * of attempting, because what stops it is the day ending rather than a counter it moved.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun loop(pass: DerivedPass) {
        var failures = 0
        while (true) {
            try {
                sweep(pass)
                failures = 0
            } catch (failure: Exception) {
                rethrowIfCancelled(failure)
                failures++
                log.warn(
                    "The {} pass has now failed {} time(s) in a row. Nothing it had already marked " +
                        "is lost, and the next sweep resumes from there.",
                    pass.deriver,
                    failures,
                    failure,
                )
            }
            delay(pass.cadence)
        }
    }

    /**
     * One sweep of one pass: take the exclusion, drain every stage in order, release it.
     *
     * Answers whether it ran, which is `false` when another process holds the exclusion. That is
     * an ordinary outcome and not a failure — a second backend against one store is a state the
     * architecture allows — so this says so once and the caller waits out its cadence rather than
     * queueing behind it.
     *
     * The stages run in the order they were given and not concurrently. Recognition's two are the
     * reason: identity assigns the contacts that inference's findings are written against, so
     * inference running first or alongside would lose every attribute whose subject identity had
     * not reached yet.
     *
     * **The stage list is asked for once, here, and that is what makes a sweep one unit of time.**
     * A stage may close over an instant — inference's horizon does — and [drain] reads progress by
     * comparing two counts taken around a batch, which only means anything if both were taken
     * against the same cut-off. Asking the pass per stage, or per batch, would hand those counts
     * two different windows and a sliding window shedding its oldest records would read as work
     * done.
     *
     * **The day is cut once, here, for the same reason.** Every stage of one sweep is bounded
     * against the same day, so a sweep running across midnight is charged to the day it began in
     * rather than splitting its stages between two totals. The next sweep cuts the new day.
     *
     * **What a failure had already cost is charged to the day on the way past.** A failure still
     * ends the sweep, still reaches [loop]'s counter, and still arrives there as whatever it was;
     * what changes is that the figure leaves with it, written where the next sweep will read it.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun sweep(pass: DerivedPass): Boolean {
        val day = Day.around(clock.instant())
        val ran = try {
            val stages = pass.stages()
            exclusion.whileHolding(pass.deriver) {
                stages.forEach { stage -> drain(pass, stage, day) }
            }
        } catch (failure: Exception) {
            rethrowIfCancelled(failure)
            val spent = spentBeforeFailing(failure)
            // Charged even at nothing, and that is deliberate. A row saying a failure cost nothing
            // is how a reader tells a call that never landed — which was never billed — from one
            // nobody accounted for. It is also what makes a repeatedly-failing pass visible in the
            // day's total rather than only in the log.
            //
            // **The charge may not replace the failure it is recording.** It is a store write
            // inside a handler, and the commonest reason a sweep fails at all is that the store is
            // unreachable — so letting it propagate would substitute a second failure for the one
            // the caller needs to see, and lose the line below that says what the attempt cost. A
            // charge that cannot be written is said and dropped; the original is what leaves here.
            try {
                ledger.charge(pass.deriver, spent)
            } catch (uncharged: Exception) {
                rethrowIfCancelled(uncharged)
                log.warn(
                    "The {} pass was billed {} input and {} output token(s) before it failed, and " +
                        "the day could not be charged for them. That much of today's allowance is " +
                        "spent and unaccounted.",
                    pass.deriver,
                    spent.inputTokens,
                    spent.outputTokens,
                    uncharged,
                )
            }
            // Said here and not with the consecutive-failure count, because these are two figures
            // and one line reporting both as one is how an attempt's cost came to be confused with
            // a debt. This one is what the attempt spent.
            log.info(
                "The {} pass was billed {} input and {} output token(s) before it failed, and the " +
                    "day is charged for them. The next sweep reads that much less of the allowance.",
                pass.deriver,
                spent.inputTokens,
                spent.outputTokens,
            )
            throw failure
        }
        if (!ran) {
            log.info(
                "Another process is running the {} pass, so this one is leaving it alone until " +
                    "its next sweep.",
                pass.deriver,
            )
        }
        return ran
    }

    /**
     * Batches of one stage until nothing is owed, a batch settles nothing, or the day is spent.
     *
     * **The loop is bounded by the lag falling, not by the batch's own return value**, and the
     * passes give different reasons why. Indexing returns spans written, which is zero for a batch
     * of stickers that nonetheless made progress. Inference returns what it settled, which is zero
     * for a batch whose windows the model never finished reading — and those messages stay owed, so
     * a loop that only checked whether work remained would re-select them, re-read them, and pay
     * for it, on every turn of this loop for as long as the sweep lasted. Closure is the same shape
     * again: a batch that submits settles nothing until the replies arrive. Identity always settles
     * what it looked at and needs no guard, which is why a guard that holds for every stage is the
     * one worth having.
     *
     * So progress is read from outside the pass: a lag that did not fall means the batch settled
     * nothing *that the version now running owes*, and the stage is left for the next sweep. The
     * cadence becomes the backoff, which is the right one for the case this usually fires on — a
     * model that has started truncating every window will still be doing so a second later.
     *
     * **It also fires benignly on a stage whose only free work is already done.** A stage that
     * spends is run once more with its spending withheld when the day is exhausted, and what it can
     * do for nothing it does permanently and early — so from then on that batch settles nothing and
     * this reads it as a stall, beside the line saying the day stopped the stage. Stated here rather
     * than worked around, for the reason the next paragraph gives: telling the causes apart would
     * mean the driver reading a figure out of the batch, which is what this guard exists not to do.
     *
     * **It also fires once, benignly, on the sweep that collects a batch submitted before a
     * reword.** Such a collection writes its output under the version that produced it rather than
     * the one deployed by now — see [io.github.ragnorrs.mainbrain.domain.Submission.version] — so the lag
     * cannot fall across it, by design. The stage backs off a cadence and the next sweep selects
     * those records and reads them under the new wording. The warning is true as written on this
     * path too: nothing was settled that the current version owes. Telling the two causes apart
     * would mean the driver reading a figure out of the batch, which is the thing this guard
     * exists not to do.
     *
     * **The day is read before every batch rather than once per stage, and only for a stage that
     * spends.** Once per stage would let a drain that ran for an hour spend arbitrarily past the
     * allowance it checked at the start, which is the shape a backfill has. Reading it per batch
     * costs one indexed query per batch and bounds the overshoot at a single batch — the one that
     * crosses the line completes, because nothing prices a batch before it runs. A stage that
     * spends nothing never reads it at all, so an exhausted day leaves indexing and identity
     * running.
     *
     * **Three of the four reasons to stop are in the loop's condition and the fourth cannot be**,
     * because an exhausted day stops the stage *after* one more batch rather than before it: the
     * figure is read at the top, the batch runs with its spending withheld, and the loop leaves
     * from the bottom. Which reason fired is said either inside the loop (a stall, or work left
     * with a provider) or after it (the backlog cleared, or the day). A lag of zero is the only
     * success; a stall is recorded as it happens, because only the batch that stalled can say what
     * it did; and anything else still owed is the day, which [reportTheDayStopped] accounts for.
     */
    private suspend fun drain(pass: DerivedPass, stage: PassStage, day: Day) {
        var owed = stage.owed()
        if (owed == 0) {
            log.debug("Nothing is owed to {}.", stage.label)
            return
        }
        log.info("{} is {} record(s) behind.", stage.label, owed)
        var stalled = false
        var awaiting = 0
        var spent: TokenUsage? = null

        while (owed > 0 && !stalled && awaiting == 0) {
            spent = exhausted(pass, stage, day)
            // **An exhausted day stops the spending, not the stage.** The batch still runs, once,
            // with the spending withheld — because the half of a step that writes back what a
            // submission already bought asks the provider for nothing, and skipping it would leave
            // findings paid for and unwritten until the allowance refreshed. Long enough and the
            // submission is abandoned: the work lost and its real cost recorded as nothing, which
            // understates the very day that stopped it.
            val did = stage.batch(spent == null)
            awaiting = did.awaiting
            val left = stage.owed()
            // A batch waiting on a provider is not a batch that stalled, and the guard is what
            // says so: such a batch settles nothing by design, so tested the other way round every
            // submission would be reported as a stall.
            stalled = awaiting == 0 && left >= owed
            reportTheBatch(stage, did, left, awaiting, stalled)
            if (!stalled && awaiting == 0) owed = left
            // One collecting batch is all an exhausted day gets: a stage holds at most one
            // submission per stage, so there is never a second to read, and going round again
            // would ask the store for a figure that cannot have moved.
            if (spent != null) break
        }
        when {
            owed == 0 -> log.info("{} has caught up.", stage.label)
            spent != null -> reportTheDayStopped(stage, pass.allowance, spent, day, owed)
            else -> Unit
        }
    }

    /**
     * What this pass has spent today, where that is already enough to stop [stage], and `null`
     * otherwise.
     *
     * **A figure or nothing, rather than a boolean, because the line that reports a stop needs the
     * figure.** Answered as a boolean it would have to be read twice — once to decide and once to
     * report — and two reads of a moving total can disagree, so the line would name a number the
     * decision was not taken on.
     *
     * **A stage that cannot spend is not asked at all.** The read is a query, so a pass of free
     * stages would make one per batch to learn something that cannot apply to it; and the answer
     * for such a stage has to be "keep going" whatever the day holds, which is what leaves indexing
     * and identity running on a day whose allowance is gone.
     */
    private suspend fun exhausted(pass: DerivedPass, stage: PassStage, day: Day): TokenUsage? {
        if (!stage.spends) return null
        val spent = ledger.spentBetween(pass.deriver, day.from, day.until)
        return spent.takeIf { pass.allowance.exhaustedBy(it) }
    }

    /**
     * What one batch did, in the terms the three outcomes differ by.
     *
     * Lifted out of the loop rather than written inline, because three outcomes with a line each
     * is most of what a reader has to hold while following the loop's own reasons to stop —
     * and the two are independent: which line is written says nothing about whether to go round
     * again.
     */
    private fun reportTheBatch(stage: PassStage, did: Batched, left: Int, awaiting: Int, stalled: Boolean) {
        when {
            awaiting > 0 -> log.info(
                "{}: {}. {} record(s) to go, and {} piece(s) of work are with the provider — " +
                    "the next sweep reads them.",
                stage.label,
                did.said,
                left,
                awaiting,
            )

            stalled -> log.warn(
                "{} settled nothing of the {} record(s) it still owes, so it is left until " +
                    "the next sweep rather than asked again now. The last batch: {}.",
                stage.label,
                left,
                did.said,
            )

            else -> log.info("{}: {}. {} record(s) to go.", stage.label, did.said, left)
        }
    }

    /**
     * Why a drain stopped with records still owed, when what stopped it was the money.
     *
     * **It names which axis ran out and when the allowance refreshes.** A pause whose end nobody
     * can state reads as a stall, and the two axes are priced an order of magnitude apart — so a
     * line reporting only that the day was spent would leave whoever reads it unable to say which
     * allowance to raise, or whether to wait.
     */
    private fun reportTheDayStopped(
        stage: PassStage,
        allowance: DayAllowance,
        spent: TokenUsage,
        day: Day,
        owed: Int,
    ) {
        log.info(
            "{} stopped on the day's allowance rather than on its backlog: {}. {} record(s) are " +
                "still owed, unmarked, and the next sweep resumes from the same watermark. The " +
                "allowance refreshes at {}.",
            stage.label,
            allowance.whatRanOut(spent),
            owed,
            day.until,
        )
    }

    /**
     * What a failed batch had already cost, where the failure can say.
     *
     * **The two model failures carry a figure and nothing else does.** Both are billed for what the
     * provider read before it gave up, which is why they carry one at all; a store that is down or
     * a sidecar that refused costs nothing, and reading a cost off those would be inventing one.
     *
     * **The whole usage and not the output half**, because the day is bounded on both axes. Input
     * is the larger half of what a reading costs, so a charge carrying only output would understate
     * a failed attempt by most of its bill.
     *
     * The cause is read as well as the failure itself, because a pass is entitled to wrap what it
     * could not handle and a figure one layer down is still the figure. One level only: a chain
     * walked to its root would start charging a day for whatever an unrelated failure happened to
     * be wrapping.
     *
     * **The larger of the two and not the first of them**, compared on the axis the reading spends
     * most of — a model failure carrying nothing is the ordinary shape, because a call that never
     * landed was not billed, and taking the first figure found would stop at that zero and never
     * read the cause, where the figure is.
     */
    private fun spentBeforeFailing(failure: Throwable): TokenUsage {
        val own = charged(failure)
        val wrapped = failure.cause?.let { charged(it) } ?: TokenUsage.NONE
        return if (wrapped.inputTokens + wrapped.outputTokens > own.inputTokens + own.outputTokens) {
            wrapped
        } else {
            own
        }
    }

    private fun charged(failure: Throwable): TokenUsage = when (failure) {
        is ModelUnavailable -> failure.usage
        is ModelReplyUnreadable -> failure.usage
        else -> TokenUsage.NONE
    }

    /**
     * Honour a cancellation that is this driver's, and treat one that is not as a failure.
     *
     * The same hazard the ingest scheduler documents at length: a client library raising its own
     * timeout inside a batch throws `CancellationException` too, and rethrowing that would end
     * [loop] with no report. Our own cancellation is the one that leaves this coroutine inactive.
     *
     * The direction that is asserted is the foreign one, because it is the only one with a
     * consequence to observe. [loop]'s `delay` is outside the `try`, so a cancellation of this
     * coroutine ends the loop whether this rethrows it or not; what rethrowing buys there is only
     * that a shutdown is not reported as a pass that failed.
     */
    private suspend fun rethrowIfCancelled(failure: Throwable) {
        if (failure is CancellationException && !currentCoroutineContext().isActive) throw failure
    }
}

/**
 * One pass, as the driver sees it: which exclusion it takes, how often it runs, and what it does.
 *
 * [deriver] is the exclusion's key, and the name on every line the driver itself logs — a stage
 * names itself by its own label. It is the watermark's own notion of a pass, which is what makes
 * both correct: the stages sharing a [Deriver] are exactly the stages that would double-derive
 * each other's records.
 *
 * Several stages rather than one because recognition is two — identity, then inference — and they
 * are one pass rather than two. They share a watermark key, they share an exclusion, and the
 * second depends on the first having run.
 *
 * [stages] is a function and not a list because a stage may be dated: inference takes the recency
 * horizon as an instant and holds no clock of its own, so somebody has to read one, and a sweep is
 * the unit that must read it exactly once. The driver calls this when a sweep starts.
 *
 * [allowance] is what this pass may spend on the model in a calendar day, and it is given for every
 * pass rather than only for the one that spends — an allowance asked of each keeps this class
 * ignorant of which is which, and a pass whose stages all report [PassStage.spends] as false never
 * reads it. Which stages spend is the stage's own property for the same reason it is not the
 * pass's: recognition has one of each.
 */
internal class DerivedPass(
    val deriver: Deriver,
    val cadence: Duration,
    val allowance: DayAllowance,
    val stages: () -> List<PassStage>,
) {
    init {
        require(stages().isNotEmpty()) { "A $deriver pass with no stage to run would sweep for ever doing nothing." }
        require(cadence.isPositive()) { "A cadence of $cadence would sweep $deriver without pause." }
    }
}

/**
 * One batch-shaped thing a pass drains: what to do once, and how far behind it is.
 *
 * Two functions and a label rather than a type the passes implement. The passes are in `:domain`
 * and know nothing of being scheduled, which is a property worth keeping — an interface here for
 * them to satisfy would be this module reaching into that one to say *you are a scheduled thing*,
 * and the composition root can close the gap with two lambdas instead.
 *
 * @property label how this stage names itself in a log, for whoever reads one.
 * @property batch do one batch, and say what it did.
 * @property owed how many records are still owed, in full rather than up to a limit. The driver
 *   reads a fall in this as progress, so a bounded count would stall it or spin it.
 * @property spends whether this stage can put anything on the model bill. The one thing the driver
 *   does need to know about a stage, because an exhausted day must stop the stage that spends and
 *   leave the ones that cannot: embedding against a sidecar and matching identifiers in a store are
 *   free, and holding them back would cost the corpus its retrievability to save money neither was
 *   going to spend. **Not defaulted**, so a stage that acquires a bill and forgets to say so fails
 *   to compile rather than being exempted from the bound in silence — the symptom of which is an
 *   emptied credit, which is the event this whole mechanism exists to prevent.
 */
internal class PassStage(
    val label: String,
    val batch: suspend (Boolean) -> Batched,
    val owed: suspend () -> Int,
    val spends: Boolean,
)

/**
 * What one batch did, as the driver needs it: a line for the log, and the bill.
 *
 * Two values with two readers: the line goes to the log and the outstanding count to the stall
 * test.
 *
 * **What a batch cost is not among them, because the driver does not read a batch's bill.** The
 * sweep that commits a submission is not the sweep that reads it, so a figure handed over here
 * would charge one sweep for what another decided. What bounds the spending is the day's own total,
 * read out of the store where the collections recorded it — so the bill reaches the bound by being
 * written down rather than by being passed along.
 *
 * @property said what the batch did, for whoever reads a log.
 */
internal class Batched(
    val said: String,
    /**
     * How many pieces of work this batch left with the provider, waiting to be read.
     *
     * **Zero is not the only honest answer to "did this batch make progress?", and this is what
     * tells the two apart.** A batch that hands work to a provider's batch endpoint settles nothing
     * and leaves the backlog exactly where it was — which, to a driver reading only the count, is
     * indistinguishable from a batch that tried and failed. One is the pass working, with the
     * replies arriving next sweep; the other is the pass stuck. [DerivedPasses.drain] reads a
     * backlog that did not fall as a stall, so without this a working pass would be reported as a
     * broken one on every sweep, and the warning that means something would stop meaning it.
     *
     * Zero for every stage that does its work within the batch, which is all of them but one.
     */
    val awaiting: Int = 0,
)

/**
 * The calendar day a sweep is charged to, as the half-open span of instants that bound it.
 *
 * **In UTC, and not in whatever zone the process happens to run in.** The store keeps instants and
 * the bound is read as a range over them, so the only thing that has to be agreed is where the
 * boundaries fall — and a boundary read from a local zone would move with a deployment's host, or
 * twice a year under daylight saving, silently giving one day twenty-three hours of allowance and
 * another twenty-five.
 *
 * **Half-open, so consecutive days neither double-count a collection nor drop one.** Closed at both
 * ends, a collection landing exactly at midnight would be charged to both days, and the allowance
 * would be refused to a stage that had room.
 */
internal data class Day(val from: Instant, val until: Instant) {

    init {
        require(from < until) { "A day from $from until $until covers no time." }
    }

    companion object {

        /** The UTC day [moment] falls in. */
        fun around(moment: Instant): Day {
            val from = moment.truncatedTo(ChronoUnit.DAYS)
            return Day(from, from.plus(1, ChronoUnit.DAYS))
        }
    }
}

/**
 * What a pass may spend on the model in one day, on each axis the provider bills at full rate.
 *
 * **Two axes rather than one, because the provider prices them an order of magnitude apart.**
 * Output costs five times input, a cached read a tenth of it, and an hour-long cache entry twice it
 * to write — so a single allowance over their sum would be set by whichever axis moved last and
 * would misstate both.
 *
 * **What a cache write cost counts against the input axis, and that is not a detail.** The provider
 * reports `input_tokens` exclusive of what it cached, so a prompt that caches well moves its spend
 * out of that figure and into the write counter — at twice the input rate, the most expensive
 * input-side token there is. An allowance reading fresh input alone would therefore loosen itself
 * exactly as the caching it is meant to account for started working.
 *
 * **A cache read is recorded and not counted.** It is billed at a tenth of input, so counting it
 * beside the rest would charge the saving to the allowance and stop a stage sooner the better its
 * caching worked. What it is for is the log: a read is how a reader tells a breakpoint that took
 * from one that did not.
 *
 * **Either one exhausting is enough to stop the stage that spends.** A prompt change can move one
 * axis a long way without touching the other — a longer instruction is input, a higher effort is
 * output — so a bound that needed both to run out would be set by whichever the change did not
 * move.
 *
 * **Not persisted, unlike the spend it is compared against, and that asymmetry is the point.** What
 * a day has cost is a fact and lives in the store; what a day is allowed is a decision and lives in
 * the configuration, so raising it is a deploy rather than an edit to a table.
 */
internal data class DayAllowance(val inputTokens: Long, val outputTokens: Long) {

    init {
        require(inputTokens > 0) { "An allowance of $inputTokens input tokens permits no reading at all." }
        require(outputTokens > 0) { "An allowance of $outputTokens output tokens permits no reading at all." }
    }

    /**
     * Whether [spent] has used up either axis.
     *
     * At or above rather than above: an allowance exactly spent is spent. The batch that crosses
     * the line is allowed to complete, because nothing prices a batch before it runs — so the
     * overshoot is bounded by one batch, and treating an exact hit as room left would make it two.
     */
    fun exhaustedBy(spent: TokenUsage): Boolean =
        inputSide(spent) >= inputTokens || spent.outputTokens >= outputTokens

    /**
     * Every input-side token billed at the input rate or above: what was read fresh, plus what
     * writing a cache entry cost.
     *
     * Added at face value rather than at their prices. A write costs twice an input token, so this
     * under-counts it — but a bound is a bound on tokens, and weighting one counter by a price the
     * provider can change would make the figure in the log something nobody could check against an
     * invoice. Under-counting the dearer axis is the safe direction for a bound to be wrong in only
     * because the other direction is to stop a stage that had room.
     */
    private fun inputSide(spent: TokenUsage): Long = spent.inputTokens + spent.cacheWriteTokens

    /**
     * Which axis ran out, and by how much, for the line that reports a stop.
     *
     * Both are given even though one is enough to stop the stage: whoever reads the line is
     * deciding which allowance to raise, and the axis that did *not* run out is what says whether
     * raising the other would buy anything.
     */
    fun whatRanOut(spent: TokenUsage): String {
        val input = inputSide(spent)
        val which = when {
            input >= inputTokens && spent.outputTokens >= outputTokens -> "both allowances are"
            input >= inputTokens -> "the input allowance is"
            else -> "the output allowance is"
        }
        return "$which spent — $input of $inputTokens input and " +
            "${spent.outputTokens} of $outputTokens output token(s), where the input figure is " +
            "${spent.inputTokens} read fresh plus ${spent.cacheWriteTokens} written to cache, " +
            "and a further ${spent.cacheReadTokens} were read from cache and are not counted"
    }
}
