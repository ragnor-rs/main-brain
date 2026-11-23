#!/usr/bin/env bash
# Prints one non-negative integer and nothing else: how many end-to-end cases this tree holds.
#
# Collection only — no test body runs, no stack is opened and no model is called, so this costs
# nothing. The count cannot be derived by reading the files: cases are parametrised, so the
# functions a search finds are fewer than the cases a run would execute.
#
# Given a file it counts the cases in that collection instead of collecting, which is how the
# counting is checked without invoking the runner. ⚠️ A relative path resolves against the
# repository root rather than the caller's directory, this script changing to the root first.
#
# ⛔ Fails loudly rather than printing a count it did not establish. A zero from collection that
# never ran would stand the floor down silently: the reader judges a run against the declaration,
# and a tree declaring nothing has nothing to judge — so a broken collection would read as a tier
# nobody needs to check rather than as a tier nobody checked.
set -uo pipefail

# ⛔ Exits rather than carrying on: counting from the wrong directory is a tree declaring no cases.
cd "$(dirname "$0")/.." || exit 1

if [ "$#" -gt 0 ]; then
  collected=$(cat "$1") || exit 1
else
  collected=$(python3 -m pytest e2e/ --collect-only -q 2>/dev/null)
  status=$?
  # 5 is this runner's "no tests collected", which is a tree that genuinely holds none and is a
  # real declaration of zero. Every other non-zero status is collection that did not finish.
  if [ "$status" -ne 0 ] && [ "$status" -ne 5 ]; then
    printf 'collection did not run (status %s); nothing was counted\n' "$status" >&2
    exit "$status"
  fi
fi

count=$(printf '%s' "$collected" |
  grep -c -E '^e2e/test_[A-Za-z0-9_]+\.py::' || true)

# ⛔ A collection that named cases and matched none of them is a shape this counter does not
# understand, not a tree holding nothing — a capture taken from inside the suite directory carries
# no prefix to anchor on, and would otherwise come back as a confident zero that also validates a
# wrong pattern wherever this path is used to check the counting.
if [ "${count:-0}" -eq 0 ] && printf '%s' "$collected" | grep -q '::'; then
  printf 'the collection names cases but none in the expected form; nothing was counted\n' >&2
  exit 1
fi

printf '%s\n' "${count:-0}"
