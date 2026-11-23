package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.IngestStateRepository
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * The machinery every request shape runs on.
 *
 * It owns the lifecycle and nothing else: binding the request to a shape, scope resolution, the
 * ordering of a shape's stages, the short-circuit before the model is spent, the grounding check
 * and the one retry. **It knows no shape.** Adding one is a new package registering itself with
 * the registry, and nothing in this file changes — which is the property the whole arrangement
 * exists for, and the one that stops a new answer kind becoming a new branch in a growing
 * `when`.
 *
 * Binding is the registry's: a command names a shape directly, free text is classified, and
 * anything unclassified takes the fallback. See [ShapeRegistry.bind].
 *
 * A plain class composing collaborators, with no interface of its own: it inverts no I/O, and
 * the ports it reaches through are already behind one.
 *
 * What it does rather than trusting a shape to:
 *
 * **It stamps freshness.** A shape has no business reading ingestion state, and an answer that
 * does not say how current the corpus is implies a currency it may lack.
 *
 * **It carries the losing subjects into the answer.** A shape that forgot would produce an
 * answer about the wrong subject with no way for the reader to see the alternative, which is
 * the failure that is invisible afterwards.
 *
 * **It checks grounding.** A check each shape owned a copy of is a check some shape will skip.
 *
 * **It bounds what one request may spend.** A shape sizes its own call and cannot see either of
 * the two multipliers that make a request cost more than that — the adapter's resumption of a
 * paused turn, and the retry below — so the per-request ceiling is the runner's and the retry
 * draws from the same [Budget] rather than a fresh one.
 *
 * @param ceiling the most one request may spend on the model, across every call and the retry.
 * @param trace where each attempt is reported, whether or not anything was wrong with it.
 *   Defaulted to reporting nothing, because most callers assert against the answer rather than
 *   the report. See [AnswerTrace] for why a clean attempt has to be reported too, which is what
 *   makes this separate from [Faults].
 */
// The two reporting seams — [trace] and [faults] — are optional rather than collaborators, and
// they are separate on purpose: one hears about every attempt and the other only about a faulty
// one, which no single port can be. Bundling them into a holder would satisfy the count by adding
// a type to this module that exists for the count. Named rather than counted, because a figure
// here is a place the next parameter has to be remembered.
@Suppress("LongParameterList")
class Runner(
    private val shapes: ShapeRegistry,
    private val scope: ScopeResolver,
    private val citations: CitationCheck,
    /**
     * Read for one thing only: who wrote the material a surviving claim cites.
     *
     * **Here rather than in a shape, because every shape's claims need it and a shape is free not
     * to.** The identifiers a claim carries are checkable and not usable — a reader needs a name and
     * a date to find the material themselves — and resolving that in each of the five shapes that
     * cite would be five chances to omit it, with the omission looking exactly like material the
     * corpus holds no name for. The runner is the one place holding the retrieved material and the
     * answer that survived grounding at the same moment.
     *
     * The cost is one bulk read per answered request carrying claims, keyed to the records actually
     * cited rather than to everything retrieved, and duplicating part of what a shape assembling
     * turns already asked. Uniformity is worth a query a question already waiting on two model calls
     * will not notice.
     */
    private val retrieval: Retrieval,
    private val ingestState: IngestStateRepository,
    private val ceiling: Int = Budget.DEFAULT_CEILING,
    /**
     * Where a fault in an answer is reported, if anywhere.
     *
     * Silent by default, so nothing has to supply one. A process that logs wires it and gets the
     * one thing a request's outcome cannot say, in either direction. An answer thrown away is
     * reported as *nothing to answer from*, which is also what an empty corpus reports, so two model
     * calls spent on substantial answers are otherwise indistinguishable from a question nobody
     * could have answered — bar the one case the reply now separates for itself, a complete answer
     * nobody could read, which still arrives here because *why* a whole answer could not be read
     * is not in the reply either. An answer that survived its fault says even less: it
     * arrives looking whole, and the findings taken out of it on the way leave no other trace.
     */
    private val faults: Faults = Faults.SILENT,
    private val trace: AnswerTrace = AnswerTrace.UNTRACED,
) {

    /**
     * The commands this build answers — see [ShapeRegistry.answerable].
     *
     * Here so that a surface holding a runner need not also be handed the registry: what a route
     * has to decide is whether to accept the request at all, and the registry is this object's
     * collaborator rather than presentation's.
     *
     * Not a command list and not offered as one. It is every identifier a request may bind, the
     * fallback included, which is a larger set than any interface should put in front of a reader.
     */
    val answerable: Set<String> get() = shapes.answerable

    /**
     * Answer [request], or say why it cannot be answered as asked.
     *
     * Every return is an [Answer]. Nothing here throws to report an ambiguous name or an empty
     * corpus: both are things the system has to say rather than faults, and an exception would
     * make the caller's degrade path the place where the wording of an ordinary reply is
     * decided.
     *
     * A command naming no shape is not among them: it is the caller having got a wire field wrong,
     * so it is refused where a status code can say so. See [answerable].
     */
    suspend fun answer(request: Request): Answer {
        val freshness = corpusFreshness()
        val binding = shapes.bind(request)
        val shape = binding.shape
        // Charged before any stage runs, because routing happened before any stage could. A
        // free-text request has already spent something by the time a shape is chosen, and a
        // budget that started counting at generation would under-report every one of them.
        val budget = Budget(ceiling).spending(binding.usage)

        // A shape accepting no subject is scoped by its own text, so resolving for it would be
        // asking a question nothing could answer. Skipped rather than resolved-and-ignored,
        // because the resolver refuses an empty accepted set outright.
        if (shape.accepts.isEmpty()) {
            return drive(shape, from = 0, entry = Exchange(request, budget = budget), freshness)
        }

        // The request's own name wins, and the classification's is the fallback. The interface
        // separates a name out of a command, where it is an argument; it cannot do that for a
        // sentence, so free text arrives with none and the routing call reads one out of the text.
        // In that order rather than the reverse, because a name somebody typed as an argument is
        // what they meant, and a name read out of prose is an inference about what they meant.
        //
        // **A blank argument cannot arrive here**, because [Request] refuses one: taken as present
        // it would shadow the name the routing call had read and resolve to the inbox, which is
        // this card's wrong answer reached through the wire instead of through the model. The
        // bound is on the type and the wire normalises an emptied field into the absence it meant,
        // so nothing is re-checked here. Which absence an absent name is, the binding says.
        val named = request.subjectName?.let(SubjectName::Given) ?: binding.subjectName

        return when (val resolved = scope.resolve(named, shape.accepts)) {
            is Scope.Ask -> Answer.SubjectUnclear(resolved.options, freshness)
            is Scope.NotFound -> Answer.SubjectUnknown(resolved.searched, resolved.name, freshness)
            // Nothing matched, and the search found the corpus holding names spelled nearly like
            // the one written. So what is established is that the form is absent and not that the
            // subject is, and the reply carries the forms rather than an absence nothing here is in
            // a position to assert.
            is Scope.Nearest ->
                Answer.SubjectNearest(resolved.nearest, resolved.searched, resolved.name, freshness)
            // Nothing was searched, so there is nothing to report having looked through. The
            // request named somebody and the name did not arrive; the only reply that does not
            // assert something untrue is to ask for it.
            Scope.Unread -> Answer.SubjectUnread(freshness)
            // Nothing was searched here either, and for the plainer reason: the request named
            // nobody and this shape cannot answer for the inbox, so there was no name to look up.
            // Kept apart from the line above because the two requests said different things — see
            // [Scope.SubjectMissing] — and apart from nothing-found because that one reports a read.
            Scope.SubjectMissing -> Answer.SubjectMissing(freshness)
            is Scope.Resolved -> drive(
                shape = shape,
                from = 0,
                entry = Exchange(
                    request = request,
                    subject = resolved.subject,
                    alternatives = resolved.alternatives,
                    budget = budget,
                ),
                freshness = freshness,
            )
        }
    }

    /**
     * Run the shape's stages, validate what they produced, and retry once if it does not hold.
     *
     * The retry resumes at the first stage that spends the model rather than restarting the
     * pipeline. Everything before that point is deterministic — resolution and retrieval return
     * what they returned — so re-running it would spend reads to arrive at the same exchange,
     * and the thing worth rolling again is the generation whose output did not hold: one that came
     * back ungrounded, one that read as the wrong shape, or one the reader could make no answer of.
     *
     * A second failure degrades to insufficient evidence. That is deliberately the same answer
     * an empty corpus produces: in both cases there is nothing the system can stand behind, and
     * a distinct case would invite a caller to render one of them as an error.
     *
     * **The one exception is a request given up on a complete reply nobody could read**, which says
     * that instead — see [Answer.Unreadable]. The conflation above was argued for a request that
     * could not ground what it generated, and for one whose material or budget ran out; it was never
     * argued for a model that answered in full twice over. Telling that reader the corpus held
     * nothing is the one case where the shared answer states something untrue.
     *
     * **The retry resumes on the budget the first attempt left, not the one it started with.**
     * The resume point holds the exchange as it stood before generation, and that exchange
     * remembers a spend of nothing — resuming from it verbatim would hand the second attempt a
     * full budget and make the ceiling per attempt rather than per request, which is the doubling
     * a per-request bound exists to prevent.
     */
    private suspend fun drive(shape: Shape, from: Int, entry: Exchange, freshness: CorpusFreshness): Answer {
        val first = attempt(shape, from, entry, freshness)
        return when (val verdict = checked(shape, FIRST, first)) {
            is Verdict.Answered -> finish(verdict.attempt, freshness)
            is Verdict.Rejected -> reroll(shape, first, freshness)
        }
    }

    /**
     * The second and last attempt, or the degrade when there is nothing to re-roll.
     *
     * No resume point means nothing in the stage list spends the model, so running it again produces
     * exactly what it produced. There is nothing to re-roll, and the answer it gave does not hold.
     *
     * **Which is why that branch answers insufficient evidence whatever the fault was.** The answer
     * for a request given up on an unreadable reply says a re-roll was spent and that the request
     * still ended on a reply nobody could read — see [Answer.Unreadable] — and here no second call
     * was made, so giving it would be claiming of the request exactly what [Progress.Settled]
     * refuses to let a stage claim. Only the branch below, which has run two attempts, can reach it.
     */
    private suspend fun reroll(shape: Shape, first: Attempt, freshness: CorpusFreshness): Answer {
        val second = first.resume?.let {
            attempt(shape, it.index, it.exchange.copy(budget = first.exchange.budget), freshness)
        } ?: return Answer.InsufficientEvidence(freshness)
        return when (val verdict = checked(shape, RETRY, second)) {
            is Verdict.Answered -> finish(verdict.attempt, freshness)
            is Verdict.Rejected -> verdict.fault.givenUp(freshness)
        }
    }

    /**
     * [attempt] as it should be answered, or the fault that ended it.
     *
     * Deciding and reporting in one step, because the two must not be able to disagree: a fault the
     * runner acted on and did not report is exactly the silence this exists to end. A survivable
     * fault is reported too, for one of two reasons depending which it is: the claims a citation
     * fault drops are gone from the reader's answer and this is the only record they were made,
     * while a call that came back with nothing readable by construction drops nothing and is
     * reported because the answer it settles as is the one an empty corpus gives.
     *
     * Both reports are made from the one verdict for the same reason. [trace] hears about every
     * attempt and [faults] only about a faulty one, but a second call to [CitationCheck] to tell
     * either of them what happened would be a second chance to disagree with the decision that was
     * actually taken.
     */
    private fun checked(shape: Shape, which: Int, attempt: Attempt): Verdict {
        val fault = fault(shape, attempt)
        // **The repair is decided before the attempt is reported, so that the report can say what it
        // produced.** Deciding and reporting were already one step to keep them from disagreeing;
        // what the answer became is part of the decision, and a reader of the trace that had to
        // reconstruct it would be doing the one thing this guards against.
        val kept = when {
            fault == null -> attempt
            fault.fatal -> null
            else -> attempt.copy(answer = grounded(shape, attempt))
        }
        // Reported in the terms it is scored in, which is span identifiers — bar a citation that
        // named no span, which is reported as the number or the text the model wrote because there
        // is nothing else to call it. The ungrounded set is read off the fault rather than
        // recomputed: `Fault.Ungrounded` already renders what the model cited and should not have,
        // and that is exactly the question.
        //
        // **Both of the fault's lists, which the fault itself keeps apart.** What a reader of the
        // log needs is which kind of citation went wrong; what the score needs is whether any did,
        // and a line reporting nothing ungrounded while `survived` had shrunk would be a trace that
        // contradicts itself — and would score an attempt that dropped findings as a clean one.
        //
        // **Only a citation fault fills this, and every other fault leaves it empty whether or not
        // a retry follows**, so neither the field nor the retry tells a faulty attempt from a clean
        // one on this line: a variant count the shape did not promise and an answer that asserted
        // nothing are both fatal and roll again, an alternative dropped for having nothing to send
        // is answered as it stands, and a reply nothing could read does one or the other depending
        // on what the call came back with — and none of them reports anything here. What separates
        // them from a clean attempt is
        // the line [faults] writes, not this one — which is the division between the two seams
        // rather than a gap, since this one is scored over what was cited and that one is read when
        // something went wrong.
        trace.attempted(
            Attempted(
                shape = shape.id,
                ordinal = which,
                retrieved = attempt.exchange.retrievedIds().map { Citation.render(it) }.sorted(),
                cited = attempt.answer.cited().map { Citation.render(it) }.sorted(),
                ungrounded = (fault as? Fault.Ungrounded)?.let { (it.cited + it.unreadable).sorted() }.orEmpty(),
                // Read off the repaired answer rather than subtracted from what was cited, because a
                // claim goes whole: one resting on a span that was retrieved and one that was not
                // takes the good identifier out with it, and a subtraction would keep it.
                survived = kept?.answer?.cited()?.map { Citation.render(it) }?.sorted().orEmpty(),
            ),
        )
        if (fault == null) return Verdict.Answered(attempt)
        faults.noticed(shape.id, which, fault)
        return kept?.let { Verdict.Answered(it) } ?: Verdict.Rejected(fault)
    }

    /**
     * [attempt]'s answer with every ungrounded claim removed.
     *
     * **Handed back untouched when it is not a composed answer, which is a real case rather than a
     * defensive one.** Every survivable fault reaches here: a citation fault, which is only decided
     * for a composed answer and has claims to take out; an alternative with no text to send, removed
     * below; and a model call that came back with nothing readable by construction, whose answer
     * carries no claims at all. Repairing that last one means doing nothing to it — it is reported
     * and given as it stands — and the cast this used to open with would have turned that report
     * into a crash on the answer path.
     *
     * **A variant left with nothing goes rather than being rendered empty**, which is only possible
     * for one that carried no prose to begin with — the list of cited items a status report is, or
     * an alternative the model wrote as assertions alone.
     *
     * **An alternative with no text to send goes too, for the shapes that offer a choice**, which is
     * the removal [Fault.AlternativesDropped] reports. A reader picks one and sends it, so one with
     * nothing in it to send is not among the things being offered; [unsendable] says why it is a
     * removal rather than a refusal, and holds the count that keeps this from emptying the menu.
     *
     * Neither removal can leave nothing. This is reached for a survivable fault alone, and both of
     * the two that remove anything are held by their own initialisers to having left something: some
     * variant still carries a claim, and some alternative still carries its text.
     */
    private fun grounded(shape: Shape, attempt: Attempt): Answer {
        val answer = attempt.answer as? Answer.Composed ?: return attempt.answer
        val retrieved = attempt.exchange.retrievedIds()
        val grounded = answer.variants.mapNotNull { it.grounded(retrieved) }
        val kept = if (shape.variants.last > 1) grounded.filter { it.prose.isNotBlank() } else grounded
        return answer.copy(variants = kept)
    }

    /**
     * One pass over the stages from [from], stopping at whichever settles the request.
     *
     * The short-circuit sits immediately before the first model-spending stage rather than
     * after retrieval, because the runner does not know which stage retrieved. What it knows is
     * that nothing retrieved plus a shape that answers from the corpus means there is nothing
     * to answer from — and that the cheapest place to notice is before the budget is spent.
     *
     * The budget is checked at the same boundary and degrades the same way. A request that has
     * spent its ceiling has nothing it can stand behind either, and stopping before the call is
     * the cheaper of the two routes to that answer — a call given what is left of an exhausted
     * budget spends it on thinking and returns a truncation.
     */
    private suspend fun attempt(shape: Shape, from: Int, entry: Exchange, freshness: CorpusFreshness): Attempt {
        var exchange = entry
        var resume: Resume? = null
        for (index in from until shape.stages.size) {
            val stage = shape.stages[index]
            if (stage.spendsModel) {
                // The two reasons to stop here are one branch because they are one answer. Nothing
                // retrieved and nothing left to spend both mean there is nothing the system can
                // stand behind, and a request that distinguished them would be inviting a caller to
                // render one of them as a fault.
                val nothingToAnswerFrom = shape.readsCorpus && exchange.retrieved.isEmpty()
                if (nothingToAnswerFrom || !exchange.budget.worthSpending) {
                    return Attempt(Answer.InsufficientEvidence(freshness), exchange, resume = null)
                }
                if (resume == null) resume = Resume(index, exchange)
            }
            when (val progress = stage.apply(exchange)) {
                is Progress.Carried -> exchange = progress.exchange
                // The settling stage's own exchange, not the one it was handed. A stage that
                // retrieves and settles in one step would otherwise have its citations checked
                // against material it had not retrieved yet.
                is Progress.Settled ->
                    return Attempt(progress.answer, progress.exchange, resume, progress.fault)
            }
        }
        error(
            "${shape.id.value} ran every stage without settling the request. A stage list that " +
                "only ever carries the exchange forward has no answer in it, and the runner has " +
                "nothing to invent one from.",
        )
    }

    /**
     * What was wrong with what a shape produced, or `null` if nothing was.
     *
     * Only a composed answer carries anything *checkable here*: every other case makes no claims and
     * offers no variants, so there is nothing in them to hold against the retrieved set. That is true
     * however they arose — the machinery decides them in the ordinary case, and a stage is free to
     * settle with one. What such an answer can still carry is the settling stage's own account of why
     * it produced nothing, which no check here could recompute; [Progress.Settled] holds a stage to
     * reporting one only alongside an answer that is not composed, so this clause and the ones
     * below it can never be deciding the same attempt.
     *
     * **A reply asserting nothing is checked here too, for the shapes whose answer is assertions.**
     * It is a contract check like the variant count rather than a reading of what was cited: there
     * is nothing cited to read, which is the whole of what is wrong with it. See
     * [Shape.assertsFindings].
     *
     * **And its counterpart, for the shapes that offer alternatives: one with no text to send.** The
     * two read opposite halves of a reply — one shape's answer is its findings and the other's is the
     * prose somebody sends — and each holds a reply to carrying what it promised. They are gated on
     * separate declarations rather than on one that could not be both, so nothing stops a shape from
     * answering to both; none of the five registered ones does, and if one did the order of these
     * clauses would decide which was reported. That counterpart reads what survives the citation
     * repair rather than what the model wrote, so it is asked immediately before the citation check
     * and reports in place of it; [unsendable] says why it is asked there.
     *
     * **The reason is returned rather than reduced to a boolean, because the faults look identical
     * from outside and are repaired in different places.** A variant count the shape did not promise
     * is the format having been misread — which is where a marked line carrying no citations at all
     * surfaces, since the variant holding it is discarded whole. An ungrounded citation is one the
     * model wrote that rests on nothing retrieved — a span that was not, a number that was never
     * offered for one, or text that is not a number. One that ends the answer degrades it to
     * *nothing to answer from* after the retry, so a
     * request that spent two model calls on substantial answers reports
     * the same thing as an empty corpus; one the answer survives takes findings out of a reply that
     * still arrives looking whole. Nothing says which happened unless this does.
     *
     * Every clause the Validate stage is specified with bar one. That one — no display text
     * where a message key belongs — cannot be checked here and is not checked anywhere yet:
     * `:domain` holds no message keys at all, so it has nothing to compare against, and the
     * clause can only be enforced by the presentation mapping that emits them.
     */
    private fun fault(shape: Shape, attempt: Attempt): Fault? {
        // What the stage reported, first and instead of anything computed here. A stage reports only
        // what the runner cannot recompute — a model call that produced nothing readable, which
        // arrives as an answer with nothing in it to examine — so there is never a second verdict to
        // weigh it against, and taking it as given is what keeps the one thing it knows from being
        // overwritten by a check that has nothing to look at.
        attempt.reported?.let { return it }
        val answer = attempt.answer as? Answer.Composed
        return when {
            answer == null -> null
            answer.variants.size !in shape.variants ->
                Fault.WrongVariantCount(wanted = shape.variants, got = answer.variants.size)
            // **Ahead of the citation check because that one cannot see it.** What survives and what
            // was claimed are both nought here, so every way of asking whether a citation failed
            // answers no — the proportion that decides which citation fault applies is never
            // reached, and the attempt reads as clean. The question this asks is a different one:
            // whether the answer made an assertion at all.
            //
            // **Only where something was retrieved**, which is what makes the count on the fault
            // mean anything. A reply asserting nothing about no material has nothing it could have
            // asserted, so refusing it would be refusing the honest answer — unreachable for the
            // shapes that declare this today, since they read the corpus and an empty read settles
            // before the model is called, and the rule for one that does not.
            shape.assertsFindings &&
                attempt.exchange.retrieved.isNotEmpty() &&
                answer.variants.none { it.claims.isNotEmpty() } ->
                Fault.NothingAsserted(of = attempt.exchange.retrievedIds().size)
            else -> answer.unsendable(shape, attempt.exchange) ?: ungrounded(answer, attempt.exchange)
        }
    }

    /**
     * The citation fault in [answer], or `null` if every claim rests on something [exchange] holds.
     *
     * The count is checked before this rather than alongside it, because a reply whose format was
     * misread has no citations worth reporting on — the variant carrying them was already dropped.
     */
    private fun ungrounded(answer: Answer.Composed, exchange: Exchange): Fault? {
        val retrieved = exchange.retrievedIds()
        val claims = answer.variants.sumOf { it.claims.size }
        // Counted off the claims rather than by re-grounding each variant and reading the count back
        // off that. The question is how many assertions survive, which the claims answer directly;
        // rebuilding the variant to ask it made the count depend on whether the variant could still
        // exist afterwards, and for one carrying no prose it could not — so deciding *which* citation
        // fault this was threw out of the runner instead of returning one.
        val surviving = answer.variants.sumOf { variant -> variant.claims.count { it.grounded(retrieved) } }
        // **Whether there is a citation fault is read off the same rule that decides what survives,
        // and not stated again here.** Asking after the ways a citation can fail separately means
        // writing the rule a second time, which `Claim.grounded` says is how the two halves of the
        // gate come to disagree. Asking `grounded` how many survived keeps the verdict from drifting
        // from what the reader is shown, and an answer whose every citation resolved cannot be read
        // as faulty because one set happened to be consulted and the others not. `Claim.grounded` is
        // where the ways are enumerated; no count of them is kept here, so a kind added there cannot
        // stale a number written down over here.
        //
        // **The offender lists below are built kind by kind, and that half is not derived.** A way for
        // `grounded` to refuse a claim that nothing below reports would make this a fault with nothing
        // to report it as, and both citation faults require something named — so it would raise rather
        // than read as clean. That is the loud failure of the two, which is why it is left rather
        // than guarded: a kind added to `grounded` has to argue for its exclusion here, and raises if
        // it is simply left out.
        if (surviving == claims) return null
        val cited = citations.ungrounded(answer.variants, retrieved)
        val unoffered = answer.unoffered()
        // The two are one list because a reader has one question of them — what material did the
        // model name that it should not have — and because the proportion that decides which fault
        // this is counts offenders rather than kinds of offender.
        val written = (cited.map { Citation.render(it) } + unoffered.map { Offer.written(it) }).sorted()
        // **What could not be read is reported apart from them, for the reason
        // `Fault.Ungrounded.unreadable` gives.** Bounded per citation here, where it enters the
        // report, because this is model output: a citation is a short number and anything standing in
        // for one is a mistake, so a piece running past this length is a model writing prose where a
        // citation belongs rather than evidence worth a log line. `BackendContainer.excerpt` bounds
        // the reply for a related reason but not the same one — that is one payload cut to a length,
        // this is each item of a list — and bounding at the one place both the fault line and the
        // trace read from leaves neither sink to remember it.
        //
        // **Deduplicated after the cut, not before.** `unreadable()` hands over a set, and cutting
        // each entry to a prefix can make two distinct citations equal again — which would print the
        // same text twice, read as the model repeating one mistake, and inflate the count this
        // fault's own `require` reports. Restoring it here is also what bounds the list's length
        // against the ordinary degraded reply, which repeats one unreadable citation across many
        // claim lines rather than inventing a new one each time. What is still unbounded is the
        // count of *distinct* unreadable citations in one answer; a reply that writes a different
        // sentence after every marker produces an entry for each.
        val unreadable = answer.unreadable()
            .map { it.take(Fault.Ungrounded.CITATION_EXCERPT) }
            .distinct()
            .sorted()
        // **Nothing surviving is a different answer from some surviving, not a worse one.** A reply
        // whose every assertion rests on something absent was not answered from the material at
        // all, and giving its prose alone would be offering a hedge as a finding. One bad citation
        // among many is a copy that went wrong, and the rest of the reply is still the answer.
        //
        // **Counted over the whole answer rather than per variant**, which can only differ for the
        // shape that offers several — and there the prose is the deliverable. A draft is instructed
        // to carry no claims in its own text and to cite outside it, so a draft left without its
        // claim line is still a complete draft rather than a hedge. A shape whose cited findings
        // *are* the answer offers exactly one variant, which makes the two counts one number.
        return if (surviving == 0) {
            Fault.NothingGrounded(
                cited = written,
                of = retrieved.size,
                claims = claims,
                unreadable = unreadable,
            )
        } else {
            Fault.ClaimsDropped(
                cited = written,
                of = retrieved.size,
                claims = claims,
                dropped = claims - surviving,
                unreadable = unreadable,
            )
        }
    }

    /**
     * Stamp what only the runner knows onto the answer.
     *
     * **Every case, not only the composed one.** *Nothing was found* is the reply that misleads
     * hardest while ingestion is behind, so an unstamped one is the worst of them to let
     * through — and a stage may settle with any of them.
     *
     * Overwritten rather than merged. A shape that set freshness set it from something it should
     * not have been reading, and keeping its value would make the answer's currency depend on
     * which shape answered. Exhaustive rather than defaulted through an `else`, so a case added
     * later cannot inherit the one behaviour this exists to prevent.
     */
    private suspend fun finish(
        attempt: Attempt,
        freshness: CorpusFreshness,
    ): Answer = when (val answer = attempt.answer) {
        is Answer.Composed -> answer.copy(
            alternatives = attempt.exchange.alternatives,
            freshness = freshness,
            attribution = answer.attributed(retrieval, attempt.exchange),
        )

        is Answer.InsufficientEvidence -> answer.copy(freshness = freshness)
        is Answer.Unreadable -> answer.copy(freshness = freshness)
        is Answer.SubjectUnclear -> answer.copy(freshness = freshness)
        is Answer.SubjectUnknown -> answer.copy(freshness = freshness)
        is Answer.SubjectNearest -> answer.copy(freshness = freshness)
        is Answer.SubjectUnread -> answer.copy(freshness = freshness)
        is Answer.SubjectMissing -> answer.copy(freshness = freshness)
    }

    /**
     * How current the corpus is, across every source it is made of.
     *
     * **Complete only as far as the least current source.** A corpus assembled from two sources
     * is complete through the earlier of their two marks, and reporting the later one would
     * claim a currency half the material does not have. A source that has never reported makes
     * the whole thing complete through nothing — which is the honest answer, because nothing is
     * known about what it holds.
     *
     * **Lagging units stay under the source that named them.** A work unit is the adapter's own
     * name for a unit of its work, so a merged list would carry names that cannot be acted on
     * without first establishing which adapter issued them, and would collapse two sources'
     * units that happen to share a string.
     *
     * Read per request rather than cached. It is one read per source, it changes while the
     * system is running, and a cached freshness is a stale claim about staleness.
     */
    private suspend fun corpusFreshness(): CorpusFreshness {
        val reported = Source.entries.associateWith { ingestState.freshnessOf(it) }
        val marks = reported.values.map { it?.completeThrough }
        return CorpusFreshness(
            completeThrough = if (marks.any { it == null }) null else marks.filterNotNull().minOrNull(),
            lagging = reported.mapNotNull { (source, freshness) ->
                freshness?.lagging?.takeIf { it.isNotEmpty() }?.let { source to it }
            }.toMap(),
        )
    }

    private companion object {
        /** Which attempt a fault belongs to, counted the way a reader counts. */
        const val FIRST = 1
        const val RETRY = 2
    }

    /**
     * One pass over the stages, and where a retry would resume.
     *
     * [reported] is what the settling stage said was wrong with what it produced, where the stage is
     * the only thing that could say — carried rather than recomputed because the answer it belongs
     * to has nothing in it to recompute from.
     */
    private data class Attempt(
        val answer: Answer,
        val exchange: Exchange,
        val resume: Resume?,
        val reported: Fault? = null,
    )

    /**
     * Where to pick up a retry, and the exchange as it stood there.
     *
     * The exchange is kept rather than recomputed because recomputing it is the thing the
     * resume exists to avoid.
     */
    private data class Resume(val index: Int, val exchange: Exchange)

    /**
     * What [checked] decided about one attempt: the answer to give, or the fault that ended it.
     *
     * **Two arms rather than an attempt that may be absent beside a fault that may be absent.** Only
     * two of those four combinations were ever inhabited, and what the request is given up as now
     * turns on which fault was fatal — so a caller reading a discarded attempt would be taking it on
     * trust that the reason came with it. Stated as a type instead, which is the same move
     * [Progress] makes for a stage.
     */
    private sealed interface Verdict {

        /** The attempt as it is to be answered, repaired where a survivable fault called for it. */
        data class Answered(val attempt: Attempt) : Verdict

        /** The attempt was discarded, and this is what was fatally wrong with it. */
        data class Rejected(val fault: Fault) : Verdict
    }
}

/**
 * What was wrong with what a model call produced.
 *
 * **Several cases, because they are repaired in different places and only some of them are fatal.**
 * A variant count the shape did not promise means the reply's *format* was not read as written —
 * which is also where a claim line carrying no citations at all lands, since a variant holding one
 * is discarded whole. A reply that asserted nothing, from a shape whose answer is its assertions,
 * followed the format and answered the question with the half of a reply nothing can check. An
 * ungrounded citation means the format read fine and something in it rests on
 * nothing retrieved — a span that was not, a number that was never offered for one, or text that is
 * not a number; whether that ends the answer depends on how much of it was ungrounded. An
 * alternative with no text to send followed the format too, and is dropped so the rest can be
 * offered. A reply nothing could read at all is the one raised where there is no composed answer to
 * examine — which is why this is not named for a composed answer: the others are about one, and that
 * one is about there not being one.
 * Collapsed to a single verdict they are indistinguishable, and none of them is then actionable.
 */
sealed interface Fault {

    /**
     * Whether an answer carrying this fault can still be given.
     *
     * **One citation got wrong must not cost a whole answer, which is why this is not a single
     * verdict.** A citation is copied out of a prompt by a language model, so a reply carrying twenty
     * of them will sometimes get one wrong — and discarding every finding over the one that was
     * mis-copied spends a second model call to arrive at *nothing to answer from*, for a reply whose
     * other nineteen findings were sound. Shortening what has to be copied to a number makes that
     * rarer; it does not make it impossible, and a model can still cite a number it was not given.
     *
     * **The survivable cases survive for unrelated reasons, and none of them reduces to another.**
     * A citation fault is worth a repair and a re-roll would throw away a good answer; an alternative
     * with nothing to send is worth a removal, for the same reason read of a different half of the
     * reply; [Unreadable] has nothing in it to repair, and where the call carried nothing readable by
     * construction a second one buys the same failure again. So this is not a measure of how bad a
     * fault is — it is whether the attempt is answered as it stands. Mostly that follows from whether
     * it can be, and sometimes it does not: [NothingAsserted] could be answered and is refused
     * anyway, which is a decision about what a reader may be shown rather than a limit on what is
     * left to show; and [Unreadable] on a reply the provider reported complete is re-rolled, because
     * there a second call is the one thing likely to go differently.
     */
    val fatal: Boolean

    /**
     * The answer does not offer the number of alternatives the shape promised.
     *
     * **Asked twice of the same promise, because the promise is about what a reader is offered.**
     * First of the reply as the model wrote it, where a count that is wrong means the format was not
     * read as written. Then again of what the repairs leave, for a shape that offers a choice: a
     * variant can be removed for resting on nothing retrieved, or for having no text to send, and an
     * answer whose alternatives fall below the promise once those are gone has nothing to offer. Only
     * the second reading can see that, because only it happens after the removals.
     *
     * @property got how many alternatives there are at the point this was asked — blocks the model
     *   wrote, on the first reading; alternatives a reader would be offered, on the second.
     */
    data class WrongVariantCount(val wanted: IntRange, val got: Int) : Fault {
        override val fatal: Boolean get() = true
    }

    /**
     * The reply made no assertion, from a shape whose answer is its assertions.
     *
     * **The fault that names nothing, because there is nothing to name.** A citation fault reports
     * what the model cited and should not have; here the model cited nothing at all, so what is
     * reported is how much material it had in front of it while asserting none of it. This is a
     * contract check like [WrongVariantCount] rather than one of them — which is also why it cannot
     * be [NothingGrounded]: that one holds itself to naming an offender and to an answer that made
     * claims, and reported with neither it would read as every one of nothing having been invented.
     *
     * **Fatal, which is the direction this build errs in, and it is a decision rather than a
     * capability.** The attempt could be answered as it stands — the prose is there and the variant
     * count is right — and it is refused anyway, because a reply with nothing to cite, a reply that
     * wrote its findings as prose, and one that honestly found nothing to say about a subject
     * retrieval returned material for are the same reply from outside. Nothing separates them, so
     * the choice is which to refuse, and a request answered as *nothing to answer from* understates
     * what was there where the alternative states something about the corpus that no check read and
     * no citation supports. It is also the one fault whose cost is a second model call bought on
     * policy, and whether the retry recovers the answer is unmeasured.
     *
     * @property of how many spans the attempt had retrieved, which is never nought: a reply
     *   asserting nothing about no material is not this fault, and [Runner] raises it only where
     *   something was retrieved. The proportion argument the citation faults carry, in the only form
     *   available here — an answer that asserted nothing about forty spans is a different event.
     */
    data class NothingAsserted(val of: Int) : Fault {
        override val fatal: Boolean get() = true

        init {
            // The house convention, and here it carries the whole of what makes this a fault. With
            // nothing retrieved there is nothing an answer could have asserted, so the honest
            // reading is the reply as it stands rather than a refusal — and the line this builds
            // would say nought spans were there, which reads as a fault nobody could have avoided.
            require(of > 0) {
                "An answer that asserted nothing about no material is not a fault: there was " +
                    "nothing to assert. This is raised where material was retrieved and none of " +
                    "it was asserted."
            }
        }
    }

    /**
     * An alternative with nothing to send was dropped, and the rest were offered.
     *
     * **What a reader picks is the whole of what they get.** A shape offering several alternatives is
     * answered by whichever one is chosen, on its own and with nothing beside it — so an alternative
     * carrying no composed text is not a worse answer, it is not one of the things being offered.
     * [Variant] itself permits prose to be blank where assertions carry the variant, and that is
     * right for the shape whose answer *is* its findings: a list of dated, cited items is a
     * rendering. Picked off a menu and pasted into a conversation it is not.
     *
     * **Not fatal, and the repair is a removal rather than a retry**, which is the same thing the
     * build already does with a variant the citation repair leaves empty. A reply offering three
     * where one was unusable still offers two; discarding all three spends a second model call to
     * arrive at *nothing to answer from* for an answer that was there. Where the removal leaves too
     * few to satisfy the shape there is nothing to offer, and [WrongVariantCount] reports that
     * instead — so this fault never stands for an answer that was refused.
     *
     * [unsendable] holds the rest of the reasoning: why it is scoped to the shapes that may offer
     * alternatives, and why the count behind it is re-asked after both removals.
     *
     * @property offered how many alternatives survived the citation repair, which is the set this
     *   drop was taken out of. Not the number of blocks the model wrote: that reply is what the
     *   citation faults describe, and a denominator mixing the two would say *one of three carried
     *   no text* about a reply whose other two had already gone.
     * @property dropped how many of those carried no text to send. Never nought, which is the clean
     *   reply, and never all of them, which leaves nothing to offer and is the count's fault.
     */
    data class AlternativesDropped(val offered: Int, val dropped: Int) : Fault {
        override val fatal: Boolean get() = false

        init {
            // The house convention, and here it is the whole of what makes this a repair rather than
            // a refusal. Nought dropped is the clean reply and would build a line naming no
            // alternative; dropping every one leaves a menu with nothing on it, which the clause
            // raising this has already sent to `WrongVariantCount`.
            require(dropped in 1..<offered) {
                "This fault reports alternatives dropped with some left to offer, and $dropped of " +
                    "$offered is not that reply."
            }
        }
    }

    /**
     * The model was called and nothing readable came back.
     *
     * **The one fault the runner cannot work out for itself, and the one the answer all but hides.**
     * A reply that read as no variants at all settles as insufficient evidence, which is the same
     * answer an empty corpus gives — so a request that paid for a truncation, a refusal or a pause is
     * indistinguishable from one that never called the model. That is correct as an *answer*: in both
     * cases there is nothing to stand behind. It is useless as a *report*, and this is what separates
     * them. The one case the answer separates for itself is a complete reply the reader rejected: it
     * is re-rolled, and a request given up after that says so in its own terms — see
     * [Answer.Unreadable].
     *
     * [outcome] whole rather than a stop reason and a string, because the four cases differ in what
     * they came back with and this is the evidence: an answer carries text that the format reader
     * rejected, a truncation and a pause carry what was produced before they stopped, a refusal
     * carries at most a category. Reduced to one nullable field they would all read as *something
     * or nothing*, and the reader of the log would be back to guessing which of four things
     * happened. Whatever reports this bounds what it prints — that is a decision about a log and
     * belongs where the log is.
     *
     * **Fatal where the model answered in full, and survivable otherwise — the one fault whose
     * verdict turns on what it carries.** An outcome that carries nothing readable by construction
     * is answered as it stands: a refusal refuses again, a truncation has no more room, and a pause
     * stopped mid-flight, so none has anything in it to repair and none is likely to go differently
     * on a second call. For those, reporting is the whole of what can be done.
     *
     * **A reply the provider reported complete is the one that can go differently, and this is where
     * it parts from them.** Not re-rolling was argued from a format the reader rejects being a
     * shape's instructions disagreeing with the format rather than a roll of the dice. That cause is
     * real and is addressed where it belongs — see [Reply.read], which no longer rejects a reply for
     * the way its first marker was reached — and what is left after it is the rest of the ways a
     * whole answer can fail to read, where the instructions and the format do agree and a second
     * call on the same prompt is the one thing with a good chance of parsing. It is also the
     * asymmetry worth closing: every other fatal fault is re-rolled, while the one raised on a paid
     * answer at the highest effort was given up on first sight, which makes it the most expensive
     * failure this path can produce.
     *
     * **The cost is the other half of that argument and is bounded.** A second call is bought only
     * where a complete answer came back unreadable, not on every request that cannot be answered —
     * and it draws on what the first attempt left of a per-request ceiling rather than on a fresh
     * one, so the request cannot cost more than it already could.
     *
     * What changes either way is that the cause is in the log instead of being inferred from a token
     * count.
     */
    data class Unreadable(val outcome: ModelOutcome) : Fault {
        // Read off what the call came back with rather than stored, so the verdict cannot be
        // constructed to disagree with the evidence beside it. The `init` below holds the one
        // outcome this turns on to the reader having really rejected it, which is what makes the
        // fatal half a checkable claim rather than a flag somebody set.
        override val fatal: Boolean get() = outcome is ModelOutcome.Answered

        init {
            // The house convention, and here it is the whole claim. Three of the four outcomes carry
            // nothing readable by construction; the fourth carries text, and this is only that
            // outcome's fault if the reader actually rejected it. Built on a reply that parses, it
            // would report a working answer as an unreadable one — and the reader is right here, so
            // the claim is checkable rather than merely documented.
            //
            // Nothing offered, because the question is whether the text read as alternatives at all
            // and no offer changes that answer. A citation is a number or it is not, whatever was
            // offered under it: one that resolves to nothing is carried on the claim rather than
            // dropping it, so a reply that parses with the request's own offer parses with none.
            require(outcome !is ModelOutcome.Answered || Reply.read(outcome.text, Offer.NONE).isEmpty()) {
                "The model answered and the reply read as alternatives, so nothing about it was " +
                    "unreadable. This fault says a call produced nothing that could be read."
            }
        }
    }

    /**
     * No claim was left resting on anything retrieved.
     *
     * Fatal: a reply whose every assertion names material that is not there was not answered from
     * the material at all, and giving its prose alone would offer a hedge as a finding.
     */
    data class NothingGrounded(
        override val cited: List<String>,
        override val of: Int,
        override val claims: Int,
        override val unreadable: List<String> = emptyList(),
    ) : Ungrounded {
        override val fatal: Boolean get() = true

        init {
            require((cited.isNotEmpty() || unreadable.isNotEmpty()) && claims > 0) {
                "A citation fault naming nothing at all, or on an answer that made no claims, is " +
                    "not a fault. Reported, it reads as every one of nothing having been invented."
            }
            Ungrounded.requireReportable(unreadable)
        }
    }

    /**
     * Some claims cited material that was never retrieved, and the rest of the answer stands.
     *
     * Those claims are gone from the answer the reader is given — see [Variant.grounded] — so what
     * arrives is the findings that could be checked, and this is the only record that the others
     * were made at all.
     */
    data class ClaimsDropped(
        override val cited: List<String>,
        override val of: Int,
        override val claims: Int,
        /** How many assertions went with them. */
        val dropped: Int,
        override val unreadable: List<String> = emptyList(),
    ) : Ungrounded {
        override val fatal: Boolean get() = false

        init {
            require((cited.isNotEmpty() || unreadable.isNotEmpty()) && dropped in 1 until claims) {
                "This is the fault an answer survives, so some of it must have gone and some of " +
                    "it must remain: $dropped of $claims claim(s) over ${cited.size} identifier(s) " +
                    "and ${unreadable.size} unreadable citation(s) is either the whole answer, " +
                    "none of it, or nothing at all."
            }
            Ungrounded.requireReportable(unreadable)
        }
    }

    /**
     * What the two citation faults share: which citations were absent, and out of how much.
     *
     * [of] and [claims] are carried because the bare offender list cannot be read: a single invented
     * identifier across twenty claims that rest on forty retrieved spans is a copying slip, and an
     * answer whose every claim cites something absent is a model answering from somewhere else
     * entirely. The proportion is the only thing that tells those apart, and it is what decides
     * which of the two faults this is.
     *
     * These count different things and none is a share of another: [cited] counts distinct
     * identifiers nothing retrieved, [unreadable] counts distinct citations that resolved to no number
     * at all, [claims] counts assertions — one of which may cite several — and [of] counts the spans
     * the check compared against, deduplicated as it deduplicates them. In particular [cited] does
     * not include [unreadable], so a line reporting either count must name which it is counting: a
     * bare figure beside the compared total reads as a comparison that happened, and for an
     * unreadable citation no comparison was reached.
     *
     * **[cited] names spans, not the numbers the prompt offered them under.** A number means nothing
     * once the request it was assigned in is over, so a line saying a claim cited 12 is unreadable an
     * hour later; what a reader of the log needs is which span was named, and that is what a span
     * identifier is for. The one exception is a number that was never offered, which has no span
     * behind it to name — there the number is all there was, and it is reported as written.
     */
    sealed interface Ungrounded : Fault {
        val cited: List<String>
        val of: Int
        val claims: Int

        /**
         * What the model wrote where a citation belongs that resolved to no number at all.
         *
         * **The one home for why this is not folded into [cited].** Both halves cost a claim alike, so
         * the counts above make no distinction; what a reader has to be able to tell is *which* went
         * wrong, because the two are repaired in different places. A number that read and named nothing
         * is a model slipping on one citation, and the answer around it stood. A citation that read as
         * no number is a model that stopped following the format, which is a question about the shape's
         * instructions rather than about its copying. Merged into [cited] it would also read as a third
         * kind of identifier, sending whoever holds the log looking up a name that was never one.
         *
         * Carried as the model wrote it up to `Runner.CITATION_EXCERPT` characters, cut and then
         * deduplicated where [Runner] builds this, because there is nothing to resolve it to and a log
         * line is not the place for a model's prose. The cut bounds each entry; the number of distinct
         * entries one answer can produce is not bounded, which that constant records.
         *
         * Empty for the ordinary case, where every citation read and some named material that was
         * not retrieved. Never empty when [cited] is: a fault names something, or it is not one.
         */
        val unreadable: List<String>

        companion object {
            /**
             * How much of one unreadable citation a report carries.
             *
             * Enough for a mangled number, a bracketed one, or the long identifier form a model
             * reverts to — which is the whole of what a citation can legitimately have been. A model
             * that wrote a sentence after the marker produces a piece far longer than that, and
             * reproducing it would put model prose in a log and in the answer trace, neither of which
             * is where composed text belongs.
             *
             * **This bounds each entry, not the list.** A reply that writes the same unreadable
             * citation on every claim line is one entry, because the entries are deduplicated; one
             * that writes a different sentence after every marker is an entry each, and nothing caps
             * how many. So the cut keeps any single entry from being prose and the deduplication
             * keeps the ordinary degraded reply short — neither is a ceiling on the worst case.
             *
             * **Owned by the fault rather than by the runner that fills it**, so the two invariants
             * it names are the type's and are required below rather than asserted in prose. Three
             * contracts described this bound while one expression in one caller provided it, which
             * is the arrangement the house convention exists to replace.
             */
            const val CITATION_EXCERPT = 48

            /**
             * Refuse a fault whose unreadable half is not fit to print.
             *
             * Both implementations call this, so the bound and the deduplication hold for every
             * fault that exists rather than for the ones the current caller happens to build. What a
             * reader of a log is owed is that no entry is a model's prose and that no entry appears
             * twice — the second because two citations cut to the same opening read as one mistake
             * repeated, and would have the count disagree with what is shown.
             */
            internal fun requireReportable(unreadable: List<String>) {
                require(unreadable.all { it.length <= CITATION_EXCERPT }) {
                    "An unreadable citation reached a fault report unbounded, at " +
                        "${unreadable.maxOf { it.length }} characters against a ceiling of " +
                        "$CITATION_EXCERPT. Cut each one where the fault is built: a log line and " +
                        "the answer trace both print this, and neither is a place for a model's prose."
                }
                require(unreadable.size == unreadable.distinct().size) {
                    "The same unreadable citation is reported twice, which reads as the model " +
                        "repeating one mistake and makes this fault's own count disagree with what " +
                        "a reader is shown: $unreadable."
                }
            }
        }
    }
}

/**
 * Where the machinery reports what it found wrong with an answer.
 *
 * **A seam rather than a log, because `:domain` depends on nothing** — it cannot reach a logging
 * library, and a layer that could would be the wrong one to decide how this reads. The process that
 * has a log wires one in; everything else stays silent and loses nothing.
 */
fun interface Faults {

    /** [reason] is what was wrong with attempt [attempt] of [shape], fatally or not. */
    fun noticed(shape: ShapeId, attempt: Int, reason: Fault)

    companion object {
        /** Reports nothing. The default, so a caller that does not log need not say so. */
        val SILENT = Faults { _, _, _ -> }
    }
}

/**
 * The answer for a request given up with this fault, stamped with [freshness].
 *
 * **Insufficient evidence wherever the system is left with nothing it can stand behind, which is
 * what an empty corpus leaves it with too** — a reply that could not be grounded, one carrying a
 * variant count the shape did not promise, one asserting nothing. They share that answer
 * deliberately. A complete reply the reader rejected is the case that does not fit: there the system
 * has an answer it could not read, and saying there was nothing to answer from asserts something
 * about the material that is false, because a model wrote about it at length.
 *
 * **Decided by the fault that ended the request rather than by any fault in it.** Every attempt is
 * reported already, so this is not where a reader learns what happened; what it decides is which
 * reply is honest about how the request finished. A first attempt nobody could read followed by a
 * second that could not be grounded ends as the second, because by then there was an answer and it
 * rested on nothing.
 *
 * **Exhaustive rather than one case against an `else`**, so that a fault added later states its own
 * answer instead of inheriting the one an empty corpus gives — which is the louder half to get
 * wrong, and the form [Fault.fatal] already takes on the same type.
 *
 * The residue is a re-roll the budget could not afford: the second attempt stops before the call,
 * answers as insufficient evidence carrying no fault of its own, and is finished as that, which is
 * the honest reading of a budget with nothing left in it. Reachable where one call spent most of the
 * per-request ceiling — nearest on the open-web shape, whose own ceiling is half of it and whose
 * turns accumulate every resumption into one outcome.
 *
 * An extension like the others below rather than a method, which is where this file keeps what is a
 * function of one value.
 */
private fun Fault.givenUp(freshness: CorpusFreshness): Answer = when (this) {
    is Fault.Unreadable -> Answer.Unreadable(freshness)
    // Some of these end a request and some cannot, and the branch is written over both. A fault the
    // runner treats as fatal arrives here whenever the retry raised it again, which is the ordinary
    // degrade path; a survivable one never does, because an attempt carrying it is answered as it
    // stands. The survivable ones are named anyway, because what an exhaustive `when` is for is
    // making the next fault choose its answer rather than inherit this one.
    is Fault.WrongVariantCount,
    is Fault.NothingAsserted,
    is Fault.AlternativesDropped,
    is Fault.NothingGrounded,
    is Fault.ClaimsDropped,
    -> Answer.InsufficientEvidence(freshness)
}

/**
 * The identifiers of everything retrieved, which is what a citation is checked against.
 *
 * A set because the check is membership and nothing else, and because one span retrieved twice
 * by two of a shape's queries is one piece of evidence rather than two.
 */
private fun Exchange.retrievedIds(): Set<SpanId> = retrieved.mapTo(mutableSetOf()) { it.id }

/**
 * What is wrong with the alternatives this answer would offer, or `null` if nothing is.
 *
 * **Asked of what the repairs leave rather than of what the model wrote, and that is the whole of
 * it.** Two things remove an alternative before a reader sees one: the citation repair drops a
 * variant that is nothing but assertions once they all fail — see [Variant.grounded], where
 * returning `null` rather than an empty variant is the difference between dropping an alternative
 * and a crash — and this rule drops one that has no text to send. Both leave a count, and the
 * shape's promise is about the alternatives a reader is *offered*, not about the blocks the model
 * typed. Read off what was typed, the count passes and a two-alternative reply reaches the
 * presentation mapping as one variant, which renders through the text outcome with the assertions
 * folded back into the prose — the defect this whole file's menu branch exists to prevent.
 *
 * So the count is re-asked here, after both removals, and that is what closes it.
 *
 * **An alternative with no text is dropped where the rest still satisfy the shape, and refused
 * where they do not.** Dropping is what the build already does with a variant nothing is left of,
 * and a reply that offered three where one was unusable still offers the two that were: discarding
 * those spends a second model call to arrive at *nothing to answer from* for an answer that was
 * there. Where too few remain there is nothing to offer, which is the count's own fault to report
 * rather than a second one saying the same thing.
 *
 * **Scoped to the shapes that may offer alternatives, and the scoping is the whole of its
 * correctness.** Read across every shape it would refuse the claims-only reply [Variant]'s
 * initialiser was widened to allow — a list of dated, cited items, which is a rendering for the
 * shape whose answer is its findings and reached a reader as *nothing to answer from* when prose
 * was required. Only an alternative somebody picks has to stand on its own.
 *
 * **Read off the range rather than [Shape.assertsFindings], which asks a different question.** That
 * one says what an answer is made of, and says so because only the shape can. This asks whether a
 * reader picks one of several, which is what a range whose upper bound exceeds one states outright.
 *
 * The cost is grounding the variants a second time for every attempt of such a shape, over a list
 * the same shape bounds at three.
 */
private fun Answer.Composed.unsendable(shape: Shape, exchange: Exchange): Fault? {
    if (shape.variants.last <= 1) return null
    val surviving = variants.mapNotNull { it.grounded(exchange.retrievedIds()) }
    val sendable = surviving.count { it.prose.isNotBlank() }
    return when {
        // Ahead of the drop report, because a drop that leaves nothing to offer is not a repair.
        // This is the same fault the clause before the model is called raises, raised again of what
        // survived: the promise is about what a reader is offered, so it is the same promise read at
        // the only point where that is known.
        sendable !in shape.variants -> Fault.WrongVariantCount(wanted = shape.variants, got = sendable)
        // Counted over what survived the citation repair rather than over what the model wrote, so
        // the two numbers cannot disagree about which set is being described: a reply whose every
        // alternative was assertions alone loses most of them to that repair and is reported by the
        // clause above, where saying *one of three carried no text* would read as a model that
        // complied for the other two.
        sendable < surviving.size ->
            Fault.AlternativesDropped(offered = surviving.size, dropped = surviving.size - sendable)
        else -> null
    }
}

/**
 * The spans this answer's claims cite, which is empty for every case but a composed one.
 *
 * Every other case makes no claims, so there is nothing in them that could cite anything. Composed
 * prose carrying no claims is empty too, which a shape that composes is allowed and one whose answer
 * is its assertions is refused outright — so an empty set here never reaches a reader as an
 * unchecked assertion. The drafting shape is not an instance of it: it is told to write what it
 * states about the history as cited lines beside the draft, so its replies ordinarily do carry
 * claims, and what keeps them out of the text somebody sends is the presentation mapping.
 */
private fun Answer.cited(): Set<SpanId> = when (this) {
    is Answer.Composed -> variants.flatMapTo(mutableSetOf()) { variant -> variant.claims.flatMap { it.cites } }
    is Answer.InsufficientEvidence,
    is Answer.Unreadable,
    is Answer.SubjectUnclear,
    is Answer.SubjectUnknown,
    is Answer.SubjectNearest,
    is Answer.SubjectUnread,
    is Answer.SubjectMissing,
    -> emptySet()
}

/**
 * The numbers this answer's claims cited that nothing was offered under.
 *
 * Collected here rather than in [CitationCheck], which compares span identifiers against the
 * retrieved set and nothing else. There is nothing here to compare: the offer is what a number was
 * resolved against, that happened where the reply was read, and what reaches this point is the
 * verdict rather than the question.
 */
private fun Answer.Composed.unoffered(): Set<Int> =
    variants.flatMapTo(mutableSetOf()) { variant -> variant.claims.flatMap { it.unoffered } }

/**
 * What this answer's claims wrote where a citation belongs that could not be read as one.
 *
 * Collected here for the reason [unoffered] is, and one step further from a comparison: a number
 * outside the offer at least resolved far enough to be looked up and missed, where this never was a
 * number. There is nothing to check it against anywhere, so what the report carries is the text.
 */
private fun Answer.Composed.unreadable(): Set<String> =
    variants.flatMapTo(mutableSetOf()) { variant -> variant.claims.flatMap { it.unreadable } }

/**
 * Who wrote the material behind each span this answer's surviving claims cite, and when.
 *
 * **Asked of what survived rather than of what was retrieved.** A request offers the model far more
 * material than its answer ends up citing, and which claims survive is decided before this runs —
 * so keying the read on the citations costs one query over a handful of records instead of over
 * everything a ranked read returned.
 *
 * **Only the message spans are asked about.** The read is keyed by a message, and a span naming a
 * document is not one; asked for it, an absent entry would mean something different from an author
 * the corpus has no name for while being indistinguishable from it. Such a span is attributed by
 * its date alone, which is the honest answer for material nobody wrote as a turn.
 *
 * A cited span absent from the retrieved material gets no entry. Grounding has already removed
 * every claim resting on one, so this cannot drop a surviving citation — what it does is leave the
 * dependency on that ordering visible rather than invent a date for a span nothing read.
 */
/**
 * Let a real cancellation keep travelling, where a store failure is swallowed.
 *
 * A cancellation is the request going away rather than the corpus being unreadable, and caught it
 * would turn an abandoned call into an answer somebody is rendering. Tested against the active
 * context for the reason ingestion's own loop gives: a client library that reports its own timeout
 * this way is a failure rather than a cancellation, and a context still active says which.
 *
 * Its own function because the alternative is an `is` check inside the catch, which the style rules
 * refuse — and because the two readers of this pattern in the other modules spell it the same way.
 */
private suspend fun rethrowIfCancelled(failure: Throwable) {
    if (failure is CancellationException && !currentCoroutineContext().isActive) throw failure
}

@Suppress("TooGenericExceptionCaught")
private suspend fun Answer.Composed.attributed(
    retrieval: Retrieval,
    exchange: Exchange,
): Map<SpanId, Attribution> {
    val cited = variants.asSequence().flatMap { it.claims }.flatMap { it.cites }.toSet()
    if (cited.isEmpty()) return emptyMap()
    val written = exchange.retrieved.associate { it.id to it.sourceTime }
    // **The one read on this path that must not be able to cost an answer.** Every other corpus
    // read a request makes happens in an assembly stage, before the model is spent, where a failure
    // loses nothing that was paid for. This one happens after both calls and after grounding
    // accepted what they produced — so letting it throw would discard a complete, checked answer
    // over a dropped connection, and a retry would buy the generation a second time. The answer is
    // what the request is for; who wrote the material behind it is what makes the answer better.
    //
    // **What a failure costs is the names and nothing else.** When each piece of material was
    // written is already in hand — it came back with the material itself — so every finding keeps
    // its age and loses only its author, which is the state an interface renders anyway for a turn
    // the corpus holds no name for.
    val turns = try {
        retrieval.turnsOf(cited.filter { it.kind == RecordKind.MESSAGE }.map { it.record }.distinct())
    } catch (failure: Exception) {
        rethrowIfCancelled(failure)
        emptyMap()
    }
    return cited.mapNotNull { span ->
        written[span]?.let { span to Attribution(sourceTime = it, author = turns[span.record]?.author) }
    }.toMap()
}
