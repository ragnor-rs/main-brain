-- Batches of model calls handed to the provider, and what each one was billed.
--
-- A submission is answered later -- allowed an hour and sometimes most of a day -- and is billed
-- whether or not anybody is still waiting for it. So the identifier cannot live only in the memory
-- of the process that made it: a backend that restarts between submitting and collecting would have
-- paid for work it can no longer read, and would submit the same windows again and pay twice.
--
-- This is the one piece of a sweep's state that does outlive the sweep. The spend ceiling
-- deliberately does not -- a restarted backend begins with its whole ceiling, because the thing
-- bounded is one drain -- and the difference is that a ceiling is a decision while a submission is
-- a debt already incurred.
CREATE TABLE model_submission (
    id            bigserial   PRIMARY KEY,
    -- Which pass submitted, keyed as the watermark keys itself, so the two can be read together.
    deriver       text        NOT NULL,
    -- The provider's own identifier. Opaque: minted there, kept here, and handed back unread.
    provider_ref  text        NOT NULL,
    submitted_at  timestamptz NOT NULL DEFAULT now(),
    -- The cut-off the records were selected against, carried so the collection can mark with it
    -- rather than with its own clock. A batch is allowed most of a day, and a message edited in
    -- that time has to come back owed: marked at collection time the mark would look current and
    -- the classification of the superseded text would stand for good.
    selected_at   timestamptz NOT NULL,
    -- Null until the results have been read. A submission with no collection time is the one
    -- in flight, and finding it is the whole reason this table exists.
    collected_at  timestamptz,
    -- What the provider billed, read off the results rather than estimated, and null until there
    -- are results to read it from. Kept per submission rather than added into a running total
    -- because the sweep that pays is not the sweep that collects: a figure folded into the
    -- collector's own spend would charge one sweep for what another committed.
    input_tokens       bigint,
    output_tokens      bigint,
    cache_read_tokens  bigint,
    cache_write_tokens bigint,
    -- One row per provider identifier. Submitting is what creates the debt, so a second row under
    -- the same identifier would mean the same debt counted twice.
    CONSTRAINT model_submission_ref UNIQUE (provider_ref),
    CONSTRAINT model_submission_deriver CHECK (deriver IN ('index', 'recognition')),
    -- Cost arrives with collection and never before or without it. Stated as a constraint rather
    -- than trusted to the writer, because a cost recorded against an uncollected submission would
    -- be a bill nothing can attribute, and a collection with no cost would under-report the spend
    -- by exactly one submission.
    CONSTRAINT model_submission_cost_with_collection CHECK (
        (collected_at IS NULL) = (output_tokens IS NULL)
        AND (output_tokens IS NULL) = (input_tokens IS NULL)
        AND (output_tokens IS NULL) = (cache_read_tokens IS NULL)
        AND (output_tokens IS NULL) = (cache_write_tokens IS NULL)
    ),
    CONSTRAINT model_submission_cost_not_negative CHECK (
        coalesce(input_tokens, 0) >= 0
        AND coalesce(output_tokens, 0) >= 0
        AND coalesce(cache_read_tokens, 0) >= 0
        AND coalesce(cache_write_tokens, 0) >= 0
    )
);

-- At most one submission of a pass may be in flight at a time.
--
-- Two would be the way this table stops preventing the thing it exists to prevent: a sweep that
-- submitted while another submission was outstanding would have no way to say which windows it had
-- already paid to have read, and would pay for them again. A partial unique index rather than a
-- column, because the constraint is about the uncollected rows only -- collected ones accumulate
-- without limit, as the record of what was spent.
CREATE UNIQUE INDEX model_submission_one_in_flight
    ON model_submission (deriver)
    WHERE collected_at IS NULL;

-- Which window each request in a submission was asking about.
--
-- The window itself is not stored. It is re-derived from the conversation, which is what the pass
-- already does on every batch -- the conversation is read whole and re-windowed so that the split
-- is the same on every run -- so the anchor and the conversation are the whole of what a collection
-- needs to find the window again after a restart. Storing the messages instead would be a second
-- copy of the corpus that could disagree with it.
CREATE TABLE model_submission_request (
    -- No cascade. Nothing in this build deletes, so a cascade would be dead weight that
    -- nonetheless states a removal as an instruction -- which is the one thing a migration here
    -- never does. A collected submission's requests are the record of what was paid for and are
    -- kept for as long as the submission is.
    submission             bigint NOT NULL REFERENCES model_submission (id),
    -- The key the request was submitted under and the provider echoes back. The anchor's native
    -- identifier, because a window is named by its first message.
    request_key            text   NOT NULL,
    conversation_source    text   NOT NULL,
    conversation_native_id text   NOT NULL,
    -- One row per key per submission. The key is how a reply is matched back to the work that
    -- asked for it, so two rows under one key would make that match ambiguous -- and a window
    -- silently paid for and discarded is exactly what the key exists to prevent.
    CONSTRAINT model_submission_request_key UNIQUE (submission, request_key)
);

-- Read whenever a submission is collected, which is once per request of it.
CREATE INDEX model_submission_request_by_submission ON model_submission_request (submission);

-- Which records the submission was selected for.
--
-- Separate from the windows because they are not the same set and never were: a window reaches
-- outside the recency horizon for context, and those older messages are read but never settled,
-- because nothing selected them. What may be marked is the intersection of what was covered with
-- what was selected, and the second half of that is what this remembers.
--
-- It has to be remembered rather than recomputed. Selection is oldest-first and bounded, so
-- re-running it at collection time returns a different page whenever the horizon has advanced or
-- ingestion has landed older material -- and the records that were actually paid for then fall
-- outside it, stay owed, and are submitted and paid for a second time. That is one of the two
-- failures this table exists to prevent, so it cannot depend on a query whose answer moves.
CREATE TABLE model_submission_record (
    submission        bigint NOT NULL REFERENCES model_submission (id),
    record_source     text   NOT NULL,
    record_native_id  text   NOT NULL,
    CONSTRAINT model_submission_record_once UNIQUE (submission, record_source, record_native_id)
);

-- Read whenever a submission is collected, which is once per submission.
CREATE INDEX model_submission_record_by_submission ON model_submission_record (submission);
