package io.github.ragnorrs.mainbrain.domain.entity

import io.github.ragnorrs.mainbrain.domain.record.IdentifierType

/**
 * One thing two records agree on, and what that agreement was worth.
 *
 * Here rather than with the matcher that computes it, because a signal is what a [MergeLink]
 * records: the weights are judgement and will be revised, so a link written today has to be
 * readable against the judgement that produced it rather than against today's. A merge nobody
 * can explain is a merge nobody dares undo.
 */
data class Signal(val kind: SignalKind, val weight: Double)

/**
 * The comparable signals, strongest first.
 *
 * Two the architecture's weight table names are absent, each for a reason rather than by
 * omission. **Name with organisation** needs an organisation, and no record kind in the corpus
 * carries one — it arrives with a source that has the field. **A profile location** is not here
 * at all: two people living in one city is not evidence that they are one person, so the claim
 * is kept for answering questions rather than for matching.
 */
enum class SignalKind(val claim: IdentifierType?) {
    /**
     * Strongest, and the only one that decays.
     *
     * Accounts on the chat platform are phone-anchored and a number is rarely shared, which is
     * what makes it decisive. Numbers are also reassigned, which is what makes it decay: two
     * records claiming one number a decade apart may be two humans who held it in turn.
     */
    PHONE(IdentifierType.PHONE),

    /** Strong rather than decisive — a household or a role account occasionally shares one. */
    EMAIL(IdentifierType.EMAIL),

    /** Unique per platform, and people reuse theirs across platforms. */
    HANDLE(IdentifierType.HANDLE),

    /** The same image on two platforms is evidence, and the hash is already stored. */
    AVATAR(IdentifierType.CONTENT_HASH),

    /**
     * Weak, and scaled by how many records share the name.
     *
     * Read from the display name rather than from a claim, which is why it carries no claim
     * type. A common name discriminates nobody; a rare one is meaningful.
     */
    NAME(null),
}
