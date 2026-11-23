plugins {
    id("main-brain.kotlin-conventions")
    alias(libs.plugins.kotlin.serialization)
}

// Wire types shared by the bot and the backend. Depends on no module: a DTO that needed a
// domain type would mean the wire format had started tracking the model.
//
// One library, and `api` rather than `implementation`. The generated serialisers are part of
// what these types offer — both processes configure a `Json` and hand it these DTOs — so a
// caller that could not see the library could not use the module for the one thing it is for.
dependencies {
    api(libs.kotlinx.serialization.json)
}
