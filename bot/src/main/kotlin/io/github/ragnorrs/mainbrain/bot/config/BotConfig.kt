package io.github.ragnorrs.mainbrain.bot.config

import io.github.ragnorrs.mainbrain.bot.port.SenderId
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Everything this process is told, read once, at the start.
 *
 * **Fail-fast, and blank counts as missing.** Compose interpolates an unset variable to an empty
 * string, so a service that treated `""` as a value would start with an empty shared secret and
 * an allowlist admitting nobody — and would report both as a platform failure rather than as its
 * own misconfiguration. A process that cannot be configured says which variable it wanted and
 * stops.
 *
 * **The whole of it is read here and nowhere else**, and the list of what it reads is the one thing
 * a reader of this container wants.
 *
 * **Split because the parts have different futures.** [backend] is how this process reaches the one
 * service it talks to and says nothing about chat at all; [telegram] is the platform's own;
 * [zone] is the corpus's and belongs to neither. A second interface would read the first and the
 * third unchanged and bring its own second.
 *
 * No database credential, no model credential, and the type cannot express either. This process
 * holds neither, which the deployment, the architecture rules and this all have to agree on.
 */
internal data class BotConfig(
    val backend: BackendSettings,
    val telegram: TelegramSettings,
    val zone: ZoneId,
) {

    companion object {

        /**
         * Read the configuration, or say what is missing.
         *
         * The reader is a parameter so that this can be exercised without an environment, which
         * is also why nothing here calls `System.getenv` directly.
         */
        fun fromEnv(env: (String) -> String? = System::getenv) = BotConfig(
            backend = BackendSettings(
                // A service name on the Compose network rather than something an installation
                // chooses, so this is read here and set only in `docker-compose.yml`. Required
                // all the same: there is no useful default for the one service this process
                // exists to call, and a bot that cannot reach the backend can answer nothing.
                url = env.required("BACKEND_URL"),
                // The one hop this process makes. Required, with no development default, for the
                // reason the backend states on the other side of it: a fallback value is a
                // published value, and the surface it guards answers questions about somebody's
                // whole archive.
                secret = env.required("BACKEND_SECRET"),
            ),
            telegram = TelegramSettings(
                token = env.required("TELEGRAM_BOT_TOKEN"),
                allowed = env.senders("ALLOWED_TELEGRAM_IDS"),
            ),
            zone = env.zone("CORPUS_TIME_ZONE"),
        )

        private fun ((String) -> String?).required(name: String): String =
            this(name)?.takeIf { it.isNotBlank() }
                ?: error("$name is not set. The bot reads it at startup and cannot run without it.")

        /**
         * The calendar the corpus's own material is dated in, defaulting rather than refusing.
         *
         * **The one variable here that has a meaningful absence.** A citation states the day a
         * message was written, and an instant has no day until a zone says so; UTC is the zone the
         * material has always been dated in, so it is the behaviour being kept rather than a
         * fallback being guessed at. Everything else this reads is a credential or an address,
         * where a default would be a published value or a wrong guess.
         *
         * **Named the same as the backend's because it is the same fact**, not a second one this
         * process gets to hold a different opinion about: a reply that dates a message one way in
         * its prose and another in its sources is one defect wearing two variables.
         *
         * **A region rather than an offset**, for the reason the backend gives where it reads this:
         * a fixed offset dates material wrongly wherever summer time is observed, in the direction
         * that moves a day boundary, which is the one thing a date here exists to get right. Either
         * is accepted, since a deployment that knows it wants an offset may say so.
         *
         * Refused on a typo, because restoring UTC silently shows a reader a date that is a day out
         * on some material and not on other — which reads as the answer being wrong rather than as
         * a variable being misspelt.
         */
        private fun ((String) -> String?).zone(name: String): ZoneId {
            val value = this(name)?.takeIf { it.isNotBlank() } ?: return ZoneOffset.UTC
            return runCatching { ZoneId.of(value) }.getOrNull()
                ?: error("$name must be a time zone such as Europe/Lisbon or UTC; '$value' is not one.")
        }

        /**
         * Who may be served, refusing anything that is not an identifier and refusing an empty
         * list.
         *
         * **Empty is a failure rather than a default.** An allowlist admitting nobody is a bot
         * that ignores every message, which from outside is indistinguishable from one that is
         * down — and it is what an unset variable interpolates to. So the deployment that forgot
         * this stops here, naming the variable, instead of going quiet in a way somebody has to
         * read a log to explain.
         *
         * **A malformed entry stops the process too.** Taken leniently it would mean *this person
         * is no longer admitted*, so the owner's own identifier with a typo in it would read as a
         * bot that has started ignoring them. Which is the same symptom as an empty list, for a
         * different cause, and neither should be guessed at.
         *
         * Parsed as a number and carried as the string of one: the platform's identifiers are
         * numbers, so an entry that is not one is a mistake worth naming, and going back through
         * `toString` is what normalises the surrounding spaces away so comparison is exact.
         */
        private fun ((String) -> String?).senders(name: String): Set<SenderId> {
            val entries = (this(name)?.takeIf { it.isNotBlank() } ?: error(empty(name)))
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val senders = entries.map { entry ->
                val identifier = entry.toLongOrNull()
                    ?: error("$name must be Telegram identifiers separated by commas; '$entry' is not one.")
                SenderId(identifier.toString())
            }
            return senders.takeIf { it.isNotEmpty() }?.toSet() ?: error(empty(name))
        }

        private fun empty(name: String) =
            "$name admits nobody. The bot enforces its allowlist at the edge, so an empty one is " +
                "a service that ignores every message — which reads from outside exactly like a " +
                "service that is down. Name at least one identifier."
    }
}

/** How to reach the backend, and how it knows this is the interface it serves. */
internal data class BackendSettings(val url: String, val secret: String)

/**
 * The platform's own: the bot's token, and who it answers.
 *
 * The allowlist is here rather than beside the backend's settings because admitting a person is a
 * property of the platform this interface is on — these are Telegram identifiers, and a second
 * interface would hold its own list of whatever its platform calls a person.
 */
internal data class TelegramSettings(val token: String, val allowed: Set<SenderId>)
