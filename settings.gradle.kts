pluginManagement {
    // The convention plugins live in their own build, so they have to be visible to plugin
    // resolution before any module's `plugins { }` block is read.
    includeBuild("build-logic")

    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Resolves a JVM 21 toolchain by download when the machine has none, so a clean clone
    // builds without anyone being told which JDK to install.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "main-brain"

// The six modules of the layering graph. `:archtest` is not one of them: it holds the rules
// that the graph cannot express and depends on no module, because it reads source rather than
// linking against it.
include(":contract")
include(":domain")
include(":data")
include(":bot")
include(":backend")
include(":ingest")
include(":archtest")
