#!/usr/bin/env bash
# Prints what an end-to-end run executed and what it skipped, as lines of these kinds:
#
#   executed <n>
#   skipped <n> <what those cases wanted>
#
# usage: e2e-report.sh [--from-report FILE] [runner arguments...]
#
# With --from-report it translates a report already captured and runs nothing, which is how the
# translation is checked without paying for a run. ⚠️ A relative path there resolves against the
# repository root rather than the caller's directory, this script changing to the root first. Otherwise it runs the suite and translates what
# comes back; ⚠️ that spends — a model call per question the suite puts to the stack — and needs a
# deploy, a credentialled account and an index that has caught up.
#
# ⛔ Any other argument is forwarded to the runner rather than interpreted here, because the one
# thing this script must not decide is which cases to select. A run against an index that has not
# caught up returns nothing for the cases that need material, and the reply is the one every
# failure on the answer path renders, so the suite blames the answer path for a stack that had not
# finished starting. Deselecting those cases is the caller's decision because only the caller knows
# whether the index is ready: pass `-m "not corpus"` for a cold one. A bare invocation runs
# everything, which is correct for the precondition this script's caller declares.
#
# ⛔ Never prints a count it did not establish. A report that could not be read, and a runner that
# did not get as far as a summary, both come back as nothing printed and a non-zero exit — because
# `executed 0` is the one answer that would be believed: it reads as a tier that ran and proved
# nothing, which is exactly the finding this output exists to make. A run in which every case
# skipped does reach a summary, and that one really is `executed 0`.
set -uo pipefail

# ⛔ Resolved before the `cd` and checked, because the `cd` is to a parent and a parent always
# exists: landed somewhere that is not the root it would succeed and the translation would then be
# missing, which is a refusal of its own rather than a count this script failed to print.
program="$(dirname "${BASH_SOURCE[0]:-$0}")/e2e-report.py"
if [ ! -f "$program" ]; then
  printf 'e2e-report.sh: no e2e-report.py beside it; looked for %s\n' "$program" >&2
  exit 3
fi
program=$(cd "$(dirname "$program")" && pwd)/e2e-report.py

cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." || exit 1

if [ "${1:-}" = "--from-report" ]; then
  if [ "$#" -lt 2 ]; then
    printf 'usage: %s [--from-report FILE] [runner arguments...]\n' "$(basename "$0")" >&2
    exit 2
  fi
  report=$(cat "$2") || exit 1
else
  report=$(MB_E2E_TARGET=dev python3 -m pytest e2e/ -v -rs "$@" 2>&1)
fi

printf '%s' "$report" | python3 "$program"
