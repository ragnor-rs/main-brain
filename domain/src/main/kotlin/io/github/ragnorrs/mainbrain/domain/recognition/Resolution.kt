package io.github.ragnorrs.mainbrain.domain.recognition

import io.github.ragnorrs.mainbrain.domain.entity.Evidence
import io.github.ragnorrs.mainbrain.domain.entity.Origin
import io.github.ragnorrs.mainbrain.domain.entity.Statement
import io.github.ragnorrs.mainbrain.domain.entity.Validity
import io.github.ragnorrs.mainbrain.domain.entity.ValueSynonym

/**
 * Many readings of one fact, resolved into the windows over which each of them held.
 *
 * The resolution stage, and the only part of recognition that decides what contradicts what. It is
 * a pure function over statements: no port, no model, no clock. Everything it needs is in the
 * evidence, which is what makes the rule below checkable rather than a matter of trust.
 *
 * **Conflicts resolve by the source time of the evidence, never by when the system extracted it.**
 * A backfill reads 2021 after it read yesterday, so extraction order carries no meaning — order by
 * it and backfilling old history silently overwrites current facts with stale ones.
 *
 * Three things happen, and they are one walk through the readings in source-time order.
 *
 * **A changed value closes the window before it.** Same subject, same predicate, a different
 * value: the earlier window ends exactly where the later one begins. Two statements, adjacent, and
 * nothing deleted — "it was true until June" and "it was never true" are different answers.
 *
 * **The same value restated attaches as evidence rather than opening a window.** Two spans saying
 * the same thing is corroboration, not a second fact, and this is where that collapses.
 *
 * **A value that holds, is contradicted, and holds again gets a second window.** It is the same
 * value and not the same period, which is why a window's start is part of a statement's key.
 *
 * Readings sharing an instant are the one case with no adjacency to find: two sources stating
 * different things at the same moment cannot close each other, so both windows stay open and
 * preferring between them is the answer path's to do, with both in view.
 *
 * **Idempotent, and that is the property to keep.** Re-resolving its own output returns it
 * unchanged, because every window is recomputed from the evidence rather than read off the input.
 * So a pass that re-derives a window writes what is already stored — provided it was given every
 * statement about the subject. Where it was not, the store is the backstop: a close it has
 * recorded is never reopened.
 *
 * What this does not do is weigh a correction against a re-reading. The rule is that a correction
 * wins and a conflicting proposal is recorded as a visible disagreement, which needs an interface
 * through which a person can make one; nothing writes a [Origin.CONFIRMED] statement yet, and a
 * branch for it here would be a rule nothing could reach.
 */
fun resolved(statements: List<Statement>, synonyms: List<ValueSynonym> = emptyList()): List<Statement> =
    statements.groupBy { it.subject to it.predicate }.values.flatMap { about ->
        windowsOf(canonicalised(about, synonyms))
    }

/**
 * [about] with every value that is known to mean the same thing written the same way.
 *
 * **Restatement is why this exists.** A claim is rewritten to stand on its own, so a re-derivation
 * under a moved prompt version words one fact differently on the second reading — and the run
 * below, which compares values for equality, then sees a changed value and closes the first window
 * where the second opens. That is worse than a duplicate: the two readings are not two rows, they
 * are a fact recorded as having changed when nothing changed but the wording.
 *
 * **What counts as the same value is decided elsewhere and bought.** Two values of one relation are
 * usually a genuine contradiction — a rate quoted in March and revised in June is exactly what a
 * validity window is for — and two short values close in wording are the pair least safely told
 * apart by a comparison of text or vectors. So nothing here infers a synonym: [synonyms] holds only
 * what the model was asked and answered, and an unrecorded pair is two values.
 *
 * **The earliest wording wins**, which is the same rule a merged finding follows: the surviving
 * value is the one whose earliest evidence is earliest, so the fact keeps the words it was first
 * stated in. A tie goes to the lower value, so that two runs over one corpus agree.
 *
 * Synonymy is treated as transitive, by taking the whole group of values a pair reaches. Three
 * wordings of one fact are one value even where only two pairs of them were ever adjudicated, which
 * is what keeps a third reading from reopening a fact the first two had settled.
 */
private fun canonicalised(about: List<Statement>, synonyms: List<ValueSynonym>): List<Statement> {
    val first = about.first()
    val relevant = synonyms.filter { it.subject == first.subject && it.predicate == first.predicate }
    if (relevant.isEmpty()) return about

    // Which values are known to be one, as groups. Built by walking the pairs and merging the
    // groups each one touches, so a chain of pairs comes out as one group however they arrived.
    val groups = mutableListOf<MutableSet<String>>()
    relevant.forEach { synonym ->
        val touching = groups.filter { synonym.one in it || synonym.other in it }
        val merged = mutableSetOf(synonym.one, synonym.other)
        touching.forEach { merged += it }
        groups -= touching.toSet()
        groups += merged
    }

    // The wording each group is written under: the earliest stated of them, by the earliest span
    // that states it. A value absent from every group is its own canonical and is left alone.
    val earliest = about.groupBy { it.value }
        .mapValues { (_, readings) -> readings.minOf { reading -> reading.evidence.minOf { it.sourceTime } } }
    val canonical = groups.flatMap { group ->
        val keeper = group
            .filter { it in earliest }
            .minWithOrNull(compareBy({ earliest.getValue(it) }, { it }))
            ?: group.min()
        group.map { it to keeper }
    }.toMap()

    return about.map { statement ->
        canonical[statement.value]?.takeIf { it != statement.value }
            ?.let { statement.copy(value = it) }
            ?: statement
    }
}

/**
 * One subject and predicate's statements, re-windowed.
 *
 * [about] is every reading of that pair — stored and fresh together. Handed only the fresh ones,
 * this would close a window against a statement it could not see and produce two claims covering
 * one instant.
 */
private fun windowsOf(about: List<Statement>): List<Statement> {
    val first = about.first()
    val readings = about
        .flatMap { statement -> statement.evidence.map { Reading(statement.value, it, statement.origin) } }
        // Source time first, then the span's own address. The address breaks a tie between two
        // readings of one instant, and it has to break it the same way on every run: an ordering
        // that depended on the order the store returned rows would re-window the corpus for
        // nothing, and could put a value's two periods either way round.
        .distinct()
        .sortedWith(
            compareBy(
                { it.evidence.sourceTime },
                { it.evidence.span.record.nativeId },
                { it.evidence.span.ordinal },
            ),
        )

    val runs = mutableListOf<MutableList<Reading>>()
    readings.forEach { reading ->
        val current = runs.lastOrNull()
        if (current != null && current.first().value == reading.value) {
            current += reading
        } else {
            runs += mutableListOf(reading)
        }
    }

    return runs.mapIndexed { index, run ->
        val from = run.first().evidence.sourceTime
        Statement(
            subject = first.subject,
            predicate = first.predicate,
            value = run.first().value,
            // The first later window that begins strictly after this one. Strictly, because a
            // window cannot close where it opened: readings sharing an instant are a
            // disagreement to keep rather than an adjacency to record.
            validity = Validity(
                from = from,
                until = runs.drop(index + 1).map { it.first().evidence.sourceTime }.firstOrNull { it > from },
            ),
            // The strongest origin any reading in the window carried. A fact read by a model and
            // then also found in a source's own structure is the stronger of the two, and
            // averaging or taking the last would let the weaker one decide how it is phrased.
            origin = run.maxOf { it.origin },
            evidence = run.map { it.evidence }.toSet(),
        )
    }
}

/** One reading of a fact: what was said, where it is said, and how it was arrived at. */
private data class Reading(val value: String, val evidence: Evidence, val origin: Origin)
