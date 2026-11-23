package io.github.ragnorrs.mainbrain.domain.answer

import io.github.ragnorrs.mainbrain.domain.Effort
import io.github.ragnorrs.mainbrain.domain.ModelOutcome
import io.github.ragnorrs.mainbrain.domain.ModelProvider
import io.github.ragnorrs.mainbrain.domain.ModelRequest
import io.github.ragnorrs.mainbrain.domain.TokenUsage

/**
 * Reads free text and says which shape should answer it.
 *
 * A port, because the only thing that can do this is a model call and `:domain` reaches one
 * through [ModelProvider] — but also because the deterministic case has to stay cheap. A request
 * carrying an explicit command never arrives here, so nothing is spent classifying what was
 * already named.
 *
 * **It picks among the shapes that exist and holds no list of its own.** The candidate set is
 * handed in by whoever is binding, so registering a shape makes it classifiable and removing one
 * makes it unreachable, with nothing here to keep in step. A classifier naming shapes itself
 * would be a second registry that could disagree with the first — and that extends to what a
 * shape *answers*, which arrives with each candidate rather than being described here.
 */
interface Classifier {

    /**
     * Which of [among] should answer [text], and what that text is about.
     *
     * Naming no shape is the ordinary answer rather than a failure: text that is not one of the
     * shapes is what the fallback is for, and a classifier forced to choose would route a
     * general question into a pipeline whose retrieval cannot serve it. The subject is not
     * nullable in the same way — it has three states rather than two, for the reason
     * [Verdict.subjectName] gives. [Verdict.usage] is returned because the call is model spend
     * and a bound that ignored it would not be a bound.
     */
    suspend fun classify(text: String, among: List<Candidate>): Verdict

    /**
     * A shape this may choose, and the line that says what it answers.
     *
     * **The description travels with the identifier because the identifier alone underdetermines
     * the choice.** One word has to stand for everything a pipeline covers, and the shape answering
     * where things stand is also the only one that reads what was agreed and whether anything
     * discharged it — which `status` does not say. A model asked to prefer no choice over a poor
     * fit, and given the word, reasonably declines; the request then reaches the fallback, which
     * reads messages and no part of the derived layer.
     *
     * Ordered, because the prompt lists them in the order it is given them: a set would leave the
     * line order a property of how the collection was built.
     *
     * [description] is [Shape.description], copied by whoever is binding rather than restated here.
     */
    data class Candidate(val id: ShapeId, val description: String)

    /**
     * What a classification decided, and what deciding it cost.
     *
     * **[subjectName] is here because the alternative is a wrong answer rather than a refusal.**
     * An interface separates a name out of a command, where the name is an argument; it cannot do
     * that for a sentence, and a sentence is what free text is. Left unresolved, a request about a
     * named client routed to the shape that accepts the inbox resolves to *the inbox* — which
     * answers confidently about the wrong subject, and is the one failure mode that is invisible
     * afterwards. So the call already reading the text to route it reads the name out of it too,
     * at no additional call.
     *
     * **Which is why it is three-valued and not a nullable name.** [SubjectName.None] is the text
     * naming nothing in particular, which is the daily question the inbox exists for.
     * [SubjectName.Unread] is this call not having said — a reply of one line answers which
     * pipeline and not who, and a name it wrote that is not in the request was not copied out of
     * it. The mitigation above only works while those two are told apart: reported as one absent
     * value they arrive at the resolver identically, and the shape accepting the inbox answers
     * the second as though it were the first.
     *
     * A name invented rather than copied is worse than none for the same reason it is not none:
     * it would be searched for, match nothing, and report that nothing was found about something
     * nobody asked about.
     */
    data class Verdict(
        val shape: ShapeId?,
        /**
         * Defaulted to nothing having been established, which is what a verdict nobody read the
         * text for says. A default of *named nobody* would have the one construction that makes
         * no call at all assert something about a request it never looked at.
         */
        val subjectName: SubjectName = SubjectName.Unread,
        val usage: TokenUsage = TokenUsage.NONE,
    )
}

/**
 * Classification as one cheap model call.
 *
 * **Cheap by construction rather than by intention.** The reply is two short lines — an
 * identifier, and a name copied out of the request — the effort is the lowest the model has,
 * and the ceiling is small, though not as small as the answer: thinking counts against it, and
 * a ceiling sized to the reply alone returns a truncation instead. Routing is the one model
 * call on the path that every request pays for, so it is the one where the cost has to be a
 * rounding error.
 *
 * **A shape it does not recognise is no shape, including a reply that is not one of the
 * candidates.** A model naming a shape that was not offered has not classified anything, and
 * accepting the nearest match would make the routing depend on string similarity between a
 * hallucination and a real identifier.
 *
 * **The subject does not fall back the same way, and that is the point of the type it carries.**
 * A second line this cannot use is not *no subject* — it is a subject this call did not establish,
 * which the resolver must refuse rather than answer corpus-wide. See [SubjectName] and [subject].
 */
class ModelClassifier(private val model: ModelProvider) : Classifier {

    override suspend fun classify(text: String, among: List<Classifier.Candidate>): Classifier.Verdict {
        if (among.isEmpty()) return Classifier.Verdict(shape = null)
        val outcome = model.respond(
            ModelRequest(
                system = SYSTEM,
                prompt = prompt(text, among),
                maxTokens = MAX_TOKENS,
                effort = Effort.LOW,
            ),
        )
        // Only a finished reply is read. A truncation, a refusal and a pause all mean nothing was
        // decided, and the fallback is what answers an undecided request — which is also what it
        // answers an unclassifiable one with, so there is nothing further to distinguish here.
        val lines = (outcome as? ModelOutcome.Answered)?.text?.trim()?.lines().orEmpty()
        return Classifier.Verdict(
            shape = among.firstOrNull { it.id.value == named(lines) }?.id,
            subjectName = subject(lines, text),
            usage = outcome.usage,
        )
    }

    /**
     * The identifier the reply named, read up to a colon.
     *
     * **The tolerance exists because the prompt now shows each candidate as `identifier: what it
     * answers`, and a model mirroring the line it was shown has not failed to classify.** Matched
     * byte-exact, `status: where things stand` names no candidate, the verdict carries no shape,
     * and the request falls to the fallback — which is this card's own defect arriving through the
     * parser instead of through the prompt, silent and indistinguishable afterwards from a question
     * that genuinely fitted no shape. It is also the asymmetry the subject line already removed:
     * the comparison whose failure costs an answer must not be the strict one. See [subject].
     *
     * **Not a nearest match, which stays refused.** A label the prompt itself displayed is dropped;
     * nothing is guessed at. An identifier holds no colon — it doubles as a command, so a colon in
     * one would not survive the interface either — so no legitimate name is shortened by this, and
     * a reply naming a shape that was not offered still names nothing.
     */
    private fun named(lines: List<String>): String? =
        lines.firstOrNull()?.trim()?.substringBefore(':')?.trim()

    /**
     * What the reply said the request is about — a name, nobody, or nothing established.
     *
     * **Checked against the request rather than taken on trust.** A name the model composed rather
     * than copied would be searched for like any other, match nothing, and produce a confident
     * report that nothing is known about something nobody asked about. Compared without regard to
     * case because the model may capitalise a name the request wrote in lower case, which is a
     * reformatting rather than an invention.
     *
     * **A reply with no second line is not the same as one saying none, and reading them alike is
     * the defect this returns three values to prevent.** Saying *none* answers the question: the
     * request names nothing in particular, which for the shape that accepts the inbox is the
     * question rather than a gap. Writing one line leaves the question unanswered, and the model
     * does write one line — the routing call runs at the lowest effort it has, which is where it
     * does the least that satisfies the instruction.
     *
     * **A name that fails either check lands on unread rather than on none, which is the same
     * argument once more.** The model read a subject out of the request and wrote something
     * unusable for it — a name it did not copy, or a sentence explaining itself. That is a
     * question about somebody whose name did not survive, not a question about nobody, and
     * rounding it down to nobody is what hands a shape accepting the inbox a request to answer
     * corpus-wide.
     */
    private fun subject(lines: List<String>, text: String): SubjectName {
        val written = lines.drop(1).firstOrNull { it.isNotBlank() }?.trim()
        // What was written with its non-letter edges trimmed — not its letters, since anything
        // between them survives: `A&B` stays `A&B`. That is what the agreed word is recognised
        // through, and its being empty is exactly the line having no letter anywhere.
        val letters = written?.trim { !it.isLetter() }
        return when {
            // Nothing was written where the subject goes, so nothing was said about it.
            written == null -> SubjectName.Unread
            // The agreed word, which answers the question rather than leaving it.
            //
            // **Read as loosely as the name beside it, and for a reason that only now applies.**
            // Compared byte for byte, every other rendering of the same intent — capitalised,
            // quoted, with a full stop after it — falls through to the copy check, fails it, and
            // is read as a subject that was lost. That used to cost nothing: a sloppy second line
            // and the agreed word both ended at the inbox, so strictness here failed onto the
            // right answer. Now it fails onto a refusal, and the question it refuses is the daily
            // one. So the one comparison whose failure costs an answer is no longer the one left
            // strict while the name above it tolerates recapitalisation.
            //
            // Trimmed on letters rather than on a list of punctuation, which is Unicode-aware and
            // so does not quietly mean *ASCII* over a Russian corpus. Equality after trimming
            // rather than a prefix test, because a prefix would read an organisation called
            // Nonet as nobody.
            letters.equals(NOTHING, ignoreCase = true) -> SubjectName.None
            // **A line with no letters in it names nothing, which is the same rule once more.** A
            // dash, an ellipsis or a bare question mark is a line this cannot use, and the rule
            // above says such a line is a subject not established. Left to fall through it reaches
            // the copy check instead, where a request containing that punctuation — an em dash in
            // Russian chat, a question mark anywhere — passes it, and the reply then reports that
            // nothing was found about a dash.
            letters.isNullOrEmpty() -> SubjectName.Unread
            // A whole sentence is the model explaining itself instead of naming something, and
            // handed to the resolver it is a search that matches nothing.
            written.length > SUBJECT_LIMIT -> SubjectName.Unread
            // Composed rather than copied, which a search would spend a read to miss.
            !text.contains(written, ignoreCase = true) -> SubjectName.Unread
            else -> SubjectName.Given(written)
        }
    }

    /**
     * The candidates, one per line with what each answers, and the text to route.
     *
     * The shapes are interpolated rather than listed in [SYSTEM] because the set is the registry's
     * and changes with it, so the two cannot fall out of step.
     *
     * **It costs no cache saving, because there is none here to lose.** What a breakpoint would be
     * measured against is [SYSTEM] alone — the byte-stable part — and that sits well below the
     * minimum prefix this model will cache at, which is the bulk model's and the larger of the two
     * this build uses. A prefix under it caches nothing and reports nothing either way. Describing
     * each candidate adds a line apiece and does not approach the minimum, so it changes no part of
     * that. **What reports it is both cache counters coming back at nothing**, which is a reading
     * to take rather than a figure to assert here.
     */
    private fun prompt(text: String, among: List<Classifier.Candidate>): String =
        buildString {
            appendLine("Candidates:")
            // Identifier first and the description after it, because line 1 of the reply has to be
            // the identifier exactly: putting the prose first invites a reply that begins with it.
            among.forEach { appendLine("${it.id.value}: ${it.description}") }
            appendLine()
            appendLine("Request:")
            append(text)
        }

    private companion object {

        /**
         * Standing instructions.
         *
         * **It names no shape, and the reason is the registry rather than the cache.** A shape
         * named here would be a second registry able to disagree with the first; byte-stability is
         * not the argument, because nothing on this call is cached either way — see [prompt]. What
         * each shape *answers* is stated beside its identifier there, which is where the set itself
         * is, so the two move together.
         */
        val SYSTEM = """
            You route a request to the pipeline that should answer it, and say what it is about.

            Reply with exactly two lines, and nothing else. No explanation, no formatting, no
            labels.

            Line 1: one of the candidate identifiers. Each candidate is listed with what it
            answers; choose on that description and not on how its identifier reads. If the
            request is not clearly one of them, write: none

            Line 2: the name of the one person, organisation or conversation the request is about,
            copied from the request exactly as it is written there. If the request names no
            particular subject, or names several, write: none

            Write both lines every time. A reply of one line has not said what the request is
            about, and saying nothing about that is not the same as saying there is nobody: where
            the request names no particular subject the second line is the word none, written out.

            Prefer none on both lines over a poor fit. A request routed to the wrong pipeline is
            answered from material that cannot support it, which is worse than being answered
            generally. A name you did not copy from the request is worse still: it will be
            searched for, and the reply will report that nothing is known about a subject nobody
            asked about.
        """.trimIndent()

        /** What the reply writes for either line when there is nothing to put there. */
        const val NOTHING = "none"

        /**
         * The longest a subject name may be.
         *
         * A guard on the shape of the answer rather than on its content: a second line running to
         * a sentence is the model explaining itself instead of naming something, and a whole
         * sentence passed to the resolver is a search that matches nothing. Generous enough for an
         * organisation's full legal name.
         */
        const val SUBJECT_LIMIT = 120

        /**
         * The ceiling for a reply of two short lines.
         *
         * Thinking counts against it, so this is sized for the reasoning rather than the answer.
         * Below roughly this the model spends the budget deciding and returns a truncation, which
         * costs the tokens and classifies nothing.
         */
        const val MAX_TOKENS = 2_048
    }
}
