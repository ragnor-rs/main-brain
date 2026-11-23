package io.github.ragnorrs.mainbrain.backend.presentation

import io.github.ragnorrs.mainbrain.contract.ScoredSignal
import io.github.ragnorrs.mainbrain.contract.UnresolvedPairView
import io.github.ragnorrs.mainbrain.domain.entity.UnresolvedPair

/**
 * The domain's reading of a kept pair, as the wire carries it.
 *
 * In presentation because mapping is presentation's work and nowhere else's: a domain type and a
 * wire type that happen to match are a coincidence rather than reuse, which is what keeps the
 * matcher's own types free to change without a deployed reader breaking.
 *
 * The names travel with the identifiers in the order the pair holds them — the store joins each
 * side's contact on its own identifier, so the pairing cannot come apart here.
 */
internal fun UnresolvedPair.toView() = UnresolvedPairView(
    one = pair.one.toString(),
    other = pair.other.toString(),
    score = pair.score,
    signals = pair.signals.map { ScoredSignal(kind = it.kind.name, weight = it.weight) },
    oneName = oneName,
    otherName = otherName,
)
