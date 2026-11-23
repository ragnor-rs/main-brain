package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.answer.Grouping
import io.github.ragnorrs.mainbrain.domain.answer.GroupingKey
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import java.sql.ResultSet

/**
 * The body of work behind a record, as SQL.
 *
 * **One statement rather than a walk, because every leg of it is a join on the same two tables.**
 * The record's conversation, that conversation's client, its container's client where it has no name
 * of its own, and for a one-to-one the person on the other side and what they write in elsewhere —
 * each is an outer join away from the row the record is on. Resolving them in Kotlin would be four
 * round trips per request on the path where somebody is waiting for an answer.
 *
 * **Which of them wins is decided here and not in the statement.** The order of preference is the
 * port's contract and the kind of key each leg produces is part of it: a `COALESCE` would hand back
 * a label with nothing to say whether it names a body of work or a person, and that distinction is
 * the one the answer must not get wrong.
 */
class JdbcGrouping(private val database: Database) : Grouping {

    override suspend fun of(records: List<ExternalId>): Map<ExternalId, GroupingKey> {
        if (records.isEmpty()) return emptyMap()
        val wanted = records.distinct()
        return database.query { connection ->
            connection.prepareStatement(BEHIND).use { statement ->
                statement.bind {
                    strings(wanted.map { it.source.name })
                    strings(wanted.map { it.nativeId })
                }
                statement.executeQuery().use { rows ->
                    rows.collect { row -> row.toRecord() to row.toKey() }
                        .mapNotNull { (record, key) -> key?.let { record to it } }
                        .toMap()
                }
            }
        }
    }

    private companion object {

        /**
         * Everything that could place one record, in one row per record.
         *
         * **A record that is not a message is absent, and that is the join doing it.** Spans are
         * read out of messages and out of things inferred from messages; a document span would be
         * neither, and there is nothing in a document to say which body of work it is about until
         * a source that has them arrives.
         *
         * **A direct conversation is identified by the person it is with**, which is why the
         * counterpart legs join `person` on the conversation's own identifier. A platform that
         * numbers a two-person chat by the account on the other side is what makes that true, and
         * it is the same equality the contact-to-thread read relies on.
         *
         * **The counterpart's single body of work is a grouped read with `HAVING`**, not a join: the
         * question is whether every body of work they write inside is the same one, which is a fact
         * about the whole group rather than about any row in it. A plain join would return one row
         * per thread and the first of them would look like an answer.
         *
         * **Counted in bodies of work and not in clients** — see [bodyOfWork] for what reduces one
         * to the other. A body of work keeping a thread per strand of it is several clients, so
         * counting clients puts somebody who works inside one of them under their own name instead,
         * which is a grouping lost to the label rather than to anything about their work.
         *
         * **The reduction decides the count and never the label.** The label stays one the corpus
         * carries — a strand's own, `min` of the group's, the same as for a body of work kept in one
         * conversation. Emitting the reduced leading name instead would head this section by a name
         * no conversation has and no reader could look up. It would not make the reply's headings
         * agree either: the legs above project each strand's label in full, so material from the
         * rooms themselves is already headed per strand, and a reduced key here would be a third
         * heading rather than the one that joins them. Joining them is the model's, from labels it
         * can read, which is why the read hands it one of those.
         *
         * **`min` is filtered by the same expression the predicate counts, because `min` passes over
         * a null name and not over a name that reduces to nothing.** A label of nothing but
         * delimiters is no body of work, so it does not decide the count — and punctuation sorts
         * first, so unfiltered it would not merely compete for the heading but win it, putting a
         * non-label in front of a reader and losing the name of the work the section is about.
         *
         * **Grouped over every counterpart in the request at once rather than correlated per record,
         * because `message` carries no index on the pair it filters on.** Written as a lateral it was
         * re-executed once per retrieved record, and each execution was a sequential scan of the whole
         * table: measured at forty records, forty scans and 4788 buffer hits, which was most of the
         * statement's time. Postgres did not memoize it, so the correlation cost what it looked like
         * it cost. Collecting the counterparts first and grouping once makes it one pass whatever the
         * answer is built from — which is the same shape the sibling read of this fact already uses,
         * and it needs no index to be the better plan, so the schema keeps the position that an index
         * guessed at is a write cost paid for nothing.
         *
         * **The grouped read applies the container rule too, which is why its client joins are outer.**
         * Where somebody writes is a conversation like any other, so one carrying no label of its own
         * counts as the container that has one — the same rule as the outer legs, in the one place it
         * would otherwise have been dropped. Counting the body of work behind `coalesce` of the two
         * keeps a conversation with no body of work at all out of the count rather than failing it,
         * because `count` passes over a null — and so does a label that reduces to nothing: writing
         * somewhere unlabelled is not evidence of working across several.
         *
         * A counterpart with nothing to place is absent from the grouped read rather than present and
         * null, and the outer join is what turns that back into no body of work — the same answer the
         * correlated form gave by returning no row. A counterpart who was never captured as a person
         * never reaches it at all.
         *
         * Keyed on the person *record* rather than on the merged contact, so a human writing as two
         * accounts is counted as whatever each account writes. Following the merge links would need
         * the cluster resolved first, and the case it would change is a one-to-one with somebody
         * whose other account works on something else — rare enough to be worth a sentence here
         * rather than a second query on the answering path.
         */
        val BEHIND = """
            WITH asked (source, native_id) AS (
                SELECT * FROM unnest(?::text[], ?::text[])
            ),
            placed AS (
                SELECT asked.source,
                       asked.native_id,
                       cv.kind              AS conversation_kind,
                       cv.name              AS conversation_name,
                       cv.source            AS conversation_source,
                       cv.native_id         AS conversation_native_id,
                       cv.parent_source,
                       cv.parent_native_id,
                       mate.source          AS mate_source,
                       mate.native_id       AS mate_native_id,
                       mate.display_name    AS counterpart_name
                FROM asked
                JOIN message m ON m.source = asked.source AND m.native_id = asked.native_id
                JOIN conversation cv ON cv.id = m.conversation_id
                LEFT JOIN person mate ON cv.kind = '${ConversationKind.DIRECT.name}'
                                     AND mate.source = cv.source
                                     AND mate.native_id = cv.native_id
            ),
            mates AS (
                SELECT DISTINCT mate_source AS source, mate_native_id AS native_id
                FROM placed
                WHERE mate_source IS NOT NULL
            ),
            wrote_in AS (
                -- One row per counterpart-and-client rather than one per message: the aggregates
                -- below ask which bodies of work somebody wrote in and never how often, so the
                -- whole scan would be sorted to answer a question about a handful of pairs.
                SELECT DISTINCT
                       theirs.author_source    AS source,
                       theirs.author_native_id AS native_id,
                       coalesce(theirs_own.name, theirs_container.name) AS label,
                       ${bodyOfWork("coalesce(theirs_own.name, theirs_container.name)")} AS body
                FROM message theirs
                JOIN mates ON theirs.author_source = mates.source
                          AND theirs.author_native_id = mates.native_id
                JOIN conversation said_in ON said_in.id = theirs.conversation_id
                LEFT JOIN admitted_client theirs_own ON theirs_own.record_source = said_in.source
                                           AND theirs_own.record_native_id = said_in.native_id
                LEFT JOIN admitted_client theirs_container ON said_in.name IS NULL
                                                 AND theirs_container.record_source = said_in.parent_source
                                                 AND theirs_container.record_native_id = said_in.parent_native_id
            ),
            counterpart AS (
                SELECT source,
                       native_id,
                       min(label) FILTER (WHERE body IS NOT NULL) AS client
                FROM wrote_in
                GROUP BY source, native_id
                HAVING count(DISTINCT lower(body)) = 1
            )
            SELECT placed.source,
                   placed.native_id,
                   placed.conversation_kind AS conversation_kind,
                   placed.conversation_name AS conversation_name,
                   own.name                 AS own_client,
                   container.name           AS container_client,
                   counterpart.client       AS counterpart_client,
                   placed.counterpart_name  AS counterpart_name
            FROM placed
            LEFT JOIN admitted_client own ON own.record_source = placed.conversation_source
                                AND own.record_native_id = placed.conversation_native_id
            LEFT JOIN admitted_client container ON placed.conversation_name IS NULL
                                      AND container.record_source = placed.parent_source
                                      AND container.record_native_id = placed.parent_native_id
            LEFT JOIN counterpart ON counterpart.source = placed.mate_source
                                 AND counterpart.native_id = placed.mate_native_id
        """.trimIndent()
    }
}

private fun ResultSet.toRecord(): ExternalId =
    requireNotNull(externalId("source", "native_id"))

/**
 * The key this row settles on, or `null` if nothing on it places the record.
 *
 * In order of how directly each leg answers the question. A conversation people share is its own
 * body of work; one with no name of its own is part of the container that has one; and a one-to-one
 * takes the body of work of the person on the other side, falling back to that person — which is a
 * different kind of key and not a weaker version of the same one.
 *
 * The first two cannot both answer: the container is only joined for a conversation carrying no
 * label, so *which* of them wins is settled in the statement and reading them in this order here is
 * a statement of the rule rather than the thing deciding it. One place decides, and it is the join.
 *
 * The last leg reads the conversation's own name, and only for a one-to-one. What a two-person chat
 * displays is the counterparty rendered by the platform, so it names the same person the leg above
 * it does; it is reached when the person record behind the chat was never captured, which leaves
 * the name the only thing that can say who the material is with. For anything else a name with no
 * client behind it means recognition has not been over it yet, and answering from the name would be
 * asserting a body of work this system has not read.
 */
private fun ResultSet.toKey(): GroupingKey? {
    val direct = getString("conversation_kind") == ConversationKind.DIRECT.name
    val bodyOfWork = labelled("own_client")
        ?: labelled("container_client")
        ?: labelled("counterpart_client")
    return when {
        bodyOfWork != null -> GroupingKey.OfBodyOfWork(bodyOfWork)
        !direct -> null
        else -> (labelled("counterpart_name") ?: labelled("conversation_name"))
            ?.let { GroupingKey.OfPerson(it) }
    }
}

/**
 * The label in [column], or `null` where there is none worth showing.
 *
 * **Blank counts as none, on every leg rather than on the last one.** A heading with nothing in it
 * places material no better than an absent label does and reads as a defect rather than as the
 * ordinary case absence is. Applied per leg so that one blank column cannot shadow a later leg that
 * does have a name: a person row carrying an empty display name would otherwise stop the chat's own
 * name from being reached, and the material would go unplaced while something could have placed it.
 */
private fun ResultSet.labelled(column: String): String? = getString(column)?.takeIf { it.isNotBlank() }
