// An included build rather than `buildSrc`, which is what Gradle's own structuring guidance
// prefers. Two of its reasons bite here: any change in `buildSrc` makes the *entire* build
// out of date, where an included build only invalidates what consumes it — and this build
// cares about invalidation, since `:archtest` had to declare the project's source as an
// input to keep its rules from going stale. The other is that `buildSrc` silently applies
// the `java` plugin whether or not it is wanted.

rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // The build's own plugin versions come from the same catalogue as everything else, so
    // "every version lives in gradle/libs.versions.toml" holds for the build logic too.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
