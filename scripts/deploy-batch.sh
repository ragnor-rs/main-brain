#!/usr/bin/env bash
# Which of the cards handed to it a deploy finishes: the `batch` command of the deploy targets in
# `.claude/mjolnir.yaml`. The toolkit hands it, one per line on its input, every card in `Merged`,
# and advances to `Done` only what this prints that the board still holds in `Merged`.
#
#   scripts/deploy-batch.sh <target> <services>
#
# Two things decide it, and a card has to pass both:
#   - The deploy was stack-wide. A deploy of one service rebuilds that service and nothing else,
#     so it cannot make a merged change elsewhere live, and it finishes nothing.
#   - The card's workstream is the one the target serves. Workstream 0 runs on the dev stack and
#     nowhere else, so dev finishes those; the later workstreams run on production. A card carrying
#     no workstream is finished by neither, and is reported as left behind.
#
# Exits nonzero when a card's workstream cannot be read, because a batch that could not be
# assembled is not a batch that carried nothing.
set -uo pipefail

fail() { printf '%s\n' "$*" >&2; exit 1; }

target=${1:-}
services=${2:-}
[[ -n $target && -n $services ]] || fail "usage: deploy-batch.sh <target> <services>"

[[ $services == all ]] || exit 0

case "$target" in
  dev) serves() { [[ $1 == "Workstream 0" ]]; } ;;
  production) serves() { [[ -n $1 && $1 != "Workstream 0" ]]; } ;;
  *) fail "no workstream is mapped to the target $target." ;;
esac

while IFS= read -r card; do
  card=${card#"#"}
  [[ -n $card ]] || continue
  milestone=$(gh issue view "$card" --json milestone --jq '.milestone.title // ""' 2>/dev/null) \
    || fail "could not read the workstream of card $card."
  if serves "$milestone"; then
    printf '%s\n' "$card"
  fi
done
