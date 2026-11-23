plugins {
    id("main-brain.kotlin-conventions")
}

// No project dependency, ever. This absence is the layering rule: `:domain` cannot import
// `:data` or `:contract` because neither is on its compile path, and no amount of prose
// elsewhere can be bypassed the way a comment can.
//
// Coroutines is `api` rather than `implementation` because ports expose `Flow` and `suspend`
// in their own signatures, so every caller sees those types whether this module says so or not.
//
// JSON is `testImplementation`, and the scope is the whole of the argument. The rule that no library
// reaches `:domain` is asserted by `:archtest` over `src/main` only — a deliberate boundary, not an
// oversight, since what the rule protects is the module's production surface: a library on the
// compile path is I/O or a framework arriving in the one module that must stay free of both. Nothing
// here reaches that path, and a production import of this library still goes red. The test itself
// does do I/O, which is ordinary for a test and is the point: the prompt prose is checked against
// the evaluation set's own cases rather than against a list copied into a test.
dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.serialization.json)
}

// The evaluation fixture the prompt-prose gate reads, handed to the test as a path.
//
// **Declared as an input, which is the part that is easy to leave out and fatal to.** Gradle knows a
// task's declared dependencies, not the files it opens at run time, so without this the test is up
// to date the moment it has passed once: a case edited into conflict with a prompt would never be
// read again and the gate would keep reporting the green it earned before the conflict existed. A
// gate that stops running after its first pass is worse than no gate, because the build still lists
// it. `:archtest` declares an input for that same reason, though not the same one: it declares the
// production sources its rules read, and not the test sources one of them also reads.
val evalCases = rootProject.layout.projectDirectory.file("eval/fixtures/cases.json")

tasks.test {
    inputs.file(evalCases)
        .withPropertyName("evalCases")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("mainbrain.evalCases", evalCases.asFile.absolutePath)
}
