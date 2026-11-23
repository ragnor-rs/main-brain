#!/usr/bin/env bash
# Release what merged work still holds: a Compose stack and a worktree each, so both slots
# turn over. Also prunes what earlier runs left behind — stale worktree records, and stacks
# whose directory is gone.
#
# **This is the containers' half and it is the only half nothing shared reaches.** The toolkit's
# own sweep reaps a tree whose branch has landed and knows nothing of Docker, and it is what
# runs at the start of a session — so this one is wired on the merge alone, from
# `.claude/settings.json`: PostToolUse on Bash, gated `if: Bash(gh pr merge:*)`. Two sweeps on
# one event would be two removers of one tree. `branch_model.mechanics` in
# `.claude/mjolnir.yaml` names this file as the project's own teardown.
#
# PostToolUse runs only after a tool succeeds — a failure goes to PostToolUseFailure, which is
# not wired — and merged-ness is read from the pull request rather than from the command's
# outcome, so the sweep asks GitHub for the truth either way. A merge this hook never saw, in
# another session or in the web interface, leaves its stack up until somebody deploys over it
# or brings it down; the tree it held is the shared sweep's.
#
# Prints a systemMessage when it acts and exits 0 silently otherwise; a PostToolUse hook must
# never fail the tool call it follows.
#
# Why this exists: a merged branch's stack was once still up three hours later, holding the
# one TDLib session lock and the bot token, which blocked another card's end-to-end tier
# entirely. Nothing dropped it because nothing was watching for the merge.
#
# Three guards that matter more than the action:
#   - NEVER `-v`. The volume is that worktree's store; `down` without it is reversible with
#     `docker compose up`. Deleting a dev database is a separate cleanup and stays the user's
#     to ask for — a detached volume looks identical to garbage and is not.
#   - NEVER the primary checkout's stack, by directory OR by project name. It holds the
#     corpus and its ingest must keep reading; downtime after a finished backfill is permanent
#     loss. A worktree that was deployed under the primary's project name is that same stack,
#     so it is reported rather than dropped.
#   - NEVER remove a worktree whose stack is still up. The containers would outlive the
#     directory holding their compose file, which is how a stack becomes unreachable.
set -uo pipefail

WORKTREE_MARK="/.claude/worktrees/"
notes=()

HOOK_DIR=${BASH_SOURCE[0]%/*}

# `${var//[[:space:]]/}` is quadratic in bash 3.2, and this hook is handed whatever command
# the call made; a glob answers the same question in constant time.
is_blank() { case "$1" in *[![:space:]]*) return 1 ;; *) return 0 ;; esac; }

payload=$(cat 2>/dev/null || printf '')
cmd=$(printf '%s' "$payload" | jq -r '.tool_input.command // ""' 2>/dev/null || printf '')

# A merge trigger must be a `gh pr merge` the call actually runs. `command-heads.py` is what
# tells that from a comment body quoting the command — and a pull-request comment naming the
# next step is routine here, while a sweep that drops stacks and removes directories is not
# something to run on a mention. SessionStart carries no command at all and is the prune
# trigger; anything else is somebody else's Bash call.
if ! is_blank "$cmd"; then
  heads=""
  if command -v python3 >/dev/null 2>&1 && [[ -r $HOOK_DIR/command-heads.py ]]; then
    heads=$(printf '%s' "$cmd" | python3 "$HOOK_DIR/command-heads.py" 2>/dev/null || printf '')
  fi
  is_blank "$heads" && exit 0
  printf '%s\n' "$heads" | command grep -qE '^gh[[:space:]]+pr[[:space:]]+merge([[:space:]]|$)' || exit 0
fi

cwd=$(printf '%s' "$payload" | jq -r '.cwd // ""' 2>/dev/null || printf '')
is_blank "$cwd" && cwd="$PWD"
git -C "$cwd" rev-parse --git-dir >/dev/null 2>&1 || exit 0

primary=""
if common=$(git -C "$cwd" rev-parse --path-format=absolute --git-common-dir 2>/dev/null); then
  primary=$(dirname "$common")
fi
[[ -n ${primary:-} ]] || exit 0
primary_project=$(basename "$primary" | tr '[:upper:]' '[:lower:]')

have_docker() { docker version --format '{{.Server.Version}}' >/dev/null 2>&1; }

# The Compose projects running out of one directory, read off the containers' own labels
# rather than guessed from the directory name. Compose derives the name from the directory,
# but a deploy may override it, and a stack that cannot be named cannot be dropped.
#
# Two spellings of the same directory are accepted, because a label records the path the
# shell that ran `up` had and git reports the one it resolved: the path as given and its
# physical form with symlinks resolved. Nothing looser — matching on the worktree-relative
# tail would make a second clone's identically-named worktree a match, and bringing down
# another checkout's live stack is worse than failing to find this one.
stacks_of() {
  local dir="$1" phys
  phys=$(cd "$dir" 2>/dev/null && pwd -P) || phys=""
  [[ -n ${phys:-} ]] || phys="$dir"
  docker ps \
    --format '{{.Label "com.docker.compose.project.working_dir"}}|{{.Label "com.docker.compose.project"}}' \
    2>/dev/null \
    | awk -F'|' -v d1="$dir" -v d2="$phys" '$2 != "" && ($1 == d1 || $1 == d2) {print $2}' \
    | sort -u
}

project_is_up() { [[ -n $(docker ps --filter "label=com.docker.compose.project=$1" --format '{{.Names}}' 2>/dev/null) ]]; }

# Bring one directory's stacks down. Containers only — the volume is that worktree's store and
# `down` without `-v` is reversible, while deleting a dev database is a separate cleanup that
# stays the user's to ask for. Returns 1 if anything is still up afterwards, which is what
# stops the worktree being removed from under it.
drop_stacks_of() {
  local dir="$1" from="$2" project still=0
  # An unreachable daemon is "cannot tell", not "nothing is up": the containers may be about
  # to come back under a restart policy, and removing the directory would delete the compose
  # file out from under them. So this reports and refuses rather than clearing the way.
  if ! have_docker; then
    notes+=("could not reach Docker, so what $(basename "$dir") still holds is unknown — left alone")
    return 1
  fi
  while IFS= read -r project; do
    is_blank "$project" && continue
    if [[ $project == "$primary_project" ]]; then
      notes+=("left $project up — that is the primary checkout's stack, whose ingest must keep reading; bringing it down is yours to decide")
      still=1
      continue
    fi
    ( cd "$from" && docker compose -p "$project" down ) >/dev/null 2>&1
    if project_is_up "$project"; then
      notes+=("stack $project still has containers up — bring it down with: docker compose -p $project down")
      still=1
    else
      notes+=("dropped the stack $project (containers only; its volume ${project}_postgres-data is kept and removable with docker volume rm)")
    fi
  done < <(stacks_of "$dir")
  (( still == 0 ))
}

# owner/name for the GitHub API, derived rather than hard-coded so a fork or a different
# clone path needs no edit here.
repo_slug() {
  local url slug
  url=$(git -C "$cwd" remote get-url origin 2>/dev/null) || return
  url=${url%.git}
  slug=$(printf '%s' "$url" | awk -F'[:/]' 'NF>1 {print $(NF-1)"/"$NF}')
  printf '%s' "$slug" | command grep -qE '^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$' || return
  printf '%s' "$slug"
}

# Every worktree whose branch has a MERGED pull request.
#
# **Git ancestry cannot answer this and neither can a tree comparison.** This repository
# squash-merges, so the branch's own commits are never ancestors of the default branch, and
# `diff base...branch` stays non-empty for a branch that merged weeks ago — a first version of
# this function read every merged worktree as live for exactly that reason. The pull request's
# `mergedAt` is the authoritative answer, so that is what gets asked.
#
# With no `gh`, no slug, or an API that errors, this yields nothing: a removal must never
# happen on a guess.
merged_worktrees() {
  local slug
  command -v gh >/dev/null 2>&1 || return
  slug=$(repo_slug) || return
  [[ -n ${slug:-} ]] || return

  git -C "$primary" worktree list --porcelain 2>/dev/null \
    | awk '/^worktree /{w=$2} /^branch /{print w"\t"$2}' \
    | while IFS=$'\t' read -r dir ref; do
        case "$dir" in *"$WORKTREE_MARK"*) ;; *) continue ;; esac
        branch=${ref#refs/heads/}
        is_blank "$branch" && continue
        merged=$(gh pr list -R "$slug" --head "$branch" --state merged --limit 1 \
          --json mergedAt --jq '.[0].mergedAt // ""' 2>/dev/null)
        is_blank "$merged" && continue
        printf '%s\n' "$dir"
      done
}

# Stacks running out of a worktree directory that no longer exists. Nothing else can find
# these: the sweep above walks registered worktrees, and a removed one is registered nowhere,
# while the containers keep their working_dir label and keep holding the cap.
orphan_stacks() {
  have_docker || return
  docker ps \
    --format '{{.Label "com.docker.compose.project.working_dir"}}' 2>/dev/null \
    | sort -u \
    | while IFS= read -r dir; do
        is_blank "$dir" && continue
        case "$dir" in *"$WORKTREE_MARK"*) ;; *) continue ;; esac
        [[ -d $dir ]] && continue
        printf '%s\n' "$dir"
      done
}

# Stale records for directories somebody removed by hand; this is what keeps `worktree list`
# honest for the sweep above. `--expire now` is required rather than cosmetic: a bare prune
# keeps a record for three months by default, so the directory is gone and the record that
# hides it from `worktree remove` is not. Every worktree here lives inside the repository on
# the same disk, so a missing directory means removed rather than unmounted.
# `--verbose` reports on stderr, so a `2>/dev/null` here counts nothing and prunes nothing.
pruned=$(git -C "$primary" worktree prune --verbose --dry-run --expire now 2>&1 | command grep -c .)
if (( pruned > 0 )); then
  git -C "$primary" worktree prune --expire now 2>/dev/null
  notes+=("pruned $pruned stale worktree record(s)")
fi

while IFS= read -r wt; do
  is_blank "$wt" && continue
  drop_stacks_of "$wt" "$wt" || { notes+=("left the worktree $(basename "$wt") in place, for the reason just given"); continue; }
  # Removing the directory the session is standing in would leave its cwd dangling and every
  # later command failing, so the current worktree is reported rather than removed. Leaving
  # it is the session's own step — the isolation is its to drop.
  if [[ $wt == "$cwd" || $cwd == "$wt"/* ]]; then
    notes+=("this session is inside $wt, which has merged — leave it, then: git -C $primary worktree remove $wt")
    continue
  fi
  if git -C "$primary" worktree remove "$wt" 2>/dev/null; then
    notes+=("removed the merged worktree $(basename "$wt")")
  else
    notes+=("could not remove $wt automatically — it has uncommitted or untracked files; check it by hand")
  fi
done < <(merged_worktrees)

# The compose file lives in the directory these stacks have lost, so the primary checkout's
# copy is what `down` is run from; the project name comes from the containers themselves.
while IFS= read -r gone; do
  is_blank "$gone" && continue
  notes+=("$(basename "$gone") is gone but its stack was still up")
  drop_stacks_of "$gone" "$primary" || true
done < <(orphan_stacks)

(( ${#notes[@]} )) || exit 0
# Both channels, because they reach different readers and only one of them reaches each: the
# user sees `systemMessage`, the session sees `additionalContext` — and a session that has
# just had a directory removed under it needs to be told, or its next command runs in a path
# that no longer exists.
event=$(printf '%s' "$payload" | jq -r '.hook_event_name // ""' 2>/dev/null || printf '')
is_blank "$event" && event="PostToolUse"
jq -n --arg m "Worktree and stack sweep: $(printf '%s; ' "${notes[@]}")" --arg e "$event" '{
  systemMessage: $m,
  hookSpecificOutput: { hookEventName: $e, additionalContext: $m }
}'
