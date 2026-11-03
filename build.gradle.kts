// Root build file: applies shared plugin versions via version catalog.
plugins {
    // Version references come from gradle/libs.versions.toml.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Shared compile SDK for subprojects (enforced in module build files).
// compileSdk = 34, AGP 8.x, Kotlin 2.x, JDK 17 (see .github/workflows/android.yml).
tasks.register("printVersions") {
    // Helper task for CI diagnostics.
    group = "help"
    description = "Prints catalog versions for debugging."
    doLast {
        println("AGP: ${libs.versions.agp.get()}, Kotlin: ${libs.versions.kotlin.get()}")
    }
}
