"""Order a board payload into the items to offer next, one issue number per line.

Reads a `gh project item-list --format json` payload on stdin and is called by
`board-next.sh`, whose header states the contract this implements: what is filtered, what the
order is, and why an empty output is a board with nothing to start rather than an error.

A file rather than a `python3 -c` argument so that `ruff check` reaches it: a sort key and a
truncation check are logic, and until this was a file nothing parsed either before it ran.
"""

import json
import sys

WAITING = {"deferred", "needs info"}

payload = json.load(sys.stdin)
items = payload.get("items") or []

# A limit is a ceiling and not a page: past it the host returns a prefix and says so in the count
# beside the items. ⛔ Refused rather than reported, because the item dropped may be the one that
# should have been offered first, and a short list is indistinguishable from a short board.
total = payload.get("totalCount")
if isinstance(total, int) and len(items) < total:
    sys.stderr.write(
        f"the board holds {total} items and this query returned {len(items)}, so it was "
        "truncated and the item to offer first may be among those dropped. Raise the limit or "
        "page through it.\n")
    raise SystemExit(1)

offered = []
for item in items:
    if item.get("status") != "Todo":
        continue
    labels = set(item.get("labels") or [])
    if labels & WAITING:
        continue
    number = (item.get("content") or {}).get("number")
    if number is None:
        continue
    offered.append((
        0 if "priority:top" in labels else 1,
        (item.get("milestone") or {}).get("title") or "",
        number,
    ))

# An item in no workstream sorts last rather than first, an empty title being the one value that
# would otherwise come before every real one.
for _, _, number in sorted(offered, key=lambda row: (row[0], row[1] == "", row[1], row[2])):
    print(number)
