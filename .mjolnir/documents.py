#!/usr/bin/env python3
# mjolnir-generated: documents-checker
# edits here are overwritten; remove it with install.py remove
"""Hold a project's prose documents to the properties its configuration states.

The rule is `plugins/mjolnir/config/schema.md § 38`, which states it and carries
the measurement behind it. Neither is restated here.

⛔ Every value is **read from the configuration**: the governed set, the entry
points, the reference graph, the forbidden names, anything quoted and the link
targets exempted. Nothing of
any project is written in this file, and a project whose `documents.carrier` is
`none` has no subject here -- which is reported as *no subject* rather than as a
verdict, because a check that holds nothing reports green.

⛔ **The three kinds are the whole of what a check's green is worth**, and each
check below declares which it is:

  COMPUTED   the property is derived -- a cell counted, a link resolved, a
             document walked to -- so no reword reaches it;
  FORBIDDING a name must not appear, which is sound exactly where the forbidden
             thing is stable, and the entry states what makes it so;
  QUOTING    a sentence quoted back at the file it came from. ⛔ It is performed
             as the project configured it **and warned about on every run**: it
             goes red on an innocent reword and green when the claim is reworded
             into its opposite, so neither of its verdicts says what a reader
             takes it to say.

⚠️ It needs no stack, no credential and no network: it reads the index and the
files in it. ⛔ And it runs no project's own domain checks -- whether a project's
request shapes each have a home in its layout is a property of that project's
subject matter, which § 38 leaves in that project's own script.

What is deliberately not held is stated once, with the rule, in
docs/single-source-of-truth.md#the-documents-against-the-graph-the-forbidden-list-and-their-own-blocks.

Usage:
    documents.py                  hold this project's documents
    documents.py --self-test      assert each check reddens on each planted
                                  violation and stays green on each planted
                                  exemption, then stop
"""

from __future__ import annotations

import argparse
import functools
import os
import re
import subprocess
import sys
import unicodedata
from urllib.parse import unquote

# The facet this copy holds a tree to, and where that tree's documents are read
# from relative to the repository this copy sits in. ⛔ `None` in the toolkit's
# own copy and rendered by the install step, which is what lets a host run this
# file in a checkout holding neither the toolkit nor the configuration -- and
# what keeps one project's graph out of every other project's gate.
STATED = {'root': '.',
 'facet': {'carrier': 'list',
           'files': ['README.md', 'CLAUDE.md', 'docs/*.md'],
           'entry_points': ['README.md', 'CLAUDE.md'],
           'graph': [{'document': 'README.md',
                      'may_name': ['docs/architecture.md',
                                   'docs/decisions.md',
                                   'docs/process.md',
                                   'docs/requirements.md'],
                      'derived_from': []},
                     {'document': 'CLAUDE.md',
                      'may_name': ['README.md',
                                   'docs/architecture.md',
                                   'docs/process.md',
                                   'docs/requirements.md'],
                      'derived_from': []},
                     {'document': 'docs/architecture.md',
                      'may_name': ['README.md',
                                   'docs/decisions.md',
                                   'docs/process.md',
                                   'docs/requirements.md'],
                      'derived_from': []},
                     {'document': 'docs/decisions.md',
                      'may_name': ['docs/architecture.md',
                                   'docs/process.md',
                                   'docs/requirements.md'],
                      'derived_from': []},
                     {'document': 'docs/process.md',
                      'may_name': ['docs/architecture.md', 'docs/decisions.md'],
                      'derived_from': []},
                     {'document': 'docs/requirements.md',
                      'may_name': ['docs/architecture.md', 'docs/decisions.md'],
                      'derived_from': []}],
           'forbidden': [{'pattern': 'SourceIngestor',
                          'reason': 'A component split into the stream and re-read '
                                    'ports is named as if it survived.',
                          'stable_because': 'A deleted type name; nothing reintroduces '
                                            'it without a code change.'},
                         {'pattern': 'SourceReset',
                          'reason': 'A re-read is described as discarding what it '
                                    'reads.',
                          'stable_because': 'A deleted type name.'},
                         {'pattern': 'reset port',
                          'reason': 'The re-read port is called by the name of the '
                                    'type it replaced.',
                          'stable_because': 'The prose spelling of that deleted type, '
                                            'which the type name alone does not '
                                            'catch.'},
                         {'pattern': 'AppContainer',
                          'reason': 'A shared container is described where each '
                                    'process has one root.',
                          'stable_because': 'A deleted type name.'},
                         {'pattern': 'PipelineStage',
                          'reason': 'The stage type is named by its old name.',
                          'stable_because': 'A deleted type name.'},
                         {'pattern': 'RetrievalService',
                          'reason': 'A second thing called retrieval is described.',
                          'stable_because': 'A deleted type name.'},
                         {'pattern': 'Task\\.kt|Deal\\.kt',
                          'reason': 'Domain files that do not exist are named.',
                          'stable_because': 'Deleted file names.'},
                         {'pattern': ':core',
                          'reason': 'A module proposal that was resolved is described '
                                    'as open.',
                          'stable_because': 'A module path settings.gradle.kts does '
                                            'not declare.'},
                         {'pattern': '(^|[^a-z])model/ +#',
                          'reason': 'A layout tree lists a package that does not '
                                    'exist.',
                          'stable_because': 'A deleted package path, matched in the '
                                            "tree's own comment form."},
                         {'pattern': 'four Gradle modules',
                          'reason': 'The module count is stated as one the build no '
                                    'longer has.',
                          'stable_because': 'A count that settings.gradle.kts '
                                            'contradicts; the computed check states '
                                            'the right one.'},
                         {'pattern': '\\b(previously|used to be|reversed|decided '
                                     'since|formerly|we changed|was rewritten|has been '
                                     'changed)\\b',
                          'reason': 'A document carries an audit trail, which rule 4 '
                                    'keeps in git, issues and pull requests.',
                          'stable_because': 'The words rule 4 bans, listed in the rule '
                                            'itself.',
                          'exempt': [{'glob': 'CLAUDE.md',
                                      'reason': 'It states rule 4, and so names the '
                                                'words the rule forbids.'}]},
                         {'pattern': '[Ѐ-ӿ]',
                          'reason': 'A document carries corpus-script text, which rule '
                                    '3 forbids.',
                          'stable_because': 'A Unicode block, not a wording.'},
                         {'pattern': '\\.\\./\\.\\./|/Users/|/home/[a-z]',
                          'reason': 'A document names a path outside this repository, '
                                    'which rule 7 forbids.',
                          'stable_because': 'The shape of an absolute or escaping '
                                            'path, not a particular one.'},
                         {'pattern': '\\([0-9]{1,2}:[0-9]{2}(:[0-9]{2})?\\)',
                          'reason': 'A document cites a position in a recording a '
                                    'reader cannot open.',
                          'stable_because': 'A bare parenthesised timestamp is written '
                                            'for nothing else here; durations are '
                                            'written in words.'}],
           'quoted': [],
           'box_diagrams': {'held': True, 'exempt': []}}}

FACET = "documents"
LIST = "list"

COMPUTED, FORBIDDING, QUOTING = "computed", "forbidding", "quoting"

# A markdown inline link or image destination. The fragment is dropped: an
# anchor is a position inside the target and the edge is to the file.
LINK = re.compile(r"\]\(([^)\s]+)\)")
ABSOLUTE = re.compile(r"^[a-z][a-z0-9+.-]*:", re.I)
FENCE = re.compile(r"^[ \t]*(?P<delim>`{3,}|~{3,})(?P<info>[^\n]*)$")
# A delimiter row: pipes, dashes, colons and space, with at least one dash. The
# dash is required, so `|   |   |` is a row with nothing in it rather than the
# shape that makes the rows above it a table.
DELIMITER = re.compile(r"^[ \t]*\|[-|: \t]*$")

# --- what the link check reads ---------------------------------------------
#
# A link's destination, in each of the forms a renderer follows: inline, with or
# without angle brackets and a title, and a reference definition on a line of
# its own. ⛔ A footnote definition (`[^1]: …`) is prose and not a destination.
# ⚠️ A bare destination may carry balanced parentheses, which the host follows:
# `a_(b).png` cut at its first `)` is a working link reported missing.
INLINE_LINK = re.compile(
    r"\]\(\s*(?:<(?P<angled>[^>\n]*)>|(?P<bare>(?:[^()\s]|\([^()\s]*\))+))"
    r"(?:\s+[^)\n]*)?\)"
)
DEFINITION = re.compile(
    r"^ {0,3}\[(?!\^)[^\]\n]+\]:[ \t]*(?:<(?P<angled>[^>\n]*)>|(?P<bare>\S+))"
)
# What a renderer shows rather than follows: an HTML comment, and a code span
# held to one line -- an odd backtick otherwise pairs with one a paragraph away
# and hides every link between them.
SHOWN = re.compile(
    r"<!--.*?(?:-->|\Z)|(?P<ticks>`+)(?:(?!(?P=ticks))[^\n])*(?P=ticks)", re.S
)
# A fragment the host answers that names no heading: a line range.
A_LINE_RANGE = re.compile(r"^L\d+(?:C\d+)?(?:-L?\d+(?:C\d+)?)?$")
HEADING = re.compile(r"^ {0,3}(#{1,6})[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$")
# The underline of a setext heading, which makes the line above it a heading.
UNDERLINE = re.compile(r"^ {0,3}(?:=+|-+)[ \t]*$")
EXPLICIT_ANCHOR = re.compile(r"""<a\s[^>]*?\b(?:id|name)\s*=\s*["']([^"']+)["']""", re.I)
MARKDOWN = (".md", ".markdown")


def flat(text: str) -> str:
    """The text with every run of whitespace collapsed to one space.

    ⛔ Here because a document is **wrapped**: a sentence quoted against one
    spans a line break, and a comparison that did not flatten both sides would
    report a sentence that is there as gone.
    """
    return " ".join(text.split())


def glob_regex(glob: str) -> re.Pattern:
    """A governed-set glob, where `*` stops at a separator and `**` crosses one.

    ⛔ **Deliberately not the end-to-end register's semantics** -- § 15's
    consumer matches a case glob with the shell's own wildcards, where `*`
    crosses a separator -- and the difference is stated rather than left to be
    discovered: a
    governed set is a statement about *where* a project's documents live, so a
    wildcard that crossed a separator could not say *the root's own markdown and
    not a nested directory's* at all -- and a project writing `*.md` would
    silently govern every markdown file in its tree, the skills and the vendored
    ones among them.
    """
    out, index = [], 0
    while index < len(glob):
        char = glob[index]
        if glob.startswith("**/", index):
            out.append(r"(?:[^/]+/)*")
            index += 3
        elif glob.startswith("**", index):
            out.append(r".*")
            index += 2
        elif char == "*":
            out.append(r"[^/]*")
            index += 1
        elif char == "?":
            out.append(r"[^/]")
            index += 1
        else:
            out.append(re.escape(char))
            index += 1
    return re.compile("^" + "".join(out) + "$")


def governed(path: str, globs) -> bool:
    return any(glob_regex(glob).match(path) for glob in globs)


def fences(text: str) -> tuple:
    """The line numbers inside a fenced block, and whether one was left open.

    Returned as the pair, because an unclosed fence is one of the checks and the
    scan that finds it is the scan every other check needs anyway.
    """
    inside, open_at, lines = set(), None, text.splitlines()
    for number, line in enumerate(lines, 1):
        found = FENCE.match(line)
        if found and open_at is None:
            open_at = (number, found.group("delim"))
            continue
        if found and open_at is not None:
            # ⛔ A closing delimiter is at least as long as the opening one and
            # of the same character, which is the format's own rule: a ``` line
            # inside a ~~~ block is content, and treating it as a close would
            # make every nested example read as prose.
            delim = found.group("delim")
            if delim[0] == open_at[1][0] and len(delim) >= len(open_at[1]):
                open_at = None
                continue
        if open_at is not None:
            inside.add(number)
    return frozenset(inside), open_at


def mentioned(text: str, path: str, named_as: str | None = None) -> bool:
    """Whether the text names this document, by its own spelling or by its path.

    ⛔ **`named_as` is the project's own spelling and the path is only the
    default.** ⚠️ **Measured on one of the two implementations this facet was
    read out of**: it refers to each of its eight role carriers by that
    carrier's **slug** and never by its filename, so matching on the path alone
    reported thirty of its declared edges as edges the text had dropped.

    The rest of this docstring is about the default form.

    ⛔ **The boundary is the whole of what makes a bare path usable as an edge**,
    and a plain substring test is wrong in exactly the case it is needed for. ⚠️
    **Measured against the implementation this property was read out of**: that
    project carries a root `CLAUDE.md`, a `tech/CLAUDE.md` and a
    `main_website-CLAUDE.md`, and it recognises the first with three lookbehinds
    of its own -- `(?<!tech/)`, `(?<!main_website/)`, `(?<!main_website-)`. A
    substring test reports an edge to the root file from every document that
    names either of the others.

    ⛔ **So the boundary reproduces those patterns and the facet declares no key
    for them**, which is what makes that project's rules expressible here
    without a special case: every one of its per-node lookbehinds is a leading
    path character.

    ⚠️ **A trailing `.` is admitted on purpose**: a mention at the end of a
    sentence is the commonest form of all, and refusing it would cost far more
    than the longer-path-with-the-same-prefix case it would catch.
    """
    if named_as:
        return re.search(named_as, text) is not None
    return re.search(
        r"(?<![\w/.\-])" + re.escape(path) + r"(?![\w/\-])", text
    ) is not None


def names(text: str, here: str, documents) -> set:
    """Every governed document this text names, by link target or by its spelling.

    ⛔ Both forms, because either alone is half the edge: a project writes most
    of its references as links and some as a bare name in prose or in a listing,
    and a reader of one form reports the other as an edge the row invented.

    ⚠️ `documents` maps each document to its `named_as`, or to `None` where the
    row states none and the path is the spelling.
    """
    found = set()
    for target in LINK.findall(text):
        path = target.split("#", 1)[0]
        if not path or ABSOLUTE.match(path):
            continue
        resolved = os.path.normpath(os.path.join(os.path.dirname(here), path))
        if resolved in documents:
            found.add(resolved)
    for other, named_as in documents.items():
        if mentioned(text, other, named_as):
            found.add(other)
    found.discard(here)
    return found


def links(text: str) -> list:
    """Every link destination a renderer would follow, as (line number, destination).

    ⛔ **What a document shows is not what it links**: a destination inside a
    fence, a code span or an HTML comment is an example, and reading it as live
    refuses the prose that documents a link's own syntax. The shown spans are
    blanked character for character, so every line keeps its number.
    """
    inside, _open = fences(text)
    blanked = SHOWN.sub(lambda found: re.sub(r"[^\n]", " ", found.group(0)), text)
    out = []
    for number, line in enumerate(blanked.splitlines(), 1):
        if number in inside:
            continue
        for found in INLINE_LINK.finditer(line):
            out.append((number, found.group("angled") or found.group("bare") or ""))
        definition = DEFINITION.match(line)
        if definition:
            out.append(
                (number, definition.group("angled") or definition.group("bare") or "")
            )
    return out


def slug(heading: str) -> str:
    """A heading's anchor, the way the code host derives it.

    ⛔ **One hyphen per space and no collapsing**: a heading carrying a dash
    between two spaces is answered with two hyphens, so a collapsing slug
    refuses a working link. A link inside a heading contributes its text, which
    is what the host renders.
    """
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading)
    text = re.sub(r"<[^>]+>", "", text).strip().lower().replace("`", "")
    text = re.sub(r"[^\w\s-]", "", text)
    return text.replace(" ", "-")


@functools.lru_cache(maxsize=None)
def anchors(text: str) -> frozenset:
    """Every fragment a document answers: its headings, and the anchors it writes.

    ⚠️ A repeated heading carries the host's `-1`, `-2` suffix, so a link to the
    second section of a title resolves and the first does not stand in for it.
    ⚠️ Both heading forms are read -- a `#` line, and a line underlined with `=`
    or `-` -- since a link into either is a link the host follows. A line inside
    the opening frontmatter is no heading, whatever follows it.

    ⛔ Cached, because one target is asked about once per link into it: uncached,
    this repository's own run spent 24 seconds slugging one surface again for
    each of its two thousand fragments.
    """
    inside, _open = fences(text)
    lines = text.splitlines()
    front = set()
    if lines and lines[0].strip() == "---":
        for number, line in enumerate(lines[1:], 2):
            if line.strip() == "---":
                front.update(range(1, number + 1))
                break
    found, seen = set(), {}

    def add(title: str) -> None:
        base = slug(title)
        nth = seen.get(base, 0)
        seen[base] = nth + 1
        found.add(base if nth == 0 else f"{base}-{nth}")

    for number, line in enumerate(lines, 1):
        if number in inside or number in front:
            continue
        heading = HEADING.match(line)
        if heading:
            add(heading.group(2))
            continue
        above = lines[number - 2] if number > 1 else ""
        if UNDERLINE.match(line) and above.strip() and number - 1 not in inside \
                and number - 1 not in front and not HEADING.match(above) \
                and not UNDERLINE.match(above) \
                and not above.lstrip().startswith(("|", ">", "- ", "* ", "+ ")):
            add(above)
    blanked = SHOWN.sub(lambda shown: re.sub(r"[^\n]", " ", shown.group(0)), text)
    found.update(anchor.lower() for anchor in EXPLICIT_ANCHOR.findall(blanked))
    return frozenset(found)


class Subject:
    """The governed documents, the graph and the lists, in memory.

    A copy is a copy: the plants below mutate it rather than the working tree, so
    a self-test leaves no file behind in the checkout it ran in.
    """

    def __init__(self, root, facet, tracked, text):
        self.root = root
        self.files = list(facet.get("files") or ())
        self.entry_points = list(facet.get("entry_points") or ())
        self.rows = {
            row["document"]: {
                # ⛔ `None` is kept as `None`: it is a third state, the edge
                # this project does not enforce for that document, and a reader
                # that coerced it to `[]` would turn the strictest rule in the
                # facet on for a project that declared no graph at all.
                "may_name": (
                    None if row.get("may_name") is None
                    else list(row.get("may_name") or ())
                ),
                "derived_from": list(row.get("derived_from") or ()),
                "named_as": row.get("named_as"),
            }
            for row in facet.get("graph") or ()
        }
        self.forbidden = [dict(entry) for entry in facet.get("forbidden") or ()]
        self.quoted = [dict(entry) for entry in facet.get("quoted") or ()]
        # ⛔ Absent is `[]`: omitting the key exempts nothing, which is the
        # strict direction and the only one a default may take here (§ 38).
        self.exempt = list(facet.get("exempt_targets") or ())
        # ⛔ `None` where the project states no block, kept apart from a block
        # reading `held: false` only so a run can say which; both hold nothing.
        self.box_diagrams = (
            None if facet.get("box_diagrams") is None
            else {
                "held": bool(facet["box_diagrams"].get("held")),
                "exempt": [dict(e) for e in facet["box_diagrams"].get("exempt") or ()],
            }
        )
        self.tracked = sorted(tracked)
        self.text = dict(text)

    @property
    def diagrams_held(self) -> bool:
        return bool(self.box_diagrams and self.box_diagrams["held"])

    @property
    def set_now(self) -> list:
        """The tracked files the globs govern, which is the set every check is over."""
        return [path for path in self.tracked if governed(path, self.files)]

    def is_entry(self, path: str) -> bool:
        return any(glob_regex(glob).match(path) for glob in self.entry_points)

    @property
    def spellings(self) -> dict:
        """Each document in the graph or the governed set, to how prose names it."""
        found = {path: None for path in self.set_now}
        for path, row in self.rows.items():
            found[path] = row.get("named_as")
        return found

    @property
    def enforced(self) -> list:
        """The governed documents whose row states an enforced edge.

        ⛔ The three graph checks are over these and no others. Where there are
        none they return nothing and `warnings` is what says the graph half holds
        nothing -- a project that enforces no edge is a legitimate configuration,
        so what it is owed is the disclosure rather than a permanent red.
        """
        return [
            path for path in self.set_now
            if path in self.rows and self.rows[path]["may_name"] is not None
        ]

    @property
    def unenforced(self) -> list:
        """The governed documents whose row states `may_name: null`.

        ⛔ **A document with no row at all is not one of these**, and the two were
        measured collapsed: keyed on a missing row reading as `null`, a document
        the graph had never declared was reported as one this project *decided*
        not to enforce -- and a tree where no document had a row at all was
        reported as one where every row states `null`. Both are a different
        fault, and check 1 is what reports it.
        """
        return [
            path for path in self.set_now
            if path in self.rows and self.rows[path]["may_name"] is None
        ]

    @property
    def indexed(self) -> set:
        """Every path a link may resolve to: each tracked file and each directory above one.

        ⛔ **The index and not the disk**, for the reason the governed set is read
        from it: a file nobody added is a link target the host does not serve.
        """
        found = set(self.tracked)
        for path in self.tracked:
            parts = path.split("/")[:-1]
            for depth in range(1, len(parts) + 1):
                found.add("/".join(parts[:depth]))
        return found

    def read(self, path: str):
        return self.text.get(path, FileNotFoundError("not in the index"))

    def read_target(self, path: str):
        """A link target's text: the governed set's copy, or the file itself.

        A markdown target the globs do not govern is still a target whose
        headings a fragment names, so it is read from the tree rather than held
        to nothing.
        """
        if path in self.text:
            return self.text[path]
        try:
            with open(os.path.join(self.root, path), encoding="utf-8") as handle:
                return handle.read()
        except (OSError, UnicodeDecodeError) as exc:
            return exc

    def copy(self) -> "Subject":
        clone = Subject.__new__(Subject)
        clone.root, clone.files = self.root, list(self.files)
        clone.entry_points = list(self.entry_points)
        clone.rows = {doc: dict(row) for doc, row in self.rows.items()}
        clone.forbidden = [dict(entry) for entry in self.forbidden]
        clone.quoted = [dict(entry) for entry in self.quoted]
        clone.exempt = list(self.exempt)
        clone.box_diagrams = (
            None if self.box_diagrams is None
            else {
                "held": self.box_diagrams["held"],
                "exempt": [dict(e) for e in self.box_diagrams["exempt"]],
            }
        )
        clone.tracked, clone.text = list(self.tracked), dict(self.text)
        return clone


def read_subject(root: str, facet: dict) -> Subject:
    """The subject read off one tree: the index, and each governed file's text."""
    out = subprocess.run(
        ["git", "-C", root, "ls-files"], capture_output=True, text=True,
    )
    tracked = sorted(path for path in out.stdout.split("\n") if path)
    files = list(facet.get("files") or ())
    text = {}
    for path in tracked:
        if not governed(path, files):
            continue
        try:
            with open(os.path.join(root, path), encoding="utf-8") as handle:
                text[path] = handle.read()
        except (OSError, UnicodeDecodeError) as exc:
            # ⛔ A tracked file missing from the tree, or one that is not text, is
            # a problem this check reports -- never a traceback out of the command
            # a workflow runs. A file deleted without `git rm` is the common one.
            text[path] = exc
    return Subject(root, facet, tracked, text)


# --- the checks -------------------------------------------------------------


def check_every_governed_document_has_a_row(subject: Subject) -> list:
    """The governed set against the graph, both ways, and the files behind it.

    ⛔ A tracked document the globs govern and no row declares is the one that
    matters: a new store of prose is exactly the thing that quietly becomes a
    second authority, and a graph that does not know about it never checks it at
    all. ⚠️ **Not a skip** -- the permissive reading is what leaves it unheld.
    """
    if not subject.set_now:
        return [
            "the globs this project states govern no tracked file, so every check "
            "here passes for a reason that has nothing to do with the documents. "
            f"The globs are {subject.files}; the index was read with `git ls-files`, "
            "so a document is governed only once it is indexed."
        ]
    problems = []
    for path in subject.set_now:
        if path not in subject.rows:
            problems.append(
                f"{path}: is governed by {subject.files} and no row of `documents.graph` "
                "declares it, so it sits outside the graph and nothing holds it. Give it "
                "a row, which is a decision about where it sits, or narrow the globs."
            )
        text = subject.read(path)
        if isinstance(text, Exception):
            problems.append(
                f"{path}: is governed and could not be read ({text}). A document the "
                "sweep cannot open is unheld, which is the one state this check may not "
                "report as green."
            )
    for document in sorted(subject.rows):
        if document not in subject.set_now:
            problems.append(
                f"{document}: has a row of `documents.graph` and is not a governed "
                "tracked file. Every assertion over a document that moved otherwise "
                "reports green, which is the same failure from the other side."
            )
    return problems


def check_a_document_names_only_what_its_row_allows(subject: Subject) -> list:
    """The enforced edge, in the direction a filename appearing is the defect.

    ⛔ An empty row may name nothing at all, and that is where the teeth are: a
    filename in such a document is an edge running the wrong way.
    """
    # ⛔ Where nothing is enforced this check holds nothing, and what it owes is
    # the **disclosure** rather than a red: a project that declares no reference
    # graph is a legitimate configuration -- one of the two this facet was read
    # out of is exactly that -- and a permanent red is not a check. `warnings`
    # below says so on every run.
    problems = []
    documents = subject.spellings
    for path in subject.enforced:
        text = subject.read(path)
        if isinstance(text, Exception):
            continue
        allowed = set(subject.rows[path]["may_name"])
        for named in sorted(names(text, path, documents) - allowed):
            problems.append(
                f"{path}: names {named!r}, which its `may_name` row does not allow"
                + (
                    " -- and that row is empty, so this document may name no other "
                    "document at all."
                    if not allowed
                    else f". It may name: {sorted(allowed)}."
                )
            )
    return problems


def check_a_row_names_only_edges_the_text_carries(subject: Subject) -> list:
    """The enforced edge, in the direction nothing else reports.

    ⛔ Held because one direction is half a check: without this, a row stating an
    edge the text has dropped stays green for ever, and the graph goes on being
    read as the document's own contents.
    """
    problems = []
    documents = subject.spellings
    for path in subject.enforced:
        text = subject.read(path)
        if isinstance(text, Exception):
            continue
        carried = names(text, path, documents)
        for named in subject.rows[path]["may_name"]:
            if named not in carried:
                problems.append(
                    f"{path}: its `may_name` row states {named!r} and the text does not "
                    "name it. A row's edge the document dropped is a graph that no longer "
                    "describes the documents -- delete the edge, or restore the reference."
                )
    return problems


def check_every_document_is_reachable(subject: Subject) -> list:
    """Reachability from an entry point, which no per-file check can see.

    ⛔ Entry points are a **list** rather than one root, because a document
    addressed by name from outside the prose is arrived at without any document
    naming it -- a role carrier loaded by a harness being the measured case.
    """
    if subject.set_now and not subject.enforced:
        # ⛔ With no edge enforced anywhere there is no graph to walk at all, so
        # every governed document would be reported unreachable -- one state
        # reported as many findings. The disclosure is `warnings`'s.
        return []
    reached, frontier = set(), []
    for path in subject.set_now:
        if subject.is_entry(path):
            reached.add(path)
            frontier.append(path)
    while frontier:
        path = frontier.pop()
        for named in subject.rows.get(path, {}).get("may_name") or ():
            if named not in reached:
                reached.add(named)
                frontier.append(named)
    problems = []
    if not reached and subject.set_now:
        problems.append(
            f"no governed document matches an entry point. The entry points are "
            f"{subject.entry_points}, so nothing is a starting place and every document "
            "below is unreachable for that one reason."
        )
    for path in subject.set_now:
        if path not in reached:
            problems.append(
                f"{path}: is governed and no entry point reaches it by a `may_name` edge. "
                "A document nothing names and nothing addresses is a document no reader "
                "arrives at -- have something name it, or state it as an entry point and "
                "say why it is one."
            )
    return problems


def held_by(entry: dict, governed_now: list) -> tuple:
    """The governed documents one forbidding entry holds, and its globs that match nothing.

    ⛔ An entry stating neither `covers` nor `exempt` holds every governed
    document, which is the only default. ⚠️ An exemption is judged against what
    the entry would otherwise hold rather than against the whole governed set,
    so an exempt glob outside `covers` is reported rather than read as an
    exemption that did its job.
    """
    stale = []
    covers = entry.get("covers")
    if covers is None:
        candidates = list(governed_now)
    else:
        candidates = []
        for at, glob in enumerate(covers):
            matched = [path for path in governed_now if glob_regex(glob).match(path)]
            if not matched:
                stale.append((f"covers[{at}]", glob, "no governed document"))
            candidates.extend(path for path in matched if path not in candidates)
    held = list(candidates)
    for at, item in enumerate(entry.get("exempt") or ()):
        glob = item.get("glob") or ""
        matched = [path for path in candidates if glob_regex(glob).match(path)]
        if not matched:
            stale.append((f"exempt[{at}].glob", glob, "no document this entry holds"))
        held = [path for path in held if path not in matched]
    # ⛔ Every glob may match and the entry still hold nothing, where its
    # exemptions take out all it covers -- the same scope holding nothing,
    # reached through globs each of which is live.
    if candidates and not held:
        globs = ", ".join(str(item.get("glob")) for item in entry.get("exempt") or ())
        stale.append(("exempt", globs, "every document this entry would hold"))
    return sorted(held), stale


def check_every_link_resolves(subject: Subject) -> list:
    """Every relative link in a governed document reaching something the index holds.

    ⛔ Computed: the target is resolved against the index and the fragment
    against the target's own headings, so no reword of the link text reaches it.
    ⛔ **An address outside the repository is not fetched** -- it names a host
    this check has no business asking, and a network read would make the verdict
    a fact about the day it ran. ⚠️ Two fragments are accepted unresolved: a line
    range, which the host answers and no heading carries, and one on a file that
    is not markdown, which has no headings to hold it to.
    """
    problems = []
    present = subject.indexed
    for path in subject.set_now:
        text = subject.read(path)
        if isinstance(text, Exception):
            continue
        for number, destination in links(text):
            if not destination or ABSOLUTE.match(destination) \
                    or destination.startswith("//"):
                continue
            target, _hash, fragment = destination.partition("#")
            target, fragment = unquote(target), unquote(fragment)
            if not target:
                resolved = path
            elif target.startswith("/"):
                resolved = os.path.normpath(target.lstrip("/") or ".")
            else:
                resolved = os.path.normpath(os.path.join(os.path.dirname(path), target))
            where = f"{path}:{number}: links to {destination!r}"
            # ⚠️ A link climbing out of the repository needs no arm of its own:
            # it resolves to a `../` path no index holds, and is reported below.
            # ⚠️ A link to an exempt directory itself is exempt: `site/**`
            # covers `site/`, which is the directory a generated index sits in.
            if resolved == "." or governed(resolved, subject.exempt) \
                    or governed(resolved + "/", subject.exempt):
                continue
            if resolved not in present:
                problems.append(
                    f"{where}, which resolves to {resolved!r}, and the index holds no file "
                    "or directory there. Restore the target, correct the link, or -- for a "
                    "file the build generates -- exempt it in `documents.exempt_targets`."
                )
                continue
            if not fragment or A_LINE_RANGE.match(fragment) \
                    or not resolved.lower().endswith(MARKDOWN):
                continue
            body = subject.read_target(resolved)
            if isinstance(body, Exception):
                problems.append(
                    f"{where}, and {resolved!r} could not be read ({body}), so whether it "
                    f"carries {fragment!r} is unanswered rather than passed."
                )
                continue
            if fragment.lower() not in anchors(body):
                problems.append(
                    f"{where}, and {resolved!r} carries no heading or anchor whose slug is "
                    f"{fragment!r}. A heading renamed or removed leaves every link into it "
                    "landing at the top of the document."
                )
    return problems


def check_no_forbidden_name_appears(subject: Subject) -> list:
    """The forbidding kind, over the governed set, each entry as the project wrote it."""
    problems = []
    for index, entry in enumerate(subject.forbidden):
        pattern = entry.get("pattern") or ""
        try:
            compiled = re.compile(pattern)
        except re.error as exc:
            # ⛔ A pattern that cannot be evaluated is reported rather than
            # treated as matching nothing: that is how a typo becomes an
            # assertion that can never fail again.
            problems.append(
                f"documents.forbidden[{index}]: {pattern!r} is not a usable expression "
                f"({exc}), so this check could not be evaluated at all. A pattern that "
                "does not compile is never *no match*."
            )
            continue
        held, stale = held_by(entry, subject.set_now)
        for where, glob, what in stale:
            problems.append(
                f"documents.forbidden[{index}].{where}: {glob!r} matches {what}, so "
                "this scope holds nothing it says it holds. A scope that went stale "
                "when a file moved is never read as the entry having nothing to find."
            )
        for path in held:
            text = subject.read(path)
            if isinstance(text, Exception):
                continue
            for number, line in enumerate(text.splitlines(), 1):
                if compiled.search(line):
                    problems.append(
                        f"{path}:{number}: carries {pattern!r}, which this project "
                        f"forbids: {' '.join(str(entry.get('reason')).split())}"
                    )
    return problems


def delimiters(line: str) -> int:
    """The cell delimiters in a row, which is every `|` the table's own grammar reads.

    ⛔ **An escaped `\\|` is one character of a cell and not a delimiter**, and
    this is the arm the obvious count gets wrong. ⚠️ **Measured on this
    repository's own decision record, on this check's first real run**: a ruling
    whose cell carries `` `high \\| medium \\| low` `` was reported as a row with
    two cells too many, while the document renders exactly as its author wrote
    it. The implementation this property was read out of counts the raw
    character and has the same defect.

    ⚠️ A doubled backslash is a literal backslash, so the pipe after it *is* a
    delimiter -- counted by consuming the escape rather than by looking back one
    character, which is what makes that case come out right.
    """
    found, index = 0, 0
    while index < len(line):
        char = line[index]
        if char == "\\":
            index += 2
            continue
        if char == "|":
            found += 1
        index += 1
    return found


def cells(line: str) -> int:
    """The cells a row carries, with the optional closing delimiter discounted.

    ⛔ **The closing `|` is optional in the format and a raw delimiter count
    treats it as a cell.** ⚠️ Measured: a table whose last row is written
    `| one | two` -- two cells, no closing delimiter -- was reported ragged
    against the rows above it, while a renderer reads the two forms
    identically. ⚠️ Whether the last character *is* a delimiter is asked by
    counting rather than by looking at it, so a row ending in an escaped `\\|`
    is not discounted.
    """
    found = delimiters(line)
    if line.endswith("|") and found > delimiters(line[:-1]):
        return found - 1
    return found


def _tables(text: str, inside) -> list:
    """Every run of consecutive pipe lines outside a fence, each as [(number, line)]."""
    runs, current = [], []
    for number, line in enumerate(text.splitlines(), 1):
        if number in inside:
            continue
        if line.lstrip().startswith("|"):
            current.append((number, line.rstrip()))
            continue
        if current:
            runs.append(current)
            current = []
    if current:
        runs.append(current)
    return runs


def check_a_table_keeps_its_shape(subject: Subject) -> list:
    """Every row of a table carrying the same number of cells.

    ⛔ Computed, and the kind is the point: a row short of a cell renders with
    every value after the gap under the wrong column, which no forbidding check
    could express -- there is nothing to ban.
    """
    problems = []
    for path in subject.set_now:
        text = subject.read(path)
        if isinstance(text, Exception):
            continue
        inside, _open = fences(text)
        for run in _tables(text, inside):
            # ⛔ A run of one line has nothing to compare, so the shape check is
            # silent about it and the **block** check is what reports it: a lone
            # line of pipes carries no delimiter row. ⚠️ Its plant is therefore
            # in that check and not here, which is where the first form of this
            # file put a green-side plant that no mutation of this guard decided.
            if len(run) < 2:
                continue
            widths = {cells(line) for _n, line in run}
            if len(widths) != 1:
                problems.append(
                    f"{path}:{run[0][0]}-{run[-1][0]}: the rows of this table carry "
                    f"{sorted(widths)} cell delimiters. A row short of a cell takes the "
                    "next column's value, and the table reads as consistent."
                )
    return problems


def check_a_table_row_keeps_its_table(subject: Subject) -> list:
    """A line of pipes that is a paragraph, which is the measured hole.

    ⚠️ Measured on this repository: a ruling appended after the blank line that
    ends a table was answered `<p>| 32 | … |</p>` by the host's own renderer
    while every check over that document was green -- each of them holding a
    line, or holding the document against the surface, and none its blocks.

    ⛔ The defect is the **absence** of a delimiter row under a line that begins
    a table, so there is no name and no pattern to forbid: this is the kind a
    forbidding check cannot express.
    """
    problems = []
    for path in subject.set_now:
        text = subject.read(path)
        if isinstance(text, Exception):
            continue
        inside, _open = fences(text)
        for run in _tables(text, inside):
            if any(DELIMITER.match(line) for _n, line in run):
                continue
            problems.append(
                f"{path}:{run[0][0]}" + (f"-{run[-1][0]}" if len(run) > 1 else "")
                + ": this run of pipe lines carries no delimiter row, so a renderer "
                "reads it as a paragraph with its pipes showing rather than as a table. "
                "A row separated from its own table by a blank line is the measured case."
            )
    return problems


def check_no_document_leaves_a_fence_open(subject: Subject) -> list:
    """An unclosed fence, which stops every check after it seeing prose at all."""
    problems = []
    for path in subject.set_now:
        text = subject.read(path)
        if isinstance(text, Exception):
            continue
        _inside, open_at = fences(text)
        if open_at:
            number, delim = open_at
            problems.append(
                f"{path}:{number}: opens a {delim!r} fence that never closes, so every "
                "line after it is read as code -- by a renderer, and by every check over "
                "this document that skips fenced lines."
            )
    return problems


def check_a_quoted_sentence_is_still_in_its_document(subject: Subject) -> list:
    """The kind that does not work, performed as the project configured it.

    ⛔ Its warning is emitted on every run whatever this returns, by
    `warnings` below: what a project is owed here is the disclosure, not a
    refusal to run the check it asked for.
    """
    problems = []
    for index, entry in enumerate(subject.quoted):
        document, sentence = entry.get("document"), entry.get("sentence") or ""
        text = subject.read(document)
        if isinstance(text, Exception):
            problems.append(
                f"documents.quoted[{index}]: {document!r} could not be read ({text}), so "
                "the sentence quoted against it was compared with nothing."
            )
            continue
        # ⛔ **Both sides flattened, because a document is hard-wrapped.** ⚠️
        # Measured on this very facet: a sentence present in a document that
        # wraps at a stated column spans a line break, so a raw substring test
        # reports it missing -- which this kind then reads as the claim having
        # been reworded away. A quoting check that cannot find a sentence that
        # is there is worse than the kind's own failure modes.
        if flat(sentence) not in flat(text):
            problems.append(
                f"{document}: no longer carries the quoted sentence "
                f"{sentence[:60]!r}. ⚠️ Read this as the kind says: the document may have "
                "been reworded without the claim changing, in which case the entry is "
                "what is wrong."
            )
    return problems


# --- box diagrams ------------------------------------------------------------
#
# ⛔ The arms of a box-drawing character are read off its **Unicode name** and
# never tabulated: the block names every character by the directions it reaches
# -- `BOX DRAWINGS LIGHT DOWN AND RIGHT`, `... VERTICAL AND HORIZONTAL`,
# `... DOWN SINGLE AND RIGHT DOUBLE` -- so the light, heavy, double, arc and
# mixed forms are one rule, and a table here would be a second statement of the
# block free to disagree with it.

ARM_WORDS = {
    "UP": {"up"}, "DOWN": {"down"}, "LEFT": {"left"}, "RIGHT": {"right"},
    "VERTICAL": {"up", "down"}, "HORIZONTAL": {"left", "right"},
}
OPPOSITE = {"up": "down", "down": "up", "left": "right", "right": "left"}
STEP = {"up": (-1, 0), "down": (1, 0), "left": (0, -1), "right": (0, 1)}


def arms(char: str) -> frozenset:
    """The directions a character reaches, empty for anything that is not a line.

    ⚠️ An arrowhead is outside the block and carries no arm, so it reads as text:
    a tip touching a junction and a tail leaving one are each drawn correctly,
    and no direction an arrowhead could be given would tell them apart.
    """
    if not "\u2500" <= char <= "\u257f":
        return frozenset()
    found = set()
    for word in unicodedata.name(char, "").split():
        found |= ARM_WORDS.get(word, set())
    return frozenset(found)


def is_junction(char: str) -> bool:
    """A corner, a tee or a cross: a box character reaching both ways at once.

    ⛔ **Only these are held.** A straight run and a half line may end where the
    drawing does -- a tree listing's `├── name` ends every horizontal run in a
    space -- while a corner's arm meeting nothing is the misalignment itself.
    """
    found = arms(char)
    return bool(found & {"up", "down"}) and bool(found & {"left", "right"})


def width(char: str) -> int:
    """A character's display width: two where East Asian wide, none where it marks.

    ⛔ **Display width and not code points**, which is the whole of why a box
    that looks aligned in its author's editor is not: a wide character takes two
    columns, so a row carrying one is one column longer on screen per character
    than its code point count says.

    ⚠️ An ambiguous-width character -- `→`, `▶`, the box characters themselves --
    counts one, as a terminal outside an East Asian locale draws it.
    """
    if unicodedata.combining(char) or unicodedata.category(char) in ("Mn", "Me", "Cf"):
        return 0
    if unicodedata.east_asian_width(char) in ("W", "F"):
        return 2
    return 1


def grid(line: str) -> dict:
    """Each display column of a line, to the character drawn there.

    The second column of a wide character maps to `""`, which carries no arm: a
    junction whose arm lands on half of a wide character meets nothing.
    """
    cells, column = {}, 0
    for char in line:
        if char == "\t":
            column += 8 - column % 8
            continue
        size = width(char)
        if size == 0:
            continue
        cells[column] = char
        for extra in range(1, size):
            cells[column + extra] = ""
        column += size
    return cells


def diagram_blocks(text: str) -> list:
    """Every fenced block carrying a box-drawing character, as [(number, line)].

    ⛔ **Fenced, and carrying one at all**: a block drawn in ASCII is not read,
    and neither is a box character in running prose -- each is outside what the
    rule is about rather than a diagram that passed.

    ⛔ **The blocks are the runs of `fences`'s own lines**, never a second scan:
    which delimiter closes which is that function's rule, and a copy of it here
    would be free to disagree about a nested example. Two blocks are told apart
    by the delimiter lines between them, which `fences` leaves out.
    """
    inside, _open = fences(text)
    lines = text.splitlines()
    blocks, current = [], []
    for number in sorted(inside):
        if current and number != current[-1][0] + 1:
            blocks.append(current)
            current = []
        current.append((number, lines[number - 1]))
    if current:
        blocks.append(current)
    return [
        block for block in blocks
        if any(arms(char) for _n, line in block for char in line)
    ]


def misalignments(block: list) -> list:
    """Each junction arm in one block that meets no opposite arm, as (line, column, arm, met)."""
    rows = [grid(line) for _n, line in block]
    found = []
    for index, cells in enumerate(rows):
        for column, char in sorted(cells.items()):
            if not char or not is_junction(char):
                continue
            for arm in sorted(arms(char)):
                d_row, d_col = STEP[arm]
                at = index + d_row
                met = rows[at].get(column + d_col, " ") if 0 <= at < len(rows) else None
                if not meets_nothing(met, arm):
                    continue
                found.append((block[index][0], column + 1, char, arm, met))
    return found


def meets_nothing(met, arm: str) -> bool:
    """Whether what a junction's arm lands on leaves that arm drawn to nothing.

    ⛔ **Text is a label and not a defect.** ⚠️ The measured case is `tree`'s own
    output, which puts each nested `└` under the first letter of its parent's
    name and the first `├` under the root's: read strictly, every listing it
    prints is red. What is a misalignment is the arm landing on a space, past
    the block's edge, on either column of a wide character -- where a box drawn
    by code point count puts its corner -- or on a line that does not reach back.
    """
    if met is None or met == "" or met == " ":
        return True
    # ⛔ **Either column of a wide character, and not only its second.** ⚠️
    # Measured on this check's own plant: a box drawn by code point count around
    # two wide characters puts its corner over the *first* column of the second
    # one, which the text arm below then read as a label -- so the one defect
    # display width exists to find went green. A listing that names a file in
    # wide characters and draws a line to it is the cost, and `exempt` is where
    # such a block is named.
    if width(met) == 2:
        return True
    reached = arms(met)
    if reached:
        return OPPOSITE[arm] not in reached
    return False


def check_a_box_diagram_keeps_its_edges(subject: Subject) -> list:
    """Every junction of a box diagram meeting what it was drawn to, where held.

    ⛔ Computed: the defect is an arm meeting nothing, which has no name to
    forbid. Columns are display columns, and a red names the junction's line.
    """
    if not subject.diagrams_held:
        return []
    problems = []
    exemptions = []
    for index, entry in enumerate(subject.box_diagrams["exempt"]):
        try:
            exemptions.append((index, entry, re.compile(entry.get("block") or "")))
        except re.error as exc:
            problems.append(
                f"documents.box_diagrams.exempt[{index}]: {entry.get('block')!r} is not "
                f"a usable expression ({exc}), so this exemption could not be evaluated. "
                "An exemption that does not compile is never *matches nothing*."
            )
    used = set()
    for path in subject.set_now:
        text = subject.read(path)
        if isinstance(text, Exception):
            continue
        for block in diagram_blocks(text):
            body = "\n".join(line for _n, line in block)
            exempted = [
                index for index, entry, compiled in exemptions
                if entry.get("document") == path and compiled.search(body)
            ]
            if exempted:
                used.update(exempted)
                continue
            for number, column, char, arm, met in misalignments(block):
                what = (
                    "the edge of the block" if met is None
                    else "half of a wide character" if met == ""
                    else f"{met!r}"
                )
                problems.append(
                    f"{path}:{number}: the {char!r} at display column {column} reaches "
                    f"{arm} and meets {what}, which carries no arm back -- an edge or "
                    "corner of this diagram does not line up."
                )
    for index, entry, _compiled in exemptions:
        if index not in used:
            problems.append(
                f"documents.box_diagrams.exempt[{index}]: matches no diagram in "
                f"{entry.get('document')!r}. An exemption that outlived its diagram "
                "silently covers whatever is drawn there next -- remove it, or correct "
                "the expression."
            )
    return problems


CHECKS = [
    ("every governed document has a row", COMPUTED,
     check_every_governed_document_has_a_row),
    ("a document names only what its row allows", COMPUTED,
     check_a_document_names_only_what_its_row_allows),
    ("a row names only edges the text carries", COMPUTED,
     check_a_row_names_only_edges_the_text_carries),
    ("every document is reachable from an entry point", COMPUTED,
     check_every_document_is_reachable),
    ("every link resolves", COMPUTED, check_every_link_resolves),
    ("no forbidden name appears", FORBIDDING, check_no_forbidden_name_appears),
    ("a table keeps its shape", COMPUTED, check_a_table_keeps_its_shape),
    ("a table row keeps its table", COMPUTED, check_a_table_row_keeps_its_table),
    ("no document leaves a fence open", COMPUTED, check_no_document_leaves_a_fence_open),
    ("a box diagram keeps its edges", COMPUTED, check_a_box_diagram_keeps_its_edges),
    ("a quoted sentence is still in its document", QUOTING,
     check_a_quoted_sentence_is_still_in_its_document),
]


def run(subject: Subject) -> dict:
    return {name: check(subject) for name, _kind, check in CHECKS}


def warnings(subject: Subject) -> list:
    """What the quoting kind obliges, said on every run rather than at the load.

    ⛔ **Both failure modes, in both directions**, because the one that matters is
    invisible: a project told only that quoting is fragile hears *this may go red
    on a reword* and not *this stays green when the claim is reversed*.
    """
    said = []
    # ⛔ The rows this project declines to enforce, counted on every run. Where
    # that is all of them, the three graph checks hold nothing and this is the
    # only place that says so -- a project reading their green as its documents
    # agreeing is the one misread the third state invites.
    unenforced = subject.unenforced
    if unenforced and not subject.enforced:
        said.append(
            f"every one of the {len(unenforced)} governed document(s) states "
            "`may_name: null`, so no edge is enforced: the two edge checks and the "
            "reachability check HOLD NOTHING and their green says nothing about whether "
            "these documents agree. ⛔ What is still held is the governed set, every "
            "link's target, the forbidden names and each document's own blocks."
        )
    elif unenforced:
        said.append(
            f"{len(unenforced)} of {len(subject.set_now)} governed document(s) state "
            "`may_name: null`, so the enforced edge is not held for them: "
            + ", ".join(sorted(unenforced)[:6])
            + (f" and {len(unenforced) - 6} more" if len(unenforced) > 6 else "")
            + ". ⚠️ `null` is not `[]` -- these documents may name anything."
        )
    if not subject.quoted:
        return said
    return said + [
        f"documents.quoted[{index}] quotes {str(entry.get('sentence'))[:50]!r} at "
        f"{entry.get('document')!r}. ⛔ This is the kind that does not work (§ 38): it "
        "goes RED on an innocent reword of a claim that is still true, and it stays "
        "GREEN when the sentence is reworded into its opposite. Its green says nothing "
        "about the claim. Derive the property instead, or forbid a stable name."
        for index, entry in enumerate(subject.quoted)
    ]


def hold(subject: Subject) -> int:
    """Run every check over one subject, print the verdict, and return the status.

    ⛔ One function for both callers -- the toolkit's own `main` below the
    sentinel and the vendored copy's `vendored_main` above it -- so the two
    cannot print different verdicts over the same tree.
    """
    results = run(subject)
    for name, kind, _check in CHECKS:
        found = results[name]
        print(f"  {'FAIL' if found else 'ok  '}  [{kind:<10}] {name}")
    said = warnings(subject)
    if said:
        print()
        for warning in said:
            print(f"  warn  {warning}")
    problems = [problem for found in results.values() for problem in found]
    if problems:
        print(f"\n{len(problems)} problem(s):\n")
        for problem in problems:
            print(f"  - {problem}")
        print(
            "\n⚠️ When one of these goes red, the assertion is as likely to be wrong as "
            "the document (§ 38). Read the row and the text against each other before "
            "editing either."
        )
        return 1
    # ⛔ The summary names what was **held** and nothing more. ⚠️ Measured on
    # this file's own first form: it said every document names what its row
    # allows and is reached from an entry point, which is false of a project
    # whose rows state `may_name: null` -- the three graph checks held nothing
    # there, and the sentence asserted they had passed.
    held = [
        f"all {len(subject.set_now)} governed document(s) have a row",
        "every relative link reaches a file and a heading the index holds"
        + (f" or {len(subject.exempt)} exempt glob(s) cover" if subject.exempt else ""),
        "no forbidden name appears",
        "and every one keeps its own blocks"
        + (
            ", its box diagrams' edges included" if subject.diagrams_held
            else " -- no box diagram is held, `documents.box_diagrams` "
            + ("stating `held: false`" if subject.box_diagrams else "being unstated")
        ),
    ]
    if subject.enforced:
        held.insert(1, (
            f"{len(subject.enforced)} of them name only what their row allows and all "
            "of it, and are reached from an entry point"
        ))
    print("\n" + "; ".join(held) + ".")
    return 0


def vendored_main(argv: list) -> int:
    """The rendered copy's entry point: the facet rendered in, the checkout read.

    ⛔ **It reads no configuration and imports nothing of the toolkit's**, which
    is the whole of what makes it runnable on a host: the checkout holds this
    file and the documents, and nothing else is assumed to be there.
    """
    parser = argparse.ArgumentParser(
        description="Hold this repository's documents to the facet rendered into this copy.",
    )
    # ⛔ Strict: an argument this copy does not take is refused rather than
    # ignored, so a flag meant for the toolkit's copy is never read as a run.
    parser.parse_args(argv)
    if not STATED:
        # ⛔ Not a verdict, and not green: a copy holding no facet holds nothing,
        # which the install step never writes -- so meeting one is a copy taken
        # by hand, and saying so is the answer.
        print(
            "This copy holds no facet, so there is nothing to hold a document to. The "
            "facet is the project's own and is rendered in by the install step.",
            file=sys.stderr,
        )
        return 2
    repository = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    root = os.path.normpath(os.path.join(repository, STATED["root"]))
    return hold(read_subject(root, STATED["facet"]))


if __name__ == "__main__":
    sys.exit(vendored_main(sys.argv[1:]))
