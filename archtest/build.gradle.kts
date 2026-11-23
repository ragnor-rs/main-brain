plugins {
    id("main-brain.kotlin-conventions")
}

// Deliberately depends on no module. These rules read Kotlin source from disk rather than
// linking against compiled classes, which is what lets one place assert rules that span
// modules — "nothing outside an adapter imports the Telegram library" is not a statement any
// single module could make about itself.
dependencies {
    testImplementation(libs.konsist)
}

// Reading source from disk is also why this task needs its inputs spelled out. Gradle knows
// about a task's declared dependencies, not about files it opens at runtime, so without this
// the rules are up-to-date the moment they have passed once: changing another module leaves
// them untouched and they are skipped. A gate that stops running after its first green is
// worse than no gate, because the build still reports it.
tasks.test {
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("*/src/main/kotlin/**/*.kt")
            exclude("build-logic/**")
            exclude("**/build/**")
        },
    ).withPropertyName("projectKotlinSource").withPathSensitivity(PathSensitivity.RELATIVE)
}
