import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * What every Kotlin module in this build shares: the toolchain, the linter, and JUnit 5.
 *
 * It exists so those three facts are stated once. Copied into seven build files they drift,
 * and the one that drifts is always the module nobody has opened in a month.
 */

plugins {
    kotlin("jvm")
    // For `api` versus `implementation`. A module that exposes another module's types in its
    // own signatures has to say so, or the layering graph is narrower in the build files than
    // it is in fact.
    `java-library`
    id("io.gitlab.arturbosch.detekt")
}

// Precompiled script plugins get no generated `libs` accessor, so the catalogue is read through
// the extension instead. The versions are still the catalogue's and nowhere else's.
val libs = the<VersionCatalogsExtension>().named("libs")

repositories {
    mavenCentral()

    // The Telegram client library is published to its maintainer's own repository and not to
    // Maven Central, so reaching it needs this second source.
    //
    // **The content filter is the point of this block, not an optimisation.** A repository with
    // no filter is consulted for every dependency the build resolves, in declaration order, so
    // whoever runs it is in a position to answer for `org.jetbrains.kotlin` or `io.ktor`
    // first — a compromise there would reach the compiler rather than one adapter. Naming the
    // one group it may answer for bounds that to the library we came here for. Everything else
    // continues to resolve from Central and this host is never asked.
    //
    // The artifacts it serves are checksum-pinned in `gradle/verification-metadata.xml`, which
    // is the other half: this says what the host may answer for, that says what the answer has
    // to be.
    exclusiveContent {
        forRepository {
            maven {
                name = "tdlight"
                url = uri("https://mvn.mchv.eu/repository/mchv/")
            }
        }
        filter { includeGroup("it.tdlight") }
    }
}

kotlin {
    jvmToolchain(21)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt.yml"))
}

dependencies {
    detektPlugins(libs.findLibrary("detekt-formatting").get())

    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("kotlin-test").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
