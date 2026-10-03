import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// plan.md §11 P10 — the Android persistence / DI / scheduling layer.
//
// This module is the ONLY place that knows about Room, Hilt, OkHttp or WorkManager. It
// implements the interfaces declared in `:data` and exposes repositories to `:app`; per
// docs/CONTRACTS.md, `:app` must not import `:data` directly, so every repository return
// type here is a `:domain` type or a plain Kotlin type declared in `com.mia.app.data.android`.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.mia.app.data.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // plan.md: no API below 26 is needed — `NotificationChannel` and `java.time` on the
        // desugared path both land at 26, which is also the floor WorkManager 2.10 supports.
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // plan.md needs no BuildConfig constants here (the app carries the version name it
        // renders in the about screen), and turning it off keeps generated sources minimal.
        buildConfig = false
    }

    testOptions {
        // The JVM unit tests in this module are deliberately Android-free, so nothing should
        // ever need a default value — but returning defaults instead of throwing "not mocked"
        // turns an accidental Android call in a test into an assertion failure that names it.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // This module cannot be compiled on a machine without an Android SDK, so lint findings
        // must not be able to block the pure-JVM `:domain` / `:data` suites either.
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
    // Room exports the schema of every version it compiles so `DatabaseMigrations` can be
    // written (and reviewed) against real SQL rather than from memory.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    // Java codegen is left at the default deliberately: `room.generateKotlin` is newer than
    // "the most stable form of the API", and generated Kotlin entity code is not needed here.
}

dependencies {
    // `:data` re-exports `:domain` as `api`, so every `:domain` model type (Candle, Ratios,
    // Match, ParserHealth, …) is visible here and to `:app` without a second declaration.
    api(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // HTTP. Only `HttpTransport` is implemented here — `:data` never sees OkHttp.
    implementation(libs.okhttp)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Pure-JVM unit tests only (RefreshSchedule maths, mappers, the scan coordinator).
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}