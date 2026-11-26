#!/usr/bin/env bash
# What a working tree needs before it can be deployed from, which `branch_model.mechanics` in
# `.claude/mjolnir.yaml` names and the starting role runs once the tree is cut and entered.
#
#   scripts/tree-setup.sh [<tree>]     the tree defaults to the one the call is made in
#
# One step: the main checkout's `.env` is linked into the tree. `.env` is git-ignored, so a tree is
# cut without it and the deploy refuses on its first precondition. It is linked rather than copied
# so the machine holds one file and an edit reaches every tree; a copy would drift, and would put a
# second copy of every secret on the disk.
#
# A tree that already holds a `.env`, as a file or a link, is left as it is, and the main checkout
# itself is never touched, so this is safe to run again.
set -uo pipefail

fail() { printf '%s\n' "$*" >&2; exit 1; }

tree=${1:-.}
top=$(git -C "$tree" rev-parse --show-toplevel 2>/dev/null) || fail "$tree is not inside a repository."
common=$(git -C "$tree" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) \
  || fail "could not read the main checkout of $top."
main=$(dirname "$common")

if [[ $top == "$main" ]]; then
  printf 'nothing to set up: %s is the main checkout\n' "$top"
  exit 0
fi

if [[ -e $top/.env || -L $top/.env ]]; then
  printf 'left %s/.env as it is\n' "$top"
  exit 0
fi

[[ -f $main/.env ]] || fail "the main checkout holds no .env to link; copy .env.example there first."

ln -s "$main/.env" "$top/.env" || fail "could not link $main/.env into $top."
printf 'linked %s/.env -> %s/.env\n' "$top" "$main"
