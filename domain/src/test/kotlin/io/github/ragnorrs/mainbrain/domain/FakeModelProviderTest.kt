package io.github.ragnorrs.mainbrain.domain

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The fake is driveable to every outcome, and to the failure.
 *
 * Testing a test double looks circular until the alternative is considered: every caller's
 * truncation and refusal handling is asserted through this class, so a fake that silently
 * handed back a complete answer where a test asked for a truncated one would turn all of
 * those assertions green over the paths they exist to cover. These cases are what make the
 * rest of the suite mean something.
 */
class FakeModelProviderTest {

    private fun request(prompt: String = "anything") =
        ModelRequest(system = "standing instructions", prompt = prompt, maxTokens = 1024)

    @Test
    fun `each outcome can be scripted`(): Unit = runBlocking {
        val provider = FakeModelProvider(
            listOf(
                FakeModelProvider.Reply.of("done"),
                FakeModelProvider.Reply.truncated("half an ans"),
                FakeModelProvider.Reply.refused("cyber"),
                FakeModelProvider.Reply.paused("searching"),
            ),
        )
        val usage = FakeModelProvider.Reply.SAMPLE_USAGE

        assertEquals(ModelOutcome.Answered("done", usage), provider.respond(request()))
        assertEquals(ModelOutcome.Truncated("half an ans", usage), provider.respond(request()))
        assertEquals(ModelOutcome.Refused("cyber", usage), provider.respond(request()))
        assertEquals(ModelOutcome.Paused("searching", usage), provider.respond(request()))
    }

    @Test
    fun `a refusal can decline without naming a category`(): Unit = runBlocking {
        // The provider sometimes declines without saying why, so a caller reading the category
        // has to cope with its absence. A fake that always supplied one would hide that.
        val outcome = FakeModelProvider(listOf(FakeModelProvider.Reply.refused())).respond(request())
        assertNull((outcome as ModelOutcome.Refused).category)
    }

    @Test
    fun `the retryable failure can be scripted`() {
        val provider = FakeModelProvider(listOf(FakeModelProvider.Reply.unavailable("overloaded")))
        // `runBlocking` inside the lambda rather than around the test: `assertThrows` takes a
        // plain function, so a suspend call cannot be made directly in it.
        val failure = assertThrows<ModelUnavailable> { runBlocking { provider.respond(request()) } }
        assertEquals("overloaded", failure.message)
    }

    @Test
    fun `replies are handed back in order and the last one repeats`(): Unit = runBlocking {
        val provider = FakeModelProvider(
            listOf(FakeModelProvider.Reply.of("first"), FakeModelProvider.Reply.of("rest")),
        )

        assertEquals("first", (provider.respond(request()) as ModelOutcome.Answered).text)
        assertEquals("rest", (provider.respond(request()) as ModelOutcome.Answered).text)
        // Past the end of the script rather than an index error, so a test that cares about one
        // answer does not have to count the calls that will ask for it.
        assertEquals("rest", (provider.respond(request()) as ModelOutcome.Answered).text)
    }

    @Test
    fun `every request is recorded in order`(): Unit = runBlocking {
        val provider = FakeModelProvider()

        provider.respond(request("first question"))
        provider.respond(request("second question"))

        assertEquals(2, provider.callCount)
        assertEquals(listOf("first question", "second question"), provider.requests.map { it.prompt })
    }

    @Test
    fun `usage is non-zero by default`(): Unit = runBlocking {
        // A caller that accumulates usage and is tested against zeroes passes whether it
        // accumulates or not, so the default has to be something.
        val outcome = FakeModelProvider().respond(request())
        assertSame(FakeModelProvider.Reply.SAMPLE_USAGE, outcome.usage)
    }
}
