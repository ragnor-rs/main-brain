-- The dense half, at the width of the model this build runs.
--
-- `V3` declared 1024 and said in as many words that another width is a migration rather than a
-- configuration change, because an index cannot span two. This is that migration.
--
-- Why the width moved: the model `V3` was dimensioned for yielded the dense vector and the
-- lexical half's term weights from one pass, which is why it was chosen. Measured on the
-- deployment's own processor it spent about a second on one short message and six seconds on a
-- single question -- and a question is embedded on the answer path, with a person waiting.
-- Nothing yields both halves at a size that answers in time, so the dense half is kept and the
-- lexical one is left unfilled.
--
-- The `sparse` column therefore stays, and is written empty. That is not an oversight: `V3`
-- declares an empty vector representable and names it as what a dense-only model stores, and
-- the fused read falls back to its vector half when a query carries no weights. Keeping the
-- column is what makes a later model that yields them a change in one file rather than here.

-- Dropped before the type changes and rebuilt after: an index is built for a column of one
-- declared width, and Postgres will not carry one across the alteration.
DROP INDEX span_by_dense;

-- **This refuses rather than clears, and that is the whole of its safety.** A vector carries
-- its width in its type, so there is no conversion from one width to another -- the values
-- would have to go. Postgres therefore rejects this statement outright on a table holding
-- spans of the old width, and the deploy stops with the width it found in the message.
--
-- Which is the right way round, and is the no-removal rule `V1` states and `V3` inherits: a
-- removal is never an instruction, so a migration does not quietly discard what a pass
-- produced. Re-indexing a populated store at another width is a deliberate operation someone
-- decides on, not a side effect of starting a container.
--
-- It succeeds here because the index has never been built: the pass that fills it could not
-- reach the sidecar within the ceiling its client actually enforced, so `span` is empty and
-- `derivation` carries no `index` mark in any store this has run against.
ALTER TABLE span
    ALTER COLUMN dense TYPE vector(768);

-- Cosine again, matching how the dense half is compared at read time, exactly as `V3` set it.
-- The operator class and the query have to agree, and the query has not changed.
CREATE INDEX span_by_dense ON span USING hnsw (dense vector_cosine_ops);

-- The watermark is deliberately left alone. It is keyed by deriver and record rather than by
-- version, and the pass compares the version it stored against the one the sidecar now
-- reports -- which differs, because the model's name is in it. So every message is owed again
-- without a row being touched here, and re-deriving overwrites each mark in place.
