package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.Freshness
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.record.Source
import io.github.ragnorrs.mainbrain.domain.record.WorkUnitId
import java.time.Instant

/**
 * What the answer path produced — the end of every request, however it went.
 *
 * **There is no message key and no display text anywhere in here, and that is the shape rather
 * than an omission.** A key is part of the wire, so it lives in `:contract`, which `:domain`
 * cannot import; mapping one of these onto keys is the backend's presentation work and the only
 * place that sees both type systems. What this carries is what was decided, which is the thing
 * a domain type can be checked against.
 *
 * Sealed, and **what carries no prose is mostly not a failure** — nothing was unable to answer when
 * a name was ambiguous, when a name was never read, when none was given, when nothing matched
 * one, or when the corpus held nothing; those are answers, and a caller that reaches for prose
 * first renders all of them as empty replies. [Unreadable] is where the system did fail, and
 * exists because that is a different thing to tell a reader: the work was done and paid for and
 * could not be read back, which is not something to say in the words an absence is said in.
 *
 * Described that way rather than counted. A figure in prose is a place a new case has to be
 * remembered, and the ones written around here were not.
 */
sealed interface Answer {

    /**
     * How current the corpus was when this was answered.
     *
     * On every case, including the ones that produced no prose. An answer about what happens
     * next is confidently wrong if the corpus stops two days ago, and *nothing was found* is
     * the reply that misleads hardest while ingestion is behind — a question answered against
     * a stale corpus and a question answered against an empty one read identically without
     * this.
     *
     * **Defaulted on every case, because the runner stamps it and a stage has nothing to stamp
     * it with.** Reading how current the corpus is means reading what each adapter reported, and
     * a stage that reached ingestion state for it would be answering a question about the system
     * rather than about the request. A required parameter here would force any stage that settles
     * the request to invent the one value it must not read.
     */
    val freshness: CorpusFreshness

    /**
     * An answer the model composed, in one or more variants.
     *
     * Several variants are a first-class outcome rather than one long reply: two or three
     * drafts are selectable alternatives, and concatenating them would make the interface
     * unable to tell them apart.
     */
    data class Composed(
        val variants: List<Variant>,
        /** The subjects that matched and lost, so the answer can name what it did not use. */
        val alternatives: List<Candidate> = emptyList(),
        override val freshness: CorpusFreshness = CorpusFreshness.NOTHING_KNOWN,
        /**
         * What the corpus holds about each span the surviving claims cite.
         *
         * **Resolved once for the answer rather than inside each shape, which is what makes it
         * uniform.** Five shapes cite material, and a rule written into one of them would attribute
         * one answer's claims and leave the other four rendering citations a reader cannot use. The
         * runner holds the retrieved material and the composed answer at the same moment, so it is
         * the only place that can do this for all of them at once.
         *
         * Keyed by span rather than by claim, because a claim resting on two spans rests on two
         * authors and two dates. Empty where nothing was cited — an alternative that asserts
         * nothing, or an answer from a shape that reads no corpus.
         *
         * **An absent entry and an absent name are different things, and only the second reaches a
         * reader.** A span is here if it was retrieved, whatever the corpus knows about it: one
         * nobody is recorded as having written is present, with no author, and is rendered without
         * a name. A span that is *not* here was not in the material this answer was built from —
         * which grounding has already made impossible for a surviving claim, so the mapping treats
         * it as the contradiction it is and drops the citation rather than inventing a date for it.
         */
        val attribution: Map<SpanId, Attribution> = emptyMap(),
    ) : Answer {
        init {
            require(variants.isNotEmpty()) {
                "A composed answer with no variants is not an answer. Producing nothing is " +
                    "insufficient evidence, which is a different case and says so."
            }
        }
    }

    /**
     * There was nothing to answer from.
     *
     * Decided deterministically when retrieval returns nothing, before any model call, which
     * makes it the cheapest answer the system can produce. Also where a request degrades to
     * when generation could not be grounded — in both cases the honest statement is the same,
     * and inventing a second case would invite a caller to treat one of them as an error.
     *
     * **What is not shared is a complete reply nobody could read** — see [Unreadable]. The shared
     * answer holds wherever there is nothing the system can stand behind, and that is not what
     * happened there: an answer was written and could not be read back. Saying there was nothing to
     * answer from would be the use of this that asserts something false.
     */
    data class InsufficientEvidence(
        override val freshness: CorpusFreshness = CorpusFreshness.NOTHING_KNOWN,
    ) : Answer

    /**
     * A model answered, and nothing could be read back from what it wrote.
     *
     * **A statement about the system, where [InsufficientEvidence] is one about the material.** That
     * one says there is nothing to answer the question from, which is what an empty retrieval means
     * and what an exhausted budget amounts to. Here a model wrote a whole answer and the reply did
     * not read as one — so the shared reply would be reporting an absence to somebody whose question
     * was answered at length, which is the worst direction to be wrong in: it sends them looking for
     * a gap that is not there. What this asks for is the question again, and that is not what an
     * absence asks for.
     *
     * **Reached only where a re-roll was spent and the request still ended on a reply nobody could
     * read.** A complete reply the reader rejected is re-rolled once — see [Fault.Unreadable] for why
     * that outcome and not the ones carrying nothing readable by construction, which are answered as
     * they stand and keep the answer an empty corpus gets. Where there was nothing to re-roll, the
     * request is insufficient evidence whatever the fault, because this says a second call was made.
     *
     * **It says nothing about what was retrieved**, and must not: a shape that declares it does not
     * read the corpus — see [Shape.readsCorpus] — runs the model on an empty set as a matter of
     * course, so a request can arrive here having retrieved nothing. What it asserts is that an
     * answer was written and could not be read, which is true of every way of reaching it.
     *
     * Carries nothing but freshness. What the model wrote and which way it stopped are in the fault
     * report, where a model's prose belongs; an answer carrying them would be handing whoever asked
     * the thing that could not be read.
     */
    data class Unreadable(
        override val freshness: CorpusFreshness = CorpusFreshness.NOTHING_KNOWN,
    ) : Answer

    /**
     * The name meant more than one unrelated thing.
     *
     * A menu, not a failure. The alternative is guessing between two clients of the same name,
     * which produces a confident answer about the wrong one.
     */
    data class SubjectUnclear(
        val options: List<Candidate>,
        override val freshness: CorpusFreshness = CorpusFreshness.NOTHING_KNOWN,
    ) : Answer

    /**
     * Nothing matched, and this says what was looked through.
     *
     * [searched] rather than a bare nothing-found, so whoever asked is told where their name was
     * looked for and can judge what to do next.
     *
     * **The kinds the search covers, never the kinds a shape accepts.** See [Scope.NotFound],
     * which this is carried from: the inbox is accepted by a shape and reached by no search, so
     * filling this from an accepted set renders a reader a place their name was never looked for.
     * Empty means no coverage is reported, and whatever renders it then says nothing about it —
     * which this type permits because anything may build one, while the resolution it is carried
     * from fills it from a search that covers something. A reader of the field therefore cannot
     * read an absence as a search having run.
     *
     * **[name] is always the name that was searched for.** This is the verdict of a read, so there
     * is one by construction — see [Scope.NotFound]. The two requests that carry no name are
     * [SubjectMissing] and [SubjectUnread], and neither is answered as a search.
     *
     * **Not [SubjectNearest], whose arm found the corpus holding a name spelled nearly like the one
     * written.** This is the one reached where none exists, and also where no spelling was ever in
     * question — a name that agreed with a stored label exactly and had nothing answerable inside
     * it. So it says there is nothing to work with and nothing more than that.
     */
    data class SubjectUnknown(
        val searched: Set<SubjectKind>,
        val name: String,
        override val freshness: CorpusFreshness = CorpusFreshness.NOTHING_KNOWN,
    ) : Answer {
        init {
            require(SubjectKind.INBOX !in searched) {
                "The inbox is never searched for a name, so it cannot be among the kinds this " +
                    "reports as looked through."
            }
        }
    }

    /**
     * Nothing matched, and the corpus holds names spelled nearly like the one written.
     *
     * **Separate from [SubjectUnknown], and the difference is what the search established.** That
     * one looked for near forms and found none, which is as close as this build comes to saying the
     * corpus holds nothing of the kind. This one found some, so what is shown to be absent is the
     * form that was written and not the subject — and the thing to do is write the name the way the
     * corpus spells it rather than stop looking. Rendered as the same reply they are one message
     * that sends half its readers to a conclusion they cannot check.
     *
     * [nearest] are spellings a reader is shown, never readings of the name: nothing here claims one
     * of them is the subject, which is what [SubjectUnclear] does and why that one is a menu. A wrong
     * one costs a glance, and the right one is the only route a reader has to the form a display name
     * is stored under — the source decides that and nothing shows it to them. Non-empty, since
     * without any this is [SubjectUnknown].
     *
     * [searched] is the kinds the search covers, under the same refusal of the inbox and for the
     * same reason: it reaches a reader as the places their name was looked for.
     */
    data class SubjectNearest(
        val nearest: List<Candidate>,
        val searched: Set<SubjectKind>,
        val name: String,
        override val freshness: CorpusFreshness = CorpusFreshness.NOTHING_KNOWN,
    ) : Answer {
        init {
            require(nearest.isNotEmpty()) {
                "A reply offering no near names is a name nothing was found for, which is " +
                    "SubjectUnknown. Reaching here with none would render an empty list as an offer."
            }
            require(SubjectKind.INBOX !in searched) {
                "The inbox is never searched for a name, so it cannot be among the kinds this " +
                    "reports as looked through."
            }
        }
    }

    /**
     * The request named nobody, at a shape that answers only for a named subject.
     *
     * **Two requests arrive here, and a reply must hold for both** — see [SubjectName.None]. A
     * command that takes a subject, carrying no argument; and free text a routing call read and
     * reported as naming no particular subject. The state is about what the request said, not about
     * how it was typed, so nothing downstream may answer it as though a command had been.
     *
     * **Separate from [SubjectUnknown], because nothing was searched.** Neither of those requests
     * has been looked up and come back empty — there was nothing to look up. Answered as
     * nothing-found it states an absence from the corpus about a subject nobody named, which sends
     * the reader to the one conclusion they cannot check; what is actually missing is the subject,
     * and that they can supply in a single turn.
     *
     * **And separate from [SubjectUnread], which is the state nothing established.** There, whether
     * the request named anybody is unknown; here, it is known and the answer is nobody. The two
     * leave a reader with the same thing to do, and are still two answers: collapsing them would
     * lose what the request said, which is the distinction [SubjectName] draws. What renders them is
     * free to say the same thing twice or to say two things.
     *
     * Carries nothing but freshness — there is no name to echo and nothing was looked through, so
     * any field it offered would claim work that did not happen.
     */
    data class SubjectMissing(
        override val freshness: CorpusFreshness = CorpusFreshness.NOTHING_KNOWN,
    ) : Answer

    /**
     * What the request is about was never established.
     *
     * **Separate from [SubjectUnknown], and the difference is what the request said rather than
     * whether a search ran.** That one is reached by a request that named a subject the corpus does
     * not hold, so a read ran on the name and the answer is that there is nothing to work with.
     * This one is reached by a request that said neither, so what nobody knows is what it was
     * about. *Nothing matched* sends whoever asked looking for a gap in the material; what happened
     * here is that the name never arrived, and the thing to do is say it again. Rendered as the
     * same reply they are one message that is wrong half the time.
     *
     * **And separate from [SubjectMissing], which named nobody on purpose.** Both ask a reader to
     * name a subject and are two answers because they say different things about the request: there
     * the absence of a name is established, here it is not.
     *
     * **And separate from the inbox, which is the whole point of it existing.** A shape accepting
     * the inbox answers corpus-wide when a request names nobody, so a request whose subject was
     * lost used to be answered that way too — confidently, about everything, to a question about
     * one person. See [SubjectName.Unread] for where that is decided and why nothing downstream
     * can recover it.
     *
     * Carries nothing but freshness: there is no name to echo and nothing was looked for, so every
     * field such a reply could offer would be a claim about work that did not happen. It shares that
     * with [SubjectMissing], which is why those two are told apart by their type and never by what
     * they hold; [SubjectUnknown] always carries the name it read against.
     */
    data class SubjectUnread(
        override val freshness: CorpusFreshness = CorpusFreshness.NOTHING_KNOWN,
    ) : Answer
}

/**
 * How current the whole corpus is, across every source it is made of.
 *
 * **Not [Freshness], which is one source's own report, and the difference is why this type
 * exists.** [WorkUnitId] is the adapter's own name for a unit of its work and means nothing
 * against another adapter, so merging every source's lagging units into one list would produce
 * names that cannot be acted on without first working out which source issued them — and would
 * collapse two sources' units that happen to share a string. Keeping the source makes the
 * report actionable without translation, which is the property it is reported for.
 *
 * @property completeThrough the source time the corpus as a whole is believed complete through,
 *   which is the earliest of its sources' marks — a corpus is only as current as its least
 *   current source, and reporting the latest would claim a currency most of the material lacks.
 *   `null` when any source has nothing to report, because nothing is then known about what it
 *   holds.
 * @property lagging the units known to be behind, under the source that named them. A source
 *   with nothing lagging is absent rather than present and empty.
 */
data class CorpusFreshness(
    val completeThrough: Instant?,
    val lagging: Map<Source, List<WorkUnitId>> = emptyMap(),
) {
    companion object {
        /**
         * Nothing is believed complete and nothing is known to be lagging.
         *
         * The honest value for an answer nobody has stamped yet, rather than a currency it
         * lacks.
         */
        val NOTHING_KNOWN = CorpusFreshness(completeThrough = null)
    }
}

/**
 * One rendering of an answer.
 *
 * [prose] is composed text and is not display text in the sense the string-catalogue rule
 * forbids: a drafted reply is the content being asked for, not the interface's own words. The
 * rule exists so that nothing the system says *about itself* is hard-coded, and a draft is not
 * that.
 *
 * [claims] are the assertions about the corpus this variant makes, held apart from the prose
 * because only they are checkable. Without that separation the grounding check either blocks
 * every draft — composed text cites nothing — or verifies nothing at all.
 */
data class Variant(
    val prose: String,
    val claims: List<Claim> = emptyList(),
    /**
     * The variant in the order it was written, every line of it together.
     *
     * **[prose] and [claims] are the separation the grounding gate needs; this is the reading
     * order that separation destroys.** A shape whose answer is sections with findings under them
     * — what happened, what is open, what is next — writes each finding after the heading it
     * belongs to. Lifting every one into [claims] leaves the headings standing with nothing
     * beneath them, and a reader was shown exactly that: three headings, no content, and the
     * findings collected in a block at the end where nothing says which heading each came from.
     *
     * So the order is kept beside the split rather than instead of it. Nothing about checking
     * changes: [claims] is still every assertion, and still the only half that carries citations.
     *
     * The default is prose then claims, which is what a variant assembled by hand means and what
     * composing one from the two fields used to produce.
     */
    val body: List<Piece> = Piece.written(prose, claims),
) {
    init {
        // **Prose or assertions, not prose specifically.** This read `prose.isNotBlank()` while
        // rendering still came from that field, and the two moved apart: a variant is read back
        // from [body], so one carrying nothing but cited assertions renders exactly as the shape
        // that asked for it intended — a list of dated, cited items. Requiring prose rejected that
        // answer at the parser, which reached a reader as *nothing to answer from* on a question
        // the material fully supported.
        require(prose.isNotBlank() || claims.isNotEmpty()) {
            "A variant with neither text nor assertions is not a rendering of anything."
        }
    }

    /**
     * This variant with every claim that rests on anything outside [retrieved] removed, or `null`
     * when that leaves nothing of it.
     *
     * **Nothing left is a removal rather than an empty variant, and the difference is a crash.** A
     * variant whose assertions were the whole of it — which is exactly what the shape asking for a
     * list of dated, cited items produces — has nothing behind it once they are all dropped, and
     * [Variant]'s own initialiser says as much. Returning one anyway threw `IllegalArgumentException`
     * out of the grounding check and failed the request as a server error, which is the one thing
     * [Runner.answer] is specified never to do: every return is an answer, and *nothing to answer
     * from* is the answer this case has.
     *
     * **A claim is dropped whole rather than stripped of the one citation that was wrong**, because
     * what it asserts may rest on the span that is not there: keeping the sentence and discarding
     * the identifier would turn an unverifiable claim into prose, which is the half nothing checks.
     * What the gate asks of a claim does not depend on which way its citation failed: a span nothing
     * retrieved, a number this request never offered and a citation that read as no number at all are
     * one question here.
     *
     * **That is still this check's rule and not the whole system's, and the parser does strip before
     * this is reached.** A line the model did not mark is only identifiable as a claim by its
     * citations, so one of those that fails to resolve leaves the line as prose and never arrives
     * here at all. And on a line the model did mark, a word written beside a citation that resolved
     * is read as prose rather than as a citation that failed, so it is discarded before this check
     * sees it — [Reply.claim] states that residue and why narrowing it would mean a judgement about
     * shape the parser does not make. So a claim can arrive here looking wholly grounded when part of
     * what the model wrote where its citations go was dropped on the way.
     *
     * Dropped from [body] as well as [claims], and everything the removal leaves behind goes with
     * it: a heading whose section it emptied, and the spacing. The format asks the model to set each
     * assertion off with a blank line either side, so taking one out of the middle leaves those two
     * adjacent, and taking the first or last one out leaves a blank at that end. Neither is spacing
     * anybody wrote, and a label over a section that is not there is not an answer — see [headed],
     * which the reader applies too, for the orphans no removal here made.
     *
     * [prose] is left as it was, because it never held these assertions: they are composed into
     * [body], which is what a reader is shown an answer out of. An alternative somebody picks and
     * sends is rendered from [prose] instead, which is why removing a claim from [body] is enough
     * here and the two forms are not kept in step.
     */
    fun grounded(retrieved: Set<SpanId>): Variant? {
        val kept = claims.filter { it.grounded(retrieved) }
        return when {
            kept.size == claims.size -> this
            prose.isBlank() && kept.isEmpty() -> null
            else -> copy(
                claims = kept,
                body = body.filterNot { it is Piece.Cited && !it.claim.grounded(retrieved) }
                    .headed()
                    .spaced(),
            )
        }
    }
}

/**
 * [this] with no blank prose line at either end and no run of them in the middle.
 *
 * One blank line in the middle stays, because that is the paragraph break the model wrote. More than
 * one is the gap a removal left between the blanks that set the assertion off, and a blank at either
 * end is the same artefact where there was nothing on the far side of it.
 *
 * **Every run rather than only the ones a removal made**, which is the same rule the prose half is
 * already held to at parse time rather than a wider one: more than one blank line is spacing nobody
 * means, so there is nothing to tell apart and no second place for the two to disagree. Which is
 * also why the reader applies it when it builds a body and not only the gate when it prunes one —
 * the two halves of a variant would otherwise answer differently about the same blank lines.
 */
internal fun List<Piece>.spaced(): List<Piece> = this
    .filterIndexed { index, piece -> !piece.isBlankProse() || getOrNull(index - 1)?.isBlankProse() != true }
    .dropWhile { it.isBlankProse() }
    .dropLastWhile { it.isBlankProse() }

/**
 * [this] without the headings that have nothing left under them.
 *
 * **A heading over an empty section is an assertion with nothing supporting it.** It names the body
 * of work or the person the section is about and promises material on them, and when every finding
 * beneath one is gone what reaches the reader is a label for a section that is not there — which
 * reads as an answer that lost something rather than as one that never had it, on the half of the
 * reply nothing can check. `docs/architecture.md` states it the other way round: a reader gets the
 * findings and not merely the headings above them.
 *
 * **Emptiness rather than the absence of findings**, so that prose under a heading keeps it. A
 * section whose claim was dropped but whose composed lines remain still says something, and deleting
 * its heading would take away the only thing saying what those lines are about. Only a section with
 * nothing in it at all loses its label.
 *
 * **Applied by the reader as well as by the gate, because the rule is that a section is empty and
 * not that the gate emptied it.** An attempt whose every citation resolved is never grounded — the
 * runner has no fault to repair — so a heading the model itself left with nothing under it would
 * reach the reader untouched if this were only the collateral of a removal.
 *
 * One pass is enough: each heading's section is read independently, so two adjacent orphans both go
 * rather than the second hiding behind the first.
 */
internal fun List<Piece>.headed(): List<Piece> = filterIndexed { index, piece ->
    piece !is Piece.Heading || subList(index + 1, size)
        .takeWhile { it !is Piece.Heading }
        .any { !it.isBlankProse() }
}

private fun Piece.isBlankProse(): Boolean = this is Piece.Prose && text.isBlank()

/**
 * One line of a variant, whatever the reader made of that line.
 *
 * A line rather than a paragraph, so that reassembling them reproduces the blank lines the model
 * wrote. Blank prose lines are kept for that reason and are not a degenerate case.
 *
 * Described as every line rather than as a list of the kinds there are. An enumeration in prose is a
 * place a new kind has to be remembered, and the ones written out around here were not: each said
 * two when there were three. What a reader needs is that nothing is left out, and the exhaustive
 * `when` over this is what says so in a form the compiler keeps honest.
 */
sealed interface Piece {

    /** A line the model composed, carrying no claim and nothing to check. */
    data class Prose(val text: String) : Piece

    /** An assertion about the corpus, in the position it was made. */
    data class Cited(val claim: Claim) : Piece

    /**
     * The label on the section under it.
     *
     * **A kind of its own rather than prose, because the grounding gate has to be able to tell.**
     * A heading asserts that there is material here about whatever it names — a body of work, or a
     * person where no single body of work can be determined — and it carries no citation of its own,
     * so one whose findings are all gone is left making a claim nothing supports and the reader
     * cannot check. Distinguishing it is what lets [headed] take it away with them; the alternative
     * is guessing which prose lines are headings, which deletes lines the model wrote on purpose.
     *
     * Only the reader builds these, and only from a line the model marked as one, cut at the cite
     * marker: a heading carries no citations, so what follows one there is machinery rather than part
     * of the label. A heading the model did not mark is prose, and is still subject to being left
     * standing — the marker is what carries the structure, so nothing here recovers a heading that
     * was never announced.
     */
    data class Heading(val text: String) : Piece {
        init {
            require(text.isNotBlank()) {
                "A heading with no label places material no better than no heading does."
            }
        }
    }

    companion object {

        /**
         * Prose first and assertions after it, separated as a paragraph.
         *
         * **No prose means no blank lines in front of the assertions**, which stopped being a
         * degenerate case when a variant was allowed to carry claims without any. Blank prose still
         * `lines()` to one empty string, so composing it the obvious way opened the body with that
         * and then the paragraph break as well — two blank lines before the first finding, in the
         * shape a status report produces. Nothing would have shown it: the one consumer joins the
         * body and trims the result, so the invariant was being kept a layer away from the code
         * responsible for it, by accident.
         */
        fun written(prose: String, claims: List<Claim>): List<Piece> {
            val written = if (prose.isBlank()) emptyList() else prose.lines().map(::Prose)
            return when {
                claims.isEmpty() -> written
                written.isEmpty() -> claims.map(::Cited)
                else -> written + Prose("") + claims.map(::Cited)
            }
        }
    }
}

/**
 * One assertion about the corpus, and the spans it rests on.
 *
 * It matters most where the derived layer is weakest. A commitment is model output, so an
 * answer stating one as fact is grounded in an earlier inference, and the citation is what
 * turns it back into something the reader can check.
 */
data class Claim(
    val text: String,
    val cites: Set<SpanId>,
    /**
     * The numbers this claim cited that were never offered for anything.
     *
     * **A citation that resolved to nothing has to be representable, or it cannot cost the claim.**
     * The model cites a per-request number rather than a span identifier — see [Offer] — so a number
     * outside the offer names no span, and there is nothing to put in [cites] for it. Discarded, it
     * would leave an assertion resting on whatever else it cited and reaching the reader as checked,
     * with part of what it was asserted on never compared against anything; made up as a span that
     * cannot exist, it would reach the wire and the fault report as an identifier nobody can look up.
     *
     * So it is carried as what it was: a number the model wrote. [grounded] is false while any is
     * here, which drops the claim on the survivable path and leaves the rest of the answer standing.
     *
     * Empty for a claim assembled by hand, which is every one a stage composes rather than reads.
     */
    val unoffered: Set<Int> = emptySet(),
    /**
     * What this claim cited that was not written as a number at all, as the model wrote it.
     *
     * **The same argument as [unoffered], and it does not stop applying because the identifier was
     * mangled rather than merely wrong.** A piece the reader cannot resolve was support the model
     * meant the assertion to have, so discarding it leaves a claim resting on whatever else it cited
     * and reaching the reader as checked, with part of what it was asserted on never compared
     * against anything.
     *
     * **What this does not reach is a word written beside a citation that resolved.** A piece holding
     * one number that read is a citation and the rest of that piece is prose, so whether a mangled
     * citation is caught here turns on whether the model separated it with a comma: `<< 1, nonsense`
     * costs its claim and `<< 1 nonsense` does not. The residue is deliberate and [Reply.claim]
     * states it — the alternative was telling a citation-shaped token apart from an ordinary word,
     * and guessing wrong there dropped sound findings for replies that cited perfectly well.
     *
     * **The text rather than a count or a sentinel.** A count cannot be reported, and a made-up
     * number would reach the fault report as a citation somebody could look up and find. What a
     * reader of the log has to answer is what the model actually wrote where a number belonged, so
     * that is what is kept.
     *
     * [grounded] is false while any is here, which drops the claim on the survivable path. Empty for
     * a claim assembled by hand, and for every one whose citations all read.
     */
    val unreadable: Set<String> = emptySet(),
) {
    init {
        require(cites.isNotEmpty() || unoffered.isNotEmpty() || unreadable.isNotEmpty()) {
            "A claim about the corpus with no citation cannot be checked, which makes it " +
                "indistinguishable from composed prose. Prose belongs in the variant's text."
        }
        require(unreadable.none { it.isBlank() }) {
            "A citation that could not be read is carried so the fault can name it, and blank " +
                "text names nothing. Nothing was written there at all, so there is no citation."
        }
    }

    /**
     * Whether everything this rests on read as a citation, was offered, and is among [retrieved].
     *
     * **The whole of the grounding rule, in the one place both halves of the gate read it.** What
     * survives and what is reported are decided separately, and a rule written out twice is written
     * once inverted — the two then disagree about a claim resting on two spans where one is missing,
     * which is reported as ungrounded and shown anyway, or the reverse.
     *
     * Every span rather than any, because what the claim asserts may rest on the one that is absent.
     * A number nobody offered and a token that is not a number fail for the same reason and not a
     * weaker one: each is support the model meant the assertion to have, and it is not there. The
     * three are one rule here rather than three tests elsewhere, so that the strictness cannot come
     * to depend on which way the citation went wrong.
     */
    fun grounded(retrieved: Set<SpanId>): Boolean =
        unoffered.isEmpty() && unreadable.isEmpty() && cites.all { it in retrieved }
}

/**
 * Who wrote the material behind a citation, and when they wrote it.
 *
 * **Beside [Claim] rather than on it, because this is the corpus's and a claim is the model's.** A
 * claim is what was asserted and which spans it cited; this is what the corpus holds about those
 * spans, read once the answer has survived its grounding. Folded into the claim, a value read out
 * of the store would be indistinguishable from one the model wrote — and what makes a citation
 * worth anything is precisely that it did not come from the model.
 *
 * **It exists because an identifier is not something a reader can use.** A span identifier
 * resolves, which is what the gate needs; a name and a date are what a person needs to go and find
 * the material themselves.
 *
 * [sourceTime] is when the material was written and not when it was captured, which is the time
 * every recency decision in this build reads. Carried as an instant: how long ago that is depends
 * on when somebody reads the answer, and saying it in words is an interface's job and a locale's.
 *
 * [author] is the corpus's own name for whoever wrote it, never a record identifier. Null where the
 * corpus records no name — the message records none, the author has no contact behind it, or
 * neither that contact nor its human was ever named. Those are one answer to a reader, which is the
 * convention a finding's absent party already follows.
 */
data class Attribution(val sourceTime: Instant, val author: String? = null) {
    init {
        require(author == null || author.isNotBlank()) {
            "A blank author is not a name. Material the corpus records no name for carries none."
        }
    }
}
