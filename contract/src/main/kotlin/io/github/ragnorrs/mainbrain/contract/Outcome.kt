package io.github.ragnorrs.mainbrain.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the interface is being asked to do with an answer.
 *
 * Semantic rather than rendered: the backend says *there are three drafts to pick from*, and how
 * that looks in a chat is the interface's business. Which is what lets a second interface render
 * the same outcome in its own register without the backend knowing there is one.
 *
 * **Sealed, so the renderer's `when` is exhaustive.** An outcome kind added here stops the
 * interface compiling until it is rendered, rather than reaching somebody as a blank reply.
 *
 * Nothing here carries prose the backend composed *about itself*. [Block.text] and
 * [MenuOption.Draft.prose] are the model's output — the content that was asked for — and the
 * distinction is the whole of the display-text rule: what the system says about itself is a key,
 * what it was asked to write is text.
 */
@Serializable
sealed interface Outcome {

    /**
     * One answer: what it says, how it is built, and what it asserts about the corpus.
     *
     * **[body] is a list of parts rather than one string, and the only thing that distinction
     * carries is which parts are headings.** A shape that answers in sections marks each heading
     * for the model to write and the domain parses it into a kind of its own; flattened to one
     * string on the way out here, that marking was destroyed and no interface could get it back.
     * What it costs a reader is the whole of an answer's navigability: a heading set in the same
     * text as the findings under it is indistinguishable from one of them, so a reply of twenty
     * findings across six sections has to be read start to finish to find anything.
     *
     * **It carries which parts are headings and not how a heading looks**, which is the line this
     * format holds everywhere: emphasis, indentation and order are an interface's, because it owns
     * the catalogue, the locale and the client's own conventions. A backend sending markup would be
     * choosing one reader's client for every reader, and a second interface would have to strip it.
     *
     * **[claims] is separate from [body] because it carries what [body] deliberately does not:
     * the identifiers.** Every assertion's text is already in [body] — the backend renders each
     * one into the answer in the place the model wrote it. What it leaves out is the identifier
     * each assertion rests on, because printed to a person that is a line of machine text per
     * finding and nothing a reader can act on.
     *
     * **So this is the same assertions in a form something can resolve.** They are the identifiers
     * of the spans each claim stands on, so an interface that means to take a reader to the message a
     * claim came from has to be handed them rather than asked to parse them back out of prose they
     * were kept out of. Beside each identifier travels what an interface shows in its place — who
     * wrote the material and when it was written — because that is the part a person can act on.
     * How they are shown is the interface's: it owns the catalogue and the layout, and a backend
     * composing either would be writing display text. Whether at all is not — an interface that
     * renders prose renders what each claim rests on, or it renders assertions a reader has no way
     * behind.
     *
     * Empty for an answer that makes no assertions rather than for one nothing checked. That is not
     * the same as the drafting case: a drafted reply is instructed to state what it says about the
     * history in cited lines beside the draft, so such an answer ordinarily does assert something —
     * what is kept out of the text itself is kept out by the mapping, not by the model asserting
     * nothing.
     */
    @Serializable
    @SerialName("text")
    data class SendText(val body: List<Block>, val claims: List<Claimed> = emptyList()) : Outcome {
        init {
            // A list of empty parts is an answer with nothing in it said at more length, so the
            // requirement is about what a reader would receive rather than about the list.
            require(body.any { it.text.isNotBlank() }) {
                "An outcome that sends text carries text to send."
            }
        }
    }

    /**
     * Something to say, named rather than worded, with what the catalogue needs to word it.
     *
     * The fields beside [key] are what the keys that take a parameter need, and every one of them
     * is absent for the keys that do not. Described that way rather than counted: a figure in prose
     * is a place a new case has to be remembered. [subjectName] is a name as it was asked for and
     * [searched] says where that name was looked for, so a reply can tell whoever asked what was
     * covered rather than only that nothing was found.
     *
     * **[nearest] is what a name was found to be spelled nearly like, which is neither of the other
     * two.** [searched] is where a name was looked for and [subjectName] is what was typed; this is
     * what the corpus turned out to hold. It exists because the two are not interchangeable and a
     * producer that filled one from the other would tell a reader a kind was searched on the
     * evidence that something of that kind was found. It is never a reading of the name — a subject
     * the name was found to *mean* travels as a [SendMenu] option, which is rendered selectable
     * because picking one is the act — and these are not pickable: they are spellings a reader is
     * shown so they can write the name the way the corpus keeps it. Empty for every key but
     * [MessageKey.SUBJECT_NEAREST_NAMES], and non-empty for that one, since without any the honest
     * reply is [MessageKey.SUBJECT_NOT_FOUND].
     *
     * **[searched] is what the search covers, not what the shape would have accepted**, and the
     * difference is visible to whoever reads it: an interface renders it as the places a reader's
     * name was looked for. [SubjectKind.INBOX] therefore never appears in it — it is reached by a
     * request having named nobody and holds no entity and no record, so no name is ever matched
     * against it.
     *
     * **An empty list reports no coverage, which is weaker than a search that covered nothing.**
     * A producer with nothing truthful to say about where a name was looked for leaves it empty —
     * an ambiguity with nothing to choose between does, holding what *matched* rather than what was
     * covered — and the keys that never had a name to look for carry no such field at all. So an
     * interface may render the absence of this field as nothing, and may never read it as evidence
     * that a search ran or did not.
     */
    @Serializable
    @SerialName("status")
    data class SendStatus(
        val key: MessageKey,
        val subjectName: String? = null,
        val searched: List<SubjectKind> = emptyList(),
        val nearest: List<SubjectRef> = emptyList(),
    ) : Outcome {
        init {
            // The key is what a reader is told and the list is the evidence for it, so the two
            // disagreeing is a reply that states a thing it does not carry. Refused here rather
            // than left to an interface, which would render the key with no names under it and
            // read as an absence worded as a near miss.
            require((key == MessageKey.SUBJECT_NEAREST_NAMES) == nearest.isNotEmpty()) {
                "Near names and the key that reports them travel together: " +
                    "$key with ${nearest.size} name(s) either states near forms it does not " +
                    "carry, or carries forms no reader is told about."
            }
        }
    }

    /**
     * Several things, one of which is to be picked.
     *
     * Alternatives as alternatives: the interface renders them selectable, which is the thing
     * concatenating them into one reply would make impossible. [prompt] is what the menu is for
     * — picking a draft is not the same act as saying which subject was meant — so the renderer
     * reads it rather than inferring the purpose from what the options happen to be.
     */
    @Serializable
    @SerialName("menu")
    data class SendMenu(val prompt: MessageKey, val options: List<MenuOption>) : Outcome {
        init {
            require(options.isNotEmpty()) { "A menu with nothing in it asks a question with no answers." }
            // One kind per menu. A mixed list is representable in the type and means nothing to
            // render — a draft and a subject are picked for different reasons — so it is refused
            // here rather than left for the interface to encounter half way down a list.
            require(options.distinctBy { it::class }.size == 1) {
                "A menu offers one kind of choice. These are " +
                    options.map { it::class.simpleName }.distinct().joinToString() +
                    ", which asks the reader to pick between a draft and a subject."
            }
        }
    }
}

/**
 * One part of an answer, and what kind of part it is.
 *
 * **Fewer kinds than the domain has, because this carries only what an interface cannot recover
 * for itself.** The domain tells a cited finding from composed prose because its grounding gate
 * has to; by the time an answer is on this wire that check has run and the findings are written
 * into the text in the places the model put them. A heading is the distinction that survives: an
 * interface composes a citation line itself and so knows which lines are its own, but a heading is
 * the model's words in the model's order, indistinguishable from any other short line once the
 * marking is gone.
 *
 * **Sealed for the same reason [Outcome] is.** A part kind added here stops every renderer
 * compiling until it is rendered, rather than reaching a reader as whatever an `else` branch
 * happened to do with it.
 */
@Serializable
sealed interface Block {

    /** The words in this part, whatever kind it is. */
    val text: String

    /**
     * What a section is called, in the model's own words.
     *
     * Carries no ordinal and no depth. There is one level of sectioning in every shape this build
     * has, and a number a reader sees is an interface's to compose from the position — which is
     * already where every other ordinal in this format is decided.
     */
    @Serializable
    @SerialName("heading")
    data class Heading(override val text: String) : Block {
        init {
            require(text.isNotBlank()) { "A heading with no words in it names no section." }
        }
    }

    /**
     * Everything that is not a heading: the answer's prose and the findings written into it.
     *
     * One part rather than one per line, because the lines of a section are read together and
     * splitting them here would put a decision about layout on the wire. An interface that wants
     * them separately splits on the newlines it was given.
     */
    @Serializable
    @SerialName("prose")
    data class Prose(override val text: String) : Block
}

/** One thing on a menu. */
@Serializable
sealed interface MenuOption {

    /**
     * One of several answers the model composed.
     *
     * **[claims] belongs to the option and not to the menu around it.** Somebody choosing the
     * second draft wants what the second draft rests on; a list pooled across the alternatives
     * would say only that one of them was supported. Empty where this alternative asserts nothing about the
     * history — the text is the deliverable here, and [prose] is what a reader sends.
     */
    @Serializable
    @SerialName("draft")
    data class Draft(val prose: String, val claims: List<Claimed> = emptyList()) : MenuOption {
        init {
            require(prose.isNotBlank()) { "A draft with no text is not one of the alternatives." }
        }
    }

    /** One thing the name might have meant. */
    @Serializable
    @SerialName("subject")
    data class Subject(val subject: SubjectRef) : MenuOption
}

/**
 * One assertion about the corpus, and the material it rests on.
 *
 * [text] is model-composed, like the prose beside it, and is not display text in the sense the
 * catalogue rule forbids: it is the content that was asked for rather than anything the system
 * says about itself.
 *
 * [cites] are the spans the claim stands on, checkable without a lookup and resolvable by whoever
 * renders them. Not what the model was shown: within a request the material is offered under short
 * numbers, which mean nothing once the request is over, so what crosses the wire is the span's own
 * identifier.
 *
 * Never empty. An assertion with no citation cannot be checked, which makes it indistinguishable
 * from composed prose — and prose belongs in the text beside this.
 */
@Serializable
data class Claimed(val text: String, val cites: List<Cited>) {
    init {
        require(text.isNotBlank()) { "A claim carries the assertion it makes." }
        require(cites.isNotEmpty()) {
            "A claim with no citation cannot be checked. Prose belongs in the answer's own text."
        }
    }
}

/**
 * One span a claim stands on, and what a reader is given in place of it.
 *
 * [span] is the written form of the identifier, a string rather than a structured identifier
 * because the form is already a format, defined in one place, and a wire type that took it apart
 * would be two spellings of one thing with nothing keeping them in step.
 *
 * **[author] and [sourceTime] are here because the identifier is not something a person can use.**
 * An interface asked to show only [span] shows a line of machine text; one handed a name and an
 * instant can say who wrote the material and when, which is enough for a reader to find it
 * themselves. They are resolved by the backend because that is where the material is still held —
 * an interface re-reading the corpus to render a citation would be reaching past the seam this
 * wire exists to be.
 *
 * [author] is the corpus's own name for whoever wrote the material and never a record identifier.
 * Null where the corpus records no name, which an interface renders as an absent one rather than
 * guessing: the several ways a name can be missing are one answer to a reader.
 *
 * [sourceTime] is when the material was written, in the form every source time in this build is
 * written. Not when it was captured, for the reason recency reads source time everywhere else, and
 * never already turned into words: an instant has no day until a zone is applied and no wording
 * until a locale is, and both of those belong to whoever is rendering rather than to this.
 */
@Serializable
data class Cited(
    val span: String,
    val sourceTime: String,
    val author: String? = null,
) {
    init {
        require(span.isNotBlank()) { "A citation names the span it resolves to." }
        require(sourceTime.isNotBlank()) { "A citation carries when its material was written." }
        require(author == null || author.isNotBlank()) {
            "A blank author is not a name. Material the corpus records no name for cites none."
        }
    }
}

/**
 * A subject, as much of one as the wire carries.
 *
 * **No opaque identifier, and that is forced rather than chosen.** A request names its subject
 * with a name and nothing else, so an identifier sent out here could not be sent back in — the
 * one thing an interface does with a picked subject is ask again, more specifically. A field that
 * looks like it round-trips and does not is worse than its absence.
 *
 * **[handle] is what makes a menu answerable within that constraint.** Two subjects of one kind
 * can share a label, and a menu offering both asks a question whose only answer is the label that
 * was ambiguous to begin with. A handle is a name as well — it resolves through the same read a
 * label does — so it round-trips where an identifier could not, and naming one picks exactly one
 * subject. Null for whatever carries none: a conversation has no handle, and neither does a
 * person nobody set one for.
 *
 * [label] is a name read off a record or an entity, not prose the backend composed, and is null
 * when whatever matched carries no name at all.
 */
@Serializable
data class SubjectRef(
    val kind: SubjectKind,
    val label: String? = null,
    val handle: String? = null,
)

/**
 * The kinds of thing a request can be about.
 *
 * The same ones the domain has, and deliberately a separate declaration: the wire format and the
 * model are free to move apart, and a shared enum would mean renaming a domain case renamed a
 * field in somebody's deployed client.
 *
 * `BODY_OF_WORK` is a body of work held as several clients — a piece of work keeping a thread per
 * strand of it. It carries no identifier here for the reason none of these does, and its label is
 * one of the strands' own: nothing stores the name the strands share, so there is no record a
 * reader could look the reduced form up in.
 */
@Serializable
enum class SubjectKind { CONTACT, CLIENT, BODY_OF_WORK, CONVERSATION, INBOX }
