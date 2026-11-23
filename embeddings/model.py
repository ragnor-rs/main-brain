"""Loading the model, and the shape the service needs from it.

Kept apart from `app.py` so that the HTTP surface can be imported, exercised and linted with
no inference stack present. Everything in this module that touches one does so inside a
function body: importing this file costs nothing.
"""

from __future__ import annotations

from typing import Protocol

#: One text's vectors: the dense list, and term position to weight.
#:
#: The second half is empty for a dense-only model, which is what this build runs. The shape
#: keeps it because the store holds a column for it and the fused read falls back to its vector
#: half when it is absent — so a model that emits weights is a change here and nowhere else.
Vectors = tuple[list[float], dict[str, float]]


class Embedder(Protocol):
    """What the service needs of a model, and no more.

    A protocol rather than a base class so the tests can supply something that was never
    written against this file, and so that swapping the model behind the sidecar stays a
    question of what it returns rather than what it inherits.
    """

    def embed(self, texts: list[str], kind: str) -> list[Vectors]:
        """Embed every text, in order, one result each."""
        ...


def load(name: str) -> Embedder:
    """Load `name`, returning something that satisfies [Embedder].

    The import is here rather than at module scope because this is the only line in the
    sidecar that requires the inference stack to exist.
    """
    from sentence_transformers import SentenceTransformer

    return SentenceTransformerModel(SentenceTransformer(name, device="cpu"))


class SentenceTransformerModel:
    """A sentence-encoder model, narrowed to what the service returns.

    **The instruction prefix is this class's whole subtlety.** The e5 family is trained with a
    word naming which side of the comparison a text is on, and the vectors are only comparable
    when both sides were embedded with their own. Omitting it, or using one word for both,
    costs accuracy and reports nothing: every call succeeds, every vector stores, and
    retrieval is quietly worse. So the prefix is required rather than defaulted — an unknown
    kind raises instead of guessing, because a guess here is the failure that hides.

    Vectors are normalised because that is what the family is trained for and what the index
    compares by. Doing it here rather than in the query keeps the two from disagreeing.
    """

    #: The word each side of the comparison is embedded behind, as the family defines them.
    PREFIXES = {"query": "query: ", "passage": "passage: "}

    def __init__(self, model: object) -> None:
        self._model = model

    def embed(self, texts: list[str], kind: str) -> list[Vectors]:
        prefix = self.PREFIXES.get(kind.lower())
        if prefix is None:
            raise ValueError(
                f"'{kind}' is not a side of the comparison this model knows. It is trained "
                f"with one of {sorted(self.PREFIXES)} naming the side, and vectors embedded "
                f"behind the wrong word are quietly less comparable rather than refused — so "
                f"this raises instead of choosing one."
            )
        dense = self._model.encode(
            [prefix + text for text in texts],
            normalize_embeddings=True,
            convert_to_numpy=True,
        )
        # No term weights: this model emits none. The store holds an empty vector for them and
        # the fused read drops to its vector half, which is the behaviour both are specified
        # for rather than a degradation to work around.
        return [([float(value) for value in dense[position]], {}) for position in range(len(texts))]
