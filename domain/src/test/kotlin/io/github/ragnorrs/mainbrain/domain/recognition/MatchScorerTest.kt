package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.entity.SignalKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.IdentifierType
import io.github.ragnorrs.mainbrain.domain.record.ObservedIdentifier
import io.github.ragnorrs.mainbrain.domain.record.Source
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scored matcher, which decides what counts as evidence that two accounts are one human.
 *
 * Every case here is one of the properties the architecture states about resolution rather than
 * a probe at an implementation detail, because the weights themselves are judgement and will
 * move. What may not move is the ordering between them and the three rules about absence,
 * disagreement and rarity — a change that breaks one of those is a change that fuses two people
 * or splits one, and neither is visible from the score alone.
 */
class MatchScorerTest {

    private val scorer = MatchScorer()

    @Test
    fun `a shared phone number carries a merge on its own`() {
        // The strongest signal there is: accounts on the chat platform are phone-anchored, and
        // two accounts advertising one number is the best evidence this corpus can offer.
        val number = claim(IdentifierType.PHONE, "+995322000000")
        val match = scorer.score(
            candidate("one", "A Name", listOf(number)),
            candidate("two", "Another Name", listOf(number)),
        )

        assertTrue(match.merges, "A shared number scored ${match.score} and did not merge.")
    }

    @Test
    fun `a common first name is near-zero evidence and merges nothing`() {
        val shared = "Common Name"
        val match = scorer.score(
            candidate("one", shared, emptyList()),
            candidate("two", shared, emptyList()),
            Frequency(names = mapOf("common name" to MANY)),
        )

        assertFalse(
            match.merges,
            "Two people sharing a common name were merged. This is the mistake the client " +
                "named: a fused contact is harder to notice than a duplicate one, because " +
                "nothing looks missing.",
        )
        assertTrue(match.score < NEAR_ZERO, "A name thirty records share scored ${match.score}.")
    }

    @Test
    fun `a rare name is evidence, but not enough on its own`() {
        val rare = candidate("one", "Rare Name", emptyList())
        val same = candidate("two", "rare  name", emptyList())

        val match = scorer.score(rare, same, Frequency(names = mapOf("rare name" to 2)))

        assertEquals(Weights.STARTING.name, match.score, TOLERANCE, "A name on two records alone should count in full.")
        assertFalse(match.merges, "A name carried a merge. A display name is what a human typed into a profile field.")
    }

    @Test
    fun `two moderate agreements together carry a merge`() {
        // Neither would do it alone, and the weights are sized so that accumulation works —
        // otherwise the score is a lookup of the single strongest signal and not a score.
        val address = claim(IdentifierType.EMAIL, "one@example.com")
        val handle = claim(IdentifierType.HANDLE, "@shared")
        val match = scorer.score(
            candidate("one", null, listOf(address, handle)),
            candidate("two", null, listOf(address, handle)),
        )

        assertTrue(match.merges, "An address and a handle together scored only ${match.score}.")
        assertEquals(
            listOf(SignalKind.EMAIL, SignalKind.HANDLE),
            match.signals.map { it.kind },
            "Both agreements should be recorded, so the merge can be read back and undone.",
        )
    }

    @Test
    fun `two moderate agreements carry a merge on the signals this source carries`() {
        // The case above proves accumulation on an address and a handle, and no source in this
        // build produces an address — so on its own it establishes the property over evidence the
        // corpus never holds. A handle and a name are what one human's two accounts can both
        // offer here, and if that pair does not merge then nothing merges without a number.
        val handle = claim(IdentifierType.HANDLE, "@shared")
        val match = scorer.score(
            candidate("one", "A Rare Name", listOf(handle)),
            candidate("two", "a  rare  name", listOf(handle)),
            Frequency(names = mapOf("a rare name" to 2)),
        )

        assertTrue(
            match.merges,
            "A handle and an uncommon name together scored only ${match.score}, so the bar is " +
                "reachable by a shared number and by nothing else this source offers.",
        )
        assertEquals(listOf(SignalKind.HANDLE, SignalKind.NAME), match.signals.map { it.kind })
    }

    @Test
    fun `a handle on its own does not merge`() {
        // The other side of the decision above, and the one that keeps it honest: a handle can be
        // released and taken by somebody else, so sizing it to merge alone would fuse two people
        // on a name change neither of them made.
        val handle = claim(IdentifierType.HANDLE, "@shared")
        val match = scorer.score(
            candidate("one", "A Name", listOf(handle)),
            candidate("two", "Another Name", listOf(handle)),
        )

        assertFalse(match.merges, "A handle alone scored ${match.score} and merged two accounts.")
    }

    @Test
    fun `a signal neither record offers counts neither for nor against`() {
        // Most person records are a handle and nothing else. Counting the absent fields against
        // the pair drifts every sparse pair to "different people", which is nearly every pair.
        val handle = claim(IdentifierType.HANDLE, "@shared")
        val match = scorer.score(
            candidate("one", null, listOf(handle)),
            candidate("two", null, listOf(handle)),
        )

        assertEquals(
            Weights.STARTING.handle,
            match.score,
            TOLERANCE,
            "The missing number, address and avatar moved the score. Absence is not disagreement.",
        )
    }

    @Test
    fun `a comparable signal that disagrees is also neutral`() {
        // One human holds two numbers. Treating a different number as evidence of a different
        // person would split every contact who ever changed phone.
        val handle = claim(IdentifierType.HANDLE, "@shared")
        val match = scorer.score(
            candidate("one", null, listOf(handle, claim(IdentifierType.PHONE, "+995322000000"))),
            candidate("two", null, listOf(handle, claim(IdentifierType.PHONE, "+995322000001"))),
        )

        assertEquals(listOf(SignalKind.HANDLE), match.signals.map { it.kind })
        assertEquals(Weights.STARTING.handle, match.score, TOLERANCE)
    }

    @Test
    fun `a number agreeing across a long gap is discounted`() {
        // Numbers get reassigned, so one claimed by two records a decade apart may be two
        // humans who held it in turn.
        val old = claim(IdentifierType.PHONE, "+995322000000", lastSeen = "2014-01-01T00:00:00Z")
        val recent = claim(IdentifierType.PHONE, "+995322000000", lastSeen = "2024-01-01T00:00:00Z")

        val match = scorer.score(candidate("one", null, listOf(old)), candidate("two", null, listOf(recent)))

        assertFalse(match.merges, "A number last seen a decade apart on the two records still merged them.")
        assertTrue(match.score > 0.0, "The number stopped counting altogether. It is weaker evidence, not none.")
    }

    @Test
    fun `a number a crowd claims is weak evidence`() {
        // A switchboard, a role account, a number pasted into thirty profiles.
        val switchboard = claim(IdentifierType.PHONE, "+995322000000")
        val match = scorer.score(
            candidate("one", null, listOf(switchboard)),
            candidate("two", null, listOf(switchboard)),
            Frequency(claimants = mapOf(switchboard.identifier to MANY)),
        )

        assertFalse(match.merges, "A number thirty records claim merged two of them on its own.")
    }

    @Test
    fun `a second number in common is the same evidence stated twice`() {
        val first = claim(IdentifierType.PHONE, "+995322000000")
        val second = claim(IdentifierType.PHONE, "+995322000001")
        val match = scorer.score(
            candidate("one", null, listOf(first, second)),
            candidate("two", null, listOf(first, second)),
        )

        assertEquals(
            Weights.STARTING.phone,
            match.score,
            TOLERANCE,
            "Two numbers in common counted twice. One pair of accounts sharing a handset is " +
                "one fact about them, and doubling it lets two weak agreements of one type " +
                "outvote a signal that should outrank them.",
        )
    }

    @Test
    fun `nothing in common scores nothing and names no signal`() {
        val match = scorer.score(
            candidate("one", "A Name", listOf(claim(IdentifierType.HANDLE, "@one"))),
            candidate("two", "Another Name", listOf(claim(IdentifierType.HANDLE, "@two"))),
        )

        assertEquals(0.0, match.score, TOLERANCE)
        assertTrue(match.signals.isEmpty())
        assertFalse(match.merges)
    }

    @Test
    fun `a record is not matched against itself`() {
        // Every signal would agree, the pair would clear any threshold, and the result would be
        // a contact merged into itself — a link whose head is its own tail.
        val one = candidate("one", "A Name", listOf(claim(IdentifierType.HANDLE, "@one")))

        assertFailsWith<IllegalArgumentException> { scorer.score(one, one) }
    }

    @Test
    fun `a threshold of nothing is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { Weights.STARTING.copy(threshold = 0.0) }
    }

    private fun candidate(label: String, displayName: String?, claims: List<ObservedIdentifier>) =
        MatchCandidate(
            record = ExternalId(Source.TELEGRAM, label),
            displayName = displayName,
            claims = claims,
        )

    private fun claim(
        type: IdentifierType,
        raw: String,
        lastSeen: String = "2024-01-01T00:00:00Z",
    ): ObservedIdentifier {
        val identifier = requireNotNull(Identifier.of(type, raw)) { "$raw is not a usable $type for this test." }
        return ObservedIdentifier(
            identifier = identifier,
            addedAt = Instant.parse("2020-01-01T00:00:00Z"),
            lastSeenAt = Instant.parse(lastSeen),
        )
    }

    private companion object {
        const val TOLERANCE = 1e-9

        /** Enough records sharing a value that it discriminates nobody. */
        const val MANY = 30

        /** Small enough that a signal scoring under it could never accumulate into a merge. */
        const val NEAR_ZERO = 0.05
    }
}
