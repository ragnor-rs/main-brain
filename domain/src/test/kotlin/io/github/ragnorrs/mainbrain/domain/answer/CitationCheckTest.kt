package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The grounding check.
 *
 * Two cases carry the whole rule and they pull in opposite directions: a claim citing something
 * nobody retrieved has to fail, and composed prose citing nothing has to pass. A check that got
 * either wrong would be useless in a way nothing downstream reports — it would block every
 * draft, or verify nothing at all.
 */
class CitationCheckTest {

    @Test
    fun `a claim resting on retrieved material is grounded`() {
        val span = span("a message")

        val ungrounded = CitationCheck().ungrounded(
            variants = listOf(Variant("what was agreed", listOf(Claim("they agreed", setOf(span))))),
            retrieved = setOf(span),
        )

        assertTrue(ungrounded.isEmpty())
    }

    @Test
    fun `a claim citing what was never retrieved is reported, by identifier`() {
        val retrieved = span("a message that was read")
        val invented = span("a message nobody read")

        val ungrounded = CitationCheck().ungrounded(
            variants = listOf(Variant("what was agreed", listOf(Claim("they agreed", setOf(invented))))),
            retrieved = setOf(retrieved),
        )

        assertEquals(
            setOf(invented),
            ungrounded,
            "The offender is named rather than a bare failure. Whoever reports a degrade has " +
                "to be able to say which citation was invented.",
        )
    }

    @Test
    fun `composed prose carrying no claims passes rather than failing for silence`() {
        // A drafted reply is composed text and cites nothing. A check that required a citation
        // per variant would refuse every draft the system produces.
        val ungrounded = CitationCheck().ungrounded(
            variants = listOf(Variant("a reply somebody can send")),
            retrieved = emptySet(),
        )

        assertTrue(ungrounded.isEmpty())
    }

    @Test
    fun `every variant is checked, not only the first`() {
        val read = span("a message that was read")
        val invented = span("a message nobody read")

        val ungrounded = CitationCheck().ungrounded(
            variants = listOf(
                Variant("the first draft", listOf(Claim("they agreed", setOf(read)))),
                Variant("the second draft", listOf(Claim("they also agreed", setOf(invented)))),
            ),
            retrieved = setOf(read),
        )

        assertEquals(setOf(invented), ungrounded)
    }

    @Test
    fun `a claim with no citation cannot be built`() {
        // It would be indistinguishable from composed prose, which this check deliberately
        // passes — so an uncited claim would be an assertion about the corpus that is never
        // examined.
        assertFailsWith<IllegalArgumentException> { Claim("they agreed", cites = emptySet()) }
    }

    @Test
    fun `a citation that could not be read is a citation for the purpose of having one`() {
        // The claim rests on something the model wrote; nothing could resolve it. That is an
        // assertion the gate refuses rather than a line with no citations on it, and the difference
        // decides whether the variant goes with it.
        val claim = Claim("they agreed", cites = emptySet(), unreadable = setOf("not a number"))

        assertFalse(
            claim.grounded(setOf(span("42"))),
            "A claim whose only citation nothing could read was treated as grounded.",
        )
    }

    @Test
    fun `an unreadable citation that is blank names nothing and cannot be carried`() {
        // It is carried so the fault report can say what the model wrote. Blank, it says nothing,
        // and what it would describe is a line with no citation on it at all — which is the case
        // the reader refuses outright rather than this one.
        assertFailsWith<IllegalArgumentException> {
            Claim("they agreed", cites = emptySet(), unreadable = setOf("   "))
        }
    }

    @Test
    fun `a variant with no text is not a rendering of anything`() {
        assertFailsWith<IllegalArgumentException> { Variant("   ") }
    }

    private companion object {
        fun span(nativeId: String) = SpanId(RecordKind.MESSAGE, ExternalId(Source.TELEGRAM, nativeId))
    }
}
