package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.Embedding
import io.github.ragnorrs.mainbrain.domain.EmbeddingKind
import io.github.ragnorrs.mainbrain.domain.EmbeddingProvider
import io.github.ragnorrs.mainbrain.domain.EmbeddingsUnavailable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * The embedding model, reached over HTTP in its own process.
 *
 * The split is forced rather than chosen: the model provider has no embeddings endpoint, so
 * something has to run a model, and nothing else in the stack is a Python process. What the
 * port buys is that this is the only file that knows so — a hosted model later is a second
 * implementation beside this one.
 *
 * Construct it with [reaching]. The constructor takes an engine so a test can answer from a
 * script instead of a socket.
 */
class SidecarEmbeddingProvider(
    private val baseUrl: String,
    engine: HttpClientEngine,
) : EmbeddingProvider {

    private val client = HttpClient(engine) {
        // Ktor's default, set explicitly because the code below depends on it: a non-2xx has
        // to arrive as a status to inspect rather than as a thrown exception, so that every
        // way the sidecar can fail to answer becomes one `SidecarUnavailable` the pass knows
        // to treat as "come back later". Nothing here retries, and this setting would not
        // govern it if anything did.
        expectSuccess = false
        install(ContentNegotiation) {
            json(
                Json {
                    // The sidecar is versioned with this code but deployed separately, so a
                    // field added there must not break a reader that has not been rebuilt.
                    ignoreUnknownKeys = true
                },
            )
        }
        install(HttpTimeout) {
            // Sized for the slowest thing this client asks for: a batch of long spans
            // embedded on a processor with no accelerator behind it. Nothing here waits for a
            // model load — the sidecar loads its weights when it starts and refuses until it
            // can answer — so this is batch work rather than a cold start. Not unbounded: a
            // sidecar that never answers has to surface as a failure rather than as a pass
            // that hangs.
            //
            // One client serves both calls, so the readiness read in `version()` carries the
            // same ceiling as a batch, which is far more than it needs. That costs nothing
            // while no pass is scheduled; whichever issue schedules one should give it the
            // backoff the ingest scheduler already has, rather than leaving a wedged sidecar
            // to hold a batch for the whole of this.
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            // **The one above does not govern on its own.** A request timeout bounds the whole
            // call, but the gap between two bytes arriving is the engine's own read timeout, and
            // an engine left unconfigured brings a default of its own — ten seconds, for the one
            // this is built with. A batch takes longer than that by design, so the ceiling above
            // would never be reached: every batch would be abandoned mid-computation while the
            // sidecar went on computing it, which turns a slow pass into one that cannot succeed
            // at any batch size and leaves the sidecar further behind on every sweep. Set to the
            // same value, because what both bound here is the same wait for the same reply.
            socketTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        }
    }

    /**
     * One call against the sidecar, with a call that never landed reported as the port's failure.
     *
     * `expectSuccess = false` above makes a non-2xx a status to inspect rather than a throw, which
     * is what both callers read — but it governs *replies*, and a request that never reached the
     * sidecar has no reply to carry a status. So without this the commonest failure of all, the
     * sidecar not being there, leaves as a transport exception while every failure it *answers*
     * leaves as [SidecarUnavailable] — and a caller that handled this port's unavailability would
     * handle the cold sidecar and not the absent one.
     *
     * [IOException] rather than anything broader: it is what a name that does not resolve, a
     * refused connection and every one of the three timeouts arrive as, and it is narrow enough
     * not to swallow a cancellation or a malformed body, neither of which is the sidecar
     * being unavailable.
     */
    private suspend fun <T> landing(call: String, request: suspend () -> T): T = try {
        request()
    } catch (failure: IOException) {
        throw SidecarUnavailable("$call could not be reached (${failure.message}).", failure)
    }

    override suspend fun version(): String {
        val response = landing("health") { client.get("$baseUrl/health") }
        if (response.status != HttpStatusCode.OK) {
            throw SidecarUnavailable("health returned ${response.status}")
        }
        val health = response.body<HealthResponse>()
        if (!health.ready) {
            // Up but unable to answer, which is three situations with one remedy: no model is
            // configured, the one that is has not finished loading, or its load failed.
            // Either way there is nothing to derive with, and indexing against a provider
            // that cannot answer would mark records derived that were never embedded. Which
            // of the three it is the sidecar's own log says; a pass can only come back later.
            throw SidecarUnavailable(
                "the sidecar is up but has no model it can answer with, reporting " +
                    "${health.model ?: "none configured"}.",
            )
        }
        return health.version
    }

    override suspend fun embed(kind: EmbeddingKind, texts: List<String>): List<Embedding> {
        if (texts.isEmpty()) return emptyList()
        val response = landing("embed") {
            client.post("$baseUrl/embed") {
                contentType(ContentType.Application.Json)
                setBody(EmbedRequest(texts = texts, kind = kind.name.lowercase()))
            }
        }
        if (response.status != HttpStatusCode.OK) {
            throw SidecarUnavailable("embed returned ${response.status}")
        }
        val body = response.body<EmbedResponse>()
        if (body.embeddings.size != texts.size) {
            // Positional: the caller pairs the n-th vector with the n-th text, so a short
            // reply would silently attach one span's vectors to another span.
            throw SidecarUnavailable(
                "asked for ${texts.size} embeddings and got ${body.embeddings.size}. The " +
                    "results are read positionally, so a short reply would file one span's " +
                    "vectors under another.",
            )
        }
        return body.embeddings.map { vectors ->
            Embedding(
                dense = vectors.dense,
                // The sidecar keys term weights by vocabulary position, as JSON object keys
                // and therefore as strings. They are positions, so they parse.
                sparse = vectors.sparse.entries.associate { (term, weight) -> term.toInt() to weight },
            )
        }
    }

    @Serializable
    private data class EmbedRequest(val texts: List<String>, val kind: String)

    @Serializable
    private data class EmbedResponse(val embeddings: List<Vectors>, val version: String)

    @Serializable
    private data class Vectors(val dense: List<Float>, val sparse: Map<String, Float>)

    @Serializable
    private data class HealthResponse(
        val ready: Boolean,
        val version: String,
        @SerialName("model") val model: String? = null,
    )

    companion object {
        private const val REQUEST_TIMEOUT_MILLIS = 600_000L
        private const val CONNECT_TIMEOUT_MILLIS = 10_000L

        /** The provider as the running stack builds it, over the real network. */
        fun reaching(baseUrl: String): SidecarEmbeddingProvider =
            SidecarEmbeddingProvider(baseUrl, OkHttp.create())
    }
}

/**
 * Thrown when the sidecar cannot answer, including when it answers that it holds no model.
 *
 * A distinct type because the indexing pass has to treat it as "come back later" rather than
 * as a record that cannot be embedded: marking a record derived against a provider that never
 * embedded it is the one failure a re-run does not heal.
 *
 * **An [EmbeddingsUnavailable] because that is the port's word for it**, which is what lets a
 * caller answer *come back later* without naming this adapter. What this type adds is which
 * implementation was not answering, and that is for a log rather than for a branch.
 */
class SidecarUnavailable(detail: String, cause: Throwable? = null) :
    EmbeddingsUnavailable("The embeddings sidecar: $detail", cause)
