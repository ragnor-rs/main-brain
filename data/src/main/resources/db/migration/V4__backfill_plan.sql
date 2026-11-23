-- The backfill plan: one row per conversation per source, not one watermark per source.
--
-- `V2` answers whether a source is finished and how far it has reached overall. This answers the
-- same two questions per conversation, which is what an adapter needs to decide what to ask for
-- next and is not derivable from the summary -- a source 60 % through its history says nothing
-- about which conversations make up the remainder.
--
-- It lives here rather than inside the client library's own store so that the plan and the corpus
-- it describes share one restore point. A library's store restores separately, and a plan ahead of
-- the records it claims to have captured is a gap nothing detects: every conversation reads as
-- walked, and the messages are simply missing.
--
-- Nothing is deleted from this table either, for a reason narrower than the corpus's. A skipped
-- conversation stays readable so that whoever asks why history stops can see that it was tried and
-- how often; a reopened one keeps the row and loses only its progress. The table is current state
-- like `ingest_state`, so progress is replaced in place rather than appended -- how far back a walk
-- has got is a fact with one value, and its history is of no use to anything.
CREATE TABLE backfill_plan (
    -- The pair is the key. `unit` is whatever the adapter calls a conversation, which is unique
    -- only within its own source -- the same reason a record's native identifier is qualified by
    -- source rather than trusted on its own.
    source        text        NOT NULL,
    unit          text        NOT NULL,

    -- A source time, never an observation time: the point in this conversation's own history the
    -- walk has got back to. NULL means nothing has been walked back to yet, which is not the same
    -- as having reached the beginning -- that is what `finished` says.
    reached       timestamptz,

    -- Complete as of the last attempt on this unit. Not a claim about the source: the work list is
    -- open, so every unit being finished is still only complete as of the last enumeration.
    finished      boolean     NOT NULL DEFAULT false,

    -- Consecutive failures, cleared by any success. A count rather than a skipped flag, because
    -- the threshold that retires a conversation is configuration and not a property of the row --
    -- storing the verdict would bake today's number into every row written under it.
    failures      integer     NOT NULL DEFAULT 0,
    failure_note  text,

    -- When this unit entered the work list, which is what orders the backlog. Without it a newly
    -- enumerated conversation is indistinguishable from one that has been waiting since the first
    -- pass, and an open work list would keep serving the new ones first.
    first_seen_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (source, unit)
);

-- The one read that runs every pass: the outstanding units for a source, least-failed and
-- longest-known first. Partial on `finished` because finished rows are the ones that accumulate --
-- a complete backfill leaves every row in the table and none of them in this index.
--
-- `failures` is in the key rather than the predicate for the same reason the port takes a ceiling
-- as a parameter: the threshold is configuration, so an index that encoded one would have to be
-- migrated to change a number.
CREATE INDEX backfill_plan_outstanding
    ON backfill_plan (source, failures, first_seen_at)
    WHERE NOT finished;
