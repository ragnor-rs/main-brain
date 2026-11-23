package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.CandidateMerge
import io.github.ragnorrs.mainbrain.domain.entity.Client
import io.github.ragnorrs.mainbrain.domain.entity.Contact
import io.github.ragnorrs.mainbrain.domain.entity.ContactRepository
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.MergeLink
import io.github.ragnorrs.mainbrain.domain.entity.Separation
import io.github.ragnorrs.mainbrain.domain.entity.Signal
import io.github.ragnorrs.mainbrain.domain.entity.SignalKind
import io.github.ragnorrs.mainbrain.domain.recognition.nameKey
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.IdentifierType
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.Person
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The identity half of the entity layer, against a real Postgres.
 *
 * Not a unit test, because what is being claimed is held by the schema rather than by Kotlin.
 * That an identifier survives a second pass is an `ON CONFLICT` clause that does not name a
 * column; that a merge destroys nothing is two rows still being there; that resolving a chain
 * terminates is a recursive query. A fake would assert that the fake does these.
 *
 * Three reads here exist only for matching and are the ones easiest to get subtly wrong: finding
 * a record from a value it claims has to bring back the claims that *did not* find it, counting
 * names has to normalise them exactly as the matcher does, and the hard negative has to see two
 * accounts in one room without seeing one account twice.
 *
 * **The rig is shared and nothing is emptied between tests**, so every case here works with
 * freshly minted identifiers and filters what it reads back to its own. A name is minted too:
 * counting how many records carry one is a question about the whole table.
 */
class RecognitionStoreTest {

    /**
     * A limit no case here can be truncated by.
     *
     * The rig is shared and never emptied, so every kept pair any case in this file wrote is still
     * there. A case that filters the report down to its own pair needs a limit above all of them,
     * or the ordering silently drops its row and the assertion fails for a reason that has nothing
     * to do with what it is testing. The limit itself is asserted on its own.
     */
    private val allOfThem = 10_000

    private val contacts = JdbcContactRepository(StoreRig.database)

    /**
     * The advisory read, which is its own port and its own class.
     *
     * Separate from [contacts] on purpose: it can do nothing but read, which is what makes the
     * surface it serves unable to write rather than merely not writing.
     */
    private val pairs = JdbcUnresolvedPairs(StoreRig.database)
    private val clients = JdbcClientRepository(StoreRig.database)
    private val persons = JdbcPersonRepository(StoreRig.database)
    private val conversations = JdbcConversationRepository(StoreRig.database)
    private val messages = JdbcMessageRepository(StoreRig.database)

    @Test
    fun `a contact round-trips with the record it was made from`(): Unit = runBlocking {
        val record = StoreRig.externalId("contact")
        val contact = Contact(EntityId.mint(), record, "A Display Name")

        contacts.upsert(listOf(contact))

        assertEquals(listOf(contact), contacts.of(listOf(record)))
    }

    @Test
    fun `a record that already has a contact keeps the identity it was given`(): Unit = runBlocking {
        // The whole of re-run safety. The pass mints an identifier for every record in its batch
        // because it cannot know which are new; if the store took the offered one, every pass
        // would renumber every contact and nothing downstream could hold onto one.
        val record = StoreRig.externalId("stable")
        val first = Contact(EntityId.mint(), record, "A Display Name")
        contacts.upsert(listOf(first))

        contacts.upsert(listOf(Contact(EntityId.mint(), record, "A Changed Name")))

        val stored = contacts.of(listOf(record)).single()
        assertEquals(first.id, stored.id, "The contact was renumbered by a second pass over an unchanged record.")
        assertEquals("A Changed Name", stored.displayName, "A renamed profile left the stored name behind.")
    }

    @Test
    fun `a client round-trips with the conversation it was read from`(): Unit = runBlocking {
        val record = StoreRig.externalId("client")
        val client = Client(EntityId.mint(), record, "A Body Of Work")

        clients.upsert(listOf(client))

        assertEquals(listOf(client), clients.of(listOf(record)))
    }

    @Test
    fun `a conversation that already has a client keeps the identity it was given`(): Unit = runBlocking {
        // Re-run safety for the other entity the identity stage assigns, and it needs saying
        // separately: the stage mints an identifier for every conversation in its batch because it
        // cannot know which are new, and a version bump hands it the whole corpus again. If the
        // store took the offered one, re-deriving identity would renumber every client — and an
        // answer grouped by one would be grouped by a different thing afterwards.
        val record = StoreRig.externalId("stable-client")
        val first = Client(EntityId.mint(), record, "A Body Of Work")
        clients.upsert(listOf(first))

        clients.upsert(listOf(Client(EntityId.mint(), record, "A Renamed Body Of Work")))

        val stored = clients.of(listOf(record)).single()
        assertEquals(first.id, stored.id, "The client was renumbered by a second pass over an unchanged conversation.")
        assertEquals("A Renamed Body Of Work", stored.name, "A renamed conversation left the stored name behind.")
    }

    @Test
    fun `a merge links one contact to another and rewrites neither`(): Unit = runBlocking {
        val (one, other) = twoContacts()

        contacts.merge(listOf(MergeLink(one.id, other.id, score = 1.2, signals = listOf(phone()))))

        assertEquals(other.id, contacts.headOf(one.id))
        assertEquals(other.id, contacts.headOf(other.id), "A head should resolve to itself.")
        assertEquals(
            setOf(one, other),
            contacts.of(listOf(one.record, other.record)).toSet(),
            "A merge rewrote or removed a contact. Both sides keep their rows; one gains a link.",
        )
    }

    @Test
    fun `a cluster comes back whole, including the members nobody asked about`(): Unit = runBlocking {
        // What the constraint check turns on. `heads` answers for its inputs and nothing else, so
        // a pass that resolved only its own batch would never see a record merged on an earlier
        // one — and a hard negative against that record forbids a join the batch can still make.
        val (one, other) = twoContacts()
        val third = Contact(EntityId.mint(), StoreRig.externalId("third"), "A Third Display Name")
        contacts.upsert(listOf(third))
        // A chain rather than a star, so the walk has to be transitive in both directions:
        // `one` resolves through `other` to `third`.
        contacts.merge(
            listOf(
                MergeLink(one.id, other.id, score = 1.2, signals = listOf(phone())),
                MergeLink(other.id, third.id, score = 1.2, signals = listOf(phone())),
            ),
        )

        val asked = contacts.clusters(listOf(one.id)).single()

        assertEquals(third.id, asked.head, "The cluster is named by the head the chain resolves to.")
        assertEquals(
            setOf(one, other, third),
            asked.members.toSet(),
            "Asking about one member returned ${asked.members.size} of a cluster of three. The " +
                "members not asked about are exactly the ones a cannot-link constraint needs.",
        )
    }

    @Test
    fun `a contact nothing has merged is a cluster of one`(): Unit = runBlocking {
        val alone = Contact(EntityId.mint(), StoreRig.externalId("alone"), "A Display Name")
        contacts.upsert(listOf(alone))

        val cluster = contacts.clusters(listOf(alone.id)).single()

        assertEquals(alone.id, cluster.head)
        assertEquals(listOf(alone), cluster.members, "Every input belongs to exactly one group.")
    }

    @Test
    fun `two clusters asked about together come back apart`(): Unit = runBlocking {
        val (one, other) = twoContacts()
        contacts.merge(listOf(MergeLink(one.id, other.id, score = 1.2, signals = listOf(phone()))))
        val unrelated = Contact(EntityId.mint(), StoreRig.externalId("unrelated"), "Another Display Name")
        contacts.upsert(listOf(unrelated))

        val groups = contacts.clusters(listOf(one.id, unrelated.id))

        assertEquals(
            setOf(setOf(one.id, other.id), setOf(unrelated.id)),
            groups.map { group -> group.members.map { it.id }.toSet() }.toSet(),
            "Two humans came back as one group, so a constraint between them would read as " +
                "internal to a cluster that was never joined.",
        )
    }

    @Test
    fun `a stored link keeps the evidence it was made on`(): Unit = runBlocking {
        val (one, other) = twoContacts()
        val evidence = listOf(phone(), Signal(SignalKind.NAME, 0.35))
        contacts.merge(listOf(MergeLink(one.id, other.id, score = 1.35, signals = evidence)))

        // A later pass scoring the same pair differently must not rewrite why the merge happened.
        contacts.merge(listOf(MergeLink(one.id, other.id, score = 9.0, signals = emptyList())))

        val link = assertNotNull(contacts.linkOf(one.id))
        assertEquals(1.35, link.score, TOLERANCE, "A stored link was rescored by a later pass.")
        assertEquals(evidence, link.signals, "The signals behind a merge did not survive the round trip.")
    }

    @Test
    fun `unmerging restores both sides and forbids the pair from merging again`(): Unit = runBlocking {
        // Removing the link alone is not enough: the evidence that carried the merge is still in
        // the corpus and still says what it said, so the next pass would merge them straight
        // back. A correction outranks re-recognition, and the separation is how it does.
        val (one, other) = twoContacts()
        contacts.merge(listOf(MergeLink(one.id, other.id, score = 1.2, signals = listOf(phone()))))

        contacts.unmerge(one.id)

        assertEquals(one.id, contacts.headOf(one.id), "The contact still resolved to the head it was unmerged from.")
        assertNull(contacts.linkOf(one.id))
        assertEquals(
            listOf(Separation.of(one.id, other.id)),
            contacts.separations(listOf(one.id, other.id)),
            "Unmerging recorded no separation, so the next pass is free to merge the pair again.",
        )
    }

    @Test
    fun `unmerging a contact that was never merged records nothing`(): Unit = runBlocking {
        val (one, other) = twoContacts()

        contacts.unmerge(one.id)

        assertTrue(
            contacts.separations(listOf(one.id, other.id)).isEmpty(),
            "Unmerging a head invented a constraint between two contacts nothing had joined.",
        )
    }

    @Test
    fun `a kept candidate is rescored as the evidence grows`(): Unit = runBlocking {
        val (one, other) = twoContacts()
        contacts.keep(listOf(CandidateMerge.of(one.id, other.id, score = 0.35, signals = emptyList())))

        contacts.keep(listOf(CandidateMerge.of(one.id, other.id, score = 0.95, signals = listOf(phone()))))

        val kept = contacts.candidates(listOf(one.id)).single { it.involves(one.id) }
        assertEquals(
            0.95,
            kept.score,
            TOLERANCE,
            "A candidate kept its first score. It is a running assessment, not a decision taken.",
        )
        assertEquals(listOf(phone()), kept.signals)
    }

    @Test
    fun `a candidate pair is found from either side`(): Unit = runBlocking {
        val (one, other) = twoContacts()
        contacts.keep(listOf(CandidateMerge.of(one.id, other.id, score = 0.5, signals = emptyList())))

        // Which side a pair is stored on is an ordering detail, and a claim arriving for one
        // record has to find it either way round.
        assertEquals(1, contacts.candidates(listOf(one.id)).count { it.involves(one.id) })
        assertEquals(1, contacts.candidates(listOf(other.id)).count { it.involves(other.id) })
    }

    @Test
    fun `a pair is ordered the way the store orders it, not the way Java does`(): Unit = runBlocking {
        // Java compares a UUID as two signed longs; Postgres compares its sixteen bytes
        // unsigned. These two straddle the sign bit, so Kotlin and SQL sort them in opposite
        // orders, and ordering the pair by `UUID.compareTo` made the `CHECK` reject it.
        //
        // Built from one random identifier with the top bit cleared and set, rather than from
        // two literals: the rig is shared, so a fixed identifier would collide with itself on a
        // second run through the same container.
        val seed = EntityId.mint().value
        val topBitClear = EntityId(UUID(seed.mostSignificantBits and Long.MAX_VALUE, seed.leastSignificantBits))
        val topBitSet = EntityId(UUID(seed.mostSignificantBits or Long.MIN_VALUE, seed.leastSignificantBits))
        assertTrue(
            topBitSet.value < topBitClear.value && topBitClear < topBitSet,
            "This case is only worth anything if the two orderings disagree on the pair, and " +
                "they no longer do — so it would pass under the bug it exists to catch.",
        )
        contacts.upsert(
            listOf(
                Contact(topBitClear, StoreRig.externalId("clear"), "A Display Name"),
                Contact(topBitSet, StoreRig.externalId("set"), "Another Display Name"),
            ),
        )

        contacts.keep(listOf(CandidateMerge.of(topBitSet, topBitClear, score = 0.5, signals = emptyList())))
        contacts.merge(listOf(MergeLink(topBitSet, topBitClear, score = 1.2, signals = listOf(phone()))))
        contacts.unmerge(topBitSet)

        assertEquals(1, contacts.candidates(listOf(topBitClear)).count { it.involves(topBitClear) })
        assertEquals(
            listOf(Separation.of(topBitSet, topBitClear)),
            contacts.separations(listOf(topBitClear, topBitSet)),
        )
    }

    @Test
    fun `a person's claims come back with the times they were seen`(): Unit = runBlocking {
        // The observation pair is half the evidence: a number agreeing across a long gap is
        // weaker than one agreeing now, and nothing but the claim's own last-seen can tell them
        // apart.
        val number = requireNotNull(Identifier.of(IdentifierType.PHONE, "+995322000111"))
        val record = writePerson("observed", "A Display Name", listOf(number))

        val read = persons.persons(listOf(record)).single()

        assertEquals(listOf(number), read.claims.map { it.identifier })
        assertTrue(read.claims.single().lastSeenAt >= read.claims.single().addedAt)
    }

    @Test
    fun `a record is found from a value it claims, carrying the claims that did not find it`(): Unit = runBlocking {
        // The property the two-pass query exists for. Filtering the claims and the records in
        // one go returns each candidate holding only the claim that found it, and every pair
        // then scores on one signal — the one already known to agree.
        val shared = requireNotNull(Identifier.of(IdentifierType.PHONE, "+995322000222"))
        val private = requireNotNull(Identifier.of(IdentifierType.HANDLE, "@only-one-of-them"))
        val one = writePerson("claimant", "A Display Name", listOf(shared, private))
        val other = writePerson("claimant", "Another Display Name", listOf(shared))

        val found = persons.claimants(listOf(shared))

        assertEquals(setOf(one, other), found.map { it.externalId }.toSet())
        assertEquals(
            setOf(shared, private),
            found.single { it.externalId == one }.claims.map { it.identifier }.toSet(),
            "The candidate came back carrying only the claim that found it, so nothing else " +
                "about it could ever be scored.",
        )
    }

    @Test
    fun `names are counted in the form the matcher compares them in`(): Unit = runBlocking {
        // The one place two normalisations have to agree: the statement's and `nameKey`'s. If
        // they drift, nothing matches, every name reads as unique, and the weakest signal
        // quietly becomes one of the strongest.
        val name = "Spaced ${StoreRig.externalId("name").nativeId}"
        writePerson("named", "  $name  ", emptyList())
        writePerson("named", name.replace(" ", "   "), emptyList())
        writePerson("named", name.uppercase(), emptyList())
        // Whitespace that is not a space, which is where the two normalisations last diverged:
        // the matcher trims every kind of it and `btrim` strips spaces alone, so a tabbed name
        // counted as a name nobody else carried and the signal took full weight.
        writePerson("named", "\t$name\n", emptyList())
        writePerson("named", name.replace(" ", "\t"), emptyList())

        val counted = persons.nameFrequency(listOf(requireNotNull(nameKey(name))))

        assertEquals(
            mapOf(requireNotNull(nameKey(name)) to 5),
            counted,
            "The store counted a different set of names than the matcher compares.",
        )
    }

    @Test
    fun `a name nothing carries is counted as none rather than going missing`(): Unit = runBlocking {
        val absent = requireNotNull(nameKey("Nobody ${StoreRig.externalId("absent").nativeId}"))

        assertEquals(mapOf(absent to 0), persons.nameFrequency(listOf(absent)))
    }

    @Test
    fun `a record is found from a name it shares, carrying its own claims`(): Unit = runBlocking {
        // The other generation key, and the one a phone-anchored source depends on: a handle is
        // unique per account and a number is fixed when one is registered, so one human's two
        // accounts agree on the name and on nothing else.
        val name = "Shared ${StoreRig.externalId("namesake").nativeId}"
        val key = requireNotNull(nameKey(name))
        val handle = requireNotNull(Identifier.of(IdentifierType.HANDLE, "@one-of-the-namesakes"))
        val one = writePerson("namesake", name, listOf(handle))
        val other = writePerson("namesake", name.uppercase(), emptyList())

        val found = persons.namesakes(listOf(key), carriedByAtMost = 4)

        assertEquals(setOf(one, other), found.map { it.externalId }.toSet())
        assertEquals(
            listOf(handle),
            found.single { it.externalId == one }.claims.map { it.identifier },
            "A record pulled in by its name came back without its claims, so the pair would " +
                "score on the name alone even where a claim agreed.",
        )
    }

    @Test
    fun `a name more records carry than the bound allows comes back empty`(): Unit = runBlocking {
        // Not truncated — empty. A partial set of a common name's carriers would be scored as
        // though it were all of them, and which records came back would depend on what the store
        // reached first.
        val name = "Crowded ${StoreRig.externalId("crowd").nativeId}"
        val key = requireNotNull(nameKey(name))
        repeat(5) { writePerson("crowd", name, emptyList()) }

        assertTrue(
            persons.namesakes(listOf(key), carriedByAtMost = 4).isEmpty(),
            "A name five records carry was pulled under a bound of four.",
        )
        assertEquals(
            5,
            persons.namesakes(listOf(key), carriedByAtMost = 5).size,
            "The same name was not pulled at a bound that admits it, so the case above passes " +
                "whether the bound works or the read is simply broken.",
        )
    }

    @Test
    fun `the name bound counts the corpus rather than what the read returns`(): Unit = runBlocking {
        // The bound has to mean *this many in the table*. Counted over the returned set instead,
        // it would be satisfied by its own result and never exclude anything.
        val crowded = "Common ${StoreRig.externalId("counted").nativeId}"
        val rare = "Rare ${StoreRig.externalId("counted").nativeId}"
        repeat(5) { writePerson("counted", crowded, emptyList()) }
        val alone = writePerson("counted", rare, emptyList())

        val found = persons.namesakes(
            listOf(requireNotNull(nameKey(crowded)), requireNotNull(nameKey(rare))),
            carriedByAtMost = 4,
        )

        assertEquals(
            listOf(alone),
            found.map { it.externalId },
            "Asking for a crowded name and a rare one together returned $found. The crowded " +
                "one is over the bound and must contribute nothing, whatever it is asked beside.",
        )
    }

    @Test
    fun `a claim arriving for the first time makes the record owed its identity again`(): Unit = runBlocking {
        // What makes re-evaluation triggered by a new identifier rather than by every pass. A
        // claim lands in its own table, so without this the record's own columns stand still and
        // the watermark — which keys on content time — never offers it again.
        val record = writePerson("claimed", "A Display Name", emptyList())
        val before = requireNotNull(persons.timesOf(record)).contentTime

        val arrived = requireNotNull(Identifier.of(IdentifierType.PHONE, "+995322000777"))
        persons.upsert(Person(externalId = record, displayName = "A Display Name", identifiers = listOf(arrived)))

        assertTrue(
            requireNotNull(persons.timesOf(record)).contentTime > before,
            "A person record gained a claim and its content time stood still, so no pass is " +
                "owed it — and the pair a newly shared number would merge waits for the display " +
                "name to change instead.",
        )
    }

    @Test
    fun `a claim seen again moves nothing, so a resync re-derives nothing`(): Unit = runBlocking {
        // The half that keeps the cost down. A resync re-offers every claim of every record, and
        // advancing content time on all of them would re-derive the whole corpus each full pass.
        val held = requireNotNull(Identifier.of(IdentifierType.PHONE, "+995322000888"))
        val record = writePerson("reclaimed", "A Display Name", listOf(held))
        val before = requireNotNull(persons.timesOf(record)).contentTime

        persons.upsert(Person(externalId = record, displayName = "A Display Name", identifiers = listOf(held)))

        assertEquals(
            before,
            requireNotNull(persons.timesOf(record)).contentTime,
            "Re-offering a claim the record already held moved its content time, so every full " +
                "ingestion pass hands the whole corpus back to every deriver.",
        )
    }

    @Test
    fun `a message gaining a claim is not re-derived, because inference is paid for in tokens`(): Unit =
        runBlocking {
            // The exclusion that keeps the model budget out of this. Identity is the only deriver
            // over person records and spends nothing; a message is derived over by inference, so
            // moving its content time because it gained a claim would buy a re-read of material
            // whose text did not change.
            val room = StoreRig.externalId("claimed-room")
            conversations.upsert(Conversation(externalId = room, kind = ConversationKind.GROUP, name = "A Room"))
            val author = writePerson("said-it", "A Display Name", emptyList())
            val said = StoreRig.externalId("claimed-message")
            val line = Message(
                externalId = said,
                sourceTime = Instant.parse("2024-01-01T00:00:00Z"),
                author = author,
                text = "a line of text",
            )
            messages.upsert(room, listOf(line))
            val before = requireNotNull(messages.timesOf(said)).contentTime

            val attached = requireNotNull(Identifier.of(IdentifierType.CONTENT_HASH, "d00dfeed"))
            messages.upsert(room, listOf(line.copy(identifiers = listOf(attached))))

            assertEquals(
                before,
                requireNotNull(messages.timesOf(said)).contentTime,
                "A message gained a claim and became owed again. Inference derives over messages " +
                    "and is paid for in model tokens, so this spends the one budget that is finite " +
                    "to re-read text that did not change.",
            )
        }

    @Test
    fun `unresolved pairs come back strongest first, with both sides named`(): Unit = runBlocking {
        val (weak, weaker) = twoContacts()
        val (strong, stronger) = twoContacts()
        contacts.keep(
            listOf(
                CandidateMerge.of(weak.id, weaker.id, 0.2, listOf(Signal(SignalKind.NAME, 0.2))),
                CandidateMerge.of(strong.id, stronger.id, 0.6, listOf(Signal(SignalKind.HANDLE, 0.6))),
            ),
        )

        val mine = setOf(weak.id, weaker.id, strong.id, stronger.id)
        val read = pairs.unresolved(floor = 0.0, limit = allOfThem)
            .filter { it.pair.one in mine || it.pair.other in mine }

        assertEquals(listOf(0.6, 0.2), read.map { it.pair.score }, "The report was not ordered by score.")
        assertTrue(
            read.all { it.oneName != null && it.otherName != null },
            "A pair came back unnamed. Both contacts here carry a name, so this says the join " +
                "onto `contact` dropped one; which name belongs to which side is asserted by " +
                "`the two sides of a reported pair keep their own names`.",
        )
    }

    @Test
    fun `a pair under the floor is kept but not reported`(): Unit = runBlocking {
        val (one, other) = twoContacts()
        contacts.keep(listOf(CandidateMerge.of(one.id, other.id, 0.1, listOf(Signal(SignalKind.NAME, 0.1)))))

        assertTrue(
            contacts.candidates(listOf(one.id)).isNotEmpty(),
            "The pair was not kept at all, so nothing here is about the floor.",
        )
        val mine = minOf(one.id, other.id)
        assertTrue(
            pairs.unresolved(floor = 0.2, limit = allOfThem).none { it.pair.one == mine },
            "A pair scoring under the floor was reported.",
        )
        assertTrue(
            pairs.unresolved(floor = 0.1, limit = allOfThem).any { it.pair.one == mine },
            "The pair was withheld at a floor it meets, so the floor excludes more than it says.",
        )
    }

    @Test
    fun `a pair already merged is not reported as unresolved`(): Unit = runBlocking {
        // Reachable rather than hypothetical: a pair merged under the weights of the day is
        // re-scored whenever the identity version moves, and today's arithmetic may put it under
        // today's threshold. The row stays; what must not happen is a live contact pair reading
        // as an open question.
        val (merged, canonical) = twoContacts()
        contacts.keep(
            listOf(CandidateMerge.of(merged.id, canonical.id, 0.6, listOf(Signal(SignalKind.HANDLE, 0.6)))),
        )
        assertTrue(
            pairs.unresolved(floor = 0.0, limit = allOfThem).any { it.pair.one == minOf(merged.id, canonical.id) },
            "The pair was not reported before the merge, so this proves nothing about the merge " +
                "being what withheld it.",
        )

        contacts.merge(listOf(MergeLink(merged.id, canonical.id, 1.0, listOf(Signal(SignalKind.PHONE, 1.0)))))

        assertTrue(
            pairs.unresolved(floor = 0.0, limit = allOfThem).none { it.pair.one == minOf(merged.id, canonical.id) },
            "A pair the pass has already joined was reported as unresolved, so the same two " +
                "accounts read as merged and as merely suspected at once.",
        )
    }

    @Test
    fun `a pair is reported when one side was merged into some third contact`(): Unit = runBlocking {
        // Excluding a pair because one side carries *a* link is not the same as excluding one
        // whose two sides are the same human. A link says something was settled about that side
        // and whoever it was merged into — it says nothing about the contact on the other end of
        // this pair, which is exactly the open question the surface exists to put in front of
        // somebody. Getting this wrong hides every open pair naming a non-canonical contact, and
        // on this corpus those are the name-only near misses hanging off phone-merged accounts.
        val (absorbed, canonical) = twoContacts()
        contacts.merge(listOf(MergeLink(absorbed.id, canonical.id, 1.0, listOf(Signal(SignalKind.PHONE, 1.0)))))
        val third = Contact(EntityId.mint(), StoreRig.externalId("third-party"), "A Third Display Name")
        contacts.upsert(listOf(third))
        contacts.keep(
            listOf(CandidateMerge.of(absorbed.id, third.id, 0.35, listOf(Signal(SignalKind.NAME, 0.35)))),
        )

        val reported = pairs.unresolved(floor = 0.0, limit = allOfThem)

        assertTrue(
            reported.any { it.pair.one == minOf(absorbed.id, third.id) },
            "A pair between a merged-away contact and an unrelated third one was withheld. " +
                "Whether an open pair is shown then depends on which side of some other merge " +
                "the contact happened to land on, which is arbitrary.",
        )
    }

    @Test
    fun `the two sides of a reported pair keep their own names`(): Unit = runBlocking {
        // Both sides named is satisfied by construction and discriminates nothing: swapping the
        // two columns in the statement would leave it green. What has to hold is that each name
        // belongs to the identifier beside it.
        val one = Contact(EntityId.mint(), StoreRig.externalId("named-one"), "First Side Name")
        val other = Contact(EntityId.mint(), StoreRig.externalId("named-other"), "Second Side Name")
        contacts.upsert(listOf(one, other))
        contacts.keep(listOf(CandidateMerge.of(one.id, other.id, 0.6, listOf(Signal(SignalKind.HANDLE, 0.6)))))

        val read = pairs.unresolved(floor = 0.0, limit = allOfThem)
            .single { it.pair.one == minOf(one.id, other.id) }

        val expected = if (one.id < other.id) one to other else other to one
        assertEquals(expected.first.displayName, read.oneName, "The first side's name is not its own.")
        assertEquals(expected.second.displayName, read.otherName, "The second side's name is not its own.")
    }

    @Test
    fun `a pair a correction separated is not reported as unresolved`(): Unit = runBlocking {
        // A correction is the most resolved a pair gets: a person has said these two are different
        // humans. The pass goes on scoring it, because the evidence in the corpus still says what
        // it said and nothing rewrites that — so the row stays and this is what keeps it away from
        // a reader who has already answered the question.
        val (one, other) = twoContacts()
        contacts.merge(listOf(MergeLink(one.id, other.id, 1.0, listOf(Signal(SignalKind.PHONE, 1.0)))))
        contacts.unmerge(one.id)
        contacts.keep(listOf(CandidateMerge.of(one.id, other.id, 0.6, listOf(Signal(SignalKind.HANDLE, 0.6)))))

        assertTrue(
            contacts.separations(listOf(one.id, other.id)).isNotEmpty(),
            "The correction was not recorded, so this case is not in the state it is about.",
        )
        val unrelated = twoContacts()
        contacts.keep(
            listOf(
                CandidateMerge.of(
                    unrelated.first.id,
                    unrelated.second.id,
                    0.6,
                    listOf(Signal(SignalKind.HANDLE, 0.6)),
                ),
            ),
        )
        assertTrue(
            pairs.unresolved(floor = 0.0, limit = allOfThem)
                .any { it.pair.one == minOf(unrelated.first.id, unrelated.second.id) },
            "A pair nothing has settled was not reported either, so the read is returning " +
                "nothing for some reason of its own and the case below proves nothing.",
        )
        assertTrue(
            pairs.unresolved(floor = 0.0, limit = allOfThem).none { it.pair.one == minOf(one.id, other.id) },
            "A pair a person has separated was reported as unresolved. The one state nobody " +
                "needs to be asked about is the one they already settled.",
        )
    }

    @Test
    fun `the report is bounded by the limit it is given`(): Unit = runBlocking {
        val scored = (1..3).map {
            val (one, other) = twoContacts()
            CandidateMerge.of(one.id, other.id, 0.5, listOf(Signal(SignalKind.HANDLE, 0.5)))
        }
        contacts.keep(scored)

        assertEquals(2, pairs.unresolved(floor = 0.0, limit = 2).size)
    }

    @Test
    fun `a limit of nothing reads nothing rather than everything`(): Unit = runBlocking {
        // A limit of zero reaching SQL as `LIMIT 0` would be right, and one reaching it as a
        // missing clause would return the table. Refused before the query either way.
        assertTrue(pairs.unresolved(floor = 0.0, limit = 0).isEmpty())
    }

    @Test
    fun `two accounts a third party was in a room with are a forbidden pair`(): Unit = runBlocking {
        // The hard negative. Two person records appearing as distinct participants of one
        // conversation are two humans, and no accumulation of weak agreement may outvote it.
        val one = writePerson("member", "A Display Name", emptyList())
        val other = writePerson("member", "Another Display Name", emptyList())
        writeRoom(listOf(one, other))

        val forbidden = messages.sharingAConversation(listOf(one, other))

        assertEquals(1, forbidden.size, "The pair was returned $forbidden times rather than once.")
        assertEquals(setOf(one, other), forbidden.single().let { setOf(it.first, it.second) })
    }

    @Test
    fun `an account is never a forbidden pair with itself`(): Unit = runBlocking {
        // One account writing many messages in one room is the ordinary case, and a self-join
        // over its own rows is exactly where it would come back as its own hard negative.
        val alone = writePerson("alone", "A Display Name", emptyList())
        writeRoom(listOf(alone, alone, alone))

        assertTrue(
            messages.sharingAConversation(listOf(alone)).isEmpty(),
            "An account was reported as sharing a conversation with itself, which would forbid " +
                "every merge it could ever be part of.",
        )
    }

    @Test
    fun `accounts in different rooms share no conversation`(): Unit = runBlocking {
        val one = writePerson("apart", "A Display Name", emptyList())
        val other = writePerson("apart", "Another Display Name", emptyList())
        writeRoom(listOf(one))
        writeRoom(listOf(other))

        assertTrue(messages.sharingAConversation(listOf(one, other)).isEmpty())
    }

    // --- the corpus these read -------------------------------------------------------------

    /**
     * The head one contact resolves to.
     *
     * Through the bulk read, which is the only one the port offers: a single-contact convenience
     * over it had no caller outside this file.
     */
    private suspend fun ContactRepository.headOf(contact: EntityId): EntityId =
        heads(listOf(contact)).getValue(contact)

    private suspend fun twoContacts(): Pair<Contact, Contact> {
        val one = Contact(EntityId.mint(), StoreRig.externalId("merged"), "A Display Name")
        val other = Contact(EntityId.mint(), StoreRig.externalId("canonical"), "Another Display Name")
        contacts.upsert(listOf(one, other))
        return one to other
    }

    private suspend fun writePerson(
        label: String,
        displayName: String?,
        claims: List<Identifier>,
    ): ExternalId {
        val record = StoreRig.externalId(label)
        persons.upsert(Person(externalId = record, displayName = displayName, identifiers = claims))
        return record
    }

    /** A conversation with one message per entry in [authors], so each becomes a participant. */
    private suspend fun writeRoom(authors: List<ExternalId>): ExternalId {
        val room = StoreRig.externalId("room")
        conversations.upsert(
            Conversation(externalId = room, kind = ConversationKind.GROUP, name = "A Room"),
        )
        messages.upsert(
            room,
            authors.mapIndexed { position, author ->
                Message(
                    externalId = StoreRig.externalId("said"),
                    sourceTime = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(position.toLong()),
                    author = author,
                    text = "a line of text",
                )
            },
        )
        return room
    }

    private fun phone() = Signal(SignalKind.PHONE, 1.0)

    private fun CandidateMerge.involves(contact: EntityId) = one == contact || other == contact

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
