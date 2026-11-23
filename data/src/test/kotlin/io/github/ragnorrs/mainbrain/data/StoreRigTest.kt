package io.github.ragnorrs.mainbrain.data

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the store rig works at all: the pinned image starts, the migrations apply to it, and the
 * vector extension the image is pinned *for* is present afterwards.
 *
 * None of this asserts anything about a repository. What it asserts is the thing every store
 * test rests on and that nobody finds out about until it fails: that Docker is reachable from
 * wherever the suite runs, that the image is the right one, and that Flyway found the migration
 * directory rather than silently migrating nothing. A repository test discovering any of that
 * for the first time would read as a defect in the repository under test.
 */
class StoreRigTest {

    @Test
    fun `the migrations apply and leave pgvector installed`(): Unit = runBlocking {
        // Constructing the rig runs Flyway; this reads the result back out of the catalogue.
        val extension = StoreRig.database.query { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT extname FROM pg_extension WHERE extname = 'vector'").use {
                    if (it.next()) it.getString(1) else null
                }
            }
        }
        assertEquals("vector", extension) {
            "The migrations ran but left no vector extension behind. The image tag says " +
                "pgvector; this is what checks the extension is in it and that `V1` asked for it."
        }
    }

    /**
     * The corpus tables are derived from [RecordTable], so a kind added without a table fails
     * here. The derived ones cannot be: nothing in Kotlin enumerates them, so they are named.
     * A table listed here and absent from a migration is what this is for.
     */
    @Test
    fun `every record table the corpus needs exists`(): Unit = runBlocking {
        val tables = StoreRig.database.query { connection ->
            connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            ).use { statement ->
                statement.executeQuery().use { rows -> rows.collect { it.getString(1) }.toSet() }
            }
        }
        val wanted = RecordTable.entries.map { it.table }.toSet() + DERIVED + "identifier"
        assertTrue(tables.containsAll(wanted)) {
            "Flyway reported success but the schema is missing ${wanted - tables}. A migration " +
                "directory that resolves to nothing migrates nothing and says so to no one."
        }
    }

    /**
     * No migration removes anything, now or later.
     *
     * Comments are stripped before the scan rather than excluded by wording. A pattern that had
     * to avoid matching the prose in `V1` would be a check anchored on how that file happens to
     * be written, and rewording the file would turn the check off without anyone noticing.
     */
    @Test
    fun `no migration removes anything`() {
        // The module directory, which is where Gradle runs a test from. Asserted rather than
        // assumed: a resolved path that turns out to be empty would pass this test over nothing.
        val directory = File("src/main/resources/db/migration")
        assertTrue(directory.isDirectory) {
            "No migration directory at ${directory.absolutePath}. This test scans the migrations, " +
                "so not finding them has to fail rather than pass."
        }
        val migrations = directory.listFiles { file -> file.extension == "sql" }?.toList().orEmpty()
        assertTrue(migrations.isNotEmpty()) { "No migrations found in ${directory.absolutePath}." }

        val destructive = Regex(
            """\b(DELETE\s+FROM|TRUNCATE|DROP\s+(TABLE|COLUMN|SCHEMA)|ON\s+DELETE)\b""",
            RegexOption.IGNORE_CASE,
        )
        migrations.forEach { migration ->
            val statements = migration.readText().replace(Regex("--[^\n]*"), "")
            val found = destructive.findAll(statements).map { it.value }.toList()
            assertTrue(found.isEmpty()) {
                "${migration.name} contains $found. Sources report removals differently or not " +
                    "at all, and an archive cannot report one — absence is indistinguishable " +
                    "from a capture that came back incomplete. A removal is never an instruction."
            }
        }
    }

    private companion object {
        /** The derived tables, which no Kotlin enumeration covers. */
        val DERIVED = setOf(
            "span",
            "derivation",
            "contact",
            "client",
            "client_admission",
            "merge_link",
            "separation",
            "candidate",
            "statement",
            "statement_evidence",
            "commitment",
            "event",
        )
    }
}
