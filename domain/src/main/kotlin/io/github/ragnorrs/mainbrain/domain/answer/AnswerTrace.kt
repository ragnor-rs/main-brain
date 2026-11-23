package io.github.ragnorrs.mainbrain.domain.answer

/**
 * Where what an attempt retrieved and cited is reported, for whoever is scoring it.
 *
 * **It exists because retrieval is decided where nothing outside can see it.** The retrieved set
 * lives in an [Exchange] that never leaves the runner, and the answer that comes back out carries
 * no account of it. So an ungrounded citation and an empty corpus arrive at the interface as the
 * same reply — which is deliberate, because neither is something the system can stand behind — and
 * that leaves nothing able to tell a retrieval regression from a corpus that happens to hold less.
 *
 * **Separate from [Faults] because that one only hears about an attempt something was wrong with.**
 * The two overlap on the faulty case and are made from the one verdict, so they cannot disagree
 * about it. What they do not share is the clean case: scoring a run means knowing what a *good*
 * attempt retrieved and cited, and a seam that fires only on a fault reports nothing at all for
 * the answers that held — which is the run that most needs measuring. Hence every attempt, fault
 * or none.
 *
 * **A port rather than a logger, because `:domain` carries no dependency of its own.** This module
 * imports no library at all, so the one thing it cannot do is write a line anywhere. What it can do
 * is say what happened and let the process it runs in decide where that goes.
 *
 * **Citations, never the answer.** What is reported names material rather than reproducing it: span
 * identifiers, derived from the sources' own, bar the two cases where a citation named no span —
 * a number nothing was offered under, and a citation that read as no number at all, each reported as
 * the model wrote it because there is nothing else to call it. The second is text, so the runner cuts
 * each entry to a length and deduplicates them where it builds the fault this reads from: no one
 * citation can become a paragraph here. How many distinct ones an answer can carry is not bounded —
 * `Runner.CITATION_EXCERPT` records that. The first needs no bound, being an integer.
 *
 * The composed answer does not pass through here: a trace carrying prose would put drafted replies
 * wherever an implementation sends them, and nothing downstream needs them to score either half.
 * A citation the model wrote badly is not that — it is the evidence the claim was unverifiable, and
 * it is the only record of what the model put in a citation's place.
 */
interface AnswerTrace {

    /** Report [attempted]. */
    fun attempted(attempted: Attempted)

    companion object {

        /**
         * Reports nothing.
         *
         * The default, so that constructing a runner does not require deciding where a trace
         * goes — most callers are tests, which assert against the answer rather than the report.
         * A process that means to be scored wires an implementation, and **an evaluation run that
         * finds no report must fail rather than pass**: silence here is indistinguishable from a
         * run that retrieved nothing, which is the one confusion the trace exists to remove.
         */
        val UNTRACED: AnswerTrace = object : AnswerTrace {
            override fun attempted(attempted: Attempted) = Unit
        }
    }
}

/**
 * One pass over a shape's stages, in the terms the two halves are scored in.
 *
 * Retrieval and answer text are scored separately, and this is the half that can be scored at all:
 * whether the right material came back is a set comparison, while whether the prose is good is not.
 * One number covering both would hide a retrieval regression behind a fluent answer.
 *
 * @property shape which pipeline answered, so that routing is checkable too — a request that bound
 *   to the wrong shape can retrieve well and answer the wrong question.
 * @property ordinal `1` for the first pass and `2` for the retry. A second report means the first
 *   answer did not hold, and [ungrounded] on the first says whether a citation was why. Empty there
 *   with a retry says only that it was not: a variant count the shape did not promise, an answer
 *   that asserted nothing and a complete reply nothing could read all produce that line, and the
 *   fault report is what tells them apart.
 * @property retrieved every span the attempt retrieved, by its identifier — which is not how the
 *   prompt labelled it, since within a request the material is offered under short numbers that
 *   mean nothing once it is over. Empty is not a short-circuit: the shapes that answer from the
 *   open web or from the request's own text declare they do not read the corpus, and run the model
 *   on an empty set as a matter of course. Nor does
 *   a short-circuit imply empty — a request that has spent its budget stops with whatever it had
 *   retrieved. What empty means is only what it says, which is why a case that cannot pin a
 *   retrieved set opts out of scoring one rather than asserting it is bare.
 * @property cited what the answer's claims cite, **before any ungrounded claim is taken out of
 *   it**. What a reader is finally shown can therefore be a subset — which is the point: this is
 *   what the model asserted, [ungrounded] says what it should not have been, and [survived] says what
 *   was left. Empty for an answer that makes no claims: composed prose where the shape composes,
 *   and an attempt the runner refused where the shape's answer is its assertions — never an
 *   unchecked assertion that reached a reader.
 * @property ungrounded what the claims rested on that nothing retrieved. Span identifiers, drawn
 *   from [cited] — except for a citation that resolved to no span at all, which appears here as the
 *   number or the text the model wrote and so is **not** a member of [cited]. The two kinds are one
 *   list here while the fault report keeps them apart, because what is scored off this is whether any
 *   citation failed, and a line reporting none while [survived] had shrunk would contradict itself.
 *   Empty means nothing was cited that
 *   should not have been — which is not the same as the attempt having held, since a reply is
 *   rejected before its citations are looked at when its variant count is wrong, when a shape whose
 *   answer is its assertions made none, and when a complete reply nothing could read left no answer
 *   to check at all.
 * @property survived what the claims that were kept rest on, which is what should reach an
 *   interface. **Reported rather than left to be derived, because it cannot be derived from the
 *   other three.** A claim is dropped whole rather than stripped of the citation that failed, so a
 *   claim resting on one span that was retrieved and one that was not takes the good identifier with
 *   it — and [cited] is flat, so nothing downstream can tell which of its entries shared a claim.
 *   Subtracting [ungrounded] from [cited] therefore keeps that good identifier, and whoever compares
 *   the result against the wire reads a repair the runner made deliberately as the presentation
 *   mapping having lost a citation. Empty where the fault was fatal and the answer was discarded
 *   whole; equal to [cited] where nothing was wrong with it.
 */
data class Attempted(
    val shape: ShapeId,
    val ordinal: Int,
    val retrieved: List<String>,
    val cited: List<String>,
    val ungrounded: List<String>,
    val survived: List<String>,
) {
    init {
        require(ordinal >= 1) { "An attempt is counted from the first one, which is 1." }
    }
}
