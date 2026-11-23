package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.index.SpanId

/**
 * Asserts that no claim rests on a span outside what was retrieved.
 *
 * **One of the three ways a citation fails, not all of them.** A claim may also cite a number the
 * request never offered, or write where a citation belongs something that reads as no number at all;
 * neither resolves to a span, so neither appears anywhere in what this compares. `Claim` holds both
 * cases and the runner asks after them separately. A caller reading an empty result here as *every
 * claim is grounded* would let an answer through whose every assertion cited a number out of the air.
 * `Claim.grounded` is the whole rule; this is the part of it that needs the retrieved set.
 *
 * **It costs no model call, which is what makes it a gate rather than an intention.** A check
 * that needed a second opinion from the model would be skipped under load, and a grounding
 * rule enforced only when there is budget for it is not enforced.
 *
 * Comparison is on the span identifier and nothing else. A [SpanId] is built from the source's
 * own identifier rather than a row number, so it is checkable without a lookup — which is the
 * property this relies on, and the reason the identifier is shaped that way.
 *
 * **It applies to claims, not to prose.** A drafted reply is composed text and carries no
 * citations; an assertion that something was agreed is a claim about the corpus and carries
 * one. Without that distinction the check either blocks every draft or verifies nothing.
 *
 * **Which is also the way round it, and that is closed elsewhere rather than here.** An answer whose
 * findings *are* the answer satisfies a claims-only check by writing them as prose, and nothing this
 * compares can see that — there is nothing cited to compare. The shape declares whether its answer
 * is its assertions and the runner refuses a reply from one that makes none; see
 * [Shape.assertsFindings]. This file still reads claims and only claims.
 */
class CitationCheck {

    /**
     * The cited spans that were not in [retrieved].
     *
     * Empty means no claim cited a span outside [retrieved], which is not the whole of being
     * grounded — see the note above. The offenders are returned rather than a boolean because whoever
     * reports a failure needs to say which citation was invented, and a bare false leaves the retry
     * and the degrade indistinguishable from a bug in the check.
     *
     * A variant with no claims contributes nothing here and passes, which is the composed-prose
     * case rather than an unchecked one — for a shape whose answer is composed prose. For one whose
     * answer is its assertions, a reply carrying none is refused before this is reached, which is
     * the runner's rule and not this comparison's.
     */
    fun ungrounded(variants: List<Variant>, retrieved: Set<SpanId>): Set<SpanId> =
        variants.asSequence()
            .flatMap { it.claims }
            .flatMap { it.cites }
            .filterNot { it in retrieved }
            .toSet()
}
