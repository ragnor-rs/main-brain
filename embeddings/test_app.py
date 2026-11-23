"""The sidecar's HTTP surface, with no model present.

Every test here runs without the inference stack installed and without reaching the network,
which is what lets CI gate on them. That is not a compromise: the surface *is* what the
backend depends on, and the one place a model genuinely matters — that the vectors are any
good — is measured by the evaluation set against a live stack, not here.

The prefix tests are the exception worth naming. They exercise real production code rather
than a stand-in, because the rule applied is ours and because its failure is silent: this
model family is trained with a word naming which side of the comparison a text is on, and
vectors embedded behind the wrong word are quietly less comparable rather than refused.

No test can reach a model, and that is structural rather than careful: the inference stack
lives in a requirements file these tests do not install, so a load attempted here fails on
the import. The warming tests replace `model.load` as well, which is what lets them assert
what was passed to it.
"""

from __future__ import annotations

import contextlib
import importlib
import logging
import threading
from collections.abc import Iterator
from types import ModuleType

import pytest
from fastapi.testclient import TestClient

import app as app_module
import model as model_module
from model import SentenceTransformerModel

#: Long enough that a loaded machine does not fail this, short enough that a thread which
#: never runs is reported as a failure rather than as a hung suite.
WAIT_SECONDS = 10

#: A model name of the configured family, used wherever a test needs one that is never loaded.
A_MODEL = "intfloat/multilingual-e5-base"


class FakeEmbedder:
    """Something shaped like a model, written against the protocol and nothing else."""

    def __init__(self) -> None:
        self.seen: list[tuple[list[str], str]] = []

    def embed(self, texts: list[str], kind: str) -> list[tuple[list[float], dict[str, float]]]:
        self.seen.append((texts, kind))
        # Empty term weights, which is the shape a dense-only model returns.
        return [([0.25, 0.5], {}) for _ in texts]


class FakeEncoder:
    """A sentence encoder's `encode`, narrowed to what the wrapper reads.

    It records what it was asked, because what the wrapper *sends* is the thing under test:
    the texts arrive prefixed, and the request to normalise has to be made rather than assumed.
    """

    def __init__(self) -> None:
        self.texts: list[str] = []
        self.options: dict[str, object] = {}

    def encode(self, texts: list[str], **options: object) -> list[list[float]]:
        self.texts = list(texts)
        self.options = dict(options)
        return [[0.1, 0.2] for _ in texts]


@pytest.fixture
def client() -> TestClient:
    # Not entered as a context manager, so no lifespan runs and nothing warms. The tests that
    # want warming ask for it explicitly, below.
    return TestClient(app_module.app)


@contextlib.contextmanager
def configured(monkeypatch: pytest.MonkeyPatch, **env: str) -> Iterator[ModuleType]:
    """The service reloaded under `env`, and put back afterwards.

    Every one of these settings is read once at import, which is deliberate — they decide what
    the index holds, and a service that re-read them per request could change its answer
    halfway through a backfill. A test that wants a different one therefore reloads the module,
    and has to reload it again on the way out or it leaves the setting behind for whatever runs
    next.
    """
    for name, value in env.items():
        monkeypatch.setenv(name, value)
    try:
        yield importlib.reload(app_module)
    finally:
        # A warm thread still running would write its model into the module globals that the
        # reload below is resetting — `importlib.reload` re-executes into the same dict — so a
        # later test could find a stand-in loaded and get 200 where it asserts 503, with the
        # failure pointing at the wrong test. Joined rather than abandoned, and the join is
        # checked.
        warmer = getattr(app_module, "_warmer", None)
        if warmer is not None:
            warmer.join(timeout=WAIT_SECONDS)
            assert not warmer.is_alive(), "a warm thread outlived the test that started it"
        # Undone here rather than left to the fixture's own teardown, because the reload below
        # has to happen under the restored environment. The other way round it reimports the
        # module while the test's settings are still set, and leaves them baked into whatever
        # runs next.
        monkeypatch.undo()
        importlib.reload(app_module)


def test_health_reports_up_but_not_ready_without_a_model(client: TestClient) -> None:
    body = client.get("/health").json()

    assert body["status"] == "ok"
    assert body["ready"] is False
    assert body["model"] is None


def test_embed_refuses_rather_than_inventing_vectors(client: TestClient) -> None:
    # Refusing matters more than it looks: the index is built at one model's dimensions, so a
    # sidecar that fell back to some default would quietly fill the index with vectors from a
    # model nobody chose, and nothing downstream could tell.
    response = client.post("/embed", json={"texts": ["anything"]})

    assert response.status_code == 503


def test_a_configured_model_is_named_long_before_it_is_ready(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Naming a model and holding one are different states, and the gap between them is minutes
    # of download. Reporting ready off the name made a sidecar that was still fetching
    # indistinguishable from one that could answer, and sent the whole wait to whichever pass
    # arrived first.
    with configured(monkeypatch, EMBEDDING_MODEL=A_MODEL) as reloaded:
        body = TestClient(reloaded.app).get("/health").json()

        assert body["ready"] is False
        assert body["model"] == A_MODEL
        assert A_MODEL in body["version"]


def test_warming_loads_the_model_at_startup_with_nothing_asking_it_to(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    loads: list[str] = []

    def fake_load(name: str) -> FakeEmbedder:
        loads.append(name)
        return FakeEmbedder()

    monkeypatch.setattr(model_module, "load", fake_load)
    with configured(monkeypatch, EMBEDDING_MODEL=A_MODEL) as reloaded:
        with TestClient(reloaded.app) as client:
            assert reloaded._warmer is not None
            reloaded._warmer.join(timeout=WAIT_SECONDS)
            assert not reloaded._warmer.is_alive(), "the load never finished"
            body = client.get("/health").json()

        assert loads == [A_MODEL]
        assert body["ready"] is True


def test_health_answers_and_embed_is_refused_while_the_weights_are_still_loading(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # One test for three properties because they are one behaviour: the load is off the request
    # path. Health answers during it, readiness stays false until it finishes, and a pass that
    # arrives early is told to come back instead of being queued behind gigabytes — which is
    # what the caller's ten-minute timeout used to absorb.
    started = threading.Event()
    release = threading.Event()

    def blocking_load(name: str) -> FakeEmbedder:
        started.set()
        assert release.wait(timeout=WAIT_SECONDS), "the test never released the load"
        return FakeEmbedder()

    monkeypatch.setattr(model_module, "load", blocking_load)
    with configured(monkeypatch, EMBEDDING_MODEL=A_MODEL) as reloaded:
        with TestClient(reloaded.app) as client:
            assert started.wait(timeout=WAIT_SECONDS), "warming never began"
            loading = client.get("/health").json()
            refused = client.post("/embed", json={"texts": ["anything"]})
            # Both answered with the loader still sitting in the middle of its load. That is
            # the whole claim, and it is a fact about ordering rather than about a duration —
            # a request that had queued behind the load could not have returned before it.
            assert reloaded._warmer is not None
            still_loading = reloaded._warmer.is_alive()

            release.set()
            reloaded._warmer.join(timeout=WAIT_SECONDS)
            assert not reloaded._warmer.is_alive(), "the load never finished"
            warmed = client.get("/health").json()

        assert loading["status"] == "ok"
        assert loading["model"] == A_MODEL
        assert loading["ready"] is False
        assert refused.status_code == 503
        assert still_loading, "the request waited for the load instead of being refused"
        assert warmed["ready"] is True


def test_the_load_is_announced_at_a_level_that_is_actually_emitted(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    # Both halves matter and only together. The lines are the only report of a load that runs
    # for minutes, and the level has to be one the service has configured a handler for: the
    # server configures loggers of its own and leaves the root one bare, which drops these
    # and makes a cold start look like a hang.
    monkeypatch.setattr(model_module, "load", lambda name: FakeEmbedder())
    with configured(monkeypatch, EMBEDDING_MODEL=A_MODEL) as reloaded:
        # Deliberately not `caplog.set_level`, which would force the level the assertion is
        # about and pass against a module that had configured none.
        assert reloaded.log.isEnabledFor(logging.INFO)
        assert reloaded.log.handlers, "the lines are emitted at a level nothing listens to"

        reloaded.warm()

    announced = " ".join(record.getMessage() for record in caplog.records)
    assert f"Loading {A_MODEL}" in announced
    assert "fetches" in announced
    assert "ready" in announced


def test_a_load_that_fails_leaves_the_sidecar_unready_rather_than_down(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # A bad model name, a missing inference stack, a half-written cache: the service stays up
    # and refuses, because every caller already treats a refusal as come-back-later. Failing
    # the process instead would put the container into a restart loop and lose the log line
    # that says which of those it was.
    def failing_load(name: str) -> FakeEmbedder:
        raise RuntimeError("no such model")

    monkeypatch.setattr(model_module, "load", failing_load)
    with configured(monkeypatch, EMBEDDING_MODEL=A_MODEL) as reloaded:
        with TestClient(reloaded.app) as client:
            assert reloaded._warmer is not None
            reloaded._warmer.join(timeout=WAIT_SECONDS)
            assert not reloaded._warmer.is_alive(), "the load never finished"
            body = client.get("/health").json()
            refused = client.post("/embed", json={"texts": ["anything"]})

        assert body["status"] == "ok"
        assert body["ready"] is False
        assert refused.status_code == 503


def test_the_test_environment_cannot_reach_a_model_at_all() -> None:
    # Warming runs unconditionally, so what keeps a multi-gigabyte fetch out of a job that
    # reaches no network is not care taken by each test — it is that the inference stack lives
    # in a requirements file the tests do not install. This asserts that structural fact, so
    # that adding it to the dev requirements fails here rather than by downloading a model in
    # CI one day.
    with pytest.raises(ModuleNotFoundError):
        model_module.load(A_MODEL)


def test_the_version_carries_the_model_and_the_encoding(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # The derived layer re-derives on a version it has not seen, so a change the string hides
    # is a change nothing re-derives — the one failure a re-run does not heal. The model is
    # not sufficient on its own: the prefix and the normalisation decide every stored vector
    # while the name stays put, so they are named too.
    with configured(monkeypatch, EMBEDDING_MODEL=A_MODEL) as reloaded:
        # Read before the patch below. `importlib.reload` re-executes into the same module
        # object, so `app_module` and `reloaded` are one thing and patching one patches both —
        # comparing against it afterwards would compare a value with itself and pass whatever
        # `version` did.
        shipped = reloaded.version()
        assert A_MODEL in shipped
        assert reloaded.ENCODING in shipped

        monkeypatch.setattr(reloaded, "ENCODING", "something-else")
        assert reloaded.version() != shipped


def test_embed_returns_one_set_of_vectors_per_text(client: TestClient) -> None:
    fake = FakeEmbedder()
    app_module.app.dependency_overrides[app_module.embedder] = lambda: fake
    try:
        body = client.post("/embed", json={"texts": ["one", "two"], "kind": "query"}).json()

        assert len(body["embeddings"]) == 2
        assert body["embeddings"][0]["dense"] == [0.25, 0.5]
        # Empty rather than absent: the store holds a column for it and the fused read falls
        # back to its vector half, so a dense-only model is a value here and not a gap.
        assert body["embeddings"][0]["sparse"] == {}
        assert fake.seen == [(["one", "two"], "query")]
    finally:
        app_module.app.dependency_overrides.clear()


def test_an_empty_request_is_rejected(client: TestClient) -> None:
    # With a model available, so that the refusal is about the request rather than about the
    # service being unconfigured. Without the override this returns 503: the dependency is
    # resolved before the body is validated, so the missing model is reported first.
    app_module.app.dependency_overrides[app_module.embedder] = FakeEmbedder
    try:
        response = client.post("/embed", json={"texts": []})

        assert response.status_code == 422
    finally:
        app_module.app.dependency_overrides.clear()


def test_each_side_of_the_comparison_is_embedded_behind_its_own_word() -> None:
    # The whole of why `kind` reaches the model. Both sides go through one model, and the
    # vectors only compare when each was embedded behind the word its own side is trained
    # with. Nothing downstream can see this: the wrong word still yields a vector of the
    # right width that stores and ranks, just worse.
    encoder = FakeEncoder()
    model = SentenceTransformerModel(encoder)

    model.embed(["what was agreed"], "query")
    asked = encoder.texts

    model.embed(["what was agreed"], "passage")

    assert asked == ["query: what was agreed"]
    assert encoder.texts == ["passage: what was agreed"]


def test_a_side_the_model_does_not_know_is_refused_rather_than_guessed() -> None:
    # Choosing one would be the silent failure above, applied to every vector from then on.
    model = SentenceTransformerModel(FakeEncoder())

    with pytest.raises(ValueError, match="side of the comparison"):
        model.embed(["a text"], "document")


def test_the_side_is_read_whatever_its_case() -> None:
    # The caller sends it lowercased, but the enum it comes from is not, and a round trip
    # through a different serialiser would be the kind of change that lands here.
    encoder = FakeEncoder()

    SentenceTransformerModel(encoder).embed(["a text"], "PASSAGE")

    assert encoder.texts == ["passage: a text"]


def test_vectors_are_normalised_because_that_is_how_the_index_compares_them() -> None:
    # Asked of the encoder rather than measured on the result, because the stand-in returns
    # whatever it likes — the claim is that this code makes the request, which is where it
    # would be dropped.
    encoder = FakeEncoder()

    SentenceTransformerModel(encoder).embed(["a text"], "passage")

    assert encoder.options.get("normalize_embeddings") is True


def test_a_dense_only_model_yields_no_term_weights() -> None:
    model = SentenceTransformerModel(FakeEncoder())

    dense, sparse = model.embed(["a text"], "passage")[0]

    assert dense == [0.1, 0.2]
    assert sparse == {}
