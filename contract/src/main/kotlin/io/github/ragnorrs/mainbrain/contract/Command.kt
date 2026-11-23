package io.github.ragnorrs.mainbrain.contract

/**
 * The commands the backend answers, and what each one accepts.
 *
 * **Here because [AnswerRequest.command] is a string and these are the words a command list may
 * offer.** Not every value a surface accepts: a caller may name the fallback, which this
 * deliberately does not declare, so what is valid on the wire is a wider set than this and the
 * registry is what holds it. The field has carried the word from the start; what it never carried
 * is any statement of which words a list may offer, or of what follows one. An interface offering a
 * command list therefore had nothing to build one from and wrote it out in prose — which is how the
 * one text that tells a reader what the system can do came to disagree with what it does, in four
 * places at once.
 *
 * **What this is for is making that text checkable, and telling a command from a typo.** A test
 * holds the interface's command list to this declaration, and another holds this declaration to the
 * shapes that answer it. No interface composes its list from this: the bot's list is prose in a
 * locale bundle, which is what it has to be, because what a command is *called* is one fact and how
 * it is described to a reader is another.
 *
 * **What an interface does read off it is the set of words, and that is not an opinion about which
 * shapes exist.** A leading slash-word no command is called is answered at the interface's own edge
 * rather than forwarded: forwarded, it binds the free-form shape, which spends an answering call on
 * a question whose ranked read is against text carrying a word that means nothing, and says nothing
 * about the mistake. The interface is not deciding which words those are — it is reading the one
 * declaration that says, which is why this lookup is [named] here rather than a set built at each
 * caller.
 *
 * **The word is the shape's identifier, and this does not make it a second one.** It is tempting to
 * present this the way the two [SubjectKind] enums are presented — a wire declaration free to move
 * apart from the model's — but that would be false here. There is no mapping between the two: the
 * check keys one side by a shape's identifier and the other by [word] and requires the sets to be
 * equal, so renaming a shape renames a command. That is the existing decision rather than a new
 * one, and it is `Shape.id`'s: one value for both, because two would be able to disagree and the
 * disagreement reads as a command that silently answers through the wrong pipeline. What this enum
 * adds is not a second name but two things the identifier alone could not say — that the set is
 * closed, so a list can be enumerated, and what each member accepts.
 *
 * **The commands an interface answers for itself are not here.** What a bot answers out of its own
 * catalogue without reaching the backend is that interface's furniture, and the backend has never
 * heard of it — so an interface offering such a command is the one responsible for listing it. This
 * is the shared half, not the whole of what any one interface accepts.
 *
 * **Nor is the fallback here, which is a different reason.** A command *can* name it — the registry
 * resolves any identifier it holds, the fallback included — so what is true is not that nothing
 * names it but that no command list offers it. Offering it would invite a reader to ask for the
 * shape they already get when nothing else matched.
 */
enum class Command(
    /**
     * The subject kinds this command can be asked about.
     *
     * Empty means it needs no subject at all: the text after the word is the whole of what scopes
     * it. Mirrors the accepting shape's own set, and a test checks that it does.
     *
     * A command accepting [SubjectKind.INBOX] can also be asked having **named nobody**, which is
     * what makes the subject-less summary a question rather than a missing argument — and *named
     * nobody* rather than *arrived without a name*, because a request whose subject was never
     * established carries no name either and must not be widened to everything. That follows from
     * the set and is not declared twice.
     */
    val accepts: Set<SubjectKind>,
) {

    /** Where a subject stands, at whatever scope the name picks out — or across everything. */
    STATUS(
        accepts = setOf(
            SubjectKind.CONTACT,
            SubjectKind.CLIENT,
            SubjectKind.BODY_OF_WORK,
            SubjectKind.CONVERSATION,
            SubjectKind.INBOX,
        ),
    ),

    /** Replies to a contact, as variants somebody picks from. */
    DRAFT(accepts = setOf(SubjectKind.CONTACT)),

    /** What to know before speaking to a contact. */
    PREPARE(accepts = setOf(SubjectKind.CONTACT)),

    /** A question answered from the open web, with its sources. */
    RESEARCH(accepts = emptySet()),

    ;

    /**
     * The word, as somebody types it after the prefix.
     *
     * Derived from the case rather than declared beside it, for the reason the class note gives:
     * two values would be able to disagree, and a command that is listed and answers nothing is
     * exactly what that disagreement looks like.
     */
    val word: String get() = name.lowercase()

    /**
     * What the text after the word is for.
     *
     * **Derived from [accepts], not declared, because the two would otherwise be able to
     * disagree** — and a declaration saying a command takes a name while accepting no subject kind
     * is the very defect this type exists to prevent, reproduced one level up. A command that
     * accepts no kind has nothing a name could resolve against, so the text can only be the
     * question itself; one that accepts a kind is scoped by the name.
     */
    val argument: Argument get() = if (accepts.isEmpty()) Argument.QUESTION else Argument.NAME

    /** What the text after a command word is for, which is not the same question for all of them. */
    enum class Argument {

        /** A name to resolve a subject from, which may be absent where the command allows it. */
        NAME,

        /**
         * The question itself.
         *
         * The one argument that is not a name, and the one a reader cannot guess: a command whose
         * value is entirely in what it is asked will be given a company name by anybody following
         * a list that does not say otherwise.
         */
        QUESTION,
    }

    companion object {

        /**
         * The command [word] names, or `null` when no command is called that.
         *
         * **One answer to *is this a command*, because two would be able to disagree.** An
         * interface that recognises a word no command is called forwards a typo; one that refuses a
         * word a command *is* called makes a declared command unreachable. Both are the same
         * mistake made from opposite sides, and neither is visible in the reply it produces.
         *
         * [word] is matched as [Command.word] spells it, which is lowercase. Folding what somebody
         * typed is the business of whoever read it off the message, since what counts as the same
         * word is the platform's convention and not this declaration's.
         *
         * A word this returns `null` for is not necessarily one the registry holds no shape for —
         * the fallback is the standing case, and the class note says why it is not declared here.
         * What this answers is whether a *command list* offers the word, which is the question an
         * interface refusing one has.
         */
        fun named(word: String): Command? = entries.firstOrNull { it.word == word }
    }
}
