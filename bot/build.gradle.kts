plugins {
    id("main-brain.process-conventions")
}

tasks.shadowJar {
    manifest { attributes["Main-Class"] = "io.github.ragnorrs.mainbrain.bot.MainKt" }
}

// `:contract` and nothing else among this build's modules. The frontend speaks wire types; it has
// no database, no model, no retrieval, and no domain type to be tempted by.
dependencies {
    implementation(project(":contract"))

    // The outbound client, which reaches the backend and nothing else. OkHttp as the engine for
    // the same reason the sidecar's client uses it — pooling and timeouts become configured
    // things — and the timeout matters more here: an answer is a model call, so the ceiling this
    // client waits under is tens of seconds rather than the default's few.
    //
    // No serialisation plugin. Every wire type is declared in `:contract`, which carries the
    // plugin and generates the serialisers; this module configures a `Json` and hands it those
    // types. A `@Serializable` class here would be a wire type outside the wire format.
    // Aligned onto one Ktor version first. The Bot API client below is built against a newer
    // client core than this build pins elsewhere and raises it transitively, so without this the
    // engine and the negotiation pair would sit at 3.2 over a 3.5 core — a mix whose failure mode
    // is a missing method at run time rather than a compile error. The catalogue says why the two
    // versions exist.
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // The Bot API client, named in one file only — `adapter/TelegramAdapter.kt` — which
    // `:archtest` asserts over the source rather than leaving to review.
    implementation(libs.tgbotapi)

    // One update is handled per coroutine, so a request waiting on generation does not hold the
    // next person's message behind it.
    implementation(libs.kotlinx.coroutines.core)

    // The logging binding, for the same reason the other two processes carry one. Here the lines
    // worth reading are the refusal at the edge — a message from a chat that is not on the
    // allowlist is answered with silence, so the log is the only place it is visible at all — and
    // a backend this process could not reach.
    implementation(libs.slf4j.simple)

    // The client's own test engine: it answers from a script instead of a socket, so the request
    // this process sends and its handling of every refusal are testable without a backend, and
    // therefore in CI.
    testImplementation(libs.ktor.client.mock)
}
