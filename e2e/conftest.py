"""Run-level wiring: the target guard, what to do when the account is not configured, and what a
run that did not do everything has to say for itself.

Two different failures, handled two different ways on purpose.

A wrong or missing target is a hard exit for the whole run. It is the guard that keeps this
suite off production, and a guard that degrades to a skip is not a guard.

An absent Telegram account is a skip, named. The stack checks do not need one, and a run that
does half the suite is worth having — provided the half it did not do says so rather than
reporting green.

**And that proviso is a mechanism here rather than a hope.** This suite's rendering cases are the
only reading anybody does of what the shapes reached by a command put in front of a reader, and
every one of them that is asked about somebody stands down without a contact subject configured —
so the run that matters most is exactly the run that can report nothing while exiting nought. The
one exception is the shape that accepts no subject, which runs regardless and is the dearest call
the suite makes; a run that stood the others down has therefore spent the most and read the least,
which is the arrangement `docs/process.md` states as an ordinary deploy's floor.

pytest's own `-rs` prints a skip
among its lines; what [pytest_terminal_summary] adds is a section of its own that says what the
run is therefore not evidence about, in the same voice the deploy uses for the tier it did not
spend on. The same section carries a property a case *ran* and could not assert — a store that
places no deadline on the subject leaves the order of a brief unasserted, and a green case would
otherwise read as one that checked it.
"""

import pytest

# Aliased because pytest's hooks take an argument called `config` and match it by name, so a
# module of the same name cannot be in scope where a hook is defined.
import config as harness_config
import store


def pytest_configure(config):
    config.addinivalue_line("markers", "telegram: drives a real Telegram user account")
    config.addinivalue_line("markers", "stack: needs the local stack to be up")
    # **Marked rather than listed by name in the deploy.** A deploy whose index has not caught up
    # drops the tests that need material to answer from, because each could only have failed for
    # want of it — which reads identically to the answer path being broken. Selecting them by
    # marker means a case added later is covered by the rule; a list of node identifiers would
    # leave it to go red for a reason that is about the deploy's timing.
    config.addinivalue_line("markers", "corpus: needs an indexed corpus to answer from")


@pytest.fixture(scope="session", autouse=True)
def _target_guard():
    """
    Fail the whole run, once, if the target is not the local stack.

    Once rather than per test: the same missing variable failing every test reads as many
    problems, and the one line that matters scrolls away.
    """
    try:
        harness_config.target()
    except harness_config.ConfigError as exc:
        pytest.exit(f"e2e refuses to run: {exc}", returncode=2)


@pytest.fixture(scope="session")
def telegram_account():
    """The configured user account, or a skip that names what is missing."""
    if not harness_config.has_telegram_account():
        pytest.skip(
            "No Telegram user account configured, so nothing drove Telegram in this run. "
            "Set MB_E2E_API_ID, MB_E2E_API_HASH and MB_E2E_SESSION — see e2e/README.md. "
            "Skipped rather than passed: an unrun check is not a satisfied one."
        )
    return harness_config


@pytest.fixture(scope="session")
def archive_subject():
    """A name the corpus holds, or a skip that names what is missing.

    A skip and not a default. The shapes reached by a command are asked about somebody, and a
    guessed name produces the reply that says the subject could not be established — which is a
    correct answer to a question about nobody and would read as a rendering defect.
    """
    if not harness_config.has_subject():
        pytest.skip(
            "MB_E2E_SUBJECT is not set, so the shapes asked about somebody were not exercised. "
            "Set it to a counterpart the corpus holds, in e2e/.env.local — it is not committed, "
            "because a name in this corpus belongs to a real person. See e2e/README.md."
        )
    return harness_config.subject()


@pytest.fixture(scope="session")
def placed(archive_subject):
    """What the store places on the configured subject, read once for the whole run.

    Once because every case that reads it asks the same question of the same name, and the answer
    cannot change under a run: a sweep that wrote a commitment between two cases would have the
    second case holding a reply to material the first one did not see.

    A store that cannot be read is a skip rather than a failure, and the distinction is the one
    this file is built on: `test_stack.py` is what reports a store that is not there, and a
    rendering case failing for it would read as a defect in the answer.
    """
    try:
        return store.placed_on(archive_subject)
    except store.StoreError as exc:
        pytest.skip(
            f"The store could not be read, so nothing a rendered answer says could be held to "
            f"what the corpus holds: {exc}"
        )


#: What a case ran and could not assert, in the order the cases reported it.
#:
#: Module state rather than a fixture's value, because the summary hook is not a test and cannot
#: be handed one. Cleared by nothing: a run is one process.
_UNASSERTED: list[str] = []


@pytest.fixture
def unasserted(request):
    """A way for a case to say a property went unchecked, rather than passing in silence.

    **For material that cannot discriminate, never for a property that is merely hard.** A case
    reporting through this has asked the store what it holds and found nothing that could tell a
    correct answer from a wrong one — no deadline in the past, no party placed on anything. The
    assertion is live and the material is inert, which is a fact about the archive on the day and
    changes with the next message ingested. A property nothing in this tier can read is said so in
    the case's own prose, where a reader of the file sees it, rather than reported here every run.
    """

    def record(what: str, why: str) -> None:
        _UNASSERTED.append(f"{request.node.name}\n    {what}, because {why}")

    return record


def pytest_terminal_summary(terminalreporter):
    """Say what the run did not do, under a heading of its own.

    Separate from pytest's own skip lines deliberately. Those are a list of cases; this is the
    consequence — that the run is not evidence about the shapes those cases are the only reading
    of. A deploy prints the same sort of sentence where it deselects the tests that need material,
    and a reader of one output should not have to know that an exit status of nought covers both
    "everything passed" and "the half that matters stood down".
    """
    skipped = terminalreporter.stats.get("skipped", [])
    if not skipped and not _UNASSERTED:
        return

    terminalreporter.write_sep("=", "what this run is not evidence about", yellow=True)
    for report in skipped:
        # The reason as pytest recorded it, which is the message the fixture skipped with. A
        # three-part tuple for an ordinary skip; anything else is printed as it stands, and
        # nothing at all is said as nothing rather than as the word `None`.
        if isinstance(report.longrepr, tuple):
            reason = report.longrepr[2].removeprefix("Skipped: ")
        else:
            reason = str(report.longrepr or "no reason recorded")
        terminalreporter.write_line(f"  did not run: {report.nodeid}\n    {reason}")
    for entry in _UNASSERTED:
        terminalreporter.write_line(f"  unasserted: {entry}")
    terminalreporter.write_line(
        "An unrun check is not a satisfied one. A deploy that reports this section has not read "
        "what it lists."
    )
