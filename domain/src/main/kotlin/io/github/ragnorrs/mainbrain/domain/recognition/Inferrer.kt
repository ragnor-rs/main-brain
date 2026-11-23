package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.BatchOutcome
import io.github.ragnorrs.mainbrain.domain.Collected
import io.github.ragnorrs.mainbrain.domain.Derivation
import io.github.ragnorrs.mainbrain.domain.DerivationStateRepository
import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.EmbeddingKind
import io.github.ragnorrs.mainbrain.domain.EmbeddingProvider
import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.ModelReplyUnreadable
import io.github.ragnorrs.mainbrain.domain.ModelRequest
import io.github.ragnorrs.mainbrain.domain.ModelUnavailable
import io.github.ragnorrs.mainbrain.domain.SpendLedger
import io.github.ragnorrs.mainbrain.domain.Submission
import io.github.ragnorrs.mainbrain.domain.SubmissionRepository
import io.github.ragnorrs.mainbrain.domain.SubmissionStage
import io.github.ragnorrs.mainbrain.domain.SubmittedWindow
import io.github.ragnorrs.mainbrain.domain.TokenUsage
import io.github.ragnorrs.mainbrain.domain.entity.CollapseRepository
import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.CommitmentRepository
import io.github.ragnorrs.mainbrain.domain.entity.ContactRepository
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Event
import io.github.ragnorrs.mainbrain.domain.entity.EventRepository
import io.github.ragnorrs.mainbrain.domain.entity.FindingKind
import io.github.ragnorrs.mainbrain.domain.entity.FindingPair
import io.github.ragnorrs.mainbrain.domain.entity.Origin
import io.github.ragnorrs.mainbrain.domain.entity.Predicate
import io.github.ragnorrs.mainbrain.domain.entity.Statement
import io.github.ragnorrs.mainbrain.domain.entity.StatementRepository
import io.github.ragnorrs.mainbrain.domain.entity.Stored
import io.github.ragnorrs.mainbrain.domain.entity.Validity
import io.github.ragnorrs.mainbrain.domain.entity.ValuePair
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.MessageRepository
import io.github.ragnorrs.mainbrain.domain.record.PersonRepository
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The pass that reads commitments, events and attributes out of the corpus.
 *
 * The expensive half of recognition, and the mirror of [Reconciler]: identity is deterministic and
 * runs over everything, while this spends model tokens per window and is therefore bounded by the
 * recency horizon. Like the indexing pass and like identity, it is a plain class composing ports
 * that does one batch and says what it did — what schedules it, under which lock, is the
 * containing process's concern.
 *
 * **One selected message is one window, so selection, extraction and marking are all per record.**
 * The watermark is per record, so what comes back as owed is messages; each gets a window of its
 * own, built from the conversation holding it, with its neighbours as context. A finding is
 * therefore only ever read out of a message this sweep selected and will mark.
 *
 * **A window reaches outside the horizon for context, and nothing is read out of what it finds
 * there.** The messages around a recent one can predate the cut-off, and they are shown to the
 * model so that it can tell what the selected message refers to — but a fact stated only in one of
 * them is not written. A stretch read whole would write it, unmarked, and every later window
 * reaching back over that message would write it again; so what is given up is one reading of an
 * old fact, and what is bought is that nothing is stored without a record of having been read.
 *
 * **A window the model did not read leaves its message owed.** That is the whole reason
 * [Candidacy] is sealed: an empty window and one nothing could read look identical in the output,
 * and marking the second settles a record against an answer nobody received.
 */
// `LargeClass` is suppressed rather than satisfied, and what it is pointing at is real: this holds
// two stages — the two reading steps and the collapse — and the collapse is the one that could leave.
// Splitting it is its own change, with its own review, because what the two share is the
// submission's lifecycle and the exclusion they run under, and a split that got that seam wrong
// would be a stage that collects a submission another stage submitted. Moving the accounting types
// below out from under the rule would satisfy it and is not the answer: a hundred lines of
// declaration elsewhere leaves the class doing exactly as much.
@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")
class Inferrer(
    private val derivations: DerivationStateRepository,
    private val messages: MessageRepository,
    private val contacts: ContactRepository,
    /**
     * Read for one thing only: what the corpus calls the authors of a window's messages.
     *
     * A record port on a derived pass, which is ordinary here — this pass already reads messages
     * and conversations. It is the person *record*'s name and never the contact's, because a window
     * is rendered from what the sources said: reaching for the contact would put the identity
     * stage's merges inside the text the model reads, so a window would be rendered differently
     * depending on how far identity had got.
     */
    private val persons: PersonRepository,
    private val extractor: Extractor,
    private val derived: Derived,
    private val bounds: Bounds,
    private val submissions: SubmissionRepository,
    private val model: ModelProvider,
    /**
     * What turns two claims into vectors, for the middle tier of the collapse.
     *
     * The same port the indexing pass embeds spans through, and the same process behind it: it runs
     * locally and costs no model budget, which is the whole reason the comparison is ordered to
     * reach it before the provider. Nothing here embeds a question, so every call is a passage.
     *
     * **Nullable, because a deployment can have a model credential and no sidecar.** The two are
     * configured separately, and this pass's reading stages need only the first — so a stack without
     * a sidecar goes on classifying and restating, and the collapse has no middle tier to run. It
     * does not fall through to the model tier instead: that would turn the absence of the free
     * comparison into a bill, which is the opposite of what ordering the tiers by cost is for.
     */
    private val embeddings: EmbeddingProvider?,
    /**
     * Where a billed call that is not a submission is recorded.
     *
     * **One call in this pass is neither a submission nor free, and it is the reason this port is
     * here.** Every other model call is handed to the batch endpoint and billed against a stored
     * submission, which is what a day's spend is totalled from; the call that warms a window's
     * shared prefix is an ordinary one, so there is no submission for its bill to live on. Recorded
     * here it reaches the same total, and the bound that stops this pass can see the cost this pass
     * adds. Unrecorded it could not, and recognition would sweep all night paying for warming
     * against a day that read as barely spent.
     */
    private val spend: SpendLedger,
) {

    /**
     * One step of the pass: write what the restatements said, ask for the restatements a
     * classification called for, or classify the next windows — never more than one.
     *
     * **Three steps rather than two, because reading a window takes two asks and both are
     * batched.** A finding is classified, and then restated so that it stands on its own; the second
     * ask depends on the first, so the two can only ever be a collection apart. That makes a finding
     * take two collections rather than one, which costs wall-clock in proportion to the gap between
     * sweeps and buys the restatement half at the batch price instead of the synchronous one.
     *
     * **One step per batch, and that is what keeps a sweep from waiting on the provider.** A
     * submission is allowed an hour and sometimes most of a day, so a batch that submitted and then
     * waited would hold the pass's exclusion for that long and block every other stage of the sweep
     * behind it. The only round trip any step waits on is one per *window* — the call that warms the
     * prefix a window's restatements share, which is a turnaround for all of them together rather
     * than one per finding, and is what batching this stage left room for.
     *
     * **The restatement step goes first, and a restatement still in flight does not stop the
     * classification stage.** Finishing the work already paid for is what frees a slot and settles
     * records; and a stage held back because the *other* stage was waiting on a turnaround would
     * idle the pass through exactly the hour that batching it was meant to use. So where the
     * restatements are not ready and the classification slot is free, the next windows are classified
     * and both stages are outstanding together — which is the ordinary state of this pass, and what
     * the store's one-in-flight rule is keyed per stage to permit.
     *
     * A classification is only collected when the restatement slot is free, because what a collected
     * classification produces is a restatement submission and there would be nowhere to put it. That
     * is a step deferred by a sweep, not a stage blocked: the restatement ahead of it is read first.
     *
     * So the batch that pays is not the batch that reads, and nothing is marked by the one that
     * pays: the work stays owed until the replies are in. The driver is told as much, because a
     * backlog that does not fall is this pass working rather than this pass stuck.
     *
     * More than one number comes back, because three of them are the ways this pass fails quietly
     * and one is what it spent. `:domain` logs nothing, so a pass that returned only its progress
     * would hide the findings it dropped and the windows it never read behind a number that looked
     * like success — and would leave the only thing in this build that costs money unreported.
     */
    suspend fun inferBatch(maySpend: Boolean = true): Inference {
        val derivation = derivation()
        val restating = submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.RESTATEMENT)
        if (!maySpend) return collectOnly(restating)
        return if (restating == null) {
            classifyOrCollect(derivation)
        } else {
            // Null only where the provider has not finished and the submission is still worth
            // waiting for, which is the one case another stage may use the sweep for.
            collectRestatements(restating)?.let { alsoOutstanding(it) }
                ?: submitWhileWaiting(derivation, restating)
        }
    }

    /**
     * The half of a step that costs nothing: write back what a submission already bought.
     *
     * **Reached when the day's allowance is gone, and it is not the same as doing nothing.** A
     * restatement collection asks the provider for nothing — the reply is already paid for — and
     * what it does is write the findings and settle the records they came from. Skipped entirely,
     * those findings would sit bought and unwritten until the allowance refreshed, and a submission
     * left in flight long enough is abandoned: the work lost *and* its real cost recorded as
     * nothing, which understates the very day that stopped it.
     *
     * **A classification collection is not taken, and that is the whole reason this is a separate
     * path rather than a flag passed down.** Reading one submits the restatements it found in the
     * same step and settles the classification against them, so there is no way to take the free
     * half of it: stopping after the read would settle a classification whose findings were never
     * asked about, and they would be lost rather than deferred.
     *
     * What is outstanding is still reported, so the driver reads a pass that is waiting rather than
     * one that has stalled.
     */
    private suspend fun collectOnly(restating: Submission?): Inference {
        val collected = restating?.let { collectRestatements(it) }
        if (collected != null) return alsoOutstanding(collected)
        return alsoOutstanding(Inference.NOTHING.copy(awaiting = restating?.requests?.size ?: 0))
    }

    /**
     * [did], plus whatever the classification stage still has with the provider.
     *
     * **A step of one stage cannot report the other stage's work as finished.** The restatement steps
     * are reached with a classification possibly in flight — that independence is what the change to
     * two slots buys — and they know nothing about it, so left alone they report `awaiting` as zero.
     * The driver reads that figure to tell a pass that is working from one that is stuck: zero with
     * the backlog unmoved is a stall, and a submission sitting with the provider is the opposite.
     * Reported as a stall, the sweep logs a warning about a pass that is in fact waiting correctly.
     */
    private suspend fun alsoOutstanding(did: Inference): Inference {
        val classifying = submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.CLASSIFICATION)
        return if (classifying == null) did else did.copy(awaiting = did.awaiting + classifying.requests.size)
    }

    /**
     * Read back the classifications, or select and classify the next windows.
     *
     * Reached with the restatement slot free, which is what a collected classification needs: what
     * it produces is a restatement submission, and there would be nowhere to put it otherwise.
     */
    private suspend fun classifyOrCollect(derivation: Derivation): Inference {
        val classifying = submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.CLASSIFICATION)
        return if (classifying == null) {
            submitNext(derivation)
        } else {
            collectClassifications(classifying)
        }
    }

    /**
     * Get on with classifying while [restating] is still with the provider, or report the wait.
     *
     * **This is what keeps the two stages independent.** The restatement slot is taken and its reply
     * has not arrived; the classification slot may still be free, and a pass that waited anyway
     * would spend a cadence doing nothing while the thing it was waiting for needed nothing from it.
     *
     * Where the classification slot is *also* taken, there is nothing to do but say what is
     * outstanding. Both submissions are counted, because the drain loop stops on the figure and the
     * pass is waiting on both of them.
     */
    private suspend fun submitWhileWaiting(derivation: Derivation, restating: Submission): Inference {
        val classifying = submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.CLASSIFICATION)
        return if (classifying == null) {
            submitNext(derivation).let { it.copy(awaiting = it.awaiting + restating.requests.size) }
        } else {
            Inference(
                records = 0,
                written = 0,
                lost = 0,
                unread = 0,
                awaiting = restating.requests.size + classifying.requests.size,
            )
        }
    }

    /**
     * Select the next windows, ask for every classification at once, and remember the submission.
     *
     * Nothing is marked for a window that was submitted. The work is owed until its reply is read,
     * which is the same rule a window the model never answered has always been under — the
     * difference is only that the answer now arrives in a later batch.
     *
     * **The submission is recorded before this returns and after the provider has minted it, and
     * neither order is interchangeable with the other.** Recorded first, a failure in between would
     * leave an identifier the provider never issued and the pass blocked behind it for good;
     * recorded after anything else, a crash would lose both the identifier and the money.
     */
    private suspend fun submitNext(derivation: Derivation): Inference {
        val pending = derivations.pending(derivation, RecordKind.MESSAGE, bounds.batchSize, bounds.notBefore)
        if (pending.records.isEmpty()) return Inference.NOTHING

        val windows = windowsFor(pending.records)
        val asking = mutableMapOf<String, ModelRequest>()
        val conversations = mutableMapOf<String, ExternalId>()
        // The records whose window has nothing to read. A sticker is read trivially and costs
        // nothing, so it settles without the provider being asked.
        val trivial = mutableSetOf<ExternalId>()
        windows.forEach { window ->
            val request = extractor.classification(window)
            if (request == null) {
                trivial += window.target
            } else {
                asking[window.target.nativeId] = request
                conversations[window.target.nativeId] = window.conversation
            }
        }

        // One window per selected record, so a record is either submitted or read trivially and
        // never both — which is what lets this settle the trivial ones here rather than holding
        // them behind a window that also covered them. The intersection is the guard on that
        // invariant and not a filter: a window for anything this sweep did not select would be a
        // record marked without having been selected, which is the one thing the shape of a window
        // exists to make impossible.
        val settled = pending.records.toSet().intersect(trivial)
        // Only when there is something to settle. A batch that submitted and marked nothing would
        // otherwise write an empty mark, which costs a transaction and — to anything watching the
        // order of writes — looks like the pass settling records before the work was done.
        if (settled.isNotEmpty()) {
            derivations.mark(derivation, RecordKind.MESSAGE, settled.toList(), pending.asOf)
        }
        // Every window held nothing worth asking about, so there is nothing to submit and the
        // trivially-read ones are already settled.
        return if (asking.isEmpty()) {
            Inference(records = settled.size, written = 0, lost = 0, unread = 0)
        } else {
            submit(asking, conversations, pending.records, settled.size, pending.asOf, derivation.version)
        }
    }

    /**
     * Hand over every window that was selected, and remember what was handed over.
     *
     * Split from selecting the windows because the two answer different questions — which windows
     * are owed, and recording what was committed to — and because a function doing both would have
     * three ways out, which is one more than a reader can follow.
     *
     * **Nothing is held back here, because what bounds the spend is not sized onto a submission.**
     * How many windows a submission may commit to is not a question this stage can answer: nothing
     * prices a submission before it is made, and the bill arrives a sweep or more later. What
     * bounds the spending is the day's own total, read by the driver before this stage is run at
     * all — so a stage that runs commits the batch it was given, and a day with nothing left runs
     * no stage rather than a smaller one.
     */
    // Past the cap, and every one of them is something the submission has to carry. Splitting it
    // would be two functions neither of which makes a submission.
    @Suppress("LongParameterList")
    private suspend fun submit(
        asking: Map<String, ModelRequest>,
        conversations: Map<String, ExternalId>,
        selected: List<ExternalId>,
        settled: Int,
        selectedAt: Instant,
        version: String,
    ): Inference {
        val id = model.submit(asking)
        submissions.record(
            deriver = Deriver.RECOGNITION,
            stage = SubmissionStage.CLASSIFICATION,
            kind = RecordKind.MESSAGE,
            id = id,
            requests = asking.keys.map { SubmittedWindow(it, conversations.getValue(it)) },
            records = selected,
            selectedAt = selectedAt,
            // The wording these windows were read by, carried so that the mark its collection
            // writes names it rather than whatever is deployed a sweep or more later. See
            // [Submission.version].
            version = version,
        )
        return Inference(
            records = settled,
            written = 0,
            lost = 0,
            unread = 0,
            awaiting = asking.size,
            held = 0,
        )
    }

    /**
     * One step of the collapse: read back what the model decided, or decide and ask.
     *
     * The same collect-or-submit shape the two reading stages have, and under the same rule — this
     * stage holds one submission at a time, and it collects what it last submitted before it submits
     * anything itself.
     *
     * **A step of its own, driven on its own count, and that is not a detail.** The obvious place
     * for it was the branch of the reading stages that finds nothing owed — the sweeps they have no
     * work for. But the driver reads a pass's lag before it asks for a batch at all and skips the
     * pass entirely when that is zero, so the one condition the collapse would have run under is
     * the condition under which nothing calls it. Hung there it would never have run on a caught-up
     * corpus, and an adjudication already paid for would have sat in flight with nothing able to
     * collect or abandon it. [collapseOwed] is what the driver watches instead.
     */
    suspend fun collapseBatch(maySpend: Boolean = true): Inference {
        val outstanding = submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.ADJUDICATION)
        return when {
            outstanding != null -> collectVerdicts(outstanding)
            // Collecting a verdict is free and happens above whatever the day holds; proposing
            // compares pairs and asks about the ones neither cheap tier could settle, which is the
            // half that spends. With nothing outstanding and nothing to spend, there is nothing to
            // do but say so — the pairs stay candidates, as they do on any sweep that defers them.
            maySpend -> proposeCollapses()
            else -> Inference.NOTHING
        }
    }

    /**
     * Compare the candidate pairs, merge what the vectors settle, and ask about what they do not.
     *
     * **The two cheap tiers are both here and the expensive one is not.** The exact comparison was
     * made by the store before any of these pairs existed — a claim is the key, so an identical
     * reading never became a second row — and the vector comparison is made here, locally, at no
     * model cost. What this submits is only what neither could decide.
     *
     * **The merges are applied before anything is submitted**, and the order matters for a failure
     * rather than for the result: a merge is the output of this step and the submission is work owed,
     * so a crash in between leaves rows correctly collapsed and nothing paid for. The other way
     * round it would leave a submission asking about pairs the cheap tier had already settled.
     */
    private suspend fun proposeCollapses(): Inference {
        val embedder = embeddings
        // With no middle tier nothing is even read: the candidates are of no use without a
        // comparison to make on them, and the only tier left is the one that costs money — see the
        // reason stated on the port. Where there is one, what names it is the sidecar's version
        // together with the bounds that read its vectors, because all three decide a cheap rule-out
        // — see `vectorsVersion`, which states why none of them may be left out.
        val comparedBy = embedder?.let { runningVectorsVersion(it.version()) }
        // What excludes the paid verdicts already bought, as `comparedBy` does the cheap ones. Both
        // sides of the collapse are selected under it, so the two stay in step.
        val adjudicatedBy = extractor.adjudicationVersion()
        val candidates = if (comparedBy == null) {
            emptyList()
        } else {
            derived.collapse.candidates(
                bounds.collapseBatch,
                bounds.collapseWindow,
                comparedBy,
                adjudicatedBy,
            )
        }
        val values = if (comparedBy == null) {
            emptyList()
        } else {
            derived.statements.valueCandidates(bounds.collapseBatch, comparedBy, adjudicatedBy)
        }
        // `comparedBy` is non-null exactly when `embedder` is, so testing it alone settles both and
        // lets the compiler carry the knowledge to the call below.
        if (comparedBy == null || (candidates.isEmpty() && values.isEmpty())) return Inference.NOTHING

        // One vector per distinct text and one call for both sides of the collapse: a claim can
        // appear in several candidate pairs, and the sidecar is a round trip per batch rather than
        // per text.
        val texts = (
            candidates.flatMap { listOf(it.survivor.claim, it.superseded.claim) } +
                values.flatMap { listOf(it.one, it.other) }
            ).distinct()
        // Zipped rather than indexed, so a provider returning fewer vectors than it was given
        // leaves the texts it skipped absent from the map instead of pairing one with another's
        // vector. An absent text makes its pairs incomparable, which the comparisons read as
        // nothing established rather than as a difference.
        val vectors = texts.zip(embedder.embed(EmbeddingKind.PASSAGE, texts).map { it.dense }).toMap()

        val collapse = collapsed(candidates, vectors)
        // Applied before anything is submitted. A merge is this step's output and a submission is
        // work owed, so a crash in between leaves rows correctly collapsed and nothing paid for.
        collapse.merge.forEach { derived.collapse.merge(it) }
        // The statement side reaches the model or nothing: a value pair has no merge tier, for the
        // reason `worthAsking` states.
        val worthBuying = values.filter { worthAsking(it.one, it.other, vectors) }
        val asking = collapse.ask.map { Proposed(Asked.Findings(it), extractor.adjudication(it)) } +
            worthBuying.map { Proposed(Asked.Values(it), extractor.adjudication(it)) }

        // **What the cheap tier decided, written down under the version that decided it.** A pair
        // this sweep neither merged nor committed to buy was ruled out by the vectors, and leaving
        // that unrecorded is what would make the stage read like one keeping up while it compared
        // the same page for ever: selection is oldest-first over a bound, so an unrecorded pair
        // comes back at the head of the queue on every sweep and the pairs behind it are never
        // reached. Recorded against the vector version, so a better embedding model or a moved
        // bound retires the judgement and nothing else does.
        val settled = collapse.merge.map { it.superseded.id }.toSet() +
            collapse.ask.map { it.superseded.id }.toSet()
        candidates.filter { it.superseded.id !in settled }
            .forEach { derived.collapse.keepApart(it.kind, it.survivor.id, it.superseded.id, comparedBy) }
        (values - worthBuying.toSet())
            .forEach { derived.statements.decide(it, same = false, decidedBy = comparedBy) }
        return if (asking.isEmpty()) {
            // The whole batch was decided without the provider, which is the ordinary case and the
            // property the cost ordering exists for.
            Inference(records = 0, written = 0, lost = 0, unread = 0, merged = collapse.merge.size)
        } else {
            askVerdicts(asking, collapse.merge.size)
        }
    }

    /**
     * Hand the pairs the vectors could not settle to the provider, and remember what was asked.
     *
     * **No records travel with it, and the store refuses any that tried to.** A pair is two rows of
     * the derived layer; the messages they were read out of were settled by the restatement that
     * wrote them, so there is nothing for this collection to mark and nothing it could leave owed.
     *
     * **The ceiling is not applied here**, for a reason narrower than the restatement stage's: what
     * this commits is bounded already by how many pairs a sweep compares, which is configuration
     * rather than a consequence of what a model happened to find. A sweep that wanted to spend less
     * here compares fewer pairs.
     */
    private suspend fun askVerdicts(wanted: List<Proposed>, merged: Int): Inference {
        // **Both sides of the collapse are bounded together**, because what the batch size bounds
        // is what one sweep may commit and the two candidate reads are each allowed that many. The
        // pairs beyond it are not submitted and are offered again by the next sweep's selection,
        // so they are counted out rather than dropped quietly.
        val held = (wanted.size - bounds.collapseBatch).coerceAtLeast(0)
        val asking = wanted.take(bounds.collapseBatch)
            .withIndex()
            .associate { (index, pair) -> verdictKey(index) to pair }
        val id = model.submit(asking.mapValues { (_, proposed) -> proposed.request })
        submissions.record(
            deriver = Deriver.RECOGNITION,
            stage = SubmissionStage.ADJUDICATION,
            // The kind [records] would be, for a submission that carries none. The column is not
            // nullable and the value is read by one thing only — the clause that keeps selection
            // off a record already paid for — which is driven by the record rows, and this
            // submission has none. So it names the kind the pass selects rather than inventing a
            // fifth, and nothing reads it either way.
            kind = RecordKind.MESSAGE,
            id = id,
            // No conversation, because this reads no window. What was asked about is re-read from
            // the payload, so a conversation here would be a value nothing reads — and for a pair
            // of attribute values there is no one conversation it could even name.
            requests = asking.map { (key, proposed) -> SubmittedWindow(key, payload = proposed.asked.payload()) },
            // Nothing to settle, so nothing to carry. See the stage's own documentation.
            records = emptyList(),
            // Read by nothing on this stage — a cut-off is what keeps a mark honest against an
            // edited record, and this marks nothing. The sweep's own instant rather than a second
            // clock read, so one sweep is one moment.
            selectedAt = bounds.now,
            // The comparison these pairs were put to, so that the verdicts name it when they are
            // written a sweep or more later. This stage marks no record, so the version is the
            // whole of what retires its output — a verdict stamped with a wording that did not
            // produce it is one no reword brings back.
            version = extractor.adjudicationVersion(),
        )
        return Inference(
            records = 0,
            written = 0,
            lost = 0,
            unread = 0,
            merged = merged,
            awaiting = asking.size,
            held = held,
        )
    }

    /** Read back [outstanding]'s verdicts, or report that the provider has not finished with it. */
    private suspend fun collectVerdicts(outstanding: Submission): Inference =
        when (val collected = model.collect(outstanding.id)) {
            is Collected.Waiting -> waitOrAbandon(outstanding)
            is Collected.Ready -> applyVerdicts(outstanding, collected.replies)
        }

    /**
     * Apply what [replies] decided about each pair, and settle the submission.
     *
     * **A finding pair is re-read before anything is done to it.** A submission is answered a sweep
     * or more later, and either side may have been merged away in between — by the cheap tier, or by
     * another pair that reached it first. Merging onto a reading that is itself superseded would undo
     * a collapse that had already happened, so a pair the store no longer holds is counted as a call
     * that bought nothing. A pair of *values* needs no such read and gets none: the verdict names
     * two wordings rather than two rows, so it stays meaningful however the rows have been
     * rewritten — see [apply].
     *
     * **A verdict of keeping two apart is recorded whether or not the pair survives.** The
     * identifiers are permanent, so the record is meaningful however the rows have moved, and
     * writing it is what stops the same two sentences being submitted on every idle sweep for the
     * rest of the deployment.
     *
     * The submission is settled last, after the merges and the verdicts are in, as every collection
     * here does it: a failure before that leaves it in flight and the next sweep collects the
     * retained replies again, which costs a re-read and no money.
     */
    private suspend fun applyVerdicts(outstanding: Submission, replies: Map<String, BatchOutcome>): Inference {
        var merged = 0
        var apart = 0
        var unread = 0
        var lost = 0
        // Rows a re-windowing took away. The one write in the build that removes anything, and the
        // defect it was built to fix was a row removed wrongly — so a figure nobody reported would
        // make a recurrence look exactly like a merge that worked.
        var superseded = 0
        var spent = TokenUsage.NONE
        // A comparison the model would not make, carried out whole rather than counted, exactly as
        // a declined window is. It is final — a refusal is deterministic — so the pair is recorded
        // as kept apart like a verdict, and this is the only trace that nothing was actually read.
        val declined = mutableListOf<String>()
        // Why a reply could not be used at all, which is a condition only a new build clears. An
        // ordinary verdict never reaches it: a pair the model read and called two facts is this
        // stage working, and naming it here would report a correct answer as a failure on every
        // sweep.
        var unreadable: String? = null
        // The comparison that produced these answers, not the one deployed now. This stage marks
        // no record, so the version on the row it writes is the only thing that can ever retire
        // it — see [Submission.version].
        val decidedBy = outstanding.version ?: extractor.adjudicationVersion()

        outstanding.requests.forEach { request ->
            val reply = replies.replyTo(request.key)
            // Before anything is looked up, because the reply was billed whether or not anything
            // can be done with it.
            spent += reply.usage
            val asked = askedAbout(request.payload)
            if (asked == null) {
                // A payload in a shape this build does not write, which means another build
                // submitted it. Nothing can be decided and nothing is owed — a pair is offered
                // again by selection on its own account.
                lost++
                return@forEach
            }
            when (val verdict = extractor.adjudicated(reply)) {
                is Verdict.Together -> {
                    val applied = apply(asked, decidedBy)
                    if (applied.merged) merged++ else lost++
                    superseded += applied.superseded
                }
                is Verdict.Apart -> {
                    keepApart(asked, decidedBy)
                    apart++
                }

                // Recorded like a verdict and reported like a loss, because it is both.
                is Verdict.Declined -> {
                    keepApart(asked, decidedBy)
                    apart++
                    declined += verdict.reason
                }

                // The provider did not answer. Counted and not named: an expiry is the ordinary end
                // of a batch, and the field that names a condition is for the one a deploy clears.
                is Verdict.Unanswered -> unread++

                is Verdict.Unread -> {
                    unread++
                    unreadable = verdict.reason
                }
            }
        }
        submissions.settle(outstanding.id, spent)
        return Inference(
            records = 0,
            written = 0,
            lost = lost,
            // Counted as requests, like every other stage's figure of this name, and meaning a pair
            // still undecided rather than a record still owed: this stage settles no records, so a
            // pair it could not read is simply offered again by the next sweep's selection.
            unread = unread,
            // Rows the re-windowings took away, which is this stage's only write that removes
            // anything and the only figure that could show it removing the wrong thing.
            superseded = superseded,
            declined = declined,
            usage = spent,
            merged = merged,
            apart = apart,
            // The last reason any pair gave. One and not a list, for the reason the field states:
            // it names a condition of the build, and every pair that hits it hits the same one.
            unreadable = unreadable,
        )
    }

    /**
     * Put [asked]'s two readings together, and say whether anything was.
     *
     * `false` where there was nothing left to do, which is counted as a call that bought nothing
     * rather than as a merge. For a finding pair that is a reading merged away while the comparison
     * was with the provider — merging onto a superseded row would undo the collapse that moved it.
     * A value pair cannot go that way: the verdict names two wordings rather than two rows, so it
     * is meaningful however the rows have since been rewritten.
     *
     * **A statement's two wordings are put together by re-resolving the subject, not by a write to
     * one row.** A statement is its own content, so what makes two readings one fact is that
     * resolution now treats the two values as one value — which means recording the verdict and
     * then running the whole subject and predicate through resolution again. Done here rather than
     * left for the next sweep that happens to write an attribute about that subject: a verdict
     * stored and not applied is one that was paid for and changed nothing.
     */
    private suspend fun apply(asked: Asked, decidedBy: String): Applied = when (asked) {
        is Asked.Findings -> Applied(
            merged = derived.collapse.pair(asked.kind, asked.one, asked.other)
                ?.also { derived.collapse.merge(it) } != null,
            // A finding merge supersedes through its own port, which answers nothing: what it did
            // is the merge this already reports.
            superseded = 0,
        )

        // The verdict is recorded whatever happens next, because it was paid for and is true of the
        // two wordings however the rows have moved. Whether anything *merged* is a separate
        // question, and the answer is whether there was still a subject to re-window: counting a
        // verdict as a merge would have the figure report work the store did not do.
        is Asked.Values -> {
            derived.statements.decide(
                asked.pair,
                same = true,
                decidedBy = decidedBy,
            )
            val removed = reresolve(asked.pair.subject)
            Applied(merged = removed != null, superseded = removed ?: 0)
        }
    }

    /** What applying one verdict did: whether anything merged, and how many rows it took away. */
    private class Applied(val merged: Boolean, val superseded: Int)

    /** Record that [asked]'s two readings are to be kept apart, whichever shape it is. */
    private suspend fun keepApart(asked: Asked, decidedBy: String) = when (asked) {
        // Recorded under the adjudication version, which is what makes it stand: a cheap rule-out
        // names the vector version instead, and each is retired when its own version moves.
        is Asked.Findings -> derived.collapse.keepApart(
            asked.kind,
            asked.one,
            asked.other,
            decidedBy,
        )

        is Asked.Values -> derived.statements.decide(
            asked.pair,
            same = false,
            decidedBy = decidedBy,
        )
    }

    /**
     * Re-window everything known about [subject], now that two of its values are known to be one.
     *
     * **Through [StatementRepository.recollapsed] and not `record`, because this is the one write
     * that has to reopen a window.** The forked state is the earlier wording closed where the later
     * one opened; once the two are known to be one value, the re-windowing produces a single window
     * that is still open, and `record` would keep the stored close and then remove the other row —
     * leaving the attribute reading as ended with nothing current, for good.
     *
     * The whole subject is read and offered, which is more than the one predicate the verdict
     * touched. Either would be correct — `record` and `recollapsed` both replace per subject *and*
     * predicate, so a predicate the argument does not mention is untouched — and the whole subject
     * is one query rather than two.
     *
     * The synonyms are read back rather than assumed from the verdict just written, so this applies
     * every verdict the subject has and not only the newest — which is also what makes it
     * idempotent: run twice, it writes what is already stored.
     *
     * **It answers with the rows it removed, and this is the write where that figure matters most.**
     * Re-windowing two wordings into one is the only write in the build that takes a row away, and
     * the defect it was built to fix was precisely a row being taken away wrongly — the attribute
     * left reading as ended with nothing current. Nothing else can see a recurrence: the store does
     * not report itself, and a merge that destroyed the current value looks, from every other
     * figure, exactly like a merge that worked.
     */
    private suspend fun reresolve(subject: EntityId): Int? {
        val held = derived.statements.about(listOf(subject))
        if (held.isEmpty()) return null
        val synonyms = derived.statements.synonymsOf(listOf(subject))
        return derived.statements.recollapsed(resolved(held, synonyms)).superseded
    }

    /**
     * The key one comparison is asked under: its place in the submission and nothing else.
     *
     * **A key may not carry what the request is about, and this is the stage where that bites.**
     * [ModelProvider.MAX_KEY_BYTES] bounds a key at well under two identifiers and a label, so the
     * pair travels in the payload beside it — which is the same division the restatement stage
     * makes, and for a reason it does not have: there the key names a record so that an unanswered
     * reply can be held back without the window being found, while nothing here settles a record at
     * all. So position is the whole of what this needs to be.
     *
     * Unique within the submission, which is all a key has to be: the provider echoes it back and
     * the payload is stored in the same row, so a reply reaches the pair it was asked about without
     * the key saying anything.
     */
    private fun verdictKey(index: Int) = "$VERDICT_KEY$index"

    /**
     * What one comparison was asked about, read back out of its payload.
     *
     * `null` covers a payload of another shape, one naming a kind, an identifier or a text this
     * cannot read, and a request carrying none at all — which is the shape of an adjudication
     * recorded as though it were a classification. All of them mean the same thing, a submission
     * made by a different build, and none is trusted to be impossible: a deploy between a
     * submission and its collection is exactly the gap batching introduced.
     */
    private fun askedAbout(payload: String?): Asked? {
        val fields = payload?.split(BETWEEN) ?: return null
        return when (fields.firstOrNull()?.lowercase()) {
            Extractor.ATTRIBUTE -> values(fields)
            else -> findings(fields)
        }
    }

    /** A finding pair's payload: the kind and the two identifiers. */
    private fun findings(fields: List<String>): Asked.Findings? {
        val kind = fields.takeIf { it.size == VERDICT_FIELDS }
            ?.let { named -> FindingKind.entries.firstOrNull { it.name.equals(named[0], ignoreCase = true) } }
        return kind?.let {
            try {
                Asked.Findings(it, EntityId(UUID.fromString(fields[1])), EntityId(UUID.fromString(fields[2])))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    /** A value pair's payload: the subject, and the predicate and two values encoded. */
    private fun values(fields: List<String>): Asked.Values? {
        if (fields.size != VALUE_FIELDS) return null
        // Named rather than left as subscripts, so a reader checking this against the payload that
        // writes it has the field order in words. The names are the whole of what ties the two
        // together — there is no type between them.
        val subject = fields[VALUE_SUBJECT]
        val predicate = fields[VALUE_PREDICATE]
        val one = fields[VALUE_ONE]
        val other = fields[VALUE_OTHER]
        return try {
            Asked.Values(
                ValuePair(
                    subject = EntityId(UUID.fromString(subject)),
                    predicate = Predicate.of(decoded(predicate)),
                    one = decoded(one),
                    other = decoded(other),
                ),
            )
        } catch (_: IllegalArgumentException) {
            // A UUID, a base64 field or a value pair this build cannot make sense of. All of them
            // are another build's data, and `ValuePair` raises the same type for a pair that is not
            // one — two equal values, or a blank.
            null
        }
    }

    /**
     * What one comparison is about, in the one column that travels with its key.
     *
     * **It is here and not in the key because a key is bounded** — see
     * [ModelProvider.MAX_KEY_BYTES] — and none of these fits. A finding pair is two identifiers and
     * a label; a value pair is an identifier, a relation and two pieces of free text.
     */
    private sealed interface Asked {

        fun payload(): String

        /**
         * Two readings of one finding, named by their identifiers.
         *
         * **Identifiers and not claims**, so that the reply can be matched back to rows whatever
         * has been written to them since: an identifier is assigned once and permanent, while a
         * claim is rewritten in place by a re-derivation under a better prompt. The order they
         * appear in carries nothing — which of the two survives is decided from the store when the
         * reply is read, because evidence accumulates and so which was stated first can move
         * between the asking and the answer.
         */
        class Findings(val kind: FindingKind, val one: EntityId, val other: EntityId) : Asked {

            constructor(pair: FindingPair) : this(pair.kind, pair.survivor.id, pair.superseded.id)

            override fun payload() = listOf(kind.name.lowercase(), one.value, other.value).joinToString(BETWEEN)
        }

        /**
         * Two values of one subject and predicate, named by the texts themselves.
         *
         * **The texts and not a row, because a statement has no identity to name**: it is its own
         * content. So they travel encoded — a value is free text written by a model and may hold
         * whatever separator the payload uses, and a field that could swallow the next one would
         * attribute a verdict to the wrong pair.
         */
        class Values(val pair: ValuePair) : Asked {

            override fun payload() = listOf(
                Extractor.ATTRIBUTE,
                pair.subject.value.toString(),
                encoded(pair.predicate.name),
                encoded(pair.one),
                encoded(pair.other),
            ).joinToString(BETWEEN)
        }
    }

    /**
     * One comparison about to be submitted: what to ask, and what it is about.
     *
     * The two travel together because the request is built where the pair is whole and the payload
     * is what survives into the collection. Separate from [Asked] alone because a finding pair's
     * request needs both claims, while what comes back out of a payload is two identifiers — so the
     * request cannot be rebuilt at collection time and does not need to be.
     */
    private class Proposed(val asked: Asked, val request: ModelRequest)

    /**
     * Read back [outstanding]'s classifications, or report that the provider has not finished.
     *
     * Nothing is written or marked on this path except where the classification found nothing to
     * restate — what it produces is the next submission. [submitRestatements] holds the reasoning
     * for that, and [writeRestatements] for the marking a collection further on.
     *
     * A wait here *is* the end of the sweep's inference step, unlike a wait on the restatement
     * stage: this is reached only with the restatement slot already free, so there is no other stage
     * left for the sweep to get on with.
     */
    private suspend fun collectClassifications(outstanding: Submission): Inference =
        when (val collected = model.collect(outstanding.id)) {
            is Collected.Waiting -> waitOrAbandon(outstanding)
            is Collected.Ready -> submitRestatements(outstanding, collected.replies)
        }

    /**
     * Read back [outstanding]'s restatements, or `null` where the provider has not finished with it.
     *
     * **`null` rather than a report of the wait, because a wait here is not the end of the sweep.**
     * The classification stage may have a slot free and work owed, and it does not need this
     * submission for either — so the caller is told nothing happened and gets on with that instead.
     * A submission outstanding past all patience is given up on here, which is a step of its own: it
     * frees the slot, and what it gives up is money already spent rather than work.
     */
    private suspend fun collectRestatements(outstanding: Submission): Inference? =
        when (val collected = model.collect(outstanding.id)) {
            is Collected.Ready -> writeRestatements(outstanding, collected.replies)
            is Collected.Waiting ->
                if (Duration.between(outstanding.submittedAt, bounds.now) <= bounds.patience) {
                    null
                } else {
                    waitOrAbandon(outstanding)
                }
        }

    /**
     * Keep waiting on [outstanding], or give up on it.
     *
     * **A submission has to be abandonable, or "in flight" and "in flight for ever" are the same
     * state.** Only a collection clears a stage's in-flight slot, so a submission the provider will
     * never finish — results past their retention, a batch cancelled elsewhere, a state that never
     * ends — would otherwise hold the pass for good, with nothing in this build able to clear it.
     * The deadline is generous against the provider's own ceiling of about a day, because giving up
     * early throws away a batch that was already paid for.
     *
     * What is given up is the money. The records stay owed, so the work is selected again under a
     * new submission.
     */
    private suspend fun waitOrAbandon(outstanding: Submission): Inference {
        val waited = Duration.between(outstanding.submittedAt, bounds.now)
        return if (waited <= bounds.patience) {
            Inference(records = 0, written = 0, lost = 0, unread = 0, awaiting = outstanding.requests.size)
        } else {
            submissions.abandon(outstanding.id)
            Inference(
                records = 0,
                written = 0,
                lost = 0,
                unread = outstanding.requests.size,
                abandoned = waited,
            )
        }
    }

    /**
     * What [replies] found, asked about in a submission of its own.
     *
     * **Nothing is marked here, and that is the whole of what this step changes.** A record is
     * settled when the findings read out of it have been written, which is now one collection
     * further on — so the records this classification covered are carried onto the restatement
     * submission and marked when *it* is collected. Marked here instead, a restatement submission
     * that was never answered would leave the findings lost and the records settled against them,
     * which is the one outcome nothing later could repair.
     *
     * **They are not left loose either.** An unmarked record is an owed record, and the classification
     * slot is free the moment this returns, so selection would offer the same records again and pay
     * to classify them twice. What prevents it is that they belong to an uncollected submission, which
     * selection skips — see [DerivationStateRepository.pending].
     *
     * The classification is settled last and with its own bill, which is now the whole of what the
     * collection read: no call is made here, so there is no synchronous spend to keep apart from it.
     * A failure before the settle leaves it in flight and the next sweep collects it again — the
     * provider keeps results for far longer than a cadence — which costs a re-read and no money.
     *
     * Where a classification found nothing to restate, there is no second submission to make and the
     * records are marked here, because the work on them is finished — under the version that
     * classification was made with rather than this sweep's, as every mark this pass writes at a
     * collection is. See [derivationOf].
     */
    private suspend fun submitRestatements(
        outstanding: Submission,
        replies: Map<String, BatchOutcome>,
    ): Inference {
        val reading = readClassifications(outstanding, replies)
        // **What keeps a mark inside the work that was paid for is `covered`, one record per
        // window read.** The intersection is the authorisation bound rather than a second filter:
        // a submission records which records it may mark, and this says a mark cannot reach past
        // them whatever a collection read. It cannot currently remove anything — every submitted
        // key is a selected record, because selection is what the targets were built from — so it
        // guards the shape of `windowsOf` rather than a case that arises today.
        //
        // Nothing is subtracted for the windows that were not read: an unread window contributes
        // no record, so a subtraction there would be a guard that can never fire and would read
        // like one that can.
        val markable = outstanding.records.toSet().intersect(reading.covered)
        // A finding with no record of its own to settle cannot be carried: a submission records
        // which records it may mark, and one recording none could mark nothing when it came back.
        // Only reachable where every record that was read has since left its conversation.
        val asks = if (markable.isEmpty()) emptyList() else reading.asks
        return if (asks.isEmpty()) {
            settleWithNothingToRestate(outstanding, reading, markable)
        } else {
            handOver(outstanding, reading, asks, markable)
        }
    }

    /**
     * There is no second ask to make, so the classification's records are finished here.
     *
     * The ordinary case for most windows, and not a failure: a message that states no commitment,
     * event or attribute is read and done. With nothing owed to the provider there is
     * nothing to wait for, so this marks and settles in the one step.
     *
     * It is also where a window whose findings cannot be carried ends up — findings were read, but
     * no record they could settle survived into [markable], so they are counted as lost rather than
     * asked about. Both conditions reach the same place because the same two writes finish them.
     */
    private suspend fun settleWithNothingToRestate(
        outstanding: Submission,
        reading: Classified,
        markable: Set<ExternalId>,
    ): Inference {
        if (markable.isNotEmpty()) {
            derivations.mark(derivationOf(outstanding), RecordKind.MESSAGE, markable.toList(), outstanding.selectedAt)
        }
        submissions.settle(outstanding.id, reading.spent)
        return Inference(
            records = markable.size,
            written = 0,
            lost = reading.shortfall.lost + reading.asks.size,
            refused = reading.shortfall.refused,
            unread = reading.shortfall.unread,
            declined = reading.shortfall.declined,
            repeated = reading.shortfall.repeated,
            unlisted = reading.shortfall.unlisted,
            usage = reading.spent,
        )
    }

    /**
     * Hand the restatements to the provider, and settle the classification that found them.
     *
     * **Submitted before the classification is settled, and the order is the one the single-stage
     * pass used for the same reason.** Settled first, a failure in between would leave the findings
     * with nothing to ask about them and the records owed again, so the classification would be paid
     * for twice. Submitted first, the same failure leaves the classification in flight and the next
     * sweep re-collects retained replies, which costs nothing — and the restatement submission it
     * already made is found and read on its own.
     *
     * The records the restatement may settle travel with it, carried from the classification rather
     * than recomputed, for the reason [Submission.records] exists: selection is oldest-first and
     * bounded, so re-running it later returns a different page and the records actually paid for
     * fall outside it.
     *
     * **Every candidate goes, and no bound is applied here.** These asks are what the
     * classification's purchase turned out to contain, and a bound applied at this point could not
     * defer them, only destroy them: the classification they came from is settled in the same step,
     * so nothing would offer them again, and a finding dropped here is one the corpus has already
     * been read and paid for to find.
     *
     * **This stage commits more than the one before it, which is why the spend is bounded by the
     * day rather than by either submission.** It asks once per finding where the classification
     * asks once per window, and at the higher of the two efforts, so one window's findings cost
     * more here than reading the window did. Bounded where the work is committed, that half could
     * only ever be reached transitively — through however many findings the allowed windows turned
     * out to hold, which is neither bounded nor priced. The day's total covers both halves because
     * it is read after the bill rather than before the commitment.
     */
    private suspend fun handOver(
        outstanding: Submission,
        reading: Classified,
        asks: List<RestatementAsk>,
        markable: Set<ExternalId>,
    ): Inference {
        warm(asks)
        val id = model.submit(asks.associate { it.key to it.ask.request })
        submissions.record(
            deriver = Deriver.RECOGNITION,
            stage = SubmissionStage.RESTATEMENT,
            kind = RecordKind.MESSAGE,
            id = id,
            requests = asks.map { SubmittedWindow(it.key, it.conversation, it.ask.line) },
            records = markable.toList(),
            selectedAt = outstanding.selectedAt,
            // **Carried from the classification rather than read afresh, which is the same reason
            // its records and its cut-off are.** What is settled when this comes back is the
            // records the classification read, and the wording that read them is the one that
            // submission was made under — whatever has been deployed since. Taking this sweep's
            // version instead would mark them as read by an instruction that never classified
            // them, and the reword would re-derive the whole horizon except the windows it
            // interrupted.
            //
            // The restatement half of the ask *is* composed by the build collecting, so after a
            // reword these findings are rewritten by the new wording and marked under the old.
            // That is the safe direction and the only one available: the two halves share a
            // version, so there is no string that could name one and not the other, and a mark
            // naming the older of them brings the record back to be read whole.
            version = derivationOf(outstanding).version,
        )
        submissions.settle(outstanding.id, reading.spent)
        return Inference(
            records = 0,
            written = 0,
            lost = reading.shortfall.lost,
            refused = reading.shortfall.refused,
            unread = reading.shortfall.unread,
            declined = reading.shortfall.declined,
            repeated = reading.shortfall.repeated,
            unlisted = reading.shortfall.unlisted,
            usage = reading.spent,
            awaiting = asks.size,
        )
    }

    /**
     * Everything [replies] restates, written and marked.
     *
     * **Marked against the records the classification carried onto this submission, against the
     * cut-off they were selected under, and under the version that read them.** Nothing there
     * is re-derived: selection is oldest-first and bounded, so re-running it here returns
     * a different page whenever the horizon has advanced or ingestion has landed older material
     * — and the records actually paid for then fall outside it, stay owed, and are submitted and
     * paid for a second time. The clock matters over the same span of time: a message edited
     * while the submission was outstanding has to come back owed, which it does only if the mark
     * carries the older cut-off. The version matters over that span against the other way a
     * record goes stale: an instruction reworded while this was with the provider has to bring
     * the windows it interrupted back, which it does only if the mark names the wording that
     * read them — see [derivationOf].
     *
     * **A restatement the model answered is settled whatever it answered; one the provider never
     * answered is not.** A refusal, a truncation or a reply nothing here can read costs the finding
     * rather than leaving the record unread — the message *was* classified, and offering it again
     * would re-classify and re-restate it for the sake of one sentence. Which figure the finding
     * lands in is `readRestatements`'s to decide, and they are not interchangeable. A request the
     * provider returned no
     * result for is the opposite case and has to be, because the whole submission arrives that way
     * when a batch expires: the provider ends it, everything it had not finished comes back
     * unanswered, and marking on that would settle records against findings nobody ever read. Those
     * records stay owed, which costs a re-classification and loses nothing.
     *
     * **The two stages divide [BatchOutcome.Unreadable] differently, and that is deliberate.** A
     * classification nothing can read leaves its record owed — [Extractor.candidacy] maps it
     * alongside the unanswered one — because giving it up loses everything that message holds, over
     * a value the next deploy may well name. What is given up here is one finding out of a message
     * that was otherwise read and written, while holding it would re-classify and re-restate that
     * message on every sweep until a new build landed; selection is oldest-first, so the pass would
     * not get past it. Holding costs more than settling at this stage, which is why this one names
     * the failure and moves on.
     *
     * The limit of that is worth stating: an unreadable reply names a condition of the build rather
     * than of a request, so a submission can come back unreadable whole — and then every finding it
     * carried is given up at once.
     *
     * The submission is settled last, after the output is written and the marks are in. A failure
     * before that leaves it in flight and the next batch collects it again, which costs a re-read and
     * no money; settled first, a failure would lose the replies and leave the records owed with
     * nothing to collect.
     */
    private suspend fun writeRestatements(
        outstanding: Submission,
        replies: Map<String, BatchOutcome>,
    ): Inference {
        val reading = readRestatements(outstanding, replies)
        val written = write(reading.proposals)

        // The output first and the marks afterwards, as every pass here does it. A failure between
        // the two re-reads a window, which costs tokens and writes what is already stored; the
        // reverse order records windows as read that were never read, and no re-run revisits them.
        val marked = outstanding.records - reading.withheld
        if (marked.isNotEmpty()) {
            derivations.mark(derivationOf(outstanding), RecordKind.MESSAGE, marked, outstanding.selectedAt)
        }
        submissions.settle(outstanding.id, reading.spent)
        return Inference(
            records = marked.size,
            // The rows this batch caused, and beside it what it handed over to cause them. The two
            // are the whole point of separating them: they differ by the collapse and the overwrite
            // below, and reported as one figure neither was readable.
            written = written.stored.written,
            attempted = written.attempted,
            overwritten = written.stored.overwritten,
            collapsed = written.collapsed,
            superseded = written.stored.superseded,
            lost = reading.shortfall.lost,
            refused = reading.shortfall.refused,
            unnamed = reading.shortfall.unnamed,
            outsideVocabulary = reading.shortfall.outside,
            misshapen = reading.shortfall.misshapen,
            unanswered = reading.shortfall.unanswered,
            unextractable = reading.shortfall.unextractable,
            backdated = reading.shortfall.backdated,
            sameInstant = reading.shortfall.sameInstant,
            // Carried here too, now that a reply holds a line per obliged party and so has a
            // second line to repeat. Left out while this stage always reported none, the figure
            // would read as an instruction obeyed on every sweep.
            repeated = reading.shortfall.repeated,
            unplaced = written.unplaced,
            unread = reading.shortfall.unread,
            usage = reading.spent,
            unreadable = reading.lostTo,
        )
    }

    /**
     * How much the collapse still has to do: pairs nothing has decided, and requests with the
     * provider.
     *
     * **The driver reads a fall in this as progress, so every way a pair leaves the set has to make
     * it fall.** A merge supersedes one side; a paid verdict records the pair against the
     * adjudication version; and a vector rule-out records it too, against the vector version —
     * which is the second reason the cheap tier writes its answers down. Without that last one the
     * count would hold while the stage worked, and every sweep would be reported as a stall.
     *
     * **Counted under the same two versions the selection excludes under**, which is what keeps
     * this a reading of the same set: a count honouring a verdict the selection would re-offer
     * reports the stage caught up with pairs it is about to be handed, and one honouring fewer
     * reports a stall while it works.
     *
     * The outstanding requests are added so that a submission keeps the stage alive until it is
     * collected. Counted and not merely tested, because the driver's own stall test compares one
     * reading of this against the next.
     *
     * Zero without a sidecar, and that is the honest answer rather than a guard: the comparison the
     * candidates exist for cannot be made, so there is no work this stage could do.
     */
    suspend fun collapseOwed(): Int {
        // Built exactly as the selection builds it, bounds and all: counted under a version the
        // selection does not share, this would report the stage caught up with pairs it is about to
        // be handed — which is what the paragraph above says and what a bound left out of one of
        // the two would quietly do.
        val comparedBy = embeddings?.let { runningVectorsVersion(it.version()) } ?: return 0
        val adjudicatedBy = extractor.adjudicationVersion()
        val outstanding = submissions.inFlight(Deriver.RECOGNITION, SubmissionStage.ADJUDICATION)
        return derived.collapse.candidateCount(bounds.collapseWindow, comparedBy, adjudicatedBy) +
            derived.statements.valueCandidateCount(comparedBy, adjudicatedBy) +
            (outstanding?.requests?.size ?: 0)
    }

    /**
     * How many messages inside the horizon are still owed inference.
     *
     * The whole backlog rather than a page of it, and it matters most here. This pass is the one
     * that can settle nothing and still have work — a window the model did not read leaves its
     * message owed — so a driver watching a bounded read would loop on it, at a model call per
     * window, for as long as the sweep lasted. Watching this, it sees the count hold and stops.
     */
    suspend fun owed(): Int = derivations.owed(derivation(), RecordKind.MESSAGE, bounds.notBefore)

    /**
     * One window per record in [owed], each reading that record with its neighbours as context.
     *
     * The conversation is read whole and the context taken from it, rather than a stretch being
     * fetched per message: the context has to be the same on every run, and one assembled from
     * whichever messages happened to be owed would not be. The cost is reading a conversation per
     * batch, which is the price of context that does not depend on the backlog.
     */
    private suspend fun windowsFor(owed: List<ExternalId>): List<Window> =
        messages.conversationsOf(owed).entries
            .groupBy({ (_, conversation) -> conversation }, { (message, _) -> message })
            .flatMap { (conversation, members) ->
                windowsIn(conversation, members.map { it.nativeId }.toSet()).values
            }

    /**
     * The window each of [keys] reads in [conversation], by the identifier that names it.
     *
     * **The one place a window is built, so that a collection re-derives exactly what was
     * submitted.** A key with no window behind it is a record no longer in its conversation, which
     * is the only way a window can be lost now that it is named by the record it reads.
     *
     * The participants are named from the person records rather than carried on the submission. The
     * cost is a second read per conversation; what it buys is that nothing about a window is stored
     * twice.
     *
     * **A window re-derived here does not always render the text it was classified from**, and two
     * things move it: a message landing inside the target's reach, or an edit to one already there,
     * changes the context lines; and a name the corpus has since learnt, changed, or come to share
     * between two of this window's authors changes a label. Neither can misplace a finding — a label
     * is a function of the author record, so it is withdrawn rather than reassigned — and both can
     * cost one.
     */
    private suspend fun windowsIn(conversation: ExternalId, keys: Set<String>): Map<String, Window> {
        val whole = messages.messagesIn(conversation)
        val targets = whole.map { it.externalId }.filter { it.nativeId in keys }
        return windowsAround(conversation, whole, targets, namesOf(whole), bounds.contextReach)
            .associateBy { it.target.nativeId }
    }

    /**
     * The window every request of [outstanding] asks about, by the record that names it.
     *
     * Grouped by conversation so that each is read once, and then flattened: a key names one
     * message, and a message belongs to one conversation, so two of them cannot collide. Either
     * reading stage's keys work here — a classification's key is the record itself, which
     * [anchorOf] leaves alone.
     *
     * **Called only by the two stages that read windows.** A request naming no conversation is one
     * another build keyed differently, and it contributes no window — so its record is not covered,
     * stays owed and is offered again, which is the same answer this pass gives a stored line it
     * cannot parse. Dropped rather than thrown on: a throw would leave the submission in flight to
     * be re-collected and re-thrown on every sweep, with nothing able to clear it.
     */
    private suspend fun windowsOf(outstanding: Submission): Map<String, Window> =
        outstanding.requests.groupBy { it.windowIn() }
            .filterKeys { it != null }
            .flatMap { (conversation, asked) ->
                windowsIn(requireNotNull(conversation), asked.map { anchorOf(it.key) }.toSet()).entries
            }
            .associate { it.key to it.value }

    /**
     * What the corpus calls the authors of [whole], where it calls them anything.
     *
     * Every author of the conversation and not only the windows' own, because the read is one query
     * either way and a window's context can reach any of them. A person record the store does not
     * hold, or holds without a name, is simply absent — [Window.label] falls back to a positional
     * label, which is the ordinary case for an account that offered a handle and nothing else.
     */
    private suspend fun namesOf(whole: List<Message>): Map<ExternalId, String> =
        persons.persons(whole.mapNotNull { it.author }.distinct())
            .mapNotNull { person -> person.displayName?.let { person.externalId to it } }
            .toMap()

    /**
     * Every window of [outstanding], read against the reply that came back for it.
     *
     * **The windows are re-derived rather than remembered**, which is what the submission records
     * the conversation and the record for. A window is named by the message it reads, so that
     * identifier and the conversation are the whole of what finding it again needs — no second copy
     * of the corpus is kept anywhere, and nothing about a window can go stale except its context.
     *
     * A key with no window behind it is the one case this adds, and it is narrow: the record it
     * names has left its conversation, which nothing in this build does — the corpus removes nothing
     * and a withdrawal marks rather than deletes. Reached anyway, nothing can be attributed to it
     * and the record is simply not covered, so it stays owed. The call was paid for and bought
     * nothing, which is counted rather than hidden.
     *
     * **Nothing here calls the provider, so no figure can be lost to a throw.** Both stages are
     * submitted, and a collection is parsing: the only model call either reader makes is the one
     * that fetched the replies, before any of this. A collection that used to make a call per
     * candidate had to keep its running total alive across a failure part-way through — there is no
     * such part-way any more.
     */
    private suspend fun readClassifications(
        outstanding: Submission,
        replies: Map<String, BatchOutcome>,
    ): Classified {
        val asks = mutableListOf<RestatementAsk>()
        val covered = mutableSetOf<ExternalId>()
        val declined = mutableListOf<String>()
        var lost = 0
        var refused = 0
        var unread = 0
        var repeated = 0
        var unlisted = 0
        // Accumulated across every window the batch read, including the ones that produced
        // nothing: a refused or truncated classification is a call the provider charged for, and
        // a batch that reported only the cost of its successes would under-report by exactly the
        // calls worth investigating.
        var spent = TokenUsage.NONE

        val byTarget = windowsOf(outstanding)
        outstanding.requests.forEach { request ->
            val reply = replies.replyTo(request.key)
            // Before the window is looked for, because the reply was billed whether or not
            // anything can be done with it.
            spent += reply.usage
            val window = byTarget[request.key]
            if (window == null) {
                // The call was paid for and bought nothing.
                unread++
                return@forEach
            }
            when (val candidacy = extractor.candidacy(window, reply)) {
                // The target and nothing else. A window's context reaches messages this
                // sweep did not select, and a record marked on the strength of a window
                // that merely looked at it is a record nothing recorded having read.
                is Candidacy.Found -> {
                    asks += candidacy.asks.mapIndexed { ordinal, ask ->
                        // The window's own conversation and not the request's: a window was found,
                        // so the two are the same, and taking it from here needs no second reading
                        // of a field that is absent for a request another build keyed differently.
                        RestatementAsk(keyFor(request.key, ordinal), window.conversation, ask)
                    }
                    lost += candidacy.lost
                    refused += candidacy.refused
                    repeated += candidacy.repeated
                    unlisted += candidacy.unlisted
                    covered += window.target
                }
                // Not added to `covered`, which is the whole of what leaves it owed.
                is Candidacy.NotRead -> unread++
                // Counted as covered, which is the opposite of the case above and
                // deliberately so: the model will refuse this window again, so
                // holding it owed would re-select and re-refuse it on every sweep,
                // at a call each, with the pass stuck behind it. What it held is
                // given up — a real loss, which is why it is reported rather than
                // folded into the ones that will be retried.
                is Candidacy.Declined -> {
                    declined += candidacy.reason
                    covered += window.target
                }
            }
        }
        return Classified(
            asks,
            covered,
            Shortfall(
                lost = lost,
                unread = unread,
                // This stage decides a finding's kind and is never asked for a date, so neither
                // refusal of one can arise here.
                backdated = 0,
                sameInstant = 0,
                refused = refused,
                // This stage decides a finding's kind and is never asked for a predicate, so
                // none of the three answers to that field can arise here — including the value
                // test, which reads a value this stage never sees.
                unnamed = 0,
                outside = 0,
                misshapen = 0,
                // The one figure this stage produces and the other does not: deciding that a fact
                // is outside the list is deciding its kind, which is this stage's whole job.
                unlisted = unlisted,
                declined = declined,
                // This stage reads a window rather than a stored line, so neither of these can
                // arise here: a window the model would not answer is a declined window, and there
                // is no per-finding line to fail to parse.
                unanswered = 0,
                unextractable = 0,
                repeated = repeated,
            ),
            spent,
        )
    }

    /**
     * Every restatement of [outstanding], read against the reply that came back for it.
     *
     * The window is re-derived exactly as the classification's collection re-derives it, and the
     * candidate is parsed back out of the line the submission carried — see
     * [SubmittedWindow.payload]. A request whose window can no longer be found, or whose line no
     * longer passes the extractor's checks against it, is a finding that cannot be placed: the
     * record may have left its conversation, and a label the line names may no longer be the
     * target's. It was paid for and bought nothing, which is counted rather than hidden.
     *
     * A reply that was never read is the exception, and it is read before either of those: what the
     * provider did not answer leaves its record owed, because the alternative settles a record
     * against a finding nobody saw. It is held back by the record its key names rather than by the
     * window, so a conversation that has moved underneath cannot turn an unanswered reply into a
     * settled record.
     */
    private suspend fun readRestatements(
        outstanding: Submission,
        replies: Map<String, BatchOutcome>,
    ): Restated {
        val proposals = mutableListOf<Proposal>()
        val withheld = mutableSetOf<ExternalId>()
        val counted = Counted()
        var lostTo: String? = null
        var spent = TokenUsage.NONE

        // The records this submission may settle, by the identifier a request key names. A key
        // names the record it was asked about, so an unanswered reply can be held back without the
        // conversation having to still hold that message.
        val settleable = outstanding.records.associateBy { it.nativeId }
        val byTarget = windowsOf(outstanding)
        // Whether this submission was made by a build whose keys or reply shape this one does not
        // know. Nothing from such a submission may settle a record — see the stated reason below.
        var foreign = false
        outstanding.requests.forEach { request ->
            val reply = replies.replyTo(request.key)
            spent += reply.usage
            val window = byTarget[anchorOf(request.key)]
            val line = request.payload
            // **A key naming no record this submission carries was minted by another build**, and
            // the one before this named a stretch of conversation rather than a selected message.
            // Read before anything else, because every later branch assumes the key points at a
            // record this collection may decide about.
            val owner = settleable[anchorOf(request.key)]
            if (owner == null) {
                foreign = true
                counted.unread++
                return@forEach
            }
            // **Read before the window is, because what the provider never answered is owed
            // whether or not its window can still be found.** The whole submission arrives that
            // way when a batch expires, and marking on it settles records against findings nobody
            // read, under the cut-off they were selected with, so nothing offers them again. A
            // reply that *was* answered and cannot be read is the deliberate opposite — see the
            // stated reason on `writeRestatements`.
            if (reply is BatchOutcome.Unfinished) {
                counted.unread++
                // The one record the key names, whether or not a window can still be built for
                // it: what leaves a record owed here is the provider not having answered, and
                // that is true however the corpus has moved underneath. A stretch-shaped window
                // had to hold back the whole conversation at this point, because its key named a
                // stretch and it could not say which messages the stretch had covered.
                withheld += owner
                return@forEach
            }
            // A request with no line behind it cannot be read at all, and is the shape of a
            // restatement recorded as though it were a classification; a window goes missing when
            // its record leaves the conversation. Neither is trusted to be impossible — the column
            // permits the one and the corpus produces the other — and a reply that was answered
            // and cannot be placed is a finding paid for and lost.
            if (window == null || line == null) {
                // The reason still travels where there is one, for the same reason the extractor
                // lifts it before parsing: a stop reason this build cannot name is the one loss
                // worth investigating, and dropping it because the record had also gone would
                // hide it in exactly the case where both went wrong at once.
                lostTo = (reply as? BatchOutcome.Unreadable)?.reason ?: lostTo
                counted.lost++
                return@forEach
            }
            val restated = extractor.restated(window, line, reply)
            restated.unreadable?.let { lostTo = it }
            // Counted beside the finding rather than instead of it: a refused date leaves a claim
            // that was written and is read, so these are the figures here that do not mean a
            // reading went nowhere. As many as the reply gave up findings, because a candidate
            // binding two parties comes back as a line each and the two carry their own dates.
            restated.datesRefused.forEach(counted::countDate)
            // Lines the reply wrote that bought no finding, for the reason the classification
            // stage counts its own: the call was paid for either way, and this is the rate that
            // says whether the division by party is being obeyed.
            counted.countRepeated(restated.repeated)
            // Every finding the reply gave up, which is one per party the thing obliges — see
            // `Restatement.proposals`. A reading going nowhere is still the single case it was:
            // what names it is why the reply produced nothing at all, not which line was which.
            if (restated.proposals.isNotEmpty()) {
                proposals += restated.proposals
            } else if (counted.count(requireNotNull(restated.unused))) {
                // The one reason that gives up the record as well as the finding, and with it the
                // whole submission — see the stated reason below.
                foreign = true
                withheld += owner
            }
        }
        // **Nothing from a foreign submission settles anything.** Its own requests are already held
        // back one by one, but a submission the build changed underneath may also carry records
        // whose requests parsed — and marking those on the strength of a collection that lost the
        // rest would settle messages against findings no build ever got to make, under the cut-off
        // they were selected with, so nothing would offer them again. Giving up the whole
        // submission costs a re-classification and no money.
        if (foreign) withheld += outstanding.records
        return Restated(proposals, withheld, counted.shortfall(), lostTo, spent)
    }

    /**
     * The reply to [key], or the outcome of there being none.
     *
     * A key the provider answered nothing for is treated as a request it never finished, because
     * that is what it is. What follows from it is decided by the caller: neither stage settles a
     * record against a reply that never came. How they do it differs, because they mark on opposite
     * sets — a classification marks what it covered, so a window it never read is simply not added,
     * while a restatement marks what the submission carried less what it held back, so the records
     * have to be named to be kept owed.
     */
    private fun Map<String, BatchOutcome>.replyTo(key: String): BatchOutcome =
        this[key] ?: BatchOutcome.Unfinished(
            "the provider returned no result for this request",
            TokenUsage.NONE,
        )

    /**
     * The proposals, as the entities and statements they are.
     *
     * **This is the crossing**, and the only place in this pass where a record becomes an entity: a
     * proposal names a record, and the contact behind that record is read here. The contact is
     * whichever one the record has — not the head it may be merged into — because a merge is a
     * reversible link and following it at write time would bake it in. Facts about two accounts of
     * one human are brought together when they are read.
     */
    private suspend fun write(proposals: List<Proposal>): Written {
        if (proposals.isEmpty()) return Written.NOTHING
        // Both record references a proposal can carry, looked up in one read: who stated the thing
        // and, for a commitment, which party it obliges. Two reads would be two round trips for one
        // question about the same table, and a party is frequently also the author of some other
        // proposal in the same batch.
        val records = proposals.flatMap { listOfNotNull(it.about, it.obliged) }.distinct()
        val contactOf = contacts.of(records).associate { it.record to it.id }

        val commitments = proposals.filter { it.kind == ProposalKind.COMMITMENT }.map { proposal ->
            Commitment(
                id = EntityId.mint(),
                claim = proposal.claim,
                conversation = proposal.conversation,
                evidence = setOf(proposal.evidence),
                hedged = proposal.hedged,
                due = proposal.dated,
                statedBy = proposal.subject(contactOf),
                obligedParty = proposal.party(contactOf),
            )
        }
        val events = proposals.filter { it.kind == ProposalKind.EVENT }.map { proposal ->
            Event(
                id = EntityId.mint(),
                claim = proposal.claim,
                conversation = proposal.conversation,
                evidence = setOf(proposal.evidence),
                hedged = proposal.hedged,
                occursAt = proposal.dated,
                statedBy = proposal.subject(contactOf),
            )
        }
        val attributes = proposals.filter { it.kind == ProposalKind.ATTRIBUTE }
        val fresh = attributes.mapNotNull { proposal -> proposal.statement(contactOf) }

        // Each write says what it did to the rows, which is the whole of why this is counted here
        // rather than from the sizes above. See `Stored`.
        val findings = derived.commitments.upsert(commitments) + derived.events.upsert(events)
        val facts = writeFacts(fresh)

        return Written(
            attempted = commitments.size + events.size + fresh.size,
            stored = findings + facts.stored,
            collapsed = facts.collapsed,
            // An attribute whose subject is not yet a contact cannot be stored: a statement is
            // about an entity, and minting one here would make this a second place identity is
            // assigned. Counted rather than dropped quietly — identity runs over the whole corpus
            // and ahead of this, so a run that loses many of these is a run whose passes are out
            // of step.
            unplaced = attributes.size - fresh.size,
        )
    }

    /**
     * [fresh] resolved against everything already known about its subjects, written, and accounted.
     *
     * **What the store answers with is not what this reports, and the difference is the whole of
     * why this function exists.** A write of one subject's attributes is handed every window that
     * subject holds, under every predicate — resolution needs them, or a window would close against
     * a reading the batch could not see. So the store's own figures count rows the batch never
     * offered: read one new attribute about a contact holding ten statements and the store
     * truthfully reports ten rows replaced, which says nothing about the reading that was paid for.
     * What a reader of a sweep wants is what became of the readings, so that is what is counted
     * here, over [fresh] alone and never over what was handed to the write.
     *
     * **Counted per surviving row and not per reading**, because two readings can share a content
     * key — one value stated twice within a second, which a corpus of chat messages produces. Asked
     * per reading, both would test as surviving and the one that opened no row of its own would be
     * reported nowhere.
     *
     * The three come to exactly what was offered: a reading opens a row, replaces one already held,
     * or is folded into another. [Stored.superseded] is the one figure that cannot be expressed that
     * way — it is a row that stopped existing rather than a reading that went somewhere — so it
     * stays the store's and travels unchanged.
     */
    private suspend fun writeFacts(fresh: List<Statement>): Facts {
        if (fresh.isEmpty()) return Facts(Stored.NOTHING, collapsed = 0)
        // Resolved against every statement already held about these subjects, never against
        // the batch alone: a window closed against a reading this pass could not see would
        // leave two claims covering one instant.
        val subjects = fresh.map { it.subject }.distinct()
        val stored = derived.statements.about(subjects)
        // And against every verdict already bought about their values. A restatement under a
        // moved version words one fact differently, and compared for equality that is a
        // *changed* value — so without these the fact would read as having changed when
        // nothing changed but the words.
        val windows = resolved(stored + fresh, derived.statements.synonymsOf(subjects))
        val removed = derived.statements.record(windows).superseded

        val written = windows.map { it.key }.toSet()
        val held = stored.map { it.key }.toSet()
        // One entry per row the offered readings account for, so two readings sharing a key
        // contribute the one row between them and the other is folded.
        val rows = fresh.map { it.key }.distinct().filter { it in written }
        return Facts(
            stored = Stored(
                written = rows.count { it !in held },
                overwritten = rows.count { it in held },
                superseded = removed,
            ),
            collapsed = fresh.size - rows.size,
        )
    }

    /** The contact behind the record a proposal names, where that record has one. */
    private fun Proposal.subject(contactOf: Map<ExternalId, EntityId>): EntityId? = about?.let { contactOf[it] }

    /**
     * The contact behind the party a commitment obliges, where the window named one and identity
     * has recognised it.
     *
     * The same lookup [subject] makes, over the other record a proposal can carry. Null stays null
     * through it: a party the window could not label and a party identity has not reached are both
     * direction unknown, and neither is worth a figure of its own — what the field can speak for is
     * read off the rows it carries.
     */
    private fun Proposal.party(contactOf: Map<ExternalId, EntityId>): EntityId? = obliged?.let { contactOf[it] }

    /** An attribute as the statement it asserts, or `null` where its subject is not yet known. */
    private fun Proposal.statement(contactOf: Map<ExternalId, EntityId>): Statement? {
        val subject = subject(contactOf)
        val predicate = predicate
        return if (subject == null || predicate == null) {
            null
        } else {
            Statement(
                subject = subject,
                predicate = predicate,
                value = claim,
                // Opening where the evidence was said, with no end. Nothing closes a window but a
                // later contradicting reading, and resolution is what finds one.
                validity = Validity(from = evidence.sourceTime),
                // A model read it, which is the weakest origin there is and the reason nothing
                // presents one of these as a bare fact.
                origin = Origin.INFERRED,
                evidence = setOf(evidence),
            )
        }
    }

    /**
     * The pass and the version of itself that is current.
     *
     * The extractor's version carries the model and the prompts; the window shape is added here,
     * because a message read with more or less of its conversation around it is read differently
     * and the extractor cannot know how much it was given.
     *
     * **The leading number is the hand-raised half, and what it covers is everything about the
     * window the two computed halves cannot see.** A digest covers the prompts and `contextReach`
     * covers how much conversation goes in; what neither notices is a change to *how* the lines
     * are built — which messages are rendered, which fields they carry, and what a participant is
     * labelled. A label rule that admits a name it used to withdraw puts a party in front of the
     * model that was not there before, and a record already marked would otherwise keep a reading
     * taken without it. So this is raised whenever the render changes, by hand, because there is
     * nothing to hash: the behaviour lives in functions rather than in a string.
     *
     * **The horizon is deliberately not in it.** Widening it should re-derive the material it
     * newly admits and nothing else — putting it in the version would re-extract everything
     * already read inside the old horizon, at full model cost, for a configuration change.
     *
     * It shares [Deriver.RECOGNITION] with the identity stage and cannot collide with it: a mark
     * is per record kind, identity marks persons and conversations, and this marks messages.
     */
    private fun derivation() = Derivation(
        Deriver.RECOGNITION,
        "inference-2:${extractor.version()}/context-${bounds.contextReach}",
    )

    /**
     * What [outstanding]'s collection marks under: the version that composed it, not this sweep's.
     *
     * **A batch is collected a sweep or more after it was made**, and a reword arrives by deploy,
     * so the two coincide whenever a prompt card is deployed while this pass has work with the
     * provider. Marked under [derivation] the records would claim a wording that never read them,
     * and nothing would revisit them: the only thing that retires a mark is the version it names
     * moving, and it would be naming the current one.
     *
     * Falls back to this sweep's version for a submission that carries none, which is what a row
     * written before the version was carried reads as — today's behaviour, and reachable only for
     * a batch that was already in flight when that landed.
     */
    private fun derivationOf(outstanding: Submission) =
        outstanding.version?.let { Derivation(Deriver.RECOGNITION, it) } ?: derivation()

    /**
     * What reading a classification submission produced: the asks it calls for, and what it settles.
     *
     * [spent] is the submission's whole bill and the figure stored against it, which are the same
     * thing now that a collection makes no calls of its own. The restatements it asks for are billed
     * to the submission that carries them, which is what keeps the per-request price each stage is
     * sized against a price for that stage.
     */
    private class Classified(
        val asks: List<RestatementAsk>,
        /**
         * The records whose window was read, whatever it was read to say.
         *
         * A window's target and never its context, which is what keeps a finding to a record the
         * sweep marks. A window the model did not read contributes nothing here, so there is
         * nothing to hold back separately: unread and not-covered are the same set.
         */
        val covered: Set<ExternalId>,
        val shortfall: Shortfall,
        val spent: TokenUsage,
    )

    /**
     * One restatement to submit: what to ask, which conversation it is in, and under which key.
     *
     * The key is carried rather than derived at the point of submission because it has to survive
     * the round trip: it is what the provider echoes back, and what finds the window and the
     * candidate again a collection later. It is built where the candidate's place in its window is
     * known, which is where the classification was read.
     */
    private class RestatementAsk(val key: String, val conversation: ExternalId, val ask: Ask)

    /**
     * What one warming call cost, and the failure it raised if it did.
     *
     * Both together because a failed call is still billed for what the provider read, and the
     * caller needs the whole bill before it decides what to raise.
     */
    private class Warmed(val spent: TokenUsage, val failure: Throwable? = null)

    /**
     * What reading a restatement submission produced, before anything was written.
     *
     * [withheld] is the records held back by a restatement the provider never answered, named by
     * the key that asked about them rather than by a window that may no longer be there. They are
     * owed rather than settled, which is the distinction a batch that expires turns on: every
     * request of it comes back unanswered, and marking on that would settle records against
     * findings nobody read.
     *
     * [lostTo] is why a restatement was given up on where it was given up on for being unreadable —
     * a stop reason this build has no reader for, which only a new build clears. `null` on almost
     * every collection; carried because the alternative is a candidate counted among [lost] beside
     * the ordinary parse failures it is nothing like.
     */
    private class Restated(
        val proposals: List<Proposal>,
        val withheld: Set<ExternalId>,
        val shortfall: Shortfall,
        val lostTo: String?,
        val spent: TokenUsage,
    )

    /**
     * The ways reading a batch's windows falls short of reading all of them.
     *
     * Grouped rather than carried loose because they travel together and mean one thing — what
     * this batch did not manage — and because the alternative is a constructor long enough that
     * the next value added to it goes into the wrong slot without failing to compile.
     *
     * **One type for both reading stages, with each filling what applies to it.** They fall short in
     * overlapping but different ways — only a classification declines a whole window, only a
     * restatement can be answered and unparseable — and two types would mean two places to add the
     * next figure to and one of them forgotten.
     *
     * **Nothing is defaulted, which is the same rule stated the other way round.** A figure a stage
     * does not produce is passed as zero at the call site, so a stage that acquires one and forgets
     * to report it fails to compile rather than reporting nothing. A default here would have put
     * the omission back where this type was built to stop it: silently, one level up.
     */
    private class Shortfall(
        val lost: Int,
        val unread: Int,
        /**
         * Dates refused for standing before a commitment whose obliged party is not somebody else.
         *
         * A commitment's alone, and within that the readings whose direction is the author's own or
         * could not be settled — an obligation on another participant keeps a date already behind.
         * The restatement's alone, with [sameInstant]: the classification is never asked for a
         * date. **The two figures here that are not a reading lost** — the finding is written, with
         * its date taken off it — carried in this type regardless, because what reports them is the
         * same shortfall the rest travels in and a second carrier would be a second thing to
         * forget.
         */
        val backdated: Int,
        /** Dates refused for being the evidencing message's own instant. The restatement's alone. */
        val sameInstant: Int,
        /** Readings read perfectly and declined by a rule. Both stages produce these. */
        val refused: Int,
        /**
         * Attributes whose predicate the model would not name, answering the refusal word.
         *
         * Produced by the restatement alone: the classification decides a finding's kind and is
         * never asked for a predicate.
         */
        val unnamed: Int,
        /**
         * Attributes whose predicate fell outside the closed vocabulary.
         *
         * The restatement's alone, for the same reason. Apart from [unnamed] because that is the
         * instructed answer where no entry fits and this is the same judgement reached by
         * stretching one, so the two say different things: [unnamed] bears on the list, this says
         * whether the stretching rule is holding.
         */
        val outside: Int,
        /**
         * Attributes whose value was of a sort the predicate it arrived under does not take.
         *
         * The restatement's alone, for the reason [unnamed] and [outside] are: this stage is the
         * one that answers a predicate, so it is the only one that can answer a legal predicate
         * over the wrong sort of value. Apart from both of them because neither can see it — the
         * name was in the vocabulary and only the value was wrong.
         */
        val misshapen: Int,
        /**
         * Facts the classification named as outside the closed vocabulary, reported and not stored.
         *
         * The classification's alone: it is the stage that decides whether a thing is one of the
         * listed facts at all, so it is the only one with anything to decline on those grounds.
         *
         * **The one figure in this type that is not a shortfall**, carried here because this is
         * what the stages report through and a second carrier would be a second thing to forget. It
         * costs no restatement, loses no reading and says nothing is wrong: it is the stage saying
         * what it met.
         */
        val unlisted: Int,
        val declined: List<String>,
        /** Restatements the model would not give: refused, truncated, or paused. */
        val unanswered: Int,
        /** Restatements answered and readable whose line would not parse. */
        val unextractable: Int,
        /**
         * Lines the model wrote in one reply that bought no finding beyond the one already read.
         *
         * **Both stages produce it, and it means the same thing in each: spend that was made.** The
         * classification writes a line per thing it finds and sometimes writes one twice, and every
         * copy is asked about regardless, because a line there carries no claim. The restatement
         * writes a line per party the thing obliges, and one repeating a party already obliged — or
         * saying the same thing in the same words — is refused, which is the rate that says whether
         * the division by party is being obeyed or taken as licence to divide what was whole.
         */
        val repeated: Int,
    )

    /**
     * What writing produced: what was offered, what it did to the rows, and what could not be.
     *
     * **[attempted] and [stored] are separate because every derived write is an upsert keyed by
     * content**, so the readings a batch hands over and the rows it causes differ by however much
     * was already held or folded away. One figure covering both cannot say which, and a collapse
     * and an overwrite mean opposite things.
     *
     * [stored]'s three figures are all over the readings this batch offered, never over what a
     * write was handed — [writeFacts] states why the two differ for attributes — with the single
     * exception of [Stored.superseded], which is a row that stopped existing rather than a reading
     * that went somewhere.
     *
     * @property attempted readings handed to the writes: commitments, events, and the attributes
     *   whose subject is known. [Stored.written], [Stored.overwritten] and [collapsed] come to
     *   exactly this.
     * @property stored what became of them.
     * @property collapsed offered readings that resolution folded into another, so they are no row
     *   of their own. Zero for commitments and events, which are keyed by their claim and collapse
     *   through the model rather than through resolution.
     * @property unplaced attributes that could not be offered at all, their subject not being a
     *   contact yet.
     */
    private class Written(
        val attempted: Int,
        val stored: Stored,
        val collapsed: Int,
        val unplaced: Int,
    ) {
        companion object {
            val NOTHING = Written(attempted = 0, stored = Stored.NOTHING, collapsed = 0, unplaced = 0)
        }
    }

    /** What writing the attributes did: the store's own figures, and what resolution folded away. */
    private class Facts(val stored: Stored, val collapsed: Int)

    /**
     * The restatement stage's shortfall, accumulated as its requests are read.
     *
     * **A mutable accumulator beside the immutable [Shortfall] it freezes into, because the figures
     * are counted one request at a time and read once.** What it buys over a local apiece is that
     * [count] and [countDate] each hold a whole enumeration in one place: the mapping from a reason
     * to a figure is the thing that has to stay readable, and it belongs somewhere a reader can see
     * all of it at once rather than spread through the loop that happens to drive it. They are two
     * functions because one gives up a reading and the other gives up a date off a reading that was
     * kept, so only the first has anything to say about whether the record stays owed.
     */
    private class Counted {
        var lost = 0
        var unread = 0

        private var backdated = 0
        private var sameInstant = 0
        private var refused = 0
        private var unnamed = 0
        private var outside = 0
        private var misshapen = 0
        private var unanswered = 0
        private var unextractable = 0
        private var repeated = 0

        /**
         * Count [unused], and say whether it is the one that gives up the record too.
         *
         * **The division the branches keep is routine against total.** A reading a rule declined,
         * a model that would not answer, a model answering the refusal word, a model stretching an
         * entry and a model answering a value of a sort no entry takes fire on most sweeps and say
         * nothing is wrong, while a line nothing could parse
         * and a stop reason with no reader say a prompt and a parser have drifted apart. The
         * foreign shape is on neither side, being the one reason that leaves the record owed. Added
         * together the routine ones hide the others completely — which is the rule the
         * classification stage already holds to, one stage further on.
         *
         * Written over the whole enumeration rather than over a subset, so a reason added to it
         * fails to compile instead of falling into whichever branch happens to be last.
         */
        fun count(unused: Restatement.Unused): Boolean = when (unused) {
            // This build's own data in another build's shape, so the finding was never read rather
            // than read and discarded. The only reason that leaves the record owed.
            Restatement.Unused.FOREIGN -> {
                unread++
                true
            }

            // Read perfectly and declined by a rule: an attribute whose stored subject field the
            // label its window now gives the author no longer bears out. The placement rule shares
            // the case and cannot fire here, the stored line being re-tested against the identifier
            // its own request named.
            Restatement.Unused.REFUSED -> {
                refused++
                false
            }

            // The refusal word, which the instruction asks for where no entry fits — so it is the
            // figure that bears on the list, though not a measure of it. Routine, and its answer
            // again.
            Restatement.Unused.UNNAMED -> {
                unnamed++
                false
            }

            // A predicate the vocabulary does not hold, which on a corpus that is not English is a
            // standing share of every sweep. The same judgement as the refusal above, reached by
            // disobeying, so it is counted apart: this says whether the stretching rule holds.
            Restatement.Unused.OUTSIDE -> {
                outside++
                false
            }

            // A legal entry over a value of a sort it does not take, which is the one refusal here
            // that no answer of the model's decides: the value is tested against the relation it
            // arrived under. Routine in the sense that nothing about the build is wrong, and the
            // reading is lost rather than stored — which is the trade, since what it would have
            // stored is a fact with an open validity window that nothing would ever contradict.
            Restatement.Unused.MISSHAPEN -> {
                misshapen++
                false
            }

            // A stop reason nothing here can read, which only a new build clears. The reason itself
            // is carried by the caller, which lifts it before this is reached.
            Restatement.Unused.UNREADABLE -> {
                lost++
                false
            }

            // The model's own answer to this request, and its answer again on any retry.
            Restatement.Unused.DECLINED -> {
                unanswered++
                false
            }

            // Answered, readable, and the line would not parse — a blank predicate field included.
            // Paid for at the higher of the two efforts and bought nothing, and unlike the routine
            // branches above — the rule, the refusal word, the stretched entry, the value of the
            // wrong sort, the model that would not answer — it is this build's to fix.
            Restatement.Unused.UNEXTRACTABLE -> {
                unextractable++
                false
            }
        }

        /**
         * Count [refusal], which is a date given up and not a reading.
         *
         * Apart from [count] because the finding survives this one: there is no record to give up
         * and nothing to say about whether it stays owed. Written over the whole enumeration for
         * the same reason that one is — a case added to it fails to compile here rather than being
         * counted as whichever branch happens to be last.
         */
        fun countDate(refusal: Restatement.DateRefusal) = when (refusal) {
            // The rare one, and a ceiling on the record contradicting itself rather than a measure
            // of it: an obligation whose party the window could not settle is refused alongside the
            // author's own promises and may well have stated its timing. The enum says so.
            Restatement.DateRefusal.BEFORE_MESSAGE -> backdated++
            // The prevalent one, so it is the figure that says whether the instruction against
            // copying the message's own line is holding.
            Restatement.DateRefusal.SAME_INSTANT -> sameInstant++
        }

        /**
         * Count the lines one reply repeated.
         *
         * Taken as a total per reply rather than one call per line, because the extractor is where
         * a line is refused and a caller counting them again would be a second definition of what
         * a repeat is.
         */
        fun countRepeated(lines: Int) {
            repeated += lines
        }

        fun shortfall() = Shortfall(
            lost = lost,
            unread = unread,
            backdated = backdated,
            sameInstant = sameInstant,
            refused = refused,
            unnamed = unnamed,
            outside = outside,
            misshapen = misshapen,
            // A fact outside the list is named by the classification, which is the stage that
            // decides a finding's kind: by the time a restatement is asked for, the thing has
            // already been called an attribute and there is no kind left for this stage to give.
            unlisted = 0,
            // A whole window is declined by the classification, never here: this stage asks about
            // one finding the classification already read, so there is no stretch of conversation
            // for it to give up on and no reason it could carry out.
            declined = emptyList(),
            unanswered = unanswered,
            unextractable = unextractable,
            repeated = repeated,
        )
    }

    companion object {

        /**
         * How long a submission may stay in flight before the pass gives up on it.
         *
         * Half again the provider's own ceiling of about a day. Long enough that a batch merely
         * taking its time is never thrown away, short enough that a submission which will never be
         * answered does not hold the pass for more than a couple of cadences past that.
         */
        val DEFAULT_PATIENCE: Duration = Duration.ofHours(36)

        /**
         * Messages per batch.
         *
         * Small, and smaller than either other pass's, because a batch here fans out into model
         * calls: every message in it is one classification and then one restatement per finding it
         * states. An interrupted run also loses more here than anywhere else — the work it loses
         * was paid for in tokens.
         */
        const val DEFAULT_BATCH_SIZE = 16

        /**
         * What separates the record a window reads from a candidate's place in it, inside a
         * request key.
         *
         * A restatement asks about one finding inside a window, so its key has to name both, and a
         * classification's key is the record alone. The key is read back by splitting on this, so a
         * character that could occur inside a record identifier would find the wrong window — and
         * the identifiers the sources produce are digits, a sign and a slash, none of which is
         * this.
         * What the provider will accept is a separate question answered elsewhere: every key is
         * encoded before it is submitted, so no character here has to fit `custom_id`.
         */
        const val WITHIN = "#"

        /**
         * What separates the fields of an adjudication's payload — three for a finding pair, five
         * for a pair of values.
         *
         * A character that occurs in none of the things it joins: a kind is a lower-case word, an
         * identifier is a UUID's hex and hyphens, and a value is hex-encoded precisely so that it
         * cannot carry this. Nothing about it has to fit the provider's identifier — a payload is
         * this build's own column and never leaves the store.
         */
        const val BETWEEN = "~"

        /** A finding pair's payload fields: the kind, and the two readings. */
        const val VERDICT_FIELDS = 3

        /** A value pair's payload fields: the kind, the subject, the relation, and the two values. */
        const val VALUE_FIELDS = 5

        /**
         * Where each of those sits on the payload.
         *
         * Named rather than counted at the point of use, for the reason the classification line's
         * fields are: the names are what tie the reader to the writer, and nothing else does.
         */
        private const val VALUE_SUBJECT = 1
        private const val VALUE_PREDICATE = 2
        private const val VALUE_ONE = 3
        private const val VALUE_OTHER = 4

        /**
         * A payload field that may hold anything, made safe to put beside others.
         *
         * **A value is free text a model wrote**, so it can hold the separator the payload is split
         * on — and a field that swallowed the next one would attribute a verdict to a pair nobody
         * asked about. Hex rather than escaping, because its alphabet excludes the separator by
         * construction rather than by a rule somebody has to keep in step with it; and written out
         * here rather than taken from a library, because `:domain` carries no dependency and the
         * JDK surface it is allowed is one named type. Two characters per byte is twice the length
         * and these fields are short.
         *
         * The values are stored in full and in the clear where a person might read them, which is
         * the verdict table. This is the transient copy.
         */
        fun encoded(field: String): String = field.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            "${HEX[byte.toInt() shr NIBBLE and LOW_NIBBLE]}${HEX[byte.toInt() and LOW_NIBBLE]}"
        }

        /**
         * The inverse of [encoded].
         *
         * Raises [IllegalArgumentException] on anything it did not write, which is what the caller
         * reads as another build's payload: an odd length, or a character outside the alphabet.
         */
        fun decoded(field: String): String {
            require(field.length % PER_BYTE == 0) {
                "A hex field has $PER_BYTE characters per byte; '$field' has an odd length."
            }
            return field.chunked(PER_BYTE)
                .map { pair ->
                    val high = HEX.indexOf(pair[0])
                    val low = HEX.indexOf(pair[1])
                    require(high >= 0 && low >= 0) { "'$pair' is not a hex byte." }
                    ((high shl NIBBLE) or low).toByte()
                }
                .toByteArray()
                .toString(Charsets.UTF_8)
        }

        /** The alphabet [encoded] writes and [decoded] reads, lower case and in order. */
        private const val HEX = "0123456789abcdef"

        /** How many characters of [HEX] one byte takes, which is what [decoded] chunks by. */
        private const val PER_BYTE = 2

        /** The width of one hex character in bits, and the mask that keeps one. */
        private const val NIBBLE = 4
        private const val LOW_NIBBLE = 0xF

        /**
         * What an adjudication's key is built from, which is its place in the submission.
         *
         * A prefix rather than a bare number so that a key is never empty and reads as this build's
         * when a person looks at the table. Short, because a key is bounded — see
         * [ModelProvider.MAX_KEY_BYTES] — and because it has nothing to say.
         */
        const val VERDICT_KEY = "pair"

        /**
         * How many candidate pairs one sweep compares.
         *
         * **This is what bounds the collapse's spend**, and it is the only thing that does — the
         * day's allowance stops a stage outright rather than trimming its batch, so this is the one
         * stage whose batch size is a configured number at all. Every pair the
         * vectors leave undecided is a model call, so at most this many calls can be committed by
         * one sweep, and in practice far fewer: the vector comparison rules out the great majority
         * of pairs for nothing.
         *
         * Larger than the message batch because a pair costs two short texts to the local sidecar
         * and usually nothing else, where a message costs a classification and then a restatement
         * each. It is still small, because the stage runs on the sweeps the reading stages do not
         * want and there is no hurry about a duplicate.
         */
        const val DEFAULT_COLLAPSE_BATCH = 64

        /**
         * How far apart two readings of one fact may be first stated.
         *
         * **A bound on the candidate set and not a judgement about sameness**, which is why it is
         * generous. One obligation restated in a later message is the case this exists to admit, and
         * a conversation can return to the same promise days later; the comparison that decides
         * whether two readings are one fact is the vector one and then the model, and neither of
         * them reads this.
         *
         * It is not unbounded, because the candidate set is every pair within it: widening it grows
         * the pairs quadratically in a busy conversation, and every one of them is a local
         * embedding and a cosine. A week is long enough to catch a restatement and short enough
         * that a year of a conversation is not compared against itself.
         */
        val DEFAULT_COLLAPSE_WINDOW: Duration = Duration.ofDays(7)
    }

    /**
     * Leave each window's prefix in the provider's cache before the submission that shares it.
     *
     * **Once per window and not once per request**, which is the whole saving: the siblings of one
     * window share a prefix, so one of them writing it is enough and the rest read it. Distinct by
     * the shared region itself rather than by the record it came from — what the provider matches
     * is bytes, so two windows that rendered identically are one entry and should cost one write.
     *
     * **Concurrently, because these are round trips and the pass holds its exclusion across them.**
     * Taken one after another, a batch spanning a dozen windows would hold the exclusion for a
     * dozen turnarounds, which is most of the cost batching this stage removed. Run together they
     * cost about one turnaround. They are independent by construction: each writes its own entry
     * and none reads another's.
     *
     * **Every call's bill is collected before any failure is raised, which is the part that must
     * not be got wrong.** These are concurrent billed calls, so a failure of one leaves the others
     * already paid for — and a figure that left carrying only the failing call's usage would
     * under-report the day by everything its siblings cost. So each outcome is caught, the four
     * counters are totalled across all of them, and the failure is re-raised carrying the total.
     * Its own kind is preserved, because the two model failures mean opposite things about retrying
     * and a reply this build cannot read does not become retryable by having been raised here.
     *
     * Only the two model failures are caught, which is the whole of what the port may raise besides
     * a cancellation — and a cancellation is not something to total and carry, it is the process
     * going away.
     */
    private suspend fun warm(asks: List<RestatementAsk>) {
        val prefixes = asks.mapNotNull { it.ask.request.shared }.distinct()
        if (prefixes.isEmpty()) return
        val warmed = coroutineScope {
            prefixes.map { prefix -> async { warmOne(prefix) } }.awaitAll()
        }
        val spent = warmed.fold(TokenUsage.NONE) { total, one -> total + one.spent }
        val failure = warmed.firstNotNullOfOrNull { it.failure }
        if (failure == null) {
            // **Charged here, and only on this path.** These calls are billed and belong to no
            // submission, so nothing else in the build would ever record them. A failure takes the
            // other route: the total leaves on the exception and the driver charges it there, which
            // is the one place that can also account for a failure this pass never saw. Charging in
            // both would count the same tokens twice, against the figure a bound is read from.
            spend.charge(Deriver.RECOGNITION, spent)
            return
        }
        val said = "Warming the restatement prefix failed after ${warmed.size} call(s)."
        throw when (failure) {
            is ModelReplyUnreadable -> ModelReplyUnreadable(said, failure, spent)
            else -> ModelUnavailable(said, failure, spent)
        }
    }

    /** One warming call, as what it cost and what — if anything — went wrong. */
    private suspend fun warmOne(prefix: String): Warmed = try {
        Warmed(model.respond(extractor.warming(prefix)).usage)
    } catch (failure: ModelUnavailable) {
        Warmed(failure.usage, failure)
    } catch (failure: ModelReplyUnreadable) {
        Warmed(failure.usage, failure)
    }

    /** The key a restatement is asked under: the record its window reads, and which finding in it. */
    private fun keyFor(record: String, ordinal: Int) = "$record$WITHIN$ordinal"

    /**
     * The record identifier inside a request key. The inverse of [keyFor].
     *
     * Correct for either stage's keys: a classification's key is the bare record, which this leaves
     * alone because there is nothing to split on.
     */
    private fun anchorOf(key: String) = key.substringBefore(WITHIN)
}

/**
 * The derived stores this pass writes.
 *
 * Bundled rather than taken one by one for the reason [Corpus] is: a pass that took its whole world
 * as positional parameters is one where a port gets wired into the wrong slot and nothing fails to
 * compile. It holds ports and no state.
 */
data class Derived(
    val commitments: CommitmentRepository,
    val events: EventRepository,
    val statements: StatementRepository,
    /**
     * Where the collapse reads its candidate pairs and writes what it decided about them.
     *
     * Beside the three stores rather than inside one of them, because it spans two: a commitment and
     * an event are collapsed by one rule, and a port on either repository would be that rule written
     * twice — see [io.github.ragnorrs.mainbrain.domain.entity.CollapseRepository].
     */
    val collapse: CollapseRepository,
)

/**
 * What bounds one run of the pass: how far back it reads, how much conversation it reads each
 * selected message in, and how much it does at once.
 *
 * [notBefore] is the recency horizon, as the instant it starts at rather than a duration. The pass
 * holds no clock — a pass that read one would date its own bound, and two runs of it would select
 * different work for reasons nothing recorded. Whoever schedules it decides what the cut-off is,
 * from configuration.
 */
data class Bounds(
    val notBefore: Instant,
    /**
     * How many messages either side of a selected one go in front of the model as context.
     *
     * In [Inferrer.derivation]'s version string, because a message read with more or less of its
     * conversation around it is read differently — so changing this re-derives the corpus inside
     * the horizon rather than leaving two readings keyed alike.
     */
    val contextReach: Int = Window.DEFAULT_CONTEXT,
    val batchSize: Int = Inferrer.DEFAULT_BATCH_SIZE,
    /**
     * The instant this sweep reads as now.
     *
     * Passed in rather than read here, for the reason [notBefore] is: this pass holds no clock, so
     * that a sweep is one unit of time and a backfill read years later dates nothing to today. The
     * only thing it is used for is deciding whether an outstanding submission has been outstanding
     * too long.
     */
    val now: Instant = notBefore,
    /**
     * How long a submission may stay in flight before it is given up on.
     *
     * Generous against the provider's own ceiling of about a day, because abandoning early throws
     * away a batch that has already been paid for. What it guards is the opposite case: a
     * submission that will never be answered holding its stage's in-flight slot for ever, which
     * nothing else in this build can clear.
     */
    val patience: Duration = Inferrer.DEFAULT_PATIENCE,
    /**
     * How many candidate pairs one sweep of the collapse compares.
     *
     * Separate from [batchSize] because the two bound different work at different prices: a record
     * is a classification and then a restatement each, while a pair is two short texts to the local
     * sidecar and a model call only where the vectors cannot decide. See
     * [Inferrer.DEFAULT_COLLAPSE_BATCH].
     */
    val collapseBatch: Int = Inferrer.DEFAULT_COLLAPSE_BATCH,
    /**
     * How far apart two readings of one fact may be first stated to be compared at all.
     *
     * Not in [Inferrer.derivation]'s version string, unlike [contextReach], and the asymmetry is the
     * point: widening the context changes what is *read* out of a window and so invalidates
     * everything read under the old value, while widening this only offers more pairs to a
     * comparison that was always going to be made. Nothing stored becomes wrong when it moves.
     */
    val collapseWindow: Duration = Inferrer.DEFAULT_COLLAPSE_WINDOW,
) {
    init {
        require(batchSize > 0) { "A batch of $batchSize records is not a batch." }
        require(collapseBatch > 0) { "A batch of $collapseBatch pairs compares nothing." }
        require(!collapseWindow.isNegative) {
            "A window of $collapseWindow admits no pair, so the collapse would never compare anything."
        }
        require(contextReach >= 0) {
            "A context of $contextReach messages either side of a selected one is not a context."
        }
        require(!patience.isNegative && !patience.isZero) {
            "A patience of $patience abandons every submission before it can be answered."
        }
    }
}

/**
 * What one batch did, the ways it fell short of what it was asked, and what it cost.
 *
 * [records] is progress — how many messages are now settled. [written] is the rows that were caused,
 * [attempted] the readings handed over to cause them.
 *
 * **That those are two figures rather than one is what makes the rest of this measurable.** Every
 * derived write is an upsert keyed by content, so a reading already held, one folded into another by
 * resolution, and one that opened a row of its own are indistinguishable to the caller handing them
 * over. [written], [overwritten] and [collapsed] come to [attempted] exactly, and each says
 * something different about whether anything is wrong; none is a defect by itself. [superseded] is
 * outside that sum, being a row that stopped existing rather than a reading that went somewhere.
 *
 * **Every way a finding fails to become a row is its own figure, and the division that matters is
 * routine against total.** [refused] is a reading made perfectly and declined by a rule — a finding
 * placed on a context line rather than on the message it was asked about, or an attribute whose
 * subject is not the author who stated it. [unnamed] is an attribute the model answered the refusal
 * word for, which is the instructed answer where no entry fits. [outsideVocabulary] is that
 * instruction disobeyed, a predicate stretched past the closed list. [misshapen] is the one neither
 * of those two can see — a predicate the list does hold, over a value of a sort it does not take.
 * [unanswered] is a restatement
 * the model would not give. [unplaced] is an attribute about somebody identity has not recognised
 * yet. All of these are the ordinary course of events and fire on most sweeps.
 *
 * **[unlisted] is on neither side of that division, being no shortfall at all.** It is the
 * classification naming a fact as outside the closed vocabulary, which costs no restatement and
 * loses no reading: the stage met something it cannot label and said so. It is reported because
 * nothing else here can — a stage that declines correctly and a stage that drops material silently
 * are otherwise the same observation.
 *
 * [lost] is a reading this build could not make sense of — a reply in a shape nothing could read, a
 * kind or a hedge it has no reader for, a request whose window or stored line could not be found —
 * and [unextractable] is a reply answered readably whose line would not parse. These two say
 * something is wrong with the build. Added in with the routine ones they would be invisible, which
 * is why no figure here covers both sides of that line.
 *
 * [unread] is requests that were cut short, never finished, or asked under a key this build does not
 * recognise — all still owed and all offered again. [declined] is what the model refused and what is
 * therefore settled rather than retried, since asking again would be refused again: a window, which
 * is a message nothing will ever read, or a comparison, which is a pair kept apart without having
 * been read. The reason is carried out whole rather than counted, because it says which.
 *
 * **[unread] and [awaiting] are counted in requests, not windows.** A window is one request of the
 * classification stage and several of the restatement stage, so a step of either reports what it
 * asked or was answered about rather than how many stretches of conversation that came to — the
 * unit both stages share, and the unit a submission is billed by. The other figures keep the unit
 * their own text gives: [records] messages, [lost] findings and pairs, [declined] windows and
 * comparisons, [held] windows and pairs. [written] and [overwritten] are rows of the derived layer
 * and also readings, being counted one per reading that caused one; [superseded] is rows alone.
 * [attempted], [collapsed], [refused], [unnamed], [outsideVocabulary], [misshapen], [unanswered],
 * [unextractable], [unplaced] and [repeated] are readings. [backdated] and [sameInstant] are
 * dates, which is a unit of its own because the readings they were taken off were kept, and
 * [unlisted] is facts, which is another: what it counts was never a reading this pass would have
 * carried anywhere.
 *
 * **[usage] is the one with a budget attached.** This pass is the only one of the derived passes
 * that spends the model credit — the answer path spends it too, a question at a time, but nothing
 * bounds a sweep of this except what it reports — and the credit is finite and small, so a batch
 * that reported what it achieved without what it cost would leave the one quantity under a hard
 * ceiling as the only one nobody could see. It covers every call the batch made, the ones that
 * produced nothing included, because those are billed too.
 *
 * All of it is returned rather than logged because `:domain` logs nothing. A caller that reports
 * only the first is reporting success for a batch that may have thrown away half of what it read.
 */
data class Inference(
    val records: Int,
    val written: Int,
    val lost: Int,
    val unread: Int,
    /**
     * How many readings were handed to the writes, which is more than [written] whenever a row was
     * already held or was folded away.
     *
     * **Separate from [written] because that is what makes a rate measured off a sweep checkable.**
     * Every derived write is an upsert keyed by content, so attempts and rows diverge by exactly
     * [overwritten] and [collapsed]; one number covering all three can be read as any of them.
     */
    val attempted: Int = 0,
    /**
     * How many attempts replaced a row already held rather than adding one.
     *
     * The ordinary result of reading a window a second time, so a figure here says nothing is wrong
     * — which is exactly why it has to be told from [collapsed], where the same reduction in rows
     * means the opposite.
     *
     * **Over the readings the batch offered and nothing else.** An attribute write is handed every
     * window its subject holds, because resolution needs them, so the rows that write touches are
     * mostly rows nobody offered; counting those here would bury the reading that was paid for
     * under the size of the subject.
     */
    val overwritten: Int = 0,
    /**
     * How many offered readings resolution folded into another, leaving them no row of their own.
     *
     * A value restated in the same words, or in a wording a paid verdict has established to be the
     * same; either attaches as evidence to the window already covering it. It is the figure that
     * accounts for eighteen attributes coming to six rows, and nothing but the pass can report it:
     * the fold happens before the store is reached, so the write never sees the reading at all.
     */
    val collapsed: Int = 0,
    /**
     * How many stored rows a write removed, because what it wrote now covers them.
     *
     * Resolution's other reduction, and the store's own: a window it held and was not given back.
     * Kept apart from [collapsed] because one is an attempt that became no row and the other is a
     * row that stopped existing, and the derived layer getting smaller is not the same event as a
     * reading going nowhere. It is the only figure here outside the sum [attempted] bounds.
     *
     * Non-zero on the collapse stage too, where re-windowing two wordings into one is the only
     * write in this build that takes a row away.
     */
    val superseded: Int = 0,
    /**
     * How many restatements the model would not give — refused, truncated, or paused.
     *
     * Settled rather than retried, because a refusal is deterministic and the same request would be
     * refused again. Apart from [lost] because this is the model declining and says nothing about
     * the build, while [lost] rising says a prompt and a parser have drifted apart.
     */
    val unanswered: Int = 0,
    /**
     * How many restatements were answered, were readable, and yielded nothing.
     *
     * **The one failure here worth a deployment's attention**: the model answered in a form the
     * pass could read and the line would not parse — the wrong field count, or a blank claim. Each
     * is a call at the higher of the two efforts that bought nothing, so a figure here that is any
     * share of the batch is a prompt and a parser to be put back in step.
     *
     * A predicate outside the vocabulary is [outsideVocabulary] and not this, for the reason that
     * figure states: on a corpus that is not English it is expected on every sweep, and counted
     * here it would have a deployment chasing a parser that is working as designed. A *blank*
     * predicate field is this, because no instruction asks for one.
     */
    val unextractable: Int = 0,
    /**
     * How many dates were refused for standing before the message that stated them.
     *
     * **Not a reading going nowhere**, which is what separates this and [sameInstant] from every
     * other figure here: the finding was written, with its date taken off it. So both are counted
     * in dates, and a figure here says a commitment is stored carrying nothing where it would
     * otherwise carry a date earlier than the message making it.
     *
     * **A commitment's alone, and a ceiling rather than a measure.** An obligation the author
     * states about another participant keeps such a date, that being the ordinary way somebody
     * else's deadline is mentioned, so nothing here counts one. What is counted is the author's own
     * promises, where the record really does rule the date out, together with the obligations whose
     * party the window could not settle — and the corpus may well have stated the timing of those.
     * The share of commitments carrying an obliged party is what bounds the difference, and it is
     * read off the rows rather than from here.
     *
     * Rare where [sameInstant] is not, which is why the two are apart. Added together, a sweep
     * could not say whether the model was answering from something it was never given or copying
     * the one instant it was.
     */
    val backdated: Int = 0,
    /**
     * How many dates were refused for being the evidencing message's own instant, to the second.
     *
     * **The figure that says whether the instruction against copying the line is holding**, and the
     * prevalent of the pair rather than the rare one — the issue this was filed as carries the
     * rates, which is where a measurement belongs. Read as no date stated, which gives up acting
     * immediately as a date: that resolves to this value legitimately and nothing can tell it from
     * an echo, so the minority reading is given up to make the field trustworthy at all.
     *
     * A fall here after a prompt change is the change working. A fall to zero with [backdated] also
     * zero and dates still arriving is the field being answered from what was said, which is the
     * whole point; a fall to zero because nothing carries a date at all is not, so this is read
     * beside the dates that survive and never on its own.
     */
    val sameInstant: Int = 0,
    /**
     * How many attributes could not be offered at all, their subject not being a contact yet.
     *
     * Identity runs over the whole corpus and ahead of this, so a sweep losing many of these is a
     * sweep whose passes are out of step rather than one reading badly — which is a different thing
     * to go and look at from anything [lost] names.
     */
    val unplaced: Int = 0,
    /**
     * How many readings were declined by a rule rather than misread.
     *
     * **Both reading stages produce these**, and the rules are about where a finding sits and which
     * party the model named: a classification declines a finding placed on a context line, or an
     * attribute whose subject field is not the author's own label; a restatement declines a stored
     * line whose subject its window no longer bears out, a label having moved in between. Neither
     * rule can see what a fact is actually about, only what the model said it was about.
     *
     * **No rule about the predicate or its value reaches here.** Those are [unnamed],
     * [outsideVocabulary] and [misshapen],
     * kept out because this figure is a standing share of every sweep for reasons that have nothing
     * to do with the vocabulary — so a width read off a total including it would move whenever a
     * conversation grew.
     *
     * Defaulted, because the collapse declines nothing and a reader of this type should not have to
     * say so. See the division stated above: this is the routine figure, and keeping it out of
     * [lost] and [unextractable] is what leaves those two able to say the build is wrong.
     */
    val refused: Int = 0,
    /**
     * How many attributes the model answered the refusal word for, naming no predicate.
     *
     * **The nearest thing a sweep has to a signal about the vocabulary's width, because it is the
     * answer the instruction asks for.** The restatement instruction tells the model to write the
     * refusal word for an attribute no entry fits rather than stretch one to cover it, so on a
     * candidate the classification already called an attribute this is the two stages disagreeing
     * about whether the fact fits the list. **It is not a clean measure of the width**: both stages
     * are given the same list, so the same figure covers a classification that was wrong to call
     * the thing an attribute at all.
     *
     * Routine, and its answer again on any retry. Apart from [outsideVocabulary] because that is the
     * same judgement reached by disobeying, and the two answer different questions — this one bears
     * on the list, that one says whether the instruction is holding.
     */
    val unnamed: Int = 0,
    /**
     * How many attributes carried a predicate the closed vocabulary does not hold.
     *
     * **A figure here is the stretching rule not holding.** A model answering `employed by`, or the
     * relation in the corpus's own language — which the instruction to write the claim in that
     * language invites — produces a key nothing would ever query, so the reading is lost rather than
     * stored. The instruction asks for the refusal word instead, which is [unnamed]; this is what
     * arrives when an entry is stretched anyway. **Zero here does not say the list is wide enough**,
     * and a figure here does not say it is too narrow: the parser tests membership alone, so a
     * synonym of an entry already held and a relation genuinely new both land in it.
     *
     * Routine on a corpus that is not English, so it is out of [lost] and [unextractable] for the
     * reason those two state. It is also out of [refused], whose rules are about placement and
     * subject.
     */
    val outsideVocabulary: Int = 0,
    /**
     * How many attributes carried a value of a sort the predicate they arrived under does not take.
     *
     * **The one figure here that no answer of the model's decides.** [unnamed] and
     * [outsideVocabulary] are read off the word in the predicate field, and both are blind to this:
     * the name was in the vocabulary, so the field is legal and only the value is wrong. What this
     * tests is the value against the relation it arrived under, so it holds whatever a later
     * wording of either instruction does to the behaviour.
     *
     * **It is the figure that stands in for a store nobody can audit.** A reading lost here would
     * otherwise have been written with an open validity window and a real message citing it, so it
     * would read on the answer path exactly as a correct fact reads. A figure here is therefore
     * neither a defect of the build nor a cost worth chasing — it is the rail reporting that it
     * held.
     */
    val misshapen: Int = 0,
    /**
     * How many facts the classification named as outside the closed vocabulary.
     *
     * **The figure that was missing, and the only one that measures the attribute path's silence.**
     * A stage that correctly declines material it cannot label and a stage that silently drops it
     * produce the same observation everywhere else: no reading, no row, no clause. So a sweep
     * reporting no attributes could be read either way, and this build read it as the first without
     * being able to establish it.
     *
     * **Not a loss.** Nothing is paid beyond the classification that found it, no restatement
     * follows, and nothing about the build is wrong when it fires — it is the answer the instruction
     * asks for. What it still cannot distinguish is a vocabulary that is too narrow from a corpus
     * that genuinely holds other facts; both read as the same number, and only the corpus says
     * which.
     */
    val unlisted: Int = 0,
    /**
     * How many classification lines the model wrote twice in one reply, each asked about regardless.
     *
     * **The one shortfall here that is money rather than bookkeeping.** Every copy buys its own
     * restatement, at the higher of the two efforts, and then arrives at a write whose content key
     * folds it away — so the spend is real and the row is not. The copies are asked about anyway
     * because a classification line carries no claim, so two genuinely different findings of one
     * kind on one message are the same line and dropping the second would lose it; this is the
     * figure that keeps the price of that visible, since a model which has started repeating every
     * finding is otherwise only a sweep that costs more for no stated reason.
     *
     * Apart from [collapsed] because that is resolution folding two readings of one fact, which is
     * the pass working as designed, and this is the model repeating itself inside one answer. Apart
     * from [lost] because nothing about the build is wrong when it happens.
     */
    val repeated: Int = 0,
    val declined: List<String> = emptyList(),
    val usage: TokenUsage = TokenUsage.NONE,
    /**
     * How many requests this batch left with the provider, waiting to be read.
     *
     * **Zero is not the only honest answer to "did this batch make progress?", and this is what
     * tells the two apart.** A batch that submits settles nothing and leaves the backlog exactly
     * where it was, which is indistinguishable from a batch that tried and failed if all a driver
     * can see is the count. One is the pass working — the replies arrive next sweep — and the other
     * is the pass stuck, and they need opposite handling: the first should stop the drain quietly
     * and wait, the second should stop it and say so.
     *
     * Non-zero also on the batch that finds a submission still unanswered, because that batch is
     * waiting on exactly the same thing.
     */
    val awaiting: Int = 0,
    /**
     * How much was worth asking about and was not submitted anyway — windows, or pairs.
     *
     * **One stage sets it, and what bounds it is configuration rather than a bound on spend.** The
     * collapse compares as many candidate pairs as a sweep is configured to compare, and the pairs
     * beyond that are reported here: they stay candidates and the next sweep offers them again. The
     * reading stages hold nothing back — what stops them is the day's allowance, which stops the
     * stage rather than trimming its batch, so a window is either asked about or still owed.
     */
    val held: Int = 0,
    /**
     * How long a submission had been outstanding when this batch gave up on it.
     *
     * `null` on every batch that gave up on nothing, which is almost all of them. Reported because
     * abandoning is spending that bought nothing, and the only thing worse than paying for a batch
     * nobody could read is doing it without saying so.
     */
    val abandoned: Duration? = null,
    /**
     * Why a reply had to be given up on for being in a shape this build cannot read.
     *
     * `null` on almost every batch. It means a stop reason, or an answer, that nothing here has a
     * reader for, which only a new build clears — so unlike the other losses it is worth a line of
     * its own rather than a place in [lost], where it would sit among the parse failures and
     * refusals it is nothing like. A provider that merely did not answer is deliberately *not* here:
     * an expired batch is the ordinary end of one and clears by being asked again, and reporting it
     * here would have every expiry read as a broken parser.
     *
     * Written by the restatement stage and by the collapse, so the line that reports it names
     * neither; the reason says which. One reason and not a list: it names a condition of the build
     * rather than of a candidate, and every request that hits it hits the same one.
     */
    val unreadable: String? = null,
    /**
     * How many readings were merged into another, because they turned out to state the same fact.
     *
     * **The collapse's own progress figure, and it falls where [written] rises.** A merge is a row
     * that stops being a finding of its own, so a sweep reporting merges is a sweep that made the
     * derived layer smaller and more correct — which no other figure here can say, every one of them
     * being about reading more of the corpus.
     *
     * It counts both cheap and paid merges together, deliberately: keeping them apart would make
     * it look as though a merge had a price. What a sweep's own figures cannot say is which tier
     * reached them — [usage] is zero on every sweep of this stage, because a submission's cost is
     * only known when it is collected — and [awaiting] beside it is the nearest reading: merges
     * with nothing outstanding were reached without asking anybody.
     */
    val merged: Int = 0,
    /**
     * How many pairs the model was paid to decide and decided against merging.
     *
     * Reported because it is the measure of how badly the cheap tier's lower bound is set. Every one
     * of these is a call that bought a negative answer — correctly, as far as anything here knows —
     * and a sweep where they dominate [merged] is a sweep sending the model pairs the vectors should
     * have ruled out. A refusal is counted here too and named in [declined], which is where a
     * comparison the model would not make is reported.
     */
    val apart: Int = 0,
) {
    companion object {
        /** Nothing was owed, so nothing was spent. */
        val NOTHING = Inference(records = 0, written = 0, lost = 0, unread = 0)
    }
}
