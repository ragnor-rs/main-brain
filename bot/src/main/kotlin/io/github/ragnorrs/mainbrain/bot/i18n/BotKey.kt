package io.github.ragnorrs.mainbrain.bot.i18n

/**
 * What this interface says on its own account, named rather than worded.
 *
 * A second enum beside the wire format's `MessageKey`, and the split is the point. That one is
 * what the *backend* says about itself, so it is part of the wire format and every interface
 * receives it. This one is what *this* interface says about itself — the command list, the header
 * above a draft, how an alternative is labelled — none of which the backend knows exists. Widening
 * the wire enum with these would put one interface's furniture in the format every interface reads.
 *
 * Nothing here is display text and nothing here is in any language. The names are codes; the one
 * place words exist is a resource bundle.
 */
internal enum class BotKey {

    /** What this assistant answers and how to ask it, for `/help` and for `/start` alike. */
    HELP,

    /**
     * A command this interface does not offer, taking the list of the ones it does.
     *
     * **It takes the list rather than carrying one.** The words a mistyped command sends a reader to
     * are [HELP]'s words, so the two cannot come to disagree about what exists — and a second list
     * would be wrong in the place it is read by somebody who has just got a command wrong, which is
     * the worst place to be wrong. What the entry holds is the frame: that a mistyped command should
     * be said in some particular way is a locale's business, and what the commands *are* is not.
     */
    UNKNOWN_COMMAND,

    /**
     * The answer was being composed and this interface stopped waiting for it.
     *
     * **Its own entry rather than the wire format's *cannot answer right now*.** That one is said
     * where something the service needs is not answering, and coming back later is the advice it
     * carries. Here nothing was down — every hop landed and the work was accepted and unfinished
     * at each one — so a reader told the service is unavailable goes looking for an outage there is
     * none of, and coming back is the action least likely to help, since a question that overran
     * may overrun again at the same cost on the most expensive call this build makes. One key for
     * both is wrong in both halves of the sentence it renders.
     *
     * **In this enum because the backend never says it** — from the far side the work was taken and
     * is being done, so this is a bound one interface keeps on its own waiting rather than a state
     * the service reports. `MessageKey` records why a case there would be wrong.
     *
     * It names no duration. The bound is a constant in the client, and a figure here would be a
     * second place to change it — which is how a reply comes to quote a number that has moved.
     */
    ANSWER_OVERRAN,

    /**
     * The header above one of several drafts, taking the variant's number.
     *
     * Inside the draft's own message rather than on the prompt, because the numbering is what
     * makes a message identifiable once several are on screen.
     */
    VARIANT,

    /** One thing a name might have meant, taking its number, its label and its kind. */
    SUBJECT_OPTION,

    /**
     * The same for a subject that carries a handle, taking it last.
     *
     * **The handle is shown because it is what the reader answers with.** Two people can share a
     * display name, and a menu listing it twice offers a choice that cannot be made — naming the
     * label returns the same menu. The handle names one of them, so it belongs on the line rather
     * than being something the reader has to know to go and find.
     */
    SUBJECT_OPTION_HANDLED,

    /** A subject with a handle and no name of its own: its number, its handle and its kind. */
    SUBJECT_OPTION_HANDLE_ONLY,

    /** The same, for a candidate the corpus holds no name for: its number and its kind. */
    SUBJECT_OPTION_UNNAMED,

    /** The name that was searched for, taking it. Said beside a refusal that carries one. */
    SUBJECT_NAMED,

    /** What was looked through, taking the kinds. Said beside a refusal that reports them. */
    SEARCHED_AMONG,

    /**
     * The spellings the corpus holds that are near the name asked for, taking them.
     *
     * **A clause of the refusal rather than a menu, because none of these is a reading of the
     * name.** The subjects on a menu are what a name was found to mean and are rendered one per
     * line and numbered, which tells a reader to pick one. These were found only to be spelled
     * nearly like what was typed, so numbering them would invite a choice between a stranger and
     * the person meant — the thing the menu rule refuses. Run into the sentence, they are what they
     * are: forms to recognise, and to ask again with.
     *
     * What it takes is the names already joined, for the reason [SEARCHED_AMONG] takes its list
     * joined — the separator is a locale's business and this is the default locale's.
     */
    NEAREST_NAMES,

    /**
     * One kind as somewhere a name was looked for, for the list [SEARCHED_AMONG] takes.
     *
     * Separate from the `KIND_*` entries, which name a subject standing on its own. These stand
     * inside a sentence saying where a search went, and a locale that inflects a list member
     * differently from a heading needs both — the default one does, which is why there are two
     * sets rather than one reused.
     *
     * There is no entry for the inbox, and that is the point: it is reached by a request having
     * named nobody and holds nothing to match a name against, so it is nowhere a name was looked
     * for and belongs in no list claiming otherwise.
     */
    SEARCHED_CONTACTS,

    /** Clients, as somewhere a name was looked for. See [SEARCHED_CONTACTS]. */
    SEARCHED_CLIENTS,

    /**
     * Bodies of work, as somewhere a name was looked for. See [SEARCHED_CONTACTS].
     *
     * Beside clients rather than folded into them, because the two are different places to a
     * reader: one is a thread somebody named, and this is the work several of those are strands of.
     */
    SEARCHED_BODIES_OF_WORK,

    /** Conversations, as somewhere a name was looked for. See [SEARCHED_CONTACTS]. */
    SEARCHED_CONVERSATIONS,

    /**
     * The subjects that matched and were not used, taking them.
     *
     * Its own message after the answer. An answer about the wrong subject is the failure that is
     * invisible afterwards, and naming the alternative is what makes it recoverable in one turn.
     */
    ALTERNATIVES_NOT_USED,

    /**
     * How current the corpus is, taking how old the material is in words.
     *
     * **The frame, not the age.** What goes in it is one of the `AGE_*` entries below, which is why
     * this takes a phrase rather than a time: a locale that says the age differently reworks those
     * and leaves this alone. It keeps an argument rather than becoming eight whole sentences
     * because the literal run in front of `{0}` is what the end-to-end suite recognises this line
     * by, and a frame guarantees that run exists however the ages are worded.
     */
    CORPUS_COMPLETE_THROUGH,

    /**
     * What a reply's assertions rest on, taking who wrote the material and the span of days it
     * falls in.
     *
     * **One block for the reply rather than a line under every finding.** A name and an elapsed age
     * per cited message identifies nothing a reader can open, and twenty findings' worth of them
     * buries the answer; the names and the dates together are what somebody actually searches a
     * conversation by. The identifier itself is on the wire and is deliberately not here — it is
     * machine text and nothing a reader can act on.
     *
     * Dates and not ages, because material past the point where an age stops counting is worded
     * the same however old it is, and a span of two such reads alike at both ends however far
     * apart they are.
     */
    SOURCES_BETWEEN,

    /** The same where every cited message was written on one day, taking that day alone. */
    SOURCES_ON,

    /**
     * The same where the corpus records no author for any of it, taking the span alone.
     *
     * Rare and not impossible: an unnamed author costs the block its names and not the block.
     */
    SOURCES_UNNAMED_BETWEEN,

    /** And the one-day span of that, taking the day alone. */
    SOURCES_UNNAMED_ON,

    /**
     * How a date is written, as a pattern rather than as words.
     *
     * **A catalogue entry because the order of the fields is a locale's own** — day before month is
     * not universal, and a pattern in Kotlin would compile one locale's convention into every
     * locale's reply. It must render digits and separators only: a month name would need the
     * platform to hold locale data for every language a bundle ships, and the digits are what keep
     * the result composed of this interface's own characters.
     */
    SOURCE_DATE,

    /**
     * What an alternative rests on, said above one draft's assertions and where they came from.
     *
     * **Its own message, after the drafts and never inside one.** A reader copies an alternative and
     * sends it, so anything added to that message is something they have to delete first — and a
     * draft's assertions are in the third person about the person being written to. They are still
     * worth having: they say what the draft was written from. So they arrive apart from the drafts,
     * headed by this, and a reader forwarding one forwards none of it.
     *
     * **It takes the alternative's number, because the drafts are sent together and adjacency no
     * longer says which is which.** Every draft the reader might copy comes first, so nothing they
     * are not sending sits between the things they are; what that costs is the basis sitting
     * directly under its draft, and a number is what replaces it. The number is the same one the
     * draft's own header carries.
     */
    DRAFT_BASIS,

    /**
     * Whole hours, taking the count — in the three grammatical numbers the floor locale needs.
     *
     * **A category per key, because the count cannot be formatted into one sentence.** The
     * catalogue substitutes literally rather than through `MessageFormat`, deliberately, so a
     * plural is chosen by picking a key and not by a pattern inside one. Which categories exist
     * is the locale's own business — two languages rarely agree on how many there are — so a
     * locale needing a different set adds keys and the selection that reads them.
     */
    AGE_HOURS_ONE,

    /** The same, for a count the floor locale treats as a small plural. */
    AGE_HOURS_FEW,

    /** And for every other count, which is the form a locale without the distinction uses. */
    AGE_HOURS_MANY,

    /** Whole days, taking the count, in the same three numbers as the hours above. */
    AGE_DAYS_ONE,

    /** The same, for a count the floor locale treats as a small plural. */
    AGE_DAYS_FEW,

    /** And for every other count. */
    AGE_DAYS_MANY,

    /**
     * Something old enough that how old stops mattering.
     *
     * Past this the reader's decision is the same whichever number it is — the answer is built on
     * material nobody should rely on — so counting further buys precision nothing acts on.
     */
    AGE_LONG_AGO,

    /** Which sources are behind, taking them. */
    CORPUS_LAGGING,

    /**
     * A source, in the name it is known by outside this build.
     *
     * One per source, found by name from what the wire carries, so a source added later is a key
     * and a bundle entry rather than a branch in the renderer. The wire carries the service's own
     * constant for a source — an internal spelling — and a reader of the lagging line above would
     * otherwise be shown it.
     */
    SOURCE_TELEGRAM,

    /** A contact: a person. */
    KIND_CONTACT,

    /** A client: an organisation. */
    KIND_CLIENT,

    /** A body of work, which is every strand of one piece of work rather than one of them. */
    KIND_BODY_OF_WORK,

    /** A conversation, which is a record rather than an entity. */
    KIND_CONVERSATION,

    /** The inbox, which has no entity behind it at all. */
    KIND_INBOX,
}
