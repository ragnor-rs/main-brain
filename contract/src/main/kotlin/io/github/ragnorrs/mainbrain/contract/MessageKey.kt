package io.github.ragnorrs.mainbrain.contract

import kotlinx.serialization.Serializable

/**
 * What the backend says, named rather than worded.
 *
 * The backend has no locale, no catalogue and nothing to translate: it emits one of these and
 * the interface that renders prose resolves `(key, locale) → text`. That is what keeps the
 * service language-agnostic, and it is why a key is part of the wire format rather than a
 * convention on the receiving side — two interfaces may render the same key differently, and a
 * chat reply and a CRM field are not the same register.
 *
 * **An enum, so the set is closed and the mapping is exhaustive.** A string key would let the
 * backend emit one no catalogue has an entry for, which surfaces as a raw code in somebody's
 * chat. A case added here fails to compile wherever the renderer switches over it, which is the
 * moment to write the translation rather than the moment somebody reads a code.
 *
 * Nothing here is display text and nothing here is in any language. The names are codes, and
 * the one place words exist is an interface's resource bundle.
 */
@Serializable
enum class MessageKey {

    /**
     * Several drafts came back and one of them is to be picked.
     *
     * The prompt above a menu of alternatives, not the alternatives themselves — those are the
     * model's prose and travel as prose.
     */
    CHOOSE_A_DRAFT,

    /** A name meant more than one unrelated thing, so the menu below asks which. */
    SUBJECT_AMBIGUOUS,

    /**
     * A name meant more than one thing and nothing on offer tells them apart.
     *
     * Said instead of a menu. A menu is answered by naming the option that was meant, so options
     * sharing a label with no handle between them pose a choice that cannot be made — and a
     * question whose every answer returns the same question is worse than saying so, because it
     * reads as progress.
     */
    SUBJECT_INDISTINGUISHABLE,

    /**
     * Nothing acceptable matched, and the menu-less reply says where the name was looked for.
     *
     * It says so **where there is something to say**: a producer holding what matched rather than
     * what was covered sends no kinds, and the reply then carries that clause not at all. So the
     * coverage is a courtesy of this key rather than a guarantee of it — see [Outcome.SendStatus].
     *
     * **Every producer of this key did look a name up.** A request that never had a name to look for
     * is [SUBJECT_MISSING] or [SUBJECT_NOT_READ], which is what keeps the promise above from being
     * made to a reader about a search that did not happen.
     *
     * **Not [SUBJECT_NEAREST_NAMES], which is the arm where the corpus turned out to hold a name
     * spelled nearly like the one written.** This one is reached where no such form exists, and
     * also by a name that agreed with a stored label of a kind nothing answerable sits inside —
     * whose spelling was never in question. So what it may be read as saying is that there is
     * nothing to work with, and never that a particular spelling was ruled out.
     */
    SUBJECT_NOT_FOUND,

    /**
     * Nothing matched the name, and the corpus holds names spelled nearly like it.
     *
     * **Not [SUBJECT_NOT_FOUND], and the difference is what the search established rather than
     * what it returned.** Both are reached by a name that agreed with no stored label, so neither
     * has a subject. That one looked for near forms and found none, which is as close as this build
     * comes to the corpus holding nothing of the kind, and its reply is read as an absence. This one
     * found some, so the only thing shown to be absent is the form that was written — and a reader
     * told it was an absence is sent to the one conclusion they cannot check, which is to stop
     * looking. One key for both is wrong every time the subject is there under another spelling.
     *
     * **The near forms travel with it, as spellings and never as readings of the name.** Nothing
     * asserts that one of them is the subject: that is what [SUBJECT_AMBIGUOUS] does, and it is
     * offered as a menu because its options are things the name was found to mean. These were found
     * to be spelled nearly like it, which is weaker and is the point — a wrong one costs a reader a
     * glance, while the right one is their only route to the form the corpus keeps. A display name
     * is stored as its source registered it and no interface shows a reader which that is, so told
     * only that nothing was found their one route forward is to guess the spelling.
     *
     * It carries the name as it was asked for and the kinds searched, exactly as
     * [SUBJECT_NOT_FOUND] does, because the reader is owed the same account of what was covered.
     */
    SUBJECT_NEAREST_NAMES,

    /**
     * The request is about somebody, and which somebody never arrived.
     *
     * **Not [SUBJECT_NOT_FOUND], which reports a search.** That one says the name is absent from
     * the corpus and sends the reader looking for a gap in the material; this one says the name
     * did not reach the backend, and what it asks for is the question again with the subject
     * named. One key for both would be wrong every second time it was rendered. A third says the
     * name arrived and matched nothing while the corpus holds a name nearly like it, which is
     * [SUBJECT_NEAREST_NAMES] — the same argument applied once more, to the case the pair of them
     * stopped short of.
     *
     * It carries no name and no searched kinds, because nothing was searched and there is no name
     * to echo.
     *
     * **What it renders as has to work for every shape that can produce it, which is not
     * decoration.** Any pipeline that takes a subject can refuse this way — drafting and
     * preparing as much as reporting where things stand — and nothing here knows which did, or
     * whether the reader meant somebody or meant everything. So the advice is restating the
     * question with the subject named, which is true of all of them, and the inbox is mentioned
     * as the thing it is rather than as the fix for this request: it is the one reading that was
     * never about a name, and a reply that spoke only of naming a subject would leave that reading
     * a dead end. Advice naming one pipeline would be wrong for the others.
     */
    SUBJECT_NOT_READ,

    /**
     * The request named nobody, and what answers it can only answer for somebody named.
     *
     * **Not [SUBJECT_NOT_FOUND], which reports a search.** No name was given, so no name was looked
     * up: rendered as nothing-found this tells the reader the corpus holds nothing about a subject
     * they never named, which is the one conclusion they cannot check and the wrong one to act on.
     * What is missing is the subject, and that is fixed in one turn by whoever asked.
     *
     * **Nor [SUBJECT_NOT_READ], though the two ask for the same thing.** That one is for a request
     * where whether anybody was named was never established — nothing could read a name out of it —
     * so it opens by saying it could not make the request out. Here it was made out and it named
     * nobody, so a reply leading with a failure to understand would describe a failure that did not
     * happen. The advice is the same and the account of what went wrong is not, which is what makes
     * it a second key rather than a second route to the first.
     *
     * **What it renders as presumes neither a pipeline nor a command, and the second is the one
     * easily got wrong.** Two requests reach this: a command that takes a subject, carrying no
     * argument, and free text a routing call read and reported as naming no particular subject. So a
     * reply saying a name is missing *after the command* would, for half of them, describe something
     * the reader never typed — the same mistake [SUBJECT_NOT_READ] avoids one axis over, and a worse
     * one than vagueness, because it is checkably untrue. What is true of both is that a subject is
     * wanted, and where a command carries one is a fact about commands rather than a claim one was
     * sent.
     *
     * **What to name is bounded by what reaches this**, which today is the two shapes accepting a
     * contact and nothing else. A reply naming kinds those shapes would refuse would be asking for
     * something they cannot answer for.
     *
     * It carries no name and no searched kinds, because there is neither.
     */
    SUBJECT_MISSING,

    /**
     * There was nothing in the corpus to answer from.
     *
     * Also what a request degrades to when generation could not be grounded. One key for both,
     * because the honest statement is the same and a second would invite the interface to
     * render one of them as a fault.
     *
     * **Not what a reply nobody could read says** — see [ANSWER_UNREADABLE]. One key for those two
     * would tell a reader whose material did answer that their material holds nothing.
     */
    NOTHING_TO_ANSWER_FROM,

    /**
     * A model wrote a whole answer, a re-roll was spent, and the reply the request ended on could
     * not be read back.
     *
     * **Its own key rather than [NOTHING_TO_ANSWER_FROM].** That one is about the material; this is
     * about the service. An answer was written at length and what could not be read is that answer —
     * so rendered as an absence it sends the reader looking for a gap that is not there, when what
     * there is to do is ask again. It deliberately says nothing about what was retrieved: a shape
     * that declares it does not read the corpus runs the model on an empty set as a matter of
     * course, and this is reachable through one.
     *
     * **And not [CANNOT_ANSWER_RIGHT_NOW], which stands for a dependency being down** — whether it
     * was reached before the work began or went away while it ran, since either is answered by
     * coming back later. Here nothing was down: the request ran, spent what a request spends, and
     * failed at the last step; asking again is the thing most likely to work, because the same
     * question put a second time usually reads.
     *
     * **Nor is it the provider response the service itself could not parse**, which is a contract
     * the build got wrong rather than an answer that failed to read, and which surfaces as
     * [UNEXPECTED_FAILURE] on a 500. That one is cleared by a deploy, so telling the reader to ask
     * again would be a lie. This one is cleared by asking again, which is why it is worth a key.
     */
    ANSWER_UNREADABLE,

    /** The request did not carry something that could be asked. */
    REQUEST_MALFORMED,

    /** The caller did not prove it was the interface this backend serves. */
    REQUEST_UNAUTHORISED,

    /**
     * The backend is up and cannot answer right now.
     *
     * Deliberately one key for every such state — the store unreachable, this backend not
     * configured to answer, a dependency refusing, work dropped while a caller was still asking
     * for it. They differ in what an operator must do and not in what the person who asked can do,
     * which is come back later. Which one it is the backend's own log says.
     *
     * **An interface may also reach for it on its own account**, where it could not land enough
     * requests to collect work it had already been given. That is a backend which has gone away,
     * and it asks the same thing of the person who asked as every state above does: come back
     * later, because it passes. The cause is in that interface's log rather than this backend's.
     *
     * **What does not belong here is an interface running out of its own patience** on work this
     * backend took and is still composing. Nothing is down in that state, so *come back later* is
     * both false and the one advice that cannot help — the same question overruns again at the
     * same cost. It is a fact about one interface's clock rather than anything this service
     * reports, and a case here for it would hand every other interface, waiting to its own bound,
     * the wording for a decision it did not make. So such an interface says it in its own
     * vocabulary and reaches for nothing here.
     */
    CANNOT_ANSWER_RIGHT_NOW,

    /** Something the surface did not anticipate. The log carries what; this carries that. */
    UNEXPECTED_FAILURE,
}
