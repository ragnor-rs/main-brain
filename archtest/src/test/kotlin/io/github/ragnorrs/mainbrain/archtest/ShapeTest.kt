package io.github.ragnorrs.mainbrain.archtest

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How the code is shaped, where the shape is a decision the documents already made.
 *
 * These are not style — the linter owns style. Each one here is a structural claim
 * `docs/architecture.md` makes that would otherwise hold only by review.
 *
 * Every rule here reads [types], never `classes()` alone. Konsist's `classes()` returns no
 * `object` declarations at all — so a rule written against it is blind to
 * `object BotContainer`, which is the ordinary way to write a composition root in Kotlin.
 * A rule that misses the idiomatic form of what it forbids is worse than no rule: it reports
 * green over the case it exists for.
 */
class ShapeTest {

    /** Handing back a flow is the other way not to block, alongside `suspend`. */
    private val flowTypes = setOf("Flow", "StateFlow", "SharedFlow")

    /**
     * Every named type a file declares: classes and objects alike.
     *
     * `enum`, `data`, `sealed`, `value` and `annotation` classes are all `classes()`;
     * `companion object` is an `objects()`. Both already include nested declarations by
     * default, so only the union matters here.
     */
    private fun KoFileDeclaration.types() = classes() + objects()

    @Test
    fun `an interface exists to invert I-O, not to decorate a class`() {
        val offenders = Source.everything()
            .flatMap { file -> file.types().map { file to it } }
            .filter { (_, declaration) ->
                // `Default` followed by a capital — `DefaultClock`, not `Defaults`, which is
                // the ordinary name for a constants holder and means nothing of the kind.
                declaration.name.endsWith("Impl") ||
                    Regex("^Default[A-Z]").containsMatchIn(declaration.name)
            }
        assertTrue(offenders.isEmpty()) {
            "Named one-implementation-of-one-interface: " +
                offenders.joinToString { (file, declaration) -> "${declaration.name} in ${file.repoPath}" } +
                ". A pure service that composes ports and does no I/O is a concrete class with " +
                "no interface and no prefix; an `Impl` suffix usually means the interface was " +
                "added for its own sake."
        }
    }

    @Test
    fun `a process has one composition root`() {
        // At most one, not exactly one: the failure this guards against is a process wired in
        // two places. Zero is what a module looks like before its process exists.
        listOf("bot", "backend", "ingest").forEach { module ->
            val roots = Source.inModules(module)
                .flatMap { file -> file.types().map { file to it } }
                .filter { (_, declaration) -> declaration.name.endsWith("Container") }
            assertTrue(roots.size <= 1) {
                "`:$module` declares ${roots.size} composition roots: " +
                    roots.joinToString { (file, declaration) -> "${declaration.name} in ${file.repoPath}" } +
                    ". One of them per process knows every layer; a second means two answers " +
                    "to what this process is made of, and they will disagree."
            }
        }
    }

    /**
     * Every function on the port surface of every repository, paired with the file it is in.
     *
     * Interfaces and concrete classes alike: the repository that most needs checking is the one
     * written without an interface, which the `*Impl` rule above actively encourages.
     *
     * The port surface only, which takes two separate exclusions.
     *
     * `.filterNot { it.hasPrivateModifier }` is the one that does the work here. Konsist's
     * `functions()` returns a declaration's own members whatever their visibility, so without it
     * every private mapper and write helper on a repository — `writeHeader`, `readConversation`
     * and their three counterparts — would be judged by rules that mean nothing of the kind, and
     * they would fire on the first repository anyone wrote.
     *
     * The two flags stop `functions()` descending into nested types and into function bodies.
     * Both are inert against the repositories as written, whose only nested declarations are
     * companions holding SQL strings — they are passed so that a nested helper added later does
     * not quietly join the port surface.
     */
    private fun repositoryFunctions(): List<Pair<KoFileDeclaration, KoFunctionDeclaration>> =
        Source.everything()
            .flatMap { file ->
                (file.interfaces(includeNested = true) + file.types())
                    .filter { it.name.endsWith("Repository") }
                    .map { file to it }
            }
            .flatMap { (file, declaration) ->
                declaration.functions(includeNested = false, includeLocal = false)
                    .filterNot { it.hasPrivateModifier }
                    .map { file to it }
            }

    @Test
    fun `a repository never blocks its caller`() {
        val blocking = repositoryFunctions().filterNot { (_, function) -> function.doesNotBlockItsCaller() }
        assertTrue(blocking.isEmpty()) {
            "Repository functions that block the caller: " +
                blocking.joinToString { (file, function) -> "${function.name} in ${file.repoPath}" } +
                ". Repositories run their JDBC through the one seam that hops to the I/O " +
                "dispatcher. Reaching it needs either `suspend` or a `Flow` return; a plain " +
                "function has no way to, so it blocks whichever thread called it."
        }
    }

    /**
     * `suspend` is one way not to block the caller; handing back a flow is the other, and a
     * flow-returning function is never `suspend`.
     *
     * Matched on the bare type name so that a qualified `kotlinx.coroutines.flow.Flow<T>`
     * counts, and against a fixed set rather than a `Flow` prefix so that `StateFlow` and
     * `SharedFlow` count while a type merely called `FlowControl` does not.
     *
     * A function with no declared return type is treated as not blocking. Konsist reads the
     * declaration, not the inferred type, so `fun records() = flow { … }` has none to read —
     * and a rule that reddened on every expression body would be turned off rather than
     * obeyed. The cost is that a blocking expression-bodied function is missed.
     */
    private fun KoFunctionDeclaration.doesNotBlockItsCaller(): Boolean {
        // No declared return type means nothing to read, so the function is let through.
        val declared = returnType?.name ?: return true
        val bare = declared.substringBefore('<').substringAfterLast('.')
        return hasSuspendModifier || bare in flowTypes
    }

    @Test
    fun `no repository offers a way to remove a record`() {
        // Matched on the STEM a destructive function starts with, not the whole verb, because
        // what is forbidden is the capability rather than any particular spelling of it. Stems
        // are what make that true for the noun and gerund forms as well: `remove` as an
        // alternative does not match `removal`, nor `delete` match `deletion`, so a rule written
        // from whole verbs would catch half the family and read as though it caught all of it.
        //
        // The verbs themselves have to be broad for the same reason the stems do. A rule holding
        // only the words that occurred to its author is the defect it is meant to prevent, and
        // `prune` is what an archive's own trimming would most naturally be called.
        val destructive = Regex(
            "^(delet|remov|purg|drop|truncat|clear|eras|forget|forgot|wip|prun|discard|destr|evict|expung)",
            RegexOption.IGNORE_CASE,
        )
        val offenders = repositoryFunctions().filter { (_, function) -> destructive.containsMatchIn(function.name) }
        assertTrue(offenders.isEmpty()) {
            "Repository functions that remove: " +
                offenders.joinToString { (file, function) -> "${function.name} in ${file.repoPath}" } +
                ". Sources report removals differently or not at all, and an archive cannot " +
                "report one — absence is indistinguishable from a capture that came back " +
                "incomplete, so a removal is never an instruction. Retrieval decides what is " +
                "too old to read; storage never does. What this rule covers is the port " +
                "surface, and only that: no repository publishes a way to remove a record. A " +
                "private helper inside `:data` running a `DELETE` as a Kotlin string is caught " +
                "by nothing here — the migration scan reads `.sql` files and would not see it."
        }
    }

    @Test
    fun `packages in domain are named for the work`() {
        val layerShaped = setOf(
            "model", "models", "entity_model",
            "service", "services",
            "repository", "repositories",
            "impl", "internal",
            "util", "utils", "helper", "helpers", "common", "misc",
        )
        Source.required("domain").forEach { file ->
            val segments = file.packagee?.name.orEmpty().split(".")
            val offending = segments.filter { it in layerShaped }
            assertTrue(offending.isEmpty()) {
                "${file.repoPath} sits in a package named $offending. Packages here are named " +
                    "for what they do — record, entity, recognition, index, answer — and a port " +
                    "lives with the thing it serves. A package of interfaces separated from the " +
                    "code they exist for has low cohesion and high coupling by construction."
            }
        }
    }

    /**
     * A test with an expression body declares what it returns.
     *
     * **This exists because four tests in this build had never run.** JUnit declines to execute a
     * test method that returns a value: it logs a line nobody reads and reports nothing, so the
     * method is not failing, not skipped, and not counted — it is absent, while looking exactly
     * like the ones beside it that run. An expression-bodied test inherits the type of its last
     * expression, so a case that ends in `assertIs` or `assertFailsWith` — both of which hand back
     * what they checked — is silently dropped, and a case ending in `assertEquals` is not. Nothing
     * about the two spellings suggests that difference.
     *
     * So the shape is forbidden rather than the symptom caught: a declared `: Unit` makes the
     * method void whatever the body ends with, and a block body cannot inherit a type at all.
     *
     * **What this reads, and what it therefore misses.** The source text, because the type in
     * question is the one *not* written down — Konsist reports declared types and has nothing to
     * say about an inferred one, which is precisely the case. It matches the backticked-name form
     * every test in this build uses; a test named as a plain identifier would not be seen. That
     * limit is recorded rather than closed, because the convention is uniform and a rule reading
     * both forms would be matching prose it cannot distinguish from a helper.
     */
    @Test
    fun `every test declares what it returns`() {
        val offenders = Source.everyTest().flatMap { file ->
            EXPRESSION_BODIED_TEST.findAll(file.text)
                .filter { it.groupValues[2].isBlank() }
                .map { "${file.repoPath}: `${it.groupValues[1]}`" }
        }
        assertTrue(offenders.isEmpty()) {
            "These tests have an expression body and no declared return type, so the runner will " +
                "not execute any of them whose last expression returns a value — and will say " +
                "nothing about it: $offenders. Write `(): Unit =`, or give the test a block body."
        }
    }

    private companion object {
        /**
         * A backticked zero-argument function with an expression body, capturing its name and
         * whatever return type it declares.
         *
         * The second group is empty exactly when no type is written, which is the whole of what
         * the rule asks.
         */
        val EXPRESSION_BODIED_TEST = Regex("""fun `([^`]+)`\(\)\s*(:\s*[\w.<>?, ]+)?\s*=""")
    }
}
