package io.github.ragnorrs.mainbrain.bot.adapter

import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.send.sendActionTyping
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.longPollingFlow
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.abstracts.WithOptionalLanguageCode
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.FromUserMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.update.MessageUpdate
import dev.inmo.tgbotapi.types.update.abstracts.Update
import dev.inmo.tgbotapi.utils.telegramBotAPIDefaultUrl
import io.github.ragnorrs.mainbrain.bot.port.ChatRef
import io.github.ragnorrs.mainbrain.bot.port.IncomingMessage
import io.github.ragnorrs.mainbrain.bot.port.MessagingPort
import io.github.ragnorrs.mainbrain.bot.port.Reply
import io.github.ragnorrs.mainbrain.bot.port.SenderId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * The only file in this process that knows what Telegram is.
 *
 * Everything above it works in this module's own vocabulary — [IncomingMessage], [Reply],
 * [ChatRef] — so the platform appears once, which `:archtest` asserts over the source rather than
 * leaving to a reviewer. A second interface is another file like this one and nothing else.
 *
 * **It owns how messages arrive, and the core does not.** Long polling is a property of this
 * platform: the bot connects out, which is why this process publishes no port and needs no
 * inbound hole. Another platform delivers by webhook, and the only thing that would change is
 * this file, because what the core exposes is a function taking one message rather than a port it
 * polls.
 *
 * **One coroutine per message.** An answer takes tens of seconds for most shapes and minutes for
 * the one that searches the open web, which this waits out by collecting it rather than by holding
 * a request — so handling updates in sequence would hold the next question behind the last one for
 * that long. Launched into a scope the caller owns, so stopping the process stops the work rather
 * than orphaning it.
 */
internal class TelegramAdapter(
    token: String,
    private val scope: CoroutineScope,
    apiUrl: String = telegramBotAPIDefaultUrl,
) : MessagingPort {

    init {
        // Before the client exists, because the first thing it logs may already carry the token:
        // the Bot API puts the credential in the request path, so a failed call prints it.
        routeLibraryLogs()
    }

    /**
     * The platform, or a stub standing in for it.
     *
     * [apiUrl] defaults to the real service and is overridden in one place: the case that drives
     * [send] against a server of its own. That case exists because this file is the only thing
     * that turns a reply into the messages a reader receives — the splitting and the footer's
     * attachment are arranged here and nowhere else — so without it the whole of that wiring
     * could be deleted with every suite still green, which is what happened to the formatting
     * this adapter now sends.
     */
    private val bot = telegramBot(token, apiUrl = apiUrl)

    /**
     * Send one reply, in as many messages as the platform's limit requires.
     *
     * Splitting happens here because the limit is Telegram's. A reply short enough to fit — which
     * every reply this build composes about itself is — goes as one message.
     *
     * **A footer rides on the last message rather than becoming one**, which only something that
     * knows where the cuts fall can arrange — see [messages]. It is the reason the port carries it
     * as a field instead of the renderer appending it to the body.
     *
     * **The parse mode is this file's business and the markup is not.** A reply body carries the
     * little markup this interface renders, already escaped — composed by the renderer or carried
     * by a catalogue entry; what belongs here is the one thing that is genuinely the platform's —
     * which dialect it is to be read as. Sent without a mode
     * the tags would be shown to a reader as the characters they are, which is the failure this
     * line prevents.
     */
    override suspend fun send(chat: ChatRef, reply: Reply) {
        messages(reply.body, reply.footer).forEach { piece ->
            bot.sendTextMessage(identifier(chat), piece, parseMode = HTMLParseMode)
        }
    }

    /**
     * Who this process is, asked of the platform, which is also what proves the token works.
     *
     * **Called before anything is received, so a bad token stops the process.** Without it the
     * refusal arrives inside the polling loop, where the library logs it and carries on: the
     * container stays up, reports nothing wrong, and answers no message ever — which is the state
     * hardest to tell from a working deployment. One call at startup turns that into a process
     * that stops and says why.
     *
     * Throws rather than returning a failure, because the one caller is an entry point whose job
     * is to stop.
     */
    suspend fun identify(): String {
        val me = bot.getMe()
        // Without the leading marker the platform's own type carries, so a caller can present it
        // however it likes rather than having to strip one.
        //
        // The platform types a username as optional, and a bot without one is not a thing this
        // build expects — but the identifier answers the question either way, which is *which
        // bot did this connect as*.
        return me.username?.withoutAt ?: me.id.chatId.long.toString()
    }

    /**
     * Receive messages until the scope is cancelled, handing each to [handle].
     *
     * Anything that is not a person sending text is dropped before the guard sees it: an edit, a
     * photo, a service message, a bot's own post. Not a refusal — there is nothing here to
     * answer, and treating it as one would reply to a sticker.
     */
    suspend fun receive(handle: suspend (IncomingMessage) -> Unit) {
        bot.longPollingFlow().collect { update ->
            incoming(update)?.let { message ->
                scope.launch { attend(message, handle) }
            }
        }
    }

    /**
     * Handle one message, showing that something is happening while it is handled.
     *
     * The indicator matters more here than it would elsewhere: an answer takes a model call, and
     * without it the assistant looks dead for the whole of it. The platform's own signal lapses
     * after a few seconds, so it is refreshed until the work finishes rather than sent once.
     *
     * A failure is logged and swallowed. The alternative is a coroutine dying with an exception
     * nobody reads, and one unanswerable message must not stop the interface.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun attend(message: IncomingMessage, handle: suspend (IncomingMessage) -> Unit) {
        val typing = typing(message.chat)
        try {
            handle(message)
        } catch (failure: Exception) {
            log.error("This message could not be handled, and the interface goes on.", failure)
        } finally {
            typing.cancel()
        }
    }

    /** Keeps the platform's *working on it* signal alive for as long as the work takes. */
    private fun typing(chat: ChatRef): Job = scope.launch {
        while (isActive) {
            // Uncaught failures here would be a dead indicator on a live answer, which is not
            // worth losing the answer over.
            runCatching { bot.sendActionTyping(identifier(chat)) }
            delay(TYPING_REFRESH_MILLIS)
        }
    }

    /**
     * One update as this module's own message, or null when there is nothing to answer.
     *
     * Four things have to hold: it is a new message, it carries content, that content is text, and
     * a person sent it. Anything else is dropped. The identifiers become strings here because that
     * is the shape everything above this file works in, and this is where a platform's own shapes
     * stop.
     */
    private fun incoming(update: Update): IncomingMessage? {
        val message = (update as? MessageUpdate)?.data as? ContentMessage<*>
        val text = (message?.content as? TextContent)?.text?.takeIf { it.isNotBlank() }
        val sender = (message as? FromUserMessage)?.user
        return if (message == null || text == null || sender == null) {
            null
        } else {
            IncomingMessage(
                sender = SenderId(sender.id.chatId.long.toString()),
                chat = ChatRef(message.chat.id.chatId.long.toString()),
                text = text,
                // Not every kind of user carries one, and the platform omits it for anyone whose
                // client never reported one — which the catalogue reads as *use the floor*.
                locale = (sender as? WithOptionalLanguageCode)?.languageCode,
                // A one-to-one conversation. Everything else is a room, which the guard refuses.
                directChat = message.chat is PrivateChat,
            )
        }
    }

    private fun identifier(chat: ChatRef) = ChatId(RawChatId(chat.value.toLong()))

    private companion object {

        private val log = LoggerFactory.getLogger(TelegramAdapter::class.java)

        /** The platform's indicator lapses after about five seconds, so it is renewed inside that. */
        private const val TYPING_REFRESH_MILLIS = 4_000L
    }
}
