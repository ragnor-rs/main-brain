-- Identity: contacts, the links that join them, and the constraints that forbid a join.
--
-- Derived, like `V3`'s tables and for the same reason -- re-runnable from the corpus `V1` holds,
-- and separable from it. A model change or a corrected weight rewrites what is here without any
-- source being read again.
--
-- The no-removal rule from `V1` is a rule about the corpus, and these are not corpus tables.
-- What a source said is never destroyed; a judgement this layer made about it may be unmade,
-- which is exactly what unmerging a wrongly joined pair is. So the rule that holds here is
-- narrower and still absolute: nothing in this migration deletes anything, nothing is written
-- with a cascade, and no row in `V1` is touched by anything below.
--
-- Records are referenced by the source's own identifier pair, following `span` and `derivation`
-- rather than `identifier`'s surrogate `record_id`. The pair is what survives a re-derivation:
-- an entity identifier is held by everything downstream and a contact outlives any particular
-- read of the record it was made from. The cost is the one `V1` names -- a source that renumbers
-- an account leaves these rows pointing at an identifier no record carries any more, and nothing
-- here notices.

-- A human as the system holds them: one row per person record, before any merging.
--
-- One per record and not one per human, which is the whole of why merging can be reversible. A
-- contact exists the moment a person record is recognised, with no judgement involved; deciding
-- that two of them are one human is a separate scored step that writes a `merge_link` and fuses
-- nothing. The count of rows here is the count of accounts seen, and the count of humans is the
-- count of link heads.
CREATE TABLE contact (
    -- Assigned by the recogniser and not by this table, which is why there is no `bigserial`
    -- here. A sequence would mean a row insert is what brings an entity into being, and identity
    -- would then be assigned wherever a row is written. A recognition pass assigns it, and no
    -- store and no source adapter may.
    --
    -- It also never changes. Merging renumbers neither side, so anything already holding one of
    -- these keeps holding it.
    id                uuid        PRIMARY KEY,
    -- Always a person record, so there is no discriminator. The other kinds become other
    -- entities: a conversation carrying a topic label becomes a client, which is its own table
    -- for the same reason this one is not polymorphic -- a contact and a client are not two
    -- flavours of one thing and share no column but the identifier.
    record_source     text        NOT NULL,
    record_native_id  text        NOT NULL,
    -- Carried rather than read back through the record: it is what the name signal compares and
    -- what anything rendering a contact shows. Whatever a human typed into a profile field, so
    -- it is evidence and never identity.
    display_name      text,
    recognised_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT contact_record UNIQUE (record_source, record_native_id)
);

-- An organisation the work is for, read from the label its conversation carries.
--
-- Its own table rather than a kind column on `contact`, because a contact and a client share no
-- column but the identifier and nothing reads them together. They are also recognised quite
-- differently: a contact is matched and merged, and a client is read off a name.
CREATE TABLE client (
    id                uuid        PRIMARY KEY,
    -- Always a conversation record, so no discriminator, as with `contact`.
    record_source     text        NOT NULL,
    record_native_id  text        NOT NULL,
    -- The label as the source gives it. A taxonomy that lives in names cannot be recovered by
    -- re-deriving if the name never entered the corpus, so nothing normalises it away.
    name              text        NOT NULL,
    recognised_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT client_record UNIQUE (record_source, record_native_id)
);

-- A decision that two contacts are one human, as a link rather than a rewrite.
--
-- `merged` is the primary key, which is what makes "at most one head per contact" a constraint
-- instead of a rule the write path has to remember. Resolving a contact follows this table until
-- a row is missing.
CREATE TABLE merge_link (
    merged      uuid             PRIMARY KEY REFERENCES contact (id),
    canonical   uuid             NOT NULL REFERENCES contact (id),
    -- The evidence as it stood when the decision was taken, not as it stands now. Re-scoring a
    -- stored link would rewrite the reason a merge happened, which is the one thing someone
    -- undoing it needs to read.
    score       double precision NOT NULL,
    -- What carried it, as `KIND:weight`. Text rather than rows because nothing queries them:
    -- they are read back whole when a human asks why two accounts were joined. The same shape
    -- `ingest_state.lagging_units` already uses.
    signals     text[]           NOT NULL DEFAULT '{}',
    linked_at   timestamptz      NOT NULL DEFAULT now(),
    CONSTRAINT merge_link_not_itself CHECK (merged <> canonical)
);

-- Reaching the merged side from the head, which is how a contact is read back with everything
-- that resolves to it. The primary key serves the other direction.
CREATE INDEX merge_link_by_canonical ON merge_link (canonical);

-- Two contacts a correction has forbidden from ever merging.
--
-- Only corrections are stored. The other hard negative -- two person records that are
-- simultaneously distinct members of one conversation -- is computed from the corpus on every
-- pass, because that is where it is visible and a stored copy could only go stale. A correction
-- is nowhere in the corpus: a human who unmerged a pair has said something the evidence does
-- not say, and a pass reading only the evidence would merge them again on its next run.
CREATE TABLE separation (
    one          uuid        NOT NULL REFERENCES contact (id),
    other        uuid        NOT NULL REFERENCES contact (id),
    recorded_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT separation_pair PRIMARY KEY (one, other),
    -- Stored with the pair ordered, so that a separation discovered from either side is one row.
    -- Without this the constraint meant to forbid a merge can be stored twice and checked under
    -- whichever ordering the caller happened to hold -- which is to say, not checked.
    CONSTRAINT separation_ordered CHECK (one < other)
);

CREATE INDEX separation_by_other ON separation (other);

-- A pair that scored without reaching the threshold, kept with what it scored.
--
-- Kept rather than discarded, and not queued for a person to approve, because a queue of these
-- would never be worked. The corpus grows: a pair scoring halfway today crosses the line the
-- moment a number appears on one side, and holding the score is what makes that a re-evaluation
-- of one pair rather than a rescoring of everything.
CREATE TABLE candidate (
    one        uuid             NOT NULL REFERENCES contact (id),
    other      uuid             NOT NULL REFERENCES contact (id),
    -- Replaced on each scoring, unlike a link's. A candidate is a running assessment of evidence
    -- that is still growing; a link is a decision that was taken at a moment.
    score      double precision NOT NULL,
    signals    text[]           NOT NULL DEFAULT '{}',
    scored_at  timestamptz      NOT NULL DEFAULT now(),
    CONSTRAINT candidate_pair PRIMARY KEY (one, other),
    CONSTRAINT candidate_ordered CHECK (one < other)
);

CREATE INDEX candidate_by_other ON candidate (other);
