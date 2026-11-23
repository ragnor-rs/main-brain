-- Commitments and events take the shape statements already have: a claim row keyed by what it says,
-- with the spans that evidenced it in a table of its own.
--
-- **A span evidences a finding and never identifies it.** Keying a commitment by its span made the
-- evidencing message the identity, with two consequences this undoes. Two findings read out of one
-- message collided and the second silently replaced the first, because a message is one span and
-- its ordinal is always zero. And one obligation restated later in a conversation was two rows
-- attributed to two different parties, because nothing compared the claims.
--
-- **A position stays a span's.** No column here numbers the findings within a span, so the written
-- citation form is untouched and a finding still reaches a reader under the identifier of the turn
-- it was read out of. The ordinal in the evidence tables below is the span's own, as in
-- `statement_evidence`.
--
-- **The key is scoped to a conversation**, which is what the claim text alone cannot be trusted to
-- do. A restated claim names its party only where the corpus names that person, so two identical
-- unattributed claims from different chats would otherwise fuse into one row holding one of their
-- authors. Within one conversation an identical claim is the same obligation.
--
-- Exact text is the first and cheapest comparison and not the whole of one: a rewriting produces a
-- different string for one fact, and recognising that pair is the collapse's to do with a vector
-- comparison. This file is what makes the collapse representable.
--
-- **No row and no column is removed here, and a duplicate already stored is resolved the way a
-- duplicated contact is.** Two constraints and eight `NOT NULL`s do come off below -- a key being
-- replaced, and four span columns becoming vestigial -- which takes nothing away from what is
-- stored. `V9` merges two person records by leaving both rows in place and having one name the
-- other as canonical; two readings of one obligation are merged the same way, by `superseded_by`
-- below. The surviving row carries the evidence of both, the superseded row keeps everything it
-- had, and the key is a partial index so that only survivors are compared. That also makes it
-- reversible: clearing the column puts the row back.

-- The old key. A unique constraint owns an index of its name, and an index and a table cannot both
-- be called `commitment_evidence`, so this goes before the table below is created.
ALTER TABLE commitment DROP CONSTRAINT commitment_evidence;
ALTER TABLE event DROP CONSTRAINT event_evidence;

-- The conversation the finding was read in, in the columns `conversation` uses for the same tuple
-- and with no foreign key to it, for the reason the span columns already record: this address is
-- built from the source's own identifier and survives a derived row being rewritten.
ALTER TABLE commitment ADD COLUMN conversation_source text;
ALTER TABLE commitment ADD COLUMN conversation_native_id text;
ALTER TABLE event ADD COLUMN conversation_source text;
ALTER TABLE event ADD COLUMN conversation_native_id text;

-- Which reading of one claim survives, naming the one that does. Null is the ordinary case and
-- means this row is the survivor. No `ON DELETE` anything: nothing in this schema is deleted, and
-- the reference is what makes the merge followable rather than a flag.
ALTER TABLE commitment ADD COLUMN superseded_by uuid REFERENCES commitment (id);
ALTER TABLE event ADD COLUMN superseded_by uuid REFERENCES event (id);

-- Read out of the corpus, which is the only place that knows it. A finding's evidencing record is a
-- message, and a message cannot exist without its conversation -- so the `SET NOT NULL` below is an
-- assertion that every stored finding points at a record the corpus holds. A migration that fails
-- there has found a real anomaly and says so, which is the honest outcome; it cannot leave a row
-- half-converted, because the whole file is one transaction.
UPDATE commitment c
   SET conversation_source = v.source,
       conversation_native_id = v.native_id
  FROM message m
  JOIN conversation v ON v.id = m.conversation_id
 WHERE m.source = c.record_source
   AND m.native_id = c.record_native_id;

UPDATE event e
   SET conversation_source = v.source,
       conversation_native_id = v.native_id
  FROM message m
  JOIN conversation v ON v.id = m.conversation_id
 WHERE m.source = e.record_source
   AND m.native_id = e.record_native_id;

ALTER TABLE commitment ALTER COLUMN conversation_source SET NOT NULL;
ALTER TABLE commitment ALTER COLUMN conversation_native_id SET NOT NULL;
ALTER TABLE event ALTER COLUMN conversation_source SET NOT NULL;
ALTER TABLE event ALTER COLUMN conversation_native_id SET NOT NULL;

-- The corpus a finding was read out of: one row per finding per span that states it.
--
-- Several rows per finding is corroboration rather than duplication, exactly as in
-- `statement_evidence` -- one obligation stated and then restated is one commitment with two
-- pointers, and anything presenting it can show either.
CREATE TABLE commitment_evidence (
    commitment        uuid        NOT NULL REFERENCES commitment (id),
    -- The span's address, in the columns `span` and `statement_evidence` use for the same tuple.
    -- **No foreign key to `span`**: a span row is derived and is rewritten wholesale by a re-index,
    -- while this address is the source's own and survives it.
    record_kind       text        NOT NULL,
    record_source     text        NOT NULL,
    record_native_id  text        NOT NULL,
    ordinal           integer     NOT NULL DEFAULT 0,
    -- The span's own source time, carried here rather than joined back to the record. It is what
    -- orders two readings of one claim, so a resolution that had to fetch the record would read the
    -- corpus once per pair it ordered.
    source_time       timestamptz NOT NULL,
    CONSTRAINT commitment_evidence_span PRIMARY KEY (commitment, record_kind, record_source, record_native_id, ordinal),
    -- Messages only, as `span` and `statement_evidence` are, and for the identical reason:
    -- `source_time` is NOT NULL here and the other three record kinds may carry none.
    CONSTRAINT commitment_evidence_record_kind CHECK (record_kind IN ('message'))
);

CREATE TABLE event_evidence (
    event             uuid        NOT NULL REFERENCES event (id),
    record_kind       text        NOT NULL,
    record_source     text        NOT NULL,
    record_native_id  text        NOT NULL,
    ordinal           integer     NOT NULL DEFAULT 0,
    source_time       timestamptz NOT NULL,
    CONSTRAINT event_evidence_span PRIMARY KEY (event, record_kind, record_source, record_native_id, ordinal),
    CONSTRAINT event_evidence_record_kind CHECK (record_kind IN ('message'))
);

-- Reaching every finding a span evidences, which is what a citation is checked with. The primary
-- key serves the other direction.
CREATE INDEX commitment_evidence_by_record ON commitment_evidence (record_kind, record_source, record_native_id, ordinal);
CREATE INDEX event_evidence_by_record ON event_evidence (record_kind, record_source, record_native_id, ordinal);

-- The span each row was keyed by becomes the first row of its evidence.
INSERT INTO commitment_evidence (commitment, record_kind, record_source, record_native_id, ordinal, source_time)
SELECT id, record_kind, record_source, record_native_id, ordinal, source_time FROM commitment;

INSERT INTO event_evidence (event, record_kind, record_source, record_native_id, ordinal, source_time)
SELECT id, record_kind, record_source, record_native_id, ordinal, source_time FROM event;

-- Duplicates already stored, merged onto the earliest reading of each claim. The earliest is kept
-- because a restatement is usually second-hand and the hedge marker says so, and `id` breaks a tie
-- between two readings of one instant so the outcome does not depend on row order.
UPDATE commitment c
   SET superseded_by = k.keeper
  FROM (
      SELECT id,
             first_value(id) OVER (
                 PARTITION BY conversation_source, conversation_native_id, claim
                 ORDER BY source_time, id
             ) AS keeper
        FROM commitment
  ) k
 WHERE k.id = c.id
   AND k.keeper <> c.id;

UPDATE event e
   SET superseded_by = k.keeper
  FROM (
      SELECT id,
             first_value(id) OVER (
                 PARTITION BY conversation_source, conversation_native_id, claim
                 ORDER BY source_time, id
             ) AS keeper
        FROM event
  ) k
 WHERE k.id = e.id
   AND k.keeper <> e.id;

-- The survivor carries the evidence of both. The superseded row keeps its own, which is what makes
-- the merge reversible and costs nothing: every read filters to survivors.
INSERT INTO commitment_evidence (commitment, record_kind, record_source, record_native_id, ordinal, source_time)
SELECT c.superseded_by, e.record_kind, e.record_source, e.record_native_id, e.ordinal, e.source_time
  FROM commitment c
  JOIN commitment_evidence e ON e.commitment = c.id
 WHERE c.superseded_by IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO event_evidence (event, record_kind, record_source, record_native_id, ordinal, source_time)
SELECT v.superseded_by, e.record_kind, e.record_source, e.record_native_id, e.ordinal, e.source_time
  FROM event v
  JOIN event_evidence e ON e.event = v.id
 WHERE v.superseded_by IS NOT NULL
ON CONFLICT DO NOTHING;

-- `source_time` stays on the claim row and changes meaning: the earliest span that evidenced it,
-- which is when the thing was first stated. The analogue of `statement.valid_from`, and what orders
-- two findings against each other and what a recency filter reads. A restatement adds evidence
-- without making the obligation newer than the promise.
UPDATE commitment c
   SET source_time = e.first_stated
  FROM (SELECT commitment, min(source_time) AS first_stated FROM commitment_evidence GROUP BY commitment) e
 WHERE e.commitment = c.id;

UPDATE event v
   SET source_time = e.first_stated
  FROM (SELECT event, min(source_time) AS first_stated FROM event_evidence GROUP BY event) e
 WHERE e.event = v.id;

-- The inline span is vestigial from here: the evidence tables above hold every span a finding has,
-- and nothing reads these four columns. They stay because nothing in this schema is removed, and
-- they stop being required because a finding written from now on has no single span to put in them.
ALTER TABLE commitment ALTER COLUMN record_kind DROP NOT NULL;
ALTER TABLE commitment ALTER COLUMN record_source DROP NOT NULL;
ALTER TABLE commitment ALTER COLUMN record_native_id DROP NOT NULL;
ALTER TABLE commitment ALTER COLUMN ordinal DROP NOT NULL;
ALTER TABLE event ALTER COLUMN record_kind DROP NOT NULL;
ALTER TABLE event ALTER COLUMN record_source DROP NOT NULL;
ALTER TABLE event ALTER COLUMN record_native_id DROP NOT NULL;
ALTER TABLE event ALTER COLUMN ordinal DROP NOT NULL;

-- The real key, and it is content. The same obligation read again -- restated in a later message,
-- or re-derived from the same message under an unchanged prompt -- collides here and accumulates
-- evidence instead of becoming a second row. Two findings read out of one message no longer
-- collide, because what separates them is what they say.
--
-- **Partial, over the survivors.** A superseded reading keeps its row and must not be compared
-- against anything, which is the same shape `V9` gives a merged contact.
--
-- `stated_by` is deliberately absent from the key. A later restatement is a different author's
-- message, so including it would keep exactly the pair this is here to collapse.
CREATE UNIQUE INDEX commitment_claim
    ON commitment (conversation_source, conversation_native_id, claim)
 WHERE superseded_by IS NULL;

CREATE UNIQUE INDEX event_claim
    ON event (conversation_source, conversation_native_id, claim)
 WHERE superseded_by IS NULL;
