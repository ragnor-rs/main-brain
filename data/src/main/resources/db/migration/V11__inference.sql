-- What a model read out of the corpus: commitments and events, each keyed by the span it came from.
--
-- Derived, like `V3`, `V9` and `V10`, and the most expensive of them to rebuild -- these are the
-- only rows in the schema that cost model tokens. That is why the watermark they advance is keyed
-- by the extractor's version as well as the record's content time: a reworded prompt re-derives
-- exactly the windows it affects, and nothing else.
--
-- `V9`'s narrower no-removal rule holds unchanged: nothing here deletes anything, nothing is
-- written with a cascade, and no row in `V1` is touched by anything below.
--
-- **Two tables and not one with a kind column.** They share their columns today and the questions
-- asked of them diverge: an event is answered by when it is and who is in it, a commitment by
-- whether it was met and by whom. The one column they look to share -- a date -- already means
-- different things in each.

-- Something someone said would be done.
CREATE TABLE commitment (
    -- Minted by the recogniser, like `contact` and `client` and for the same reason: a row insert
    -- is not what brings an entity into being. Kept across re-derivation, so a better prompt
    -- restates the claim without renumbering anything that holds the identifier.
    id                uuid             PRIMARY KEY,
    -- The span this was read out of, in the columns `span` and `statement_evidence` use for the
    -- same tuple, and no foreign key to either -- a span row is rewritten wholesale by a re-index
    -- while this address survives it, and a commitment may cite a record the index does not hold.
    record_kind       text             NOT NULL,
    record_source     text             NOT NULL,
    record_native_id  text             NOT NULL,
    ordinal           integer          NOT NULL DEFAULT 0,
    -- The evidencing span's own source time. What orders two commitments against each other, and
    -- what a recency filter reads -- never when the extraction ran, because a backfill reads old
    -- history long after the fact.
    source_time       timestamptz      NOT NULL,
    -- Restated to stand on its own, which is the second extraction stage's whole output. Replaced
    -- on re-derivation: a better prompt is a better reading of the same evidence.
    claim             text             NOT NULL,
    -- Whether the language was tentative. Stored rather than resolved, because a hedged promise is
    -- worth recording and is not worth showing the same way -- this is the column that stops "I
    -- might get to it next week" being rendered as a commitment.
    hedged            boolean          NOT NULL,
    -- When it is due, where a date was stated or implied, resolved against the message it was
    -- stated in. Null is the ordinary case: most promises carry no date.
    due_at            timestamptz,
    -- The contact behind the author of the evidencing message. Nullable and with no foreign key:
    -- identity and inference are separate passes, so a message whose author is not yet recognised
    -- leaves this empty rather than holding up the pass, and the next run fills it.
    --
    -- **Direction is not here.** Owed by us and owed to us are different rows, and telling them
    -- apart needs the archive's own account to be marked in the corpus, which nothing does. This
    -- column is what can be said for certain.
    stated_by         uuid,
    inferred_at       timestamptz      NOT NULL DEFAULT now(),
    -- Identity is the evidence. The same window read again under the same prompt collides here and
    -- writes nothing new; two different messages promising the same thing are two commitments,
    -- which over-counts rather than fusing two promises -- the safer way round, because a fused
    -- pair is invisible while a duplicate is merely untidy.
    CONSTRAINT commitment_evidence UNIQUE (record_kind, record_source, record_native_id, ordinal),
    -- Messages only, as `span` and `statement_evidence` are: `source_time` is NOT NULL here and
    -- the other three record kinds may carry none, so a wider check would admit a row that cannot
    -- be written. The kind a document is read as widens this when something reads one.
    CONSTRAINT commitment_record_kind CHECK (record_kind IN ('message'))
);

-- Something happening at a stated time.
CREATE TABLE event (
    id                uuid             PRIMARY KEY,
    record_kind       text             NOT NULL,
    record_source     text             NOT NULL,
    record_native_id  text             NOT NULL,
    ordinal           integer          NOT NULL DEFAULT 0,
    source_time       timestamptz      NOT NULL,
    claim             text             NOT NULL,
    hedged            boolean          NOT NULL,
    -- When it happens. Null where no date could be read, which makes the event nearly useless and
    -- is still better recorded than guessed at.
    occurs_at         timestamptz,
    stated_by         uuid,
    inferred_at       timestamptz      NOT NULL DEFAULT now(),
    CONSTRAINT event_evidence UNIQUE (record_kind, record_source, record_native_id, ordinal),
    -- Messages only, for the reason `commitment` states above.
    CONSTRAINT event_record_kind CHECK (record_kind IN ('message'))
);

-- Reaching both by the date they carry, which is the one question each is asked: what is owed soon,
-- and what is coming up. Partial, because most commitments carry no date and the rows that do are
-- the only ones this read is for.
CREATE INDEX commitment_by_due ON commitment (due_at) WHERE due_at IS NOT NULL;

CREATE INDEX event_by_occurrence ON event (occurs_at) WHERE occurs_at IS NOT NULL;
