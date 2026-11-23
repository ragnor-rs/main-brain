package io.github.ragnorrs.mainbrain.ingest.source.telegram

import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.IdentifierType
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Platform shapes to records: the identifier rules, the taxonomy, and what is excluded.
 *
 * These are pure functions, so nothing here stands a session or a store up. That is the point of
 * the shapes they map from — the rules worth testing are naming rules, and a test that had to
 * construct protocol objects to reach one would mostly be testing the construction.
 */
class RecordsTest {

    private val sentAt = Instant.parse("2026-02-01T10:00:00Z")

    private fun chat(id: Long, kind: TelegramConversationKind = TelegramConversationKind.DIRECT) =
        TelegramConversation(chatId = id, kind = kind)

    private fun message(chatId: Long, messageId: Long) =
        TelegramMessage(chatId = chatId, messageId = messageId, sentAt = sentAt)

    private fun topic(chatId: Long, topicId: Long, title: String? = null) = TelegramConversation(
        chatId = chatId,
        topicId = topicId,
        topicOf = chatId,
        kind = TelegramConversationKind.TOPIC,
        title = title,
    )

    /**
     * The rule whose absence corrupts rather than impoverishes.
     *
     * Message numbering restarts in every chat, so the same bare number occurs in all of them.
     * Were it handed over unqualified, two unrelated messages would share one key and the corpus
     * would fuse them — which reads as ordinary data.
     */
    @Test
    fun `the same message number in two chats yields two identifiers`() {
        val first = message(chatId = 100, messageId = 7).externalId()
        val second = message(chatId = 200, messageId = 7).externalId()

        assertEquals(ExternalId(Source.TELEGRAM, "100/7"), first)
        assertEquals(ExternalId(Source.TELEGRAM, "200/7"), second)
    }

    /**
     * Qualified by the chat and not by the topic, because the platform numbers across the group.
     *
     * Including the topic would give one message two identifiers as it moved between them, and
     * moving a message between topics is something the platform allows — so the corpus would hold
     * it twice and count it twice.
     */
    @Test
    fun `a message keeps one identifier across the topics of its group`() {
        val inOneTopic = message(chatId = 500, messageId = 42).copy(topicId = 11).externalId()
        val inAnother = message(chatId = 500, messageId = 42).copy(topicId = 22).externalId()

        assertEquals(inOneTopic, inAnother)
    }

    @Test
    fun `a topic is a conversation of its own, inside the chat containing it`() {
        val topic = topic(chatId = 500, topicId = 11, title = "a client track")

        val record = topic.toRecord()

        assertEquals(ExternalId(Source.TELEGRAM, "500/11"), record.externalId)
        assertEquals(ConversationKind.TOPIC, record.kind)
        assertEquals(ExternalId(Source.TELEGRAM, "500"), record.parent)
    }

    /**
     * Two topics in one forum must not collapse into one conversation.
     *
     * The chat identifier alone would do exactly that, and what is lost is the taxonomy the
     * client actually works by — recoverable only by re-reading the source, never by re-deriving.
     */
    @Test
    fun `two topics in one chat are two conversations`() {
        val first = topic(chatId = 7, topicId = 1)
        val second = topic(chatId = 7, topicId = 2)

        assertFalse(first.externalId() == second.externalId())
    }

    @Test
    fun `a flat conversation has no parent and is named by its chat alone`() {
        val record = chat(31, TelegramConversationKind.GROUP).toRecord()

        assertEquals(ExternalId(Source.TELEGRAM, "31"), record.externalId)
        assertNull(record.parent)
    }

    @Test
    fun `a work unit is spelled the same as the conversation it names`() {
        val topic = topic(chatId = 9, topicId = 4)

        assertEquals(topic.externalId().nativeId, topic.workUnit().value)
    }

    @Test
    fun `every platform kind maps to a record kind`() {
        val mapped = TelegramConversationKind.entries.map { chat(1, it).toRecord().kind }

        assertEquals(
            listOf(
                ConversationKind.DIRECT,
                ConversationKind.GROUP,
                ConversationKind.CHANNEL,
                ConversationKind.TOPIC,
            ),
            mapped,
        )
    }

    @Test
    fun `a message points at its author's person record rather than resolving it`() {
        val record = message(1, 2).copy(authorId = 77).toRecord()

        assertEquals(ExternalId(Source.TELEGRAM, "77"), record.author)
        assertEquals(sentAt, record.sourceTime)
    }

    @Test
    fun `a message with no author is ordinary`() {
        assertNull(message(1, 2).toRecord().author)
    }

    /**
     * The edit time is read off the platform and never stored.
     *
     * Whether an edit moves content time is the store's decision, taken by comparing what it
     * holds against what arrives. An adapter reporting an edit time as well would be a second
     * opinion on a question already answered, and the two could disagree.
     */
    @Test
    fun `an edited message carries its original time and nothing about the edit`() {
        val edited = message(1, 2).copy(editedAt = Instant.parse("2026-03-01T00:00:00Z"), text = "changed")

        val record = edited.toRecord()

        assertEquals(sentAt, record.sourceTime)
        assertEquals("changed", record.text)
    }

    @Test
    fun `a profile's handles and phone become normalised claims`() {
        val person = TelegramPerson(
            userId = 5,
            displayName = "a counterparty",
            handles = listOf("AnAccount"),
            phone = "+995 555 10 20 30",
        )

        val claims = person.toRecord(account = null).identifiers

        assertTrue(Identifier(IdentifierType.HANDLE, "anaccount") in claims)
        assertTrue(claims.any { it.type == IdentifierType.PHONE })
    }

    /**
     * A value the normaliser refuses is dropped, not stored raw.
     *
     * A claim that cannot match is worse than no claim: whatever reconciles contacts later counts
     * it as evidence, so a national-format number nobody can resolve a country for would be
     * weighed as though it identified someone.
     */
    @Test
    fun `a claim the normaliser refuses is dropped rather than stored`() {
        val person = TelegramPerson(userId = 5, handles = listOf("  "), phone = "555 10 20 30")

        assertEquals(emptyList<Identifier>(), person.toRecord(account = null).identifiers)
    }

    @Test
    fun `a profile carries no source time`() {
        assertNull(TelegramPerson(userId = 5).toRecord(account = null).sourceTime)
    }

    /**
     * The account the session reads through is marked, and no other account is.
     *
     * Both halves in one test because the pair is the claim: a mapping that marked everything true
     * would pass a test that only looked at the account's own record, and a corpus where every
     * person is the reader is worse than one where nobody is.
     */
    @Test
    fun `the account the session reads through is the only record marked`() {
        val own = TelegramPerson(userId = 5, displayName = "the account this was read through")
        val counterparty = TelegramPerson(userId = 6, displayName = "a counterparty")

        assertEquals(true, own.toRecord(account = 5).isSelf)
        assertEquals(false, counterparty.toRecord(account = 5).isSelf)
    }

    /**
     * A session that would not say leaves the mark unset rather than answering *no*.
     *
     * `false` here would be a corpus asserting of every person that they are not the one it was
     * read through — indistinguishable from one where the question was answered and nobody is.
     */
    @Test
    fun `a session that did not say which account it reads through leaves the mark unset`() {
        assertNull(TelegramPerson(userId = 5).toRecord(account = null).isSelf)
    }

    /**
     * The mark is not a claim, which is what keeps it away from the matcher.
     *
     * A claim shared by two records is scored as evidence that they are one human, weighted by how
     * few records carry the value — so a claim carried by exactly the accounts read through would
     * be the rarest signal there is. The assertion is that marking a profile changes nothing about
     * what it advertises.
     */
    @Test
    fun `marking the account adds nothing to what a profile claims`() {
        val person = TelegramPerson(userId = 5, handles = listOf("anaccount"), phone = "+995555102030")

        assertEquals(person.toRecord(account = null).identifiers, person.toRecord(account = 5).identifiers)
    }

    @Test
    fun `an excluded chat excludes its conversations and its messages`() {
        val excluded = ExcludedConversations(setOf(900))

        assertTrue(chat(900) in excluded)
        assertTrue(message(900, 1) in excluded)
        assertFalse(chat(901) in excluded)
        assertFalse(message(901, 1) in excluded)
    }

    /**
     * Exclusion is by chat, so a forum cannot re-enter the corpus one topic at a time.
     *
     * Each topic has an identifier the excluded set does not contain, so a rule written against
     * conversation identifiers rather than chats would let a coordination group that grew topics
     * back in without anything reporting it.
     */
    @Test
    fun `excluding a chat excludes every topic inside it`() {
        val excluded = ExcludedConversations(setOf(900))
        val topic = topic(chatId = 900, topicId = 3)

        assertTrue(topic in excluded)
        assertTrue(excluded.excludes(topic.workUnit()))
    }

    @Test
    fun `a work unit from an unexcluded chat is not excluded`() {
        val excluded = ExcludedConversations(setOf(900))

        assertFalse(excluded.excludes(chat(901).workUnit()))
    }

    /**
     * A unit that does not parse as a chat is not treated as excluded.
     *
     * Nothing in this build writes such a unit, so this is about what a hand-edited row or a
     * later migration leaves behind: reading it as excluded would quietly stop reading a
     * conversation, which is the failure that looks like success.
     */
    @Test
    fun `a work unit that names no chat is not excluded`() {
        assertFalse(ExcludedConversations(setOf(900)).excludes(WorkUnitId("nonsense")))
    }

    /**
     * The same unit, the opposite answer, once a list of what to read is in force.
     *
     * Read one way round, a unit nobody can parse must not be dropped — that would quietly stop
     * reading a conversation. Read the other, a unit nobody can match against the list is not one
     * of the few that were asked for, and reading it would quietly widen a bounded run. Both
     * halves are about not doing something silently, which is why the answer flips.
     */
    @Test
    fun `a work unit that names no chat is excluded once an allowlist is in force`() {
        val allowlist = ExcludedConversations(chatIds = emptySet(), onlyChatIds = setOf(100))

        assertTrue(allowlist.excludes(WorkUnitId("nonsense")))
        assertFalse(allowlist.excludes(WorkUnitId("100")))
        assertTrue(allowlist.excludes(WorkUnitId("200")))
    }

    /**
     * A named chat the platform never offered is reported, because it looks like a quiet one.
     *
     * An allowlist is typed by hand from identifiers that came from somewhere else, and the
     * platform marks a group's identifier differently from a bare one — so an unconverted entry
     * reads nothing at all and nothing says why.
     */
    @Test
    fun `an allowlist entry the enumeration never offered is named`() {
        val allowlist = ExcludedConversations(chatIds = emptySet(), onlyChatIds = setOf(100, 200))

        assertEquals(setOf(200L), allowlist.namedButNotListed(listed = setOf(100, 300)))
        assertEquals(emptySet<Long>(), allowlist.namedButNotListed(listed = setOf(100, 200)))
    }

    /** Nothing was named, so nothing can be missing — and no warning may claim otherwise. */
    @Test
    fun `with no allowlist nothing is reported as missing`() {
        assertEquals(emptySet<Long>(), ExcludedConversations(setOf(900)).namedButNotListed(emptySet()))
    }
}
