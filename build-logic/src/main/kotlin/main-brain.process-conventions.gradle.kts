/**
 * A module that becomes a container: it has a `main`, and it ships as one fat jar.
 *
 * No `application` plugin. Its start scripts and distribution archives have no consumer here —
 * the image runs `java -jar <module>.jar` — and its `distZip`/`distTar` tasks read the thin
 * jar's output path, which is the same path the fat jar wants. One artifact per process module
 * removes the collision instead of configuring around it.
 *
 * The jar is named for the module with no version or classifier, because the deploy copies it
 * into an image by that name. A classifier appearing in the filename would break the image
 * build rather than the Gradle build, which is the wrong place to find out.
 */

plugins {
    id("main-brain.kotlin-conventions")
    id("com.gradleup.shadow")
}

// Nothing depends on a process module, so the thin jar is an artifact with no reader. Disabled
// rather than renamed, so there is one answer to "which jar runs this service".
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("${project.name}.jar")

    // Two jars contributing the same `META-INF/services/…` file must be merged, not resolved
    // in favour of one. Both lines below are needed, and each is useless alone.
    //
    // This is load-bearing rather than precautionary. Flyway 10 splits each database's support
    // into its own artifact and finds it through `ServiceLoader`, so `flyway-core` and
    // `flyway-database-postgresql` both ship an `extensibility.Plugin` file. Keep only one and
    // the backend starts, reports `V1__corpus.sql` as badly named, writes an empty schema
    // history and creates no tables. No test can catch that: the suite runs on Gradle's
    // classpath, where the two files are separate and `ServiceLoader` reads both. Only a
    // built jar shows it, so verify against one.
    //
    // `duplicatesStrategy` is the half that is easy to miss. Shadow 9 made `ShadowJar` honour
    // Gradle's duplicates strategy, whose default drops a duplicate entry *before* any
    // transformer sees it — so `mergeServiceFiles()` alone registers a transformer that is
    // then handed one copy and merges nothing, which looks exactly like the transformer being
    // broken.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
