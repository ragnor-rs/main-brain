package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-request spend bound.
 *
 * Small arithmetic, and worth testing because of what rests on it: the ceiling is the only thing
 * standing between a request that resumes repeatedly and an unbounded bill, and every case here is
 * one somebody could plausibly have got the wrong way round.
 */
class BudgetTest {

    @Test
    fun `a fresh budget has its whole ceiling to spend`() {
        assertEquals(1_000, Budget(ceiling = 1_000).remaining)
    }

    @Test
    fun `only output tokens are charged`() {
        // The ceiling bounds what a single call may produce, so the two have to be in one unit.
        // Charging input as well would make the bound tighten with the size of the prompt, which is
        // the retrieved material rather than anything the model chose to spend.
        val spent = Budget(ceiling = 1_000).spending(
            TokenUsage(inputTokens = 5_000, outputTokens = 100, cacheReadTokens = 9_000),
        )

        assertEquals(900, spent.remaining)
    }

    @Test
    fun `spend accumulates across calls`() {
        val budget = Budget(ceiling = 1_000)
            .spending(TokenUsage(inputTokens = 0, outputTokens = 300))
            .spending(TokenUsage(inputTokens = 0, outputTokens = 200))

        assertEquals(500, budget.remaining)
    }

    @Test
    fun `overshooting floors at zero rather than going negative`() {
        // A resumed turn can overshoot: the adapter's resumption is a fresh call at the same
        // ceiling and nothing can refuse it mid-flight. A negative remainder is not something a
        // caller could pass on as a ceiling.
        val budget = Budget(ceiling = 100).spending(TokenUsage(inputTokens = 0, outputTokens = 900))

        assertEquals(0, budget.remaining)
    }

    @Test
    fun `a budget below the floor is not worth spending`() {
        val budget = Budget(ceiling = Budget.FLOOR).spending(
            TokenUsage(inputTokens = 0, outputTokens = 1),
        )

        assertFalse(budget.worthSpending)
    }

    @Test
    fun `a budget exactly at the floor is still worth spending`() {
        // The boundary rather than near it: a floor that excluded its own value would refuse the
        // smallest call it was written to permit.
        assertTrue(Budget(ceiling = Budget.FLOOR).worthSpending)
    }

    @Test
    fun `a budget that permits nothing is refused outright`() {
        assertFailsWith<IllegalArgumentException> { Budget(ceiling = 0) }
    }

    @Test
    fun `negative spend is not a thing a call can cost`() {
        assertFailsWith<IllegalArgumentException> { Budget(ceiling = 100, spent = -1) }
    }

    @Test
    fun `the default ceiling leaves room for more than one full-sized call`() {
        // The two multipliers are what this exists for: the adapter resumes a paused turn, and the
        // runner re-rolls generation once. A default that fitted one call would make the retry
        // impossible rather than bounded.
        assertTrue(Budget.DEFAULT_CEILING > Sizing.ANSWERING.ceiling)
    }
}
