package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.entity.Signal
import io.github.ragnorrs.mainbrain.domain.entity.SignalKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.ObservedIdentifier
import java.time.Duration
import kotlin.math.pow

/**
 * How much evidence there is that two person records are one human.
 *
 * Scored rather than binary, and the score is a sum of what the two records agree on. Each
 * comparable signal contributes a weight, and a pair reaching [Weights.threshold] merges without
 * asking anyone — there is no queue for a person to approve, because nobody would work it.
 *
 * Three properties decide whether this survives real data, and each is a test rather than a
 * remark.
 *
 * **A signal neither record offers contributes nothing.** Person records are sparse: most
 * participants in a chat offer a handle and no more. Counting an absent field against a pair
 * would drift every sparse pair towards "different people", which is every pair in the corpus.
 *
 * **A comparable signal that disagrees also contributes nothing.** One human holds two numbers
 * and three handles, so disagreement is not evidence of difference — it is the ordinary state of
 * a person with more than one account. What separates two humans is a hard negative, and that is
 * [Reconciler]'s to apply: it needs conversation membership, which a pair of records cannot see.
 *
 * **Weak signals are scaled by how rare they are.** A name shared by thirty records is near-zero
 * evidence and a name shared by two is meaningful, and the same holds for a switchboard number.
 * The corpus supplies that denominator without anyone labelling data, which is why [Frequency]
 * is an argument rather than a constant.
 *
 * Accumulated as a sum and not divided by the evidence available. Dividing is the other reading
 * of "normalised over the comparable signals", and it collapses every single agreement to the
 * same score — a shared handle would then weigh exactly as much as a shared phone number, and
 * the weights below would order nothing. Absence is already neutral because it adds zero, which
 * is what the normalising was for.
 *
 * The weights are judgement, recorded as judgement, and only the denominator is measured. What
 * that judgement is answerable to is `MatchAccuracyTest`: pairs carrying the signals this source
 * can offer, each with a verdict read off it, which the weights and [Weights.threshold] together
 * have to reproduce. The sample is small and says so, and it is a sample rather than an argument.
 *
 * **That gate is here and not in the evaluation set.** Identity makes no model call, so its
 * accuracy can be asserted hermetically, for nothing, on every merge. The evaluation set is for
 * the dimensions a model decides; it spends, and it is asked for rather than automatic. A
 * deterministic property owned there would be measured only when somebody remembered to buy it.
 */
class MatchScorer(private val weights: Weights = Weights.STARTING) {

    /**
     * What [a] and [b] agree on, and whether that is enough to merge them.
     *
     * [frequency] is counted over person records, and a value it does not mention is taken to be
     * claimed by these two alone — the most generous reading, and the right default for a
     * caller that counted only the candidates it was comparing.
     */
    fun score(a: MatchCandidate, b: MatchCandidate, frequency: Frequency = Frequency()): Match {
        require(a.record != b.record) {
            "A record cannot be matched against itself: every signal would agree and the pair " +
                "would merge a contact into itself."
        }
        val signals = buildList {
            addAll(claimSignals(a, b, frequency))
            nameSignal(a, b, frequency)?.let(::add)
        }
        val total = signals.sumOf { it.weight }
        return Match(score = total, signals = signals, merges = total >= weights.threshold)
    }

    /**
     * One signal per claim type the two records agree on.
     *
     * Agreement is on the normalised value, which is the only form the store holds — an
     * identifier that reached it unnormalised would match nothing and be invisible rather than
     * wrong. Where both records carry several claims of one type, the strongest agreement wins:
     * a second number in common is the same evidence stated twice, not twice the evidence.
     */
    private fun claimSignals(a: MatchCandidate, b: MatchCandidate, frequency: Frequency): List<Signal> =
        SignalKind.entries.mapNotNull { kind ->
            val type = kind.claim ?: return@mapNotNull null
            val shared = a.claims.filter { it.identifier.type == type }
                .flatMap { mine -> b.claims.filter { it.identifier == mine.identifier }.map { mine to it } }
            shared.mapNotNull { (mine, theirs) -> claimSignal(kind, mine, theirs, frequency) }
                .maxByOrNull { it.weight }
        }

    private fun claimSignal(
        kind: SignalKind,
        mine: ObservedIdentifier,
        theirs: ObservedIdentifier,
        frequency: Frequency,
    ): Signal? {
        val rarity = rarity(frequency.claimants[mine.identifier])
        // Only a number decays. A handle or a hash agreeing across a long gap is the same
        // evidence it ever was, because neither gets reassigned to somebody else.
        val decay = if (kind == SignalKind.PHONE) decay(gap(mine, theirs)) else 1.0
        return Signal(kind, weights.of(kind) * rarity * decay).takeIf { it.weight > 0.0 }
    }

    /**
     * The name signal, scaled by how many person records carry the same name.
     *
     * Weak on its own by design: a display name is what a human typed into a profile field, and
     * two people sharing a common first name is an everyday coincidence rather than evidence.
     */
    private fun nameSignal(a: MatchCandidate, b: MatchCandidate, frequency: Frequency): Signal? {
        val mine = nameKey(a.displayName)
        if (mine == null || mine != nameKey(b.displayName)) return null
        val weight = weights.of(SignalKind.NAME) * rarity(frequency.names[mine])
        return Signal(SignalKind.NAME, weight).takeIf { it.weight > 0.0 }
    }

    /**
     * How much a value counts, given how many person records claim it.
     *
     * A value carried by exactly the two records being compared is as rare as it can be and
     * counts in full. One carried by a crowd — a shared office number, a name half the country
     * has — counts for almost nothing. `null` means the caller counted nothing, which is read as
     * the pair alone.
     */
    private fun rarity(claimants: Int?): Double {
        val sharers = (claimants ?: SHARED_BY_THE_PAIR).coerceAtLeast(SHARED_BY_THE_PAIR)
        return SHARED_BY_THE_PAIR.toDouble() / sharers
    }

    /**
     * How much a number agreeing across a gap of [gap] is discounted.
     *
     * Numbers get reassigned, so two records claiming one number years apart may be two humans
     * who held it in turn. Full weight inside the grace period, halving every [HALVING_YEARS]
     * beyond it: a claim is not evidence that expires on a date, so nothing here is a cliff.
     *
     * The gap is between the two claims' own last-seen instants, which is the only thing that
     * can measure it. A profile re-observed yesterday whose number quietly vanished some year
     * earlier looks exactly as current as the record carrying it.
     */
    private fun decay(gap: Duration): Double {
        val years = gap.toDays().toDouble() / DAYS_IN_YEAR
        if (years <= GRACE_YEARS) return 1.0
        return HALVING.pow((years - GRACE_YEARS) / HALVING_YEARS)
    }

    private fun gap(mine: ObservedIdentifier, theirs: ObservedIdentifier): Duration =
        Duration.between(mine.lastSeenAt, theirs.lastSeenAt).abs()

    private companion object {
        /** Within this many years, two claims are contemporaneous and nothing is discounted. */
        const val GRACE_YEARS = 1.0

        /** Beyond the grace period a number's weight halves every this many years. */
        const val HALVING_YEARS = 3.0

        const val HALVING = 0.5
        const val DAYS_IN_YEAR = 365.0
    }
}

/**
 * A value claimed by exactly the pair being compared — as rare as a shared value gets.
 *
 * Shared by the scorer, which scales a signal by it, and by [Weights.namesakeLimit], which bounds
 * what is assembled by it. Two copies of this number would let the bound and the scaling disagree
 * about what rare means.
 */
private const val SHARED_BY_THE_PAIR = 2

/**
 * The weakest rarity scaling at which a signal is still worth acting on.
 *
 * One judgement behind two numbers — how common a name may be before a pair sharing only it is not
 * assembled, and the least score worth putting in front of a reader. Both are derived from it, so
 * moving it moves them together.
 */
private const val WORTH_COMPARING = 0.5

/**
 * A person record reduced to what it can be matched on.
 *
 * Assembled by [Reconciler] from the corpus rather than read as a row: the claims come from one
 * table and the name from another, and nothing downstream needs the rest of the record.
 */
data class MatchCandidate(
    val record: ExternalId,
    val displayName: String?,
    val claims: List<ObservedIdentifier>,
)

/**
 * How common a value is across person records — the denominator rarity is scaled by.
 *
 * Counted rather than assumed, because it is the one part of the scoring that real data can
 * supply on its own. A value absent from either map is taken to be claimed by the pair alone.
 *
 * Names are keyed through [nameKey], and a caller that counts them any other way will count
 * nothing: the two sides have to agree on what makes two names the same name.
 */
data class Frequency(
    val claimants: Map<Identifier, Int> = emptyMap(),
    val names: Map<String, Int> = emptyMap(),
)

/**
 * What the pair agreed on, what it scored, and whether that merges them.
 *
 * [signals] travels with the score because a merge has to be explainable and reversible: the
 * link records what carried it, so unmerging is possible long after and a wrong join can be
 * read rather than guessed at.
 */
data class Match(val score: Double, val signals: List<Signal>, val merges: Boolean)

/**
 * The weight of each signal, and the score at which a pair merges.
 *
 * A weight is the ratio between how often one human agrees with themselves on a field and how
 * often two different people do, which is why an address outweighs a name with no special case
 * being written for it. The numbers are ordered by that ratio and sized so that the behaviours the
 * architecture states hold — a shared number carries a merge alone, a shared handle does not, and
 * two moderate agreements together do.
 *
 * **[threshold] is one decisive signal's worth of evidence, and that is the whole of its meaning.**
 * It is a unit rather than a dial: a pair merges when it agrees on as much as a shared number says.
 * So a judgement about how much some other signal is worth belongs in that signal's weight, where
 * the ratio above can be read against it, and never in the bar — moving the bar restates every
 * signal's worth at once while appearing to state none.
 *
 * The behaviours have to hold **on the signals a source actually carries**, which is what the
 * `init` block below asserts. A phone-anchored source offers a handle and a name; an address and
 * an avatar are shapes this build has no material for. Sizing the weights so that two signals the
 * corpus never holds can merge a pair, while the two it does hold fall short, is a calibration
 * against imaginary evidence — and it reads as working, because the pair that proves it is one
 * nothing ever assembles.
 */
data class Weights(
    val phone: Double,
    val email: Double,
    val handle: Double,
    val avatar: Double,
    val name: Double,
    val threshold: Double,
) {
    init {
        require(threshold > 0.0) { "A threshold of $threshold merges every pair that agrees on nothing." }
        // The rail under the paragraph above. Stated as the weakest pair of *available* signals
        // rather than as a number, so it tracks the weights instead of having to be rechecked
        // against them.
        require(handle + name >= threshold) {
            "A handle and a name agreeing scores ${handle + name}, under a threshold of " +
                "$threshold. Those are the two signals one human's two accounts can both offer " +
                "on a phone-anchored source, so the bar is then reachable by a shared number and " +
                "by nothing else — and a record carrying no number cannot be merged whatever the " +
                "corpus evidences about it."
        }
    }

    /**
     * How many records may carry a name before a pair sharing only that name is not worth
     * assembling.
     *
     * Rarity decides what a shared name *scores*; this decides whether it is worth scoring. The
     * two are the same judgement read twice, so this is derived from it rather than chosen
     * separately: a value is worth comparing while its rarity scaling is at least
     * [WORTH_COMPARING], and rarity is the pair's own count over the number of carriers — so the
     * bound is that count divided by the scaling.
     *
     * Bounding generation and scaling the score are not the same mechanism and both are needed. A
     * name a crowd carries would score near zero anyway; what the bound saves is assembling and
     * scoring a batch-sized set of pairs to learn that.
     */
    fun namesakeLimit(): Int = (SHARED_BY_THE_PAIR / WORTH_COMPARING).toInt()

    /**
     * The least score worth reporting as an unresolved pair.
     *
     * Derived from the weakest signal rather than picked, so that it tracks the weights instead of
     * drifting from them: a pair carrying less evidence than a half-rare name agrees on
     * substantially nothing, and reporting it would bury the pairs worth reading.
     *
     * This is a floor on a *report*, never a second threshold. Nothing is discarded for falling
     * under it — a pair that scored is kept whatever it scored, and this decides only what a
     * reader is shown first.
     */
    fun samplingFloor(): Double = name * WORTH_COMPARING

    internal fun of(kind: SignalKind): Double = when (kind) {
        SignalKind.PHONE -> phone
        SignalKind.EMAIL -> email
        SignalKind.HANDLE -> handle
        SignalKind.AVATAR -> avatar
        SignalKind.NAME -> name
    }

    companion object {
        private const val PHONE = 1.0
        private const val EMAIL = 0.7

        /**
         * A handle, which is the strongest thing short of a number that this source offers.
         *
         * Worth more than the ratio alone would suggest, and the reason is the platform's: a handle
         * is unique across it at any moment, so two person records bearing the same one are the
         * same account seen twice far more often than they are two humans. What keeps it under
         * [PHONE] is that a released handle can be taken by somebody else, and that people reuse
         * handles across platforms where uniqueness does not hold.
         *
         * Sized so that a handle and a name together reach the bar exactly, which is the judgement
         * this number carries: that pair is the strongest evidence one human's two accounts can
         * offer when neither advertises a number, and a build that would not merge on it cannot
         * merge most of what it holds. A handle alone stays under the bar, because a reassigned
         * handle with a different name beside it is the case that would fuse two people.
         */
        private const val HANDLE = 0.65
        private const val AVATAR = 0.45
        private const val NAME = 0.35
        private const val THRESHOLD = 1.0

        val STARTING = Weights(
            phone = PHONE,
            email = EMAIL,
            handle = HANDLE,
            avatar = AVATAR,
            name = NAME,
            threshold = THRESHOLD,
        )
    }
}

/**
 * The form in which two display names are the same name, or `null` when there is no name.
 *
 * Public because the scorer and whoever counts name frequencies have to agree on it. Counting
 * raw names while comparing normalised ones would find no duplicates at all, so every name would
 * read as unique and the weakest signal would become one of the strongest.
 */
fun nameKey(displayName: String?): String? =
    displayName?.trim()?.split(WHITESPACE)?.joinToString(" ")?.lowercase()?.takeIf { it.isNotEmpty() }

private val WHITESPACE = Regex("\\s+")
