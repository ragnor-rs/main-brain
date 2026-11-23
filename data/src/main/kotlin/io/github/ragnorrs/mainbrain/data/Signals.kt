package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.Signal
import io.github.ragnorrs.mainbrain.domain.entity.SignalKind

// How the evidence behind a merge is written down and read back. Stored as text rather than as
// rows because nothing queries it: the signals exist so that a human asking why two accounts were
// joined gets an answer, and so that the answer is still available when the weights have moved on.

private const val SEPARATOR = ':'

/** `KIND:weight` per signal, in the order they were scored. */
internal fun List<Signal>.wire(): List<String> = map { "${it.kind.name}$SEPARATOR${it.weight}" }

/**
 * The signals this stored text denotes.
 *
 * Strict: a value that cannot be read throws rather than being dropped. Skipping it would mean a
 * merge quietly reporting less evidence than it was made on, which is the one thing someone
 * deciding whether to undo it must not be shown. A row that cannot be parsed is a defect in
 * whatever wrote it, and this is where it becomes visible.
 */
internal fun List<String>.toSignals(): List<Signal> = map { entry ->
    val kind = entry.substringBefore(SEPARATOR)
    val weight = entry.substringAfter(SEPARATOR, missingDelimiterValue = "")
    Signal(
        kind = requireNotNull(SignalKind.entries.firstOrNull { it.name == kind }) {
            "A stored signal names `$kind`, which is not a signal this build knows. A kind that " +
                "was removed has to be migrated, not read back as nothing."
        },
        weight = requireNotNull(weight.toDoubleOrNull()) {
            "A stored signal carries `$weight` as its weight, which is not a number."
        },
    )
}
