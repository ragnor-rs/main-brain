package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.index.SpanId

/**
 * The numbers one request's retrieved material was offered under, and the written form of one.
 *
 * **A citation the model copies back is a number rather than a span identifier, because a number
 * cannot be mis-copied in the ways a long string can.** A span identifier runs to about forty
 * characters, and a shape whose answer is a set of cited findings writes some eighteen of them — so a
 * reply carrying that many copies of that much text will sometimes truncate one, drop a delimiter or
 * reformat a field, and each of those costs the claim it was on. A single digit can be got wrong only
 * by being a different digit, and a number outside what was offered is recognisable as such rather
 * than looking like a plausible identifier. It also shortens the prompt by a few hundred tokens on
 * the path where somebody is waiting.
 *
 * **It means nothing without the offer that assigned it, which is the whole difference from
 * [Citation].** The long form is symmetric and stateless: the same string names the same span in any
 * request, so rendering and parsing need nothing but the string. A number is per-request, so the
 * assignment has to travel from the prompt to the reply — which is why this is carried on
 * [Assembled], beside the prompt text it labelled, rather than derived twice.
 *
 * Numbers start at one, because they are read by somebody counting rather than indexing.
 */
data class Offer(val spans: List<SpanId>) {

    init {
        require(spans.distinct().size == spans.size) {
            "One span offered two numbers is one piece of evidence presented as two, and the " +
                "model then cites both — which shows the reader the same finding twice. " +
                "Deduplicate the material before numbering it."
        }
    }

    /**
     * The label the material at [position] carries in the prompt.
     *
     * Takes the position rather than the span so that it cannot be asked for a label the prompt
     * never printed: the caller is walking the same list this was built from, and a lookup by span
     * would quietly answer for one that is not in the offer at all.
     */
    fun label(position: Int): String {
        require(position in spans.indices) {
            "Nothing was offered at position $position, so there is no label for it. The prompt " +
                "and the offer are built from one list, and a position outside it means they have " +
                "come apart."
        }
        return written(position + FIRST)
    }

    /**
     * The number [token] was written as, or `null` if it was not written as a number at all.
     *
     * Brackets are accepted but not required. The material block prints them, so a model copying a
     * label verbatim writes them too; the format asks for the bare number because that is the
     * shorter thing to copy. Both have to read.
     *
     * **Either bracket alone is stripped, not only a matching pair**, because the citations are cut
     * apart on the comma before they get here. A model handed labels printed as `[1]` and an example
     * written `1, 4` compacts the two into `[1, 2]` — which is one bracketed list to a reader and
     * two half-bracketed tokens to this. Requiring the pair left both unreadable, and a citation that
     * reads as no number costs the claim it was on: the finding is dropped and the reader is simply
     * not shown it, so a reply that cited perfectly well arrives missing what it was asked for.
     *
     * **The punctuation that ends a sentence is not part of a citation either.** A cited line the
     * model wrote without the claim marker ends the way prose ends, so its last citation arrives as
     * `2.` — and read as nothing, the whole line fails the test that recognises an unmarked line as a
     * claim and goes to the reader verbatim, cite marker and numbers included. That is the leak the
     * unmarked-line rule exists to close, reached through its own guard.
     *
     * **Nothing else is tolerated**, which is what keeps a composed reply from being read as a claim.
     * Russian prose is quoted with a doubled angle bracket often enough that a drafted message
     * contains a pair, and a tail of `2>>` that parsed as two would turn somebody's draft into an
     * assertion about material it has nothing to do with. The comma is absent from [SENTENCE] for the
     * same reason it is absent from the brackets: it separates one citation from the next.
     */
    fun number(token: String): Int? = token.trim()
        .removePrefix(OPEN)
        .removeSuffix(CLOSE)
        .trimEnd { it in SENTENCE }
        .trim()
        .toIntOrNull()

    /**
     * What [number] was offered for, or `null` if nothing was.
     *
     * Null rather than an exception for the reason [Citation.parse] is: this reads model output, and
     * a number outside the offer is an ordinary thing for a model to write. What answers it is the
     * grounding gate, which cannot run if this throws first.
     */
    fun span(number: Int): SpanId? = spans.getOrNull(number - FIRST)

    companion object {

        /** Nothing was offered, which is every request that retrieved nothing. */
        val NONE = Offer(emptyList())

        /**
         * [number] as a citation is written down.
         *
         * **The one place the form is spelled out.** The prompt's labels go through [label] and a
         * fault report naming a number the model invented comes here directly, because there is no
         * span behind it to render and nothing else to call it.
         */
        fun written(number: Int): String = "$OPEN$number$CLOSE"

        /** The number the first piece of material is offered under. */
        private const val FIRST = 1

        private const val OPEN = "["
        private const val CLOSE = "]"

        /**
         * What may trail the last citation on a line, because a sentence ends somewhere.
         *
         * Not the comma, which separates citations rather than ending anything.
         */
        private const val SENTENCE = ".;:)!?"
    }
}
