-- Candidate generation reads person records by their normalised display name, so that two
-- accounts of one human are compared even when they share no identifier — which on a
-- phone-anchored source is every such pair, because a handle is unique per account and a number is
-- fixed when one is registered.
--
-- The expression is the one the matcher's name key writes and the frequency count already reads:
-- trim, collapse runs of whitespace, lowercase. Three statements now depend on it agreeing with
-- itself, and an index on a different normalisation would be an index the planner never uses while
-- every read still returned the right rows — slow rather than wrong, which is the harder thing to
-- notice.
--
-- Not unique, and could not be: two person records carrying one name is the evidence being looked
-- for, exactly as with the identifier table's deliberately absent constraint on (type, value).
CREATE INDEX person_by_name_key
    ON person (lower(btrim(regexp_replace(display_name, '\s+', ' ', 'g'))));
