package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.Derivation
import io.github.ragnorrs.mainbrain.domain.DerivationStateRepository
import io.github.ragnorrs.mainbrain.domain.Pending
import io.github.ragnorrs.mainbrain.domain.entity.CandidateMerge
import io.github.ragnorrs.mainbrain.domain.entity.Client
import io.github.ragnorrs.mainbrain.domain.entity.ClientRepository
import io.github.ragnorrs.mainbrain.domain.entity.Cluster
import io.github.ragnorrs.mainbrain.domain.entity.Contact
import io.github.ragnorrs.mainbrain.domain.entity.ContactRepository
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.MergeLink
import io.github.ragnorrs.mainbrain.domain.entity.Separation
import io.github.ragnorrs.mainbrain.domain.entity.SignalKind
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ConversationRepository
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.IdentifierType
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.MessageRepository
import io.github.ragnorrs.mainbrain.domain.record.ObservedIdentifier
import io.github.ragnorrs.mainbrain.domain.record.ObservedPerson
import io.github.ragnorrs.mainbrain.domain.record.Person
import io.github.ragnorrs.mainbrain.domain.record.PersonRepository
import io.github.ragnorrs.mainbrain.domain.record.RecordKind
import io.github.ragnorrs.mainbrain.domain.record.RecordTimes
import io.github.ragnorrs.mainbrain.domain.record.Source
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The identity pass, against fakes.
 *
 * Fakes rather than a database because what this class does is selection, ordering and
 * constraint: which records it pulls in, which pairs it dares merge, and that nothing is marked
 * recognised before the contacts are written. The store is tested on its own, and a real one
 * would make the constraint cases harder to set up rather than more convincing.
 *
 * The fake contact store keeps one piece of real behaviour — an upsert leaves the identifier a
 * record already has — because that is the port's contract rather than the store's internals,
 * and the pass is built on top of it. Without it nothing here would notice the pass reminting an
 * entity on every run.
 */
class ReconcilerTest {

    @Test
    fun `a person record becomes a contact, and only then is it marked`(): Unit = runBlocking {
        val record = id("alone")
        val world = World(pending = listOf(record), stored = listOf(person(record, "A Name")))

        val recognised = world.reconciler().reconcileBatch()

        assertEquals(Recognised(records = 1, clients = 0), recognised)
        assertEquals(listOf(record), world.contacts.stored.values.map { it.record })
        assertEquals(listOf(record), world.derivations.marked)
        assertEquals(
            listOf("contact", "merge", "keep", "mark"),
            world.order,
            "A record was marked recognised before its contact was written. A failure between " +
                "the two then leaves it recorded as done with no entity behind it, and no " +
                "re-run revisits it.",
        )
    }

    @Test
    fun `a record already carrying a contact is not reminted`(): Unit = runBlocking {
        // Everything downstream holds an entity identifier. Reminting one on every pass would
        // renumber the whole entity layer each time the pass ran.
        val record = id("stable")
        val world = World(pending = listOf(record), stored = listOf(person(record, "A Name")))
        world.reconciler().reconcileBatch()
        val first = world.contacts.stored.values.single().id

        world.derivations.pendingIds = listOf(record)
        world.reconciler().reconcileBatch()

        assertEquals(first, world.contacts.stored.values.single().id)
    }

    @Test
    fun `two records sharing a phone number are merged`(): Unit = runBlocking {
        val one = id("one")
        val other = id("other")
        val number = claim(IdentifierType.PHONE, "+995322000000")
        val world = World(
            pending = listOf(one, other),
            stored = listOf(person(one, "A Name", number), person(other, "Another Name", number)),
        )

        world.reconciler().reconcileBatch()

        val link = world.contacts.links.single()
        assertEquals(
            setOf(one, other),
            setOf(world.contacts.recordOf(link.merged), world.contacts.recordOf(link.canonical)),
        )
        assertTrue(link.score >= Weights.STARTING.threshold)
        assertTrue(link.signals.isNotEmpty(), "A merge was written with no record of what carried it.")
    }

    @Test
    fun `two records that were distinct members of one conversation are never merged`(): Unit = runBlocking {
        // The hard negative. Both advertise one number — a household handset, a shared desk —
        // and a third party was in a room with both of them, so they are two humans.
        val one = id("one")
        val other = id("other")
        val number = claim(IdentifierType.PHONE, "+995322000000")
        val world = World(
            pending = listOf(one, other),
            stored = listOf(person(one, "A Name", number), person(other, "Another Name", number)),
            roommates = listOf(one to other),
        )

        world.reconciler().reconcileBatch()

        assertTrue(
            world.contacts.links.isEmpty(),
            "A pair that shared a conversation was merged on a shared number. No accumulation " +
                "of agreement may outvote a hard negative.",
        )
    }

    @Test
    fun `a cluster holding a forbidden pair is split rather than merged`(): Unit = runBlocking {
        // What makes this clustering under constraints rather than following links wherever they
        // lead. The middle record matches both others strongly, and the two outer ones are two
        // humans — so joining all three on pairwise strength is exactly the fusion to avoid.
        val middle = id("middle")
        val first = id("first")
        val second = id("second")
        // A number each, rather than one between the three: a value three records claim is
        // scaled down by its own rarity and would carry no merge at all, so the case would
        // pass without the constraint it exists to exercise ever being reached.
        val withFirst = claim(IdentifierType.PHONE, "+995322000001")
        val withSecond = claim(IdentifierType.PHONE, "+995322000002")
        val world = World(
            pending = listOf(middle, first, second),
            stored = listOf(
                person(middle, "A Name", withFirst, withSecond),
                person(first, "Another Name", withFirst),
                person(second, "A Third Name", withSecond),
            ),
            roommates = listOf(first to second),
        )

        world.reconciler().reconcileBatch()

        val joined = world.contacts.clustered()
        assertEquals(
            setOf(2, 1),
            joined.map { it.size }.toSet(),
            "The three records clustered as $joined. Two may merge; the pair that shared a " +
                "conversation may not end up in one cluster however strong each link looks.",
        )
    }

    @Test
    fun `when a constraint forces a choice, the stronger evidence wins`(): Unit = runBlocking {
        // Both outer records match the middle one, and they may not share a cluster. Only one
        // of the two joins can happen, so which is considered first decides the outcome — and
        // the one that survives has to be the better-evidenced of the two.
        val middle = id("middle")
        val weaker = id("weaker")
        val stronger = id("stronger")
        val withWeaker = claim(IdentifierType.PHONE, "+995322000001")
        val withStronger = claim(IdentifierType.PHONE, "+995322000002")
        val alsoWithStronger = claim(IdentifierType.EMAIL, "shared@example.com")
        val world = World(
            pending = listOf(middle, weaker, stronger),
            stored = listOf(
                person(middle, "A Name", withWeaker, withStronger, alsoWithStronger),
                person(weaker, "Another Name", withWeaker),
                person(stronger, "A Third Name", withStronger, alsoWithStronger),
            ),
            roommates = listOf(weaker to stronger),
        )

        world.reconciler().reconcileBatch()

        val joined = world.contacts.clustered().single { it.size == 2 }.map { world.contacts.recordOf(it) }
        assertEquals(
            setOf(middle, stronger),
            joined.toSet(),
            "The weaker of the two possible merges won, so the pass joined on $joined. " +
                "Considering pairs strongest first is what makes the refusal fall on the " +
                "weaker evidence rather than on whichever pair came up first.",
        )
    }

    @Test
    fun `a hard negative against a record merged on an earlier pass still forbids the join`(): Unit = runBlocking {
        // Every other constraint case here puts all three records in one batch, which is the one
        // arrangement where the check cannot be wrong. This is the arrangement that matters: the
        // forbidden record was merged by an earlier pass, so it is in neither the batch nor its
        // candidates, and the cluster it belongs to has to be read whole for the constraint to be
        // seen at all. Checking only what the batch resolved allows the join — and the result is
        // two humans in one cluster, which nothing afterwards reports.
        val head = id("head")
        val absent = id("absent")
        val arriving = id("arriving")
        val betweenTheFirstTwo = claim(IdentifierType.PHONE, "+995322000001")
        val betweenHeadAndArriving = claim(IdentifierType.PHONE, "+995322000002")
        val world = World(
            pending = listOf(head, absent),
            stored = listOf(
                person(head, "A Name", betweenTheFirstTwo, betweenHeadAndArriving),
                person(absent, "Another Name", betweenTheFirstTwo),
                person(arriving, "A Third Name", betweenHeadAndArriving),
            ),
            // The pair that may never share a cluster is the one this batch never sees.
            roommates = listOf(absent to arriving),
        )
        world.reconciler().reconcileBatch()
        assertEquals(
            1,
            world.contacts.links.size,
            "The first pass was meant to merge the first two records, so that the second pass " +
                "meets one of them only through the other.",
        )

        world.derivations.pendingIds = listOf(arriving)
        world.reconciler().reconcileBatch()

        val fused = world.contacts.clustered().any { group -> group.size > 2 }
        assertTrue(
            !fused,
            "The arriving record was merged into a cluster holding a record it shared a " +
                "conversation with. The constraint is checked across every member of both " +
                "clusters, and a member merged on an earlier pass is still a member.",
        )
    }

    @Test
    fun `a correction is not undone by the next pass`(): Unit = runBlocking {
        // The evidence that carried the original merge is still in the corpus and still says
        // what it said. Only the stored separation stops the pass merging the pair again.
        val one = id("one")
        val other = id("other")
        val number = claim(IdentifierType.PHONE, "+995322000000")
        val world = World(
            pending = listOf(one, other),
            stored = listOf(person(one, "A Name", number), person(other, "Another Name", number)),
        )
        world.reconciler().reconcileBatch()
        val merged = world.contacts.links.single().merged
        world.contacts.unmerge(merged)
        world.contacts.links.clear()

        world.derivations.pendingIds = listOf(one)
        world.reconciler().reconcileBatch()

        assertTrue(
            world.contacts.links.isEmpty(),
            "The pass re-merged a pair a correction had separated. A correction outranks " +
                "re-recognition and is never overwritten.",
        )
    }

    @Test
    fun `a pair below the threshold is kept with its score rather than discarded`(): Unit = runBlocking {
        // The corpus grows: this pair crosses the line the moment a number appears on one side,
        // and the kept score is what makes that a re-evaluation of one pair.
        val one = id("one")
        val other = id("other")
        val handle = claim(IdentifierType.HANDLE, "@shared")
        val world = World(
            pending = listOf(one, other),
            stored = listOf(person(one, "A Name", handle), person(other, "Another Name", handle)),
        )

        world.reconciler().reconcileBatch()

        assertTrue(world.contacts.links.isEmpty(), "A handle alone carried a merge.")
        val kept = world.contacts.kept.single()
        assertEquals(Weights.STARTING.handle, kept.score, "The kept pair did not carry what it scored.")
    }

    @Test
    fun `a pair agreeing on nothing is neither merged nor kept`(): Unit = runBlocking {
        val one = id("one")
        val other = id("other")
        val world = World(
            pending = listOf(one, other),
            stored = listOf(
                person(one, "A Name", claim(IdentifierType.HANDLE, "@one")),
                person(other, "Another Name", claim(IdentifierType.HANDLE, "@two")),
            ),
        )

        world.reconciler().reconcileBatch()

        assertTrue(world.contacts.links.isEmpty())
        assertTrue(
            world.contacts.kept.isEmpty(),
            "A pair with nothing in common was kept as a candidate. Every pair in the corpus " +
                "would then be stored, and the table would be the cross product.",
        )
    }

    @Test
    fun `a pair with both records in the batch is scored once`(): Unit = runBlocking {
        // Both sides of the pair are in the batch, so without the guard it is compared twice —
        // once from each end. The store would collapse the two into one row and the outcome
        // would be the same, so what this protects is the work: the comparison is quadratic in
        // the batch, and doing each pair twice doubles the whole pass.
        val one = id("one")
        val other = id("other")
        val handle = claim(IdentifierType.HANDLE, "@shared")
        val world = World(
            pending = listOf(one, other),
            stored = listOf(person(one, "A Name", handle), person(other, "Another Name", handle)),
        )

        world.reconciler().reconcileBatch()

        assertEquals(
            1,
            world.contacts.offered.size,
            "The pair was scored and offered ${world.contacts.offered.size} times. The store " +
                "would hide this by replacing the row, so only what the pass offered shows it.",
        )
    }

    @Test
    fun `two records sharing only a name are scored and kept, from separate batches`(): Unit = runBlocking {
        // **The two records are deliberately never in one batch together.** Scoring compares every
        // batch member against every other one, so a pair that shares a batch is scored whatever
        // generation does — and a test built that way would pass with the name key absent. Putting
        // them in separate batches is what makes this a test of which records are *pulled in*.
        //
        // This is the shape a phone-anchored source produces: a handle is unique per account and a
        // number is fixed when one is registered, so one human's two accounts agree on the name
        // and on nothing else, for good.
        val first = id("first")
        val second = id("second")
        val world = World(
            pending = listOf(first),
            stored = listOf(
                person(first, "One Name", claim(IdentifierType.HANDLE, "@first")),
                person(second, "One Name", claim(IdentifierType.HANDLE, "@second")),
            ),
        )

        // The first batch gives `first` a contact; `second` has none yet, so there is no pair to
        // write even once it is scored.
        world.reconciler(batchSize = 1).reconcileBatch()
        world.derivations.pendingIds = listOf(second)
        world.reconciler(batchSize = 1).reconcileBatch()

        assertTrue(
            world.contacts.links.isEmpty(),
            "A bare name match carried a merge. The threshold is unmoved by this: the name key " +
                "decides what is scored, never what is joined.",
        )
        val kept = world.contacts.kept.singleOrNull()
        assertTrue(
            kept != null,
            "The pair was never scored at all, so nothing recorded that these two accounts might " +
                "be one person. Candidate generation did not pull a record sharing only a name.",
        )
        // Two carriers is as rare as a shared name gets, so the signal is at full weight.
        assertEquals(Weights.STARTING.name, kept.score)
        assertEquals(listOf(SignalKind.NAME), kept.signals.map { it.kind })
    }

    @Test
    fun `a name a crowd carries pulls nothing, however many carry it`(): Unit = runBlocking {
        // The bound on generation, and the reason it is separate from the rarity that scales the
        // score. A name five people share would score near nothing anyway; what the bound saves is
        // assembling and scoring every pair of them to find that out.
        val crowd = (1..5).map { id("person-$it") }
        val world = World(
            pending = crowd,
            stored = crowd.map { person(it, "A Common Name", claim(IdentifierType.HANDLE, "@$it")) },
        )

        // One record per batch throughout, so nothing is scored by having shared a batch.
        crowd.forEach { record ->
            world.derivations.pendingIds = listOf(record)
            world.reconciler(batchSize = 1).reconcileBatch()
        }

        assertTrue(
            world.contacts.kept.isEmpty(),
            "A name carried by ${crowd.size} records pulled its carriers in and scored " +
                "${world.contacts.kept.size} pair(s). The bound on generation did not hold, so a " +
                "common name costs a batch-sized set of comparisons for evidence of nothing.",
        )
    }

    @Test
    fun `a name carried by exactly the bound is still pulled`(): Unit = runBlocking {
        // The other half of the previous case. Without this, a generation step that pulled nothing
        // at all would pass it — the bound would read as holding when what held was the absence of
        // the whole mechanism.
        val carriers = (1..Weights.STARTING.namesakeLimit()).map { id("person-$it") }
        val world = World(
            pending = carriers,
            stored = carriers.map { person(it, "A Name", claim(IdentifierType.HANDLE, "@$it")) },
        )

        carriers.forEach { record ->
            world.derivations.pendingIds = listOf(record)
            world.reconciler(batchSize = 1).reconcileBatch()
        }

        assertTrue(
            world.contacts.kept.isNotEmpty(),
            "A name carried by exactly the bound of ${carriers.size} pulled nothing. The bound is " +
                "then tighter than it says, and the case above passes for the wrong reason.",
        )
        assertTrue(
            world.contacts.links.isEmpty(),
            "A name shared by ${carriers.size} records carried a merge.",
        )
    }

    @Test
    fun `a record found by both keys is counted once, so its claim is not scaled down`(): Unit = runBlocking {
        // The overlap path: one record shares both a handle and a name with the batch, so both
        // reads return it. Without the dedup it sits in the assembled set twice, its handle is
        // counted as having three carriers instead of two, and the signal the pair actually agrees
        // on is scaled to two thirds — a wrong score, from a pass that looks like it worked.
        val mine = id("mine")
        val both = id("both")
        val handle = claim(IdentifierType.HANDLE, "@shared")
        val world = World(
            pending = listOf(both),
            stored = listOf(person(both, "One Name", handle), person(mine, "One Name", handle)),
        )
        world.reconciler(batchSize = 1).reconcileBatch()
        world.derivations.pendingIds = listOf(mine)

        world.reconciler(batchSize = 1).reconcileBatch()

        // A handle and a name together are the weakest pair of signals this source can offer that
        // still merges, so the overlap shows up on the link rather than among the kept pairs.
        val link = world.contacts.links.single()
        // Two carriers of each, so both signals are at full weight and nothing is scaled.
        assertEquals(
            Weights.STARTING.handle + Weights.STARTING.name,
            link.score,
            "The pair scored ${link.score}. A record returned by both keys was counted twice in " +
                "the frequency, so the evidence it agrees on was scaled down by its own presence " +
                "— and the pair then falls under the bar it reaches exactly.",
        )
    }

    @Test
    fun `a pair sharing a claim scores what it always did`(): Unit = runBlocking {
        // The regression the widened generation could plausibly break: the name key adds a way for
        // a pair to be *found*, and must add nothing to what a found pair *scores*. Distinct names,
        // separate batches, so the claim is the only thing either side agrees on.
        val one = id("one")
        val other = id("other")
        val handle = claim(IdentifierType.HANDLE, "@shared")
        val world = World(
            pending = listOf(one),
            stored = listOf(person(one, "A Name", handle), person(other, "Another Name", handle)),
        )

        world.reconciler(batchSize = 1).reconcileBatch()
        world.derivations.pendingIds = listOf(other)
        world.reconciler(batchSize = 1).reconcileBatch()

        val kept = world.contacts.kept.single()
        assertEquals(
            Weights.STARTING.handle,
            kept.score,
            "A claim-sharing pair no longer scores the handle weight alone.",
        )
        assertEquals(listOf(SignalKind.HANDLE), kept.signals.map { it.kind })
    }

    @Test
    fun `a pair already merged is neither merged again nor filed as a candidate`(): Unit = runBlocking {
        // The third state, and the one the version bump makes reachable. A pair merged under the
        // weights of the day is re-scored whenever the identity version moves; today's arithmetic
        // may put it under today's threshold, and the sub-threshold branch would then file a live
        // contact pair as an unresolved one — so the same two accounts would read as joined and as
        // merely suspected at once.
        val one = id("one")
        val other = id("other")
        val number = claim(IdentifierType.PHONE, "+995322000000")
        val world = World(
            pending = listOf(one, other),
            stored = listOf(person(one, "A Name", number), person(other, "Another Name", number)),
        )

        // Merged under weights that clear the threshold on a shared number alone.
        world.reconciler().reconcileBatch()
        assertEquals(1, world.contacts.links.size, "The pair was never merged, so there is nothing to re-score.")

        // Re-derived under weights that do not. The link stands — nothing here unlinks — and the
        // question is what the pass does with the pair the second time.
        val weaker = Weights.STARTING.copy(phone = 0.4)
        world.derivations.pendingIds = listOf(one, other)
        world.reconciler(weights = weaker).reconcileBatch()

        assertEquals(
            1,
            world.contacts.links.size,
            "A stored merge was disturbed by a re-derivation. A decision is undone by a " +
                "correction, never by a pass rescoring it.",
        )
        assertTrue(
            world.contacts.kept.isEmpty(),
            "A pair that is already one human was filed as a candidate to merge, at " +
                "${world.contacts.kept.map { it.score }}. It would then be offered to a reader as " +
                "an open question about two accounts the pass has already joined.",
        )
    }

    @Test
    fun `a candidate not yet recognised is left for its own batch`(): Unit = runBlocking {
        // A record that has never been reconciled has no contact, so there is nothing to merge
        // it into yet and this pass leaves it alone. Nothing is lost: every record becomes
        // pending in its turn, and the pair is scored from whichever side arrives last — which
        // is also why identity is not assigned to records the batch did not select.
        val mine = id("mine")
        val stranger = id("stranger")
        val number = claim(IdentifierType.PHONE, "+995322000000")
        val world = World(
            pending = listOf(mine),
            stored = listOf(person(mine, "A Name", number), person(stranger, "Another Name", number)),
        )

        world.reconciler().reconcileBatch()

        assertTrue(world.contacts.links.isEmpty(), "A contact was merged into one that does not exist yet.")
        assertEquals(
            listOf(mine),
            world.contacts.stored.values.map { it.record },
            "Identity was assigned to a record this batch never selected and will not mark.",
        )

        // Its own batch merges the pair, from the other side.
        world.derivations.pendingIds = listOf(stranger)
        world.reconciler().reconcileBatch()

        assertEquals(1, world.contacts.links.size, "The deferred pair was never merged at all.")
    }

    @Test
    fun `a topic carrying a label becomes a client`(): Unit = runBlocking {
        val topic = id("topic")
        val world = World(
            pending = emptyList(),
            stored = emptyList(),
            pendingConversations = listOf(topic),
            rooms = listOf(room(topic, ConversationKind.TOPIC, "An Organisation")),
        )

        val recognised = world.reconciler().reconcileBatch()

        assertEquals(Recognised(records = 1, clients = 1), recognised)
        assertEquals("An Organisation", world.clients.stored.getValue(topic).name)
    }

    @Test
    fun `a named group and a named channel are clients exactly as a topic is`(): Unit = runBlocking {
        // Where work is organised as named groups rather than as topics inside one supergroup, the
        // name is the same kind of fact: a human's own statement of what a stretch of conversation
        // is for. A rule keyed on the container's kind would mint nothing at all on such a corpus.
        val group = id("group")
        val channel = id("channel")
        val world = World(
            pending = emptyList(),
            stored = emptyList(),
            pendingConversations = listOf(group, channel),
            rooms = listOf(
                room(group, ConversationKind.GROUP, "A Body Of Work"),
                room(channel, ConversationKind.CHANNEL, "Another Body Of Work"),
            ),
        )

        world.reconciler().reconcileBatch()

        assertEquals(
            setOf("A Body Of Work", "Another Body Of Work"),
            world.clients.stored.values.map { it.name }.toSet(),
            "A named conversation minted no client, so a corpus organised as groups has none.",
        )
    }

    @Test
    fun `a direct exchange is no client however it is named, and is still marked`(): Unit = runBlocking {
        // What a two-person chat displays is the counterparty, rendered by the platform rather than
        // chosen by anybody — so it states nothing about what the conversation is for, and it is
        // already that person's contact name. Admitting it would mint a body of work per person
        // ever written to and leave a contact as its own parent.
        //
        // A conversation nobody named is no client either. Both are marked regardless: neither
        // becomes one unless the source says so, and leaving them unmarked hands them back on every
        // pass for ever.
        val direct = id("direct")
        val unnamed = id("unnamed")
        val world = World(
            pending = emptyList(),
            stored = emptyList(),
            pendingConversations = listOf(direct, unnamed),
            rooms = listOf(
                room(direct, ConversationKind.DIRECT, "A Person"),
                room(unnamed, ConversationKind.GROUP, null),
            ),
        )

        world.reconciler().reconcileBatch()

        assertTrue(world.clients.stored.isEmpty(), "A client was read from ${world.clients.stored.keys}.")
        assertEquals(setOf(direct, unnamed), world.derivations.marked.toSet())
    }

    @Test
    fun `a batch that settles conversations and names none says so`(): Unit = runBlocking {
        // The failure this pass has is silent: it marks every record it looks at, so a count of
        // records settled rises identically whether the rule admitted everything or nothing. Only
        // the second number tells those apart, and it is why the batch returns one.
        val direct = id("direct")
        val unnamed = id("unnamed")
        val world = World(
            pending = emptyList(),
            stored = emptyList(),
            pendingConversations = listOf(direct, unnamed),
            rooms = listOf(
                room(direct, ConversationKind.DIRECT, "A Person"),
                room(unnamed, ConversationKind.GROUP, null),
            ),
        )

        val recognised = world.reconciler().reconcileBatch()

        assertEquals(
            Recognised(records = 2, clients = 0),
            recognised,
            "A batch that settled two conversations and read no client from either reported the " +
                "same thing a batch that minted two would.",
        )
    }

    @Test
    fun `a topic already carrying a client keeps its identity and takes the new label`(): Unit = runBlocking {
        val topic = id("topic")
        val world = World(
            pending = emptyList(),
            stored = emptyList(),
            pendingConversations = listOf(topic),
            rooms = listOf(room(topic, ConversationKind.TOPIC, "An Organisation")),
        )
        world.reconciler().reconcileBatch()
        val first = world.clients.stored.getValue(topic).id

        world.derivations.pendingConversationIds = listOf(topic)
        world.conversations.renamed(topic, "A Renamed Organisation")
        world.reconciler().reconcileBatch()

        val stored = world.clients.stored.getValue(topic)
        assertEquals(first, stored.id, "A rename renumbered the client.")
        assertEquals("A Renamed Organisation", stored.name)
    }

    @Test
    fun `each record kind advances its own watermark`(): Unit = runBlocking {
        // One queue for both would hand person records to the client stage and conversations to
        // the matcher, and neither would notice.
        val person = id("person")
        val topic = id("topic")
        val world = World(
            pending = listOf(person),
            stored = listOf(person(person, "A Name")),
            pendingConversations = listOf(topic),
            rooms = listOf(room(topic, ConversationKind.TOPIC, "An Organisation")),
        )

        assertEquals(Recognised(records = 2, clients = 1), world.reconciler().reconcileBatch())

        assertEquals(listOf(person), world.contacts.stored.values.map { it.record })
        assertEquals(listOf(topic), world.clients.stored.values.map { it.conversation })
        assertEquals(0, world.reconciler().owed())
    }

    @Test
    fun `work is pending while only conversations are owed`(): Unit = runBlocking {
        // Asserting that the count reaches zero proves nothing about the conversation half: by
        // then both kinds are marked, and a count that ignored conversations entirely would
        // read zero too. This is the state that tells them apart — nothing owed on persons and
        // a conversation still owed — and a driver looping until zero stops here with every
        // topic unrecognised.
        val topic = id("topic")
        val world = World(
            pending = emptyList(),
            stored = emptyList(),
            pendingConversations = listOf(topic),
            rooms = listOf(room(topic, ConversationKind.TOPIC, "An Organisation")),
        )

        assertEquals(1, world.reconciler().owed(), "A conversation owed identity was not counted as work.")
    }

    @Test
    fun `nothing pending does no work at all`(): Unit = runBlocking {
        val world = World(pending = emptyList(), stored = emptyList())

        assertEquals(Recognised(records = 0, clients = 0), world.reconciler().reconcileBatch())
        assertTrue(world.order.isEmpty(), "An empty batch still wrote something.")
    }

    @Test
    fun `a changed weight changes the version, so what it decided is re-derived`(): Unit = runBlocking {
        // A mark the version does not invalidate is a decision nothing re-derives, and these
        // weights are the whole of the decision.
        val record = id("one")
        val world = World(pending = listOf(record), stored = listOf(person(record, "A Name")))
        world.reconciler().reconcileBatch()

        world.derivations.pendingIds = listOf(record)
        world.reconciler(weights = Weights.STARTING.copy(phone = 0.5)).reconcileBatch()

        assertEquals(
            2,
            world.derivations.versionsSeen.distinct().size,
            "Two different sets of weights produced one version, so changing a weight leaves " +
                "every contact joined on arithmetic the build no longer performs.",
        )
    }

    @Test
    fun `the identity prefix is none of the ones this build has already written marks under`() {
        // The prefix is the only thing that carries a changed rule to a corpus already settled —
        // a widened client predicate moves none of the weights, so the rest of the string cannot
        // say the decision changed. Nothing else in the suite can see it: staleness is a version
        // comparison in SQL and the fakes here hand back whatever was queued, so reverting the
        // prefix leaves every other case in this file green.
        //
        // Asserted against what has been retired rather than against what is current, so that it
        // fails on going backwards and stays quiet on going forwards. Add the string being left
        // behind whenever the prefix moves; this list is the only record that it did.
        val retired = setOf("identity-1", "identity-2")

        val prefix = Weights.STARTING.identityVersion().substringBefore(':')

        assertTrue(
            prefix !in retired,
            "The identity version is back to '$prefix', which this build has already written " +
                "marks under — so every record settled at it stays settled, and whatever rule " +
                "changed is never asked about the corpus that already exists.",
        )
    }

    @Test
    fun `the mark is stamped with when the work was selected, not when it finished`(): Unit = runBlocking {
        val record = id("one")
        val world = World(pending = listOf(record), stored = listOf(person(record, "A Name")))
        val selected = Instant.parse("2024-03-01T09:00:00Z")
        world.derivations.selectedAt = selected

        world.reconciler().reconcileBatch()

        assertEquals(selected, world.derivations.markedAsOf)
    }

    @Test
    fun `a batch size of nothing is refused at construction`() {
        val world = World(pending = emptyList(), stored = emptyList())
        assertFailsWith<IllegalArgumentException> { world.reconciler(batchSize = 0) }
    }

    // --- the world the pass runs in -------------------------------------------------------

    private class World(
        pending: List<ExternalId>,
        stored: List<ObservedPerson>,
        roommates: List<Pair<ExternalId, ExternalId>> = emptyList(),
        pendingConversations: List<ExternalId> = emptyList(),
        rooms: List<Conversation> = emptyList(),
    ) {
        val order = mutableListOf<String>()
        val derivations = FakeDerivations(pending, pendingConversations, order)
        val persons = FakePersons(stored)
        val conversations = FakeConversations(rooms)
        val messages = FakeMessages(roommates)
        val contacts = FakeContacts(order)
        val clients = FakeClients(order)

        fun reconciler(weights: Weights = Weights.STARTING, batchSize: Int = 64) = Reconciler(
            derivations = derivations,
            corpus = Corpus(persons, conversations, messages),
            contacts = contacts,
            clients = clients,
            weights = weights,
            batchSize = batchSize,
        )
    }

    private class FakeDerivations(
        var pendingIds: List<ExternalId>,
        var pendingConversationIds: List<ExternalId>,
        private val order: MutableList<String>,
    ) : DerivationStateRepository {
        val marked = mutableListOf<ExternalId>()
        val versionsSeen = mutableListOf<String>()
        var selectedAt: Instant = Instant.parse("2024-01-01T12:00:00Z")
        var markedAsOf: Instant? = null

        // `notBefore` is held rather than applied: this fake has records and no source times,
        // and whether the store filters by one is asserted against Postgres. Both passes here
        // pass nothing anyway — neither is bounded by a horizon.
        override suspend fun pending(
            derivation: Derivation,
            kind: RecordKind,
            limit: Int,
            notBefore: Instant?,
        ): Pending {
            versionsSeen += derivation.version
            // Per kind, as the store is. One queue for both would hand person records to the
            // client stage and conversations to the matcher.
            val owed = if (kind == RecordKind.CONVERSATION) pendingConversationIds else pendingIds
            return Pending(records = owed.take(limit), asOf = selectedAt)
        }

        override suspend fun mark(
            derivation: Derivation,
            kind: RecordKind,
            records: List<ExternalId>,
            asOf: Instant,
        ) {
            order += "mark"
            marked += records
            markedAsOf = asOf
            if (kind == RecordKind.CONVERSATION) {
                pendingConversationIds = pendingConversationIds - records.toSet()
            } else {
                pendingIds = pendingIds - records.toSet()
            }
        }

        // Unbounded, as the store's own is: there is no limit to saturate against here, which is
        // the property the pass relies on to tell a batch that made progress from one that stalled.
        override suspend fun owed(derivation: Derivation, kind: RecordKind, notBefore: Instant?): Int =
            if (kind == RecordKind.CONVERSATION) pendingConversationIds.size else pendingIds.size
    }

    private class FakePersons(private val stored: List<ObservedPerson>) : PersonRepository {
        override suspend fun persons(externalIds: List<ExternalId>): List<ObservedPerson> =
            stored.filter { it.externalId in externalIds.toSet() }

        /** Every record claiming any of these values, which is how candidates are found at all. */
        override suspend fun claimants(claims: List<Identifier>): List<ObservedPerson> =
            stored.filter { person -> person.claims.any { it.identifier in claims.toSet() } }

        /**
         * Every record carrying a wanted name, for the names few enough records carry.
         *
         * **The bound is counted over everything stored**, as the statement counts it over the
         * whole table. A fake counting only the records it was about to return would admit a name
         * the store refuses, and the bound would then be untested where it matters — a name common
         * in the corpus but rare in one batch.
         */
        override suspend fun namesakes(names: List<String>, carriedByAtMost: Int): List<ObservedPerson> {
            val wanted = names.toSet()
            val rare = wanted.filter { name ->
                stored.count { nameKey(it.displayName) == name } <= carriedByAtMost
            }.toSet()
            return stored.filter { nameKey(it.displayName) in rare }
        }

        override suspend fun nameFrequency(names: List<String>): Map<String, Int> =
            names.associateWith { name -> stored.count { nameKey(it.displayName) == name } }

        override suspend fun upsert(person: Person) = Unit
        override suspend fun find(externalId: ExternalId): Person? = null
        override suspend fun timesOf(externalId: ExternalId): RecordTimes? = null
        override suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier> = emptyList()
    }

    private class FakeConversations(stored: List<Conversation>) : ConversationRepository {
        private val rooms = stored.associateBy { it.externalId }.toMutableMap()

        /** A source renaming a topic, which is what makes the label worth re-reading. */
        fun renamed(conversation: ExternalId, name: String) {
            rooms[conversation] = requireNotNull(rooms[conversation]).copy(name = name)
        }

        override suspend fun conversations(externalIds: List<ExternalId>): List<Conversation> =
            externalIds.mapNotNull { rooms[it] }

        override suspend fun upsert(conversation: Conversation) = Unit
        override suspend fun find(externalId: ExternalId): Conversation? = null
        override suspend fun timesOf(externalId: ExternalId): RecordTimes? = null
        override suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier> = emptyList()
    }

    private class FakeClients(private val order: MutableList<String>) : ClientRepository {
        val stored = mutableMapOf<ExternalId, Client>()

        override suspend fun upsert(clients: List<Client>) {
            order += "client"
            clients.forEach { client ->
                val held = stored[client.conversation]
                stored[client.conversation] = client.copy(id = held?.id ?: client.id)
            }
        }

        override suspend fun of(conversations: List<ExternalId>): List<Client> =
            conversations.mapNotNull { stored[it] }
    }

    private class FakeMessages(
        private val roommates: List<Pair<ExternalId, ExternalId>>,
    ) : MessageRepository {
        override suspend fun sharingAConversation(
            authors: List<ExternalId>,
        ): List<Pair<ExternalId, ExternalId>> =
            roommates.filter { it.first in authors.toSet() && it.second in authors.toSet() }

        override suspend fun upsert(conversation: ExternalId, messages: List<Message>) = Unit

        // Neither pass here asks which conversation a message is in: indexing works from a
        // list of records, and identity works from the pairs that shared a room.
        override suspend fun conversationsOf(messages: List<ExternalId>): Map<ExternalId, ExternalId> =
            emptyMap()

        override suspend fun messagesIn(conversation: ExternalId): List<Message> = emptyList()
        override suspend fun latestIn(conversation: ExternalId, limit: Int): List<Message> = emptyList()
        override suspend fun messages(externalIds: List<ExternalId>): List<Message> = emptyList()
        override suspend fun timesOf(externalId: ExternalId): RecordTimes? = null
        override suspend fun identifiersOf(externalId: ExternalId): List<ObservedIdentifier> = emptyList()

        // Ingestion's, not this pass's: identity reads the pairs that shared a room and writes
        // nothing back to the corpus.
        override suspend fun markWithdrawn(externalIds: List<ExternalId>) = Unit
    }

    /**
     * Contacts, links and constraints, in memory.
     *
     * [upsert] keeps the identifier a record already has, which is the store's documented
     * contract and the thing the pass relies on to be re-runnable. [merge] likewise leaves a
     * stored link alone rather than rescoring it.
     */
    private class FakeContacts(private val order: MutableList<String>) : ContactRepository {
        val stored = mutableMapOf<ExternalId, Contact>()
        val links = mutableListOf<MergeLink>()
        val kept = mutableListOf<CandidateMerge>()
        val separated = mutableListOf<Separation>()

        /**
         * Every candidate handed over, before [keep]'s own replacement collapses it.
         *
         * The stored map cannot show a pair offered twice, because the store replaces a kept
         * pair by key and `CandidateMerge.of` orders it — so both scorings of one pair are the
         * same row. What the pass offered is the only place the duplicate is visible.
         */
        val offered = mutableListOf<CandidateMerge>()

        override suspend fun upsert(contacts: List<Contact>) {
            order += "contact"
            contacts.forEach { contact ->
                val held = stored[contact.record]
                stored[contact.record] = contact.copy(id = held?.id ?: contact.id)
            }
        }

        override suspend fun of(records: List<ExternalId>): List<Contact> =
            records.mapNotNull { stored[it] }

        override suspend fun merge(links: List<MergeLink>) {
            order += "merge"
            links.forEach { link -> if (this.links.none { it.merged == link.merged }) this.links += link }
        }

        override suspend fun linkOf(contact: EntityId): MergeLink? = links.firstOrNull { it.merged == contact }

        override suspend fun unmerge(contact: EntityId) {
            val link = links.firstOrNull { it.merged == contact } ?: return
            links -= link
            separated += Separation.of(link.merged, link.canonical)
        }

        override suspend fun heads(contacts: List<EntityId>): Map<EntityId, EntityId> =
            contacts.associateWith { contact ->
                var head = contact
                while (true) {
                    head = links.firstOrNull { it.merged == head }?.canonical ?: break
                }
                head
            }

        /**
         * The clusters whole, as the store returns them: every contact resolving to a touched
         * head, including ones nobody asked about.
         *
         * Mirroring the store here is what makes the cross-batch constraint testable at all. A
         * fake that returned only the seeds would agree with [heads] and hide the case where a
         * member merged on an earlier pass is the one a constraint forbids.
         */
        override suspend fun clusters(contacts: List<EntityId>): List<Cluster> {
            val everyone = heads(stored.values.map { it.id })
            return heads(contacts).values.toSet().map { head ->
                val members = everyone.filterValues { it == head }.keys
                Cluster(head, members.map { member -> stored.values.first { it.id == member } })
            }
        }

        override suspend fun separations(among: List<EntityId>): List<Separation> =
            separated.filter { it.one in among.toSet() && it.other in among.toSet() }

        override suspend fun keep(candidates: List<CandidateMerge>) {
            order += "keep"
            offered += candidates
            candidates.forEach { candidate ->
                kept.removeAll { it.one == candidate.one && it.other == candidate.other }
                kept += candidate
            }
        }

        override suspend fun candidates(among: List<EntityId>): List<CandidateMerge> =
            kept.filter { it.one in among.toSet() || it.other in among.toSet() }

        fun recordOf(contact: EntityId): ExternalId =
            stored.entries.first { it.value.id == contact }.key

        /** The contacts grouped by the head they resolve to, which is one group per human. */
        suspend fun clustered(): List<Set<EntityId>> {
            val ids = stored.values.map { it.id }
            return heads(ids).entries.groupBy({ it.value }, { it.key }).values.map { it.toSet() }
        }
    }

    private fun id(label: String) = ExternalId(Source.TELEGRAM, label)

    private fun person(externalId: ExternalId, displayName: String?, vararg claims: ObservedIdentifier) =
        ObservedPerson(externalId = externalId, displayName = displayName, claims = claims.toList())

    private fun room(externalId: ExternalId, kind: ConversationKind, name: String?) =
        Conversation(externalId = externalId, kind = kind, name = name)

    private fun claim(type: IdentifierType, raw: String) = ObservedIdentifier(
        identifier = requireNotNull(Identifier.of(type, raw)),
        addedAt = Instant.parse("2024-01-01T00:00:00Z"),
        lastSeenAt = Instant.parse("2024-01-01T00:00:00Z"),
    )
}
