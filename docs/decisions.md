# Decisions

Scope and platform as they stand, each with the reason behind it. What is still open is
gathered under "Open questions", and what could go wrong under "Risks".

Design decisions are recorded where they are specified, each with the reason behind it: the
shape of the system in [`architecture.md`](architecture.md), what it must do in
[`requirements.md`](requirements.md). A design decision has one home. A few rules are
deliberately stated in more than one document — the scope table, the adapter rule, the
default locale, the demo corpus, how a client is identified — and `scripts/check-docs.sh`
asserts that those agree.

## Scope

Three areas of work: the assistant, the CRM, and the update track. The numbering below takes
the assistant apart, because a first increment carrying three source shapes proves none of
them until all three work. The numbers are an order of delivery rather than a map of the
areas: the assistant is delivered across 0, I and II, and the CRM's interface arrives in I,
ahead of the material it consolidates. Each workstream is an increment of capability rather
than a layer of one system.

| Workstream | Sources ingested | Interface delivered |
|---|---|---|
| **0** | Telegram | The Telegram bot |
| **I** | LinkedIn, Email, call recordings via Plaud | HubSpot Contact |
| **II** | Google Drive, Excel, ClickUp | An MCP server over the corpus and its entities |
| **III** | WhatsApp | Outbound messaging, over Telegram |

**Workstream 0 is the current build**: one source, one interface, and every stage
between them. It is the thinnest slice that works end to end, and what a demonstration
shows. Telegram carries the great majority of the value, so one source is enough to prove
the whole path.

**Workstream I widens what the assistant reads, and puts what it builds where
the team already works.** Its sources need no record kind the build does not have: the
professional network contributes persons and the attributes on them, email and call
transcripts are conversations. What they add is reach — the largest contact store in the
company, and the channels carrying the correspondence Telegram does not. Its interface
is the CRM rather than a second chat; what is wanted from the CRM is in
[`requirements.md`](requirements.md), and which of the two later interfaces comes first is
decided below.

**Workstream II brings the material that is not conversation, and the surface that
suits it.** Documents are the one record kind nothing else reaches — the knowledge base
on corporate Drive and the spreadsheets beside it — and they are the heaviest item in
the plan: an adapter, a text-extraction component, and a chunked index where the unit
indexed today is a message. Request shape #2 is answered here, where both halves of
commercial memory exist, rates in documents and agreed rates in the CRM. The MCP server
belongs with them and reaches both layers — the corpus and the entities recognised from
it: over conversations alone it would duplicate what the bot already does, while over
documents, transcripts, a contact graph and the entities read out of all of them it
answers the aggregate questions a chat surface is worst at. It is read-only; writing
to anyone is workstream III.

**Workstream III also delivers the update track** — website, SMM and agents, including a
legal agent. That is a third kind of deliverable: it is neither material the assistant
reads nor a way to reach what it holds, so it appears in neither column of the table above
rather than in one that cannot describe it.

**Each source is ingested by exactly one workstream**, and no second workstream
ingests it as well. That is a rule about ownership, not about frequency. A source
with an adapter is read continuously, refreshed about daily. A source
belonging to a later workstream is not read at all. One source is neither: the
professional network is read from an archive a person requests, so it refreshes when
that archive is produced — the exception recorded against it below.

**What the CRM receives is the contact table the assistant maintains**, never raw channel
traffic. Contact data sits in several systems today, and an inventory of where it sits is
not a second ingestion path: each of those systems is read by the workstream that owns it,
or not at all.

**Telegram occupies three positions and they are not interchangeable**: a source in
0, the assistant's chat interface in 0, and the outbound channel to counterparties
in III.

## Platform

**CRM — HubSpot**, on its startup plan. ClickUp is not
a system of record in this design; it is material the CRM consolidates, and the scope table
above assigns it to workstream II. Telegram is the integration gap HubSpot cannot close by
itself, and a third-party bridge is one possibility. **That bridge is not built.** Telegram
is ingested by workstream 0 alone.

**HubSpot is workstream I's interface**, as the Telegram bot is workstream 0's.

HubSpot carries data both ways, as the bot does. That does not make either a source:
an interface is where a person works, a source is where material comes from. The bot
writes only into its own chat — sending to anyone else is workstream III.

The assistant builds and continually maintains a contact table, and that
table is what appears in HubSpot; contacts already there become entities the same way
a conversation does. What the team adds — deal state, owner, agreed rates — is not
derivable from message history, and comes back on the next pass. HubSpot is read like
every other connection: an initial backfill, then incremental passes on a cadence.
Where a person corrects a record the system wrote, which wins is a workstream I
question and is not settled here.

**Model access — an API credential of the project's own.** Programmatic access is what the
assistant needs; a subscription plan does not serve. The budget bounds the recency horizon
rather than the design, which is recorded under "Open questions" below.

## Scope decisions

Which workstream owns which source, how each source is read, which interface arrives when,
and what else a later increment carries.

| | Decision |
|---|---|
| **Every source enters through an adapter, with one named exception** | No source is imported by hand, and the Telegram Desktop export is ruled out on other grounds. The exception is the professional network, below: there the account risk outweighs what automating the refresh buys, and it is the only source read from a file a person produces. Naming it as an exception keeps the rule enforceable everywhere else; widening it quietly would not |
| **LinkedIn** | A first-class source, read from a **periodic manual export** — the named exception to the adapter rule. It holds the largest contact count in the company and carries the profile attributes the harder network queries depend on, and it is neither routed through HubSpot nor reduced to a HubSpot field. Automating it is refused rather than unavailable: the official routes are partner-gated, and driving a browser against an account of that size risks a ban that costs more than the automation saves. The trade is freshness — the contact graph is as current as the last export, roughly monthly, where every other source refreshes itself. It is read first within workstream I, because the contact network is the company's principal asset and a file parse is the cheapest reader in the plan. What that buys cheaply it pays for elsewhere: the largest contact store in the company arriving at once is the hardest test reconciliation gets, and it is the first source to carry an address — a signal the matcher weighs on judgement no sample has read, because nothing here produces one. So extending the matcher's own sample with pairs from it is part of that increment rather than an item in the risk register |
| **Google Drive** | A workstream II source, with Excel beside it. It is the heaviest single item in the plan and really three: an adapter, a text-extraction component for formats nobody has chosen, and a chunked index where the unit indexed today is a message. Documents are also the only record kind no earlier increment produces, so grouping the spreadsheets with them buys one extraction path rather than two. The knowledge base lives on Drive, so request shape #2 is answered in II — where the agreed rates it also needs have already come back from the CRM. Drive contributes documents and no other record kind, and it is the only path to document text — attachments inside conversations are captured as metadata |
| **WhatsApp** | A workstream III source. It arrives in the increment that also opens the outbound channel, so the platform's inbound and outbound roles land together |
| **Email** | A workstream I source. It is the same shape as Telegram — conversations, mostly — so it adds a second instance of a shape already covered rather than a new one. Until it arrives, request shape #1 reaches one channel of the two it is stated for, and inbox triage reads the message history rather than an inbox |
| **The assistant is more than one increment** | Workstream 0 is one source and one interface; what widens the assistant past that is split across I and II rather than landing at once. The split exists because a first increment that carries three source shapes proves none of them until all three work, and a demonstration needs one path working rather than three half-built |
| **Call recordings are a workstream I source, read through Plaud** | That tool's interface returns transcripts with speakers already separated — so ingesting recordings needs no speech model, only an adapter over an interface that already exists. Request shape #5 therefore ships as preparation-only until workstream I, when its debrief half becomes answerable |
| **HubSpot Contact is workstream I's interface** | What is wanted from it is under "Platform" above and in [`requirements.md`](requirements.md); what is decided here is when it arrives. It comes ahead of the MCP server because what it carries outward is the contact table the assistant already maintains, and because it is the heavier of the two — the only later interface carrying data both ways — which is why its increment takes the sources needing no new record kind. It also lands the question of whose value wins when a person corrects a record the system wrote in the increment straight after this build, which "Platform" above leaves unsettled |
| **An MCP server is workstream II's interface** | Read-only, over the corpus and the entities recognised from it. Over conversations alone it would duplicate the bot; over documents, transcripts, a contact graph and the entities read from all of them it answers the questions scoped to nobody in particular, which a chat surface renders worst — so it arrives with the documents rather than ahead of them. It writes nothing — reaching a counterparty is III |
| **Outbound messaging belongs to workstream III** | Drafting stays in 0: the assistant produces drafts and sends nothing. Reaching a counterparty is III, alongside the other agents that act |
| **Narrowing which named conversations are clients is a pass a deployment asks for** | A client's identity is the name its conversation carries, which admits every shared conversation somebody named — a two-person exchange is excluded, because what it displays is the counterparty rather than a name anybody chose, and that is the source's taxonomy rather than a reading of what a name means. Deciding which of the rest stands for a body of work does need the model, so it is a pass of its own rather than part of identity, with a pass identifier of its own: a mark is one row per pass and record, so sharing identity's identifier over conversations would have the two overwrite each other's and both re-derive the table for ever. What it buys depends on the source, which is why it is asked for rather than assumed — it pays on a taxonomy that mixes bodies of work with thematic tracks, and where every named conversation is already a body of work it rejects nothing. An unjudged conversation counts as a client, so nothing downstream waits for it and a deployment that leaves it off reads the corpus exactly as the rest of the build does. It is neither a source nor an interface, so it appears in neither column of the scope table |
| **The remote deploy path is workstream I** | A demonstration runs on the developer's machine, so no capability workstream 0 delivers needs a remote host reachable. The deferral costs the increment nothing, and the board nothing yet: workstream 0 runs on the dev stack, which is where its cards are finished, and the cards that wait on a remote host belong to the workstreams the remote path arrives with, as [`process.md`](process.md) records. It is neither a source nor an interface, so it appears in neither column of the scope table |

## Open questions

| Question | State |
|---|---|
| **Whether the chat surface offers correction in this build** | What wins is settled: a confirmation outranks re-recognition and is never overwritten. Whether the bot exposes the operation at all in this workstream, rather than corrections arriving with HubSpot, is not. The MCP server does not answer it either way: it reads only, and writes nothing |
| **Whether material dropped at the source stays retrievable** | Nothing is deleted, so it remains indexed. Drafting should not quote a withdrawn message while a question about history should still find it, which makes this a per-request retrieval decision and it has not been made |
| **What an address and an avatar are worth, and how often each shape occurs** | Settled for the signals this build carries: the denominator is measured from corpus frequency, the threshold is one decisive signal's worth of evidence, and the weights answer to the matcher's own sample of read pairs — a handle and a name agreeing reach the bar exactly, a handle alone does not, and a hermetic gate asserts it on every merge. Open is the rest. An address and an avatar hash are weighed on judgement no sample has read, because no source here produces either, so those two numbers stay unexercised until one does. And a sample of shapes cannot say how often each occurs, which needs a corpus holding duplicates rather than a different gate |
| **What refusing an answer that asserts nothing costs** | A shape whose answer is its cited findings refuses a reply carrying none, which is settled and is stated in [`architecture.md`](architecture.md) along with why the refusal is the direction to err in. What is unmeasured is the price: how often a reply that honestly had little to say is refused rather than hedged, against how often the refusal catches an unsupported statement about the corpus. Both are judgements about answers, so the evaluation set is the only thing that can weigh them, and the fixture case asking for an answer the material does not support is where it shows |
| **Commitment extraction precision** | Hedged language is where this kind of extraction fails, and business correspondence is full of it. Nothing has yet measured how often a commitment is invented or missed, and the central output of request shape #3 rests on it |
| **What a reworded recognition instruction costs** | A reading instruction is digested into the pass's watermark, so rewording one re-reads the whole corpus inside the horizon under the new wording. A stage that reads no record is bounded far more narrowly, against the rows it produced rather than the corpus — see [`architecture.md`](architecture.md). Either way it is the watermark working rather than a cost to avoid: an instruction that changed what is read without re-reading anything would leave the old readings standing and indistinguishable from current ones. It is also the largest single event the daily allowance has to bound, and costs more per record than a steady sweep because every window is read again rather than only the new ones — so two changes that both move it are much cheaper deployed together than one after the other |
| **The recency horizon** | Inference stops at fourteen days, which is bounded by the provisioned credit rather than by what would answer best: every day of it is more of the corpus carrying recognised entities, at a model call per window. It bounds how much work a sweep may find; what recognition may pay to read it is a separate bound on the calendar day, carried on input tokens and on output separately because the provider prices the two an order of magnitude apart. A day rather than a sweep, because a sweep drains until nothing is owed and the cadence alone says nothing about cost, and because a bound held in the store is one a restart cannot grant afresh. All three are settings, so changing one is a deploy rather than a rebuild — moving the horizon re-derives what falls inside it, while moving an allowance only changes what a day may pay — and each batch logs what it cost, which is how the numbers get raised against evidence. What is open is those numbers, and whether queries bound their own recency by the horizon — capture and indexing reach further back than either, deliberately, because both are cheap |
| **Document text extraction** | Drive is the only document path and request shape #2 depends on extracted text. Formats, scanned files and spreadsheets that are not prose are unaddressed, and no tooling is chosen |
| **Whether a second model provider is ever measured** | The provider is chosen in [`architecture.md`](architecture.md) and the design leans on it: request shape #4 is answered by that provider's own server-side web search, which is what keeps general outbound access closed. Swapping providers is a new `ModelProvider` implementation, but one whose model has no such tool would need shape #4 redesigned rather than re-wired. Nothing is benchmarked |
| **Multi-account rate limits** | Untested against several accounts at once |

## Risks

**Single-developer dependency.** The project rests on one developer with
no handover path and no second reviewer. Acceptable for a one-week prototype; not
acceptable for a system a company must own. Mitigation — documentation, a repository
and CI — is begun but not sufficient.

**What inference makes of the corpus is unmeasured.** Retrieval, grounding and the answers
themselves are scored against a pinned fixture corpus, so a regression in any of the three is
caught. How often a commitment is invented or missed is not: judging an extraction means reading
the message behind it, so the evaluation set is where it gets measured rather than argued, and it
sits in the open questions above.

**The merge decision is the part of the derived layer that is measured**, because it is the part
that makes no model call. The matcher carries a sample of pairs that were read and a gate that
asserts every one of them on every merge, hermetically and for nothing. What a sample of shapes
cannot give is a rate — how often each shape occurs needs a corpus that holds duplicates, and the
one this build runs on holds almost none.

**The gate binds by convention rather than by a rail.** The evaluation set cannot run in CI —
scoring retrieval needs real embeddings and a model credential, and CI holds neither — and
branch protection needs a paid plan the repository does not have. So a regression blocks a
merge because it is reported and acted on, which is a weaker guarantee than a rail and is the
one available.

**A derived layer makes errors durable.** Retrieval that surfaces a wrong figure fails
once and the session ends; an extraction that writes a wrong commitment recalls it on
every future answer. That is the defining risk of materialising anything, and the
mitigations are structural rather than hopeful: every derived fact carries the span that
evidences it, an inferred fact is phrased as a claim rather than as a fact, a person's
correction outranks the extractor, and re-derivation over a corrected corpus is cheap
because no source is re-read. None of that removes the risk; it makes it visible and
reversible.

**Source access carries account risk.** The professional network has no usable
official integration: connection and message access is restricted to approved partners
under a private programme. Writing our own automation would move accountability without
reducing exposure, and the account at stake holds the largest contact store in the
company, so the source is read from an archive a person requests instead.

That trades one risk for another rather than removing risk. No automation runs against
the account, so nothing there can be locked out mid-pass; but deciding the source is
first-class makes the remaining exposure load-bearing, because the largest contact
store in the company then depends on a route that can be withdrawn without notice and
refreshes only when someone produces the archive. The chat platform's user-level access
is the other account-risk surface, and the one that can be rate-limited or locked out
while running. It sits in the ingest container, which is why that container holds no
model credential and nothing the assistant needs to answer: losing it delays freshness
rather than stopping replies.

**Untested against the corpus it is for.** The prototype is developed against a personal
archive. Retrieval quality against a company's own network is unknown until those accounts
are connected.
