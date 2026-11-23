-- Which named conversations stand for a body of work, as a judgement beside the label.
--
-- Identity reads a client off any shared conversation's name, which is right where every named
-- conversation is a body of work and wrong where a source mixes client threads with thematic
-- tracks -- referrals, partner programmes, banks, HR. Telling one from the other means knowing
-- what the names mean, which is inference rather than a read, so it is a pass of its own and what
-- it decides lives here.

-- The passes the watermark admits, widened by one.
--
-- A check constraint cannot be extended in place, so it is replaced. Nothing is dropped but the
-- constraint itself: every row keeps the value it had, and both existing values stay legal.
--
-- **Its own pass identifier rather than recognition's, and that is forced rather than chosen.** A
-- mark is one row per pass, record kind and record -- `derivation_mark` below is unchanged and
-- says so -- with the version compared rather than keyed on. Judging conversations under
-- `recognition` would therefore have this pass and the identity stage overwrite one mark with each
-- other's version, and both would read the whole conversation table on every sweep for ever, one
-- of them at the model's prices.
ALTER TABLE derivation
    DROP CONSTRAINT derivation_deriver;

ALTER TABLE derivation
    ADD CONSTRAINT derivation_deriver CHECK (deriver IN ('index', 'recognition', 'admission'));

-- The same vocabulary, in the two tables a day's spend is totalled from.
--
-- One vocabulary of passes and not three, which is what lets a day's figure be taken per pass: a
-- charge under a value one of these tables refuses would be money paid and totalled into nobody's
-- day. `model_submission` is widened with it though this pass makes no submission -- what a stage
-- may hand to the batch endpoint is bounded by `model_submission_stage`, and leaving one table
-- ignorant of a pass the others know would make the deriver column mean something different in
-- each.
ALTER TABLE model_submission
    DROP CONSTRAINT model_submission_deriver;

ALTER TABLE model_submission
    ADD CONSTRAINT model_submission_deriver CHECK (deriver IN ('index', 'recognition', 'admission'));

ALTER TABLE model_charge
    DROP CONSTRAINT model_charge_deriver;

ALTER TABLE model_charge
    ADD CONSTRAINT model_charge_deriver CHECK (deriver IN ('index', 'recognition', 'admission'));

-- What the pass decided about one conversation.
--
-- **Its own table rather than a column on `client`**, which keeps the label apart from an
-- inference about the label. A client row is a reading of a name and nothing about what the name
-- means changes it; a judgement written onto it would be re-derived away the next time identity
-- wrote the row, and a reader could no longer tell a conversation nobody has judged from one
-- judged to be a body of work.
--
-- **Keyed on the conversation and not on the client**, because that is what the pass reads and what
-- its watermark marks. It also lets a verdict outlive a re-derivation of the client, and lets one
-- be recorded for a conversation whose client identity has not been assigned yet.
--
-- **An absent row means admitted.** Nothing here has to run for the corpus to be answerable: a
-- conversation this pass has not reached is a client exactly as it was before the pass existed,
-- which is what keeps identity standing on its own and lets this lag the corpus arbitrarily, or
-- never run at all.
--
-- **Nothing is deleted, and no client row is touched.** What the pass rejects is filtered on read
-- -- see `admitted_client` below -- as the no-removal rule this layer works under requires: a
-- judgement may be unmade, and unmaking one must leave the reading it was made about intact.
CREATE TABLE client_admission (
    -- The conversation, as the source's own pair, following `derivation` and `client` rather than
    -- `identifier`'s surrogate. It is what survives a re-derivation of either side.
    record_source     text        NOT NULL,
    record_native_id  text        NOT NULL,
    -- What it was judged to be. Both verdicts are recorded, not only the rejection, for the reason
    -- the closure verdict records its negative: a conversation nothing has rejected is
    -- indistinguishable from one nothing has judged, so without the positive written down every
    -- sweep pays the model to reach the same answer about the same conversation.
    standing          text        NOT NULL,
    -- Everything that would make two runs disagree about one conversation -- the wording, the
    -- model, the effort and how much of the conversation was sampled -- as one opaque string.
    --
    -- **On the row rather than in the watermark, though this pass has a watermark too.** The mark
    -- says the conversation was looked at; this says what looked at it, which is what lets a
    -- reworded instruction re-judge what an older one decided. Compared for equality only.
    version           text        NOT NULL,
    judged_at         timestamptz NOT NULL DEFAULT now(),
    -- One verdict per conversation, replaced by the next judgement rather than accumulated. A
    -- history of judgements is not something any reader here asks for, and two live verdicts about
    -- one conversation would leave the read filter picking between them.
    CONSTRAINT client_admission_record PRIMARY KEY (record_source, record_native_id),
    -- The two things a named conversation can be. Stated as a constraint so that a verdict the
    -- read filter has no branch for cannot be stored: an unrecognised value would otherwise read
    -- as admitted, which is the direction that silently does nothing.
    CONSTRAINT client_admission_standing CHECK (standing IN ('BODY_OF_WORK', 'THEMATIC_TRACK'))
);

-- The clients an answer may be about: every one the pass has not rejected.
--
-- **A view rather than a predicate repeated at each reader.** Clients are read across three
-- adapters -- subject resolution, the hop into a client's people, the body of work a contact sits
-- under, resolving a client to its conversations, and the sectioning of an answer -- and a filter
-- written once per statement is as many things that have to agree as there are statements. Written
-- once here, a reader that forgets it is the only way to bypass it, which is a rule over the source
-- rather than a review note.
--
-- **Columns are enumerated and not `*`.** A view expands a star once, when it is created, so a
-- column added to `client` later would be invisible through here with nothing failing to say so.
--
-- `LEFT JOIN`, so a conversation with no verdict is admitted: that is the rule the table above
-- states, expressed where it is read.
CREATE VIEW admitted_client AS
SELECT c.id,
       c.record_source,
       c.record_native_id,
       c.name,
       c.recognised_at
FROM client c
LEFT JOIN client_admission a
       ON a.record_source = c.record_source
      AND a.record_native_id = c.record_native_id
WHERE a.standing IS NULL
   OR a.standing <> 'THEMATIC_TRACK';
