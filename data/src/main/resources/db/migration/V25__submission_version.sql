-- The version of itself a pass was running under when it handed the batch over.
--
-- Every stage that submits writes its answers when the batch is collected, and a batch is allowed
-- an hour and may take most of a day. Reading the version at that point records the answers under
-- whatever wording is deployed by then -- so an instruction reworded while a batch is outstanding
-- has its predecessor's output stamped as current, and nothing can tell it from output the new
-- wording actually produced. That is the one window in which a retired wording's output is not owed
-- again, and it opens on exactly the deploy a reader most wants to trust: the one that follows a
-- prompt change.
--
-- Kept on the submission row rather than on each request, because a submission is one pass's one
-- ask: every request in it was composed by the same wording, and a version per request would be the
-- same string repeated once per window.
--
-- **Null means the version current at collection**, which is what every stage did before this
-- column existed and is therefore the reading that changes nothing. It is reachable for submissions
-- already in flight when this migration lands, and for those only -- the port requires a version of
-- every writer, so no build that has this column writes a row without one.
--
-- Nullable rather than `NOT NULL` with a default backfilled and then dropped, which is how `stage`
-- and `record_kind` were added to this table. That shape exists so that a writer has to name the
-- value instead of silently taking a default, and here the port's own type does that job: the
-- parameter is not nullable, so a stage that forgot it would not compile. What is left for the
-- column is the one row the port cannot speak for, the batch that was already outstanding, and a
-- backfilled value for that row would be a wording claim nothing could stand behind.
ALTER TABLE model_submission
    ADD COLUMN version text;

-- Stated rather than trusted to the writer, because a blank is the one value that is accepted and
-- means nothing: it would compare unequal to the pass's own and make this batch's output owed again
-- on every sweep after it, which is the same page re-read at full price with nothing to show a
-- reader why. The enumerated columns beside it are constrained for the narrower reason that a value
-- no table uses makes a clause match nothing; this one is about emptiness rather than membership.
ALTER TABLE model_submission
    ADD CONSTRAINT model_submission_version_stated
        CHECK (version IS NULL OR length(btrim(version)) > 0);
