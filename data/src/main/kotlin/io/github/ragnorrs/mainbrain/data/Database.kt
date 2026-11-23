package io.github.ragnorrs.mainbrain.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.SQLException

/**
 * The one way into Postgres, and the only file here that knows a pool exists.
 *
 * Three things are deliberately bundled into one type, because they are the same concern seen
 * from three sides:
 *
 * **The pool.** Opening a connection costs a TCP connect, a handshake, authentication, and a
 * forked backend process on the server -- Postgres is process-per-connection. Paying that per
 * query would put tens of milliseconds on every read. The pool also bounds concurrency against
 * a server with a hard `max_connections` ceiling, shared by every process in the stack.
 *
 * **The migrations.** Flyway runs once, here, against the same pool, so the schema a
 * repository queries is the schema this build shipped rather than whatever the volume happens
 * to hold.
 *
 * **The dispatcher hop.** JDBC blocks, and there is no asynchronous driver in play, so every
 * call hops to [Dispatchers.IO] -- which is what lets repository functions be `suspend` and
 * mean it. The pool size and that dispatcher are the same budget seen twice: threads blocking
 * on a connection that will not come are the cost this bounds.
 *
 * A repository takes a [Database] and never a `DataSource`, so there is no route to a
 * connection that does not pass through here.
 *
 * **[query], [transaction] and [pinned] are `internal`, and that is the seam rather than a
 * convention.** All three hand a `java.sql.Connection` to a lambda, whose type is inferred and so
 * never written down — which means no source rule can recognise `database.query { it.prepareStatement(…) }`
 * in another module, because there is no spelling of it to match. Visibility makes it a compile
 * error instead. Repositories are in this module and see them; the test source set is a friend
 * compilation and sees them; a composition root outside needs only [migrated] and [close].
 *
 * The consequence to know: a write spanning two repositories cannot be wrapped in a transaction
 * from outside `:data`. Such a writer belongs in this module, beside the repositories it
 * composes.
 */
class Database private constructor(private val source: HikariDataSource) : AutoCloseable {

    /**
     * Borrow a connection, run [block] on the I/O dispatcher, and return the connection to the
     * pool.
     *
     * Autocommit is on, so this is one statement committed on its own. Anything that writes
     * more than one row's worth of meaning wants [transaction] instead.
     */
    internal suspend fun <T> query(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        source.connection.use(block)
    }

    /**
     * As [query], but [block] runs inside one transaction: committed if it returns, rolled
     * back if it does not.
     *
     * This is what makes a record and the claims read off it one write. Two separate calls
     * would be two transactions, and a failure between them would leave a record whose
     * identifiers were never stored -- invisible to the matcher rather than absent from it.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun <T> transaction(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        source.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (failure: Throwable) {
                // Throwable rather than SQLException, because a mapping error in the block has
                // to roll back as surely as a constraint violation does.
                //
                // Not for cancellation: `block` does not suspend, so there is no suspension
                // point inside the transaction for a cancellation to arrive at. One racing the
                // commit is delivered at the `withContext` boundary, after the commit has
                // already happened, and nothing here can refuse it.
                connection.rollback()
                throw failure
            }
        }
    }

    /**
     * Borrow one connection and hold it for the whole of [block], which may suspend.
     *
     * The seam a session-level advisory lock needs, and the one thing [query] and [transaction]
     * cannot offer: both borrow a connection and return it when their lambda ends, while such a
     * lock lives on the session that took it and is released when that session goes. A lock
     * taken through [query] would therefore be released the instant it was granted.
     *
     * **This is the only thing here that holds a connection while suspended**, so it is also the
     * only thing that can exhaust the pool by waiting rather than by working. A caller that pins
     * one is holding it against everything else the process wants to read, including whatever
     * runs inside [block] — so a pool must be sized for the pins as well as the work, and
     * [DEFAULT_POOL_SIZE] is the work alone.
     *
     * Nothing is committed or rolled back on the way out: a caller of this is running statements
     * for their effect on the session rather than on any row.
     *
     * **A block that failed does not get its connection returned to the pool, and that is load
     * bearing rather than hygiene.** Session state outlives a pooled connection's `close`, which
     * hands it back rather than ending the session — so a holder that set some and could not
     * unset it would put the connection back still carrying it, to be handed to whoever asks
     * next. For an advisory lock that is the one unrecoverable shape: the lock stays held by a
     * pooled session nobody is using, and the pass it guards never runs again for the life of
     * the process. Evicting instead ends the session, and Postgres releases what it held.
     */
    // `SwallowedException` is suppressed for the `close` below and nowhere else here: the rule is
    // right that losing an exception usually loses the diagnosis, and wrong for this one, because
    // the work it would be attributed to had already succeeded. The reasoning is at the catch.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    internal suspend fun <T> pinned(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        val connection = source.connection
        val result = try {
            block(connection)
        } catch (failure: Throwable) {
            // Throwable rather than Exception, and cancellation is the case that makes it
            // necessary: a sweep stopped mid-run is exactly when a lock has been taken and its
            // release may not have run.
            source.evictConnection(connection)
            throw failure
        }
        // **Swallowed rather than rethrown, and that is the whole point of it being out here.**
        // By this line the block has returned: a holder that set session state has already unset
        // it, and a sweep that got here finished. Letting a failing `close` out would hand the
        // caller an exception for work that succeeded — the driver counts that as a failed sweep
        // and says so — which is the one thing a connection's return has no business causing.
        // Evicted rather than left, because a connection that could not be handed back is one
        // whose session should end instead of going back into the pool carrying whatever stopped
        // it. Nothing is logged because nothing in this module logs; the eviction is the repair,
        // and there is nothing for a caller to do about it.
        try {
            connection.close()
        } catch (failure: SQLException) {
            source.evictConnection(connection)
        }
        result
    }

    override fun close() = source.close()

    companion object {
        /**
         * How many connections one process holds, for the work it does.
         *
         * Small on purpose. Two processes reach this database and the server's ceiling is
         * shared; a pool sized for throughput nobody has measured is how a prototype exhausts
         * `max_connections` and reports it as a database fault.
         *
         * It does not cover a connection held by [pinned], which is idle for as long as its
         * holder is working. A process that pins one adds to this rather than spending from it.
         */
        const val DEFAULT_POOL_SIZE = 4

        /** Flyway's own default, stated so that moving the files cannot silently migrate nothing. */
        private const val MIGRATIONS = "classpath:db/migration"

        /**
         * Open the pool and bring the schema up to date, in that order.
         *
         * Migrating here rather than from a separate command is what makes a deploy one step:
         * the process that needs the schema is the process that applies it, so there is no
         * state in which the containers are running against a schema nobody ran.
         */
        fun migrated(
            url: String,
            user: String,
            password: String,
            poolSize: Int = DEFAULT_POOL_SIZE,
        ): Database {
            val config = HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = poolSize
                poolName = "main-brain"
            }
            val source = HikariDataSource(config)
            Flyway.configure().dataSource(source).locations(MIGRATIONS).load().migrate()
            return Database(source)
        }
    }
}
