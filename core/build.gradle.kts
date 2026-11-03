// Core module: pure Kotlin logic with no Android UI dependencies.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Android defaults shared by all modules.
android {
    namespace = "com.galaxydual.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
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

// No external dependencies for the skeleton core.
dependencies {
}
