package io.github.ragnorrs.mainbrain.ingest.source.telegram

import it.tdlight.Init
import it.tdlight.Log
import it.tdlight.client.APIToken
import it.tdlight.client.AuthenticationData
import it.tdlight.client.AuthenticationSupplier
import it.tdlight.client.ClientInteraction
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientFactory
import it.tdlight.client.TDLibSettings
import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The session, against the library. The only class that speaks the protocol.
 *
 * It implements the seam and nothing above it knows this file exists — which is what lets the
 * adapter's rules be tested by calling functions rather than by standing a session up, and what
 * keeps the protocol's thousands of generated types from reaching anything that would have to
 * mention them.
 *
 * **The library owns the position, the session and the gap detection.** There is no field here
 * holding any of them, and that is the decision rather than an omission: TDLib keeps its own
 * store in the session directory, resumes its update stream from where it left off, and fills
 * gaps itself. A second copy kept here would be two authorities on one fact, free to disagree
 * after a crash — so what this class holds is a queue of updates not yet asked for, a cache of
 * chats, and whether a person is needed.
 *
 * **Updates arrive on the library's threads and leave on the caller's.** No handler makes a
 * request: each one either puts an event on a queue or writes a field this class owns. Resolving
 * an event — an edit needs the message fetched, because the update carries only the new content —
 * happens in [changes], on the caller's coroutine, where a failure can be reported to somebody
 * and where a wait can be sat out rather than blocking the library's own thread.
 *
 * **A sign-in prompt is refused rather than waited on.** Nothing in this container can answer
 * one: there is no console attached to it, and a session file is a logged-in account, so it is
 * created on the host out of band — see [signInOnConsole]. Refusing turns an unauthorised session
 * into a report that a person is needed, where waiting would be a process that is up and silent.
 */
@Suppress("TooManyFunctions")
internal class TdlibClient private constructor(session: TelegramSession) : TelegramClient, AutoCloseable {

    private val chatListLimit = session.chatListLimit

    /**
     * Updates taken from the library and not yet asked for.
     *
     * A deque rather than a queue, because an event that cannot be resolved right now goes back
     * where it was. The order these arrive in is the order they happened in — an edit after the
     * message it edits — and handing them over out of order would offer the corpus an edit to a
     * message it has not been given.
     */
    private val pending = ConcurrentLinkedDeque<Pending>()

    /**
     * Chats as the library last described them.
     *
     * The protocol's chat list is identifiers, so without this every enumeration would be one
     * lookup per chat — thousands of them, every pass. The library announces each chat it learns
     * about and announces again when a title or a last message changes, so an entry is dropped on
     * either and re-read once: the cache is refreshed by eviction rather than by being patched,
     * because patching a protocol object held in two places is how a field goes stale in only one
     * of them.
     */
    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()

    /** Why a person is needed, or `null`. Written on the library's threads, read on the caller's. */
    @Volatile
    private var awaitingAPerson: String? = null

    /**
     * Which chat lists have been pulled from the server in this process, by name.
     *
     * Ours rather than the library's, which exposes such a flag and never sets it — see
     * [loadTheChatList]. Read and written on the caller's single thread of control.
     */
    private val chatListsLoaded = mutableSetOf<String>()

    private val factory = SimpleTelegramClientFactory()

    private val client: SimpleTelegramClient = factory.builder(settingsFor(session)).run {
        addUpdateHandler(TdApi.UpdateNewChat::class.java) { chats[it.chat.id] = it.chat }
        addUpdateHandler(TdApi.UpdateChatTitle::class.java) { chats -= it.chatId }
        addUpdateHandler(TdApi.UpdateChatLastMessage::class.java) { chats -= it.chatId }
        addUpdateHandler(TdApi.UpdateNewMessage::class.java) { pending += Pending.Arrived(it.message) }
        // Only the content update, not `UpdateMessageEdited`: the two arrive for one edit, and the
        // second carries nothing the corpus wants — a reply markup and the edit time, which is
        // read off the message itself.
        addUpdateHandler(TdApi.UpdateMessageContent::class.java) { pending += Pending.Edited(it.chatId, it.messageId) }
        // `isPermanent` distinguishes a removal from the library forgetting its local copy, which
        // it does routinely to bound its cache. Treating the second as a removal would record a
        // withdrawal nobody made — and a withdrawal is kept from its first statement and never
        // cleared, so that mistake is permanent in a way a touched observation would not be.
        addUpdateHandler(TdApi.UpdateDeleteMessages::class.java) {
            if (it.isPermanent) pending += Pending.Withdrawn(it.chatId, it.messageIds.toList())
        }
        // Readiness clears the flag and nothing else sets it here. What a person is needed *for*
        // is known one level down, where the library asks for a code or a password and is
        // refused — and reading it off the authorisation states instead would mean listing the
        // eight that happen to need a human today, with the one added next release reading as
        // fine.
        addUpdateHandler(TdApi.UpdateAuthorizationState::class.java) { update ->
            if (update.authorizationState is TdApi.AuthorizationStateReady) awaitingAPerson = null
        }
        setClientInteraction(refusing())
        // **The service does not sign in, and is given no number to sign in with.** Handing the
        // library an account is what makes it submit one, and submitting one is what makes the
        // platform send a code — so a process started before a session exists would spend a
        // sign-in attempt on a code nobody can type, and a container that restarts would spend
        // another. Sign-in attempts are a small budget: enough of them and the account is locked
        // out for the better part of a day, which during a week that ends in a demonstration is
        // the expensive failure. The number is read by [signInOnConsole] alone.
        build(refusingToSignIn())
    }

    override suspend fun conversations(): List<TelegramConversation> {
        refuseIfAPersonIsNeeded()
        loadTheChatList()
        // Both lists, because a conversation somebody put away is still part of the archive —
        // and in a personal account it is disproportionately where the old material is. Asking
        // for the main list alone would leave those conversations unenumerated, so nothing would
        // ever plan a backfill for them and only their new messages would arrive.
        val lists = listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())
        return lists
            .flatMap { list ->
                val ids = request(TdApi.GetChats(list, chatListLimit)).chatIds.toList()
                // At the limit there may be more the enumeration never sees, which is the
                // silently-half-read failure the limit's own comment warns about — so it says so
                // rather than being discovered as conversations that never ingest.
                if (ids.size >= chatListLimit) {
                    LOG.warn(
                        "{} came back at the limit of {}; conversations past it are not enumerated.",
                        list::class.java.simpleName,
                        chatListLimit,
                    )
                }
                ids
            }
            .distinct()
            .mapNotNull { chatId -> chat(chatId)?.toShape() }
    }

    override suspend fun conversation(chatId: Long): TelegramConversation? {
        refuseIfAPersonIsNeeded()
        return chat(chatId)?.toShape()
    }

    override suspend fun history(chatId: Long, before: Long?, limit: Int): List<TelegramMessage> {
        refuseIfAPersonIsNeeded()
        // `before` of zero is the protocol's *from the newest*, and the offset stays at zero
        // because this walk only ever goes backwards. Not local-only: the point of the walk is
        // the history the library has never held.
        return request(TdApi.GetChatHistory(chatId, before ?: 0L, 0, limit, false))
            .messages
            .orEmpty()
            .filterNotNull()
            .map { it.toShape() }
    }

    override suspend fun positionAt(chatId: Long, reached: Instant): Long? {
        refuseIfAPersonIsNeeded()
        return try {
            // The protocol answers with the last message sent no later than the date, which is
            // the message the previous walk ended on — and a history request takes what is
            // strictly older than it. Nothing is re-fetched and nothing between them is skipped.
            request(TdApi.GetChatMessageByDate(chatId, reached.epochSecond.toInt())).id
        } catch (ignored: NothingThere) {
            // No message at or before that time, which is this seam's `null`: there is no
            // position older than the one reached for a walk to resume from.
            null
        }
    }

    /**
     * Who the library says is signed in, as a bare identifier.
     *
     * The protocol answers with a whole profile and only the number is taken: the account's own
     * name and claims reach the corpus through [people] like anybody else's, and reading them off a
     * second answer would be a second opinion on a record this already identifies.
     */
    override suspend fun account(): Long {
        refuseIfAPersonIsNeeded()
        return request(TdApi.GetMe()).id
    }

    override suspend fun people(userIds: Set<Long>): List<TelegramPerson> {
        refuseIfAPersonIsNeeded()
        return userIds.mapNotNull { userId ->
            try {
                request(TdApi.GetUser(userId)).toShape()
            } catch (ignored: NothingThere) {
                // A profile the session cannot see, which the seam says to omit. Anything else is
                // a failure about the request and is left to the caller.
                null
            }
        }
    }

    /**
     * Whatever the library has announced since this was last called, resolved.
     *
     * An edit arrives as *the content of this message changed*, so the message has to be fetched
     * before it can be handed on — and that is a request, which can be refused or waited on. What
     * is already resolved is handed over and the rest goes back on the queue: the failure then
     * arrives on a later ask, with nothing dropped in between. A failure with nothing resolved
     * yet is handed to the caller instead, because an empty answer would report *nothing is
     * happening* of a source that is in fact unreachable.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun changes(limit: Int): List<TelegramChange> {
        refuseIfAPersonIsNeeded()
        val resolved = mutableListOf<TelegramChange>()
        var unresolved: Exception? = null
        while (unresolved == null && resolved.size < limit) {
            val next = pending.poll() ?: break
            try {
                next.resolve()?.let { resolved += it }
            } catch (cancellation: CancellationException) {
                pending.addFirst(next)
                throw cancellation
            } catch (failure: Exception) {
                pending.addFirst(next)
                unresolved = failure
            }
        }
        if (resolved.isEmpty()) unresolved?.let { throw it }
        return resolved
    }

    override suspend fun changesPending(): Boolean = pending.isNotEmpty()

    /**
     * Close the session, waiting for the library to flush its own store.
     *
     * Waiting is the point. The session directory holds the incremental position and the message
     * database, and a process that exits without letting the library write them out is a process
     * whose next start re-reads whatever it had not committed.
     */
    override fun close() {
        client.closeAndWait()
        factory.close()
    }

    /**
     * One update, resolved into what the seam carries — or `null` where nothing is left to carry.
     *
     * **An edit the platform will not hand over is dropped, not retried.** The content update says
     * a message changed; fetching it can be refused for reasons that are about that message — it
     * has since been removed, the session has lost access to the chat — and none of them clears by
     * asking again. Parking it instead would be worse than losing an edit: the queue is ordered,
     * so one unresolvable event at its head stops the whole change feed draining for the life of
     * the process, and every pass behind it would read a history page and throw it away.
     *
     * What is *not* caught here is a stated wait or a session needing a person. Those are the
     * whole source's state rather than this message's, they do clear by waiting, and [changes]
     * puts the event back when it sees one.
     */
    private suspend fun Pending.resolve(): TelegramChange? = when (this) {
        is Pending.Arrived -> TelegramChange.Upserted(message.toShape())
        is Pending.Edited -> try {
            TelegramChange.Upserted(request(TdApi.GetMessage(chatId, messageId)).toShape())
        } catch (ignored: TelegramRequestFailed) {
            null
        }
        is Pending.Withdrawn -> TelegramChange.Removed(chatId, messageIds)
    }

    /**
     * Pull both chat lists from the server, once per process, where *nothing there* means done.
     *
     * The protocol answers a load request with 404 once every chat has been loaded, which is a
     * statement about the list and not a failure — but it arrives as the same refusal as any
     * other, so a caller that did not know would report a healthy source as broken on every pass.
     * Measured, not theorised: it stopped the enumeration before any history was read, and the
     * health row said `UNEXPECTED 404: Not Found` while the archive sat there intact.
     *
     * **Once per process, on a flag of our own.** The library exposes a *list loaded* flag that it
     * never sets in this version, so trusting it meant a server request on every single pass —
     * against the one budget this build exists to protect. After the lists are loaded, new chats
     * arrive as updates like anything else, and the enumeration reads what those updates left
     * behind.
     *
     * Both lists, because an archived conversation is still part of an archive.
     */
    private suspend fun loadTheChatList() {
        listOf(TdApi.ChatListMain(), TdApi.ChatListArchive()).forEach { list ->
            val name = list::class.java.simpleName
            if (name in chatListsLoaded) return@forEach
            loadEveryChatIn(list, name)
            // Marked per list and only once it finished. A wait partway through the second list
            // must not cost the first one all over again on the next pass.
            chatListsLoaded += name
        }
    }

    /**
     * Ask for one list until the protocol says there is no more of it, or until enough rounds.
     *
     * A load request takes a limit and returns what it could add, so one call is a page rather
     * than the list — and the way to know the end is the 404 above. Looping here is what makes the
     * enumeration that follows see a complete list rather than whichever page had arrived.
     *
     * **The round cap is not belt and braces.** The protocol answers this request as soon as it
     * has *started* fetching rather than when it has added anything, so a state in which it keeps
     * answering *ok* without progress is a loop of server requests whose only brake is the wait it
     * eventually provokes — inside a process whose stated purpose is protecting that budget, in a
     * call no test can reach. The cap turns that into a bounded cost and a line somebody can read.
     * Each round may add [chatListLimit] chats, so the ceiling is far past any list this build
     * was shaped for.
     *
     * Cancellation is checked each round because this is the one loop here that can run long, and
     * a process being stopped should not wait out a chat list.
     */
    private suspend fun loadEveryChatIn(list: TdApi.ChatList, name: String) {
        repeat(LOAD_ROUNDS) { round ->
            currentCoroutineContext().ensureActive()
            try {
                request(TdApi.LoadChats(list, chatListLimit))
            } catch (ignored: NothingThere) {
                return
            }
            if (round == LOAD_ROUNDS - 1) {
                LOG.warn(
                    "Stopped loading {} after {} rounds without the platform saying it was " +
                        "complete; the enumeration will see what has arrived so far.",
                    name,
                    LOAD_ROUNDS,
                )
            }
        }
    }

    /** A chat from the cache, read from the library once if it is not there. */
    private suspend fun chat(chatId: Long): TdApi.Chat? = chats[chatId] ?: try {
        request(TdApi.GetChat(chatId)).also { chats[chatId] = it }
    } catch (ignored: NothingThere) {
        null
    }

    /**
     * One request, with the protocol's failures translated into the three this seam declares.
     *
     * Everything the library can refuse arrives here as one exception type carrying a code, so
     * this is the only place that reads one — and the only place that could get the translation
     * wrong. Above it, a stated wait, a session needing a person and a refused request are three
     * different things with three different answers.
     */
    private suspend fun <R : TdApi.Object> request(function: TdApi.Function<R>): R = try {
        client.send(function).await()
    } catch (error: TelegramError) {
        throw error.translated()
    }

    private fun refuseIfAPersonIsNeeded() {
        awaitingAPerson?.let { throw TelegramSignInNeeded(it) }
    }

    /**
     * An interaction that answers no prompt and records that one was asked.
     *
     * The library asks for a code, a password or a confirmation by calling this. Its default
     * reads the console, which in a container is a process blocked on a terminal nobody is at —
     * up, silent, and reading nothing. Failing the request instead turns the same situation into
     * a health report naming the one person who can clear it.
     */
    private fun refusing() = ClientInteraction { parameter, _ ->
        val reason = "the session is waiting for $parameter, which only a person can give"
        awaitingAPerson = reason
        CompletableFuture.failedFuture(TelegramSignInNeeded(reason))
    }

    /**
     * An authentication that supplies nothing, and records that a person is needed.
     *
     * Asked for only when the session directory holds no usable session — so reaching this is
     * itself the answer: there is nothing to read the source with, and the one route to a session
     * is a person at a console. Refusing here rather than later is what keeps the platform from
     * being asked to send a code, which is the part of this that cannot be undone.
     */
    private fun refusingToSignIn() = AuthenticationSupplier<AuthenticationData> {
        val reason = "no session exists yet, and the service never signs in; run the login mode"
        awaitingAPerson = reason
        CompletableFuture.failedFuture(TelegramSignInNeeded(reason))
    }

    /** An update taken off the library's stream and not yet handed on. */
    private sealed interface Pending {

        /** A message arrived whole, which is the ordinary case and needs nothing fetched. */
        data class Arrived(val message: TdApi.Message) : Pending

        /** A message changed, and the update says so without saying what it now reads. */
        data class Edited(val chatId: Long, val messageId: Long) : Pending

        /** Messages were removed, which carries identifiers because there is nothing else left. */
        data class Withdrawn(val chatId: Long, val messageIds: List<Long>) : Pending
    }

    companion object {

        /**
         * Load the native library, then open the session.
         *
         * The two are one step on purpose: the library cannot be used before its compiled half is
         * loaded, loading it twice in a process is a no-op, and a constructor that could be
         * reached without it would fail with a linkage error rather than a sentence.
         */
        fun opened(session: TelegramSession): TdlibClient {
            Init.init()
            quietenTheLibrary()
            return TdlibClient(session)
        }
    }
}

/**
 * Sign in on the machine, interactively, and leave the session behind.
 *
 * Re-authentication is the one thing this source needs a human for, and it is done out of band —
 * so this is a mode of the ingest process rather than anything the service reaches: it reads the
 * console, writes the session into the same directory the service then uses, and exits. The
 * service itself refuses every prompt, which is why running this is the only way a session comes
 * to exist.
 *
 * It returns once the library can answer who is signed in, because that is the first question
 * that cannot be answered before authorisation has finished — and only then does it leave the
 * marker the service waits on. A sign-in that failed leaves nothing behind, so a service waiting
 * on it goes on waiting rather than opening a session that cannot read.
 *
 * **The marker is written after the client is closed, and the order is load-bearing.** A waiting
 * service opens its own client within seconds of seeing it, and the library locks its store — so
 * a marker written while this process still held the lock would hand the service a session
 * directory it cannot open, which costs a crash and a restart to recover from.
 */
internal fun signInOnConsole(session: TelegramSession) {
    Init.init()
    quietenTheLibrary()
    SimpleTelegramClientFactory().use { factory ->
        factory.builder(settingsFor(session))
            .build(AuthenticationSupplier.consoleLogin())
            .use { client -> client.getMeAsync().join() }
    }
    session.recordSignedIn()
}

/**
 * What it takes to open one session: the application's credential, the account, and where the
 * library keeps its store.
 *
 * Our own type rather than the library's settings object, so that the composition root can read
 * this out of the environment without importing a protocol type — which is the rule that keeps
 * Telegram inside this package.
 */
internal data class TelegramSession(
    val apiId: Int,
    val apiHash: String,
    val phoneNumber: String,
    val directory: Path,
    val chatListLimit: Int = DEFAULT_CHAT_LIST_LIMIT,
) {

    /**
     * Whether a session has been signed in here, readable without opening one.
     *
     * **Why a file of our own rather than the library's.** The library writes its store the moment
     * it is initialised, which happens whether or not anyone signs in — so the presence of its
     * database says only that a process once started. This file is written after the platform has
     * confirmed who is signed in, so it says the one thing a waiting service needs to know.
     *
     * And it has to be readable *without* opening a client, because the library locks its store:
     * a service that probed by opening one would hold the lock the login needs. Waiting on a file
     * leaves the directory free for exactly as long as the wait lasts.
     */
    fun isSignedIn() = Files.isRegularFile(directory.resolve(SIGNED_IN))

    /** Record that a sign-in completed here, which is the whole of what the marker says. */
    fun recordSignedIn() {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve(SIGNED_IN), "")
    }

    private companion object {
        /**
         * The marker's name.
         *
         * Inside the session directory on purpose: the one thing that must never be true is a
         * marker surviving a session it does not describe, and a directory that is wiped, moved
         * or mounted elsewhere takes the marker with it.
         */
        const val SIGNED_IN = "signed-in"
    }
}

/**
 * How many chats one request asks the library for.
 *
 * Both a bound and a page, because the two calls that take it differ: the enumeration asks once
 * and gets at most this many — the point past which a chat list is larger than anything this
 * build was shaped for, and where it says so rather than being silently half-read — while the
 * load loop asks repeatedly and this is how much each round may add.
 */
private const val DEFAULT_CHAT_LIST_LIMIT = 5_000

/**
 * Turn the compiled library's own logging down to what is worth reading.
 *
 * Measured rather than guessed: at its default level the deployed container writes two lines
 * every two seconds for the empty poll alone, plus a full dump of every update object — so the
 * one thing the deploy's operating notes say to check, whether a recent pass moved anything, is the thing
 * the log makes impossible to find. At errors only, what remains is what someone would act on.
 *
 * Set once per process, before a client exists, because it is global to the native library.
 */
private fun quietenTheLibrary() = Log.setVerbosityLevel(ERRORS_ONLY)

/** The protocol's own scale: 0 fatal, 1 errors, 2 warnings, 3 informational — which is default. */
private const val ERRORS_ONLY = 1

/**
 * The library's settings for one session.
 *
 * Three of these are decisions rather than defaults. The message database is on, because it is
 * what lets the library answer a history request it has already seen without going to the server
 * — the budget this whole build is shaped around. The chat information database is on for the
 * same reason, one level up. The file database is off: nothing here downloads a file, and a cache
 * of them in a container's volume would grow without anything reading it.
 */
private fun settingsFor(session: TelegramSession): TDLibSettings =
    TDLibSettings.create(APIToken(session.apiId, session.apiHash)).apply {
        databaseDirectoryPath = session.directory
        downloadedFilesDirectoryPath = session.directory.resolve("files")
        isMessageDatabaseEnabled = true
        isChatInfoDatabaseEnabled = true
        isFileDatabaseEnabled = false
    }

/** The protocol's refusal, as one of the three failures the seam declares. */
private fun TelegramError.translated(): Exception = when (errorCode) {
    TOO_MANY_REQUESTS -> TelegramFloodWait(statedWait() ?: UNSTATED_WAIT)
    UNAUTHORIZED -> TelegramSignInNeeded(errorMessage)
    NOT_FOUND -> NothingThere(errorMessage)
    else -> TelegramRequestFailed(errorMessage, this)
}

/**
 * The wait the protocol stated, read out of the message it states it in.
 *
 * There is no field for it: the duration arrives inside the text, as a trailing number of
 * seconds. `null` when the text does not carry one, which is answered with a wait of our own
 * rather than with no wait at all — the one thing that must not happen on a stated limit is
 * asking again straight away.
 */
private fun TelegramError.statedWait(): Duration? =
    errorMessage?.substringAfterLast(' ')?.toIntOrNull()?.seconds

/** A thing the protocol says is not there, which is a different answer from a refusal. */
private class NothingThere(reason: String?) : TelegramRequestFailed(reason)

/**
 * How many load rounds one chat list gets before the loop gives up and says so.
 *
 * Generous on purpose: at the limit each round asks for, this is further than any list this build
 * was shaped for, so reaching it means the protocol is answering without progressing.
 */
private val LOG = LoggerFactory.getLogger(TdlibClient::class.java)

private const val LOAD_ROUNDS = 20

private const val TOO_MANY_REQUESTS = 429
private const val UNAUTHORIZED = 401
private const val NOT_FOUND = 404

/** What to wait when a limit is stated without a duration. Long enough to be a pause, not a stall. */
private val UNSTATED_WAIT = 30.seconds
