#!/usr/bin/env bash
#
# Deploy the Main Brain stack.
#
# Two environments: dev (this machine) and production (the office machine). Only dev is
# implemented — the remote target is separate work, and this script refuses it by name rather
# than half-attempting an ssh sequence. A deploy that partly works against a live host is
# worse than one that declines.
#
#   ./scripts/deploy.sh --env dev [--eval] [--all | --backend --bot --ingest --postgres --embeddings]
#
# A full dev deploy is: build the fat jars, build the images, bring the stack up, wait for the
# index pass to catch up with the corpus, then run the end-to-end suite. If the index has not
# caught up in time the suite still runs, less the tests that need material to answer from
# — so a stack that came up broken still fails rather than passing in silence. A selective one
# (--bot, --backend, ...) builds and starts only what was asked and runs no suite at all — they
# assert against the whole stack.
#
# `--eval` additionally runs the evaluation set, which is by far the larger part of a deploy's
# model spend — about $3.65 a run, nearly all of it the one shape that reads the
# open web. It is opt-in rather than default because a deploy happens many times a day and
# the budget is finite; ask for it when the change could move retrieval or answering. The
# end-to-end suite spends too, a call for each question it puts to the stack: the cases that
# ask about a subject are what read the rendered message, and they run only where one is
# configured, which is most of what bounds an ordinary deploy. The exception sets its floor —
# the shape that reads the open web accepts no subject, so no setting gates it and it runs on
# every deploy that has an account, at the highest effort this build asks for.
# Nothing here touches the board, but a successful stack-wide deploy does finish cards: dev
# finishes Workstream 0, which runs on this machine and nowhere else, and production finishes
# the later workstreams, which run on the office machine. The sweep is the toolkit's deploy
# role's advance, configured in .claude/mjolnir.yaml, and so are the conditions that make it
# honest.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# The interpreter the suites run under: the main checkout's virtual environment where it has one,
# shared by every tree, and whatever `python3` is first on the path otherwise. A deploy is run by
# a person's shell and by the toolkit's deploy role alike, and the two do not share a path.
MAIN_CHECKOUT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON=python3
[[ -x $MAIN_CHECKOUT/.venv/bin/python3 ]] && PYTHON="$MAIN_CHECKOUT/.venv/bin/python3"

ENVIRONMENT=""
SERVICES=()
JVM_SERVICES=(backend bot ingest)

# How long to let the embeddings sidecar load its weights before giving up on it. Generous
# because the two cold cases differ by an order of magnitude: a recreate with the cache warm
# loads in about a minute, while a machine fetching the weights for the first time has taken
# over eight. Waiting the long case out costs a deploy nothing when the short one applies —
# the wait ends when the sidecar says it is ready, not when this elapses.
SIDECAR_READY_SECONDS=720

# How long to let the index pass catch up before the tests needing material are dropped.
#
# **Bounded by the sidecar's throughput, not by the cadence.** A sweep drains rather than doing
# one batch per interval — `DerivedPasses` says so, and indexing spends no model budget, so
# nothing stops a sweep short — which means a cold corpus is one sweep of however long the
# embeddings take, and the cadence only governs how soon that sweep starts. So this is a
# tolerance for how slowly the machine can embed, and a loaded machine running several sidecars
# at once is the case that makes it large. A redeploy onto a corpus already indexed ends on the
# first poll and pays none of it.
INDEX_DRAIN_SECONDS=1800

die() { printf '\n\033[31merror:\033[0m %s\n\n' "$*" >&2; exit 1; }
say() { printf '\n\033[36m==>\033[0m %s\n' "$*"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    # `shift 2` on a lone `--env` returns non-zero and `set -e` kills the script before the
    # error below is ever reached — so the one input that message exists for exits in
    # silence. Consume the flag, then the value only if there is one.
    --env)
      shift
      [[ $# -gt 0 ]] || die "--env needs a value. One of: dev, prod."
      ENVIRONMENT="$1"; shift
      ;;
    # Opt-in because it is the only part of a deploy that spends money. See the header.
    --eval) EVAL=1; shift ;;
    # Does not clear SERVICES. Clearing it meant `--bot --all` erased the flag before the
    # exclusivity check below could see it, so one of the two orders still slipped through
    # as a silent full deploy — the exact order-dependence the check exists to remove.
    --all) ALL=1; shift ;;
    --postgres|--embeddings|--ingest|--backend|--bot) SERVICES+=("${1#--}"); shift ;;
    # Bounded by where the header stops rather than by a line number: print from line 2 while
    # the lines are comments, and stop at the first that is not. A hard upper bound has to be
    # re-counted every time the header grows, it truncates `--help` silently rather than
    # failing when it is not, and it has been left behind twice — once ending mid-sentence, and
    # once dropping the paragraph that says what a deploy does to the board.
    -h|--help) awk 'NR>1 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

# `--all` with a service flag is a contradiction, and which one won used to depend on the
# order they were typed in.
if [[ "${ALL:-0}" -eq 1 && ${#SERVICES[@]} -gt 0 ]]; then
  die "--all and a service flag are mutually exclusive. Pass one or the other."
fi

[[ -n "$ENVIRONMENT" ]] || die "--env is required. One of: dev, prod."

case "$ENVIRONMENT" in
  dev) ;;
  prod)
    die "production deploy is not implemented yet — see the production deploy issue on the
       board. It needs the ssh, rsync and secrets path, and improvising one by hand against
       the machine that holds the live corpus is exactly what that issue exists to prevent."
    ;;
  *) die "unknown environment: '$ENVIRONMENT'. One of: dev, prod." ;;
esac

# --- dev ---------------------------------------------------------------------------------

# Compose reads .env, and refuses to interpolate the file at all while a required variable is
# unset. Catching that here names the fix; Compose's own message names the variable but not
# where it comes from.
[[ -f .env ]] || die "no .env in the repository root. Copy .env.example and fill it in:
       cp .env.example .env"

# The source credential, checked here for the same reason as the libraries below: the ingest
# process reads these three at startup and stops without them, so a deploy that did not look
# would bring the whole stack up and leave one container restarting — and the stack check
# asserts all five are running, so it reads as a broken deploy rather than an unset variable.
# Read out of `.env` rather than the environment, because that is the file Compose will read.
# Only on a full deploy: a selective one may legitimately not touch ingest at all.
if [[ ${#SERVICES[@]} -eq 0 ]]; then
  for variable in TELEGRAM_API_ID TELEGRAM_API_HASH TELEGRAM_PHONE; do
    value=$(sed -n "s/^${variable}=//p" .env | tail -1)
    [[ -n ${value//[[:space:]]/} ]] || die "$variable is empty in .env, and the ingest process
       reads it at startup. Fill in all three of TELEGRAM_API_ID, TELEGRAM_API_HASH and
       TELEGRAM_PHONE — an application from https://my.telegram.org/apps, and the account the
       session signs in as."
  done
fi

# The shared secret on the one hop between the bot and the backend, checked for the same reason
# and in the same place. The backend reads it at startup and stops without it, so a deploy that
# did not look would leave that container restarting while the stack check reports a service
# down. Compose is deliberately not the thing that demands it — see the comment beside it in
# `docker-compose.yml` — so this is where an unset one is caught.
#
# Checked whenever either end of that hop is being deployed: a full deploy, or a selective one
# that names the backend or the bot. A selective deploy of postgres alone needs no secret.
needs_secret=false
if [[ ${#SERVICES[@]} -eq 0 ]]; then
  needs_secret=true
else
  for service in "${SERVICES[@]}"; do
    if [[ $service == backend || $service == bot ]]; then
      needs_secret=true
    fi
  done
fi
if [[ $needs_secret == true ]]; then
  value=$(sed -n "s/^BACKEND_SECRET=//p" .env | tail -1)
  [[ -n ${value//[[:space:]]/} ]] || die "BACKEND_SECRET is empty in .env, and the backend reads
       it at startup and refuses every request without it. Any long random string will do:
       openssl rand -hex 32"
fi

# The bot's own two, checked on the same principle and for the same reason: `BotConfig` reads
# both at startup and stops without either, so an unset one is a container in a restart loop
# behind a deploy that reported success. Compose defaults them empty rather than refusing, so
# that one unset secret cannot make the whole stack uninspectable — which is what leaves this
# as the place an empty one is caught.
#
# Only when the bot is actually being deployed. A selective deploy of the backend alone has no
# use for a Telegram token.
needs_bot=false
if [[ ${#SERVICES[@]} -eq 0 ]]; then
  needs_bot=true
else
  for service in "${SERVICES[@]}"; do
    if [[ $service == bot ]]; then
      needs_bot=true
    fi
  done
fi
if [[ $needs_bot == true ]]; then
  value=$(sed -n "s/^TELEGRAM_BOT_TOKEN=//p" .env | tail -1)
  [[ -n ${value//[[:space:]]/} ]] || die "TELEGRAM_BOT_TOKEN is empty in .env, and the bot reads
       it at startup and stops without it. Create a bot with @BotFather and paste the token it
       gives you."
  value=$(sed -n "s/^ALLOWED_TELEGRAM_IDS=//p" .env | tail -1)
  [[ -n ${value//[[:space:]]/} ]] || die "ALLOWED_TELEGRAM_IDS is empty in .env, and the bot
       refuses to start without it rather than admitting everyone. Comma-separated numeric
       Telegram user ids: ask @userinfobot for yours."
fi

# Checked before anything is built, not after, and only when the suites are actually going
# to run. A missing client library is a collection error rather than the skip the harness is
# designed around, so without this a full deploy on a clean machine brings the stack up and
# then dies on an import — which reads as a broken deploy rather than a missing dependency.
# A selective deploy runs no suite, so demanding the libraries there would refuse work that
# does not need them.
if [[ ${#SERVICES[@]} -eq 0 ]]; then
  "$PYTHON" -c 'import pytest, telethon' || die "the end-to-end harness needs its client
       libraries, and importing them failed (the error is above). They are not optional on
       a full deploy: the suites are how it knows it worked.
       pip install -r e2e/requirements.txt"
  # The evaluation set's own, checked in the same breath and named separately. It talks to its
  # store over SQL to seed the fixtures, which is a library the other harness does not carry.
  "$PYTHON" -c 'import psycopg' || die "the evaluation set needs its store driver, and importing
       it failed (the error is above). It seeds the fixture corpus over SQL, so without this
       the one gate that detects a retrieval regression cannot run.
       pip install -r eval/requirements.txt"
fi

if [[ ${#SERVICES[@]} -eq 0 ]]; then
  say "Building the fat jars for every JVM service"
  ./gradlew :backend:shadowJar :bot:shadowJar :ingest:shadowJar
else
  # Only the selected JVM services, so redeploying the bot does not rebuild the backend.
  JAR_TASKS=()
  for service in "${SERVICES[@]}"; do
    for jvm in "${JVM_SERVICES[@]}"; do
      [[ "$service" == "$jvm" ]] && JAR_TASKS+=(":${service}:shadowJar")
    done
  done
  if [[ ${#JAR_TASKS[@]} -gt 0 ]]; then
    say "Building the fat jars for: ${JAR_TASKS[*]}"
    ./gradlew "${JAR_TASKS[@]}"
  fi
fi

# The embedding weights cache is declared `external` in Compose, so that one copy serves
# every worktree on this machine and no teardown of one stack can delete it. The price of
# `external` is that Compose will not create it: a missing one is a hard error at `up`, not
# a volume it makes. Creating it here is what keeps a machine that has never run the stack
# from needing a step somebody has to remember — this machine, note, not a remote host: the
# production path will run Compose over ssh, and the volume has to be created there too.
# Idempotent — `docker volume create` on an existing volume succeeds and changes nothing —
# and unconditional, because a selective deploy of `--embeddings` needs it just as much as
# a full one. The document gate asserts both the name and that this line stays at the top
# level, since an indented one would be a conditional create.
#
# This is also the script's first call to Docker, so it is where a stopped daemon surfaces.
# Unguarded it would print only that it cannot connect, which reads as a broken deploy.
say "Ensuring the shared weights cache exists"
docker volume create mainbrain-embeddings-models >/dev/null || die "could not create the
       shared weights volume (the error above is Docker's own). If the daemon is not
       running, start it and re-run."

# `"${SERVICES[@]}"` on an empty array is an unbound variable under `set -u` in the bash
# macOS ships (3.2), so an all-services deploy would abort before building anything. The
# `+` form expands to nothing at all when the array is empty, which is what Compose wants:
# no service arguments means every service.
say "Building images"
docker compose build ${SERVICES[@]+"${SERVICES[@]}"}

say "Starting the stack"
docker compose up -d ${SERVICES[@]+"${SERVICES[@]}"}

say "Container status"
docker compose ps

# The two tiers CI cannot run. They are part of the deploy rather than a thing to remember
# afterwards: a deploy that brought the stack up and never asked whether it works has
# verified that containers start, which is not the same claim.
#
# Only after a full deploy, though. `docker compose up -d bot` starts the bot and its
# dependency chain and nothing else, so `ingest` is legitimately absent — and the stack check
# asserts all five services are running. Running it anyway would fail a deploy that did
# exactly what was asked.
if [[ ${#SERVICES[@]} -gt 0 ]]; then
  say "Selective deploy of ${SERVICES[*]} — the suites did NOT run.
    They assert against the whole stack, and this deploy started part of it. Run a full
    deploy before trusting anything about the services you did not touch."
else
  # **Container health is not readiness, and for the sidecar that gap is deliberate.** It
  # answers `/health` with 200 while the weights are still loading, precisely so that a cold
  # start does not fail its healthcheck — and it reports `ready` in the same body so that a
  # caller who needs the stronger claim can have it. Until something reads that field, a
  # deploy runs the suites seconds after `up` against a sidecar that refuses every embed, and
  # the bot answers that it has nothing to answer from: the same reply as an empty index, so
  # the red names the wrong thing. Reached through `exec` because the service publishes no
  # host port.
  #
  # Waited for here rather than in the suite: whether the stack this script just started is
  # warm is a property of the deploy, not of the question being asked. A suite that skipped
  # on a cold sidecar would report nothing ran, which is a worse answer than waiting.
  say "Waiting for the embeddings sidecar to load its weights"
  ready=""
  deadline=$(( SECONDS + SIDECAR_READY_SECONDS ))
  while (( SECONDS < deadline )); do
    ready=$(docker compose exec -T embeddings python -c \
      "import json, urllib.request
print(json.load(urllib.request.urlopen('http://localhost:8000/health'))['ready'])" \
      2>/dev/null | tr -d '[:space:]') || true
    if [[ $ready == "True" ]]; then
      break
    fi
    sleep 5
  done
  [[ $ready == "True" ]] || die "the embeddings sidecar did not report ready within
       ${SIDECAR_READY_SECONDS}s, so retrieval would have returned nothing and the suites
       would have blamed the answer path. Its own log separates the two cases — still
       fetching, or a load that failed: docker compose logs embeddings."

  # **A sidecar that can embed is not a corpus that has been embedded, and the suite needs the
  # second.** The wait above ends when the sidecar says it holds a model; nothing yet says whether
  # anything has been put through it. A deploy onto a volume whose records are present but whose
  # spans are not — a fresh project, a restored archive, a stack that ingested and was then
  # stopped — asks the answer question against an empty index, retrieval returns nothing, and the
  # reply is the one *every* failure on the answer path renders as. So the suite reports the answer
  # path broken for a stack that had not finished starting, which is the reading this whole block
  # exists to prevent and which the sidecar half of it does not cover.
  #
  # **Asked as the pass's own definition of owed, not as a count of marks.** A mark says a record
  # was looked at — `Indexer` marks the stickers and captionless images too, so that they are not
  # handed back for ever — and it is kept at whatever version wrote it, updated in place. So marks
  # reaching records says neither that spans exist nor that the ones that do are current: change
  # the embedding model and every record carries a mark while the whole corpus is owed again, which
  # is the one state where retrieval returns nothing from a full-looking index. `owedFrom` in
  # `JdbcDerivationStateRepository` has the three branches that settle it — no mark, a mark at
  # another version, a mark older than the record — and says a second copy of that definition is
  # how a working pass comes to look stalled. This is that query with the pass's own version string,
  # read from the sidecar that would have written the marks. Its fourth clause, the horizon, is
  # omitted because indexing passes none.
  #
  # Narrower than `eval/corpus.py`, which waits on recognition's two marks as well: a body of work
  # is a conversation's label, and its fixture cases assert on sectioning. Here inference is off by
  # default and bounded by the model budget when it is on, so recognition may never drain — and
  # these tests need none of it. Measured rather than assumed, on the free-text question: it passes
  # on a corpus holding no commitments and no events at all, answering out of spans. The shapes a
  # command selects read those same message turns, so the same should hold for them — but that is
  # reasoning and not a measurement, and they ask more of the reply than that one does.
  say "Waiting for the index pass to catch up with the corpus"
  # Read out of `.env` the way every other value here is, and defaulted the way `docker-compose.yml`
  # defaults them. Compose prefers a value exported in the shell to the one in the file, so this
  # agrees with the container for every deployment that keeps its settings where this script looks.
  pg_user=$(sed -n "s/^POSTGRES_USER=//p" .env | tail -1 | tr -d '[:space:]')
  pg_db=$(sed -n "s/^POSTGRES_DB=//p" .env | tail -1 | tr -d '[:space:]')
  # The version a current mark has to carry. Read from the sidecar rather than from the database,
  # because it is the sidecar's answer that decides what is stale — the backend compares the two
  # the same way. Passed to psql as a variable so that a version string carrying a slash or a
  # quote cannot change the query.
  index_version=$(docker compose exec -T embeddings python -c \
    "import json, urllib.request
print(json.load(urllib.request.urlopen('http://localhost:8000/health'))['version'])" \
    2>/dev/null | tr -d '[:space:]') || true
  [[ -n $index_version ]] || die "the embeddings sidecar reported ready but no version string, so
       there is nothing to tell a current mark from a stale one. Its /health body is what this
       reads: docker compose exec embeddings python -c \"import urllib.request;
       print(urllib.request.urlopen('http://localhost:8000/health').read())\""

  OWED="" RECORDS="" PROBE_OUT=""

  # How much the index still owes, and over how many records. Returns 0 when it owes nothing,
  # 1 when it owes something, and 2 when the store could not be read at all — three outcomes
  # because the caller has to act differently on each, and a read that failed is not a cold
  # corpus. One function rather than the comparison written at both places that need it.
  # `PROBE_OUT` keeps whatever came back, so the caller can put postgres's own complaint in the
  # message instead of guessing at the cause.
  corpus_owed() {
    local counts
    # **The statement goes in on stdin, not through `-c`.** psql interpolates `-v` variables only
    # for SQL it reads, never for `-c`, so the version would arrive as the literal text `:'version'`
    # and the query would fail on it — which this function would then report as an unreadable store.
    # Interpolated as `:'version'` rather than pasted in, so a version string carrying a quote
    # cannot change the query; the strings in use carry a slash and a plus already.
    counts=$(printf '%s\n' "SELECT (SELECT count(*)
                 FROM message r
                 LEFT JOIN derivation d
                        ON d.deriver = 'index'
                       AND d.record_kind = 'message'
                       AND d.record_source = r.source
                       AND d.record_native_id = r.native_id
                WHERE d.id IS NULL
                   OR d.version <> :'version'
                   OR d.derived_at < r.content_time),
              (SELECT count(*) FROM message)" \
      | docker compose exec -T postgres psql -qAt -v ON_ERROR_STOP=1 \
          -v version="$index_version" \
          -U "${pg_user:-mainbrain}" -d "${pg_db:-mainbrain}" 2>&1) \
      || { PROBE_OUT="$counts"; return 2; }
    PROBE_OUT="$counts"
    counts=$(printf '%s' "$counts" | tr -d '[:space:]')
    # **The whole read has to be two numbers, not each half separately.** Splitting a value that
    # carries no separator yields that value twice, which compares equal — the hole the first
    # version of this had. A psql notice on stdout, one column where two were asked for, or an
    # absent table on an old volume all land here rather than being read as a count.
    [[ $counts =~ ^([0-9]+)\|([0-9]+)$ ]] || return 2
    OWED=${BASH_REMATCH[1]}
    RECORDS=${BASH_REMATCH[2]}
    # Both numbers every poll rather than the total once, because ingestion runs throughout and
    # raises the target as it goes.
    (( RECORDS > 0 )) && (( OWED == 0 ))
  }

  caught_up=0
  last=""
  deadline=$(( SECONDS + INDEX_DRAIN_SECONDS ))
  while :; do
    probe=0
    corpus_owed || probe=$?
    # **A store this script cannot read is an environment fault and stops the deploy**, rather
    # than polling out the whole bound and then reporting a timing skip it has not established.
    # Every other unreachable dependency here dies at once for the same reason; psql's own
    # complaint is in the message because it is the one thing that tells the cases apart.
    if (( probe == 2 )); then
      die "could not read the index state out of postgres, so whether the corpus is indexed is
       unknown — which is not the same as it being cold, and this is the one failure that would
       otherwise wait ${INDEX_DRAIN_SECONDS}s and then report a skip. The query is the index
       pass's own definition of owed, so an unapplied migration reaches it as a missing column.
       What came back: ${PROBE_OUT:-nothing at all}"
    fi
    if (( probe == 0 )); then
      caught_up=1
      say "The index has caught up: $RECORDS record(s), none owed."
      break
    fi
    # Nothing ingested is answered at once rather than waited out: no sweep can make this true,
    # because there is nothing for one to do.
    if (( RECORDS == 0 )); then
      break
    fi
    # Checked after the poll, not before it, so a pass that drains during the last sleep is still
    # seen rather than reported as having run out of time.
    (( SECONDS < deadline )) || break
    if [[ "$OWED/$RECORDS" != "$last" ]]; then
      say "Indexing: $OWED of $RECORDS record(s) still owed."
    fi
    last="$OWED/$RECORDS"
    sleep 10
  done

  # **The tests that need material are dropped; the rest of the suite is not.** Each of them could
  # only have failed for want of something to answer from, and a red there on an index that is
  # still filling is a statement about this deploy's timing rather than about the build. The rest
  # is what says the stack came up at all — every service running, and the Telegram session still
  # authorised — and those are exactly the failures a deploy must not pass over in silence, so a
  # stack that is down still goes red here. The evaluation set below is unaffected either way: it
  # seeds and scores its own fixture corpus on its own stack and never reads this one.
  #
  # **Selected by marker and not by name.** Every such case carries `corpus`, so one added later is
  # covered by this rule; a list of node identifiers would leave it going red for a reason that is
  # about the deploy rather than the change. `conftest.py` registers the marker and says the same.
  if (( caught_up == 1 )); then
    say "Running the end-to-end suite"
    MB_E2E_TARGET=dev "$PYTHON" -m pytest e2e/ -v -rs
  else
    if (( RECORDS == 0 )); then
      why="the corpus holds no records at all, so nothing has been ingested and no sweep could
    have changed that — the ingest log says why"
    else
      why="the index still owes ${OWED} of ${RECORDS} record(s) after ${INDEX_DRAIN_SECONDS}s"
    fi
    say "Every test that needs material is DESELECTED and this run is not evidence that the stack
    answers: ${why}. Those tests could only have failed for want of material, which reads
    identically to the answer path being broken. The rest of the suite still runs, so a stack that
    came up broken still fails here. Leave the stack up and deploy again once the pass has caught
    up; it reports itself each sweep in \`docker compose logs backend\`."
    MB_E2E_TARGET=dev "$PYTHON" -m pytest e2e/ -v -rs -m "not corpus"
  fi

  # **The evaluation set is opt-in, and a skip is announced rather than silent.** It is the only
  # part of a deploy that spends the model budget, and a deploy runs many times a day, so paying
  # for it by default spends a finite budget on changes that could not have moved an answer. A
  # tier that did not run must never read like one that passed — hence the message below, rather
  # than an absence of output that looks the same as success.
  if [[ "${EVAL:-0}" -eq 1 ]]; then
    # The evaluation set's own stack, which the `up` above did not start: its two services sit
    # behind a Compose profile so that nothing scored here is part of what the bot talks to.
    #
    # **`--build`, and it is load-bearing.** The build above runs without `--profile eval`, so
    # Compose skips this service entirely; and `backend-eval` declares a build context with no
    # `image:`, so its tag is its own rather than the serving backend's. Started without `--build`
    # it comes up on whatever image a previous deploy left behind — which is a gate reporting
    # green on code it never ran, the failure that makes every other number here meaningless.
    say "Starting the evaluation stack"
    docker compose --profile eval up -d --build backend-eval

    say "Running the evaluation set"
    MB_E2E_TARGET=dev "$PYTHON" -m pytest eval/ -v -rs
  else
    say "Evaluation set skipped: it was not asked for. Nothing scored retrieval, grounding or
    the answers in this run, so this deploy says nothing about whether they got worse. Pass
    --eval when the change could have moved them; it spends about four dollars."
  fi
fi

if [[ ${#SERVICES[@]} -gt 0 ]]; then
  say "Dev deploy complete. Selective, so it finishes no card: a sweep needs a stack-wide
    deploy, since rebuilding one service says nothing about a merged change to another."
else
  say "Dev deploy complete. Workstream 0 runs here and nowhere else, so a stack-wide dev
    deploy from a clean origin/main is what finishes its Merged cards; later workstreams
    run on the office machine and wait for production."
fi
say "This script does not touch the board. The toolkit's deploy role sweeps it, and holds
    the conditions that make the sweep honest."
