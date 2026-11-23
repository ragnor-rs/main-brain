-- What inference has already read stays read, across the model it is asked on.
--
-- The watermark carries the extractor's version, and the model's name is in that version -- so
-- pointing recognition at another model makes every window inside the horizon owed again. That
-- is the mechanism working: a different model reads a window differently. It is also, here, a
-- bill to replace output that already exists.
--
-- Measured on this deployment's own log: 464 records read at about four and a half cents each,
-- which is most of the credit provisioned for this build. Re-reading them on the cheaper model
-- would spend roughly half that again to produce findings of the same kinds from the same text.
-- The saving the cheaper model exists for is on the windows nobody has read yet, and it is not
-- reached by discarding the ones somebody has.
--
-- **`Inferrer` already settles this question for the other configuration value that costs money
-- to re-apply.** The recency horizon is deliberately kept out of the version, in as many words,
-- because widening it "should re-derive the material it newly admits and nothing else" rather
-- than "re-extract everything already read inside the old horizon, at full model cost, for a
-- configuration change". The model is the same class of value and was left in. This migration is
-- what makes the two consistent, without changing how a version is composed.
--
-- **What it costs is provenance, and that is the honest price of it.** After this runs, 464 marks
-- name a model that did not read them. Nothing downstream reads the version for anything but the
-- comparison that decides what is owed, so nothing breaks -- but the table can no longer say which
-- model produced which finding, and a later question about why older findings read differently
-- from newer ones has no answer here. That is accepted deliberately, in exchange for not spending
-- the credit twice.
--
-- A deliberate re-read is still available and is now what it should be: an explicit act. Deleting
-- the marks for the records concerned makes them owed again, which is a decision somebody takes
-- rather than a consequence of editing a variable.

-- Scoped three ways, and each one matters.
--
-- To `recognition`, because **the index must keep invalidating on its model** -- vectors from two
-- embedding models are not comparable, so a mixed index returns wrong neighbours rather than
-- differently-phrased ones. That is correctness, where this is cost, and `V12` is the migration
-- that carried the consequence of exactly that.
--
-- To the `inference-1:` marks, because identity's version carries thresholds and no model at all,
-- and it costs nothing to re-derive -- it is identifier matching and label reading.
--
-- And by `replace` on the model's name rather than by writing the whole version string, because
-- the rest of it -- the digest of the two prompts, the two efforts, the window shape -- is
-- whatever this store last read with, and none of it is this migration's business to assert.
UPDATE derivation
SET version = replace(version, 'claude-opus-5', 'claude-sonnet-5')
WHERE deriver = 'recognition'
  AND version LIKE 'inference-1:claude-opus-5@%';
