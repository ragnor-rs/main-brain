package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.FindingPair
import kotlin.math.sqrt

/**
 * Which of two readings of a fact are the same fact, decided as cheaply as the pair allows.
 *
 * **The comparison is ordered by cost, and this is the middle tier.** The exact one is already made
 * by the store: a claim is the key, so an identical reading in one conversation collides on write
 * and accumulates evidence instead of becoming a second row, and no pair ever reaches here for it.
 * What reaches here is every pair the store cannot decide — two survivors of one conversation and
 * kind, stated near each other — and most of them are simply two different facts that happened to
 * be mentioned together.
 *
 * **A rewriting is why the exact tier is not enough.** Restatement turns a finding into a sentence
 * that stands on its own, and a re-derivation under a moved prompt version restates the same
 * finding in words it did not choose the first time. The two strings share no lexical signal worth
 * keying on — a shingle or an edit distance answers for a misspelling and not for a rewording — so
 * the comparison that answers is a vector one, which the embeddings sidecar already provides.
 *
 * **Three outcomes and not two, because the model is the last tier and it costs money.** A pair the
 * vectors put beyond doubt is merged with no call at all; a pair they put near is handed to the
 * model, which is the only thing that can read two sentences and say whether they are one
 * obligation; and a pair they put apart is left for the caller to record, against
 * [vectorsVersion], so that it is compared again when the arithmetic that put it apart moves and
 * not on every idle sweep.
 *
 * It is a pure function over the pairs and their vectors: no port, no model, no clock. Everything it
 * decides on is in front of it, which is what makes the thresholds below checkable rather than a
 * matter of trust.
 */
fun collapsed(candidates: List<FindingPair>, vectors: Map<String, List<Float>>): Collapse {
    val merge = mutableListOf<FindingPair>()
    val ask = mutableListOf<FindingPair>()
    // Every reading a decision in this batch has already moved away. A pair naming one on either
    // side is skipped: merging onto a row that has itself been merged away would undo the collapse
    // that moved it, and asking about one would pay to adjudicate a finding that is no longer a
    // finding of its own.
    val gone = mutableSetOf<EntityId>()
    // Every reading a decision in this batch is merging *onto*, which may not then be merged away
    // by a later one.
    //
    // **Without this a batch can decide a chain, and a chain is only safe if its merges are applied
    // in the order they were decided.** Three readings of one fact can pair up as the last onto the
    // middle and the middle onto the first; applied in that order the evidence reaches the first
    // row transitively, and applied in the other order the last reading's spans are attached to a
    // row that is no longer a survivor and vanish from every read. Nothing in the store's interface
    // promises an order, so the decision is kept flat instead: every merge in a batch points at a
    // reading the batch leaves alone, and the chain resolves over two sweeps.
    val kept = mutableSetOf<EntityId>()

    // Ordered, so that a batch holding two decisions about one reading takes the same one on every
    // run. Strongest first, because the pair this is surest about is the one that should get to
    // claim a reading when two pairs want it; the identifiers break a tie so the order is total and
    // does not depend on what the store returned first.
    candidates
        .mapNotNull { pair -> similarityOf(pair, vectors)?.let { Scored(pair, it) } }
        .sortedWith(
            compareByDescending<Scored> { it.similarity }
                .thenBy { it.pair.survivor.id }
                .thenBy { it.pair.superseded.id },
        )
        .forEach { (pair, similarity) ->
            if (pair.survivor.id in gone || pair.superseded.id in gone) return@forEach
            // The reading this pair would move away is one another decision is moving onto, so
            // applying it would turn two merges into a chain. Left for a later sweep, by which
            // time the other decision has been applied and the two readings compared again.
            if (pair.superseded.id in kept) return@forEach
            when {
                similarity >= SAME_FACT -> {
                    merge += pair
                    gone += pair.superseded.id
                    kept += pair.survivor.id
                }
                // The superseded side is claimed here too, though nothing is merged yet: a pair
                // handed to the model is a decision this batch has committed to buy, and offering
                // the same reading to a second pair would pay twice to decide where it belongs.
                // The reply arrives a sweep later and either merges or records a difference.
                similarity >= WORTH_ASKING -> {
                    ask += pair
                    gone += pair.superseded.id
                    kept += pair.survivor.id
                }

                else -> Unit
            }
        }
    return Collapse(merge, ask)
}

/**
 * Whether two values of one relation are close enough to be worth paying to decide.
 *
 * **The statement side reaches the model or nothing, and has no merge tier of its own.** Two values
 * of one subject and predicate are usually a deliberate contradiction — a rate quoted in March and
 * revised in June is exactly what a validity window holds — and two short values close in wording
 * are the pair a vector comparison is least able to tell from a rewording. Merging one of those
 * would delete the history the statement table exists for, where the same mistake on a commitment
 * costs a row. So the vectors only choose what to ask about, and [SAME_FACT] is deliberately not
 * consulted here.
 *
 * `false` where either value was not embedded, for the reason an uncompared pair is not merged: a
 * pair nothing compared is not a near miss, and paying to decide it would spend the budget on
 * whatever the sidecar happened to drop.
 */
fun worthAsking(one: String, other: String, vectors: Map<String, List<Float>>): Boolean {
    val first = vectors[one]
    val second = vectors[other]
    return first != null && second != null && cosine(first, second) >= WORTH_ASKING
}

/**
 * How alike the two claims of [pair] are, or `null` where either was not embedded.
 *
 * `null` rather than zero, which would read as a decision that the two are different: a claim with
 * no vector was not compared at all, and the pair has to come back rather than be ruled out by an
 * answer nobody computed.
 */
private fun similarityOf(pair: FindingPair, vectors: Map<String, List<Float>>): Double? {
    val one = vectors[pair.survivor.claim]
    val other = vectors[pair.superseded.claim]
    return if (one == null || other == null) null else cosine(one, other)
}

/**
 * The cosine of the angle between two vectors, in -1 to 1.
 *
 * **Not assumed normalised.** The port promises a dense vector and says nothing about its length,
 * so a model that returns an unnormalised one would otherwise make every similarity read high and
 * the merge tier fire on everything. Vectors of different widths, or a zero vector, compare as
 * `0.0`: the first is two embedding versions in one comparison and the second cannot have an angle,
 * and in both cases the honest answer is that nothing was established.
 */
internal fun cosine(one: List<Float>, other: List<Float>): Double {
    if (one.size != other.size) return 0.0
    var dot = 0.0
    var oneLength = 0.0
    var otherLength = 0.0
    one.indices.forEach { index ->
        val a = one[index].toDouble()
        val b = other[index].toDouble()
        dot += a * b
        oneLength += a * a
        otherLength += b * b
    }
    val scale = sqrt(oneLength) * sqrt(otherLength)
    return if (scale == 0.0) 0.0 else dot / scale
}

/**
 * What the cheap tier's answers depend on, so that moving any of it retires them.
 *
 * **Everything this tier weighs, which is the embedding version *and* its bounds.** What it
 * decides on is a cosine over two vectors held against [SAME_FACT] and [WORTH_ASKING]: the vectors
 * come from the sidecar, and the arithmetic that reads them is these bounds. A version naming
 * only the sidecar would leave a bound outside every version, so moving one ships a behavioural
 * change that invalidates nothing — the pairs put apart under the old arithmetic stay put apart,
 * nothing is re-compared, and the store keeps answers no deployed stage would reach again. That is
 * the failure a version exists to prevent.
 *
 * **The bounds are printed rather than digested**, which is where this departs from
 * `Extractor.adjudicationVersion` without departing from its shape. A digest covers a thing too
 * large to put in a column; a bound is smaller than its hash and says which arithmetic
 * decided, so a person reading `decided_by` can tell. What a digest would add is nothing, and what
 * printing adds is that the test which refuses an uncovered bound can look for the value itself.
 *
 * [VECTORS] goes in front for the reason `Extractor.ADJUDICATION` does: both tiers' answers
 * share one column, so each value has to name the stage that produced it or a query honouring one
 * would honour the other. It carries a revision for the same reason too — what no digest covers is
 * a change to the comparison itself, and [cosine] or the ordering in [collapsed] moving is exactly
 * that.
 *
 * **The bounds are parameters so that a case can move one**, which is the only way to show the
 * property rather than assert it: they are compile-time constants, so a test that could not pass a
 * different value could only re-derive this string and compare it with itself.
 *
 * **None of them is defaulted**, which is the part that keeps this honest rather than merely tidy.
 * A bound added later as one more optional parameter would compile at every call site and reach no
 * version — failing open on the one thing this function exists to close. Required, it cannot be
 * added without every caller naming it.
 *
 * **One version over both sides of the collapse, and the statement side consults only one bound.**
 * The findings' tiers weigh both; a value pair is only ever handed to the model or left alone, so
 * [SAME_FACT] decides nothing there. Moving it therefore retires value rule-outs it did not decide
 * — over-coverage, taken deliberately: both sides are a local comparison whose re-derivation costs
 * no model budget, and a version per side would be a second value space in the same column for no
 * answer it could change.
 */
fun vectorsVersion(
    embeddingVersion: String,
    sameFact: Double,
    worthAsking: Double,
): String = "$VECTORS:$embeddingVersion@$sameFact/$worthAsking"

/**
 * The version the build runs under: [vectorsVersion] over the bounds this tier actually weighs.
 *
 * What every caller but a test wants, and the one place the running bounds are read into a version
 * — so the string can only ever name the arithmetic [collapsed] and [worthAsking] perform.
 */
fun runningVectorsVersion(embeddingVersion: String): String =
    vectorsVersion(embeddingVersion, sameFact = SAME_FACT, worthAsking = WORTH_ASKING)

/**
 * What names the cheap tier in [vectorsVersion].
 *
 * A shape and a revision, as `Extractor.ADJUDICATION` is. The revision is what moves where the
 * meaning of a rule-out changes without either bound being touched: a change to how the similarity
 * is computed, to which claims go into the cosine, or to the order a batch decides in. The bounds
 * need no help from it, being in the value themselves.
 *
 * Not the collapse window, which decides which pairs are offered rather than what a comparison
 * answers about one — see `Inferrer.InferenceBounds.collapseWindow`, which states why nothing
 * stored becomes wrong when it moves.
 */
const val VECTORS = "vectors-1"

/** One candidate pair with the comparison made, before a threshold has decided anything. */
private data class Scored(val pair: FindingPair, val similarity: Double)

/**
 * What a batch of candidates came to: what to merge now, and what to pay to decide.
 *
 * [merge] is reached without a model call, which is the property the cost ordering exists for and
 * the one worth asserting about this type: a deduplication that cost a call per pair would be
 * dearer than the readings it collapses.
 *
 * [ask] is the pairs the vectors could not settle. Everything else the batch held is absent from
 * both, which is this tier's own answer rather than a deferral: the caller records it against
 * [vectorsVersion], so the pair is compared again when that moves and not on every idle sweep.
 */
data class Collapse(val merge: List<FindingPair>, val ask: List<FindingPair>)

/**
 * The similarity at or above which two claims are one fact, with nothing asked.
 *
 * **Unmeasured, and deliberately set high.** Nothing in this build has yet scored the sidecar's
 * model on paraphrase pairs from the corpus, so this is a bound chosen to fail in the affordable
 * direction: set too high it sends a true duplicate to the model, which costs one call and reaches
 * the same answer, and set too low it merges two different obligations into one row and the second
 * is unreachable until somebody clears the column. The first mistake is money and the second is
 * data, so the bias is toward asking.
 *
 * What would calibrate it is the evaluation set: a fixture corpus holding known paraphrase pairs and
 * known distinct ones, scored at a range of values. Until that exists this is a guess, and the
 * figure the model tier charges for is the measure of how wrong it is.
 *
 * **It is in [vectorsVersion], so moving it costs a re-comparison of every pair the vectors have
 * ruled out.** That is the price of the bound being a guess that may be corrected: a change here
 * decides pairs differently, so the answers it no longer gives have to be taken again.
 */
const val SAME_FACT = 0.97

/**
 * The similarity at or above which a pair is worth paying to decide.
 *
 * Below it the two claims are taken to be different facts and nothing is spent. This is the bound
 * that sets what the stage costs: every pair between here and [SAME_FACT] is one model call, and a
 * conversation where business is discussed daily produces many pairs that are near without being
 * the same thing. Lowering it buys recall at a call per pair, against a budget that is finite.
 *
 * It is also the cheap tier's own answer and not a deferral, which is why a pair ruled out here is
 * written down — against [vectorsVersion], like any other answer this tier gives. Selection is
 * oldest-first over a bound, so a pair nothing records would come back at the head of the queue on
 * every sweep and the pairs behind it would never be reached; see `CollapseRepository.keepApart`.
 *
 * **It is in [vectorsVersion] too**, and for a sharper reason than [SAME_FACT]: this is the bound
 * that sets what the stage costs, so it is the one a deployment has an active reason to move.
 * Lowering it to buy recall has to offer the pairs it previously ruled out, or the recall is bought
 * for new pairs only and the corpus keeps the answers given under the bound that was replaced.
 */
const val WORTH_ASKING = 0.88
