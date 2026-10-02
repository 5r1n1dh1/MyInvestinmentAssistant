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
val androidSdkAvailable: Boolean = run {
    val localProps = file("local.properties")
    val hasLocal = localProps.exists() && localProps.readLines().any { it.startsWith("sdk.dir") }
    hasLocal || System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
}

if (androidSdkAvailable) {
    include(":android-data")
    include(":app")
}