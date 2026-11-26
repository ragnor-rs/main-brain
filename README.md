# Main Brain

[![CI](https://github.com/ragnor-rs/main-brain/actions/workflows/ci.yml/badge.svg)](https://github.com/ragnor-rs/main-brain/actions/workflows/ci.yml)

An internal AI assistant over a company's working history. It reads the correspondence a
business already has — predominantly Telegram — into a local store as a source-faithful
corpus, recognises business entities from it, and answers over both through a Telegram bot.
Every claim it makes about the corpus carries the material behind it, and an answer the
corpus cannot support says so rather than guessing.

The bot is the interface; Telegram-as-a-source is a separate thing the system reads, and the
two share nothing but a brand.

## What it answers

Four request shapes, each a named pipeline with its own retrieval, its own prompt and its own
evaluation cases — not one clever prompt:

| Command | What it answers |
|---|---|
| `/status` | Where a contact, a body of work or the inbox stands: what was said, what is owed in each direction, what is coming |
| `/draft` | Two or three complete replies to a thread, in the register of the correspondence, ready for a person to send |
| `/prepare` | A brief before a call: the history, the open undertakings, and the questions worth asking |
| `/research` | A question answered from the open web, every claim carrying its source |

Free text naming none of them is routed to the shape it fits, or answered as a question over
the corpus.

## How it works

```
Telegram (user account)                      a person, in a Telegram chat
        │ MTProto, read-only                         │ Bot API
        ▼                                            ▼
    ingest  ───────────▶  Postgres  ◀───────────   backend  ──────▶  Claude
    records only          corpus, entities         retrieval,        answering,
    deletes nothing       pgvector index           grounding,        recognition
                               ▲                   citation
                               │                       │
                      embeddings sidecar  ◀────────────┘
                      local model, no egress
```

- **Capture and recognition are separate activities.** Adapters yield records — conversations,
  the messages inside them, persons, documents — kept as the source stated them and never
  deleted. A later pass reads that corpus and produces entities: contacts, clients,
  commitments, events. So a better extractor re-runs over material already held, spending no
  request against any source.
- **Three retrieval paths, and the question decides which applies**: the corpus directly when
  a thread or a contact is named, the entities when a question aggregates across everything,
  the index when the scope is unknown. The index is lexical and vector together.
- **Every derived fact points at its evidence**, a span in the corpus — which, because nothing
  is deleted, is a pointer that cannot dangle.
- **Nothing is sent on the system's own authority.** A draft is a draft; a person sends it.

## Status

| | |
|---|---|
| Phase | Prototype — Workstream 0 is built and running on the dev stack |
| Demo data | The author's own Telegram archive |
| Model access | A Claude API credential, reached programmatically |

A prototype, published as it stands rather than groomed into a product. It is not looking for
feature contributions; a defect report that names what it read and what it answered is
welcome.

## Running it

A stack needs Docker, a JDK that Gradle can use for the Java 21 toolchain, a Telegram user
application for the ingest side and a bot token for the interface, and a Claude API
credential.

```sh
cp .env.example .env     # every required value is documented in it, in place
./scripts/deploy.sh --env dev
```

That builds the three fat jars, builds the images, brings the five containers up, waits for
the index pass to catch up with the corpus, and runs the end-to-end suite against the running
stack. `--eval` additionally runs the evaluation set, which is the larger part of a deploy's
model spend and so is asked for rather than assumed.

The first pass backfills the whole history and is slow; indexing follows it. Until both have
run the bot answers that it has nothing to answer from, which is the honest answer rather
than a failure.

## Gates

Everything hermetic runs in CI; what needs the real world runs locally.

```sh
./gradlew detekt test        # style, the architecture suite, unit and store tests
./scripts/check-docs.sh      # assertions over the documents
ruff check embeddings/ e2e/ eval/ scripts/
pytest embeddings/
```

The store tests run against real Postgres through Testcontainers rather than against a mock,
and `:archtest` asserts over the source what the module graph cannot say — that no library
reaches `:domain`, that Telegram types stay inside their adapters, that ingestion never calls
the derived layer. The end-to-end suite and the evaluation set need a live stack, a real
Telegram account and a model credential, so they run on a deploy rather than in CI.

## What leaves the machine

The corpus, the entities and the index stay local. Outbound traffic is the model API —
carrying the spans one answer needs, and the queries of the research shape — and nothing
else: the embeddings sidecar holds its own model and embeds on the host's own CPU.

## Scope

Three areas of work: the assistant, the CRM, and the update track. The assistant is delivered
across 0, I and II below; each workstream is an increment of capability rather than a layer
of one system.

| Workstream | Sources ingested | Interface | State |
|---|---|---|---|
| **0** | Telegram | The Telegram bot | **Completed** |
| **I** | LinkedIn, Email, call recordings via Plaud | HubSpot Contact | **In progress** |
| **II** | Google Drive, Excel, ClickUp | An MCP server over the corpus and its entities | Later |
| **III** | WhatsApp | Outbound messaging, over Telegram | Later |

Workstream 0 is one source, one interface and every stage between them — the thinnest
slice that works end to end. Telegram carries the great majority of the value, which is
why one source proves the whole path. Workstream I widens what the assistant reads and puts what
it builds where the team already works, in the CRM's contact records. II brings the
document path — the knowledge base and the text extraction it needs — and another way to
reach what the system holds, an MCP server that opens both the corpus and the entities
recognised from it to a model client. III also delivers the update track of website, SMM
and agents, which is neither a source nor an interface. Every source arrives
through an adapter and nothing is imported by hand, with one named exception —
LinkedIn, read from a requested archive because automating against an account that
size is not worth the ban risk.

## Built with

Kotlin and Ktor; Gradle, with a module graph that points inward and enforces the layering;
Postgres with pgvector and Flyway; Docker Compose; Konsist for the architecture suite;
Testcontainers for the store tests; and the Claude API for recognition and answering. No
agent framework, no dependency-injection framework, no object-relational mapper, no managed
vector database — each refusal is recorded with the reason for it.

## Documentation

The documents are the specification, and they are most of what this repository ships.

| Document | Contents |
|---|---|
| [`docs/requirements.md`](docs/requirements.md) | What the system must do, and what it is measured against |
| [`docs/architecture.md`](docs/architecture.md) | The shape of the system, the seams that must not be bypassed, and the technology it is built from |
| [`docs/process.md`](docs/process.md) | How work is tracked, built, reviewed and shipped |
| [`docs/decisions.md`](docs/decisions.md) | What is settled about scope and platform, what is open, and what could go wrong |

## Licence

MIT — see [`LICENSE`](LICENSE).
