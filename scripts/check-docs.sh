#!/usr/bin/env bash
# Assertions over the documents, which are most of what this repository ships.
#
# Two kinds work and one does not. **Computed** checks derive the property — count the
# cells in a table, resolve a link, look for a Cyrillic codepoint — and cannot be
# fooled by a reword. **Forbidding** checks assert a name or a pattern must not appear,
# and are sound when the thing forbidden is stable (a deleted module name, an audit-trail
# word). **Quoting** a sentence back at the file it came from is the kind that fails: it
# goes red on an innocent reword and green when the claim is reworded into its opposite.
#
# So: when adding a check, write the violation first and confirm this script goes red on
# it. An assertion written before its break tends to match only the break its author
# imagined. And when one goes red, the assertion is as likely to be wrong as the document.
#
# Run from anywhere in the working tree.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1

# No pattern below may match a multibyte character (an em dash, a box-drawing rule) with
# `.`: under a C locale that is several bytes and the match silently fails, so the check
# passes for a reason that has nothing to do with the documents. Use `.*` across it, or
# match the words either side.

DOCS=(README.md CLAUDE.md docs/architecture.md docs/requirements.md docs/decisions.md docs/process.md)
fail=0
skipped=0
# A fixed path under /tmp is a file two concurrent runs write over each other, and
# parallel worktrees are the documented way of working here.
HITS="$(mktemp)"
trap 'rm -f "$HITS"' EXIT

ok()   { printf '  PASS  %s\n' "$1"; }
bad()  { printf '  FAIL  %s\n' "$1"; fail=1; }
skip() { printf '  SKIP  %s\n' "$1"; skipped=1; }

# A document that moved or was renamed must fail loudly; otherwise every assertion over
# it silently reports PASS and the gate goes on being quoted as evidence.
for f in "${DOCS[@]}"; do
  [ -r "$f" ] || { bad "a governed document is missing: $f"; }
done

# absent <label> <regex> : regex must match nothing in DOCS.
# grep exits 2 on a malformed pattern or an unreadable file. Treating that as "no match"
# is what turns a typo into an assertion that can never fail again.
absent() {
  grep -rIn -E "$2" "${DOCS[@]}" >"$HITS" 2>/dev/null
  local rc=$?
  if   [ "$rc" -gt 1 ]; then bad "$1 (grep could not evaluate this pattern)"
  elif [ "$rc" -eq 0 ]; then bad "$1"; sed 's/^/        /' "$HITS"
  else ok "$1"; fi
}
# present <label> <file> <regex>
present() {
  grep -qIn -E "$3" "$2" 2>/dev/null
  local rc=$?
  if   [ "$rc" -gt 1 ]; then bad "$1 (grep could not evaluate this pattern)"
  elif [ "$rc" -eq 0 ]; then ok "$1"
  else bad "$1 (missing in $2)"; fi
}

echo "1. Scope: the two scope tables agree, and each source has one workstream"
# The defect this group exists for is two documents disagreeing. A per-file grep cannot
# see that by construction, so both checks below read every document at once.
python3 - <<'PYEOF' || fail=1
import re, sys, pathlib

TABLES = {"README.md": None, "docs/decisions.md": None}
bad = []


def scope_table(path):
    """{workstream: (sources, interface)} from the scope table, ignoring extra columns."""
    rows = {}
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        m = re.match(r"\|\s*\*\*(0|I{1,3})\*\*\s*\|(.+)\|\s*$", line.strip())
        if m:
            cells = [c.strip() for c in m.group(2).split("|")]
            rows[m.group(1)] = (cells[0], cells[1]) if len(cells) >= 2 else None
    return rows


def norm(cell):
    """Compare meaning, not punctuation: a cell may be worded differently per document."""
    cell = re.sub(r"[*`]", "", cell).lower()
    # Two letters, not four: "a web UI" is a different interface and every word in it
    # is short. Drop only words that carry no content.
    stop = {"a", "an", "the", "and", "or", "of", "in", "on", "to", "over", "with",
            "from", "that", "this", "its", "it", "as", "by", "for"}
    return {w for w in re.findall(r"[a-z]{2,}", cell)} - stop


for f in TABLES:
    TABLES[f] = scope_table(f)
    if set(TABLES[f]) != {"0", "I", "II", "III"}:
        bad.append(f"{f}: scope table has rows {sorted(TABLES[f])}, expected 0/I/II/III")

if not bad:
    a, b = TABLES["README.md"], TABLES["docs/decisions.md"]
    for ws in ("0", "I", "II", "III"):
        for idx, what in ((0, "sources"), (1, "interface")):
            left, right = norm(a[ws][idx]), norm(b[ws][idx])
            # Equality, not subset. Tolerating "one says more" is what let the update
            # track sit in one table's interface column and not the other's.
            if left != right:
                bad.append(f"workstream {ws} {what}: only in README {sorted(left - right)}, "
                           f"only in decisions.md {sorted(right - left)}")

print("  PASS  README.md and docs/decisions.md agree on every scope row" if not bad
      else "  FAIL  the two scope tables disagree")
for b_ in bad[:6]:
    print("        " + b_)
sys.exit(1 if bad else 0)
PYEOF
python3 - <<'PYEOF' || fail=1
import re, sys, pathlib
# A source belongs to exactly one workstream, asserted across the whole corpus rather
# than per line — the realistic violation is one document moving a source and another
# not. Attribution matters: a single line may assign several sources to several
# workstreams ("... with workstream I; ClickUp and Excel with II"), so pairing every
# source on a line with every workstream on it reports contradictions that are not there.
# Telegram is excluded: the documents state it occupies three positions by design.
# A source is matched by pattern rather than by its full name, because the documents name
# it in full once and then shorten it: most of the claims that move a source read "Drive
# arrives with workstream II", and a check keyed on "Google Drive" sees none of those. The
# alternations below are the shortenings in live use, each verified to catch a break
# written in it; a wording absent from them is a claim this check cannot see, which is the
# standing weakness of keying on a vocabulary at all.
#
# Two known holes, both measured. "Drive" collides with the English imperative, so a
# clause pairing "Drive CI to green" with one workstream would report a contradiction in
# Google Drive — no such clause exists today, and case-sensitivity does not help, since
# that wording is capitalised too. And a table row whose subject is not itself a source
# name contributes nothing, so an assignment stated only inside such a row is unseen.
SOURCES = {"Google Drive": r"(?:Google )?Drive",
           "LinkedIn": r"LinkedIn|professional network",
           "Email": r"Email|mailboxes",
           "ClickUp": r"ClickUp",
           "Excel": r"Excel|spreadsheets",
           "WhatsApp": r"WhatsApp",
           "Call recordings": r"(?:call )?recordings|Plaud"}
DOCS = ["README.md", "CLAUDE.md", "docs/decisions.md", "docs/requirements.md",
        "docs/architecture.md", "docs/process.md"]
WS = r"workstream (0|I{1,3})\b"
claims, bad = {s: {} for s in SOURCES}, []


def attribute(line):
    """(source, workstream) pairs this line actually asserts."""
    row = re.match(r"\|\s*\*\*([^*|]+)\*\*\s*\|(.*)", line)
    if row:
        # A decision-table row: the first cell is the subject of everything after it.
        subject, rest = row.group(1).strip(), row.group(2)
        for src, pat in SOURCES.items():
            if re.fullmatch(pat, subject, re.I):
                return [(src, w.upper()) for w in re.findall(WS, rest, re.I)]
        return []
    # Prose: a clause assigns at most one workstream, so attribute within the clause.
    pairs = []
    for clause in re.split(r"[;.]", line):
        found = re.findall(WS, clause, re.I)
        if len(found) != 1:
            continue
        for src, pat in SOURCES.items():
            if re.search(rf"\b{pat}\b", clause, re.I):
                pairs.append((src, found[0].upper()))
    return pairs


for f in DOCS:
    for n, line in enumerate(pathlib.Path(f).read_text(encoding="utf-8").splitlines(), 1):
        for src, ws in attribute(line):
            claims[src].setdefault(ws, []).append(f"{f}:{n}")
for src, seen in claims.items():
    if len(seen) > 1:
        where = "; ".join(f"{w} at {v[0]}" for w, v in sorted(seen.items()))
        bad.append(f"{src} claimed for {sorted(seen)} — {where}")
print("  PASS  each source is claimed for exactly one workstream, corpus-wide" if not bad
      else "  FAIL  a source is claimed for more than one workstream")
for b in bad[:4]:
    print("        " + b)
sys.exit(1 if bad else 0)
PYEOF
absent "WhatsApp is not undecided or unresolved" 'WhatsApp is (undecided|unresolved)'
absent "nothing is deferred without a workstream" \
       'deferred within this workstream|designed for, no adapter built'
absent "no document still calls a later workstream the current build" \
       'Workstream I is the current build'
# `Done` is decided by what a change is for, never by whether it ships code — the scaffold
# ships Gradle modules, images and a Compose file and was finished on merge. The absolute
# form has been written twice now and is the easier sentence to reach for, so it is
# forbidden by name: the subject has to stay conditional.
#
# Forbidding, and keyed on the shape of the claim rather than on wordings already deleted:
# an absolute — only, never, every, any — reaching "ships code" inside one sentence. The
# first version enumerated the three sentences that had just been removed, which made it a
# quoting check that passed on all three live instances elsewhere in the repo.
#
# `[^.]` keeps a match inside one sentence, which is what lets a correct use through: a
# document may call a scaffold "a change that ships code serving the build" as long as no
# absolute shares the sentence. Flattened first, because these files are hard-wrapped and
# the phrase lands across a line break as often as not — a line-based `absent` reported
# PASS on the very sentence it forbade, with `stays` and `queued` on two lines.
python3 - <<'PYEOF' || fail=1
import re, sys, pathlib, glob

# `anything` before `any`: the alternation is leftmost-first, and `\bany\b` does not match
# inside `anything`, which is how three live phrasings got through a first attempt.
PAT = (r'(?:only|never)[^.]{0,70}\bships? code\b'
       r'|\b(?:every|anything|any|each|all|no|nothing)\b[^.]{0,40}\bships? code\b[^.]{0,40}'
       r'(?:stays? queued|parks?\b|waits?\b|cannot|can(?:not)? reach|must wait|remains? queued)'
       r'|\bships? code\b[^.]{0,40}only a deploy')
bad = []
for f in ["README.md", "CLAUDE.md"] + sorted(glob.glob("docs/*.md")):
    try:
        text = pathlib.Path(f).read_text(encoding="utf-8")
    except OSError as exc:
        # Loudly: a file that cannot be read is not a file that passed.
        bad.append(f"cannot read {f} ({exc.strerror}) — this assertion could not run")
        continue
    flat = re.sub(r'\s+', ' ', text)
    for m in re.finditer(PAT, flat):
        bad.append(f"{f}: ...{flat[max(0, m.start() - 40):m.end() + 40]}...")

print("  PASS  nothing decides Merged or Done by whether a change ships code"
      if not bad else "  FAIL  something decides Merged or Done by whether a change ships code")
for b in bad:
    print("        " + b)
sys.exit(1 if bad else 0)
PYEOF

# Which deploy finishes a card follows the card's workstream — dev for Workstream 0, which
# runs on the developer's machine and nowhere else, production for the later ones — and the
# sweep selects on the milestone, so an issue without one is finished by neither target.
#
# Two assertions, and both are the quoting kind. That is a deliberate retreat rather than a
# default: polarity is what distinguishes the rule from its opposite, and two attempts to
# derive it both failed. A proximity pattern went red on `A dev deploy, never a production
# deploy, is what finishes a Workstream 0 card` and green on `Only production can finish a
# card, so a dev deploy sweeps nothing` — wrong in both directions at once. Token
# co-occurrence inside a sentence failed differently and worse: `dev`, `Workstream 0` and
# `finish` co-occur in three unrelated places in `docs/process.md`, so deleting both real
# statements of the rule still passed, while rewording them to `Each issue must carry exactly
# one workstream` — which states the rule more strongly — failed. Co-occurrence also assumed a
# sentence splitter this file does not have: splitting flattened text on ". " leaves segments
# that run across headings and bullets, because a bold-terminated sentence ends `.**`.
#
# So the first assertion asks the only question a pattern can answer honestly: does the
# sentence that states the rule still appear, verbatim, in each place that has to state it.
# That catches a deletion, which no forbidding check can, and it is the load-bearing half,
# because the rule is a convention with no other rail. It also goes red on an innocent
# reword, and that cost is accepted rather than worked around: the fix for a red here is to
# re-point the pattern at the new wording in the same commit, never to loosen it until it
# stops complaining.
#
# The second is a re-paste guard. Each pattern is a phrase that was live in a scanned file
# before this rule changed, so it catches the exact text returning by a revert, a bad rebase
# or a copy out of an old pull request. Two candidates were dropped rather than kept: one that
# had never been live anywhere but in this script's own earlier guard, and `dev deploy changes
# nothing`, which reds the true sentence `A selective dev deploy changes nothing on the
# board`. Case-insensitive, because the document wrote its claim sentence-initially and a
# lowercase-only pattern never fired on it.
#
# `scripts/deploy.sh` carries a statement of its own and is asserted like the documents: it
# states the rule in a header comment and in what it prints at the end of every run, and an
# operator reads that line more often than either document.
python3 - <<'PYEOF' || fail=1
import re, sys, pathlib, glob

SCANNED = (["README.md", "CLAUDE.md"] + sorted(glob.glob("docs/*.md"))
           + ["scripts/deploy.sh"])

# Quoting, and labelled as such: the sentence that carries the claim, matched over flattened
# text so the hard wrapping does not decide the outcome. A reword means updating the pattern.
STATES = [
    ("docs/process.md", "a stack-wide dev deploy is what finishes a Workstream 0 card",
     r'stack-wide dev deploy is what puts a Workstream 0 change where it does its job'),
    ("docs/process.md", "a sweep takes the cards of the workstream its environment serves",
     r'sweeps the `Merged` cards of the workstream that environment serves'),
    ("docs/process.md", "every issue carries a workstream",
     r'\*\*Every issue carries one\*\*'),
    ("scripts/deploy.sh", "which target finishes which workstream",
     r'dev # finishes Workstream 0, which runs on this machine and nowhere else'),
    # No `#` in this one: it is inside the closing `say`, not the header comment block.
    ("scripts/deploy.sh", "a stack-wide dev deploy finishes the Workstream 0 cards",
     r'stack-wide dev deploy from a clean origin/main is what finishes its Merged cards'),
]

# Re-paste guard: phrases that were live in a scanned file. Not a reword guard, by design.
WAS_LIVE = (r'only a stack-wide production deploy sweeps'
            r'|sweeps: not dev'
            r'|carries \*\*no milestone\*\*'
            r'|carries none, which is why'
            r'|Omit `--milestone`'
            r'|one per issue at most')

def flatten(path, unreadable):
    try:
        return re.sub(r'\s+', ' ', pathlib.Path(path).read_text(encoding="utf-8"))
    except OSError as exc:
        # Loudly, and filed under the assertion that could not run rather than the other one.
        unreadable.append(f"cannot read {path} ({exc.strerror}) — this assertion could not run")
        return None

missing, pasted = [], []
for f in SCANNED:
    flat = flatten(f, pasted)
    if flat is None:
        continue
    for m in re.finditer(WAS_LIVE, flat, re.IGNORECASE):
        pasted.append(f"{f}: ...{flat[max(0, m.start() - 40):m.end() + 40]}...")

for path, claim, sentence in STATES:
    flat = flatten(path, missing)
    if flat is None:
        continue
    if not re.search(sentence, flat):
        missing.append(f"{path} no longer states that {claim}")

print("  PASS  every place that has to state the deploy-and-milestone rule still states it"
      if not missing else "  FAIL  a place that has to state the deploy-and-milestone rule no longer does")
for b in missing:
    print("        " + b)
print("  PASS  no retired wording of the board rule has been pasted back"
      if not pasted else "  FAIL  a retired wording of the board rule is back verbatim")
for b in pasted:
    print("        " + b)
sys.exit(1 if (missing or pasted) else 0)
PYEOF

echo "2. No third deploy environment is named, anywhere in the repository"
# The documents' own forbidden names, links, tables and box diagrams are held by the toolkit's
# documents facet (`documents` in .claude/mjolnir.yaml), rendered into .mjolnir/documents.py
# and run as its own gate. What stays here is what that facet leaves to a project: checks over
# files beyond the documents, and properties of this project's own subject matter.
# Repo-wide rather than over DOCS: the environment names live in `scripts/deploy.sh` and
# `e2e/` too, and a check scoped to the documents reports PASS while the survivors
# sit outside it. Anchored on the deployment vocabulary, because the bare word is also a verb
# — `git status` before staging. Excludes this file, which has to name what it forbids.
if grep -rIn -E '(--env +staging|staging +(deploy|environment|host)|deploy(ing|ed)? +to +staging|staging +(or|and) +production|dev,? +staging|STAGING_HOST|three environments)' \
     . --exclude=check-docs.sh --exclude-dir=.git --exclude-dir=worktrees \
     --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.venv \
     --exclude-dir=__pycache__ --exclude-dir=.idea >"$HITS"; then
  bad "a third deploy environment is named"; sed 's/^/        /' "$HITS"
else ok "no third deploy environment is named"; fi

echo "3. Module graph is stated and consistent"
# Property: the architecture states the module set, however it words the comment.
# The previous form asserted one literal comment string and went red when the tree
# gained :archtest and the comment was reworded to say so.
python3 - <<'PYEOF' || fail=1
import re, sys, pathlib
# Computed, not quoted: the count the architecture states must equal the modules
# settings.gradle.kts actually declares, minus :archtest which it names separately.
inc = set(re.findall(r'include\("(:[a-z]+)"\)',
                     pathlib.Path("settings.gradle.kts").read_text(encoding="utf-8")))
prod = inc - {":archtest"}
WORDS = {3: "three", 4: "four", 5: "five", 6: "six", 7: "seven", 8: "eight"}
arch = pathlib.Path("docs/architecture.md").read_text(encoding="utf-8")
want = WORDS.get(len(prod), str(len(prod)))
hit = re.search(rf"the {want} modules", arch)
print(f"  PASS  the architecture states the module count ({want})" if hit
      else f"  FAIL  the architecture does not state the {want} modules "
           f"that settings.gradle.kts declares")
sys.exit(0 if hit else 1)
PYEOF
present ":domain depends on nothing" docs/architecture.md ':domain. depends on nothing'
present "ingest cannot reach presentation" docs/architecture.md ':ingest. cannot reach presentation'

# Rule 4's governed set, which later checks read.
GOVERNED=(README.md docs/architecture.md docs/requirements.md docs/decisions.md docs/process.md)

echo "4. The five request shapes each have a home in the layout"
# Computed from both sides, like the file-level check below it, rather than quoting the tree's
# shape list back at itself. The quoting form named the packages in a string here, which made
# this a check on wording: it went red when a shape was renamed and, worse, stayed green while
# naming a shape the build does not have — `offer` sat in that string for as long as it sat in
# the tree, and neither was a package.
python3 - <<'PY' || fail=1
import os, re, sys
arch = open("docs/architecture.md", encoding="utf-8").read()
shapes = "domain/src/main/kotlin/io/github/ragnorrs/mainbrain/domain/answer/shape"
bad, real = [], set()

if not os.path.isdir(shapes):
    bad.append(f"{shapes} does not exist, so this assertion's anchor has moved and it tested nothing")
else:
    real = {n for n in os.listdir(shapes) if os.path.isdir(os.path.join(shapes, n))}
    if not real:
        bad.append(f"{shapes} holds no shape package, so this assertion would pass over nothing")

# The tree writes them as `name/`, several to a line. Read from the shape/ block alone: a bare
# `draft/` elsewhere in the document would otherwise satisfy this.
block = re.search(r"[├└]── shape/.*?(?=\n\s*│?\s*[├└]── \w+\.kt|\n\s*│\n)", arch, re.S)
if block is None:
    bad.append("docs/architecture.md's layout tree no longer has a shape/ block")
else:
    listed = set(re.findall(r"\b(\w+)/", block.group(0))) - {"shape"}
    for name in sorted(real - listed):
        bad.append(f"answer/shape/{name}/ is a shape package and the layout tree does not name it")
    for name in sorted(listed - real):
        bad.append(f"the layout tree names answer/shape/{name}/ and no such package exists")

print(f"  PASS  the layout tree names the {len(real)} shape package(s) that exist, both ways"
      if not bad else "  FAIL  the shape packages and the layout tree disagree")
for b in bad: print("        " + b)
sys.exit(1 if bad else 0)
PY
present "machinery knows no shape" docs/architecture.md 'knows no shape'
# The answer package and the layout tree name the same files. Computed from both sides rather
# than from a list kept here, so neither a file added without being documented nor a line left
# behind by a delete or a rename stays green.
#
# Three things this has to get right, each of which it got wrong first:
#
#   * **The haystack is the `answer/` block, not the whole tree.** The tree names ~70 Kotlin
#     files across every package, so a substring test over all of it passes for a file
#     documented under some other package — `answer/Indexer.kt` would be satisfied by the
#     `Indexer.kt` sitting under `index/`.
#   * **The match is bounded, not a substring.** `Resolver.kt` is a suffix of `ScopeResolver.kt`,
#     `Check.kt` of `CitationCheck.kt`, `Registry.kt` of `ShapeRegistry.kt` — every one of which
#     would pass unanchored while being undocumented.
#   * **Both directions.** Asking only whether the tree names each real file leaves the reverse
#     green, and the reverse is what a delete or a rename produces.
#
# Scoped to the package's own files: everything under `shape/` is covered by the per-shape claim
# above, and listing those individually would go red the moment one shape grows a file.
python3 - <<'PY' || fail=1
import os, re, sys
arch = open("docs/architecture.md", encoding="utf-8").read()
package = "domain/src/main/kotlin/io/github/ragnorrs/mainbrain/domain/answer"
bad, real = [], set()

def branch(line):
    """Where this line's tree connector sits, or None for a line that is not an entry."""
    found = re.search(r"[├└]──", line)
    return found.start() if found else None

lines = arch.splitlines()
start = next((i for i, l in enumerate(lines) if re.search(r"[├└]── answer/", l)), None)
if start is None:
    bad.append("docs/architecture.md's layout tree no longer has an answer/ block")
if not os.path.isdir(package):
    bad.append(f"{package} does not exist, so this assertion's anchor has moved and it tested nothing")

if not bad:
    # The block is every entry indented deeper than `answer/` itself, stopping at the first
    # sibling or shallower entry. Depth, not a line count, because a doc edit moves the latter.
    base, body = branch(lines[start]), []
    for line in lines[start + 1:]:
        here = branch(line)
        if here is not None and here <= base:
            break
        # Everything below `shape/` is covered by the per-shape claim above. Including it would
        # redden this the moment a shape grows a file of its own.
        if "shape/" in line:
            break
        body.append(line)

    listed = set(re.findall(r"\b([A-Z]\w*\.kt)\b", "\n".join(body)))
    real = {n for n in os.listdir(package) if n.endswith(".kt")}
    if not real:
        bad.append(f"{package} holds no Kotlin file, so this assertion would pass over nothing")
    for name in sorted(real - listed):
        bad.append(f"{name} is in answer/ and the layout tree's answer/ block does not name it")
    for name in sorted(listed - real):
        bad.append(f"the layout tree names answer/{name} and the package does not hold it")

print(f"  PASS  the layout tree's answer/ block names the package's {len(real)} file(s), both ways"
      if not bad else "  FAIL  the answer package and the layout tree disagree")
for b in bad: print("        " + b)
sys.exit(1 if bad else 0)
PY

echo "5. Derived layer invariants are stated where they are relied on"
# The checks in this group and the next quote a sentence back at the file it came
# from. They catch a statement being deleted outright and nothing subtler: a red
# here may mean only that the sentence was reworded. Converting them into computed
# checks is work in its own right and is not pretended to have been done.
#
# Weaker still where a phrase has two homes. "Nothing is deleted" heads a section as well
# as Principle 8, so dropping it from either one alone leaves this green; only losing both
# reddens it. Pinning it to the principle would make it a quoting check on one sentence,
# which is the kind the header warns against, so the limit is recorded instead of closed.
present "nothing is deleted" docs/architecture.md 'Nothing is deleted'
present "conflicts order by evidence source time" docs/architecture.md \
        'source time of the evidence'
present "citations validated against the retrieved set" docs/architecture.md \
        'cited identifier was in the retrieved set'
present "recency reads source time" docs/requirements.md \
        'too old read the \*source'

echo "6. Scope, horizon, layering and provenance properties"
# 13a. The demo corpus is the workstream's sources and no more, and every document
# that names it agrees. Anchored on the property — the corpus must match the scope
# table, not on any particular sentence — so it goes red when a source moves.
python3 - <<'PY' || fail=1
import re, sys
bad = []
for f in ["README.md", "CLAUDE.md", "docs/requirements.md"]:
    text = re.sub(r"\s+", " ", open(f, encoding="utf-8").read())
    if not re.search(r"own \**Telegram archive", text):
        bad.append(f"{f}: does not name the demo corpus")
    # A source the current workstream does not ingest must not be named as demo data.
    if re.search(r"own \**Telegram archive\** and \**Google\s*Drive", text):
        bad.append(f"{f}: names a demo source outside the current workstream")
print("  PASS  every document names the same demo corpus" if not bad
      else "  FAIL  demo sources disagree between documents")
for b in bad: print("        " + b)
sys.exit(1 if bad else 0)
PY
# 13b. A stated count of properties must equal the bullets that follow it.
python3 - <<'PY' || fail=1
import re, sys
WORDS = {"one":1,"two":2,"three":3,"four":4,"five":5,"six":6,"seven":7,"eight":8}
bad = []
for f in ["docs/requirements.md","docs/architecture.md"]:
    lines = open(f, encoding="utf-8").read().split("\n")
    for n, line in enumerate(lines):
        m = re.match(r"^(\w+) propert(?:y|ies) (?:hold|holds)", line.strip())
        if not m: continue
        want = WORDS.get(m.group(1).lower())
        if want is None: continue
        got, i = 0, n + 1
        while i < len(lines) and (lines[i].strip() == "" or lines[i].startswith(("- ", "  "))):
            if lines[i].startswith("- "): got += 1
            i += 1
        if got != want:
            bad.append(f"{f}:{n+1}: says {want}, {got} bullets follow")
print("  PASS  every stated property count matches its bullets" if not bad
      else "  FAIL  property count disagrees with the list")
for b in bad: print("        " + b)
sys.exit(1 if bad else 0)
PY
# 13c. The horizon bounds inference, never capture — the index is wider in time.
absent "the horizon does not bound backfill" \
       'bounds backfill|backfill stops at the same point|same value as.{0,30}retrieval recency'
present "capture reaches past the horizon" docs/architecture.md \
        'Capture reaches as far back as|reach further back than either'
# 13d. No deleted module name survives anywhere, including as a table qualifier.
absent "no deleted module name survives as a qualifier" '\((model|core)\)|`model`'
# 13e. The layering check names a package, so it covers both adapter roles.
absent "the layering check is not scoped to source adapters alone" \
       'Telegram type outside a source adapter|never leave a source adapter'
# The rule must name both adapter roles — the interface's and ingestion's — where it is
# specified and where it is summarised, which is the same pair the locale check uses.
# `process.md` points at the testing table rather than restating the rule, so it is not one
# of them. This is a quoting check wearing an alternation: it goes red on a third phrasing
# that is perfectly correct, and green if either sentence is reworded into its opposite. It
# has already had to be re-anchored twice as the sentence moved. Read the document before
# editing the pattern.
for f in CLAUDE.md docs/architecture.md; do
  present "the layering check covers both adapter roles in $f" "$f" \
          "(interface adapter and ingestion.s source adapters|adapter/. and under ingestion.s .source/)"
done
# 13e-ii. The architecture rules are enumerated in one document, not two.
#
# Computed rather than quoting: it counts how many of the rule clauses each governed
# document holds, so a reword of any one clause changes nothing and a second home for the
# list goes red the moment it has three of them. The phrases are the stable half of each
# clause — the thing forbidden — rather than any sentence's wording.
#
# The property is worth a check because losing it is silent. The list lived in two places
# once, and the second copy drifted out of date rather than announcing itself; a merge that
# takes the wrong side of this file restores it with every other gate still green.
RULE_PHRASES=(
  'standing in for a string catalogue'
  'naming a SQL or Postgres type'
  'layer-shaped package'
  'publishing a way to remove a record'
  'driving a browser'
  'composition root per process'
)
rule_homes=()
for f in "${DOCS[@]}"; do
  held=0
  for phrase in "${RULE_PHRASES[@]}"; do
    grep -qIF -- "$phrase" "$f" 2>/dev/null && held=$((held + 1))
  done
  [ "$held" -ge 3 ] && rule_homes+=("$f:$held")
done
if [ "${#rule_homes[@]}" -eq 1 ] && [ "${rule_homes[0]%:*}" = "docs/architecture.md" ]; then
  ok "the architecture rules are enumerated in one document"
elif [ "${#rule_homes[@]}" -eq 0 ]; then
  bad "no document enumerates the architecture rules (expected the testing table in docs/architecture.md)"
else
  bad "the architecture rules are enumerated in more than one document: ${rule_homes[*]}"
fi

# 13f. Every layer that runs a pass has a row in the testing table.
present "the derived layer is tested" docs/architecture.md \
        '\| \*\*Derived layer\*\* \|'
absent "reconciliation is not tested under ingest" \
       'source fixtures.{0,120}Reconciliation is tested'
# 13g. Rule 4, past the words the documents facet forbids. A phrase like "has since dropped"
# describes the world rather than the document, so these patterns target constructions that
# narrate a decision or a change in what we know.
if grep -rIn -E '\bsince been (confirmed|decided|agreed|established|settled|ruled)\b|\breplaces the (deferred|earlier|previous|abandoned) \w+\b' \
     "${GOVERNED[@]}" 2>/dev/null >"$HITS"; then
  bad "documents narrate a change in state or knowledge"; sed 's/^/        /' "$HITS"
else ok "documents narrate no change in state or knowledge"; fi
# 13h. The embeddings sidecar's callers are stated the same way in both places.
present "the sidecar names both callers" docs/architecture.md \
        'from the derived layer.*from the answer path'
# 13i. When the weights arrive. The sidecar fetches and loads them while it starts and stays
# unready until it can answer, so a document with that load happening on a request describes
# what this replaced.
#
# Two arms, because either alone is half a check. The forbidding arm keys on the *shape* of
# the claim — a weight or a model arriving on demand or with a request — rather than on the
# phrasings that were deleted, which is the trap this file's header warns about: an
# enumeration of what was just removed can never fail. The presence arm catches the sentence
# simply going missing, which no forbidding check can see, and it is computed rather than
# quoted — the Egress section is extracted and asked whether it places the sidecar's edge at
# startup, so a reword inside that section passes and a deletion does not.
# Scanned with newlines flattened, one document at a time. `absent` is line-based, and this
# claim is a sentence: in the Egress paragraph "model weights" ends one line and the rest of
# the clause begins the next, so a line-anchored pattern passes on exactly the wording it is
# meant to catch. `[^.]` still cannot cross a full stop, so flattening does not let the two
# halves of the pattern match across neighbouring sentences.
demand='(weights|model)[^.]{0,80}(on demand|on first use|first time a model is loaded)'
on_request='(weights|model|fetch|fetched|load|loads|loaded)[^.]{0,80}(the first request|the first embed|first call|a request arrives|requested)'
found=0
for f in "${DOCS[@]}"; do
  hit="$(tr '\n' ' ' <"$f" | grep -oE "$demand|$on_request" | head -3)"
  [ -n "$hit" ] && { found=1; printf '        %s: %s\n' "$f" "$hit"; }
done
[ "$found" -eq 0 ] && ok "the weights are not said to arrive on demand or with a request" \
                   || bad "a document says the weights arrive on demand or with a request"
egress="$(awk '/^### Egress/{f=1;next} f&&/^### /{exit} f' docs/architecture.md | tr '\n' ' ')"
if [ -z "$egress" ]; then
  bad "the Egress section could not be found in docs/architecture.md"
elif printf '%s' "$egress" \
     | grep -qE 'embeddings sidecar.{0,240}(while it is starting|as it starts|when it starts|at startup)'; then
  ok "the Egress section places the weights fetch at startup"
else
  bad "the Egress section no longer says the sidecar fetches its weights as it starts"
fi

echo "7. Decisions are stated rather than left open"
# 14a. The free-form fallback has a home, and nothing still counts five pipelines.
absent "no document still claims five fixed pipelines" 'five fixed pipelines'
present "the fallback has a package" docs/architecture.md 'freeform/'
absent "shipping the free-form path is no longer an open question" \
       'Whether the free-form path ships'
# 14b. Where the derived layer runs. The container set is computed from
# `docker-compose.yml` and compared against every place the architecture states it — the
# count sentence, the responsibility table, and the layout tree's comment — because a set
# stated in three places is wrong in two of them eventually. The service ids are the
# authority, so a container cannot arrive undocumented or outlive its row.
#
# The two sentence sites are located by their phrasing, which is the weak kind: they go red
# on an innocent reword, and the message says which it is. There is deliberately no check
# that a pass has no container of its own — the architecture says promoting one is a Compose
# service and an environment variable, so forbidding it here would gate a documented change
# on a list of service names somebody would have to guess.
absent "the derived layer's container is not deferred" \
       'Which container they run in is not settled|from wherever it runs|Where the recognition job runs'
present "the derived layer runs somewhere named" docs/architecture.md \
        '\*\*in the backend container\*\*'
python3 - <<'PYEOF' || fail=1
import re, sys, pathlib

bad = []


def read(path):
    try:
        return pathlib.Path(path).read_text(encoding="utf-8")
    except OSError as exc:
        # Loudly: every assertion below would otherwise be vacuous.
        bad.append(f"cannot read {path} ({exc.strerror}) — this assertion could not run")
        return ""


compose = read("docker-compose.yml")
# Everything under the `services:` key at column 0, stopping at the next column-0 key so
# `volumes:` cannot contribute its children as service names.
tail = re.split(r'^services:[ \t]*$', compose, maxsplit=1, flags=re.M)
block = re.split(r'^[A-Za-z_]', tail[1], maxsplit=1, flags=re.M)[0] if len(tail) > 1 else ""
# Compose allows a dot and a leading digit in a service name, so the charset has to as
# well: an id this misses is dropped silently, and every arm below then agrees with a
# document that omits a container. `web.api` and `2fa` are both legal and both got through.
declared = re.findall(
    r'^  [\'"]?([A-Za-z0-9_][A-Za-z0-9._-]*)[\'"]?:[ \t]*(?:#.*)?$\n((?:(?!^  \S).*\n)*)',
    block, re.M)
if not declared:
    bad.append("could not read any service id out of docker-compose.yml")

# Split by whether a service sits behind a Compose profile, because the three arms below
# state the *deployed* stack and a profiled service is not in it: nothing starts it on an
# ordinary `up`. Counting them together would make adding the evaluation stack look like a
# documentation defect, and the document would then be wrong to agree.
#
# The split is also why the fourth arm exists. Without it a profile would be the one place a
# container could be added that no document has to mention, which is exactly the hiding place
# this whole check is here to close.
services = [name for name, body in declared if not re.search(r'^    profiles:', body, re.M)]
profiled = [name for name, body in declared if re.search(r'^    profiles:', body, re.M)]

WORDS = {1: "one", 2: "two", 3: "three", 4: "four", 5: "five", 6: "six", 7: "seven",
         8: "eight", 9: "nine", 10: "ten"}
arch = read("docs/architecture.md")

# 1. The stated count. Weak side: anchored on the phrasing of the sentence that states it.
intro = re.search(r'(\w+) containers under Docker Compose', arch)
if not intro:
    bad.append("docs/architecture.md no longer states how many containers there are "
               "(or the sentence was reworded)")
elif intro.group(1).lower() not in (WORDS.get(len(services)), str(len(services))):
    bad.append(f"docs/architecture.md says {intro.group(1)} containers; "
               f"docker-compose.yml defines {len(services)}")

# 2. The responsibility table, read as the first run of table rows after the heading that
#    introduces it. Anchored on the heading rather than on the count sentence: keyed to the
#    sentence, a reword silently skips this arm and nothing says the table went unchecked.
topology = re.search(r'^## Topology[ \t]*$', arch, re.M)
if not topology:
    bad.append("docs/architecture.md has no Topology section, so the container table "
               "could not be located — this assertion did not run")
else:
    rows_block = re.search(r'((?:^\|.*\n)+)', arch[topology.end():], re.M)
    rows = re.findall(r'^\|\s*\*\*([^*|]+)\*\*\s*\|', rows_block.group(1), re.M) if rows_block else []
    if sorted(r.strip() for r in rows) != sorted(services):
        bad.append(f"the container table has rows for {sorted(r.strip() for r in rows)}; "
                   f"docker-compose.yml defines {sorted(services)}")

# 3. The layout tree's comment, which states the count a third time. EVERY line of that
#    shape has to agree, not the first one found: a second tree elsewhere in the document
#    would otherwise satisfy this while the real one stayed stale.
layouts = list(re.finditer(r'^.*docker-compose\.yml\s+#\s*(.+?)\s*$', arch, re.M))
if not layouts:
    bad.append("docs/architecture.md's layout tree no longer comments docker-compose.yml")
for m in layouts:
    stated = re.search(r'(\w+) services', m.group(1))
    if not stated:
        bad.append(f"a layout comment states no service count: {m.group(1)!r}")
    elif stated.group(1).lower() not in (WORDS.get(len(services)), str(len(services))):
        bad.append(f"a layout comment says {stated.group(1)} services; "
                   f"docker-compose.yml defines {len(services)}")

# 4. Every profiled service is named somewhere in the architecture. A weaker assertion than
#    the three above — it asks only that the name appears, not that a table row describes it —
#    because a service outside the deployed stack has no row in a table about the deployed
#    stack. What it forbids is a container nobody wrote down.
for name in profiled:
    if name not in arch:
        bad.append(f"{name} is a profiled service that docs/architecture.md never names; "
                   "a Compose profile is not a place to keep a container out of the documents")

print("  PASS  every place stating the container set agrees with docker-compose.yml"
      if not bad else "  FAIL  a stated container set disagrees with docker-compose.yml")
for b in bad:
    print("        " + b)
sys.exit(1 if bad else 0)
PYEOF
# 14c. The language claim rests on the model, not on lexical search covering for it.
absent "lexical search is not claimed to cover what embeddings miss" \
       'lexical search carries'
# Property, not phrase: the embeddings row must justify the model on all three
# languages retrieval has to cover. The previous form asserted one sentence verbatim
# and went red on a reword that kept every language it names.
present "the embedding model covers the languages retrieval needs" docs/architecture.md \
        'Georgian and Russian.{0,120}English'
present "unmeasured language coverage is admitted" docs/architecture.md \
        'Georgian retrieval is unmeasured'
absent "Armenian is not a language retrieval must cover" 'Armenian'


echo "8. Cross-document agreement"
# Every assertion in this group exists because a contradiction shipped past a review
# that reported green. They are stated as properties for the reason in the header: the
# versions that missed them were anchored on wording.
python3 - <<'PYEOF' || fail=1
import re, sys, pathlib

DOCS = ["README.md", "CLAUDE.md", "docs/architecture.md", "docs/requirements.md",
        "docs/decisions.md", "docs/process.md"]
TEXT = {f: pathlib.Path(f).read_text(encoding="utf-8") for f in DOCS}
FLAT = {f: re.sub(r"\s+", " ", t) for f, t in TEXT.items()}
ALL = " ".join(FLAT.values())
bad = []


def check(name, cond, detail=""):
    print(f"  {'PASS' if cond else 'FAIL'}  {name}")
    if not cond:
        if detail:
            print("        " + detail)
        bad.append(name)


# Two documents naming different default locales is the defect this group was written
# for. The rule's own home is architecture.md, so both it and the entry point must say it.
# Do not enumerate the phrasings a contradiction might take — extract whichever
# language each sentence names and compare. "The floor is English" slipped past a
# version of this check that knew only "English is the floor".
LANG = r"(?:English|Russian|Georgian|Armenian)"
FLOOR = [
    rf"default locale is \**({LANG})",
    rf"\**({LANG})\** is the floor",
    rf"floor is \**({LANG})",
    rf"keys? (?:must resolve|resolves) in \**({LANG})",
    rf"falls back to \**({LANG})",
    rf"resolves? in \**({LANG})\b",
]
named = {}
for f, t in FLAT.items():
    for pat in FLOOR:
        for m in re.finditer(pat, t):
            named.setdefault(m.group(1), set()).add(f)
check("every document that names a default locale or floor names the same one",
      set(named) <= {"Russian"},
      "; ".join(f"{lang} in {sorted(fs)}" for lang, fs in sorted(named.items())
                if lang != "Russian"))
check("the locale rule is stated where it is specified and where it is summarised",
      {"CLAUDE.md", "docs/architecture.md"} <= named.get("Russian", set()),
      f"stated in {sorted(named.get('Russian', set()))}")

# A rule stated absolutely in one document and conditionally in another binds neither.
#
# The window is the statement, not the file. Scoped per file, this reported PASS on a
# document that stated the rule absolutely and happened to use the words "named
# exception" in unrelated prose elsewhere — which `architecture.md` does twice, in the
# tech-stack table and under "Deliberately not chosen".
#
# A character radius is not enough, and the file already owns the right idiom: `[^.]`
# keeps a match inside one sentence, which is what the `ships code` check above uses. A
# bare 120-character window reports PASS on the text it forbids — plant an absolute
# statement immediately before the unrelated "named exception" in the tech-stack row and
# the qualifier two sentences away satisfies it. So the span is the sentence the statement
# sits in, bounded by a full stop or a table-cell pipe either side.
#
# It reaches both ways, because "with one named exception, every source enters through an
# adapter" states the rule correctly and a forward-only span reds it.
#
# The radius is kept as a cap, so an unpunctuated run cannot widen the span without limit.
# Its limit: a sentence longer than 120 characters before its own qualifier goes red while
# being correct. The live statements name the exception 49 to 82 characters in, and markup
# counts against that — an inline `[`doc`](doc.md)` link spends about 30.
WINDOW = 120
PHRASE = "named exception"
BOUND = re.compile(r"[.|]")
unqualified = []
for f, t in FLAT.items():
    for m in re.finditer(r"[Ee]very source (?:enters|arrives) through an adapter", t):
        lo = max(0, m.start() - WINDOW)
        for b in BOUND.finditer(t, lo, m.start()):
            lo = b.end()                      # last boundary before the statement
        hi = min(len(t), m.end() + WINDOW)
        nxt = BOUND.search(t, m.end(), hi)
        if nxt:
            hi = nxt.start()
        if PHRASE not in t[lo:hi]:
            unqualified.append(
                f"{f}: states the rule, names no exception in the same sentence: "
                f"...{t[m.start():m.start() + 70]}...")
check("every statement of the adapter rule names its exception", not unqualified,
      "; ".join(unqualified))

# Presence, because the check above is vacuous wherever the locator stops matching: it
# enumerates one phrasing, so "each source" or "all sources enter through an adapter"
# drops a document out of coverage silently and the gate stays green on an absolute. The
# locale rule twelve lines down is the model — `decisions.md` names four rules as stated
# in more than one document on purpose, and this was the only one of the four asserted in
# one direction alone.
stated = {f for f, t in FLAT.items()
          if re.search(r"[Ee]very source (?:enters|arrives) through an adapter", t)}
check("the adapter rule is stated where it is specified and where it is summarised",
      {"CLAUDE.md", "docs/architecture.md"} <= stated,
      f"stated in {sorted(stated)}")

# An ordinal is a name that silently retargets when something is inserted ahead of it.
check("no document calls an interface by a positional ordinal",
      not re.search(r"the (second|third) interface", ALL, re.I))

# The property is that nothing requires one — the decision record says plainly that
# none is needed, which a phrase grep would flag.
check("no document requires a speech model",
      not re.search(r"(needs?|requires?|depends on) a speech model", ALL, re.I))

# CLAUDE.md rule 6: this repository is published, so no handle identifies anybody in it.
check("no account handle appears in any document",
      not re.search(r"@[A-Za-z][A-Za-z0-9_]{3,}", ALL))

for b in bad:
    pass
sys.exit(1 if bad else 0)
PYEOF

# The CI job set, against each place that states it. Computed on the job-id side: the ids come
# out of the workflow, so this cannot be satisfied by rewording. The two count sentences are
# matched by their phrasing, which is the weak kind — they go red on an innocent reword, and
# the message says so. Both documents once disagreed with the workflow at the same time and
# nothing noticed, which is what this is for; the two breaks that happened are the two the
# test plan reproduces.
python3 - <<'PYEOF' || fail=1
import re, pathlib, sys

bad = []

def read(path):
    try:
        return pathlib.Path(path).read_text(encoding="utf-8")
    except OSError as exc:
        # Loudly, and not as a pass: every assertion below this would otherwise be vacuous.
        bad.append(f"cannot read {path} ({exc.strerror}) — this assertion could not run")
        return ""

wf = read(".github/workflows/ci.yml")
# Everything after the `jobs:` key at column 0, stopping at the next column-0 key so a
# top-level block added below it cannot contribute its children as job ids.
tail = re.split(r'^jobs:[ \t]*$', wf, maxsplit=1, flags=re.M)
block = re.split(r'^[A-Za-z_]', tail[1], maxsplit=1, flags=re.M)[0] if len(tail) > 1 else ""
# Job ids are the two-space keys in it. Quoted keys and trailing comments are legal YAML, and
# an id may hold an underscore or a capital — a charset narrower than the spec reads as a
# shorter job list, which is a silent pass rather than a red.
jobs = re.findall(r'^  [\'"]?([A-Za-z_][A-Za-z0-9_-]*)[\'"]?:[ \t]*(?:#.*)?$', block, re.M)
if not jobs:
    bad.append("could not read any job id out of .github/workflows/ci.yml")

WORDS = {1: "one", 2: "two", 3: "three", 4: "four", 5: "five", 6: "six", 7: "seven",
         8: "eight", 9: "nine", 10: "ten"}

def count_matches(word, n):
    return word in (WORDS.get(n), str(n))

arch = read("docs/architecture.md")

# 1. The repository-layout comment, which states the set as a `·`-separated list. Read by
#    position — the line naming the workflow — because its names carry no backticks and a
#    search of the whole document finds them in unrelated prose.
layout = re.search(r'^.*\.github/workflows/ci\.yml\s+#\s*(.+?)\s*$', arch, re.M)
if not layout:
    bad.append("docs/architecture.md's layout tree no longer comments the CI jobs")
else:
    listed = [p.strip().strip('`') for p in re.split(r'[·,]', layout.group(1))]
    if sorted(listed) != sorted(jobs):
        bad.append("docs/architecture.md's layout comment lists "
                   f"{listed}; ci.yml defines {sorted(jobs)}")

# 2. The CI table, read as rows rather than as tokens anywhere in the file.
intro = re.search(r'GitHub Actions, no secrets, (\w+) jobs:', arch)
if not intro:
    bad.append("docs/architecture.md no longer states how many CI jobs there are")
else:
    if not count_matches(intro.group(1), len(jobs)):
        bad.append(f"docs/architecture.md says {intro.group(1)} CI jobs; "
                   f"ci.yml defines {len(jobs)}")
    # The first run of table lines after the sentence. A blank line sits between the two, so
    # this cannot be "everything up to the next blank line".
    block = re.search(r'((?:^\|.*\n)+)', arch[intro.end():], re.M)
    rows = re.findall(r'^\|\s*`([^`]+)`\s*\|', block.group(1), re.M) if block else []
    if sorted(rows) != sorted(jobs):
        bad.append(f"docs/architecture.md's CI table has rows for {sorted(rows)}; "
                   f"ci.yml defines {sorted(jobs)}")

# Only the documents are read here. The third reader of this job set is the toolkit's
# `host.checks` facet in `.claude/mjolnir.yaml`. That file is committed, but it loads only with
# an untracked file CI never has, and the toolkit's reader is what resolves it: an assertion here
# would be a second reader of the facet. The toolkit ships the reader that holds the two
# together, and it runs where the configuration loads.

print("  PASS  every document naming the CI jobs agrees with the workflow"
      if not bad else "  FAIL  a document disagrees with the CI workflow")
for b in bad:
    print("        " + b)
sys.exit(1 if bad else 0)
PYEOF

# The architecture states how many rules `:archtest` asserts, and agrees with it.
#
# Computed, and here because the same defect has now landed four times: a gate added to the
# build and to no list anyone works from. Three of those were caught in review rather than by
# anything mechanical, because adding a rule changes no document and so reddens nothing.
#
# **What this catches, and what it does not.** It catches the realistic case — the rule set and
# the enumeration drifting apart in either direction — and it cannot be satisfied by a reword.
# It cannot say *which* rule went unlisted, and two shapes slip past it: a rule deleted and
# another added in the same commit, and a rule renamed. Matching each rule to its sentence
# would need a test-name-to-prose map in this script, which is the list-someone-must-update
# problem moved one file across; the count is the part worth deriving, and its limits are
# stated here rather than left to be discovered.
python3 - <<'PYEOF' || fail=1
import pathlib, re, sys

# The documents spell a count in words, so this has to know the word for it. A number the
# table does not reach falls back to digits and the check then demands a spelling the
# documents do not use — which reads as the architecture having diverged when what actually
# happened is that a rule was added past the end of this list. Carried well past the current
# count for that reason.
WORDS = {8: "eight", 9: "nine", 10: "ten", 11: "eleven", 12: "twelve", 13: "thirteen",
         14: "fourteen", 15: "fifteen", 16: "sixteen", 17: "seventeen", 18: "eighteen",
         19: "nineteen", 20: "twenty", 21: "twenty-one", 22: "twenty-two",
         23: "twenty-three", 24: "twenty-four", 25: "twenty-five", 26: "twenty-six",
         27: "twenty-seven", 28: "twenty-eight", 29: "twenty-nine", 30: "thirty"}
bad = []
rules = 0

# Globbed rather than a tuple of filenames. A named pair is the same
# list-someone-must-update this assertion exists to replace: a third test class would
# contribute zero rules and the count would stay green over a whole group of them.
sources = sorted(pathlib.Path("archtest/src/test/kotlin/io/github/ragnorrs/mainbrain/archtest").glob("*Test.kt"))
if not sources:
    bad.append("found no *Test.kt under archtest — this assertion could not run")

# Any JUnit annotation that declares a case, not `@Test` alone: a rule written as a
# `@ParameterizedTest` or a `@TestFactory` is still a rule, and counting only one spelling
# would miss it while reading as though it counted all of them. `@Disabled` is subtracted
# because a rule that does not run is not a rule the documents should promise.
# The qualifier is optional on both: `@org.junit.jupiter.api.Test` is the same annotation as
# `@Test`, and a pattern that reads only the short form counts one spelling while its name
# claims to count the annotation.
DECLARES = re.compile(r'^\s*@(?:[\w.]+\.)?(Test|ParameterizedTest|RepeatedTest|TestFactory)\b', re.M)
DISABLED = re.compile(r'^\s*@(?:[\w.]+\.)?Disabled\b', re.M)

for path in sources:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        # Loudly rather than as a pass: a count of zero would satisfy nothing honestly.
        bad.append(f"cannot read {path} ({exc.strerror}) — this assertion could not run")
        continue
    rules += len(DECLARES.findall(text)) - len(DISABLED.findall(text))

if not bad:
    if rules <= 0:
        bad.append("counted no enabled archtest rule, so the count proves nothing")
    else:
        want = WORDS.get(rules, str(rules))
        arch = pathlib.Path("docs/architecture.md").read_text(encoding="utf-8")
        # Word or digit, either case. The other two count checks in this script accept both,
        # and demanding the word form alone would make a count outside WORDS unsatisfiable
        # except by writing a digit into prose that is otherwise written in words.
        forms = {want, want.capitalize(), str(rules)}
        if not any(re.search(rf"\b{re.escape(f)} rules\b", arch) for f in forms):
            bad.append(f":archtest asserts {rules} enabled rules; no document sentence says "
                       f"\"{want} rules\". The rule set and the enumeration in "
                       f"docs/architecture.md's testing table have diverged — a rule was added, "
                       f"removed, or renamed without the document being updated.")

print(f"  PASS  the architecture states how many rules :archtest asserts ({rules})"
      if not bad else "  FAIL  the architecture disagrees with :archtest")
for b in bad:
    print("        " + b)
sys.exit(1 if bad else 0)
PYEOF

# Forbidding, and sound because the fact is settled: `docker-compose.yml` has never set a
# project name, so Compose derives one per directory and parallel checkouts get their own
# containers, networks and database. The one volume they do share is named rather than the
# project, which is what the assertion below covers and why this one is still true as written.
if grep -rIn -E 'Compose project is named|compose project is named|project name is (set|pinned)' \
     . --exclude=check-docs.sh --exclude-dir=.git --exclude-dir=worktrees \
     --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.venv \
     --exclude-dir=__pycache__ --exclude-dir=.idea >"$HITS"; then
  bad "a file claims the Compose project is named"; sed 's/^/        /' "$HITS"
else ok "nothing claims the Compose project is named"; fi

# Computed, and the counterpart to the check above: the project is unnamed, but one volume
# is, and that one is `external`. Compose therefore will not create it, so the deploy script
# must — under the same name, before anything mounts it. Nothing in Compose or in bash
# relates those two literals, and a drift between them does not fail at review: it fails at
# `up`, on whichever machine has not got the old volume lying around.
#
# Computed in the sense that matters — no prose is quoted back at a file, so no reword can
# redden it. It does read two syntaxes literally, though, and both are narrower than what
# Compose accepts: the short `- volume:/models` mount form, and a `docker volume create` at
# column 0. Writing either the other legal way goes red with a message saying so, which is
# the safe direction but is not the same as being free of them.
python3 - <<'PYEOF' || fail=1
import re, sys, pathlib

bad = []


def read(path):
    try:
        return pathlib.Path(path).read_text(encoding="utf-8")
    except OSError as exc:
        bad.append(f"cannot read {path} ({exc.strerror}) — this assertion could not run")
        return ""


compose = read("docker-compose.yml")
deploy = read("scripts/deploy.sh")

# Identified by the mount that uses it rather than by its key. Hardcoding the key would
# make this agree with whichever side it was written against, and renaming a volume is one
# of the two ways the two literals drift apart.
mount = re.search(r'^\s*-\s*[\'"]?([A-Za-z0-9_][A-Za-z0-9._-]*):/models\b', compose, re.M)
if not mount:
    bad.append("no volume is mounted at /models in docker-compose.yml, so the weights "
               "cache could not be identified. Only the short `- name:/models` form is "
               "read; the long `type:/source:/target:` form would need this widened")

# The top-level `volumes:` mapping. Anchored at column 0, which a service's own `volumes:`
# key never is.
parts = re.split(r'^volumes:[ \t]*$', compose, flags=re.M)
if len(parts) != 2:
    bad.append(f"expected exactly one top-level volumes: block in docker-compose.yml, "
               f"found {len(parts) - 1}")

declared = {}
if len(parts) == 2:
    current = None
    for line in parts[1].splitlines():
        key = re.match(r'^  ([A-Za-z0-9_][A-Za-z0-9._-]*):[ \t]*(?:#.*)?$', line)
        if key:
            current = key.group(1)
            declared[current] = []
        elif re.match(r'^    \S', line) and current:
            declared[current].append(line.strip())
        elif line.lstrip().startswith("#"):
            continue  # a comment at any indent, which is legal YAML and ends nothing
        elif line.strip() and not line.startswith(" "):
            break  # a column-0 key ends the block

if mount and len(parts) == 2:
    volume = mount.group(1)
    body = declared.get(volume)
    if body is None:
        bad.append(f"docker-compose.yml mounts '{volume}' at /models but declares no such "
                   f"volume (declared: {sorted(declared)})")
    elif not any(re.fullmatch(r'external:\s*true', line) for line in body):
        bad.append(f"the weights cache '{volume}' is not external, so each Compose project "
                   f"gets its own copy and `down -v` in one worktree deletes it")
    else:
        named = [re.fullmatch(r'name:\s*[\'\"]?(\S+?)[\'\"]?', line) for line in body]
        named = [m for m in named if m]
        if not named:
            bad.append(f"the weights cache '{volume}' is external but sets no name:, so the "
                       f"name the deploy script must create is not stated anywhere")
        else:
            name = named[0].group(1)
            # `docker volume create` must name the same volume, must be unconditional, and
            # must run before the first thing that mounts it — an external volume that does
            # not exist yet is a hard error at `up`, not something Compose fills in.
            #
            # Both patterns anchor at column 0, and that is the assertion rather than an
            # accident of formatting: an indented create is one inside an `if`, and the only
            # condition anyone would wrap it in is the full-vs-selective test above it. That
            # passes a full deploy and hard-fails `--embeddings` on a fresh machine, which is
            # exactly the regression `scripts/deploy.sh` says the unconditional call prevents.
            creates = list(re.finditer(r'^docker volume create\s+(\S+)', deploy, re.M))
            ups = list(re.finditer(r'^docker compose (up|build)\b', deploy, re.M))
            matching = [m for m in creates if m.group(1) == name]
            if not matching:
                indented = re.search(r'^[ \t]+docker volume create\s+' + re.escape(name),
                                     deploy, re.M)
                got = sorted({m.group(1) for m in creates}) or "nothing"
                bad.append(
                    f"scripts/deploy.sh creates '{name}' but only under a condition — an "
                    f"indented create runs for some deploys and not others, and the "
                    f"selective ones still mount the volume"
                    if indented else
                    f"docker-compose.yml expects the external volume '{name}' to exist, but "
                    f"scripts/deploy.sh creates {got} at the top level")
            elif not ups:
                # Without a Compose call to order against, the ordering arm below silently
                # tests nothing. Say so rather than reporting a pass it did not earn.
                bad.append("scripts/deploy.sh runs no top-level `docker compose up|build`, "
                           "so this assertion cannot check that the volume is created before "
                           "anything mounts it")
            elif matching[0].start() > ups[0].start():
                bad.append(f"scripts/deploy.sh creates '{name}' after it already runs "
                           f"docker compose, so the first deploy on a machine fails")

print("  PASS  the external weights cache is created by the deploy script under the name "
      "Compose expects" if not bad else
      "  FAIL  the weights cache Compose expects and the one the deploy script creates "
      "disagree")
for b in bad:
    print("        " + b)
sys.exit(1 if bad else 0)
PYEOF

# 15h. How a client is identified, which `decisions.md` names as deliberately stated in more
# than one document. Two arms, because one alone leaves a hole: the forbidding arm is keyed on
# the shape of the superseded claim — a client identified by a *topic* label — rather than on
# any sentence, so it survives a reword; the presence arm is located by phrasing, which is the
# weak kind, and is here because without it rewording an anchor takes a document out of
# coverage silently. Flattened per document: these files are hard-wrapped and both claims are
# sentences, so a line-based match passes on the very wording it is meant to catch.
for f in docs/architecture.md docs/requirements.md; do
  flat="$(tr '\n' ' ' <"$f")"
  hit="$(printf '%s' "$flat" |
         grep -oiE 'client[^.]{0,60}topic label|topic label[^.]{0,60}client' | head -2)"
  if [ -n "$hit" ]; then
    bad "$f identifies a client by a topic label, where identity is any conversation's name"
    printf '%s\n' "$hit" | sed 's/^/        /'
  else
    ok "$f does not narrow a client's identity to a topic label"
  fi
  if printf '%s' "$flat" | grep -qiE "conversation([^.]{0,20}own name|[^.]{0,20}carrying a name)"; then
    ok "$f states that a client is identified by its conversation's name"
  else
    bad "$f no longer states how a client is identified (or the anchor phrasing moved)"
  fi
done

# 15i. The conversation kind identity refuses, held between the pass and the documents.
#
# Computed from the predicate rather than keyed on a sentence, which is what makes it the
# stronger kind: the pass is the thing that decides, so this reads the decision out of it and
# then requires every document stating how a client is identified to carry the exclusion too.
# Bidirectional for the reason 15h's first arm is not — a predicate that stopped excluding
# anything would make the documents' exclusion the stale claim, and a presence-only arm could
# never say so.
#
# All three documents, where 15h reads two. `decisions.md` tells the reader that how a client is
# identified is stated in more than one document and that this script asserts they agree, so a
# document carrying the rule and no assertion over it makes that sentence false.
pass="domain/src/main/kotlin/io/github/ragnorrs/mainbrain/domain/recognition/Reconciler.kt"
if grep -q 'it\.kind != ConversationKind\.DIRECT' "$pass"; then
  for f in docs/architecture.md docs/requirements.md docs/decisions.md; do
    if tr '\n' ' ' <"$f" | grep -qiE '(two-person|direct) exchange'; then
      ok "$f states that identity refuses a two-person exchange"
    else
      bad "$f states how a client is identified but not that a two-person exchange is refused,
       which $pass excludes"
    fi
  done
else
  for f in docs/architecture.md docs/requirements.md docs/decisions.md; do
    hit="$(tr '\n' ' ' <"$f" | grep -oiE '(two-person|direct) exchange[^.]{0,80}' | head -1)"
    if [ -n "$hit" ]; then
      bad "$f says identity refuses a two-person exchange, and $pass no longer does"
      printf '        %s\n' "$hit"
    else
      ok "$f does not claim an exclusion the pass has dropped"
    fi
  done
fi

echo "9. The schema and the documents agree about the derived passes and the watermark"
# Both halves read the schema and hold the prose to it, rather than forbidding the wording a
# defect happened to use. A pattern keyed on a deleted sentence is the kind this file's header
# warns about twice over: it cannot fire again, and it does not see the same claim stated in
# other words elsewhere — which is exactly how a corrected specification can sit in one
# document beside an uncorrected summary of itself.
#
# Read over every migration in version order, the newest definition winning. The set is
# append-only and Flyway validates what it has applied, so a constraint is widened by a later
# migration rather than edited in place; reading one file by name would go green on precisely
# the change this exists to catch. Each file is flattened first, because a constraint
# reformatted across lines is a reshape, not a removal.
schema=""
while IFS= read -r m; do
  schema="$schema $(tr '\n' ' ' <"$m")"
done < <(find data/src/main/resources/db/migration -name 'V*__*.sql' 2>/dev/null |
         awk -F'/V' '{split($2, v, "__"); print v[1] " " $0}' | sort -n -k1,1 | cut -d' ' -f2-)

# 16a. Every pass the schema admits has a section of its own. One-way on purpose: a pass may
# be described before it has a deriver, which is what deferred looks like. Top-level headings
# only — matching any heading lets a sub-rule about what a pass may *not* do stand in for the
# section describing it, and that is a check which cannot fail for its own reason.
derivers="$(printf '%s' "$schema" |
            sed -n 's/.*CONSTRAINT derivation_deriver CHECK *( *deriver IN *(\([^)]*\)).*/\1/p' |
            tr -d "' " | tr ',' '\n' | grep -v '^$')"
heads="$(grep -E '^## ' docs/architecture.md | tr 'A-Z' 'a-z')"
if [ -z "$derivers" ]; then
  # Fail rather than skip: a skip leaves the script's exit code at 0, so in CI a gate that
  # lost its grip on the schema and one that checked it are the same colour.
  bad "no migration defines the deriver constraint, so which passes exist cannot be computed"
elif [ -z "$heads" ]; then
  bad "docs/architecture.md has no top-level headings, so no pass can be matched to a section"
else
  for d in $derivers; do
    # -F, because a deriver value is a literal: interpolated into a pattern, one containing a
    # metacharacter would report a document defect for what is a pattern defect.
    if printf '%s\n' "$heads" | grep -qF -- "$d"; then
      ok "the '$d' pass has a section of its own in the architecture"
    else
      bad "the schema allows a '$d' pass that no top-level architecture section describes"
    fi
  done
fi

# 16b. The watermark's key, computed both ways. The version is a column compared for
# staleness, and whether it is part of the key is what decides if two passes may share a
# deriver over one record kind — so the documents are held to whichever the constraint says.
# Keyed on the shape of the claim rather than a wording, and flattened per document, because
# these files are hard-wrapped and a line-based match reports PASS on the sentence it forbids.
key="$(printf '%s' "$schema" | sed -n 's/.*CONSTRAINT derivation_mark UNIQUE *(\([^)]*\)).*/\1/p')"
if [ -z "$key" ]; then
  bad "no migration defines the derivation mark constraint, so its key cannot be computed"
elif printf '%s' "$key" | grep -q 'version'; then
  present "the architecture states that the watermark's key includes the version" \
          docs/architecture.md 'version'
else
  found=0
  for f in "${DOCS[@]}"; do
    hit="$(tr '\n' ' ' <"$f" |
           grep -oiE '(watermark|a mark)[^.]{0,80}key(ed|s)? (by|on)[^.]{0,80}version' | head -3)"
    if [ -n "$hit" ]; then
      found=1
      printf '  FAIL  %s puts the version inside the watermark key, which the schema does not\n' "$f"
      printf '%s\n' "$hit" | sed 's/^/        /'
      fail=1
    fi
  done
  [ "$found" -eq 0 ] && ok "no document puts the derivation version inside the watermark's key"
fi

echo "10. Batching is described where it is done, and only where it is done"
# Computed from the pass rather than keyed on a sentence, which is what makes this the stronger
# kind: the pass is the thing that decides, so this reads the decision out of it and then requires
# the architecture to carry it. Bidirectional, because both directions are reachable — a stage that
# stopped batching would leave the document describing a saving nothing takes, and a document that
# never mentioned it would leave the one place a reader looks for model configuration silent about
# the endpoint half the model budget now goes through.
inferrer="domain/src/main/kotlin/io/github/ragnorrs/mainbrain/domain/recognition/Inferrer.kt"
if [ ! -f "$inferrer" ]; then
  bad "the inference pass is not at $inferrer, so whether it batches cannot be computed"
elif grep -q 'model\.submit(' "$inferrer"; then
  # **The exclusion is what is asserted, not the two words near each other.** An earlier form of
  # this asked only that one sentence hold both "answer path" and "batch", which the inversion
  # satisfies: "The answer path is batched as well, because a batch is cheaper and a person can
  # wait" passed it. A check that goes green on the negation of its own invariant is worse than no
  # check, because it is quoted as evidence. So the sentence has to say the answer path is *not*
  # batched, and a sentence saying it *is* fails outright.
  #
  # **`grep -i ... >/dev/null` rather than `grep -qi`, and that is not a style choice.** With
  # `pipefail` set, a `-q` at the end of a pipe exits on its first match and closes the pipe
  # under the upstream `grep`, which then dies of SIGPIPE and makes the whole pipeline's status
  # non-zero — so the assertion reports FAIL on a document that satisfies it. It is a race, and
  # a small input usually wins it, which is why an earlier form passed locally and failed on CI.
  # Without `-q` the downstream reads to the end and nothing is ever killed.
  sentences="$(tr '\n' ' ' <docs/architecture.md | tr '.' '\n' | grep -i 'answer path')"
  claims="$(printf '%s\n' "$sentences" |
            grep -iE 'answer path is (never|not) batched|never batched[^.]{0,40}answer path')"
  denies="$(printf '%s\n' "$sentences" |
            grep -iE 'answer path is batched|answer path[^.]{0,40}(is|are) (also |as well )?batched')"
  if [ -n "$denies" ]; then
    bad "docs/architecture.md says the answer path is batched, which the pass does not do and
       must not do: a person is waiting on it and a batch is allowed most of a day"
    printf '        %s\n' "$denies"
  elif [ -n "$claims" ]; then
    ok "the architecture says which calls are batched and that the answer path is not"
  else
    bad "the inference pass batches its model calls, and docs/architecture.md does not say that
       the answer path is excluded — which is the half a reader needs before copying the pattern"
  fi
else
  hit="$(tr '\n' ' ' <docs/architecture.md | grep -oiE 'batch(ed|ing)?[^.]{0,80}(endpoint|submission)' | head -1)"
  if [ -n "$hit" ]; then
    bad "docs/architecture.md describes batched model calls, and $inferrer makes none"
    printf '        %s\n' "$hit"
  else
    ok "no document claims a batching this build does not do"
  fi
fi

# Which stages batch. Computed from the pass on the code side and quoted on the document side, which
# makes it the weaker kind in the document's direction: a red there can mean a rewording rather than
# a defect. It is still worth having, because the stage is where most of the model budget goes and
# the document is the one place a deployment reads to find out what a call costs.
#
# Bidirectional, because both directions are reachable. A document asserting an exclusion the pass
# does not make sends a reader looking for a synchronous call that is not there; one asserting the
# batching over a pass that does not do it promises a saving nothing takes.
#
# **Two patterns, because the two directions are not each other's negation.** "The document says
# restatement is batched" and "the document says restatement is not batched" are different claims,
# and a single pattern asked to serve both answers the wrong one: `restatement.*batch` matches
# "restatement is not batched" and "excluded from the batch endpoint" just as readily as the
# affirmative. Each arm therefore requires the statement it wants to see, and neither is the other
# negated.
#
# **The denial subtracts from the affirmative, because the two are not disjoint.** A denial that
# names the endpoint satisfies every word the affirmative asks for, so an arm reading the affirmative
# on its own reports a correct exclusion as a claim that the stage *is* batched — the opposite of
# what the document says. Each arm still tests the statement that would contradict the code before
# the one that would confirm it; the subtraction is what makes that ordering safe.
#
# **The denial is bounded by the clause and not by the sentence.** "The answer path is never batched"
# is a true sentence of this document that can sit in the same period-delimited run as a claim about
# the restatement stage, and a denial allowed to reach across the comma between them reads the one as
# the other — which, subtracted, would turn a document that affirms the batching into a silent pass.
# A comma ends the clause. Unbounded *within* it rather than a counted run, so no multibyte dash can
# defeat it.
#
# The affirmative requires the *endpoint* and not merely "batch" near "restatement", so that the arm
# stays about where a call is submitted rather than about any sentence with both words in it: a
# claim about a batch's size or a batch's cost is not a claim about the batch endpoint.
#
# What the denial still cannot tell apart is *which* exclusion a sentence means. This document keeps
# a classified record out of selection as well, and a sentence rewording that to "excluded" beside
# the word "restatement" would be read here as a claim about batching and go red. The trade is
# deliberate: requiring the denial to name batching is what loses the plainest form of it — "with
# the restatement stage excluded" — and a loud red on a reworded document is worth more than a
# silent pass over a document that denies the batching in plain English.
#
# `.*` rather than a bounded `[^.]{0,N}` between the words, because the sentence contains em dashes
# and a bounded run counts bytes under `LC_ALL=C` — the script's own header forbids reaching across
# a multibyte character with a counted pattern, and the margin was about nine bytes.
#
# "Synchronous" is deliberately not a marker of the exclusion, though an exclusion would use it: the
# affirmative sentence says the batch endpoint "costs half the synchronous one", so the word appears
# either way. What only an exclusion says is that the stage is left out of the batching.
restated_batched='restatement.*batch.*endpoint|all three.*stages.*batch.*endpoint'
restated_excluded='restatement[^.,]*(excluded|not batched|never batched|rather than batched)'
restated_excluded="$restated_excluded|(excluded|not batched|never batched)[^.,]*restatement"
architecture_sentences() { tr '\n' ' ' <docs/architecture.md | tr '.' '\n'; }
# The affirmative with any denial removed; see above. A pattern that will not compile exits 2 and
# reads as "no match", which would make both arms answer from an empty set, so both are tried
# against nothing first and a malformed one is reported rather than passed over.
for restated_pattern in "$restated_batched" "$restated_excluded"; do
  printf '' | grep -qE "$restated_pattern"
  [ "$?" -gt 1 ] && bad "a restatement-batching pattern will not compile: $restated_pattern"
done
restated_affirming() { architecture_sentences | grep -iE "$restated_batched" | grep -ivE "$restated_excluded"; }

if [ ! -f "$inferrer" ]; then
  : # Already reported by the assertion above; a second line about the same missing file says nothing.
# The signal is the stage existing in the pass at all, not the spelling of one call's arguments: a
# stage that is never submitted has no reason to be named here, and anchoring on `stage = ...` would
# turn a positional-argument refactor into a red about the documents.
elif grep -q 'SubmissionStage\.RESTATEMENT' "$inferrer"; then
  excluded="$(architecture_sentences | grep -iE "$restated_excluded")"
  batched="$(restated_affirming)"
  if [ -n "$excluded" ]; then
    bad "docs/architecture.md says the restatement stage is not batched, and $inferrer submits it.
       A reader is sent looking for a synchronous call that is not there"
    printf '        %s\n' "$excluded"
  elif [ -n "$batched" ]; then
    ok "the architecture says the restatement stage is batched, which the pass does"
  else
    bad "the pass submits its restatements, and docs/architecture.md does not say so — the stage
       is the larger half of the model bill and the document is where that is recorded"
  fi
else
  stale="$(restated_affirming)"
  excluded="$(architecture_sentences | grep -iE "$restated_excluded")"
  if [ -n "$stale" ]; then
    bad "docs/architecture.md says the restatement stage is batched, and $inferrer does not submit it"
    printf '        %s\n' "$stale"
  elif [ -n "$excluded" ]; then
    ok "the architecture says the restatement stage is not batched, which the pass does not do"
  else
    bad "the pass does not submit its restatements, and docs/architecture.md does not say so — the
       stage is where the model budget goes and the document is where that is recorded"
  fi
fi

echo "11. The predicate vocabulary's width is stated as the extraction stage holds it"
# Computed from the list rather than keyed on a sentence. The extraction stage is where the
# vocabulary is chosen, so this counts the entries there and holds the architecture to that
# width — which is the one number an entry is added or withheld against.
#
# **Bidirectional, and the second arm is the one worth having.** The failure this exists to catch
# is a document keeping a width the list has moved past: adding an entry is cheap and is meant to
# be, so the stale claim is the likely one. A presence-only arm would go green on exactly that,
# since the sentence would still be there saying something false.
#
# Matched on "N entries wide" and not on the sentence around it, so the prose may be rewritten
# freely. A width this has no number word for is a skip rather than a pass: it is an assertion
# that could not run, which the summary below reports as such.
extractor="domain/src/main/kotlin/io/github/ragnorrs/mainbrain/domain/recognition/Extractor.kt"
# **Read off the declaration that pairs each entry with what it takes**, which is where the
# vocabulary is held: the bare list is derived from it, so a width read off the list would be a
# width read off a projection of this. Flattened first, because the declaration spans lines and
# adding an entry is the cheap, expected direction — read line-wise it would match nothing and
# skip, which is honest but no longer a guard.
predicates="$(tr '\n' ' ' <"$extractor" | grep -oE 'val TAKES = mapOf\([^)]*\)')"
if [ -z "$predicates" ]; then
  skip "the predicate vocabulary could not be read out of $extractor, so its width is unasserted"
else
  # Counted per pairing and not per quoted string, because each entry carries two of those — its
  # name and its gloss — and not always in the same form: the one named as a constant elsewhere is
  # quoted once here and the rest twice. Counted per match and not per line, the declaration having
  # been flattened to one.
  entries="$(printf '%s' "$predicates" | grep -oE ' to[[:space:]]+"' | wc -l | tr -d ' ')"
  case "$entries" in
    1) word=one ;; 2) word=two ;; 3) word=three ;; 4) word=four ;; 5) word=five ;;
    6) word=six ;; 7) word=seven ;; 8) word=eight ;; 9) word=nine ;; *) word="" ;;
  esac
  # `awk NR==1` rather than `head -1`: head closes the pipe early, and under `pipefail` that
  # turns a satisfied match into a failed pipeline.
  widths="$(tr '\n' ' ' <docs/architecture.md |
    grep -oiE '(one|two|three|four|five|six|seven|eight|nine) entries wide' |
    tr 'A-Z' 'a-z' | awk '{ print $1 }')"
  found="$(printf '%s' "$widths" | grep -c . || true)"
  stated="$(printf '%s\n' "$widths" | awk 'NR==1 { print $1 }')"
  if [ -z "$word" ]; then
    skip "the vocabulary holds $entries entries, a width this check has no number word for"
  elif [ "$found" -gt 1 ]; then
    # Only the first would be read, so a second such sentence would quietly become the assertion
    # and the one about this list could go stale underneath it. Said rather than resolved, because
    # which of them is meant is a judgement this cannot make.
    bad "docs/architecture.md states a width in \"N entries wide\" form $found times, so which one
       this assertion reads is arbitrary: $(printf '%s' "$widths" | tr '\n' ' ')"
  elif [ -z "$stated" ]; then
    bad "docs/architecture.md does not state how wide the predicate vocabulary is, and $extractor
       holds $entries entries — the width is what an entry is added against"
  elif [ "$stated" = "$word" ]; then
    ok "docs/architecture.md states the predicate vocabulary's width, and it is $entries entries"
  else
    bad "docs/architecture.md says the predicate vocabulary is $stated entries wide and
       $extractor holds $entries"
  fi
fi

echo
echo "12. The example file and the variables something reads agree, in both directions"
# A documented dial that nothing interpolates turns nothing, and the failure is silent in the worst
# way: the variable is in `.env`, the deployment believes it is set, and the process never sees it.
# Compose reads `.env` for interpolation only, so a variable `docker-compose.yml` does not name is
# never passed into a container — which the compose file says in prose beside the model settings and
# nothing checked.
#
# Both directions are computed, over different sets, because the two complaints are not
# symmetrical. A variable documented and never read is this defect, and it is checked over every
# reader. A variable read and never documented is an undocumented dial, and demanding that of every
# reader would be wrong: some belong to the deploy script or a test harness and have no place in an
# example meant for a person configuring a stack — the end-to-end suite and the evaluation suite
# each keep their own example file for exactly those.
#
# So the reverse is asserted over the one set where that objection cannot apply — the names
# `docker-compose.yml` interpolates. Compose resolves those from the environment and then from
# `.env`, so a person configuring a stack is who supplies them and this file is where they would
# look, and an undocumented one is a dial that exists, works, and is discoverable only by reading
# the compose file. The overrides that make the evaluation stack behave unlike the serving one
# reached `main` that way, with defaults in the compose file and no mention anywhere else.
#
# Computed from the compose file rather than from a list, so adding an interpolation is what moves
# what the reverse direction demands.
#
# "Read by something" stays wider than compose for the forward direction, on purpose: a variable may
# be the deploy script's or the end-to-end harness's rather than a container's, and a check that
# demanded compose name all of them would be wrong about every one of those. The reader set is the
# compose file, `scripts/`, `e2e/*.py` and `eval/*.py` — Kotlin is not in it, because a variable
# only reaches a container through a name the compose file has to carry anyway.
#
# One block and one parse of the example file, deliberately. Two copies of that pattern would let
# one direction change what counts as a documented line while the other kept the old rule, and both
# would still report PASS while disagreeing about the same word.
python3 - <<'PYEOF' || fail=1
import pathlib, re, sys

example = pathlib.Path(".env.example")
compose = pathlib.Path("docker-compose.yml")
for required in (example, compose):
    if not required.is_file():
        # A verdict rather than a traceback: a renamed file is a thing to be told about.
        print(f"  FAIL  {required} is missing, so neither direction here can be compared")
        sys.exit(1)

# Both forms the file uses: a commented default and an uncommented placeholder.
documented = {
    hit.group(1)
    for line in example.read_text(encoding="utf-8").split("\n")
    if (hit := re.match(r"^#?\s*([A-Z][A-Z0-9_]{2,})=", line))
}

readers = [str(compose)]
readers += [str(p) for p in pathlib.Path("scripts").rglob("*") if p.is_file()]
readers += [str(p) for p in pathlib.Path("e2e").rglob("*.py")] if pathlib.Path("e2e").is_dir() else []
readers += [str(p) for p in pathlib.Path("eval").rglob("*.py")] if pathlib.Path("eval").is_dir() else []
read = "\n".join(pathlib.Path(r).read_text(encoding="utf-8", errors="replace") for r in readers)

failed = False

orphans = sorted(name for name in documented if name not in read)
if orphans:
    print("  FAIL  .env.example documents variables nothing reads, so each is a dial that turns")
    print("        nothing: " + ", ".join(orphans))
    failed = True
else:
    print(f"  PASS  all {len(documented)} documented variables are read by something")

# `${NAME}`, `$NAME`, `${NAME:-default}` and `${NAME:?message}` alike: the name is what is read,
# and the braces and whatever follows them decide only the syntax and the absent case. `$$` is
# compose's own escape for a literal dollar, so the lookbehind keeps it out — it is not a read.
interpolated = set(re.findall(r"(?<!\$)\$\{?([A-Z][A-Z0-9_]{2,})", compose.read_text(encoding="utf-8")))
if not interpolated:
    # Without this, a compose file whose interpolations moved or were renamed reports PASS over an
    # empty set, which is the shape that retires an assertion while still printing green.
    print("  FAIL  no interpolation found in docker-compose.yml, so this direction measured nothing")
    print("        — the syntax moved, or the pattern no longer matches what the file uses")
    failed = True
else:
    undocumented = sorted(interpolated - documented)
    if undocumented:
        print("  FAIL  docker-compose.yml reads variables that .env.example does not document, so")
        print("        each is a dial discoverable only by reading the compose file: "
              + ", ".join(undocumented))
        failed = True
    else:
        print(f"  PASS  all {len(interpolated)} variables compose reads are documented")

sys.exit(1 if failed else 0)
PYEOF


# The configuration itself takes care not to hold a second default — it falls back to the pass's own
# constant rather than to a figure of its own, so the two cannot drift. The example file is the one
# place that *does* restate them, as a commented line a person uncomments, and nothing checked it.
# A stale one there is the worst kind of wrong: it is read as the value in force by whoever is
# deciding whether to change it, and it is the value they get if they uncomment the line.
#
# Computed from the Kotlin rather than from a list of expected numbers, so this cannot go stale
# itself — moving a constant moves what this demands, and a constant it cannot find is a loud
# failure rather than a silent pass.
#
# **It covers the closure bounds and not every restated default, and that is a gap rather than a
# principle.** Other commented figures in the file restate constants too — the recency horizon and
# the two daily allowances among them — and carry the same hazard. Widening this is computable:
# every one of them reaches its default through an `env.reader("NAME") ?: Constant` fallback in
# BackendConfig, so the variable-to-constant pairs can be read off that file instead of listed
# here. Until they are, the pairs below are the covered set and nothing else is.
#
# **The evaluation stack's figures are outside even that widening**, and they are the wider half of
# the gap. They restate `docker-compose.yml`'s own `${NAME:-default}` rather than a Kotlin constant,
# so reading pairs off BackendConfig would not reach them; the two ports are restated in four places
# between this file, the compose file, the harness and the suite's own example. Comparing a
# documented figure against a compose default is computable from the two files this section already
# reads — what stops it today is that most names are interpolated as `${NAME:-}` with an empty
# default, deferring to the process's own constant, so a naive comparison would report eighteen
# disagreements that are not disagreements. Telling those apart is the work, and it is not done.
# Part of the section above rather than a numbered section of its own: all three assertions here are
# about .env.example, and a heading of its own would say less than the one they share.
python3 - <<'PYEOF' || fail=1
import pathlib, re, sys

closer = pathlib.Path(
    "domain/src/main/kotlin/io/github/ragnorrs/mainbrain/domain/recognition/Closer.kt"
).read_text(encoding="utf-8")
example = pathlib.Path(".env.example").read_text(encoding="utf-8")

def constant(pattern, name):
    hit = re.search(pattern, closer)
    if not hit:
        print(f"  FAIL  {name} is not declared where this expects it, so nothing can be compared")
        sys.exit(1)
    return hit.group(1)

def documented(variable):
    hit = re.search(rf"^#\s*{variable}=(\d+)\s*$", example, re.M)
    return hit.group(1) if hit else None

pairs = [
    ("RECOGNITION_CLOSURE_REARM_MESSAGES", constant(r"const val DEFAULT_REARM = (\d+)", "DEFAULT_REARM")),
    ("RECOGNITION_CLOSURE_DAYS", constant(r"DEFAULT_EXAMINE: Duration = Duration\.ofDays\((\d+)\)", "DEFAULT_EXAMINE")),
]

bad = []
for variable, expected in pairs:
    shown = documented(variable)
    if shown is None:
        bad.append(f"{variable} has no commented default in .env.example to compare against {expected}")
    elif shown != expected:
        bad.append(f"{variable} is documented as {shown} and the code's default is {expected}")

if bad:
    print("  FAIL  a commented default restates a constant and no longer matches it, so the figure")
    print("        a deployment reads is not the one in force:")
    for line in bad:
        print(f"          {line}")
else:
    print("  PASS  both closure bounds are documented at the figure the pass defaults to "
          f"({pairs[0][1]} messages, {pairs[1][1]} days)")
sys.exit(1 if bad else 0)
PYEOF

echo
echo "13. Every span an evaluation case names is one the corpus fixture holds"
# A case states the material that must come back and the material that must not, as span
# identifiers written by hand. One naming a record the fixture does not hold cannot be retrieved by
# anything, so its case can never score full recall — and the red reads as a retrieval regression
# rather than as a typo in the fixture, on the one tier that costs money to run.
#
# One direction only. A record no case names is ordinary: the corpus is there to be ranked over,
# and most of it exists so that a scoped read has something to leave out.
#
# A span identifier carries the record's own native identifier between the kind and the position,
# and that identifier is what the fixture declares — so the check resolves the middle and compares
# it, rather than matching the whole string against anything. An inference shares the identifier of
# the message it was read from, which is why comparing against the messages is right for a kind
# this fixture does not yet carry; one naming any other kind is reported as the kind it named,
# because this fixture holds messages and nothing else.
#
# **The position is checked too, and it is the half that looks harmless.** A message is indexed
# whole and its span is always at nought — `SpanId` states it — so a position of anything else
# names nothing retrievable while looking exactly like a working identifier.
#
# **Vacuity is asked per case rather than over the set.** A corpus-wide total is satisfied by the
# other cases, so one whose spans were emptied or whose key was misspelled would contribute nothing
# and go unnoticed. The one case that legitimately names none says so, through the same flag it
# already uses to opt out of being scored on retrieval at all.
python3 - <<'PYEOF' || fail=1
import json, pathlib, re, sys

cases = json.loads(pathlib.Path("eval/fixtures/cases.json").read_text(encoding="utf-8")).get("cases")
corpus = json.loads(pathlib.Path("eval/fixtures/corpus.json").read_text(encoding="utf-8"))
messages = corpus.get("messages")
if not cases or not messages:
    # Said rather than raised: a fixture missing either key would otherwise leave a Python
    # traceback where this assertion's own verdict belongs.
    print("  FAIL  the evaluation fixtures hold no cases, or the corpus holds no messages, so "
          "this assertion has nothing to compare")
    sys.exit(1)
held = {message.get("native_id") for message in messages}

SPAN = re.compile(r"^([A-Z]+)/[A-Z]+/(.+)#(\d+)$")
bad = []
for case in cases:
    retrieval = case.get("retrieval", {})
    spans = [(half, span) for half in ("must_include", "must_exclude") for span in retrieval.get(half, [])]
    if not spans and case.get("scores_retrieval", True):
        bad.append(f"{case['id']} names no span at all, and does not say it is not scored on retrieval")
    for half, span in spans:
        hit = SPAN.match(span)
        if not hit:
            bad.append(f"{case['id']} {half}: {span} is not a span identifier at all")
            continue
        kind, native, ordinal = hit.group(1), hit.group(2), hit.group(3)
        if kind != "MESSAGE":
            bad.append(f"{case['id']} {half}: {span} names a {kind}, and this fixture holds messages")
        elif native not in held:
            bad.append(f"{case['id']} {half}: {span} names a record the corpus does not hold")
        elif ordinal != "0":
            bad.append(f"{case['id']} {half}: {span} is at position {ordinal}, and a message is "
                       "indexed whole at nought")

if bad:
    print("  FAIL  an evaluation case names a span nothing could retrieve")
    for entry in bad[:6]:
        print("        " + entry)
    sys.exit(1)
counted = sum(
    len(case.get("retrieval", {}).get(half, []))
    for case in cases
    for half in ("must_include", "must_exclude")
)
print(f"  PASS  all {counted} span(s) named by an evaluation case are held by the corpus fixture, "
      f"across {len(cases)} case(s)")
PYEOF

echo
echo "14. The evaluation corpus rebuild knows every table the schema declares"
# The rebuild replaces the fixture corpus by truncating a list of tables named in `eval/corpus.py`.
# A table the schema has gained and that list has not is one the rebuild leaves alone, so the next
# run scores against whatever a previous fixture put there — and the one before it, since nothing
# cleared it either.
#
# **`corpus.py` already computes this against the live schema, and that is not enough.** Its check
# runs inside the reseed, which happens only on the tier that needs a live stack, a model credential
# and about four dollars. Six migrations added tables between one evaluation run and the next, and
# what ran into the gap was a run that erred out across every case before asking anything. The same
# question is answerable from the migrations alone, hermetically, here — so the next migration meets
# it at a merge rather than at a gate somebody is paying to run.
#
# Read off `CREATE TABLE` in the migration files rather than a list of expected names, so adding a
# migration moves what this demands. `NOT_CORPUS` counts as covered: it is the deliberate exclusion
# list, and its two members are the migration record and the suite's own bookkeeping.
#
# One direction only, as `corpus.py`'s own check is: a name in the list that the schema has dropped
# fails the `TRUNCATE` itself, loudly, on the next rebuild.
python3 - <<'PYEOF' || fail=1
import pathlib, re, sys

corpus = pathlib.Path("eval/corpus.py")
migrations = pathlib.Path("data/src/main/resources/db/migration")
if not corpus.is_file() or not migrations.is_dir():
    print("  FAIL  eval/corpus.py or the migration directory is missing, so the rebuild's table")
    print("        list cannot be compared against the schema")
    sys.exit(1)

source = corpus.read_text(encoding="utf-8")
listed = set()
for name in ("TABLES", "NOT_CORPUS"):
    hit = re.search(rf"^{name} = \((.*?)\)", source, re.S | re.M)
    if not hit:
        print(f"  FAIL  {name} is not declared in eval/corpus.py where this expects it, so nothing")
        print("        can be compared — the rebuild's own coverage check reads the same names")
        sys.exit(1)
    listed |= set(re.findall(r'"([a-z_][a-z0-9_]*)"', hit.group(1)))

declared = set()
for sql in sorted(migrations.glob("*.sql")):
    declared |= {
        found.lower()
        for found in re.findall(
            r"CREATE TABLE (?:IF NOT EXISTS )?([A-Za-z_][A-Za-z0-9_]*)",
            sql.read_text(encoding="utf-8"),
            re.I,
        )
    }

if not declared:
    print("  FAIL  no CREATE TABLE found under the migration directory, so this measured nothing")
    sys.exit(1)

missing = sorted(declared - listed)
if missing:
    print("  FAIL  the schema declares tables the evaluation corpus rebuild never clears, so a run")
    print("        would score against what a previous fixture left in them: " + ", ".join(missing))
    sys.exit(1)
print(f"  PASS  all {len(declared)} tables the migrations declare are named by the rebuild")
PYEOF

echo
echo "15. The end-to-end harness's example file and the settings it reads agree"
# The second credential file, and the one whose absence is quiet. Section 19 compares the root
# `.env.example` against what Compose and the scripts read; nothing compared this one, and its
# failure mode is worse than a missing dial. A setting the harness reads and the example omits is
# discoverable only by reading the config module, and the cases needing it skip — which a deploy
# reports as a skip line rather than a failure, so the tier reads as having run.
#
# Both directions, like section 13, and for the same reason: one of them alone would let a name
# rot in whichever file the other does not read.
#
# Keyed on the shared prefix rather than on a list of names, so a setting added later is covered
# without editing this. That also makes the empty-set guard necessary: renaming the prefix would
# otherwise retire both directions while still printing PASS.
python3 - <<'PYEOF' || fail=1
import pathlib, re, sys

example = pathlib.Path("e2e/.env.local.example")
harness = sorted(pathlib.Path("e2e").glob("*.py")) if pathlib.Path("e2e").is_dir() else []
if not example.is_file() or not harness:
    print(f"  FAIL  {example} or the harness it documents is missing, so neither direction compares")
    sys.exit(1)

PREFIX = "MB_E2E_"
documented = {
    hit.group(1)
    for line in example.read_text(encoding="utf-8").split("\n")
    if (hit := re.match(rf"^#?\s*({PREFIX}[A-Z0-9_]+)=", line))
}
read = set()
for module in harness:
    read |= set(re.findall(rf"\b({PREFIX}[A-Z0-9_]+)\b", module.read_text(encoding="utf-8")))

if not read:
    print(f"  FAIL  no {PREFIX}* setting is read anywhere under e2e/, so this measured nothing —")
    print("        the prefix moved, or the harness no longer reads its settings by name")
    sys.exit(1)

failed = False
orphans = sorted(documented - read)
if orphans:
    print(f"  FAIL  {example} documents settings the harness never reads, so each is a line")
    print("        somebody fills in for nothing: " + ", ".join(orphans))
    failed = True
else:
    print(f"  PASS  all {len(documented)} documented setting(s) are read by the harness")

undocumented = sorted(read - documented)
if undocumented:
    print(f"  FAIL  the harness reads settings {example} does not document, so a run that needs")
    print("        one skips rather than failing, and the tier reads as having run: "
          + ", ".join(undocumented))
    failed = True
else:
    print(f"  PASS  all {len(read)} setting(s) the harness reads are documented")

sys.exit(1 if failed else 0)
PYEOF

echo
echo "16. Every Compose service runs under an init process"
# Computed over `docker-compose.yml` alone, and one of the few assertions here that gates a
# deploy rather than a document. A service whose PID 1 is the application does not reap what the
# application abandons, and a container holding an unreaped child cannot be stopped by signal —
# which aborts a recreate partway and leaves a renamed container behind whose name collides with
# the next attempt. The compose file carries the rest of the reasoning; what earns this section
# is that no CI job builds an image, so the first thing that reads the setting is a deploy, and
# a service missing it costs two deploy slots rather than a red.
#
# The keys are scanned for rather than read with the pattern section 14b takes service ids with.
# That pattern matches a key it can name and skips one it cannot — an anchor, a trailing comment
# — and a skipped service is one this assertion never looks at while printing PASS over the
# rest. 14b notices that for a deployed service, because it counts them against the
# architecture, and not for a profiled one, which it only checks is mentioned. So the scan here
# is deliberately permissive about what follows the name: anything at two spaces that is not a
# comment is a service, and whatever trails the colon is not this section's business. Bodies are
# cut between consecutive keys for the same reason — a two-space comment inside a service would
# otherwise end its body early and hide the setting below it.
#
# `init: true` is read literally, and that is narrower than YAML: `"true"`, `yes` and `on` are
# all legal and none of them is read here. A service spelling it one of those ways is reported
# as exactly that rather than as one that set nothing, because Docker very likely did run an
# init there — telling a reader the application is PID 1 would send them to debug a container
# that is already correct.
python3 - <<'PYEOF' || fail=1
import re, sys, pathlib

try:
    compose = pathlib.Path("docker-compose.yml").read_text(encoding="utf-8")
except OSError as exc:
    print(f"  FAIL  cannot read docker-compose.yml ({exc.strerror}) — this assertion did not run")
    sys.exit(1)

# Everything under the column-0 `services:` key, stopping at the next column-0 key so
# `volumes:` cannot contribute its children.
tail = re.split(r'^services:[ \t]*$', compose, maxsplit=1, flags=re.M)
block = re.split(r'^[A-Za-z_]', tail[1], maxsplit=1, flags=re.M)[0] if len(tail) > 1 else ""
keys = list(re.finditer(r'^  (?![ \t#])(?P<name>[^\s:]+)[ \t]*:', block, re.M))

if not keys:
    print("  FAIL  no service could be read out of docker-compose.yml, so this measured nothing —")
    print("        the services block moved, or its indentation changed")
    sys.exit(1)

absent, misspelt = [], []
for i, key in enumerate(keys):
    end = keys[i + 1].start() if i + 1 < len(keys) else len(block)
    body = block[key.end():end]
    name = key.group("name").strip("'\"")
    if re.search(r'^    init: true[ \t]*(?:#.*)?$', body, re.M):
        continue
    (misspelt if re.search(r'^    init:', body, re.M) else absent).append(name)

if absent:
    print(f"  FAIL  {len(absent)} of {len(keys)} Compose service(s) set no `init:` at all, so the")
    print("        application is PID 1 there and a stop can hang on a child it never reaped: "
          + ", ".join(sorted(absent)))
if misspelt:
    print(f"  FAIL  {len(misspelt)} of {len(keys)} Compose service(s) spell `init:` a way this")
    print("        check does not read — Docker may well run an init there, but write it as "
          "`init: true`: " + ", ".join(sorted(misspelt)))
if absent or misspelt:
    sys.exit(1)

print(f"  PASS  all {len(keys)} Compose services set `init: true`")
PYEOF

echo
echo "17. The end-to-end register and the case files on disk agree"
# Computed in both directions over `e2e/`, because neither direction shows the failure the other
# does: a case file with no row is coverage nobody recorded, and a row naming a file that is not
# there is a record of coverage that is gone. ⛔ The reason this is a section rather than a habit
# is that it has already failed — a case file sat unlisted while the table around it was accurate,
# and the table is what a reader consults to learn what the tier covers.
#
# Only the table rows are read, never the prose beneath them: that prose names case files to
# discuss them, and reading it would let a row be absent while the section still mentioned the
# file somewhere.
python3 - <<'PYEOF' || fail=1
import pathlib
import re
import sys

HEADING = "## What it covers today"
suite = pathlib.Path("e2e")
register = pathlib.Path("e2e/README.md")

for required in (suite, register):
    if not required.exists():
        print(f"  FAIL  {required} is missing, so neither direction here can be compared")
        sys.exit(1)

cases = {path.name for path in suite.glob("test_*.py")}
if not cases:
    print(f"  FAIL  no case files under {suite}, so the register describes a suite that is gone")
    sys.exit(1)

text = register.read_text(encoding="utf-8")
if HEADING not in text:
    print(f"  FAIL  {register} carries no `{HEADING}` heading, so there is no table to compare")
    sys.exit(1)

section = text.split(HEADING, 1)[1].split("\n## ", 1)[0]
# A row names its case file in a code span; the header and separator rows carry none.
listed = set()
for line in section.split("\n"):
    if not line.lstrip().startswith("|"):
        continue
    listed.update(re.findall(r"`(test_[A-Za-z0-9_]+\.py)`", line))

failed = False

unlisted = sorted(cases - listed)
if unlisted:
    print("  FAIL  case file(s) with no row under the register's table, so what they cover is")
    print("        recorded nowhere a reader looks: " + ", ".join(unlisted))
    failed = True
else:
    print(f"  PASS  all {len(cases)} case file(s) under e2e/ have a row in the register")

dangling = sorted(listed - cases)
if dangling:
    print("  FAIL  row(s) naming a case file that is not on disk, so the register records")
    print("        coverage that is gone: " + ", ".join(dangling))
    failed = True
else:
    print(f"  PASS  all {len(listed)} row(s) in the register name a case file that exists")

sys.exit(1 if failed else 0)
PYEOF

echo
echo "18. The architecture document's listing of scripts/ and the directory agree"
# Computed in both directions, for the reason section 18 is: a file with no entry is tooling a
# reader never learns about, and an entry naming a file that is gone is a record of tooling that
# is not there. ⛔ A hand-maintained enumeration of a directory is the restated-fact shape this
# repository keeps finding, and the remedy for an enumeration worth keeping is a check rather than
# care — without one, the only thing holding the list to the directory is whoever last looked.
#
# Only the tree block is read. The document names these files in prose elsewhere to discuss them,
# and reading that would let an entry be absent while the file was still mentioned somewhere.
python3 - <<'PYEOF' || fail=1
import pathlib
import re
import sys

doc = pathlib.Path("docs/architecture.md")
directory = pathlib.Path("scripts")

for required in (doc, directory):
    if not required.exists():
        print(f"  FAIL  {required} is missing, so neither direction here can be compared")
        sys.exit(1)

on_disk = {path.name for path in directory.iterdir() if path.is_file()}
if not on_disk:
    print(f"  FAIL  no files under {directory}, so the listing describes a directory that is gone")
    sys.exit(1)

lines = doc.read_text(encoding="utf-8").splitlines()
start = next((i for i, line in enumerate(lines) if re.search(r"[|+]?[├└]── scripts/", line)), None)
if start is None:
    print(f"  FAIL  {doc} carries no `scripts/` entry in its tree, so there is no listing to compare")
    sys.exit(1)

# The entries under it, stopping at the first line that is not one of them.
listed = set()
for line in lines[start + 1:]:
    found = re.match(r"^│?\s*[├└]──\s+(\S+)", line)
    if not found:
        break
    listed.add(found.group(1))

if not listed:
    print(f"  FAIL  the `scripts/` entry in {doc} is followed by no files, so the tree does not")
    print("        enumerate the directory at all")
    sys.exit(1)

failed = False

unlisted = sorted(on_disk - listed)
if unlisted:
    print(f"  FAIL  file(s) under {directory} with no entry in the tree, so a reader learns of")
    print("        them nowhere: " + ", ".join(unlisted))
    failed = True
else:
    print(f"  PASS  all {len(on_disk)} file(s) under scripts/ have an entry in the tree")

dangling = sorted(listed - on_disk)
if dangling:
    print(f"  FAIL  tree entr(ies) naming a file that is not under {directory}, so the listing")
    print("        records tooling that is gone: " + ", ".join(dangling))
    failed = True
else:
    print(f"  PASS  all {len(listed)} tree entr(ies) name a file that exists")

sys.exit(1 if failed else 0)
PYEOF

echo
if   [ "$fail" -ne 0 ];    then echo "CHECKS FAILED"
elif [ "$skipped" -ne 0 ]; then echo "PASSED, BUT AT LEAST ONE ASSERTION COULD NOT RUN — see SKIP above"
else echo "ALL CHECKS PASSED"; fi
exit "$fail"
