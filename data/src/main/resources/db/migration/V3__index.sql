-- The index, and the watermark that says what has been derived from what.
--
-- Everything here is derived: re-runnable from the corpus `V1` holds, and separable from it.
-- That separation is why the two live in different migrations rather than different schemas —
-- re-deriving touches only these tables, and a model change rewrites them without the corpus
-- being read from a source again.
--
-- The no-removal rule from `V1` applies unchanged. Re-indexing a span overwrites it in place;
-- it never clears first.

-- Both derived tables point at a record they do not own, and neither carries a foreign key to
-- it. The same reasoning as `identifier` in `V1`: the reference is polymorphic over the four
-- record kinds, so there is no one table to point at, and four nullable keys would turn every
-- read into a four-way union, which is the reasoning `identifier` already records.
--
-- Where this differs from `identifier`, deliberately: that table points at a record by its
-- surrogate `record_id`, and these two point at it by the source's own identifier pair. The
-- pair is what a citation has to survive on -- a span identifier is written into an answer and
-- checked long after, so it cannot be a row number that a re-index reissues. The cost is the
-- one `V1` names for the surrogate: a source that renumbers a conversation leaves these rows
-- pointing at an identifier no record carries any more. Nothing rewrites them, and nothing
-- here notices.

-- The corpus made findable. One row per retrievable piece of text, with the vectors that find
-- it and the text itself -- the text is held rather than re-read because it is what an answer
-- quotes and what a citation is checked against.
CREATE TABLE span (
    id                bigserial    PRIMARY KEY,
    record_kind       text         NOT NULL,
    record_source     text         NOT NULL,
    record_native_id  text         NOT NULL,
    -- A position within the record, so that one record can have several spans. A message is
    -- indexed whole and is always 0; the only kind that needs more is a document, which is
    -- split because a file is too large for a single embedding. Carrying the column now is
    -- what makes that an addition rather than a rewrite of every stored identifier.
    ordinal           integer      NOT NULL DEFAULT 0,
    body              text         NOT NULL,
    -- When the material is from, never when it was captured -- the same axis every recency
    -- filter in the corpus reads, for the same reason.
    source_time       timestamptz  NOT NULL,
    -- 1024 is the dense width of the embedding model this build runs. A model of another
    -- width is a migration, not a configuration change, because an index cannot span two.
    dense             vector(1024) NOT NULL,
    -- The lexical half, as term weights rather than a second index type: the model emits them
    -- in the same pass as the dense vector. The width is the model's vocabulary. An empty
    -- vector is representable and is what a dense-only model would store, which degrades the
    -- fused read to its vector half rather than failing.
    --
    -- At most 1000 terms may be stored, because that is pgvector's ceiling for a `sparsevec`
    -- under an HNSW index -- past it the write itself is refused. The provider therefore
    -- yields the strongest weights rather than every one; see `EmbeddingProvider`.
    sparse            sparsevec(250002) NOT NULL,
    indexed_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT span_unit UNIQUE (record_kind, record_source, record_native_id, ordinal),
    -- Messages only, for now, and the constraint says so rather than the comment alone.
    -- `source_time` above is NOT NULL, which a message always has and the other three kinds
    -- may lack -- so admitting them here would mean a span that cannot be stored for want of
    -- a time nobody has. Whichever workstream indexes a document decides what that span's
    -- time is, and widens this then.
    CONSTRAINT span_record_kind CHECK (record_kind IN ('message'))
);

-- Cosine, matching how the dense half is compared at read time. An index built for one
-- operator does not serve another, so this and the query have to agree.
CREATE INDEX span_by_dense ON span USING hnsw (dense vector_cosine_ops);

-- Inner product for the sparse half: term weights score by how much weight the query and the
-- span share, which is their dot product.
CREATE INDEX span_by_sparse ON span USING hnsw (sparse sparsevec_ip_ops);

-- Reaching a span from the record it came from -- what neighbour expansion and re-indexing
-- both do, and what the unique constraint above cannot serve on its own because neither knows
-- the ordinal in advance.
CREATE INDEX span_by_record ON span (record_kind, record_source, record_native_id);

-- How far each derived pass has got, and against which version of itself.
--
-- One table for both passes rather than one each. They are invalidated by the same two things
-- -- a changed version of the pass, and changed content in the record -- so two tables would
-- be two sets of rules that have to be kept agreeing.
CREATE TABLE derivation (
    id                bigserial   PRIMARY KEY,
    deriver           text        NOT NULL,
    record_kind       text        NOT NULL,
    record_source     text        NOT NULL,
    record_native_id  text        NOT NULL,
    -- Opaque, and compared only for equality. It identifies the embedding model for indexing
    -- and the prompt for recognition; what matters is that it changes whenever the pass's
    -- output would, because a change it does not capture is a change nothing re-derives.
    version           text        NOT NULL,
    derived_at        timestamptz NOT NULL DEFAULT now(),
    -- One mark per record per pass, not a history. A re-derivation overwrites the mark in
    -- place; what the previous version produced is not interesting once it has been replaced.
    CONSTRAINT derivation_mark UNIQUE (deriver, record_kind, record_source, record_native_id),
    CONSTRAINT derivation_deriver CHECK (deriver IN ('index', 'recognition')),
    CONSTRAINT derivation_record_kind CHECK (record_kind IN ('conversation', 'message', 'person', 'document'))
);
