package io.github.ragnorrs.mainbrain.backend

import io.github.ragnorrs.mainbrain.backend.config.BackendConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the admission pass may spend in a day, against what the deployment configured.
 *
 * **Asserted because nothing else can see it.** The figure is read inside a builder reachable only
 * through the container's own entry point, which opens a store — so a divisor changed, or the floor
 * dropped, would ship green with nothing but a comment to disagree with it. A decision taken in
 * prose and asserted nowhere is the shape this repository has had to correct before.
 *
 * What is held is the decision and not the number: that the pass takes a fraction of the pair, that
 * the fraction is strictly less than the whole of it, and that a configured pair small enough to
 * divide to nothing still yields an allowance the pass can be refused against rather than one the
 * process refuses to start on.
 */
class AdmissionAllowanceTest {

    @Test
    fun `the pass takes a fraction of the configured pair, on both axes`() {
        val allowance = BackendContainer.admissionAllowance(passes())

        assertEquals(130_000, allowance.inputTokens)
        assertEquals(36_000, allowance.outputTokens)
    }

    @Test
    fun `it is strictly less than what the deployment configured`() {
        // **The property, rather than the divisor.** The configured pair is recognition's, and the
        // one thing that must hold of this figure is that a second spending pass adds a fraction
        // to the day rather than another whole pair of it — which is what the comment beside it
        // and the architecture both claim.
        val configured = passes()

        val allowance = BackendContainer.admissionAllowance(configured)

        assertTrue(
            allowance.inputTokens < configured.inputTokensPerDay,
            "admission may spend as much input in a day as the deployment configured for the pass " +
                "whose lag a person notices",
        )
        assertTrue(
            allowance.outputTokens < configured.outputTokensPerDay,
            "admission may spend as much output in a day as the deployment configured",
        )
    }

    @Test
    fun `a pair too small to divide still yields an allowance rather than a refusal`() {
        // `DayAllowance` refuses a non-positive axis, and it is constructed at startup — so without
        // the floor a deliberately tiny configured pair would stop the process rather than stop
        // this pass at its first batch, which is the wrong place for that to be discovered.
        val allowance = BackendContainer.admissionAllowance(passes(input = 1, output = 1))

        assertEquals(1, allowance.inputTokens)
        assertEquals(1, allowance.outputTokens)
    }

    @Test
    fun `it scales with the configured pair rather than being a figure of its own`() {
        // Doubling what the deployment allows doubles what this pass may take. A constant here
        // would leave a deployment raising the day's budget and this pass pinned, which is the
        // behaviour a fraction is chosen over.
        val once = BackendContainer.admissionAllowance(passes())
        val twice = BackendContainer.admissionAllowance(passes(input = 2_600_000, output = 720_000))

        assertEquals(once.inputTokens * 2, twice.inputTokens)
        assertEquals(once.outputTokens * 2, twice.outputTokens)
    }

    /**
     * A pass configuration carrying the two figures this reads and defaults for the rest.
     *
     * Read through [BackendConfig.fromEnv] rather than constructed, so the defaults are the
     * deployment's own and a case asserting the division cannot be written against a pair nobody
     * ships.
     */
    private fun passes(input: Long? = null, output: Long? = null) =
        BackendConfig.fromEnv(
            mapOf(
                "DATABASE_URL" to "jdbc:postgresql://postgres:5432/mainbrain",
                "DATABASE_USER" to "mainbrain",
                "DATABASE_PASSWORD" to "a password",
                "BACKEND_SECRET" to "a shared secret",
            ).let { required ->
                required +
                    listOfNotNull(
                        input?.let { "RECOGNITION_INPUT_TOKENS_PER_DAY" to it.toString() },
                        output?.let { "RECOGNITION_OUTPUT_TOKENS_PER_DAY" to it.toString() },
                    )
            }::get,
        ).passes
}
