#!/usr/bin/env bash
# The Compose stack a working tree started, for the toolkit's tree removal: the four commands
# `branch_model.teardown`, `resource_up`, `resources_running` and `resource_stop` in
# `.claude/mjolnir.yaml` name. The toolkit runs each at the main checkout, with the tree in
# `MJOLNIR_TREE` and, for `stop`, the stack in `MJOLNIR_RESOURCE`.
#
#   stack.sh down     bring the tree's stacks down              (teardown)
#   stack.sh up       print each of the tree's stacks still up  (resource_up)
#   stack.sh list     print every tree's running stack          (resources_running)
#   stack.sh stop     bring one stack down                      (resource_stop)
#
# A stack is identified by its containers' `working_dir` label, never by a name derived from the
# directory: a deploy may override the project name, and a stack nobody can name cannot be dropped.
# Two spellings of a directory are accepted — as given, and with symlinks resolved — and nothing
# looser, since a suffix match would reach another clone's identically named tree.
#
# Three guards matter more than the action:
#   - NEVER `-v`. The volume is that tree's store, and `down` without it is undone by `up`.
#     Deleting a dev database is a separate cleanup and stays a person's to ask for.
#   - NEVER the main checkout's stack, by directory or by project name. It holds the corpus and its
#     ingest must keep reading. A tree deployed under the main checkout's project name is that same
#     stack, so it is reported and the removal is held rather than the stack dropped.
#   - An unreachable daemon is "cannot tell", never "nothing is up". The services restart unless
#     stopped, so containers may come back with the daemon, and removing the tree would delete the
#     compose file from under them. Every subcommand exits nonzero on it, which keeps the tree.
set -uo pipefail

WORKTREE_MARK="/.claude/worktrees/"

fail() { printf '%s\n' "$*" >&2; exit 1; }

have_docker() { docker version --format '{{.Server.Version}}' >/dev/null 2>&1; }

main_checkout() {
  local common
  common=$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || return 1
  dirname "$common"
}

# Every running stack as `<working_dir><TAB><project>`, one per line.
running() {
  docker ps \
    --format '{{.Label "com.docker.compose.project.working_dir"}}	{{.Label "com.docker.compose.project"}}' \
    | awk -F'\t' '$1 != "" && $2 != ""' | sort -u
}

stacks_of() {
  local dir="$1" phys listing
  phys=$(cd "$dir" 2>/dev/null && pwd -P) || phys="$dir"
  listing=$(running) || return 1
  printf '%s\n' "$listing" | awk -F'\t' -v d1="$dir" -v d2="$phys" '$1 == d1 || $1 == d2 {print $2}'
}

project_is_up() {
  [[ -n $(docker ps --filter "label=com.docker.compose.project=$1" --format '{{.Names}}') ]]
}

# Bring one project down, from the tree while it is still there — its compose file is the one the
# stack was brought up from, and a service only its branch declares is one the main checkout's file
# does not know — and from the main checkout once the tree is gone. The project name comes from the
# containers themselves.
drop() {
  local project="$1" root="$2" from="$root" primary_project
  primary_project=$(basename "$root" | tr '[:upper:]' '[:lower:]')
  if [[ $project == "$primary_project" ]]; then
    fail "left $project up: it is the main checkout's stack, whose ingest must keep reading. Bringing it down is a person's decision."
  fi
  [[ -f $MJOLNIR_TREE/docker-compose.yml ]] && from="$MJOLNIR_TREE"
  ( cd "$from" && docker compose -p "$project" down ) >/dev/null 2>&1
  if project_is_up "$project"; then
    fail "stack $project still has containers up; bring it down with: docker compose -p $project down"
  fi
  printf 'dropped the stack %s (containers only; its volumes are kept)\n' "$project"
}

need_tree() {
  [[ -n ${MJOLNIR_TREE:-} ]] || fail "MJOLNIR_TREE is not set, so there is no tree to read."
}

need_docker() {
  have_docker || fail "Docker is unreachable, so what the tree still holds is unknown; the tree is kept."
}

case "${1:-}" in
  down)
    need_tree
    need_docker
    root=$(main_checkout) || fail "not inside a repository, so the main checkout is unknown."
    projects=$(stacks_of "$MJOLNIR_TREE") || fail "could not list the running containers."
    # The first stack that cannot be dropped ends the run nonzero, which keeps the tree; the
    # toolkit runs this again on the next removal, from the top.
    while IFS= read -r project; do
      [[ -n $project ]] || continue
      drop "$project" "$root"
    done <<< "$projects"
    ;;
  up)
    need_tree
    need_docker
    stacks_of "$MJOLNIR_TREE" || fail "could not list the running containers."
    ;;
  list)
    need_docker
    listing=$(running) || fail "could not list the running containers."
    printf '%s\n' "$listing" | awk -F'\t' -v m="$WORKTREE_MARK" 'index($1, m) > 0'
    ;;
  stop)
    need_tree
    [[ -n ${MJOLNIR_RESOURCE:-} ]] || fail "MJOLNIR_RESOURCE is not set, so there is no stack to stop."
    need_docker
    root=$(main_checkout) || fail "not inside a repository, so the main checkout is unknown."
    drop "$MJOLNIR_RESOURCE" "$root"
    ;;
  *)
    fail "usage: stack.sh down|up|list|stop"
    ;;
esac
