-- What recognition has spent on a calendar day, and the bound read from it.
--
-- The figures themselves are already here. `model_submission` records what the provider billed for
-- every batch it answered, on all four of the counters the provider reports, keyed by the pass and
-- the stage that submitted. A day's spend is therefore a total over those rows rather than a second
-- tally kept beside them: one accounting that cannot drift from the other, because there is no
-- other. What this migration adds is the way to read that total cheaply, and the one kind of charge
-- those rows structurally cannot hold.
--
-- The bound is per day rather than per sweep because a sweep drains until nothing is owed, so the
-- cadence says nothing about cost and one unattended run over a backlog can take the whole
-- provision. A bound held in the store is also one a restart cannot grant afresh, which a figure
-- kept in a process's memory is not.

-- Reading one day's spend for one pass.
--
-- Keyed on the pass and the time of collection, not on a date: the day is computed by the backend,
-- which owns the clock, and asked for here as a half-open range of instants. An index on a cast to
-- `date` would have to name a time zone, and casting a `timestamptz` to a date is not immutable
-- enough to index on anyway -- zone rules move, so Postgres will not promise the expression is
-- stable. Worse, left to the session's own zone the same rows would total differently depending on
-- which process asked, which is the kind of disagreement a spend figure must never have.
--
-- Partial, on the collected rows alone. An uncollected submission has no cost yet -- the
-- constraint in `V14` makes that structural -- so including those rows would index nulls nobody
-- reads, and the one in flight is already found by its own index.
CREATE INDEX model_submission_collected
    ON model_submission (deriver, collected_at)
    WHERE collected_at IS NOT NULL;

-- What the provider billed for an attempt that never became a submission.
--
-- **No stage, unlike a submission, and that is the honest granularity rather than an omission.**
-- A submission is made by one of the reading stages and knows which. A charge is recorded by the
-- driver that caught the failure, and what the driver runs is a pass -- the stage names it knows
-- ("inference", "collapse") are not the stage names a submission uses, and a failure inside one of
-- them could have been either kind of model call. Keyed on the pass alone, which is also the
-- granularity the bound is read at.
--
-- **A table rather than a row in `model_submission`, because the two are different things and one
-- of them has no provider identifier.** A submission is a debt the provider minted a reference
-- for; what this holds is a charge for a call that was read and billed and then failed before any
-- reference came back. Recording it as a submission would mean inventing a reference, and a
-- reference this build invented is one a later collection would try to read back. Relaxing
-- `model_submission` to admit a null one instead would make a single table mean two things and put
-- a null in the column every other reader keys on.
--
-- It is not a second accounting of the same figures. Nothing lands here that could also land in
-- `model_submission`: a failure after collection is settled against its submission row in the
-- ordinary way, and only a failure before one exists reaches this. A day's spend is the sum of
-- both, and no row is in both.
CREATE TABLE model_charge (
    id          bigserial   PRIMARY KEY,
    -- The pass, keyed as `model_submission` keys it and as the watermark keys itself, so the three
    -- can be read together and a day's total can be taken per pass.
    deriver     text        NOT NULL,
    -- When the charge was incurred, which is when the attempt failed. The backend reads the clock
    -- and the day is computed from this, exactly as it is for a collection.
    charged_at  timestamptz NOT NULL DEFAULT now(),
    -- All four counters, because the day is bounded on two of them and the other two are what say
    -- whether the caching is working. A failure reports whatever the provider had billed before it
    -- gave up, which is often nothing: a call that never landed was never billed, and zero is the
    -- honest figure there rather than a reason to omit the row.
    input_tokens       bigint NOT NULL,
    output_tokens      bigint NOT NULL,
    cache_read_tokens  bigint NOT NULL,
    cache_write_tokens bigint NOT NULL,
    -- The same vocabulary the submission table checks. Stated again rather than referenced,
    -- because one table cannot share another's constraint; a charge under a pass nothing runs is a
    -- figure that would be totalled into no day's spend and silently understate what was paid.
    CONSTRAINT model_charge_deriver CHECK (deriver IN ('index', 'recognition')),
    CONSTRAINT model_charge_not_negative CHECK (
        input_tokens >= 0
        AND output_tokens >= 0
        AND cache_read_tokens >= 0
        AND cache_write_tokens >= 0
    )
);

-- Reading one day's charges for one pass, keyed as the submissions are and for the same reasons.
CREATE INDEX model_charge_incurred
    ON model_charge (deriver, charged_at);
