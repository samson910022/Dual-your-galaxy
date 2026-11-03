// Root settings: declares modules used by this project.
pluginManagement {
    // Load version catalog from gradle/libs.versions.toml.
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Enable version catalog sharing across modules.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "Galaxy-Dual-app-Plus"

// Application, shared logic, and LSPosed hook modules.
include(":app")
include(":core")
include(":hook")
