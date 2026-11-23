package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.answer.Candidate
import io.github.ragnorrs.mainbrain.domain.answer.Referent
import io.github.ragnorrs.mainbrain.domain.answer.SubjectKind
import io.github.ragnorrs.mainbrain.domain.entity.Client
import io.github.ragnorrs.mainbrain.domain.entity.Contact
import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.entity.MergeLink
import io.github.ragnorrs.mainbrain.domain.record.Conversation
import io.github.ragnorrs.mainbrain.domain.record.ConversationKind
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import io.github.ragnorrs.mainbrain.domain.record.Identifier
import io.github.ragnorrs.mainbrain.domain.record.IdentifierType
import io.github.ragnorrs.mainbrain.domain.record.Message
import io.github.ragnorrs.mainbrain.domain.record.Person
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turning a name into the thing a request is about, against a real Postgres.
 *
 * **The claim under test is that one name is matched across every kind at once**, which is the
 * property three queries in turn could not have: asking the kinds in some order would make the order
 * the tie-breaker, and the first kind to match would win on evidence weaker than a later kind's.
 *
 * The other claim is that the score is absolute rather than a rank. Resolution's traverse rule merges
 * what several referents contain and orders the result, so two separate reads have to produce numbers
 * that mean the same thing — which a retrieval score could not give and which is why the rarity factor
 * is counted rather than assumed.
 *
 * **The rig is shared and nothing is emptied between tests**, so every name here is minted unique and
 * every assertion is scoped to the records the case wrote.
 */
class SubjectSearchStoreTest {

    private val search = JdbcSubjectSearch(StoreRig.database)
    private val conversations = JdbcConversationRepository(StoreRig.database)
    private val messages = JdbcMessageRepository(StoreRig.database)
    private val persons = JdbcPersonRepository(StoreRig.database)
    private val contacts = JdbcContactRepository(StoreRig.database)
    private val clients = JdbcClientRepository(StoreRig.database)

    @Test
    fun `a contact is found by its display name`(): Unit = runBlocking {
        val name = unique("Findable Person")
        val contact = aContact(aPerson("findable"), displayName = name)

        val found = search.matching(name).single()

        assertEquals(Referent.OfContact(contact), found.referent)
        assertEquals(name, found.name)
    }

    @Test
    fun `a client is found by its name`(): Unit = runBlocking {
        val name = unique("Findable Client")
        val conversation = aConversation("client thread", name = null)
        val client = EntityId.mint()
        clients.upsert(listOf(Client(id = client, conversation = conversation, name = name)))

        assertEquals(Referent.OfClient(client), search.matching(name).single().referent)
    }

    @Test
    fun `a client and the conversation it was read from are one carrier of their name`(): Unit = runBlocking {
        // The shape recognition actually produces, which the fixture above does not: a client's
        // label is read *from* a conversation, so the two always share a name. Counted apart, every
        // client in the corpus scores half of what any uniquely-named subject does — so a person
        // whose display name merely contains the query would outrank the body of work somebody
        // asked about by name. The scores are compared against a lone conversation rather than
        // against a literal, so the case survives the bands being retuned.
        val name = unique("Composed Body Of Work")
        val conversation = aConversation("composed", name = name)
        clients.upsert(listOf(Client(id = EntityId.mint(), conversation = conversation, name = name)))
        val lone = unique("Lone Body Of Work")
        aConversation("lone", name = lone)

        val found = search.matching(name)
        val alone = search.matching(lone).single()

        assertEquals(2, found.size, "The client and its conversation did not both come back.")
        found.forEach { candidate ->
            assertEquals(
                alone.score,
                candidate.score,
                "A name carried by a client and the one conversation it was read from counted as " +
                    "two subjects, so every client in the corpus ranks below a uniquely-named one.",
            )
        }
    }

    @Test
    fun `a conversation is found by its label`(): Unit = runBlocking {
        val name = unique("Findable Thread")
        val conversation = aConversation("labelled", name = name)

        assertEquals(Referent.OfConversation(conversation), search.matching(name).single().referent)
    }

    @Test
    fun `one name reaches every kind in a single ranking`(): Unit = runBlocking {
        // The property three queries in turn could not have. All three carry the same label, and all
        // three have to come back as competing readings of it.
        val name = unique("Shared Name")
        aContact(aPerson("shared"), displayName = name)
        val thread = aConversation("shared thread", name = name)
        val clientThread = aConversation("shared client", name = null)
        clients.upsert(listOf(Client(id = EntityId.mint(), conversation = clientThread, name = name)))
        // A second client built on the same leading name, so the one kind no branch of the union is
        // for is reachable too: a body of work is reported off the client rows, so a
        // fixture with one client could not produce one and the coverage assertion below would be
        // checking a smaller set than the read can return.
        val strandThread = aConversation("shared strand", name = null)
        clients.upsert(listOf(Client(id = EntityId.mint(), conversation = strandThread, name = "$name strand")))

        val found = search.matching(name)

        assertEquals(
            setOf(
                SubjectKind.CONTACT,
                SubjectKind.CLIENT,
                SubjectKind.BODY_OF_WORK,
                SubjectKind.CONVERSATION,
            ),
            found.map { it.kind }.toSet(),
        )
        // **And the coverage this search declares is that same set, measured rather than restated.**
        // A refusal tells a reader where their name was looked for by reporting `covers`, which is
        // written out because nothing parses the query — so this is what keeps the two in step. A
        // branch added to the union without an entry in `covers`, or an entry that no branch backs,
        // fails here.
        assertEquals(
            found.map { it.kind }.toSet(),
            search.covers,
            "The declared coverage is not the set of kinds one name actually comes back under.",
        )
        assertTrue(found.any { it.referent == Referent.OfConversation(thread) })
    }

    @Test
    fun `a person and the direct chat with them are one subject, not two readings`(): Unit = runBlocking {
        // **The case that made every person with a direct chat unanswerable.** A source addresses a
        // one-to-one conversation by the identifier of the person on the other side of it, and names
        // it after them — so the name matched a contact and a conversation, neither was the other's
        // parent, and the resolver asked which was meant. Both options read identically, and a menu
        // is answered by naming the option that was meant, so the answer returned the same menu for
        // as long as anybody tried.
        val name = unique("Direct Correspondent")
        val record = aPerson("direct-correspondent")
        val contact = aContact(record, displayName = name)
        aDirectChat(record, name = name)

        val found = search.matching(name)

        assertEquals(listOf(Referent.OfContact(contact)), found.map { it.referent })
    }

    @Test
    fun `a thread that merely shares a person's name still competes with them`(): Unit = runBlocking {
        // The collapse is on record identity, not on the names agreeing. A room somebody named after
        // a person is a different subject from that person, and offering both is the right answer.
        val name = unique("Shared With A Room")
        val contact = aContact(aPerson("shares-with-room"), displayName = name)
        val room = aConversation("a room named for them", name = name)

        val found = search.matching(name)

        assertEquals(
            setOf(Referent.OfContact(contact), Referent.OfConversation(room)),
            found.map { it.referent }.toSet(),
        )
    }

    @Test
    fun `a direct chat still resolves when the person behind it was never recognised`(): Unit = runBlocking {
        // Nothing is collapsed into a contact that is not there. Recognition runs after ingestion, so
        // a thread whose person has not been read yet is an ordinary state, and the conversation is
        // then the only handle anybody has on it.
        val name = unique("Unrecognised Correspondent")
        val chat = aDirectChat(aPerson("unrecognised"), name = name)

        assertEquals(listOf(Referent.OfConversation(chat)), search.matching(name).map { it.referent })
    }

    @Test
    fun `a person is found by their handle`(): Unit = runBlocking {
        // What makes a menu answerable: a label is not a key and two people can share one, so the
        // handle is the only name that resolves to exactly one of them.
        val handle = unique("handled").replace(" ", "_")
        val contact = aContact(aPerson("by-handle", handle = handle), displayName = unique("Some Name"))

        assertEquals(Referent.OfContact(contact), search.matching(handle).single().referent)
    }

    @Test
    fun `a handle resolves whether or not it is typed with its marker`(): Unit = runBlocking {
        // A reader types the marker, because that is how the platform shows a handle; the corpus
        // stores what the platform reported, which has none.
        val handle = unique("markered").replace(" ", "_")
        val contact = aContact(aPerson("by-marker", handle = handle), displayName = unique("Another Name"))

        assertEquals(Referent.OfContact(contact), search.matching("@$handle").single().referent)
        assertEquals(Referent.OfContact(contact), search.matching(handle).single().referent)
    }

    @Test
    fun `a marker with no handle after it matches nobody`(): Unit = runBlocking {
        // The marker is handed to the handle branch as it is, and no stored handle carries one, so
        // nothing matches — which is right, because nobody named a subject.
        aContact(aPerson("not-by-bare-marker", handle = unique("bare").replace(" ", "_")), unique("A Name"))

        assertTrue(search.matching("@").isEmpty(), "A bare marker matched something.")
    }

    @Test
    fun `a contact carries its handle however the name was matched`(): Unit = runBlocking {
        // The handle has to be on the candidate whatever the query agreed with, because what needs it
        // is the menu rather than the match: two people found by a display name they share are told
        // apart by handles neither of them was found by.
        val name = unique("Handled By Name")
        val handle = unique("carried").replace(" ", "_")
        aContact(aPerson("carries-handle", handle = handle), displayName = name)

        assertEquals(handle, search.matching(name).single().handle)
    }

    @Test
    fun `a handle typed with its marker scores as the exact match it is`(): Unit = runBlocking {
        // **The form the menu teaches is the form that must score.** The handle branch matches the
        // marker-less string, so scoring against what was typed instead misses both the exact and the
        // prefix band and leaves an identifier somebody typed in full at the weakest agreement there
        // is — which is what the ranking the port promises is read from.
        val handle = unique("marked").replace(" ", "_")
        aContact(aPerson("marked-exact", handle = handle), displayName = unique("Marked Owner"))

        val typedBare = search.matching(handle).single()
        val typedWithMarker = search.matching("@$handle").single()

        assertEquals(
            typedBare.score,
            typedWithMarker.score,
            "Typing the marker changed the score, so the same subject ranks differently " +
                "depending on how a reader wrote its handle.",
        )
    }

    @Test
    fun `the marker narrows a search to handles, leaving names that merely contain it`(): Unit = runBlocking {
        // Only the handle branch strips the marker, so a display name is searched for with it still
        // attached and no name carries one. That makes the marker a way of saying which kind of name
        // was meant — and it is why the menu can offer a handle as the answer to an ambiguous label.
        val handle = unique("narrowing").replace(" ", "_")
        aContact(aPerson("narrowing-exact", handle = handle), displayName = unique("Narrowing Owner"))
        aContact(aPerson("narrowing-coincidence"), displayName = "A $handle Coincidence")

        assertEquals(2, search.matching(handle).size, "The bare form should reach both.")
        assertEquals(
            listOf(handle),
            search.matching("@$handle").map { it.handle },
            "The marker did not narrow to the subject that carries the handle.",
        )
    }

    @Test
    fun `a subject with no handle carries none`(): Unit = runBlocking {
        val name = unique("Handleless")
        aContact(aPerson("no-handle"), displayName = name)

        assertNull(search.matching(name).single().handle)
    }

    @Test
    fun `an exact handle outranks a brush with somebody's name`(): Unit = runBlocking {
        // A handle is carried by one subject, so the rarity factor leaves it at full strength. An
        // identifier somebody typed in full should not lose to a substring of a stranger's name.
        val handle = unique("exacting").replace(" ", "_")
        aContact(aPerson("exact-handle", handle = handle), displayName = unique("Precise Owner"))
        aContact(aPerson("coincidental"), displayName = "A $handle Coincidence")

        val found = search.matching(handle).sortedByDescending { it.score }

        assertEquals(2, found.size)
        assertEquals(handle, found.first().handle)
        assertTrue(found.first().score > found.last().score)
    }

    @Test
    fun `matching ignores case and surrounding whitespace`(): Unit = runBlocking {
        val name = unique("Casefold Person")
        aContact(aPerson("casefold"), displayName = name)

        assertEquals(1, search.matching("  ${name.uppercase()}  ").size)
    }

    @Test
    fun `runs of whitespace inside a name are collapsed the same way on both sides`(): Unit = runBlocking {
        // The store folds the typed name and the stored label with one definition, so a run of
        // whitespace reduces the same way on both sides. Were the two sides to disagree nothing
        // would match, every name would read as unique, and the rarity factor would silently
        // become a constant.
        val name = unique("Spaced Out Person")
        aContact(aPerson("spaced"), displayName = name.replace(" ", "   "))

        assertEquals(1, search.matching(name).size)
    }

    @Test
    fun `a word from inside a name finds it, and scores below an exact match`(): Unit = runBlocking {
        val exact = unique("Precise")
        aContact(aPerson("precise-exact"), displayName = exact)
        aContact(aPerson("precise-longer"), displayName = "A $exact Person")

        val found = search.matching(exact).sortedByDescending { it.score }

        assertEquals(2, found.size)
        assertEquals(exact, found.first().name, "An exact match did not outrank a containing one.")
        assertTrue(found.first().score > found.last().score)
    }

    @Test
    fun `a name written in another script resolves the subject whose label is in the other`(): Unit =
        runBlocking {
            // **The case the corpus makes ordinary.** Sources store a display name in whatever script
            // the account was registered in, the interface answers in Russian, and a reader writes a
            // name the way their own question writes it. Compared as they stand the two forms share no
            // character at all, so this is not a weaker match than the Latin form — it is no match.
            //
            // Several names, because transliteration is not one mapping: each pair here turns on a
            // different letter whose renderings disagree, and a rule derived from one of them fails
            // on the next. The Latin half is what a source stored; the Cyrillic half is what a reader
            // types. Where a letter has a commoner and a rarer Latin rendering the pair uses the
            // commoner one — a fixture written in the rendering that happens to fold correctly
            // would leave the gap the fold exists to close untested.
            CROSS_SCRIPT.forEach { (cyrillic, latin) ->
                val (label, typed) = uniquely(latin, cyrillic)
                val contact = aContact(aPerson("cross-script-$latin"), displayName = label)

                assertTrue(
                    Referent.OfContact(contact) in search.matching(typed).map { it.referent },
                    "A name written as the question writes it did not reach the subject whose " +
                        "label holds the other script's form of it: $latin.",
                )
            }
        }

    @Test
    fun `a label stored in the corpus's script is reached by the Latin a reader writes`(): Unit =
        runBlocking {
            // The other direction, and this archive needs both: it holds display names in either
            // script, so a reader writing Latin against a label stored in Cyrillic is no more
            // hypothetical than the reverse. A fold that only transliterated the query would pass
            // every case above and fail this one.
            val (label, typed) = uniquely(CYRILLIC_LABEL, LATIN_FOR_CYRILLIC_LABEL)
            val contact = aContact(aPerson("latin-against-cyrillic"), displayName = label)

            assertTrue(
                Referent.OfContact(contact) in search.matching(typed).map { it.referent },
                "A Latin name did not reach the subject whose label is stored in Cyrillic.",
            )
        }

    @Test
    fun `the two scripts of one name reach the same subject and score alike`(): Unit = runBlocking {
        // The forms are two spellings of one name rather than two names, so neither is the weaker
        // evidence: a reader who typed the whole of it typed the whole of it. Were the bands read off
        // the typed string instead of the compared one, the Cyrillic form would land at the weakest
        // agreement there is while naming its subject exactly.
        val (cyrillic, latin) = CROSS_SCRIPT.first()
        val (label, typed) = uniquely(latin, cyrillic)
        val contact = aContact(aPerson("same-subject"), displayName = label)

        val byLatin = search.matching(label).single { it.referent == Referent.OfContact(contact) }
        val byCyrillic = search.matching(typed).single { it.referent == Referent.OfContact(contact) }

        assertEquals(byLatin.referent, byCyrillic.referent)
        assertEquals(byLatin.score, byCyrillic.score)
    }

    @Test
    fun `a short name does not match inside an unrelated longer one`(): Unit = runBlocking {
        // A short name sitting in the middle of an unrelated longer one is a coincidence of letters,
        // and what it costs is not a weak match but a question with no answer: the reader is offered
        // the subject they meant beside one they have never mentioned, and both options carry a label
        // that looks right.
        // The short name is typed as a reader would type it, without the tag the rig adds — appending
        // one would make the query agree with nothing but its own label and the case would pass
        // whatever the comparison did. So the assertions name the subjects rather than count them.
        val (own, unrelated) = uniquely("Port", "Transport Desk")
        val named = aContact(aPerson("short-own"), displayName = own)
        val stranger = aContact(aPerson("short-unrelated"), displayName = unrelated)

        val found = search.matching("Port").map { it.referent }

        assertTrue(Referent.OfContact(named) in found, "The subject whose name it is was not found.")
        assertFalse(
            Referent.OfContact(stranger) in found,
            "A name matched inside a word, so an unrelated subject is in the menu.",
        )
    }

    @Test
    fun `a name genuinely part of a longer one still resolves it`(): Unit = runBlocking {
        // The other side of the rule above, and the reason it is a word's start rather than a whole
        // word: the ways somebody under-specifies a name are to give one of its words or to give the
        // beginning of one, and both of those are how a subject is ordinarily referred to.
        // Asserted as membership rather than as a count, for the reason the case above gives: the
        // queries carry no tag, and the rig is shared with every other case in this module.
        val contact = aContact(aPerson("part-of-longer"), displayName = unique("Quarterly Shipment"))
        val referent = Referent.OfContact(contact)

        assertTrue(
            referent in search.matching("Shipment").map { it.referent },
            "A later word did not reach the subject its whole label names.",
        )
        assertTrue(
            referent in search.matching("Quarte").map { it.referent },
            "The beginning of a name did not reach the subject it names.",
        )
    }

    @Test
    fun `a name inflected by the sentence reaches the label carrying its plain form`(): Unit =
        runBlocking {
            // **What a question actually does to a name.** The language the interface answers in puts
            // a name in whatever case the sentence needs, while a label carries the plain form — and
            // the routing stage hands the name over as written, deliberately, since a name it composed
            // rather than copied would be a search spent on nothing. So the form that arrives here
            // ends in letters no label holds, and comparing the two as they stand finds the subject
            // only when the sentence happened to need the plain form.
            // The plain form first and the three cases a question puts it in after it, all carrying
            // one tag so that the label and every form typed for it agree.
            val forms = uniquely(
                "Zhurnal Priyoma",
                "\u0416\u0443\u0440\u043D\u0430\u043B\u0430 \u041F\u0440\u0438\u0451\u043C\u0430",
                "\u0416\u0443\u0440\u043D\u0430\u043B\u0443 \u041F\u0440\u0438\u0451\u043C\u0430",
                "\u0416\u0443\u0440\u043D\u0430\u043B\u043E\u043C \u041F\u0440\u0438\u0451\u043C\u0430",
            )
            val contact = aContact(aPerson("inflected"), displayName = forms.first())
            val referent = Referent.OfContact(contact)

            forms.drop(1).forEach { typed ->
                assertTrue(
                    referent in search.matching(typed).map { it.referent },
                    "An inflected name did not reach the label that carries its plain form: $typed.",
                )
            }
        }

    @Test
    fun `a name that resolves is not widened by the tolerance`(): Unit = runBlocking {
        // **The ordering of the two reads is what keeps the tolerance free of regression**, and this
        // is the case that says so. Both labels below are reached by the shortened form of the query
        // and only one by the query itself, so a read that shortened unconditionally — or that merged
        // the two results — would offer a second subject here. Asking strictly first and widening
        // only where nothing was found is the whole of what prevents it.
        val (wanted, other, typed) = uniquely("Prokat", "Prokata Sklad", "Prokat")
        val contact = aContact(aPerson("exact-wins"), displayName = wanted)
        val widened = aContact(aPerson("would-be-widened"), displayName = other)

        val found = search.matching(typed).map { it.referent }

        assertTrue(Referent.OfContact(contact) in found, "The exactly-named subject was not found.")
        assertFalse(
            Referent.OfContact(widened) in found,
            "A name that resolved on its own was widened anyway, so the tolerance ran when it " +
                "had nothing to repair.",
        )
    }

    @Test
    fun `a short name is never shortened, so the tolerance cannot reopen the mid-word fault`() {
        // The floor on the rule, asserted on the rule itself. A stem of two or three letters is not
        // evidence of anything, and the shortest names are exactly the ones that landed inside a
        // stranger's label before the comparison was anchored. Below five letters nothing is dropped,
        // so there is no pattern to widen with and the read does not run a second time.
        assertNull(JdbcSubjectSearch.stems("port"))
        assertNull(JdbcSubjectSearch.stems("stas"))
        assertEquals("% iuli%", JdbcSubjectSearch.stems("iulii"))
        assertEquals("% egoro%", JdbcSubjectSearch.stems("egorova"))
        // Every word is shortened and each is anchored at a word start, in order but not joined,
        // because it is the words of a name that inflect and not the gaps between them.
        assertEquals("% zhurna% prie%", JdbcSubjectSearch.stems("zhurnala priema"))
    }

    @Test
    fun `a name whose stored form parts after its opening is found as a near spelling`(): Unit = runBlocking {
        // **The measured case.** A source registered a given name in a form that is not a
        // transliteration of the one a reader writes, so the two agree on their opening and part
        // after it — which no fold and no inflection tolerance can cross, because there is no
        // character rule between them. Matching therefore finds nothing, and the reply that said so
        // told the reader their subject was absent from a corpus holding hundreds of their messages.
        //
        // Both halves are asserted here, because only the pair is the behaviour: the strict read
        // still finds nothing, and the near read finds the subject. Asserting the second alone would
        // pass just as well if the near rule had been folded into matching, which is the change this
        // must not become.
        val (stored, typed) = uniquely("Arbour Warden", "Arbitrage")
        val contact = aContact(aPerson("near-opening"), displayName = stored)
        val referent = Referent.OfContact(contact)

        assertFalse(
            referent in search.matching(typed).map { it.referent },
            "The strict read matched, so this case no longer exercises the near one.",
        )
        assertTrue(
            referent in search.nearest(typed).map { it.referent },
            "A name agreeing on its opening did not reach the subject the corpus stores it as.",
        )
    }

    @Test
    fun `a near spelling is anchored at a word's start, not found inside one`(): Unit = runBlocking {
        // The anchor is half the near rule and not formatting. Three letters landing mid-word are a
        // coincidence of letters at a width where almost every label has one, so without this a
        // reader is shown a list of strangers instead of the spelling they were looking for.
        //
        // The label below carries the typed name's opening inside its first word and nowhere at the
        // start of one, so dropping the anchor from the pattern fails exactly this assertion and
        // nothing else in the module.
        val (stranger, typed) = uniquely("Rhubarb Lane", "Arbitrage")
        val unrelated = aContact(aPerson("near-midword"), displayName = stranger)

        assertFalse(
            Referent.OfContact(unrelated) in search.nearest(typed).map { it.referent },
            "An opening matched inside a word, so an unrelated subject is offered as a spelling.",
        )
    }

    @Test
    fun `a name nothing is spelled nearly like has no near spelling either`(): Unit = runBlocking {
        // The arm that keeps an absence an absence. Where the corpus holds nothing agreeing even on
        // an opening, the near read answers empty and the reply is the absence it always was — which
        // is what makes the pair of replies a division of the old one rather than a replacement.
        assertEquals(emptyList<Candidate>(), search.nearest(unique("Zzyzx Nothing")))
    }

    @Test
    fun `a name too short to reduce has no near spelling`() {
        // The floor, asserted on the rule itself. A name that is its own opening cannot be widened,
        // and widening it would be a pattern most of the corpus satisfies — so the read answers
        // nothing rather than everything.
        assertNull(JdbcSubjectSearch.openings("por"))
        assertNull(JdbcSubjectSearch.openings("ab"))
        // Each word is cut to its opening however long it is, which is what tells this rule from the
        // inflection tolerance beside it: that one drops an ending, this one keeps a beginning.
        assertEquals("% arb%", JdbcSubjectSearch.openings("arbitrage"))
        assertEquals("% arb%", JdbcSubjectSearch.openings("arbitration"))
        // Anchored and in order but not joined, exactly as the tolerance's pattern is, so a two-word
        // name has to meet a label's words in sequence rather than anywhere among them.
        assertEquals("% arb% des%", JdbcSubjectSearch.openings("arbitrage desk"))
        // A word already at the opening's width survives beside one that is cut, which is what lets
        // a tagged label in this rig be reached at all.
        assertEquals("% arb% 001%", JdbcSubjectSearch.openings("arbitrage 001"))
    }

    @Test
    fun `a handle is not reduced, so nobody is offered as a near spelling of a key`(): Unit = runBlocking {
        // A handle is the source's key and a key reduced is a different key: offering its holder as a
        // near spelling of a typed name would say the key nearly matched, which is the one thing a
        // key cannot do. The near read therefore reaches the handle branch with a form no stored
        // handle holds, and this is the case that says so — the label shares no opening with what is
        // typed, so the only route to this subject is through its handle.
        val handle = unique("ledgerbook").replace(" ", "")
        val contact = aContact(aPerson("near-handle", handle = handle), displayName = unique("Rail Depot"))

        assertFalse(
            Referent.OfContact(contact) in search.nearest(handle).map { it.referent },
            "A handle was reduced to an opening, so its holder is offered as a near spelling of it.",
        )
    }

    @Test
    fun `one spelling is offered once, however many subjects carry it`(): Unit = runBlocking {
        // **Where the menu's rule is inverted.** Two subjects sharing a label are two options there
        // and are told apart by their handles, because the reader is choosing between them. Here
        // they are not subjects but forms to write, and two of them read identically — so the second
        // teaches nothing and spends one of the few lines on offer. Measured on the
        // archive, where labels do repeat across records, which is what makes this the ordinary case.
        val (shared, other, typed) = uniquely("Dorian Keep", "Dorian Keep", "Dormouse")
        aContact(aPerson("dup-label-one"), displayName = shared)
        aContact(aPerson("dup-label-two"), displayName = other)

        val found = search.nearest(typed).mapNotNull { it.name }

        assertEquals(listOf(shared), found, "The same spelling was offered more than once: $found")
    }

    @Test
    fun `near spellings are ordered by how much of the name agrees, not by the score`(): Unit = runBlocking {
        // **The score cannot order these and the port promises an order.** Every row this read
        // returns agreed on an opening rather than on a word, so all of them land in one agreement
        // band and the score reduces to rarity — how strongly a name means a subject, which is the
        // question the matching read asks and this one does not. Left to it, a spelling two subjects
        // share ranks below an unrelated unique one, and the bound then shows the reader the unique
        // stranger instead of the form they were looking for.
        //
        // The fixture is that case exactly: the nearest label is carried by two records, so its
        // rarity is halved, while the barely-agreeing one is unique and scores full. Ordering by the
        // score puts them the wrong way round.
        val tagged = uniquely("Benedict Works", "Benedict Works", "Benignity Hall", "Benediction")
        aContact(aPerson("agree-near-one"), displayName = tagged[0])
        aContact(aPerson("agree-near-two"), displayName = tagged[1])
        aContact(aPerson("agree-far"), displayName = tagged[2])

        val found = search.nearest(tagged[3]).mapNotNull { it.name }

        assertEquals(
            listOf(tagged[0], tagged[2]),
            found,
            "The near spellings came back ordered by something other than how much of the typed " +
                "name they hold, so the bound can cut the one the reader needed: $found",
        )
    }

    @Test
    fun `near spellings are bounded, because a reader reads them at a glance`(): Unit = runBlocking {
        // A reader is shown these to recognise one, and any of them is acted on the same way. So the
        // length of the list is bounded rather than left to how common an opening happened to be —
        // and the bound is asserted against more labels than it admits, so a read that returned
        // everything fails here rather than passing on a corpus that happened to be small.
        val tagged = uniquely(
            "Bergamot One",
            "Bergenia Two",
            "Bergschrund Three",
            "Bergamask Four",
            "Bergander Five",
            "Bergylt Six",
            "Bergamo Seven",
        )
        tagged.forEachIndexed { index, label -> aContact(aPerson("near-many-$index"), displayName = label) }

        val found = search.nearest("Bergenstock ${tagged.first().substringAfterLast(' ')}")

        // The number is written here rather than read from the constant it bounds: an expectation
        // taken from the code under test holds whatever that code does, so raising the bound would
        // pass and removing it would pass on a corpus that happened to be smaller than it.
        assertEquals(4, found.size, "The near read returned ${found.size} spellings for a seven-label opening.")
        assertTrue(
            found.size < tagged.size,
            "Every label sharing the opening came back, so nothing bounds how long the list a " +
                "reader is handed can be.",
        )
    }

    @Test
    fun `a handle keeps its separator and is reached from inside it`(): Unit = runBlocking {
        // **The casualty the word-start rule would have cost, if a handle were a name.** A handle is
        // the source's key: it has no words, a reader may write only the part of it they remember,
        // and two of them that differ by one reduced letter are two different people. So it is
        // matched unreduced and anywhere in the value — and the separator stays a character rather
        // than becoming a word break, which is what keeps it from colliding with somebody's label.
        val handle = unique("ledger").replace(" ", "_")
        val contact = aContact(aPerson("separated-handle", handle = handle), displayName = unique("Rail Depot"))
        val referent = Referent.OfContact(contact)

        assertTrue(
            referent in search.matching(handle.substringAfter("_")).map { it.referent },
            "A handle was not reachable from a part of it after the separator.",
        )
        assertTrue(
            referent in search.matching(handle).map { it.referent },
            "A handle written in full did not reach its subject.",
        )
        assertFalse(
            referent in search.matching(handle.replace("_", " ")).map { it.referent },
            "The separator folded to a space, so a handle now answers to a two-word label.",
        )
    }

    @Test
    fun `a name of nothing but punctuation searches nothing`(): Unit = runBlocking {
        // It folds away to the empty string, which must not be read as *a space somewhere in the
        // label*. Almost every label has one, so the failure this guards against is the whole corpus
        // coming back as readings of a name nobody typed.
        assertEquals(emptyList<Candidate>(), search.matching("!!!"))
    }

    @Test
    fun `a name carried by one subject scores higher than the same name carried by a crowd`(): Unit =
        runBlocking {
            // The rarity factor, which is what makes the score absolute. A rarer name is stronger
            // evidence, and a score that ignored this would rank a common first name with a unique
            // company equally.
            val rare = unique("Unrepeated")
            aContact(aPerson("rare"), displayName = rare)
            val common = unique("Repeated")
            repeat(4) { aContact(aPerson("common-$it"), displayName = common) }

            val rareScore = search.matching(rare).single().score
            val commonScore = search.matching(common).first().score

            assertTrue(
                rareScore > commonScore,
                "A name four subjects carry scored as highly as one only a single subject does.",
            )
        }

    @Test
    fun `a conversation that was read as a client sits under it rather than competing`(): Unit = runBlocking {
        // Which is what makes the two compose: the narrow subject is answered and the broad one is
        // context. Without the parent they are two unrelated matches and the resolver asks.
        val name = unique("Composing Thread")
        val conversation = aConversation("composing", name = name)
        val client = EntityId.mint()
        clients.upsert(listOf(Client(id = client, conversation = conversation, name = name)))

        val asConversation = search.matching(name).single { it.kind == SubjectKind.CONVERSATION }

        assertEquals(listOf(Referent.OfClient(client)), asConversation.parents)
    }

    @Test
    fun `a contact writing in one client's threads sits under that client`(): Unit = runBlocking {
        val name = unique("Client Member")
        val conversation = aConversation("member thread", name = null)
        val client = EntityId.mint()
        clients.upsert(listOf(Client(id = client, conversation = conversation, name = "Their Client")))
        val author = aPerson("member")
        messages.upsert(conversation, listOf(message(conversation, "hello", author)))
        aContact(author, displayName = name)

        assertEquals(listOf(Referent.OfClient(client)), search.matching(name).single().parents)
    }

    @Test
    fun `a contact writing in two bodies of work has no single parent`(): Unit = runBlocking {
        // Choosing one would assert a hierarchy the corpus does not state, and the answer would be
        // scoped to a client the person merely also talks to. The two labels agree on nothing, so
        // the leading-name rule has nothing to join and this is the case it must not fire on.
        val name = unique("Crosses Clients")
        val author = aPerson("crosser")
        listOf(unique("Alpha"), unique("Beta")).forEach { label ->
            val conversation = aConversation("crossed-$label", name = null)
            clients.upsert(
                listOf(Client(id = EntityId.mint(), conversation = conversation, name = label)),
            )
            messages.upsert(conversation, listOf(message(conversation, "hello $label", author)))
        }
        aContact(author, displayName = name)

        assertEquals(emptyList<Referent>(), search.matching(name).single().parents)
    }

    @Test
    fun `a client whose label reduces to nothing is not handed back as a parent`(): Unit = runBlocking {
        // `count` passes over a null and `array_agg` does not, so a label that is nothing but
        // delimiters takes no part in deciding the count and would still be aggregated — handing
        // the contact a parent belonging to no body of work, admitted by a group it never joined.
        val name = unique("Beside An Unnamed Client")
        val author = aPerson("beside-unnamed")
        val real = aConversation("beside-real", name = null)
        val client = EntityId.mint()
        clients.upsert(listOf(Client(id = client, conversation = real, name = unique("Kestrel"))))
        messages.upsert(real, listOf(message(real, "hello", author)))
        val unnamed = aConversation("beside-unnamed-thread", name = null)
        // Delimiters the rule names, so this reduces to nothing. An em dash is not one of them —
        // it survives as a leading name of its own, which is the rule failing towards keeping
        // labels apart and not the case under test here.
        clients.upsert(listOf(Client(id = EntityId.mint(), conversation = unnamed, name = " - . ")))
        messages.upsert(unnamed, listOf(message(unnamed, "hello again", author)))
        aContact(author, displayName = name)

        assertEquals(listOf(Referent.OfClient(client)), search.matching(name).single().parents)
    }

    @Test
    fun `a contact writing in two strands of one body of work sits under both`(): Unit = runBlocking {
        // The strands are two clients and one body of work, so counting clients would leave this
        // contact with no parent at all — the grouping lost to how the work is filed rather than
        // to anything about who they work with. Both clients are returned because the body of
        // work is no entity: naming one of them would name a strand as the parent.
        val work = unique("SharedWork")
        val name = unique("Inside One Body Of Work")
        val author = aPerson("one-body")
        val strands = listOf("design", "delivery").map { strand ->
            val conversation = aConversation("strand-$work-$strand", name = null)
            val client = EntityId.mint()
            clients.upsert(listOf(Client(id = client, conversation = conversation, name = "$work $strand")))
            messages.upsert(conversation, listOf(message(conversation, "hello $strand", author)))
            Referent.OfClient(client)
        }
        aContact(author, displayName = name)

        // Ordered and not merely present: the parents reach a prompt as the subjects a request is
        // answered against, and the read states an order so that one question resolves the same way
        // twice. Compared as a set, the statement could drop its `ORDER BY` and nothing would say so.
        assertEquals(
            strands.sortedBy { (it as Referent.OfClient).client.value.toString() },
            search.matching(name).single().parents,
        )
    }

    @Test
    fun `a client sits under nothing, being the broadest subject there is`(): Unit = runBlocking {
        val name = unique("Topmost Client")
        clients.upsert(
            listOf(Client(id = EntityId.mint(), conversation = aConversation("topmost", null), name = name)),
        )

        assertEquals(emptyList<Referent>(), search.matching(name).single { it.kind == SubjectKind.CLIENT }.parents)
    }

    @Test
    fun `a body of work is reported beside the strands that make it up`(): Unit = runBlocking {
        // What a name picking out the work resolved to before: three clients, none of them another's
        // parent, so each was a competing reading and the reader was asked which strand they meant.
        // The work is reported as a subject of its own, carrying every record of every strand, so
        // the compose rule has one narrow subject and the whole of what it is read through.
        val work = oneWord("Loomfield")
        val strands = listOf("design", "delivery", "packaging").map { strand ->
            val label = "$work $strand"
            val conversation = aConversation("strand-$label", name = label)
            val client = EntityId.mint()
            clients.upsert(listOf(Client(id = client, conversation = conversation, name = label)))
            Referent.OfClient(client) to Referent.OfConversation(conversation)
        }

        val found = search.matching(work)
        val body = found.single { it.kind == SubjectKind.BODY_OF_WORK }

        // Keyed on the name the strands share, which is what makes two reads of one request agree
        // without anything having to store it.
        assertEquals(Referent.OfBodyOfWork(work.lowercase()), body.referent)
        assertEquals(
            strands.flatMap { (client, conversation) -> listOf(client, conversation) }.toSet(),
            body.parents.toSet(),
            "A strand missing from the work's own list stays a competing reading of the name, " +
                "which is the menu this read exists to stop producing.",
        )
        // A label the corpus holds, and the lexically first of them — which is the tie-break the
        // grouping read takes for the same choice, so the strand that stands for the work on a
        // subject line is the strand that heads its section. A reduced name is one no conversation
        // has, and nothing here may show one.
        assertEquals("$work delivery", body.name)
        assertEquals(
            found.filter { it.referent in body.parents }.maxOf { it.score },
            body.score,
            "The work scores as something other than its strands did, so a number nothing here " +
                "produced is being compared against the ones everything else is ranked by.",
        )
    }

    @Test
    fun `two bodies of work that merely share a word are not read as one`(): Unit = runBlocking {
        // The query agrees with a word inside both labels while they lead with different names — a
        // kind of work rather than whose it is. Two pieces of work are two however much of a label
        // they have in common, and deciding what a label *means* is the narrowing pass's, so
        // nothing is reported here and the caller's menu stands.
        val shared = oneWord("Haulage")
        listOf("Ashford", "Brightwell").forEach { lead ->
            val label = "$lead $shared"
            val conversation = aConversation("separate-$label", name = label)
            clients.upsert(listOf(Client(id = EntityId.mint(), conversation = conversation, name = label)))
        }

        val found = search.matching(shared)

        assertEquals(
            2,
            found.count { it.kind == SubjectKind.CLIENT },
            "The fixture's two clients did not both match, so this asserts nothing.",
        )
        assertTrue(
            found.none { it.kind == SubjectKind.BODY_OF_WORK },
            "Two bodies of work were read as one, so a question about either is answered from " +
                "both and no reader is asked which was meant.",
        )
    }

    @Test
    fun `two bodies of work that share their leading name are read as one`(): Unit = runBlocking {
        // **The cost of settling this by a rule, asserted rather than left to be discovered.** The
        // reduction is the leading name, so two pieces of work whose labels begin with one word are
        // one body of work here — and that decides a scope rather than only a count, which is where
        // it is paid for. It is accepted on the terms the sibling reads accept it on, and what would
        // settle it properly is the pass that judges what a label means. Written down because a
        // tolerance nothing asserts is indistinguishable from one nobody knew about.
        val lead = oneWord("Corvid")
        listOf("Freight", "Clinic").forEach { trade ->
            val label = "$lead $trade"
            val conversation = aConversation("shared-lead-$label", name = label)
            clients.upsert(listOf(Client(id = EntityId.mint(), conversation = conversation, name = label)))
        }

        val found = search.matching(lead)

        assertEquals(
            2,
            found.count { it.kind == SubjectKind.CLIENT },
            "The fixture's two clients did not both match, so this asserts nothing.",
        )
        assertEquals(
            1,
            found.count { it.kind == SubjectKind.BODY_OF_WORK },
            "Two labels built on one leading name were kept apart, which the rule cannot do — so " +
                "either the reduction changed or a narrowing was added, and this tolerance is no " +
                "longer the one the documents record.",
        )
    }

    @Test
    fun `two clients labelled the same way are two subjects rather than one body of work`(): Unit =
        runBlocking {
            // A label cannot say which of two identically-named threads a piece of work owns, so
            // nothing here can say they are strands of one rather than two pieces of work named
            // alike — which is what the rarity count already treats them as. Read as one, a
            // question about either would be answered from both under the name that was typed,
            // with no menu and nothing in the reply saying so.
            val label = unique("Twinned Work")
            listOf(1, 2).forEach { index ->
                val conversation = aConversation("twinned-$index-$label", name = label)
                clients.upsert(
                    listOf(Client(id = EntityId.mint(), conversation = conversation, name = label)),
                )
            }

            val found = search.matching(label)

            assertEquals(
                2,
                found.count { it.kind == SubjectKind.CLIENT },
                "The fixture's two clients did not both match, so this asserts nothing.",
            )
            assertTrue(
                found.none { it.kind == SubjectKind.BODY_OF_WORK },
                "Two clients of one label were read as one piece of work, so a question about " +
                    "either is answered from both and the reader is never asked which was meant.",
            )
        }

    @Test
    fun `a single strand is a client and gets no second referent`(): Unit = runBlocking {
        // One client is a body of work already, so a referent for the work would be the same
        // subject twice — two readings of a name that resolve identically, which is a menu nobody
        // can answer.
        val label = unique("Solitary Work")
        val conversation = aConversation("solitary", name = label)
        clients.upsert(listOf(Client(id = EntityId.mint(), conversation = conversation, name = label)))

        val found = search.matching(label)

        assertTrue(found.any { it.kind == SubjectKind.CLIENT }, "The fixture matched no client at all.")
        assertTrue(found.none { it.kind == SubjectKind.BODY_OF_WORK })
    }

    @Test
    fun `a near spelling of a name is never offered as a body of work`(): Unit = runBlocking {
        // The near read shows spellings and resolves to none of them, so a body of work there would
        // be a strand's label listed as a second spelling of itself.
        val work = oneWord("Marchbank")
        listOf("design", "delivery").forEach { strand ->
            val label = "$work $strand"
            val conversation = aConversation("near-$label", name = label)
            clients.upsert(listOf(Client(id = EntityId.mint(), conversation = conversation, name = label)))
        }

        // Agrees with the stored labels on their opening and parts after it, which is what the near
        // read is for and what the matching read refuses.
        val near = search.nearest(work.take(NEAR_SPELLING) + "ville")

        assertTrue(near.isNotEmpty(), "Nothing was near enough to spell, so this asserts nothing.")
        assertTrue(near.none { it.kind == SubjectKind.BODY_OF_WORK })
    }

    @Test
    fun `a blank name searches nothing`(): Unit = runBlocking {
        // There is no query for it, and a wildcard would match the whole corpus.
        assertEquals(emptyList<Candidate>(), search.matching("   "))
    }

    @Test
    fun `a name nothing carries finds nothing`(): Unit = runBlocking {
        assertEquals(emptyList<Candidate>(), search.matching(unique("Nobody At All")))
    }

    @Test
    fun `the contacts inside a client are reached one hop in`(): Unit = runBlocking {
        val conversation = aConversation("inside a client", name = null)
        val client = EntityId.mint()
        clients.upsert(listOf(Client(id = client, conversation = conversation, name = unique("Container"))))
        val author = aPerson("inside")
        messages.upsert(conversation, listOf(message(conversation, "hello", author)))
        val contact = aContact(author, displayName = unique("Inside Person"))

        val found = search.within(Referent.OfClient(client))

        assertEquals(listOf(Referent.OfContact(contact)), found.map { it.referent })
        assertEquals(listOf(Referent.OfClient(client)), found.single().parents)
    }

    @Test
    fun `the contacts inside a conversation are reached one hop in`(): Unit = runBlocking {
        val conversation = aConversation("inside a thread", name = null)
        val author = aPerson("participant")
        messages.upsert(conversation, listOf(message(conversation, "hello", author)))
        val contact = aContact(author, displayName = unique("Participant"))

        val found = search.within(Referent.OfConversation(conversation))

        assertEquals(listOf(Referent.OfContact(contact)), found.map { it.referent })
    }

    @Test
    fun `a contact contains nothing, and neither does the inbox`(): Unit = runBlocking {
        // A person is not a container. Both return empty without a query, since this is called on the
        // traverse path for every unacceptable kind.
        assertEquals(emptyList<Candidate>(), search.within(Referent.OfContact(EntityId.mint())))
        assertEquals(emptyList<Candidate>(), search.within(Referent.Inbox))
    }

    @Test
    fun `a container whose contacts are not recognised yet is empty rather than failing`(): Unit = runBlocking {
        // A client whose members have not been through recognition is an ordinary state.
        val conversation = aConversation("unrecognised", name = null)
        messages.upsert(conversation, listOf(message(conversation, "hello", aPerson("unrecognised"))))

        assertEquals(emptyList<Candidate>(), search.within(Referent.OfConversation(conversation)))
    }

    @Test
    fun `a merged account is offered as the human it belongs to`(): Unit = runBlocking {
        // Two accounts, one person. Offering the merged row as a subject would narrow every read to
        // one of their accounts, and the answer would be a partial history with citations that check
        // out — the failure that cannot be seen from the outside.
        val shared = unique("Shared Name")
        val head = aContact(aPerson("head account"), displayName = "$shared One")
        val other = aContact(aPerson("other account"), displayName = "$shared Two")
        link(merged = other, into = head)

        val found = search.matching(shared)

        assertEquals(listOf(Referent.OfContact(head)), found.map { it.referent })
    }

    @Test
    fun `two accounts of one human are one carrier of their name`(): Unit = runBlocking {
        // Rarity counts what the name could have meant. A merge makes a name more certain, not less,
        // so counting the rows would halve the score of exactly the names recognition has resolved.
        val name = unique("Resolved Person")
        val head = aContact(aPerson("rarity head"), displayName = name)
        val other = aContact(aPerson("rarity other"), displayName = name)
        link(merged = other, into = head)

        val found = search.matching(name).single()

        assertEquals(Referent.OfContact(head), found.referent)
        assertEquals(1.0, found.score, "An exact name carried by one human scores in full.")
    }

    @Test
    fun `two unmerged people sharing a name still halve each other's score`(): Unit = runBlocking {
        // The other side of the same count, and the reason it cannot simply be dropped: two people
        // really do make a name ambiguous.
        val name = unique("Ambiguous Person")
        aContact(aPerson("ambiguous one"), displayName = name)
        aContact(aPerson("ambiguous two"), displayName = name)

        val found = search.matching(name)

        assertEquals(2, found.size)
        assertTrue(found.all { it.score == 0.5 }, "Scores were ${found.map { it.score }}.")
    }

    @Test
    fun `the contacts inside a supergroup include the ones who wrote in its topics`(): Unit = runBlocking {
        // Where a supergroup's traffic actually is. The container itself holds almost nothing, so
        // matching only the named conversation returns the members of an empty shell.
        val supergroup = aConversation("a supergroup", name = unique("Supergroup"))
        val topic = aTopic("a topic", parent = supergroup)
        val author = aPerson("topic writer")
        messages.upsert(topic, listOf(message(topic, "said in the topic", author)))
        val contact = aContact(author, displayName = unique("Topic Writer"))

        val found = search.within(Referent.OfConversation(supergroup))

        assertEquals(listOf(Referent.OfContact(contact)), found.map { it.referent })
    }

    @Test
    fun `the contacts inside a client include the ones who wrote in its topics`(): Unit = runBlocking {
        // The same gap reached through a client, which is the shape that matters: a client is read
        // off a conversation, and the conversation it was read off is usually the container.
        val supergroup = aConversation("a client supergroup", name = null)
        val client = EntityId.mint()
        clients.upsert(listOf(Client(id = client, conversation = supergroup, name = unique("Client"))))
        val topic = aTopic("a client topic", parent = supergroup)
        val author = aPerson("client topic writer")
        messages.upsert(topic, listOf(message(topic, "said in the client's topic", author)))
        val contact = aContact(author, displayName = unique("Client Topic Writer"))

        val found = search.within(Referent.OfClient(client))

        assertEquals(listOf(Referent.OfContact(contact)), found.map { it.referent })
        assertEquals(listOf(Referent.OfClient(client)), found.single().parents)
    }

    @Test
    fun `a topic's own members do not leak into an unrelated container`(): Unit = runBlocking {
        // The widening is one hop down from the named container and no further sideways. A topic of
        // another supergroup is not inside this one.
        val mine = aConversation("my supergroup", name = null)
        val theirs = aConversation("their supergroup", name = null)
        val topic = aTopic("their topic", parent = theirs)
        val author = aPerson("their writer")
        messages.upsert(topic, listOf(message(topic, "not yours", author)))
        aContact(author, displayName = unique("Their Writer"))

        assertEquals(emptyList<Candidate>(), search.within(Referent.OfConversation(mine)))
    }

    @Test
    fun `a merged pair inside one container is offered once`(): Unit = runBlocking {
        val conversation = aConversation("merged inside", name = null)
        val headRecord = aPerson("inside head")
        val otherRecord = aPerson("inside other")
        messages.upsert(
            conversation,
            listOf(
                message(conversation, "under one account", headRecord),
                message(conversation, "under the other", otherRecord),
            ),
        )
        val head = aContact(headRecord, displayName = unique("Inside Head"))
        val other = aContact(otherRecord, displayName = unique("Inside Other"))
        link(merged = other, into = head)

        val found = search.within(Referent.OfConversation(conversation))

        assertEquals(listOf(Referent.OfContact(head)), found.map { it.referent })
    }

    /**
     * A candidate says whether it is the account the corpus was read through.
     *
     * Both halves together, because a read that marked everything would pass a test that only
     * looked at the reader's own candidate — and the resolver would then drop every subject there
     * is from the choices it offers.
     */
    @Test
    fun `a candidate says whether its human is the account read through`(): Unit = runBlocking {
        val name = unique("Marked Reader")
        aContact(aPerson("marked reader", isSelf = true), displayName = name)
        val counterpartName = unique("Unmarked Counterpart")
        aContact(aPerson("unmarked counterpart", isSelf = false), displayName = counterpartName)

        assertTrue(search.matching(name).single().isSelf)
        assertFalse(search.matching(counterpartName).single().isSelf)
    }

    /**
     * A corpus nothing has marked offers no subject as the reader.
     *
     * Null is *nothing established*, and a predicate written as a comparison against false would
     * read it as a denial — which is the same answer here, but the opposite one for a mark that is
     * genuinely unset elsewhere. What this holds is that an unasked corpus behaves exactly as it
     * did before the question existed.
     */
    @Test
    fun `a person nobody asked about is not the account read through`(): Unit = runBlocking {
        val name = unique("Unasked Person")
        aContact(aPerson("unasked person"), displayName = name)

        assertFalse(search.matching(name).single().isSelf)
    }

    /**
     * Two accounts of the reader that a merge has joined are one subject, and it is the reader.
     *
     * The acceptance this exists for: several accounts of the reader are all the reader, and merging
     * two of them does not produce a second one.
     */
    @Test
    fun `two merged accounts of the reader are one marked subject`(): Unit = runBlocking {
        val name = unique("Merged Reader")
        val head = aContact(aPerson("merged reader head", isSelf = true), displayName = name)
        val other = aContact(aPerson("merged reader other", isSelf = true), displayName = name)
        link(merged = other, into = head)

        val found = search.matching(name)

        assertEquals(listOf(Referent.OfContact(head)), found.map { it.referent })
        assertTrue(found.single().isSelf)
    }

    /**
     * The question is the cluster's, not the row's — which a marked account merged into an unmarked
     * head is the case that proves.
     *
     * Read row by row, the surviving subject is the head's own record and that one is not marked, so
     * the reader would quietly become an ordinary subject again by being recognised as one human.
     */
    @Test
    fun `a human is the account read through where any of their accounts is`(): Unit = runBlocking {
        val name = unique("Partly Marked Reader")
        val head = aContact(aPerson("partly marked head"), displayName = name)
        val other = aContact(aPerson("partly marked other", isSelf = true), displayName = name)
        link(merged = other, into = head)

        assertTrue(search.matching(name).single().isSelf)
    }

    /**
     * And inside a container, which is where it matters most.
     *
     * What is inside a body of work is read from who wrote in it, and the account an archive is read
     * through wrote in everything — so an unmarked candidate here puts the reader among the people
     * offered for every client in the corpus.
     */
    @Test
    fun `a contact reached inside a container carries the mark too`(): Unit = runBlocking {
        val conversation = aConversation("marked inside", name = null)
        val record = aPerson("inside reader", isSelf = true)
        messages.upsert(conversation, listOf(message(conversation, "written by the reader", record)))
        val contact = aContact(record, displayName = unique("Inside Reader"))

        val found = search.within(Referent.OfConversation(conversation)).single()

        assertEquals(Referent.OfContact(contact), found.referent)
        assertTrue(found.isSelf)
    }

    // --- doubles and fixtures -------------------------------------------------------------

    /** Records that [merged] is the same human as [into]. */
    private suspend fun link(merged: EntityId, into: EntityId) =
        contacts.merge(listOf(MergeLink(merged = merged, canonical = into, score = 1.0, signals = emptyList())))

    /** A topic inside [parent] — the kind of conversation a supergroup's traffic actually hangs off. */
    private suspend fun aTopic(label: String, parent: ExternalId): ExternalId {
        val externalId = StoreRig.externalId(label)
        conversations.upsert(
            Conversation(externalId = externalId, kind = ConversationKind.TOPIC, name = null, parent = parent),
        )
        return externalId
    }

    private suspend fun aConversation(label: String, name: String?): ExternalId {
        val externalId = StoreRig.externalId(label)
        conversations.upsert(
            Conversation(externalId = externalId, kind = ConversationKind.DIRECT, name = name),
        )
        return externalId
    }

    /**
     * The one-to-one conversation with the person [record] names.
     *
     * **Keyed by that person's own record identifier**, which is what a source does for a direct
     * chat and is the whole of what makes the two one subject. Distinct from [aConversation], which
     * mints an identifier of its own and is therefore a different subject however it is named.
     */
    private suspend fun aDirectChat(record: ExternalId, name: String): ExternalId {
        conversations.upsert(
            Conversation(externalId = record, kind = ConversationKind.DIRECT, name = name),
        )
        return record
    }

    /**
     * A person record, optionally one the corpus was read through.
     *
     * [isSelf] defaults to unset rather than to false, which is the state of every record in a
     * corpus whose adapter has not answered the question — and therefore the state the cases that
     * are not about the mark should be read in.
     */
    private suspend fun aPerson(label: String, handle: String? = null, isSelf: Boolean? = null): ExternalId {
        val externalId = StoreRig.externalId(label)
        persons.upsert(
            Person(
                externalId = externalId,
                displayName = label,
                identifiers = listOfNotNull(handle?.let { Identifier.of(IdentifierType.HANDLE, it) }),
                isSelf = isSelf,
            ),
        )
        return externalId
    }

    private suspend fun aContact(record: ExternalId, displayName: String): EntityId {
        val id = EntityId.mint()
        contacts.upsert(listOf(Contact(id = id, record = record, displayName = displayName)))
        return id
    }

    private companion object {

        val WRITTEN: Instant = Instant.parse("2026-10-01T09:00:00Z")

        private var minted = 0

        /**
         * A name no other case holds.
         *
         * The rig is shared and nothing is emptied, so a name reused across cases would be counted as
         * a common one — and the rarity assertions would then depend on how many other tests had run.
         */
        fun unique(label: String): String = "$label ${++minted}"

        /**
         * A name no other case holds, in one word.
         *
         * [unique] separates its tag with a space, which the leading-name rule reads as a word
         * boundary — so a label built on one would reduce to the stem every case that used it
         * shares, and an assertion naming the body of work would be naming something several cases
         * produce. Closed up, the tag is part of the name the strands share.
         */
        fun oneWord(label: String): String = unique(label).replace(" ", "")

        /** How much of a word's opening the near read requires, which is its own constant. */
        const val NEAR_SPELLING = JdbcSubjectSearch.NEAR_LETTERS

        /**
         * [labels], each carrying one tag, so a case can hold two spellings of the same name.
         *
         * [unique] mints a tag per call, which is what the rarity assertions need and what a
         * cross-script case cannot use: the label and the name typed for it have to agree, and two
         * calls would hand them different tags. The tag is digits, which both scripts spell the same
         * way, so it reaches the comparison identically from either side.
         *
         * **Fixed width, because the pattern a name is matched by is open at the end.** A bare tag
         * `4` is a prefix of `40`, so a case holding tag `4` would match a label another case minted
         * as `40` and an exact-cardinality assertion would fail on nothing but the order the methods
         * ran in. Padding removes the prefix relation between tags outright.
         */
        fun uniquely(vararg labels: String): List<String> {
            val tag = "%03d".format(++minted)
            return labels.map { "$it $tag" }
        }

        /**
         * Pairs of one name as a source stored it and as a reader writes it.
         *
         * **Each turns on a different letter**, which is the property that matters: one Russian letter
         * has several Latin renderings and the reverse, so a rule derived from one name is not a rule.
         * In order — a vowel written with a diaeresis and a letter rendered as two; a consonant
         * cluster written as one Latin letter and the four-letter rendering; a name ending in the
         * glide; a soft sign before a vowel and another at a word's end; the glide at the front and a
         * soft sign before the diaeresis; and a letter rendered with or without its leading stop.
         *
         * **Written as escapes rather than as the script itself.** The convention here is that no
         * Kotlin file carries a corpus script, test source included — `LayeringTest` reading only
         * `/src/main/` is a limit of that check and not a licence. The names are invented, as every
         * fixture in this repository is.
         */
        val CROSS_SCRIPT = listOf(
            "\u0416\u0451\u043B\u0442\u044B\u0439 \u0416\u0443\u0440\u043D\u0430\u043B" to "Zhelty Zhurnal",
            "\u042D\u043A\u0441\u043F\u0440\u0435\u0441\u0441 \u0417\u0430\u0449\u0438\u0442\u0430" to
                "Express Zashchita",
            "\u0426\u0435\u043D\u0442\u0440\u0430\u043B\u044C\u043D\u044B\u0439 \u041E\u0442\u0434\u0435\u043B" to
                "Tsentralny Otdel",
            "\u041F\u0440\u0435\u043C\u044C\u0435\u0440\u043D\u044B\u0439 \u041E\u0442\u0447\u0451\u0442" to
                "Premyerny Otchet",
            "\u042E\u0436\u043D\u044B\u0439 \u0424\u0438\u043B\u0438\u0430\u043B" to "Yuzhny Filial",
            "\u0425\u0440\u0430\u043D\u0438\u043B\u0438\u0449\u0435" to "Khranilishche",
            "\u0421\u0442\u0430\u043B\u044C\u043D\u043E\u0439 \u041F\u0440\u043E\u043A\u0430\u0442" to "Stalnoy Prokat",
        )

        /**
         * A label stored in the corpus's own script, for a reader who types the Latin of it.
         *
         * The other direction, which this archive needs as much: it holds display names in both
         * scripts, so a reader writing Latin against a Cyrillic label is no more hypothetical than
         * the reverse. Paired in the case with the rendering a reader would type for it.
         */
        const val CYRILLIC_LABEL = "\u041F\u0440\u0438\u0451\u043C\u043D\u044B\u0439 \u041F\u0443\u043D\u043A\u0442"

        /** The Latin a reader would write for [CYRILLIC_LABEL]. */
        const val LATIN_FOR_CYRILLIC_LABEL = "Priyomny Punkt"

        fun message(conversation: ExternalId, text: String, author: ExternalId): Message = Message(
            externalId = StoreRig.externalId("${conversation.nativeId}-msg"),
            sourceTime = WRITTEN,
            author = author,
            text = text,
        )
    }
}
