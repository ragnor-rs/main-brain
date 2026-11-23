#!/usr/bin/env bash
# The board items this project would offer next, most promising first, one issue number per line.
#
# usage: board-next.sh OWNER NUMBER
#
# ⛔ It offers candidates and does not decide takeability. An item here may still be refused when
# it is started: whether anything blocks it is read from the dependency edges by the readiness
# gate, which this does not call, and an item whose blocker is open is routinely first in this
# output. Offering and gating are separate on purpose — duplicating the gate here would be a
# second answer to one question, free to disagree with the one that actually stops the work.
#
# What it does filter is what the board itself records: an item not in Todo, and an item carrying
# a process label that means it is waiting on somebody.
#
# ⛔ The board it reads is an argument and not a default. Which board this project plans on is a
# fact its configuration states, and a copy here would be a second statement free to disagree.
#
# The order: demonstration-visible items first, then the workstreams in their own order, then the
# lowest number — the tie-break being filing order, which is as close to a rank as this board
# keeps. ⚠️ The workstreams are ordered by sorting their own names, which is a property of the
# names this project uses rather than of roman numerals: `Workstream 0` through `Workstream III`
# sort into exactly their own sequence, so no list of them is written here. ⛔ The first name that
# would break it is one ending `IX`, which sorts before one ending `V`.
#
# Prints nothing when nothing is on offer, which is a board with nothing to start rather than an
# error. A reader that cannot tell those apart is the failure this exits zero to avoid arguing
# about: the caller reads the line count.
set -euo pipefail

if [ "$#" -lt 2 ]; then
  printf 'usage: %s OWNER NUMBER\n' "$(basename "$0")" >&2
  exit 2
fi

# ⛔ Resolved and checked before the query, because the query spends an API call: a program this
# cannot find is refused here rather than after `gh` has already run, and with a code of its own
# rather than the 2 above, which means a usage error. ⚠️ The resolution is the invocation path and
# is not followed through a symlink, so this is run where it lives — reached through a link, or fed
# to `bash` on stdin, it refuses by name rather than working, which is the bound being stated
# rather than defended.
program="$(dirname "${BASH_SOURCE[0]:-$0}")/board-next.py"
if [ ! -f "$program" ]; then
  printf 'board-next.sh: no board-next.py beside it; looked for %s\n' "$program" >&2
  exit 3
fi

gh project item-list "$2" --owner "$1" --format json --limit 300 | python3 "$program"
