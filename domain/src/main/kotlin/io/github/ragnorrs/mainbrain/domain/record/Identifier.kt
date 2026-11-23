package io.github.ragnorrs.mainbrain.domain.record

import java.time.Instant

/**
 * A supplemental identifier: something a record advertises about itself that may help match it
 * to another record.
 *
 * Two rules keep this from becoming identity by accident.
 *
 * **It names the same thing, never a related thing.** A mail header pointing at another
 * message is a relation and does not belong here.
 *
 * **It is a claim, not identity.** A profile advertising an address is evidence for
 * reconciliation, not a reconciliation. Nothing in the corpus layer may fuse two records
 * because they share one of these — that crossing belongs to the recogniser, and an adapter
 * that minted identity would fuse two people the first time a shared address appeared.
 *
 * Which is also why the store puts no uniqueness constraint on `(type, value)`. Two records
 * claiming one phone number is the evidence reconciliation runs on; rejecting it at write
 * time would turn the strongest available signal into a write error.
 *
 * Construct through [of], which normalises. An identifier that reached the store unnormalised
 * would match nothing and be invisible rather than wrong, which is the harder failure to find.
 */
data class Identifier(val type: IdentifierType, val value: String) {
    init {
        require(value.isNotBlank()) { "An identifier value cannot be blank." }
    }

    companion object {
        /**
         * The normalised form of [raw] for [type], or `null` when it has none.
         *
         * `null` rather than an exception, and rather than storing the raw string: a source
         * hands over whatever a human typed into a profile field, so unusable values are
         * ordinary input and not an error in the adapter. The caller decides whether a
         * dropped claim is worth reporting; what it may not do is store one that cannot match.
         */
        fun of(type: IdentifierType, raw: String): Identifier? =
            type.normalise(raw)?.let { Identifier(type, it) }
    }
}

/**
 * A claim together with when it was seen.
 *
 * Only the observation axis applies to a claim. It has no source time, because a claim is not
 * an event the source timestamps; and no content time, because the value is part of its key —
 * a changed value is a different claim, not the same one edited.
 *
 * The pair is worth keeping because the strongest matching signal is the one that decays. A
 * phone number agreeing across a long gap is weaker evidence than one agreeing now, since
 * numbers get reassigned, and only the claim's own last-seen can tell the two apart: a profile
 * re-observed yesterday whose number quietly vanished in some earlier year looks as current as
 * the record carrying it. Nothing is deleted, so a withdrawn claim stays — with its last-seen
 * standing still while the record's advances.
 */
data class ObservedIdentifier(
    val identifier: Identifier,
    val addedAt: Instant,
    val lastSeenAt: Instant,
)

/**
 * The kinds of claim a record can carry.
 *
 * Typed rather than free strings because untyped values both over- and under-match: a handle
 * that happens to read as a number should never be compared against a phone number, and two
 * identical hashes mean something only if both are hashes of the same kind of thing.
 */
enum class IdentifierType {
    /** A platform handle. Compared case-insensitively, so stored lowercase, without its sigil. */
    HANDLE,

    /**
     * A telephone number.
     *
     * Normalised to E.164's *shape* — a leading `+`, then digits — and no further. Deriving a
     * country code from a national-format number needs a region to assume and a library that
     * knows each country's numbering plan; neither belongs in a module that depends on
     * nothing. So a number already in international form is canonicalised, and one that is
     * not is refused rather than guessed at. Guessing is worse than dropping: a wrong country
     * code produces a number that matches a different person's.
     */
    PHONE,

    /** A mail address. Lowercased whole, which is wrong for the local part in theory and right in practice. */
    EMAIL,

    /** A free-text place from a profile. Lowercased and whitespace-collapsed; nothing else is derivable here. */
    LOCATION,

    /** A hash of content — an avatar, a file. Lowercased hex. */
    CONTENT_HASH,
    ;

    internal fun normalise(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return when (this) {
            HANDLE -> trimmed.removePrefix("@").lowercase().takeIf { it.isNotEmpty() }
            PHONE -> normalisePhone(trimmed)
            EMAIL -> trimmed.lowercase().takeIf { it.isMailAddress() }
            LOCATION -> trimmed.split(WHITESPACE).joinToString(" ").lowercase()
            CONTENT_HASH -> trimmed.lowercase().takeIf { it.all(Char::isHexDigit) }
        }
    }

    private fun normalisePhone(trimmed: String): String? {
        // Punctuation a human types into a phone field, and nothing else. Letters are not
        // stripped: a value carrying them is not a number in international form, and silently
        // discarding them would turn a vanity string into a plausible-looking wrong number.
        val stripped = trimmed.filterNot { it in PHONE_PUNCTUATION }
        if (!stripped.startsWith("+")) return null
        val digits = stripped.drop(1)
        return stripped.takeIf { digits.isNotEmpty() && digits.length <= E164_MAX_DIGITS && digits.all(Char::isDigit) }
    }

    private fun String.isMailAddress(): Boolean {
        val at = indexOf('@')
        return at > 0 && at == lastIndexOf('@') && at < lastIndex && '.' in substring(at)
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val PHONE_PUNCTUATION = " -()./"

        /** E.164 caps a number at fifteen digits after the `+`, country code included. */
        const val E164_MAX_DIGITS = 15
    }
}

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
