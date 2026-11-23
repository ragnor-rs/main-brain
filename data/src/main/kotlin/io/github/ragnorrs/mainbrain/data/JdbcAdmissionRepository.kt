package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.Admission
import io.github.ragnorrs.mainbrain.domain.entity.AdmissionRepository
import io.github.ragnorrs.mainbrain.domain.entity.Standing
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import java.sql.ResultSet

class JdbcAdmissionRepository(private val database: Database) : AdmissionRepository {

    /**
     * One row per conversation, with the newer judgement replacing the older.
     *
     * `judged_at` is written by the conflict branch as well as by the insert, because a verdict
     * reached today under a reworded instruction is not dated to whenever the first one was — and
     * the column is the only thing that could say when the standing answer was arrived at.
     */
    override suspend fun record(admissions: List<Admission>) {
        if (admissions.isEmpty()) return
        database.transaction { connection ->
            connection.prepareStatement(UPSERT).use { statement ->
                admissions.forEach { admission ->
                    statement.bind {
                        externalId(admission.conversation)
                        string(admission.standing.name)
                        string(admission.version)
                    }
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    override suspend fun of(conversations: List<ExternalId>): List<Admission> {
        if (conversations.isEmpty()) return emptyList()
        return database.query { connection ->
            connection.prepareStatement(SELECT_BY_RECORD).use { statement ->
                val wanted = conversations.distinct()
                statement.bind {
                    strings(wanted.map { it.source.name })
                    strings(wanted.map { it.nativeId })
                }
                statement.executeQuery().use { rows -> rows.collect { it.toAdmission() } }
            }
        }
    }

    private companion object {
        val UPSERT = """
            INSERT INTO client_admission (record_source, record_native_id, standing, version)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (record_source, record_native_id) DO UPDATE SET
                standing = EXCLUDED.standing,
                version = EXCLUDED.version,
                judged_at = now()
        """.trimIndent()

        val SELECT_BY_RECORD = """
            SELECT a.record_source, a.record_native_id, a.standing, a.version
            FROM client_admission a
            JOIN unnest(?::text[], ?::text[]) AS wanted (source, native_id)
              ON a.record_source = wanted.source AND a.record_native_id = wanted.native_id
        """.trimIndent()
    }
}

private fun ResultSet.toAdmission(): Admission = Admission(
    conversation = requireNotNull(externalId("record_source", "record_native_id")),
    standing = Standing.valueOf(getString("standing")),
    version = getString("version"),
)
