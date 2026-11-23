-- A pass submits in stages, and each stage may have a submission of its own in flight.
--
-- Recognition's inference reads a window in two asks: the first classifies what a stretch of
-- conversation states, the second restates one finding so that it stands on its own. Both go to the
-- batch endpoint, and the second cannot be submitted until the first has come back -- so a pass
-- holding one submission at a time could never have both, and the restatement half would have to be
-- made synchronously at the full price while the classification half paid half.
--
-- Keyed on the pass and the stage rather than the pass alone, because the reason for the rule is
-- per stage: a stage with two submissions outstanding could not say which of its own work had
-- already been paid for. Two stages with one each can always say, because a reply is matched by the
-- key it was submitted under and the stages do not share keys.
ALTER TABLE model_submission
    ADD COLUMN stage text NOT NULL DEFAULT 'classification';

-- The default exists to fill the rows already here, every one of which is a classification: before
-- this there was no other kind. It is dropped immediately so that a writer has to name the stage it
-- is submitting for -- a column that silently defaults is one a new stage would be recorded under
-- the wrong value by forgetting, and the mistake would look like a stage that never submits.
ALTER TABLE model_submission
    ALTER COLUMN stage DROP DEFAULT;

ALTER TABLE model_submission
    ADD CONSTRAINT model_submission_stage CHECK (stage IN ('classification', 'restatement'));

DROP INDEX model_submission_one_in_flight;

-- At most one submission per pass per stage may be in flight at a time.
--
-- The replacement for the same rule keyed on the pass alone. What it still prevents is a stage
-- paying twice for work it cannot account for; what it now permits is the two stages of one pass
-- being outstanding together, which is the ordinary state of a pass whose second stage is waiting on
-- a turnaround while its first gets on with the next batch.
CREATE UNIQUE INDEX model_submission_one_in_flight
    ON model_submission (deriver, stage)
    WHERE collected_at IS NULL;

-- What a restatement request is asking to have restated.
--
-- Null for a classification request, which asks about a window and needs nothing but the window to
-- be found again. A restatement asks about one finding *inside* a window, and the finding is not
-- re-derivable: it is what the classification replied, and that reply is read once. So the line the
-- classification produced is kept here and parsed again at collection, against the window rebuilt
-- the same way the classification's own collection rebuilds it.
--
-- The line rather than its parts, because the line is already the format both sides agree on -- the
-- parser that reads it at collection is the one that read it when the candidate was found, so there
-- is one definition of what a finding is and not two that can drift.
--
-- `request_key` carries a second shape alongside this. A classification asks about a window and is
-- keyed by its anchor; a restatement asks about one finding inside that window and is keyed by the
-- anchor and the finding's place in it, so the collection reads the anchor out of the key before the
-- key names a window.
ALTER TABLE model_submission_request
    ADD COLUMN payload text;

-- Which kind of record a submission was selected for.
--
-- On the submission rather than on each of its records, because a submission selects one kind: a
-- pass asks for work by kind and the whole batch is of that kind.
--
-- **It is here so that selection can skip only what it should.** A record identifier is unique only
-- within its kind -- the sources key different kinds alike, which is why `derivation` is keyed by
-- kind too -- and `recognition` is the deriver of two passes, inference over messages and identity
-- over conversations and persons. In the Telegram archive a forum topic is named by the message that
-- created it, so a conversation and a message can carry the same identifier under the same source.
-- Without the kind, an inference submission holding that message would withhold the topic from
-- identity's selection, for as long as the submission was outstanding.
ALTER TABLE model_submission
    ADD COLUMN record_kind text NOT NULL DEFAULT 'message';

-- Dropped for the reason the stage default is: every row already here is the inference pass's, and
-- the inference pass selects messages. A writer of the next kind has to say which it is.
ALTER TABLE model_submission
    ALTER COLUMN record_kind DROP DEFAULT;

-- Constrained like every other record kind in this schema, and for a sharper reason here: this
-- column is one conjunct of the clause selection skips a submitted record by, and a value no record
-- table uses is enough on its own to make that clause match nothing and the exclusion silently a
-- no-op -- selection goes on offering records already paid for, and nothing fails. The four the
-- corpus has, as `derivation` lists them, because a submission can be made for whatever kind a pass
-- selects. Nothing asserts this: the writer passes a four-value enum, so it guards a hand-edit.
ALTER TABLE model_submission
    ADD CONSTRAINT model_submission_record_kind
        CHECK (record_kind IN ('conversation', 'message', 'person', 'document'));

-- Read when selecting work, to skip records a submission has already been billed for.
--
-- Selection is oldest-first over everything owed, and a record stays owed until the findings read
-- out of it are written -- which is now two collections after it was first paid for. Without this
-- index that skip is a sequential scan of every record ever submitted, on the one query the pass
-- runs before it spends anything.
CREATE INDEX model_submission_record_by_record
    ON model_submission_record (record_source, record_native_id);
