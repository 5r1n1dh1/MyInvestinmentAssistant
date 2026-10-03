# Environment & build

## Quick start

```bash
# Pure-JVM modules — no Android SDK needed. This is the whole engine: every indicator,
# detector, scorer, red flag, parser and the accuracy tracker, with its tests.
./gradlew :domain:test :data:test

# Everything at once
./gradlew checkAll

# The Android app (needs an Android SDK — see below)
./gradlew :app:assembleDebug
```

## Module layout and why

```
:domain        pure Kotlin/JVM   pattern engine, indicators, red flags, ratios, accuracy
:data          pure Kotlin/JVM   parsers + aggregation, IO behind an injected transport
:android-data  Android library   Room, Hilt, OkHttp, WorkManager
:app           Android app       Compose UI, ViewModels, navigation
```

`settings.gradle.kts` includes `:android-data` and `:app` **only when an Android SDK is
detected** (`local.properties` `sdk.dir`, or `ANDROID_HOME`/`ANDROID_SDK_ROOT`). Without one,
`./gradlew :domain:test` still works — deliberately, because the parts of this app most worth
testing are the parts that do not need a device.

This is not a convenience. plan.md §12 requires `domain/` to be pure so it is unit-testable on
the JVM with no Robolectric, and a build that refuses to run at all without an SDK makes that
guarantee untestable in CI-sized environments.

## Toolchain

| Component | Version | Note |
|---|---|---|
| JDK | **21** (Temurin 21.0.12.1) | Newest LTS AGP 8.13 supports reliably |
| Gradle | 9.8.0 | via `./gradlew` |
| Kotlin | 2.2.21 | Room 2.8.x needs Kotlin 2.0+ / KSP2 |
| AGP / compileSdk / minSdk | 8.13.2 / 36 / 26 | minSdk 26 gives `java.time` without desugaring |
| JUnit | 5.11.4 | |

Version pins live in `gradle/libs.versions.toml`.

### JDK 21 is a hard requirement

`jvmToolchain(21)` is set in both JVM modules. The machine this was built on had only a JRE 25
stub (no `javac`), so a Temurin 21 JDK was installed at `~/.local/tools/jdk/jdk-21.0.12.1+1`
and pointed at from `~/.gradle/gradle.properties`:

```properties
org.gradle.java.home=/path/to/jdk-21
org.gradle.java.installations.paths=/path/to/jdk-21
```

That file is machine-local and **not** in the repository. On a normal dev machine with a JDK 21+
on `PATH`, Gradle's toolchain detection handles it and no configuration is needed.

## Android SDK

`:app` and `:android-data` have **never been compiled** — this machine has no Android SDK. See
`docs/VERIFICATION.md` for what that leaves unverified and what was done instead. To build them:

1. Install `cmdline-tools`, then `platforms;android-36` and `build-tools;36.0.0`.
2. `sdkmanager --licenses` (accept all).
3. Point Gradle at it: `sdk.dir=/path/to/android-sdk` in `local.properties`, or export
   `ANDROID_HOME`.
4. `./gradlew :app:assembleDebug`

Expect to fix things on that first build: it is the first true compile of the Compose layer.
`docs/VERIFICATION.md` lists the specific API calls that were verified only by reading source.

## Verified commands

```bash
./gradlew :domain:test :data:test   # 1,337 tests — 853 domain + 484 data, 0 failures
```

> If `./gradlew` reports "Permission denied", run `chmod +x gradlew`. Git records the executable bit
> only when `core.fileMode` is true, so the wrapper can arrive as mode 644 on a fresh clone. CI does
> this for you.

## CI

`.github/workflows/build.yml` builds on every push and pull request to `main`, and on manual
dispatch. The step order is deliberate:

1. **JDK 21**, then the pure-JVM suites *before* the SDK exists. Those 1,337 tests need no Android
   SDK, so a broken parser or detector fails in about a minute instead of after a full SDK download —
   and they are the only part of this build that has actually been executed before.
2. **Android SDK + licences**, writing `local.properties`. Load-bearing rather than cosmetic:
   `settings.gradle.kts` includes `:android-data` and `:app` *only when an SDK is detectable*, so no
   task touching them will even configure until that file exists.
3. **Debug keystore**, generated if absent. `assembleRelease` needs *a* signing config.
4. **Upload keystore, optional.** With none configured, release falls back to the debug key and
   still produces an installable APK — deliberate, per plan.md G1 (no keys, no cost).
5. **Build, then verify the signature** with `apksigner` rather than assuming it.
6. **Upload** the APKs, plus the build log and reports on failure.

The workflow deliberately does **not** pass `--gradle-version` to `gradle wrapper`, unlike the
reference workflow it is modelled on. The committed wrapper pins Gradle 9.8.0 — the version this
build was written against — and regenerating with a different number would silently move the
project.

R8 is off (see `app/build.gradle.kts` for the reasoning), so `proguard-rules.pro` is present and
documented but inactive.

## Test layout

| Path | Holds |
|---|---|
| `domain/src/test/kotlin/…/technical/` | indicator golden values + invariants (RSI bounds, warm-up nulls, flat-series handling) |
| `domain/src/test/kotlin/…/pattern/` | zigzag properties, the three hand-computed score fixtures, and the **P7 discrimination gate** |
| `domain/src/test/kotlin/…/pattern/detectors/` | per-pattern synthetic formations, near-misses, polarity, and real-data detection rates |
| `domain/src/test/kotlin/…/redflag/` | per-rule fires / quiet / cannot-evaluate, sector overrides, evidence completeness |
| `domain/src/test/kotlin/…/accuracy/` | hit-rate gating, idempotency, and a real-data simulation |
| `data/src/test/kotlin/…/price,fundamentals,symbols,history,aggregate/` | golden-file parser tests against committed real responses |

Golden fixtures are committed under `data/src/test/resources/fixtures/` and
`domain/src/test/resources/fixtures/` so the suite runs with no network at all.

## Adding a pattern

1. Write a `PatternDetector` in `domain/src/main/kotlin/com/mia/app/domain/pattern/detectors/…`.
2. Register the name in `PatternIds.CANONICAL` (ids key the accuracy table — decision D6).
3. Emit weighted `Measure`s, never a score.
4. Add it to `DetectorRegistry.all()` in `:android-data`. That single list is the line of the app
   that says which patterns ship.
5. If the pattern has a textbook direction (and no measured move), add it to `PatternIds.DIRECTIONAL_BIAS`
   so the regime gate can evaluate it.

## Known environment quirks

- **`write` can silently drop backticks**, producing `fun \`name() {` with no closing
  backtick. The Kotlin lexer then swallows text to the *next* backtick and reports
  `Syntax error: Expecting '('` at an unrelated-looking line, with error positions that shift
  between runs. Check for unbalanced backticks when a compile error points somewhere implausible.
- The `nsearchives.nseindia.com` host is unreachable from this network; the
  `archives.nseindia.com` mirror serves the identical file. See `docs/DATA-NOTES.md` §1.
- Yahoo Finance returns 429 from this IP. See `docs/DATA-NOTES.md` §2.