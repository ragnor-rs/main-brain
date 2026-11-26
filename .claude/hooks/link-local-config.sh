#!/usr/bin/env bash
# Link the toolkit's two untracked files into a working tree, so its configuration loads there.
#
# `.claude/mjolnir.yaml` is committed, but it names blocks and a person map that are supplied from
# files `.gitignore` excludes: `.claude/mjolnir.private.yaml` and `.claude/mjolnir.people.yaml`. A
# named block is read at load, so a tree without the first file has no configuration at all, and
# a tree is a fresh checkout that carries neither. Each is linked from the primary checkout rather
# than copied, so there is one file per machine and an edit reaches every tree.
#
# Wired from `.claude/settings.json` as PostToolUse on EnterWorktree, which is how a session comes
# to stand in a tree; run by hand with a tree's path as the one argument for a tree entered any
# other way. Links only what is missing, never replaces a file already there, and does nothing in
# the primary checkout.
#
# Prints a systemMessage when it links something and exits 0 otherwise; a PostToolUse hook must
# never fail the tool call it follows.
set -uo pipefail

FILES=(".claude/mjolnir.private.yaml" ".claude/mjolnir.people.yaml")

tree="${1:-}"
if [[ -z "$tree" ]]; then
  # The hook's input carries the session's working directory, which is the tree just entered.
  tree=$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("cwd", ""))' 2>/dev/null || true)
fi
[[ -n "$tree" && -d "$tree" ]] || exit 0

top=$(git -C "$tree" rev-parse --show-toplevel 2>/dev/null) || exit 0
common=$(git -C "$tree" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || exit 0
primary=$(dirname "$common")
[[ "$top" == "$primary" ]] && exit 0

linked=()
for file in "${FILES[@]}"; do
  if [[ -e "$primary/$file" && ! -e "$top/$file" && ! -L "$top/$file" ]]; then
    ln -s "$primary/$file" "$top/$file" && linked+=("$file")
  fi
done

if (( ${#linked[@]} )); then
  printf '{"systemMessage": "Linked into %s: %s"}\n' "$top" "${linked[*]}"
fi
exit 0
