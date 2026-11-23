package io.github.ragnorrs.mainbrain.backend

import io.github.ragnorrs.mainbrain.backend.config.AnswerConfig
import io.github.ragnorrs.mainbrain.backend.config.BackendConfig
import io.github.ragnorrs.mainbrain.backend.config.PassConfig
import io.github.ragnorrs.mainbrain.data.ClaudeProvider
import io.github.ragnorrs.mainbrain.data.Database
import io.github.ragnorrs.mainbrain.data.JdbcAdmissionRepository
import io.github.ragnorrs.mainbrain.data.JdbcClientRepository
import io.github.ragnorrs.mainbrain.data.JdbcClosureRepository
import io.github.ragnorrs.mainbrain.data.JdbcCollapseRepository
import io.github.ragnorrs.mainbrain.data.JdbcCommitmentRepository
import io.github.ragnorrs.mainbrain.data.JdbcContactRepository
import io.github.ragnorrs.mainbrain.data.JdbcConversationRepository
import io.github.ragnorrs.mainbrain.data.JdbcDerivationStateRepository
import io.github.ragnorrs.mainbrain.data.JdbcEventRepository
import io.github.ragnorrs.mainbrain.data.JdbcExclusion
import io.github.ragnorrs.mainbrain.data.JdbcGrouping
import io.github.ragnorrs.mainbrain.data.JdbcIndexRepository
import io.github.ragnorrs.mainbrain.data.JdbcIngestStateRepository
import io.github.ragnorrs.mainbrain.data.JdbcMessageRepository
import io.github.ragnorrs.mainbrain.data.JdbcPersonRepository
import io.github.ragnorrs.mainbrain.data.JdbcRetrieval
import io.github.ragnorrs.mainbrain.data.JdbcSpendLedger
import io.github.ragnorrs.mainbrain.data.JdbcStatementRepository
import io.github.ragnorrs.mainbrain.data.JdbcSubjectSearch
import io.github.ragnorrs.mainbrain.data.JdbcSubmissionRepository
import io.github.ragnorrs.mainbrain.data.JdbcUnresolvedPairs
import io.github.ragnorrs.mainbrain.data.SidecarEmbeddingProvider
import io.github.ragnorrs.mainbrain.domain.DerivationStateRepository
import io.github.ragnorrs.mainbrain.domain.Deriver
import io.github.ragnorrs.mainbrain.domain.EmbeddingProvider
import io.github.ragnorrs.mainbrain.domain.IngestStateRepository
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.SubmissionRepository
import io.github.ragnorrs.mainbrain.domain.answer.CitationCheck
import io.github.ragnorrs.mainbrain.domain.answer.Fault
import io.github.ragnorrs.mainbrain.domain.answer.Lookup
import io.github.ragnorrs.mainbrain.domain.answer.ModelClassifier
import io.github.ragnorrs.mainbrain.domain.answer.Runner
import io.github.ragnorrs.mainbrain.domain.answer.ScopeResolver
import io.github.ragnorrs.mainbrain.domain.answer.ShapeId
import io.github.ragnorrs.mainbrain.domain.answer.shape.shapes
import io.github.ragnorrs.mainbrain.domain.entity.ContactRepository
import io.github.ragnorrs.mainbrain.domain.entity.UnresolvedPair
import io.github.ragnorrs.mainbrain.domain.entity.UnresolvedPairs
import io.github.ragnorrs.mainbrain.domain.index.Indexer
import io.github.ragnorrs.mainbrain.domain.recognition.Admitter
import io.github.ragnorrs.mainbrain.domain.recognition.Bounds
import io.github.ragnorrs.mainbrain.domain.recognition.Closer
import io.github.ragnorrs.mainbrain.domain.recognition.ClosureBounds
import io.github.ragnorrs.mainbrain.domain.recognition.Corpus
import io.github.ragnorrs.mainbrain.domain.recognition.Derived
import io.github.ragnorrs.mainbrain.domain.recognition.Extractor
import io.github.ragnorrs.mainbrain.domain.recognition.Inferrer
import io.github.ragnorrs.mainbrain.domain.recognition.Reconciler
import io.github.ragnorrs.mainbrain.domain.recognition.Weights
import io.github.ragnorrs.mainbrain.domain.record.PersonRepository
import io.github.ragnorrs.mainbrain.domain.record.Source
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.toJavaDuration

/**
 * What this process is made of, wired in one place by hand.
 *
 * The only file here that knows every layer. One of them per process, because a second would be a
 * second answer to what this process is, and the two would disagree — which is a rule the build
 * asserts rather than a convention.
 *
 * **It is not the HTTP surface's container, and the distinction is deliberate.** Serving the bot
 * is one of the things this process does and not the only one: the derived passes run here as
 * scheduled work, each under an advisory lock. So what this holds is the process's collaborators,
 * and the surface is one reader of them.
 *
 * **Whether this process can answer, and what it can derive, both follow from what it was able to
 * assemble.** The two values that decide it are the same pair — a model credential and the
 * sidecar's address — and a deployment may hold neither, which is a configuration this build
 * supports rather than a gap: a credential-free stack is how the store and ingestion are exercised
 * in every worktree. So [runner] is nullable, and the null is a configured state rather than an
 * unfinished one, which the surface answers as *cannot answer right now*.
 *
 * **What they cost differs between the two, because the answer path needs both and a pass needs
 * one.** The path is assembled from the credential and the sidecar together or not at all.
 * Indexing needs only the sidecar, inference only the credential, and identity needs neither — so
 * the credential-free stack that cannot answer a question still recognises contacts and clients
 * while indexing and inferring nothing.
 */
internal class BackendContainer private constructor(
    private val database: Database,
    config: BackendConfig,
) : AutoCloseable {

    private val ingestState: IngestStateRepository = JdbcIngestStateRepository(database)

    private val derivations: DerivationStateRepository = JdbcDerivationStateRepository(database)

    private val contacts: ContactRepository = JdbcContactRepository(database)

    /**
     * The matching weights this process runs identity under.
     *
     * Held here rather than defaulted at each reader, because two of them read it for different
     * purposes — the pass scores pairs by it, and the advisory surface derives its floor from it —
     * and a floor computed from one set of weights while pairs were scored under another would
     * report a sample drawn by a rule nothing applied.
     */
    private val weights = Weights.STARTING

    /** The least score the advisory read reports by default. See `Weights.samplingFloor`. */
    val samplingFloor: Double = weights.samplingFloor()

    /**
     * The kept pairs, read for somebody placing the threshold.
     *
     * Its own port, so the surface is given the one read it serves rather than everything identity
     * can do to a contact. That is what makes *the advisory surface cannot write* structural
     * rather than stated.
     */
    private val pairs: UnresolvedPairs = JdbcUnresolvedPairs(database)

    suspend fun unresolved(floor: Double, limit: Int): List<UnresolvedPair> =
        pairs.unresolved(floor, limit)

    /**
     * The model for the request a person is waiting on, or null when this process holds no
     * credential.
     *
     * **One client for everything that calls a model, and two models over it.** The client holds a
     * connection pool and is never closed, because it is meant to outlive any one call — so a
     * second built from the credential would mean two pools, two copies of the credential and two
     * sets of retry state, and a process that holds the key twice is one that can leak it twice.
     * The model travels on each request rather than on the connection, which is what makes [answering]
     * and [bulk] one client's worth of cost; see `ClaudeProvider.on`.
     *
     * Here rather than inside either caller, because this is the file whose subject is what the
     * process is made of, and *made once* is part of that.
     */
    private val answering: ClaudeProvider? = config.modelCredential?.let {
        ClaudeProvider.of(it, model = config.models.answering)
    }

    /**
     * And the model for the work nobody is waiting on: recognition's inference, and the
     * classification that routes free text.
     *
     * Derived from [answering] rather than built, so the two share the one client. Null exactly
     * when that one is, because both come from the same credential.
     */
    private val bulk: ModelProvider? = answering?.on(config.models.bulk)

    /** The sidecar, once per process, and null without an address. Same reasoning as [answering]. */
    private val embeddings: EmbeddingProvider? = config.embeddingsUrl?.let {
        SidecarEmbeddingProvider.reaching(it)
    }

    /**
     * The answer path, or null when this process was not configured to hold one.
     *
     * Deliberately a property of the container rather than a flag on the surface: whether this
     * process can answer follows from what it was able to assemble, which is this file's subject.
     */
    val runner: Runner? = run {
        val answering = answering
        val bulk = bulk
        val embeddings = embeddings
        // Two facts, not three: `bulk` is derived from `answering`, so it is non-null exactly
        // when that one is. Testing it as well would read as a third independent thing and
        // disagree with `answerPathMissing`, which reports two.
        if (answering != null && embeddings != null) {
            checkNotNull(bulk) { "The bulk provider is derived from the answering one." }
            assemble(
                answering = answering,
                bulk = bulk,
                embeddings = embeddings,
                answer = config.answer,
                zone = config.zone,
            )
        } else {
            // Said once, at startup, and named down to the variable. A deployment nobody
            // configured and one that is misconfigured both answer every question the same way
            // from outside, so the difference has to be stated by whichever one can tell. The
            // list comes from the configuration rather than from these two locals, so what is
            // reported cannot disagree with what was read.
            log.warn(
                "This backend was not configured to answer: {} unset. It serves health and " +
                    "ingestion state, and answers every question as cannot answer right now.",
                config.answerPathMissing().joinToString(" and "),
            )
            null
        }
    }

    /**
     * The answer path, assembled from the ports it needs.
     *
     * Takes its ports rather than reading the configuration back, because by here the decision
     * that they are present has already been made and a second read would be a second chance to
     * get it wrong. [answer] is passed whole for the opposite reason: nothing here decides how a
     * call is sized, and unpacking it would put a second opinion about that in this file.
     *
     * **Routing goes to [bulk] and the shapes to [answering], which is the one split worth
     * stating.** Classification reads a sentence and replies with an identifier out of a set it
     * was handed — the answer is checked against that set, so a worse reply is caught rather than
     * believed, and it is the one call every free-text request pays for before any shape is
     * chosen. Both providers share a connection, so this costs routing nothing in latency.
     */
    private fun assemble(
        answering: ModelProvider,
        bulk: ModelProvider,
        embeddings: EmbeddingProvider,
        answer: AnswerConfig,
        zone: ZoneId,
    ): Runner {
        // One retrieval for both, because the runner resolves the authors behind an answer's
        // citations through the same port the shapes read their material through. A second instance
        // would be a second connection's worth of nothing.
        val retrieval = JdbcRetrieval(database)
        return Runner(
            shapes = shapes(
                model = answering,
                retrieval = retrieval,
                lookup = Lookup(
                    embeddings = embeddings,
                    index = JdbcIndexRepository(database),
                ),
                grouping = JdbcGrouping(database),
                clock = Clock.systemUTC(),
                // Named rather than left to its default, which is absent. Without it free text
                // is not read before it is routed and takes the fallback, which would make four
                // of the five shapes reachable by command only — and this is the interface that
                // free text arrives at.
                classifier = ModelClassifier(bulk),
                answering = answer.answering,
                researching = answer.researching,
                zone = zone,
            ),
            scope = ScopeResolver(JdbcSubjectSearch(database)),
            citations = CitationCheck(),
            retrieval = retrieval,
            ingestState = ingestState,
            ceiling = answer.perRequest,
            faults = { shape, attempt, reason -> log.warn("{}", fault(shape, attempt, reason)) },
            // Named rather than left to its default, which reports nothing. The evaluation set
            // scores retrieval and grounding from what this writes, and the two are decided
            // inside the runner and carried on no answer — so a default here would leave the one
            // gate that can detect a retrieval regression with nothing to read.
            trace = LoggedAnswerTrace(),
        )
    }

    /**
     * The derived passes, on their cadences.
     *
     * Held here rather than started here. A container says what a process is made of; starting a
     * loop is the entry point's business, and one started from a constructor would run before
     * anything could decide whether it should.
     */
    /**
     * Where a day's spend is read and a billed non-submission is recorded.
     *
     * One instance for the process rather than one per reader, because both readers are reading the
     * same figure: the driver asks what a day has cost, and the reading pass records the warming
     * calls that are part of that cost.
     */
    private val spend: JdbcSpendLedger = JdbcSpendLedger(database)

    val passes: DerivedPasses = DerivedPasses(
        JdbcExclusion(database),
        scheduled(config),
        spend,
    )

    /**
     * Whether the backend can serve, asked fresh each time.
     *
     * A read of ingestion state, which is a real query against the store and the same one every
     * answer makes to stamp its freshness. So this is not a liveness ping that happens to touch a
     * socket: if it answers, the thing the store is needed for works.
     *
     * Deliberately says nothing about the derived passes. A pass that has stopped is a real
     * problem and not an unhealthy container — reporting it here would have Compose restart a
     * backend that is serving every request correctly, and restarting is not what fixes a sidecar
     * that is down. How far behind each pass is belongs in the lag it reports, not in a status
     * code.
     *
     * Every failure is *not serving* rather than an exception, because the caller is a health
     * route whose whole purpose is to turn this into a status code.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun serving(): Boolean = try {
        ingestState.freshnessOf(Source.TELEGRAM)
        true
    } catch (failure: Exception) {
        log.warn("The store did not answer, so this backend is not serving.", failure)
        false
    }

    override fun close() = database.close()

    /**
     * Which passes this process can run, given what it was configured with.
     *
     * Two passes and three stages, because recognition is two stages of one pass: they share a
     * watermark key, so they share an exclusion, and identity has to run ahead of inference —
     * inference writes its findings against contacts that identity assigns, and an attribute
     * whose subject has no contact yet is counted as lost.
     */
    private fun scheduled(config: BackendConfig): List<DerivedPass> = listOfNotNull(
        // The whole of what a missing sidecar costs, in one expression: no sidecar, no pass. A
        // check inside the builder would be the same decision written where it cannot be read,
        // and this way the port reaches it non-null rather than being asserted again.
        embeddings?.let { indexing(it, config.passes) },
        // The cheaper model, because this is the pass that runs over thousands of short messages
        // and produces mechanical output. See `ModelConfig`.
        recognition(bulk, config.passes),
        // The cheaper model again, and for the same reason: a judgement about what a name means is
        // a short classification over material the model is shown, which is what that model is for.
        admission(bulk, config.passes),
    )

    /**
     * The indexing pass, for a stack that has a sidecar to embed with.
     *
     * The embedding model's version is part of the watermark, and this pass asks the sidecar for
     * it once per batch rather than holding the answer — so a sidecar restarted onto another
     * model re-derives the corpus instead of filing new spans under the old version's name.
     *
     * **A cold stack's first sweeps fail, and that is the intended behaviour rather than a fault
     * to wait out here.** The sidecar refuses every call while it loads several gigabytes of
     * weights, which takes minutes, and nothing between here and it tells that refusal apart from
     * a broken sidecar: the pass asks for the version uncaught, the driver catches it as it
     * catches any failure, and the cadence is what brings the pass back. So this is scheduled
     * immediately and the first few sweeps are logged as consecutive failures of a pass that is
     * only waiting for its weights. Delaying the schedule instead would mean guessing how long a
     * load takes, and the cost of being wrong either way is one count against the store.
     */
    private fun indexing(embeddings: EmbeddingProvider, cadences: PassConfig): DerivedPass {
        val indexer = Indexer(
            embeddings = embeddings,
            derivations = derivations,
            messages = JdbcMessageRepository(database),
            index = JdbcIndexRepository(database),
        )
        val indexing = PassStage(
            label = "indexing",
            batch = { _ -> Batched("${indexer.indexBatch()} span(s) written") },
            owed = indexer::owed,
            // Embedding is the sidecar's local compute and costs no model tokens, so this stage is
            // never gated: a day whose allowance is gone still indexes, which is what keeps newly
            // captured material retrievable while the reading waits for tomorrow.
            spends = false,
        )
        return DerivedPass(
            deriver = Deriver.INDEX,
            cadence = cadences.indexing,
            // Given to every pass rather than only to the one that spends, because the driver
            // stays ignorant of which is which — a pass whose stages all spend nothing never
            // reads it. One configured pair rather than a pair per pass: there is one credit.
            allowance = DayAllowance(cadences.inputTokensPerDay, cadences.outputTokensPerDay),
            // Nothing here reads a clock, so the same stage serves every sweep.
            stages = { listOf(indexing) },
        )
    }

    /**
     * The pass that judges which named conversations stand for a body of work.
     *
     * **A pass of its own and not a stage of recognition, which the watermark decides.** A mark is
     * one row per pass, record kind and record, so this and identity — both reading conversations —
     * would overwrite each other's marks and both re-derive the whole conversation table on every
     * sweep, one of them at the model's prices. Its own pass means its own exclusion and its own
     * cadence too, which is what it wants: it is paced by conversations arriving rather than by
     * messages.
     *
     * **Two things have to hold for it to exist, as for inference.** A credential is whether this
     * process can judge; `cadences.admissionEnabled` is whether anybody asked it to. What it buys
     * also depends on the source — where every named conversation is already a body of work it
     * rejects nothing — so it is asked for rather than assumed.
     *
     * **No clock reaches it**, unlike recognition's, so the stage is built once rather than per
     * sweep. It takes no recency horizon: a body of work discussed years ago is still one, and the
     * conversations of an archive are a handful against its messages.
     */
    private fun admission(model: ModelProvider?, cadences: PassConfig): DerivedPass? {
        val judging = model?.takeIf { cadences.admissionEnabled } ?: return null
        val admitter = Admitter(
            derivations = derivations,
            conversations = JdbcConversationRepository(database),
            messages = JdbcMessageRepository(database),
            admissions = JdbcAdmissionRepository(database),
            model = judging,
            // Every call it makes is an ordinary one rather than a submission, so this is the only
            // place its spending is written down — and what the driver reads back before each
            // batch to decide whether the day has room.
            spend = spend,
        )
        val judgement = PassStage(
            label = "admission",
            batch = { maySpend -> Batched(admitter.admitBatch(maySpend).reported()) },
            owed = admitter::owed,
            // It calls the model once per named conversation, so an exhausted day stops it. The
            // half it can still do for nothing — settling the conversations there is no client to
            // read from — runs either way, which is why the batch is given the flag rather than
            // skipped.
            spends = true,
        )
        return DerivedPass(
            deriver = Deriver.ADMISSION,
            cadence = cadences.admission,
            // **A fraction of the configured pair, because an allowance is per pass and this is
            // the second pass that spends.** The driver reads the day's total per deriver, so the
            // two bounds are independent and the figures add: with a tenth here the worst-case day
            // is a tenth more than the configured pair, not twice it, and not the pair itself. That
            // is stated rather than rounded away — the credit is finite and has been emptied once,
            // so a reader sizing it needs the real arithmetic.
            //
            // **Recognition keeps the whole pair, deliberately.** Its defaults are sized from what
            // a record measurably costs, and taking a tenth off them here would move a measured
            // bound for the pass whose lag a person notices in order to tidy the sum.
            //
            // A tenth is a judgement, sized from what a judgement measurably costs: about 1,600
            // input tokens per conversation, so the default admits some eighty of them a day. An
            // archive with more named conversations than that takes a few days over its first sweep
            // and converges — which costs nothing anybody waits on, since a conversation with no
            // verdict is a client exactly as it was. The floor keeps a deliberately tiny configured
            // pair from refusing the process at startup rather than refusing the pass at its first
            // batch.
            allowance = admissionAllowance(cadences),
            stages = { listOf(judgement) },
        )
    }

    /**
     * The recognition pass: identity always, inference when there is a credential to spend.
     *
     * Identity is identifier matching and label reading, so it costs nothing but local compute
     * and is scheduled whatever this process holds. Inference is the half that spends the model
     * budget, and without a credential it is left out rather than made to fail every sweep.
     *
     * **The horizon is computed per sweep**, which is why the stages arrive as a function the
     * driver calls when a sweep starts rather than as a list built here. The pass takes the cut-off
     * as an instant and holds no clock, deliberately — two runs of it must select the same work for
     * reasons something recorded — so whoever schedules it is whoever reads a clock, and that is
     * this. Read once here, a process left running for a week would hold its first sweep's idea of
     * recent for the whole of it.
     *
     * **Per sweep and not per call**, which is the part that has to be exact: the driver reads
     * progress by comparing what is owed before a batch against what is owed after it, so a
     * cut-off that moved between those two counts would shed the oldest records at the trailing
     * edge of the window and read their disappearance as work the batch had settled. Inference
     * selects oldest-first and a batch takes a model call per window, so that is tens of seconds of
     * slide against exactly the records the count is most sensitive to. One instant per sweep, held
     * by the closure every stage of it reads, is what makes the comparison mean what the driver
     * thinks it means.
     */
    private fun recognition(model: ModelProvider?, cadences: PassConfig): DerivedPass {
        val messages = JdbcMessageRepository(database)
        // Both stages of recognition read person records, for different things: identity matches
        // them against each other, and inference reads what they are named so that a window can
        // say who said each line. One repository, because it is one table either way.
        val persons = JdbcPersonRepository(database)
        val reconciler = Reconciler(
            derivations = derivations,
            corpus = Corpus(
                persons = persons,
                conversations = JdbcConversationRepository(database),
                messages = messages,
            ),
            contacts = contacts,
            clients = JdbcClientRepository(database),
            weights = weights,
        )
        val identity = PassStage(
            label = "identity",
            batch = { _ -> Batched(reconciler.reconcileBatch().reported()) },
            owed = reconciler::owed,
            // Identity is identifier matching and label reading, and names no model provider at
            // all. It shares the pass with the stage that spends and spends nothing itself, so an
            // exhausted day leaves it running — which matters more here than for indexing, because
            // identity is what has to run ahead of the reading when the day turns over.
            spends = false,
        )
        // **Two things have to hold for the paid half to exist, and they are different things.**
        // A credential is whether this process *can* infer; `cadences.inference` is whether anybody
        // said it should. Without the second, a stack that comes up with a key in its environment
        // starts spending on its own schedule — which is not something a deployment should be able
        // to do by accident. Identity is below and unconditional: it costs nothing.
        //
        // The extractor is built once and the pass around it per sweep. An `Inferrer` holds ports
        // and a horizon, so making one costs nothing; the provider it wraps is the one this process
        // derived for the bulk passes — not built for this pass — because the HTTP client inside it
        // is never closed and is meant to outlive any one call, so one per batch would leak a
        // connection pool per batch.
        //
        // The provider is kept beside the extractor built from it, because the pass needs both and
        // for different things: the extractor turns a window into a request and reads the reply,
        // while the provider is what a submission is handed to and asked after. Reaching the one
        // through the other would mean the extractor exposing the port it holds, which is the sort
        // of accessor that exists only to serve one caller.
        val inferring = model?.takeIf { cadences.inference }
        val extractor = inferring?.let { Extractor(it) }
        val derived = Derived(
            commitments = JdbcCommitmentRepository(database),
            events = JdbcEventRepository(database),
            statements = JdbcStatementRepository(database),
            collapse = JdbcCollapseRepository(database),
        )
        val submissions = JdbcSubmissionRepository(database)

        // One pass object for both of the stages that read it, built per sweep from the one horizon
        // the sweep computed. An `Inferrer` holds ports and bounds, so making one costs nothing —
        // what would cost something is two of them disagreeing about when the sweep began.
        fun inferrerAt(horizon: Instant): Inferrer? = extractor?.let { reader ->
            val inferrer = Inferrer(
                derivations = derivations,
                messages = messages,
                contacts = contacts,
                persons = persons,
                extractor = reader,
                derived = derived,
                bounds = Bounds(
                    notBefore = horizon,
                    // Derived from the horizon rather than read again, so a sweep holds one
                    // instant: the two were computed together, and a second clock read here
                    // would let the two disagree by however long the stages above took.
                    now = horizon.plusSeconds(cadences.horizon.inWholeSeconds),
                    // Configured rather than left at the pass's default, because it is the only
                    // thing that decides how fast a backlog drains: the cadence cannot, a stage
                    // holding one submission in flight at a time.
                    batchSize = cadences.messagesPerSweep,
                ),
                submissions = submissions,
                // Non-null exactly when the extractor is: both come from the same provider, and
                // one was built from the other.
                model = checkNotNull(inferring) { "The extractor is built from the bulk provider." },
                spend = spend,
                // The container's own sidecar port, which is null for a stack configured without
                // one. That costs the collapse its free comparison and nothing else — the reading
                // stages need no sidecar, so the pass is still worth scheduling. The indexing pass
                // above takes the opposite decision for the same value, because without a sidecar
                // it has nothing at all to do.
                embeddings = embeddings,
            )
            inferrer
        }

        fun inference(horizon: Instant): PassStage? = inferrerAt(horizon)?.let { inferrer ->
            PassStage(
                label = "inference",
                // **The cost goes to the log and to the store, and not to the driver.** The sweep
                // that commits a submission is not the sweep that reads it, so handing a figure to
                // the driver would bill one sweep for what another decided. What bounds this stage
                // is the day's own total, which the collections write and the driver reads back
                // before every batch of it.
                batch = { maySpend ->
                    inferrer.inferBatch(maySpend).let { Batched(it.reported(), it.awaiting) }
                },
                owed = inferrer::owed,
                // A stage that can put something on the model bill, which is why the day's
                // allowance reaches it. It is not the only one: the collapse and the closure
                // stages below spend too, and so does the admission pass above.
                spends = true,
            )
        }

        /**
         * The collapse, as a stage the driver drives on its own count.
         *
         * Separate from inference rather than a branch inside it, because the driver reads a
         * stage's lag before it asks for a batch and skips the stage when that is zero — so work
         * reached only when nothing is owed would never be reached at all. Its lag is the candidate
         * pairs nothing has decided plus whatever it has with the provider.
         *
         * It spends for the same reason inference does — it asks the model where the vectors
         * cannot decide a pair — so an exhausted day stops it too.
         */
        fun collapse(horizon: Instant): PassStage? = inferrerAt(horizon)?.let { inferrer ->
            PassStage(
                label = "collapse",
                batch = { maySpend ->
                    inferrer.collapseBatch(maySpend).let { Batched(it.reported(), it.awaiting) }
                },
                owed = inferrer::collapseOwed,
                spends = true,
            )
        }

        // Said once, at startup, because a pass that was switched off and a pass that has
        // nothing owed report the same thing afterwards — nothing. Whoever expects findings to
        // appear should be able to find out here why none are.
        if (model != null && !cadences.inference) {
            log.warn(
                "Recognition will not infer: RECOGNITION_INFERENCE_ENABLED is not true. Identity " +
                    "still runs and costs nothing. Nothing will be read out of a conversation, and " +
                    "no model tokens will be spent, until that variable is set.",
            )
        }
        return DerivedPass(
            deriver = Deriver.RECOGNITION,
            cadence = cadences.recognition,
            // Given to every pass rather than only to the one that spends, because the driver
            // stays ignorant of which is which — a pass whose stages all spend nothing never
            // reads it. One configured pair rather than a pair per pass: there is one credit.
            allowance = DayAllowance(cadences.inputTokensPerDay, cadences.outputTokensPerDay),
            // One clock read per sweep, closed over by inference's functions.
            //
            // **The collapse is a stage of its own, and not a branch of inference's.** The driver
            // reads a stage's lag before it asks for a batch and skips the stage when that is zero,
            // so work hung off inference's nothing-owed branch would never run on a caught-up
            // corpus — which is the only state the collapse has anything to do in. Its own lag is
            // candidate pairs plus what it has with the provider, so it is driven and drained like
            // any other stage, and it is listed last because the reading stages are what put the
            // pairs there.
            stages = {
                // Read once and shared, so the two stages that take a horizon agree about when
                // this sweep began.
                val horizon = back(cadences)
                listOfNotNull(
                    identity,
                    inference(horizon),
                    collapse(horizon),
                    closureStage(inferring, persons, submissions, cadences, horizon),
                )
            },
        )
    }

    /**
     * Closure recognition, as a stage of the same pass.
     *
     * **A third stage rather than a branch of either reading stage**, for the reason the collapse is
     * one: the driver reads a stage's lag before it asks for a batch and skips the stage when that
     * is zero, so work hung off another stage's nothing-owed branch would never be reached on a
     * caught-up corpus — which is exactly the state this has most to do in. Its lag is the
     * commitments no current verdict covers.
     *
     * **It reads no corpus record and takes no recency horizon**, which is what makes it independent
     * of the two reading stages: what it examines is commitments already written, and how far past
     * each one it looks is its own bound measured from the promise rather than from the sweep. The
     * sweep's instant still reaches it, because a submission has to be given up on eventually and
     * that is a comparison against now.
     *
     * **So it is not held back while inference is behind, and that is a decision rather than an
     * omission.** The material it weighs is the message table, which ingestion fills and inference
     * does not touch, so a conversation still being read for findings is not a conversation whose
     * messages are incomplete — a window is as complete as it will ever be the moment the messages
     * are in. What inference's lag changes is which commitments exist, and a commitment that does
     * not exist yet is not examined early. There is also nothing honest to gate it with: the driver
     * reads a stage's lag and skips the stage when that is zero, so a stage reporting no lag because
     * another one is busy would be indistinguishable from a drained one, which is the reading this
     * build may not produce.
     *
     * It spends, so an exhausted day stops it submitting — and not collecting, which is free and
     * writes the verdicts a submission already bought.
     *
     * **A member function rather than a local one inside the pass it belongs to**, unlike the two
     * stages beside it: composing it there took `recognition` past what the complexity gate allows,
     * and a composition root is the one place where that reads as a warning about size rather than
     * about structure. What it costs is the parameters the enclosing function would have closed
     * over.
     */
    @Suppress("LongParameterList")
    private fun closureStage(
        provider: ModelProvider?,
        persons: PersonRepository,
        submissions: SubmissionRepository,
        cadences: PassConfig,
        horizon: Instant,
    ): PassStage? = provider?.let { model ->
        val closer = Closer(
            closures = JdbcClosureRepository(database),
            // Read for what each author is named, so a window can say who sent each line — the same
            // reason inference reads them, and the same repository.
            persons = persons,
            submissions = submissions,
            model = model,
            bounds = ClosureBounds(
                now = horizon.plusSeconds(cadences.horizon.inWholeSeconds),
                examine = cadences.closure.toJavaDuration(),
                rearm = cadences.closureRearm,
            ),
        )
        PassStage(
            label = "closure",
            batch = { maySpend ->
                closer.closeBatch(maySpend).let { Batched(it.reported(), it.awaiting) }
            },
            owed = closer::owed,
            spends = true,
        )
    }

    companion object {

        private val log = LoggerFactory.getLogger(BackendContainer::class.java)

        /**
         * What was wrong with what a model call produced, as one line.
         *
         * **At warning rather than info, even for the fault the answer survives.** A rejection is spend
         * that bought nothing — a model call whose answer was thrown away, and two of them end the
         * request with nothing to show: as *nothing to answer from*, the same reply an empty corpus
         * gets, or — where what was rejected was a complete reply nobody could read — as the one
         * answer that says that instead. A survivable fault costs no second call but either silently
         * removes findings the reader was going to be shown —
         * and this is the only record they were made — or settles the request as *nothing to answer
         * from* on a model call that did happen, which is the one reply that is indistinguishable from
         * an empty corpus. Without either line, the only trace is a pair of token counts in the
         * adapter's log and no indication that anything was wrong.
         *
         * Formatted here rather than in `:domain`, which holds no logger and should not choose how this
         * reads. Exhaustive, so a fault added later is worded here rather than reaching a log as a
         * class name.
         *
         * **On the companion and not the instance, so the line can be asserted without a store.** It
         * is already a pure function of its arguments, and the alternative — reading it back out of a
         * captured logger on a container that needs a live database to exist — is why the wording went
         * untested until a model reply had to go into it. [LoggedAnswerTrace] draws the same line for
         * the same reason: what the string says is testable, where it goes is slf4j's business.
         */
        internal fun fault(shape: ShapeId, attempt: Int, reason: Fault): String = when (reason) {
            is Fault.WrongVariantCount ->
                "Rejected attempt $attempt of ${shape.value}: it carried ${reason.got} variant(s) " +
                    "where the shape promises ${reason.wanted}. A variant holding a claim line with " +
                    "no citations at all is dropped whole, which is the usual way this count falls short."

            // **The one rejection with no offender to name**, so the line says what it had instead:
            // the material the reply was composed from while asserting nothing about it. A reader
            // of this line is being told that the answer went into the half of the reply nothing
            // checks, or that there was genuinely nothing to say — the two are the same reply, and
            // the prose the attempt produced is not reproduced here for the reason a model's prose
            // is kept out of every other line.
            is Fault.NothingAsserted ->
                "Rejected attempt $attempt of ${shape.value}: it asserted nothing at all, with " +
                    "${reason.of} span(s) retrieved. This shape's answer is the assertions it " +
                    "cites, so a reply carrying none either put them where nothing can check them " +
                    "or had nothing to say about material that was there."

            // **Counts and not the text, like the line above it.** What the alternatives said is the
            // model's prose and stays out of every line here. Both counts are over what survived
            // the citation check, which is the set the drop was taken from — a reply that lost
            // alternatives to that check instead is reported by the faults below, so no line here
            // describes one set with the other's denominator.
            is Fault.AlternativesDropped ->
                "Dropped ${reason.dropped} of the ${reason.offered} alternative(s) attempt " +
                    "$attempt of ${shape.value} had left and offered the rest, because they " +
                    "carried no text to send. This shape offers alternatives a reader picks " +
                    "between and sends, so one holding nothing but the notes written beside it is " +
                    "not among the things being offered."

            is Fault.NothingGrounded ->
                "Rejected attempt $attempt of ${shape.value}: not one of its ${reason.claims} claim(s) " +
                    "came through the check against the ${reason.of} span(s) compared, so none of what " +
                    "it asserted reached the reader" + offenders(reason)

            is Fault.ClaimsDropped ->
                "Dropped ${reason.dropped} of ${reason.claims} claim(s) from attempt $attempt of " +
                    "${shape.value} and gave the rest, against the ${reason.of} span(s) compared" +
                    offenders(reason)

            // **The one fault whose line turns on what came back, because its verdict does.** A
            // reply the provider reported complete and the reader rejected is discarded and
            // generation rolled again; the outcomes that carry nothing readable by construction are
            // answered as they stand. Read off the fault's own verdict rather than matched on
            // the outcome a second time, so the line cannot say one thing while the runner did
            // another — and worded as two whole sentences rather than one with a substituted verb,
            // for the reason `Reply.opening` gives.
            //
            // Neither of these two sentences says what happens next, which is deliberate: the line
            // is written per attempt and nothing here knows whether a roll is left, so a rejection
            // that promised a re-roll would be wrong on the second attempt of every failed request.
            // What `stopped` appends for a refusal is the one exception and says the opposite — that
            // this attempt was not re-rolled — which holds for every refusal, because a refusal is
            // never fatal.
            is Fault.Unreadable -> if (reason.fatal) {
                "Rejected attempt $attempt of ${shape.value} because the model call produced " +
                    "nothing readable, which is not the same as an empty corpus: " +
                    stopped(reason.outcome)
            } else {
                "Answered attempt $attempt of ${shape.value} as nothing to answer from because the " +
                    "model call produced nothing readable, which is not the same as an empty corpus: " +
                    stopped(reason.outcome)
            }
        }

        /**
         * What [reason] says the model cited and should not have, in the two kinds it is reported in.
         *
         * **Each half is named only when it has something in it, which is what the fault's own guard
         * makes necessary.** Either list may be empty — a fault needs something in one of them, not in
         * both — so a line that printed both unconditionally would leave `Mistyped or invented:` with
         * nothing after it. A half that is absent said nothing rather than said nothing happened.
         *
         * **One label for both faults.** `NothingGrounded` and `ClaimsDropped` report their identifier
         * half identically; which fault it is, is already the sentence the label is appended to, so
         * wording them apart only gave a reader two phrasings to learn for one thing.
         *
         * **Reported apart rather than merged, for the reason `Fault.Ungrounded.unreadable` gives.**
         * The unreadable half is quoted because a piece is delimited by commas and so may carry spaces;
         * unquoted it would read as several citations where the model wrote one. It arrives with each
         * entry cut to a length and the entries deduplicated — `Runner` does both where it builds the
         * fault — so no single entry can be a model's prose. The number of entries is not bounded,
         * so a reply that writes a different unreadable citation on every claim line still makes this
         * a long line; `Runner.CITATION_EXCERPT` records that.
         */
        private fun offenders(reason: Fault.Ungrounded): String = buildString {
            if (reason.cited.isNotEmpty()) {
                append(". Mistyped or invented: ${reason.cited.joinToString()}")
            }
            if (reason.unreadable.isNotEmpty()) {
                append(". Unreadable, as written: ${reason.unreadable.joinToString { "\"$it\"" }}")
            }
            append(".")
        }

        /**
         * What the model did, and the evidence for it.
         *
         * **The reply is the point of the line.** Unrelated things leave a request with nothing to
         * show and they render to whoever asked as one of two replies, so the only way to tell which
         * happened is to see what came back. A refusal and a truncation are legible from the stop
         * reason alone; a reply the format reader rejected is legible only from the reply, and a line
         * reporting its length would have the next reader guessing at which rule dropped it.
         *
         * **Bounded here, which is the whole reason `:domain` hands the outcome over whole.** A reply is
         * model output of a size a shape's ceiling sets, and a log line is not the place for several
         * thousand characters of it. The head rather than a middle: the format markers this build reads
         * are at the start of a reply and of each block, so the opening is where the disagreement shows.
         */
        private fun stopped(outcome: ModelOutcome): String = when (outcome) {
            is ModelOutcome.Answered ->
                "it answered in full and the reply read as no alternatives at all, so the format was " +
                    "not followed or the reader rejected every block. ${excerpt(outcome.text)}"

            is ModelOutcome.Truncated ->
                "it ran out of room before finishing. ${excerpt(outcome.partial)}"

            is ModelOutcome.Refused ->
                "the provider's classifiers declined it, naming ${outcome.category ?: "no category"}. " +
                    "Asking again is refused again, so this attempt was not re-rolled."

            is ModelOutcome.Paused ->
                "the turn stopped mid-flight and could not be carried further. ${excerpt(outcome.partial)}"
        }

        /**
         * [text]'s opening, to the bound a log line can carry, saying how much was left out.
         *
         * Not the only model text this file bounds, and not bounded the same way: an unreadable
         * citation is cut by `Fault.Ungrounded.CITATION_EXCERPT` before it ever arrives here, and
         * that cut marks neither its length nor the fact of it, because a citation is short enough
         * that what survives reads as the whole of what the model wrote. This is one payload cut to
         * a length and says so; that is each entry of a list cut to a length and does not.
         */
        private fun excerpt(text: String): String = when {
            text.isBlank() -> "It came back empty."
            text.length <= REPLY_EXCERPT -> "It read: $text"
            else -> "It opened, of ${text.length} character(s): ${text.take(REPLY_EXCERPT)}…"
        }

        /** The pinned session a sweep's exclusion is, and the one its own queries borrow. */
        private const val CONNECTIONS_PER_PASS = 2

        /**
         * What the admission pass may spend in a day, from what the deployment configured.
         *
         * **A function of its own, and internal, because the figure has to be assertable.** It is
         * read inside a builder reachable only through [of], which opens a store — so a divisor
         * changed, or the floor dropped, would ship green with nothing but a comment to disagree
         * with it. The decision this expresses is the one worth holding: that what the pass may
         * spend is a fraction of the pair and strictly less than it.
         */
        internal fun admissionAllowance(cadences: PassConfig) = DayAllowance(
            inputTokens = (cadences.inputTokensPerDay / ADMISSION_SHARE).coerceAtLeast(1),
            outputTokens = (cadences.outputTokensPerDay / ADMISSION_SHARE).coerceAtLeast(1),
        )

        /**
         * What fraction of the configured day allowance the admission pass may take.
         *
         * **Here rather than in the configuration, because what it bounds is a second pass's
         * spending relative to the first, not a figure anybody sizes on its own.** A configured
         * pair of its own would be two unrelated numbers a deployment could set to anything, and
         * the one thing a reader needs to be able to say about the day — how much more than the
         * configured pair it can cost — would then depend on both of them. As a fixed fraction it
         * is one sentence: a tenth more.
         */
        private const val ADMISSION_SHARE = 10

        /**
         * How much of an unreadable reply one log line carries.
         *
         * Enough for the opening of a reply and the first few of its markers, which is where a
         * format disagreement shows. Larger and a single degraded request writes a wall; smaller and
         * the line says only that something came back, which is what there was before it.
         */
        private const val REPLY_EXCERPT = 600

        /** The horizon as an instant, read from the clock once per sweep by the caller. */
        private fun back(cadences: PassConfig): Instant =
            Instant.now().minusSeconds(cadences.horizon.inWholeSeconds)

        /**
         * Open the store and assemble what this process is.
         *
         * The store applies its own migrations as it opens, so the schema anything here reads
         * through is the one this build shipped rather than whatever the volume happened to hold.
         * Before the surface starts listening, deliberately: a schema problem should stop a
         * deployment rather than surface as a route that fails once somebody asks something.
         *
         * **The pool is the work's plus two connections per pass**, and both are needed. One is
         * the pin: an exclusion is a session-level advisory lock, so it is a connection held for
         * the whole of a sweep. The other is what that sweep's own queries borrow — the counts,
         * the selections, the marks and the derived writes — and a pool enlarged only for the pins
         * would leave a draining pass taking those from the share [DEFAULT_POOL_SIZE] sizes for
         * serving. A pass borrows one at a time, because a sweep's stages run in sequence.
         *
         * Sized from the passes that exist rather than from the ones configured: the difference is
         * a pair of idle connections per pass nobody switched on, against a server whose ceiling is
         * in the hundreds, and sizing it from configuration would make a pool that is correct until
         * somebody sets a variable.
         * What this buys is the acceptance the issue asks for — a sweep cannot take the answer
         * path's connections, because neither the one it pins nor the one it works through was
         * ever part of that share.
         */
        fun of(config: BackendConfig): BackendContainer = BackendContainer(
            database = Database.migrated(
                url = config.store.url,
                user = config.store.user,
                password = config.store.password,
                poolSize = Database.DEFAULT_POOL_SIZE + CONNECTIONS_PER_PASS * Deriver.entries.size,
            ),
            config = config,
        )
    }
}
