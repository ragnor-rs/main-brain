"""Building the fixture corpus, and waiting for the stack to derive it.

**The snapshot is the point.** Ingestion runs daily and the serving corpus mutates underneath it,
so a suite scored against whatever happens to be in the store measures the corpus rather than the
code: a case passing today and failing tomorrow would say nothing about either. So this writes a
fixed set of records into a store nothing else touches, and rebuilds it only when the fixture file
changes or the snapshot has aged out.

**Records go in; the derived layer is produced by the stack itself.** Spans carry an embedding
nobody can hand-write, and both derived passes are code under test — seeding their output would
score the answer path against a derived layer that this build had no part in making. So the
fixtures are the corpus, and this waits for indexing and recognition to catch up before anything
is asked.

**Rebuilding is the part that costs money**, because recognition's inference half spends the model
budget per window. That is why the snapshot persists in a volume of its own and why a run that
finds it current spends nothing at all.
"""

import json
import time
from datetime import UTC, datetime, timedelta

import psycopg

import harness

SOURCE = "TELEGRAM"

# Every table the schema declares. Truncated as one statement so that no foreign key has to be
# dropped and no order has to be worked out; listed in full rather than filtered, because a table
# added later and forgotten here would leave the snapshot holding whatever a previous fixture put
# in it.
#
# The submission tables are in it for a sharper reason than completeness. Recognition hands its
# classifications to the provider's batch endpoint and reads them back a turnaround later, so a
# reseed can land while one is in flight — and a submission that survived the truncate was then
# collected against a corpus it was never selected from: its replies attributed to records that had
# been deleted and reinserted underneath it, and its marks written against a cut-off older than the
# records they settle, so the work came back owed and was selected again.
#
# **What this buys is a clean reseed rather than a cheaper one**, and the distinction is worth
# keeping straight. The provider bills a batch when it processes it, so the money for one already in
# flight is spent whether or not its record survives here; clearing it forfeits that batch instead
# of applying its answers to a corpus they are not about. The second charge lands either way, and
# the only thing that avoids it is not reseeding while a submission is outstanding.
#
# `model_charge` is the one name here whose membership is arguable, and it is not in by the reason
# above: it records what the provider billed for an attempt that never became a submission, so a
# surviving row is misapplied to nothing — it remembers money that really was spent. It is in
# because a day's spend is the sum of the two, and the submission tables are already truncated: half
# an accounting left behind is worse than none, since a bound read off it would be confidently
# wrong rather than plainly absent. What that costs is that a reseed forgives the day's recorded
# spend on this stack. Acceptable only here, and for a stated reason — the daily allowances on the
# evaluation stack are a backstop against a misconfigured run rather than what bounds one, which is
# the fixture corpus being finite. On the serving store the same reset would matter.
TABLES = (
    "ingest_state",
    "commitment_closure",
    "commitment_evidence",
    "commitment",
    "event_evidence",
    "event",
    "finding_kept_apart",
    "span",
    "derivation",
    "backfill_plan",
    "statement_value_verdict",
    "statement_evidence",
    "statement",
    "identifier",
    "merge_link",
    "separation",
    "candidate",
    "contact",
    "client_admission",
    "client",
    "message",
    "conversation",
    "person",
    "document",
    "model_charge",
    "model_submission_record",
    "model_submission_request",
    "model_submission",
)

# What the store holds that is not the corpus. `flyway_schema_history` is the migration record and
# truncating it would make the next startup replay every migration over a populated schema;
# `eval_snapshot` is this suite's own bookkeeping, and `ensure` clears it by itself at the point
# where clearing it means something.
NOT_CORPUS = ("flyway_schema_history", "eval_snapshot")

# What the suite records about the corpus it built. Its own table rather than a row in one of the
# schema's, because this is the suite's bookkeeping and not something the system knows about —
# and a column added to a real table would have to survive a migration it has no business in.
SNAPSHOT_TABLE = """
CREATE TABLE IF NOT EXISTS eval_snapshot (
    only_row  boolean     PRIMARY KEY DEFAULT true CHECK (only_row),
    digest    text        NOT NULL,
    seeded_at timestamptz NOT NULL
)
"""


class CorpusError(RuntimeError):
    """The corpus could not be built or did not finish being derived."""


def load() -> dict:
    """The fixture corpus, as written."""
    with open(harness.CORPUS_FILE, encoding="utf-8") as handle:
        return json.load(handle)


def ensure(verbose: bool = True) -> dict:
    """
    The corpus in the store, matching the fixtures, fully derived.

    Returns what was done, so that a run can report whether it rebuilt or reused — the cost and
    the determinism of a run both follow from that, and a suite that did not say would leave a
    model charge unexplained.
    """
    fixture = load()
    digest = harness.corpus_digest()
    with psycopg.connect(harness.store_dsn(), autocommit=True) as connection:
        connection.execute(SNAPSHOT_TABLE)
        current = connection.execute("SELECT digest, seeded_at FROM eval_snapshot").fetchone()
        reason = _why_rebuild(current, digest)
        if reason is None:
            if verbose:
                print(f"eval corpus: reusing the snapshot seeded at {current[1].isoformat()}")
            return {"rebuilt": False, "reason": None, "messages": len(fixture["messages"])}
        if verbose:
            print(f"eval corpus: rebuilding — {reason}")
        _seed(connection, fixture)
        connection.execute("DELETE FROM eval_snapshot")
    # **Derivation is awaited before the snapshot is recorded, not after.** The row is what the
    # next run reads to decide it can skip this wait, so writing it first means any way out of
    # `_await_derivation` other than success — the timeout, a Ctrl-C, a sidecar that dies
    # mid-sweep — leaves a half-indexed corpus marked current. The next run reuses it, scores
    # every case against material that was never indexed, and reports the misses as
    # `not retrieved:` — which is precisely the regression this suite exists to detect, arriving
    # as a false positive nobody can tell from the real thing.
    _await_derivation(fixture, verbose=verbose)
    with psycopg.connect(harness.store_dsn(), autocommit=True) as connection:
        connection.execute(
            "INSERT INTO eval_snapshot (digest, seeded_at) VALUES (%s, %s)",
            (digest, datetime.now(UTC)),
        )
    return {"rebuilt": True, "reason": reason, "messages": len(fixture["messages"])}


def _why_rebuild(current, digest: str) -> str | None:
    """Why the snapshot has to be rebuilt, or `None` when it does not."""
    if current is None:
        return "no snapshot in this store yet"
    if current[0] != digest:
        return "the fixture file changed"
    age = datetime.now(UTC) - current[1]
    limit = timedelta(hours=harness.snapshot_max_age_hours())
    if age > limit:
        # Stated in hours rather than as a timestamp, because what matters is that the corpus has
        # a future in it: the fixtures are positioned relative to the moment they were seeded, so
        # an old snapshot has *what comes next* in the past.
        return f"the snapshot is {int(age.total_seconds() // 3600)}h old, over the {limit} limit"
    return None


def _check_tables_are_whole(connection) -> None:
    """
    Fail unless `TABLES` still names every table the schema declares.

    **Computed against the live schema rather than asserted in the comment above it**, which is the
    difference between a rule and a hope. The list was already prose claiming to be exhaustive when
    a migration added three tables beside it, and nothing said so: the suite went on truncating
    eighteen of twenty-one and carried an in-flight model submission across a reseed, which cost a
    second run of the same windows at the provider's price. A list of names that only a reader can
    check is a list that goes stale on the next migration, so this checks it on every rebuild.

    One-directional deliberately. A name here that the schema has dropped fails the `TRUNCATE`
    itself, loudly and in the same run, so there is nothing for this to add on that side.
    """
    declared = {
        row[0]
        for row in connection.execute(
            "SELECT table_name FROM information_schema.tables "
            "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
        ).fetchall()
    }
    missing = sorted(declared - set(TABLES) - set(NOT_CORPUS))
    if missing:
        raise CorpusError(
            "The corpus is replaced by truncating every table the schema declares, and "
            f"{', '.join(missing)} is not in that list — so a rebuild would leave whatever a "
            "previous fixture put there. Add it to TABLES in eval/corpus.py, or to NOT_CORPUS if "
            "it is not corpus."
        )


def _seed(connection, fixture: dict) -> None:
    """Replace the whole corpus with [fixture]."""
    _check_tables_are_whole(connection)
    connection.execute(f"TRUNCATE {', '.join(TABLES)}")
    seeded_at = datetime.now(UTC)

    for person in fixture["persons"]:
        connection.execute(
            """
            INSERT INTO person (source, native_id, display_name, source_time)
            VALUES (%s, %s, %s, %s)
            """,
            (SOURCE, person["native_id"], person["display_name"], seeded_at),
        )

    for conversation in fixture["conversations"]:
        connection.execute(
            """
            INSERT INTO conversation (source, native_id, kind, name, source_time)
            VALUES (%s, %s, %s, %s, %s)
            """,
            (
                SOURCE,
                conversation["native_id"],
                conversation["kind"],
                conversation["name"],
                seeded_at,
            ),
        )

    for message in fixture["messages"]:
        # The conversation's surrogate key, read back rather than assumed: `message` holds a
        # foreign key to it while every fixture addresses records the way a source does.
        row = connection.execute(
            "SELECT id FROM conversation WHERE source = %s AND native_id = %s",
            (SOURCE, message["conversation"]),
        ).fetchone()
        if row is None:
            raise CorpusError(
                f"{message['native_id']} is in a conversation the fixture does not declare: "
                f"{message['conversation']}"
            )
        author = message.get("author")
        connection.execute(
            """
            INSERT INTO message
                (source, native_id, conversation_id, author_source, author_native_id,
                 body, source_time)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
            """,
            (
                SOURCE,
                message["native_id"],
                row[0],
                SOURCE if author else None,
                author,
                message["body"],
                _at(seeded_at, message),
            ),
        )


def _at(seeded_at: datetime, message: dict) -> datetime:
    """
    When [message] happened, relative to the moment the snapshot was seeded.

    Relative rather than absolute, because a shape that reads what is coming filters on whether a
    date is ahead of now: fixed timestamps would make half the corpus unreachable a month after
    they were written, and the case built on it would fail for a reason that is not a regression.
    """
    if "days_ago" in message:
        return seeded_at - timedelta(days=message["days_ago"])
    if "hours_ago" in message:
        return seeded_at - timedelta(hours=message["hours_ago"])
    raise CorpusError(f"{message['native_id']} says nothing about when it happened.")


def _await_derivation(fixture: dict, verbose: bool, timeout_seconds: int | None = None) -> None:
    """
    Wait until both derived passes have been over every fixture message, and over its conversations.

    **Measured on the derivation marks rather than on what the passes produced**, which is the
    only reading that tells *ran and found nothing* from *has not run*. Inference legitimately
    produces no commitment for a thread that promises nothing, so counting commitments would read
    a pass that had not started as one that had finished.

    **The conversations are waited for as well as the messages, because the bodies of work are read
    from them.** A body of work is a conversation's label; an answer assembled before the pass that
    reconciles conversations has run is grouped by nothing, and the sectioning cases would then fail
    for a reason that is about timing rather than about the answer path. Identity is what marks a
    conversation for the reading the cases need, so that is the pair counted here. The pass that
    judges which named conversations are bodies of work marks conversations too, under a deriver of
    its own, and is deliberately not waited for: it is off for this suite, and a conversation it has
    not judged is a client exactly as it was — so there is nothing for the cases to wait on.

    Version-agnostic deliberately: a mark at a stale version would be re-derived by the stack, and
    this would then wait for the second mark. What it must not do is wait for a version string it
    would have to know, since that is computed from the embedding model and the scorer's weights.

    **The bound is the provider's batch turnaround, not local compute**, and that is why it is not a
    number written here: see `harness.derivation_timeout_seconds`. Two of the three marks are local
    and finish in seconds against a warm sidecar, but inference submits its classification to the
    batch endpoint and reads it back a turnaround later — allowed an hour for most submissions and a
    day for any. A bound picked for the local halves expires while the remote one is still being
    done, and the message below would then report work that was never finished as work that could
    not be.
    """
    messages = len(fixture["messages"])
    conversations = len(fixture["conversations"])
    timeout_seconds = timeout_seconds or harness.derivation_timeout_seconds()
    # What each mark has to reach. Written once so the wait, the progress line and the timeout all
    # read the same numbers; three copies of the comparison is where one of them comes to disagree.
    wanted = {
        "index/message": messages,
        "recognition/message": messages,
        "recognition/conversation": conversations,
    }
    deadline = time.monotonic() + timeout_seconds
    last = None
    with psycopg.connect(harness.store_dsn(), autocommit=True) as connection:
        while time.monotonic() < deadline:
            marks = {
                f"{deriver}/{kind}": connection.execute(
                    "SELECT count(*) FROM derivation WHERE deriver = %s AND record_kind = %s",
                    (deriver, kind),
                ).fetchone()[0]
                for deriver, kind in (
                    ("index", "message"),
                    ("recognition", "message"),
                    ("recognition", "conversation"),
                )
            }
            if all(marks[key] >= count for key, count in wanted.items()):
                if verbose:
                    print(
                        f"eval corpus: {messages} message(s) indexed and recognised, "
                        f"{conversations} conversation(s) recognised"
                    )
                return
            if verbose and marks != last:
                print(
                    f"eval corpus: waiting — indexed {marks['index/message']}/{messages}, "
                    f"recognised {marks['recognition/message']}/{messages}, "
                    f"conversations {marks['recognition/conversation']}/{conversations}"
                )
            # Outside the reporting branch, because it is also what the timeout reports. Kept inside
            # it, a quiet run that ran out of time said the marks were `None` — the one diagnostic a
            # long wait is there to leave behind.
            last = marks
            time.sleep(5)
    # Named by which mark is behind, because the three fall behind for unrelated reasons and a
    # message that guessed sent the last reader to the wrong logs.
    behind = [key for key, count in wanted.items() if (last or {}).get(key, 0) < count]
    blame = {
        "index/message": (
            "indexing is local: check `docker compose logs embeddings` — a sidecar that cannot "
            "load its weights reports healthy while refusing every call"
        ),
        "recognition/message": (
            "inference submits to the provider's batch endpoint and reads it back a turnaround "
            "later, which is allowed an hour for most submissions and a day for any — so this is "
            "as likely to be a batch still running as a fault. Check "
            f"`docker compose logs {harness.BACKEND_SERVICE}` for the submission it is waiting on, "
            "and raise EVAL_DERIVATION_TIMEOUT_SECONDS rather than assuming a regression"
        ),
        "recognition/conversation": (
            "reconciling conversations is deterministic and spends no model, so it does not wait "
            "on a provider: behind here means the recognition sweep is not running at all. Check "
            f"`docker compose logs {harness.BACKEND_SERVICE}` for the sweep and its exclusion lock"
        ),
    }
    raise CorpusError(
        f"The derived passes did not finish within {timeout_seconds}s: {last}, against "
        f"{messages} message(s) and {conversations} conversation(s). "
        f"Behind: {', '.join(behind) or 'nothing — the first read never completed'}. "
        + " ".join(f"For {key}, {blame[key]}." for key in behind)
    )
