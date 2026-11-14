// App module: Android application shell hosting MainActivity.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.io.File
import java.util.Properties

// Release signing (Phase E1, issue #5): credentials are injected via
// environment / CI secrets and NEVER committed (see docs/KEY_MANAGEMENT.md).
//   SIGN_STORE_FILE / SIGN_STORE_PASSWORD / SIGN_KEY_ALIAS / SIGN_KEY_PASSWORD
// Local fallback (non-CI only): ~/.android-keys/Galaxy-Dual-app-Plus-release.properties
//   with keys storeFile/storePassword/keyAlias/keyPassword.
// Missing-key policy: CI (CI=true or GITHUB_ACTIONS=true) fails fast with a
// clear error and never falls back to an unsigned build -- but only when a
// release packaging task was actually requested. Non-release tasks
// (assembleDebug, test, lint, check, ...) only warn so PR CI stays green.
// Local builds fall back to the debug key with a loud warning so development
// stays buildable.
val isCiBuild = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"
// Fail-fast gate: only release packaging (or full aggregates that include
// release) must fail when the key is missing. Checked at configuration time
// via the requested task names so :app:assembleDebug / lint / test on CI
// are never blocked by a missing release key.
val requestedTaskNames = gradle.startParameter.taskNames
// Explicit, narrow allowlist for the release *manifest-merge-only* CI step
// (workflow passes ALLOW_RELEASE_MANIFEST_MERGE=1). Only tasks that merge
// manifests WITHOUT packaging/signing may run under this flag; any other
// release-flavored task is still governed by requestsReleasePackaging.
val allowReleaseManifestMerge = System.getenv("ALLOW_RELEASE_MANIFEST_MERGE") == "1" &&
    requestedTaskNames.isNotEmpty() &&
    requestedTaskNames.all { taskName ->
        val simple = taskName.substringAfterLast(":")
        listOf("processReleaseMainManifest", "processReleaseManifest", "processReleaseResources")
            .any { it.equals(simple, ignoreCase = true) }
    }
val requestsReleasePackaging = requestedTaskNames.any { taskName ->
    taskName.contains("Release", ignoreCase = true) ||
        taskName.equals("assemble", ignoreCase = true) ||
        taskName.equals("build", ignoreCase = true) ||
        taskName.equals(":app:assemble", ignoreCase = true) ||
        taskName.equals(":app:build", ignoreCase = true) ||
        taskName.equals("app:assemble", ignoreCase = true) ||
        taskName.equals("app:build", ignoreCase = true)
}

fun releaseSigningProps(): Map<String, String> {
    val fromEnv = mapOf(
        "storeFile" to (System.getenv("SIGN_STORE_FILE") ?: ""),
        "storePassword" to (System.getenv("SIGN_STORE_PASSWORD") ?: ""),
        "keyAlias" to (System.getenv("SIGN_KEY_ALIAS") ?: ""),
        "keyPassword" to (System.getenv("SIGN_KEY_PASSWORD") ?: "")
    )
    if (fromEnv.values.all { it.isNotEmpty() }) return fromEnv
    // Local-only fallback: developer keystore reference outside the repo.
    if (!isCiBuild) {
        val propsFile = File(
            System.getProperty("user.home"),
            ".android-keys/Galaxy-Dual-app-Plus-release.properties"
        )
        if (propsFile.isFile) {
            val loaded = Properties()
            propsFile.inputStream().use { loaded.load(it) }
            val fromProps = mapOf(
                "storeFile" to (loaded.getProperty("storeFile") ?: ""),
                "storePassword" to (loaded.getProperty("storePassword") ?: ""),
                "keyAlias" to (loaded.getProperty("keyAlias") ?: ""),
                "keyPassword" to (loaded.getProperty("keyPassword") ?: "")
            )
            if (fromProps.values.all { it.isNotEmpty() }) return fromProps
        }
    }
    return emptyMap()
}

val releaseSigningProps = releaseSigningProps()
val releaseSigningReady =
    releaseSigningProps.isNotEmpty() &&
        releaseSigningProps.values.all { it.isNotEmpty() } &&
        File(releaseSigningProps.getValue("storeFile")).isFile

// Application-specific Android configuration.
android {
    namespace = "com.galaxydual.plus"
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    // SDK levels are single-sourced from gradle/libs.versions.toml.
    compileSdk = libs.versions.compileSdk.get().toInt()

    // Phase A1 P0: BuildConfig.DEBUG gates the GalaxyExecService deny-path logs.
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.galaxydual.plus"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
        // Phase P29 (issue #29): instrumentation harness runner.
        // connectedCheck needs a device/emulator (CI has none, so PR CI only
        // compiles androidTest via :app:assembleDebugAndroidTest); the runner
        // entry must still be declared so emulator/manual runs can execute.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only registered when a real key is present: an empty named config
        // would fail AGP validation, and CI must fail explicitly below.
        if (releaseSigningReady) {
            create("release") {
                storeFile = File(releaseSigningProps.getValue("storeFile"))
                storePassword = releaseSigningProps.getValue("storePassword")
                keyAlias = releaseSigningProps.getValue("keyAlias")
                keyPassword = releaseSigningProps.getValue("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Skeleton keeps minification off for readability.
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            } else if (isCiBuild && requestsReleasePackaging && !allowReleaseManifestMerge) {
                throw GradleException(
                    "Release signing key missing in CI: set SIGN_STORE_FILE " +
                        "(restored from SIGN_STORE_BASE64), SIGN_STORE_PASSWORD, " +
                        "SIGN_KEY_ALIAS and SIGN_KEY_PASSWORD secrets; refusing unsigned release build."
                )
            } else if (isCiBuild) {
                logger.warn(
                    "WARNING: no release signing key found in CI and no release task " +
                        "requested (${requestedTaskNames}); continuing without release signing. " +
                        "Release packaging will fail fast once a Release/assemble/build task is requested."
                )
            } else {
                logger.warn(
                    "WARNING: no release signing key found (SIGN_* env vars or " +
                        "~/.android-keys/Galaxy-Dual-app-Plus-release.properties); " +
                        "release APK will carry the DEBUG signature and MUST NOT be published."
                )
                signingConfig = signingConfigs.getByName("debug")
            }
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
    // D1 single-APK: hook ships inside this APK so DualHook +
    // META-INF/xposed/* install as one LSPosed module + UI artifact.
    implementation(project(":hook"))
    // Shizuku API V2 + binder provider for rikka.shizuku.ShizukuProvider (manifest).
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // Stage2 (hook-policy-source P1): module-app side of the LSPosed remote
    // preferences channel. The service AAR contributes XposedProvider
    // (authority ${applicationId}.XposedService) via manifest merge; GalaxyApp
    // registers XposedServiceHelper to obtain the XposedService binder.
    implementation(libs.libxposed.service)
    // Phase F2 (issue #6): observable state (StateFlow + viewModelScope) and
    // lifecycle-aware collection in MainActivity (lifecycleScope/viewModels).
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.ktx)
    // Phase F4a (issue #6): JVM unit tests for the device-free parts of the
    // true channels (su argv assembly, Shizuku error mapping, capture helper).
    // Same pinned junit:junit:4.13.2 the core module already uses (cached).
    testImplementation("junit:junit:4.13.2")
    // PR-32 P2-2: deterministic DualViewModel tests (dispatchers/heartbeat/
    // dedup/channel queue). Test-only, production dependencies unchanged.
    // PR-4 P1 (issue #23): RemoveConfirmTest drives DualViewModel (viewModelScope
    // needs Dispatchers.Main). kotlinx-coroutines-test provides setMain/resetMain
    // so JVM tests can replace Main with Unconfined; same coroutines line as
    // the app (1.9.0).
    testImplementation(libs.coroutines.test)
    // Phase P29 (issue #29): instrumentation harness (androidTest only,
    // production untouched). PR CI runs the full harness on the
    // locale-instrumentation job (API 29 emulator) and compiles these via
    // :app:assembleDebugAndroidTest in the build job; an additional manual
    // profile (API 34) runs on workflow_dispatch (see android.yml
    // instrumentation-manual). Fake gateway keeps every test zero-write:
    // no test ever calls confirmRemove()/clone on a real gateway.
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.junit.junit)
}
