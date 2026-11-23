package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.Embedding
import io.github.ragnorrs.mainbrain.domain.EmbeddingKind
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * The sidecar client, answered from a script rather than from a socket.
 *
 * What is worth asserting here is the wire contract and the failure handling, both of which
 * are this file's own work: that the request carries what the sidecar reads, that term
 * positions survive the trip through JSON object keys, and that every way the sidecar can
 * fail to answer becomes a refusal rather than a plausible-looking empty result. Whether the
 * vectors are any good is not a question a client test can ask.
 */
class SidecarEmbeddingProviderTest {

    @Test
    fun `the version comes from the sidecar rather than from this side`(): Unit = runBlocking {
        val provider = providerAnswering("""{"ready":true,"model":"a-model","version":"a-model+sparse512"}""")

        assertEquals("a-model+sparse512", provider.version())
    }

    @Test
    fun `a sidecar holding no model is unavailable rather than versionless`(): Unit = runBlocking {
        // Up but not ready is what the sidecar reports before a model is configured. Treating
        // it as an ordinary answer would let the pass mark records derived against a provider
        // that embedded nothing, and a re-run would not revisit them.
        val provider = providerAnswering("""{"ready":false,"model":null,"version":"+sparse512"}""")

        assertFailsWith<SidecarUnavailable> { provider.version() }
        Unit
    }

    @Test
    fun `a sidecar still loading its weights is unavailable rather than ready`(): Unit = runBlocking {
        // The other way to be up and unable to answer, and the common one: the sidecar names
        // the model it is loading from the moment it starts, and loading it takes minutes on
        // a machine that has never run it. A pass reading the name rather than the readiness
        // would index against a provider that cannot answer yet.
        val provider =
            providerAnswering("""{"ready":false,"model":"a-model","version":"a-model+sparse512"}""")

        assertFailsWith<SidecarUnavailable> { provider.version() }
        Unit
    }

    @Test
    fun `the request carries the texts and the kind the sidecar reads`(): Unit = runBlocking {
        var seen: String? = null
        val engine = MockEngine { request ->
            seen = request.body.toByteArray().decodeToString()
            jsonResponse("""{"version":"v","embeddings":[{"dense":[0.5],"sparse":{}}]}""")
        }
        val provider = SidecarEmbeddingProvider("http://sidecar:8000", engine)

        provider.embed(EmbeddingKind.QUERY, listOf("a question"))

        val body = requireNotNull(seen)
        assertTrue("\"a question\"" in body) { "The text never reached the request: $body" }
        assertTrue("\"query\"" in body) {
            "The kind is sent lowercased because that is what the sidecar reads; got: $body"
        }
    }

    @Test
    fun `term positions survive the trip through JSON object keys`(): Unit = runBlocking {
        // They are integers on both sides and strings only in between, because a JSON object
        // has no other kind of key. A position read back as anything else would land the
        // weight on the wrong term.
        val provider = providerAnswering(
            """{"version":"v","embeddings":[{"dense":[0.1,0.2],"sparse":{"0":0.9,"250001":0.4}}]}""",
        )

        val embedding = provider.embed(EmbeddingKind.PASSAGE, listOf("a text")).single()

        assertEquals(listOf(0.1f, 0.2f), embedding.dense)
        assertEquals(mapOf(0 to 0.9f, 250001 to 0.4f), embedding.sparse)
    }

    @Test
    fun `a reply with fewer vectors than texts is refused rather than paired up`(): Unit = runBlocking {
        // The pairing is positional, so a short reply does not lose a span — it files one
        // span's vectors under another, which reads as ordinary data for ever after.
        val provider = providerAnswering("""{"version":"v","embeddings":[{"dense":[0.1],"sparse":{}}]}""")

        assertFailsWith<SidecarUnavailable> {
            provider.embed(EmbeddingKind.PASSAGE, listOf("first", "second"))
        }
        Unit
    }

    @Test
    fun `a sidecar that refuses is unavailable rather than empty`(): Unit = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val provider = SidecarEmbeddingProvider("http://sidecar:8000", engine)

        assertFailsWith<SidecarUnavailable> { provider.embed(EmbeddingKind.PASSAGE, listOf("a text")) }
        Unit
    }

    @Test
    fun `embedding nothing asks the sidecar nothing`(): Unit = runBlocking {
        val engine = MockEngine { jsonResponse("""{"version":"v","embeddings":[]}""") }
        val provider = SidecarEmbeddingProvider("http://sidecar:8000", engine)

        assertEquals(emptyList<Embedding>(), provider.embed(EmbeddingKind.PASSAGE, emptyList()))
        assertTrue(engine.requestHistory.isEmpty()) {
            "An empty batch still called out. A pass that filters its batch down to nothing " +
                "should cost no round trip."
        }
    }

    @Test
    fun `the version is read from health and the vectors from embed`(): Unit = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/health" && request.method == HttpMethod.Get ->
                    jsonResponse("""{"ready":true,"model":"m","version":"m+sparse512"}""")
                request.url.encodedPath == "/embed" && request.method == HttpMethod.Post ->
                    jsonResponse("""{"version":"m+sparse512","embeddings":[{"dense":[1.0],"sparse":{}}]}""")
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val provider = SidecarEmbeddingProvider("http://sidecar:8000", engine)

        assertEquals("m+sparse512", provider.version())
        assertEquals(1, provider.embed(EmbeddingKind.PASSAGE, listOf("a text")).size)
    }

    /**
     * A sidecar that is not there at all, which is the failure `expectSuccess = false` cannot see.
     *
     * That setting turns a non-2xx into a status to inspect, and every assertion above reads one
     * — but a request that never landed carries no status, so without the wrapping in the adapter
     * this leaves as a transport exception instead of this port's *come back later*. Asserted for
     * both calls, because they are wrapped separately and one of them being missed is the shape
     * this would take.
     *
     * [java.net.UnknownHostException] specifically: a stopped container is a name that no longer
     * resolves, which is what a deploy that brings part of the stack up actually produces.
     */
    @Test
    fun `a sidecar that cannot be reached is unavailable rather than a transport failure`(): Unit = runBlocking {
        val absent = SidecarEmbeddingProvider(
            "http://sidecar:8000",
            MockEngine { throw java.net.UnknownHostException("sidecar: Name or service not known") },
        )

        assertFailsWith<SidecarUnavailable> { absent.version() }
        assertFailsWith<SidecarUnavailable> { absent.embed(EmbeddingKind.QUERY, listOf("a question")) }
    }

    /**
     * The engine is told how long a reply may take, rather than left on a default of its own.
     *
     * **This one is here because the live stack produced it.** A request timeout bounds the whole
     * call; the gap between two bytes is the engine's read timeout, which is a separate setting
     * with a separate default — ten seconds for the engine this is built with. A batch of records
     * takes longer than that on a processor with no accelerator, so every batch was abandoned
     * part-computed while the sidecar went on computing it: the indexing pass failed every sweep,
     * at any batch size, and each attempt left the sidecar further behind. Nothing in the suite
     * could see it, because a mock engine answers instantly.
     *
     * Asserted by reading what the client hands the engine rather than by delaying a reply past
     * the default, which would be a test that takes ten seconds to say this.
     */
    @Test
    fun `the engine is given a read ceiling, not left on its own default`(): Unit = runBlocking {
        var given: HttpTimeoutConfig? = null
        val engine = MockEngine { request ->
            given = request.getCapabilityOrNull(HttpTimeoutCapability)
            jsonResponse("""{"version":"v","embeddings":[{"dense":[0.1],"sparse":{}}]}""")
        }

        SidecarEmbeddingProvider("http://sidecar:8000", engine)
            .embed(EmbeddingKind.PASSAGE, listOf("a text"))

        val timeouts = requireNotNull(given) { "The client asked for no timeouts at all." }
        assertEquals(timeouts.requestTimeoutMillis, timeouts.socketTimeoutMillis) {
            "A socket ceiling of ${timeouts.socketTimeoutMillis} against a request ceiling of " +
                "${timeouts.requestTimeoutMillis}. Left unset, the engine's own default governs " +
                "and no batch long enough to matter can finish."
        }
    }

    private fun providerAnswering(body: String) =
        SidecarEmbeddingProvider("http://sidecar:8000", MockEngine { jsonResponse(body) })
}

private fun MockRequestScope.jsonResponse(body: String) = respond(
    content = ByteReadChannel(body),
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private typealias MockRequestScope = io.ktor.client.engine.mock.MockRequestHandleScope
