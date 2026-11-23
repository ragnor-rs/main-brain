package io.github.ragnorrs.mainbrain.domain.record

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Normalisation, which is the whole of what a claim's value means.
 *
 * An identifier that reaches the store unnormalised matches nothing. That failure is invisible
 * rather than loud — the record is there, the claim is there, and the matcher simply never
 * pairs it with anything — so the cases below are the ones that decide whether recognition can
 * work at all, and they are cheap to assert here against no database.
 */
class IdentifierTest {

    @Test
    fun `a handle loses its sigil and its case`() {
        assertEquals(Identifier(IdentifierType.HANDLE, "someone"), Identifier.of(IdentifierType.HANDLE, "@SomeOne"))
        assertEquals(Identifier(IdentifierType.HANDLE, "someone"), Identifier.of(IdentifierType.HANDLE, "  someone "))
    }

    @Test
    fun `a handle that is nothing but its sigil has no normal form`() {
        assertNull(Identifier.of(IdentifierType.HANDLE, "@"))
    }

    @Test
    fun `a number already in international form is canonicalised`() {
        assertEquals(
            Identifier(IdentifierType.PHONE, "+995555123456"),
            Identifier.of(IdentifierType.PHONE, "+995 (555) 12-34-56"),
        )
    }

    @Test
    fun `a number in national form is refused rather than guessed at`() {
        // Deriving the country code would need a region to assume. A wrong one produces a
        // number that matches a different person's, which is worse than having no claim.
        assertNull(Identifier.of(IdentifierType.PHONE, "555 12 34 56"))
        assertNull(Identifier.of(IdentifierType.PHONE, "8 555 1234567"))
    }

    @Test
    fun `a number that is not digits is refused`() {
        assertNull(Identifier.of(IdentifierType.PHONE, "+1-800-FLOWERS"))
        assertNull(Identifier.of(IdentifierType.PHONE, "+"))
    }

    @Test
    fun `a number longer than E164 allows is refused`() {
        assertEquals(
            Identifier(IdentifierType.PHONE, "+123456789012345"),
            Identifier.of(IdentifierType.PHONE, "+123456789012345"),
        )
        assertNull(Identifier.of(IdentifierType.PHONE, "+1234567890123456"))
    }

    @Test
    fun `a mail address is lowercased whole`() {
        assertEquals(
            Identifier(IdentifierType.EMAIL, "someone@example.com"),
            Identifier.of(IdentifierType.EMAIL, " Someone@Example.COM "),
        )
    }

    @Test
    fun `something that is not a mail address is refused`() {
        assertNull(Identifier.of(IdentifierType.EMAIL, "someone"))
        assertNull(Identifier.of(IdentifierType.EMAIL, "someone@example"))
        assertNull(Identifier.of(IdentifierType.EMAIL, "someone@@example.com"))
        assertNull(Identifier.of(IdentifierType.EMAIL, "@example.com"))
    }

    @Test
    fun `a place is collapsed and lowercased`() {
        assertEquals(
            Identifier(IdentifierType.LOCATION, "a city, somewhere"),
            Identifier.of(IdentifierType.LOCATION, "  A   City,\tSomewhere "),
        )
    }

    @Test
    fun `a hash is lowercased and must be hexadecimal`() {
        assertEquals(
            Identifier(IdentifierType.CONTENT_HASH, "abcdef0123"),
            Identifier.of(IdentifierType.CONTENT_HASH, "ABCDEF0123"),
        )
        assertNull(Identifier.of(IdentifierType.CONTENT_HASH, "not-a-hash"))
    }

    @Test
    fun `nothing at all has no normal form, whatever the type`() {
        IdentifierType.entries.forEach { type ->
            assertNull(Identifier.of(type, "   ")) { "$type accepted blank input." }
        }
    }
}
