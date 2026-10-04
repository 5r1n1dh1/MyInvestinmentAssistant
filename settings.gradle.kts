// MyInvestinmentAssistant — multi-module monorepo (plan.md §11.1 OQ-3).
//
// Module boundaries are enforced AT COMPILE TIME (plan.md §4.3):
//   :domain       pure Kotlin/JVM — no Android, no network, no Room. All pattern /
//                 indicator / red-flag / accuracy logic. Unit tests run on plain JVM.
//   :data         pure Kotlin/JVM — parsers + aggregation (bhavcopy, screener.in,
//                 Yahoo history, EQUITY_L master). Talks to the network only through
//                 an injected HttpTransport, so it is testable with fakes.
//   :android-data Android library — Room entities/DAOs, Hilt modules, OkHttp/Retrofit
//                 wiring, WorkManager. Implements the interfaces declared in :data.
//   :app          Android application — Compose UI, ViewModels, navigation.
//
// :domain and :data never depend on :android-data or :app.
// :app never talks to :data directly; it goes through :domain-facing repositories.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MyInvestinmentAssistant"

// Pure-JVM modules: always buildable, always testable without an Android SDK.
include(":domain")
include(":data")

// Android modules are included only when an SDK is available (local.properties sdk.dir
// or ANDROID_HOME). This keeps `./gradlew :domain:test` working on machines that have
// no Android SDK installed — see docs/ENVIRONMENT.md.
//
// Two extra rules, both learned from CI failures:
//
// 1. `-PskipAndroid` forces the Android modules OUT even when an SDK is detectable. The
//    SDK check alone is not enough on a GitHub-hosted runner: `ANDROID_SDK_ROOT` is
//    *pre-set* on ubuntu-latest, so the check below returned true, `:app` and
//    `:android-data` were included, and AGP was applied during the pure-JVM test step.
//    That step then died on an AGP/Gradle incompatibility before a single engine test
//    ran — the exact failure this guard exists to prevent. CI now passes -PskipAndroid,
//    so `:domain:test`/`:data:test` are genuinely SDK-free.
//
// 2. An explicit opt-in (`-PincludeAndroid`) overrides the SDK check. Android Studio does
//    not pass -PskipAndroid, so local sync still picks the Android modules up from
//    `local.properties` as before; the opt-in is for anyone configuring the SDK by
//    environment variable only.
// Presence, not value. `-PskipAndroid` on the command line sets the property to an EMPTY
// STRING, not "true" — Gradle only assigns a value with `-Pkey=value`. So parsing the value
// with toBoolean() reads "" -> false and silently does nothing, which is precisely the bug
// this guard exists to prevent. isPresent is the only correct test for a bare flag.
//
// These are read as bare flags for that reason: pass `-PskipAndroid`, not
// `-PskipAndroid=true`.
val skipAndroid: Boolean = settings.providers.gradleProperty("skipAndroid").isPresent
val forceAndroid: Boolean = settings.providers.gradleProperty("includeAndroid").isPresent

val androidSdkAvailable: Boolean = run {
    val localProps = file("local.properties")
    val hasLocal = localProps.exists() && localProps.readLines().any { it.startsWith("sdk.dir") }
    hasLocal || System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
}

// Rule 2 wins over both the SDK check and -PskipAndroid: an explicit opt-in means the
// caller wants the Android modules even in an environment that would otherwise exclude them.
val includeAndroid = forceAndroid || (androidSdkAvailable && !skipAndroid)

if (includeAndroid) {
    include(":android-data")
    include(":app")
} else {
    // Logged because a silently-narrowed build is the kind of thing that wastes an
    // afternoon: `./gradlew build` looks like it covered everything, but half the modules
    // were never in the build.
    logger.lifecycle(
        "Android modules excluded (-PskipAndroid=$skipAndroid, sdkAvailable=$androidSdkAvailable). " +
            ":domain and :data only. Drop -PskipAndroid to build them.",
    )
}