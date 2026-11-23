"""The embeddings sidecar.

A separate process because the model provider has no embeddings endpoint, so there is nothing
to fold this into — and because it is the only process that holds a model. The backend calls
it to embed corpus spans while indexing and to embed a question while answering; nothing else
calls it at all.

**Both halves of the fused read come out of one pass.** The model emits a dense vector and a
set of term weights together, so the lexical half of retrieval is a product of the same call
rather than a second artifact built from something else.

**The weights are loaded when the service starts, not when the first request arrives.** Cold,
that load is a multi-gigabyte download; putting it on the request path put the whole of it in
front of whichever pass happened to arrive first, and that pass had no way to tell a sidecar
that was about to answer from one that was still fetching. It runs on a thread, so the health
endpoint keeps answering throughout and the container is not restarted for being slow to warm.

Nothing here imports the inference stack at module scope. That is what lets the tests — and
therefore CI — exercise the HTTP surface with no model installed and no network reached.
"""

from __future__ import annotations

import logging
import os
import threading
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel, Field

from model import Embedder

#: This module's log, carrying its own level and its own handler.
#:
#: Both are set here rather than left to the server, which configures loggers of its own and
#: leaves the root one bare — so anything below a warning from this module would be written
#: nowhere. That matters for one line in particular: warming is the only part of this service
#: that can run for minutes, and without a report of it starting and finishing, a cold start
#: cannot be told from a hang.
#:
#: Named rather than root, and attached rather than `basicConfig`, which does nothing at all
#: when something else has already claimed the root logger.
log = logging.getLogger("embeddings")
log.setLevel(logging.INFO)
if not log.handlers:
    _handler = logging.StreamHandler()
    _handler.setFormatter(logging.Formatter("%(levelname)s:     %(name)s: %(message)s"))
    log.addHandler(_handler)

#: Which model to load. Empty means none is configured, and the service says so rather than
#: choosing one for itself.
MODEL = os.environ.get("EMBEDDING_MODEL", "").strip()

#: How this service turns text into a vector, as a word that changes when the vectors would.
#:
#: Not decoration, and not derivable from the model's name: the family is embedded behind an
#: instruction prefix and the result is normalised, and either of those changing would change
#: every stored vector while the name stayed put. The derived layer carries the string this
#: appears in on its marks and compares it for staleness, so a difference it cannot see is a
#: difference nothing re-derives —
#: which is the one failure a re-run does not heal. Change this whenever the encoding changes.
ENCODING = "dense-e5prefix-l2"

_loaded: Embedder | None = None

#: Guards [_loaded]. The load runs on its own thread while requests are being served, so every
#: read of it and the one write to it cross threads.
_lock = threading.Lock()

#: The warming thread, kept so that a test can wait for the load rather than poll for it.
_warmer: threading.Thread | None = None


def warm() -> None:
    """Fetch and load the weights, so the wait lands on the boot rather than on a caller.

    Cold this downloads gigabytes; warm it reads them off a mounted volume. Either way it is
    by far the slowest thing the service does, which is why nothing on the request path waits
    for it — a request arriving while this runs is refused and retried, not queued behind it.

    A failure leaves the sidecar unready rather than dead, which is the state the port is
    specified to handle: `EmbeddingProvider` has a not-ready sidecar raise rather than return,
    so a pass that reaches one too early can come back. Downstream that is indistinguishable
    from a load still in flight, so it is logged loudly here, where the difference is known.

    Nothing here can reach the network in a test environment: the inference stack lives in a
    requirements file the tests do not install, so the import below fails and is logged like
    any other failed load.
    """
    global _loaded
    if not MODEL:
        log.warning("No embedding model is configured, so there is nothing to warm.")
        return
    log.info("Loading %s. On a machine with no cached weights this fetches them first.", MODEL)
    try:
        from model import load

        model = load(MODEL)
    except Exception:
        log.exception(
            "Loading %s failed. The sidecar will stay unready and refuse every embed until it "
            "is restarted; callers will read that as a load still in progress and keep "
            "retrying, so this line is the only place the difference shows.",
            MODEL,
        )
        return
    with _lock:
        _loaded = model
    log.info("Loaded %s. The sidecar is ready.", MODEL)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    """Start warming, and do not wait for it.

    A thread rather than a task: the load is synchronous and holds the processor, so awaiting
    it here would stall the event loop and with it the health endpoint — which is how a
    container gets itself restarted for being slow to come up.
    """
    global _warmer
    _warmer = threading.Thread(target=warm, name="warm", daemon=True)
    _warmer.start()
    yield


app = FastAPI(title="main-brain embeddings", lifespan=lifespan)


def version() -> str:
    """What is doing the embedding, as a string that changes whenever the vectors would.

    Both halves matter. The model decides the vectors; [ENCODING] decides how text reaches it
    and what is done to the result, and a change to either makes two runs disagree. The derived
    layer carries this on its marks and compares it for staleness, and a difference it cannot
    see is a difference nothing re-derives.
    """
    return f"{MODEL}+{ENCODING}"


def loaded() -> Embedder | None:
    """The model if one has finished loading, and nothing if none has."""
    with _lock:
        return _loaded


def embedder() -> Embedder:
    """The loaded model, or a refusal.

    Nothing loads here. Warming owns the load, so a request that arrives before it finishes is
    refused in the time it takes to read a variable rather than queued behind gigabytes. The
    port is specified for that: a refusal is a come-back-later rather than a text that cannot
    be embedded, where waiting would have held a connection open until it timed out.
    """
    model = loaded()
    if model is None:
        raise HTTPException(status_code=503, detail=_unready())
    return model


def _unready() -> str:
    """Why there is nothing to embed with, in the terms the operator needs."""
    if not MODEL:
        return (
            "No embedding model is configured. Set EMBEDDING_MODEL. The sidecar will not "
            "choose one for itself: the index is built at one model's dimensions, so "
            "picking a model is a schema decision rather than a default."
        )
    return (
        f"{MODEL} is not loaded. The weights are fetched and loaded at startup, so this is "
        "either a load still running or one that failed, and the log says which. This is a "
        "come-back-later, not a text that cannot be embedded."
    )


class EmbedRequest(BaseModel):
    """Texts to embed, and which side of the comparison they are on."""

    texts: list[str] = Field(min_length=1)
    kind: str = "passage"


class Vectors(BaseModel):
    """One text's vectors.

    ``sparse`` is keyed by the term's position in the model's vocabulary, holding only the
    terms that carry weight. The keys are strings because JSON object keys are; the reader
    parses them back to integers.
    """

    dense: list[float]
    sparse: dict[str, float]


class EmbedResponse(BaseModel):
    embeddings: list[Vectors]
    version: str


@app.get("/health")
def health() -> dict[str, object]:
    """Up, which model it holds, and whether that model can answer.

    Loads nothing and waits for nothing, so it still answers while the weights are being
    fetched — which is what keeps the container from failing its healthcheck over a cold
    start. Not that it answers as quickly: the load holds the processor in stretches, so a
    reply during one is slower, and the healthcheck's own timeout is what has to cover that.

    `ready` means an embed will be answered, not that a model has been named. The two fields
    together are what a caller reads: no model named is a sidecar nobody configured, named but
    not ready is one still loading them (or one whose load failed, which the log says and this
    cannot), and named and ready is one that will answer.
    """
    return {
        "status": "ok",
        "model": MODEL or None,
        "ready": loaded() is not None,
        "version": version(),
    }


@app.post("/embed", response_model=EmbedResponse)
def embed(request: EmbedRequest, model: Annotated[Embedder, Depends(embedder)]) -> EmbedResponse:
    """Embed every text in order, returning one set of vectors each."""
    vectors = model.embed(request.texts, request.kind)
    return EmbedResponse(
        embeddings=[Vectors(dense=dense, sparse=sparse) for dense, sparse in vectors],
        version=version(),
    )
