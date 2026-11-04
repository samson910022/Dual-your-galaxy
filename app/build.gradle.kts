// App module: Android application shell hosting MainActivity.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Application-specific Android configuration.
android {
    namespace = "com.galaxydual.plus"
    // SDK levels are single-sourced from gradle/libs.versions.toml.
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.galaxydual.plus"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            // Skeleton keeps minification off for readability.
            isMinifyEnabled = false
        }
        debug {
            // Debug suffix avoids clashing with release installs.
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// App depends on core for privilege routing and parsing.
dependencies {
    implementation(project(":core"))
    // Shizuku API V2 + binder provider for rikka.shizuku.ShizukuProvider (manifest).
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
