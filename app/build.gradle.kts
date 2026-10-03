import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// plan.md §4.1 / §4.2 — the Compose UI module.
//
// Module boundary (plan.md §4.3, docs/CONTRACTS.md): `:app` MUST NOT import `:data`.
// Everything on screen is a `:domain` type, so the only hard dependency on `:data` is the
// transitive one `:android-data` re-exports; no file under `app/src` names a
// `com.mia.app.data.*` type. That is checked in review and must stay true.
//
// This module is conditionally excluded from the build (see settings.gradle.kts) because the
// build machine has no Android SDK, so nothing here can be compiled or linted here. That is
// exactly why the code is written to be boring and explicit, and why `lint.abortOnError` is
// off: a lint finding must never be able to block the pure-JVM `:domain` / `:data` suites.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.mia.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    sourceSets {
        getByName("main") {
            assets.srcDirs("$rootDir/data/src/main/resources")
        }
    }

    defaultConfig {
        applicationId = "com.mia.app"

        // plan.md §11 P0 + the version catalog: 26 is the floor the whole design assumes
        // (`java.time` on the desugared path, `NotificationChannel`, WorkManager 2.10).
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Release signing is supplied entirely by the environment, and is **optional**.
        //
        // The whole point of plan.md G1 is that this app costs nothing to build and needs no
        // secrets, so a missing keystore must degrade to an unsigned release rather than fail the
        // build. CI sets the four KEYSTORE_* variables when a signing secret exists and leaves them
        // empty otherwise; `assembleRelease` then still produces an APK that installs via
        // `adb install` and can be side-loaded, just without a Play-upload signature.
        //
        // The values are read as Gradle properties rather than from `local.properties` because CI
        // passes them as environment variables, and reading both keeps a developer's machine and a
        // runner working from one code path.
        create("release") {
            val keystorePath = providers.gradleProperty("KEYSTORE_PATH")
                .orElse(providers.environmentVariable("KEYSTORE_PATH"))
                .orNull
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = signingValue("STORE_PASSWORD")
                keyAlias = signingValue("KEY_ALIAS")
                keyPassword = signingValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // plan.md G1: no API keys, no build-time configuration, no flavour matrix. The
            // release variant is the debug variant with a different name.
            isMinifyEnabled = false
        }
        release {
            // R8 stays **off**, deliberately, and this is worth being explicit about because
            // `isMinifyEnabled = false` on a release build looks like an oversight.
            //
            // Two independent reasons:
            // 1. plan.md G1 makes this app free to build with no configuration, and turning R8 on
            //    introduces a class of failure (a stripped reflection entry point, a renamed Hilt
            //    or Room generated class) that is invisible until a release APK is installed —
            //    and the build machine here has no Android SDK, so that failure could not be
            //    caught before shipping. The APK is a few hundred KB larger and that is the price.
            // 2. `release` is currently signed with the debug key or unsigned in CI, so it is not
            //    distributable via Play anyway. R8 earns its risk back when real signing exists.
            //
            // If it is ever enabled, `proguard-rules.pro` already exists with the keep rules
            // kotlinx.serialization and Room need.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Falls back to the debug signing config when no keystore was supplied, so
            // `assembleRelease` produces an installable APK instead of failing on a missing
            // signing config.
            signingConfig = signingConfigs.findByName("release")
                ?.takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // No `BuildConfig` field is read anywhere in this module, so the generated class is
        // pure cost on a 2 GB-RAM device's dex budget (plan.md §12).
        buildConfig = false
    }

    lint {
        // See the note at the top of this file: this module is never compiled on the build
        // machine, so a lint failure must not be able to block the JVM test suites.
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // Hilt's generated sources are only needed by the app module itself; Room codegen lives in
    // `:android-data`. `arg` is set here so a future Room entity in this module has a home.
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * One signing value, read from a Gradle property or the environment.
 *
 * `[providers.gradleProperty(x)]` is Kotlin's bracket access on a provider, which reads like
 * `gradleProperty(x)` and is *not* a call into the provider — it is only valid inside
 * `build.gradle.kts`, which is the only place this is used.
 *
 * Returns an empty string rather than `null` so a missing value becomes an explicit signing
 * failure with a clear message from AGP, instead of a `NullPointerException` inside this function.
 */
fun signingValue(name: String): String =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orNull
        ?: ""

dependencies {
    // `:domain` is declared explicitly even though `:android-data` re-exports it, because the UI
    // is allowed to depend on `:domain` and on nothing else (plan.md §4.3).
    implementation(project(":domain"))

    // `:android-data` for the Room/Hilt/WorkManager graph. Screens reach data only through the
    // narrow interface each one owns (see `ui/technicals/TechnicalsDataSource.kt`), but
    // `MiaApplication` implements `Configuration.Provider` and hands WorkManager a
    // `HiltWorkerFactory`, and `ui/di/` adapts the repositories. So `:android-data` is named
    // here even though no *screen* imports it.
    implementation(project(":android-data"))

    // Required because `:android-data` installs its WorkManager graph into the singleton
    // component, so Hilt generates a field of type `WorkManager` in `:app`'s component. Without
    // this on the compile classpath `:app` does not compile. It is `implementation`, not `api`,
    // so nothing else in the module can name a WorkManager type by accident.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose: every version comes from the BOM, so the UI / graphics / material3 trio can
    // never drift apart.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
}
