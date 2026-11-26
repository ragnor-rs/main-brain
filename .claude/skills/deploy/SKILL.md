---
name: deploy
description: Deploy the Main Brain stack (postgres + embeddings + ingest + backend + bot) to dev or production via scripts/deploy.sh. Use when the user wants to deploy, redeploy a service, or provision a host (e.g. "/deploy", "deploy to production", "redeploy the bot").
---

# Deploy — prebuilt jars to dev or production

Main Brain ships **prebuilt fat jars**: Gradle runs on the dev machine and the
backend/bot/ingest images only `COPY` the resulting jar (the embeddings sidecar, the one
remaining Python service, is an image built on the host). So a remote
deploy = **build the jars → rsync the tree + those jars to the host → `docker compose build
&& up` THERE**; the host needs no JDK or Gradle. Flyway migrates on backend startup.

**Two environments.**

| `--env` | Where | State |
|---|---|---|
| `dev` | this machine | **works** — build, up, wait for the index, and on a *full* deploy the end-to-end suite. The evaluation set only with `--eval` |
| `prod` | the office machine | not implemented; the script refuses it by name |

> ⛔ **Only `dev` runs today.** The remote target is separate work on its own card, and
> `scripts/deploy.sh` declines it rather than half-attempting an ssh sequence. If it is
> asked for, say so and STOP — never improvise rsync and ssh by hand against a host that
> holds a live Telegram session. The rest of this skill documents the remote path so that it
> is designed before it is written, and so nobody invents a different one under time
> pressure.

> **Guardrail:** a deploy to production replaces live containers and interrupts ingestion.
> NEVER run `--env prod` without the owner's explicit go for THIS deploy.
> Dev (`--env dev`) is fine to run freely.

## Prerequisites

**Dev — what the script actually checks today.** It refuses, naming the fix, if either is
missing:

- **`.env` in the repository root.** `cp .env.example .env`, then fill it in. Compose will
  not interpolate the file at all while `POSTGRES_PASSWORD` is unset. A working tree holds
  the main checkout's file as a link, which `scripts/tree-setup.sh` makes when the tree is
  cut; run it by hand in a tree cut any other way.
- **The end-to-end client libraries.** `pip install -r e2e/requirements.txt`. Checked before
  anything is built, because the suites run at the end and a missing library is an import
  error rather than the skip the harness is designed around.

**Production — not implemented.** What follows describes the path that card will build, so
that it is designed before it is written. None of it is true of the script yet, and nothing
below exists in the repository.

- **SSH access** to the host (key-based), from `PROD_HOST` or `DEPLOY_HOST`. No baked-in
  default: the machine is on a residential connection and its address is not stable.
- **Host provisioned** with Docker + Compose.
- **A git-ignored secrets file** holding `POSTGRES_PASSWORD`, `ANTHROPIC_API_KEY`,
  `BACKEND_SECRET`, `TELEGRAM_BOT_TOKEN` and the allowlist.
- **Telegram user sessions are NOT deployed.** They live on the host, outside the project
  tree, and are created there by signing in once. A session file is a logged-in account —
  never rsync one, never commit one, never copy one between machines.

## Steps

1. **Confirm intent + target.** For `--env prod`, restate what will happen ("rebuild +
   restart `<services>` on `<host>`, replacing live containers and pausing ingestion") and
   get an explicit go. For `--env dev`, proceed.

2. **Preflight (read-only):**
   - For `--env prod`, STOP — not implemented (see the banner above).
   - For `--env dev`, the script's own preconditions are `.env` and the e2e client
     libraries; it checks both before building and names the fix. Nothing else to do here.
   - ⛔ **Capacity, which the script does not check and a guard does.** A dev deploy brings a
     stack up and builds the jars, and both are machine-wide resources shared with every other
     session working this repository. **At most one Compose stack and two Gradle builds may
     run at once**, stated in `.claude/mjolnir.yaml` and held by the toolkit's guard: with every
     place held the call is **refused rather than queued**, and what unblocks it is a place
     coming back — a holder finishing, or its lease expiring. So a deploy that is refused is
     retried once a slot frees rather than waited out in place.

     Over the cap a deploy does not merely slow itself: it slows what was already running, and
     the contention surfaces as a red nobody can attribute — a store test failing on the
     reaper, an embed timing out into `CANNOT_ANSWER_RIGHT_NOW`, an `:ingest` timeout test red
     on a diff that touched no `:ingest` file.

     ⚠️ **A place is held by a lease and not by what is running**, so these reads say what is
     actually up and the guard's count says what is claimed; they disagree in both directions —
     a build started outside a guarded call is in neither count, and a lease left by a refused
     call pins a cap with nothing running. To see what is actually up:

     ```bash
     docker ps --format '{{.Label "com.docker.compose.project.working_dir"}}\t{{.Label "com.docker.compose.project"}}' | sort -u
     ps ax -o pid,etime,command | grep "[G]radleWrapperMain"            # one per ./gradlew
     ps ax -o pid,%cpu,command | grep "[G]radleDaemon"                  # an IDE build has no client
     ```

     A stack is identified by its `working_dir` label and not its project name — Compose
     derives the name from the directory, but a deploy may override it, and unrelated projects
     on this daemon must be neither counted nor touched. A build is the **greater** of the two
     Gradle tells, never the sum: one command-line build is a wrapper client and a busy daemon
     at once, while an IDE-driven build is a busy daemon with no client at all. Idle daemons
     outnumber live builds and do not count, which is what the CPU floor is for.

     **A merged branch's stack is released for you when its tree is removed**, which is the
     ordinary way a slot turns over and needs no step here: the toolkit brings the stack down
     first, at the merge a session makes and on the sweep each session opens with, and keeps the
     tree while the stack is still up or Docker cannot be asked. A stack whose tree is already
     gone is found and stopped on that same sweep. If one is still up, the removal's report says
     why. An **unmerged** worktree's stack is
     another session's live workload, and the primary checkout's own stack holds the archive:
     neither is yours to take down, so say the deploy is waiting and on what.
   - A second stack is also what the TDLib session lock forbids: `TELEGRAM_SESSION_HOST_PATH`
     is one path for the machine as long as `.env` is the single symlinked file it is, so
     whichever `ingest` starts second fails on `td.binlog` indefinitely while the deploy still
     looks green. `INGEST_ENABLED=false` is for keeping the lock with the stack that ingests —
     set per deploy, never in `.env`, which every worktree shares — and not a way to run a
     stack the cap does not allow.
   - `git status` — a deploy ships the working tree, not a commit: dev builds the images from
     it and the remote path rsyncs it. Note uncommitted changes; step 5 refuses to sweep on them.
   - `git fetch origin && git rev-parse --abbrev-ref HEAD HEAD origin/main` — record the branch
     and whether HEAD is `origin/main`. Step 5 reads this, and by then the deploy has run; a
     clean `main` that is behind the remote looks identical to an up-to-date one without the
     fetch. Not a reason to refuse the deploy — only to skip the sweep.
   - Production: nothing to preflight — the script refuses it above. The secrets file that
     target will need does not exist yet either.
   - Optional: `ssh <host> 'docker compose version'` to confirm the host is provisioned.

3. **Run the deploy** from the repo root:
   ```bash
   ./scripts/deploy.sh --env <dev|prod> [--all | --postgres --embeddings --ingest --backend --bot]
   ```
   - No service flag ⇒ `--all`.
   - Redeploy one service after a code change, e.g. `--bot` or `--backend`.
   - First-ever run builds the embeddings image (large, CPU-only torch wheel) — slow once, cached after.
   - The script: builds the fat jars for the selected JVM services (`./gradlew :backend:shadowJar
     :bot:shadowJar :ingest:shadowJar`, failing the deploy if Gradle fails) → rsyncs the tree + those jars → writes
     the remote `.env` → `docker compose build && up -d` → prints container status + recent logs.
   - **The rsync must exclude `.claude/worktrees/`.** Parallel sessions check out working trees
     there, each a full copy of the repository with its own build output, and rsync does not
     read `.gitignore`. Without the exclusion a deploy ships every one of them to the host.
   - **It must exclude the toolkit's local configuration too** — `.claude/mjolnir.private.yaml`
     and `.claude/mjolnir.people.yaml`. Both are git-ignored for the same reason the archive and
     the time log are, and rsync reading no `.gitignore` is exactly how a file kept out of the
     repository reaches a machine anyway. Neither is read by anything that runs on the host.

4. **Verify.** The script prints `docker compose ps` and, on a **full** dev deploy, runs the
   end-to-end suite itself — and the evaluation set too when `--eval` was passed, bringing its
   stack up first. There is nothing to run by hand; the commands below are what it runs:

   ```bash
   MB_E2E_TARGET=dev python3 -m pytest e2e/ -v -rs
   docker compose --profile eval up -d backend-eval
   MB_E2E_TARGET=dev python3 -m pytest eval/ -v -rs
   ```

   ⚠️ **A selective dev deploy runs neither, and says so.** `docker compose up -d bot`
   starts the bot and its dependency chain only, so `ingest` is legitimately absent and the
   stack check — which asserts all five services — would fail a deploy that did exactly what
   was asked. Run a full deploy before trusting anything about the services you did not
   touch.

   The **end-to-end suite** exercises the system the way a person uses it, and a full deploy
   runs it with no decision to make — after waiting for the index pass to catch up, and less
   the tests that need material to answer from if it has not. The **evaluation set** is the only thing that measures
   whether retrieval is *correct* rather than merely working, and it is the gate this project's
   own risk register calls decisive. It runs here because scoring retrieval needs real
   embeddings and a model credential — the two things CI deliberately does not have.

   ⚠️ **The evaluation set is opt-in: pass `--eval`.** It is the one part of a deploy that
   spends the model budget, about four dollars a run, nearly all of it the single shape that
   reads the open web. A deploy happens many times a day and most of them could not have moved
   an answer, so it is asked for rather than automatic. Ask for it when the change touched the
   answer path, a shape, retrieval, a prompt, the embedding model, or a derived pass feeding
   them. **A deploy without it names the tier it skipped** — repeat that in the report rather
   than letting the absence pass as a green.

   Read the skip lines, not just the exit code. **Three things skip when they are unconfigured**
   and each names what it wanted: the Telegram half of the end-to-end suite, the cases that ask
   about a subject when none is configured, and the whole of
   the evaluation set when the store password, the shared secret or the model credential is
   absent. Another is not a skip at all — an evaluation set that was never asked for did not
   run, and the deploy says so in those words. The last is announced by the deploy rather than
   by pytest: an index that has not caught up deselects every test that needs material, which is
   announced in a `==>` line and not in a skip line, so a run reporting fewer tests than usual
   measured less than it looks like — and the count to compare against is the suite's, not a
   number worth writing down here. A stack that is down fails rather than skipping — including
   then, which is why that case deselects those tests instead of dropping the suite — and a wrong `MB_E2E_TARGET` exits
   the whole run non-zero on purpose. So a green run can still have measured less than it
   appears to — the skips name what was missing precisely so they cannot be read as passes.
   **Report which checks actually ran**, by name, and treat a skipped evaluation set as *the
   gate did not run* rather than as *the gate passed*.

   The evaluation set also prints three scores and the number of cases each was taken over.
   **Report the numbers**, not just the colour: that is what makes this run comparable with
   the last one, and a dimension that says `not graded` measured nothing.

   ⛔ **Never against production.** The end-to-end suite plants real messages into a real
   Telegram account for ingestion to pick up, production ingests into the live corpus, and the
   evaluation set seeds a fixture corpus of its own. `MB_E2E_TARGET` accepts only `dev` and
   both suites refuse anything else; do not look for a way around it. Production deploys get
   the manual checks below instead.

   ⚠️ **A regression in the evaluation set blocks the merge by convention**, not by a rail —
   branch protection needs a paid plan the repository does not have. So a red here is
   reported and acted on, never noted and passed over.

   - `ssh <host> 'cd /opt/main-brain && docker compose ps'` — all healthy.
   - **Healthy is not ready, for the embeddings sidecar.** Its healthcheck is liveness only,
     deliberately: it fetches and loads gigabytes of weights at startup and must not be
     restarted for being slow about it. So a container that reports healthy may be mid-fetch,
     or may have failed the load and be refusing every embed for the life of the process.
     Read the readiness field, and the sidecar's log if it is false:
     `docker compose exec -T embeddings python -c "import urllib.request;print(urllib.request.urlopen('http://localhost:8000/health').read())"`
     A first start on a machine that has never run it is legitimately slow here; a `ready` of
     false that never turns true is a failed load, and only the log says why.
   - Backend health from inside the container (Compose publishes no host ports):
     `ssh <host> 'cd /opt/main-brain && docker compose exec -T backend curl -fsS http://localhost:8080/health'`
   - **Ingestion is still connected**: check the ingest container's log for a recent sync, not
     just that the process is up. A deploy that silently invalidated the Telegram session looks
     healthy on every other signal.
   - **Two states say *up, quiet, reading nothing*, and only one of them is a fault.** Read the
     log before treating it as one. A deployment can be told not to read — the switch is in
     `.env.example` under ingestion — and such a container says so once at startup and then idles,
     which is deliberate and needs nothing done about it. Both states also report that a person is
     needed in the health row, so the row alone does not separate them; the log line does. Beware
     setting that switch in a `.env` more than one stack reads: it reaches whichever stack is
     actually ingesting, and a backfill unit already finished is never re-read, so the corpus it
     would have captured while quiet is gone. Set it for the one deploy instead.
   - **A session that needs a person reads as a process that is fine.** With no session minted,
     the ingest container waits: up, quiet, reading nothing, and saying so in its log and in the
     health row. Mint one by hand, once — `docker compose run --rm ingest login` locally, or
     `ssh -t <host> 'cd /opt/main-brain && docker compose run --rm ingest login'` remotely. The
     `-t` is not optional: the command reads a code from the console, and a plain `ssh` allocates
     no terminal for it to read from.
     **The stack can stay up while you do it** — a waiting service holds nothing, and starts
     reading within seconds of the sign-in landing. What must not happen is two ingest containers
     against one session directory, because the client library locks its store. That is also why
     the capacity rail judges this command: `compose run` starts a container, so from a checkout
     whose stack is not the one that is up it queues like any other start, and the slot has to be
     yours before you mint a session.
   - **A hole behind the front edge is repaired by hand, with the service stopped.** A corpus that
     has merely fallen behind needs nothing: the adapter measures its front edge against the source
     on every start and closes the difference itself. What it cannot reach is a hole further back —
     a page an interrupted pass skipped, a field never captured — and that is a re-read:
     `docker compose run --rm ingest reread` for the whole source, or `… reread <conversation>` for
     one, naming the conversation as the plan holds it. It records the request and exits; the
     reading happens on the next sweep, at the usual pace. **It opens no session**, so unlike
     `login` it needs no slot and contends for nothing — but the service must still be **stopped**
     first, for a reason the lock has nothing to do with: the beliefs a re-read clears are the
     running process's own memory, so one that kept reading through it would re-walk the history and
     still skip every profile and header it already thinks it has sent. Start it again afterwards —
     that start is what spends it, and a deployment switched off by `INGEST_ENABLED` never will,
     which the command says when it is the one you are sitting in.
   - **One login is the budget.** Repeated sign-ins can get the account soft-locked for about a
     day, so nothing in the service ever initiates one: it submits no number, because submitting
     one is what makes the platform send a code. A session file is a logged-in account, so it is
     never copied between machines and each host signs in once on its own. Settle where the
     session lives *before* signing in — `TELEGRAM_SESSION_HOST_PATH` in `.env` shares one
     session across every worktree on a machine, and leaving it unset gives each worktree its own
     volume and so its own sign-in.
   - The process also stops at startup if a source credential is missing, naming the variable
     it wanted. A restarting ingest container is that, most of the time, and its log says which.
   - Send a message to the bot from an allow-listed account (manual smoke).

5. **Sweep the board: `Merged` → `Done`.** On the board, `Done` means nothing is left, and
   for a card that waits on a deploy only this step can make that true — so this is what
   finishes those cards. See "Work tracking" in
   [`docs/process.md`](../../../docs/process.md).

   **Only sweep when all of these hold**, and say in the report when a condition stopped it:
   - the deploy was **stack-wide** (`--all`, or no service flag). A `--bot` deploy rebuilds
     the bot and nothing else, so it cannot make a merged `:backend` change live — and the
     sweep takes cards it never touched;
   - step 4 verified the stack healthy — a failed deploy has shipped nothing;
   - **the end-to-end suite measured the stack, rather than exiting zero having skipped it.**
     Two things make a green run mean less than it looks: the tests that need material are
     *deselected* when the index has not caught up — the script says so in a `==>` line — and
     the Telegram cases skip with no account configured, which `-rs` prints as a skip reason.
     Both are ordinary on a cold stack. `Done` means nothing further is required, so **a run
     with those tests deselected sweeps nothing**: they are the only ones that establish the
     stack answers at all, and every Workstream 0 card ultimately rests on that. A case that
     skipped for want of a configured subject is the same kind of gap, one shape narrower:
     it bounds which shapes were driven rather than whether any answered. Other skipped
     Telegram cases are reported but do not stop the sweep, since they bound how the stack was
     driven rather than whether it answers. The evaluation set is opt-in and its absence
     blocks nothing — say in the report that it did not run, which is not the same as passing;
   - **the deploy ran in the primary checkout**, which is the repository root rather than a
     path under `.claude/worktrees/` — `git rev-parse --show-toplevel` against
     `git rev-parse --git-common-dir` settles it. Compose derives its project name from the
     directory, so a worktree deploy brings up a stack of its own, and retiring the worktree
     takes its containers down and then removes the directory, leaving the volume that holds
     that stack's store. A card marked `Done` would point at that rather than at the machine's dev stack.
     From a worktree, report what would have been swept instead of sweeping it;
   - **HEAD was `origin/main` and the tree was clean.** Neither environment ships a commit —
     dev builds the images from the working tree and the remote path rsyncs it — so from a
     feature branch or a dirty tree what is now live is not what is merged. A *stale* `main`
     is the easy one to miss: the finishing pass merges on GitHub, so local `main` is behind
     until someone pulls — which is exactly the state a deploy tends to run in. Sweeping then
     marks cards `Done` whose code is not in the tree that shipped.

   ⚠️ **The target decides which cards the sweep may take, not whether it runs at all.**
   `--env dev` finishes **Workstream 0** cards, because Workstream 0 runs on the dev stack
   and nowhere else; `--env prod` finishes the cards of every later workstream, which run on
   the office machine. A card whose workstream the deploy did not serve stays in `Merged`,
   and so does a card carrying no workstream at all — the sweep selects on that field, so a
   card without it is selected by neither target. Name every card left behind in step 6.

   The query therefore reads the milestone alongside the status, and the sweep takes only the
   cards whose workstream the target serves rather than the whole column:
   ```bash
   gh api graphql -f query='
   {
     user(login: "ragnor-rs") {
       projectV2(number: 2) {
         items(first: 100) {
           nodes {
             id
             content { ... on Issue { number title milestone { title } } }
             status: fieldValueByName(name: "Status") { ... on ProjectV2ItemFieldSingleSelectValue { name } }
           }
         }
       }
     }
   }' --jq '.data.user.projectV2.items.nodes[]
            | select(.status.name == "Merged")
            | "\(.content.milestone.title // "(none)")\t\(.id) #\(.content.number) \(.content.title)"'
   ```
   A card with no milestone prints as `(none)` rather than the bare word `null` — jq indexes
   null without complaint, so the fallback buys legibility rather than safety, and the point
   is that such a row is *listed and named* rather than quietly matching nothing. Sweep the
   rows whose workstream the target serves — `Workstream 0` on `--env dev`, every other
   workstream on `--env prod` — and report the rest.

   ⚠️ **`first: 100` is a ceiling, not a page.** It covers the board today and silently
   truncates past it, which would read as a sweep that finished everything. Count the items
   returned: at 100, paginate with `after:` before sweeping anything.
   Then, for each item id:
   ```bash
   gh api graphql -f query='
   mutation {
     updateProjectV2ItemFieldValue(input: {
       projectId: "PVT_kwHOADM8g84Bnayf"
       itemId: "<ITEM_ID>"
       fieldId: "PVTSSF_lAHOADM8g84Bnayfzhl_heA"
       value: { singleSelectOptionId: "98236657" }
     }) { projectV2Item { id } }
   }'
   ```

6. **Report** the outcome: which services were (re)built, health status, ingestion state,
   any failing container with the relevant log tail, and which cards the sweep moved — by
   issue number, so the board's claim is checkable against what was actually deployed.
   **Name the `Merged` cards it left behind and why**, workstream by workstream: a card
   waiting on the other environment reads exactly like a card the sweep overlooked, and
   only the report tells them apart.

## Constants

- **Board**: `ragnor-rs` / project number `2` — id `PVT_kwHOADM8g84Bnayf`
- **Status field ID**: `PVTSSF_lAHOADM8g84Bnayfzhl_heA`
- **Status options**: Todo `f75ad846`, In Progress `47fc9ee4`, Merged `76620d5d`,
  Done `98236657`

## Notes

- Named volumes persist: the script brings services up and never tears them down, so no
  path through it can remove one. The store holds years of
  re-ingested history — losing it costs days of rate-limited backfill, not minutes.
- **The model weights are one cache for the machine, shared by every worktree.** Volumes
  otherwise take the Compose project's derived name, which is isolation working as designed;
  the weights are the exception, declared `external` under a fixed name so that the first
  deploy on a machine pays the fetch and every worktree after it starts warm. A dev deploy
  creates it, so there is no manual step — on *this* machine. The remote path runs Compose
  on the host, where the volume does not exist and `up` hard-fails without it, so creating
  it there is part of building that path rather than something this one already covers.
- **Do not remove the shared cache; do remove the project-scoped ones.** Deleting
  `mainbrain-embeddings-models` costs every worktree a cold refetch of about 4.6 GB, so it
  is not part of retiring a worktree. The per-project copies from before the cache was
  shared are the opposite: `docker volume ls | grep embeddings-models` lists them, each is
  about 4.6 GB, and every one whose stack has been redeployed is dead weight. A machine that
  runs out of disk mid-fetch can take the Docker daemon down with it — every API call
  returning 500 until it is restarted, which reads as a broken deploy and is not one. Prune
  images and build cache before blaming the code.
- To point at a replaced machine, the remote path will read `PROD_HOST=<address>` from the
  environment rather than a baked-in default — so swapping the host is an export, not an
  edit. Nothing reads it yet.
- This skill is deploy-only; it does not commit or open a PR. The toolkit's bookkeeping and
  finishing roles are for that. The one thing it does write outside the host is the board sweep in
  step 5, and only because nothing else can know that a change is live.
