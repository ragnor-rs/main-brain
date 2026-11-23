# The evaluation set

A demonstration is not a test. This turns the shipped request shapes into fixed inputs with
known-good answers, scored automatically, so that a change to retrieval or prompting cannot
quietly make answers worse.

```
pip install -r eval/requirements.txt
docker compose --profile eval up -d --build backend-eval
MB_E2E_TARGET=dev python3 -m pytest eval/ -v -rs
```

A full `./scripts/deploy.sh --env dev` does all three. There is nothing to run by hand after one.

## Three scores, kept apart

Whether the right records came back is deterministic and assertable. Whether the prose is good is
not. One number covering both would hide a retrieval regression behind a fluent answer, so the run
reports three:

| Score | What it is | How it is judged |
| --- | --- | --- |
| **retrieval** | Did the material a case names come back, and did material from another subject stay out | Set comparison against the trace |
| **citations** | Did every claim rest on something retrieved | Subset test, across every attempt |
| **answers** | Does the prose report what the corpus supports | Figures and organisation names, never wording |

Each is a fraction, reported with the number of cases it was taken over — a mean over four cases
and a mean over forty are not the same evidence. A dimension nothing graded says so rather than
reporting 1.0 for an empty mean. A case opts a dimension out by name —
`scores_retrieval`, `scores_citations`, `scores_answer` — and says why in its own note, so a run
reports what it did not measure instead of a smaller denominator reading as success.

**Routing is asserted rather than scored.** A request either bound to the shape its case names or
it did not, so a fraction would hide one wrong answer behind the ones beside it. It gets its own
check because the failure passes every other dimension: a request routed to the wrong shape
retrieves well — the wrong shape's retrieval works perfectly — cites what it retrieved, and
composes fluent prose answering a question nobody asked. A case whose `shape` is null names no
shape and is asserted the other way round: its subject resolves to a question, so what the check
holds is that nothing answered at all.

## Why it cannot run in CI

Scoring retrieval needs real embeddings and answering needs a model credential. CI runs without
credentials of any kind, and keeping it that way is worth more than the coverage: nothing there
can leak a session file or an API key because none is reachable from it. An evaluation set running
there against a mocked embedding model would measure the mock.

So it runs where the end-to-end suite runs — a dev deploy, and the reviewing pass before it waits
on CI — but **it is asked for rather than automatic**: `./scripts/deploy.sh --env dev --eval`. It is
the only tier that spends the model budget, about four dollars a run against a finite one, and a
deploy happens many times a day. Ask for it when the change could have moved an answer; a deploy
that skipped it says so, in those words, so a run that measured nothing cannot read as a pass.

**A regression blocks the merge by convention rather than by a rail**, because branch protection
needs a paid plan the organisation does not have. That means a red has to be reported and acted
on, never noted and passed over.

## Its own stack, and its own corpus

The `eval` Compose profile adds two services: a store nothing else writes, and a second backend
pointed at it. Neither is part of the serving stack, and both are published on the loopback
address — which is why a host `pytest` can reach `/answer` at all while the serving backend stays
unreachable from outside the Compose network.

The corpus is **invented**, and it has to be: known-good answers are only knowable for material
somebody wrote, and this repository carries no correspondence. Organisations are fictional and
people appear as roles. Bodies are Russian because the corpus this system reads is Russian, and
retrieval over a multilingual embedding model is exactly what could regress unnoticed.

**It is pinned.** The serving corpus mutates daily as ingestion runs, so a suite scored against it
would measure the corpus rather than the code. `eval/fixtures/corpus.json` is the snapshot; it is
rebuilt when that file changes or when it has aged past `EVAL_SNAPSHOT_MAX_AGE_HOURS`, and reused
otherwise. Fixtures are positioned in time relative to the moment they were seeded, because a
shape that reads what is coming filters on whether a date is ahead of now.

Records go in; the derived layer is produced by the stack. A span carries an embedding nobody can
hand-write, and both derived passes are code under test — seeding their output would score the
answer path against a derived layer this build had no part in making. Rebuilding therefore costs
one recognition sweep over the fixtures, which is the only part of this that spends the model
budget. A run that finds the snapshot current spends nothing.

## How it sees retrieval

Through the log. `AnswerResponse` carries the outcome, the corpus freshness, the subjects that
lost, and each claim with the identifiers it rests on — but nothing about what was *retrieved*, or
about which attempt produced any of it. The split is what an interface could ever use: a citation
is something a reader can be taken to, so it goes on the wire; a retrieved set and an attempt
ordinal are facts about how the machinery ran, and putting those in `:contract` would be a shape
only this suite reads.

So the backend writes one line per attempt and this parses it. The marker `answer-trace` and its
six keys are a contract between `LoggedAnswerTrace` and `eval/ask.py`, pinned on the Kotlin side
by `LoggedAnswerTraceTest`. A refactor that renames them breaks a gate that runs nowhere near CI,
which is the cost of this choice and is stated here so it is not discovered later.

## What it does not cover

Named rather than left implicit, because a smaller denominator reported as success is worse than
an admitted gap:

- **`offer`** — offer and price reads the knowledge base, the knowledge base is corporate Drive,
  and Drive arrives with a later workstream. There is no shape to score.
- **`research`'s retrieved set** — it answers from material the corpus never held, so a fixed
  corpus cannot pin what it retrieves. The case asserts that it answers and that nothing it
  claims is ungrounded.
- **Recognition quality** — how often a commitment is invented or missed is a decision waiting on
  this suite, not something it answers yet. What is scored today is retrieval, grounding and the
  answer, over a derived layer the stack produced. **The merge decision is not waiting on this
  suite and should not**: identity makes no model call, so the matcher owns a sample of read pairs
  asserted hermetically on every merge, where this tier would measure it only when somebody bought
  a run. A deterministic property owned by a tier that is asked for is a property nobody owns.
- **The inbox scope** — `status` asked with no subject reads entities across everything, which
  depends on what inference extracted from the fixtures rather than on the fixtures themselves.
- **Whether an answer's sections group the right things** — one case spans two bodies of work and
  one covers a body of work keeping two threads, and both assert only that each
  one's material and label reached the answer, which is as far as a figures-and-names check goes. A
  heading per body of work with the findings beneath it, and two labels naming one body of work
  sharing a heading, are read from the answer a run prints. The wire marks which parts are headings,
  so *how many* there are is countable; whether the right findings sit under each is the judgement,
  which is why the set is what holds it rather than a unit test.

## An unconfigured run

Skips, and names what is missing. A suite that measured nothing must not exit looking like one
that measured everything — so read the skip lines, and treat a skip as *not run* rather than as
*passed*.
