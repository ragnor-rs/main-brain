-- Per-source ingestion state: freshness, health, and how far backfill has reached.
--
-- Neither corpus nor derived. Every table in `V1` holds what a source said; this one holds how
-- the reading of a source is going, which is why it shares none of that schema's shape:
--
--   * The source is the key, with no surrogate `id`. A record needs one because sources reissue
--     their own identifiers and every row referring to a record would otherwise be rewritten.
--     There is exactly one state row per source and nothing refers to it, so the indirection
--     would buy nothing.
--
--   * No three time axes. Observation times answer "when did we see this record", and the
--     subject here is not a record. The times this table keeps are named for what they are.
--
-- The row is current state, replaced in place: a report overwrites the columns it owns, which is
-- the point of the table. That is not in tension with nothing being removed from the corpus --
-- what a source said is kept forever, while how far the reading had got is a fact with one
-- current value. The three groups of columns are written by separate reports and never read as a
-- unit, so each statement touches only its own.
--
-- It carries no state a client library already owns. The position stays with the adapter, and
-- the gap that creates -- a restore leaving the corpus behind a library's own store -- is what
-- an explicit re-read exists for, not something a column here could detect.
CREATE TABLE ingest_state (
    source                 text        PRIMARY KEY,

    -- Freshness. A source time, never an observation time: a backfill captures a five-year-old
    -- message today, and recording when the capture happened would make old history look
    -- current. The lagging units are the exception to it rather than a second version of it, and
    -- they are held as an array because they are always read with the time they qualify and are
    -- replaced wholesale by each report.
    complete_through       timestamptz,
    lagging_units          text[]      NOT NULL DEFAULT '{}',

    -- Backfill, as of the last enumeration. `backfill_enumerated_at` is what makes the claim
    -- legible: complete *as of* a date is a statement, and complete with no date is not. NULL
    -- across all three means no enumeration has reported yet, which is not the same as
    -- incomplete.
    backfill_complete      boolean,
    backfill_reached       timestamptz,
    backfill_enumerated_at timestamptz,

    -- Health. Last success and last failure are kept as separate times rather than one outcome,
    -- because the question a reader acts on is which of the two came last.
    last_success_at        timestamptz,
    last_failure_at        timestamptz,
    last_failure_kind      text,
    last_failure_note      text
);
