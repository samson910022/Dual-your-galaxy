// Core module: pure Kotlin logic with no Android UI dependencies.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Android defaults shared by all modules.
android {
    namespace = "com.galaxydual.core"
    // SDK levels are single-sourced from gradle/libs.versions.toml.
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // Consumer ProGuard rules are empty for this skeleton.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Core depends only on kotlinx-coroutines-core (CancellationException
// rethrow discipline in DualRepository gateway defense, PR-2 Phase 3) plus
// JUnit for unit tests.
dependencies {
    implementation(libs.coroutines.core)
    testImplementation("junit:junit:4.13.2")
}
