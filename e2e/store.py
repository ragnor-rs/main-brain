"""What the corpus already places on the subject, read so a delivered answer can be held to it.

**Why a case here reads the store at all, against this suite's own rule that nothing is inspected
from inside the stack.** That rule is about the *observation*: a reply is read the way a person
reads it, from the chat, so a failure says the system did not answer. It says nothing about where
the *expectation* comes from — and for every property of a brief that is not its length, the
alternative to reading the store is matching a phrase. The standing of a commitment, which party
it obliges and how stale it is are each asked for by instruction rather than emitted as a fixed
string, so the model's wording varies and a substring scan for one phrasing is a flaky assertion
wearing a strict one's clothes. What does not vary is the material: a name is a name the store
holds, and a date is a date it holds. So the answer is read from outside and the expectation is
read from the store, and neither half reaches into the stack to find out what it decided.

**This is not the subject resolver, and where the two could disagree it declines to say
anything.** The resolver scores candidates, folds names, matches handles and resolves a whole
merge cluster; what is here is the label half of its own first query — the store's `name_fold`
over `contact.display_name`, with the same word-start pattern — and nothing else. That is enough
to find the material a brief was written from, and not enough to be relied on when more than one
human answers to the configured name: [Placed.undecided] is what a case reads then, and the case
asserts nothing about the material rather than asserting against somebody else's.

**Read through Compose, as `stack.py` is, though for a reason of its own.** There it is because
Compose knows what was *meant* to be running, which is how a service that exited is told from one
that was never defined. Here it is the address: a service name is stable, where a container name is
the project's and changes with the directory the stack was brought up from. The consequence of both
is the same — run from anywhere but the checkout the stack belongs to, Compose derives a different
project, finds no service, and the read fails rather than answering about the wrong corpus.
"""

import csv
import io
import subprocess
from dataclasses import dataclass
from datetime import date

import config

#: How many commitments a brief reads, which is `PrepareShape`'s own bound.
#:
#: Mirrored rather than ignored, because the material behind a brief is the first thirty and a
#: thirty-first the store happens to hold is not something the answer was given. A figure copied
#: from the Kotlin and able to drift from it, which is why the comment says where it lives:
#: `RetrievePrepare.COMMITMENTS`. A bound that rises there is the harmless direction, because these
#: rows are then a subset of what the brief was given; one that falls is what would have this
#: expecting an item the brief never saw. It has not moved since it was written.
COMMITMENTS = 30

#: How many items a brief may report, which is `PrepareShape`'s own instruction.
#:
#: Read here because it decides whether anything was left out: where the store places no more
#: undertakings than this, the brief reports all of them and the one that leads can be asserted to
#: be present. Past it, the instruction keeps the items a longer silence sits behind rather than the
#: furthest past, so the leading item may be correctly absent.
ITEMS = 5

#: How deep a merge chain is followed, which is the recursion bound the store's own walk carries.
#:
#: A chain arises because a later pass merges one canonical account into another, so following the
#: table a single hop finds a head that is itself merged — and the cluster then misses whatever the
#: far member stated, which can be the very item a brief had to lead with. `ContactClusters` walks
#: it recursively under this same bound, which is there so that a cycle cannot spin rather than
#: because a real chain is ever long.
CLUSTER_DEPTH = 64

#: The subject's whole merge cluster, and how many separate people the configured name reached.
#:
#: Shared by both reads below, which have to agree about who the subject is. Written as the
#: resolver's own label query — the store's `name_fold` over `contact.display_name`, with the same
#: word-start pattern — then walked over `merge_link` in both directions, because a human is the
#: connected component and not the row whose name matched.
#:
#: A head is a member nothing merged into something else, which is the account whose name stands in
#: for a nameless one. Two of them means the name reached two people and nothing here may be relied
#: on; nought means a cycle in a derived table, and reads the same way.
_CLUSTER = f"""
WITH RECURSIVE seed AS (
    SELECT c.id
    FROM contact c
    WHERE c.display_name IS NOT NULL
      AND ' ' || name_fold(c.display_name) LIKE '% ' || name_fold(:'subj') || '%'
),
reach (id, depth) AS (
    SELECT id, 0 FROM seed
    UNION
    SELECT CASE WHEN ml.merged = r.id THEN ml.canonical ELSE ml.merged END, r.depth + 1
    FROM reach r
    JOIN merge_link ml ON ml.merged = r.id OR ml.canonical = r.id
    WHERE r.depth < {CLUSTER_DEPTH}
),
cluster AS (
    SELECT DISTINCT id FROM reach
),
head AS (
    SELECT id FROM cluster
    WHERE NOT EXISTS (SELECT 1 FROM merge_link ml WHERE ml.merged = cluster.id)
)
"""

#: What `psql` is asked for, per commitment the brief reads.
#:
#: **The narrowing is the port's, not an approximation of it.** `COMMITMENTS_BY_CONTACT` reads
#: `stated_by = ANY (cluster)` over rows with no successor, newest first, limited, and joins the
#: evidence — so a claim no span evidences never reaches a brief and is not counted here either.
#: The standing is computed against the store's own `now()` for the reason the shape computes it
#: once against one instant: a date sorted as ahead and labelled as past is the defect, and two
#: clocks are how it happens.
#:
#: **Closure is read the way the port reads it, which is why the message is joined.** A closing
#: turn that was withdrawn discharges nothing, and counting it would move an item out of what is
#: owed here while the shape still ranks it among the overdue.
#:
#: The obliged party is named as `namesOf` names it — its own profile name, and its head's where it
#: has none — because that is the name the prompt carried and so the only one a brief can have
#: written.
_COMMITMENTS = f"""
{_CLUSTER}
SELECT (SELECT count(*) FROM head) AS humans,
       to_char(c.due_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS due_on,
       -- The deadline whole, which is what orders two of them: `Material.owedFirst` sorts on the
       -- instant, and two items due the same day at different times tie on the date alone.
       coalesce(to_char(c.due_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS'), '') AS due_at,
       -- Both ends taken to the day boundary, in UTC, which is the calendar `Material.overdue`
       -- counts in and deliberately not the one a message's age is counted in.
       ((now() AT TIME ZONE 'UTC')::date - (c.due_at AT TIME ZONE 'UTC')::date) AS days_past,
       -- How long the record has been silent about it, which the material renders beside the
       -- standing as a second figure of days. Counted here in UTC where the shape counts it in the
       -- correspondents' zone, so it can be a day out — which is why a caller comparing figures
       -- allows a day either way rather than reading this as the figure itself.
       ((now() AT TIME ZONE 'UTC')::date
            - (ev.last_stated AT TIME ZONE 'UTC')::date) AS silent_days,
       CASE
           WHEN c.due_at IS NULL THEN 'none'
           WHEN (c.due_at AT TIME ZONE 'UTC')::date < (now() AT TIME ZONE 'UTC')::date THEN 'past'
           WHEN (c.due_at AT TIME ZONE 'UTC')::date = (now() AT TIME ZONE 'UTC')::date THEN 'today'
           ELSE 'ahead'
       END AS standing,
       EXISTS (
           SELECT 1
           FROM commitment_closure cc
           JOIN message m ON m.source = cc.record_source AND m.native_id = cc.record_native_id
           WHERE cc.commitment = c.id
             AND cc.record_source IS NOT NULL
             AND m.withdrawn_at IS NULL
       ) AS closed,
       coalesce(obliged.display_name, obliged_head.display_name, '') AS obliged
FROM (
    SELECT * FROM commitment
    WHERE stated_by IN (SELECT id FROM cluster)
      AND superseded_by IS NULL
    ORDER BY source_time DESC
    LIMIT {COMMITMENTS}
) c
JOIN (
    SELECT commitment, max(source_time) AS last_stated
    FROM commitment_evidence
    GROUP BY commitment
) ev ON ev.commitment = c.id
LEFT JOIN contact obliged ON obliged.id = c.obliged_party
LEFT JOIN merge_link obliged_link ON obliged_link.merged = c.obliged_party
LEFT JOIN contact obliged_head ON obliged_head.id = obliged_link.canonical
ORDER BY c.source_time DESC
"""

#: Every spelling the store holds for whoever the configured name reaches.
#:
#: **The cluster's spellings and not one item's party**, which is a deliberate widening: the
#: account that stated a thing frequently carries no profile name while another account of the same
#: human does, and `namesOf` substitutes the head's. Holding an answer to one spelling would red on
#: a brief that wrote the one the prompt actually gave it. What it costs is that a cluster holding
#: several named accounts admits a spelling this item could not have shown — the same human under
#: another of their own names, which is a weaker pass and never a wrong red.
_NAMES = f"""
{_CLUSTER}
SELECT (SELECT count(*) FROM head) AS humans,
       coalesce(c.display_name, '') AS name
FROM contact c
WHERE c.id IN (SELECT id FROM cluster)
  AND c.display_name IS NOT NULL
"""


class StoreError(RuntimeError):
    """The store could not be read. Distinct from it holding nothing."""


@dataclass(frozen=True)
class Commitment:
    """One undertaking the store places on the subject, in the form a brief is held to it.

    The claim itself is deliberately absent. It is restated by the composer in its own words, so
    nothing in a rendered brief can be matched against it — and holding a real conversation's text
    in a test's failure message is the other reason.
    """

    #: Where its deadline stands: `past`, `today`, `ahead`, or `none` for one with no date.
    #:
    #: The first three are the instruction's own words. The fourth is this file's: the material
    #: says no date was stated and `Material.Standing` calls it `UNDATED`. The enum carries a fifth
    #: — a discharged promise, which is deliberately not grouped by its date — and that one is not
    #: modelled here: [closed] is the same fact, and every caller reads the two together.
    standing: str
    #: The day it falls due, or `None` where no date was stated.
    due: date | None
    #: The deadline whole, as text that sorts, or `""`. Ordering only — two items due the same day
    #: at different times are ordered by the instant where the shape orders them.
    due_at: str
    #: How many days past that day stands, or `None` where no date was stated. Negative for one
    #: still ahead, which is a reading no caller wants and is left as the arithmetic rather than
    #: clamped: a figure of days is only looked for in a brief where the standing is `past`.
    days_past: int | None
    #: How many days the record has been silent about it — the second figure of days the material
    #: renders, which is why a caller has to know it before reading the first one as a position.
    silent_days: int | None
    #: Whether the corpus records a later message, itself not withdrawn, discharging it.
    closed: bool
    #: What the store calls the party it places the thing on, or `""` where it places nobody.
    obliged: str

    @property
    def owed(self) -> bool:
        """Whether the record shows no outcome for it, which is what a call is for."""
        return not self.closed


@dataclass(frozen=True)
class Placed:
    """What the store holds about the configured subject, and whether it may be relied on."""

    #: Every spelling the cluster carries, longest first so the most specific is tried first.
    names: tuple[str, ...]
    #: The subject's commitments, newest first — the order the store returns them in.
    commitments: tuple[Commitment, ...]
    #: How many separate humans the configured name reached.
    humans: int

    @property
    def undecided(self) -> bool:
        """Whether this says nothing, because the name reached nobody or several people.

        Both arms are the same answer to a case: the material behind the brief is not known to be
        this material. Nobody means the resolver found somebody this label query did not — a
        handle, or a fold this does not reproduce — and several means the brief was written about
        one of them and this cannot say which.
        """
        return self.humans != 1

    @property
    def owed(self) -> tuple[Commitment, ...]:
        return tuple(item for item in self.commitments if item.owed)

    @property
    def owed_past(self) -> tuple[Commitment, ...]:
        """The unsettled undertakings whose deadline has passed, the furthest past first.

        **This is the order a brief's account is held to, and it is the store's own rule rather
        than this file's.** `Material.owedFirst` sorts on the standing and then on the deadline, so
        what fell due longest ago with nothing recorded against it leads. The deadline is compared
        whole for the reason [Commitment.due_at] gives.

        What this does *not* say is that such an item survives into the brief. The length bound is
        the shape's instruction rather than this ordering, and where more items are owed than it
        allows, what the instruction keeps is the ones a longer silence sits behind — so a
        compliant brief can leave the furthest past out. A caller asserting its presence has to
        establish first that nothing was cut.
        """
        past = (item for item in self.owed if item.standing == "past")
        return tuple(sorted(past, key=lambda item: item.due_at))

    @property
    def parties(self) -> tuple[str, ...]:
        """Every party the store places an unsettled undertaking on, longest name first.

        **The unsettled ones only, because they are what a brief reports.** The instruction asks
        for the party of each item the brief carries, and a promise the record reports as done is
        one it is told not to raise — so a party placed on nothing but a discharged item is a party
        a correct brief never names, and asking for it would red on correct prose.
        """
        obliged = {item.obliged for item in self.owed if item.obliged}
        return tuple(sorted(obliged, key=len, reverse=True))

    def ambiguous(self, days: int) -> bool:
        """Whether a figure of `days` days could be about more than one of these undertakings.

        **The material renders two figures of the same shape on every unsettled item** — how far
        past its deadline stands, and how long the record has been silent about it — and nothing in
        a brief tells one from the other. So a figure is only a position when no other item's
        either kind carries the same count; where one does, a caller reads the order as unasserted
        rather than placing the item by a number that points at a neighbour's sentence.

        **The residual, stated because this cannot close it.** A deadline's distance is counted in
        UTC on both sides and is therefore exact here, while a silence is counted in the
        correspondents' calendar by the shape and in UTC here — so a silence of this many days can
        be rendered one either side of what this holds, and a collision of that one day is not
        visible from here. What it would cost is a position read off a neighbouring sentence, which
        is the failure this method exists to prevent in its ordinary form.
        """
        deadlines = [item.days_past for item in self.commitments if item.standing == "past"]
        silences = [item.silent_days for item in self.commitments if item.silent_days is not None]
        return deadlines.count(days) > 1 or days in silences


def placed_on(subject: str) -> Placed:
    """What the store places on `subject`, in one read of the dev stack's store."""
    names = _query(_NAMES, subject)
    commitments = _query(_COMMITMENTS, subject)
    # Read off whichever query returned a row. Both carry it, because a subject with no
    # commitments at all still has to be told from a subject nothing resolved.
    humans = int((names + commitments)[0]["humans"]) if names or commitments else 0
    return Placed(
        names=tuple(sorted({row["name"] for row in names if row["name"]}, key=len, reverse=True)),
        commitments=tuple(
            Commitment(
                standing=row["standing"],
                due=date.fromisoformat(row["due_on"]) if row["due_on"] else None,
                due_at=row["due_at"],
                days_past=int(row["days_past"]) if row["days_past"] else None,
                silent_days=int(row["silent_days"]) if row["silent_days"] else None,
                closed=row["closed"] == "t",
                obliged=row["obliged"],
            )
            for row in commitments
        ),
        humans=humans,
    )


def _query(sql: str, subject: str) -> list[dict[str, str]]:
    """`sql` run against the dev stack's store, with `subject` quoted by `psql` rather than by us.

    **The name reaches the statement through `:'subj'`, which is interpolation — quoted
    interpolation.** `psql` wraps the value as a string literal and escapes what is in it, so a
    name carrying a quote cannot end the literal and become more statement; what it is not is a
    bound parameter, and nothing here pretends otherwise. `docker compose exec -e` hands the value
    over as an argument rather than through a shell, so nothing re-parses it on the way either, and
    the store's credentials are read inside the container and never printed.
    """
    command = (
        "docker", "compose", "exec", "-T",
        "-e", f"MB_SUBJECT={subject}",
        "postgres",
        "sh", "-c",
        # `--csv` rather than a separator of our own: a display name is a human's own text and may
        # hold anything, and CSV is the one quoting both ends already agree on.
        'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" --csv -v ON_ERROR_STOP=1 '
        '-v subj="$MB_SUBJECT" -f -',
    )
    try:
        result = subprocess.run(
            command,
            cwd=config.compose_project_dir(),
            input=sql,
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
    except FileNotFoundError as exc:
        raise StoreError("`docker` is not on PATH, so the store cannot be read.") from exc
    except subprocess.TimeoutExpired as exc:
        raise StoreError("The store did not answer within 60s.") from exc

    if result.returncode != 0:
        raise StoreError(
            f"The store could not be read: {result.stderr.strip() or result.stdout.strip()}"
        )
    return list(csv.DictReader(io.StringIO(result.stdout)))
