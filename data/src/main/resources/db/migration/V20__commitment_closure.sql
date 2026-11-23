-- The stage a submission was made for, widened by one.
--
-- A check constraint cannot be extended in place, so it is replaced. Nothing is dropped but the
-- constraint itself: every row keeps the value it had, and all three existing values stay legal.
--
-- The closure stage asks the model one question per commitment over a window of what followed it,
-- which is the shape classification already uses, so it goes through the batch endpoint like every
-- other reading stage. That is also what accounts for its spending: the day's bound is read off
-- what the collections recorded, so a stage calling the provider outside a submission would spend
-- against a ledger that could not see it.
ALTER TABLE model_submission
    DROP CONSTRAINT model_submission_stage;

ALTER TABLE model_submission
    ADD CONSTRAINT model_submission_stage
        CHECK (stage IN ('classification', 'restatement', 'adjudication', 'closure'));

-- What closed a commitment, where the corpus says so.
--
-- A commitment carries no state, and the entity model says why: state derives from a later message
-- evidencing the closure, which needs a pass that recognises one. This is what that pass writes.
--
-- **A link beside the commitment rather than a column on it.** The commitment row is the reading of
-- one message and nothing about a later message changes it, so writing state onto it would mutate a
-- row this schema otherwise only ever replaces wholesale on re-derivation -- and a re-derivation
-- would then drop the closure with it. Beside it, a closure is removable without touching the
-- reading it qualifies: delete the row and the prior answer comes back, which is the property the
-- card asked for.
--
-- **And it makes the closing message citable like any other evidence.** The closure is a span, in
-- the shape `commitment_evidence` already uses, so what closed a promise can be offered to a reader
-- as a turn they can look up rather than as an assertion this system makes about one.
CREATE TABLE commitment_closure (
    commitment         uuid        NOT NULL,
    -- The message that closed it, absent where the examination found none.
    --
    -- **Absent is a verdict and not a missing value, which is the whole reason this table holds
    -- rows that say nothing closed.** A commitment nothing closed stays a candidate by every test
    -- SQL can make -- a promise with later material in its own conversation -- so without the
    -- negative recorded the next sweep pays the model to reach the same answer, and every sweep
    -- after that. `finding_kept_apart` exists for the same reason one tier over.
    --
    -- The kind is carried though only one value is legal, as `commitment_evidence` carries it: a
    -- span names what it points into, and a column that said so by being absent would have every
    -- reader assume it.
    record_kind        text,
    record_source      text,
    record_native_id   text,
    ordinal            integer,
    -- The closing span's own source time, carried here rather than joined back to the record.
    --
    -- The convention every span table in this schema follows, and for the reason `V16` gives: an
    -- instant on the row is what orders two readings without reading the corpus once per pair. It
    -- matters more than usual here because the row carries a second instant that is *not* this one
    -- -- `considered_through` is the last message weighed, which is usually later than the message
    -- that closed the promise. A reader taking the watermark for the span's time would date a
    -- closure to whenever the sweep stopped reading.
    record_source_time timestamptz,
    -- The last message this decision was read against: the watermark.
    --
    -- **A message instant rather than a wall clock is what makes it a watermark.** A promise is
    -- closed by material that arrives after it, so a verdict of *nothing closed this* is only good
    -- as far as the pass had read -- and the corpus grows backwards as well as forwards, because
    -- ingestion backfills. Keyed on when the pass ran, a window of history arriving later would
    -- never be examined; keyed on the last message weighed, anything outside what was weighed makes
    -- the commitment owed again.
    --
    -- **And the instant alone cannot be it, which is why the record travels beside it.** A source's
    -- instants are coarse: several messages of one conversation routinely carry the same second,
    -- and the window is read in instant-then-identifier order. Resuming on the instant alone steps
    -- over whatever else shared the last one, so those messages are weighed by no sweep ever --
    -- including, if it is among them, the message that closed the promise. The pair resumes where
    -- the reading stopped.
    considered_through  timestamptz NOT NULL,
    considered_source   text        NOT NULL,
    considered_native_id text       NOT NULL,
    -- What decided it: the closure pass's own version, carrying its model and its prompt.
    --
    -- **This is what retires a verdict, and it is why no derivation mark is needed beside this
    -- row.** A better closure prompt should re-examine everything it ever decided, which is exactly
    -- what the derivation table does for the passes that read corpus records -- but a commitment is
    -- an entity this system minted rather than a record a source gave it, so it has no source, no
    -- native identifier and no content time, and the three ways that table calls a record owed do
    -- not include the one that matters here: its own conversation gained a message. Holding the
    -- version on the verdict answers staleness, and the pass's candidate query answers the rest.
    decided_by         text        NOT NULL,
    decided_at         timestamptz NOT NULL DEFAULT now(),
    -- One verdict per commitment, replaced in place when a re-examination reaches a new one -- or
    -- when a merge carries a closure onto a commitment whose own verdict found none, which is the
    -- only other write this table takes. A history of verdicts would be a second thing to read and
    -- nothing reads it: what the last sweep thought before more of the conversation arrived is not
    -- interesting once it has been revised.
    CONSTRAINT commitment_closure_one PRIMARY KEY (commitment),
    CONSTRAINT commitment_closure_commitment FOREIGN KEY (commitment) REFERENCES commitment (id),
    CONSTRAINT commitment_closure_record_kind CHECK (record_kind IS NULL OR record_kind = 'message'),
    -- A span is whole or absent, never half-written. The same shape `message_author_whole` states
    -- for an author: three columns that mean one thing, so a row carrying a source and no
    -- identifier would be a citation pointing at nothing.
    CONSTRAINT commitment_closure_span_whole CHECK (
        (record_kind IS NULL) = (record_source IS NULL)
            AND (record_source IS NULL) = (record_native_id IS NULL)
            AND (record_native_id IS NULL) = (ordinal IS NULL)
            AND (ordinal IS NULL) = (record_source_time IS NULL)
    )
);

-- **A merge carries a found closure onto the survivor**, in the transaction that carries the
-- evidence. A row keyed on a merged-away reading is one nothing reaches: this table is read by
-- commitment, and the reads that hand it those identifiers return survivors only. So the surviving
-- reading would be examined from scratch, and what the closure pass is costed on -- stated at
-- `ClosureRepository.owed` -- is that a closure already found is not paid for again.
--
-- Which of two verdicts a merge keeps is a judgement rather than a mechanism, so it is stated once,
-- at `CollapseRepository.merge`, and enforced by a conflict clause in the collapse's own store. A
-- verdict that found nothing is not carried: its mark would be the survivor's promise, leaving the
-- survivor owed the window it was owed with no row at all.
--
-- **The carried closure is recorded against the survivor's own watermark**, its promise where it has
-- none, and never the superseded reading's. A watermark claims everything before it was weighed, and
-- the superseded reading's window began at its own promise -- later than the survivor's -- so
-- carrying that mark would record a stretch of the conversation as weighed that no examination read.

-- The closing message, for the read that asks what closed a set of commitments.
--
-- Partial, because the rows that close nothing are the ones this index would otherwise be mostly
-- made of: they are read by the candidate query through `commitment` and never by this direction.
CREATE INDEX commitment_closure_by_record
    ON commitment_closure (record_source, record_native_id)
    WHERE record_source IS NOT NULL;
