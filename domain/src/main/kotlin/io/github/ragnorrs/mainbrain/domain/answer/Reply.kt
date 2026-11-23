package io.github.ragnorrs.mainbrain.domain.answer

/**
 * The format every generating shape asks the model to answer in, and the reader for it.
 *
 * **Line-oriented rather than JSON, and that follows from the layering.** `:domain` depends on
 * nothing, so there is no parser to reach for; hand-written JSON handling is a brace counter and
 * a string-escape reader, which is a great deal of code to get subtly wrong for a reply whose
 * whole structure is "some variants, each with some claims". Markers on their own lines need no
 * escaping, because nothing in prose has to be quoted for them.
 *
 * It is also the format most robust to the thing actually generating it. A model reproduces a
 * marker on its own line reliably; it drops a closing brace, trails a comma, or wraps the lot in
 * a code fence often enough that a strict reader would reject good answers.
 *
 * ```
 * [VARIANT]
 * Prose, as many lines as it takes.
 * [HEADING] the body of work this section is about
 * [CLAIM] what was agreed << 1, 2
 * [VARIANT]
 * A second draft.
 * ```
 *
 * The numbers are the ones the prompt printed beside each piece of material — see [Offer], which is
 * what resolves them back to the spans a claim can be checked against.
 */
internal object Reply {

    /**
     * The instruction block describing this format, for a shape's system prompt.
     *
     * Held here beside the reader rather than copied into each shape's prompt, because a format
     * described in five places and parsed in one is a format that will disagree with itself. What
     * a shape owns is its own instructions; how an answer is written down is shared.
     *
     * **It takes the range because the runner enforces it.** A reply whose variant count falls
     * outside what the shape asked for is rejected whole, retried once, and then answered as
     * insufficient evidence — so a format that described the marker without saying how many to
     * write left the model to volunteer a number. Several is the natural reading of a block named
     * *alternative*, and four of the five shapes accept exactly one, which made a good answer
     * indistinguishable from an empty corpus. The count is therefore stated wherever the format
     * is, rather than trusted to each shape's own prose.
     *
     * **[sections] decides whether headings are forbidden outright, and the asymmetry is the point.**
     * A shape that never sections is told not to write one at all: its answer is one message to one
     * counterpart or one reply about the open world, and a heading in either is a defect the shape's
     * own note names. A shape that may section is told neither way about a heading — not permitted
     * and not forbidden — because whether this request has sections is a fact about the material and
     * so belongs in the prompt, where [sectioning] says it, along with the marker to write one with.
     * Granting the permission here instead would hand it to every request of every sectioning shape,
     * including the ones with one section. The Markdown form stays forbidden to all five either way,
     * in the paragraph below: an interface decides its own emphasis, and a `#` reaches a reader as
     * the character it is.
     *
     * **[asserts] adds a paragraph rather than switching one, and the asymmetry is the same as
     * [sections]'s.** A shape whose answer is its cited findings is told that a statement about the
     * material is one of those findings; a shape whose deliverable is composed text is told nothing
     * extra, because the paragraph every shape gets already says that prose carries no claims. See
     * [Shape.assertsFindings] for what the runner does with the declaration — a reply that asserts
     * nothing is refused, so this is the sentence that makes that rule obeyable rather than a trap.
     *
     * **The relative-time rule is here rather than in any shape, because the shapes that offer
     * turns produced it alike.** A word that was only meaningful on the day it was written —
     * *tomorrow*, *this evening* — relayed into an answer read weeks later places nothing, and
     * reads as a commitment to a day nobody named. It is worst exactly where it matters most: the
     * staler the undertaking, the more confident the word sounds. One shape happened to name the
     * gap instead, generalising a rule of its own about unnamed authors, but nothing in the build
     * produced that, which is why it is written down here. `ShapesTest` holds it across all of
     * them rather than a count here saying how many there were.
     *
     * **Its antecedent is the quoted message and nothing else, which is the whole difficulty.** A
     * block carries three kinds of time and only one of them is this rule's: the words a person
     * wrote, which are relative to the day they wrote on; the phrases [Material] computes —
     * *today*, *now past*, *last stated so many days ago* — which are relative to the present and
     * would be thrown months out by being re-dated to a block header that is routinely old; and
     * the absolute times [Material] prints inside the text — a deadline, an event's moment — which
     * are already placed, on the one calendar every time a reply says is said in, and are about
     * when a thing is due rather than when it was written. See [Moment]. Stated over *the material*
     * the rule reaches all three, so it names the one it means and excludes the other two outright.
     * That width is the failure this build has paid for most often, and the third kind is now
     * printed in the heading's own form — so the rule says outright which line a heading is.
     *
     * **A drafted reply is excluded for the reason the paragraph below gives for claims.** Its
     * reader is the counterpart and it is read when it is sent, so *tomorrow* in it is ordinary
     * correct prose — and an obligation to resolve would put a date into the one output somebody
     * sends verbatim.
     *
     * **Not in [FORBIDDEN], and not because it declares no extent** — two members declare none and
     * say so. The registry is for prohibitions on an output *form*, and this one is about what a
     * sentence asserts: no pattern separates a word relayed out of the material from an answer's
     * own *today*, which is correct and means the day it is read.
     *
     * **The rule about a hedged finding is here for the same reason: more than one shape produced
     * it alike.** A finding the speaker left unsettled reached a reader as a parenthetical on how
     * cautiously this system had read the message, and then as a sentence saying the thing was a
     * reading of a message rather than a recorded fact — in the shape reporting where things stand,
     * and in the one preparing for a call, in the same literal rendering of a marker. So it is not
     * either shape's prose fault, and a rule written into one instruction would leave the other
     * saying it. What the marker itself says is now a fact about the speaker rather than about the
     * extraction — see `Material`, which carries that half — and what is said here is the half no
     * label can: that the bookkeeping is not the answer. Both are needed, because a model shown a
     * marker it was told nothing about renders the marker. It carries no extent either, and for the
     * first of those two reasons: no pattern tells a sentence hedging a promise from one describing
     * the reading that produced it.
     *
     * **It opens on the marker rather than on the material, which is what keeps it true of the
     * shapes that never see one.** Only the two that render a commitment or an event can show it;
     * a draft is handed messages, and the two that read the open world are handed whatever the
     * ranking found. Stated as *some material is this system's reading*, the sentence asserted to
     * three shapes that their material carries a mark it does not, which is the failure a prior
     * pass named as instructing a model about absent material. Stated as *where a piece is marked*
     * it is a conditional, and a shape shown no such mark reads nothing into it. That is also why
     * the seat here cannot borrow [STATE]'s argument: a model can call something *open* unprompted,
     * so that rule is worth stating to all five, where this one is about a marker a model cannot
     * render unless it was given it. What it is doing in the shared format instead is holding one
     * wording for the shapes that do see it, rather than two that drift.
     *
     * **It asks for less than the first wording did, because the marker records less.** Two
     * conditions set the flag — the speaker was unsure, or was passing on somebody else's — and
     * which of them it was is not stored, so a rule asking who said it and whether it was theirs
     * to settle would be asking for a fact the material does not carry, and a model answers that
     * by choosing. What it asks for instead is the hedge in the verb and no claim about its cause.
     * A drafted reply is excluded here for the reason it is excluded from the relative-time rule:
     * its reader is the counterpart, and a hedge rendered as a remark about how firmly they put
     * something belongs in an answer about the work, not in the message going back to them.
     *
     * See [STATE] for the rule about what an undertaking may be called or assumed, which is shared
     * because the shape that carried it is the one that broke it; [RESOLVED] for the form a time
     * and a figure reach the reader in, which is spliced for a third reason again — the template is
     * at detekt's method-length ceiling; [SUBJECT] for the rule that a finding has to name what
     * it is about, which is shared for the relative-time rule's reason instead: more than one shape
     * produced it alike; [NAMES] for which of the forms a person is named in the answer uses,
     * shared for that same reason; and [ABSENCE] for what a statement that something is not there
     * may say, which is shared because the shape it was read on is the one that could not hold it.
     * Each is appended or spliced rather than interpolated, and they are named rather than counted
     * — a figure in prose is a place the next one has to be remembered, and the ones written out
     * around here were not.
     */
    fun format(variants: IntRange, sections: Boolean, asserts: Boolean = false): String = """
        ${opening(sections)}

        Write plain prose with no Markdown: no asterisks or underscores around words, no
        backticks, no `#` headings. An interface decides its own emphasis, and the markers
        reach a reader as the characters you typed rather than as formatting.

        ${alternatives(variants)}

        Start each alternative with a line containing exactly:
        [VARIANT]

        Then write the answer as ordinary prose, over as many lines as it needs.

        Every assertion about the material you were given goes on its own line, in this form:
        [CLAIM] the assertion << 1, 4

        The numbers are the ones printed beside each piece of material. Cite only numbers you were
        given. A claim you cannot cite does not belong in the answer.

        Citations go after the << marker and nowhere else. Write the marker once on a line and
        separate the numbers after it with commas, as above: a line carrying the marker a second
        time is read up to the last one, so everything before it stays in the sentence a reader
        sees. Do not mark one up inside the prose, as a
        bracketed reference or in any other way: nothing reads it there, so it reaches whoever asked
        as stray characters in the middle of a sentence. Figures that are part of what you are
        saying — a price, a quantity, a document number, a date — are ordinary prose and belong
        wherever they read naturally.

        Each block of material is headed with the date, the weekday and the time its message was
        written. That heading is the numbered line the block opens with, and it is the only time in
        the block that says when the message was written: a date written in the same form inside the
        block's own text is something else the material is telling you — a deadline, or when an
        event happens — and is not the head of anything. Inside the message itself somebody may
        place something in time relative to the day they wrote on — tomorrow, this evening, on
        Monday, during the week, the day before.
        Work such a word out against the head of its own block rather than against today, and
        report the date you arrived at. Count from the weekday printed there rather than working
        out what day that date fell on, and give the weekday beside the date you report, so that
        a reader can see the step. Where it works out to no date, give the date the message was
        written and say that no day was named. Material already marked as stating no date stays
        that way: do not date it from when it was mentioned.

        Three kinds of wording are outside that and stay as they are. A phrase this system wrote
        into a block — today, ahead, now past, last stated so many days ago — is already measured
        against the present, so it is used as it stands and never re-dated. A line at the head of
        the request saying when now is, if one is there, is the present and not a block: nothing
        inside a message is worked out against it. And a reply you draft is your own voice,
        written to be read now, so a relative word belongs in it wherever it reads naturally.

    """.trimIndent() + "\n\n" + RESOLVED + "\n\n" + HEDGE + "\n\n" + """
        Prose you composed — a drafted message, a suggested wording — carries no claims. An
        assertion about what was said, agreed, promised or scheduled is a claim and carries at
        least one.
    """.trimIndent() +
        assertions(asserts) + "\n\n" + STATE + "\n\n" + SUBJECT + "\n\n" + NAMES + "\n\n" + ABSENCE

    /**
     * The form a time and a figure reach the reader in, which is the form they can be used in.
     *
     * **Two rules in one block because they are one complaint, and both were measured twice.** A
     * meeting agreed for an hour or two after a stated time reached a reader as the offset, with
     * both halves of the arithmetic in the same sentence of the same message; a price reached one
     * as a bare number with no currency, in a brief and again in the free-text answer, while
     * another figure in that same reply carried its unit. So the reader is handed something they
     * have to finish — a sum to do, or a figure they cannot act on — out of material that was
     * complete. Neither is either shape's prose fault: the shapes that compose a figure produced it
     * alike, which is the test the shared format applies, and a rule written into one instruction
     * would leave the others saying it.
     *
     * **The time half is the relative-time rule's answer applied to a second antecedent.** That
     * rule computes rather than relays, because date arithmetic left to the reader is arithmetic
     * nobody does; a word placed against another time in the same message is the same failure with
     * the anchor inside the message instead of at the head of the block. So it is stated beside it,
     * in the same terms, rather than as a rule of its own. It carries the same bound for the same
     * reason: where only one half is in hand, what was said is reported, because a clock time
     * nobody named is worse than an offset.
     *
     * **The figure half settles a conflict rather than filling a vacancy, which is why it names
     * the permission it narrows.** The shapes that grant it tell the model that a name or a phrase
     * it quotes keeps the form the source gave it — which is right about a name's script, silent
     * about which source a person's name comes from, where [NAMES] narrows it, and wrong for a
     * currency, and
     * it is what left one answer carrying a unit in the corpus's own script beside a figure with
     * no unit at all. Nothing governed either, so this says what a unit is: not a phrase being
     * quoted, so the permission does not reach it, and it is given in the language of the answer
     * like the rest of the prose around it.
     *
     * **It also says what to do where the material does not say what a figure measures**, which
     * is the direction a rule demanding a unit fails in: told only to carry one, a model supplies
     * the likeliest currency, and the answer gains a fact the corpus never held.
     *
     * Not in [FORBIDDEN], and for the reason the comma rule is not: this is a requirement rather
     * than a prohibition on an output form, so there is no string an answer must be free to
     * contain that it rules out. Nothing hermetic can read a prompt for whether it is obeyed —
     * `ShapesTest` holds that it reaches all five shapes, and only a brief read cold says the rest.
     *
     * A `val` spliced into [format] rather than a paragraph inside it, for [STATE]'s reason: the
     * template is at detekt's method-length ceiling. Spliced above the paragraph defining a claim
     * rather than appended after everything, because the time rule's antecedent is the block of
     * relative-time paragraphs it extends — and that paragraph has to stay directly above the one
     * [assertions] opens on, which an insertion between the two would strand. First of the two
     * splices for the same reason, [HEDGE] being indifferent to which side of it they fall.
     */
    private val RESOLVED = """
        A time somebody placed against another time in the same message — an hour or two after
        six, half an hour before the call, straight after the standup — is worked out and reported
        as the time of day it comes to, where the message gives you both halves. Where it gives
        you only the offset, report what was said rather than resolving it to a time nobody named.

        A figure you report carries what it measures: a price its currency, a quantity its unit, a
        duration its span. Give that beside the figure, in the language you are answering in — a
        unit is not a phrase you are quoting, so it is not kept in the form the source wrote it in,
        and two figures in one answer are not worded one way and then the other. Where the material
        does not say what a figure measures, say that it is given without one rather than supplying
        the likeliest.
    """.trimIndent()

    /**
     * How a finding the speaker did not put firmly is written, which is in the verb.
     *
     * **A `val` spliced into [format] rather than a paragraph inside it, for the reason [STATE] is
     * one: the template reached detekt's method-length ceiling.** Appended between the two halves
     * of the template rather than after it, because position is load-bearing here where it is not
     * for [STATE] — the paragraph has to stay above the one defining a claim, so that
     * [assertions]'s opening words keep the antecedent they bind to. Carried through
     * `trimIndent` of its own, which is what keeps the splice from flattening the template's
     * indentation.
     */
    private val HEDGE = """
        Where a piece of material is marked as not stated firmly or not first hand, that is this
        system's reading of a message rather than something a source stated, and the mark is on
        the wording of the reading. Where you report one, write the thing itself as less than
        settled — they said they would probably, they expect to, it was mentioned as likely — and
        carry it in the verb rather than in a remark beside it. That marker covers two cases and
        does not say which, so do not assert either one: not that the speaker was unsure of it,
        and not that they were passing on somebody else's. How the material was read is not part
        of the answer: a clause about a reading, an inference or a marker on a line is this
        system's bookkeeping, and it reaches whoever asked as a remark about the machine rather
        than an answer about their work. A reply you draft is your own voice, written to be read
        by the other party, and carries none of this.
    """.trimIndent()

    /**
     * What an undertaking whose outcome nothing records may be called or taken to be, which is
     * nothing, in either direction.
     *
     * **Here for the opposite reason to the relative-time rule: two shapes carried it already, and
     * the shape that carried it is the one that broke it.** An answer called an undertaking an
     * unclosed task, and another the only thing awaiting action from the other side — out of the
     * shape whose instruction forbade calling one *open*, *still owed*, *outstanding* or *pending*.
     * Both statements were closed lists of adjectives, neither listed the word the model reached
     * for, and no list will: a prohibition satisfied by finding a synonym is not a prohibition. So
     * what is stated is what cannot be known — nothing records whether a promise was kept — and the
     * vocabulary follows as illustration rather than as the rule. The shapes keep what is theirs:
     * which outcomes their material carries, how a closure is cited, and what not to chase. All
     * this says about a recorded outcome is that there is nothing here forbidding it, which the
     * both-directions half below makes necessary — forbidden to assume one, a model has to be told
     * that finding one written down is different.
     *
     * **Over all five, because three of them never see the label and can still say it.** Only the
     * two shapes that retrieve commitments render *outcome not recorded*; the other three are handed
     * messages, and a promise read out of a message is as unestablishable as one read off a label.
     * Seated in a shape, the rule would reach exactly the material that already states it and none
     * of the material that does not.
     *
     * **And it says what to write instead, which is the half a prohibition cannot carry.** Told
     * only what not to say, a model picks the nearest word outside the list, which is the failure
     * above. The replacement is the record's own answer: what was undertaken, and that no outcome
     * is recorded.
     *
     * **It binds in both directions, because stating one of them is what taught the other.** Told
     * at length that an undertaking with no recorded outcome may not be called open, still owed or
     * outstanding, a model does not arrive at *no state at all* — it arrives at the state on the
     * far side and writes that the thing was done. A proposed call question asserted that a
     * promised round of checking had been carried out and asked what it had turned up, over
     * material recording the undertaking and no outcome at all. So the symmetry is stated rather
     * than left to follow: what nothing records is not knowable either way round.
     *
     * **A premise is named beside the adjective, because the premise is where it was measured.** A
     * sentence resting on an outcome asserts it as plainly as one naming it, and worse for the
     * reader — it arrives as background they are assumed to share rather than as a finding they
     * could check, and no hermetic gate reads composed prose at all. Saying only *not done* would
     * leave the construction the failures were written in. The shape that asks for questions
     * carries the rest of it, where what to ask instead belongs.
     *
     * **The far half is scanned nowhere, and the near half's own scan says why that is not an
     * oversight.** *Done*, *completed* and *delivered* are correct prose wherever the records
     * report an outcome — the sentence below requires an answer to be free to write them — so no
     * substring tells the permitted use from the forbidden one, which is the ground the near-side
     * list is kept narrow on. `e2e/test_rendered.py` reads a delivered reply for a few of the
     * near-side wordings and is the one check anywhere that reads composed prose; it gains nothing
     * here.
     *
     * **The exclusions below were derived against the near half and now carry both, which is a
     * decision and not a consequence of the wording.** For the date standing and for a source's
     * own *open* it changes nothing — neither has a far side. For a drafted reply it does: ordinary
     * correspondence asking what came of something stays admitted, and so does correspondence
     * taking it as done, which is a defect in the one output somebody sends. What holds the line
     * there is the exclusion's own last clause — a claim line a draft carries is held to this rule
     * — and the shape that composes drafts, not this block.
     *
     * **Three exclusions, each of them a defect the rule would otherwise be.** A due date's
     * standing — now past, today, ahead — and how long ago something was last stated are computed
     * against the present and are facts about the date rather than states of the promise; read as
     * states they would take the one thing telling a reader what deserves attention, which is what
     * the forbidden words were doing badly. The rule is about an undertaking, so a vacancy or a
     * question a source itself calls open keeps its source's word — stated over *anything* it would
     * rewrite the open-web shape's findings. And a drafted reply is excluded as it is for relative
     * time and for the same reason: it is correspondence somebody sends, where following something
     * up is the point. What a draft reports *about* the material is not excluded, which is where a
     * claim line falls.
     *
     * **Not in [FORBIDDEN], and on the same ground the relative-time rule is kept out** — the
     * registry is for prohibitions on an output *form*, and this one is about what a sentence
     * asserts. Not from that registry's membership rule, which two members already refute by
     * declaring no extent: *open* is ordinary prose about a question, a vacancy or a source's own
     * wording, so a pattern over it would report a defect in every answer that correctly contains
     * it. `ShapesTest` holds it across the five instead, and `e2e/test_rendered.py` reads a
     * delivered reply for a few of the wordings themselves — the only check here that reads
     * composed prose rather than what a claim cited, and much the weaker of the two.
     *
     * **Appended after [assertions] rather than before it, which is not a cosmetic choice.** That
     * paragraph opens on *those assertions* and binds back to the template's last paragraph, the
     * one defining what a claim is. A block inserted between the two strands the reference — the
     * same defect, in the same slot, that the relative-time paragraphs were moved above the claims
     * paragraph to fix. Anywhere below that paragraph leaves both intact, which is what this needs
     * and all it needs: [SUBJECT] sits below this one and binds back to nothing, so the two are in
     * no competition for a slot.
     *
     * Appended rather than interpolated, for the reason [assertions] gives: `trimIndent` measures
     * every line of the template it is applied to, so a multi-line value interpolated at an indented
     * position takes the whole prompt's indentation with it.
     *
     * A value rather than a function, since it takes nothing and varies with nothing — every shape
     * is told this and told it identically, which is the whole of the rule.
     */
    private val STATE = """
        An outcome that is not recorded is not an outstanding commitment. What this
        organisation's own records hold is what somebody undertook, and sometimes a later
        message reporting it done; they do not record whether a promise was kept, because a
        promise kept in silence leaves no closing message behind. So an outcome you cannot find
        in them is one nobody wrote down rather than a thing still being waited on. Report such
        an undertaking as what the record holds
        — who undertook what, and that no outcome is recorded — and give it no state beyond
        that. Not open, not unclosed, not pending, not unresolved, not still owed, not awaiting
        anybody's action, and in no wording of your own that puts a person at the end of it,
        waiting.

        That holds in both directions, and the second half is as easy to write as the first. It
        is no more done than it is waited on: not carried out, not completed, not delivered, not
        dealt with, and not taken as read by a sentence resting on it. A question or a remark
        that asks what a promised piece of work produced has said the work happened, as plainly
        as calling it done would, and it says so as background the reader is assumed to share
        rather than as something anybody could check. Where these records do report an outcome,
        that outcome is reported as they have it. Where they report none, that is what you
        write, and anything you build on it carries no more than that.

        Three things sit outside all of that. A due date is reported as the material puts it: its
        standing — now past and by how far, today, ahead — and how long ago the thing was last
        stated are facts about the date and about the record, not states of the promise, and
        they are what tells a reader where to look first. The rule is about an undertaking and
        nothing else, so a question, a vacancy or an option that a source itself calls open
        keeps the word its source gave it. And a reply you draft is your own voice, written to
        be read now, so ordinary correspondence belongs in it — what you report about the
        material is held to this, including a claim line a draft carries.
    """.trimIndent()

    /**
     * That a finding has to name the thing it is about, and what to do where it cannot.
     *
     * **Here rather than in a shape because more than one shape produced it alike, which is the
     * relative-time rule's reason and not [STATE]'s.** A brief read before a call relayed a hedged
     * undertaking whose condition, threshold and actor were all given and whose subject appeared
     * nowhere in it, and, separately, an item identified only by the shorthand two correspondents
     * use for it. The free-text fallback then produced the same symptom on a progress fraction
     * whose denominator was never named. The figures themselves are in the pull request rather than
     * here, as the convention for anything read off the archive is.
     *
     * **The fallback is what settles which layer this belongs to**, and it is the whole of the
     * argument: that shape retrieves a ranked read of records and reads no finding at all, so it
     * runs no extraction stage to have dropped a referent — and it produces the symptom anyway.
     * The fault is therefore not the extraction's and not either instruction's. `ShapesTest` holds
     * the rule across the five rather than a count here saying how many shapes there were.
     *
     * **Both branches are stated, because only one of them is usually available and neither is the
     * default.** Where the turns that resolve a shorthand are in the material — and for the shapes
     * that retrieve a conversation's recent tail alongside their findings they routinely are — the
     * answer is to resolve it. Where they are not, the answer is to say the record does not name the
     * thing, which is a statement about the record and not a state of the undertaking, so [STATE]
     * does not reach it. Told only the first, a model shown no resolving turn invents a subject that
     * fits; told only the second, it declines to resolve a shorthand it was handed the answer to.
     *
     * **Each branch opens on a condition about the material rather than asserting one**, which is
     * the care [HEDGE]'s note asks for and the width this file has paid for most often. Only the
     * shapes that read a conversation are handed the turns either side of a finding; the two reading
     * the open world are handed whatever a ranking returned. Stated as *those turns are there for
     * this*, the sentence would tell three shapes their material carries something it does not.
     * Stated as *where it carries them*, a shape shown none reads nothing into it.
     *
     * **The shorthand is permitted where it is explained, which is the one thing a flat prohibition
     * would get wrong.** The correspondents' own word for a body of work is frequently the most
     * precise name it has, and an answer that paraphrased it away would be harder to check against
     * the conversation than one that keeps it and says once what it stands for.
     *
     * **A drafted reply is excluded, for the reason it is excluded from the relative-time rule and
     * from [HEDGE].** Its reader is the other party to the thread the shorthand belongs to, so a
     * thing spelled out in words nobody there uses reads as a message written by somebody who was
     * not in the conversation. **The exemption points at the thread's vocabulary and not at the
     * counterpart's**, which is a distinction a prior card had to make in the drafting shape: that
     * shape composes as the sender, so an exemption worded as *write it as they write it* would
     * contradict it. What a draft reports *about* the material is held to this, which is where a
     * claim line falls.
     *
     * **Not in [FORBIDDEN], on the ground the relative-time rule and [STATE] are kept out** — the
     * registry is for prohibitions on an output *form*, and this one is about what a sentence
     * identifies. No pattern separates a pronoun whose antecedent is in the same sentence from one
     * pointing at a conversation the reader cannot see, which is the whole of the defect.
     * `ShapesTest` holds it across the five instead.
     *
     * **Appended after [STATE] rather than inserted anywhere above it.** It binds back to nothing and
     * nothing binds back to it, which is what makes any slot below [assertions] available to it —
     * [STATE] satisfies the same test and sits in the one before. What is not available is anywhere
     * higher: [HEDGE] has to stay above the paragraph defining a claim and [assertions] directly
     * below it, so a block inserted between those two strands the reference its opening words carry.
     *
     * A value rather than a function, as [STATE] is: every shape is told this and told it identically.
     */
    private val SUBJECT = """
        A finding you report names the thing it is about, in terms somebody who does not have the
        conversation in front of them can identify. A pronoun, a bare demonstrative, a figure whose
        noun is left out, or a shorthand the correspondents use with each other names nothing on
        its own, and whoever reads your answer has no way to look it up — so a sentence carrying
        one reaches them with a hole where its subject should be, however accurately it reports
        what was said.

        Where the material you were given says what such a word or figure refers to, write what it
        refers to. Where it carries the turns either side of a finding, read them before relaying a
        word that points outside the sentence it sits in. A shorthand the correspondents share may
        be kept where you also say once what it stands for.

        Where the material does not say, report what it does hold and say that what you were given
        does not name the thing in question. Do not supply a subject that seems to fit: a finding
        reported as having no nameable subject is usable, and one given a subject you chose is not.

        A reply you draft is your own voice, and the shorthand the thread already uses belongs in
        it as the thread uses it, since the other party is the one reading it. A finding you report
        about the material is held to this wherever it appears, a claim line in a draft included.
    """.trimIndent()

    /**
     * Which of the forms a person is named in the answer uses, which is one of them throughout.
     *
     * **Here rather than in a shape, for the relative-time rule's reason: more than one shape
     * produced it alike.** A sectioned reply about everything headed sections with a person's
     * stored name while the prose under them named the same person in the corpus's own script; a
     * reply reporting findings named one person by account handle in some of them and by stored
     * name in others; and the one that composes claim lines beside what they rest on put a name in
     * the prose that the support line under it printed differently. Three forms of one person for a
     * reader to reconcile, none of them wrong about what was said. The figures are in the pull
     * request, as the convention for anything read off the archive is.
     *
     * **The material's own name is canonical, and that is the decision this block makes.** Two were
     * available: the form the conversation writes, or the name the record holds. The second is the
     * one already in front of the reader everywhere except the prose — the attribution and party
     * labels print it, a section's label is it, and the interface's own account of what an answer
     * rests on names the authors from the same read, since the turn read resolves a name *through*
     * the party read rather than beside it. So rendering the prose from it makes four places agree
     * by stating one rule, where choosing the conversation's form would mean rewriting a label this
     * layer composes, a heading drawn from it, and a line a different interface builds — or
     * substituting a name into a claim after the fact, which is prose surgery on a sentence a model
     * wrote.
     *
     * **It says the script out loud, because that is the direction the failure runs in.** The names
     * this corpus holds are mostly in a script the answers are not written in, so a model answering
     * in the language of the question renders them into it unprompted and reads perfectly well doing
     * so. Nothing deterministic sees it: every claim is cited, every citation resolves, and the two
     * renderings differ by no rule a pattern could state.
     *
     * **The heading half is in [sectioning] and not here, which is this file's own asymmetry rather
     * than a second thought.** A heading is where the two forms were measured against each other —
     * it is the one line in an answer copied from a label rather than composed — but a shape that
     * never sections is told in its opening line to write no heading at all, so a heading rule in
     * the shared format would speak of one to the two shapes forbidden it. Whether a heading exists
     * at all is a fact about the request, which is what puts the clause where the marker to write
     * one with already is.
     *
     * **The label a marker fuses to a name is exempted, because the material really does fuse
     * one.** A turn the archive owner wrote carries their role in brackets after the name — see
     * `Material.authoredBySelf`, which appends it to the name rather than beside it — and the shape
     * that prepares for a call tells the model never to write that parenthesis into the brief.
     * Stated without the exemption, *character for character* and *never cut to a shorter form*
     * would contradict that in the same prompt, which is the silent disagreement the relaying
     * paragraph below exists to prevent in the other direction.
     *
     * **The handle is kept rather than forbidden, and the permission is conditioned on having
     * been given one.** Nothing in [Material] prints a handle, so the only handles in front of the
     * model are the ones written inside the messages — which the same paragraph has just called no
     * name for anybody. What the rule takes away is that use; what it leaves is the one job a
     * handle alone does, which is telling apart two people the material names alike, and this
     * corpus collides stored names readily. Worded as a permission over a handle *you were given*
     * rather than over handles at large, so a shape whose material carries none reads nothing into
     * it.
     *
     * **The paragraph about relaying a turn settles a conflict rather than filling a vacancy.** The
     * shapes that read the corpus tell the model that a name or a phrase it quotes keeps the form
     * the source gave it — which this rule agrees with and which says nothing about *which* source
     * a person's name comes from. A live run of the two prompts over one set of material found what
     * that leaves: told to settle on the material's form, the model stopped rendering whole answers
     * into the corpus's script and went on naming people as their messages name them wherever it
     * was reporting what a message said, so one person arrived in two forms inside a single
     * sentence — the material's name as the party to a finding, and the message's name as a
     * participant in the turn behind it. Relaying is the ordinary way these shapes write, so that
     * is not a corner of the rule but most of it.
     *
     * **So the permission is narrowed in its own words**, which is the move [RESOLVED] makes for a
     * currency: a unit is not a phrase being quoted, and how a message addresses somebody is not a
     * name this answer carries. Stated as a general consistency rule instead, the model reads
     * relaying as the quoting case and the two rules disagree silently — in the half of the answer
     * no gate reads.
     *
     * **Two bounds are stated, because a rule about which name to use reads as a rule that there
     * must be one, and because not every name an answer carries comes from the material.** A
     * finding the material attaches nobody to has to stay that way — which [SUBJECT] also protects
     * from the other side, and which this card's own acceptance asks for outright. And the shape
     * that reads the open world names people it found on web pages, for whom there is no material
     * form to prefer: stated over every name alike, this would have told it to leave those unnamed
     * or to invent a stored form for them, which is the width this file has paid for most often.
     *
     * **A drafted reply is excluded as it is from [HEDGE], [STATE] and [SUBJECT], and the exclusion
     * covers the draft's prose rather than only how it addresses its reader.** A message calling
     * anybody — the counterpart or a third party — by the name a record holds rather than the one
     * the thread uses reads as correspondence from somebody who was not in the conversation, and
     * the drafting shape composes as the sender, which a narrower exclusion would contradict for
     * everyone in the message but its reader. What a draft reports *about* the material is held to
     * this, which is where a claim line falls.
     *
     * **Not in [FORBIDDEN], on the ground [SUBJECT] and [STATE] are kept out** — the registry is for
     * prohibitions on an output *form*, and this one is about which of several true names a sentence
     * uses. Not from that registry's membership rule either, which two members already refute by
     * declaring no extent: no pattern separates a person's name rendered into the answer's script
     * from an ordinary word of it, and a pattern over the handle's own sigil would report a defect
     * in every answer that correctly carries one. `ShapesTest` holds it across the five instead.
     *
     * Appended after [SUBJECT], which binds back to nothing and which nothing binds back to, so the
     * slot below it is free for the same reason [SUBJECT]'s own note gives for the slot it took. What
     * is unavailable is anywhere above [assertions], whose opening words bind back to the paragraph
     * defining a claim.
     *
     * **The shapes that grant the quoting permission also point it at this, which is a copy into
     * four instructions and is argued rather than assumed.** This file's own rule is that a format
     * described in five places and parsed in one will disagree with itself, and [RESOLVED] settles
     * the same permission from here for a currency. What makes this different is measurement: the
     * narrowing stated here alone left the model relaying names as its messages write them, because
     * the permission it narrows is granted in the shape's own instruction and read before this
     * block. So the pointer sits where the permission is granted, says only which form a person's
     * name takes, and leaves the rule itself in one place.
     *
     * A value rather than a function, as [STATE] and [SUBJECT] are: every shape is told this and told
     * it identically.
     */
    private val NAMES = """
        One person is named one way in the whole of your answer, and that way is the one the
        material gives for them. Wherever it names a person — who wrote a turn, who stated a
        finding, which party a finding obliges, who an attribute is about — that name is what you
        call them: character for character, in the script it is written in, never transliterated,
        never translated and never cut to a shorter form, even where every other word of the
        sentence is in another language. What a bracketed clause after a name says — that this is
        the person you are writing for, or the one being written to — is a label on the material
        and no part of anybody's name, so leaving one out is not shortening a name.

        The corpus names people in other ways, and none of those is a second name for the same
        person. Inside a message somebody may be addressed by a given name on its own, in another
        script, or by an account handle. Written into your answer beside the name the material
        gives, any of those reads as a second person, and whoever asked has no way to tell the two
        are one. So where the material names somebody, that is the name you use — and where it
        names two people alike, a handle you were given may go once beside the name to tell them
        apart, never in place of it.

        Relaying what a message said is not quoting a name, and this is where the two rules would
        otherwise disagree. Where you report a turn — who asked, who was told, who agreed with
        whom — each person in it the material names is named in the form it gives, even where the
        message's own words name them another way and even where the rest of your sentence keeps
        that message's wording. Keeping a name in the form its source gave it means the form the
        material gives for the person: how one message happens to address them is that message's
        wording and not a second name for your answer to carry.

        All of that is about the people the material names, and it asks nothing else of you. A
        finding the material attaches nobody to is reported as it stands rather than given a name
        you chose, and somebody named in a source you read rather than in the material is named as
        that source has them. A reply you draft is your own voice, written to the other party, so
        it names the people in it as the thread names them, its reader included; what a draft
        reports about the material in a claim line is held to this like any finding you report.
    """.trimIndent()

    /**
     * What a statement that something is not there may say, which is less than it was saying.
     *
     * **The one wrong answer the reader it is given to cannot catch.** Every other defect measured
     * on this archive is checkable against the material a claim cites — a wrong date, a
     * misattributed promise, a dropped party. An absence offers nothing to check, and the action it
     * invites is for whoever asked to stop looking. A reply asserted that there were no agreements
     * about a topic while a broader question, asked separately, reported an agreement on exactly
     * that topic, each with citations of its own and both passing the gate.
     *
     * **Which is the defect rather than the two answers differing.** They retrieved different
     * material, as a narrow question and a broad one should. What is wrong is that the narrow one
     * stated a corpus-level negative out of a retrieval-level one, and that distinction is in the
     * model's hands at the moment of writing: it knows what it was given and knows it is not
     * everything.
     *
     * **No check reads this and none can, which is why it is seated in the prose.** The grounding
     * gate asks whether a cited span was retrieved and resolves — see [Claim.grounded] — and for
     * most claims a citation to the message a statement was read out of is strong evidence of the
     * statement. For a negative existential the gap is total: two messages cannot evidence the
     * absence of a third, so the one claim shape that most needs checking is the shape the check is
     * blindest to. Widening the gate would mean reading free prose for what a sentence asserts,
     * which is the ground [STATE] and [SUBJECT] are kept out of [FORBIDDEN] on and the same answer
     * applies — `ShapesTest` holds that it reaches all five shapes, and a live run says the rest.
     *
     * **Over all five, because the shape this was read on is the one that cannot be fixed in its
     * own instruction.** The fallback retrieves a ranked read of the whole corpus, so its material
     * is the furthest thing from everything there is, and it is told nothing about assertions at
     * all — [assertions] reaches only the two whose answer is its findings. Seated there, the rule
     * would miss the shape that produced it; seated in a shape, it would miss the other four, each
     * of which can state an absence just as confidently.
     *
     * **It says what to write instead, which is the half a prohibition cannot carry.** [STATE] was
     * a closed list of adjectives and the model reached for the one word outside it. Told only not
     * to assert an absence, a model has the same escape and a worse one — it can write the sentence
     * as prose, where nothing marks it at all. So the permitted form is given beside the forbidden
     * one.
     *
     * **And the third paragraph is the half that decides what the reader does**, which is the whole
     * reason the defect matters out of proportion to its rate. A scoped absence a reader treats as
     * a verdict has done the same damage as an unscoped one.
     *
     * **Two exclusions, each of them a rule this would otherwise break.** An absence on the open
     * web is the research shape's to report, because that shape ran the search itself and chose
     * the terms — its own instruction says so, and the premise above does not reach a result
     * nobody handed it. And an undertaking with no outcome recorded is what [STATE] *requires* be
     * written, which is a fact about that undertaking rather than a claim about how much the
     * records hold; the fallback already carries the same carve-out against its own wording, and a
     * prohibition shared without it would contradict the paragraph above it in the one prompt
     * holding both.
     *
     * **Where the sentence goes is not here, because each shape already names its own place and
     * they disagree.** The shape reporting where things stand puts an absence in its opening and
     * nowhere else; the one preparing for a call puts it at the head of the account; the fallback
     * puts it after the answer, so that a reply does not spend its opening on how far the search
     * reached. All three are right for the shape that states them, and a shared rule naming any
     * one of them would contradict the other two — which is the test the shared format applies,
     * stated in the call shape's own note: it takes a rule only where every shape can be held to
     * it. What is shared is *once*, which all three already say.
     *
     * **And *once* alone was measured as insufficient where it stood alone, so the two shapes it
     * reaches unplaced have to be the two it cannot mislead.** Asked for an absence once and told
     * no place, the shape reporting where things stand put it in its opening and again under every
     * heading, both readings obeying the word — which is why that shape's own ask names the place
     * now. Of the two left unplaced by the paragraph above, the drafted reply is excluded outright
     * below, and the open-web shape's absence is its own to report and seated in its own
     * instruction. So no shape is told *once* with nowhere to put it.
     *
     * **The premise is hedged for the same reason the fallback hedges its own.** A corpus is not
     * always larger than the read — the pinned fixture the evaluation set runs against sits inside
     * what the fallback's widening retrieves — so a sentence asserting that a remainder exists is
     * false there. What holds in every case is the weaker thing: the material may be a part rather
     * than all, and nothing in it says which.
     *
     * **It says nothing about whether the sentence carries a citation, which is not this block's to
     * decide, and the branch that looked safe is not.** For a shape whose answer is its findings,
     * [assertions] requires a claim line and citations on the whole of what was read; for one whose
     * deliverable is composed text, the scope sentence is prose and that shape's own instruction
     * says so outright. A sentence here conditioned on *whether your findings are claim lines*
     * reaches the wrong set: every shape writes claim lines, because the format asks all five for
     * them, so the condition is true everywhere and contradicts the three that are told the scope
     * sentence carries none. The question is settled in the two places that know the answer and is
     * not restated here.
     *
     * **A `val` appended after [SUBJECT] rather than inserted above it.** It binds back to nothing
     * and nothing binds back to it, which is what makes any slot below [assertions] available —
     * what is not available is anywhere higher, since a block between the claims paragraph and the
     * sentence opening on *those assertions* strands that reference, which `ShapesTest` measures as
     * adjacency.
     */
    private val ABSENCE = """
        What you were given is what this request retrieved, which may be a part of what there is
        rather than all of it. So it can establish that something was said and it can never
        establish that something was never said: what is not in front of you may not exist, or may
        exist and not have been retrieved, and nothing you were given tells those two apart.

        So a statement that something is not there is made about the material you were given, and
        never about the records as a whole. Write that nothing in what you were given says it, or
        that the material you read carries no such thing — not that there is no such agreement, not
        that it was never promised, and not that the records hold nothing on it. The first is true
        and a reader can weigh it against the material you were given; the second asserts the whole
        of what you were not shown. Say once what you did look at, so the scope the statement was
        made in is there to be read.

        And such a statement settles nothing, which is the half that decides what the reader does
        next. An absence in retrieved material is not a finding that the thing does not exist.
        Write it as what it is — that you did not find it in what you were given — and leave what
        to do next to whoever asked: not that there is nothing to find, not that the matter needs
        no further looking, not that they have their answer. They can look again in words you were
        never given, and a sentence telling them not to is the one mistake they have no way to
        catch.

        Three things sit outside all of that. A search you ran yourself rather than material you
        were handed — the open web, where you chose the terms and saw what came back — is one whose
        result is yours to report, and finding nothing there is worth stating plainly. An
        undertaking in front of you with no outcome recorded after it is a fact about that
        undertaking rather than a statement about how much the records hold, so reporting it as the
        record has it is asked for elsewhere in these instructions and is not what any of this
        forbids. And a reply you draft is your own voice, written to be read by the other party, so
        none of this belongs inside it — what you report about the material outside the draft is
        held to all of it.
    """.trimIndent()

    /**
     * What a shape whose answer is its findings is told on top of the format, or nothing.
     *
     * **A statement about the material is an assertion however it is phrased, and saying the
     * material holds nothing about something is the hardest case.** It is a claim about the whole
     * of what was retrieved, it is the one a reply with nothing to cite reaches for, and written as
     * prose it is the half of the answer no check reads. So it is named in the terms the mistake is
     * made in, like the sectioning rule, rather than left to follow from the paragraph above.
     *
     * **It stays a claim line, and that is load-bearing rather than merely tidy.** The runner
     * refuses a reply from one of these shapes when no variant carries a claim — see
     * `Fault.NothingAsserted` — so a reply whose whole answer is that nothing was found has to
     * assert that as a claim or be rejected, retried and answered as *nothing to answer from*. An
     * honest statement of absence is exactly the answer that would be lost by moving it to prose,
     * which is why [ABSENCE] governs its wording instead of its kind.
     *
     * **Cited on the whole of what was read, not on some of it.** There is no span standing for an
     * absence, so what such a claim rests on is the material it was read against — and the reader
     * who has to judge whether the absence follows can only do that against all of it. Asked for
     * *the material you read it against*, a live run rested the absence on a handful of the pieces
     * it had been given, which puts a reader in front of an assertion about material they were
     * never shown. The read is in the pull request, where a measurement belongs.
     *
     * The consequence is stated because the runner enforces it, for the reason [alternatives] gives
     * for stating the variant count: a rule the reply is rejected over and the prompt never
     * mentioned leaves the model to volunteer the behaviour.
     *
     * Appended after [format]'s own text rather than interpolated into it, because `trimIndent`
     * measures every line of the template it is applied to — a multi-line value interpolated at an
     * indented position takes the whole prompt's indentation with it.
     */
    private fun assertions(asserts: Boolean): String = if (!asserts) {
        ""
    } else {
        "\n\n" + """
            What is asked of you here is those assertions, so a statement about the material is one
            of them. That includes saying that what you were given holds nothing about what was
            asked: write it as a claim line, and cite every piece of material you read it against
            rather than a few of them, because an absence is only as checkable as the material a
            reader can see it was looked for in. Every piece means all of them and not the ones
            that came closest: shown a handful of citations under a statement about the whole of
            what you were given, a reader cannot tell whether you looked at the rest. A reply that
            asserts nothing at all is rejected whole, because there is nothing in it anybody can
            check.
        """.trimIndent()
    }

    /**
     * The first line, which is where a shape that never sections is told so.
     *
     * One sentence rather than an appended clause, so that neither wording is assembled out of
     * fragments: what the model is told is one of two literal sentences, and reading either of them
     * is reading what it got.
     */
    private fun opening(sections: Boolean): String = if (sections) {
        "Answer in this format and nothing else. No preamble and no code fences."
    } else {
        "Answer in this format and nothing else. No preamble, no code fences, no headings."
    }

    /**
     * How to section an answer that has more than one heading to write.
     *
     * More than one body of work, or a body of work beside somebody who belongs to none — the second
     * is a heading too, and the one case where a reply has sections while spanning a single body of
     * work. Which is why what the model is told names both rather than only the first.
     *
     * **Asked per request rather than in [format], and that is not a stylistic choice.** It is true
     * of a request whose material has several headings in it and false of the next one, so putting
     * it in the system block would interpolate a per-request fact into the cache prefix and move the
     * breakpoint's contents on every call. It also has nothing to say to a question about one
     * subject, where it would be an instruction to section an answer that has one section.
     *
     * Beside the format rather than in the assembly that sends it, because what a reply looks like
     * is this file's subject. Assembly decides *whether* to ask, which is a fact about the material;
     * the asking is written down once, here.
     *
     * **Body of work first, then topic, and the reason is a correctness one.** A heading that
     * gathers one topic across several bodies of work reads as a ranking across all of them, and
     * every claim under it is individually sound — so nothing deterministic catches it, which is
     * why it is stated to the model in the terms the mistake is made in rather than as a layout
     * preference.
     *
     * **What a shape's own item bound does to this is answered here, because this is where the ask
     * is.** A shape held to a count of items small enough that each section would hold one or two
     * cannot both section and obey it, and a request carrying the two rules from two places leaves
     * the model to pick one — which it does silently, by merging the bodies of work and heading
     * nothing. So the exception is stated beside the rule it bends, and it preserves what the rule
     * is for rather than dropping it: the label moves onto the item, so no piece arrives detached
     * from the work it came from. A shape with no such bound never meets the condition, so this
     * says nothing to the one answering where things stand.
     */
    fun sectioning(): String = """
        The material comes from more than one body of work, or from a body of work and somebody
        who belongs to none, and each piece says which. Section the answer by them: a heading
        naming it, then what belongs under it, ordered the way these instructions ask for within
        each section.

        Start each heading with a line containing exactly:
        [HEADING] the name it is headed by

        A heading is not a claim and carries no citations. Mark every one of them: a heading you
        write as a plain line is read as part of the answer's prose, and if everything under it is
        later set aside it is left standing on its own with nothing beneath it.

        Do not section by topic. A heading that gathers one topic across several bodies of work
        presents one body of work's agreed order as though it held for all of them, which the
        material does not say.

        Two labels naming one body of work are one section, under the name they share. A body of
        work that keeps a separate thread per strand of it is still one body of work.

        A piece labelled with a person belongs to no single body of work. Head its section with
        that person, and do not file it under a body of work. A piece carrying no label goes
        wherever it bears on the question, or under no heading at all.

        A heading naming a person uses the name the material gives for them, so the heading and
        the prose under it call that person the same thing.

        Where you are also held to a count of items that would leave a section with fewer than two
        of them, do not section the answer: write one list and name on each item the body of work
        it belongs to. A heading is how an item is kept with the work it came from, and naming it
        on the item keeps that where a heading would be organising almost nothing. What must not
        happen either way is the two arriving merged, with nothing saying which piece belongs
        where.
    """.trimIndent()

    /**
     * How many alternatives to write, as the one sentence that has to be unambiguous.
     *
     * Stated as a count rather than as a permission: *you may write more than one* reads as an
     * invitation, and the runner does not treat it as one.
     *
     * **A shape offering a choice is also told that each alternative needs text of its own**, for the
     * reason this function states the count at all: the runner drops an alternative that is nothing
     * but claim lines, and drops the answer when too few are left to offer. [variant] deliberately
     * reads a block of claim lines with no prose as a variant, because for the shape whose answer is
     * its cited findings that is the answer — so a reply where one alternative comes back that way
     * is a shape of reply nothing had forbidden, and a rule enforced but never mentioned leaves the
     * model to volunteer the behaviour.
     */
    private fun alternatives(variants: IntRange): String = when {
        variants.first == variants.last && variants.first == 1 ->
            "Write one answer and one only: a single [VARIANT] block. A reply carrying a second " +
                "is rejected whole, not trimmed to the first."
        variants.first == variants.last ->
            "Write exactly ${variants.first} alternatives, each in its own [VARIANT] block. An " +
                "alternative is the text itself: one carrying only claim lines is dropped, and the " +
                "answer is rejected whole if that leaves too few."
        else ->
            "Write between ${variants.first} and ${variants.last} alternatives, each in its own " +
                "[VARIANT] block. Fewer or more than that is rejected whole. An alternative is the " +
                "text itself: one carrying only claim lines is dropped, and the answer is rejected " +
                "whole if that leaves too few."
    }

    /**
     * [text] read as variants, with its citations resolved against [offer].
     *
     * **A citation that failed costs its claim at the gate rather than its variant here.** Whether
     * the model named material that does not exist or wrote something that is not a number at all,
     * it followed the format, and what it cited is a question for the grounding gate. Carried on the
     * claim as [Claim.unoffered] or [Claim.unreadable], such a citation costs that claim and leaves
     * the answer around it standing — where refusing it at this point would take the variant with
     * it, fall short of the promised count, and spend the retry to arrive at nothing to answer from.
     *
     * **A line that did not follow the format at all is still dropped, and takes its variant.** An
     * assertion the model marked and then wrote no citations for is one nothing can check, and
     * folding its words into the prose would launder it past the grounding gate as composed text —
     * which is the one failure the separation of claims from prose exists to prevent. Dropping it
     * shows up as a variant count below what the shape promised, which the runner answers with a
     * retry.
     *
     * **Everything before the first marker is ignored, and that includes the line the marker ends.**
     * A model that opens with a sentence of preamble has still answered, and rejecting the reply over
     * it would spend a re-roll on something the reader can simply skip. Read as a line that has to
     * *be* the marker, the tolerance held only where the preamble ended in a newline: the same
     * sentence running straight into the marker opened no block at all, and a complete answer at the
     * highest effort reached the reader as *nothing to answer from*.
     *
     * **So the run-up is tolerated only where the model never wrote the marker on a line of its
     * own, and nowhere else.** Two bounds, and the reply that cost a full answer needs only the
     * first of them. No block may be open: once one is, a line ending in the marker is the model's
     * own answer and stays in the block it was written in. And no line may be the marker alone: with
     * one present the ordinary rule already reads the reply and everything before that marker is
     * preamble by it, so tolerating the run-up as well would open a block the model never wrote —
     * handing a shape that promises a single alternative a variant count it did not promise, which
     * is rejected whole, re-rolled and answered as *nothing to answer from* for a reply that
     * answered, and handing the shape that offers several a draft made of the model's own narration,
     * where every block still parses and no check can see it. Narrating into the first of several
     * markers is the ordinary way a model writes that, and the bound on an open block alone does not
     * reach it: the block that narration opens is the one the markers after it are counted against.
     *
     * **A line of the answer that runs into the marker keeps its words and loses the marker.** The
     * words are the model's answer and belong in the block they were written in. The literal is this
     * format's own machinery, and nothing downstream takes it out — the one set of forbidden shapes
     * is asserted over the prompt's own prose, never over a reply — so a reader handed that line
     * would be shown the seam.
     *
     * **A reply that never wrote the marker and opens on a claim or a heading is one block.** The
     * model followed the format from its second line, every assertion still goes through the gate,
     * and one block is all it can be, so a shape that promises several still rejects it on the count.
     * A reply opening on prose is not read this way: nothing in it says where an answer begins.
     *
     * **A marker with anything written after it opens nothing either way.** What follows one would
     * have to be guessed at as the new block's first line or as more of the narration. What the
     * format asks for is the marker alone on its line, so what is tolerated is a model running up to
     * it before it has written anything else, and nothing further.
     */
    fun read(text: String, offer: Offer): List<Variant> {
        val blocks = mutableListOf<MutableList<String>>()
        // Read for a marker of its own before splitting on anything, because whether the run-up is a
        // preamble at all is a fact about the whole reply and cannot be told from the line in hand.
        val alone = text.lineSequence().any { it.trim() == VARIANT }
        val opening = text.trimStart()
        val marked = opening.startsWith(CLAIM) || opening.startsWith(HEADING)
        if (VARIANT !in text && marked) blocks += mutableListOf<String>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            val preamble = !alone && blocks.isEmpty() && trimmed.endsWith(VARIANT)
            if (trimmed == VARIANT || preamble) {
                blocks += mutableListOf<String>()
            } else {
                blocks.lastOrNull()?.add(line.withoutMarker())
            }
        }
        return blocks.mapNotNull { variant(it, offer) }
    }

    /** A line of a block as the model wrote it, less a marker it ran into at the end. */
    private fun String.withoutMarker(): String =
        if (trim().endsWith(VARIANT)) trimEnd().removeSuffix(VARIANT).trimEnd() else this

    /** One block's lines as a variant, or `null` if it is empty or carries an unusable claim line. */
    private fun variant(lines: List<String>, offer: Offer): Variant? {
        val prose = mutableListOf<String>()
        val claims = mutableListOf<Claim>()
        // Every line again, in the order it was written, and not only the ones the two halves above
        // took: a heading reaches neither of them and a marked one that named nothing reaches
        // nothing at all. Kept because the split loses the order, and a shape that answers in
        // sections needs each finding to stay under its own heading rather than join a block at the
        // end. This is the half an answer is rendered from; an alternative somebody sends is
        // rendered from the prose, which is what keeps the findings out of a message being copied.
        val body = mutableListOf<Piece>()
        lines.forEach { line ->
            val trimmed = line.trim()
            val marked = trimmed.removePrefixOrNull(CLAIM)
            // Cut at the cite marker, because a heading carries no citations and was told so. What
            // follows one on this line is therefore machinery rather than words the model composed,
            // and kept it reaches the reader in the middle of a label — which is the leak the
            // unmarked-line arm below already exists to close, in the one place a marker said
            // outright that the rest of the line is not prose. Cut whether or not it resolves: the
            // lenience the claim reader extends to a doubled angle bracket in composed text is
            // extended on the strength of the line maybe being prose, and this line is not.
            val headed = trimmed.removePrefixOrNull(HEADING)?.substringBefore(CITES)?.trim()
            when {
                // **A marked heading, and it is kept out of the prose half deliberately.** What the
                // prose half decides is whether the variant carries anything at all, and a heading
                // carries nothing on its own: counting it would keep alive a variant whose findings
                // were all dropped, leaving the reader a label and no answer. Held only in [body],
                // which is what is rendered.
                //
                // A marker with nothing after it contributes neither, rather than becoming a blank
                // line: the line was never content, and [Piece.Heading] rejects an empty label.
                headed != null -> if (headed.isNotEmpty()) body += Piece.Heading(headed)

                // Marked: an assertion whatever follows, so a line with no citations at all — which
                // is the one thing [claim] refuses here — drops the variant.

                marked != null -> (claim(marked, offer) ?: return null).let {
                    claims += it
                    body += Piece.Cited(it)
                }

                // **Unmarked but cited, which is what reached a reader.** The model closed a
                // paragraph with its citations and no marker in front of them, so the line was not
                // a claim by this reader's rule and went into the prose entire — cite marker and
                // all — and somebody was shown the machinery at the end of a sentence. Read as a
                // claim instead: that removes the leak and puts the assertion through the grounding
                // gate, which is the opposite of laundering it into prose, since prose is the half
                // nothing validates.
                //
                // Recognised by citations that resolve rather than by the marker alone. A line the
                // model did not mark is only identifiable *as* a claim by them, and a composed
                // reply may legitimately contain the two characters — a time range, a comparison —
                // so requiring them to resolve is what keeps a usable draft from being answered
                // with *nothing to answer from*.
                //
                // **The whole of what follows the marker has to be citations**, which is a stricter
                // test than the claim reader's own and has to be — see [claim]'s `wholeTailCites`.
                CITES in trimmed -> when (val cited = claim(trimmed, offer, wholeTailCites = true)) {
                    null -> {
                        prose += line
                        body += Piece.Prose(line)
                    }
                    else -> {
                        claims += cited
                        body += Piece.Cited(cited)
                    }
                }

                else -> {
                    prose += line
                    body += Piece.Prose(line)
                }
            }
        }
        // Lifting a claim out closes the gap it leaves behind. The format asks the model to
        // interleave claims with prose, and a model that sets each one off with a blank line
        // either side — which reads naturally and is what they do — leaves those two blanks
        // adjacent once the claim between them is gone. The reader then gets a two-line hole
        // mid-answer that nothing wrote. Collapsed rather than rejected: the reply is correct
        // and only its spacing is an artefact of parsing it. `trim` cannot see this, since the
        // run is interior rather than at either end.
        val text = prose.joinToString("\n").trim().replace(GAP, PARAGRAPH)
        // **Blank prose is not an empty variant when there are assertions under it.** The format
        // asks for prose and claims together, but a shape may legitimately ask for the second
        // without the first — status reports "a list of dated, cited items is the answer; a
        // narrative is not" — and a model that obeys it wrote no prose at all. Dropped for that,
        // the whole answer became *nothing to answer from* on material that fully supported it.
        // What still drops is a block with neither: a stray marker the model emitted and then
        // wrote nothing after.
        return if (text.isBlank() && claims.isEmpty()) {
            null
        } else {
            // **The orphan rule is applied here as well as at the gate, because the gate is not
            // reached by every reply.** A reply whose every citation resolved has no fault, and the
            // runner returns that attempt without grounding it — so a heading the model itself left
            // empty, two of them in a row or one at the end, travelled untouched to the reader. The
            // rule is that the section is empty and not that the gate emptied it, so it holds where
            // a heading is built as well as where findings are taken away from one.
            Variant(prose = text, claims = claims, body = body.headed().spaced())
        }
    }

    /**
     * One claim line's body as a claim, or `null` if nothing was written where its citations go.
     *
     * A claim with no citation marker at all, or a marker with nothing after it, is not a claim that
     * happens to be uncited: the model was told that an assertion carries at least one, so what this
     * is looking at is a line that did not follow the format.
     *
     * **Every piece written after the marker is carried, whether or not it resolved.** A number that
     * was offered resolves to the span it names; one that was not is carried as the number — see
     * [Claim.unoffered]; a piece that read as no number at all is carried as the text — see
     * [Claim.unreadable]. The last two are claims the gate will drop, and they are carried so that it
     * can: discarded instead, a mangled citation would leave the claim resting on whatever else was
     * written and reaching the reader as checked.
     *
     * **A word sharing a piece with a number that resolved is prose, and is not carried.** That is
     * what stops `<< 1 and 4` reading as a failed citation and dropping a finding the model cited
     * perfectly well — and it is a deliberate residue of the rule above rather than an instance of
     * it. A mangled citation the model separated with a space rather than a comma — `<< 1 nonsense`
     * — is prose by this test and costs its claim nothing, where `<< 1, nonsense` costs it. So on a
     * line the model marked, how strictly a claim is treated does still turn on how it separated its
     * citations. Narrowing that would mean telling a citation-shaped token apart from an ordinary
     * word, which is a judgement about shape this parser does not make; the alternative of asking
     * the whole tail to read belongs to the unmarked line, where the citations are all that
     * identifies the line at all — see `wholeTailCites`.
     *
     * **Dropping such a claim here instead would cost more than the fault it tightens.** A `null`
     * takes the whole variant with it — see [variant] — which falls short of the count the shape
     * promised, which is fatal, which spends the retry to arrive at *nothing to answer from*. So the
     * claim survives parsing and fails the gate, where it costs itself and leaves the answer around
     * it standing.
     *
     * @param wholeTailCites where the line carries no marker, so its citations are the only thing
     *   identifying it as a claim at all. Every token after the marker then has to read as a number,
     *   not merely every piece, and at least one of them has to name material that was offered.
     *   **A forty-character identifier appearing in composed prose was evidence in itself; a small
     *   number is not.** A drafted
     *   message quoting something with a doubled angle bracket and going on to a figure — the
     *   deadline, a count, a price — leaves a bare number where a citation would be, and read as a
     *   claim the line is lifted out of the prose, cut down to the words before the marker, and then
     *   dropped by the gate: what the reader is shown is the first half of a sentence somebody meant
     *   to send. So a citation that did not read leaves the line as prose here, rather than being
     *   carried on a claim as it is above — where nothing but the citations says this was ever an
     *   assertion, carrying one that failed is how a draft becomes an assertion and then nothing.
     */
    private fun claim(body: String, offer: Offer, wholeTailCites: Boolean = false): Claim? {
        val split = body.lastIndexOf(CITES)
        if (split < 0) return null
        val text = body.take(split).trim()
        // **The comma separates one citation from the next; whitespace inside a piece does not.**
        // Cutting on both at once would be simpler and is wrong now that a citation nobody can read
        // costs its claim: a model that writes `<< 1 and 4` has cited two pieces of material and
        // joined them with a word, and a flat split makes that word a failed citation and silently
        // drops a sound finding. So the tail is cut on the comma, which is what the format's example
        // separates citations with, and a piece is read as a whole.
        val pieces = body.substring(split + CITES.length)
            .split(PIECE)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        // **Whitespace still reads inside a piece, which is what keeps `[1] [2]` working.** The
        // format's example separates citations with a comma, and a model that writes them with a
        // space instead has still written two citations; handed over whole, the piece would read as
        // one unresolvable citation. So every number in a piece is a citation of its own.
        val numbered = pieces.associateWith { piece -> piece.split(WORD).mapNotNull { offer.number(it) } }
        // Resolved once per number rather than once per use, which also makes a number written
        // twice one citation — as it already was for the spans, since those were collected into a
        // set. A claim citing the same material twice rests on it once.
        val resolved = numbered.values.flatten().associateWith { offer.span(it) }
        val cites = resolved.values.filterNotNullTo(mutableSetOf())
        val unoffered = resolved.filterValues { it == null }.keys
        // **A piece is a failed citation only when nothing in it read as a number at all.** That is
        // the line between a citation the model mangled and a word it put between two good ones: the
        // first is a piece the model delimited as a citation and nothing can resolve, the second sits
        // beside a number that resolved and is prose. Kept whole and as written, because the piece is
        // what the model offered as one citation and the report has to be able to say what that was.
        val unreadable = numbered.filterValues { it.isEmpty() }.keys
        // **Where the citations are the only thing identifying the line as a claim, every token has
        // to read.** The piece is the right unit for deciding *which* citation failed and the wrong
        // one for deciding whether this is a claim at all: a tail carrying no comma is a single
        // piece, so one bare number in it would make the rest of a drafted sentence prose beside a
        // citation and carry the line off as an assertion. That is the leak the unmarked-line rule
        // exists to close, reached through the widened piece that closes a different one above — so
        // the tail is asked the stricter question here, and `unreadable` goes on answering the
        // narrower one for a line the model marked itself.
        val everyTokenReads = pieces.all { piece -> piece.split(WORD).all { offer.number(it) != null } }
        return when {
            text.isBlank() -> null
            wholeTailCites && (!everyTokenReads || cites.isEmpty()) -> null
            // Nothing was written after the marker at all, which is a line that did not follow the
            // format rather than a claim whose citations failed.
            cites.isEmpty() && unoffered.isEmpty() && unreadable.isEmpty() -> null
            else -> Claim(text = text, cites = cites, unoffered = unoffered, unreadable = unreadable)
        }
    }

    /** This string without [prefix], or `null` if it does not start with it. */
    private fun String.removePrefixOrNull(prefix: String): String? =
        if (startsWith(prefix)) substring(prefix.length) else null

    /**
     * One form an answer may not take: the words the model is told, beside the extent they cover.
     *
     * **The pair is the whole point, because prompt prose is read for meaning by nothing.** `detekt`
     * reads style, `:archtest` reads which alphabets appear, and the tests over this file match a few
     * substrings — so a prohibition written as a sentence alone can be grammatically true, pass every
     * hermetic gate, and rule out output an answer needs. Declaring the extent beside the wording
     * lets a test ask the one question about this prose a machine can settle: does anything forbidden
     * here cover something an answer is required to contain. See [PIECE], which names the same gap
     * from the reader's side.
     *
     * **What it does not do is make the prose checked, and the incident that prompted it is the
     * proof.** The sentence that nearly shipped widened a prohibition *in prose* — it kept the
     * citation form this list declares and rewrote the words around it — so the [clause] was still
     * present and the [pattern] was never touched. Reinstate it today and this list notices nothing.
     * What catches it is the separate pin on the sentence admitting a figure, and that pin predates
     * all of this.
     *
     * So the gain is narrow, and it is only these two: a prohibition's *declared extent* cannot be
     * widened past what the evaluation set requires without a gate going red, and a declared clause
     * cannot be dropped or reworded out of the prompt without the same. A prohibition written
     * straight into [format] with no declaration is invisible, so is one whose [pattern] is narrower
     * than its [clause] claims, and so is any widening that leaves both alone.
     *
     * @param clause the words as the prompt states them, matched against the assembled prompt with
     *   whitespace flattened — so re-wrapping a paragraph is not a rewording, and a rewording is not
     *   mistaken for a re-wrap.
     * @param pattern what those words rule out of an answer, or absent where they rule out no string
     *   at all. Deliberately the narrow reading: this is applied to text an answer must be free to
     *   contain, so a pattern claiming more than the clause says reports a defect that is really in
     *   the pattern.
     *
     *   **Absent is the honest answer for a prohibition about position rather than content.**
     *   Emphasis and a heading are decided by where a marker sits, and a required string carries no
     *   position: the model is free to put it anywhere in the prose, so requiring it never forces
     *   either. A pattern guessing at that from the characters alone gets both directions wrong —
     *   `#1234` on a continuation line reads as a heading while the same text on the first line does
     *   not, and an underscore beside punctuation reads as emphasis. Such a prohibition keeps its
     *   wording checked and declares no extent, which is less than it looks and more than a pattern
     *   that is wrong.
     */
    internal class Forbidden(val clause: String, private val pattern: Regex? = null) {

        /** Whether [text] is something this prohibition rules out of an answer. */
        fun rules(text: String): Boolean = pattern?.containsMatchIn(text) == true

        /**
         * Whether this prohibition claims an extent at all.
         *
         * Read by the gate so that declaring none — the right answer for a positional rule — can be
         * told apart from declaring one that matches nothing. The two are indistinguishable through
         * [rules] alone, and the second is a check that cannot fail.
         */
        val declaresExtent: Boolean get() = pattern != null
    }

    /**
     * The output forms [format] forbids every shape, each beside the extent its clause claims.
     *
     * **The membership rule is narrower than "every prohibition in the prompt", twice over.** Only
     * [format]'s own are here, not a shape's; and only those with a form a pattern can match. So
     * `no preamble` is absent though it is unconditional and about form, because there is no string
     * an answer is required to contain that it could rule out, and *a claim you cannot cite does not
     * belong in the answer* is absent for the same reason. The opening's `no headings` is absent
     * because it is conditional on [sections].
     *
     * **A shape's own instructions are out of scope, and that is a real gap rather than a tidy
     * boundary.** Most of them are about content — *never present a guess as fact* rules out no
     * string — but not all: the open-web shape tells the model its web findings must *never carry a
     * number*, which is the same over-wide antecedent as the sentence this whole mechanism exists
     * because of, in the one shape whose evaluation case requires nothing of its answer. Nothing
     * here or anywhere else would catch it.
     */
    internal val FORBIDDEN = listOf(
        // **These two are positional, so they declare no extent — see [Forbidden.pattern].** Three
        // attempts at a pattern for them were each wrong in a new direction, and the third made the
        // reason plain: emphasis and a heading are markup by where a character sits, and a required
        // string is handed over with no position at all. `[*_]` ruled out every snake_case
        // reference; narrowing it to a lookaround ruled out every underscore beside punctuation, and
        // beside a Cyrillic letter too, since Java reads `\w` as ASCII. `(?m)^\s*#` read any string
        // starting with `#` as a heading; anchoring on a line break instead missed a heading on the
        // first line, which is where headings are. All of it reported a defect in a prompt that
        // permits the string perfectly well.
        Forbidden("no asterisks or underscores around words"),
        Forbidden("no `#` headings"),
        // The three that are a property of the string rather than of where it sits. A backtick, a
        // bracketed figure and a fence are the markup itself: an answer containing one has written
        // it, wherever it fell.
        Forbidden("no backticks", Regex("`")),
        // The form the citation rule names. *Or in any other way* closes the escape for a reader and
        // is not something a pattern can carry.
        Forbidden("as a bracketed reference", Regex("\\[\\d+]")),
        Forbidden("no code fences", Regex("```")),
    )

    private const val VARIANT = "[VARIANT]"
    private const val CLAIM = "[CLAIM]"
    private const val HEADING = "[HEADING]"
    private const val CITES = "<<"

    /**
     * What separates one citation from the next: the comma the format's example shows.
     *
     * **Required now, not merely shown, because a live run found the case this predicted.** The
     * format used to print `<< 1, 4` and say only that citations go after the marker, so nothing
     * required the separator this cuts on — and a probe against the real model came back with four
     * claim lines written `<< 4 << 5 << 6`. Read to the *last* marker, that leaves `<< 4 << 5`
     * inside the sentence a reader is shown and rests the claim on one span where the model meant
     * three. So [format] now states the comma and states what a second marker on the line costs.
     * It is still a requirement rather than a prohibition, so [Forbidden] cannot carry it and
     * nothing hermetic reads the prompt for it; what would catch a regression is another live run.
     *
     * **The comma alone, because a piece is what the model offered as one citation.** A piece that
     * resolves to no number is a citation that failed and costs its claim — see [claim] — so what
     * counts as a piece decides what counts as a failure. Cutting on whitespace too would make every
     * ordinary word between two good citations a failure of its own, and the claim would be dropped
     * for a reply that cited perfectly well. It decides that for a line the model marked only: an
     * unmarked line is held to every token reading, so what counts as a piece cannot widen what
     * counts as a claim.
     */
    private val PIECE = Regex(",")

    /**
     * What separates two citations a model wrote without the comma.
     *
     * Read inside a piece rather than instead of it, so a model that writes `[1] [2]` has written two
     * citations and a model that writes `1 and 4` has written two with a word between them. A word
     * that is not a number contributes no citation, and a piece that held none at all keeps its whole
     * text for the report — a piece that held one is reported as the citations it held, so the word
     * beside them is not kept anywhere. That is the residue [claim] records, and it is also why the
     * unmarked line is tested token by token rather than piece by piece.
     */
    private val WORD = Regex("\\s+")

    /** One blank line: what separates two paragraphs of prose. */
    private const val PARAGRAPH = "\n\n"

    /** More blank lines than that, which is a gap a lifted claim left rather than a paragraph. */
    private val GAP = Regex("\n{3,}")
}
