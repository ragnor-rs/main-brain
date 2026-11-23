-- The corpus: the four record kinds, their claims, and the three time axes.
--
-- Every table here holds what a source said. Nothing derived lives in this schema -- no
-- entity identifier, no embedding, no extracted fact -- because all of that is re-derivable
-- from these rows and must stay separable from them.
--
-- Two properties hold across all four record tables and are worth reading once rather than four
-- times. Neither applies to `identifier`, which has its own key and only one axis; see its own
-- comment below.
--
--   * A surrogate `id` with a uniqueness constraint on the external identifier, rather than
--     the external identifier as the primary key. Sources reissue and migrate their own: a
--     group promoted to a supergroup arrives with a new identifier for a conversation that
--     already exists, and every row referring to it would otherwise have to be rewritten.
--
--   * Three time axes, kept apart. `source_time` is when the source says it happened;
--     `added_at` and `last_seen_at` are observation, stamped by the store on first capture
--     and on every pass that still finds the record; `content_time` moves only when content
--     actually changed. Conflating the last two is the expensive mistake -- a full resync
--     would make the whole corpus look freshly modified and every recency filter built on it
--     would be wrong.
--
-- There is no `ON DELETE` clause anywhere below, and no migration after this one may add
-- one. A source dropping something is an observation about the source, not an instruction to
-- destroy what was captured.

-- Needed by the index, which is a later concern, but created here because this is the only
-- migration that runs before everything else and nothing in the stack creates it otherwise.
-- The image carries pgvector; the extension still has to be asked for.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE conversation (
    id               bigserial   PRIMARY KEY,
    source           text        NOT NULL,
    native_id        text        NOT NULL,
    kind             text        NOT NULL,
    name             text,
    -- The parent is held as the source's own identifier pair rather than as a foreign key to
    -- this table. A batch is not ordered, so a topic can arrive before the supergroup that
    -- contains it, and a parent not yet captured is an ordinary state rather than an error.
    parent_source    text,
    parent_native_id text,
    source_time      timestamptz,
    added_at         timestamptz NOT NULL DEFAULT now(),
    last_seen_at     timestamptz NOT NULL DEFAULT now(),
    content_time     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT conversation_external_id UNIQUE (source, native_id),
    CONSTRAINT conversation_parent_whole CHECK ((parent_source IS NULL) = (parent_native_id IS NULL))
);

CREATE TABLE message (
    id               bigserial   PRIMARY KEY,
    source           text        NOT NULL,
    native_id        text        NOT NULL,
    -- NOT NULL and a real foreign key: a message without its conversation is the one thing
    -- this schema will not hold. The write path cannot express it either, but a constraint
    -- costs nothing and does not depend on the write path staying correct.
    conversation_id  bigint      NOT NULL REFERENCES conversation (id),
    author_source    text,
    author_native_id text,
    body             text,
    source_time      timestamptz NOT NULL,
    added_at         timestamptz NOT NULL DEFAULT now(),
    last_seen_at     timestamptz NOT NULL DEFAULT now(),
    content_time     timestamptz NOT NULL DEFAULT now(),
    -- The native identifier is unique within the source, not within the conversation. A
    -- platform that numbers messages per chat must therefore hand over an identifier
    -- qualified by its chat; a bare per-chat number would have two unrelated messages share
    -- one. That qualifying is the adapter's to do, and this constraint cannot check it — what
    -- it does is make the collision reachable, so that the write path can refuse it. The
    -- refusing is in the upsert, which never updates `conversation_id`: a message arriving
    -- under a different conversation than the stored row fails rather than moving it.
    CONSTRAINT message_external_id UNIQUE (source, native_id),
    CONSTRAINT message_author_whole CHECK ((author_source IS NULL) = (author_native_id IS NULL))
);

-- Reading a conversation in order, which is how every window and every answer reaches it.
CREATE INDEX message_by_conversation ON message (conversation_id, source_time);

-- Recency filters read source time, never observation time: a backfill captures a five-year-old
-- message today, and filtering on when it was captured would make old history look current.
CREATE INDEX message_by_source_time ON message (source_time);

CREATE TABLE person (
    id           bigserial   PRIMARY KEY,
    source       text        NOT NULL,
    native_id    text        NOT NULL,
    display_name text,
    source_time  timestamptz,
    added_at     timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    content_time timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT person_external_id UNIQUE (source, native_id)
);

CREATE TABLE document (
    id                bigserial   PRIMARY KEY,
    source            text        NOT NULL,
    native_id         text        NOT NULL,
    name              text,
    media_type        text,
    body              text,
    -- The message an attachment arrived on, held as the source's own identifier pair and not
    -- as a foreign key -- the same treatment a conversation's parent gets, for a stronger
    -- reason. A foreign key would store only references that resolve, so a document whose
    -- message has not been captured would read back without the reference it was written
    -- with. Keeping the pair means what a source said survives whether or not the rest of
    -- what it said has arrived. Nullable because a document-only source has no messages.
    message_source    text,
    message_native_id text,
    source_time       timestamptz,
    added_at          timestamptz NOT NULL DEFAULT now(),
    last_seen_at      timestamptz NOT NULL DEFAULT now(),
    content_time      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_external_id UNIQUE (source, native_id),
    CONSTRAINT document_message_whole CHECK ((message_source IS NULL) = (message_native_id IS NULL))
);

-- Supplemental identifiers: handles, phone numbers, addresses, places, content hashes.
--
-- One table across every record kind, with a discriminator instead of four foreign keys. The
-- usual argument for a table per kind is referential integrity on delete, and nothing here is
-- ever deleted; meanwhile the read this exists to serve -- which records claim this value? --
-- is the one the recogniser is built on, and it crosses kinds. Four tables would make it a
-- four-way union.
--
-- `record_id` therefore carries no foreign key. That a claim belongs to a record that exists
-- is held by the write path, which inserts the claim in the same transaction as the record it
-- was read from.
CREATE TABLE identifier (
    id           bigserial   PRIMARY KEY,
    record_kind  text        NOT NULL,
    record_id    bigint      NOT NULL,
    type         text        NOT NULL,
    value        text        NOT NULL,
    -- Only the observation axis. A claim is not an event, so it has no source time; and its
    -- value is part of its key, so a changed value is a different claim rather than this one
    -- edited -- which leaves no content to time.
    added_at     timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    -- Unique per record, deliberately NOT unique on (type, value). Two records claiming one
    -- phone number is precisely the evidence reconciliation scores on, so refusing it here
    -- would turn the strongest available signal into a write error. What this does forbid is
    -- the same claim twice on one record, which is what makes re-delivering a batch idempotent.
    CONSTRAINT identifier_claim UNIQUE (record_kind, record_id, type, value),
    CONSTRAINT identifier_record_kind CHECK (record_kind IN ('conversation', 'message', 'person', 'document'))
);

-- The matcher's direction: given a value, which records claim it. Not unique, for the reason
-- stated on the constraint above.
CREATE INDEX identifier_by_value ON identifier (type, value);
