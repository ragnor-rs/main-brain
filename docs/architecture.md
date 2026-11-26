# Architecture

The shape of the system and the technology it is built from. Scope is workstream 0
(see [`../README.md`](../README.md)); the requirements it serves are in
[`requirements.md`](requirements.md).

The build is scaffolded — modules, images, Compose, CI and the gates — and no feature
code is written against it yet.

The system is shaped by how it is used: five recurring request shapes addressed
through the Telegram bot, not open-ended conversation. See "How the system is used"
in [`requirements.md`](requirements.md) — that section, not this one, is where the
design pressure comes from.

## Principles

**A principle states an invariant, and points at the section specifying it where there is
one.** The sections below carry the reasoning, the detail and the exceptions, so a
principle that would restate them at greater length gives way and points instead. Where a
principle is the only home of what it states, it keeps it. The same holds for "Seams that
must not be bypassed": it indexes rules specified elsewhere without becoming a second
statement of them.

0. **Five shapes, not a general assistant.** Each request shape is a named pipeline
   with its own retrieval, its own prompt and its own evaluation cases. A new shape is
   a new stage list, not a cleverer prompt. Free-form questions are answerable, but
   they are not what the system is tuned or measured on.
1. **Local-first.** Everything that holds company data runs on hardware the company
   controls. No hosted backend, no managed vector database.
2. **Thin frontend, thick backend.** The chat frontend normalises platform events
   and renders outcomes. It knows nothing about the model, retrieval, or storage.
3. **Ports and adapters, in two families.** Sources and interfaces are different
   contracts. A **source port** yields raw records inbound and never handles a
   business entity; an **interface port** carries what the system holds outward and
   never contributes to the corpus. Direction is what separates them, not who is at the
   other end and not which layer the material comes from: an interface may be read-only,
   may serve records as readily as entities, and may be addressed by a program rather
   than a person. It is an interface because nothing passing through it becomes corpus. Every other outward dependency —
   the model, embeddings, the database — sits behind an interface owned by the domain
   with the implementation in an outer layer. Telegram implements a source port and
   is separately an interface: two contracts, not one component in two moods. Its
   third position, outbound to counterparties, belongs to workstream III.
4. **Source-agnostic core.** Every source yields the same four record kinds:
   conversations, the messages inside them, persons and documents. Adding a source
   must not change the store, the retrieval path or the chat, and a source that
   emits one kind only is ordinary rather than deficient.
5. **Every source enters through an adapter, with one named exception.** What this
   forbids is the hand-made import — files dropped into a directory, a corpus that
   refreshes only when someone remembers to refresh it. It does not require an
   official API. The exception is the professional network;
   [`decisions.md`](decisions.md) records it with its reason, its cost, and why naming
   it is what keeps the rule binding everywhere else.
6. **One codebase.** Ingestion is not a separate service with its own language and
   its own copy of the domain. It shares the codebase, the entities and the ports
   with everything else, so there is no schema contract to keep in step across
   runtimes.
7. **Deterministic where possible.** The model is used for judgement, not for work
   that code can do exactly.
8. **Nothing is deleted.** A source dropping something is an observation about the
   source, not an instruction to destroy what was captured. Retrieval decides what is
   too old to read; storage never does.

## The corpus and its two consumers

```
source ports ──▶ CORPUS ───┬──▶ INDEX                  vector over corpus
                 records,  │    (the whole corpus)     spans — it has no schema, so
                 source-   │                           nothing can be missing from it
                 faithful  │
                           └──▶ ENTITIES + STATEMENTS  contacts, clients, commitments,
                                (identity everywhere;  events; this is what the
                                 inference inside the  interface ports exchange
                                 horizon)
```

Capture is one activity and deriving is another. The corpus is what a source said;
everything below it is what was made of that, and both consumers read the same
material independently.

**They share one derivation mechanism**, which is why there is one set of rules rather
than two:

- one mark per pass and record, carrying the version that derived it, so changing a
  prompt, a chunker or an embedding model invalidates exactly what it affects
- invalidation when a record's content changes
- a pointer back at the corpus span, so citation is structural in both
- re-derivation that spends no request against a source

**They differ in economics, and that is what sets their coverage.** Indexing is local
compute; recognition spends the model budget. So the index runs over everything back to
the first backfilled message, while inference is bounded by the recency horizon. The
index is therefore wider than the entity layer in two dimensions at once — in schema,
because it has no fields to lack, and in time. An unscoped question can surface a
2019 exchange from which no commitment was ever extracted, and if recognition is behind
or wrong, unscoped retrieval still works.

Two further consequences, neither optional. **The model credential belongs to
recognition, not to capture** — a source adapter needs no model access. And **there are
two re-run paths**: re-deriving from the corpus is cheap in requests and wanted often,
while re-reading a source is rate-limited and wanted rarely. Conflating them is how an
improved prompt turns into re-downloading years of history.

Every pass is specified below, and all of them run **in the backend container** as scheduled
passes, each taking a Postgres advisory lock of its own first so it cannot double-run. Their cost is
model latency rather than local CPU, they are resumable from their watermarks, and the
backend already holds the model credential and reaches both the model API and the
embeddings sidecar — so this adds no container and no credential surface. Should a sweep
start competing with the answer path, promoting it is a Compose service and an
environment variable over the same modules, not a redesign. Separation from capture is
the part that is structural.

**One driver, a cadence per pass.** The passes have the same shape — do a batch, say how far
behind you are — so one loop runs them all, and a sweep drains batches until nothing is owed
rather than doing one per tick. What differs is their economics, which is a cadence and
not a mechanism: indexing sweeps every minute, because its cost is local compute and the
interval decides how soon a captured message becomes retrievable, while recognition
sweeps hourly because inference spends the model budget per window. Both are configured,
and so is the horizon, which is a duration recomputed per sweep — the passes hold no
clock, so two runs of one cannot select different work for reasons nothing recorded.

**What recognition may spend is bounded per day, because draining means the cadence does
not bound it.** The interval says when a sweep starts and nothing about what it costs, so
one run over a real backlog can spend the whole provisioned credit and leave the answer
path failing on an empty balance — and the answer path is what a person waits on. What
bounds it is an accounting of what recognition actually spent, kept in the store and
totalled per calendar day, with a separate allowance for input tokens and for output. A day
that exhausts either stops the stage that spends and says so; the stages that spend nothing
keep running, because indexing embeds against the sidecar and identity matches in the store,
so neither draws on the model credit and neither should be held back by a day that has. The
backend owns the clock, so which day a figure lands in is decided there and the passes hold
none. What a sweep did not reach is left owed and unmarked, so the next one resumes from the
same watermark.

**Two axes rather than one, because the provider prices them an order of magnitude apart.**
Output costs five times input, a cached read costs a tenth of it, and writing an entry that
lasts an hour costs twice it — so a single allowance over their sum would be set by whichever
axis moved last and would misstate both. All four counters the provider reports are recorded;
the allowances sit on input and output, the two billed at full rate.

**The day is read from the store, so a restart grants no fresh allowance.** What a day has
spent is the sum of what its collected submissions were billed, which is already kept per
submission — so the figure outlives the process that produced it, a container started after
an allowance is gone makes no model call at all, and the day can be read without that
container. That is what makes an exhausted day a pause with a date on it rather than a
question that fails on an empty balance. The answer path is unaffected: it is bounded per
request, consults no day, and answers on one whose allowance is spent.

**A failed batch is charged to the day too, so far as a failure can say what it cost.** A
batch is charged from what it returns and a failing one returns nothing, so the two model
failures carry what the provider had already billed for the attempt, and that figure is
written against the day rather than held over for a later sweep. An attempt that failed
before it became a submission has no submission to be billed against, so its charge is
recorded beside them and totalled with them. The figure is said each time a pass reports a
failure. What stays outside it is a cost the provider never reported, which nobody can see.

**A stop for an exhausted day is reported distinctly from a sweep that caught up and from
one that found nothing owed.** All three leave the loop idle and only one of them means work
is still owed; reported alike, a paused backfill would be indistinguishable from a drained
corpus. The report names when the allowance refreshes, because a pause whose end nobody can
state reads as a stall.

**Lag is what makes a stopped pass legible.** How far behind each pass is, is counted in
full rather than probed up to a page: a bounded read answers whether there is work and
never how much, and the number is what separates a pass that has stopped from a corpus
with nothing new in it — both look like no new spans appearing, and only one has a count
that climbs. The driver reads it twice over, because a fall in it is also the only honest
signal that a batch made progress. A batch's own return value is not: indexing writes no
spans for a stretch of stickers it nonetheless settled, and inference settles nothing for
a window the model never finished reading — which stays owed, so a loop trusting the
return value would re-read and re-pay for that window for as long as the sweep lasted.

A pass that cannot run leaves the other running, and what each absence costs is a pass
rather than the process: without the sidecar nothing is indexed, without the model
credential nothing is inferred, and identity needs neither. That is what lets the stack
come up credential-free, which is how the store and ingestion are exercised.

## Topology

Five containers under Docker Compose, on one machine. The office machine is what the
system targets; workstream 0 runs and is demonstrated on the developer's own, a deferral
recorded in [`decisions.md`](decisions.md). Nothing in the topology differs between them.

```
┌───────────────────────────── docker compose (one host) ───────────────────────┐
│                                                                               │
│  SOURCES — Telegram MTProto user session                                      │
│         ▲  ingest connects out; history flows back                            │
│         │                            ┌──────────────────────────────┐         │
│  ┌──────────────┐                    │                              │         │
│  │ ingest       │──   records   ────▶│        postgres              │         │
│  │ (Kotlin)     │                    │        + pgvector            │         │
│  │ source       │                    │                              │         │
│  │ adapters     │                    └───────────┬──────────────────┘         │
│  └──────────────┘                                │ JDBC                       │
│                                                  │                            │
│                                      ┌───────────▼──────────────────┐         │
│  ┌──────────────┐ HTTP (shared       │  backend (Kotlin / Ktor)     │         │
│  │ bot (Kotlin) │────  secret)  ────▶│  retrieval, pipelines,       │         │
│  │  FRONTEND    │◀───  outcomes  ────│  model client, answer path   │         │
│  └──────────────┘                    └───────────┬──────────────────┘         │
│         │                                        │ HTTP /embed                │
│         ▼  the bot connects out (long polling)   │                            │
│  INTERFACE — Telegram Bot API        ┌───────────▼──────────────────┐         │
│                                      │ embeddings (Python)          │         │
│                                      │ FastAPI + sentence-transf.   │         │
│                                      └──────────────────────────────┘         │
│                                                                               │
│  ingest  ───▶ its sources only    (no model access, no embeddings call)       │
│  backend ───▶ api.anthropic.com   (model + server-side web search)            │
│                                                                               │
│  No edge joins bot and ingest, in either direction.                           │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

| Container | Responsibility |
|---|---|
| **ingest** | One adapter per source, each yielding records and nothing else. Telegram's adapter speaks MTProto as a *user*, not a bot. Every adapter owns its own backfill plan, its incremental position and its rate-limit posture. Holds every source credential and no model credential. |
| **backend** | Everything heavy: retrieval, the answer pipelines, the model client, persistence, and the derived-layer passes — indexing, recognition and admission — as advisory-locked scheduled jobs. No Telegram types cross into it, and no source credential either. |
| **bot** | The chat frontend. Normalises Telegram updates into commands and messages, calls the backend, renders returned outcomes. Enforces the allowlist at the edge, and answers there what reaches no backend: the command list, and a command it does not offer. Renders **multi-variant answers as selectable alternatives** — two or three drafts or packages are a first-class outcome shape, not one long message — and **never sends anything onward**: what the drafting command produces is a reply the person sends themselves, and every other command answers to the reader alone. Outbound messaging to counterparties is workstream III. |
| **embeddings** | A sidecar wrapping the embedding model, called from the derived layer to embed corpus spans and from the answer path to embed a query. Anthropic has no embeddings endpoint, which is why this is a separate process rather than another call to the model provider. |
| **postgres** | `pgvector`. Both the relational store and the vector store; reached only by the backend and the ingest worker. |

**Why ingest is separate from backend.** Two components here can be rate-limited,
locked out, or left needing a human: the Telegram user session, and the
professional-network source whose archive a person has to request. Isolating them means a wedged session cannot take down
the assistant, which answers from the store while ingestion is behind. It is a process
boundary, not a codebase boundary — both sides are the same modules. Holding both
account-risk surfaces in one container concentrates the risk deliberately: it is the
container with no model credential and nothing to answer with, so the blast radius of
losing it is delayed freshness rather than a system that cannot reply.

**Two Telegram roles, two entry points.** Ingestion signs in as a *user* over
MTProto and reads history; the frontend is a *bot* over the Bot API and converses.
Different protocol, different credential, different rate budget, and — for the
outbound role — a different workstream. The diagram draws them as separate entry
points for that reason.

Two rules keep them apart, and they are not the same rule.

- **No edge joins the bot and the ingest worker**, in either direction. Ingestion
  state can still reach the chat: it is read from the store by the backend and
  returned like any other answer. What is forbidden is the direct call.
- **Ingestion is not operable from the chat.** Triggering a backfill, reaching a
  source or re-authenticating a session belongs to the host, by any route — routing
  the command through the backend would not rescue it.

Re-authentication is the one thing the user session needs a human for, and it is done
on the machine itself, out of band.

## Source ingestion

Ingestion is not an import that happens once. Every adapter backfills, then keeps
running. A daily refresh is what the system is specified for, and it applies to every
adapter. The one source read
from a requested archive rather than by an adapter refreshes when that archive is
produced, which is the cost recorded against it in [`decisions.md`](decisions.md) —
a third cadence, neither daily nor never.

**One deployment may be switched off, and it is operational rather than a cadence.**
A switch brings the worker up reading nothing; unset, it reads as it always does,
which is what the daily refresh above means. What makes it worth having is the
session: the client library locks the session directory it is given, so every
deployment pointed at one directory competes for one session, and a second stack can
only come up if it leaves that session to whichever stack is reading. The switch is
read before the session is waited for or opened, which is the whole of why it sits
where it does — one read later would take the lock before giving it up. Pointed at
directories of their own, two stacks contend for nothing and each wants a sign-in
instead, which is a budget rather than a lock.

A switched-off worker stays up and reports in both channels, log and health row, that
it is reading nothing. Staying up is not politeness: the service is restarted unless
stopped, so one that exited on a deliberate switch would sit in a restart loop, and
the check that every service came up fails on any state but running. Reporting it is
what keeps a reader of the store from seeing the last run's row, which says nothing
is wrong where it means nothing is being read. One thing it cannot report is that
freshness has stopped advancing: the source time believed complete stays true and
stops being the whole truth, because nothing a source reports can say *and no more is
coming*.

### The port

One adapter per source, satisfying two ports, because two different callers need two
different conversations:

```kotlin
interface SourceStream {                   // drained by the scheduler
    val source: Source
    suspend fun next(): Batch?             // null — nothing available right now
}

interface SourceReread {                   // triggered by the host
    suspend fun reread(unit: WorkUnitId?)  // null — the whole source
}
```

The scheduler depends on `SourceStream` alone and has no way to trigger a re-read; a
port carrying a method only one of its callers uses is two ports wearing one name.

**Named for reading, because that is what it does.** The operation adds: records upsert
onto what is there and nothing is removed. A name suggesting it forgets or discards is a
name nobody reaches for to repair a hole, which is the one thing it is for.

**A re-read takes a unit the adapter itself reported**, never a scope invented for all
sources. A conversation means nothing to a document-only source, so a shared scope
vocabulary would force implementations to reject arguments they cannot honour. Adapters
already publish their backfill work items, and a re-read names one of those or nothing.

The scheduler decides *when* to ask; the adapter decides what is safe to answer.
Pacing cannot belong to the adapter alone in a system whose reason for existing is
that the previous tool died on request limits — but it cannot belong anywhere that
would have to understand the difference between a flood-wait with a stated duration,
an HTTP 429, and a change feed's expired page token.

### What a batch carries

A batch is a bundle of record updates, and messages are never free-floating:

```
Batch {
  conversations: [ Conversation { …, messages: [ Message { … } ] } ],
  persons:       [ Person   { … } ],
  documents:     [ Document { …, message_ref? } ],
  withdrawn:     [ record_id ]
}
```

Nesting messages inside their conversation makes a message without its conversation
unrepresentable, rather than an ordering mistake the write path has to avoid. Persons
stay top-level because a person spans conversations and one source yields profiles
with no conversation anywhere near them; documents stay top-level because a
document-only source has no conversations at all, while an attachment carries a
reference to its message, resolved inside the same batch.

**`withdrawn` carries identifiers where the others carry records**, because that is all
a source has left to give: a platform reporting a removal names what it removed and
offers no content. It is not a record kind for that reason — a message shape would need
a source time and a body the adapter cannot know without reading back what it wrote, and
an adapter never reads the corpus.

A conversation carries its identity, kind, name and parent. The parent is not
decoration: a topic sits inside a supergroup, and the client's own structure is one
topic per major client alongside thematic tracks (51:28–53:30) — the closest thing
to a statement of the real data model. That taxonomy lives in topic *names*, and a
name that never enters the corpus cannot be recovered by re-deriving, only by
re-reading the source.

**A batch carrying a conversation's messages carries that conversation's header**,
whether the conversation is new or years old, so a message without its header is
unrepresentable rather than an ordering mistake.

**What an adapter may not do is decide from what it believes was persisted** — that is
wrong after a restore, wrong after a re-run, and untestable without a populated
database. What it may do is notice that *the source itself* now describes a
conversation differently, which is new information rather than a belief about the
store, and is the only route a rename has to the corpus once a conversation's history
is finished and its chat has gone quiet. Those names are the client's own taxonomy, so
losing them loses the one thing re-deriving cannot recover.

A restore is therefore recovered by a restart: every adapter asserts each header it can
see on its first pass, which is a property of the process rather than a model of the
store.

### Record identity

Every record of every kind carries:

- **An external identifier, source-qualified** — `(source, native id)`. Not
  account-qualified: one message fetched by five sessions has one native identifier,
  and putting the account into the key multiplies the corpus and over-counts every
  history. Which session observed a record is provenance, and there may be several.
- **Supplemental identifiers, typed and normalised** — handles, phone numbers in
  E.164, lowercased addresses, profile locations, content hashes. Untyped strings
  make matching both over- and under-shoot.

Two rules stop this becoming identity by accident. **A supplemental identifier names
the same thing, never a related thing** — a mail header pointing at another message
is a relation and does not belong here. And **a record's identifiers are claims, not
identity**: a profile advertising an address is evidence for reconciliation, not a
reconciliation. Only `Reconciler` promotes a claim. An adapter that mints identity
fuses two people the first time a shared address appears, and duplicate or fused
contacts are an error surface the client named.

The store keeps a surrogate key with a uniqueness constraint on the external
identifier, because sources reissue and migrate their own — a group promoted to a
supergroup arrives with a new identifier for a conversation that already exists.

Supplemental identifiers are constrained per record and never by value. The same claim
twice on one record is what re-delivery must not duplicate; the same value on two
records is evidence they are one thing, which is the strongest signal reconciliation
has rather than an error to refuse at write time. Each claim carries the observation
pair and nothing else: it is not an event, so it has no source time, and its value is
part of its key, so a changed value is a different claim. A claim is never withdrawn —
its last-seen standing still while its record's advances is what separates a current
number from one abandoned years ago.

### The account a source was read through

A person record carries **whether it is the account the source was read through** — whether
it is one of ours, which is what the record calls *self*. Nothing else in the corpus marks
it, and without it the account whose archive this is — more than half the messages, present
in every conversation — is held exactly like a counterparty who sent one message.

It comes from the source and is never configured by hand: an adapter asks the session it
holds which account it authenticated as, and compares that identifier against the profiles
it emits. **A source whose adapter cannot report its own account leaves the mark unset
rather than guessing**, which is a third state and not the answer *no*; nothing infers it
from how much anybody said.

**It is not a supplemental identifier**, and that is the whole of why it sits on the record
rather than among its claims. A claim shared by two records is evidence that they are one
thing, weighted by how few records carry the value — so a claim carried by exactly the
accounts read through would be the rarest signal there is, merging them at a weight nobody
chose. Nothing merges two accounts because both are the archive's own.

**A human is read through where any of their accounts is.** The question is asked of a
cluster rather than of a row, which is what lets several accounts be one owner without a
second concept, and what makes merging two of them produce one owner rather than two.

Two things read it, for opposite purposes. Scope resolution keeps the reader out of the
subjects a question could be about; retrieval marks the reader's own turns in the material,
so that a shape whose answer is addressed to one of the parties knows which of them that is —
the shape composing a message, to write from the right side of the thread, and the shape
preparing for a call, to keep its questions to what the reader is not already holding. Both
ask the cluster. **Direction is not derived from it**: which
party a claim obliges is in the claim's prose, and the author of a message is not that
party.

### Nothing is deleted, and there are three time axes

Sources report removals differently or not at all: an archive cannot report one at
all, because absence is indistinguishable from a capture that came back incomplete.
So a removal is never an instruction.

| Axis | Meaning |
|---|---|
| **Source time** | when the source says it happened — a message's own timestamp |
| **Observation time** | added when first captured; last seen on every pass that still finds it |
| **Content time** | updated only when content actually changed |

Content time is compared before writing, never stamped on every touch: a full resync
that re-observes unchanged material must not make the whole corpus look freshly
modified, or every recency filter built on it is wrong.

**Recency filters read source time.** A backfill captures a five-year-old message
today, so filtering on observation time makes old history look current — which is the
mistake this table exists to prevent.

Last-seen is the honest weak form of removal: material unseen for several passes is a
signal, never a fact.

**A reported removal is a fact, and it is not an axis.** A source that names what it
removed has made a statement about a record it still knows, and the record keeps
everything — its body, its author, its claims and all three times. What is added is
the moment the source said so, in a column of its own. Last-seen is deliberately not
that column: it means a pass still *found* the record, so moving it on a removal would
make the one message explicitly withdrawn look like the freshest in the corpus, and
leave the weak signal above firing for everything except the case where removal is
certain. The first statement is the one kept, because a re-delivered batch repeats it
and a fact that moved on every repeat would be a clock.

### The adapter owns its position

**The port does not expose it.** For Telegram the position is the protocol's — an
account-wide sequence plus one per channel, held by the client library in its own
store. A second copy kept by the scheduler would be two authorities on one fact, free
to disagree after a crash. The other sources hand back opaque tokens that are equally
nobody else's business.

So a rejected position — a difference too long to serve, an expired sync token, a
page token gone stale — is an internal matter the adapter resolves by resyncing
itself, not a failure surfaced through the port.

This is safe because every write is an upsert and nothing is ever deleted, so
delivery need only be at-least-once: commit the batch, then advance the position. A
crash in between re-delivers, and re-delivery changes nothing. Exactly-once is
therefore refused rather than unavailable — the transactional coupling it would need
buys a guarantee the storage model already provides.

**Asking again is the acknowledgement**, because the port carries no other. So an
adapter advances when it is next asked rather than when it hands a batch over, and the
scheduler holds a batch until the corpus has it — retrying that one rather than asking
for a second. Asking after a failed write would acknowledge material that was never
stored, which is the one way this loop could lose something.

### The adapter owns its backfill plan

Backfill is not a phase that finishes before incremental sync begins. The assistant
needs recent material on the first day, so an adapter stays current on its change
feed and spends the request budget left over walking history backwards. Only the
adapter can make that trade, because only it knows what its own limits cost.

- The plan is **a work item per conversation**, not a single watermark: how far back
  this one reaches, how far forward its records are complete, and whether it is
  finished. Both edges, because a finished unit can still fall behind at the front
  while nothing is owed at the back.
- **Backfill depth is configuration, not adapter policy** — and it is not the recency
  horizon. Capture reaches as far back as the archive allows, because indexing what it
  returns is local compute; the horizon bounds only what inference spends the model
  budget on.
- **The work list is open**, not a queue that drains. New conversations appear, so an
  adapter re-enumerates; "backfill complete" means complete as of the last
  enumeration.
- **A work item that fails every pass is marked and skipped**, not retried forever.
  One unreachable conversation must not stall the plan.
- **Plan state lives in Postgres through a repository**, so it shares one restore
  point with the corpus it describes. State held inside a client library is the
  exception, and it is why a partial restore can leave a gap nothing detects.

### What an adapter reports

Hiding the position costs no visibility, because the position was never the readable
thing — a protocol sequence number answers none of these questions.

| Reported | Why it is needed |
|---|---|
| **Freshness** — the source time up to which this source is believed complete, plus any conversation known to be lagging | An answer about what happens next is confidently wrong if the corpus stops two days ago. The assistant may answer while ingestion is behind; it may not imply a currency it lacks |
| **Health** — last successful pass, last failure and its kind, and whether the adapter is blocked on something only a person can clear | Re-authentication is cleared by a human on the machine, and nobody clears it without being told |
| **Backfill completion** — finished or not, and the source time reached | Until it finishes, the corpus is partial in an unknown way, which makes any measurement of retrieval quality meaningless |

Progress as a fraction exists only for backfill. Neither a difference feed nor a
change feed reports how many events remain, so incremental sync has no denominator
and "how far behind" is not a number.

This is per-source operational state — neither corpus nor entity — written by the
adapter and read by the backend when it answers, which is how ingestion state reaches
the chat through the store rather than by a direct call.

### Re-reading a source

Once the position is private, an adapter is the only thing that knows whether it is
caught up, and that belief has to stay revisable. Nothing below is detectable from
inside the adapter: a field it never captured and now needs, a synthesised position
that skipped a page an interrupted pass skipped, a conversation excluded yesterday and
wanted today.

- **Scoped to a unit the adapter reported** — one backfill work item, or the whole
  source. One missed conversation must not cost a full sweep, and no caller has to know
  what units a given source even has.
- **Not destructive.** Re-read records upsert onto what is there, and content time
  stays put where nothing changed. The only cost is request budget, which is the
  expensive one.
- **Never automatic.** A restart resumes and never re-backfills, or a crash loop
  becomes a re-backfill loop walking into the limits this system exists to stay
  under. The catch-up below is bounded by what is stored and is not this.
- **The host's, by one route** — a positional argument on the ingest image, beside the
  one that signs in. Reaching a source is not operable from the chat, and routing the
  request through the backend would not rescue it.

### Catching up is not re-reading

A re-read walks a unit's history again from its newest message, regardless of what the
corpus holds. A catch-up reads only what is newer than what the corpus holds. The
second is the one that happens by itself, on every start, because the reasoning that
forbids an automatic re-read does not reach it: there is nothing for a crash loop to
turn into a re-backfill loop when a pass with no gap in front of it reads nothing at
all.

This is what closes an outage. A unit the plan calls finished is never walked again, so
once backfill completes, the only intake is the change feed consumed while the process
is listening — and whatever arrived while it was not is absent, in a corpus
indistinguishable from a conversation that went quiet. A client library replays what it
can on reconnection and a long enough outage exceeds that, which is undetectable from
inside the library and is why this is the adapter's question rather than its store's.

- **Detected per unit, from what the source already offers.** An enumeration names each
  conversation's newest message, so the comparison costs no request of its own: against
  it stands the source time the plan records that unit's records as complete to. A unit
  whose front edge lags has a gap; a unit whose does not is left alone.
- **Bounded by what is stored**, never by a count of passes. A catch-up walks back from
  the newest message only as far as the front edge it is closing, or the configured
  depth, whichever it meets first. The depth bound applies to both walks for one reason:
  how far back capture reaches is configuration, and a front edge can sit below the
  floor — a conversation captured before the bound was set, or dormant since before it.
- **Only a unit the backfill will not cover** — one it has a position in, or one it has
  finished. In the first it resumes below the ground already taken and carries on away
  from the present; in the second it is never offered the unit again. A unit both
  unfinished and unwalked is the opposite case: the walk takes it from the newest
  message, so it covers the front itself and records the edge from that page, and a
  catch-up beside it would pay twice for the same pages.
- **A corpus complete to no time is not behind.** That is what a cold start looks like,
  and it is why one begins no catch-up: there is no edge to read back to, and a walk
  without one is a re-backfill. A unit in that state which the source has material for
  is reported, because it is a conversation nothing here can measure.
- **The edge only ever moves behind material that was stored**, and a page the corpus
  accepted is what licenses the move. Two pages can: one taken at the newest message,
  which sets the edge to its own newest, since that is the only position with nothing
  above it; and the page that closes a gap, which sets it to what the source offered at
  detection, that being the point the descent started from. A page nothing accepted
  moves neither. What cannot license it is an enumeration on its own — the source naming
  a newest message says nothing about whether the corpus holds it, and a feed drained to
  empty says only that nothing is queued, not that history is complete. A plan ahead of
  the records it claims is the one error here with no recovery, since nothing looks at a
  conversation's front twice.
- **A shared second is read as a gap, not as level.** Source times are whole seconds and
  several messages of one conversation routinely share one, so an edge standing at the
  second the source now offers cannot be trusted to mean nothing arrived: a message from
  that second which never reached the corpus would be invisible for ever. The comparison
  is therefore not strict, which costs a page that re-reads the second and upserts what
  is already held. It cannot be made exact instead — the chat list offers a time and no
  identifier, and the plan holds a source time rather than a position, by the decision
  above.
- **A unit finished without ground is covered by the rule above**, not by an exception:
  an empty first page leaves one finished with nothing walked, so the backfill will never
  offer it again and this reads its front like any other finished unit — provided it has
  an edge. Where the corpus holds no message for it, there is no edge, and that is the
  state reported rather than acted on.
- **On start, not on every pass.** A gap found before this process has drained anything
  is the previous one's; a difference measured mid-run is as likely to be material the
  feed is about to deliver, and acting on it would spend a history page per pass to
  learn that.
- **Idempotent, and that is what makes it safe to run unasked.** Re-read records upsert,
  so a catch-up over a conversation that lost nothing changes nothing. What a restart
  costs is one page for each conversation the walk will not cover and whose edge is not
  strictly ahead of the source — in practice one page each, since the edge only moves
  from a page and the feed does not advance it. That is the price of the comparison
  being inexact, and it is bounded: a page cannot find a second page's worth of work
  where there is none.

The restore case is this one seen from the other side: a dump captures the corpus and
the plan together, so after a restore they agree with each other and the library's
private position is the only thing left believing itself further ahead. That
disagreement is exactly a lagging front edge, so the start-up catch-up closes it. What
a catch-up cannot reach is a hole behind the front edge — a page an interrupted pass
skipped, a field never captured — which is what the explicit re-read is for.

### Nothing the system authored is evidence

Content the assistant produced, which later returns through a source, is never
counted as independent corroboration. The cases that make this bite — fields written
out to the CRM interface, messages sent in workstream III landing back in an
account's own history — belong to deferred workstreams, so how the marking works is
not designed here.

### Egress

Three containers reach the internet, each to named endpoints and no others.
**Ingestion** reaches the sources it adapts and nothing else — no model API, no
embeddings call. The **backend** reaches the model API, including request shape #4,
which reads the open web and must cite sources, served by the provider's server-side
web search rather than by opening general outbound access from the machine. The
**bot** reaches the Bot API. The **embeddings sidecar** reaches the host its model weights
are published on, once, while it is starting and before it reports itself able to answer;
they are cached in a volume afterwards, so an ordinary restart reaches nothing and a first
start on a new machine needs that one edge. Postgres reaches nothing.
Recognition runs in the backend, so its model calls leave by that same named edge and
add none of their own.

**Why the bot is separate from the backend.** The frontend is the part most likely
to be replaced — a Telegram mini app, a web client, a second messenger. Keeping the
boundary at HTTP means replacing it costs a new frontend, not a refactor.

## Indexing

The index is the corpus made findable when a question carries no scope. A scope-keyed
question needs it for little: a thread, a contact or a topic names the material to read.
For little rather than nothing — a span already in hand is also a place in a
conversation, so a request holding a finding reads the index to reach the turns around
it, which is how it recovers what a claim cannot carry. Questions like *where did we ever
say anything about this* name no subset at all,
and the entity layer has no field for them — that is what the index is for, and the
knowledge base is the clearest case rather than the only one, because nobody knows which
file holds a rate card.

### The unit is a record, so conversations need no chunking

**A message is indexed one-to-one**, and neighbours are expanded at read time using the
conversation ordering already stored. A single "yes, agreed" is useless in isolation,
but expanding around a hit is cheap and needs no second artifact — which also keeps
windowing a recognition concern alone, with its stability question confined there.

**Only documents are split**, because a file is too large for one embedding. A chunk
points at a span inside its document, which is the same pointer shape as evidence, so
nothing new is introduced.

### Lexical and vector, fused

Neither mode is sufficient, and they fail in opposite places.

| Mode | Good at | Why the other fails |
|---|---|---|
| Lexical | names, company names, invoice references, amounts, exact phrases | identifiers and numbers have no useful semantic neighbourhood |
| Vector | paraphrase, and questions asked in a different language from the material | the corpus is Russian while the knowledge base, when it arrives, is mixed, and a Russian question does not lexically match an English proposal |

The cross-language case is the one that decides it here, and it is what the embedding
model has to carry: breadth across every language the constraints name, ahead of
benchmark position.

The fusion is built and the lexical half is unfilled. Reciprocal rank fusion combines
the two rankings wherever both have something to say, and a span holds a column for term
weights the model this build runs does not emit — so a question arriving without weights
is ranked on the vector half alone, which is what both the column and the read are
specified for. What that costs is the table's first row: an exact name or an invoice
reference ranks by semantic neighbourhood, which is precisely what it has least of.
Filling it needs a model that emits both halves fast enough to sit on the answer path,
where a person is waiting, or a second index built for the lexical half alone. Neither is
a change to how the read fuses.

### Coverage and cost

Embedding is cheap per unit of text — orders of magnitude below extraction, whether it
runs on the office machine's CPU or against an API. That is why the index covers the
whole corpus while inference stays inside the horizon, and why being generous here and
stingy with recognition is the right way round.

Material the source has since dropped stays indexed, because nothing is deleted.
Whether retrieval hides it is a retrieval decision, and it differs by request: drafting
should not quote a withdrawn message, while a question about history should still find
it. Both halves have something to read: a reported removal is recorded as the moment
the source said so, and material that merely stopped appearing shows a last-seen that
has stopped advancing. The first is a fact and the second is a signal, which is why a
request may reasonably treat them differently.

## Recognition

Recognition reads the corpus and produces business entities. It is a pass with its own
watermark, and it never reaches a source.

### The unit of work is one message, read in context

A commitment is stated in one turn and closed several turns later, so a message cannot be
read alone, and a conversation spanning years is too large to put in front of a model.
Recognition works over **a window: one message of a conversation, with a fixed number of
its neighbours either side as context**. Findings are reported from that one message, and
the context is there so that the model can tell what it refers to and whether anybody took
it up.

**One selected record is one window, which is what makes selection, extraction and marking
agree.** The watermark is per record, so what comes back as owed is messages; each gets a
window of its own and is marked when that window has been read. A finding is therefore only
ever read out of a record the sweep marks. The alternative — reading a stretch whole and
reporting from every message in it — writes findings from records nothing selected, so
nothing records that those messages were read and every later window reaching back over one
of them writes its finding again.

**What that gives up is a fact stated only in a context message.** The call covering it has
been paid for either way, and a stretch read whole would keep it; it is given up because
keeping it means keeping it unmarked, which is the duplicate above rather than a saving.

**A window is named by the message it reads**, so it does not move. A submission is
collected a sweep or more after it was made, and the only way a window can be lost in
between is the record itself leaving its conversation — not material arriving around it,
which changes a window's context without changing which record it answers for.

### How a fact's party is determined, and what that cannot do

**A line names who sent it where the corpus names them, and carries no name otherwise.** A
claim is kept only if it survives being read on its own, which is impossible where the
model cannot tell who said anything: the only agents available to it are "the sender" and a
bare first-person verb. So each line carries the corpus's own name for its author's person
record. It is a name and never a record identifier, because a model shown identifiers
writes them into claims, and assigning identity belongs to the `Reconciler` crossing alone.

**A label is a function of the author record and never of the window, and that is what
makes it safe to compare.** The comparison that decides what an attribute is about happens
a submission after the model answered, against a window re-derived by then — so a label
computed from the window, a position in it being the obvious candidate, is reassigned when
the context grows. The same string then means one party when the model writes it and
another when the answer is read, which stores a fact about one person against another and
produces a citation that checks out. A name cannot move that way: it belongs to the record.

A name is passed over in three cases, each where it would be worse than none: two of a
window's authors share it, so it names a party only ambiguously; it carries the field
delimiter or a line break, which stops the window being readable at all; or it is the word
the instructions reserve for "no subject". Where no name survives, the field is empty, the
instructions tell the model not to invent one, and nothing about that party is nameable.

**What that costs is a claim that cannot name an unnamed author.** The restating stage is
told to say what was undertaken without saying who by, rather than to reach for a pronoun
or a number. The party is not lost — it travels as a record identifier on the proposal and
into the stored row — so what is missing is a reader-facing name, and supplying one is the
answer path's job rather than this stage's.

**A proposal's party is the author of the message it was read from, and nothing else can
make it anything else.** For a commitment or an event that is who *stated* the thing, which
is the question those kinds ask — a promise relayed second-hand is the reporter's statement,
and the hedge marker is what says so. Who is obliged is a separate question from who
stated it, and the restating stage is asked it outright: an obligation a participant states
about another is a commitment, and which party it obliges comes back as one of the window's
own labels and is stored as a reference beside the claim.

**That answer is resolved against the window's author records rather than by matching the
claim's prose**, which is how an attribute's subject is decided too, and a label no line
here carries resolves to no party rather than to a new one. It is matched in the form the
label rule withdrew a shared name in, which is what makes it unambiguous: a looser
comparison could hold two surviving labels equal and place an obligation on whichever
author the window listed first. Nothing is minted either — the model echoes a label and the
record behind it is looked up.
What the field cannot speak for is an obligation placed on somebody who never wrote in the
conversation — there is no label to carry, so it records no party, and the claim's prose is
the only account of that one's direction.

**A commitment has to have a party who is to do the thing, and the classification stage is
where that is held.** The kind holds whoever performs the thing, which is what lets an
obligation a speaker places on another participant be read as one; without the requirement
beside it, that also admits a sentence placing the work on nobody — work stated impersonally
as needing doing, or a decision to put something off with nothing said about who then acts.
A status answer lists what a reader is meant to act on, so an obligation nobody undertook
sends them after work that may already be settled, which is worse than an omission. Such a
sentence states none of the four, with two routes closed by name and one left open: an
occasion it reports at a stated time is still an event, while the kind for a fact outside
the closed vocabulary is refused it outright, because a figure collecting unowned work would
stop measuring what it exists to measure. Nothing is counted for the refusal itself — it
arrives as a window holding none of the four and is settled as one.

**The requirement is on somebody being there to act, and never on that somebody being
nameable.** The restating stage already answers that a commitment obliges no party where no
line carries one, and nothing downstream requires a commitment to resolve one, so a
commitment whose party cannot be named is a commitment still — a rule refusing every unnamed
party would discard a sound reading of that shape along with the spurious ones. The
instruction carries that bound in the paragraph after the rule rather than leaving it to be
inferred. The deferral half is scoped the same way, to a decision with nobody taking the
thing on afterwards, since a speaker who puts something off and says who does it next has
undertaken it; and the clause refusing such a sentence however firm a time it carries is
what settles it against the rule sending a date something is wanted by to a commitment,
which is itself scoped to where somebody is to do the thing.

**A candidate binding two parties comes back as one reading per party, because a row holds
one obliged party.** A sentence putting one participant on a piece of work and another on
reviewing it is one thing the classification reports and two obligations the corpus has to
hold, so the restating stage answers a line for each party the thing obliges. The split is at
restatement rather than a stage earlier because a classification line carries a kind, a
message, a hedge and a subject and no discriminator: two commitment lines on one message are
the same line and build the identical request, so two candidates could not be asked different
questions. Answered per party instead, the second obligation costs no further call at the
stage that is expensive, and the stage runs once per candidate rather than once per finding.

**Two rails bound what a further line may add, and they are rails rather than requests.** An
instruction to divide something invites the division of what was whole, so a line is refused
where another line of the same reply already obliged its party, and refused where another
already said the same thing in the same words. The second is not the first restated: a finding
is keyed by its conversation together with its claim, so two rows worded alike are one row
whoever they oblige, and both carrying one message's instant the write would keep the later
party and lose the earlier obligation under a figure that reads as a better reading arriving.
The sentinel is a party like any other to the first rule, so two obligations no label could
place are one reading. An event and an attribute oblige nobody, so each has one party and
keeps the single reading it has. A refused line is counted rather than passed over, which is
the rate saying whether the division is being obeyed.

What this gives up is two obligations genuinely falling on one party, and the loss is
unconditional: it cannot be recovered by the classification stage reporting two lines, since
two such lines are identical and buy identical restatements. The evidence stays in the corpus,
and a claim naming one of the two undertakings is the narrower reading rather than a wrong one.

**A label is carried into a claim in the script it is written in.** The claim is asked for
in the conversation's language, which for a participant whose name is in another script asks
two things of one sentence, so the instruction says which wins: the label is copied and never
translated, transliterated or shortened, and the prompt shows a rendering it must not
produce. Nothing verifies it — a label is compared against the author record only for an
attribute's subject, so a translated name inside a claim is caught by no rail and only by
reading the prose against the contact it names.

**For an attribute the author is the subject, so only a fact the model presented as being
about its author is stored.** The classification stage is asked which party the fact
concerns, as the label on that party's line; a subject that is not the author's own label
is a reading this build cannot place. Without that, an attribute about a third party, a
company or a system is asserted of the person who happened to raise it — a human recorded
as being based in a hostname. The instructions therefore ask for an attribute only where
the sender describes themselves, and the parser refuses the rest rather than trusting the
instruction. An author the corpus does not name has no label, so no attribute stated by
them can be stored either.

**That check is the subject the model named and not what the fact is about, so a second one
reads the value.** A fact about a system offered under the author's own label passes the
subject rule — the subject is the author's, which is all that rule asks — and every figure
read off the predicate field is blind to it, the name being one the list holds and only the
value being wrong. So the value is tested for three shapes no entry
can take: a URL scheme, a leading path segment, and a numeric address. Those are values no
town, job title, employer or rate can be, in any script and whatever their capitalisation,
so refusing them costs nothing that was ever going to be stored.

**A bare dotted host is deliberately not among them, because no pattern can test it here.**
A host and a place name are the same shape, so a pattern wide enough to catch one refuses
abbreviated Latin place names and job titles, and one narrow enough to spare those misses
every host written in the corpus's own script. The two classes coincide rather than merely
overlapping; a rail of that kind was tried, measured wrong in both directions, and removed.
What covers the case instead is the classification stage naming the kind outside the list,
which reads the fact rather than its shape.

This is the one check in the stage that does not read the model's answer for its evidence,
which is why it is the one that survives a prompt being reworded. What it cannot see is a
plausible wrong value: a place that was never stated is refused by nothing here. A refusal is
its own figure, and what it refuses would otherwise be stored with an open validity window
and a real message citing it — which on the answer path reads exactly as a fact that is true.

**The subject rule is applied at the classification and not at the restatement**, because
the restatement is the expensive stage: a reading that can never be stored has to be given
up before it is paid for. The value test cannot be moved there and is the exception — the
value is what the restatement answers, so there is nothing to test until that call has been
made and billed. What it saves is the row and not the money.

**A reading refused by this rule is counted apart from one the build could not read**, and
the two figures mean opposite things. A refusal is routine and expected on most sweeps; a
reading the parser could not make sense of says a prompt and a parser have drifted apart,
which is total — nothing can be read from any reply until a build fixes it. Added together
the routine figure hides the other one completely.

**What this cannot do is attribute a fact to its real subject.** A label resolves to a
person record, so the half about another participant could be done; a fact about a company
or a system resolves to nothing, so the two halves would behave differently under one rule.
Attribution is a decision of its own, and until it is taken a third party's fact is a
counted refusal rather than a row — wherever the model named that third party as the
subject, which is the only place this rule can see.

### The pass

| Stage | Model | What it does |
|---|---|---|
| Identity | no | Person records become contacts by identifier match; a conversation carrying a name becomes a client by reading it, unless it is a two-person exchange |
| Selection | no | Records that changed, inside the recognition horizon — one window each |
| Extraction | **yes** | Two stages — classify utterances as commitment, event or attribute candidates, then rewrite each to stand alone. Emits proposals |
| Closure | **yes** | For one commitment, reads what followed it in its own conversation and records which later message reports the thing as done, or that none of the material weighed does |
| Resolution | no | Dedupe, conflicts, validity windows |
| Commit | no | Advance the watermark |

Identity runs first and over everything, because it is deterministic and cheap, and
because extraction's output refers to people who must already have identity to resolve
against.

**A two-person exchange is excluded, and that is the taxonomy rather than a judgement.**
What such a chat displays is the counterparty, rendered by the platform rather than chosen
by anybody, and it is already the display name of that person's contact — so it is not a
statement about what the conversation is for. Reading it would make a body of work of every
person ever written to and leave a contact beneath itself. Identity therefore excludes the
kind, which it can read, and never the name, which it cannot interpret.

**Reading a name admits every shared conversation somebody named, and narrowing that is a
pass of its own rather than a stage of this one** — see [Admission](#admission). It needs
the model, which identity does not, because a label is read while what a label *means* is
inferred. And it judges conversations, which identity already marks: sharing recognition's
pass identifier over one record kind is the unbounded re-derivation the rule below forbids,
so it carries an identifier of its own. Its absence changes nothing — a conversation it has
not judged counts as a client — and what it rejects is filtered on read, because this layer
deletes nothing.

**Extraction emits proposals, never entities.** A proposal carries its evidence span, a
hedge marker, and references to records — never an entity identifier. It cannot name a
contact: naming identity is the `Reconciler` crossing, and an extractor sits on the
wrong side of it.

**The hedge marker says how far a finding can be relied on, and two conditions make it
hedged.** One is the speaker's own uncertainty, so that a tentative remark does not read
as a promise. The other is second-hand knowledge: somebody passing on what they were told
cannot settle it on the original speaker's behalf, and what this build holds of a
commitment's direction is which party it obliges rather than whose account the speaker had
it on, so nothing else distinguishes a relayed claim from a first-hand one. Both make it
hedged and the two are not told apart, which bounds what a consumer may say: the answer
path marks such a finding as not stated firmly or not first hand, covering both without
choosing between them, and instructs that one so marked is never presented as settled and
that the reading behind it is not reported to whoever asked. Grammatical person is a clue to
the second and not the test for it: a first-person sentence is hedged where its own
wording hedges it, and a fact stated about another person from the speaker's own knowledge
is plain. The instruction names both conditions rather than asking for tentativeness
alone, which does not reliably produce either, and carries cues in Russian as well as
English, since that judgement is made in the language the hedging is in. Georgian is not
covered yet.

**The marker reaches a commitment and an event and stops there.** A statement carries no
such field, and it needs none for the case the subject rule can see: a fact the model named
as being about somebody else is refused rather than stored unmarked. What it does not cover
is a relayed fact the model misattributed to the author, which that rule cannot see and
which is therefore stored with no marker on it.
Attribution is what would let such a fact be kept as the reporter's rather than the
subject's, and it is not held. So the absence does bound something: that statement is
stored indistinguishable from one the author knew at first hand, and the only qualification
travelling with it is that a model read it — which every statement this pass writes carries,
and which therefore says nothing about this one in particular.

**An attribute's predicate comes from a fixed vocabulary, and one outside it is not
recorded.** A statement is keyed by its predicate, and resolution decides which readings
contradict each other by grouping on it — so two names for one idea are two series that
never meet, and the superseded value keeps an open window for ever. The store accepts any
predicate, deliberately, so that extending the vocabulary is a prompt change rather than
a rebuild; extraction is what holds the line, by offering the model a closed list and
losing what falls outside it. What it loses is reported rather than dropped silently, and
which figure it lands in follows from how the model answered.

**The instructed answer and the disobedient one are separate figures.** A predicate field
holds one of four answers, and three of them are not a predicate: the refusal word, a
word the list does not hold, and an empty field. The restatement stage asks for the
refusal word where no entry fits, rather than a stretched entry — so on something the
classification already called an attribute, the refusal is the two stages disagreeing
about whether the fact fits the list, which is the nearest thing a sweep has to a signal
about the width. A word outside the list is the same judgement reached by disobeying; it
says whether that rule is holding, and because the parser tests membership alone it mixes
a synonym of an entry already held with a relation genuinely new. An empty field is a
prompt and a parser out of step, reported with the lines that would not parse. Summed,
none of the three could be read for what it means. The rules declining a finding over
where it sits or whom it is about are reported separately again, for the same reason: they
fire as a conversation grows.

**A fourth refusal reaches the same stage and is read off the value rather than the
field.** A legal entry over a value of a sort it does not take is invisible to every figure
above, so it has one of its own — and it is the only refusal in the stage that does not take
the model's answer as its evidence, which is what makes it the one that holds when either
instruction is reworded.

**The kind outside the list has a name, and that name is what measures it.** The
classification stage offers a fourth kind for a fact stated about a subject that no entry
asserts, and nothing is stored from one: the line is read, refused for being outside the
vocabulary, and counted. Without it the stage has two moves and no third — call the thing an
attribute anyway and let the next stage key it under whichever entry parses, or report
nothing at all. The second is byte-identical to a window that holds nothing, so a sweep
writing no attributes reads as a corpus holding none and as a stage dropping everything it
cannot label alike, and no other instrument separates the two.

Disobeyed, what happens still turns on the kind the model gives it instead. Called an
attribute, two checks stand in the way: the subject rule, which refuses a fact named as
being about the system itself or about anybody but the author, and the value test, which
refuses a value of a sort no entry takes however the subject was named. Called a commitment
or an event, a subject is asked for — the refusal word is the answer the instruction asks
for there — and nothing checks it for either kind, so whatever the field holds is ignored
and it becomes a row of that kind, stating the author as the party where the corpus names
them and nobody where it does not.

**What the figures still cannot do is size the list.** The signal above cannot be told
apart from a classification that was wrong to call the thing an attribute, both stages being
given the same list; and the count of facts outside the list cannot tell a vocabulary that is
too narrow from a corpus that genuinely holds other facts. Both read as the same number.
What the count does settle is whether such material is arriving at all, which is the question
a silence cannot answer. Sizing the list still means reading the corpus.

**The list is four entries wide: where a contact works, what they do, where they are, and
what they charge.** The width is what the request shapes ask about a business contact, and
an entry is added only where a corpus asserts the attribute *and* a shape asks for it. An
attribute a corpus asserts that no shape asks about — what a system runs on, where a
service is deployed — is left out rather than absorbed, because a vocabulary sized to
whatever a corpus happens to say is an open vocabulary arriving one word at a time. The
classification stage is what holds that line, by naming such a fact as the kind outside the
list rather than leaving it to be filed under whichever entry it resembles.
Contact details are outside the list on purpose: an address or a telephone number is
a claim on the person record, matched by the identity stage, so a predicate for one would
be a second and weaker copy of something already structural.

Adding an entry is cheap and retiring one is not. Both move the recognition version, so
the corpus inside the horizon is read again — but a statement is only ever removed by a
re-windowing that replaces it under the same subject and predicate, so statements keyed
under a retired predicate stay, with validity windows nothing will ever close: a window
closes only where a later reading contradicts it under that same pair, and no later
reading will ever carry that name.

**Both stages are told the vocabulary.** Whether a thing is an attribute at all is
decided by the classification stage, so that stage names the list too — deciding it
without the list in view is how a fact the list does not describe comes to be filed under
whichever entry parses. The value is checked against the relation it arrived under, a
predicate the list holds over a value of a sort it does not take being the one shape no
figure read off the predicate field can see. The subject is checked too, but by party
rather than by kind — an attribute is kept only where its subject is the author who stated
it — because an entity identifier carries no kind to compare against.

Relative dates resolve against the evidencing message's own instant rather than
against now, so "next Friday" in a message from 2021 is a date in 2021.

Two values are refused against that same instant, and each is counted on its own.
A due date standing before the message that stated it is refused on a commitment
the message's own author owes, because nothing is promised for a time already past
by the party who is to do it. It stands on an obligation the author states about
another party, whose deadline is routinely mentioned once it has already been
missed, and on an event, because an occasion is routinely reported after it
happened. So the refusal consults which party a commitment obliges, and wherever
direction cannot be settled — no label for the party, or no author on the message to
compare one against — the date is refused as the author's own would be: that is the
reading the record can contradict, which makes the figure a ceiling on the record
contradicting itself rather than a measure of it. How often the party can be placed
at all is read off the stored rows, and is what bounds the difference. A date equal
to the message's instant is refused for
either kind and read as no date stated: something undertaken or happening straight
away resolves there legitimately, nothing can tell that from the one instant the
model was shown being copied back, and most of the dated findings measured arrived
that way — so the value is given up to make the field trustworthy at all. Either
refusal costs the date and keeps the claim, an undated finding being the ordinary
case, and the two figures are apart because one says the record contradicted itself
and the other says whether the instruction against copying the line is holding.

### A mark carries the version that wrote it

One mark per `(pass, record kind, record)`, holding the version that read the record. For a
pass that reads synchronously that is the version current when the mark is written; for one
that submits a batch it is the version the submission was made under, which the paragraph on
submissions below gives the reason for. A record is owed again on any of three conditions:
it carries no mark, the mark's
version differs from the pass's own, or its content has moved since the mark was written —
content time rather than observation time, so re-reading a record that did not change
settles nothing twice. Two things therefore invalidate a mark without anyone deciding to: a
record whose content changed, and a change to the prompt or model that reads it. Improving
an extractor re-derives exactly the windows it affects, and spends model tokens rather than
one request against a source.

**A version that does not move is a change nothing re-derives**, which is the failure this
watermark exists to prevent and the one it cannot detect. A version built from what the
pass reads — a model identifier, a digest of its prompts, the weights themselves — moves on
its own. Anything outside that, such as a widened rule about which records the pass acts
on, moves only because somebody bumped the prefix that covers it.

**Two passes over one record kind need distinct pass identifiers**, because the version is
compared and not part of the key. Sharing one would give each pass a mark written by the
other at a version it does not recognise, so both would re-derive the same records on every
run for ever — unbounded, and expensive for whichever of the two spends model tokens.

### A stage that marks no record carries its version on its output

A watermark decides which *records* are owed a reading, so a stage that reads no record has
nothing to mark. The closure examination, which weighs a commitment against the messages
that followed it, and the adjudication, which compares two findings already written, are
both of that shape. What each produces is a row of its own — a verdict, or a note that two
findings are to be kept apart — and that row carries the version that produced it. The
query offering the stage its next work then excludes only the rows naming the version it is
running under, so a row decided by wording that no longer exists is owed again.

**A submission carries the version it was made under, and its collection writes under that
rather than under the one current when the reply is read.** A batch is collected a sweep or
more after it was made and a reword arrives by deploy, so the two coincide whenever a prompt
card is deployed while a pass has work with the provider. Reading the version at the write
would record a retired wording's answers as current, and nothing could tell them from output
the new wording produced: the only thing that retires either is the version it is claiming.
Carried, those answers are owed again the moment they are read back. It holds for every stage
that submits — the reading stages, whose mark is written at collection, and the closure
examination and the adjudication, whose verdicts are. A submission naming no version means
the version current at collection.

**A version per stage, and not one over all of them.** The invalidations are different
sizes and point at different material. Rewording the classification or the restatement
means the records inside the recency horizon are owed a reading again, which is the
expensive direction: the whole horizon, re-read at the bulk rate. Rewording a comparison
has to revisit the pairs that comparison decided, and must not re-read a corpus no word of
it touches. Folded into one version the cheap invalidation would buy the expensive one
every time, so a model stage's version covers its own instruction, model and effort and
nothing else, and no stage's covers another's.

**Every standing instruction a stage sends is covered by some version.** An instruction
outside them all is the failure the watermark exists to prevent, arriving by the back door:
editing it ships a behavioural change that invalidates nothing, and the symptom — a prompt
edit that appears to do nothing — is several files from its cause. Both of these stages
assert the property rather than the instructions that currently hold it, enumerating their
own instructions so that one added later has to be given a version before the suite is
green. What the assertion keys on is the naming convention those constants follow, so that
convention is the rail and an instruction named outside it is a reviewer's job.

What a digest covers is the wording, never how the material is rendered in front of the
model. Each stage states where that is raised by hand instead: the reading stages have the
pass's own prefix, and the comparison has the revision in its stage name.

**Both tiers of the collapse are retired on the same rule, and they share one column.** A
pair is kept apart either by the vectors, which costs nothing, or by a paid comparison; the
row names the vector version in the first case and the adjudication version in the
second, and each is retired when its own version moves. So a better embedding model or a
moved similarity bound retires the cheap judgements while the paid ones stand, and a
reworded comparison retires the paid ones while the cheap ones stand. The two value spaces
cannot be confused because each version names its own stage first and the two stages are not
named alike — the paid one *adjudication*, the cheap one *vectors*.

The rule is the same shape and the same strength on both tiers, each version covering
everything that decides its own tier's answer. The paid tier's names its instruction, its
model and its effort. The cheap tier's names the embedding version together with every
similarity bound that reads its vectors — the one above which a pair is worth paying to
compare, and the one above which it is treated as the same fact — because what decides a
rule-out is the cosine and the bounds it is held against, and a bound left outside would
ship a behavioural change that invalidated nothing. The bounds are written into the value
rather than hashed into it, as the identity stage writes its weights: a bound is smaller
than its digest and says which arithmetic decided. None of them is optional where the
version is built, so one added later cannot reach no version by being forgotten.

**The statement side of the collapse is over-covered, deliberately.** It has no merge tier
— a value pair is handed to the model or left alone — so the bound above which two values
are one fact decides nothing there, and moving it retires rule-outs it did not decide. The
alternative is a version per side, which is a second value space in the same column buying
no answer it could change; both sides are a local comparison whose re-derivation costs no
model budget.

**A version move is paid for once, at the next sweep.** The pairs *kept apart* under the
wording being replaced become candidates again and are compared at the new one; a pair the
old wording merged does not return, because merging supersedes one side and the candidate
query offers only survivors. It is an ordinary re-derivation, bounded by how many verdicts
the store holds rather than by the size of the corpus, and far smaller than a reading
stage's because the stage's whole prompt is two sentences. There is no floor on how much new
material brings a pair back, as the closure stage has: the bound is the verdict count alone,
which is what makes this cheap while the derived layer is small.

### Dedupe happens at three levels

| Level | What duplicates | Mechanism |
|---|---|---|
| Record | one message fetched by several accounts | unique `(source, external id)`, enforced at capture |
| Entity | one human appearing as several person records | scored identifier matching, below |
| Finding | one fact extracted more than once | content-derived natural key, with the evidencing spans beside it |

**A finding is addressed by what it says and carries the spans it was read out of.** A
statement, a commitment and an event take that one shape: the row holds the claim and the
key is derived from it, and a separate table holds one row per span that evidenced it. A
fact read out of two *different* spans is corroboration rather than a duplicate — the key
collapses to one finding and the evidence accumulates on it. Two findings read out of
*one* span are two findings, because a span evidences a finding and never identifies it.

**A position belongs to a span and never to a finding.** An ordinal addresses a unit
within a record — zero for a message, which is indexed whole — and is the tail of the
written citation form. Nothing numbers the findings within a span, so a citation names
material a reader can look up, and a finding reaches the model under the identifier of
the turn it was read out of rather than beside it.

**Words a rewriting chose are not a key.** Restatement produces a different string for
one fact on a second reading, so an exact comparison recognises only an identical one.
Collapsing is therefore ordered by cost: the exact key first, then a vector comparison
bounded to one conversation and kind, and the model for whatever that leaves. A duplicate
is a pair of claims that mean the same thing, which is what the comparison has to answer.

**A statement collapses by the same ordering and with one tier withheld.** A commitment has
an identifier, so two readings of it are merged by having one row name the other; a statement
is its own content, so what is recorded about two readings is that their two *values* mean one
thing, and resolution then treats them as one value and re-windows. The vector comparison
there only chooses what to ask about and decides nothing, because two values of one relation
are usually a deliberate contradiction — a rate quoted in March and revised in June is what
the validity window exists to hold, and two short values close in wording are the pair a
vector comparison is least able to tell from a rewording. Merging one of those would delete
the history the table was built for, where the same mistake on a commitment costs a row. So a
statement's two wordings are only ever put together by something that was paid for.

**A reworded value is not a duplicate but a false contradiction**, which is why the collapse
reaches statements at all. Compared for equality, a second wording is a *changed* value: the
earlier window closes where the later opens, and the fact reads as having changed when nothing
changed but the words.

### Entity resolution is scored, and merges automatically

Each comparable signal contributes a weight, the weights sum, and a pair above the
threshold merges without asking anyone. A signal's weight is the ratio between how
often the same person agrees on that field and how often two different people do —
which is why an address outweighs a name with no special case, and why two moderate
agreements together can carry a merge.

| Signal | Weight | Why |
|---|---|---|
| Phone, in E.164 | strongest | accounts on the chat platform are phone-anchored and numbers are rarely shared. Discounted across long gaps, because numbers get reassigned |
| Address, normalised | strong | occasionally shared — a household, a role account — so strong rather than decisive |
| Handle, across platforms | moderate to strong | unique per platform, and people reuse them |
| Avatar content hash | moderate | the same image on two platforms is evidence, and the hash already exists |
| Name with organisation | moderate | the pair discriminates far better than either half |
| Name alone | weak, **scaled by rarity** | a common name is near-zero evidence; a rare one is meaningful |

Rarity is counted from the corpus — how often each name, address and number appears
across person records. That supplies the denominator without labelled data, and it is
what makes the weakest signal behave correctly instead of uniformly.

**A pair is compared on two keys, and one of them is the name.** Comparing every record
against the whole corpus is not an option, so what a batch pulls in is the records sharing
a claim with it and the records carrying a name it carries. The second key is not a
refinement: on a phone-anchored source a handle is unique per account and a number is fixed
when the account is registered, so one human's two accounts agree on the display name and on
nothing else, permanently. Selecting on claims alone would never assemble such a pair, and a
pair that is never assembled is never scored, never kept, and invisible to anyone looking
for duplicates. The name key is bounded by how common the name is, which is a separate
decision from the rarity that scales the score: rarity says what a shared name is worth, and
the bound says whether the pair is worth assembling to find out.

Three rules decide whether this survives real data:

- **A missing field is neutral, never disagreement.** Person records are sparse; most
  participants offer a handle and nothing else. A score is normalised over the signals
  actually comparable, because counting absence against a pair drifts every sparse
  pair to "different people".
- **Hard negatives override the score.** Two person records that are simultaneously
  distinct members of one conversation are two humans, and no accumulation of weak
  agreement may outvote that.
- **Below the threshold a candidate pair is kept with its score** — not discarded, and
  not queued for a person to approve. The corpus grows, so a pair scoring halfway today
  crosses the line when a number appears on one side. Re-evaluation is triggered by a
  new identifier arriving for either record, not by every pass: a claim arriving for the
  first time moves the record's content time, which is what a watermark reads, and a claim
  seen again moves nothing so a resync re-derives nothing.

**A pair that has been settled is not a candidate.** One whose sides resolve to a single
head is already one human, and one a correction has separated is two; neither is an open
question. The pass writes no candidate for a pair it has joined, and none for a separated
pair still clearing the threshold, since the constraint refuses that join before anything is
kept. A separated pair under the threshold is still kept, because the evidence in the corpus
says what it said and nothing rewrites it — so for that one, and for any row written before
the pair was settled, it is the read that withholds it rather than the pass.

**Merging destroys nothing.** Both contacts keep their rows and their identifiers; one
carries a link naming the other as canonical, and retrieval follows links to the head.
Unmerging removes a link. Internal identifiers never change, so nothing downstream
renumbers.

**Confidence lives on the link, not on the contact**, because the uncertainty is in the
join rather than in either person. A contact assembled from four records has three
links, and its reliability is the weakest of them — exposed rather than averaged away.
Resolution is therefore clustering under constraints, not transitive closure: a hard
negative is a cannot-link, and a cluster violating one is split rather than merged,
however strong each pairwise link looks on its own.

The weights are judgement and are recorded as judgement; only the denominator is
measured. What the judgement answers to is a sample of pairs, each one read and carrying
the decision it should produce, which the weights and the threshold together have to
reproduce — so resolution accuracy is a gate beside the scorer rather than a tuning
constant nobody owns. **That gate is hermetic and not in the evaluation set.** Identity
makes no model call, so the property is deterministic and can be asserted on every merge
for nothing; a tier that spends and is asked for would measure it only when somebody
remembered to buy it, which is how an uncalibrated constant survives unexamined. The
sample is small and says so: it pins the decision each shape produces and cannot estimate
how often a shape occurs, which needs a corpus that holds duplicates.

**The threshold is one decisive signal's worth of evidence**, which is the whole of its
meaning — a pair merges when it agrees on as much as a shared number says. So a judgement
about what some other signal is worth belongs in that signal's weight, where the ratio
above can be read against it, and never in the bar: moving the bar restates every signal's
worth at once while appearing to state none.

**The weights are sized so those behaviours hold on the signals a source actually carries.**
On a phone-anchored platform that is a handle and a name, so those two agreeing reach the bar
exactly, and a handle alone stays under it because a released handle can be taken by somebody
else. Sizing them so that two signals the corpus holds none of can carry a merge, while the two
it does hold fall short, is a calibration against imaginary evidence — and it reads as working,
because the pair that would disprove it is one nothing ever assembles.

**So the kept pairs are readable, strongest first, above a floor.** A number nobody can
sample is a constant nobody can set, which is what that paragraph would otherwise amount
to. The read is advisory in the strict sense: nothing waits on it, no pass blocks on a
person having read it, and a pair is kept whether or not it is ever reported — the floor
bounds what a reader is shown and discards nothing from the store. There is deliberately no
shape for acting on a reported pair, because a list of pairs to approve is the queue this
design rejects on the grounds that nobody would work it.

### Conflict resolution

**Conflicts resolve by the source time of the evidence, never by when the system
extracted it.** A backfill reads 2021 after it read yesterday, so extraction order
carries no meaning — order by it and backfilling old history silently overwrites
current facts with stale ones.

| Case | Resolution |
|---|---|
| Same subject and predicate, different value, later evidence | Close the earlier statement's validity window where the later one starts. Two statements, adjacent windows, nothing deleted |
| Two sources disagree | Keep both. Prefer the later validity start; on a tie prefer a labelled origin over an inferred one, because a profile field outranks a passing mention |
| A correction against re-recognition | The correction wins. A conflicting proposal is recorded as a visible disagreement and not applied |
| A commitment open, then met | Not a conflict. A later message evidencing the closure is recorded beside the commitment rather than on it, so the reading of the message that stated the promise is untouched and removing the link restores it |
| Contacts wrongly joined | Remove the link, which is possible because the merge recorded the score and signals behind it |

### Re-running changes nothing by itself

Recognition re-run over an unchanged corpus produces no writes: windows are stable,
finding keys are content-derived, merges are links rather than rewrites, and internal
identifiers are immutable.

**A re-derivation under a moved version is the case that tests it.** The pass reads
material it has already read and restates it in words it did not use the first time, so
a comparison on the exact key alone forks the finding. Collapsing resolves fresh readings
against the stored ones rather than within the batch, which is what keeps a version move
from doubling the derived layer.

### Scope, because extraction is the expensive part

Extraction dominates the cost of building a derived layer, and the model budget for
this build is a fixed credit. So recognition is deliberately
uneven:

- **Identity across the whole corpus** — identifier matching and label reading, with no
  model calls at all.
- **Inference inside the recency horizon only** — commitments, events, and attributes
  read out of text. The same configured value that bounds query recency, and it is
  independent of how far back capture reaches.
- Corpus outside the horizon stays captured and unrecognised. Widening the horizon is a
  re-derivation, not a re-fetch.

### What recognition may not do

- Mint identity. It proposes; only `Reconciler` assigns.
- Overwrite a correction.
- Present an inferred entity without its evidence.
- Reach a source. It reads the corpus and nothing else.
- Broaden a claim past what the message bounded. A restatement has to stand without the
  conversation in front of the reader, and a qualifier that bounds the thing — the context
  it was said of, the tool it applies to, the condition attached — reads like a dependence
  on context rather than part of what was said. Dropped, what is stored is fluent, correctly
  attributed and wider than anything anybody said, and no later stage can recover it: the
  answer path is handed the claim and not the window. So the bound is kept where the window
  supplies it, and the narrower reading is written where it cannot be told.

## Admission

Which named conversations stand for a body of work, and which only collect material about
a theme. Identity reads a client off any shared conversation's name, which is right where
every named conversation is work somebody is doing and wrong where a source mixes client
threads with thematic tracks — referrals, partner programmes, banks, hiring, a shelf of
resources. Each of those carries a label exactly as a client's thread does, so nothing a
label *is* tells them apart; what settles it is what the messages under it are doing.

| Stage | Model | What it does |
|---|---|---|
| Selection | no | Conversations the pass has not judged, or judged under an instruction that has since moved. No recency horizon |
| Judgement | **yes** | One call per named conversation, shown the name and a bounded sample of its newest messages. Answers *body of work* or *thematic track* |
| Filtering | no | Rejected clients are withheld wherever a client is read |

**A pass of its own, which the watermark decides rather than a preference.** A mark is one
row per pass, record kind and record, with the version compared rather than keyed on — so
this pass and the identity stage, both reading conversations, would overwrite one mark with
each other's version and both would re-derive the whole conversation table on every sweep,
one of them at the model's prices. Its own identifier means its own advisory lock and its
own cadence, which is what it wants: it is paced by conversations arriving rather than by
messages, and the default cadence is the longest in the build.

**Nothing downstream depends on it having run.** A conversation with no verdict is a client
exactly as it was, so the pass may lag the corpus arbitrarily, be switched off, or never
have run at all. That is what keeps identity standing on its own, and it is why the pass is
asked for rather than assumed: it spends, and on a taxonomy where every named conversation
is already a body of work it rejects nothing and the tokens buy no change in output. The
taxonomy it is for is the mixing kind — one topic per major client alongside tracks for
referrals, partner programmes and the rest.

**The verdict is its own table, keyed on the conversation.** Not a column on the client
row, which is a reading of a name that nothing about the name's meaning changes — a
judgement written onto it would be re-derived away the next time identity wrote the row,
and a reader could no longer tell a conversation nobody has judged from one judged to be
work. Keyed on the conversation rather than the client, so a verdict can be recorded before
identity has assigned the client and survives identity re-deriving it.

**What a verdict withholds is the client and not the conversation.** A rejected track is still
a named thread somebody can ask about, and is still offered as one — what it stops being is a
body of work: nothing resolves to it as a client, no contact is reported as sitting under it,
and no section of an answer is headed by it. Withholding the conversation too would make the
thread unaskable, which is a second decision nothing here has taken and the opposite of what
reading a label too generously costs.

**What it rejects is filtered on read and never deleted**, as the no-removal rule requires.
The filter is a view over the client table, and every reader goes through it: subject
resolution, the hop into a client's people, the body of work a contact sits under, resolving
a client to its conversations, and the sectioning of an answer. One definition of *admitted*
rather than the same predicate once per statement — and a reader that joined the table instead would offer a rejected client
with nothing failing to say so, which is why `:archtest` holds that too. The one exception
is identity's own read-back of what it wrote: a pass that judges labels has to be able to
see the ones that were rejected.

**A verdict carries the wording, the model, the effort and the size of the sample that
reached it**, as the extractor's version does and for the same reason: a reworded
instruction re-judges what an older one decided, and nothing else does. A conversation
judged on more or less of itself is judged differently, which is why the sample size is in
it.

**A stale verdict goes on being honoured until it is replaced.** The version decides what
is owed a re-judgement and not what is believed meanwhile — a filter that preferred the
current version would re-admit every rejected track the moment a prompt moved, and answers
would be about material a judgement had already excluded for as long as the pass took to
catch up.

**An unsettled judgement admits.** The two mistakes are not symmetrical: a track wrongly
admitted offers material under a label nobody would ask about, while a body of work wrongly
rejected cannot be asked about at all. So the instruction states the default rather than
leaving it to the model, and a conversation with nothing readable under it is still settled
rather than offered on every sweep for ever.

**What it spends is reported and bounded like any other paying stage.** Every call is an
ordinary one rather than a submission, so each is charged to the day as it is made — which
is what the driver reads back before each batch to decide whether the day has room. The
batch is bounded, the pass converges, and the free half of a batch — settling the two-person
exchanges and unnamed conversations there is no client to read from — runs whether or not
the day has anything left.

**Its allowance is a fraction of the configured pair, and the figures add.** An allowance is
per pass and the day's total is read per pass, so the two bounds are independent: with a
tenth here, the worst-case day costs a tenth more than the configured pair rather than twice
it. Recognition keeps the whole pair, because its defaults are sized from what a record
measurably costs and taking a tenth off them to tidy the sum would move a measured bound for
the pass whose lag a person notices. The fraction is fixed here rather than configured, so
that how much more than the pair a day can cost is one sentence instead of two numbers.
Sized from what a judgement costs, it admits some tens of conversations a day; an archive
with more than that takes a few days over its first sweep, which costs nothing anybody waits
on.

## The answer path

Five shapes with fixed pipelines, a free-form fallback, one runner. A pipeline is an
ordered list of pure stages and the runner owns the lifecycle, so a new answer kind is
a new stage list rather than new machinery. The fallback is a pipeline like the others
and registers the same way; what makes it different is that it has no fixtures, and that
an empty corpus does not refuse it — a general question is what it exists to catch.

**Four shapes are built here, and `offer` is not one of them.** Offer and price reads the
knowledge base, the knowledge base is corporate Drive, and Drive arrives with workstream II,
so that shape belongs to that workstream. The registry is open, so it arrives as a package
rather than as a change to the runner.

**What a command accepts is declared on the wire, so a text describing the commands can be
checked against them.** `Command` in `:contract` names the four commands the backend
answers and the subject kinds each takes; what follows the word is read off that, since a
command accepting no kind has nothing a name could resolve against and its argument can
only be the question. The word is the shape's own identifier rather than a second name for
it. Two tests hold the two ends: one that every declared command binds its shape at exactly
the declared scope, and one that the bot's help text names every command and every kind.
Nothing composes a command list from the declaration — the bot's list is prose in its
catalogue, because what a command is called and how it is described to a reader are
different facts. What an interface does read off it is the set of words, which is how a
leading slash-word no command is called is told from one that is. The declaration does not
enumerate the registry: a shape nobody has declared is simply not offered yet, which keeps
adding one a package and a line. The commands an interface answers from its own catalogue
are not here either, and listing those is its own business.

**A command the interface does not offer is answered at its own edge, with the command
list.** What it offers is the declared commands and the two it answers itself, so the set it
accepts and the set it lists are one set, held both ways by a test. The reply is a catalogue
entry taking the command list as its argument rather than carrying one, so the words
somebody who has just mistyped a command is sent to cannot be a second, staler list than the
help text's. It reaches no backend and spends nothing, which is the point: forwarded instead,
a word that means nothing binds the free-form shape, which is an answering model call at the
costliest path, a corpus ranked against text carrying that word, and a composed reply saying
nothing about the mistake. A friendlier reply is the argument for forwarding, and it rests on
the pass-through being free, which it is not.

**The backend refuses one too, for a caller that is not this interface.** A command naming no
registered shape is the caller having got a wire field wrong, so both routes that answer
refuse it with the status and key they already use for a malformed request, before any work
starts — which is also what makes the log line true, since the word it reports a request under
is always one some shape answers. A word naming the fallback is not refused: the registry
holds that shape and a caller may ask for it by name, which the evaluation set does. What is
true of the fallback is not that nothing names it but that no command list offers it.

| Stage | What it does |
|---|---|
| Classify | Selects the pipeline, and reads the subject out of the text in the same call. An explicit command binds directly and spends nothing; free text costs one cheap model call, reading the registry's own identifiers and, beside each, the one line that shape declares about what it answers — a command's reader had the list, while a sentence has to be placed against what the shapes do; anything unclassified falls to the free-form path. A command naming no registered shape never arrives: it is refused at the interface's edge with the command list, and refused again at the surface for a caller that is not the interface. A call that did not say what the request is about leaves the subject unestablished, which is not the same as naming nobody |
| Resolve scope | Turns a name into a subject — below |
| Retrieve | Runs the shape's query set, with no model in the loop for the three that read the corpus. Empty retrieval short-circuits before any model call, and so does an exhausted budget |
| Assemble | Byte-stable prefix before the cache breakpoint, retrieved material after it, each span offered under a short number assigned for this request |
| Generate | One model call, sized to the smaller of the shape's ceiling and what the request has left |
| Validate | Mechanical: citations resolve, a shape whose answer is its assertions made one, an alternative a shape offers carries text to send, the variant count matches the shape's contract — asked of the reply and again of what the removals leave — no display text where a message key belongs |
| Render | An outcome of message keys, which the bot resolves against the chat's locale |

### Scope resolution

Every answerable shape starts from a subject, and a subject may be an entity or a
record: a contact, a client, a body of work across several of those, a conversation, or
the inbox, which has no entity behind it at all. Matching runs across all kinds at once,
scored the way reconciliation is scored. A person's handle is matched alongside the names,
and counts as the strongest agreement there is, because one subject carries it.

**A body of work is the one scope nothing stores, and it carries every strand of the
work.** Identity reads one label from one conversation, so a piece of work keeping a
thread per strand of it is several clients and no entity joins them — and a name that
picks out the work matches all of them. What the name resolves to is the work, read
through every strand that matched; the strands stay separate clients and nothing is
reconciled into one. It is derived from the labels for the length of a request, so there
is no row, no identifier and no pass behind it: what identifies it is the leading name the
strands share, and what is shown for it is one of their own labels, because a reduced name
is one no conversation holds.

Two clients of one leading name are a body of work; one is a client already, and a second
referent for a single strand would offer two readings of a name that resolve identically.
Two clients labelled *identically* are two subjects rather than one work — a label cannot
say which of two same-named threads a piece of work owns, which is what the counts already
treat them as — so those keep the menu they had. Strands named differently stay two pieces
of work for the mirror reason: reducing two names to one is a judgement about what a label
means, which belongs to the pass that narrows which named conversations are bodies of work
at all and not to a rule over characters.

**What the rule cannot separate is two pieces of work whose labels begin with one name**,
and that is where it is paid for. A query agreeing with a word two of them use *after*
their leading name — the kind of work rather than whose it is — matches labels that lead
differently, so nothing is reported and the menu stands. A word they share *as* their
leading name reduces to one name and they are read as one body of work: a question about
either is answered across both, with no menu offered. That is the tolerance the count
behind a heading and a contact's parent already carry, and it costs more here — there the
price is a grouping somebody is filed under, and here it is the material an answer is
composed from. It is accepted on the same terms, because the alternative is that a name
picking out several threads of one piece of work resolves to a question nobody can answer;
a corpus whose labels lead with a kind of work rather than whose it is needs the narrowing
pass before any of the three reads is worth trusting. A name broad enough to overflow what
one read returns can also decide it, in both directions: the client that would have kept
two works apart can be cut, and so can a strand of one work.

**One subject is often two records.** A source addresses a one-to-one conversation by the
identifier of the person it is with and names it after them, so the person and that
conversation answer to the same name. They are matched as one subject — the contact, which
is the richer referent, and which the conversation is read from anyway. The test is that
shared record identity and not the names agreeing, so a room somebody named after a person
still competes with them.

| Shape | Subject it accepts |
|---|---|
| #1 | a contact — a reply goes to a person |
| #2 | a contact or a client |
| #3 | a contact, a client, a body of work, a conversation, or the inbox |
| #4 | free text; entity lookup supplements it rather than scoping it |
| #5 | a contact |

**A request arrives having named a subject, having named nobody, or having said neither,
and the three are not two.** A command carrying no argument names nobody, and so does free
text the routing call read and reported as naming no particular subject: for a shape that
accepts the inbox that is the question, and the inbox is reached by it rather than by
matching a name. A routing call that came back without saying — a reply of one line answers
which pipeline and not who — has established nothing, and so has one that wrote a name the
request does not contain, which was not copied out of it.

Only the first of those two absences reaches the inbox. Read as one, the second is answered
as the first: a question about a named person is answered across the whole corpus,
confidently and about the wrong thing, with nothing in the answer, the grounding check or
the log to distinguish it from a sweep somebody asked for.

In order:

- **Named nobody** — the inbox, for a shape that accepts it; nothing is searched for,
  because the inbox has no entity and no record behind it. For a shape that does not accept
  it, a request with its subject missing: its own verdict, and not nothing-found, since no
  name arrived for a search to have run against. Reported as a search that failed it tells
  the reader the corpus holds nothing about a subject they never named, which is the one
  conclusion they cannot check; what is missing is the subject, and they can supply it in a
  turn. It carries neither a name nor any coverage, because there is neither — the cause is
  known here and is stated as a verdict, rather than left to be read out of two empty fields.
  What it says has to hold for both requests that reach it, a command carrying no argument
  and free text read as naming no particular subject, so it asks for a subject without
  presuming a command was sent.
- **Nothing established** — say so, and ask what the request was about. Not the inbox, and not
  nothing-found: no name was searched for, so reporting one as absent from the corpus would
  send the reader looking for a gap in the material rather than restating the question. What
  it says has to hold for every shape that can refuse this way, since any of them that takes
  a subject can and nothing at this point knows which did: restating the question with the
  subject named works for all of them, and the inbox is mentioned as what it is rather than
  as this request's fix — it is the one reading that was never about a name, and leaving it
  out would make that reading a dead end.
- **Unique and acceptable** — proceed.
- **Several and related — compose.** A contact and the body of work it belongs to are a
  hierarchy, not competing alternatives: the narrow subject is answered, the broad one
  is context. A body of work keeping a thread per strand of it is several records of one
  broader subject rather than several subjects, so every strand is context and none of them
  competes with the contact inside it. A name that picks out the work itself composes the
  same way and is the same rule: the work is what is answered and its strands — each a
  client and the conversation that client's label was read from — are what it is answered
  against, so what reaches retrieval is the whole of the work rather than a strand of it.
- **Several and unrelated — ask, with something to answer by.** Two different clients
  matching one name is a menu, and a chat interface can afford a question where a batch
  process cannot. A menu is answered by naming the option that was meant, so an option
  carries the handle its subject goes by where there is one: a label is not a key, and two
  people who share one would otherwise be offered a choice whose every answer returns the
  same question. Where nothing on offer can be told apart that way, it is said rather than
  asked.
- **Unacceptable kind, reachable in one hop — traverse.** A reply requested for a client
  resolves to that client's contacts and re-applies these rules, rather than objecting
  that an organisation cannot receive a message.
- **No match, and nothing spelled nearly like the name either — say so, and say where the
  name was looked for.** Not a guess. That is the
  kinds the search covers, which it declares, and not the kinds the shape accepts: a search
  reaches kinds a shape refuses, and a shape may accept the inbox, which no search reaches.
  So the inbox never appears there. Where there is no coverage to report — a reply holding
  what matched rather than what was covered — the clause is left unsaid rather than said
  empty, which makes its absence no evidence either way. Every request that reaches this rule
  did have a name looked up, so the name it echoes is never absent; the two that had none are
  the verdicts above. One other route ends in this same reply and did not ask about a
  spelling: a name that matched something with nothing answerable inside it agreed with a
  stored label exactly. What both share is that there is nothing to work with, and that is
  the whole of what the reply says.
- **No match, but the corpus holds a name spelled nearly like it — say that instead, and
  carry the spellings.** A search that matched nothing has established that the form written
  is absent, never that the subject is, and the two have different next actions: there is
  nothing to find, against write the name the way the corpus keeps it. Reported as the
  first, the second sends a reader to the one conclusion they cannot check, which is to stop
  looking — the same objection that splits a name that never arrived from a name that
  matched nothing, applied to the case those two stop short of. So where a name was searched
  for and found nothing, the near forms are asked for, and whether any exist decides which
  of the two replies is made.

  The near read is the matching comparison reduced, not a different one — both sides folded,
  anchored at a word's start, words in order — with only each word's opening required to
  agree. It runs where matching found nothing, so it can regress nothing. What it finds is
  **shown and never resolved to**: the agreement is too weak to be a reading of the name, so
  a wrong one costs a reader a glance while the right one is their only route to a form no
  interface shows them. That is also why they are run into a sentence rather than numbered as
  a menu, whose options are things a name was found to *mean*.

  The quantity is measured rather than chosen. On the archive, how much of two names agrees
  does not say whether they are one name: the pairs that are one agree on their opening and
  part after it, while a surname built on somebody else's given name agrees on more and is
  further away. So the opening is the signal and a distance is not, and the width above the
  opening buys nothing in either direction.

**The account the corpus was read through is never one of the competing readings.** It is
who is asking: in a personal archive it carries the reader's own name, so any question
naming part of that name offered the reader themselves beside the person they meant, and a
traversal into a body of work offered them among its people — the account an archive is
read through wrote in everything. It is dropped from the choices and not from the matches,
so a name that means nobody else still resolves to it exactly as any other subject does.

Candidates that lost are carried into generation as labels — name, kind, score — and are
never retrieved for. The answer can then name the alternative it did not use, which makes
a wrong subject recoverable in one turn instead of invisible. The pipeline chooses the
subject; the model is told what it chose against.

**A name is compared in a form that crosses the script it was written in.** The corpus is
Russian and the interface answers in Russian, so a reader writes a name as their own
question writes it, while a source stores whatever script the display name was registered
in — and a label is copied from the source rather than transliterated, so the two forms
share no character. Both sides are folded to one comparable form instead: the letters that
render as several Latin ones, the variants that render one sound differently, and a run of
a repeated letter all reduce, so a name ending in the glide meets the same name without it.
The fold is defined once, in the store, because it applies to the stored label as much as to
the typed name. What it cannot cross is a label holding the English form of a given name
rather than a transliteration of it; such a subject resolves by the surname beside it.

The reduction is paid for in the other direction, which is stated rather than implied: two
names differing only by what it removes are read as one name, and offered as two readings
of it.

The comparison also aligns to the start of a word. The ways somebody under-specifies a
name are to give one of its words or to give the beginning of one, and both of those begin a
word; a short name landing inside an unrelated longer one is a coincidence of letters. What
admitting it costs is not a weak match but a menu nobody can answer — the reader is offered
the person they meant beside a stranger, both under labels that look right.

**A name the sentence inflected is tolerated, by reading twice.** The language the interface
answers in puts a name in whatever case the question needs, while a label carries the plain
form, and the routing stage hands the name over as it was written — a name it composed
rather than copied would be a search spent on nothing. So the strict comparison is tried
first, and only where it finds nothing is it tried again with each word of the name
shortened by what an ending can be and each anchored at the start of a word, in order.
Ordering the two that way is what makes the tolerance cost nothing: a name that resolves
takes the first read and never reaches the second, so nothing that matches can begin
matching differently, and the wider comparison only ever runs where the answer was
otherwise that nothing was found. Below five letters a word is not shortened at all, which
is where a stem stops being evidence and is also where the mid-word fault lived.

**A handle is matched by none of these rules, because it is a key rather than a name.** It is
what a reader names to pick one of two subjects sharing a label, so what it must keep is
exactness:
it is compared unreduced, and anywhere in the value rather than at a word's start, since a
handle has no words and a reader may write only the part of it they remember. Reducing it
would make two handles that differ by one letter into one string, and turn its separator
into a word break — which is to say it would cost the property that makes a menu answerable
at all.

Resolution is the one read that crosses the corpus and the entity layer in a single
operation, because a conversation is a record while a contact is an entity and both are
legitimate subjects.

### Retrieval is fixed for the shapes that read the corpus

| Shape | Retrieval |
|---|---|
| #1 | the thread, the full history with the contact, and an index lookup for comparable past replies |
| #2 | an index lookup over knowledge-base documents, plus client and contact for context |
| #3, one subject | commitments stated, events inside a horizon and events stating no moment at all, attributes with the periods they held over, the recent stretch of that subject's conversations, and the turn each undischarged promise was read out of with the few that follow it |
| #3, the inbox | commitments, near and undated events, and attributes across everything — nothing scopes it, so no thread is read; the turns around each undischarged promise are, which with a closing turn is how a message reaches this request at all |
| #5 | contact history and what was promised |
| #4 | entity lookup, then a provider-side web-search loop |

Three of the four shapes built here read a fixed query set with no model in the loop, which
keeps cost predictable and fixtures deterministic. **#4 is the one that is genuinely agentic,
and it is the provider that makes it so**: searching the open web is a tool loop the provider
runs on its own side, which is also how it reaches the internet without any outbound access
being opened here. That confines `xhigh` effort to one path instead of spending it on all of
them — the other four and the fallback share one sizing, because they do the same kind of work
on the same kind of material, and a per-shape effort would be five numbers nobody could tune
against each other.

The free-form fallback reads a ranked lookup over the index rather than driving a loop of its
own. Putting the corpus inside a loop needs a model call that can be handed a tool this system
implements and resumed with its result; [`ModelProvider`](#the-answer-path) offers the
provider's own web search and no such entry point, so widening it is a change to the model seam
rather than to a shape.

**That fallback answers the broadest question the system can be asked, which makes its reply the one
most in need of structure, so it is asked for a lead and for an order.** A question naming nobody is
answered from material the ranking reached across every conversation, which sections by body of
work; without those two rules the reply carries its findings under a heading each, with nothing
above them and in whatever order relevance produced. Neither is a fault anything downstream can see
— every finding is cited and faithfully read, so the grounding gate passes the whole reply, and what
is wrong with it is only that it cannot be read. So the order is computed as well as stated, as what
is owed is: the stretches the ranking returned are offered newest first, each hit still moving with
the turns gathered around it, because sorting the turns themselves would interleave several
conversations into one apparent thread. A position therefore carries recency across those stretches
and not inside one, where the gathered turns read forwards — which is why the instruction sends the
model to the date on each block rather than to where the block sits, and why two stretches of one
conversation that overlap are folded to what each still carries before they are ordered.

**Because that one shape runs for minutes, an answer is asked for and collected separately.** The
interface posts a request, is given a name for the work, and comes back for it until there is
something to say; the backend runs the work outside the request that asked for it, holds it under
that name, and hands it over once. An interface waiting inside a single request would have to bound
that wait, and any bound wide enough for a tool loop is also how long a backend that has stopped
answering may hold the interface — so the two are separated instead, leaving each hop short enough
that silence is noticed quickly. The interface still stops waiting eventually, but it does so
without a request held open, which is what decouples the two bounds.

A failure during the work is raised where it is collected, so one status mapping serves both
however the request arrived. The single-request route stays, because a caller holding its own clock
wants it: the evaluation set reads the answer path that way.

**Giving up because nothing answered and giving up on work that was answering are different
failures, and the reader is told which.** Hops that stop landing is a backend that has gone away,
which renders as the key the service itself uses when something it depends on is not answering:
that state passes, so coming back later is the advice it carries. Waiting out the budget while
every hop landed is the other thing — nothing was down, and the work was accepted and unfinished
at each check — so calling the service unavailable would be false, and coming back is the action
least likely to help, since a question that overran may overrun again at the same cost. That one is
the interface's own fact rather than anything the service reports, so it travels to the renderer as
a case carrying no wire key and is worded from the interface's own vocabulary. An interface that
waits differently words it differently, which is why the key is not in the format every interface
reads.

**Which bound ran out is the operator's question; whether every hop landed is the reader's.** They
are not the same, because an answered hop clears the consecutive count the allowance is measured
on — so a backend flapping beneath that allowance, or dying with less than it left on the clock,
runs out of time rather than of hops. The reader's message is the one asserting a service that was
answering, so it is owed the stronger condition: any hop that did not land is told as the backend
being unreachable. A blip therefore costs a genuine overrun its own wording, which is the safe
direction to be wrong in — being vague about a cause costs the asker a wasted retry, and being
wrong about it costs them the one action that would have worked. What the message does not claim is
why the answer took so long: the interface cannot see whether the work was large or was repeated
after a reply that could not be read, and those call for different things from the asker.

**An attribute is answered with the period it held over, and the period is in the material rather
than applied to it.** A question about what holds is answered from the statements the recognition
pass wrote, read without reference to the clock. Filtering to what holds now would hand the
composition a bare value and no way to say as of when, and it would make a question about what
used to hold unanswerable from the same read.

The reason it has to be in the material is that the alternative fails silently. A value taken
from the wrong stretch of time is a real fact with real evidence: it cites correctly, so the
grounding check passes it, and what comes out is fluent, specific and wrong about when. Nothing
downstream can catch that, so the window is put in front of the model and the shape's instruction
says what it means — the same move the hedge marker already makes for a commitment.

Three things follow. A window with no end means the fact has not been contradicted since it was
stated, which is weaker than being true today, because nothing closes a window except a later
contradicting statement and a fact going unmentioned is not evidence it stopped holding. Two
windows giving one attribute different values are a fact that changed, and both are stated with
their periods rather than one being chosen. And how strongly an attribute may be put is its
origin: a model's reading of hedged text, a field a source recorded, and a correction a person
made are three strengths, worded apart so the weakest cannot be reported like the strongest.

**A conversation reaches no attribute.** A statement's subject is a contact, because only a fact
stated about its own author is stored — so a thread reaches no attribute a contact does not, and
every attribute stated in a thread is about whoever spoke rather than about the client the thread
stands for. Read by conversation, a question about a client would be answered with facts about its
participants.

**A finding is shown with the party that stated it, named from the entity layer.** Recognition
records that party as a contact identifier and never as a name, so a reader-facing name is this
layer's to supply: the retrieval port resolves the identifiers a page of findings reaches to
the names the corpus gave those contacts, in one read after the findings are in hand, and the
name is rendered beside the claim rather than left to whatever label a restating model wrote
into the claim prose.

**The name beside a finding and the names inside its claim are different facts, and neither
replaces the other.** The one here is who stated the thing, taken from the entity layer. The ones
in the claim are whoever owes what to whom, written by the restating stage from the labels its
window carried, and that prose is what a reader sees and what a citation is checked against. The
two coincide for something undertaken first-hand and diverge for anything relayed, which is why a
finding carries both rather than one being dropped as a duplicate of the other.

Which party a commitment obliges is recorded as a reference beside the claim as well, so that
direction is a value a query can reach rather than a sentence to be read. The shape answering where
things stand reads it with the party that stated the thing and marks both on the material, so an
answer names the party an undertaking is placed on instead of leaving it to be read out of the
words. It is empty where the window could not place that party, and there the prose is the only
account of direction there is.

A party takes its own account's name where it has one and its human's otherwise, resolved through
the merge as every other read on that port is. An account that states something often carries no
profile name while another account of the same human does, so naming only the account would drop a
name the store holds; and the merge is trusted here because it is already trusted upstream — the
finding is in the answer at all because the narrowing resolved the subject to a whole cluster, so a
wrong merge misattributes the claim before the name is reached and withholding the name would not
undo it.

Where neither the account nor its human is named, the answer says what was undertaken without
saying who by — which is what the restating stage is already told to do, so the two stages agree
instead of one inventing a placeholder the other would not recognise. Only contacts are named this
way: an attribute's subject may be a client, which is named by the conversation it was read from
and is reached by a different read, so an absent name there is a structural limit rather than a
person nobody named.

**A retrieved turn names its author, and whether the shape sections its answer has nothing to do
with it.** A turn offered as material says who wrote it where the corpus names them and carries no
name otherwise, for the reason recognition's own lines carry a name: a claim is kept only if it
survives being read on its own, which is impossible where the model cannot tell who said anything. A
shape following the threads a person appears in reads rooms they merely write in, so a stretch
offered unattributed leaves the sender and the subject as the only agents available, and other
participants' words are read as the subject's. Nothing downstream sees that: the grounding gate asks
whether a cited span was retrieved and not whether it was the right span, which is the same
limitation a closing turn's instant works around, reached through authorship rather than through
time. A name is what makes attribution possible and does not on its own say whose business a turn
records, which the marker below is for.

**A turn asked about on somebody's behalf also says whether neither that person nor the reader
wrote it.** Most of a subject's threads are rooms, so the tail of them carries matters the subject
took no part in, and a turn offered with a name and nothing else is composed into a finding about
where that person stands — fluent, correctly cited, and an answer to a question nobody asked. The
fact is marked on the material rather than left to the instruction, because a rule telling one
participant from another by name is unanswerable on a corpus where a display name belongs to more
than one person and the form a question writes a name in is frequently not the form the store
holds. It is resolved on the contact and through the merge, so it answers per human rather than per
account, and the comparison rides the walk that names the author rather than costing a read of its
own.

The question is three-valued and the absent answer carries every case nothing settles: a shape that
named no subject, a turn the corpus names no author for, one whose author no contact has been
reached for, and one whose account nothing has placed as the reader's or not. The owner's own mark
folds its unknown into a denial, which is the only answer a composed reply can act on and the wrong
one here — intersecting the two would publish an account whose adapter never reported itself as
somebody else's, demoting the reader's own half of the relationship out of the answer. So the
combination is made where every state is visible, and the two halves never meet as separate flags.
What the marker says is who wrote a turn and not whose matter it reports: a third party acting on
the subject is that party's doing and the subject's situation at once, so the instruction carries
the judgement and the marker carries only the fact it can resolve. It is rendered on a closing turn
as well as on a retrieved one, since once the mark exists its absence says the subject or the reader
wrote the turn.

**Nothing marks who did work a message reports as already done.** A turn's sender and a
finding's stated-by party both name who *said* something; the obliged party is the one marker
that names an actor, and it names who is to carry an undertaking out. A turn reporting work —
a status, a summary, a handover — recounts what several participants did, and whoever wrote it
is frequently not the one who did any of it; a finding carries the party of the message it was
read out of, which is not always the participant whose words it restates. So the shape
answering where things stand is told, for work reported as done, to take the actor from what
the sentence says, to report work whose actor is unnamed without supplying one, and to keep
each participant's part when several messages are compressed into one finding. Scoping it to
finished work is what keeps it clear of the rule naming the party an undertaking is placed on.
Nothing downstream can see the error either: the sentence is fluent, the citation resolves, and
the grounding gate asks only whether a cited span was retrieved. The three other shapes that
read turns carry the same attribution rule and are exposed to the same reading; the rule is
seated where the defect was measured, and widening it is left to the cards rewriting those
shapes' prose.

**A finding whose own content is a question can be answered by a later turn that nothing
marks.** A closure is a message reporting a promise done, so an item asking who did something
or which option to take carries no closure however plainly the thread settles it, and renders
as having no recorded outcome. The same shape is therefore told to report what a later message
settled and cite it, rather than relaying the question to a reader the record shows has nothing
left to chase.

**What a commitment row cannot carry at all is whether anybody agreed to it, and that is why the
shape reads the turn behind each one.** A claim is restated to stand on its own, and the restating
stage is asked for a sentence naming who owes what to whom — so something one party merely put to
another arrives as a flat assertion that it will happen, with nothing on the row saying which it
was. Three things a reader needs telling apart therefore collapse into one rendering: what somebody
proposed, what one party undertook, and what both sides settled. The store holds the middle one and
can express neither of the others, so **the distinction is recovered from the material rather than
added to the row or asked for in prose**: the turn a promise was read out of states the thing in the
speaker's own words, and the turns after it are where the other party took it up or turned it down.
Both come from the index, keyed by the span the finding carries, looking forward only — what
precedes a proposal is what its speaker was answering, which is a different question. The
alternative, a rule telling the model to hedge every claim, asks it for a judgement the material it
was given cannot support.

The cost is bounded by the promises rather than by the window: the turns are read only for the ones
nothing records as discharged, since a closed promise already carries its closing turn, and a turn
two promises reach is offered once. A record the request has already read is kept as the copy it
read, because the index holds whatever the last pass wrote and an edited message differs between
the two — one identifier carrying both bodies is a citation that resolves to neither.

Two consequences are worth stating because neither is visible from the change itself. The material
is no longer bounded by the tail's recency window: a commitment carries no horizon by design, so the
turn behind one agreed months ago arrives with it, dated by its own block heading like any other.
And **this is a further reason that shape's instruction states the owed-first order as something to
write rather than as a fact about the material** — a finding is gathered into the block of the first
turn that reaches it, which is its own evidencing turn only where no earlier promise's window
already covered it, so the computed order reaches the prompt partly reordered. Each item's own
standing label is what carries it.

The window looks forward only, and is sized from how many turns pass on this corpus before the
other party answers rather than from the one refusal the defect was read off. It is set at the knee
of that distribution: past it the window grows faster than the share of answers it reaches, so a
proposal answered much later is missed rather than paid for on every promise in every request.

**What transfers from recognition's rule is the name and not its usability test.** There a label is
compared back, a submission later, to decide what an attribute is about — so a name two of a
window's authors share is withdrawn from both rather than filed against whichever is reached first.
Here the name is rendered to a reader and compared to nothing, so the reason to withdraw it does
not arise; what a shared name costs is a reader who cannot tell two people apart, which is the
ordinary cost of the corpus's own names. A finding's party is named on the same terms.

A finding's party is a contact identifier the derived layer minted, so naming a turn's author is a
read beside that one rather than a widening of it — and it is **keyed by the record rather than by
its author**, which is what lets every turn be named. A shape reading the tail of a conversation
holds each message and so holds its author; a shape reading the index holds a span, whose identifier
names the record and nothing else, because the index stores no author. Keyed by the author, the read
would serve the first kind of shape and be unusable to the second. The name itself comes through the
same contact and the same merge, and is asked once after the turns are in hand.

A record whose author the derived layer has not reconciled has no contact and so no name; a turn
whose source recorded no author has none; and neither has one whose contact and whose human are both
unnamed. All three are the unnamed case rather than separate ones, because a reader cannot act on
the difference. Attribution is not sectioning: labelling a turn with its author divides no answer,
so a shape producing one sendable message carries the author while carrying no headings.

**A turn written by the account the corpus was read through says so, wherever that turn is
named.** What needs it is an answer addressed to one of the parties, and the mark names which
of the two that party is to the answer: somebody to compose as, or somebody the answer is
handed to. A reply composed from the wrong side is fluent, correctly attributed, and
unsendable; a brief that does not know who is reading asks the other side to confirm what the
reader is holding. A shape writing *about* the corpus to nobody in particular names every
party in the third person and reads none of this. The mark extends the author's name rather than
standing beside it, so one clause still says who wrote a turn — which is also why the unnamed
case above absorbs the owner's own unnamed turns: with no name to extend there is no mark to
carry. It is on the material for the same reason the register marker is, and a corpus where no
account is marked is an ordinary state the instruction answers for rather than a gap.

**A turn also says who else reads the thread it was said in, which is a different fact from which
thread that is.** The identifier says which, and is what tells a subject's own material from
material a ranked read found for its wording; this says whether a message sent there reaches one
person or a room, which only a shape composing a message to send has any use for. It is required on
a retrieved turn rather than defaulted, because there is no safe value to leave it at: a room taken
for a one-to-one thread is a reply addressed privately to somebody in front of everybody else in
the room, and that is a failure a default would make silent. For the same reason every reading of
it fails towards a room — an unrecognised container, and a turn a read did not reach — since a
private thread treated as a room costs a draft an audience it did not have, where the reverse is
the failure itself. Two cases rather than one per container the sources distinguish: what a reply
turns on is who is present, so a topic inside a supergroup and a broadcast channel answer alike,
and the taxonomy stays on the record where it belongs. A room the corpus holds no name for is a
room all the same, kept apart from a private thread rather than folded into it, and what is then
missing is which thread to send to — which the instruction has an arm for rather than asking for a
name anyway. Where the thread is one-to-one the material says nothing, and the instruction is what
gives that silence its meaning, a clause on every turn of every thread being a line of prompt per
message to carry a fact the model is told once.

**A turn also says whether it is one of the latest exchange's, which the retrieval decides rather
than the model.** It is the one clause a shape asks for rather than a fact the record carries: only
a composed reply has a live exchange to find, the shapes that write *about* the corpus answering
over the whole window they read. So it is rendered for that shape alone, and on the turns of the
exchange alone — a clause on every turn would tell a reader nothing, which is the problem it exists
to solve stated a second way. It is appended after the author and the room so that the author's name
keeps leading the block, which is what the attribution and voice rules are written over, and it
carries a leading and a joining form like those two so that whichever is present can open the block.
What the clause claims is bounded by what the selection establishes, which is why it says the latest
exchange in a person's threads rather than with the person.

**Material retrieved for how it reads is marked as that, and is not evidence about the subject.** A
shape that widens with a ranked lookup for comparable past exchanges is asking the corpus how
things have been worded. The marker denies the reading rather than qualifying it, and it is on the
material and not only in the instruction, because a model told one thing and shown another follows
what it is shown. The author is named beside it all the same, and the two say different things: the
marker says this is not evidence about the subject, and the name says whose wording is being shown.

**Which turn that marker applies to is decided by where the turn was said.** A ranked read is
corpus-wide and its question is the subject's own recent words, so its nearest neighbours are
frequently that same subject's older turns — the ones the message read's window left behind. Marked
as register those would be offered under an instruction forbidding the model to state or cite them,
which suppresses the subject's own history while it sits in the prompt. So a turn from a thread the
request read is this subject's material however it was reached, and only the rest is register. A
thread the request did not read is not reachable that way and is marked with the rest: the bound on
how many threads a shape reads is the bound on this too.

**A drafted reply answers what the other party left open, and the instruction has to say so because
nothing else does.** Every other rule a draft is held to — the register of the thread, two or three
that genuinely differ, no placeholders, nothing stated beyond the material, and which side of the
thread it is written from — is satisfied in full by a summary of the sender's own earlier turns. That
summary is fluent, correctly attributed and grounded, and the other party was present for all of it,
so it moves nothing on and nothing downstream can see the difference: a draft carries no claims by
design, so the grounding check has nothing to bite on. Where a thread leaves nothing open the rule
does not lapse but inverts — the drafts say less and come back close to one another, because
inventing something to answer re-opens what was settled and asks the other party to decide it again.

**The sender's own turns are offered for two purposes, and the instruction states both, because
stating only the first is what makes the restatement the easiest draft to write.** They are the
register the shape is learning, and they are also the record of what the sender has already said.
The register examples carry an explicit rule bounding them to their wording; with no matching rule
the sender's turns are wording to imitate and content to compose from at once, and that content is
what the other party already holds. The two are stated apart rather than equated, since a register
example is also forbidden to be replied to, stated as fact or cited, and a turn that is both the
register and citable evidence cannot inherit those prohibitions.

**What the rule is keyed on is the conversation a turn was said in, which is weaker than a claim
about the whole material and is the claim that is true.** Only the route through a contact selects
on authorship, so a thread reached through a client or a named conversation may be one the
counterpart never wrote in, and a register example comes from outside the followed threads
altogether. Asserting the other party was present for everything marked as the sender's own would
be false on those routes, and false in the direction that withholds material its readers never
received — the defect inverted. It is also the question the rule needs, because a draft is composed
for one conversation and most of them here are rooms: what the sender said in a thread this one's
readers were not in is not something they have.

**What is bounded is re-delivery and not reference.** A reply has to be able to name the thing it is
about, so a flat prohibition on mentioning anything already said is one the correct draft breaks —
and a rule the correct answer breaks is discounted wholesale rather than obeyed narrowly. So naming
the thing in as few words as the sentence takes is allowed, restating its content, its list or its
detail is not, and a fact something since has put back in question is the exception and is stated
afresh. Each draft is required to carry something its readers do not have, which is the same rule
stated as a thing to do: told only what not to send, a model sends the nearest thing outside the
prohibition. The bound is carried into the nothing-open case rather than handed off with it, because
the rule for that case bounds a confirmation's length and not its content, and the thread with
nothing obviously open is the one that invites the summary in the first place.

**The drafts differ in how they say it and not in how much of it they say, and the invariant needs
stating because the rule asking them to differ is answered most cheaply by dividing the material
up.** Everything the reply owes is in every draft, so a sender can send whichever reads best and
lose nothing another one had. Drafts that partition the facts are each well-formed by every other
rule the shape carries and are no alternatives at all: choosing one drops a fact, and the reader is
left writing a further message out of the set, which is the work the shape exists to do. The axis is
bounded away from what a draft commits to, and that bound leaves the material as the remaining way
to differ, which is why the two are one rule. Of the axes it does leave, *how much they explain* is
the one that reads two ways — how much is explained, or how much there is to explain — and the rule
names the first. It also names what the difference positively is, briefly or at length and plainly
or warmly, because the paragraph above forbids variations of one sentence and a rule demanding the
same facts beside that prohibition is a prompt that forbids a reading and then asks for it.

**What is owed is the test, and it reaches past the exchange being answered**, since a request
further back that nothing has met is still owed. Keyed on the marked exchange alone the invariant
would leave a partition across exactly the material the sibling rule admits, so it names both
sources. **The whole rule is conditioned on the thread leaving something open**, which is what
keeps it clear of the nothing-open case: the same short message at two lengths is the honest answer
there, and that is a difference in content, so any sentence left standing over the drafts at large
would forbid it — a single clause disclaiming the owed half would not reach the ones about dividing
the material. The condition opens the rule and its close repeats that none of it applies, which
leaves what a draft says on a settled thread stated in one place. The sentence naming the kinds a
draft is built out of offers them in a disjunction, which reads as a choice to make per draft; the
enumeration is what makes it usable, so it carries what it is — kinds to look for, not alternatives
to pick between — under the same condition, which keeps *what has changed since* from being
required of every draft on a thread that is settled.

**All of the drafts are aimed at one thing, which is the same defect reached through the clause that
keeps the rest of the material in bounds.** That clause holds: a request further back that nothing
has met is still owed. What it does not say is where such a request goes, and a draft that takes it
as its subject while the others answer the marked turns is no alternative to them but a second
reply, offered as a choice — so an older request is carried inside the reply to the marked turns
rather than instead of it. Where the marked turns leave nothing open and the older request is the
only thing to answer, it is what every draft answers: stated unconditionally the rule would forbid
the one sendable reply on that thread, and a rule the correct answer breaks is discounted wholesale
rather than obeyed narrowly.

**What makes that inversion fire is what the latest exchange still leaves waiting on the sender,
and not which party wrote last.** The other party writes last on an active thread, so a test keyed
on the sender having written last cannot fire in the ordinary case at all; that party having written
last is one way the condition is satisfied rather than the test for it. Three things bound it, each
of them a reading the condition would otherwise invert: a question the sender has already answered
is not one still asked, an undertaking they gave and have not reported on is still theirs to write
about however little the other party asked for, and a question put to somebody else in a thread
other people are in is not put to them — so an addressee is what the instruction sends the model to,
not a sender. The count is not narrowed for the case, because the runner holds every reply to the
shape's range: what the instruction names instead is the alternatives, one short message at
different lengths. That the exchange asked nothing is reported to the reader in a cited claim line,
which is what keeps a short pair of drafts readable as the answer rather than as a thin reply.

**Which turns a reply is aimed at is selected where it can be computed, and marked on the material,
because a draft's material is ordered by relevance and not by time.** A shape reading several threads has
them concatenated closest-first with nothing rendered between them, and ranked material is appended
after the whole window although it reaches further back than the window does — a thread longer than
the window being the ordinary case rather than the exception, since the window is a bound on how much
of one a shape reads. So the oldest block offered is routinely the last, and position says nothing
about what followed what. A rule reading the material forward would find its unanswered question in
a quieter thread or behind the window and answer something settled long ago; one sending the model to
the instant on each block instead asks it to order every heading in the prompt to find a single turn,
which was measured answering a request the thread had already moved past. So the retrieval picks the
thread holding the newest turn and, within it, the turns from after the sender's own turn that
precedes the newest one not theirs — everything they have not answered — and renders a clause saying
so. The turns a blank body keeps out of the material still count towards that boundary, a sender who
replies with a file having replied. What the mark bounds is where the reply is aimed rather than what
it may answer, so a request further back that nothing has met is not excluded by it; and what it
follows is recency across the threads retrieved for a subject rather than which of them is with them,
which is why the clause claims the latest exchange in a person's threads and not with the person.
Every block still carries when it was said, for everything the mark does not settle. Where no turn
carries the sender's own mark, which side answered what cannot be told at all, and the mark says
where the thread's live end is and no more.

**A fact is only as current as the thing it was observed on, and a draft states as current only
what the material still supports as current.** The material carries a reading and the turn that
superseded it alike, and nothing but the dates separates them, so an observation taken before the
thread rebuilt the thing it was taken on is not a statement about the present. The condition is
what the thread says happened to that thing rather than whether anybody called it lost: a rebuild
and a list of what came back is the case the thread does not state in so many words, and a rule
keyed on the wording of a loss reaches only the case that was never the difficulty. The verbs that
describe such a turn are illustration under the condition and not a list to match, for the reason
the shared rule about an undertaking's outcome is written that way — a prohibition satisfied by
reaching for a word nobody enumerated is not one. Two ways out are offered and both are
statements: when the reading was taken, or that nothing since has confirmed it. Offering to go and
check is not among them, because it is an undertaking the sender has not made and another rule
here forbids originating one. The note belongs in the message in the sender's own voice rather
than in a claim line, since it is addressed to the counterpart rather than being a remark about
what the record holds, and a claim line it could not cite would cost the whole alternative. The
same rule refuses the rounder statement the material suggests, a list of parts restored not being
everything working and one lifted restriction not being none of them, worded as saying what the
material says rather than as a demand for precision the thread's own loose register would fight.
It is this shape's rather than the shared format's because the remedy is: elsewhere the answer
dates a finding it cites, and here it is how a sentence somebody sends is worded.

**A word that was only meaningful on the day it was written may not be relayed as though it still
held, and every shape is told so in the format they share rather than in any one instruction.**
*Tomorrow*, *this evening*, *during the week* place nothing in material that is weeks old, and
because such a word reads as a specific commitment it is worse than an acknowledged gap — the
staler the undertaking the more confident it sounds, which inverts the signal exactly where it
matters. The rule is to work the reference out against the date at the head of its own block
rather than against today, and where it works out to nothing, to give the date the message was
written and say that no day was named. It is in the shared format because the shapes that offer
turns produced it alike; one happened to name the gap instead, generalising a rule it carries
about unnamed authors, and nothing in the build produced that behaviour reliably.

**Its antecedent is the quoted message and nothing else, which is the whole difficulty.** A block
carries three kinds of time: the words a person wrote, relative to the day they wrote on; the
phrases the material layer computes — *today*, *now past*, *last stated so many days ago* —
relative to the present, which being re-dated to a block header months old would throw out by
months; and the times that layer prints inside the text. Stated
over the material a rule of this kind reaches all three, so it names the one it means and excludes
the other two outright. A drafted reply is excluded as well, for the reason its claims are: its
reader is the counterpart and it is read when it is sent, so a relative word in it is ordinary
correct prose.

**Which is why each block is headed in the correspondents' own zone rather than in the instant's.**
The arithmetic is a day out for a message written in the hours where the local day and the UTC day
disagree — just after midnight where the zone is ahead of UTC, late evening where it is behind, and
the error runs opposite ways. How much of a corpus sits in that window is a property of the corpus,
read off the source times of the messages carrying such a word. Nothing can read the zone: an
instant carries none, the platform reports a reader's language but never their zone, and inferring
it from the hours people happen to write in pins no single zone. So it is a deployment's to name,
and UTC otherwise. The clock the shapes read is an instant and carries no zone of its own; what the
present moment is printed as, and the day boundary a commitment's standing is decided against, are
that named zone, so no two times a reply prints fall on different days.

**A time is said one way wherever it appears, which is that same zone and that same form.** A reply
dating one thing two ways gives a reader two figures and the one they can check is the one that
disagrees — so the heading over a block, the time of a closing turn printed inside one, how long ago
a commitment was last stated, a commitment's due date, an event's moment, how far past a deadline
stands, where that date stands at all, the present moment stated at the head of a request, and the
dates the interface cites a reply's material by are all the correspondents' calendar. A day count
follows from it rather than from elapsed periods, which come up one short for every message written
later in the day than the hour a question is asked. A deadline is not a message's time and is said
this way regardless, because it is printed beside one and the two figures a finding carries — how
far past a date is, how long since the thing was mentioned — exist to be weighed against each
other: counted on separate calendars, a deadline and the message mentioning it falling at one
moment report figures a day apart, and a commitment falling due today where the correspondents are
is grouped as overdue. The interface's one sentence about how current the corpus is keeps counting
elapsed hours below a day, since currency and not a calendar is what it answers.

**The heading names the weekday as well as the date, because the arithmetic cannot be done without
it.** Working *on Wednesday* out against a block written on the eighth means knowing what day the
eighth was, and a date alone leaves that to be inferred — which was measured on the archive going
wrong: one resolution in six landed a day out, and two answers placed the same meeting on
different days. The name is spelled out in code rather than asked of a date formatter, which
resolves weekday names through a locale and would otherwise vary the prompt with whatever the host
is set to.

**This settles which calendar a time is read on and not which instant was stored, and the two meet
in one place worth naming.** Recognition resolves a stated date to the start of a day in UTC, so a
date-only deadline is the UTC encoding of a calendar date. Read on the named zone where that zone
is UTC or ahead of it, the encoding keeps the date somebody stated. Read on a zone behind UTC it
lands on the previous day, and because the standing and the day count come from the same reading,
the group, the figure and the printed date move back together rather than disagreeing with each
other — self-consistent and a day early. Reading a deadline on UTC instead would keep that date and
give back the disagreement with the age beside it, which follows the messages. The two agree at
every zone only once a stated date is resolved in the named zone, which is recognition's to do; no
choice in the answer path has both.

**A finding has to name the thing it is about, and every shape is told so in the format they
share.** A sentence that is accurate, cited and built on a pronoun or on a shorthand the
correspondents use with each other names nothing to somebody who has not read the conversation, and
a brief read minutes before a call is exactly where there is no time to go and find out. The rule
has two halves, because only one of them is available at a time: where the turns that resolve the
word are in the material — and a shape retrieving a conversation's recent tail alongside its
findings routinely has them — the answer resolves it, and where they are not, the answer says the
record does not name the thing rather than relaying the sentence or supplying a subject that fits.
The correspondents' own shorthand is permitted where it is also explained once, since it is
frequently the most precise name a thing has.

**Which layer it belongs to is settled by the shape that has no extraction behind it.** The
fallback retrieves a ranked read of records and reads no finding at all, and it produces the same
symptom — so the referent is not merely being lost where a claim is restated inside one window, and
a rule seated in one shape's instruction would leave the others free to relay the shorthand. A
drafted reply is excluded as it is from the relative-time rule and for the same reason: its reader
is the other party, whose shorthand it is. What a draft reports about the material is held to the
rule, which is where a claim line falls.

**A status answer says where things stand, and the instruction has to say so because every rule it
carries about an individual line is satisfied by a recap.** Cite each item, date it where the
material dates it, never call an unclosed promise open, prefer a list to a narrative — all of that is
true of a line reporting that somebody raised a question or explained a position, and an answer
assembled out of those lines is cited, dated, honest about state and not a statement of where
anything stands. It sends the reader back to the thread the request exists to save them, and nothing
downstream can see the difference: every claim in it resolves, so the grounding gate passes it. The
rules a recap does break are the ones about the answer as a whole — its parts in order, and what is
owed grouped by standing with the ones now past leading — and those are asked for and not thereby
obeyed, which is why the order is computed as well as stated. So the answer is asked for as a bottom
line first — whether anything needs attention, or that nothing does — then what was undertaken, then
what moved. Leading with the conclusion rather than the activity is also what a brief written for
somebody who has to act on it does, and on a phone it is layering rather than ordering: the adapter
splits at the largest boundary that fits, so the first message is what gets read.

**The brief read before a call states its length as a count, because brevity asked for as a
judgement loses to the rest of the prompt.** Every other rule that prompt carries adds material —
the account, the questions, a standing on each item, a party named on each undertaking — and all of
them are obeyed, so brevity asked for as a judgement is outvoted by instructions that are
individually right. The bound is a number of items and of questions, over the whole brief rather
than over a section, since the material routinely sections by body of work and a per-section
reading licenses most of the length. It opens with a lead of two sentences, one for where the
relationship stands and one for what the call turns on, which asserts and cites like anything else
in the answer. What keeps the cut deterministic is the standing marked on each item rather than
where the item sits — the two come apart for a commitment evidenced by a turn still inside the
message window, so the cut is sent to the label as the ordering rule is. And what the bound may not
buy is stated with it, because a cap is met as readily by folding two items into one sentence, by
dropping the citations that make one checkable, or by letting what survives read as everything
retrieved — each worse than the item being absent, and none of which anything downstream can see.

**What orders that brief is what was asked, and where that says nothing, the kind of each matter.**
The shape takes a contact and nothing else, so a brief covering the whole relationship is a
defensible answer to a request naming only a person, and guessing which half of a relationship a
call concerns is not an improvement on it. The words the reader typed do reach the model, and where
they name a matter beside the person that is the only statement of purpose anything in the request
holds: the account leads with what bears on it, and every question is drawn from there. **That arm
reaches as far as the interface passes those words through as text and no further** — one that takes
everything after a command word as the name to resolve sends a matter named beside the person into
subject resolution, where it reads as part of the name, so the arm bites on free text and the other
one carries a typed command. Where nothing says what the call is for, the kind of each matter is
what is left to go on. The work two people do together is what a working call is for; an arrangement
between them as people is something a reader taking one has nothing to do about, so those are
reported last within the part of the brief they fall in, seed no question, and are left out by the
length bound's own precedence rather than by a rule of their own — confined rather than refused,
since a record holding only matters of that kind is then the whole of what the call can be about,
and a brief emptied of them would be no brief. The kind is a reading of each matter and not of where
it was read from, a one-to-one carrying a person's label whatever the two of them discuss. So the
prompt states three things that cannot be left to follow: that such an item goes last inside the
part it falls in, that it heads nothing, and that the order by standing holds within a kind rather
than across the two — because what a section is headed by admits a body of work or a person and
refuses a topic, and a second grouping over the same material leaves the model to pick one of them.
**And a matter the record reports as done is not an item at all**, unless something with no recorded
outcome turns on it, in which case what it bears on is said: writing one up so that the brief can
say it is finished spends an item of a bounded few on the one thing there is nothing to do about.
The closure read makes one of its two shapes visible and is read so that nothing finished is chased;
the other is a matter the messages themselves carry the end of, which no marker records. Reported as
done rather than settled, which is the vocabulary the store holds — what a later message evidences
is a report, so a rule written over what the record settles would ask the answering model for a
determination nothing upstream makes. What the rule drops is named in the line that already stands
in for material the brief does not write out, since a rule that only removes trades a brief that
says too much for one that reads as having found nothing.

**What is owed is ordered before it is offered, and the order is not the one the store returns.** The
commitment read orders by recency of evidence, which says nothing about what has fallen due — a thing
agreed three months ago for last week being exactly the item that goes unnoticed. So the answer path
re-orders: now past, the furthest past first, then today, then ahead, then the undated, and whatever
is recorded as discharged last, because a promise discharged late is finished and grouping it by its
date would put settled work back at the top. **The order is computed and still stated in the
instruction, which is not redundancy.** A finding is addressed by the span it was read out of and a
shape offers its messages before its findings, so a commitment evidenced by a turn still inside the
message window is folded into that turn's block and shown at its position whatever the sort produced.
Position therefore carries the order for the findings whose turns fell outside the window and not for
the rest, which is why the standing is a label on each item and the instruction sends the model there.

**Where two readings of one undertaking reach an answer with outcomes that do not agree, the answer
reports it once and reports it as done.** A restatement is rewritten on its own, so one obligation can
stand as two rows — one carrying a deadline with no verdict against it, the other carrying a closure
with evidence. Offered as two items and grouped by standing, they compose into one reply as overdue and
as discharged, and both halves cite spans that exist, so the grounding gate passes the pair as readily
as two real findings. The collapse settles such a pair where it reaches it; what leaves one in front of
a reader is that it runs inside the inference pass, which a deployment may have switched off, that its
candidates are bounded to one conversation and kind within the collapse window, and that a pair the
vectors score below the asking threshold is ruled out without the model tier being bought. So the
answer path reconciles rather than assuming the store has. It reconciles the way a merge does — a
found closure outranks a verdict that found none, because each verdict reads forward from its own
reading and the one restated later was never read against the message that closed the thing, which
makes the closure evidence the other reading missed rather than a second opinion. Asking the model for
that pairing is what the comparison needs: two differently worded sentences share no lexical signal,
and a shared conversation or a shared obliged party holds for two genuinely different promises as
readily as for one restated. The ask is therefore bounded to one undertaking read twice, and a model
that cannot tell the two cases apart reports them separately — because the costs are not symmetrical:
a pair wrongly joined reports an undertaking as done that nothing records as done and loses it
silently, where a real pair left unpaired is the contradiction a reader can see.

**Two facts the record holds about an undertaking are rendered into the material a reader's answer
is composed from.** Which party a claim obliges is marked beside the party that stated it, so an
answer about what was undertaken names the party it is placed on rather than leaving direction to be
read out of the claim's prose — the two being routinely different people, since an obligation one
person states about another is recorded as a commitment. The marker is worded as what the claim does
rather than as what anybody is still carrying, so that it can be rendered on a discharged commitment
too: direction holds whatever became of the thing, and a wording reading as *owed* would assert on a
finished item the one state nothing here establishes. And an item with no recorded outcome says how
long ago it was last stated, and a past one how far past it is: that an outcome is unrecorded answers
whether the field is filled, while how long the thing has sat unmentioned is what says whether to
act, and without it an undertaking three weeks old and one from yesterday read alike. Both figures
are computed rather than left to be worked out from the instants in the prompt, because date
arithmetic across a prompt is where a relative expression gets relayed as though it were still
current. How far past a deadline is counts the days between two dates, where how long ago something
was stated counts the time since an instant, because that is what each of the two is a question
about. Both are asked of the answer about where things stand and of the brief read before a call,
which are the two that turn on who a thing is on and how long it has sat; neither is asked of the
drafted reply, where a marker that reads well in a report reads as a label in a message somebody
sends.

**Three things a shape cannot bound, so the runner does.** A shape sizes its own call and
cannot see that the adapter resumes a paused turn at the same ceiling, that generation is
re-rolled once when what it produced does not hold, or that routing already spent something
before any shape was chosen. So the ceiling is per request rather than per call, the retry draws on what
the first attempt left, and a request with nothing left to spend degrades to insufficient
evidence — the same answer an empty corpus produces, because in both cases there is nothing the
system can stand behind.

**A reply the model wrote in full and the reader could not make an answer of is re-rolled, and
reported in its own words if the second call comes back the same way.** It is the only unreadable
reply worth another call: a refusal refuses again, a truncation has no more room, and a paused turn
stopped mid-flight, so those carry nothing to repair and are answered as they stand. A whole
answer the
reader rejected is the opposite case — it is the most expensive failure this path can produce, and
the same prompt put a second time usually parses. A request given up that way is also the one that
does not report what an empty corpus reports: an answer was written at length and what could not be
read is that answer, so the reply says so and asks for the question again rather than describing an
absence. Every other way of giving up keeps the shared answer, because for those the honest
statement is the same one.

### An answer spanning several bodies of work is sectioned by them

Body of work first, then topic, then findings — never topic first. A reply that groups by
topic and merges the clients inside each one asserts something false: that one body of
work's agreed priority order is the priority order. Every citation under such a heading is
individually sound, so grounding passes it, which is what makes the shape of the reply a
correctness rule rather than a matter of presentation — and the only rule here that no
deterministic gate can see. The evaluation set is where it is held, as far as it can be: a case
can assert that each body of work's material and label reached the answer, because those are
figures and names, and whether the headings nest as asked is read from the answer a run prints.

**It applies to the shapes that report findings, and not to the two that do not.** A drafted
reply is one message to one counterpart, and it reads widely on purpose because register is
learned across threads rather than within one — so headings would divide something nobody can
then send. An answer about the open world is about material the corpus never held, and grouping
it by whichever bodies of work the supplement happened to touch would divide a reply along a
line its question did not have.

**A section is headed by a body of work, or by a person where no body of work can be
determined.** Those are the two admissible top-level keys and a topic is never one. The
second exists because material from a one-to-one conversation may belong to no single body of
work, and a reply that cannot say which must not imply one.

**The grouping key is carried into generation as a label, not left to be inferred.** Which
client a span belongs to is a fact about where it was said, and the only structure a model
can recover from text it is shown is the topic — so a prompt that withholds the label gets
topic-first sections whatever it is asked for. How a span is addressed is no substitute: the
address is qualified by the chat rather than by the conversation, because a source may let a
message move between the topics inside one chat, so for a nested taxonomy it names the
container and not the body of work — and within a request the model is shown a number standing
for the span rather than its address at all.

**Clients whose labels name one body of work share a section.** Where a body of work keeps a
thread per strand of it, those are several clients and one heading, and the labels are what
make that decidable without a merged entity: two labels built on one name read as one body of
work to anybody who sees them, which is the whole reason the label travels rather than
anything the span is addressed by. Grouping them under one heading is therefore the model's to do
from what it is shown, and a section per thread is as wrong as a section per topic.

**Which is the heading and not the scope**, and the two are settled in different places on
purpose. What a request is *read across* when it names the work is decided by a rule, in
resolution: the work is the subject and every strand is what it is answered against, because
a scope is a set of threads to read and a model shown one strand's label cannot widen it. What
a section is *headed by* stays the model's, because that is a choice between labels it can
read and the alternative is heading a section with a name no conversation holds. Neither
merges anything: the strands remain separate clients, no entity joins them, and nothing is
reconciled into one.

**A conversation carrying no label of its own belongs to the one that contains it.** Almost all
of a forum's traffic hangs off the topics inside it, and a topic nobody named is part of the
thing that is named rather than a body of work in its own right. A label that has not been read
yet is the different case and takes nothing: borrowing the container's would file the same
material under the container before a recognition sweep and under its own name afterwards, so
what a reader saw would depend on when they asked.

**A one-to-one conversation takes the body of work of the person on the other side**, where that
person writes inside exactly one. Otherwise it is headed by that person, under the second of the
two keys above: somebody whose writing is spread across several bodies of work has no single one,
and choosing between them asserts a hierarchy the corpus does not state.

**Counted in bodies of work, by the leading name each label carries.** Strands of one body of work
are labels built on one name, so they count once and a counterpart writing across two of them is
headed by the work rather than by themselves. The reduction is a name boundary and not a shared
prefix of characters: a character threshold would have to be calibrated against how a corpus happens
to name things, and would go stale the first time that changed without anything reporting it. It
decides this count, and the one behind a contact's parent, and the scope a name picking out the
work resolves to — and never a heading, because the label a section carries is one the corpus
holds and a reduced name is one no conversation has.

**The rule can join two bodies of work whose labels share a first name, and that is accepted
rather than overlooked.** Someone writing in both is then placed under one body of work instead of
under their own name — the opposite of the direction erred in elsewhere, and the price of settling
the count by a rule instead of asking what a label means. Asking is the narrowing pass's, which
belongs to a later workstream; a corpus whose labels begin with a kind of work rather than whose it
is would need it before any of the three reads is worth trusting. The price is highest at the third:
a count misplaces a heading, where a scope decides which threads an answer is composed from.

### Grounding is checked, not assumed

Retrieved spans are offered under short numbers assigned for the request, and a claim
about the corpus cites those; the numbers are resolved back where the reply is read, so
validation asserts each cited identifier was in the retrieved set and compares nothing
else. A claim citing anything else is removed, and the answer around it still stands: a
citation is copied by a model by hand, so one gone wrong costs the finding it was on rather
than the twenty that were sound. An answer with no claim left standing is a different case —
it was not answered from the material at all, so it retries once and then degrades to an
insufficient-evidence answer, since giving its prose alone would offer a hedge as a
finding. Checking costs no model call, which is what makes it a gate rather than an
intention.

**A heading left with nothing under it goes with the findings that were under it.** A heading
asserts that there is material here about whatever it names — a body of work, or the person
under the second of the two keys above — and it carries no citation of its own, so one with
nothing beneath it states something the reader cannot check and cannot see is missing. It is
removed only when its section is empty: a section whose finding went but whose prose remains
keeps its heading, because that heading is the only thing saying what those lines are about.
Emptiness is the whole of the test, however the section came to be empty — the gate takes one
whose findings it removed, and a reply that arrives with a heading already bare loses it where it
is read, since a reply whose every citation resolved reaches no gate at all. A heading carrying
citations loses them rather than the label: it was told it carries none, so what follows the
marker there is machinery and reaches a reader as characters in the middle of a name. This is
why the model marks a heading rather than writing it as a plain line — a heading is a line of
words and so is prose, and telling them apart by shape would delete text the model composed on
purpose. A heading it does not mark is prose, and can still be left standing.

**The number is what the model copies, and the identifier is what everything else holds.**
A span identifier runs to about forty characters and a reply citing eighteen findings
carries each of them twice over, so every copy is a chance to truncate a field or drop a
delimiter — and one mis-typed character costs the claim it was on. A single number cannot
fail in those ways, and one outside the range offered is recognisable as such rather than
looking like a plausible identifier. It also takes a few hundred tokens out of each request,
on the path where somebody is waiting. What a number cannot do is mean anything after the
request that assigned it, so it goes no further: the wire, the answer trace and the fault
report all name spans by their identifiers, and a citation that resolved to nothing is
reported as the number or the text the model wrote, because there is no span behind it to
name. A citation that reads as no number at all costs the claim it was on, the same way one
naming material nothing retrieved does — what a claim rests on decides whether it reaches a
reader, not which way the model got a citation wrong.

**It applies to claims, not to prose.** A draft reply is composed text and carries no
citations; an assertion that something was agreed is a claim about the corpus and carries
one. Without that distinction the check either blocks every draft or verifies nothing.

**A shape whose answer *is* its findings says so, and a reply from one that asserts nothing is
refused.** That scope is what keeps the check off a draft, and it is also the way round it: an
answer made of cited findings satisfies a claims-only check by writing those findings as prose
instead, and the worse the answer the likelier that route, because a reply with nothing to cite
has nothing to mark. What then reaches a reader is a statement about the corpus — often that the
corpus holds nothing about what was asked, with a list of what it does hold beside it — on the half
of the reply nothing reads. So the shape declares whether its answer is the assertions it cites;
the ones whose deliverable is text the model composed — a message somebody sends, a report on the
open web, a general question the corpus may hold nothing about — declare that it is not, and a rule
reaching those is the one the claims-only scope exists to prevent. Such a shape is also told, in
the format it answers in, that a statement about the material is one of those assertions and that
saying what it was given holds nothing about something is such a statement: it is written as a claim
line, and cited on every piece of material it was read against rather than a few of them, since an
absence is only as checkable as the material a reader can see it was looked for in. A reply
from one of them carrying no claim at all is rejected whole, retried once, and then degrades the
way a reply with nothing left standing does. That refusal makes a claim line the only form an
honest absence can take from one of these shapes: written as prose it is the answer the refusal
discards, so what any rule about it can govern is its wording.

**What an absence may say is bounded over all five shapes, because no gate reads it.** The
grounding check asks whether a cited span resolves and was retrieved, and for an ordinary claim a
citation to the message a statement was read out of is strong evidence of the statement. For a
negative existential the gap is total: two messages cannot evidence the absence of a third, so the
claim shape that most needs checking is the one the check is blindest to. What is retrieved may be
a part of the corpus rather than all of it, so the material in hand cannot distinguish a thing that
is absent from one that was not retrieved — and a reply that states a corpus-level negative out of a
retrieval-level one sends a reader to the single conclusion they cannot check, which is to stop
looking. So the shared format states that premise, gives the permitted form beside the forbidden
one — that nothing in what was given says it, never that the records hold nothing on it — asks for
the scope to be named once, and says that an absence in retrieved material settles nothing.

The premise is hedged rather than flat because a corpus is not always larger than the read: the
pinned fixture the evaluation set runs against sits inside what the fallback's widening retrieves,
so a sentence asserting that a remainder exists would be false there. **Where the scope sentence
goes is not shared at all**, and that is the boundary the shared format is for: the shape reporting
where things stand puts an absence in its opening and nowhere else, the one preparing for a call
puts it at the head of the account, and the fallback puts it after the answer so a reply does not
spend its opening on how far the search reached. Each is right for the shape stating it, so naming
any one of them here would contradict the other two. *Once* is what every shape already says, so
*once* is what is shared. Whether the sentence carries a citation is settled the same way and for
the same reason — a claim line where the answer is its findings, prose where the deliverable is
composed text, each said by the instruction that knows which.

Two things sit outside the rule. An absence on the open web belongs to the shape that searched it:
that search is one it ran and chose the terms for, so finding nothing is a result it established
rather than a gap in material somebody else selected. And an undertaking with no outcome recorded
is a fact about that undertaking rather than a claim about how much the records hold — it is what
the rule about an unrecorded outcome *requires* be written, so a prohibition shared without the
carve-out would forbid the sentence another paragraph of the same prompt demands.

It reaches all five because the shape it was read on is the one that cannot hold it: the
fallback's material is a ranked read of the whole corpus, the furthest thing from everything there
is, and the claim-line rule above reaches only the two whose answer is its findings. No hermetic
check reads a reply for what a sentence asserts, which is the ground the rule about an
undertaking's state and the one about naming a finding's subject are kept out of the forbidden-form
registry on; what holds this is that it reaches every shape, and a live read says the rest.

**Two replies nothing can separate, and the refusal is where this errs.** A subject with
genuinely nothing to say about it and an answer that asserted something it did not cite arrive
identically: composed prose and no claims. Nothing mechanical tells them apart — what was
retrieved is the same either way, and whether a sentence is true is not something a gate reads —
so what is left is which of the two to let through. Refusing understates a corpus that did hold
material, and says so as *nothing to answer from*. Letting it through puts an unsupported statement
about the corpus in front of a reader, as prose, where nothing checks it and nothing records that
it was made. The second is the worse failure, so the refusal is the way this is settled. The honest
hedge is not refused with it: it is an assertion about what was read and cites it.

**What the declaration leaves open is a shape that composes.** One whose deliverable is text can
still write a statement about the corpus as prose, and nothing refuses it — the instruction asks
for a claim line and that is prose asking for prose. The fallback is where this is most reachable,
because a question the corpus has nothing to say about is what it exists to catch, and the open-web
shape reaches it too, since a question naming a person routes there unasked. It is the accepted
residue: a rule reaching those shapes is the one the claims-only scope exists to prevent, and which
of the two costs more is what the evaluation set is for.

**The one class of such a statement the build does act on is a claim about the whole corpus**, made
from a read that saw a part of it. Two shapes compose their answer from a ranked read, and a ranked
read is bounded by a limit and filtered by nothing, so what it returns is a sample whenever the
index is larger than that limit. Each of those instructions therefore says both halves of what the
ranking did — that it may have brought material the question did not ask for, and that it may have
left material behind — and refuses the statements that only a read of the whole could support: that
what is carried is everything, that somebody appears in one place or nowhere else, a count of
contexts, and an absence asserted of the corpus rather than of what this request retrieved. Each
says it for the read it answers from rather than sharing one wording, because a shape asserting its
findings is told to write such a sentence as a claim line citing what it read, and a shape composing
prose cannot cite one at all. The rail is the instruction and a pin on its wording in that shape's
own test, which is the weak kind knowingly: no citation can evidence what the corpus does not hold
elsewhere, so the gate that holds a claim against what was retrieved cannot see the error.

Citing matters most where the derived layer is weakest. A commitment is model output, so an
answer stating one as fact is grounded in an earlier inference — the citation is what
turns it back into something the reader can check.

**An answer crosses to the interface in parts, and the only thing the parts carry is which of them
are headings.** That one distinction is there because it is the only thing about an answer's shape
an interface cannot work out for itself: it composes a citation line itself and so knows its own
lines, while a heading is the model's words and, unmarked, is a short line like any other. What
crosses is the marking and never the emphasis — how a heading looks is the interface's, which owns
the catalogue, the locale and the client's conventions, and a backend sending markup would be
choosing one reader's client for every reader. The sectioning the model is instructed to mark is
therefore the same structure the reader navigates, rather than something flattened on the way out.

**Claims travel to the interface beside the answer, carrying the identifiers they rest on.** On an
answer somebody reads, the assertions themselves are already in it: each one is rendered back into
the prose in the place the model wrote it, so a reader gets the findings and not merely the headings
above them. What the prose leaves out is the identifiers, because printed to a person they are a
line of machine text per finding and nothing a reader can act on. They travel separately so that an
interface meaning to take somebody to the message a claim came from is handed them rather than
asked to parse them back out, and so that whether a validated claim survived the mapping is
checkable at all. How they are shown belongs to the interface, which owns the catalogue and the
layout — but not whether at all: a claim a reader cannot get behind is the one failure this build
cannot afford, so an interface that renders prose renders what each claim rests on.

**What a reader is given is names and dates, because an identifier is not actionable and a number
does not outlive the request that assigned it.** So a claim travels with the author and the source
time of what it rests on beside the identifiers, resolved where that material is still held rather
than read again at the edge, and an interface meaning to link straight into a conversation still
has the identifiers to do it with. Where the corpus records no author the claim carries no name,
under the convention a finding's absent party already follows: the several ways a name can be
missing are one answer to a reader, and none of them licenses inventing one.

**The bot renders them once for a reply rather than under every claim, which is the weaker of two
forms and the one that holds.** A line under each claim is the stronger shape, since it says which
assertion came from where — but it is only worth its room if the line locates a message, and a name
with an elapsed age does not: one person's consecutive turns render identically, and a reply's worth
of such lines takes more room than the findings. So what is rendered is the set of authors the
reply's material is from and the span of days it falls in, which is what somebody searches a
conversation by. Dates rather than ages, because past the bound where an age stops counting every
piece of material is worded the same, and a span whose two ends read alike says nothing; a date
needs a calendar, so the interface is told the zone the corpus is dated in and does not guess one
from a reader's locale. An interface wanting the per-claim mapping has the identifiers to build one
that locates, which is the form that earns the room.

**An alternative somebody sends carries the composed text alone, which is the opposite rule for the
opposite act.** A reader picks one of several drafts, copies it and pastes it into a conversation,
so what it carries has to be sendable unedited — and a draft's assertions are about that
conversation and in the third person about the person being written to, so rendered into the text
they are sent to the person they describe. An answer is read and an alternative is used: only the
second has to survive being copied. The assertions are composed outside the draft by instruction
and reach a reader apart from the alternatives rather than inside one, each alternative carrying
its own: somebody choosing the second draft wants what the second draft rests on, where a list
pooled across the alternatives says only that one of them was supported. They follow the
alternatives rather than sitting between them, so that what a reader may copy is contiguous and
reaching the third does not mean scrolling past what the first two were written from — and each
names the alternative it belongs to by the number that alternative's own header carries, since
adjacency no longer says which is which.

Because the text is the deliverable there, an alternative carrying none of it is not one of the
things being offered. The runner drops it and offers the rest, which is what it already does with a
variant whose every assertion rested on nothing retrieved: a reply that offered three where one was
unusable still offers two, and discarding those would spend a second model call to arrive at
*nothing to answer from* for an answer that was there. Prose may legitimately be blank where the
cited findings carry a variant — a list of dated, cited items is a whole answer for a shape whose
answer is its findings — so the rule is scoped to the shapes that offer a choice.

**What makes that safe is that the promised number of alternatives is checked again after both
removals.** A shape declares how many it offers, and the promise is about what a reader is handed
rather than about what the model typed, so an answer left with too few has nothing to offer and is
refused. Checked only against what was typed, a two-alternative reply losing one would be given as
a plain answer instead — and a plain answer is rendered in the reading order, which is how the
assertions would arrive back inside the text somebody was told to send.

### Two outcomes that are not failures

**Insufficient evidence.** Decided deterministically when retrieval returns nothing,
before any model call, which also makes it the cheapest answer the system can produce. It
is available to every shape and none of them treats it as a failure.

**Freshness.** An answer about what happens next is wrong if the corpus stops two days
ago, so the outcome carries the source time up to which the corpus is complete, read from
what each adapter reports. The assistant may answer while ingestion is behind; it may not
imply a currency it lacks.

## Tech stack

| Concern | Choice | Why |
|---|---|---|
| Backend / bot language | Kotlin 2.2.x, JVM 21, multi-module Gradle (Kotlin DSL), Shadow fat jars | Coroutines for I/O-bound fan-out; one toolchain for both services |
| Backend HTTP | Ktor server (Netty); Ktor client (OkHttp) for outbound | |
| Model | Anthropic Java SDK (`com.anthropic:anthropic-java`); **`claude-opus-5`** answers a question, **`claude-sonnet-5`** does the bulk work | Adaptive thinking, 1M context, tool use on both. Which pass asks which, and why there are two, is under "Model configuration" below |
| Chat frontend | `dev.inmo:tgbotapi`, long polling | Bot API is sufficient for replies in the assistant chat and for receiving commands. Kotlin-first and coroutine-shaped, so the adapter satisfies a `suspend` port with no bridge between the two, and served from Maven Central — which is what keeps the number of repositories this build trusts outside Central at one. The cost is that it requires a newer Ktor client than the rest of the build pins, so the frontend aligns its own and no other module moves |
| Ingestion | Kotlin, same modules as the backend; `tdlight-java` over TDLib for the Telegram user session | One codebase means one domain model and one set of ports. TDLib owns session persistence, gap detection and the incremental position, so none of those is ours to reimplement. The costs are a native library on the image, a second state store inside the container, an LGPL-3 wrapper, and artifacts served from the maintainer's own Maven repository rather than Maven Central |
| Professional-network access | A requested archive, read on arrival; no automation against the account | There is no self-serve API for connections or messages — the official routes are partner-gated and approval is discretionary — and the remaining route, driving a browser, carries an account risk the source's own size makes unacceptable. So this is Principle 5's named exception rather than an adapter. The cost is freshness, recorded in [`decisions.md`](decisions.md) |
| Embeddings | Python sidecar: FastAPI over `intfloat/multilingual-e5-base` (768-dim dense), served by a general sentence-encoder library. Each side of a comparison is embedded behind the word the family is trained with, and the result is normalised; both are named in the version the derived layer re-indexes on, because either would change every stored vector while the model's name stayed put. The weights are fetched into a mounted cache rather than baked into the image, so the image's size is a property of the code and a redeploy does not re-download them | The corpus is Georgian and Russian, against a knowledge base that is partly English when it arrives with workstream II, and this model's backbone spans a hundred languages, Georgian among them. Dense only, because whatever runs here answers twice: once per span while indexing, and once per question on the answer path with a person waiting. A hybrid model of twice the size yields term weights from the same pass as the dense vector, and measured on a processor with no accelerator it spent about a second on one short message and six seconds on a single question, degrading as batches grew — so the lexical half is left unfilled rather than bought at that price, and several open models share this one's width, which makes trying another of them a re-index rather than a migration. Running locally keeps every span on hardware the organisation controls, where the answer path sends a third party only the spans one answer needs. Georgian retrieval is unmeasured — published multilingual benchmarks barely cover it — so no benchmark decides this |
| Store | Postgres + `pgvector`; HikariCP, Flyway migrations, plain JDBC | No ORM. Raw SQL for `<=>` cosine search |
| Config | `fromEnv()` factories, fail-fast on missing required values, assembled in a composition root | No DI framework |
| Static analysis | detekt + detekt-formatting (Kotlin); ruff (Python) | |
| Architecture rules | Konsist, in `:archtest` | Reads Kotlin source rather than bytecode, so packages, imports and literals are all directly inspectable. Konsist parses and JUnit asserts, which keeps every failure message ours to write |
| Tests | JUnit 5, MockK, `kotlinx-coroutines-test`, Testcontainers; pytest | |
| End-to-end | Python: Telethon + pytest | A real Telegram user account is the only thing that can act as the human side of a conversation, and its client libraries are Python |
| CI | GitHub Actions, secret-free | |
| Runtime | Docker Compose on one machine | The office machine is the target; workstream 0 runs on the developer's own |

Dependency versions live in `gradle/libs.versions.toml` — one place, referenced by
every module **and by the build logic itself**: `build-logic` reads the same catalogue, so
the Kotlin, detekt and Shadow versions it resolves are not a second copy.

**Shared module configuration lives in convention plugins**, which is what Gradle's own
structuring guidance prefers over an `allprojects { }` or `subprojects { }` block — the
toolchain, the linter and JUnit are stated once rather than in each build file. They sit in
an **included build** rather than in `buildSrc`, for the two reasons that guidance gives and
that apply here: any change under `buildSrc` makes the entire build out of date, where an
included build only invalidates what consumes it, and `buildSrc` applies the `java` plugin
whether or not it is wanted. The one rough edge is Gradle's, not ours — a convention plugin
cannot use version-catalogue accessors, so the catalogue is read through
`VersionCatalogsExtension` instead.

### Model configuration

**Two models, one credential, one connection.** The model travels on each request rather
than on the connection, so the second provider is derived from the first and costs no
second pool, no second copy of the key and no second set of retry state.

| Work | Model | Why it is this one |
|---|---|---|
| Answering a question — the five request shapes | `claude-opus-5` | A dozen calls a day, and the one place a person reads the output |
| Recognition's inference, and the classification that routes free text | `claude-sonnet-5` | Thousands of calls producing mechanical output; three fifths the price per token either way. Classification's reply is checked against the candidate set it was handed, so a worse one is caught rather than believed |

Going cheaper than `claude-sonnet-5` is an adapter change, not a configuration one:
every request this build sends carries adaptive thinking and an `output_config`
effort, and the small-model family rejects both. Extraction quality below this tier
is also unmeasured, with hedged language and relative dates the first things
expected to suffer.

**Moving the bulk model normally re-derives the horizon.** A derivation mark carries
the version that wrote it and the model's name is in that version — a different model
reads a window differently, and output filed under the old name would be stale in the
way nothing downstream can detect — so the sweep after the change re-reads everything
inside the recency horizon once, at the new model's prices.

**The one move this build makes is exempted, and only that one.** Re-reading what had
already been read would have spent most of the credit this build was given to replace
findings of the same kinds from the same text; the saving a cheaper model exists for is
on the windows nobody has read yet. So the marks are rewritten to name the model that
reads from now on, which trades away their provenance and says so. Any later move does
re-derive.

- **No date suffix on either id.** This family's ids carry none, and a suffixed one is a 404.
- **Thinking is on by default.** `max_tokens` caps thinking *plus* response text,
  so size it generously — 64K on the answer path, 96K on the open-web shape.
- **Effort** goes in `output_config`, not top-level, and it is the dial a deployment
  turns: `xhigh` on the open-web shape, `medium` on everything that is handed what to
  read, and `low`/`medium` are strong on this model. A single call's ceiling is
  deliberately *not* configurable — thinking counts against it, so lowering one buys a
  truncation, which this build reports as having nothing to answer from and is
  therefore indistinguishable from a corpus with no answer in it.
- **One question is bounded across all of its calls**, not just per call: a turn may
  pause and be resumed, and a rejected reply is re-rolled once. That bound is the only
  thing between a pathological question and the provider's patience.
- **Stream** anything above ~16K `max_tokens`, or the request risks an HTTP timeout.
- **Removed parameters:** `temperature`, `top_p`, `top_k` and `budget_tokens` all
  return 400. Steer with prompting and `effort`.
- **Check `stop_reason` before reading content.** A safety decline returns HTTP 200
  with `stop_reason: "refusal"` and empty or partial content; indexing `content[0]`
  unconditionally will break. Opt into server-side fallbacks by default.
- **Prompt caching:** the minimum cacheable prefix is the model's own and does not track
  price — five hundred tokens on the answering model and twice that on the bulk one, so
  the cheaper model is the harder one to cache against, and it is the one the bulk passes
  use. A prefix below it caches nothing and reports nothing, which is the failure to
  design against: a breakpoint that silently does not take looks exactly like one that
  does. Only the classification instruction clears the minimum unaided, and by a margin
  thin enough that shortening it would turn its caching off — which nothing can detect
  before the call, so what reports it is both cache counters coming back at nothing, said
  rather than suppressed. The closure instruction is where that reading has been taken:
  measured just under the minimum, so it is read in full on every call and both of its
  counters sit at nothing — and close enough to it that one more paragraph would carry it
  over and start the caching, which nothing reports either, so the figure is re-counted
  whenever that instruction grows. The adjudication instruction is in the same
  shape and shorter still, and the restatement one clears the minimum only because a
  shared region sits inside its prefix. So an uncached bulk prefix is the ordinary case
  here rather than a defect, and only closure's has been counted. Neither of the two has
  anything to put before the breakpoint: a request is one commitment or one pair, and no
  two of them share more than the instruction. That is recorded rather than fixed —
  padding prose to a provider's threshold buys the caching by spending the instruction,
  and a sub-minimum prefix writes no entry however it is called, so there is nothing to
  warm either. A breakpoint caches the prefix up
  to and including the block it sits on; keep whatever sits before one byte-stable, and put
  what repeats across calls before it and what varies after.
  Entries live five minutes by default and an hour on request, the longer one costing
  more to write — recognition takes it, because a pass that sweeps hourly would otherwise
  find every entry expired and pay a write per sweep that nothing ever reads.
- **A batch cannot be relied on to read its own cache, so the prefix is warmed before it.**
  An entry becomes readable when the first response using it begins, and the requests
  inside one submission are answered concurrently and in any order — so requests sharing a
  prefix within a submission may each pay for it in full. What makes it certain is one
  ordinary call per window, asked before the submission that shares that window, and
  asked at the same effort: changing the effort invalidates the message blocks a shared
  prefix is cached in, so a warming call at any other setting writes an entry its siblings
  cannot read. Pre-warming by asking for no output at all is refused on the batch endpoint,
  so the warming call is a small ordinary one whose reply is discarded.
- **A warming call is billed and belongs to no submission, which the accounting has to
  carry.** A day's spend is otherwise a total over what the collections recorded; this one
  is a call the provider answered with no submission to record it against, so it is written
  to the day directly. Its cost is also the reason the input allowance counts what writing
  an entry cost: the provider reports fresh input exclusive of what it cached, so a prompt
  that caches well moves its spend into the write counter, and a bound reading fresh input
  alone would loosen itself exactly as the caching started working. The calls of one
  submission are made together rather than in turn, so what they cost the pass is one
  turnaround and not one each, and every one of their bills is totalled before any failure
  among them is raised — a failure carrying only its own would under-report the day by
  everything its siblings cost.
- **Web search** is a server-side tool (`web_search_20260209`, which also does
  dynamic filtering) declared on the request — Anthropic runs it, so no outbound
  access is opened from the machine. Used only by request shape #4, where results
  carry citations. Do not also declare a separate code-execution tool alongside it.
- **Batched where nobody is waiting, synchronous where somebody is.** The classification
  that finds what a window states, the restatement that rewrites one finding to stand on
  its own, the adjudication that decides whether two findings already written are one, and
  the closure that decides which later message reports a promise as done — every stage of
  recognition that spends — go to the provider's batch endpoint, which costs half the
  synchronous one for identical
  requests. Extraction is where the model budget goes and the recency horizon is set by
  the credit provisioned rather than by what would answer best, so halving the unit
  price buys about twice the corpus for the same money. Restatement is the larger half
  of that bill: measured over sixteen records it was about five sixths of what the pass
  spent, because it runs at the higher of the two efforts and once per candidate rather
  than once per message read. It is also where a shared prefix is worth taking — one
  window is read once by the classification and again by every candidate the
  classification found in it, so the window travels many times over where the
  instruction travels once. So the window sits before a breakpoint of its own and the
  one field that distinguishes a request from its siblings sits after it; ordered the
  other way round, requests about one window would share no prefix at all. The answer path is never batched,
  because a person is waiting on it while a batch is allowed an hour and may take most
  of a day. Identity needs no model at all.
- **The adjudication stage runs on the sweeps the reading stages have no work for.** It
  compares two pieces of text and nothing else — no window, no conversation, no
  participants, since a claim was restated precisely so that it stands on its own, and an
  attribute's value is the value alone with its relation named on the first line. It is
  reached for a finding pair the exact key and the vector comparison both leave undecided,
  and for a pair of a subject's values the vectors put close, which is the one tier a
  statement has. Both go in one submission, because one submission is one request shape and
  the comparison is one instruction. Neither it nor the closure stage reads a record — one
  compares findings already written, the other examines a commitment — so their submissions
  carry none and their collections mark nothing; what this one writes is either a merge or a
  note that the two are to be kept apart.
  That note is what stops a pair being paid for twice, since a pair stays a candidate by
  every test the store can make. A refusal is recorded the same way and for the same
  reason a declined window is settled: a refusal is deterministic, so a pair held open
  would be submitted and refused on every idle sweep. The cost of placing it on the idle
  sweeps is that a duplicate survives for as long as a backfill does, which is the right
  way round — a duplicate is a row too many, and an unread message is a fact nobody has.
- **A submission outlives the sweep that made it.** It is billed whether or not
  anybody is still waiting for it, so the identifier and the windows it covers are
  persisted the moment the provider mints them: a backend restarting in between would
  otherwise have paid for work it can no longer read, and would submit the same
  windows again. At most one submission of a pass's stage is in flight, so a sweep
  collects what that stage last submitted before it submits more. The sweep that
  pays is not the sweep that reads, which is why what a submission was billed is kept
  against the submission rather than added into the collector's own total — a figure
  folded into the collector's spend would charge one sweep for what another committed.
  Totalled by the day it was collected on, those figures are the day's spend, and the
  bound is read from them before the stage that spends runs again. Both halves of the
  reading therefore count against the same day: what a submission commits, and what
  reading it back costs. Nothing prices a submission before it is made, which is why the
  bound is consulted between submissions rather than sized onto one — a stage stops
  because the day is spent, not because the next batch was estimated and refused.
- **A stage waiting on a turnaround does not stop the stage beside it.** Restatement
  asks about what classification found, so the two can only ever be submitted a
  collection apart; each holds an in-flight slot of its own, and what each last cost is
  recorded separately, since a message read for what it states and a sentence rewritten
  do not cost the same. A record whose classification has been paid for is still owed — nothing
  is derived from it until the restatements are read — but it is not offered for selection
  again, or the pass would pay to classify it twice while waiting on itself.
- **A finding takes two collections rather than one.** Classify, collect and restate,
  collect and write — one step a sweep, so the only round trip held under the pass's
  exclusion is the one that warms a window's shared prefix, taken once per window rather
  than once per candidate. The cost is latency in proportion to the gap between sweeps,
  which is why a shorter cadence is worth more here than a larger batch.
- **A turn that pauses cannot be resumed inside a submission**, so the open-web shape
  is never batched. It is the only shape that declares a server-side tool, so nothing
  that is batched is affected.

### Evaluation cases follow the request shapes

The evaluation set is organised by request shape, not as one undifferentiated suite:
each has at least one case of its own, with its own known-good answer, because they read
different things and fail in different ways. A regression in "where did we leave off" and a
regression in "draft a reply" are not the same defect and should not be reported as one number.
A shape takes a further case per property one answer cannot carry — what a reply says and
how it is divided are scored by different material, so they are asked for separately.

The set covers the shapes that have shipped and grows as each lands, rather than waiting for
all of them. A shape whose retrieved set a fixed corpus cannot pin — the one that reads the
open web answers from material the corpus never held — says so in its own case rather than
being scored on something it does not do.

### Deliberately not chosen

| Rejected | Instead | Reason |
|---|---|---|
| A managed vector database | Local pgvector | Nothing that holds company data runs off company hardware; one store is one thing to operate |
| An agent framework | Ktor + the SDK directly | The pipeline is a short, well-understood sequence; a framework would add a dependency without removing work |
| Hand-made imports as the general case | An adapter per source | The professional network and WhatsApp have no direct integration, which is the argument for exporting them by hand. A corpus that only refreshes when someone remembers to refresh it is not a corpus, so this is refused everywhere the account risk does not forbid the alternative — which is everywhere but the professional network, Principle 5's one named exception |

## Repository layout

`<src>/` stands for what each Kotlin module's tree elides: `src/main/kotlin/`, the Gradle
default, plus the package root `io/github/ragnorrs/mainbrain/` beneath it. The module's own package
sits under that, which is why its name is written twice. `<test-src>/` is the same for
`src/test/kotlin/`, and `:archtest` is written with it because it has no production source
at all — it is nothing but tests.

```
main-brain/
├── settings.gradle.kts              # the six modules, plus :archtest
├── gradle/libs.versions.toml        # every dependency version, including the build's own
├── build-logic/                     # convention plugins: toolchain, detekt, JUnit, shadow
├── docker-compose.yml               # the five services
├── config/detekt.yml
├── ruff.toml                        # the style; `ci.yml`'s path list decides what is read
├── .env.example                     # every variable the stack reads
├── .github/workflows/ci.yml         # jvm · python · docs · shell
├── scripts/
│   ├── check-docs.sh                # assertions over the documents; the `docs` CI job
│   ├── deploy.sh                    # dev · prod; only dev is implemented
│   ├── board-next.sh                # the board items to offer next, most promising first
│   ├── board-next.py                # the ordering itself
│   ├── e2e-declare.sh               # how many cases the end-to-end tree holds
│   ├── e2e-report.sh                # what an end-to-end run executed, and what it skipped
│   └── e2e-report.py                # the translation itself
│
├── contract/                        # the backend's wire DTOs.       depends on no module
│   └── <src>/contract/
│       ├── Api.kt                   # request/response DTOs
│       ├── Command.kt               # the commands the backend answers, and what each takes
│       ├── MessageKey.kt            # localisation codes the backend emits
│       ├── Outcome.kt               # SendText | SendStatus | SendMenu; Block marks a heading
│       └── Unresolved.kt            # the advisory read; no interface asks for it
│
├── domain/                          # DOMAIN.                         depends on nothing
│   └── <src>/domain/
│       ├── record/                  # CORPUS — what a source said
│       │   ├── Conversation.kt · Message.kt · Person.kt · Document.kt
│       │   ├── Batch.kt · ExternalId.kt          # identifiers, carried as claims
│       │   ├── Identifier.kt          # typed, normalised, and observed
│       │   ├── RecordTimes.kt         # the three axes, read back
│       │   ├── SourceStream.kt · SourceReread.kt # port interfaces (SEAMs)
│       │   └── ConversationRepository.kt · MessageRepository.kt
│       │       · PersonRepository.kt · DocumentRepository.kt
│       ├── entity/                  # DERIVED — what was made of the corpus
│       │   ├── Contact.kt · Client.kt · Commitment.kt · Event.kt
│       │   ├── Statement.kt         # subject, predicate, value, validity interval
│       │   ├── Evidence.kt          # a span in the corpus; cannot dangle
│       │   ├── MergeLink.kt         # canonical head, score, signals — reversible
│       │   ├── EntityId.kt          # one internal id, many resolved external ones
│       │   └── ContactRepository.kt · StatementRepository.kt
│       │       · CommitmentRepository.kt · EventRepository.kt
│       ├── recognition/             # the passes that make entities
│       │   ├── Window.kt · Proposal.kt           # proposals name no entity
│       │   ├── MatchScorer.kt · Reconciler.kt    # scores; then applies
│       │   ├── Extractor.kt          # the only model call in recognition
│       │   ├── Resolution.kt         # readings → adjacent validity windows
│       │   └── Inferrer.kt           # one batch of the pass; schedules nothing
│       ├── index/                   # the corpus made findable
│       │   ├── Span.kt · IndexEntry.kt           # a corpus span plus its embedding
│       │   ├── Indexer.kt           # one batch of the pass; schedules nothing
│       │   └── Chunker.kt · IndexRepository.kt   # documents only; messages 1:1
│       ├── answer/
│       │   ├── Runner.kt · Stage.kt              # machinery; knows no shape (SEAM)
│       │   ├── Answer.kt                         # what a request ends as; no message key
│       │   ├── ShapeRegistry.kt                  # each shape registers itself
│       │   ├── Classifier.kt                     # free text → a shape; one cheap call
│       │   ├── Budget.kt                         # what one request may still spend
│       │   ├── Sizing.kt                         # effort and room for one call; effort configurable
│       │   ├── ScopeResolver.kt · CitationCheck.kt
│       │   ├── Retrieval.kt · Lookup.kt          # the reads a shape makes (SEAMs)
│       │   ├── Grouping.kt                       # the body of work behind a record (SEAM)
│       │   ├── Stages.kt                         # assembly and generation, parameterised
│       │   ├── Reply.kt · Offer.kt               # the format a model answers in, and the numbers it cites
│       │   ├── Citation.kt                       # a span identifier, written down
│       │   ├── Material.kt                       # everything retrieved, as spans
│       │   ├── Moment.kt                         # a message's time: one form, one calendar
│       │   ├── AnswerTrace.kt                    # what an attempt retrieved (SEAM)
│       │   └── shape/               # one package per request shape
│       │       ├── Shapes.kt                     # the only place naming all five
│       │       ├── draft/ · status/              # each owns its retrieval,
│       │       ├── research/ · prepare/          # prompt, checks and fixtures
│       │       └── freeform/                     # the fallback; ranked read, no fixtures
│       ├── ModelProvider.kt · EmbeddingProvider.kt          # port interfaces (SEAMs)
│       ├── BackfillPlanRepository.kt   # a work item per conversation, per source
│       ├── Exclusion.kt             # at most one process runs a given derived pass
│       └── IngestStateRepository.kt · DerivationStateRepository.kt
│
├── data/                            # implements the ports; owns I/O.  → :domain
│   ├── <src>/data/
│   │   ├── Database.kt              # pool + Flyway + transaction{} + pinned{} — only Hikari importer
│   │   ├── Sql.kt                   # what the record repositories share
│   │   ├── Jdbc*Repository.kt
│   │   ├── JdbcExclusion.kt         # the advisory lock, on one pinned session
│   │   └── ClaudeProvider.kt · SidecarEmbeddingProvider.kt
│   └── src/main/resources/db/migration/   # Flyway, applied on startup
│
├── bot/                             # FRONTEND process.               → :contract
│   ├── Dockerfile                   # copies the prebuilt jar; no JDK on the host
│   ├── <src>/bot/
│   │   ├── Main.kt · BotContainer.kt    # composition root for this process
│   │   ├── config/BotConfig.kt      # fromEnv()
│   │   ├── port/                    # MessagingPort (chat replies), IncomingMessage
│   │   ├── adapter/TelegramAdapter.kt   # the only file importing Telegram types
│   │   ├── client/BackendClient.kt  # the only way the frontend reaches the backend
│   │   ├── i18n/Catalogue.kt        # resolves (key, locale) → text; Russian is the floor
│   │   └── core/                    # Handlers, AuthGuard, OutcomeRenderer
│   └── src/main/resources/i18n/     # messages_ru.properties, one file per locale —
│                                    #   the only place display text exists
│
├── backend/                         # process.            → :domain :data :contract
│   ├── Dockerfile
│   └── <src>/backend/
│       ├── Main.kt · BackendContainer.kt   # composition root for this process
│       ├── config/BackendConfig.kt  # fromEnv()
│       ├── DerivedPasses.kt         # drives every pass; knows what none of them does
│       └── presentation/            # HTTP — maps domain types to :contract
│
├── ingest/                          # process.                  → :domain :data
│   ├── Dockerfile                   # the only image to carry TDLib
│   └── <src>/ingest/
│       ├── Main.kt · IngestContainer.kt    # composition root for this process
│       ├── config/IngestConfig.kt   # fromEnv()
│       ├── source/                  # one reader per source; each an adapter but the
│       │   │                        #   last, which reads an archive on arrival
│       │   ├── telegram/            # TDLib user session; owns its position and backoff
│       │   ├── drive/               # workstream II — change feed; documents only
│       │   └── linkedin/            # workstream I — reads a requested archive
│       ├── BatchWriter.kt           # one batch onto the record ports; headers first
│       └── Scheduler.kt             # drains each stream at the pace it sets
│
├── archtest/                        # the rules the module graph cannot express.
│   └── <test-src>/archtest/         #   depends on no module: it reads source
│
├── embeddings/                      # Python sidecar
│   ├── requirements.txt             # the service. requirements-model.txt: the model,
│   ├── requirements-model.txt       #   kept apart so the tests install no inference stack
│   ├── Dockerfile
│   ├── app.py                       # POST /embed {texts, kind}; GET /health
│   ├── model.py                     # the only file that loads a model, and it does it
│   └── test_app.py                  #   inside a function, so importing costs nothing
│
├── e2e/                             # end-to-end. Python, because it drives a real
│   ├── config.py · stack.py         #   Telegram USER account — see the testing section
│   ├── login.py                     # mints a session string, once
│   └── test_*.py                    # dev stack only; never CI, never production
│
└── eval/                            # the evaluation set. Python, and its own stack behind a
    ├── harness.py · stack.py        #   Compose profile so nothing it scores is what the bot
    ├── corpus.py                    #   talks to. Seeds a pinned fixture corpus and waits for
    ├── ask.py · scoring.py          #   both derived passes before asking anything.
    ├── fixtures/                    # corpus.json: the records. cases.json: the questions,
    │                                #   and what each one grades
    └── test_*.py                    # retrieval · citations · answers · routing
```

## Layering rules

**The layers are Gradle modules, not packages.** Kotlin has no package-private
visibility, so a layering rule expressed in package names is a comment. Expressed as
module dependencies it is a compile error: `settings.gradle.kts` names the modules and
each module's `build.gradle.kts` declares which others it may see, so the arrows below
are enforced by the build rather than by discipline. Several rules further down reach
past what any module graph can express — which types stay inside a package, which
imports are allowed within a module, how many composition roots a process has. Those
are asserted by `:archtest`, which reads the source; the testing table lists which, and
[`process.md`](process.md) lists the remainder a reviewer must still read for.

The ones that matter most are those where the module graph actively *permits* what they
forbid, and they are not counted here on purpose. `:ingest` depends on `:domain`, where
recognition and the re-read port both live, so only a source rule stops an ingest process
calling the recogniser and needing the model credential beside the live session files, or
the scheduler reaching the port that triggers a re-read. `:ingest` also depends on
`:data`, where the model client is, so only a source rule keeps the SDK to the one file
implementing the port. The same goes for an external library added to domain's own build
file, and for display text reaching any Kotlin source at all.

```
:contract ← :bot                    the frontend speaks wire types and nothing else
:contract ← :backend
:domain   ← :data ← :backend :ingest
:domain   ←         :backend :ingest
```

- **`:domain` depends on nothing at all.** Records, entities, ports, and pure services
  that compose them. It cannot import `:data` and cannot import `:contract`, because
  neither is on its path.
- **`:data` implements the ports and owns outward I/O** to Postgres, the embeddings
  sidecar and the model API. Every implementation of a `:domain` port lives in `:data`
  or in a process module that owns the I/O in question.
- **Source adapters live in `:ingest`, not in `:data`.** They implement a `:domain`
  port and are data-layer code by nature, but keeping them in the ingest module keeps a
  native Telegram library off the backend's classpath and out of its image. The backend
  has no reason to carry it.
- **Only `:bot` and `:backend` see `:contract`.** `:bot` maps it to platform types and
  `:backend`'s `presentation` maps it to domain types. A domain type and a wire type
  that happen to match are a coincidence, not reuse.
- **`:ingest` cannot reach presentation**, because presentation is inside `:backend` and
  `:ingest` does not depend on it. The rule needs no statement beyond the module graph.
- **Interfaces exist to invert I/O, not as decoration.** A repository or provider is an
  interface because an outer module swaps a real implementation behind it. A pure service
  that only composes those interfaces and does no I/O is a concrete class in `:domain`
  with no interface and no `Default*` prefix.
- **Ports are named for the conversation, not the technology**, and a port used by two
  different callers is two ports. `SourceStream` is what the scheduler drains;
  `SourceReread` is what the host triggers; one adapter satisfies both and the scheduler
  depends only on the first.
- **One composition root per process.** `BotContainer`, `BackendContainer` and
  `IngestContainer` are each the only class in their module aware of every layer. Each
  hand-wires its graph from config and is reused by `main()` and by tests, which inject
  fakes plus a Testcontainers database.
- **`Database` owns the pool and the I/O dispatch.** A repository function never blocks its
  caller: it is `suspend`, or it hands back a flow. Either way the blocking JDBC runs through
  `Database.query { }` / `transaction { }` / `pinned { }`, which hop to `Dispatchers.IO` inside
  that one seam. Callers never touch the pool type. `pinned { }` is the one that holds a
  connection while suspended, which is what a session-level advisory lock needs and why the
  pool is sized for the pins as well as the work.
- **Multi-statement writes go through `transaction { }`.** Two separate calls are two
  transactions and leave orphan rows.
- **Ingestion is another consumer of `:domain`**, not a parallel world. It defines no
  record kinds of its own and writes no SQL outside a repository. **It does not
  recognise, reconcile or chunk** — those belong to the derived layer, and an ingest
  process that called them would put the model credential back beside the session files.

### Packages follow the work, not the layer

Inside `:domain`, packages are named for what they do — `record`, `entity`,
`recognition`, `index`, `answer` — and **a port lives with the thing it serves**:
`MessageRepository` in `record`, `StatementRepository` in `entity`, `IndexRepository` in
`index`. A package of interfaces separated from the code they exist for has low cohesion
and high coupling by construction, which is the failure a layer-shaped package invites.

`answer/shape/` is where this matters most. The system is five request shapes and a
fallback, so the tree says so, and **each owns its retrieval, its prompt, its checks and
its fixtures in one package**. `Runner` and `Stage` are machinery and know no shape, so
another shape is a new package that registers itself rather than an edit to files the
existing ones share.

## Seams that must not be bypassed

Two kinds of seam are listed here and they are not the same thing. A **port** is an
interface that inverts I/O, and the rule is that nothing reaches past it. A **layer
boundary** has no implementation to swap; the rule is about what may cross it.

Each row names the seam and what must not be done with it. Where the rule is also
specified under "Layering rules" or in the section that owns the pass, the row stops at
the rule and leaves the reasoning there, so this table stays the index a reader arrives
through rather than a second statement of the design.

| Seam | Kind | Rule |
|---|---|---|
| `SourceStream` (ingest) | port | The only way source material enters the system. What arrives through an interface is not a source, so no interface appears among its implementations |
| `SourceReread` (ingest) | port | The only way a re-read is requested, and the host is its only caller. A catch-up is the adapter's own and does not come through here |
| `MessagingPort` (bot) | port | The only surface for replies in the assistant chat. Handlers never import Telegram types outside `adapter/`. Messaging counterparties is workstream III and has no seam here yet |
| `BackendClient` (bot) | port | The only way the frontend reaches anything. The bot has no database, no model, no retrieval, and no call to the ingest worker |
| `ModelProvider` | port | Every LLM call goes through it. Swapping or benchmarking a second provider is a new implementation, not a refactor |
| `EmbeddingProvider` | port | A hosted model rather than a local one is one more implementation in `:data`, and no caller moves either way. What the port does not make free is the *model*: the index is declared at one model's dense width, so changing it is a migration and a re-index rather than an environment variable. The answer path and the derived layer both go through it |
| `Stage` (domain, `answer/`) | port | A pipeline is an ordered list of pure stages, and `Runner` owns the lifecycle |
| `Reconciler` (domain, `recognition/`) | layer boundary | The only place entity identity is assigned — internal identifiers, external identifiers, and merges. Records carry claims and extraction emits proposals; only this crossing turns either into identity |

## Localisation

**Every interface that renders prose for a person is localised.** Not the bot because
it happens to be first — every such interface, because an interface is the only place
display text exists at all. The bot is workstream 0's; HubSpot is I's and outbound
messaging is III's, and the rule is written once here so none arrives with its strings
in its source. The MCP server in II is the exception that shows what the rule is for:
it hands outcomes and their message keys to a model client, which composes the prose
itself, so it renders nothing and owns no catalogue.

Three parts:

- **The backend emits semantic outcomes carrying message keys, never display text.**
  This is what keeps it language-agnostic: it has no locale, no catalogue, and nothing
  to translate. `MessageKey` lives in `:contract` because it is part of the wire
  format.
- **Each interface that renders prose owns a catalogue and resolves
  `(key, locale) → text`.** Two interfaces rendering the same key differently is
  correct — a chat reply and a CRM field are not the same register — so the catalogue
  belongs to the interface, not to the contract. An interface that renders nothing
  passes keys onward and owns none.
- **Display text lives in per-locale resource files, never in source.** A string in a
  `.kt` file cannot be translated without a code change, which is the whole failure
  this rule exists to prevent. The catalogue is a set of resource bundles, and no
  Kotlin declaration *is* the catalogue — `Strings.kt` holding constants is the shape
  being forbidden.

**A locale's resource files hold the interface's markup, and the interface checks
it.** Emphasis inside a sentence lives in the entry rather than being composed around
it: a locale is free to word a line the way that language wants it and where the
emphasis falls is part of a wording, so composing that in code would mean the
interface knowing the shape of every message instead of only its words. What the
interface does compose is emphasis around a *whole* line it placed there itself — a
section's heading, the block saying what a reply rests on — where the decision is
about position rather than wording and no entry could take it. The division is
ownership either way: material from a model or from the corpus is made literal on the
way out, and the interface's own reviewed text is what may carry markup. What the arrangement
costs is a check, and the failures it guards differ from one another. Markup the
platform cannot parse means the message is refused whole and the failed send abandons
whatever was queued behind it, so a reader gets a missing reply rather than a
mis-rendered one. An ampersand that begins no entity is instead accepted and the text
quietly altered, so the entry says something other than what it reads as. And a tag
the interface does not declare, or emphasis nested inside emphasis, is accepted by the
platform but cannot be closed where a long reply is cut into pieces — so it survives
until a reply using it grows past the message limit and then does not arrive at all.
Every entry of every locale is therefore checked as the catalogue loads, and any of
these stops the process at startup naming the key. A translator cannot be expected to
know a platform's parse rules, which is why this is a check and not a convention. The
vocabulary is accordingly narrower than what the platform accepts, and holds two
emphases that are a decision about attention rather than a limit. What is set in bold
is what a reader is looking for — the section a long answer is scanned for, a command
in a list, whichever name tells one menu option from another. What is set back is the
line they consult rather than read, which is the block saying where an answer's
material came from. A finding takes neither: on a reply of twenty across six sections,
emphasis on each would be emphasis on nearly every line, and a text with everything
emphasised has none.

**The bot's default locale is Russian.** Every key must resolve in Russian; any other
locale may be partial, and a missing translation falls back to Russian rather than
rendering a key or an empty string. The floor is whichever locale is always complete,
which makes a missing translation a visible gap rather than a broken screen. Which
further locales ship is a decision for the interface that needs them: the team's
reading language is what sets the floor, and the corpus being Russian and Georgian
does not settle it on its own.

Generated files are the exception: the backend assembles them and the bot forwards the
bytes without parsing, so file scaffolding is localised where it is written.

**What holds these mechanically, and what does not.** Two checks over the source carry
most of it, and they do not map one-to-one onto the three parts: no corpus script in
any Kotlin source outside a model instruction, and no Kotlin declaration standing in for
a catalogue. Together they make the third part enforceable and the first harder to
breach quietly. The second — that each rendering interface owns its catalogue rather
than borrowing another's — has no mechanical form and never will. Two more wait on there
being an interface to check: that every key resolves in Russian, and that a handler
reaches the catalogue rather than a literal. The character rule catches a Cyrillic
literal in a `.kt` but not one written in another locale's language, which is why the
last of those stays a reviewer's job.

**A model instruction is the one place the corpus's own script is allowed in Kotlin**,
because neither reason for the character rule reaches it: nothing renders a standing
instruction to a person and no locale selects one, so it is not text that escaped the
message-key seam and not a translation needing a rebuild to change. A prompt asking for
a judgement about tentative language needs cues in the language the tentativeness is in,
which is what the exception buys. It is bounded by package — the recognition
stages, which reach no interface — and by nothing finer, because telling a prompt from any
other literal means lexing Kotlin and a rule over the source does not. So anything in that
package may carry the script, and display text written there is caught by neither check,
the catalogue rule reading a closed list of type names and being blind to characters.
There too a reviewer is what covers it, as for a literal in a locale's language.

## Testing strategy

The port/adapter split makes most of the system testable with fakes — no Telegram,
no Anthropic, no secrets in CI.

| Layer | What it covers |
|---|---|
| **Architecture** | `:archtest`, which reads the Kotlin source rather than linking against it — that is what lets one place assert a rule spanning modules. Twenty-two rules, nine of which read the source text and not only the import list, because a fully qualified name declares no import for a rule to read: Telegram types confined to the bot's `adapter/` and under ingestion's `source/`; the frontend's `core/`, `client/`, `i18n/` and `config/` importing nothing from its own `adapter/`, so the dependency inside the module runs one way and the half a second interface reuses stays reusable; `:contract` named only under `presentation` within the backend; no library reaching `:domain`; no Russian or Georgian in any Kotlin source, with one bounded exception — a standing instruction sent to a model is not display text, so the recognition stages may carry the corpus's own script at all; the bound is the package and the carve-out is tested from both sides, and nothing mechanical keeps display text out of that package — the catalogue rule beside this one reads type names rather than characters, so a reviewer covers it; no Kotlin declaration standing in for a string catalogue; ingestion never naming the derived layer; no `*Impl` or `Default*`, objects included; at most one composition root per process; repository functions `suspend` or returning `Flow` — a function whose return type is inferred rather than declared is let through, because there is nothing written down to read; no layer-shaped package in `:domain`; no module outside `:data` naming a SQL or Postgres type, `java.sql` included, which the module graph cannot keep out because the virtual machine ships it — the rule reads source and so cannot see a connection taken from `Database`'s seam, and that route is closed by `internal` visibility instead, because an inferred type has no spelling for a source rule to match; no repository publishing a way to remove a record; the model SDK named in one file only, the adapter implementing `ModelProvider`, so one place holds the credential and one place knows the provider's quirks; the backend's composition root handing the bulk passes — routing, recognition's inference and the admission pass's judgement — the cheaper of the two models, which is a rule over the source because nothing else can see it: the two providers satisfy one port so swapping them compiles, the root opens a store so no unit test reaches the wiring, and both models answer correctly, which leaves the invoice as the only symptom; ingestion naming no model port either, which the SDK rule cannot see; ingestion reaching the network only from a source adapter, and driving a browser nowhere at all; the re-read port named only by a source adapter, a composition root or an entry point, which is what stops a crash loop becoming a re-backfill loop; clients read through the admitted view in every adapter but the one identity writes through, which is what keeps a conversation the admission pass rejected out of an answer — a statement joining the table instead compiles, runs, and returns a rejected client with nothing failing to say so; the answer path naming no spend ledger, so a question asked on a day whose reading allowance is spent is still answered — the answer path is bounded per request and the module graph cannot say so, the port sitting beside the ones it legitimately names; and one rule over the test source rather than the production source — every test with an expression body declaring what it returns, because the runner silently declines to execute a test method that returns a value, so a case ending in an assertion that hands back what it checked is absent rather than failing |
| **Unit** | The allowlist, outcome rendering, handler loops against a fake backend client, pipeline stage order against a fake model provider, `fromEnv()` fail-fast. This list is the intended extent, not a menu — see the unit-test ceiling in [`process.md`](process.md) |
| **Store integration** | Testcontainers `pgvector/pgvector:pg16`. Flyway migrates the container, then repositories and cosine ordering run against real Postgres. Mocking Postgres here would prove nothing |
| **Backend API** | Ktor `testApplication` with fake providers: status codes, auth rejection, response shapes |
| **Ingest** | JUnit against recorded source fixtures; no live client in CI. Re-delivering a batch must upsert rather than duplicate, and a re-read must leave content time untouched where nothing changed |
| **Derived layer** | Recognition and indexing over corpus fixtures, not source fixtures. Re-running a pass must not fork an entity, renumber one, or duplicate a statement; merges are asserted to be reversible links; a record that did not change is not re-inferred, and a finding is only ever written from a record the pass marks |
| **Retrieval quality** | `eval/`, an **evaluation set** over the shipped request shapes — fixed inputs, known-good answers, three scores reported separately, against a pinned fixture corpus in a store of its own. See below |
| **End-to-end** | `e2e/`, a Python suite driving a real Telegram user account against a running dev stack. See below |
| **Manual** | Live Telegram rendering — buttons, file upload, long answers — is verified by hand, not automated |

**The evaluation set is not optional, but it is asked for.** Whether retrieval is correct is the
gap that decides whether a demonstration becomes a product, and nothing else in this architecture
measures it — so a change that could have moved an answer is run against it before it merges. What
is deliberate is that a deploy does not run it unless told to (`--eval`): it is the only tier that
spends the model budget, and most deploys carry changes that could not have moved an answer. Not
optional means a red is acted on and a relevant change is measured; it does not mean every deploy
pays for it.
It lives in `eval/`, a Python suite like the end-to-end one and for a different reason: it
reads the answer path through `POST /answer` as a host process, so it holds no runner, no shape
and no connection on the answer path, and cannot pass by bypassing a stage.

Each shipped request shape becomes a fixed case with a known-good answer — see "Evaluation
cases follow the request shapes" above for how they are organised. It reports three numbers,
because folding them together lets a fluent answer hide a retrieval failure: whether the right
material came back, whether every claim rests on it, and what the prose said.

**Two things make it possible to run at all**, and both are stated here because they are
deployment shape rather than suite detail.

The first is that it has a stack of its own, behind the `eval` Compose profile: `postgres-eval`,
a store the suite seeds, and `backend-eval`, the same backend image pointed at it. Neither
starts on an ordinary `up` and neither is in the container table above, because neither is part
of what the bot talks to. Both publish a port on the loopback address, which is how a host
process reaches `/answer` at all while the serving backend publishes none. Two services rather
than one because the serving corpus mutates daily and a gate cannot be scored against it; the
embeddings sidecar is shared, since a cold second copy would cost minutes per run and it holds
nothing between calls.

The second is that the retrieved set reaches the suite through the log rather than the response.
`AnswerResponse` carries neither what was retrieved nor what was cited, because the bot has no
use for either and a wire format carrying one would answer a question nobody on the wire asked.
So the runner reports each attempt through a port, `AnswerTrace`, which the backend implements
with its logger — citations, never the answer. A citation that read as no number at all has no
identifier to report, so it reaches the trace as the model wrote it, cut to a length.

It cannot run in CI, which has neither real embeddings nor a model credential; when it does run,
and what a red obliges, is in [`process.md`](process.md).

### End-to-end, and why it is Python

The suite drives a real Telegram **user** account. There is no mock layer and there
cannot be one: the Bot API can only act as the bot side of a conversation, so the
human side has to be an actual user session — and the client libraries for one live
in Python. That is the whole reason a Python suite sits beside a Kotlin codebase.

Telegram is both a source and an interface here, so one session can do double duty.
What the suite does today is the second half: it **asks the assistant** a question and
reads what comes back, over the bot, the wire contract, the answer path, retrieval and
the rendering. It does not plant a message first, and that is deliberate — planting
costs a capture, an index sweep and a reading of the new material, so a suite that
planted would spend the model budget every time it ran. It therefore answers from
whatever the corpus already holds.

The price of that is what it can assert. Against a fixed corpus a suite can check that
an answer arrived, is grounded in the shape the format requires and is not a catalogue
line; it cannot check that one particular fact came back, because nothing guarantees
that fact is in the archive. Pinning a known answer is the evaluation set's job, which
holds its own corpus for exactly this reason.

**It runs on the dev stack and nowhere else.** `MB_E2E_TARGET` has no default and
accepts only `dev`. Production holds the live corpus and the logged-in
ingest session, and a Telegram account does not roll back the way a database does.

## CI

GitHub Actions, no secrets, four jobs:

| Job | Runs |
|---|---|
| `jvm` | `./gradlew detekt test` on Java 21 — which covers the architecture rules too. The runner provides Docker, so Testcontainers works |
| `python` | `ruff check` over the three Python trees, the hook helper and the two programs the board and report scripts call — the last of these because a program inside a shell script is a string to `shellcheck` and to `bash -n` alike, and only a file is read before it runs. ⚠️ The step's path list is what decides coverage and selection is by extension, so the Python in `scripts/check-docs.sh`'s heredocs sits under a named path unread; `pytest` for `embeddings/`, and for the cases in `e2e/` that carry none of the markers naming something of the world. Neither a model nor the stack that runs one is downloaded: those are a separate requirements file that the image installs and the tests do not, and nothing in the sidecar imports an inference library at module scope |
| `docs` | `./scripts/check-docs.sh` — assertions over the documents. Every one of them is hermetic, so a runner reaches the same verdict a checkout does; an assertion that cannot run reports SKIP rather than claiming a clean pass |
| `shell` | `bash -n` and `shellcheck --severity=warning` over the `*.sh` of `scripts/` and `.claude/hooks/` — the Python beside them is the `python` job's. That shell is read by something outside this build — a merge, a deploy, every Bash call the harness makes, and the facets of the toolkit the development process runs from — so a break in any of it surfaces where no suite is looking |

**No job builds an image, and the deploy is the first thing that assembles one.** A runner
starts with an empty cache, so building the sidecar would fetch its whole inference stack
every run — the most expensive thing this workflow could do, for the narrowest thing it
could establish: that the Dockerfiles parse and the jars copy. The Actions allowance is
finite and shared with the four jobs above, which read every line of source and every
document. So a Dockerfile or a Compose change is proved by the deploy that runs it, which
fails on the build step, in front of the person who asked for it, and before the stack it
would have replaced is touched.

The backend, bot and ingest images **copy a prebuilt jar** rather than running
Gradle inside the build, so the deploy builds the jars before `compose build`. The host
needs no JDK.

**The end-to-end tree is collected here by marker, and what reaches the world is
deselected.** Everything in it that drives a Telegram account, inspects the stack or
answers from the corpus declares one of three markers, and the step runs what declares
none — the readers its cases assert through, driven with a passage that has the
property and one that breaks it. That calibration needs no credential of any kind, and
an assertion nobody has seen fail is not known to work, so a tier that ran only where
somebody deploys would be one nobody re-runs. Selected by marker rather than by file
name, so a case that later grows a dependency on the world drops out of this step by
declaring it. Keeping CI credential-free is worth more than the rest of the coverage —
nothing in it can leak a session file or an API key, because neither is reachable from
it.

## Configuration and secrets

- Every service reads configuration from the environment through a `fromEnv()`
  factory that fails fast on a missing required value. `.env.example` documents
  every variable; `.env` is git-ignored.
- **Every adapter owns its own credential**, with a documented location and a
  rotation path. Telegram session files and API credentials live outside the project
  tree and are never committed: they are the highest-value secrets in the system,
  because possession of either is possession of a live account.
- The bot↔backend hop is protected by a shared secret header, and so is every other
  route the backend serves, including the advisory read of unresolved contact pairs —
  which the bot does not call. Nothing but the health endpoint is open, because whatever
  reads that one is establishing that the hop works at all.
- **The model API key belongs to recognition and to the answer path.** Capture needs
  no model access, so the ingest container — the one holding live session credentials
  — never sees the key, and neither does the bot.
- Neither later interface — HubSpot in workstream I, the MCP server in II — has a
  credential in this build.

## Operational constraints

- **Wake on power.** The office loses power and connectivity. The machine is
  configured to boot on power restore and Compose restarts services with
  `restart: unless-stopped`.
- **Residential IP.** Ingestion runs from the office connection, not a datacentre,
  on the assumption that a user session logging in from a datacentre IP is treated
  as suspicious. The Bot API carries no such assumption.
- **Read limits are generous, write limits are tight.** Ingestion never writes to a
  source, only to the store.
  Replies in the assistant chat go through the Bot API, which is a separate budget.
  Messaging counterparties cannot use the Bot API at all — a bot cannot open a
  conversation with someone who never started it — so workstream III would have to
  spend the tight user-session budget. That is a cost III has to price, not one this
  build avoids.
- **Restart resumes and catches up; a re-read is asked for explicitly.** The adapter
  holds its own position, so a restart continues where it was, and closes whatever gap
  opened in front of the corpus while the process was down. Re-reading a unit's history
  is the other operation and stays a deliberate, scoped, host-side request — never the
  automatic consequence of a failure, or a crash loop becomes a re-backfill loop.
- **A stop can be signalled at all.** Every container runs an init process as PID 1, so a
  signal reaches the application and the children it abandons are reaped. An application
  that is itself PID 1 and reaps nothing accumulates processes the kernel will not clear,
  and a container holding one cannot be stopped by signal — which leaves the grace period
  below with nothing to govern, and aborts a recreate with the outgoing container left
  behind under a renamed name that the next attempt then collides with.
- **A stop is given time to be an ordinary one.** The shutdown sequence stops the loop
  and then lets the client library write its own store, which holds the incremental
  position nothing here keeps a copy of. The container is configured a grace period
  long enough for that, because the platform's default is short enough to kill the
  sequence partway and turn every stop into the case the catch-up has to repair.
- **Three stores, one restore point — almost.** The corpus, the derived layer and the
  backfill plan all live in Postgres, so a dump captures them together. The Telegram
  client library's own store does not, and after a restore it can believe it is
  further ahead than the corpus is. Nothing inside the library can tell, which is why
  the adapter measures its front edge against the source on start rather than trusting
  a position it was handed.
- **Backups.** The store is the irreplaceable artefact — re-ingesting years of
  history is slow and rate-limited, so a nightly `pg_dump` belongs in the first
  deployment rather than a later hardening pass. **Where it goes is unresolved.** The
  corporate Drive is not available to it: the Drive adapter
  arrives with workstream II, and the system will read that Drive as a source rather
  than write to it — sending the whole store off the machine is what "data does not
  leave" forbids in any case. An attached volume
  survives disk loss but not the machine.
