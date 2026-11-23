# Requirements

What the system must do, and what it is measured against.

## Problem

A company's working history is spread across Telegram, ClickUp, Excel, Google Drive,
email, LinkedIn and WhatsApp. None of it is queryable, and reconstructing the
state of a relationship means reading back through several systems by hand.

**Telegram carries the great majority of the correspondence**, email a small remainder,
and every other channel less again. That ranking is what makes one source enough to
prove the whole path, and it is a ranking of where conversation happens rather than of
where the network sits.

**Ranked by contacts rather than by conversation, the professional network leads** — it
holds the largest contact store here and grows monthly, where the work runs inbound
rather than as an exchange. So a channel ranking understates it, which is why it is a
first-class source rather than a HubSpot field.

Several Telegram accounts are in regular use. An existing third-party Telegram–LLM
bridge fails on Telegram's request limits;
that failure is the direct trigger for this project.

The company's principal asset is its contact network. The system exists to
make that asset queryable.

## System

A local-first assistant over the company's own data, addressed through a Telegram bot.

1. **Ingest — Telegram.** Every source enters through an adapter, with one named
   exception recorded in [`decisions.md`](decisions.md); nothing else is imported by
   hand. Telegram's adapter is a user-mode client over
   MTProto — not the Bot API, and not the desktop export, which omits a large share
   of the required material, something like half of it. It reads private chats, groups
   and channels
   across all connected accounts, **excluding the assistant's own chat and the
   project coordination group**, which are the system's own output rather than
   corpus. Ingestion is continuous: a backfill, then incremental passes
   about once a day. Telegram carries the great majority of the value, which is why
   one source is enough to prove the whole path. LinkedIn, Email and call recordings
   arrive with workstream I; Google Drive, Excel and ClickUp with II; WhatsApp with
   III — see the scope table in [`decisions.md`](decisions.md). What each of those
   defers is set out in "What each request needs that does not exist yet" below.
   They are trades made deliberately, not by omission.
2. **Store — a corpus, then what is made of it.** Capture and recognition are
   separate activities. Adapters yield **records** — conversations, the messages
   inside them, persons and documents — which are kept source-faithful and are never
   deleted. A separate pass over that corpus recognises **entities** — contacts,
   clients, commitments, events — and reconciles them automatically across every
   source. Because entities are derived rather than captured, a better extractor
   re-runs over material already held, with no request spent against a source. The
   model cannot query Telegram directly.
3. **Retrieve.** Tool calls against the corpus, the entities recognised from it, and an
   index over it — lexical and vector together — so answers are grounded in retrieved
   material rather than recalled. The index exists because the entity
   model is a schema and a schema cannot hold everything: a question that names no
   contact, thread or file still has to find its material. The index covers the corpus
   entire, and in this build the corpus is conversations and their messages — so the
   unit indexed is a message, and chunking arrives with the first record kind that
   needs splitting, which is the document — see "Data model".
4. **Interface — the Telegram bot.** A chat over the Bot API, and a different system
   from the Telegram source in item 1: one reads history as a user, the other
   converses as a bot. A web interface is refused: the reading happens in Telegram. It
   is the only interface
   this build delivers; HubSpot is workstream I's and the MCP server workstream II's —
   see item 5.
5. **The later interfaces — HubSpot, then an MCP server.** Contacts are the spine of
   the model, and HubSpot's contact records are what everything else there hangs off;
   it is where the team needs somewhere to work. Unlike the server below it carries data
   both ways: entities the assistant reconciles appear in it, and the deal state, owner
   and agreed rates the team maintains there come back. The local store holds
   authority. This is workstream I.

   The MCP server opens all three access paths of item 3 to a model client — the
   corpus, the entities recognised from it, and the index — so the same material is
   reachable from the other systems a model client is already connected to rather than
   only from the bot's chat. What it adds is not a path the bot lacks — the bot reads all three —
   but a surface that suits a different answer: a question scoped to nobody in
   particular returns a table to filter and re-ask against, which a chat renders
   badly. It reads and never writes, and it is workstream II's — arriving with the
   documents those aggregate questions read rather than ahead of them.

One position stands against the design and is worth stating: no heavy bespoke
backend — a chat as the interface and the minimum connective code to join things up. The
design in [`architecture.md`](architecture.md) is heavier than that. What makes it heavier
is the corpus, the two derived layers over it, and a module graph that enforces the layering
rather than describing it — each present because a specific failure was otherwise
unavoidable, and each recorded with that reason. The instinct survives where it belongs,
in what was refused: no agent framework, no dependency-injection framework, no
object-relational mapper, no managed vector database.

## How the system is used

Five recurring requests are the usage model.
**They are not a wishlist and not merely a test set.** The
assistant is a small number of request shapes that recur daily, addressed through the
bot, rather than an open-ended chat.

Four properties hold across all five:

- **Every answer is a draft for a person, never an action taken.** Nothing is sent,
  filed or committed on the assistant's own authority. A person approves anything
  client-facing before it goes out. Sending is
  workstream III, so the boundary is a scope line as well as a safety rule.
- **Several answers are explicitly multi-variant** — two or three drafts, two or
  three packages. The chat surface has to render alternatives as alternatives, not
  concatenate them into one wall of text.
- **The register is business correspondence**, in the language of the thread. A
  reply that reads as machine-written is a failed answer even if its content is right.
- **An answer the corpus cannot support says so**, and every claim about the corpus
  points at the material behind it. Where an answer depends on how current the corpus
  is, it states how current the corpus is. A plausible guess is the one failure mode
  this system cannot afford, because the whole point is that the answer is checkable.

### The five requests

| # | Request | Reads | Returns |
|---|---|---|---|
| **1** | **Draft a message ready to send** — reply to a client, partner or lead, in Telegram or email | The thread being answered, the full history with that contact, how comparable replies were written before | Two or three complete drafts, ready to send as-is. The person sends them — outbound is workstream III |
| **2** | **Offer and price for a specific lead** | What was offered to comparable accounts and at what price; the service catalogue; the lead's context | A recommendation plus two or three priced packages |
| **3** | **Where we left off and what's next** — a client, a project, or the inbox | Recent exchanges, commitments outstanding in both directions, upcoming events | Status, what is owed, and next actions in priority order |
| **4** | **Research with sources** — companies, leads, banks, providers, events in named markets | The open web, plus the network for who is already known | A list where every claim carries its source |
| **5** | **Call preparation and debrief** | Before: contact history and open threads. After: the recording of the call | Before: a script and questions. After: a digest and a task list |

Two further requests are the same shapes in daily form:
a prioritised task list with inbox triage each morning (a standing #3), and the
complete negotiation history for one contact on demand (a narrow #3).

### What each request needs that does not exist yet

Reading the five as a usage model rather than a feature list surfaces what this
build answers and what it does not:

- **#4 reads the world, not the corpus.** It is the only request that does so
  rather than the corpus, and "with sources" makes citation a requirement rather than
  a nicety. It is served by the provider's server-side web search on the model API,
  so no general outbound access is opened — see "Egress" in
  [`architecture.md`](architecture.md). It waits on no source, and is answerable in
  this build.
- **#5 ships as preparation only.** The preparation half reads contact history and
  open commitments, which this build has. The debrief half reads the recording of the
  call, and recordings arrive with workstream I, so it is answered there.
- **#2 needs commercial memory** — past offers, rates, packages. Little of that lives
  in message history; most is in documents: the knowledge base on corporate Drive,
  where rates and invoices sit. Drive arrives with
  workstream II, so #2 is answered there — the increment by which the agreed rates the
  team maintains in the CRM have come back as well, so the shape arrives whole rather
  than half-supported. It is the only shape that depends on document text being
  extracted rather than captured as metadata, and the only one of the five this build
  cannot attempt.
- **#1 reaches one channel, not two.** It is stated for Telegram *or* email, and email
  arrives with workstream I, so there is no thread to answer and no prior
  correspondence to learn register from on that side. The Telegram half is unaffected.
- **#3's inbox triage reads the message history, not an inbox.** The daily
  prioritised list and the status of a client or project are answerable from
  conversations; mail triage follows email into workstream I.

**#3 is the daily driver and the one the entity model most directly serves.** It is
also the only one of the five that is pure corpus retrieval end to end.

### Delivery order

**#3, then #1, then the preparation half of #5, with #4 alongside.** Those four are
answerable from conversation history and the open web, so all of them belong to this
build. #4 waits on nothing at all — the web search it needs is a tool declared on the
model call, not an egress change. #5's debrief follows the recordings, which arrive in
workstream I; #2 follows the knowledge base and the document extraction it needs, which
is workstream II, and so is the last of the five to be answerable.

## Data model

**Records are what a source said; entities are what was made of them.** Adapters yield
four record kinds — conversations, the messages inside them, persons and documents —
and those are kept as observed. Recognition reads them and produces business entities.

The distinction is not bookkeeping. A person record is one profile as a source
presented it; a contact is one human assembled from several. Keeping them apart is
what lets recognition be re-run without touching what was captured.

The entities the system is asked for are **contacts** and **tasks**, **clients**,
**events**, **deals** and a **knowledge base**.
They do not form one problem, because identity reaches them by different routes and
they fail in different ways:

| Entity | Identity comes from | Materialised | Serves |
|---|---|---|---|
| **Contact** | source identifiers — chat account, profile, address, phone | always, across the whole corpus | #1, #3, #4, #5, and the contact table HubSpot receives |
| **Client** | a conversation's own name, where the conversation is one people share — the topic taxonomy where a source carries one, one topic per major client | always; the label is read, not inferred. The model pass that narrows what it admits is **not in this build** | #2, #3, #4 |
| **Commitment** | nothing — read out of text | inside the recency horizon | #3's central output, #5 preparation |
| **Event** | weak — a date and its participants | inside the recency horizon | #3, #5 preparation |
| **Deal** | commercial memory is documents, and deal state belongs to HubSpot | **not in this build** | #2 is answered from the knowledge base, in workstream II |

The knowledge base is not an entity. It is document records from Drive, and it is
listed above only because it is asked for alongside the others.

**Client is the cheapest of these and the most reliable**, because its identity is a
label somebody already maintains by hand: the name a conversation carries. A taxonomy of
one topic per major client is one way that happens,
and a named group is the same kind of fact where
work is organised that way rather than as topics — both are a human's own statement of
what a stretch of conversation is about, which is why neither needs scoring, a merge link
or a threshold. Reading a label beats inferring one at any price; an extractor may propose
a mapping, but a label outranks it.

**A two-person exchange carries no such label.** What one displays is the other
participant, rendered by the platform rather than chosen by anybody, and it is already
that person's contact name — so it states nothing about what the conversation is for and
no client is read from one. Reading it would make a body of work of every person ever
written to, name each after a human, and leave a contact beneath itself, while the name a
reader searches by would mean two things at once. The exclusion is the source's own
taxonomy rather than a judgement about what a name means, which is the next paragraph's
subject and needs the model.

**A body of work discussed only in a two-person exchange therefore has no client**, and
one spread across several conversations is several — a client is keyed by the conversation
its name was read from, and nothing states that two of them are one thing. Both are the
cost of an identity that reads labels instead of inferring them, and both are bounded by
what a label can say.

**What the entity holds is a body of work**, which is an organisation where the taxonomy
names clients and a project where it names projects. The distinction does not reach the
identity rule — the label is read either way — but it does reach what an answer may claim,
because one body of work's agreed priorities are not another's.

**Not every named conversation stands for one**, and separating a client's thread from a
thematic track means knowing what the names mean. That is a judgement rather than a word
list, so it narrows what reading the label admits instead of replacing it, and it is a model
pass of its own — see [`architecture.md`](architecture.md#admission). A conversation the pass
has not judged counts as a client, so the read label stands without it. It pays on a taxonomy
that mixes the two, so a deployment asks for the pass
rather than getting it: where every named conversation is already a body of work it rejects
nothing and the tokens buy no change in output.

**Several conversations may name one body of work**, where one has a thread per strand of
the work rather than a single channel. They are separate clients, because identity here is
one label and joining two labels is a judgement about what they mean. Nothing is reconciled
into one client, and no entity joins them.

**What the reader needs of them is a merged scope and a merged section, never a merged
entity.** Asked about the work, they get every strand of it read: naming the work is naming
all of its threads, so a question about it is answered across them rather than about
whichever strand the reader happened to pick, and rather than by asking them to pick one.
Asked something broader, they get the strands under one heading rather than one heading
each. The scope is settled by a rule, because a scope is a set of threads to read and only
the labels say which they are; the heading stays the model's, from the labels it is shown,
because that is a choice between names a reader can see.

Both are derived from the labels for the length of a request — the strands share a leading
name, which is what makes either decidable without an entity — and both show one of those
labels rather than the shared name, because a reduced name is one no conversation holds.
Where two pieces of work are named alike the rule reads them as one, which is the cost of
settling it this way and is recorded in
[`architecture.md`](architecture.md#scope-resolution).

**Commitments are the opposite** — the highest value and the weakest evidence. A
commitment is a speech act, frequently hedged, sometimes retracted, often met in
silence. Direction is part of the thing: owed by us and owed to us are different rows,
and #3 asks for both. Each carries the counterparty, when it was stated, a normalised
due date where one can be parsed, and the message that reports the thing as done where the
corpus holds one. **What it does not carry is an open state, and that is a ceiling rather
than a gap**: a promise met in silence produces no closing message at all, so closure
evidenced can be told from closure not evidenced and neither of those is *still owed*. The
record therefore subtracts from a list of what is owed and never confirms what remains on
one. Because hedged language is where this goes wrong, a commitment is never shown as a
fact — it is shown with the message that evidences it.

**Statements carry validity, not entities.** A contact has no period of validity; *that
this contact works at that company* does. So attributes and relations are recorded as
statements with the interval over which they held, and separately when the system came
to believe them. A price quoted in March and revised in June is two statements with
adjacent windows rather than one value overwritten — which is what makes "what was true
when we last spoke" answerable at all.

**Every derived fact points at its evidence**, a span in the corpus. Because the corpus
is never deleted, such a pointer cannot dangle, so citation is structural here rather
than best effort. An entity's own origin is recorded too — read from a label, inferred
by a model, or confirmed by a person — because that decides how the assistant is
allowed to phrase it.

**Identity.** Every entity carries one internal identifier that never changes, and a
set of external identifiers placing it in the systems it came from. For a contact
that means its Telegram accounts, phone numbers, email addresses and professional
network profile, and more as sources are added. The internal identifier is what the
store, the retrieval path and the bot use; the external ones are how an incoming
record is matched to an entity already known.

**A record's identifiers are claims.** A profile advertising an address is evidence
for reconciliation, not a reconciliation — addresses and phone numbers are shared and
mistyped, so promoting a claim automatically fuses two people into one contact.
Records carry claims; only reconciliation turns a claim into identity.

**Nothing is deleted, and recency is read from source time.** Records and entities
carry when they were added, when they were last seen and when their content last
changed. A source dropping something is an observation, not an instruction, and an
archive cannot tell a deletion from a capture that came back incomplete. Queries
that exclude material as too old read the *source's* timestamp: a backfill captures a
five-year-old message today, so ingestion time would make old history look current.

**Reconciliation is automatic and scored.** Each pass matches what it finds against
entities already known, and a match is a sum of signals rather than a single test: a
shared phone number or address weighs heavily, a shared name barely at all until the
name is rare, and two moderate agreements together can carry a merge. Above a threshold
the merge happens without anyone being asked; below it, the candidate pair is kept with
its score and reconsidered when a new identifier appears. Merges are reversible,
because they are recorded as links rather than as rewrites, and internal identifiers
never change, so re-running does not churn identity.

Duplicate contacts are one of the error surfaces this system is measured against, and
so is the opposite failure: a shared
household address must not fuse two people into one contact. Both are requirements
rather than refinements, and the threshold that separates them can only be set by
sampling real pairs, which puts it in the evaluation set.

**What each request reads.** Materialised entities exist for questions that cannot be
scoped in advance; everything scoped reads the corpus directly.

| Request | Reads entities | Reads the corpus | Reads the index |
|---|---|---|---|
| #1 draft | the contact, to scope the history | **the prior messages themselves** — drafting needs real text, and a summary of it is worse | comparable past replies |
| #3 for one client | commitments, events | recent messages in the client's conversations | the turns around each undischarged promise |
| #3 inbox triage | commitments, events, contacts | — nothing scopes it, so no thread is read | the turns around each undischarged promise |
| #2 offer | client, contact | — | **the knowledge base** — nothing names which file holds a rate |
| #4 research | contact and client lookup | — | — plus the provider's web search |
| #5 preparation | contact, open commitments | recent messages | — |

Three access paths, and which one applies is decided by the question rather than by
preference: a question that names a contact, thread or topic reads the corpus directly;
one that aggregates across everything reads entities; one whose scope is unknown reads
the index. A request reading entities reads the index too, for one thing the entities
cannot carry: a commitment says what was undertaken and not whether anybody agreed to
it, so the turns around it are read to recover which it was. The third path is also the widest, because indexing runs locally and so
covers the whole corpus while entity inference stops at the recency horizon.

**The harder network queries** sit at the same end as inbox triage: who in the network is
a plausible buyer for a given product, who is known in a given profession and city, who
invests in a given sector, who to invite to a small private dinner, and **who has not been
contacted in a long time despite a strong relationship**. The last is dormant-tie detection
over relationship history — the most demanding requirement here and the one with the
clearest return — and it reduces to the latest message per contact. Aggregation, no
inference and no model call, which makes the most demanding requirement also one of the
cheapest.

The corpus's own structure is a Telegram supergroup organised by topic — one topic
per major client, pinned, alongside thematic tracks for referrals, partner
programmes, banks, HR, business development, resources, PR and SMM, events, and
daily plans and statuses. That taxonomy is the closest thing to a
statement of the company's real data model and is the starting point.

The knowledge base is corporate Google Drive, to be consolidated from
several mailboxes and drives. **Drive arrives with workstream II**, so the knowledge
base and request shape #2 are answered there rather than in this build.

Two things bound it when it does arrive. Drive is the only document path: attachments
travelling through the chat platform are recorded as media metadata rather than
extracted text, so file content inside conversations stays out of reach. And document
text has to be extracted from the formats it arrives in, which is a component the
conversation sources do not need. The limit is narrower than it sounds — only request
shape #2 reads documents at all. #4 reads the open web, #5's debrief reads audio, and
#1 and #3 are conversation history end to end.

## Constraints

| Constraint | Detail |
|---|---|
| **Runs on hardware the company controls** | A dedicated machine on the company's own premises rather than a rented server. The constraint binds wherever company material is held, so it binds the deployment rather than this build: workstream 0 runs on the developer's own machine, which the topology in [`architecture.md`](architecture.md) records, against the separate corpus "Demo data" below describes. Telegram is assumed to treat a user session logging in from a datacentre IP as suspicious. The Bot API carries no such assumption |
| **Data does not leave** | Nothing that holds company data runs off company hardware. A web interface is refused because the reading happens in Telegram, and a rented server because a datacentre IP is assumed to look suspicious — this constraint is why neither is revisited |
| **Survives power loss** | The premises lose power and connectivity; the machine must restore itself unattended |
| **Multilingual** | Georgian, Russian and English. What binds is retrieval, and that follows from the corpus, which is observable rather than agreed: Georgian and Russian material, against a knowledge base that is partly English when it arrives |
| **The assistant answers in Russian** | Russian is the default and the floor: every message a rendering interface can emit resolves in it, and a further language may be partial and falls back. This is the team's reading language, which is a separate question from the corpus — see Localisation in [`architecture.md`](architecture.md) |
| **Freshness — daily** | Ingestion refreshes about once a day. Near-real-time is not a goal and nothing is built for it |
| **Programmatic model access** | The assistant reaches a model over an API on a credential of its own. Which container holds that credential, and why capture never sees it, are in [`architecture.md`](architecture.md) with the provider and model; the budget and why a subscription does not serve are in [`decisions.md`](decisions.md) |

On the Telegram user session's limits: read limits are generous,
write limits are the binding ones, and session keys persist across re-login.
Multi-account behaviour is untested.

## Demo data

The prototype is developed against the **author's own Telegram archive**, which is held
outside this repository. Its contents, extent and location are not recorded here.

One personal source, because this build reads one source. It carries the record shape
the build is about — conversations — and exercises capture, recognition, indexing,
retrieval and answering end to end.

**Consequence.** The demo establishes that the mechanism works. It does not
establish retrieval quality against a company's own network, which remains untested
until those accounts are connected.

## Accounts

A source is read across several accounts rather than one, which is why an account is a
unit of configuration rather than a source in its own right. Which accounts a deployment
connects, and in what order, is a deployment decision and is not recorded here; this
repository carries no handles.

## Not in this build

**Deferred.** Workstream I — LinkedIn, email and call recordings via
Plaud, and the HubSpot interface. Workstream II — Google Drive, Excel and ClickUp, and
the MCP server. Workstream III — WhatsApp, outbound messaging, and the update track of
website, SMM and agents. The record kinds and the entity model stay source-agnostic so
each remains cheap to add, but nothing is built for them now.
