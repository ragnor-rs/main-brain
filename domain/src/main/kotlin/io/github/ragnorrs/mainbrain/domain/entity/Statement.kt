package io.github.ragnorrs.mainbrain.domain.entity

import java.time.Instant

/**
 * One fact about one entity, over the period it held.
 *
 * **Validity belongs here and not on an entity.** A contact has no period of validity; *that this
 * contact works at that company* does. So attributes and relations are statements with the
 * interval over which they were true, and a value that changes becomes two statements with
 * adjacent windows rather than one field overwritten — which is the whole of what makes "what was
 * true when we last spoke" answerable at all.
 *
 * **Its key is its content**: [subject], [predicate], [value] and the start of [validity]. Nothing
 * mints an identifier for a statement, unlike every entity in this package, and that is the
 * property re-derivation rests on — the same fact read again out of the same corpus produces the
 * same key and writes nothing new. Two parts of the key are there for reasons worth stating:
 *
 *  * **[value], because a contradiction keeps both readings.** Same subject, same predicate, a
 *    different value and later evidence closes the earlier window where the later one opens. That
 *    is two statements, adjacent, with nothing deleted — so a key without the value would make a
 *    correction an overwrite and lose the history it exists to keep.
 *  * **The window's start, because one value can hold over two separate periods.** A rate quoted
 *    in March, revised in June and restored in September is three statements, two of which agree
 *    on every other part of the key.
 *
 * [evidence] accumulates on the statement rather than splitting it. The same fact read out of two
 * *different* spans is corroboration, not a duplicate: it is one statement carrying two pointers,
 * and anything presenting it can show either. The same span read twice — which a re-derivation does
 * whenever the pass's version moves — adds nothing, because a piece of evidence is itself keyed by
 * where it points.
 *
 * A statement is never presented as a bare fact. [origin] decides how it may be phrased, and the
 * evidence is shown with it, because the weakest thing in this package is a value a model read out
 * of hedged text.
 */
data class Statement(
    /**
     * The entity the fact is about — a contact or a client.
     *
     * No discriminator and no reference to either table, because the referent is one of two and
     * the asker always holds the entity it is asking about. The cost is that nothing refuses a
     * statement about an entity that was never created; what closes that is identity being
     * minted by a recognition pass and by nothing else, so there is one producer of these and
     * it has the entity in hand.
     */
    val subject: EntityId,
    val predicate: Predicate,
    val value: String,
    val validity: Validity,
    val origin: Origin,
    val evidence: Set<Evidence>,
) {
    init {
        require(value.isNotBlank()) {
            "A statement with a blank value asserts nothing, and it is not the way to say a fact " +
                "stopped holding — closing its validity window is."
        }
        require(evidence.isNotEmpty()) {
            "Every derived fact points at the corpus it was read out of. A statement with no " +
                "evidence cannot be cited, cannot be ordered against a contradicting one, and " +
                "cannot be checked by anyone who doubts it."
        }
    }

    /** Whether this statement was holding at [instant]. */
    fun heldAt(instant: Instant): Boolean = validity.covers(instant)

    /**
     * The four parts above, as one value — this statement's row, without what is merged into it.
     *
     * **The close is not in it, and that is what makes it the key rather than the whole statement.**
     * [Validity.until] sits outside the key so that closing a window is an update; so two readings
     * sharing this are one row, one of them possibly carrying a close the other does not.
     *
     * Read by a caller that has to ask whether a reading it offered survived resolution as a row of
     * its own: compared on the whole statement, a window that merely ended up closed would look like
     * a reading that was dropped.
     */
    val key: Key get() = Key(subject, predicate, value, validity.from)

    /** A statement's content key. See [Statement.key]. */
    data class Key(val subject: EntityId, val predicate: Predicate, val value: String, val from: Instant)
}

/**
 * What a statement asserts about its subject, as a normalised name.
 *
 * Text rather than an enumeration, because the vocabulary is the extractor's and is not settled:
 * closing it here would mean a prompt change that names a new relation could not be stored until
 * the domain was rebuilt. What that openness costs is real and belongs in the open — two
 * predicates meaning the same thing are two statements that will never be compared, so nothing
 * *here* dedupes "works at" against "employed by".
 *
 * **The line is held where the vocabulary is chosen rather than here.**
 * [io.github.ragnorrs.mainbrain.domain.recognition.Extractor] offers the model a fixed list and refuses
 * anything outside it, so the only predicates that reach this type are the ones that list names.
 * This stays open so that extending the list is a prompt change; it is not open so that anything
 * may be stored.
 *
 * Construct through [of], which normalises. An unnormalised predicate reaching the store would
 * key a statement under a name nothing else queries — a fact that is present, correct and
 * invisible, which is the harder kind of wrong to find.
 */
@JvmInline
value class Predicate(val name: String) {
    init {
        require(name.isNotBlank()) { "A statement's predicate names what is being asserted." }
        require(name == name.normalisedPredicate()) {
            "A predicate is stored normalised, so that one written two ways is one key. " +
                "Construct it through `Predicate.of`."
        }
    }

    override fun toString(): String = name

    companion object {
        /** The predicate [raw] denotes: trimmed, lowercased, and with its whitespace collapsed. */
        fun of(raw: String): Predicate = Predicate(raw.normalisedPredicate())
    }
}

private val WHITESPACE = Regex("\\s+")

private fun String.normalisedPredicate(): String = trim().split(WHITESPACE).joinToString(" ").lowercase()

/**
 * The period a statement held over: from [from], up to but not including [until].
 *
 * Half-open on purpose. A contradiction closes the earlier window exactly where the later one
 * opens, so the two share an instant — and under closed intervals that instant would belong to
 * both, making a question about it answerable two ways. Half-open makes adjacency exact: no gap
 * between them, and no overlap.
 *
 * [until] is `null` while a statement still holds, which is the ordinary case and not a missing
 * value. Nothing closes a window except a later, contradicting statement; a fact simply going
 * unmentioned is not evidence that it stopped being true.
 *
 * **[from] is the source time of the evidence, never the moment of extraction.** A backfill reads
 * 2021 after it read yesterday, so ordering by when the system learnt something would let old
 * history overwrite current facts as it arrived.
 */
data class Validity(val from: Instant, val until: Instant? = null) {
    init {
        require(until == null || until > from) {
            "A validity window ends after it begins. A window closed at or before its own start " +
                "covers no instant, so the statement would be stored as never having held."
        }
    }

    /** Whether this window covers [instant]. */
    fun covers(instant: Instant): Boolean =
        instant >= from && (until == null || instant < until)
}

/**
 * Where a statement came from, which decides how the assistant is allowed to phrase it.
 *
 * Declared in increasing order of authority, and compared in that order: a tie between two
 * readings of the same fact is broken by preferring the more authoritative origin, because a
 * profile field outranks a passing mention and a human's correction outranks both.
 */
enum class Origin {
    /** A model read it out of text. The weakest, and the one that is never shown as a bare fact. */
    INFERRED,

    /** A source stated it as structure — a profile field, a topic's label. */
    LABELLED,

    /** A person said so. A correction, which re-recognition may not overwrite. */
    CONFIRMED,
}
