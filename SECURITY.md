# Security

There is no hosted instance of this system. It runs on the machine of whoever deploys it,
against their own accounts and their own credentials, so a vulnerability here is a
vulnerability in what somebody else is running rather than in a service anybody can reach.

**Reporting.** Open an issue. If the finding is one a report should not state in the open,
open an issue saying only that, and a private channel will be arranged from there.

**Do not paste real correspondence into a report.** This system reads private messages, so a
reproduction is tempting to copy verbatim. Describe the shape of the material instead — how
many turns, who addressed whom, what the text claimed — which is what a defect in retrieval,
recognition or rendering actually turns on. Every fixture in this repository is invented for
that reason.

**What the credentials reach.** A deployment holds a Telegram user session, a bot token, a
model API key and a store password. The session is the sharpest of them: it is a logged-in
account. All four live in files that `.gitignore` excludes, and no test or CI job can reach
any of them — CI runs without credentials of any kind.
