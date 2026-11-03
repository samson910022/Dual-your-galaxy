// Hook module: LSPosed module library, loaded only in target processes.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Library config for the Xposed module artifact.
android {
    namespace = "com.galaxydual.hook"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
        // Consumer rules keep hook entry points from being obfuscated.
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

// LibXposed API is compileOnly so the host LSPosed framework provides it at runtime.
dependencies {
    implementation(project(":core"))
    compileOnly("io.github.libxposed:api:102")
}
