package io.github.ragnorrs.mainbrain.archtest

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The layering rules that the Gradle module graph cannot express.
 *
 * Most of the layering is a compile error: `:domain` declares no project dependency, so it
 * cannot import `:data`, and `:ingest` does not depend on `:backend`, so it cannot reach
 * presentation. Those need no test. What is here is the remainder — rules about which types
 * stay inside a package, which imports are allowed *within* a module, and which external
 * libraries a module may carry. A module graph has nothing to say about any of them.
 *
 * The ones that matter most are those where the module graph actively permits what they
 * forbid, and there is no count of them here on purpose: `:ingest` depends on `:domain`, where
 * recognition and the re-read port both live, so only a source rule stops an ingest process
 * calling the recogniser or reaching the port that triggers a re-read. `:ingest` also depends
 * on `:data`, where the model client is, so only a source rule keeps the SDK to the one file
 * implementing the port. The same goes for an external library added to domain's own build
 * file, and for display text reaching any Kotlin source at all.
 */
class LayeringTest {

    /**
     * Telegram client packages.
     *
     * `it.tdlight.` is the one in use — ingestion reads a user session through it. The rest are
     * the candidates the bot's side may yet be written against, and the list is as good as its
     * own completeness: a library added under a package nobody listed here is not caught.
     * Extending it is part of choosing one.
     */
    private val telegramPackages = listOf(
        "org.drinkless.",
        "it.tdlight.",
        "com.github.kotlintelegrambot.",
        "dev.inmo.tgbotapi.",
        "org.telegram.telegrambots.",
    )

    @Test
    fun `Telegram types never leave an adapter`() {
        Source.everything()
            .filter { it.reaching(telegramPackages).isNotEmpty() }
            .forEach { file ->
                // Matched on the repository-relative path, not the absolute one. A checkout
                // living under a directory called `source` or `adapter` would otherwise
                // satisfy the test by accident, and the rule would permit Telegram imports
                // anywhere in the module.
                val where = file.repoPath
                val allowed = "/bot/src/main/" in where && "/adapter/" in where ||
                    "/ingest/src/main/" in where && "/source/" in where
                assertTrue(allowed) {
                    "${file.repoPath} reaches ${file.reaching(telegramPackages)}. " +
                        "Telegram types belong in the bot's interface adapter or in an ingest " +
                        "source adapter; everywhere else they make the platform a dependency of " +
                        "code that should not know which platform it is serving."
                }
            }
    }

    /**
     * How a source is reached over a network.
     *
     * As with the Telegram list, these are candidates rather than the set in use, and the rule
     * is as good as its own completeness. Two choices in it are deliberate.
     *
     * The platform's own entries name capabilities rather than the whole of `java.net`:
     * `java.net.URL(address).readText()` is the shortest route to the network from Kotlin, so
     * `java.net.http.` alone would be a rule written around the client its author thought of —
     * while `java.net.URI` reaches nothing, and a configuration holding a parsed endpoint is the
     * typed-value shape this codebase prefers elsewhere.
     *
     * These are prefixes, so two of them catch their neighbours: `java.net.URL` also matches
     * `URLEncoder` and `URLDecoder`, and `java.net.Socket` also matches `SocketException` and
     * the other socket value types. That is accepted rather than worked around — in this module,
     * outside an adapter, none of those has a use either — and `java.net.URI`, the one neighbour
     * that does, is the one the narrowing exists to let through.
     *
     * As with the store rule, this is matched against the source text as well as the imports, so
     * naming one of these packages in a comment outside an adapter fails too. That cost is real
     * rather than nil — the prose explaining why the scheduler holds no client cannot be written
     * in the scheduler — and a comment can be reworded while a client reaching the pipeline
     * cannot be unwritten.
     */
    private val networkPackages = listOf(
        "io.ktor.client.",
        "io.ktor.server.",
        "okhttp3.",
        "retrofit2.",
        "org.apache.hc.client5.",
        "org.apache.http.",
        "jakarta.mail.",
        "javax.mail.",
        "java.net.http.",
        "java.net.URL",
        "java.net.URLConnection",
        "java.net.HttpURLConnection",
        "java.net.Socket",
        "java.net.ServerSocket",
        "java.net.DatagramSocket",
        "java.net.MulticastSocket",
        "java.net.InetAddress",
        "java.nio.channels.",
        "javax.net.",
        "io.ktor.network.",
    )

    /**
     * Browser drivers, forbidden in ingestion entirely rather than confined to an adapter.
     *
     * Nothing here drives a browser, and that is a decision rather than an absence: the one
     * source that would have needed it is read from a requested archive, because automating
     * against an account that size risks a ban worth more than the freshness. Confining these
     * to `source/` would permit that reversal in the one directory where it would be written,
     * so this rule covers the module.
     *
     * **What it does not cover.** The decision is "no automation against that account", and a
     * driver is only the most obvious way to write one — an HTML parser over an authenticated
     * fetch is another, and forbidding those outright would also forbid parsing an HTML mail
     * body, which is a legitimate need in a later workstream. So this list is browser drivers,
     * and the wider decision stays a thing a reviewer reads for; `process.md` says so.
     *
     * HtmlUnit appears here only under its pre-3.x package. Its current one, `org.htmlunit.`,
     * is shared with the standalone parsers released beside it — `org.htmlunit.cyberneko`,
     * `org.htmlunit.cssparser` — so forbidding the prefix would forbid the parsing this comment
     * has just finished saying must stay legal.
     */
    private val browserPackages = listOf(
        "org.openqa.selenium.",
        "com.microsoft.playwright.",
        "com.codeborne.selenide.",
        "com.gargoylesoftware.htmlunit.",
    )

    @Test
    fun `ingestion reaches the network only from a source adapter`() {
        // Scoped to `:ingest`. The bot speaks HTTP to the backend and the backend serves it, so
        // a build-wide rule would be wrong; what is wrong is the scheduler — or anything else
        // under this module — holding a client. One adapter per source owns how its source is
        // reached, which is also what keeps a credential inside the thing that needs it.
        Source.required("ingest")
            .filter { it.reaching(networkPackages).isNotEmpty() }
            .forEach { file ->
                assertTrue("/source/" in file.repoPath) {
                    "${file.repoPath} reaches ${file.reaching(networkPackages)}. Only a " +
                        "source adapter talks to the outside: the scheduler drains streams and " +
                        "knows no source, so a client reaching it means the pipeline has learnt " +
                        "which platform it is serving."
                }
            }
    }

    @Test
    fun `ingestion drives no browser, anywhere`() {
        val offenders = Source.required("ingest").filter { it.reaching(browserPackages).isNotEmpty() }
        assertTrue(offenders.isEmpty()) {
            "These files reach a browser driver: " +
                offenders.joinToString { "${it.repoPath} (${it.reaching(browserPackages)})" } +
                ". Driving a browser against a source is the route this build rejected: the one " +
                "source it would have served is read from a requested manual export instead, " +
                "because automating against an account that size risks losing it. An adapter is " +
                "no exception to this one — that is where the reversal would be written."
        }
    }

    @Test
    fun `only an adapter, a composition root or an entry point names the re-read port`() {
        // The anchor first. This rule is a name, so a renamed or deleted port would leave it
        // asserting over nothing — the failure `Source.required` exists to refuse, reached by a
        // different road.
        val declaring = Source.inModules("domain")
            .filter { file -> file.interfaces(includeNested = true).any { it.name == REREAD_PORT } }
        assertTrue(declaring.size == 1) {
            "`$REREAD_PORT` is declared in ${declaring.size} files under `:domain`, not one: " +
                "${declaring.map { it.repoPath }}. This rule is anchored on that name, so a " +
                "rename, a move out of `:domain`, or a second declaration leaves it asserting " +
                "over nothing. Point `REREAD_PORT` and this search at wherever the port now is."
        }
        // Three roles may name it, and they are recognised by what a file *is* rather than by
        // what it is called: the adapter that implements the port, the composition root that
        // wires it, and a process entry point, which is the route `architecture.md` gives the
        // host — a positional argument on this image — without naming a file. Everything else in
        // ingestion is refused — the drainer above all, since a crash loop able to trigger a
        // re-read becomes a re-backfill loop walking into the limits this system exists to stay
        // under, but equally the machinery it calls, which would be a one-file way around that.
        //
        // The catch-up that closes an outage is not an exception to any of this and needs none:
        // it reads only what is newer than what the corpus holds, so it is the adapter's own
        // business and names no port here.
        //
        // Matched on the source text, which covers the import line too: a fully qualified
        // `io.github.ragnorrs.mainbrain.domain.record.SourceReread` declares no import for a rule to
        // read. The cost is that naming the port in a comment anywhere else fails as well,
        // including in `:domain` beside the port itself — and that is the right side to err on,
        // because a comment can be reworded while a re-read triggered from the wrong place
        // cannot be undone.
        //
        // **What this cannot reach.** The capability travels without the name: a root may pass
        // `telegram::reread` as a function type, and a concrete adapter carries `reread()` whether
        // or not the port is named. An inferred type has no spelling for a source rule to match
        // — the hole `internal` visibility closes for the store seam, which a domain port cannot
        // have. Both are in `process.md`'s list of what a reviewer still has to read for.
        val declaringPaths = declaring.map { it.repoPath }
        Source.everything()
            .filterNot { it.repoPath in declaringPaths }
            .filter { REREAD_PORT in it.text }
            .forEach { file ->
                val where = file.repoPath
                val adapter = "/ingest/src/main/" in where && "/source/" in where
                val root = "/ingest/src/main/" in where &&
                    file.types().any { it.name.endsWith("Container") }
                val entryPoint = "/ingest/src/main/" in where &&
                    file.functions().any { it.name == "main" }
                assertTrue(adapter || root || entryPoint) {
                    "${file.repoPath} names the re-read port. Its implementation is a source " +
                        "adapter, its wiring is the composition root, and its caller is the " +
                        "host — reached through an entry point, by whatever route the host is " +
                        "given. Nothing else in ingestion, and nothing outside it: a re-read is " +
                        "never automatic, and it is not operable from the chat."
                }
            }
    }

    /**
     * The ports a model is reached through, which is the half the SDK rule cannot see.
     *
     * `the model SDK is reached from one file and no other` covers the library, and more
     * strictly than this could: if only the file implementing the port may name the SDK, then
     * ingestion may not either. What it does not cover is a `:ingest` file holding the *port* —
     * `ModelProvider` names no SDK package, and `:ingest` depends on both `:domain` and `:data`,
     * so the import compiles. All four exist, so a rename of any of them empties this rule
     * silently -- which is what the anchor assertion below is for.
     *
     * What neither rule reaches: an adapter calling a model's HTTP endpoint over the client it
     * is already allowed, since `source/` is where a network client legitimately lives. The
     * egress rule — ingestion reaches the sources it adapts and nothing else — is wider than any
     * source rule, and the deployment holds the rest of it: the ingest container is given no
     * model credential, so an adapter that tried would have nothing to authenticate with.
     */
    private val modelPorts = listOf(
        "io.github.ragnorrs.mainbrain.domain.ModelProvider",
        "io.github.ragnorrs.mainbrain.domain.EmbeddingProvider",
        "io.github.ragnorrs.mainbrain.data.ClaudeProvider",
        "io.github.ragnorrs.mainbrain.data.SidecarEmbeddingProvider",
    )

    @Test
    fun `ingestion names no model port`() {
        // The module graph permits this and the egress rule forbids it: `:ingest` depends on
        // `:data`, where both providers are implemented, so a scheduler could import one and
        // need the model credential beside the live session files. The derived-layer rule below
        // does not cover it — these two ports sit at the domain root rather than under
        // `recognition/`, `index/` or `answer/`, so its prefixes miss them. An adapter is no
        // exception: a source is read with a source credential, never with a model's.
        Source.required("ingest").forEach { file ->
            val reached = file.reaching(modelPorts)
            assertTrue(reached.isEmpty()) {
                "${file.repoPath} reaches $reached. Ingestion produces records and nothing else. " +
                    "Recognising entities is a separate pass in the backend, which is what keeps " +
                    "the model credential away from the container holding live source sessions."
            }
        }
    }

    @Test
    fun `the backend speaks wire types only in presentation`() {
        Source.required("backend")
            .filter { it.reaching(listOf("io.github.ragnorrs.mainbrain.contract.")).isNotEmpty() }
            .forEach { file ->
                assertTrue("/presentation/" in file.repoPath) {
                    "${file.repoPath} reaches :contract from outside presentation. The wire " +
                        "format is presentation's to map; a domain type and a wire type that " +
                        "happen to match are a coincidence, not reuse."
                }
            }
    }

    @Test
    fun `domain carries no dependency of its own`() {
        // The module graph stops `:domain` importing another module. It says nothing about an
        // external library added to domain's own build file, which is the easier mistake.
        val allowed = listOf(
            "kotlin.",
            "kotlinx.coroutines.",
            "kotlinx.datetime.",
            "java.time.",
            "java.util.UUID",
            "io.github.ragnorrs.mainbrain.domain.",
        )
        Source.required("domain").forEach { file ->
            val foreign = file.imports.map { it.name }.filterNot { name -> allowed.any { name.startsWith(it) } }
            assertTrue(foreign.isEmpty()) {
                "${file.repoPath} imports $foreign. `:domain` holds records, entities, ports and " +
                    "pure services; a library reaching it means I/O or a framework has arrived " +
                    "in the one module that must stay free of both. Widen the list deliberately " +
                    "if that is genuinely what was intended."
            }
        }
    }

    /**
     * Postgres packages, the pool and the migrator.
     *
     * `java.sql` is the one that makes this rule necessary rather than merely tidy. The driver,
     * Hikari and Flyway are all `implementation` dependencies of `:data`, so the module graph
     * already keeps them off every other module's compile path — but `java.sql` ships with the
     * virtual machine and is on everyone's.
     *
     * **What this rule cannot reach, and why it does not have to.** A connection obtained from
     * `Database`'s own seam is a lambda parameter, so its type is inferred and never written
     * down — `database.query { it.prepareStatement("…") }` names nothing for any source rule to
     * match. That route is closed by visibility instead: `query` and `transaction` are
     * `internal` to `:data`, which makes it a compile error rather than something to assert
     * over the source. So this rule covers the other half — a module naming a store type.
     */
    private val storePackages = listOf(
        "java.sql.",
        "javax.sql.",
        "org.postgresql.",
        "com.zaxxer.hikari.",
        "org.flywaydb.",
    )

    @Test
    fun `SQL and Postgres types stay inside data`() {
        // Imports *and* the source text. Reading imports alone would miss the one form that
        // evades it without even looking suspicious — `fun f(c: java.sql.Connection)`, fully
        // qualified, which declares no import for a rule to read. The cost is that naming one
        // of these packages in a comment outside `:data` also fails; that is the right side to
        // err on, and a comment can be reworded.
        Source.everything()
            .filterNot { "/data/src/main/" in it.repoPath }
            .forEach { file ->
                val reached = file.reaching(storePackages)
                assertTrue(reached.isEmpty()) {
                    "${file.repoPath} reaches $reached. The store is reached through " +
                        "a repository port, and `:data` is the only module that knows Postgres is " +
                        "what is behind one. A connection opened anywhere else bypasses the pool, " +
                        "the transaction seam and the dispatcher hop at once."
                }
            }
    }

    /**
     * The model SDK, and every provider type that comes with it.
     *
     * The module graph already keeps it off most compile paths — it is an `implementation`
     * dependency of `:data`, so nothing that depends on `:data` inherits it. What the graph
     * cannot say is that `:data` itself holds exactly one caller: nothing stops a second file
     * in that module opening its own client, and two call sites is how the credential ends up
     * read from two places and the provider's quirks handled in one of them.
     *
     * **What this rule cannot reach, and what covers it instead.** Two routes stay open to it,
     * stated here because a rule that is believed to be wider than it is covers less than a
     * rule known to be narrow. A file in `:data` can call the `internal` helpers beside
     * `ClaudeProvider` and hold an SDK object whose type is inferred and so never written
     * down — the same inference route that defeated the store rule's first form. And any
     * module depending on `:data` can call `ClaudeProvider.of`, which names no SDK type at
     * all. Neither can *send* anything: the client enters the module through one function and
     * the method that uses it is private to its class, so what leaks is at worst a request
     * nobody can dispatch. The credential itself is kept from the ingest process by its
     * container environment rather than by this rule.
     */
    private val modelPackages = listOf("com.anthropic.")

    /** The one file allowed to name them. Asserted to be present, so the exclusion cannot go stale. */
    private val providerPath = "/data/src/main/kotlin/io/github/ragnorrs/mainbrain/data/ClaudeProvider.kt"

    @Test
    fun `the model SDK is reached from one file and no other`() {
        // Imports and source text both, for the same reason the store rule reads both: a fully
        // qualified `com.anthropic.client.AnthropicClient` declares no import for a rule to
        // read. The cost is that naming the package in a comment elsewhere also fails, which
        // is the right side to err on.
        val reaching = Source.everything().filter { file ->
            (file.importsMatching(modelPackages) + modelPackages.filter { it in file.text }).isNotEmpty()
        }

        // Presence first, and before the offender check rather than after it. A renamed or
        // moved adapter would otherwise land in `offenders` and be reported as a second call
        // site — a false diagnosis, since there is still exactly one.
        //
        // What presence cannot tell apart is the allowed file moving and the SDK call moving
        // out of it: both leave the excluded path naming nothing while one other file reaches
        // the SDK, and the message below invites pointing `providerPath` at it. That is why it
        // prints what is reaching the SDK — the reader decides which happened, because the
        // source cannot say.
        assertTrue(reaching.any { it.repoPath.endsWith(providerPath) }) {
            "No file at `$providerPath` reaches the model SDK, so this rule's exclusion names " +
                "nothing and the rule has stopped checking anything. If the adapter moved or was " +
                "renamed, point `providerPath` at where it is now. Files reaching the SDK: " +
                "${reaching.map { it.repoPath }}."
        }

        // Exactly one, not merely at least one. Presence plus no-offenders would pass over two
        // files whose paths both end with that suffix, and the rule's name says one file.
        assertTrue(reaching.count { it.repoPath.endsWith(providerPath) } == 1) {
            "More than one file matches `$providerPath`: " +
                "${reaching.filter { it.repoPath.endsWith(providerPath) }.map { it.repoPath }}. " +
                "The rule allows one adapter, and the exclusion cannot tell these apart."
        }

        val offenders = reaching.filterNot { it.repoPath.endsWith(providerPath) }
        assertTrue(offenders.isEmpty()) {
            "${offenders.map { it.repoPath }} reach the model SDK. Every model call goes through " +
                "`ModelProvider`, and `ClaudeProvider` is the one implementation behind it — one " +
                "place that holds the credential and one place that knows the provider's quirks. " +
                "A second call site is an architecture failure, not a style preference."
        }
    }

    /** Where the backend decides what it is made of, and the one file that names both models. */
    private val backendRootPath =
        "/backend/src/main/kotlin/io/github/ragnorrs/mainbrain/backend/BackendContainer.kt"

    /**
     * The calls that must be handed the cheaper model, and what each one is.
     *
     * Named by the expression the composition root writes, because that expression *is* the
     * decision — there is nothing downstream of it to assert against.
     */
    /**
     * And what `bulk` has to be bound to, because the calls below name only an identifier.
     *
     * A rule checking the call sites alone passes over
     * `val bulk = answering?.on(config.models.answering)`: both literals still present, the
     * identifier still spelled `bulk`, and the dearer model back on the pass that runs thousands
     * of times. That is precisely the defect the rule exists for, reachable without touching a
     * line it reads — the shape earlier reviews in this repository named, a rule anchored on
     * whichever form its author happened to write.
     */
    private val bulkBinding = "answering?.on(config.models.bulk)"

    private val bulkCallers = mapOf(
        "ModelClassifier(bulk)" to "routing free text to a shape",
        "recognition(bulk," to "recognition's inference over the corpus",
        // The third bulk pass: one short classification per named conversation. Listed here for
        // the reason the two above are — the dearer model answers it just as well, so the only
        // evidence of a wrong wiring is the bill.
        "admission(bulk," to "the judgement of which named conversations are bodies of work",
    )

    @Test
    fun `the bulk passes are wired to the cheaper model`() {
        // **Nothing else can catch this, which is why it is here.** The two providers satisfy the
        // same port, so swapping them compiles; the composition root opens a store, so no unit
        // test reaches the wiring; and both models answer every question, so a stack wired to the
        // dearer one works perfectly. The only evidence would be the bill — and the pass this
        // guards is thousands of calls over short messages, which is most of what this build
        // costs. An invariant whose violation is invisible until an invoice arrives is exactly
        // what a rule over the source is for.
        val root = Source.required("backend").filter { it.repoPath.endsWith(backendRootPath) }

        // Presence first, so that a renamed or moved composition root fails as a stale rule
        // rather than passing over a file that no longer exists.
        assertTrue(root.size == 1) {
            "Expected exactly one composition root at `$backendRootPath`, found " +
                "${root.map { it.repoPath }}. This rule reads that file's source, so a move or a " +
                "rename stops it checking anything until `backendRootPath` is pointed at the new one."
        }

        val text = root.single().text
        assertTrue(bulkBinding in text) {
            "`${root.single().repoPath}` does not bind the bulk provider with " +
                "`$bulkBinding`. The calls below name an identifier, so the identifier can be " +
                "bound to the answer path's model while both of them still read correctly — and " +
                "then the pass that runs thousands of times is back on the dearer model with " +
                "nothing failing. The binding is half the invariant; the call sites are the other."
        }
        bulkCallers.forEach { (call, work) ->
            assertTrue(call in text) {
                "`${root.single().repoPath}` does not pass the bulk provider to `$call`, so $work " +
                    "is running on the model the answer path uses. Both satisfy `ModelProvider` and " +
                    "both answer correctly, so nothing else fails — the cost is the only symptom, " +
                    "and it arrives as an invoice rather than as a red build."
            }
        }
    }

    /**
     * The one adapter allowed to name the client table itself, by repository-relative path.
     *
     * It is the write path. A row is inserted there and read back there, and a view withholding the
     * rejected would make that read-back disagree with the insert beside it — so the exemption is
     * about writing and about reading back what was written, not about any judgement.
     *
     * **The exemption is the whole file and nothing finer**, which is the limit of a rule over
     * source: telling one statement in a file from another means parsing SQL out of Kotlin. So a
     * read added to that file is exempt by construction, and what covers it is a reviewer — as it
     * is for the other bounds here that stop at a path or a package.
     */
    private val clientWriterPath =
        "/data/src/main/kotlin/io/github/ragnorrs/mainbrain/data/JdbcClientRepository.kt"

    /**
     * How a statement names the client table rather than the view over it.
     *
     * **Every way SQL reaches a table by name, not only the two keywords a reader thinks of
     * first.** A comma join is a join, a schema qualifier is the same table, and a write names it
     * too — and a rule that saw only `FROM`/`JOIN` without a qualifier would be satisfied by
     * `FROM public.client` or by `FROM conversation cv, client cl`, which are exactly the forms
     * somebody writes when the obvious one has been ruled out.
     *
     * `\b` after the name is what keeps `client_admission` out: an underscore is a word character,
     * so the boundary does not fall inside it. `admitted_client` is excluded by the optional
     * qualifier being anchored — a prefix has to end in a dot, and `admitted_` does not.
     */
    private val readsTheClientTable = Regex(
        """(?i)\b(?:FROM|JOIN|UPDATE|INTO)\s+(?:[a-z_]+\.)?client\b""" +
            // A comma join. It takes the name bare and requires an alias after it, which is what
            // tells it from the two shapes this file would otherwise report: a Kotlin list of enum
            // entries (`CONTACT, CLIENT, CONVERSATION`) and a projected column somebody qualified
            // (`, counterpart.client AS …`). What that gives up is a comma join with no alias and
            // a comma join through a schema, neither of which anything here writes.
            """|,\s+client\s+[a-z]""",
    )

    /** How a statement reads the view instead. The other direction of the rule above. */
    private val readsTheAdmittedView = Regex("""(?i)\badmitted_client\b""")

    @Test
    fun `clients are read through the admitted view`() {
        // **Nothing else can catch this.** A statement joining `client` compiles, runs, and
        // returns rows — more rows than it should, which is a conversation offered as a body of
        // work after a pass has judged that it is a thematic track. The verdict is filtered on
        // read because this layer deletes nothing, so a reader that forgets the view is the only
        // way the filter is bypassed, and the symptom is an answer about material somebody decided
        // was not work. The view is one definition of *admitted*; this is what keeps it the only
        // one.
        val adapters = Source.required("data")

        // Presence first, so that a renamed or moved adapter fails as a stale rule rather than
        // leaving every file in the module exempt by matching nothing.
        val writer = adapters.filter { it.repoPath.endsWith(clientWriterPath) }
        assertTrue(writer.size == 1) {
            "Expected exactly one client adapter at `$clientWriterPath`, found " +
                "${writer.map { it.repoPath }}. The exception below is keyed on that path, so a " +
                "move or a rename either exempts nothing or exempts the wrong file."
        }

        val offenders = adapters
            .filterNot { it.repoPath.endsWith(clientWriterPath) }
            .filter { readsTheClientTable.containsMatchIn(it.text) }
        assertTrue(offenders.isEmpty()) {
            "${offenders.map { it.repoPath }} read the `client` table directly. Every reader but " +
                "the adapter identity writes through goes through `admitted_client`, which " +
                "withholds the conversations the admission pass judged to be thematic tracks " +
                "rather than bodies of work. Nothing is deleted, so the view is the filter — and " +
                "a statement that joins the table instead offers a rejected client to a reader " +
                "with nothing failing to say so."
        }

        // **And the other direction, because a filter nobody reaches is the failure this cannot
        // otherwise see.** Dropping the view and the rule above goes green over a module that
        // never mentions it — which is the state the whole change exists to prevent, arrived at by
        // deleting rather than by forgetting.
        val throughTheView = adapters.filter { readsTheAdmittedView.containsMatchIn(it.text) }
        assertTrue(throughTheView.isNotEmpty()) {
            "No adapter in `:data` names `admitted_client`. The rule above then holds over a " +
                "module that reads no client at all, which is indistinguishable from one where " +
                "the filter was removed."
        }

        // The rule is as good as its pattern, which has to tell the table from three similar
        // names and has to see the forms somebody writes when the obvious one is ruled out.
        boundOnTheClientTable()
    }

    /**
     * The pattern above, asserted on the three names it has to distinguish.
     *
     * A rule whose predicate is only ever shown what it permits cannot be shown to catch anything.
     * Not a `@Test` of its own, for the reason [boundOnModelInstructions] is not: the published
     * count counts rules over the source, and this is a unit test of a predicate.
     */
    private fun boundOnTheClientTable() {
        listOf(
            "FROM client c",
            "JOIN client cl ON cl.id = x",
            "left join client on true",
            // The forms somebody writes when the obvious one is ruled out, and the ones a rule
            // keyed on two keywords without a qualifier lets through.
            "FROM conversation cv, client cl",
            "FROM public.client",
            "JOIN public.client cl ON cl.id = x",
            "UPDATE client SET name = ?",
            "INSERT INTO client (id) VALUES (?)",
        ).forEach {
            assertTrue(readsTheClientTable.containsMatchIn(it)) {
                "`$it` names the client table and the rule does not see it."
            }
        }
        listOf(
            "FROM admitted_client c",
            "JOIN admitted_client cl ON cl.id = x",
            "FROM conversation cv, admitted_client cl",
            "INSERT INTO admitted_client (id) VALUES (?)",
            "JOIN client_admission a ON a.record_source = c.record_source",
            "FROM conversation cv, client_admission a",
            "SELECT c.id, c.name FROM contact c",
            // The two shapes this file really holds, both found by a looser draft of the pattern:
            // a Kotlin list of enum entries, and a projected column somebody qualified.
            "enum class Kind { CONTACT, CLIENT, CONVERSATION }",
            "setOf(SubjectKind.CONTACT, SubjectKind.CLIENT, SubjectKind.CONVERSATION)",
            "own.name AS own_client,\n   counterpart.client       AS counterpart_client",
        ).forEach {
            assertFalse(readsTheClientTable.containsMatchIn(it)) {
                "`$it` does not name the client table, and a rule that says it does is a rule " +
                    "nobody can satisfy."
            }
        }
        assertTrue(readsTheAdmittedView.containsMatchIn("LEFT JOIN admitted_client cl")) {
            "the other direction's pattern does not see the view it is looking for"
        }
        assertFalse(readsTheAdmittedView.containsMatchIn("FROM client c")) {
            "the other direction's pattern is satisfied by the table it is meant to replace"
        }
    }

    @Test
    fun `ingestion does not recognise, reconcile or chunk`() {
        val derived = listOf(
            "io.github.ragnorrs.mainbrain.domain.recognition.",
            // The entities the recognition pass produces, as well as the pass itself. An
            // adapter reaching a contact or a statement is reaching past the corpus into what
            // was made of it, and an adapter that could name an entity is one that could mint
            // identity — the crossing `Reconciler` exists to be the only holder of.
            "io.github.ragnorrs.mainbrain.domain.entity.",
            "io.github.ragnorrs.mainbrain.domain.index.",
            "io.github.ragnorrs.mainbrain.domain.answer.",
            // The watermark is derived-layer state and sits at the top of the domain rather
            // than in one of those packages, because both derived passes share it. Named
            // individually for that reason: a prefix would not reach it, and ingestion keeps
            // its own position in `IngestStateRepository`, which is the port it may name.
            "io.github.ragnorrs.mainbrain.domain.DerivationStateRepository",
            "io.github.ragnorrs.mainbrain.domain.Derivation",
            "io.github.ragnorrs.mainbrain.domain.Deriver",
            "io.github.ragnorrs.mainbrain.data.JdbcDerivationStateRepository",
            // The exclusion that lets one process at a time run a pass, in the same position and
            // named for the same reason. Keyed by `Deriver`, which is already on this list — but
            // only a file that names a deriver would be caught by that, and one that holds an
            // exclusion to hand on would not.
            "io.github.ragnorrs.mainbrain.domain.Exclusion",
            "io.github.ragnorrs.mainbrain.data.JdbcExclusion",
            // The submissions a pass has outstanding with the model provider, in the same position
            // and named for the same reason again. This one is not keyed by anything already on the
            // list — a file could hold a `Submission` or mint a `SubmittedWindow` without ever
            // naming a deriver — so every type of it is named, not just the port.
            "io.github.ragnorrs.mainbrain.domain.SubmissionRepository",
            "io.github.ragnorrs.mainbrain.domain.Submission",
            "io.github.ragnorrs.mainbrain.domain.SubmissionId",
            "io.github.ragnorrs.mainbrain.domain.SubmissionStage",
            "io.github.ragnorrs.mainbrain.domain.SubmittedWindow",
            "io.github.ragnorrs.mainbrain.data.JdbcSubmissionRepository",
            // What a pass has been billed over a span, which is read from those submissions and is
            // derived-layer state for the same reason they are. Ingestion has no model credential
            // and spends nothing, so a figure of what the reading cost is not its to read — and a
            // process that could charge one could put spend on a day nothing in it paid for.
            "io.github.ragnorrs.mainbrain.domain.SpendLedger",
            "io.github.ragnorrs.mainbrain.data.JdbcSpendLedger",
        )
        Source.required("ingest").forEach { file ->
            val reached = file.reaching(derived)
            assertTrue(reached.isEmpty()) {
                "${file.repoPath} reaches $reached. Ingestion produces records and nothing else: " +
                    "the derived layer belongs to the backend, and an ingest process that called " +
                    "it would need the model credential beside the live session files."
            }
        }
    }

    /**
     * The answer path consults no day's spend.
     *
     * **The property that keeps a question answerable on a day the reading has spent out**, and it
     * is asserted over the source because that is the only place it is visible. The answer path is
     * bounded per request by its own budget; a day's total is what bounds the passes that read the
     * corpus. Handed a spend ledger, a shape could consult it — and then an exhausted allowance
     * would reach whoever asked as a question that cannot be answered, which is exactly the
     * symptom the daily bound exists to replace. Nothing in the module graph prevents it: the port
     * sits at the top of `:domain` beside the ones the answer path legitimately names.
     *
     * The ledger's implementation is named too, because a shape reaching the adapter directly
     * would be the same fault arriving past the port.
     */
    @Test
    fun `the answer path names no spend ledger`() {
        val spend = listOf(
            "io.github.ragnorrs.mainbrain.domain.SpendLedger",
            "io.github.ragnorrs.mainbrain.data.JdbcSpendLedger",
        )
        val answering = Source.required("domain").filter { file ->
            "/domain/answer/" in file.repoPath
        }
        assertTrue(answering.isNotEmpty()) {
            "No answer-path source was found, so this rule would pass over nothing."
        }
        answering.forEach { file ->
            val reached = file.reaching(spend)
            assertTrue(reached.isEmpty()) {
                "${file.repoPath} reaches $reached. The answer path is bounded per request, not " +
                    "by the day: a question asked on a day whose allowance is spent still has to " +
                    "be answered, and a shape that could read the day is one that could refuse it."
            }
        }
    }

    @Test
    fun `no Kotlin source holds display text`() {
        // The bound first, over text handed to the predicate rather than read from the tree. A
        // rule whose only input is the repository passes for exactly as long as nobody writes
        // what it forbids, which cannot be told apart from a rule that never fires — and this
        // one carries an exception, so what needs showing is where the exception stops. These
        // cases stay inside this rule rather than becoming one of their own: the archtest count
        // the documents publish is a count of rules over the source, and a unit test of a
        // helper is not one of those however much it looks like a case.
        boundOnModelInstructions()

        // Every module, the bot included. Display text lives in per-locale resource files,
        // so a corpus script in a `.kt` is a leak almost wherever it appears: below the bot it
        // is text that escaped the message-key seam, and inside the bot it is a translation
        // that cannot be translated without a code change. The exception is a model
        // instruction, which is neither — see [holdsDisplayText].
        Source.everything().forEach { file ->
            assertTrue(!holdsDisplayText(file.text, file.repoPath)) {
                "${file.repoPath} contains Russian or Georgian text outside a model " +
                    "instruction, in code or in a comment. In code: the backend emits message " +
                    "keys, each interface owns a catalogue, and the catalogue is a set of " +
                    "resource bundles — so display text never appears in Kotlin at all. In a " +
                    "comment: documentation here is written in English. Text written for a " +
                    "model rather than for a person is the one exception, and it is bounded: " +
                    "see `holdsDisplayText`."
            }
        }
    }

    /**
     * Corpus script in Kotlin that is not a model instruction.
     *
     * **The rule bans display text, and a prompt is not display text.** Nothing renders a
     * standing instruction to a person and no locale selects one, so neither reason the ban
     * exists reaches it — it is not text that escaped the message-key seam, and it is not a
     * translation that needs a rebuild to change. What a prompt does need is the corpus's own
     * script: a judgement about tentative language is asked for in the language the language is
     * tentative in, and seeding it from English alone is what made the marker track grammatical
     * person instead.
     *
     * **The exception is bounded by package and by nothing else**: the recognition stages, which
     * reach no interface, so text there cannot be display text whatever script it is in.
     *
     * **It was bounded by literal form too, and that is removed rather than repaired.** Telling a
     * prompt from any other multi-line literal means pairing `"""` delimiters, pairing them by
     * position is wrong wherever one appears inside a comment, and a parity check catches one such
     * stray but not two — so the tighter bound silently deleted the source between two literals
     * and hid the very comment the rule looks for. A bound that fails open on a plausible
     * authoring mistake is worse than an honest wider one, and this predicate has no business
     * lexing Kotlin.
     *
     * **So the cost is real and is stated here rather than implied.** Anything under that package
     * may carry the corpus's script — a comment, an identifier, display text — and is caught by
     * neither this rule nor the catalogue rule beside it, which reads a closed list of type names
     * and is blind to characters. `docs/architecture.md` records this rule as the only mechanical
     * catch for a literal in the corpus's own script; after this exception that holds everywhere
     * but in one package, where a reviewer covers it, as the document already says for a literal
     * in a locale's language.
     *
     * Both corpus scripts, not only Cyrillic. Georgian is the first-named corpus language in
     * `docs/requirements.md`, so a rule checking Russian alone would miss the likelier leak
     * while its name promised otherwise. Armenian is deliberately absent: it was dropped from
     * the language scope. Georgian spans three blocks rather than one — the main block,
     * Mtavruli (the all-caps form a UI label is likeliest to be written in), and the supplement.
     *
     * Taking [path] as a parameter rather than reading it off a file is what lets the bound be
     * tested on text that is not in the repository.
     */
    private fun holdsDisplayText(text: String, path: String): Boolean {
        val corpusScript = Regex("[\\u0400-\\u04FF\\u10A0-\\u10FF\\u1C90-\\u1CBF\\u2D00-\\u2D2F]")
        if (INSTRUCTIONS in path) return false
        return corpusScript.containsMatchIn(text)
    }

    /**
     * The bound on the exception above, asserted from both sides.
     *
     * A carve-out tested only on what it permits cannot be shown to stop anywhere, so every case
     * here but the permitted ones is a thing that still fails.
     *
     * **The script is written as escapes, not as literal characters.** The convention in this
     * repository is that no Kotlin file holds a corpus script, test source included — the rule
     * reading `/src/main/` only is a limit of the check rather than a licence, and a Cyrillic
     * label in a test file has been removed by hand before on exactly that reading. A character
     * range or an escape is the sanctioned way to name the script deliberately, because anything
     * spelled that way is a review question rather than something a grep has to adjudicate.
     *
     * Not a `@Test` of its own: the archtest count the documents publish counts rules over the
     * source, and this is a unit test of a predicate. Called from the rule it bounds instead.
     */
    private fun boundOnModelInstructions() {
        // A Cyrillic greeting, a Georgian one, and the same Georgian in Mtavruli — the all-caps
        // form the KDoc above names as the likeliest shape for a label, and the one block of the
        // three that a Mkhedruli-only case would leave unexercised. Spelled as escapes so this
        // file holds no corpus script of its own.
        val russian = "\u043F\u0440\u0438\u0432\u0435\u0442"
        val georgian = "\u10D2\u10D0\u10DB\u10D0\u10E0\u10EF\u10DD\u10D1\u10D0"
        val mtavruli = "\u1C92\u1C90\u1C9B\u1C90\u1CA0\u1CAF\u1C9D\u1C91\u1C90"
        val instruction = "$INSTRUCTIONS/Extractor.kt"
        val elsewhere = "/bot/src/main/kotlin/io/github/ragnorrs/mainbrain/bot/Outcomes.kt"

        // Permitted, in any form: nothing under the recognition stages reaches an interface, so
        // the bound is the package and the predicate does not try to read Kotlin's grammar.
        assertFalse(holdsDisplayText("val P = \"\"\"hedged when $russian\"\"\"", instruction)) {
            "A standing instruction may carry the script of the corpus it asks about."
        }

        // The bound itself. The same literal one package over is still a leak — without this the
        // exception would be the raw-string form alone, which every module can write.
        assertTrue(holdsDisplayText("val P = \"\"\"$russian\"\"\"", elsewhere)) {
            "A raw string does not excuse corpus script outside the recognition stages."
        }
        assertTrue(holdsDisplayText("// $russian", elsewhere)) {
            "A comment outside the exception is documentation, and documentation here is English."
        }

        // Every corpus script, so the exception did not quietly become a Cyrillic one — and all
        // three Georgian blocks, since the rule claims the script rather than one of its forms.
        listOf(georgian to "Mkhedruli", mtavruli to "Mtavruli").forEach { (script, form) ->
            assertTrue(holdsDisplayText("// $script", elsewhere)) {
                "Georgian in $form is a corpus script too, and is the likelier leak."
            }
            assertFalse(holdsDisplayText("val P = \"\"\"asks about $script\"\"\"", instruction)) {
                "An instruction may carry Georgian in $form for the same reason it carries Russian."
            }
        }
    }

    /**
     * What a file reaches: its imports, and its source text.
     *
     * Reading imports alone misses the one form that evades them without looking suspicious — a
     * fully qualified `java.net.URL(…)` or `fun f(c: okhttp3.OkHttpClient)` declares no import
     * for a rule to read. The text covers the import line as well, so this is one check rather
     * than two, and the duplicates it can report are harmless in a failure message.
     */
    private fun KoFileDeclaration.reaching(packages: List<String>): List<String> =
        (importsMatching(packages) + packages.filter { it in text }).distinct()

    /**
     * Every named type a file declares, classes and objects alike.
     *
     * `ShapeTest` explains why both: Konsist's `classes()` returns no `object` declarations, and
     * a composition root is ordinarily written as one.
     */
    private fun KoFileDeclaration.types() = classes() + objects()

    @Test
    fun `no Kotlin declaration stands in for the string catalogue`() {
        // The shape being forbidden is `object Strings { const val GREETING = "..." }`.
        // It looks like localisation and defeats it: adding a language means editing code
        // and rebuilding, which is exactly what per-locale resource files avoid. Named
        // types rather than string contents, because a catalogue in English cannot be
        // recognised by its characters — only by what it is for.
        val catalogueNames = setOf(
            "Strings",
            "Messages",
            "Translations",
            "Copy",
            "Texts",
            "Labels",
            "I18n",
        )
        // Interfaces and type aliases included, not just classes and objects. Konsist sorts
        // an interface into neither `classes()` nor `objects()`, so a rule reading only
        // those two waves through
        // `interface Messages { companion object { const val GREETING = "..." } }` — a
        // working catalogue, invisible, because the companion is called `Companion`. A
        // `typealias Strings = Map<String, String>` is a third place the name can hide.
        val declared = Source.everything().flatMap { file ->
            val named = file.classesAndInterfacesAndObjects().map { it.name } + file.typeAliases.map { it.name }
            named.map { file to it }
        }
        val offenders = declared.filter { (_, name) -> name in catalogueNames }
        assertTrue(offenders.isEmpty()) {
            "These declarations look like a string catalogue: " +
                offenders.joinToString { (file, name) -> "$name in ${file.repoPath}" } +
                ". Display text belongs in per-locale resource files under an interface's " +
                "`i18n/` resources; a Kotlin object holding it cannot be translated without " +
                "a code change. A class that *resolves* a key against those files is wanted " +
                "— call it `Catalogue`."
        }
    }

    /**
     * The frontend's platform-free half does not reach into its platform adapter.
     *
     * The rule above keeps Telegram's own types inside `adapter/`. This one keeps the dependency
     * pointing the same way for the module's *own* types: the guard, the command reader, the
     * catalogue, the renderer and the backend client know nothing of the adapter, and the adapter
     * knows all of them.
     *
     * **Why it is worth asserting rather than reviewing.** Those four packages are the whole of
     * what a second interface reuses — another chat platform is another `adapter/` and another
     * bundle, and nothing else. One import pointing the wrong way makes that untrue while leaving
     * every other rule green, because the import is inside the one module the Telegram rule
     * permits Telegram in. It is also the kind of edge a convenience function finds: a renderer
     * reaching for the adapter's message-length limit would read as tidying up.
     *
     * Read over imports rather than source text, because what is forbidden here is a dependency
     * and not a mention: a comment naming the adapter is fine, and the KDoc above says so.
     */
    @Test
    fun `the frontend's reusable half does not reach into its platform adapter`() {
        val adapterPackage = "io.github.ragnorrs.mainbrain.bot.adapter"
        // Named by path rather than derived, so adding a package to the frontend is a decision
        // about which side of this line it falls on rather than something that happens silently.
        val reusable = listOf("/core/", "/client/", "/i18n/", "/config/")
        val governed = Source.required("bot").filter { file -> reusable.any { it in file.repoPath } }

        // Presence first, by the convention `Source.required`'s own note states and the
        // bulk-model rule above follows: a rule asserted over zero files holds the way an empty
        // set satisfies anything, so renaming one of these packages would leave this green over
        // nothing — and the KDoc's claim that adding a package is a deliberate decision would
        // quietly stop being true.
        assertTrue(governed.isNotEmpty()) {
            "No file under `:bot` sits in any of $reusable, so this rule is checking nothing. " +
                "If the frontend's reusable half was renamed or moved, point `reusable` at it."
        }

        // Imports, and the fully qualified form that declares none. The KDoc's allowance is for a
        // *mention* — a comment naming the adapter is fine — so comment lines are excluded rather
        // than the text scan being left out altogether, which is what let a fully qualified name
        // walk past the rules this build tightened for exactly that reason.
        val offenders = governed.flatMap { file ->
            val imported = file.importsMatching(listOf(adapterPackage))
            val qualified = file.text.lineSequence()
                .map { it.trim() }
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .filter { "$adapterPackage." in it }
                .map { "a fully qualified use" }
                .toList()
            (imported + qualified).map { file to it }
        }
        assertTrue(offenders.isEmpty()) {
            "These reach the platform adapter: " +
                offenders.joinToString { (file, import) -> "$import in ${file.repoPath}" } +
                ". The frontend's core, client, catalogue and configuration are the half a " +
                "second interface reuses unchanged, so the dependency runs one way: the adapter " +
                "knows them and they do not know it. Whatever is wanted from the adapter either " +
                "belongs on a port or is platform knowledge that should stay where it is."
        }
    }

    private companion object {
        /** The port whose confinement is asserted above, by name. The anchor is checked there. */
        const val REREAD_PORT = "SourceReread"

        /**
         * Where a model instruction may carry the corpus's own script.
         *
         * The recognition package and nothing else. It reaches no interface — its output is
         * proposals, and what renders prose is the bot, which holds a catalogue — so text here
         * cannot be display text whatever script it is in. A path fragment rather than a list
         * of file names, because an enumeration of the files that happen to hold a prompt today
         * goes stale the first time one is added.
         *
         * Tested against `repoPath` and anchored on `/src/main/`, as every path rule here is: a
         * checkout living under a directory called `recognition` would otherwise widen the
         * exception to the whole repository, and nothing would say so.
         */
        const val INSTRUCTIONS = "/src/main/kotlin/io/github/ragnorrs/mainbrain/domain/recognition/"
    }
}
