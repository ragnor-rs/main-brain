package io.github.ragnorrs.mainbrain.ingest.config

import io.github.ragnorrs.mainbrain.ingest.source.telegram.TelegramLimits
import io.github.ragnorrs.mainbrain.ingest.source.telegram.TelegramSession
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything this process is told, read once, at the start.
 *
 * **Fail-fast, and blank counts as missing.** Compose interpolates an unset variable to an empty
 * string, so a service that treated `""` as a value would start with an empty credential and
 * report the resulting refusal as a platform failure. A process that cannot be configured says
 * which variable it wanted and stops, which is a line in a log rather than a mystery in the
 * health row.
 *
 * **The whole of it is read here and nowhere else.** No part of the build reads the environment
 * on its own: a value read at the point of use is a value nobody can see the shape of, and the
 * one thing a reader of a container wants is the list of what it needs to run.
 *
 * No model credential, and the type cannot express one. This container holds a live session, so
 * the absence is enforced in three places that have to agree — the deployment, the architecture
 * rules, and this.
 */
internal data class IngestConfig(
    val store: StoreConfig,
    val ingesting: Boolean,
    val session: TelegramSession,
    val excludedChats: Set<Long>,
    val onlyChats: Set<Long>,
    val limits: TelegramLimits,
) {

    companion object {

        /**
         * Read the configuration, or say what is missing.
         *
         * The reader is a parameter so that this can be exercised without an environment, which
         * is also why nothing here calls `System.getenv` directly.
         */
        fun fromEnv(env: (String) -> String? = System::getenv) = IngestConfig(
            store = StoreConfig(
                url = env.required("DATABASE_URL"),
                user = env.required("DATABASE_USER"),
                password = env.required("DATABASE_PASSWORD"),
            ),
            // Whether to read a source at all, and on unless a deployment says otherwise — the
            // product backfills and then refreshes daily, and this does not soften that.
            //
            // It is operational, and the lock is what it is for: the client library locks the
            // session directory it is given, so every deployment pointed at the same directory
            // competes for one session, and a stack brought up to exercise a branch has to be
            // able to leave it to whichever stack is ingesting. Pointed at directories of their
            // own, two stacks contend for nothing and each wants a sign-in of its own instead,
            // which is a budget rather than a lock. Either way the second stack is the one that
            // does not need to read.
            ingesting = env.flag("INGEST_ENABLED") ?: DEFAULT_INGESTING,
            session = TelegramSession(
                apiId = env.required("TELEGRAM_API_ID").toIntOrNull()
                    ?: error("TELEGRAM_API_ID must be the number from the application registration."),
                apiHash = env.required("TELEGRAM_API_HASH"),
                // The account, read by the sign-in mode and by nothing else. The service is
                // deliberately given no number: handing one to the client library is what makes
                // it submit one, and submitting one is what makes the platform send a code — so
                // a service that did it would spend a sign-in attempt every time it started, on
                // a code nobody can type. Required all the same, because it names which account
                // the session must be, and the mode that needs it is the one that matters.
                phoneNumber = env.required("TELEGRAM_PHONE"),
                directory = Path.of(env.optional("TELEGRAM_SESSION_DIRECTORY") ?: DEFAULT_SESSION_DIRECTORY),
            ),
            // Two by requirement: the assistant's own chat and the project coordination group,
            // both of which hold this system's output rather than anyone's business. They are
            // identifiers rather than titles because a title is renameable by anyone in the
            // group, and the exclusion that matters most is the one that must not lapse quietly.
            excludedChats = env.chats("TELEGRAM_EXCLUDED_CHATS"),
            // The inverse, and empty almost always: naming conversations here reads those and
            // nothing else, which is how a first run is kept to a handful of them rather than an
            // archive. An exclusion still wins, so the two need not know about each other.
            onlyChats = env.chats("TELEGRAM_ONLY_CHATS"),
            limits = TelegramLimits(
                betweenPages = env.seconds("TELEGRAM_SECONDS_BETWEEN_PAGES") ?: DEFAULT_BETWEEN_PAGES,
                // A source time, in the form a source time is written everywhere else. Absent
                // means as far back as the archive allows, which is the default this build wants:
                // capture is bounded by request budget, and a shallower read is a decision to
                // spend less of it rather than a property of the corpus.
                reachBackTo = env.sourceTime("TELEGRAM_REACH_BACK_TO"),
            ),
        )

        private fun ((String) -> String?).required(name: String): String =
            this(name)?.takeIf { it.isNotBlank() }
                ?: error("$name is not set. The ingest process reads it at startup and cannot run without it.")

        private fun ((String) -> String?).optional(name: String): String? =
            this(name)?.takeIf { it.isNotBlank() }

        /**
         * On or off, refusing anything that is neither rather than reading as unset.
         *
         * The same reader `:backend` uses, and the same reason: a switch spelled `no`, `0` or
         * `off` would, taken leniently, mean *carry on* — so a deployment that meant to leave the
         * session alone would read the source anyway, and whoever set the variable would have no
         * way to tell that from the switch not working.
         *
         * Duplicated rather than shared because the two processes read their configuration in
         * their own file by design; the duplication is three lines and the seam it would need is
         * a module.
         */
        private fun ((String) -> String?).flag(name: String): Boolean? = optional(name)?.let { value ->
            when (value.lowercase()) {
                "true" -> true
                "false" -> false
                else -> error("$name must be true or false; '$value' is neither.")
            }
        }

        /**
         * Chat identifiers, refusing anything that is not one.
         *
         * **Absent and malformed are not the same answer.** An unset list means nothing is
         * excluded, which is a state this build is in today. A list with a typo in it would,
         * taken leniently, mean *this chat is no longer excluded* — and three separate comments
         * in this codebase say the exclusion that matters most is the one that must not lapse
         * quietly. So an entry that is not an identifier stops the process, naming both the
         * variable and the entry.
         */
        private fun ((String) -> String?).chats(name: String): Set<Long> = optional(name)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { entry ->
                entry.toLongOrNull()
                    ?: error("$name must be chat identifiers separated by commas; '$entry' is not one.")
            }
            ?.toSet()
            .orEmpty()

        /** A whole number of seconds, refusing anything else rather than reading as unset. */
        private fun ((String) -> String?).seconds(name: String): Duration? = optional(name)?.let { value ->
            value.toIntOrNull()?.seconds ?: error("$name must be a whole number of seconds; '$value' is not one.")
        }

        /**
         * A source time, in the one form every source time in this build is written.
         *
         * Parsed here rather than where it is used so that the failure names the variable. The
         * platform's own exception names only the text, which in a container log is a date with
         * no indication of which setting it came from.
         */
        private fun ((String) -> String?).sourceTime(name: String): Instant? = optional(name)?.let { value ->
            runCatching { Instant.parse(value) }.getOrNull()
                ?: error("$name must be an instant such as 2024-01-01T00:00:00Z; '$value' is not one.")
        }

        /**
         * Where the client library keeps its own store.
         *
         * A default rather than a required value, because this is a property of the image and not
         * of the installation: it is the path the container mounts a named volume at, and a
         * session file is a logged-in account, so it lives in a volume and is never copied.
         */
        private const val DEFAULT_SESSION_DIRECTORY = "/var/lib/mainbrain/session"

        /**
         * On, so that a deployment never told about the switch reads its source as before.
         *
         * The default has to be the product's behaviour and not the convenient one: continuous
         * capture is what this system is, and a corpus that quietly stopped growing looks exactly
         * like a quiet week.
         */
        private const val DEFAULT_INGESTING = true

        /** Deliberately unambitious; see [TelegramLimits]. */
        private val DEFAULT_BETWEEN_PAGES: Duration = 2.seconds
    }
}

/** How to reach the store. The same three values every process in this stack is given. */
internal data class StoreConfig(
    val url: String,
    val user: String,
    val password: String,
)
