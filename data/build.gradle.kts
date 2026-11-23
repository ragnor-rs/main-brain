plugins {
    id("main-brain.kotlin-conventions")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":domain"))

    // All four are `implementation`, not `api`. Three concerns — the pool, the migrator and the
    // driver — across four coordinates, because the migrator ships its Postgres support
    // separately. They are this module's business, and a caller that could see them could open
    // its own connection; `Database` is the only way through, which is the point of the seam.
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    // `implementation` for the same reason, and it matters more here than for the store. `:ingest`
    // depends on this module, so an `api` dependency would put the model SDK on the compile path
    // of the one process that must never hold the model credential. Behind `implementation` there
    // is no SDK type for ingest code to name.
    //
    // **Compile path only, and the distinction is worth stating rather than discovering.** A
    // runtime dependency is transitive whatever the scope, so the SDK and its transport are in
    // `ingest.jar` as well as the backend's. Keeping them out would mean the adapter living in a
    // module the ingest process does not depend on, which the repository layout in
    // `docs/architecture.md` does not call for. So what the build enforces is that ingest cannot
    // be *written* against the model; what keeps the credential away from it is the container
    // environment, which withholds the key.
    implementation(libs.anthropic)

    // The sidecar is reached over HTTP, so the outbound client lives here with every other
    // adapter. `implementation` for the same reason the store dependencies are, and with the
    // same qualification as the model SDK above: it keeps the client off another module's
    // compile path, so ingest code cannot be *written* against it, but a runtime dependency is
    // transitive whatever the scope and the client is in `ingest.jar` regardless.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)

    // Store code is tested against a real Postgres. Mocking it would prove that the mock
    // behaves like the mock.
    //
    // The container is started by `StoreRig` rather than by the JUnit annotation, so the
    // `junit-jupiter` Testcontainers artifact is not needed: the annotation's lifecycle is per
    // test class, which would start a container and run the migrations once per class.
    testImplementation(libs.testcontainers.postgresql)

    // The client's own test engine: it answers from a script instead of a socket, so the
    // provider's request shape and its handling of a refusal are both testable without a
    // sidecar — and therefore in CI, which reaches no network.
    testImplementation(libs.ktor.client.mock)
}
