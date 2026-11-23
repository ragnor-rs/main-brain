package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Source
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.atomic.AtomicLong

/**
 * One Postgres, migrated once, shared by every store test in this module.
 *
 * Started here rather than through the JUnit annotation because the annotation's lifecycle is
 * per test class, and starting a container and running the migrations for each class costs
 * seconds per class for no isolation anyone needs. Testcontainers reaps this one when the
 * virtual machine exits.
 *
 * **Isolation comes from unique identifiers, not from emptying tables between tests.** There is
 * no truncate here and no delete anywhere, which is the same rule the production code follows
 * — a test helper that cleared the corpus would be the one code path able to remove a record,
 * and it would be in the module whose central claim is that none exists. [externalId] mints a
 * fresh key per call instead, so two tests writing "a person" are writing two person records.
 */
internal object StoreRig {

    /** The image the whole project pins. The migrations ask for the extension it carries. */
    const val IMAGE = "pgvector/pgvector:pg16"

    private val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .also { it.start() }

    /** Migrated on first touch: constructing this is what runs Flyway. */
    val database: Database = Database.migrated(postgres.jdbcUrl, postgres.username, postgres.password)

    private val minted = AtomicLong()

    /**
     * An external identifier no other test holds.
     *
     * Counted rather than randomised, so a failure names the same record on a re-run.
     */
    fun externalId(label: String): ExternalId = ExternalId(Source.TELEGRAM, "$label-${minted.incrementAndGet()}")
}
