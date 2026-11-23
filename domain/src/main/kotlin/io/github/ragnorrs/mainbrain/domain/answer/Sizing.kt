package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.Effort

/**
 * How one generation call is sized: how hard it thinks, and how much room it has to do it in.
 *
 * **The two travel together because they trade against each other.** Thinking counts against the
 * ceiling, so raising the effort without raising the room buys a truncation rather than a better
 * answer — and this build settles a truncation as insufficient evidence, which reads to whoever
 * asked as the corpus having nothing to say. Two separate knobs would let a deployment set exactly
 * that pair, so they are one value with named defaults instead.
 *
 * **Effort is the dial a deployment is meant to turn; the ceiling is not.** Effort trades depth for
 * spend and nothing else, and the model is strong well below the top of its range. Lowering a
 * ceiling trades spend for truncations, which is why the configuration this build reads exposes the
 * first and leaves the second to [ANSWERING] and [RESEARCHING].
 */
data class Sizing(
    val effort: Effort,
    val ceiling: Int,
) {
    init {
        require(ceiling > 0) { "A call with no room to answer in cannot answer." }
    }

    companion object {

        /**
         * What the shapes that read the corpus ask for.
         *
         * **Medium rather than high, and that is a cost decision taken deliberately.** These four
         * shapes retrieve a fixed query set and hand the model what to read, so the judgement left
         * is composing an answer out of material already in front of it — which is the kind of work
         * this model does well below the top of its effort range. High was the default this path
         * shipped at, and it ran on every question: the dearest setting anything reached by typing
         * a message used to carry.
         *
         * The ceiling is sized for the reasoning and the prose together, because thinking counts
         * against it and a value sized to the prose alone truncates the answer instead of the
         * thinking.
         */
        val ANSWERING = Sizing(effort = Effort.MEDIUM, ceiling = 64 * 1024)

        /**
         * And what the one shape that reads the open web asks for.
         *
         * Higher on both counts, because this is the only path where the model is searching and
         * reading inside the call rather than being handed what to read — the tool loop runs on the
         * provider's side and every round of it comes out of this ceiling. Keeping the highest
         * effort this build asks for here and nowhere else is what confines it to one path instead
         * of spending it on all five. It is not the top of the range the model offers; `MAX` is,
         * and nothing here asks for it.
         */
        val RESEARCHING = Sizing(effort = Effort.XHIGH, ceiling = 96 * 1024)
    }
}
