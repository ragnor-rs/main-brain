package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Source
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Scope resolution, against a fake search.
 *
 * The rules are the whole of this class, and each of them is a decision that is invisible once
 * taken: a composed subject and a guessed one produce the same shape of answer, and the
 * difference only shows up as a confident reply about the wrong organisation. So every rule has
 * a case here, including the two that must *not* fire — a hop that would run twice, and a
 * question asked where one match was enough.
 */
class ScopeResolverTest {

    @Test
    fun `a unique acceptable match resolves, with nothing else carried`(): Unit = runBlocking {
        val only = contact("a lead")
        val search = FakeSearch(matches = listOf(only))

        val scope = ScopeResolver(search).resolve("a lead", setOf(SubjectKind.CONTACT))

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(only, resolved.subject.focus)
        assertTrue(resolved.subject.context.isEmpty())
        assertTrue(resolved.alternatives.isEmpty())
    }

    @Test
    fun `several related matches compose, narrow in focus and broad as context`(): Unit = runBlocking {
        // A contact and the client it belongs to are a hierarchy, not competing readings of one
        // name. The client scores higher on purpose: composing is decided by the relation, and
        // a resolver that picked the strongest match would answer about the organisation when
        // somebody asked about a person.
        val client = client("an organisation", score = 0.9)
        val person = contact("an organisation lead", score = 0.4, parents = listOf(client.referent))
        val search = FakeSearch(matches = listOf(client, person))

        val scope = ScopeResolver(search)
            .resolve("an organisation", setOf(SubjectKind.CONTACT, SubjectKind.CLIENT))

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(person, resolved.subject.focus)
        assertEquals(listOf(client), resolved.subject.context)
        assertTrue(
            resolved.alternatives.isEmpty(),
            "The broader subject is context and was already used. Carrying it as an " +
                "alternative too would offer the reader a choice that was never a choice.",
        )
    }

    @Test
    fun `a broader subject the shape cannot answer for is still context, not a competitor`(): Unit = runBlocking {
        // Shape #1 accepts a contact only, so the client it belongs to can never be the focus.
        // Reading the chain from the acceptable matches alone drops it, and it reappears as a
        // losing alternative — turning the hierarchy into the competition the rule forbids.
        val organisation = client("an organisation", score = 0.9)
        val lead = contact("a lead", score = 0.4, parents = listOf(organisation.referent))
        val search = FakeSearch(matches = listOf(organisation, lead))

        val scope = ScopeResolver(search).resolve("an organisation", setOf(SubjectKind.CONTACT))

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(lead, resolved.subject.focus)
        assertEquals(listOf(organisation), resolved.subject.context)
        assertTrue(
            resolved.alternatives.isEmpty(),
            "The client was offered as a competing reading of the name rather than used as " +
                "the context the focus sits under.",
        )
    }

    @Test
    fun `the traverse path keeps what the name actually matched as context`(): Unit = runBlocking {
        // The client is the one thing that was certainly searched for. Replacing the matched
        // set with the hop's results loses it entirely — neither context nor alternative — so
        // the answer cannot say what it was answered against.
        val organisation = client("an organisation")
        val lead = contact("its lead", parents = listOf(organisation.referent))
        val search = FakeSearch(
            matches = listOf(organisation),
            inside = mapOf(organisation.referent to listOf(lead)),
        )

        val scope = ScopeResolver(search).resolve("an organisation", setOf(SubjectKind.CONTACT))

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(lead, resolved.subject.focus)
        assertEquals(listOf(organisation), resolved.subject.context)
    }

    @Test
    fun `two matches holding one subject offer it once, not as a choice between itself`(): Unit = runBlocking {
        // The fan-out is what creates the duplicate: each matched client returns the shared
        // contact once. Undeduplicated, the pair reaches `narrowest` as two entries and the
        // request becomes a menu offering the same subject twice.
        val one = client("an organisation", score = 0.8)
        val other = client("an organisation group", score = 0.5)
        val shared = contact("a lead", parents = listOf(one.referent))
        val search = FakeSearch(
            matches = listOf(one, other),
            inside = mapOf(one.referent to listOf(shared), other.referent to listOf(shared)),
        )

        val scope = ScopeResolver(search).resolve("an organisation", setOf(SubjectKind.CONTACT))

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(shared, resolved.subject.focus)
    }

    @Test
    fun `a contact under several strands of one body of work composes with all of them`(): Unit = runBlocking {
        // One body of work keeping a thread per strand of it is several clients, so the contact
        // inside it names them all as parents. Tested for equality against a single parent, only
        // the first would have counted: the others would have been nobody's parent, so each would
        // have reached `narrowest` in its own right and the request would have become a menu
        // between the contact and the strands of the work they are inside.
        val supply = client("a body of work — supply", score = 0.9)
        val packing = client("a body of work — packing", score = 0.8)
        val person = contact("a lead", score = 0.4, parents = listOf(supply.referent, packing.referent))
        val search = FakeSearch(matches = listOf(supply, packing, person))

        val scope = ScopeResolver(search)
            .resolve("a body of work", setOf(SubjectKind.CONTACT, SubjectKind.CLIENT))

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(person, resolved.subject.focus)
        assertEquals(
            setOf(supply, packing),
            resolved.subject.context.toSet(),
            "Every strand the contact sits under is context: they are one broader subject held " +
                "as several records, so leaving one out drops part of what the answer is " +
                "answered against.",
        )
        assertTrue(
            resolved.alternatives.isEmpty(),
            "A strand was offered as a competing reading of the name rather than used as context.",
        )
    }

    @Test
    fun `a body of work named by its own name is the subject, with its strands as context`(): Unit = runBlocking {
        // The case the menu used to be asked for. A name picking out the work matches every strand
        // of it, and nothing among the strands is another's parent — so each reached `narrowest` in
        // its own right and the reader was asked which one they meant, when what they named was all
        // of them. The work naming its strands is what makes it the one narrow subject here.
        val supply = client("a body of work — supply", score = 0.7)
        val packing = client("a body of work — packing", score = 0.7)
        val work = bodyOfWork(
            "a body of work — packing",
            score = 0.7,
            strands = listOf(supply.referent, packing.referent),
        )
        val search = FakeSearch(matches = listOf(supply, packing, work))

        val scope = ScopeResolver(search).resolve(
            "a body of work",
            setOf(SubjectKind.CLIENT, SubjectKind.BODY_OF_WORK),
        )

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(work, resolved.subject.focus)
        assertEquals(
            setOf(supply, packing),
            resolved.subject.context.toSet(),
            "Every strand is what the work is read through, so one left out of the context is a " +
                "strand the answer cannot reach.",
        )
        assertTrue(
            resolved.alternatives.isEmpty(),
            "A strand was announced as something the name might have meant instead, which is the " +
                "menu this case exists to have stopped asking.",
        )
    }

    @Test
    fun `strands naming two bodies of work are still asked between`(): Unit = runBlocking {
        // The collision the rule leaves alone. Where the matched clients lead with different names,
        // nothing reports a body of work and these are two pieces of work that share a word — so
        // the menu is the right answer and this is the resolver proving it still arrives.
        val one = client("one body of work — haulage", score = 0.5)
        val another = client("another body of work — haulage", score = 0.5)
        val search = FakeSearch(matches = listOf(one, another))

        val scope = ScopeResolver(search).resolve(
            "haulage",
            setOf(SubjectKind.CLIENT, SubjectKind.BODY_OF_WORK),
        )

        assertEquals(setOf(one, another), assertIs<Scope.Ask>(scope).options.toSet())
    }

    @Test
    fun `a body of work is not among the readings a menu offers`(): Unit = runBlocking {
        // The only answer to a menu is to name the option that was meant, and a body of work has no
        // name of its own to carry: its label is a strand's, so naming it reaches that strand. An
        // option nobody can pick is worse than one that is absent, because the reply asserts the
        // work was on offer. It stays in the set that decides whether anything resolved — here the
        // contact and the work are both narrowest, which is why this is a question at all.
        val supply = client("a body of work — supply", score = 0.7)
        val packing = client("a body of work — packing", score = 0.7)
        val work = bodyOfWork(
            "a body of work — packing",
            score = 0.7,
            strands = listOf(supply.referent, packing.referent),
        )
        val person = contact("a body of work lead", score = 0.6)
        val search = FakeSearch(matches = listOf(supply, packing, work, person))

        val scope = ScopeResolver(search).resolve(
            "a body of work",
            setOf(SubjectKind.CONTACT, SubjectKind.CLIENT, SubjectKind.BODY_OF_WORK),
        )

        val asked = assertIs<Scope.Ask>(scope)
        assertEquals(setOf(supply, packing, person), asked.options.toSet())
        assertTrue(
            asked.options.none { it.kind == SubjectKind.BODY_OF_WORK },
            "A body of work was offered as something to name back, and naming its label resolves " +
                "to the strand it borrowed it from rather than to the work.",
        )
    }

    @Test
    fun `a shape that cannot answer for a body of work reaches the people inside its strands`(): Unit =
        runBlocking {
            // A reply goes to a person, so the work is traversed like any other unacceptable kind.
            // The hop is through the strands rather than through the work: a body of work holds
            // nothing that is not inside one of them, and its own hop answers nothing.
            val supply = client("a body of work — supply")
            val packing = client("a body of work — packing")
            val work = bodyOfWork(
                "a body of work — packing",
                strands = listOf(supply.referent, packing.referent),
            )
            val person = contact("a lead", score = 0.6)
            val search = FakeSearch(
                matches = listOf(work, supply, packing),
                inside = mapOf(supply.referent to listOf(person)),
            )

            val scope = ScopeResolver(search).resolve("a body of work", setOf(SubjectKind.CONTACT))

            val resolved = assertIs<Scope.Resolved>(scope)
            assertEquals(person, resolved.subject.focus)
            assertTrue(
                search.hops.contains(work.referent),
                "The work was never asked what is inside it, so a search that did answer for one " +
                    "would go unread.",
            )
            assertTrue(
                resolved.alternatives.none { it.kind == SubjectKind.BODY_OF_WORK },
                "The work was announced as a name to ask by, and asking by its label reaches the " +
                    "strand it borrowed it from rather than the work.",
            )
        }

    @Test
    fun `a parent nobody searched for does not take its siblings out of the context`(): Unit = runBlocking {
        // The walk is through the matched set, so a parent that was never searched for is not
        // context — but stopping at it would drop the strands that were. Written as a chain that
        // breaks on the first absent link, a contact whose first strand went unmatched would be
        // answered with no context at all.
        val matched = client("a body of work — supply", score = 0.9)
        val unmatched = Referent.OfClient(EntityId.mint())
        val person = contact("a lead", score = 0.4, parents = listOf(unmatched, matched.referent))
        val search = FakeSearch(matches = listOf(matched, person))

        val scope = ScopeResolver(search)
            .resolve("a body of work", setOf(SubjectKind.CONTACT, SubjectKind.CLIENT))

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(person, resolved.subject.focus)
        assertEquals(listOf(matched), resolved.subject.context)
    }

    @Test
    fun `several unrelated matches ask, ordered by score`(): Unit = runBlocking {
        val weaker = client("one organisation", score = 0.3)
        val stronger = client("another organisation", score = 0.8)
        val search = FakeSearch(matches = listOf(weaker, stronger))

        val scope = ScopeResolver(search).resolve("organisation", setOf(SubjectKind.CLIENT))

        val ask = assertIs<Scope.Ask>(scope)
        assertEquals(
            listOf(stronger, weaker),
            ask.options,
            "A menu leads with the likeliest. The ordering is a courtesy and not a decision, " +
                "which is why this is still a question.",
        )
    }

    /**
     * The account the corpus was read through is not one of the things a name might have meant.
     *
     * In a personal archive it carries the reader's own name, so a question naming any part of it
     * matched the reader beside the person meant — and the menu that produced is answerable only by
     * naming an option, which returns the same menu. The counterpart is what the question is about.
     */
    @Test
    fun `the account read through is not offered beside the person a name means`(): Unit = runBlocking {
        val asking = reader("the reader", score = 0.9)
        val meant = contact("the reader's counterpart", score = 0.4)
        val search = FakeSearch(matches = listOf(asking, meant))

        val scope = ScopeResolver(search).resolve("reader", setOf(SubjectKind.CONTACT))

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(meant, resolved.subject.focus, "The stronger match was the reader, not a subject.")
        assertTrue(
            resolved.alternatives.isEmpty(),
            "An alternative is announced as something the name might have meant instead, and the " +
                "account the corpus was read through is never that.",
        )
    }

    /** Two people and the reader is still a question, asked between the two. */
    @Test
    fun `the account read through is dropped from a menu it would otherwise be on`(): Unit = runBlocking {
        val asking = reader("the reader", score = 0.9)
        val one = contact("one counterpart", score = 0.5)
        val another = contact("another counterpart", score = 0.4)
        val search = FakeSearch(matches = listOf(asking, one, another))

        val scope = ScopeResolver(search).resolve("counterpart", setOf(SubjectKind.CONTACT))

        assertEquals(listOf(one, another), assertIs<Scope.Ask>(scope).options)
    }

    /**
     * A name that means nobody else still resolves to the account read through.
     *
     * The rule is about a choice offered to a reader and not about what is answerable. Dropped from
     * the matches outright, a question about oneself would say nothing was found — and `Ask` cannot
     * be constructed from what is left, so emptying the competing set has to fall back to it.
     */
    @Test
    fun `a name that means only the account read through resolves to it`(): Unit = runBlocking {
        val asking = reader("the reader")
        val search = FakeSearch(matches = listOf(asking))

        val scope = ScopeResolver(search).resolve("the reader", setOf(SubjectKind.CONTACT))

        assertEquals(asking, assertIs<Scope.Resolved>(scope).subject.focus)
    }

    /**
     * Two accounts of the reader that nothing has merged are still a question between them.
     *
     * Not a case worth a rule of its own: both are the same human, so neither is a better answer
     * than the other. What it must not do is produce a menu of one or an empty one, which is what
     * dropping the last competing option would mean.
     */
    @Test
    fun `two unmerged accounts of the reader are asked between rather than dropped`(): Unit = runBlocking {
        val one = reader("the reader", score = 0.9)
        val another = reader("the reader", score = 0.5)
        val search = FakeSearch(matches = listOf(one, another))

        val scope = ScopeResolver(search).resolve("the reader", setOf(SubjectKind.CONTACT))

        assertEquals(listOf(one, another), assertIs<Scope.Ask>(scope).options)
    }

    /**
     * Traversal is where this matters most: the reader wrote in everything.
     *
     * What is inside a body of work is read from who wrote in it, so without the rule the account
     * the archive was read through is among the people offered for every client in the corpus.
     */
    @Test
    fun `the account read through is not among the people a body of work resolves to`(): Unit = runBlocking {
        val organisation = client("an organisation")
        // Each carries the container as its parent, as the search reports a hop: the organisation is
        // then the context the focus sits under rather than a reading competing with it.
        val lead = contact("its lead", parents = listOf(organisation.referent))
        val asking = reader("the reader", score = 1.0, parents = listOf(organisation.referent))
        val search = FakeSearch(
            matches = listOf(organisation),
            inside = mapOf(organisation.referent to listOf(asking, lead)),
        )

        val scope = ScopeResolver(search).resolve("an organisation", setOf(SubjectKind.CONTACT))

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(lead, resolved.subject.focus)
        assertEquals(listOf(organisation), resolved.subject.context)
        assertTrue(resolved.alternatives.isEmpty())
    }

    @Test
    fun `an unacceptable kind is traversed one hop and re-judged`(): Unit = runBlocking {
        // A reply requested for a client resolves to that client's contacts rather than
        // objecting that an organisation cannot receive a message.
        val organisation = client("an organisation")
        val lead = contact("its lead")
        val search = FakeSearch(
            matches = listOf(organisation),
            inside = mapOf(organisation.referent to listOf(lead)),
        )

        val scope = ScopeResolver(search).resolve("an organisation", setOf(SubjectKind.CONTACT))

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(lead, resolved.subject.focus)
        assertEquals(listOf(organisation.referent), search.hops)
    }

    @Test
    fun `the hop is taken once, not followed as far as the relations reach`(): Unit = runBlocking {
        // Both levels are the wrong kind. Without the bound this walks for as long as the chain
        // is, spending a read per level and answering about something several removes from what
        // was asked.
        val outer = client("an outer organisation")
        val inner = client("an inner organisation")
        val search = FakeSearch(
            matches = listOf(outer),
            inside = mapOf(outer.referent to listOf(inner), inner.referent to listOf(contact("a lead"))),
        )

        val scope = ScopeResolver(search).resolve("an organisation", setOf(SubjectKind.CONTACT))

        assertIs<Scope.NotFound>(scope)
        assertEquals(
            listOf(outer.referent),
            search.hops,
            "A second hop was taken. One is the bound, and a contact two relations away is not " +
                "what the question named.",
        )
    }

    @Test
    fun `no match says so, and says what the search read rather than what the shape accepts`(): Unit = runBlocking {
        // The two sets differ in both directions here, which is what makes this discriminating: the
        // search reads conversations this shape would refuse, and the shape accepts the inbox no
        // search reads. Asserted as equality against the coverage, so filling it from the accepted
        // set fails rather than passing on an overlap.
        val search = FakeSearch()

        val scope = ScopeResolver(search).resolve("nobody", setOf(SubjectKind.CONTACT, SubjectKind.INBOX))

        val missing = assertIs<Scope.NotFound>(scope)
        assertEquals(SEARCHABLE, missing.searched)
        assertEquals("nobody", missing.name)
    }

    @Test
    fun `a shape accepting the inbox does not report it as searched`(): Unit = runBlocking {
        // The measured fault: the shape answering where things stand accepts the inbox, so a name
        // that resolved to nothing told the reader their name was looked for among their inbox —
        // which has no entity and no record to match a name against.
        val accepts = setOf(SubjectKind.CONTACT, SubjectKind.CLIENT, SubjectKind.CONVERSATION, SubjectKind.INBOX)

        val scope = ScopeResolver(FakeSearch()).resolve("nobody", accepts)

        val missing = assertIs<Scope.NotFound>(scope)
        assertFalse(
            SubjectKind.INBOX in missing.searched,
            "Reported the inbox as searched. Nothing matches a name against it.",
        )
    }

    @Test
    fun `a search covering less than every kind reports only what it covers`(): Unit = runBlocking {
        // The coverage is the search's own, not a constant standing in for it: a read that does not
        // union a kind must not have that kind claimed on its behalf.
        val search = FakeSearch(covers = setOf(SubjectKind.CONTACT))

        val scope = ScopeResolver(search).resolve("nobody", setOf(SubjectKind.CONTACT, SubjectKind.CLIENT))

        assertEquals(setOf(SubjectKind.CONTACT), assertIs<Scope.NotFound>(scope).searched)
    }

    @Test
    fun `an unacceptable match with nothing inside it reports the coverage, not the accepted kinds`(): Unit =
        runBlocking {
            // The traverse path's own refusal. It reaches nothing-found by a different route and
            // filled the same field the same wrong way.
            val organisation = client("an organisation")
            val search = FakeSearch(matches = listOf(organisation), inside = emptyMap())

            val scope = ScopeResolver(search).resolve("an organisation", setOf(SubjectKind.CONTACT, SubjectKind.INBOX))

            assertEquals(SEARCHABLE, assertIs<Scope.NotFound>(scope).searched)
        }

    @Test
    fun `a name the corpus holds a near spelling of is not reported as an absence`(): Unit = runBlocking {
        // The measured fault. A reader writes a name whose stored form diverges after its opening,
        // nothing matches, and the reply says nothing suitable was found — which is a statement
        // about the corpus the search is in no position to make, and the one conclusion a reader
        // cannot check. Asserted as the type and not only as the payload, because what has to
        // change is which reply is made.
        val search = FakeSearch(matches = emptyList(), near = listOf(contact("Arbour Warden")))

        val scope = ScopeResolver(search).resolve("Arbitrage", setOf(SubjectKind.CONTACT, SubjectKind.INBOX))

        val near = assertIs<Scope.Nearest>(scope)
        assertEquals(listOf("Arbour Warden"), near.nearest.map { it.name })
        assertEquals("Arbitrage", near.name)
        // The coverage still comes from the search, never from the accepted set — the same
        // distinction the sibling verdict carries, and the inbox is in neither.
        assertEquals(SEARCHABLE, near.searched)
    }

    @Test
    fun `near spellings are asked for only where nothing matched`(): Unit = runBlocking {
        // Ordering is what makes this free of regression: a name that resolves today never reaches
        // the near read, so no widening can change what it resolves to. Asserted on the call and
        // not on the verdict, since a verdict cannot show a read that was spent and discarded.
        val search = FakeSearch(matches = listOf(contact("a name")), near = listOf(contact("a near name")))

        val scope = ScopeResolver(search).resolve("a name", setOf(SubjectKind.CONTACT))

        assertIs<Scope.Resolved>(scope)
        assertEquals(
            emptyList(),
            search.nearAsked,
            "Read near spellings for a name that matched. That read is only ever a last resort.",
        )
    }

    @Test
    fun `a name with no near spelling is still an absence, in the same verdict as before`(): Unit = runBlocking {
        // The other half of the split, and the one that must not move: where the corpus holds
        // nothing spelled like the name, the reply is the absence it always was. Without this the
        // change could have replaced the old verdict rather than divided it.
        val search = FakeSearch(matches = emptyList(), near = emptyList())

        val scope = ScopeResolver(search).resolve("nobody at all", setOf(SubjectKind.CONTACT))

        val missing = assertIs<Scope.NotFound>(scope)
        assertEquals("nobody at all", missing.name)
        assertEquals(listOf("nobody at all"), search.nearAsked, "Reported an absence without looking for a near form.")
    }

    @Test
    fun `a near verdict carrying no names is refused`() {
        // Enforced at the type because the key and the list are one statement: rendered with an
        // empty list the reply tells a reader there are near spellings and shows none, which reads
        // as an absence worded as a near miss.
        val refused = assertFailsWith<IllegalArgumentException> {
            Scope.Nearest(nearest = emptyList(), searched = setOf(SubjectKind.CONTACT), name = "a name")
        }

        assertTrue("NotFound" in refused.message.orEmpty(), "Said nothing about why: ${refused.message}")
    }

    @Test
    fun `a near verdict claiming the inbox was searched is refused`() {
        // The sibling verdict refuses this and so must this one: both render the field to a reader
        // as the places their name was looked for, and a second producer would otherwise have to
        // remember the rule on its own.
        val refused = assertFailsWith<IllegalArgumentException> {
            Scope.Nearest(
                nearest = listOf(contact("a near name")),
                searched = setOf(SubjectKind.CONTACT, SubjectKind.INBOX),
                name = "a name",
            )
        }

        assertTrue("inbox" in refused.message.orEmpty(), "Said nothing about why: ${refused.message}")
    }

    @Test
    fun `a verdict claiming the inbox was searched is refused`() {
        // Enforced at the type rather than trusted to every producer, because the claim is rendered
        // to a reader and a fourth producer would otherwise have to remember this on its own.
        val refused = assertFailsWith<IllegalArgumentException> {
            Scope.NotFound(searched = setOf(SubjectKind.CONTACT, SubjectKind.INBOX), name = "a name")
        }

        assertTrue("inbox" in refused.message.orEmpty(), "Said nothing about why: ${refused.message}")
    }

    @Test
    fun `matches of an unacceptable kind are carried as labels, not retrieved for`(): Unit = runBlocking {
        // The losing candidate is the wrong kind for this shape, and it still reaches generation
        // as a label — which is what makes a wrong subject recoverable in one turn rather than
        // invisible.
        val lead = contact("a name")
        val thread = conversation("a thread of the same name")
        val search = FakeSearch(matches = listOf(lead, thread))

        val scope = ScopeResolver(search).resolve("a name", setOf(SubjectKind.CONTACT))

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(lead, resolved.subject.focus)
        assertEquals(listOf(thread), resolved.alternatives)
        assertTrue(search.hops.isEmpty(), "Nothing of an acceptable kind was missing, so nothing should have hopped.")
    }

    @Test
    fun `the inbox is reached by a request having named nobody, and searches for nothing`(): Unit = runBlocking {
        val search = FakeSearch()

        val scope = ScopeResolver(search).resolve(SubjectName.None, accepts = setOf(SubjectKind.INBOX))

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(SubjectKind.INBOX, resolved.subject.focus.kind)
        assertTrue(
            search.namesAsked.isEmpty(),
            "The inbox has no entity and no record behind it, so searching for it asks the " +
                "store a question with no answer.",
        )
    }

    @Test
    fun `one subject recorded twice is not an alternative reading of its own name`(): Unit = runBlocking {
        // Measured against a real corpus: a person is present both as a contact and as the direct
        // conversation with them, carrying the same label under different referents. Excluding
        // only the chosen referent left the other to be announced, so the reader was told that
        // the name they had just asked about was not the one answered — which reads as the
        // assistant having drafted to somebody else.
        val name = "a counterpart"
        val search = FakeSearch(matches = listOf(contact(name), conversation(name)))

        val scope = ScopeResolver(search).resolve(name, setOf(SubjectKind.CONTACT))

        val resolved = assertIs<Scope.Resolved>(scope)
        assertEquals(name, resolved.subject.focus.name)
        assertTrue(
            resolved.alternatives.isEmpty(),
            "A record of the subject itself was offered as something else it might have meant: " +
                "${resolved.alternatives.map { it.name }}",
        )
    }

    @Test
    fun `a genuinely different subject of the same search is still an alternative`(): Unit = runBlocking {
        // The guard above must not swallow the case it exists beside: two different people whose
        // names both matched are competing readings, and dropping one would hide that the search
        // was ambiguous.
        val chosen = contact("one counterpart", score = 0.9)
        val other = contact("another counterpart", score = 0.4)
        val search = FakeSearch(matches = listOf(chosen, other))

        val scope = ScopeResolver(search).resolve("counterpart", setOf(SubjectKind.CONTACT))

        // Two acceptable matches with no hierarchy between them is a question, not a resolution —
        // so the alternative survives as an option rather than as one that lost.
        val ask = assertIs<Scope.Ask>(scope)
        assertEquals(listOf(chosen, other), ask.options)
    }

    @Test
    fun `a blank name is refused as a name rather than read as an absent one`() {
        // It used to reach here and resolve to the inbox, which made a blank string a third way
        // of saying *nobody* — and the one the caller could arrive at by accident. Which absence
        // it is is now the caller's to state, so the type refuses to carry the question.
        assertFailsWith<IllegalArgumentException> { SubjectName.Given("   ") }
    }

    @Test
    fun `a subject that was never established is neither the inbox nor a search`(): Unit = runBlocking {
        // The defect this whole state exists for: the shape accepting the inbox would otherwise
        // answer corpus-wide, confidently, to a question about one person.
        val search = FakeSearch(matches = listOf(contact("somebody")))

        val scope = ScopeResolver(search)
            .resolve(SubjectName.Unread, accepts = setOf(SubjectKind.INBOX, SubjectKind.CONTACT))

        assertEquals(Scope.Unread, scope)
        assertTrue(
            search.namesAsked.isEmpty(),
            "There is no name to search for, so a search here would be asking the store about " +
                "whatever the last caller happened to leave behind.",
        )
    }

    @Test
    fun `no name and no inbox is a subject missing, not a search that came back empty`(): Unit = runBlocking {
        // The defect: a command that takes a subject and was given no argument reached the
        // nothing-found verdict, so the reader was told the corpus held nothing about a subject
        // they had never named — about a search that never ran. What is missing is the argument.
        val search = FakeSearch(matches = listOf(contact("somebody")))

        val scope = ScopeResolver(search).resolve(SubjectName.None, accepts = setOf(SubjectKind.CONTACT))

        assertEquals(Scope.SubjectMissing, scope)
        assertTrue(
            search.namesAsked.isEmpty(),
            "There is no name to search for, so a verdict naming what was looked through would " +
                "be reporting work that did not happen.",
        )
    }

    @Test
    fun `naming nobody and never establishing a subject are two verdicts`(): Unit = runBlocking {
        // Both leave a reader having to name a subject, and they are still kept apart: one request
        // said nobody and the other said nothing at all. Collapsed into one verdict the domain
        // would lose what the request said.
        val resolver = ScopeResolver(FakeSearch())
        val accepts = setOf(SubjectKind.CONTACT)

        assertEquals(Scope.SubjectMissing, resolver.resolve(SubjectName.None, accepts))
        assertEquals(Scope.Unread, resolver.resolve(SubjectName.Unread, accepts))
    }

    @Test
    fun `nothing found always carries the name it searched for`(): Unit = runBlocking {
        // The field used to be nullable, and the absent case was how a request with no name at all
        // was reported as a failed search. Every route here now runs a read first, so there is
        // always a name to echo.
        val search = FakeSearch(matches = emptyList())

        val scope = ScopeResolver(search).resolve("a name nothing holds", setOf(SubjectKind.CONTACT))

        val missing = assertIs<Scope.NotFound>(scope)
        assertEquals("a name nothing holds", missing.name)
        assertEquals(listOf("a name nothing holds"), search.namesAsked)
        // And the coverage it reports is the search's, which is what a reader is owed: the places
        // their name was looked for. The path that ran no search reports no coverage because it
        // reports nothing at all — see the subject-missing cases above.
        assertEquals(search.covers, missing.searched)
    }

    @Test
    fun `resolving for a shape that accepts no subject is refused`() {
        // The runner skips resolution for such a shape. Reaching here means it did not, and
        // returning anything would hand a subject to something that declared it needs none.
        assertFailsWith<IllegalArgumentException> {
            runBlocking { ScopeResolver(FakeSearch()).resolve("a name", accepts = emptySet()) }
        }
    }

    @Test
    fun `asking which of one thing was meant is refused`() {
        assertFailsWith<IllegalArgumentException> { Scope.Ask(listOf(contact("the only one"))) }
    }

    // --- doubles and fixtures -------------------------------------------------------------

    /**
     * Resolve a request that named [name].
     *
     * Every rule above is about what a name matched, so spelling out the wrapper on each of them
     * would bury the rule under the state it is not about. The three cases that *are* about which
     * state arrived name it themselves.
     */
    private suspend fun ScopeResolver.resolve(name: String, accepts: Set<SubjectKind>): Scope =
        resolve(SubjectName.Given(name), accepts)

    private class FakeSearch(
        private val matches: List<Candidate> = emptyList(),
        private val inside: Map<Referent, List<Candidate>> = emptyMap(),
        override val covers: Set<SubjectKind> = SEARCHABLE,
        private val near: List<Candidate> = emptyList(),
    ) : SubjectSearch {

        val namesAsked = mutableListOf<String>()
        val nearAsked = mutableListOf<String>()
        val hops = mutableListOf<Referent>()

        override suspend fun matching(name: String): List<Candidate> {
            namesAsked += name
            return matches
        }

        override suspend fun nearest(name: String): List<Candidate> {
            nearAsked += name
            return near
        }

        override suspend fun within(referent: Referent): List<Candidate> {
            hops += referent
            return inside[referent].orEmpty()
        }
    }

    private companion object {

        /**
         * What a search reads: every kind but the inbox, which holds nothing to match a name
         * against. What the store's own query covers, so a refusal asserted here is the one a
         * reader gets.
         */
        val SEARCHABLE = setOf(
            SubjectKind.CONTACT,
            SubjectKind.CLIENT,
            SubjectKind.BODY_OF_WORK,
            SubjectKind.CONVERSATION,
        )

        fun contact(name: String, score: Double = 1.0, parents: List<Referent> = emptyList()) =
            Candidate(Referent.OfContact(EntityId.mint()), name, score, parents)

        /** The account the corpus was read through, which is who is asking rather than a subject. */
        fun reader(name: String, score: Double = 1.0, parents: List<Referent> = emptyList()) =
            Candidate(Referent.OfContact(EntityId.mint()), name, score, parents, isSelf = true)

        fun client(name: String, score: Double = 1.0, parents: List<Referent> = emptyList()) =
            Candidate(Referent.OfClient(EntityId.mint()), name, score, parents)

        /**
         * A body of work, carrying the strands it is read through.
         *
         * [name] is a strand's own label, which is what the search borrows: nothing stores the name
         * the strands share, so a reduced one would be a label no record holds. The strands are the
         * [Candidate.parents], which is the field the compose rule walks — see its own note.
         */
        fun bodyOfWork(name: String, score: Double = 1.0, strands: List<Referent> = emptyList()) =
            Candidate(Referent.OfBodyOfWork(name.substringBefore(" ")), name, score, strands)

        fun conversation(name: String, score: Double = 1.0) =
            Candidate(Referent.OfConversation(ExternalId(Source.TELEGRAM, name)), name, score)
    }
}
