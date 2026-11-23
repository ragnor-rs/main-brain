package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.Source

/**
 * The written form of a span identifier — what a span is called when something has to write one down.
 *
 * **Not what the model is shown and not what it cites back.** Within one request the material is
 * offered under short numbers, because that is what survives being copied by a language model — see
 * [Offer]. A number means nothing outside the request that assigned it, so everything that outlives
 * one comes here instead: the wire a cited claim leaves on, the trace the evaluation set is scored
 * from, and the fault report that has to say which span the model named.
 *
 * `KIND/SOURCE/native#ordinal`. The two leading fields are enum names and contain neither
 * delimiter; the native identifier may contain both, so it is taken as everything between the
 * second slash and the **last** hash rather than by splitting the whole string. A form that split
 * greedily would mis-parse exactly the identifiers an adapter qualifies with a separator, which is
 * every Telegram message.
 *
 * **Parseable without a lookup, which is the property the grounding check rests on.** It compares
 * identifiers and nothing else, so a form needing a database round trip to interpret would make the
 * check cost a read per claim.
 *
 * **Visible outside this package, because the form leaves the backend.** A cited claim travels
 * carrying these strings so that whoever renders it can take somebody to the message it rests on.
 * Rendering a second copy of the form where the wire is mapped would be two spellings of one thing,
 * and the drift would show up as citations that no longer resolve rather than as a failure anybody
 * notices.
 */
object Citation {

    /** [span], written down. */
    fun render(span: SpanId): String =
        "${span.kind.name}$FIELD${span.record.source.name}$FIELD${span.record.nativeId}$ORDINAL${span.ordinal}"

    /**
     * [text] read back as a span identifier, or `null` if it is not one.
     *
     * **The inverse of [render], which is what keeps the form on the wire resolvable rather than
     * merely unique.** A cited claim leaves carrying these strings so that whatever renders it can
     * be handed the material a finding rests on; reading one back is how that is done, and no
     * interface in this build does it yet — the bot shows the prose and nothing else. So this has no
     * caller outside the round trip asserted over the pair, and that assertion is what holds the two
     * halves in step until one arrives.
     *
     * Null rather than an exception, because what it reads arrived from outside: a string that is not
     * an identifier is a question with an answer, and throwing would make the caller's error path
     * decide it.
     *
     * Two steps, because the string has to be cut up before any piece of it can be interpreted:
     * [cut] finds the fields and [read] decides whether they name anything this build knows.
     */
    fun parse(text: String): SpanId? = cut(text.trim())?.let { read(it) }

    /**
     * The four fields of [text], or `null` if it is not shaped like an identifier at all.
     *
     * Each delimiter is required strictly past the one before it, so an empty field fails here
     * rather than arriving at [read] as an empty enum name. The ordinal is found from the right,
     * which is what lets a native identifier contain the delimiter itself.
     */
    private fun cut(text: String): Fields? {
        val firstField = text.indexOf(FIELD)
        val secondField = if (firstField > 0) text.indexOf(FIELD, startIndex = firstField + 1) else NOWHERE
        val lastOrdinal = text.lastIndexOf(ORDINAL)
        val shaped = firstField > 0 && secondField > firstField + 1 && lastOrdinal > secondField + 1
        return if (!shaped) {
            null
        } else {
            Fields(
                kind = text.take(firstField),
                source = text.substring(firstField + 1, secondField),
                native = text.substring(secondField + 1, lastOrdinal),
                ordinal = text.substring(lastOrdinal + 1),
            )
        }
    }

    /**
     * [fields] as a span identifier, or `null` if any of them names nothing.
     *
     * An unknown kind or source is null rather than an exception for the same reason the whole
     * function is: both are enums this build declares, so a name outside them was invented rather
     * than retrieved, and `valueOf` would raise where the caller needs a verdict.
     */
    private fun read(fields: Fields): SpanId? {
        val kind = RecordKind.entries.firstOrNull { it.name == fields.kind }
        val ordinal = fields.ordinal.toIntOrNull()?.takeIf { it >= 0 }
        val record = Source.entries
            .firstOrNull { it.name == fields.source }
            ?.takeIf { fields.native.isNotBlank() }
            ?.let { ExternalId(it, fields.native) }
        return if (kind == null || record == null || ordinal == null) {
            null
        } else {
            SpanId(kind = kind, record = record, ordinal = ordinal)
        }
    }

    /** The four substrings of a rendered identifier, before any of them has been interpreted. */
    private class Fields(
        val kind: String,
        val source: String,
        val native: String,
        val ordinal: String,
    )

    private const val FIELD = '/'
    private const val ORDINAL = '#'

    /** What [String.indexOf] returns when it found nothing. */
    private const val NOWHERE = -1
}
