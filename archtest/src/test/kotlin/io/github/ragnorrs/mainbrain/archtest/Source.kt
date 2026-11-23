package io.github.ragnorrs.mainbrain.archtest

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Reading the project's Kotlin source, for the rules the module graph cannot express.
 *
 * Konsist parses; JUnit asserts. Keeping the two apart means every failure message is written
 * here, naming the file and the rule, rather than being a library's rendering of a predicate.
 */
internal object Source {

    /**
     * Production source of this build, and nothing else.
     *
     * Every path test here is against [repoPath], never the absolute path. A checkout living
     * under a directory called `source`, `adapter` or `presentation` would otherwise satisfy
     * a rule by accident, and the rule would pass without reading anything.
     *
     * Two trees are excluded for reasons that are not obvious:
     *
     * `.claude/worktrees/` holds other branches' checkouts. Konsist walks the whole tree from
     * the directory holding `gradlew` and does not skip dotted directories, so without this a
     * run in the primary checkout reads every parallel session's half-written code — and goes
     * red on it, for a change nobody here made.
     *
     * `build-logic/` is the build's own logic, which these rules do not govern. Konsist
     * excludes `buildSrc` by name and knows nothing about the included build that replaced
     * it, so the exclusion has to be stated rather than inherited. It is excluded from this
     * task's declared inputs too, and the two have to agree or editing build logic would
     * change what the rules see without re-running them.
     */
    private val production: List<KoFileDeclaration> by lazy {
        Konsist.scopeFromProject().files.filter { file ->
            "/src/main/" in file.repoPath &&
                "/.claude/worktrees/" !in file.repoPath &&
                "/build-logic/" !in file.repoPath
        }
    }

    fun everything(): List<KoFileDeclaration> = production

    fun inModules(vararg modules: String): List<KoFileDeclaration> =
        production.filter { file -> modules.any { "/$it/src/main/" in file.repoPath } }

    /**
     * The production files of one module, or a skipped test when it has none yet.
     *
     * Skipped rather than passed, deliberately. A rule asserted over zero files holds for the
     * same reason an empty set satisfies anything, and a green that means nothing is worse than
     * no check at all — it is a check someone will trust. The modules fill up as the build
     * proceeds and each rule starts biting on its own.
     */
    fun required(module: String): List<KoFileDeclaration> {
        val files = inModules(module)
        assumeTrue(
            files.isNotEmpty(),
            "`:$module` has no production Kotlin yet, so this rule is not exercised. " +
                "It is skipped rather than passed, because passing over no files proves nothing.",
        )
        return files
    }

    /**
     * The test source of this build, which one rule governs rather than none.
     *
     * Every other rule here reads what ships, because that is what the layering decisions are
     * about. The exception is the shape of a test declaration: a test the runner silently declines
     * to run reports nothing, passes nothing and is indistinguishable from one that holds — so it
     * is the one place where the suite has to be asserted over rather than trusted.
     *
     * The same two exclusions as [production], for the same reasons: a parallel worktree's
     * half-written tests are not this run's to judge, and the build's own logic is not governed by
     * these rules.
     */
    fun everyTest(): List<KoFileDeclaration> =
        Konsist.scopeFromProject().files.filter { file ->
            "/src/test/" in file.repoPath &&
                "/.claude/worktrees/" !in file.repoPath &&
                "/build-logic/" !in file.repoPath
        }
}

internal fun KoFileDeclaration.importsMatching(prefixes: List<String>): List<String> =
    imports.map { it.name }.filter { name -> prefixes.any { name.startsWith(it) } }

/** Where a file sits, relative to the repository root — the half of a path worth printing. */
internal val KoFileDeclaration.repoPath: String
    get() = projectPath
