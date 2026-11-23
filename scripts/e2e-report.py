"""Translate an end-to-end run's report into what it executed and what it skipped.

Reads the report on stdin and is called by `e2e-report.sh`, whose header states the contract
this implements: the line kinds printed, and that a count never established is refused rather
than printed as `executed 0`.

A file rather than a `python3 -c` argument so that `ruff check` reaches it: this is a parser
over a test runner's output, and until this was a file nothing parsed it before it ran. The
move also retires a hazard this program used to carry — as a single-quoted argument it could
hold no apostrophe anywhere, not even in a comment, because one would have ended the argument
and left the shell to fail on the remainder.
"""

import re
import sys

report = sys.stdin.read()

# ⛔ One run per report. Executions are read from the last banner carrying counts and skips are
# summed over the whole text, so a file holding two appended runs would answer with the later
# executions against both sets of skips — a mixed figure that looks like a measurement.
sessions = report.count("test session starts")
if sessions > 1:
    sys.stderr.write(
        f"this report holds {sessions} runs, and the counts of several runs do not add up to a "
        "measurement of one. Translate each run separately.\n")
    raise SystemExit(1)

# Every outcome a case reached by running. ⛔ An error is not one of them: this runner counts a
# collector that failed to import under the same word as a case whose setup blew up, and in
# neither did a case body execute — so counting errors would report cases as run where none was.
# A run that only errored therefore comes back `executed 0`, which is the floor firing correctly.
RAN = ("passed", "failed", "xpassed", "xfailed")

# ⛔ The last banner carrying counts, not the first and not all of them. Which banner holds the
# outcome is a fact about where the runner prints it, and summing several would count one case
# repeatedly.
banners = [line for line in report.splitlines()
           if line.startswith("=") and line.rstrip().endswith("=")]
pairs: list[tuple[str, str]] = []
for line in reversed(banners):
    found = re.findall(r"(\d+) (\w+)", line)
    if found:
        pairs = found
        break

summarised = bool(pairs)
executed = sum(int(count) for count, word in pairs if word in RAN)

# ⛔ No summary line at all is a run that did not report, never a run that executed nothing.
if not summarised:
    sys.stderr.write(
        "the run reached no summary line, so nothing was counted. "
        "A count was not printed, because `executed 0` would read as a tier that ran.\n")
    raise SystemExit(1)

# One line per reason, counts summed. ⛔ A reason is required: a count with nothing saying what
# the cases wanted is a skip folded into a pass.
# ⚠️ The line number is optional because this runner drops it when it folds a skip taken during
# setup whose marker is the skip itself. A pattern demanding it would drop those cases entirely —
# no line, no count, nothing saying they were missing. The skips this suite takes today come from
# fixtures and all carry one, so the relaxed form is for the case that is not reached yet.
wanted: dict[str, int] = {}
for count, where, reason in re.findall(
        r"^SKIPPED \[(\d+)\] ([^:]+?(?::\d+)?): ?(.*)$", report, re.MULTILINE):
    reason = reason.strip() or f"the condition at {where}"
    wanted[reason] = wanted.get(reason, 0) + int(count)

# ⛔ The banner counted the skips and the lines name them, and the two have to agree. They come
# apart when the run was asked for a different report — a later -r flag wins and the SKIPPED lines
# vanish while the banner still counts them, which would print executions with the skip half
# missing. That is the shape this output exists to prevent, so a mismatch is refused rather than
# printed with a count nobody can account for.
counted = sum(int(count) for count, word in pairs if word == "skipped")
named = sum(wanted.values())
if counted != named:
    sys.stderr.write(
        f"the run reports {counted} skipped and names {named} of them, so what the missing cases "
        "wanted cannot be stated. Nothing was printed: a skip with no reason is a skip folded "
        "into a pass. Run the suite with -rs and no later -r flag.\n")
    raise SystemExit(1)

print(f"executed {executed}")
for reason, count in sorted(wanted.items()):
    print(f"skipped {count} {reason}")
