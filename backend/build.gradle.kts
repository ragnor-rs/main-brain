plugins {
    id("main-brain.process-conventions")
}

tasks.shadowJar {
    manifest { attributes["Main-Class"] = "io.github.ragnorrs.mainbrain.backend.MainKt" }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":contract"))

    // The inbound surface. `implementation` throughout: nothing depends on a process module, so
    // there is no consumer for any of it to be `api` for.
    //
    // No serialisation plugin here. Every wire type is declared in `:contract`, which carries the
    // plugin and generates the serialisers; this module configures a `Json` and hands it those
    // types. A `@Serializable` class in this module would be a wire type outside the wire format.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)

    // The JSON tree, for the one line the evaluation set parses out of the log. No plugin and no
    // `@Serializable` class, for the reason stated above — this builds a `JsonObject` by hand,
    // which needs the runtime and no code generation.
    implementation(libs.kotlinx.serialization.json)

    // The logging binding, for the same reason the ingest process carries one: without it the
    // facade discards everything the server and this code log and prints a warning saying so.
    // Refusals at the edge and unhandled failures are the two things here worth reading, and a
    // refusal nobody can see is the one that matters.
    implementation(libs.slf4j.simple)

    // Serves the application in-process, so status codes, the refusal at the edge and every
    // response shape are testable without a socket, a database or a model call.
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.core)
}

// The wire body `:bot`'s live-wire case is driven by, which the case below holds to what this
// surface really serialises. Declared as an input for the reason `:domain` declares the evaluation
// fixtures: a hand edit to the file has to re-run the test that checks it, or the gate would keep
// reporting the green it earned before the two disagreed.
val wireFixture = rootProject.layout.projectDirectory.file("bot/src/test/resources/wire/long-sectioned-answer.json")

tasks.test {
    inputs.file(wireFixture)
        .withPropertyName("wireFixture")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("mainbrain.wireFixture", wireFixture.asFile.absolutePath)
}
