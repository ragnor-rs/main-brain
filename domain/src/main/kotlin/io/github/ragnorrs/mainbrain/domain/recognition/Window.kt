package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Message

/**
 * The unit of work inference reads: one message, with the conversation around it as context.
 *
 * **One message is read and its neighbours are only looked at.** A commitment is stated in one turn
 * and closed several turns later, so a message on its own cannot say whether what was promised was
 * ever done — but a stretch of conversation read as a whole produces findings from every message in
 * it, including the ones nothing selected and nothing marks. Naming one [target] and treating the
 * rest as context lines selection, extraction and marking up on the same record: what is read is
 * what was owed, and what is written is what gets marked.
 *
 * **What that gives up is a fact stated only in a context message.** A stretch read whole would
 * find it, and the call has already been paid for either way. It is given up because keeping it
 * means keeping it *without a mark* — nothing would record that the message had been read, so every
 * later window reaching back over it would produce the same finding again.
 *
 * **A line names its author where the corpus names them, and carries no name otherwise.** A claim
 * kept only if it survives being read on its own needs something to name its party with, and the
 * corpus's own name for a person record is the one thing that both reads as a party and is a
 * function of that record rather than of this window — see [label]. Where the corpus holds no
 * usable name the field is empty, and nothing is invented to fill it.
 *
 * **Named by its target, which is a record identifier and does not move.** A window is found again
 * a submission later by the identifier of the message it reads, so material landing around it
 * changes a window's context without changing which window it is.
 */
data class Window(
    val conversation: ExternalId,
    val messages: List<Message>,
    val target: ExternalId,
    /**
     * What the corpus calls each author, where it calls them anything.
     *
     * Read from the person records rather than carried on a message, and passed in rather than
     * looked up here: `:domain` holds no store. Not every entry is used — see [label].
     */
    val names: Map<ExternalId, String> = emptyMap(),
) {

    init {
        require(messages.isNotEmpty()) { "A window with no messages has nothing to read." }
        require(messages.any { it.externalId == target }) {
            "A window's target is the message it reads, so it has to be one of the messages in it: " +
                "${target.nativeId} is not."
        }
    }

    /** The message this window is read for. The context is every other message here. */
    val targetMessage: Message get() = messages.first { it.externalId == target }

    /**
     * Whether there is anything to read.
     *
     * The target's own text, and not the context's. A sticker is a message a model has nothing to
     * find in; asked anyway, it would be billed for the instruction and the surrounding
     * conversation and reply nothing — and a finding from a context line is not this window's to
     * report. So this is what a caller checks before spending a call.
     */
    val hasText: Boolean get() = !targetMessage.text.isNullOrBlank()

    /**
     * What to call [author] in front of the model, or `null` where the corpus names them nothing
     * this can use.
     *
     * **A label the model may echo, never an identifier it may invent.** The claim has to name the
     * party it concerns, so the model needs something to name it *with*; handed a record identifier
     * it would write identifiers into claims, and minting identity is [Reconciler]'s and nothing
     * else's.
     *
     * **It is the corpus's own name or nothing, because a label has to be a function of the author
     * and not of this window.** This is the comparison that decides what an attribute is about, and
     * a window is re-derived between the two stages a submission apart. A label computed from the
     * window — a position in it, say — is reassigned when the context grows, so the same string
     * means one party when the model answers and another when the answer is read, and a fact about
     * one person is stored against another. A name cannot move that way: it belongs to the record.
     *
     * A name is passed over in two cases, each one where it would be worse than none:
     *
     * - two of this window's authors share it once normalised, so it names a party only
     *   ambiguously — and an ambiguous label here is a fact filed against whichever of them the
     *   comparison reaches;
     * - it is the word the instructions reserve for "no subject", so that an attribute about a
     *   company could not match a person who happens to be named that word.
     *
     * A name carrying the field delimiter or a line break is not one of them: it would make the
     * line unreadable, so it is repaired rather than passed over — see [labels].
     */
    fun label(author: ExternalId?): String? = author?.let { labels[it] }

    /**
     * Which author [label] names, or `null` where it names none of this window's.
     *
     * **[label] read the other way round.** The restating stage answers which party an obligation
     * falls on as one of these labels, and what is stored is a reference to the record behind it —
     * so the direction the model writes in and the direction this reads back have to agree about
     * what a label is.
     *
     * **Matched through [nameKey], which is the relation [labels] withdrew a shared name under,
     * and that is what makes this unambiguous rather than nearly so.** A looser comparison can
     * hold two labels equal that the withdrawal left standing as distinct — case folding and
     * [nameKey]'s lowercasing do not agree on every script — and then this would answer whichever
     * author the window happens to list first, which is the obligation placed on the wrong
     * participant.
     *
     * **A string no author here carries resolves to nothing, never to a new party.** A party the
     * window never showed the model is a party nothing can place, and minting a record for one is
     * the invention [label] exists to prevent. So is the reserved word, which [labels] already
     * withdraws from every author.
     *
     * **The answer goes through [repaired] first, which is what lets the model re-space a label
     * without costing the finding its direction.** A stored label is one of [label]'s own outputs
     * and needs nothing: it carries no delimiter and single ASCII spaces, so the key round-trips
     * it. What arrives here is not bound to be one of those outputs, and the gap is narrow but
     * real — [nameKey] trims by [Char.isWhitespace] and then collapses runs with a pattern, whose
     * class is the ASCII six, so a space outside that class survives *inside* a name and keys to a
     * string no stored label can produce. The repair reads the Unicode predicate throughout, so
     * composing the two is what makes the relation here the one [labels] tested uniqueness under
     * rather than a near neighbour of it.
     *
     * **It is the caller that repairs, and not [nameKey], because that key is pinned to the
     * store.** The same expression is written in SQL and indexed there, so widening it would
     * desynchronise the matcher, the frequency count and the index from each other — which is why
     * the normalisation belongs at the one call site that needs it.
     *
     * The repair also substitutes the delimiter, which cannot fire on this path: the field reaching
     * here was produced by splitting a reply on that very character. Only the whitespace half does
     * anything, and it is the half this composes for.
     */
    fun party(label: String): ExternalId? {
        val wanted = nameKey(repaired(label)) ?: return null
        return labels.entries.firstOrNull { nameKey(it.value) == wanted }?.key
    }

    /**
     * Every author here that the corpus names usably, labelled.
     *
     * Keyed by the author record, so a label is a function of that record and of which names this
     * window's *other* authors carry — the one window-dependent part, and it can only ever remove a
     * label rather than move one to a different party.
     *
     * **A name carrying the delimiter or a line break is repaired rather than discarded.** What the
     * line's shape needs is that no label contains either, and removing the offending characters
     * buys that as completely as refusing the name does — while refusing it costs every finding
     * about that party, which on a corpus where people put punctuation in their display names is a
     * participant the pass can never record anything about. The repair is a function of the name
     * alone, so the label stays a property of the author record and not of this window.
     *
     * Everything else is still tested after the repair, which is the order that matters: a name of
     * nothing but delimiters repairs to empty and is dropped, and two names that differ only in
     * the characters removed repair to one string, which the uniqueness test then withdraws from
     * both rather than filing a fact against whichever is reached first.
     *
     * **Uniqueness is tested in the form the subject is compared in, which is [nameKey]'s.** The
     * comparison deciding what an attribute is about ignores case, because the subject is free text
     * the model echoes back and a recapitalised name means the same party. So two authors whose
     * names differ only in case name the same party just as ambiguously as two who share a name
     * outright, and counting the raw strings would find them distinct, leave both labels standing,
     * and accept a fact stated about one of them as being about the other. Reusing [nameKey] rather
     * than writing the comparison again is what keeps the two from drifting apart; the repair runs
     * first, so every name reaching it has already had its whitespace normalised by a rule that
     * does not assume the corpus is written in ASCII.
     */
    private val labels: Map<ExternalId, String> by lazy {
        val authors = messages.mapNotNull { it.author }.distinct()
        val offered = authors.associateWith { names[it]?.let(::repaired).orEmpty() }
        val shared = offered.values.mapNotNull(::nameKey).groupingBy { it }.eachCount()
        authors.mapNotNull { author ->
            val name = offered.getValue(author)
            val key = nameKey(name)
            val usable = key != null &&
                shared.getValue(key) == 1 &&
                !key.equals(Extractor.NOTHING, ignoreCase = true)
            if (usable) author to name else null
        }.toMap()
    }

    /**
     * This window as a model reads it: one message per line, identifier first and text last.
     *
     * **Here rather than beside one stage's prompt, because it is the line shape every stage's
     * instruction describes in prose.** Two stages ask about a window — what one message states, and
     * what closed a commitment — and each tells the model that a line is its identifier, when it was
     * sent, who sent it, and its text. Two copies of the renderer would be two formats that have to
     * agree with two instructions, and the drift would not be visible: the model would be handed
     * fields in one order and told to read them in another, and would answer plausibly.
     *
     * The instant is on the line because the restating stage resolves a relative date against the
     * message it was stated in rather than against now — so the only clock in recognition is the
     * corpus's own, and a backfill read years later dates a promise to when it was made.
     *
     * **The author is on the line as a label and never as an identifier.** It is the third field and
     * not the last, because the text is last so that a delimiter inside it cannot shift a field —
     * and a label cannot carry the delimiter, which is [label]'s to guarantee rather than this
     * function's to escape.
     *
     * Newlines inside a message are flattened, because one message per line is what makes the
     * identifier the model echoes unambiguous. Only messages with text appear: the others carry
     * nothing to read and would spend tokens on a line saying so. The target is always among them
     * where it has text, and a window whose target has none is never asked about — see [hasText].
     */
    fun rendered(): String =
        messages
            .filter { !it.text.isNullOrBlank() }
            .joinToString("\n") { message ->
                listOf(
                    message.externalId.nativeId,
                    message.sourceTime.toString(),
                    label(message.author).orEmpty(),
                    message.text.orEmpty().lines().joinToString(" ") { it.trim() },
                ).joinToString(Extractor.FIELD)
            }

    /**
     * [name] with everything a rendered line cannot carry taken out of it.
     *
     * The delimiter, replaced by a space rather than closed up, so that two words a delimiter
     * separated do not run together into a third. Every kind of whitespace goes the same way and
     * runs are then collapsed, which is what keeps the result a name somebody would recognise and
     * keeps two authors whose names differ only in spacing from reading as two different labels.
     *
     * **Whitespace is decided by [Char.isWhitespace] and not by a pattern.** A regular expression's
     * `\s` is the ASCII six, so a non-breaking space — which display names on this source carry
     * routinely — would survive it, render invisibly, and leave two labels that look identical in
     * the prompt as two distinct strings here. Kotlin's predicate covers the space characters as
     * well, and the corpus is not written in ASCII.
     */
    private fun repaired(name: String): String =
        name.map { if (it == Extractor.FIELD.single() || it.isWhitespace()) ' ' else it }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    companion object {

        /**
         * How many messages either side of the target go in as context.
         *
         * Enough that a commitment and its closing message fall inside one window, which is the
         * whole reason the unit is not a bare message. Symmetric because the two directions buy
         * different things: what came before is what a pronoun in the target refers back to, and
         * what came after is whether the promise was taken up. Larger costs tokens on every call
         * and spreads the model's attention over material that has nothing to do with the one
         * message being read.
         */
        const val DEFAULT_CONTEXT = 10
    }
}

/**
 * One window per message in [targets] that [messages] still holds.
 *
 * **The conversation is read whole and the slices taken from it, rather than a stretch being
 * fetched per target.** The context around a message has to be the same on every run, so the input
 * is sorted here rather than trusted: the identifier breaks a tie between two messages sharing an
 * instant, so a re-run cannot produce different context and re-infer the corpus for nothing.
 *
 * A target [messages] does not hold is absent from the result rather than an error. Nothing in this
 * build takes a message out of its conversation — the corpus removes nothing, and a withdrawal
 * marks rather than deletes — so a caller reaching that case has found a corpus it did not expect
 * rather than the ordinary course of events. It is still the only way a window can go missing, now
 * that a window is named by the record it reads.
 *
 * [names] is every author's name the corpus knows, not only this conversation's. A window uses the
 * entries for its own authors and ignores the rest.
 */
fun windowsAround(
    conversation: ExternalId,
    messages: List<Message>,
    targets: Collection<ExternalId>,
    names: Map<ExternalId, String> = emptyMap(),
    reach: Int = Window.DEFAULT_CONTEXT,
): List<Window> {
    require(reach >= 0) { "A context of $reach messages either side of the target is not a context." }
    if (messages.isEmpty() || targets.isEmpty()) return emptyList()

    val ordered = messages.sortedWith(compareBy({ it.sourceTime }, { it.externalId.nativeId }))
    val wanted = targets.toSet()
    return ordered.withIndex()
        .filter { (_, message) -> message.externalId in wanted }
        .map { (position, message) ->
            Window(
                conversation = conversation,
                messages = ordered.subList(
                    maxOf(0, position - reach),
                    minOf(ordered.size, position + reach + 1),
                ),
                target = message.externalId,
                names = names,
            )
        }
}
