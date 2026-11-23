package io.github.ragnorrs.mainbrain.bot.port

/**
 * Who sent something, as whatever platform delivered it names them.
 *
 * **A string rather than the number Telegram uses, and that is the whole point.** The identity a
 * platform hands over has its own shape — a number here, a telephone number elsewhere — and the
 * one thing this build does with an identity is compare it against a list, which needs nothing of
 * its shape. Typed to one platform's shape, every signature it passes through would have to change
 * to serve a second interface: the guard, the configuration, and every test of either.
 *
 * Normalised by whoever parses it, so that comparison is exact. Nothing here trims or reformats.
 */
@JvmInline
internal value class SenderId(val value: String)

/** Where a reply goes back to, in the same spirit as [SenderId]. */
@JvmInline
internal value class ChatRef(val value: String)

/**
 * One thing a person said to the assistant, in this process's own vocabulary.
 *
 * What every chat platform has, and nothing else. No platform object, no update identifier, no
 * entity offsets — a message the core can act on, so that the only code knowing which platform
 * delivered it is the adapter that built this.
 *
 * **Inbound is not a port, deliberately.** Nothing here is pulled from: the adapter owns however
 * messages arrive — long polling on this platform, a posted webhook on another — and calls the
 * handler with one of these. A port the core polled would make the arrival mechanism the core's
 * business and would not survive a platform that pushes.
 *
 * [locale] is the language tag the platform reported for the sender, and null when it reported
 * none. A tag rather than a platform user object, because resolving a key against a catalogue
 * needs the language and nothing else.
 *
 * [directChat] is whether this is a one-to-one conversation. It is here rather than inferred later
 * because the guard reads it: an assistant that answers in a group shows one person's archive to
 * whoever else is in the room, and that is an authorisation decision rather than a rendering one.
 */
internal data class IncomingMessage(
    val sender: SenderId,
    val chat: ChatRef,
    val text: String,
    val locale: String?,
    val directChat: Boolean,
)
