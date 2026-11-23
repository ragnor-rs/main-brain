package io.github.ragnorrs.mainbrain.domain.entity

/**
 * Statements, by the entity they are about.
 *
 * The derived layer's other half: identity says who someone is, and this says what is known about
 * them. Nothing here writes to the corpus and nothing here reads a source.
 *
 * As everywhere else in this layer, a write is an upsert and a re-run writes nothing new. What
 * makes that true here is that a statement's key is its content — see [Statement] — so there is no
 * identifier to mint, nothing to renumber, and no way for the same fact read twice to become two
 * facts.
 */
interface StatementRepository {

    /**
     * Record [statements] as the whole of what holds about the subjects and predicates they cover.
     *
     * **This is a replacement per subject and predicate, not an addition.** Every statement given
     * is written, and a stored statement under one of the same `(subject, predicate)` pairs that
     * is *not* given is superseded and removed. A pair the argument does not mention is untouched,
     * so a caller writing one predicate cannot disturb another.
     *
     * That is a strong precondition and it is the caller's to meet: hand this a partial set and it
     * deletes the rest. It is stated this way because the alternative is worse. A statement's key
     * is its content, and the start of its window is part of that content — so when a backfill
     * delivers an *older* reading of a value already stored, resolution correctly recomputes the
     * window as opening earlier, and that is a different key. Written as a pure addition, the
     * earlier-opening row would be inserted beside the row it supersedes: two rows, the same
     * value, overlapping windows, one of them holding half the evidence. Nothing could then tell
     * which was current, and the acceptance this layer is built on — that re-running duplicates no
     * statement — would be false under the ordinary behaviour of the backfill it is built for.
     *
     * [resolved][io.github.ragnorrs.mainbrain.domain.recognition.resolved] produces exactly such a whole
     * set, per subject and predicate, which is why it is what the pass hands over.
     *
     * **Removing a superseded row is not a removal this layer forbids.** The corpus rule is about
     * what a source said and nothing here touches it; this is a judgement this layer made, unmade
     * by a better reading of the same evidence — the same unmaking an unmerge performs on a merge
     * link. No evidence pointer is lost with it: the surviving statement carries the union, which
     * is why the removal happens after the writes rather than before.
     *
     * **Evidence accumulates and is never replaced.** A second span saying the same thing is
     * corroboration, so it is added; the same span offered again — which a re-derivation does
     * whenever the pass's version moves — is already there and changes nothing.
     *
     * **A stored close is never reopened, though it may move.** [Validity.until] sits outside the
     * key so that closing a window is an update rather than a delete, and the rule runs one way: a
     * statement offered with an open window against one already closed leaves the close alone,
     * while a close offered against an open or later one takes effect. Without the first half, a
     * pass re-deriving an old fact in a batch that happens not to contain the later, contradicting
     * one would reopen a window that was correctly shut. Without the second, a backfill landing a
     * contradiction between two stored windows could not narrow the earlier of them, and the two
     * would both cover the instants in between.
     *
     * **Answers with what it did to the rows, and this is the port where all three figures move.**
     * [Stored.written] is the windows that were not held before, [Stored.overwritten] the ones that
     * were and have been replaced, and [Stored.superseded] the stored rows the precondition above
     * removed. The last is the figure nothing else in this build can produce: the removal is this
     * port's, computed from the difference between what it holds and what it was given, so a caller
     * counting what it handed over cannot see it happen at all.
     */
    suspend fun record(statements: List<Statement>): Stored

    /**
     * [statements] as the whole of what holds, with a close this layer computed free to come off.
     *
     * **The one caller allowed to reopen a window, and only because of what it knows.** [record]
     * refuses to: a pass re-deriving an old fact in a batch that happens not to hold the later,
     * contradicting one would otherwise unclose a window that was correctly shut, and that rule is
     * what keeps the ordinary path safe. The collapse is the case the rule was not written for. Its
     * close was computed from two values this layer took to be different, a paid verdict has since
     * established that they are one wording of one fact, and re-windowing them correctly yields a
     * single window that is still open. Left closed, the attribute reads as having ended with
     * nothing current — and permanently, because re-resolving is idempotent and offers the same
     * open window on every run.
     *
     * So what comes off is this layer's own earlier reading of the same evidence, which is the
     * removal `V10` already permits and names: the unmaking of a judgement rather than of a fact.
     * The corpus said nothing about it either way, and the surviving row carries the evidence of
     * both wordings.
     *
     * Otherwise exactly [record]: a replacement per subject and predicate, with the same
     * precondition on the caller, the same three figures answered, and the same meaning for each —
     * hand it a partial set and it deletes the rest.
     */
    suspend fun recollapsed(statements: List<Statement>): Stored

    /**
     * Every statement about any of [subjects], each with all of its evidence.
     *
     * The whole set rather than a filtered slice, because that is what both callers need.
     * Resolution has to see every window under a subject and predicate before it can decide which
     * of them a new reading contradicts — handed only its own batch, it would close a window
     * against a statement it could not see and produce overlapping claims. And anything answering
     * "what was true when we last spoke" filters these by [Statement.heldAt], which is a
     * comparison on values and needs no query of its own.
     *
     * Workable because statements are per-entity and few: a contact carries the handful of
     * attributes and relations read out of the conversations it appears in, not a history of the
     * corpus. A subject that ever grows past that wants a query here rather than a filter at the
     * caller.
     */
    suspend fun about(subjects: List<EntityId>): List<Statement>

    /**
     * Every verdict that two wordings under any of [subjects] state one fact.
     *
     * **Read beside [about] and handed to resolution with it**, because a reworded value is not a
     * duplicate the windowing can collapse on its own: compared for equality it is a *changed*
     * value, so the earlier window closes and the fact reads as having changed when only the words
     * did. What makes the two readings one is this, and nothing else.
     *
     * Only the verdicts that say *same*. A pair the model kept apart is recorded too — see
     * [decide] — but resolution has nothing to do with it: two values it does not know to be one
     * are two values, which is already what it assumes.
     */
    suspend fun synonymsOf(subjects: List<EntityId>): List<ValueSynonym>

    /**
     * Up to [limit] pairs of values under one subject and predicate that might be one fact.
     *
     * **The cheap filter and not the comparison.** What comes back is every pair of values stored
     * under one subject and predicate that no verdict covers — most of which are a genuine
     * contradiction, since that is what two values of one relation usually are. Which of them are
     * worth paying to decide is settled outside the store, by a vector comparison; which of them
     * *are* one fact is settled by the model.
     *
     * A pair any answer covers is not returned, whichever way it went and whichever tier gave it —
     * a vector rule-out until [comparedBy] moves, a paid verdict until [adjudicatedBy] does. Both
     * are recorded: this query is oldest-first over a bound, so a pair nothing records comes back at
     * the head of the queue for ever and the pairs behind it are never reached. The finding side
     * draws the same two, for the reasons [CollapseRepository.candidates] states.
     */
    suspend fun valueCandidates(limit: Int, comparedBy: String, adjudicatedBy: String): List<ValuePair>

    /**
     * How many value pairs are still undecided, in full rather than up to a limit.
     *
     * What the driver reads as the collapse's remaining work on this side, for the reason
     * [CollapseRepository.candidateCount] gives: a bounded read cannot say whether a sweep made
     * progress.
     */
    suspend fun valueCandidateCount(comparedBy: String, adjudicatedBy: String): Int

    /**
     * Record what decided [pair], and which way.
     *
     * **Both answers are worth storing, and that is the point of [same].** A verdict of *different*
     * buys nothing that can be seen in the data — the two values were already two — but it is what
     * stops the pair being submitted on every idle sweep for the rest of the deployment. A refusal
     * is recorded the same way and for the same reason a declined window is settled.
     *
     * [decidedBy] is the version of whichever tier answered — the adjudication version, or the
     * vector version whose vectors ruled the pair out. Either answer is retired when its own
     * version moves, which is the one rule [CollapseRepository.keepApart] states for both tiers.
     * **This side does not consult every bound that version carries**, having no merge tier:
     * a value pair is handed to the model or left alone. So a move to the bound it never reads
     * retires its rule-outs anyway — over-coverage, for the reason `vectorsVersion` gives.
     * Only `same` answers are read back as synonyms, so a vector rule-out tells resolution nothing
     * and is stored only to keep the queue draining.
     *
     * **[synonymsOf] reads every `same` row and not only the live ones**, so a verdict whose
     * version has moved goes on folding two wordings into one value while the pair is simultaneously
     * offered as owed — two reads disagreeing about one row. It is a window and not a fork: the
     * pair is re-decided by the stage that is here now, and the answer replaces this one. Filtering
     * the read instead would un-merge a value on the deploy that moved the version and re-merge it
     * on the sweep that re-bought the verdict, which is the worse of the two for anybody reading an
     * answer in between.
     *
     * The two values are stored in one order whichever order they arrive in, so one unordered pair
     * is one row — and the store is what decides that order, because ordering text is a question of
     * collation and a caller using its own would disagree with the index. A pair decided twice is
     * one row: the later answer replaces the earlier.
     */
    suspend fun decide(pair: ValuePair, same: Boolean, decidedBy: String)
}

/**
 * Two values of one subject and predicate, as a question nobody has answered yet.
 *
 * The statement side of the collapse, and it names values rather than rows because a statement has
 * no identity to name: it *is* its content. So what can be asked about two readings of one fact is
 * whether their two wordings mean one thing, and what can be recorded is the answer to that.
 */
data class ValuePair(
    val subject: EntityId,
    val predicate: Predicate,
    val one: String,
    val other: String,
) {
    init {
        require(one != other) { "A value is not a candidate duplicate of itself." }
        require(one.isNotBlank() && other.isNotBlank()) { "A blank value states nothing to compare." }
    }
}

/**
 * Two values of one subject and predicate that the model read as stating one fact.
 *
 * Distinct from [ValuePair], which is the same shape before anything decided it: this one is an
 * answer that was paid for, and the only thing that makes resolution treat two wordings as one
 * value.
 */
data class ValueSynonym(
    val subject: EntityId,
    val predicate: Predicate,
    val one: String,
    val other: String,
) {
    init {
        require(one != other) { "A value is not a synonym of itself, and recording it as one decides nothing." }
    }
}
