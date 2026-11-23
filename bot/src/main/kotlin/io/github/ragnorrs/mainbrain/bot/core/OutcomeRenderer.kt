package io.github.ragnorrs.mainbrain.bot.core

import io.github.ragnorrs.mainbrain.bot.i18n.BotKey
import io.github.ragnorrs.mainbrain.bot.i18n.Catalogue
import io.github.ragnorrs.mainbrain.bot.port.Reply
import io.github.ragnorrs.mainbrain.contract.AnswerResponse
import io.github.ragnorrs.mainbrain.contract.Block
import io.github.ragnorrs.mainbrain.contract.Freshness
import io.github.ragnorrs.mainbrain.contract.MenuOption
import io.github.ragnorrs.mainbrain.contract.Outcome
import io.github.ragnorrs.mainbrain.contract.SubjectKind
import io.github.ragnorrs.mainbrain.contract.SubjectRef
import java.time.Clock
import java.time.ZoneId

/**
 * Turns what came back into the messages a person reads.
 *
 * **A function, not a sender.** It returns replies and reaches nothing — no platform, no socket,
 * no port — which is what makes every rendering decision here a unit test rather than something
 * only a live chat can show. It is also what makes a second interface's renderer a sibling of this
 * rather than a rewrite of it: the mapping from an outcome to messages is platform-free, and only
 * putting them somewhere is not.
 *
 * **Several drafts are several messages, and they are contiguous.** Each carries its own numbered
 * header, so the one a person wants is the one they forward — the alternative is a wall of text
 * they have to cut a passage out of, which is the failure the outcome kind exists to prevent.
 * Nothing is appended to a draft's own message either, for the same reason: a footer is something
 * somebody has to delete before using what they were given. Nor does anything a reader will not
 * send come between two they might, which is what sends the drafts together and what each rests on
 * after them. The header is emphasised because it is the part that is *not* the draft — a person
 * forwarding one should be able to see at a glance where what they were given starts.
 *
 * **What an answer's own structure looks like is decided here, from what the wire marks.** A
 * [Block.Heading] is set in bold and the block saying what the reply rests on is set back, because
 * the two things a reader does with a long answer are find the section they want and occasionally
 * check where it came from. A finding takes neither: on a reply of twenty across six sections,
 * emphasis on each would be emphasis on nearly every line. Which parts are which is the backend's
 * to say and how they look is this interface's, which is the same division the catalogue and the
 * locale are on.
 *
 * **Prose is what the model wrote and everything else is a key.** [Block.text] and
 * [MenuOption.Draft.prose] are what the model was asked to write; every word this interface says
 * about itself comes out of the catalogue. That split is the whole of the display-text rule.
 *
 * **Every value that did not come out of the catalogue is escaped, and the split above is what
 * decides which.** A reply body carries markup — see [escaped] — so an ampersand in a company's
 * name or a less-than sign in a comparison would otherwise make the platform reject the whole
 * message. The catalogue's own text is not escaped, because it is this build's own reviewed text
 * and carries the emphasis this interface renders; what holds it to carrying only markup the
 * platform can parse is [markupFaults], which the catalogue runs over every entry as it loads. Its
 * parameters are escaped where they are somebody else's words — a label, a handle, a name that was
 * searched for — and not where this interface composed them itself out of catalogue entries and its
 * own digits, as the freshness line's age and a citation's dates are.
 *
 * **So an entry may put emphasis around a parameter, and that is where a menu's gets it.** The tags
 * are the entry's and the words inside them are escaped, which composes correctly in either order:
 * escaping cannot touch a tag it never sees, and the tag cannot be escaped by a locale that does
 * not know it is there.
 *
 * **Times are worded by this interface rather than by the wire**, in [FreshnessLine] and
 * [ClaimSources], which hold the reasoning for the two questions a reader asks about time: how
 * current the corpus is, which is an age, and when the material behind an answer was written, which
 * is a date. [clock] and [zone] are parameters and not calls to `now()` and the default zone, for
 * the reason the first paragraph gives: a decision about time should be a unit test like every
 * other decision here.
 *
 * Message length is not this file's business. A platform's limit is the platform's, so splitting a
 * long reply belongs to the adapter that knows what the limit is.
 */
internal class OutcomeRenderer(
    private val catalogue: Catalogue,
    clock: Clock,
    zone: ZoneId,
) {

    private val freshnessLine = FreshnessLine(catalogue, clock, zone)

    private val claimSources = ClaimSources(catalogue, zone)

    /**
     * Everything [response] turns into, in the order it is read.
     *
     * What was answered, then the subjects that matched and lost, then how current the corpus was.
     * The subjects are their own message, which is what keeps an answer copy-clean.
     *
     * **The third is a footer and lands on the first message, never on its own and never on a
     * draft.** It qualifies what was answered, so a reader needs it beside that and not after the
     * subjects that lost; and the one message every outcome has is the first, which for a menu of
     * drafts is this interface's own prompt rather than anything somebody copies. Carried as
     * [Reply.footer] rather than concatenated, because only whoever splits a long body can keep it
     * on the last piece instead of letting it become one.
     */
    fun render(response: AnswerResponse, locale: String?): List<Reply> {
        val replies = content(response.outcome, locale) + alternatives(response.notUsed, locale)
        val note = freshness(response.freshness, locale) ?: return replies
        return listOf(replies.first().copy(footer = note)) + replies.drop(1)
    }

    private fun content(outcome: Outcome, locale: String?): List<Reply> = when (outcome) {
        is Outcome.SendText -> listOf(Reply(claimSources.under(outcome.body, outcome.claims, locale)))

        is Outcome.SendStatus -> listOf(Reply(status(outcome, locale)))

        // Which layout a menu gets follows from what is on it: picking a draft and saying which
        // subject was meant are different acts. Matched on the kind rather than on the prompt,
        // and exhaustively, so a third option kind stops this compiling rather than reaching
        // somebody rendered as whatever the catch-all happened to do.
        is Outcome.SendMenu -> when (outcome.options.first()) {
            is MenuOption.Draft ->
                drafts(outcome, outcome.options.filterIsInstance<MenuOption.Draft>(), locale)

            is MenuOption.Subject ->
                subjects(outcome, outcome.options.filterIsInstance<MenuOption.Subject>(), locale)
        }
    }

    /**
     * Something the system says about itself, with whatever it was given to say it with.
     *
     * Assembled as separate sentences rather than one template with holes in it. Both extras are
     * absent for most keys, and a template covering every combination would be four templates per
     * locale — while a locale that composes its clauses differently keeps the choice here from
     * being one it has to live with.
     */
    private fun status(outcome: Outcome.SendStatus, locale: String?): String = buildList {
        add(catalogue.text(outcome.key, locale))
        outcome.subjectName?.let { add(catalogue.text(BotKey.SUBJECT_NAMED, locale, escaped(it))) }
        // Said only where there is coverage to report. An empty list is a refusal with nothing
        // truthful to say about where a name was looked for — which is not the same as a search
        // that covered nothing, and is why this renders no clause rather than wording the absence.
        outcome.searched.mapNotNull { searchedKind(it) }
            .map { catalogue.text(it, locale) }
            .ifEmpty { null }
            ?.let { add(catalogue.text(BotKey.SEARCHED_AMONG, locale, it.joinToString())) }
        // Last of the clauses, because it is the one a reader acts on: what was said and where it
        // was looked for are the account, and these are the forms to try instead.
        //
        // Named by label and by nothing else, which is not a choice between the two names a subject
        // can have: a near form is found by a *label* agreeing, so what is near about it is the
        // label and only that. A near entry does carry its subject's handle where one exists —
        // every subject on the wire does — and saying it here would offer a key as a spelling, which
        // is the one thing a key cannot be: a handle is never reduced, so it either matched or was
        // not compared.
        //
        // The null arm is a drop rather than a fallback, for the same reason there is nothing to
        // fall back to. It is unreachable from the backend shipped here, whose near read requires a
        // label and refuses a row without one; the wire permits a label-less subject, and this is
        // the interface reading the wire.
        outcome.nearest.mapNotNull { it.label }
            .map { escaped(it) }
            .ifEmpty { null }
            ?.let { add(catalogue.text(BotKey.NEAREST_NAMES, locale, it.joinToString())) }
    }.joinToString(" ")

    /**
     * The prompt, then every draft, then what each was written from.
     *
     * **The basis is its own message, which is the whole reason it can be shown at all.** A reader
     * copies an alternative and sends it, so anything inside that message is something they have to
     * delete first — and a draft's assertions are in the third person about the person being written
     * to, so sending one sends somebody a note about themselves. Apart from the drafts they are
     * what they were always for: what one rests on, checkable, and left behind when it is sent.
     *
     * **Grouped rather than interleaved, which reverses an earlier reading of the same rule.** It
     * was each draft followed by its own basis, so that the support sat under the thing it
     * supported. Measured on a real reply that was eight messages, the cost of that is what a
     * reader does: the drafts are the only messages they might copy, and putting a message they
     * will not copy between each pair meant reaching the third alternative by scrolling past what
     * the first two rested on. So the things to be copied are contiguous, and nothing that is not
     * one separates them.
     *
     * What that gives up is adjacency saying which basis belongs to which draft, and the number
     * each one now carries is what replaces it — the same number its draft's header shows. Silent
     * for an alternative that asserts nothing, so the count of these need not match the drafts.
     *
     * **One message each, which was tried the other way and read worse.** Collapsed into a single
     * block they are five messages instead of seven, and the repetition between them becomes
     * visible — the drafts come from one retrieval of one thread, so they open on the same
     * premises. De-duplicating that is what the single block then wants, and it costs the thing
     * this message is for: an alternative whose every premise was already stated has nothing left
     * to head, so the headings skip a number, and a reader meeting one and three is told something
     * is missing rather than that nothing more was needed. Naming the omission needs a line of its
     * own, and a basis saying *as above* is a worse answer to *why should I send this one* than the
     * repetition was.
     *
     * So each stands alone and complete, which is also what the per-alternative shape is for: a
     * reader choosing the second draft wants the second draft's support, not a pointer upwards.
     */
    private fun drafts(menu: Outcome.SendMenu, options: List<MenuOption.Draft>, locale: String?): List<Reply> =
        listOf(Reply(catalogue.text(menu.prompt, locale))) +
            options.mapIndexed { index, option ->
                Reply(
                    bold(catalogue.text(BotKey.VARIANT, locale, position(index))) +
                        PARAGRAPH + escaped(plain(option.prose)),
                )
            } +
            options.mapIndexedNotNull { index, option ->
                claimSources.basis(position(index), option.claims, locale)?.let(::Reply)
            }

    /**
     * The prompt and the candidates, in one message.
     *
     * One rather than several because a candidate is a label and a kind — short, and nothing
     * anybody forwards. The reason drafts are separate messages does not apply to a list of names.
     *
     * Answered by asking again with a name, which is forced rather than chosen: a subject on the
     * wire carries no identifier, so there is nothing else to send back that the backend could
     * accept. Which is why an option carries its handle where it has one — a label two subjects
     * share names neither of them, and the handle is the name that names one.
     */
    private fun subjects(menu: Outcome.SendMenu, options: List<MenuOption.Subject>, locale: String?): List<Reply> {
        val lines = options.mapIndexed { index, option -> option(position(index), option.subject, locale) }
        return listOf(Reply((listOf(catalogue.text(menu.prompt, locale)) + lines).joinToString("\n")))
    }

    /**
     * One candidate, said with whatever it carries that a reader could name it by.
     *
     * Four shapes rather than one with optional pieces, because a locale that composes them
     * differently should not inherit this one's punctuation — and because the handle is not
     * decoration: it is the only thing that tells two people of one display name apart, so a line
     * that has one says it.
     */
    private fun option(position: String, subject: SubjectRef, locale: String?): String {
        val kind = kind(subject.kind, locale)
        val label = subject.label?.let(::escaped)
        val handle = subject.handle?.let(::escaped)
        return when {
            label != null && handle != null ->
                catalogue.text(BotKey.SUBJECT_OPTION_HANDLED, locale, position, label, kind, handle)

            label != null -> catalogue.text(BotKey.SUBJECT_OPTION, locale, position, label, kind)
            handle != null -> catalogue.text(BotKey.SUBJECT_OPTION_HANDLE_ONLY, locale, position, handle, kind)
            else -> catalogue.text(BotKey.SUBJECT_OPTION_UNNAMED, locale, position, kind)
        }
    }

    /**
     * The subjects that matched and were not used.
     *
     * Empty for every answer but a composed one, so this is silent almost always.
     */
    private fun alternatives(notUsed: List<SubjectRef>, locale: String?): List<Reply> =
        if (notUsed.isEmpty()) {
            emptyList()
        } else {
            val named = notUsed.joinToString { it.label?.let(::escaped) ?: kind(it.kind, locale) }
            listOf(Reply(catalogue.text(BotKey.ALTERNATIVES_NOT_USED, locale, named)))
        }

    /**
     * How current the corpus was, when that is worth a reader's attention, else null.
     *
     * Said only when the corpus is behind — including on a reply that found nothing, which is
     * where it matters most: *nothing was found* and *nothing was found yet* read identically
     * without it. Whether there is anything to say is [FreshnessLine]'s, which is also where the
     * reasoning about wording a date for a person lives; where it goes is [render]'s.
     */
    private fun freshness(freshness: Freshness, locale: String?): String? =
        freshnessLine.said(freshness, locale)

    /**
     * A kind, in words, naming a subject that stands on its own.
     *
     * Exhaustive, so a fifth kind on the wire stops this compiling. Its inbox arm is unreachable
     * for the two callers that remain — a menu offers what competed for a name and the not-used
     * line names what lost, and the inbox competes for nothing — so it is kept for the compiler
     * rather than for a reader.
     */
    private fun kind(kind: SubjectKind, locale: String?): String = catalogue.text(
        when (kind) {
            SubjectKind.CONTACT -> BotKey.KIND_CONTACT
            SubjectKind.CLIENT -> BotKey.KIND_CLIENT
            SubjectKind.BODY_OF_WORK -> BotKey.KIND_BODY_OF_WORK
            SubjectKind.CONVERSATION -> BotKey.KIND_CONVERSATION
            SubjectKind.INBOX -> BotKey.KIND_INBOX
        },
        locale,
    )

    /** What a reader counts from. */
    private fun position(index: Int): String = (index + 1).toString()

    private companion object {

        /** A blank line between a header and what it heads. */
        private const val PARAGRAPH = "\n\n"
    }
}

/**
 * A kind as somewhere a name was looked for, or null for one that is nowhere.
 *
 * Its own keys rather than the `KIND_*` ones, because the two read in different places: those name
 * a subject standing on its own, and this one is a member of a list inside a sentence about where a
 * search went. A locale whose grammar inflects the second does not get to inflect the first with
 * it.
 *
 * **The inbox is nowhere a name was looked for**, so it is dropped rather than worded. No search
 * reads it and the domain's own verdict refuses it in that set, so this is unreachable from this
 * build's backend — it is here so that one which sent it anyway renders a true list instead of a
 * false one, which is the fault this whole path exists to not have. Exhaustive, so a fifth kind on
 * the wire stops this compiling rather than being silently dropped as nowhere.
 *
 * A key rather than the text, so it needs no catalogue and no locale: what it decides is which
 * entry words a kind, which is the same decision in every locale.
 */
private fun searchedKind(kind: SubjectKind): BotKey? = when (kind) {
    SubjectKind.CONTACT -> BotKey.SEARCHED_CONTACTS
    SubjectKind.CLIENT -> BotKey.SEARCHED_CLIENTS
    SubjectKind.BODY_OF_WORK -> BotKey.SEARCHED_BODIES_OF_WORK
    SubjectKind.CONVERSATION -> BotKey.SEARCHED_CONVERSATIONS
    SubjectKind.INBOX -> null
}
