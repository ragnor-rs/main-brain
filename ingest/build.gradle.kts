plugins {
    id("main-brain.process-conventions")
}

tasks.shadowJar {
    manifest { attributes["Main-Class"] = "io.github.ragnorrs.mainbrain.ingest.MainKt" }
}

// No `:contract` and no `:backend`. Presentation lives inside the backend, so "ingestion
// cannot reach presentation" needs no statement beyond this list.
dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))

    // `implementation` rather than `api`: no Telegram type appears in a signature this module
    // publishes, which `:archtest` asserts over the source as well. Declaring it `api` would
    // not break that rule, but it would stop the build graph from agreeing with it.
    //
    // The generated protocol classes are excluded here and taken explicitly below. The wrapper's
    // POM declares that module twice — plain and at a `legacy` classifier — and leaves the choice
    // to Maven profiles, which Gradle does not evaluate: so both artifacts resolve, each carrying
    // the same class names compiled against a different Java version, and the shadow jar writes
    // both. The jar then holds two `TdApi.class` entries of different sizes and the virtual
    // machine loads whichever comes first. Excluding by module takes out both, which is why the
    // one that is wanted is declared rather than left implied.
    implementation(libs.tdlight) {
        exclude(group = "it.tdlight", module = "tdlight-api")
    }
    implementation(libs.tdlight.api)

    // The compiled library, needed to run and not to compile — so `runtimeOnly`, which also
    // keeps a platform-specific artifact out of the compile classpath where its absence on
    // another architecture would be a compile error rather than a deployment detail.
    //
    // One per architecture the image is built for: the wrapper picks by the running machine, and a
    // missing one surfaces only when the library is first loaded.
    runtimeOnly(variantOf(libs.tdlight.natives) { classifier("linux_amd64_gnu_ssl3") })
    runtimeOnly(variantOf(libs.tdlight.natives) { classifier("linux_arm64_gnu_ssl3") })

    // The facade, because this module now says things of its own: the two places that drop a
    // failure rather than acting on it — a report about the reading that the store refused —
    // would otherwise be silent in a process that runs for real.
    implementation(libs.slf4j.api)

    // The binding, like the natives above: nothing compiles against it, and without it on the
    // classpath at run time everything written through the facade is discarded — the client
    // library's authorisation states and errors included.
    runtimeOnly(libs.slf4j.simple)
}
