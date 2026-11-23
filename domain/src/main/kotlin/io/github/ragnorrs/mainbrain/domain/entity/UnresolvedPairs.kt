package io.github.ragnorrs.mainbrain.domain.entity

/**
 * The kept pairs, read for somebody placing the merge threshold.
 *
 * **A port of its own rather than another method on [ContactRepository]**, because it is the only
 * read in the entity layer that exists for a person rather than for a pass. The pass needs to keep
 * pairs, resolve clusters and check constraints; it never needs them ordered, floored or named.
 * Separating them is what makes *the advisory surface cannot write* structural rather than stated:
 * whatever is given this port can do nothing else.
 *
 * Setting the threshold means reading pairs and judging them, which is why the architecture puts
 * that accuracy in the evaluation set instead of in a constant — and a number nobody can sample is
 * a constant nobody can set. This is the read that makes sampling possible.
 */
interface UnresolvedPairs {

    /**
     * The kept pairs scoring at least [floor], strongest first, at most [limit] of them.
     *
     * **Ordered and bounded rather than filtered down to what matters**, because what matters is
     * exactly what is in question. [floor] keeps a reader from paging through pairs that agree on
     * next to nothing; it discards nothing and decides nothing, and a pair under it is still kept
     * and still scored. Strongest first because a reader sampling to place a threshold wants the
     * pairs nearest it.
     *
     * **A pair that has been settled is not here.** One whose sides resolve to a single head is
     * already one human, and one a correction has separated is two; neither is an open question.
     *
     * The pass and this read withhold a settled pair at different points, which is worth being
     * exact about rather than summarising. The pass writes no row for a pair it has joined, and
     * none for a separated pair that still clears the threshold — the constraint refuses the join
     * and the keeping branch is never reached. What it does keep is a separated pair scoring under
     * the threshold, because the evidence in the corpus still says what it said and nothing
     * rewrites that. So this read is what withholds those, and what withholds any row written
     * before a pair was settled: nothing goes back to remove one.
     *
     * A [limit] below one reads nothing rather than everything.
     */
    suspend fun unresolved(floor: Double, limit: Int): List<UnresolvedPair>
}
