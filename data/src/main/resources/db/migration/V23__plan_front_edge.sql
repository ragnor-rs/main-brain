-- The plan's other edge: how far forward each conversation's records are complete.
--
-- `V4` records how far back a walk has got, which is what backfill owes, and that is the only
-- question an adapter asks while there is history left to read. It cannot express the one an
-- outage asks. A conversation finished at the back is never offered to a walk again, so once
-- backfill completes the only intake is the change feed consumed while the process is listening
-- -- and a conversation that moved while it was not is stored as one that went quiet. Those two
-- readings are identical from inside the plan, and this column is what separates them.
--
-- A source time like `reached`, and for the same reason: it is a point in the conversation's own
-- history, not when anything was read.
ALTER TABLE backfill_plan
    ADD COLUMN newest timestamptz;

-- Seeded from the corpus, because the alternative is a column that can never fill.
--
-- Left at NULL, a row already marked finished would never acquire a front edge: nothing walks a
-- finished unit again, so the one event that would report one never happens, and a catch-up
-- comparing against an unknown edge would wait on it forever. Every unit whose history is already
-- captured would keep exactly the gap this column exists to find.
--
-- The newest message stored for the conversation is the honest value, and it is derivable here
-- because the plan and the corpus are one database and a work unit is deliberately the same string
-- as its conversation's native identifier -- no translation table stands between them. `V1` indexes
-- `(conversation_id, source_time)`, so this is one ordered read per conversation.
--
-- A withdrawn message counts. Nothing is deleted from the corpus, so a conversation whose last
-- message was taken back is still complete up to it; skipping those would place the edge before
-- material that is here and buy a re-read of it on the next start.
--
-- The exact time, not a moment before it. This source's times are whole seconds and several
-- messages of one conversation routinely share one, so an edge cannot be compared strictly against
-- what the source later offers -- but that is the comparison's problem and it is solved there, by
-- treating a shared second as a gap. Standing back a second here would solve it for seeded rows
-- only, at the price of a column that no longer means what it says.
UPDATE backfill_plan AS plan
SET newest = corpus.newest
FROM (
    SELECT conversation.source, conversation.native_id, max(message.source_time) AS newest
    FROM conversation
    JOIN message ON message.conversation_id = conversation.id
    GROUP BY conversation.source, conversation.native_id
) AS corpus
WHERE plan.source = corpus.source
  AND plan.unit = corpus.native_id;

-- A row this leaves NULL is a unit the corpus holds no message for, and the comparison that
-- consumes this column treats unknown as *not behind* rather than as behind: an edge that is
-- absent gives a walk nothing to stop at, so acting on one would be a re-backfill under another
-- name. That is the one case this seed cannot settle and the adapter says out loud instead.
--
-- What this seed claims is *the corpus holds everything up to here*, inferred from the newest
-- record it holds -- and that inference is only as good as the history beneath it. A conversation
-- with a hole further back reads as complete to its newest message either way, so a catch-up will
-- not repair one: it reads down to this edge and stops. A hole behind the front edge is what the
-- deliberate re-read is for, and a corpus known to have one needs that rather than this.
