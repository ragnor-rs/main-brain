# Process

How work is tracked, built, reviewed and shipped. The architecture it applies to
is in [`architecture.md`](architecture.md).

The process is deliberately heavier than a one-week prototype needs, for one
reason: the standing constraint on this project is that it must not depend on a
single undocumented developer (see the risk register in
[`decisions.md`](decisions.md)). Issues, pull requests, review and CI are what make
the work legible to someone who did not write it.

## Work tracking

**All work is GitHub issues.** No task lists in documents — a list in a document
goes stale the moment work starts.

| | |
|---|---|
| Issues | `github.com/ragnor-rs/main-brain/issues` |
| Board | [Main Brain](https://github.com/users/ragnor-rs/projects/2) — Kanban: `Todo → In Progress → Merged → Done` |
| Labels | Component: `ingest`, `backend`, `bot`, `embeddings`, `postgres`, `infra`, `docs` — one per container, plus the two that are not containers. Process: `needs info`, `deferred`, `priority:top` |
| Types | `Task`, `Bug`, `Feature` |
| Workstream | A GitHub **milestone** mirroring the scope table in [`decisions.md`](decisions.md): `Workstream 0`, `Workstream I`, `Workstream II`, `Workstream III`. A workstream is not a label — **exactly one applies**, and the platform enforces the ceiling. **Every issue carries one**, work that serves the build included: a skill, a CI workflow, the module graph or this document carries the workstream whose process it serves — the one in flight for work on the current build, and a later one for that workstream's own machinery, as the remote deploy path is. The field answers two questions: which increment an issue belongs to, and — for a card that waits on a deploy — which deploy finishes it, since the sweep selects on it and a card without one is finished by no deploy at all. Because process work is counted beside increment work, the progress bar says how much of a workstream's own backlog is left rather than how close the increment is to being shown |

**`Done` means nothing further is required for the change to be doing its job.**

**The test is what the change is for, not which files it touches.** A change that
serves users is doing its job once it is running where its workstream runs, so its card
waits in `Merged` until a deploy to that environment. A change that serves the build —
documentation, a skill, a CI workflow, the module graph, an architecture rule, a deploy
script — is doing its job the moment it is on `main`, because that is where CI and the
issue loop read it from. Its card belongs in `Done`, and the bookkeeping pass is what puts it
there.

**Which environment that is follows the workstream.** Workstream 0 runs on the dev
stack and nowhere else — one personal archive on the developer's machine — so a
stack-wide dev deploy is what puts a Workstream 0 change where it does its job, and
its card is `Done` once that deploy succeeds. Every later workstream runs on the office
machine, so its cards wait for production.

File paths are a decent first approximation of that and a bad rule on their own. A
scaffold is the case that breaks them: it ships Gradle modules, images and a Compose
file, so by path it looks deployable, but deploying it would start processes that do
nothing. Nothing is waiting on a deploy, so nothing is left.

⚠️ **The question to ask is what a deploy to that card's environment would change.** If
the answer is "nothing anyone could observe", the card is `Done` on merge. A migration, a
service with behaviour, an image whose contents changed — all answer otherwise and wait.

⚠️ **A card that waits for production cannot leave `Merged` yet.** `scripts/deploy.sh`
refuses `prod` and the remote path is workstream I, so a later workstream's card stays
where it is until that path exists. That is a queue rather than a stall: a sweep takes
every card of its workstream at once, so the backlog drains in one deploy whenever that
workstream runs.

A stack-wide deploy syncs the tree and rebuilds everything, so everything merged since
the last one goes live together. The move out of `Merged` is therefore a sweep rather
than a per-card judgement, over the cards whose workstream that environment serves. A
per-service deploy sweeps nothing — rebuilding the bot says nothing about a merged
change to the backend.

**The built-in workflow that fires when an item closes points at `Merged`.** It is what
moves a card, since every pull request carries `Closes #<n>`, and it points there
because the platform cannot read a diff and so cannot tell the two end states apart —
landing every close on `Merged` at least never claims something is live when it is not.
GitHub exposes project workflows for reading only, with no update mutation, so the
pointing is a web-interface setting and nothing in the repository can assert it; the
check that it holds is where a card lands when a pull request merges.

**Ordering is enforced by GitHub-native issue dependencies (`blocked_by`), not by
prose.** An issue with an open blocker cannot be started. This is what keeps the
build order — contract, then store, then retrieval, then frontend — from being
re-litigated every morning.

An issue is ready to start when it states the outcome, the acceptance check, and
which seam it touches. An issue that says only "add search" is not ready.

## The loop

**The loop's roles are the shared toolkit's, and what they read about this project is
`.claude/mjolnir.yaml`** — the board and its columns, the labels carrying type and priority, the
milestone carrying the workstream, the readiness gate, what this project counts as done, the
caps, the issue templates and the guards, each stated there once and read rather than copied. A
step restated here would be a second copy of it. The roles are invoked namespaced, and
`/deploy` is the one that is this project's own, under `.claude/skills/deploy/`, because the
toolkit carries none.

⚠️ **Neither the configuration nor the toolkit is committed**, and that is the one thing this
section cannot make reproducible: the file is git-ignored for the reason `.gitignore` states
beside it, and the toolkit is installed per machine. So a clone carries the process in this
document and none of the machinery that holds it, and a session on a machine without both
holds nothing — which reads exactly like a session where the rules do not apply.

**What a rule costs if it is only written down, it is not written down.** The rules that
protect the shared machine, the spend and the merge are hooks rather than prose, and all but
one of them are the toolkit's, reading the facets above. The exception is this project's own,
wired from a committed `.claude/settings.json`: the stack a merged tree holds, which the
toolkit's own sweep does not reach because it reaches trees rather than containers.

| Rule | What happens |
|---|---|
| At most two Gradle builds | the call is **refused** while every place is held; what unblocks it is a place coming back — a holder finishing, or its lease expiring |
| At most one project Compose stack | same cap, same refusal |
| A merge of any pull request | refused until the change itself carries a recorded answer to whether a person is going to read the diff |
| A gate CI has already run green on this commit | declined locally, in its canonical form only, with `MB_LOCAL_GATE` as the one escape |
| A command that spends | refused outright: the deploy, and the evaluation set |
| After a merge | that tree's stack comes down, containers only — unless it is still up, Docker cannot be reached, or the session is standing in that tree, each of which is reported instead |
| At every session start | a tree whose branch has landed is reaped, which is the shared sweep and reaches trees rather than containers |

**A place on a cap is held by a lease rather than by a count of what is running**, so a build
or a stack started outside a guarded call is in neither count, and a lease outliving the call
that took it pins the cap until it expires. Refusal rather than a queue is what the lease buys
in exchange: there is no slot to wait for when the count cannot see everything that holds one.

**The merge question is answered on the change rather than in the session**, which is what
makes an answer outlive the window that gave it: the guard reads a recorded answer on the pull
request, and refuses the merge where there is none, where the change cannot be resolved to a
number, or where the host could not be asked — a merge is not undone, so an unread record is
not an empty one. **And the merge is read back afterwards**, because both outcomes are silent:
the command can merge while printing nothing, and a refusal prints nothing either, so the
state comes from the pull request or from the remote refs and never from the absence of
output.

```
/mjolnir:file-work-item → /mjolnir:start N → work → /mjolnir:finish → /mjolnir:review → merge
                                             └─ /mjolnir:save at the end of a session ─┘
```

**Starting takes an item named on the invocation, or the next one this board's own order makes
available** — `scripts/board-next.sh` is what states that order here: the starting column,
demonstration-visible first. Finishing and reviewing fall back to the branch when given no
argument, and a session that opens on work somebody already started reads where it stands with
`/mjolnir:load` rather than inferring it.

`/mjolnir:save` ends a working session: it brings the board current — including the cards a
merge already finished — and commits the branch HEAD is on. It never opens or merges a pull
request and never decides anything; anything that is a judgement call gets reported instead.
Releasing a merged branch's stack is not among its steps, because the hook does it at the
merge; what a save owes is to name a stack that is still up and why. `/deploy` is separate,
and refuses any target whose path is not implemented rather than improvising one.

The loop runs per checkout, so several working trees can run it at once: each cuts its
branch from `origin/main` rather than holding `main`, and each names its own scratch
files. What stays shared is shared deliberately. The board is one board, and neither sweep
is confined to the cards of the checkout it runs from: a save takes the `Merged` cards its
own diff finished, a deploy takes those of the workstream it deployed for, and both reach
other people's work. Production is one machine, and a deploy asks for
approval before it touches it. Local `main` is nobody's job: no step of the loop moves
it, so a checkout that wants it current pulls it by hand.

⚠️ **A dev deploy writes `Done` to that shared board, so only the primary checkout sweeps.**
The dev stack is per checkout — see below — so a deploy in a worktree brings up a Compose
project of its own, and that stack is nobody's afterwards. Retiring a merged worktree takes
its containers down first and then removes the directory, so what outlives it is the volume
holding that stack's store, which `docker volume rm` is for once the data is not wanted.
A card marked `Done` would then point at a stack that happens to exist rather than at
the machine's dev stack. The primary checkout is the repository root — the one a worktree's
`.claude/worktrees/` path sits under — and a deploy from anywhere else reports what it would
have swept instead of sweeping it.

**The dev stack is per checkout, with two exceptions.**
`docker-compose.yml` sets no project name, so Compose derives one from the directory and
each worktree gets its own containers, database and — bar the one volume below — volumes.
Both sides find the same project without being told — `scripts/deploy.sh` and
`e2e/config.py` each resolve the root from their own location — so a deploy and the suite
that checks it are always the same tree.

The embedding weights are the first exception: one cache for the machine, declared
`external` in Compose under a fixed name and created by `scripts/deploy.sh`, since Compose
will not create what it does not own. They are gigabytes; the sidecar fetches them once and
then only reads them, and which bytes it fetches follows from the model setting alone. So a
copy per worktree costs disk and buys no isolation. Being external, the cache also outlives
`docker compose down -v` in any one worktree: tearing a dev stack down is bounded by that
stack.

The account is the second. The end-to-end suite plants real messages into one real
Telegram account, and there is only one. Two sessions running the suite at the same time
read each other's messages as their own, so that step is not safe to run concurrently:
check before starting.

### What this project adds to the roles

The steps are the roles' own. What follows is this build's, and is not derivable from the
configuration they read:

- **Ground a request in `docs/` before filing it.** Every issue traces to a requirement or a
  named gap, and a request contradicting a recorded decision gets raised rather than quietly
  filed. Findings, ideas and proposals are not work that appeared; they reach the board once
  somebody decides to act on them.
- **A prerequisite is a `blocked_by` edge and never a text mention**, because the edge is what
  the readiness gate reads and prose is what it cannot.
- **Which end state a change earns is decided where its diff and its intent are both in
  view**, which is the finishing pass: if a deploy to the environment its workstream runs on
  would change nothing anyone could observe, the card is `Done` on merge; otherwise it waits
  for that deploy. See "Work tracking" above.
- **A test plan item has to be mechanically checkable**, and a box is ticked once its check has
  passed. For a documentation change that means greps and scripts against the actual files; a
  check worth keeping is added to `scripts/check-docs.sh` rather than left in a plan that is
  thrown away with the pull request.
- **The two tiers CI cannot run are the reviewing pass's**, and they run even where the review
  found nothing: a clean review says nothing about whether the suites pass. The report names
  which tiers actually ran.
- **Where a finding has more than one reasonable reading, raise it** rather than silently
  picking one.

### `/deploy`

Two environments: **dev** on the developer's machine and **production** on the office
machine. Only dev is implemented; `scripts/deploy.sh` refuses production by name rather
than improvising an ssh sequence against a host that holds a live Telegram session. The
remote path is workstream I, so this build runs on dev throughout, the demonstration
included.

A dev deploy builds the fat jars, builds the images, brings the stack up and — when it is
a full deploy — waits for the index pass to catch up and runs the end-to-end suite, with the
evaluation set behind `--eval`. A remote
deploy will instead build the jars
locally, sync the tree and jars to the host, and `docker compose build && up` there, so
the host needs no JDK. Migrations run on backend startup.

**Guardrail: a deploy to production replaces live containers.** Never run
it without explicit approval for that specific deploy. A dev deploy needs no approval but
does need capacity: it brings up a stack and builds the jars, both machine-wide, so it
**queues for a slot** when the caps below are full. A stack-wide one writes `Done` to the shared board, so what it sweeps is bounded
by the conditions `/deploy` holds — this section's clean-tree requirement among them, and the
primary-checkout one above — rather than by how often it runs.

A successful stack-wide deploy then sweeps the `Merged` cards of the workstream that
environment serves — dev finishes Workstream 0, production finishes the rest — and
nothing else finishes a card that waits on a deploy. The sweep requires HEAD to have
been `origin/main` with a clean tree, because neither environment ships a commit: dev
builds the images from the working tree, and the remote path rsyncs it. From a feature
branch, a dirty tree, or a `main` left behind by a merge nobody has pulled, what is live
is not what is merged, and the board would be asserting something untrue. A
single-service deploy sweeps nothing, and neither does a deploy that left a card's own
workstream untouched.

## Branches, commits, and merges

- `main` is always green and always deployable.
- **Everything goes through a pull request. Never push to `main` directly.** The
  platform cannot enforce this yet (see "Not yet decided"), which makes it a rule
  to keep rather than a rail to lean on — the PR is what leaves a reviewable record
  of why a change was made, and that record is the point.
- One issue, one branch, one PR. `feature/<n>-<slug>` or `bugfix/<n>-<slug>`, where
  `<n>` is the issue number and `<slug>` is its title lowercased with non-alphanumeric
  runs collapsed to `-`, trimmed to about fifty characters. `bugfix` when the issue's type is
  **`type:bug`**, `feature` for `type:feature` and `type:task` alike. The type is one of three
  `type:`-prefixed labels, exactly one per issue: a repository owned by a user account has no
  issue-type field, since types are defined per organisation, so the prefix convention carries
  it. An issue with no type label is unclassified, and starting stops on it rather than
  guessing a prefix.
  This is the canonical form: the starting pass derives the branch name from it, and the
  finishing and reviewing passes read the issue number back out of it whenever they are given
  no argument. A hand-made name silently misroutes them.
- Commit messages: a short imperative subject, then why rather than what. The diff
  already says what.
- Squash on merge; the PR is the unit of history.
- Never force-push a branch someone else may have pulled.

## Definition of done

An issue is done when all of these hold:

- [ ] Every item in the PR's test plan executed and ticked
- [ ] `./gradlew detekt test` green — this covers ingestion, and it covers the
      architecture rules; `ruff check embeddings/ e2e/ eval/ .claude/hooks/ scripts/` and
      `pytest embeddings/` green
- [ ] `shellcheck --severity=warning scripts/*.sh .claude/hooks/*.sh` and `bash -n` green on
      each of them — a merge gate, so a shell change that skips it is caught in CI rather
      than here. The `*.sh` of both trees gate something: one a deploy, the other every
      Bash call the harness makes. The Python beside them is `ruff`'s, in the line above
- [ ] `./scripts/check-docs.sh` green — and read any SKIP line, which is an assertion that
      did not run rather than one that passed
- [ ] New behaviour covered by a test at the right layer (see the testing table in
      [`architecture.md`](architecture.md)) — store code against real Postgres, not a mock
- [ ] New behaviour has an end-to-end case, and a **dev** deploy ran the suite green.
      Only the readers its cases assert through run in CI, so nothing else will catch
      the absence of the case itself
- [ ] No new secret, session file, or build artefact in the diff
- [ ] Layering intact. The mechanical part needs no reading: the module graph is a
      compile error, with its arrows under "Layering rules" in
      [`architecture.md`](architecture.md), and `:archtest` asserts what the graph cannot
      say, listed in that document's testing table. `./gradlew test` is the check for both.
      What neither can reach has to be read for — a port carrying a method only one of
      its callers uses, a multi-statement write that is two transactions, every message
      key resolving in Russian, and a handler reaching the catalogue rather than a
      literal, which the character rule cannot see in a locale it does not read. Two more
      belong on that list because the capability travels without the name: a re-read
      handed to the scheduler as a function type, or carried on an adapter's concrete
      type, which no source rule can see for the reason `internal` visibility exists on
      the store seam; and automation against the professional network, of which a browser
      driver is only the form a package list can catch. A third is a repository function
      whose return type is inferred rather than declared — the rule that every one of them
      suspends or hands back a flow reads the declaration, and an expression body gives it
      nothing to read
- [ ] If it changes retrieval or answering: the evaluation set still passes, run locally —
      it is the only thing that detects a retrieval regression, and CI cannot run it
- [ ] Documentation updated where the change makes an existing statement wrong
- [ ] PR body contains `Closes #<n>`

That list is what a merge requires; the board's `Done` additionally waits on a deploy.

## Quality gates

Gates in order of how early they catch things. Everything hermetic runs in CI; what
needs the real world runs on the dev stack.

| Gate | Runs | CI | Dev |
|---|---|---|---|
| Compile | the module graph is a compile error; `test` compiles everything | yes | yes |
| Static analysis | `./gradlew detekt`, `ruff check` over the paths `.github/workflows/ci.yml` names, `shellcheck` over the `*.sh` of `scripts/` and `.claude/hooks/` | yes | yes |
| Documents | `./scripts/check-docs.sh` — properties the documents must hold | yes | yes |
| Architecture | `./gradlew test` — the rules a module graph cannot express | yes | yes |
| Tests | `./gradlew test`, `pytest embeddings/` — store code against real Postgres | yes | yes |
| End-to-end | `pytest e2e/` — a real Telegram account against a running stack; the cases declaring no marker need neither and run in CI too | partly | yes |
| Evaluation set | `pytest eval/` — retrieval scored against fixed cases | no | yes |
| Review | every PR, before merge | — | — |

**Where CI has already answered for the commit in hand, that answer is the gate.** The static
analysis, documents, architecture and test rows run the same commands in both columns, so
re-running one locally on a commit CI has gone green on buys no information and costs a shared
machine a build or a test run. This is a rail rather than a habit: the guard declines such a
run, and it declines **the canonical form of each gate and nothing else** — the command as the
workflow runs it, which is the command `host.checks` in `.claude/mjolnir.yaml` names it by,
with every line of the call being one of them. The rows below name what each gate covers
rather than how it is spelled, and a variation is not the same work: CI lints exactly the path
list the workflow gives and nothing else, runs `ruff check` and never `ruff format`, and runs
`pytest` over
`embeddings/` and over the end-to-end cases that declare no marker — not over either
tree whole. **The path list is read from the workflow and never from here**, because a second
copy of it is a copy that goes stale while reading as current.

The dev column is what runs when CI has *not* answered, which is more often than it looks —

- the tree is dirty, so what would be checked was never pushed;
- no run exists for this commit, because the workflow triggers on a pull request and on
  pushes to the default branch only, so a branch with no pull request gets none — and a
  pull request with conflicts gets none either, reporting no checks rather than a failure;
- the run **started no job at all**. A suspended account fails every job in seconds having
  executed no steps, which presents as a full set of red checks and is not a verdict on the
  code. The number of steps a job executed is what separates the two, and nothing on the
  surface of the check does;
- the run is genuinely red, which is exactly when reproducing it locally is the point;
- the compile row, which a dev deploy repeats whatever CI said, because it builds the images
  from the working tree rather than from a commit.

A skipped gate is reported as skipped and never as passed, in either direction — "CI green on
this commit, so the mirrored jobs were not re-run" is an answer; silence about them is not.

The two local tiers are not alike, and the difference is money. The **end-to-end suite** is
mechanical: a **full dev** deploy waits for the index pass to catch up and then runs it, with no
judgement about whether this change needed it — and when the index has not caught up it runs
everything but the tests that need material to answer from. The **evaluation set** is **asked for** — `--eval` on a deploy —
because it is by far the larger spend, roughly four dollars a run against a
budget that is finite and has been exhausted once already. A deploy happens many times a day
and most changes could not have moved an answer, so paying on every one of them buys nothing
and costs the budget that the runs which matter depend on.

**The end-to-end suite spends too, which is why what it asks is bounded.** Every case that puts
a question to the stack is a model call: one for the free-text question, and one for each shape
a command selects — the drafting one composing two or three alternatives in its single call.
Those shape cases are the only checks on what a reader is actually shown, and the ones asked about
somebody run only when a subject to ask about is configured, so leaving that unset is what keeps a
deploy's cost down. It is a fraction of the evaluation set either way, and unlike it the suite is
mechanical: nothing here is a judgement about whether this change needed it.

**What a deploy's floor is instead is one case that no setting gates, and it is the dearest call
the suite makes.** The shape that researches the open web accepts no subject, so a configured name
cannot gate it and nothing else about the deploy can either — it needs no indexed corpus to answer,
because its material is not the corpus. What bounds it is the question, which is fixed and narrow
in the case itself rather than open-ended. It runs at the highest effort and the largest ceiling
this build asks for, with the provider searching and reading on its own side, so it is slow as well
as dear: how long it takes varies several-fold between runs of the same question, which is why the
suite's window for it is derived from the interface's bound rather than from an observation. So a
deploy with no subject configured pays the most and reads the least.

The trade is what that case covers. The shape is answered over HTTP and scored by the evaluation
set, and neither of those reads the rendered message or waits under the interface's bound — the
evaluation set waits on the same fifteen minutes the interface does, which is where that figure was
derived, and the HTTP route waits on whatever its caller allows.

**Asking for it is the judgement; reporting that it did not run is not.** A deploy without
`--eval` names the tier it skipped and what that leaves unmeasured, so a run that scored
nothing never reads like one that scored everything. Pass `--eval` when the change could have
moved retrieval or answering — the answer path, the shapes, retrieval, the prompts, the
embedding model, the derived passes that feed them.

Two deploys run neither suite, for different reasons. A **selective** dev deploy (`--bot`,
`--backend`, …) starts only what was asked, so the stack check — which asserts all five
services — would fail work that did exactly what it should; it says so rather than running
them. A **production** deploy is refused them outright, because both suites plant real
messages into a real Telegram account.

**A cold index drops the tests that need material, and keeps the rest.** A stack can be up and able to embed
while nothing has been embedded yet — a new Compose project, a restored archive, a stack that
ingested and was then stopped. Asked a question then, retrieval returns nothing and the reply is
the one every failure on the answer path renders as, so the suite reports the answer path broken
for a stack that had not finished starting. So a full dev deploy first waits for the index pass
to catch up, asking the pass's own definition of owed — no mark, a mark at another embedding
version, a mark older than the record it covers — rather than counting marks, because a mark
says a record was looked at and keeps whatever version wrote it. When the pass has not caught up
in time, the deploy deselects the tests that need material and runs the others, so the checks
that say the stack came up at all still fail when it did not. Those tests are selected by the
marker each carries rather than by a list of their names, so one added later is covered by the
rule instead of going red for a reason that is about the deploy's timing. The wait is narrower
than the evaluation harness's, which also waits on recognition because its cases assert on bodies
of work: inference is off by default here and bounded by the budget when it is on, so recognition
may never drain, and these are answered out of spans.

A store the deploy cannot read is not a cold corpus and does not wait: it stops the deploy and
reports what the store said, because the two have the same shape from outside and only one of
them is about timing. A deselected test says nothing about the build in either direction
— which is the one thing it must not be read as saying.

**The documents have a gate.** They are most of what this repository ships and the
first thing a second developer reads, so a contradiction between two of them is a defect
like any other — one saying the default locale is one language while the other says it is
another is not a wording problem. `scripts/check-docs.sh` holds the assertions and the
`docs` CI job runs it.

Not all of them are equally strong, and the file says which is which. **Computed** checks
derive the property and cannot be fooled by a reword. **Forbidding** checks assert a name
or pattern must not appear, and are sound while the thing forbidden is stable. Checks that
**quote a sentence** back at the file it came from catch a deletion and nothing subtler —
they go red on an innocent reword and green when the claim is reworded into its opposite.
Converting those is outstanding work, not a solved problem.

Three rules follow. **Write the violation before the assertion**, and confirm the gate goes
red on it: an assertion written first tends to match only the break its author imagined.
**When one goes red, the assertion is as likely to be wrong as the document** — read both
before editing either.

And **the undo for a deliberate break is a copy, never git.** `cp -p` the one file aside
immediately before breaking it and copy it back after, under a shell trap so an interrupted
run cannot leave the break in the tree. `git checkout` restores the last commit, so it cannot
tell the break from any other uncommitted edit to that file and takes both; against a file
git does not yet track it does nothing at all, and the break survives. The same holds for a
test deliberately broken to prove it bites. Whatever the undo, the loop ends with a run
against an unbroken tree: a battery can report every break caught while quietly holding work
the undo removed, because the assertions that passed were reading a file it never touched.

**Style is not a review topic; the linter owns it.** Review findings get fixed on the
branch, not filed as follow-ups, unless they are genuinely out of scope.

**Unit tests are a ceiling, not a floor.** A unit test earns its place when it covers a
decision — branching, ordering, parsing, a boundary — that is expensive to reach
end-to-end. A test that only re-asserts wiring is already covered and is bloat: it slows
the suite, and it has to be rewritten every time the wiring moves, which teaches people
to distrust a red. The list in the testing table of
[`architecture.md`](architecture.md) is the intended extent, not a menu to fill in.

**CI cannot run the two local tiers, so a person has to.** They need a live stack, a real
Telegram user account and a model credential; CI has none of them, and keeping it that way
is worth more than the coverage would be. The reviewing pass runs them on the dev stack before
a merge and `/deploy` runs them again on the next dev deploy, which is what makes the
definition of done checkable rather than aspirational.

**That is a convention, not a rail.** Nothing in the platform stops a merge whose end-to-end
case never ran — branch protection needs a paid plan the organisation does not have — so the
test plan on the pull request is where it is claimed, and an unticked box there is the only
signal anyone gets. **A production deploy does not close this gap.** It verifies what it
can — the containers are healthy, the backend answers, ingestion is still connected, and a
message to the bot gets a reply — but it runs neither suite, because both plant real
messages into a real Telegram account and production ingests into the live corpus. So a
production deploy that sets `Done` confirms the stack is alive, not that it is correct.
A full dev deploy runs the end-to-end suite, so for Workstream 0 the deploy that finishes
a card usually exercises it too — but not always, and not by itself: the evaluation set is
asked for rather than automatic, the Telegram cases skip when no account is configured, the
cases that ask about a subject skip when none is configured, and
a deploy whose index has not caught up deselects every test that needs material to answer
from. Each of those still exits zero. So a deploy may finish a card having measured less
than a green run suggests, which is why the sweep reads what actually ran rather than the
exit code.

**The evaluation set is the gate that detects a retrieval regression, and it binds by the
convention above rather than by a rail.** A red there is acted on and never noted and passed
over, which is the whole of what makes it a gate. It reports three numbers rather than one —
whether the right material came back, whether every claim rests on it, and what the prose said —
because a single figure lets a fluent answer hide a retrieval failure. A dimension nothing graded
says so instead of averaging to nothing, and a run that cannot score skips and names what is
missing.

**It is pinned to a corpus snapshot, and scores its own.** Ingestion runs daily and the serving
corpus mutates underneath it, so a suite scored against that measures the corpus rather than the
code. The fixtures are invented — known-good answers are only knowable for material somebody
wrote — and they go into a store nothing else writes, behind a Compose profile of its own.
Records are seeded and the derived layer is produced by the stack, because a span carries an
embedding nobody can hand-write and both derived passes are code under test. Refreshing the
snapshot is deliberate: it is rebuilt when the fixtures change or when it has aged out, which is
also the only part of a run that spends the model budget.

**What it does not cover is named rather than implied.** Offer and price has no shape to score
until Drive arrives; research answers from material the corpus never held, so a fixed corpus
cannot pin its retrieved set; and how well recognition reads the corpus is a decision waiting on
this suite rather than something it answers. A smaller denominator reported as success is worse
than an admitted gap.

## Handover

The constraint that shapes this whole process. Concretely:

- The repository lives in the client's own GitHub organisation, not a personal one.
- Architecture decisions that were not obvious live in `docs/`, with the reasoning,
  not just the outcome.
- Every operational secret has a documented location and a rotation path. Nothing
  is only in someone's terminal history.
- The deploy is a script, not a sequence of remembered commands.
- Anything a second developer would need to ask about is a documentation bug.

## Not yet decided

- **Branch protection — postponed, not skipped.** Branch protection and rulesets
  require a paid GitHub plan on a private repository; the organisation is on the
  free plan, and the API returns 403. Enable a rule requiring a green CI run before
  merge as soon as the plan allows. Until then the PR flow under "Branches, commits,
  and merges" is **convention, enforced by discipline rather than by the platform**.
- **Release tagging.** No versioning scheme yet. Not needed before there is
  something deployed to roll back to.
- **Monitoring.** No alerting on a stalled ingest or a failed nightly backup. A
  stalled cursor is the dangerous case: the worker stays up, answers stay plausible,
  and the corpus quietly stops moving. Needed before the system is relied on, not
  before the demo.
