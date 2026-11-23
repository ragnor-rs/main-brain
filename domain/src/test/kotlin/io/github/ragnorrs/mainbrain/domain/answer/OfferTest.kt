package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The numbers a request's material is offered under.
 *
 * **Most of these are about what must not read as a citation**, because that is the side with a cost.
 * A number the model never meant as one turns a line of composed prose into an assertion, which then
 * fails the grounding gate and is taken out of the reply — so a draft loses a sentence somebody wrote.
 * The other direction is cheaper: a citation that fails to read costs the claim it was on.
 */
class OfferTest {

    @Test
    fun `material is numbered from one, as somebody counting reads it`() {
        val offer = Offer(listOf(span("42"), span("43")))

        assertEquals("[1]", offer.label(0))
        assertEquals("[2]", offer.label(1))
    }

    @Test
    fun `a number resolves to the span it was offered for`() {
        val offer = Offer(listOf(span("42"), span("43")))

        assertEquals(span("42"), offer.span(1))
        assertEquals(span("43"), offer.span(2))
    }

    @Test
    fun `one span offered two numbers is not constructible`() {
        // It is one piece of evidence, and offered twice the model cites both — which shows the
        // reader the same finding under two numbers. The guard is here rather than in the caller
        // because every caller of this is a caller that has to get it right.
        assertFailsWith<IllegalArgumentException> { Offer(listOf(span("42"), span("42"))) }
    }

    @Test
    fun `a label for a position nothing was offered at is a defect rather than a number`() {
        // The prompt and the offer are built by walking one list. A position outside it means they
        // have come apart, and answering with a plausible label would hide that until a reply cited
        // it and could not be resolved.
        assertFailsWith<IllegalArgumentException> { Offer(listOf(span("42"))).label(1) }
    }

    @Test
    fun `a bare number is what the format asks for and reads`() {
        assertEquals(3, Offer.NONE.number("3"))
    }

    @Test
    fun `a number in the brackets the label printed reads too`() {
        // The material block prints `[3]`, so a model copying the label rather than the format's
        // example writes the brackets back. Both are the same citation and neither is worth a retry.
        assertEquals(3, Offer.NONE.number("[3]"))
    }

    @Test
    fun `surrounding whitespace does not stop a number reading`() {
        assertEquals(3, Offer.NONE.number("  [3]  "))
    }

    @Test
    fun `something that is not a number is not a citation`() {
        assertNull(Offer.NONE.number("nonsense"))
    }

    @Test
    fun `one bracket alone reads, because the citations are cut apart before they get here`() {
        // `<< [1, 2]` is one bracketed list to whoever wrote it and two half-bracketed pieces to this,
        // because the tail is split on the comma first. While a matching pair was required, neither
        // read, and each piece was a citation that resolved to nothing — which costs the claim it was
        // on, so the reader loses a finding the material fully supported.
        assertEquals(1, Offer.NONE.number("[1"))
        assertEquals(2, Offer.NONE.number("2]"))
    }

    @Test
    fun `a number with a tail is not a citation`() {
        // The guard that keeps a composed reply out of the gate. Russian prose is quoted with angle
        // brackets often enough that a drafted message contains a pair of them, which leaves `2>>`
        // where a citation would be — and read as two, the model's own draft becomes an assertion
        // about material it has nothing to do with, which the gate then takes out of the reply.
        assertNull(Offer.NONE.number("2>>"))
        assertNull(Offer.NONE.number("3 or so"))
    }

    @Test
    fun `an empty citation is not a number`() {
        assertNull(Offer.NONE.number(""))
    }

    @Test
    fun `a number past the end was offered for nothing`() {
        // Which is the case the whole representation exists for: it read as a citation, so the claim
        // stands as a claim, and it names no span, so the gate drops it.
        assertNull(Offer(listOf(span("42"))).span(2))
    }

    @Test
    fun `zero and below were offered for nothing either`() {
        // Numbering from one means there is no position zero. Read as an index it would be the first
        // span, which is a citation the model was never given resolving to real material.
        val offer = Offer(listOf(span("42")))

        assertNull(offer.span(0))
        assertNull(offer.span(-1))
    }

    @Test
    fun `nothing offered resolves nothing`() {
        assertNull(Offer.NONE.span(1))
    }

    @Test
    fun `a label and a reported number are written the same way`() {
        // The one coupling worth asserting over this type. A fault report names a number that was
        // never offered, so it cannot go through `label` — and two spellings of one form would have
        // the log say the model cited something the prompt never looks like it wrote.
        //
        // Both against the literal rather than against each other: `label` delegates to `written`, so
        // comparing the two would hold however the form were rewritten, including a rewrite the
        // reader could no longer parse.
        assertEquals("[1]", Offer(listOf(span("42"))).label(0))
        assertEquals("[1]", Offer.written(1))
    }

    private companion object {
        fun span(nativeId: String) = SpanId(RecordKind.MESSAGE, ExternalId(Source.TELEGRAM, nativeId))
    }
}
