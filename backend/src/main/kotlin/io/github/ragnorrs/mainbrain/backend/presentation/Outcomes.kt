package io.github.ragnorrs.mainbrain.backend.presentation

import io.github.ragnorrs.mainbrain.contract.AnswerRequest
import io.github.ragnorrs.mainbrain.contract.AnswerResponse
import io.github.ragnorrs.mainbrain.contract.Block
import io.github.ragnorrs.mainbrain.contract.Cited
import io.github.ragnorrs.mainbrain.contract.Claimed
import io.github.ragnorrs.mainbrain.contract.Freshness
import io.github.ragnorrs.mainbrain.contract.MenuOption
import io.github.ragnorrs.mainbrain.contract.MessageKey
import io.github.ragnorrs.mainbrain.contract.Outcome
import io.github.ragnorrs.mainbrain.contract.SubjectKind
import io.github.ragnorrs.mainbrain.contract.SubjectRef
import io.github.ragnorrs.mainbrain.domain.answer.Answer
import io.github.ragnorrs.mainbrain.domain.answer.Attribution
import io.github.ragnorrs.mainbrain.domain.answer.Candidate
import io.github.ragnorrs.mainbrain.domain.answer.Citation
import io.github.ragnorrs.mainbrain.domain.answer.CorpusFreshness
import io.github.ragnorrs.mainbrain.domain.answer.Piece
import io.github.ragnorrs.mainbrain.domain.answer.Request
import io.github.ragnorrs.mainbrain.domain.answer.Variant
import io.github.ragnorrs.mainbrain.domain.index.SpanId
import io.github.ragnorrs.mainbrain.domain.answer.SubjectKind as DomainSubjectKind

/**
 * Where an answer crosses between the two type systems.
 *
 * This is where a domain answer becomes a wire outcome, and it is the only thing standing between
 * `:domain`'s deliberate absence of message keys and an interface that needs one for everything it
 * says about itself. It is not the only domain-to-wire mapper in the process — `UnresolvedMapping`
 * is the other — but it is the only one carrying anything the system says *about itself*, which is
 * what the next paragraph is about.
 *
 * **It is also the enforcement of the third validation clause.** The runner checks that
 * citations resolve and that the variant count matches the shape's contract, and says in its own
 * comments that *no display text where a message key belongs* cannot be checked there, because
 * `:domain` holds no keys to compare against. What makes it hold is a property of the types
 * rather than of this file's care: every slot for something the system says *about itself* is
 * typed [MessageKey], so no string can reach one. The plain strings on the wire are of two kinds,
 * and neither is prose this backend wrote about itself. Some are names and values echoed back from
 * the request or read off a record — a status's `subjectName`, a subject's `label` and `handle`, a
 * citation's `author` and `sourceTime`, and, on the advisory read, a pair's contact identifiers,
 * display names and signal kinds. The rest is the model's own output, the thing that was asked for:
 * a claim's `text`. A citation's `span` is composed here, by [Citation.render] — an identifier
 * rather than language, carrying no words in any locale, and the one form that has to round-trip
 * for a citation to resolve. How long ago a `sourceTime` is stays unsaid, because saying it is
 * choosing words in a locale.
 *
 * Mapped exhaustively, with no `else`. A new answer kind should stop this compiling rather than
 * reach an interface as whatever the catch-all happened to say.
 */
internal fun Answer.toResponse(): AnswerResponse = AnswerResponse(
    outcome = toOutcome(),
    freshness = freshness.toWire(),
    // Only a composed answer has losing subjects to report: every other case settled before
    // anything was chosen, so there is no alternative that was not used.
    notUsed = (this as? Answer.Composed)?.alternatives?.map { it.toWire() }.orEmpty(),
)

/**
 * One variant as a person reads it: every part of it, in the order it was written.
 *
 * **The claims are most of the answer, and sending the prose alone threw them away.** The
 * format separates them so that the grounding gate has something to check — an assertion about
 * the corpus carries citations and composed prose does not — and that separation is about
 * checking, not about what to show. Measured on one real question: two lines of prose against
 * six claim lines, so the reader was getting under a fifth of what the model wrote, and getting
 * it as a summary with every specific removed. An answer that reads as a hedge is the worst
 * possible report of retrieval that is working.
 *
 * **Read back in the order they were written, which is the second half of the same mistake.**
 * Sending the claims but collecting them into a block at the end was measured next: the shape
 * that answers in sections writes each finding under its own heading, so lifting every one of
 * them out left the headings standing empty and piled the findings at the bottom, where nothing
 * said which heading each had come from. The grounding gate still reads the split halves; this
 * reads the order kept beside them.
 *
 * The identifiers stay out. They are what makes a claim checkable and the validation has
 * already read them; printed to a person they are a line of machine text per assertion, and
 * nothing a reader can do with one.
 *
 * **Applied to an answer somebody reads and not to an alternative somebody sends, and the reason it
 * was once applied to both is worth stating because it was wrong.** This read that a drafted reply
 * carries no claims at all, so its body was prose alone and the interleaving could not affect it.
 * The drafting shape instructs the opposite: the draft itself carries no assertions, and anything
 * the model needs to state about the history goes in a separate cited line *outside* it. So a draft
 * variant routinely carries claims, the body interleaves them, and what a reader copied out of the
 * message they were told to send was a line of third-person commentary about the person they were
 * sending it to. An alternative is built from the prose instead — see [toOutcome], which also says
 * what keeps a drafting shape's answer out of this branch.
 *
 * The marker is punctuation rather than display text: it carries no words and reads the same in
 * every locale, so it does not belong in a catalogue.
 *
 * **A heading stays a heading, and flattening it into the prose beside it was a defect.** The
 * marker that announced it is not something to show — an interface decides its own emphasis — but
 * *which part is a heading* is not emphasis, it is structure, and it is the one thing about an
 * answer's shape that an interface cannot work out for itself. Joined into one string here, every
 * heading reached six measured replies as a short line of ordinary text, so a reader navigating a
 * twenty-finding answer had nothing to navigate by. What goes out is the marking; what to do with
 * it stays the interface's.
 *
 * Prose and findings adjacent to each other become one part, because they are read together and
 * one part per line would be this file deciding a layout. Whether a heading with nothing under it
 * arrives here at all is settled before this — by the reader where the model left the section
 * empty, and by the grounding gate where it emptied one.
 */
private fun Variant.blocks(): List<Block> = body
    .fold(mutableListOf<Block>()) { blocks, piece ->
        val line = when (piece) {
            is Piece.Prose -> piece.text
            is Piece.Cited -> "$ASSERTION${piece.claim.text}"
            is Piece.Heading -> null
        }
        when {
            line == null -> blocks += Block.Heading((piece as Piece.Heading).text)
            // Appended to the part being built rather than started as a new one, so that the lines
            // of a section arrive together however many pieces the model wrote it in.
            blocks.lastOrNull() is Block.Prose ->
                blocks[blocks.lastIndex] = Block.Prose(blocks.last().text + "\n" + line)

            else -> blocks += Block.Prose(line)
        }
        blocks
    }
    .mapNotNull { block ->
        when (block) {
            is Block.Heading -> block
            // Trimmed per part, which the single string this replaced did once at the end. The
            // blank line the model writes between a section and the next heading is absorbed into
            // the part before it and taken off here, so the separation above a heading is composed
            // by the interface instead — see `ClaimSources.under`, which owns that spacing now
            // that it is told which parts are headings. A part left with nothing in it is dropped:
            // it would otherwise reach a reader as a gap with no content.
            is Block.Prose -> block.text.trim().takeIf { it.isNotEmpty() }?.let(Block::Prose)
        }
    }

/** What separates one assertion from the next. Punctuation, carrying no words and no locale. */
private const val ASSERTION = "• "

/**
 * This variant's assertions with their citations, for a caller that wants them addressable.
 *
 * **Beside the rendered prose rather than inside it.** [blocks] deliberately leaves the
 * identifiers out, because printed to a person they are a line of machine text per assertion —
 * and that stays true. What this adds is the same assertions in a form something can resolve: an
 * interface that means to link a claim to the message it rests on has to be given the identifier
 * rather than asked to parse one back out of prose it was deliberately kept out of.
 *
 * It is also what makes delivery checkable. Whether a claim the runner validated survived the
 * mapping out to the wire is invisible in every other dimension — grounding passed, the prose
 * reads fine, and a reader simply cannot get to the material.
 *
 * **Each identifier carries what an interface shows in its place**, which is what [attribution]
 * supplies: who wrote the material and when. The identifier is what resolves and what the gate
 * reads; a name and a date are what a reader can act on, and an interface handed only the first
 * has nothing to render but machine text.
 *
 * Sorted citations, because a set's iteration order is an accident of how it was built and two
 * identical answers would otherwise serialise two ways. Sorted on the identifier rather than on
 * the date, so the order does not change with what the corpus happens to hold.
 *
 * **A citation [attribution] has no entry for is dropped, and takes its claim with it only if it
 * was the last one.** That is what grounding already does with a citation resting on nothing
 * retrieved, for the reason the architecture gives: one gone wrong costs the finding it was on
 * rather than the twenty that were sound. It is unreachable rather than expected — grounding
 * removes every claim citing a span outside the retrieved material, and everything retrieved is
 * attributed — so this is what makes the dependency on that ordering visible instead of rendering
 * a date nobody read off the corpus. A claim lost here fails the delivery dimension, which is the
 * one place it would be seen.
 */
private fun Variant.claimed(attribution: Map<SpanId, Attribution>): List<Claimed> = claims.mapNotNull { claim ->
    val cites = claim.cites.mapNotNull { span ->
        attribution[span]?.let {
            Cited(
                span = Citation.render(span),
                sourceTime = it.sourceTime.toString(),
                author = it.author,
            )
        }
    }.sortedBy { it.span }
    if (cites.isEmpty()) null else Claimed(text = claim.text, cites = cites)
}

private fun Answer.toOutcome(): Outcome = when (this) {
    // One variant is an answer; several are a choice. The interface is told which by the outcome
    // kind rather than by counting a list, because a menu of one and a plain reply are the same
    // thing to a reader and concatenating several is the failure this distinction prevents.
    //
    // **The count is safe to read because the runner holds it to the shape's promise, and it is
    // held to it after the repairs rather than before.** That matters here and nowhere else: a
    // drafting shape promises two or three, and a variant can be removed for resting on nothing
    // retrieved or for having no text to send. Were the count only checked against what the model
    // wrote, a two-alternative reply losing one would arrive here as a single variant and take the
    // branch below — rendering a draft through `blocks()`, with the third-person assertions folded
    // back into the text somebody was told to copy. So a drafting shape's answer never reaches that
    // branch, and the prose-versus-reading-order decision stays settled by which outcome this is.
    is Answer.Composed -> if (variants.size == 1) {
        Outcome.SendText(
            body = variants.single().blocks(),
            claims = variants.single().claimed(attribution),
        )
    } else {
        Outcome.SendMenu(
            prompt = MessageKey.CHOOSE_A_DRAFT,
            // **The prose and not the reading order, which is the opposite of the line above and the
            // whole of the difference between the two outcomes.** What this carries is a message
            // somebody is about to send: a reader picks one alternative, copies it, and pastes it
            // into the conversation. The assertions the model wrote beside it are about that
            // conversation and in the third person, so interleaved into the text they are sent to
            // the person they describe. An answer is read and an alternative is used, and only the
            // second has to survive being copied.
            //
            // **The claims travel beside the alternative they belong to, which is per option and
            // not per menu.** Somebody choosing the second draft wants what the second draft rests
            // on; a list pooled across the alternatives would say only that one was supported. So
            // they are kept out of the sendable text and carried on the option, which is the one
            // place a reader's choice can reach them from.
            //
            // Blank prose cannot arrive here, because the runner drops an alternative that has no
            // text to send and refuses the answer when too few are left — so `MenuOption.Draft`'s
            // own requirement is unreachable rather than relied on.
            options = variants.map { MenuOption.Draft(prose = it.prose, claims = it.claimed(attribution)) },
        )
    }

    is Answer.InsufficientEvidence -> Outcome.SendStatus(MessageKey.NOTHING_TO_ANSWER_FROM)

    // Two keys for what used to be one, because the two statements are not the same one: an answer
    // nobody could read is about this service and an empty corpus is about the material. Which of
    // them happened is settled in the domain, where the fault that ended the request is known — so
    // nothing here reads a flag or infers a cause, and a case added there stops this compiling.
    is Answer.Unreadable -> Outcome.SendStatus(MessageKey.ANSWER_UNREADABLE)

    // An ambiguity with nothing to choose between is not a menu. The runner builds this from a
    // resolution that holds more than one option, so an empty list means a shape settled with one
    // itself — and to whoever asked, an ambiguity offering no choices is indistinguishable from
    // nothing having matched. Said that way rather than rendered as a menu with no entries.
    //
    // **Neither parameter is filled, because nothing here can fill them truthfully.** The
    // candidates this carries are what *matched*, and the field means what was *looked through* —
    // reporting one as the other would tell the reader a kind was searched on the evidence that
    // something of that kind was found. The name is not in this answer at all.
    is Answer.SubjectUnclear -> options.map { it.toWire() }.let { offered ->
        when {
            offered.size <= 1 -> Outcome.SendStatus(key = MessageKey.SUBJECT_NOT_FOUND)
            offered.none { it.nameableAmong(offered) } ->
                Outcome.SendStatus(key = MessageKey.SUBJECT_INDISTINGUISHABLE)

            else -> Outcome.SendMenu(
                prompt = MessageKey.SUBJECT_AMBIGUOUS,
                options = offered.map { MenuOption.Subject(it) },
            )
        }
    }

    // **Neither the name nor the searched kinds, because there is neither.** Nothing was looked
    // through and no name arrived, so filling either field would report work that did not happen —
    // and reporting this as nothing-found would tell the reader their subject is absent from the
    // corpus when what is absent is the name itself.
    is Answer.SubjectUnread -> Outcome.SendStatus(key = MessageKey.SUBJECT_NOT_READ)

    // **Its own key, decided in the domain, for the reason the two above are.** A command given no
    // argument named nobody and was never searched for, so neither field can be filled here either
    // — and the key an empty search produces would report an absence from the corpus about a subject
    // the reader never named. Which of the two no-name states this is the resolver knows and nothing
    // here infers: a mapping that read *subject missing* off both fields being absent would be
    // reading a cause out of empty fields, which is what this file refuses to do.
    is Answer.SubjectMissing -> Outcome.SendStatus(key = MessageKey.SUBJECT_MISSING)

    // **The near forms are what matched nearly, and they go in the field that means that.** The
    // kinds searched stay what the search covered, which is the distinction the sibling arm above
    // refuses to blur: a name found to be spelled nearly like a contact's says nothing about which
    // kinds were read. Carried as subjects rather than as bare strings so an interface can say what
    // kind each near form is, which is what tells a room's name from a person's.
    is Answer.SubjectNearest -> Outcome.SendStatus(
        key = MessageKey.SUBJECT_NEAREST_NAMES,
        subjectName = name,
        searched = searched.map { it.toWire() }.sorted(),
        nearest = nearest.map { it.toWire() },
    )

    is Answer.SubjectUnknown -> Outcome.SendStatus(
        key = MessageKey.SUBJECT_NOT_FOUND,
        subjectName = name,
        // Sorted, because the order a set iterates in is an accident of how it was built rather
        // than anything meaning: two shapes declaring the same kinds in a different order would
        // otherwise serialise the same answer two ways. A settled order is what makes the body
        // something a test can assert on.
        searched = searched.map { it.toWire() }.sorted(),
    )
}

/**
 * How current the corpus was, as much of it as an interface can act on.
 *
 * The lagging work units are deliberately dropped and only their sources kept. A unit is the
 * adapter's own name for a piece of its work: it means nothing to a reader, cannot be acted on
 * without knowing which adapter issued it, and sending it would put an internal identifier in
 * front of a person. Which sources are behind is the part that changes what an answer is worth.
 */
private fun CorpusFreshness.toWire() = Freshness(
    completeThrough = completeThrough?.toString(),
    lagging = lagging.keys.map { it.name }.sorted(),
)

private fun Candidate.toWire() = SubjectRef(kind = kind.toWire(), label = name, handle = handle)

/**
 * Whether a reader could pick this option out of [offered] by naming it.
 *
 * **The only way to answer a menu is to name the option that was meant**, so an option nothing
 * distinguishes is one that cannot be chosen — and a menu where that is true of every option asks a
 * question whose every answer is the question again.
 *
 * **Both arms ask the same question, which is why neither is a bare null check.** A handle is the
 * stronger name and still not a unique one: a platform releases a handle and the next person takes
 * it, and the corpus keeps every claim it ever saw, so two subjects here can carry one. Counting is
 * what establishes the property the arm relies on rather than assuming it.
 *
 * **Compared case-insensitively, because resolution is at least that.** The read these names go
 * back through lowercases and collapses whitespace, so `ANNA` and `Anna` are one name to it and two
 * to a raw comparison — and the option counted as distinguishable is then the one whose name returns
 * the same menu. [io.github.ragnorrs.mainbrain.domain.answer.ScopeResolver] already folds case for this
 * reason where it excludes a chosen name.
 *
 * **Resolution equates more than case in a label, and this does not see it.** A label is matched in
 * a reduced form that crosses a script and drops what distinguishes two spellings of one name, so
 * two options whose labels differ only by that much are counted distinguishable here while naming
 * either returns both. The reduction is the store's one definition and cannot be reached from
 * presentation without a second copy of it, so the gap is named rather than closed: what shrinks it
 * is that an option carries a handle wherever its subject has one, and a handle is compared
 * unreduced. Two options both lacking a handle and sharing a reduced label are the case left open.
 *
 * It is a weaker test than resolving each option would be — a name unique on the menu may still
 * match something the menu does not list — and deliberately so: the strong version is a search per
 * option, and what this has to catch is the case where the reader has nothing to type at all.
 */
private fun SubjectRef.nameableAmong(offered: List<SubjectRef>): Boolean {
    val nameable = handle ?: label ?: return false
    return offered.count { it.nameAsTyped().equals(nameable, ignoreCase = true) } == 1
}

/**
 * The string a reader would name this option by: its handle, else its label.
 *
 * Null for an option carrying neither, which no count should match — including another option in
 * the same state, since two unnameable options are not one nameable one.
 */
private fun SubjectRef.nameAsTyped(): String? = handle ?: label

private fun DomainSubjectKind.toWire(): SubjectKind = when (this) {
    DomainSubjectKind.CONTACT -> SubjectKind.CONTACT
    DomainSubjectKind.CLIENT -> SubjectKind.CLIENT
    DomainSubjectKind.BODY_OF_WORK -> SubjectKind.BODY_OF_WORK
    DomainSubjectKind.CONVERSATION -> SubjectKind.CONVERSATION
    DomainSubjectKind.INBOX -> SubjectKind.INBOX
}

/**
 * The request as the runner takes it.
 *
 * Null for a request the domain type refuses — which today is a blank ask. Returned rather than
 * thrown because a malformed request is a status code and a key, and letting the domain's own
 * `require` escape would turn it into an unexpected failure: a 500 for something the caller got
 * wrong, with nothing in the reply to say what.
 */
internal fun AnswerRequest.toDomain(): Request? =
    if (text.isBlank()) {
        null
    } else {
        Request(
            text = text,
            // Emptied is the same as omitted here too, which is the reading the domain already
            // takes of a blank command. Normalised here so that the check refusing a word naming
            // no shape is not handed a blank one to refuse.
            command = command?.takeIf { it.isNotBlank() },
            // **Emptied is the same as omitted, and normalising it here is what keeps that from
            // being either a crash or a wrong answer.** The domain type refuses a blank name
            // rather than carrying one, because which absence a request means decides whether a
            // shape sweeps the inbox or asks who was meant. On the wire the field is a plain
            // optional string and a caller that is not the interface shipped here may well send
            // it empty meaning *no argument* — so the honest reading of an empty one is the
            // absence it was meant as, converted at the boundary that knows the wire's
            // conventions rather than raised at a caller who got nothing wrong.
            subjectName = subjectName?.takeIf { it.isNotBlank() },
        )
    }
