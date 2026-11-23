-- The last tier of the collapse: a third stage of model calls, and a record of what it decided.
--
-- `V16` made a finding addressable by what it says, which collapses an identical reading on write.
-- What it cannot collapse is a rewording -- restatement rewrites a finding into a sentence that
-- stands on its own, and a re-derivation under a moved prompt version chooses different words for
-- the same fact. The comparison that answers that is ordered by cost: the exact key here in the
-- store, then a vector comparison over the two claims, then the model for the pairs the vectors put
-- near without settling. This file is what the third tier needs that the schema does not yet hold.

-- The stage a submission was made for, widened by one.
--
-- A check constraint cannot be extended in place, so it is replaced. Nothing is dropped but the
-- constraint itself: every row keeps the value it had, and both existing values stay legal.
ALTER TABLE model_submission
    DROP CONSTRAINT model_submission_stage;

ALTER TABLE model_submission
    ADD CONSTRAINT model_submission_stage
        CHECK (stage IN ('classification', 'restatement', 'adjudication'));

-- A request need not be about a conversation, and the adjudication stage's are not.
--
-- These two columns say which conversation to re-read to find a window again, which is the whole of
-- what a classification or a restatement needs remembered. An adjudication reads no window: it
-- compares two findings already written, re-read by their identifiers, or two values of one subject
-- and predicate, which belong to no single conversation at all. Filling them anyway would store a
-- value nothing reads and state something false about the request.
--
-- Widening only. Every row already here keeps what it had, and both reading stages go on writing
-- them -- `SubmittedWindow` is what records that a window-shaped request still must.
ALTER TABLE model_submission_request ALTER COLUMN conversation_source DROP NOT NULL;
ALTER TABLE model_submission_request ALTER COLUMN conversation_native_id DROP NOT NULL;

-- Two values of one subject and predicate, and what the model said about them.
--
-- **The statement side of the collapse, and it is shaped differently because a statement is its own
-- content.** A commitment has an identifier, so two readings of it are merged by having one row name
-- the other. A statement has no identity to assign -- `V10` says so -- and is keyed by subject,
-- predicate, value and the start of its window, so what can be recorded about two readings is that
-- the two *values* mean the same thing. Resolution then treats them as one value and re-windows,
-- which is where the second reading stops being a separate fact.
--
-- **Nothing here is ever merged by a vector comparison, unlike the finding collapse.** Two values of
-- one relation are usually a deliberate contradiction: a rate quoted in March and revised in June is
-- exactly what the validity window exists to hold, and two short values close in wording -- a rate
-- and the rate it became -- are the pair a vector comparison is least able to tell from a
-- paraphrase. Merging one of those would delete the history the table was built for. So the vectors
-- only choose which pairs are worth asking about, and the model is what decides; a `same` row here
-- is always something that was paid for. What the vectors do write is the other answer: a pair they
-- put too far apart to be worth asking about is recorded `false`, so the queue drains.
CREATE TABLE statement_value_verdict (
    subject    uuid        NOT NULL,
    predicate  text        NOT NULL,
    -- The two values, in the order the database collates them rather than the order a sweep
    -- happened to compare them in, so one unordered pair is one row. Ordered by SQL on the way in
    -- for the same reason the constraint below is checked here: a caller ordering text itself would
    -- be using a different collation from the one this index is built with.
    lower_value text       NOT NULL,
    upper_value text       NOT NULL,
    -- Whether the two state one fact. `false` is as much worth storing as `true`: the pair stays a
    -- candidate by every test this schema can make, so without the answer the next idle sweep pays
    -- for it again, and every sweep after that.
    same       boolean     NOT NULL,
    -- The version of whichever tier decided it: the adjudication version where the model was paid
    -- for a verdict, the vector version where the vectors ruled the pair out.
    --
    -- **Both are recorded, and neither is permanent.** Each is retired when its own version moves
    -- and by nothing else, so a reworded comparison retires the paid answers while the cheap ones
    -- stand, and a better embedding model or a moved similarity bound does the reverse. A vector
    -- rule-out costs a local embedding, and the first instinct is not to record it at all -- but a
    -- candidate query is oldest-first over a bound, so a pair nothing records comes back at the head
    -- of the queue for ever and the pairs behind it are never reached. Recording it keeps the queue
    -- draining and still retires the judgement the moment a version that would answer differently
    -- arrives.
    --
    -- The two value spaces cannot be confused, because each version names its own stage first and
    -- the two stages are not named alike. A reader wanting the rule rather than the column should
    -- read `StatementRepository.decide` for what this table records -- including that the vector
    -- version names every similarity bound while this side does not weigh them all, so a move to
    -- one it never reads retires rule-outs it did not decide -- and `CollapseRepository.candidates`
    -- for the rule itself.
    decided_by text        NOT NULL,
    decided_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT statement_value_verdict_ordered CHECK (lower_value < upper_value)
);

-- **Keyed on a digest rather than on the values, because a value has no length limit and an index
-- row does.** A predicate's value is short by construction -- the restating stage is told to write
-- the value alone -- but nothing enforces it, and a long one would fail the insert and stop the
-- pass rather than merely waste a row. `md5` is immutable, so it can be indexed; the values stay on
-- the row in full, because what a person reading this table wants to see is the two sentences.
CREATE UNIQUE INDEX statement_value_verdict_pair
    ON statement_value_verdict (subject, predicate, md5(lower_value), md5(upper_value));

-- Two findings the model has been asked to compare and which are not to be merged.
--
-- **It exists because an answer has to be remembered whatever it cost.** A pair stays a candidate
-- by every test this schema can make -- two survivors of one conversation and kind, stated near
-- each other -- so without a record of the answer the next idle sweep would submit it again, and
-- every sweep after that, at a model call each. A pair the vector comparison rules out is recorded
-- here too, though ruling it out again costs a local embedding and no money: the candidate query is
-- oldest-first over a bound, so an unrecorded pair comes back at the head of the queue for ever and
-- the pairs behind it are never reached at all. What keeps that from committing a cheap judgement
-- for good is the version beside it rather than the absence of a row.
--
-- **Kept apart rather than judged different, because two answers land here.** The model reading the
-- pair and calling them two facts is the ordinary one. A refusal is the other, and it has to be
-- recorded for the reason a declined window is marked: a refusal is deterministic, so the same pair
-- would be submitted and refused for ever. Both mean never merge these two and never ask again, and
-- neither asserts that anything was understood.
CREATE TABLE finding_kept_apart (
    -- Which table the two identifiers below are in. **No foreign key, and none is possible**: one
    -- column cannot reference two tables, and splitting this into a pair of tables would duplicate
    -- the shape for nothing. It cannot dangle regardless -- nothing in this schema is deleted, and a
    -- merged finding keeps its row.
    kind       text        NOT NULL,
    lower_id   uuid        NOT NULL,
    upper_id   uuid        NOT NULL,
    -- The version of whichever tier decided it, as in `statement_value_verdict` above and for the
    -- same reason: the adjudication version where the model was paid for a verdict, the comparison
    -- version where the vectors ruled the pair out. A vector rule-out is recorded so the candidate
    -- queue drains, and each tier's answer is retired when its own version moves.
    decided_by text        NOT NULL,
    -- When the verdict was bought. Read by nothing today and kept because it is the only thing that
    -- could ever say how much of the budget this stage has spent over a deployment's life; the
    -- submission table holds the spend but not which pair it went on.
    decided_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT finding_kept_apart_pair PRIMARY KEY (kind, lower_id, upper_id),
    CONSTRAINT finding_kept_apart_kind CHECK (kind IN ('commitment', 'event')),
    -- **One unordered pair is one row, and this is what makes it so.** The two identifiers are
    -- stored in their own order rather than in the order the pass happened to compare them, because
    -- the order it compares them in can move: the survivor is the earlier-stated of the two, and
    -- evidence accumulates, so a later merge elsewhere can change which that is. Stored by the
    -- order it was asked in, one pair could be written twice and the second asking would not find
    -- the first.
    --
    -- Postgres compares a uuid as sixteen unsigned bytes. The domain's own identifier type orders
    -- itself the same way and says why, so a pair ordered there satisfies this.
    CONSTRAINT finding_kept_apart_ordered CHECK (lower_id < upper_id)
);
