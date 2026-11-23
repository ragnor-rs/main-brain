-- Statements: what is known about an entity, over the period it held, with the corpus it was read
-- out of.
--
-- Derived, like `V3`'s tables and `V9`'s, and re-runnable from the corpus `V1` holds. A changed
-- prompt rewrites what is here without any source being read again.
--
-- `V9`'s narrower no-removal rule holds: no row in `V1` is touched by anything below, nothing is
-- written with a cascade, and a fact that stopped being true has its validity window closed
-- rather than removed — "it was true until June" and "it was never true" are different answers
-- and the second one is a lie.
--
-- **One removal is permitted, and it is the unmaking of a judgement rather than of a fact.** A
-- statement's key includes the start of its window, so when a backfill delivers an older reading
-- of a value already stored, resolution recomputes the window as opening earlier and that is a
-- different key. The row it replaces is deleted, with its evidence rows first because nothing
-- here cascades. What is removed is this layer's own earlier reading of the same evidence, which
-- the surviving row then carries in full; the corpus said nothing about it either way. The same
-- unmaking `V9` permits when an unmerge removes a merge link.

-- One fact about one entity, over the interval it held.
--
-- **Validity is here and not on an entity.** A contact has no period of validity; that a contact
-- works at a company does. A value that changes becomes two rows with adjacent windows rather
-- than one column overwritten, which is what makes "what was true when we last spoke" a question
-- the schema can answer.
CREATE TABLE statement (
    -- A surrogate, unlike `contact` and `client`, and the difference is the point. Those hold an
    -- identifier the recogniser minted, because an entity's identity is assigned in exactly one
    -- place and a row insert may not be what brings one into being. A statement has no identity
    -- to assign: it *is* its content, and the unique constraint below is its real key. This
    -- column exists so that evidence can reference a fact in one column instead of four, the
    -- same way `span` carries one.
    id           bigserial        PRIMARY KEY,
    -- The entity the fact is about -- a contact or a client. Polymorphic, so no foreign key, for
    -- the reason `identifier` and `span` already record: there is no one table to point at, and
    -- a nullable key per kind turns every read into a union. The cost here is specific and worth
    -- naming: nothing refuses a statement about an entity that was never created. What closes
    -- that is identity being minted by a recognition pass and by nothing else, so there is one
    -- producer of these rows and
    -- it has the entity in hand.
    subject      uuid             NOT NULL,
    -- What is being asserted, as a normalised name. Text rather than a constrained set, because
    -- the vocabulary is the extractor's and is not settled -- a `CHECK` here would mean a prompt
    -- that named a new relation could not store what it emitted until this file was migrated.
    -- Two names for one relation are two facts that will never be compared; holding that line is
    -- extraction's job, done by fixing the model's vocabulary rather than by rejecting its
    -- output at the last possible moment.
    predicate    text             NOT NULL,
    value        text             NOT NULL,
    -- The source time of the evidence that opened the window, never the moment of extraction. A
    -- backfill reads 2021 after it read yesterday, so ordering by when the system learnt
    -- something would let old history close windows over current facts as it arrived.
    valid_from   timestamptz      NOT NULL,
    -- Open while the fact still holds, which is the ordinary case and not a missing value.
    -- Nothing closes a window but a later, contradicting statement: a fact going unmentioned is
    -- not evidence that it stopped being true.
    --
    -- Outside the key on purpose, so that closing a window is an update rather than a delete and
    -- an insert. The write path's rule is one-way -- a close is never reopened by a pass
    -- re-deriving the earlier fact without the later one in its batch.
    valid_until  timestamptz,
    -- How the fact was arrived at, which decides how the assistant may phrase it and which of
    -- two readings wins a tie. A model's reading is the weakest and is never presented as a bare
    -- fact; a source's own structure outranks it; a person's correction outranks both.
    origin       text             NOT NULL,
    -- When the system came to believe it, kept separately from when it held. Set once: a
    -- re-derivation that closes a window has not changed when the fact was first believed, so
    -- the upsert leaves this alone.
    believed_at  timestamptz      NOT NULL DEFAULT now(),
    -- The real key, and it is content. Nothing mints an identifier for a statement, so the same
    -- fact read again out of the same corpus collides here and writes nothing new -- which is
    -- what makes re-running the pass free.
    --
    -- `value` is in it because a contradiction keeps both readings: same subject, same predicate,
    -- a different value and later evidence closes the earlier window where the later one opens.
    -- Without the value that correction would be an overwrite, and the history this table exists
    -- for would be gone. `valid_from` is in it because one value can hold over two separate
    -- periods -- a rate quoted in March, revised in June and restored in September is three
    -- rows, two of which agree on everything else.
    CONSTRAINT statement_fact UNIQUE (subject, predicate, value, valid_from),
    -- Half-open: a window covers `valid_from` and everything before `valid_until`. Closing the
    -- earlier of two windows exactly where the later one opens therefore leaves no gap and no
    -- overlap, so the instant they share belongs to one of them and a question about it has one
    -- answer.
    CONSTRAINT statement_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT statement_origin CHECK (origin IN ('inferred', 'labelled', 'confirmed'))
);

-- No index on `subject` alone. `statement_fact` leads with it, so reading every statement about an
-- entity -- which is how resolution and the answer path both read this table -- is served by that
-- constraint's own index.

-- The corpus a statement was read out of: one row per fact per span that says it.
--
-- Several rows per statement is corroboration rather than duplication. The same fact read out of
-- two *different* spans is one fact with two pointers, and anything presenting it can show
-- either; the same span read twice -- which overlapping recognition windows guarantee -- collides
-- on the primary key and changes nothing.
CREATE TABLE statement_evidence (
    statement         bigint      NOT NULL REFERENCES statement (id),
    -- The span's address, in the columns `span` uses for the same tuple. **No foreign key to
    -- `span`**, and that is not an omission: a span row is derived and is rewritten wholesale by
    -- a re-index, while this address is built from the source's own identifier and survives it.
    -- A statement may also cite a record the index does not hold. What makes the pointer safe is
    -- the corpus rule rather than a constraint -- nothing in `V1` is ever deleted, so a pointer
    -- written today still resolves.
    record_kind       text        NOT NULL,
    record_source     text        NOT NULL,
    record_native_id  text        NOT NULL,
    ordinal           integer     NOT NULL DEFAULT 0,
    -- The span's own source time, carried here rather than joined back to the record. It is what
    -- orders two contradicting statements, so a resolution that had to fetch the record would
    -- read the corpus once per pair it ordered.
    source_time       timestamptz NOT NULL,
    CONSTRAINT statement_evidence_span PRIMARY KEY (statement, record_kind, record_source, record_native_id, ordinal),
    -- Messages only, as `span` is, and for the identical reason: `source_time` above is NOT NULL,
    -- which a message always has and the other three kinds may lack, so admitting them would mean
    -- a pointer that cannot be stored for want of a time nobody has. Whichever workstream reads a
    -- document decides what that span's time is, and widens this then.
    CONSTRAINT statement_evidence_record_kind CHECK (record_kind IN ('message'))
);

-- Reaching every statement a span evidences, which is what a citation is checked with and what a
-- changed record has to invalidate. The primary key serves the other direction.
CREATE INDEX statement_evidence_by_record ON statement_evidence (record_kind, record_source, record_native_id, ordinal);
