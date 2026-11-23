# End-to-end tests

These drive a **real Telegram user account** against the **dev stack** — this machine. There is no
mock layer and there cannot be one: the Bot API can only act as the bot side of a
conversation, so the human side has to be an actual user session. That is also why this suite
is Python beside a Kotlin codebase — the client libraries for a user account live here.

Telegram is both a source and an interface in this system, so one session does double duty. It
**plants** a message for ingestion to find, and it **asks the assistant** about it afterwards.
That closed loop — plant, wait for ingestion, ask, check the answer cites the planted record —
is what the suite exists for, and it spans every component at once: source adapter, corpus,
index, retrieval, the answer path, citation checking, and the bot's rendering. No other kind
of test reaches it.

## Mostly not in CI, and never against production

Two separate rules.

**Not CI, for everything that needs the world.** Those cases need a live stack, a real Telegram
account and a model credential. CI runs without credentials of any kind, which is a rule worth more
than the coverage would be. The hermetic tiers — style, architecture, unit, store integration — run
there instead.

**What is left of this tree does run there, selected by marker.** Everything reaching the world
declares `telegram`, `stack` or `corpus`, and CI runs what declares none: the readers in
`reading.py`, which find a fact the store holds inside prose the model wrote, with
`test_reading.py` driving each of them through a passage that has the property and one that breaks
it; the guard that every case needing the world says so; and the catalogue cases that were always
hermetic. None of them touches anything, and an assertion nobody has seen fail is not known to
work — so a calibration running only where somebody deploys would be one nobody re-runs. A case
that later grows a dependency on the world drops out of that step by declaring the marker, which
is why the selection is by marker and not by file name.

**Not production.** `MB_E2E_TARGET` has no default and accepts only `dev`.
Production holds the live corpus and the logged-in ingest session; this suite writes real
messages into a real account for ingestion to pick up, and a Telegram account does not roll
back the way a database does. One typo is not an acceptable distance from that.

## Setup

1. Install the pinned client:

   ```bash
   pip install -r e2e/requirements.txt
   ```

2. Create an application at <https://my.telegram.org/apps> for an `api_id` and `api_hash`.

3. Mint a session string, once — Telegram rate-limits repeated logins:

   ```bash
   export MB_E2E_API_ID=... MB_E2E_API_HASH=...
   python3 e2e/login.py
   ```

4. Copy `e2e/.env.local.example` to `e2e/.env.local` and fill it in. That file is git-ignored,
   and `config.py` is the only thing that reads it, so credentials need not pass through a
   shell or a chat. Anything already set in the environment wins over the file.

**`MB_E2E_SESSION` is a logged-in account, not a password.** Possession of the string is
possession of the account — the same rule as a session file. Use an account you are willing to
have a test suite write to.

**`MB_E2E_SUBJECT` is a name and not a credential, and it is in that file for a different
reason.** Three of the request shapes are asked about somebody, so each needs a name the corpus
holds; a name it does not know is answered — correctly — with the reply that says the subject
could not be established, which tests nothing about the shape. It is configured rather than
written into a test because every name in this corpus belongs to a real person or organisation
and this repository is not private. Unset, the cases that need it skip and say so.

**Set it to a contact and not to the name of a conversation.** The drafting and call-preparation
shapes accept a contact and nothing else, so a group's name is answered by them with the reply
that offers that conversation's participants as candidates — a short reply carrying no assertion,
which fails the cases on their fixture rather than on the stack. The shape answering where things
stand accepts a conversation as well, so a group name makes that one case pass beside two that
cannot. A counterpart with history answers all three.

**Pick the one whose material can tell a right answer from a wrong one.** Two of the cases hold
what was delivered to what the store places on the subject — that the parties are named, and that
the brief leads with what fell due. Both stand down, by name in the run's own output, on a
counterpart whose undertakings carry no deadline in the past or no party at all: every order of
them is then correct and a brief naming nobody is the right answer. So the material decides how
much the tier can say, and the right subject is the counterpart whose threads hold the most
undertakings that are **past their deadline with nothing recorded against them**, ideally at
two different distances past — which is what makes the order readable. The store answers that in
one query; `store.py` is the shape of it.

## Running

`/deploy --env dev` runs this after bringing the stack up and waiting for the index pass to catch
up. When it has not caught up, the deploy runs everything here except the tests marked `corpus` —
the ones that need material to answer from, each of which could then only have failed for want of
it. By hand:

```bash
MB_E2E_TARGET=dev python3 -m pytest e2e/ -v
```

Without a configured account the Telegram checks **skip, naming what is missing**. The stack
checks still run. An unrun check is never reported as a passing one.

## What it covers today

| File | Property |
|---|---|
| `test_stack.py` | all five Compose services are running, and an absent one is named |
| `test_session.py` | the user session is authorized and belongs to a real account |
| `test_answer.py` | the parts compose: a question naming nobody is answered from the corpus, with cited assertions, and not from the catalogue |
| `test_rendered.py` | what each commanded shape puts in front of a reader — a draft that can be sent as written, findings present where they are read, a brief that names the parties and leads with what fell due, and a researched answer that arrives at all and carries its web sources in the prose |
| `test_commands.py` | the interface's own furniture, as delivered rather than as worded: the command list arrives whole and as one message, a command that does not exist is answered from the catalogue rather than composed, and the formatting the bundle asked for survived the platform's parse. It declares no `corpus` marker, so it needs no indexed material and runs on a cold deploy, costing round trips rather than a model call |
| `test_reading.py` | that each reader behind those assertions can tell the property held from the property broken, and that every case needing the world declares a marker — hermetic, as are the catalogue cases in `test_answer.py`, and those are what CI runs of this tree |

The first two are shallow, and deliberately so. They cover the failures that otherwise waste the
most time: a container that exited at boot, and a session string that was revoked. Each of those
surfaces later as something else's defect.

`test_answer.py` and `test_rendered.py` are the only checks on the composition. The frontend
depends on the wire contract alone, so what the backend emits and what a reader is shown meet
nowhere but in a running stack — and a defect living exactly there is invisible to every other
tier. `conversation.py` holds what they share, including the one reader of the bot's catalogue: a
case that listed the status lines it knew about would pass over the one it had not heard of, which
is how a reply that answers nothing reads as an answer.

**What a case here may assert on, and why it reads the store.** A floor on length and a marker
somewhere in the reply are satisfied by any long, cited answer, including one that got every
decision the shape makes wrong — so the cases that read an answer somebody *reads* also hold it to
the material. What the shapes decide is composed in the model's own words, and the one thing that
does not vary is what they were given: a name is the store's name and a deadline is the store's
date. `store.py` reads those in one query and `reading.py` finds them in the delivered prose. The
answer is still observed from outside, the way a person reads it; only the expectation comes from
the store.

## What arrives with the rest of the build

**The closed loop.** Planting a message for ingestion to find and then asking about it is what
makes a check deterministic. Until then a case cannot assert a particular fact reached the reader,
only that what did reach them is consistent with what the store holds — which is why the cases
above read the store for their expectation and say so in the run's output where the material
cannot discriminate. That needs a cursor so a check can say *after this point*, and waiting for an absence
as well as a presence.

**Picking an alternative, once there is anything to pick.** A menu of drafts arrives as a prompt
and then one message per alternative, and the prompt asks the reader to copy one and send it
themselves — so there is no selection to drive and the cases read the alternatives as they arrive.
A driver for buttons belongs with whatever first renders one.
