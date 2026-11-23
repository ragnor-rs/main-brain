package io.github.ragnorrs.mainbrain.domain.entity

/**
 * What one write to the derived layer actually did to the rows, as opposed to what it was offered.
 *
 * **It exists because a caller cannot count its own writes.** Every derived write here is an upsert
 * keyed by content, so a reading offered twice — by two windows, by a re-derivation, by a model
 * wording one fact the way it worded it before — is one row however many times it is handed over.
 * A pass totalling what it passed in therefore reports its attempts, and attempts and rows diverge
 * by exactly the amount that is worth knowing: a figure of fifty-four against a store holding
 * forty-two says nothing about which of the two reasons took the twelve.
 *
 * **Three figures and not one, because the ways a row fails to appear are not interchangeable.**
 * [overwritten] is a reading replacing one already held, which is the ordinary result of reading a
 * window again and says nothing is wrong. [superseded] is a row the write removed because what it
 * wrote now covers it, which is the derived layer getting smaller and more correct. Summed into a
 * single "not written" they would be indistinguishable, and that is the state this type was added to
 * end.
 *
 * @property written rows this write inserted — the ones it caused.
 * @property overwritten rows it found already there and replaced.
 * @property superseded rows it removed, because something it wrote now covers them. Zero for
 *   findings, which supersede through [CollapseRepository] and not through their own upsert, and
 *   non-zero only where a write recomputes which windows hold — see [StatementRepository.record].
 */
data class Stored(val written: Int, val overwritten: Int, val superseded: Int = 0) {

    init {
        require(written >= 0 && overwritten >= 0 && superseded >= 0) {
            "A write caused $written row(s), replaced $overwritten and removed $superseded, and " +
                "none of the three can be negative."
        }
    }

    /**
     * These two writes together, which is what a caller writing to several tables reports.
     *
     * Summed rather than kept per table because the figures mean the same thing in each, and a
     * reader of a sweep's line wants to know how many rows it caused and not which table they
     * landed in — the kinds are already told apart by the claims themselves.
     */
    operator fun plus(other: Stored) = Stored(
        written = written + other.written,
        overwritten = overwritten + other.overwritten,
        superseded = superseded + other.superseded,
    )

    companion object {
        /** Nothing was offered, so nothing happened. */
        val NOTHING = Stored(written = 0, overwritten = 0)
    }
}
