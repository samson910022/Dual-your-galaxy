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
        // Keeps the Xposed entry point from obfuscation. Resource files under
        // src/main/resources (META-INF/xposed/java_init.list, scope.list,
        // module.prop) are packaged into the APK/AAR automatically by AGP.
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
    compileOnly("io.github.libxposed:api:102.0.0")
    // Optional compile-time annotations used by the API surface (e.g. @SinceApi).
    compileOnly("io.github.libxposed:annotation:1.0.0")
}
