package io.github.ragnorrs.mainbrain.data

// The one rule that reduces a client's label to the body of work it names, shared by the two
// reads that count them. A file of its own because it is a rule rather than a helper: a second
// copy of it in either read would be the two disagreeing about what one body of work is.

/**
 * The body of work a client's label names, as the leading name it carries.
 *
 * **Two labels built on one name are one body of work, and this is what makes that decidable in a
 * read.** A body of work that keeps a thread per strand of it is several clients — one per named
 * conversation — so a read counting clients counts the strands and concludes that somebody working
 * inside one of them works across several. Reduced to the leading name, those strands count once.
 *
 * **The leading name rather than a shared prefix of characters, because a prefix needs a threshold
 * and a threshold has to be calibrated.** How far two strands of one body of work agree, and how far
 * two unrelated labels do, are both facts about one corpus's naming habits: a number separating them
 * here is a number that goes stale the first time a label is written differently, and nothing would
 * report that it had. A name boundary is the corpus's own unit and needs no calibration.
 *
 * **What this does not decide is the heading.** The grouping read heads a section with a label the
 * corpus carries, never the name this reduces a label to: that name is one no conversation holds
 * and no reader could look up. Joining two labels under one heading is the model's to do from what
 * it is shown, which is where that judgement belongs.
 *
 * **What it does decide is the scope a name resolves to**, which is the one site that reads this as
 * a value rather than as a predicate. The subject search projects it, and where two or more matched
 * clients reduce to one name it reports the body of work they are strands of — keyed on the reduced
 * name, since nothing stores it, and shown under a strand's own label for the reason the heading
 * takes one. A scope is a set of threads to read, so it has to be settled by a rule; a heading is a
 * choice between labels a reader can see, so it is not. Everywhere else this is still a predicate:
 * the counts behind a section's heading and behind a contact's parent compare it and project
 * nothing.
 *
 * **It can still join two bodies of work that share a first name, and that is the cost of being a
 * rule rather than a judgement.** Two unrelated labels built on one leading word read as one body
 * of work here, so somebody writing in both is placed under it rather than under their own name.
 * The direction is the opposite of the one this build errs in elsewhere, and it is accepted for the
 * same reason the leading name is read at all: the alternative counts strands and loses a grouping
 * that the labels plainly state. A corpus whose labels begin with a generic word — a kind of work
 * rather than whose it is — would want the narrowing pass instead, which judges what a label means.
 *
 * **The delimiters are enumerated, and an unlisted one keeps labels apart.** A character this does
 * not name leaves more of the label in the key, so two labels stay two bodies of work. Matching
 * alphanumerics instead would be shorter and would fail the other way on a collation that does not
 * count a Cyrillic letter as one.
 *
 * Null for a label that is nothing but delimiters, so that it counts as no body of work rather than
 * as one more — the same answer a conversation carrying no label at all gives.
 */
internal fun bodyOfWork(label: String): String =
    "nullif(split_part(btrim(regexp_replace($label, '$DELIMITERS', ' ', 'g')), ' ', 1), '')"

/**
 * What separates a label's leading name from the rest of it.
 *
 * The dash leads the class so that it is a member and not a range, which is also why no escape
 * appears here: escaped inside a bracket expression it would read as a backslash to match as well.
 */
private const val DELIMITERS = "[-[:space:]().,:;/|_]+"
