package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The written form of a span identifier, which has to survive a round trip through a model.
 *
 * The cases that matter are the ones where a careless parse would succeed with the wrong answer
 * rather than fail: a native identifier carrying the delimiters, and a reply that is nearly an
 * identifier. A citation parsed wrongly is worse than one rejected, because the grounding check
 * then compares something nobody wrote against the retrieved set.
 */
class CitationTest {

    @Test
    fun `a rendered identifier parses back to itself`() {
        val span = SpanId(RecordKind.MESSAGE, ExternalId(Source.TELEGRAM, "42"), ordinal = 0)

        assertEquals(span, Citation.parse(Citation.render(span)))
    }

    @Test
    fun `a native identifier containing the field delimiter survives`() {
        // Telegram numbers messages per chat, so an adapter qualifies the identifier — which is
        // exactly the shape a greedy split would cut in the wrong place.
        val span = SpanId(RecordKind.MESSAGE, ExternalId(Source.TELEGRAM, "chat/88/msg/3"), ordinal = 0)

        assertEquals(span, Citation.parse(Citation.render(span)))
    }

    @Test
    fun `a native identifier containing the ordinal delimiter survives`() {
        // The ordinal is found from the right for this reason: the last hash is the real one.
        val span = SpanId(RecordKind.DOCUMENT, ExternalId(Source.TELEGRAM, "file#7"), ordinal = 2)

        assertEquals(span, Citation.parse(Citation.render(span)))
    }

    @Test
    fun `a non-zero ordinal round trips`() {
        val span = SpanId(RecordKind.DOCUMENT, ExternalId(Source.TELEGRAM, "doc-1"), ordinal = 11)

        assertEquals(span, Citation.parse(Citation.render(span)))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        // The identifiers arrive split out of a comma-separated list, so each carries a space.
        val span = SpanId(RecordKind.MESSAGE, ExternalId(Source.TELEGRAM, "42"))

        assertEquals(span, Citation.parse("  ${Citation.render(span)}  "))
    }

    @Test
    fun `a kind this build does not declare is not an identifier`() {
        assertNull(Citation.parse("INVOICE/TELEGRAM/42#0"))
    }

    @Test
    fun `a source this build does not declare is not an identifier`() {
        assertNull(Citation.parse("MESSAGE/WHATSAPP/42#0"))
    }

    @Test
    fun `a missing ordinal is not an identifier`() {
        assertNull(Citation.parse("MESSAGE/TELEGRAM/42"))
    }

    @Test
    fun `an ordinal that is not a number is not an identifier`() {
        assertNull(Citation.parse("MESSAGE/TELEGRAM/42#first"))
    }

    @Test
    fun `a negative ordinal is not an identifier`() {
        // `SpanId` refuses one outright, so parsing it would raise where a verdict is wanted.
        assertNull(Citation.parse("MESSAGE/TELEGRAM/42#-1"))
    }

    @Test
    fun `an empty native identifier is not an identifier`() {
        assertNull(Citation.parse("MESSAGE/TELEGRAM/#0"))
    }

    @Test
    fun `an empty kind is not an identifier`() {
        assertNull(Citation.parse("/TELEGRAM/42#0"))
    }

    @Test
    fun `an empty source is not an identifier`() {
        assertNull(Citation.parse("MESSAGE//42#0"))
    }

    @Test
    fun `prose is not an identifier`() {
        assertNull(Citation.parse("as agreed last week"))
    }

    @Test
    fun `an empty string is not an identifier`() {
        assertNull(Citation.parse(""))
    }
}
