# CLAUDE.md

Guidance for Claude Code working in this repository.

## Orientation

An internal AI assistant over a company's working history. Read
[`README.md`](README.md), then [`docs/requirements.md`](docs/requirements.md) and
[`docs/architecture.md`](docs/architecture.md).

The current build is workstream 0: Telegram in, the bot out.

## Building

Follow [`docs/architecture.md`](docs/architecture.md) for the topology, the layering
rules, and the seams that must not be bypassed; [`docs/process.md`](docs/process.md)
for the issue → branch → PR → review loop and the definition of done.

Five things are easy to get wrong and worth stating here:

- **Two models, no date suffix on either.** `claude-opus-5` answers a question;
  `claude-sonnet-5` does the bulk work — recognition's inference, the classification
  that routes free text, and the judgement of which named conversations are bodies of work —
  over one credential and one connection. Thinking is on by
  default and counts against `max_tokens`; `temperature`, `top_p`, `top_k` and
  `budget_tokens` all return 400. Check `stop_reason` before reading content. Effort is
  the dial a deployment turns; a single call's ceiling deliberately is not, because
  lowering one buys a truncation this build reports as having nothing to answer from.
  Moving the bulk model normally re-derives the recency horizon once, by design; the one
  move this build makes rewrites the marks instead, and any later move re-derives.
- **Layering points inward, and the build enforces it.** `:domain` depends on nothing,
  so it cannot import `:data` or `:contract`; `:ingest` cannot reach presentation. What
  the module graph cannot say, `:archtest` asserts over the source — Telegram types stay in
  the bot's `adapter/` and under ingestion's `source/`, no library reaches `:domain`,
  ingestion never calls the derived layer. If a rule needs prose to hold, it is in the
  wrong place.
- **Display text never appears in Kotlin, and every interface that renders prose owns a
  catalogue.** The backend emits message keys; such an interface holds per-locale resource
  files and resolves `(key, locale) → text`. The bot's default locale is **Russian**, and
  every key must resolve in it — another locale may be partial and falls back. Resource
  bundles are not Kotlin, so the rule forbidding Cyrillic and Georgian in Kotlin source is
  unaffected. An `object Strings` holding text is the shape this forbids. An interface that
  renders nothing — the MCP server passes keys to a model client — owns no catalogue.
  **A model instruction is not display text**, and is the one exception: the recognition
  stages may hold the corpus's own script, because nothing there renders to a person or is
  selected by a locale. The bound is that package and nothing finer — telling a prompt from
  any other literal means lexing Kotlin, which a source rule has no business doing. So
  anything in that package may carry the script and is caught by neither this rule nor the
  catalogue rule, which reads type names and not characters; a reviewer covers it, as for a
  literal in a locale's language.
- **Ingestion shares the codebase** and produces the corpus everything else reads —
  records only. Recognising entities is a separate pass, so no source adapter holds
  the model credential. Ingestion is not a separate service in another language, and
  Telegram-as-source is not Telegram-as-interface.
- **Everything hermetic runs in CI; what needs the real world runs locally.** Style,
  architecture, unit and store tests gate a merge, and so does `scripts/check-docs.sh`,
  which asserts what must be true of the documents, beside the toolkit's documents gate,
  which holds their links, tables, diagrams and forbidden names. Its computed checks are the stronger
  kind, but one keyed on a list of literal names — a source, a service — sees only the
  wordings that list enumerates; the ones that quote a sentence back at a file are weaker
  still, in both directions, so a red there is as likely to mean a rewording as a defect. The end-to-end suite and the evaluation
  set cannot — they need a live stack, a real Telegram user account and a model credential,
  and CI is credential-free. The end-to-end suite runs mechanically: a **full dev** deploy waits
  for the index pass to catch up — the pass's own definition of owed, not a count of marks, since
  a mark says a record was looked at and keeps whatever embedding version wrote it — and then runs
  it. A selective dev deploy skips it and says so. A deploy whose index has not caught up drops
  only the tests that need material — selected by the marker each carries, not by name — because
  asked a question then the stack answers that it has nothing to answer from, which is
  indistinguishable from the answer path being broken; the checks that say the stack came up at
  all still run, and still fail. A store the deploy cannot
  read stops it rather than waiting, since that is not a cold corpus. Production is refused the
  suite, because it writes to a real Telegram account. The evaluation set is the gate
  that matters: it lives in `eval/`, reads the answer path through `POST /answer` on a backend
  of its own, and scores retrieval, grounding and the answers as three separate numbers against
  a pinned fixture corpus. **It is the one tier that is asked for rather than automatic** —
  `--eval` on a deploy — because it is by far the larger spend, about four
  dollars a run against a budget that is finite and already once exhausted. Ask for it when a
  change could have moved retrieval or answering, which is a judgement; everything else here is
  not. The end-to-end suite spends as well, a model call per question it puts to the stack: the
  cases that drive the commanded shapes are what read the rendered message, and the ones asked
  about somebody run only where a subject to ask about is configured, which is most of what bounds
  an ordinary deploy's cost. What sets its floor is the exception — the shape that reads the open
  web accepts no subject, so nothing gates it and it runs on every deploy that has an account, at
  the highest effort and the largest ceiling this build asks for. A red there blocks a merge by convention rather than by a rail, so it is acted on and
  never noted and passed over, and a run that skipped it says so rather than reading as green.

## Language

The corpus is Russian; documentation is English. Respond in the language the user
writes in.

## Rules

1. **The prototype runs on the author's own Telegram archive.** No company material is
   ingested. One personal source carries the record shape this build is about,
   conversations. This is the plan for this build, not a blocker to work around.
2. **The source in scope is Telegram, and the interface is the bot.** That is
   workstream 0, and it is the whole of the current build — one source, one
   interface, every stage between them. Telegram carries the great majority of the value.
   LinkedIn, Email and call recordings via Plaud arrive with workstream I; Google Drive, Excel
   and ClickUp with II; WhatsApp with III. HubSpot is I's interface and the MCP server
   is II's; neither is a source. Every source enters through an adapter and nothing
   is imported by hand, with one named exception: LinkedIn is read from a periodic
   manual export, because automating against an account that size risks a ban worth
   more than the freshness. Later workstreams are deferred entire: keep them cheap to
   add, do not build for them.
3. **Documents are English and carry no corpus text.** No fragment of the material the
   system reads appears in `docs/`, code comments or commit messages — not as an
   illustration, not as a test fixture quoted in prose.
4. **No audit trail in the documents.** They state what is true now, never how it
   got that way. No "previously", "reversed", "decided since", "used to be", no
   note that a section was rewritten or an option abandoned. Change history belongs
   in git, issues and pull requests, which is what they are for.
5. **Never cite private correspondence.** Facts that arrived by private message are
   stated as facts, never attributed to a message, a channel or a timestamp.
6. **No personal names; no company, client or prospect names.** Use roles and
   organisational descriptions. This repository is published.
7. **Nothing about the author's archive beyond its existence**, and nothing about
   local tooling or other projects. Its contents, extent and location are not
   recorded here and must not be committed.
8. **`sources/` is git-ignored and stays that way.** Nothing under it is part of this
   repository.

## Working style

Keep it minimal. Do not add structure, abstraction or documentation beyond what the
current task requires. This is a one-week prototype, built for a reader who prefers a
demonstrated increment to a large custom programme.
