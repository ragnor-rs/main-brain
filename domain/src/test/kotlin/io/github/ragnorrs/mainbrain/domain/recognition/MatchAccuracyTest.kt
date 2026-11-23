package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.IdentifierType
import io.github.ragnorrs.mainbrain.domain.record.ObservedIdentifier
import io.github.ragnorrs.mainbrain.domain.record.Source
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate that owns how accurate the match decision is.
 *
 * [MatchScorer]'s weights are judgement, and judgement that nothing measures drifts. This is the
 * sample it answers to: pairs whose shapes were read off the corpus, each carrying the decision it
 * should produce and the reading that assigned it. Moving a weight or the threshold moves what
 * happens here, which is the point — the numbers can then be re-derived from a sample rather than
 * taken on trust.
 *
 * **Hermetic, and deliberately not in the evaluation set.** Identity makes no model call, so every
 * decision below is arithmetic over data this file holds: it runs on every merge, in CI, for
 * nothing. The evaluation set scores what a model decides, spends to do it, and is asked for
 * rather than automatic — a deterministic property owned there would be measured only when
 * somebody remembered to buy it, which is how an uncalibrated constant survives unexamined.
 *
 * **The sample is small, and that is reported rather than hidden.** A handful of shapes is enough
 * to pin the decisions the available signals can produce, and is not enough to estimate a rate.
 * What it proves is that every shape a reader judged one way is decided that way; what it cannot
 * say is how often each occurs. That second question needs a corpus with duplicates in it, and it
 * belongs to this gate when there is material rather than to the evaluation set.
 *
 * **What it does not cover**, named rather than left implicit:
 *
 * - **An address and an avatar hash.** No source in this build produces either, so a pair agreeing
 *   on one is a shape with no material behind it. Those two weights are unexercised judgement, and
 *   a sample asserting them would be measuring an invention.
 * - **A second platform.** Every pair here is two accounts on one phone-anchored source. A handle
 *   is unique there, which is load-bearing in the reading on the handle-and-name pair; it does not
 *   hold across platforms, so that weight wants re-reading when one arrives.
 */
class MatchAccuracyTest {

    private val scorer = MatchScorer()

    @Test
    fun `every pair in the sample is decided the way it was read`() {
        val wrong = SAMPLE.filter { judged -> decisionOn(judged.shape) != judged.decision }

        assertTrue(
            wrong.isEmpty(),
            "The weights no longer reproduce the sample they were set from. " +
                wrong.joinToString("; ") { judged ->
                    "'${judged.shape.label}' scored ${score(judged.shape)}, which is " +
                        "${decisionOn(judged.shape)} where it was read as ${judged.decision}, " +
                        "because ${judged.because}"
                },
        )
    }

    @Test
    fun `the sample exercises every decision the scorer can take`() {
        // A sample that only ever says "merge" measures nothing: every weight could be enormous
        // and each case would still pass. Each decision has to appear, or the gate is one-sided.
        assertEquals(
            Decision.entries.toSet(),
            SAMPLE.map { it.decision }.toSet(),
            "A decision the scorer can take is absent from the sample, so nothing here would " +
                "notice the weights collapsing towards it.",
        )
    }

    @Test
    fun `a pair merges without a number agreeing`() {
        // The defect this sample was first written against. Weights can be ordered correctly, sum
        // correctly, and still leave the bar reachable only by a shared number — and then every
        // account advertising none is unmergeable whatever else the corpus evidences about it. The
        // pair that disproves it has to be built from signals the source carries, which is why an
        // address and an avatar cannot stand in.
        val withoutANumber = SAMPLE
            .filter { it.decision == Decision.MERGE }
            .filter { judged -> judged.shape.sharedPhone == null }

        assertTrue(
            withoutANumber.isNotEmpty(),
            "Nothing in the sample merges except on a shared number, so the bar is unreachable " +
                "for a record carrying none — and on a source where a handle is near-universal " +
                "and a number is not, that is most of them.",
        )
    }

    @Test
    fun `a pair a reader would look at scores above the floor it is reported over`() {
        // Kept and reportable are not the same thing. A pair is kept whatever it scored, but the
        // advisory read leads with what clears the floor — so a near-miss scoring under it is kept
        // and never shown, which is the same as invisible to anyone looking for duplicates.
        val floor = Weights.STARTING.samplingFloor()
        val hidden = SAMPLE
            .filter { it.decision == Decision.REPORTED }
            .filter { judged -> score(judged.shape) < floor }

        assertTrue(
            hidden.isEmpty(),
            "A pair read as worth a reader's attention scores under the reporting floor of " +
                "$floor: ${hidden.map { it.shape.label }}. It is kept, and never seen.",
        )
    }

    @Test
    fun `the weakest pair of available signals reaches the bar exactly`() {
        // Not an accident for the next reader to rediscover. The handle weight is sized so that
        // this sum is the bar, and `Weights`' own construction refuses any numbers where it falls
        // short — so what this adds is that the arithmetic lands on it, which a comparison over
        // doubles can miss by a rounding nobody wrote down.
        val weights = Weights.STARTING

        assertEquals(
            weights.threshold,
            weights.handle + weights.name,
            "A handle and a name agreeing scores ${weights.handle + weights.name} against a bar " +
                "of ${weights.threshold}. A gap either way is a decision: under it, the two " +
                "signals this source can both offer cannot merge a pair together; over it, the " +
                "bar is no longer one decisive signal's worth of evidence.",
        )
    }

    private fun decisionOn(shape: Shape): Decision {
        val match = scorer.score(shape.one(), shape.two(), shape.frequency())
        return when {
            match.merges -> Decision.MERGE
            match.score >= Weights.STARTING.samplingFloor() -> Decision.REPORTED
            else -> Decision.UNREPORTED
        }
    }

    private fun score(shape: Shape): Double = scorer.score(shape.one(), shape.two(), shape.frequency()).score

    /** What the scorer does with a pair, which is all three of the outcomes it can produce. */
    private enum class Decision {
        /** Joined without asking anyone. */
        MERGE,

        /** Kept, and above the floor the advisory read leads with, so a reader is shown it. */
        REPORTED,

        /** Kept if it scored at all, but under that floor — so no reader is shown it. */
        UNREPORTED,
    }

    /**
     * One sampled pair: what the two records agree on, and how common each agreement is.
     *
     * Invented rather than copied. The shapes are what a phone-anchored source produces — a handle
     * on nearly every account, a number on some, a display name a human typed — while the values
     * are placeholders, because what is asserted is the arithmetic over a shape and never a fact
     * about anybody.
     */
    private class Shape(
        val label: String,
        val sharedPhone: String? = null,
        val sharedHandle: String? = null,
        val oneName: String? = null,
        val twoName: String? = null,
        val context: Context = Context(),
    ) {
        private val phoneOne = sharedPhone?.let { claim(IdentifierType.PHONE, it, AT_FIRST) }
        private val phoneTwo = sharedPhone?.let {
            claim(IdentifierType.PHONE, it, AT_FIRST.plus(Duration.ofDays(DAYS_IN_YEAR * context.yearsApart)))
        }
        private val handle = sharedHandle?.let { claim(IdentifierType.HANDLE, it, AT_FIRST) }

        fun one(): MatchCandidate = candidate("one", oneName, listOfNotNull(phoneOne, handle))

        fun two(): MatchCandidate = candidate("two", twoName, listOfNotNull(phoneTwo, handle))

        /**
         * The corpus counts the scorer scales by.
         *
         * A name is counted only where both sides carry the same one, because a frequency entry
         * keyed on a name neither shares would scale a signal that never fires — and the two sides
         * have to agree on what makes two names the same name, so it is keyed through [nameKey].
         */
        fun frequency(): Frequency {
            val shared = nameKey(oneName)?.takeIf { it == nameKey(twoName) }
            return Frequency(
                claimants = phoneOne?.let { mapOf(it.identifier to context.phoneClaimants) }.orEmpty(),
                names = shared?.let { mapOf(it to context.nameCarriers) }.orEmpty(),
            )
        }

        private fun candidate(label: String, displayName: String?, claims: List<ObservedIdentifier>) =
            MatchCandidate(
                record = ExternalId(Source.TELEGRAM, label),
                displayName = displayName,
                claims = claims,
            )

        private fun claim(type: IdentifierType, raw: String, lastSeen: Instant): ObservedIdentifier {
            val identifier = requireNotNull(Identifier.of(type, raw)) { "$raw is not a usable $type." }
            return ObservedIdentifier(identifier = identifier, addedAt = AT_FIRST, lastSeenAt = lastSeen)
        }
    }

    /**
     * What the corpus around a pair says, which is what turns an agreement into a weight.
     *
     * Separate from the agreement itself because none of it is a fact about the two records: how
     * many others carry the name, how many others claim the number, and how far apart the two
     * sightings of it sit are all read off the rest of the corpus, and each one can take a shared
     * value from decisive to worthless without the pair changing at all.
     */
    private class Context(
        /** How many person records carry the shared name, where one is shared. */
        val nameCarriers: Int = PAIR_ALONE,
        /** How many claim the shared number, where one is shared. */
        val phoneClaimants: Int = PAIR_ALONE,
        /** Years between the two records' last observations of the shared number. */
        val yearsApart: Long = 0,
    )

    /** A sampled pair, the decision it should produce, and the reading that assigned it. */
    private class Judged(val shape: Shape, val decision: Decision, val because: String)

    private companion object {
        /** A value carried by exactly the pair compared, which is as rare as a shared value gets. */
        const val PAIR_ALONE = 2

        /** Enough records sharing a value that it discriminates nobody. */
        const val A_CROWD = 30

        const val DAYS_IN_YEAR = 365L

        val AT_FIRST: Instant = Instant.parse("2024-01-01T00:00:00Z")

        /**
         * The sample, and the whole of it.
         *
         * Ordered strongest to weakest rather than grouped by decision, so that the boundary reads
         * as a boundary: each entry is the same kind of evidence as the one above with a little
         * less of it, and the line the threshold draws falls between two neighbours.
         */
        val SAMPLE = listOf(
            Judged(
                Shape(
                    label = "the same number, and names that disagree",
                    sharedPhone = "+995322000000",
                    oneName = "A Name",
                    twoName = "Another Name",
                ),
                Decision.MERGE,
                "accounts here are anchored to a number, so two advertising one are one human " +
                    "holding two accounts; a profile name differing between them is ordinary and " +
                    "is not evidence against it",
            ),
            Judged(
                Shape(
                    label = "the same handle and the same uncommon name",
                    sharedHandle = "shared-handle",
                    oneName = "A Rare Name",
                    twoName = "a  rare   name",
                ),
                Decision.MERGE,
                "a handle is unique on this platform at any moment, so two records bearing one " +
                    "are the same account seen twice far more often than two humans; the name " +
                    "agreeing too leaves a release and a retake as the only other reading, and " +
                    "one landing on a namesake is too thin to keep every duplicate for",
            ),
            Judged(
                Shape(
                    label = "the same handle, and the same name a crowd carries",
                    sharedHandle = "shared-handle",
                    oneName = "A Common Name",
                    twoName = "A Common Name",
                    context = Context(nameCarriers = A_CROWD),
                ),
                Decision.REPORTED,
                "the name is carried by enough records to discriminate nobody, so it adds nearly " +
                    "nothing and what is left is a handle on its own — which a release and a " +
                    "retake explains, and which this build does not merge on",
            ),
            Judged(
                Shape(
                    label = "the same handle, and names that disagree",
                    sharedHandle = "shared-handle",
                    oneName = "A Name",
                    twoName = "Another Name",
                ),
                Decision.REPORTED,
                "a handle alone with a differing name beside it: the shape a released handle " +
                    "taken by somebody else produces, which is the join that fuses two people",
            ),
            Judged(
                Shape(
                    label = "the same number, observed four years apart",
                    sharedPhone = "+995322000000",
                    context = Context(yearsApart = 4),
                ),
                Decision.REPORTED,
                "numbers get reassigned, so a gap that long is a second reading of the only " +
                    "signal there is; still the strongest thing either record offers, and worth " +
                    "putting in front of a reader",
            ),
            Judged(
                Shape(
                    label = "an uncommon name, and nothing else",
                    oneName = "A Rare Name",
                    twoName = "A Rare Name",
                ),
                Decision.REPORTED,
                "a display name is what a human typed into a profile field; an uncommon one is " +
                    "worth reading, and is not worth joining two accounts on",
            ),
            Judged(
                Shape(
                    label = "the same number, observed a decade apart",
                    sharedPhone = "+995322000000",
                    context = Context(yearsApart = 10),
                ),
                Decision.UNREPORTED,
                "two records holding one number that far apart are as readily two humans who " +
                    "held it in turn, which is too little to put in front of anyone",
            ),
            Judged(
                Shape(
                    label = "a number a crowd claims",
                    sharedPhone = "+995322000000",
                    context = Context(phoneClaimants = A_CROWD),
                ),
                Decision.UNREPORTED,
                "a switchboard or a role account: the strongest signal there is, carried by " +
                    "enough records to evidence nothing about any particular two of them",
            ),
            Judged(
                Shape(
                    label = "a name a crowd carries, and nothing else",
                    oneName = "A Common Name",
                    twoName = "A Common Name",
                    context = Context(nameCarriers = A_CROWD),
                ),
                Decision.UNREPORTED,
                "the mistake hardest to notice, because a fused contact looks like nothing is " +
                    "missing while a duplicate one looks wrong",
            ),
            Judged(
                Shape(
                    label = "nothing in common",
                    oneName = "A Name",
                    twoName = "Another Name",
                ),
                Decision.UNREPORTED,
                "no comparable signal agrees, so there is nothing to report and nothing to keep",
            ),
        )
    }
}
