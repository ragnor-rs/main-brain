package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.entity.Commitment
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.FindingKind
import io.github.ragnorrs.mainbrain.domain.entity.FindingPair
import io.github.ragnorrs.mainbrain.domain.entity.firstStatedAt
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The middle tier of the collapse: which pairs it settles, which it pays to settle, and which it
 * leaves alone.
 *
 * Every case here is over the pure function, which is the point of its having no port: the thresholds
 * and the ordering are the whole of what it decides, and they are decidable from the vectors in front
 * of it.
 */
class CollapseTest {

    @Test
    fun `a pair the vectors put beyond doubt is merged without a call`() {
        val one = commitment("will send the quote on Friday", at = 100)
        val other = commitment("the quote is to be sent on Friday", at = 200)

        val collapse = collapsed(
            listOf(FindingPair.of(FindingKind.COMMITMENT, one, other)),
            mapOf(one.claim to listOf(1f, 0f), other.claim to listOf(1f, 0f)),
        )

        assertEquals(1, collapse.merge.size, "identical vectors are the same fact")
        assertEquals(emptyList(), collapse.ask, "nothing the vectors settled is worth paying for")
    }

    @Test
    fun `a pair in the band between the thresholds is asked about rather than merged`() {
        val one = commitment("will draft the contract", at = 100)
        val other = commitment("the contract is to be drafted", at = 200)

        val collapse = collapsed(
            listOf(FindingPair.of(FindingKind.COMMITMENT, one, other)),
            mapOf(one.claim to listOf(1f, 0f), other.claim to between()),
        )

        assertEquals(emptyList(), collapse.merge, "the vectors did not settle it, so nothing is merged")
        assertEquals(1, collapse.ask.size, "a pair the vectors cannot settle is what the model is for")
    }

    @Test
    fun `a pair the vectors put apart is neither merged nor asked about`() {
        val one = commitment("will send the quote", at = 100)
        val other = commitment("will call the lawyer", at = 200)

        val collapse = collapsed(
            listOf(FindingPair.of(FindingKind.COMMITMENT, one, other)),
            mapOf(one.claim to listOf(1f, 0f), other.claim to listOf(0f, 1f)),
        )

        assertEquals(emptyList(), collapse.merge)
        assertEquals(emptyList(), collapse.ask, "ruling a pair out is free, so it costs nothing")
    }

    /**
     * The case the thresholds exist to get right, and the one that would be silently wrong if
     * `cosine` assumed a normalised vector.
     *
     * Two vectors pointing the same way at different lengths are the same direction and so the same
     * claim; a comparison that took the dot product alone would read the longer one as more similar
     * than identical and still merge, but would also read two *unrelated* long vectors as similar.
     * So the property asserted is the one that matters: length carries nothing.
     */
    @Test
    fun `length carries nothing, only direction`() {
        val one = commitment("the quote", at = 100)
        val other = commitment("the revised quote", at = 200)

        val collapse = collapsed(
            listOf(FindingPair.of(FindingKind.COMMITMENT, one, other)),
            mapOf(one.claim to listOf(1f, 0f), other.claim to listOf(50f, 0f)),
        )

        assertEquals(1, collapse.merge.size, "a longer vector in the same direction is the same claim")
    }

    @Test
    fun `a claim with no vector is compared against nothing`() {
        val one = commitment("will send the quote", at = 100)
        val other = commitment("the quote is to be sent", at = 200)

        val collapse = collapsed(
            listOf(FindingPair.of(FindingKind.COMMITMENT, one, other)),
            // The sidecar answered for one side and not the other, which is what a short reply
            // from the provider looks like after the zip.
            mapOf(one.claim to listOf(1f, 0f)),
        )

        assertEquals(emptyList(), collapse.merge, "nothing was established, so nothing is merged")
        assertEquals(
            emptyList(),
            collapse.ask,
            "an absent vector is not a near miss: paying to decide a pair nothing compared would " +
                "spend the budget on whatever the sidecar happened to drop",
        )
    }

    /**
     * Three readings of one fact, offered as all three of their pairs.
     *
     * **The property asserted is that the batch's merges are flat, not how many there are.** Which
     * pairs survive the guard depends on the identifiers the readings happen to carry, because the
     * similarities are equal and the identifiers are what breaks the tie — so a case fixing the
     * count would be asserting the fixture's UUIDs. What must hold whatever the order is that no
     * reading is both merged away and merged onto, since that is a chain and a chain is only safe
     * if the store applies the merges in the order they were decided.
     */
    @Test
    fun `a batch never decides a chain of merges`() {
        val first = commitment("will send the quote", at = 100)
        val second = commitment("the quote is to be sent", at = 200)
        val third = commitment("the quote gets sent", at = 300)
        val same = listOf(1f, 0f)

        val collapse = collapsed(
            listOf(
                FindingPair.of(FindingKind.COMMITMENT, first, second),
                FindingPair.of(FindingKind.COMMITMENT, second, third),
                FindingPair.of(FindingKind.COMMITMENT, first, third),
            ),
            mapOf(first.claim to same, second.claim to same, third.claim to same),
        )

        val superseded = collapse.merge.map { it.superseded.id }
        val survivors = collapse.merge.map { it.survivor.id }
        assertTrue(collapse.merge.isNotEmpty(), "three identical readings are not left alone")
        assertEquals(superseded.distinct(), superseded, "no reading is superseded twice")
        assertTrue(
            superseded.none { it in survivors },
            "no reading is merged away and merged onto in one batch, which is what a chain is",
        )
    }

    /**
     * The chain the guard refuses, left to converge over sweeps.
     *
     * Offered as the two pairs that form one — the last reading onto the middle, and the middle onto
     * the first — so that the order is not a matter of which identifier sorts first. One of them is
     * taken and the other is not, and the reading left over is compared again once the first merge
     * has been applied.
     */
    @Test
    fun `a chain resolves over two sweeps rather than in one`() {
        val first = commitment("will send the quote", at = 100)
        val middle = commitment("the quote is to be sent", at = 200)
        val last = commitment("the quote gets sent", at = 300)
        val same = listOf(1f, 0f)

        val collapse = collapsed(
            listOf(
                FindingPair.of(FindingKind.COMMITMENT, middle, last),
                FindingPair.of(FindingKind.COMMITMENT, first, middle),
            ),
            mapOf(first.claim to same, middle.claim to same, last.claim to same),
        )

        assertEquals(1, collapse.merge.size, "the two decisions would be a chain, so only one is taken")
        assertEquals(
            emptyList(),
            collapse.ask,
            "the one left over is not paid for either: it is free to compare again next sweep",
        )
    }

    /**
     * **The guard is on the reading that would be merged away, and not on the one kept.** Two pairs
     * sharing the *superseded* side are two answers about which finding one reading belongs to, and
     * only one of them can be applied — so buying both is buying a contradiction. Two pairs sharing
     * the *survivor* are a different thing entirely and both are asked: three readings of one fact
     * merge onto the earliest, which takes two decisions and not one.
     */
    @Test
    fun `two pairs wanting to merge away the same reading are not both paid for`() {
        val first = commitment("will draft the contract", at = 100)
        val second = commitment("the contract is to be drafted", at = 200)
        val last = commitment("the contract is being drafted", at = 300)

        val collapse = collapsed(
            listOf(
                FindingPair.of(FindingKind.COMMITMENT, first, last),
                FindingPair.of(FindingKind.COMMITMENT, second, last),
            ),
            mapOf(first.claim to listOf(1f, 0f), second.claim to listOf(1f, 0f), last.claim to between()),
        )

        assertEquals(
            1,
            collapse.ask.size,
            "both pairs would merge the same reading away, and it can only go to one of them",
        )
        assertEquals(last.id, collapse.ask.single().superseded.id)
    }

    @Test
    fun `two pairs sharing the reading they would keep are both paid for`() {
        val first = commitment("will draft the contract", at = 100)
        val second = commitment("the contract is to be drafted", at = 200)
        val third = commitment("the contract is being drafted", at = 300)

        val collapse = collapsed(
            listOf(
                FindingPair.of(FindingKind.COMMITMENT, first, second),
                FindingPair.of(FindingKind.COMMITMENT, first, third),
            ),
            mapOf(first.claim to listOf(1f, 0f), second.claim to between(), third.claim to between()),
        )

        assertEquals(
            2,
            collapse.ask.size,
            "three readings of one fact are two decisions, both onto the earliest reading",
        )
        assertEquals(
            listOf(first.id, first.id),
            collapse.ask.map { it.survivor.id },
            "neither decision moves the reading that would be kept",
        )
    }

    /** The earliest reading survives, which is the rule the migration used on what was already stored. */
    @Test
    fun `the earlier reading is the survivor`() {
        val later = commitment("the quote is to be sent", at = 500)
        val earlier = commitment("will send the quote", at = 100)

        val pair = FindingPair.of(FindingKind.COMMITMENT, later, earlier)

        assertSame(earlier, pair.survivor, "a restatement is usually the second-hand reading")
        assertSame(later, pair.superseded)
    }

    @Test
    fun `two readings of one instant are ordered by identifier, the same way every time`() {
        val one = commitment("will send the quote", at = 100)
        val other = commitment("the quote is to be sent", at = 100)

        val pair = FindingPair.of(FindingKind.COMMITMENT, one, other)
        val reversed = FindingPair.of(FindingKind.COMMITMENT, other, one)

        assertEquals(pair.survivor.id, reversed.survivor.id, "the order a caller offers them carries nothing")
        assertEquals(
            minOf(one.id, other.id),
            pair.survivor.id,
            "broken on the identifier's own ordering, which is the store's and not Java's",
        )
    }

    @Test
    fun `a finding is not a duplicate of itself`() {
        val one = commitment("will send the quote", at = 100)

        assertFailsWith<IllegalArgumentException> { FindingPair(FindingKind.COMMITMENT, one, one) }
    }

    @Test
    fun `two findings from different conversations are not a pair`() {
        val one = commitment("will send the quote", at = 100)
        val other = commitment("will send the quote", at = 200, conversation = OTHER_CHAT)

        assertFailsWith<IllegalArgumentException> { FindingPair.of(FindingKind.COMMITMENT, one, other) }
    }

    /**
     * A finding's own instant is the earliest span that states it, so a restatement adds evidence
     * without making the obligation newer than the promise.
     */
    @Test
    fun `a finding is first stated by its earliest span`() {
        val restated = commitment("will send the quote", at = 100).copy(
            evidence = setOf(evidence(100), evidence(900)),
        )

        assertEquals(Instant.ofEpochSecond(100), restated.firstStatedAt)
    }

    @Test
    fun `vectors of different widths establish nothing`() {
        assertEquals(0.0, cosine(listOf(1f, 0f), listOf(1f, 0f, 0f)), "two embedding versions in one comparison")
    }

    @Test
    fun `a zero vector has no angle`() {
        assertEquals(0.0, cosine(listOf(0f, 0f), listOf(1f, 0f)))
    }

    /**
     * The cheap tier's version names its stage, so that its answers cannot be read as the paid
     * tier's.
     *
     * The two share one column, and the query that excludes a decided pair excludes under both
     * values at once — so a cheap value that collided with a paid one would have a reworded
     * comparison honour what the vectors said, or the reverse.
     */
    @Test
    fun `the vector version names its own stage and carries the embedding version`() {
        val version = runningVectorsVersion("some-embedding-model")

        assertTrue(version.startsWith("$VECTORS:"), "it names the stage first: $version")
        assertTrue("some-embedding-model" in version, "and the sidecar's own version within it: $version")
        assertNotEquals(
            VECTORS,
            Extractor.ADJUDICATION,
            "the two tiers write to one column, so the names that tell them apart must differ",
        )
    }

    /**
     * The acceptance check for this version: moving a bound makes what the old arithmetic ruled out
     * owed again.
     *
     * **Both bounds, because either one decides a rule-out.** A pair is put apart here by a cosine
     * weighed against the two of them, so a version naming only the sidecar would leave a bound
     * outside every version — and editing it would ship a behavioural change that invalidated
     * nothing, which is the failure a version exists to prevent.
     *
     * Asserted as a difference between two values rather than against a written-out string, so that
     * the format is free to change and the property is not. `CollapseStoreTest` is where the
     * difference is shown to make the pairs owed again through real SQL; this is where it is shown
     * that moving a bound produces a difference at all.
     */
    @Test
    fun `moving either similarity bound moves the vector version`() {
        val current = runningVectorsVersion(EMBEDDINGS)

        assertNotEquals(
            current,
            vectorsVersion(EMBEDDINGS, sameFact = SAME_FACT - 0.01, worthAsking = WORTH_ASKING),
            "the bound above which a pair is merged outright decides a rule-out, so it is covered",
        )
        assertNotEquals(
            current,
            vectorsVersion(EMBEDDINGS, sameFact = SAME_FACT, worthAsking = WORTH_ASKING - 0.01),
            "and so does the bound above which a pair is worth paying to compare",
        )
        assertEquals(
            current,
            runningVectorsVersion(EMBEDDINGS),
            "while nothing moving leaves it where it was, or every sweep would retire the last one's work",
        )
    }

    /**
     * A bound this tier weighs that reaches no version ships a behavioural change which invalidates
     * nothing — and this is the assertion that refuses it.
     *
     * **Enumerated off the file rather than named, so that a bound added later is covered by this
     * case rather than by whoever happens to look.** What it keys on is the type: every bound here
     * is a `Double` constant, and a third one could not be added as anything else and still be
     * weighed against a cosine. That convention is therefore the real gate, as the `_SYSTEM` suffix
     * is for the extractor's instructions.
     *
     * The bounds known today are asserted as a floor and not as the whole set, so one legitimately
     * added later fails on being uncovered — with the message that says so — rather than on a
     * sentinel that blames the test for reading the wrong place.
     *
     * **Compared as whole values and not by substring**, which is the difference between a rail and
     * the appearance of one: `0.9` sits inside `0.97`, and both are ordinary similarity bounds, so a
     * containment test would report a third bound as covered while it sat outside every version.
     * Split on the separators instead and compare the sets, which also gives the other direction —
     * a version printing something that is not a bound of this tier fails too.
     *
     * Read as fields off the file class, which is where Kotlin puts a top-level constant.
     */
    @Test
    fun `every bound the cheap tier weighs is covered by its version, and nothing else is`() {
        val bounds = Class.forName("io.github.ragnorrs.mainbrain.domain.recognition.CollapseKt").declaredFields
            .filter { it.type == java.lang.Double.TYPE }
            .associate { field ->
                field.isAccessible = true
                field.name to field.get(null) as Double
            }
        assertTrue(
            bounds.values.containsAll(setOf(SAME_FACT, WORTH_ASKING)),
            "the bounds are not where this test reads them from, so it is asserting nothing: $bounds",
        )

        // Everything the version prints after the sidecar's own, which is where this tier's own
        // arithmetic goes. The sidecar's version is excluded by taking the last `@` rather than the
        // first: it carries separators of its own.
        val printed = runningVectorsVersion(EMBEDDINGS).substringAfterLast('@').split('/').toSet()

        assertEquals(
            bounds.values.map { it.toString() }.toSet(),
            printed,
            "every bound this tier weighs has to reach its version, or moving that one ships a " +
                "behavioural change that invalidates nothing — and nothing that is not one of them " +
                "may be printed as though it were: $bounds against $printed",
        )
    }

    private companion object {

        /** A sidecar version to build a vector version over; its content carries nothing. */
        const val EMBEDDINGS = "fake-embeddings-1"

        val CHAT = ExternalId(Source.TELEGRAM, "chat")
        val OTHER_CHAT = ExternalId(Source.TELEGRAM, "other-chat")

        /**
         * A vector landing between the two thresholds, built from them rather than written out.
         *
         * **Derived so that moving a threshold moves these cases with it.** A hand-picked pair of
         * floats would keep asserting the band it was written for, and the first change to either
         * constant would leave the case testing a tier it no longer reaches — passing, and about
         * nothing.
         */
        fun between(): List<Float> {
            val target = (SAME_FACT + WORTH_ASKING) / 2
            // The unit vector at the angle whose cosine against (1, 0) is the midpoint.
            val sine = kotlin.math.sqrt(1 - target * target)
            return listOf(target.toFloat(), sine.toFloat()).also {
                val reached = cosine(listOf(1f, 0f), it)
                check(abs(reached - target) < 1e-6) { "the midpoint vector should sit at $target, not $reached" }
                check(reached < SAME_FACT && reached >= WORTH_ASKING) {
                    "the two thresholds have moved together and no longer leave a band between them"
                }
            }
        }

        fun evidence(at: Long) = Evidence(
            span = SpanId(kind = RecordKind.MESSAGE, record = ExternalId(Source.TELEGRAM, "m$at")),
            sourceTime = Instant.ofEpochSecond(at),
        )

        fun commitment(claim: String, at: Long, conversation: ExternalId = CHAT): Commitment = Commitment(
            id = EntityId(UUID.nameUUIDFromBytes(claim.toByteArray())),
            claim = claim,
            conversation = conversation,
            evidence = setOf(evidence(at)),
            hedged = false,
        )
    }
}
