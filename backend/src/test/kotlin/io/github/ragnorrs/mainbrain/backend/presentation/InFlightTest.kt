package io.github.ragnorrs.mainbrain.backend.presentation

import io.github.ragnorrs.mainbrain.domain.answer.Answer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What is held between asking for an answer and collecting it.
 *
 * The route tests cover handing work over and getting it back; what they cannot reach is the clock.
 * Letting go of work nobody came back for is the one behaviour here that is a function of time, and
 * a test that waited for it would wait half an hour.
 */
class InFlightTest {

    @Test
    fun `work is filed under a name that comes back`(): Unit = runBlocking {
        val inFlight = InFlight()
        val work = async { Answer.InsufficientEvidence() }

        val id = inFlight.accept(work)

        assertTrue(id.isNotBlank())
        assertEquals(work, inFlight.collect(id))
    }

    @Test
    fun `each piece of work gets its own name`(): Unit = runBlocking {
        val inFlight = InFlight()

        val one = inFlight.accept(async { Answer.InsufficientEvidence() })
        val other = inFlight.accept(async { Answer.InsufficientEvidence() })

        assertNotEquals(one, other, "Two pieces of work filed under one name would lose an answer.")
        assertEquals(2, inFlight.size())
    }

    @Test
    fun `nothing is filed under a name this store never minted`(): Unit = runBlocking {
        assertNull(InFlight().collect("not-a-name-this-store-minted"))
    }

    /**
     * Unfinished work stays, because the caller is going to ask again.
     *
     * The case that would break if collecting were made to let go unconditionally — every attempt
     * before the last one would discard the work it was waiting for.
     */
    @Test
    fun `work still running is kept for the next attempt`(): Unit = runBlocking {
        val inFlight = InFlight()
        val never = CompletableDeferred<Unit>()
        val running = async {
            never.await()
            Answer.InsufficientEvidence()
        }
        val id = inFlight.accept(running)

        assertNotNull(inFlight.collect(id))
        assertNotNull(inFlight.collect(id))
        assertEquals(1, inFlight.size())

        running.cancel()
    }

    /**
     * Finished work stays, so a response lost in flight can be asked for again.
     *
     * The case that fails if collecting is made to let go of finished work: the slot is released
     * when the response reaches the server, not when it reaches the reader, so a retry would find
     * nothing and a finished answer would be destroyed by the allowance meant to protect it.
     */
    @Test
    fun `work that finished is kept so it can be collected again`(): Unit = runBlocking {
        val inFlight = InFlight()
        val work = async { Answer.InsufficientEvidence() }
        val id = inFlight.accept(work)
        work.await()

        assertEquals(work, inFlight.collect(id))
        assertEquals(work, inFlight.collect(id), "A lost response must be recoverable by asking again.")
        assertEquals(1, inFlight.size())
    }

    /**
     * Work nobody came back for is let go, and what was still running is cancelled with it.
     *
     * An interface that restarted mid-wait never collects what it asked for, so the slot would be
     * held for the life of the process. Cancelling rather than merely dropping is the half worth
     * asserting: past the keeping period there is no one left to hand the answer to, and a model
     * call still running for a reader who has gone is spend with nobody at the end of it.
     *
     * Swept on arrival, so a later request is what triggers it — which is also the only moment it
     * can matter. The piece of work that triggered the sweep must survive it, which is the half a
     * cutoff written the wrong way round would get wrong.
     */
    @Test
    fun `work nobody came back for is discarded and cancelled`(): Unit = runBlocking {
        var now = 0L
        val inFlight = InFlight(now = { now })
        val never = CompletableDeferred<Unit>()
        val abandoned = async {
            never.await()
            Answer.InsufficientEvidence()
        }
        val id = inFlight.accept(abandoned)

        now += STALE_MILLIS
        val stillWanted = async {
            never.await()
            Answer.InsufficientEvidence()
        }
        val fresh = inFlight.accept(stillWanted)

        assertNull(inFlight.collect(id), "Work nobody collected should not still be held.")
        assertTrue(abandoned.isCancelled, "Discarded work left running would spend into nothing.")
        assertNotNull(inFlight.collect(fresh), "The sweep must not take the request that triggered it.")

        stillWanted.cancel()
    }

    private companion object {

        /** Past anything the store keeps, so the exact period is not restated here. */
        const val STALE_MILLIS = 24 * 60 * 60 * 1000L
    }
}
