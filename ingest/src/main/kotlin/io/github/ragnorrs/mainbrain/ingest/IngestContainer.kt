package io.github.ragnorrs.mainbrain.ingest

import io.github.ragnorrs.mainbrain.data.Database
import io.github.ragnorrs.mainbrain.data.JdbcBackfillPlanRepository
import io.github.ragnorrs.mainbrain.data.JdbcConversationRepository
import io.github.ragnorrs.mainbrain.data.JdbcDocumentRepository
import io.github.ragnorrs.mainbrain.data.JdbcIngestStateRepository
import io.github.ragnorrs.mainbrain.data.JdbcMessageRepository
import io.github.ragnorrs.mainbrain.data.JdbcPersonRepository
import io.github.ragnorrs.mainbrain.domain.FailureKind
import io.github.ragnorrs.mainbrain.domain.Pass
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import io.github.ragnorrs.mainbrain.ingest.config.IngestConfig
import io.github.ragnorrs.mainbrain.ingest.source.telegram.ExcludedConversations
import io.github.ragnorrs.mainbrain.ingest.source.telegram.TdlibClient
import io.github.ragnorrs.mainbrain.ingest.source.telegram.TelegramSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What this process is made of, wired in one place by hand.
 *
 * The only file here that knows every layer: the store, the adapter, the loop. One of them per
 * process, because a second would be a second answer to what this process is, and the two would
 * disagree — which is a rule the build asserts rather than a convention.
 *
 * **One adapter, named once.** The scheduler is handed a list of streams and knows no source, so
 * adding a second source is a line in this file and nothing else. It is handed the stream port
 * alone: the same adapter satisfies the re-read port, and the scheduler is deliberately given no
 * route to it.
 *
 * **The session credential reaches the adapter and nothing else**, and there is no model
 * credential in this process to reach anything.
 *
 * **Nothing Telegram is opened until a session exists.** A session is minted by a person at a
 * console, and the library locks its own store — so a service that opened a client in order to
 * discover it was not signed in would hold the lock that the sign-in needs, and the only way
 * through would be to stop the service first. Waiting instead leaves the directory free, and the
 * reading starts by itself when the sign-in lands.
 *
 * **A deployment may switch the reading off**, and [run] decides it before anything else it does.
 * That placement is the whole of the feature: what a switched-off deployment owes the machine is
 * the session directory, so the check has to sit ahead of the wait and ahead of the client. A
 * switch that only skipped sweeps would open a client and hold the directory, leaving nothing for
 * another stack. The store is a different matter and is opened on both paths — a switched-off
 * process still has something to report, and reporting it is worth a connection.
 *
 * The store is nonetheless handed in unopened, for one reason that is about this file rather than
 * about deployment: opening it after the switch has been read is what lets both paths be asserted
 * without a database.
 *
 * Closing order is the one decision in here worth stating: the session first, the store second.
 * The client library writes its own store on the way out — the incremental position among it — so
 * a process that dropped the database pool first would still have to wait for the session, and a
 * process that never closed the session at all would re-read whatever it had not committed.
 */
internal class IngestContainer internal constructor(
    // Not private, so that both of [run]'s paths can be exercised against a store that refuses to
    // open. It is not a second composition root: `of` is the one place this process is wired, and
    // what comes through here is a store and a configuration rather than a different answer to
    // what the process is made of. The rule `:archtest` asserts counts declared roots and cannot
    // see a construction, so this is the one place the distinction is written down.
    private val config: IngestConfig,
    private val store: Lazy<Database>,
) : AutoCloseable {

    /**
     * Opened once a session exists, which is why this is not a constructor parameter.
     *
     * Deferred like the store above and shaped differently all the same: the store is opened by
     * whichever path runs and then belongs to the process, while this is opened on one path only
     * and has to be reachable from [close] on a thread that is not the one that opened it.
     */
    @Volatile
    private var client: TdlibClient? = null

    /**
     * The one thing both of [run]'s paths need, so it opens the store between them.
     *
     * Lazy rather than eager because the switch is read first: a process that is about to say it
     * is reading nothing still says it here, but it says it after the decision rather than before.
     */
    private val state by lazy { JdbcIngestStateRepository(store.value) }

    private val log = LoggerFactory.getLogger(IngestContainer::class.java)

    /** Wait for a session if there is none, then sweep until the process is stopped. */
    suspend fun run() {
        if (!config.ingesting) return idleWithoutReading()
        // Forced here, and not where this was assembled, so that the switch above is answered
        // before a connection is made. Still ahead of the wait, though, and deliberately: the
        // store brings the schema up to date as it opens, and a schema problem is worth finding
        // immediately rather than after somebody has been asked for a telephone code.
        //
        // On the I/O dispatcher because the pool and the migrations both block, and this is a
        // `suspend` function whose caller is the event loop `runBlocking` holds — which the
        // shutdown hook joins. Blocking it directly is the one thing a suspend function here
        // must not do.
        val database = withContext(Dispatchers.IO) { store.value }
        awaitSignIn()
        val opened = TdlibClient.opened(config.session)
        client = opened
        val telegram = TelegramSource(
            client = lazyOf(opened),
            plan = JdbcBackfillPlanRepository(database),
            state = state,
            excluded = ExcludedConversations(config.excludedChats, config.onlyChats),
            limits = config.limits,
        )
        val corpus = BatchWriter(
            conversations = JdbcConversationRepository(database),
            messages = JdbcMessageRepository(database),
            persons = JdbcPersonRepository(database),
            documents = JdbcDocumentRepository(database),
        )
        Scheduler(streams = listOf(telegram), corpus = corpus, state = state).run()
    }

    /**
     * Record a re-read request and exit, which is the host's one route to one.
     *
     * The request is a change to the plan, not the reading itself: the unit goes back to unwalked
     * and the service's next sweep walks it, at the pace and under the limits every other page
     * answers to. A command that did the walking here would be a second reader of the same source
     * with its own ideas about both.
     *
     * **It goes through the port rather than reaching the plan directly.** The refusal of a unit
     * the adapter never reported lives in the adapter, with the rest of what the adapter knows
     * about its own work units, and an entry point that wrote to the plan itself would be a second
     * implementation of a re-read that happens to share a table.
     *
     * **No session is opened, and that is not an optimisation.** The library begins consuming the
     * account's difference as soon as it is built, into a queue this process would exit and throw
     * away — and that difference is the one thing no second copy of the position can recover. A
     * command whose purpose is to repair lost records must not lose any, so the adapter is handed
     * a session it never forces.
     *
     * **The service still has to be stopped**, for the reason that outlives the lock: the beliefs
     * a re-read clears are the running process's own memory, so one that kept reading through
     * this would re-walk the history and still skip every profile and header it already thinks it
     * has sent. Stopping it is what makes the request whole, and the next start is what performs
     * it.
     */
    suspend fun reread(unit: WorkUnitId?) {
        val database = withContext(Dispatchers.IO) { store.value }
        val telegram = TelegramSource(
            client = lazy { TdlibClient.opened(config.session).also { client = it } },
            plan = JdbcBackfillPlanRepository(database),
            state = state,
            excluded = ExcludedConversations(config.excludedChats, config.onlyChats),
            limits = config.limits,
        )
        telegram.reread(unit)
        val what = unit?.let { "Conversation ${it.value}" } ?: "Every conversation of ${Source.TELEGRAM}"
        log.info(
            "{} will be read again, from its newest message back. Nothing has been removed and " +
                "nothing has been read yet: the records that arrive upsert onto what is stored, " +
                "and the reading happens on the next sweep of the service. Start it again to " +
                "spend it.",
            what,
        )
        // Said after the request lands, because the request is good either way — the plan is the
        // same store whichever deployment reads it. What would be false is the line above: a
        // deployment told not to read sweeps nothing, so nothing spends this until the switch
        // moves, and a re-read reported as requested and never performed is the one outcome this
        // route must not produce.
        if (!config.ingesting) {
            log.warn(
                "This deployment is switched off by INGEST_ENABLED, so no sweep here will spend " +
                    "it. The request is recorded in the plan and whichever stack is ingesting " +
                    "will perform it; on this one, nothing will.",
            )
        }
    }

    override fun close() {
        client?.close()
        // Only if something opened it. A report the store refused leaves it unopened, and forcing
        // it here would make a pool, migrate a schema and close it again on the way out.
        if (store.isInitialized()) store.value.close()
    }

    /**
     * Say that nothing is being read, in both places that can be read, and then stay up.
     *
     * Staying up rather than exiting, for the same reason the sign-in wait does: the stack is
     * restarted unless stopped, so a process that exited on a deliberate switch would sit in a
     * restart loop, and the check that every service is `running` fails on any other state. Said
     * once, because a line repeated every few seconds is the one that gets filtered out — and
     * said at all, because a process reading nothing and a source with nothing new look identical
     * from outside.
     *
     * **Reported to the store as well, which is the half worth spelling out.** A switched-off
     * worker is the second state in which this process is up and reading nothing, and the first
     * one — no session minted — is reported in both channels precisely because a log line reaches
     * whoever is at the machine and nothing else. Recorded as needing a person because that is
     * what it is: no amount of waiting starts the reading, and only somebody changing the
     * configuration will.
     *
     * One thing it does not fix, because nothing here can. Freshness keeps saying the corpus is
     * complete through the last drain, which stays true and stops being the whole truth — there
     * is no way to say *and it is not advancing* in what a source reports, and inventing one is a
     * larger change than this switch.
     */
    private suspend fun idleWithoutReading(): Nothing {
        log.warn(
            "Ingestion is switched off: INGEST_ENABLED is false, so no source is being read and " +
                "nothing Telegram is opened. This process holds no lock on the session " +
                "directory, which is what leaves it to whichever stack is ingesting. Unset the " +
                "variable, or set it to true, to read the source as usual.",
        )
        reportThatAPersonIsNeeded("ingestion is switched off on this deployment by INGEST_ENABLED")
        awaitCancellation()
    }

    /**
     * Wait until somebody has signed in, saying so once in both places that can be read.
     *
     * Reported to the store as well as to the log, because the two are read by different people:
     * health is what a reader of the system asks, and the log is what whoever is at the machine
     * sees. Reported once rather than on every check — a row that says a person is needed does
     * not become truer for being written every five seconds.
     */
    private suspend fun awaitSignIn() {
        if (config.session.isSignedIn()) return
        log.warn(
            "No Telegram session yet, so nothing is being read. Mint one with " +
                "`docker compose run --rm ingest login`; this process will start by itself when " +
                "it lands. Nothing here signs in, and nothing here holds the session directory " +
                "while it waits. A session already signed in but unrecorded here needs the same " +
                "command, which recognises it and spends no sign-in attempt.",
        )
        reportThatAPersonIsNeeded("no session has been signed in on this machine")
        while (!config.session.isSignedIn()) delay(SIGN_IN_POLL)
        log.info("A session has been signed in. Starting to read the source.")
    }

    /**
     * Record that the reading is blocked on a person, and carry on if that cannot be written.
     *
     * The same best-effort rule the rest of the reporting follows: a store that cannot take the
     * note is not a reason to stop waiting, because the wait is what makes the sign-in possible in
     * the first place — and on the switched-off path there is nothing left to abandon anyway.
     *
     * Two states share it, with [note] the only difference between them: a session nobody has
     * minted, and a deployment told not to read. Both are a reader of this process being owed an
     * answer that only a person can give, which is what the kind means, and the note is what
     * separates the two for whoever goes looking.
     *
     * Opening the store is part of the report rather than a precondition of it, which is why this
     * reaches [state] inside the `try`: on the switched-off path this is the first thing that
     * wants a connection, so a store that cannot be opened must leave the process up and quiet
     * rather than crash it.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun reportThatAPersonIsNeeded(note: String) {
        try {
            state.recordPass(Source.TELEGRAM, Pass.Failed(FailureKind.NEEDS_A_PERSON, note))
        } catch (failure: Exception) {
            log.warn("Could not record that a person is needed; carrying on without the row.", failure)
        }
    }

    companion object {

        /**
         * Assemble what needs neither a session nor a store.
         *
         * The store is described here and opened by [run]. It applies its own migrations as it
         * opens, so the schema the adapter's plan is written through is the schema this build
         * shipped rather than whatever the volume happened to hold — and [run] opens it before
         * the sign-in wait for that reason, so a schema problem is still found immediately
         * rather than after somebody has been asked for a telephone code. A switched-off
         * deployment applies no migrations from here, which costs nothing: the backend opens the
         * same store the same way, so no stack runs against a schema nobody ran.
         */
        fun of(config: IngestConfig): IngestContainer {
            // Said once, at startup, because the alternative is the quiet lapse the exclusion
            // exists to prevent: an empty set is a legitimate state — the assistant's own chat
            // does not exist until the bot does — and it is indistinguishable at a glance from
            // a list that was meant to be set and was not.
            //
            // Not said while an allowlist is in force, because then it would be false: a list of
            // what to read keeps everything else out by itself, the assistant's own chat
            // included, so there is nothing for an exclusion to add. Said whether or not the
            // reading is switched off, though: the sentence is about what this deployment is
            // configured to keep out, which is as true of a stack somebody is about to switch on
            // as of one that is reading now.
            if (config.excludedChats.isEmpty() && config.onlyChats.isEmpty()) {
                LoggerFactory.getLogger(IngestContainer::class.java).warn(
                    "No conversations are excluded. Set TELEGRAM_EXCLUDED_CHATS to keep the " +
                        "assistant's own chat and the project coordination group out of the corpus.",
                )
            }
            return IngestContainer(
                config,
                lazy {
                    Database.migrated(
                        url = config.store.url,
                        user = config.store.user,
                        password = config.store.password,
                    )
                },
            )
        }

        /**
         * How often to look for a session.
         *
         * A file check costs nothing, so this is about how long somebody waits after the sign-in
         * before the log says anything — not about load.
         */
        private val SIGN_IN_POLL: Duration = 5.seconds
    }
}
