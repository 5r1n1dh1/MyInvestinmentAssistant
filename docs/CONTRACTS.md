# Shared build contracts (read before writing code)

You are one of several agents working on this repo in parallel. **Other agents own other
files.** Do not edit files you do not own; if you need a change in a file you do not own,
note it in your final report instead.

## Repo layout

```
settings.gradle.kts        :domain (pure JVM)  :data (pure JVM)  [:android-data, :app if SDK]
domain/                    com.mia.app.domain.*    — pure Kotlin, no Android, no network
data/                      com.mia.app.data.*      — parsers + aggregation, IO behind interfaces
android-data/              Room, Hilt, OkHttp, WorkManager  (only builds when an Android SDK exists)
app/                       Compose UI + ViewModels          (only builds when an Android SDK exists)
```

Module boundaries are compile-time enforced (plan.md §4.3):
- `:domain` must NOT depend on `:data`, Room, Retrofit or any Android class.
- `:data` must NOT use Android classes. Network access goes through an injected transport.
- `:app` must not import `:data` directly.

## Environment

- **There is NO Android SDK on this machine.** Gradle therefore only includes `:domain` and
  `:data` (see the conditional in `settings.gradle.kts`). `:app` and `:android-data` code is
  written and reviewed but cannot be compiled here — be extra careful with it, and prefer
  code you can reason about statically.
- Build with:
  ```bash
  cd /home/srinidhi/Documents/Google_AI_Studio/MyInvestinmentAssistant
  ~/.local/tools/gradle-9.8.0/bin/gradle --console=plain :domain:test :data:test
  ```
- JDK 21 (Temurin) is already wired as the Gradle toolchain. Do not change toolchain config.
- Kotlin 2.2.21, Gradle 9.8. `jvmToolchain(21)`. JUnit 5 via `kotlin.test`.
- Android module versions are already in `gradle/libs.versions.toml`; use the aliases
  (`libs.androidx.room.runtime`, `libs.hilt.android`, …) rather than hardcoding versions.

## Already-written contracts — import these, do not redefine them

`domain/src/main/kotlin/com/mia/app/domain/model/`
- `Market.kt` — `Timeframe` (D1/W1), `Source`, `Confidence`, `Sector`, `Candle`, `Merged<T>`, `Ratios`
- `Pattern.kt` — `Category`, `PivotKind`, `Direction`, `Pivot`, `MeasuredMove`, `ProjectedPath`,
  `BreakoutTrigger`, `Match`, `ScoreBreakdown`, `PatternDetector`, `MatchScorePolicy`, `MatchScoreLabels`
- `RedFlag.kt` — `FlagSeverity`, `FlagTab`, `FlagGroup`, `Evidence`, `RedFlag`,
  `ParserHealth`, `FundamentalsSnapshot`, `QuarterRecord`, `AnnualRow`, `CashFlowDerived`
- `Accuracy.kt` — `PatternOutcome`, `HitRate`, `AccuracyKey`, `AccuracyReport`,
  `OutcomeTrackerPolicy`
- `domain/.../compliance/Disclosure.kt` — `Disclosure`, `AdviceLanguage`, `HonestyContract`

If you need a new shared type, put it in **your** package and report it; the orchestrator
will promote it to `domain/model`.

## Fixtures (real, captured from live sources)

`data/src/test/resources/fixtures/`
- `bhavcopy_20261001.zip` and `.csv` — NSE EOD bhavcopy for 2026-10-01, 3,712 instruments
- `sec_bhavdata_20261001.csv` — legacy delivery-percentage file (note leading-space headers)
- `RELIANCE.html`, `TCS.html`, `HDFCBANK.html`, `INFY.html`, `TITAN.html` — real screener.in
  consolidated pages (~220–240 KB each)
- `data/src/main/resources/assets/EQUITY_L.csv` — NSE symbol master, 2,593 rows, has ISIN
- `domain/src/test/resources/fixtures/eod_history.json` — multi-month daily OHLCV for ~40 large
  caps assembled from the NSE archive (schema documented in `docs/DATA-NOTES.md`)

## Non-negotiable product rules (plan.md §1.4, §6.1)

1. **No advice language.** No "buy", "sell", "target price", "guaranteed", "will rise",
   "% chance". Pattern geometry must never be labelled as a price target.
2. **Match score ≠ probability.** It measures geometric fit to the textbook template only.
3. **Every fundamentals figure shows source + "as of" date.**
4. Every red flag carries `Evidence` (the `RedFlag` data class enforces this in `init`).
5. A parser returns a `ParserHealth`; it must never silently return an empty list.
6. Hit rates are withheld below `MatchScorePolicy.MIN_HIT_RATE_SAMPLES` (30) and always
   shown with `n`.
7. Hit rates are reported **per stock AND per pattern** (decision D6).
8. Default to `Sector.OTHER` and never guess a sector.
9. Timeframes are `D1` and `W1` only. No intraday.

## Code conventions

- Kotlin idioms, `data class`es, immutable values, no mutable global state.
- Pure functions in `:domain`; document non-obvious math with the book chapter it comes from
  (e.g. `// AI-Invest Part 3, Ch.23 — volume confirmation`).
- Every public type gets a KDoc explaining *why* it exists, referencing plan.md sections.
- Tests: JUnit 5 (`kotlin.test` assertions are fine). Test names state the behaviour, e.g.
  `zigzag never emits two consecutive same-kind pivots`. Golden-file tests assert **exact
  values** from the real fixtures. Property tests for invariants listed in plan.md §12.
- No test framework beyond kotlin-test/JUnit 5. No mocking libraries — hand-written fakes.
- Do not add new Gradle dependencies without listing them in your report.
- Do not run `git commit`. The orchestrator commits.

## Verifying your work

Run the relevant suite before reporting:

```bash
~/.local/tools/gradle-9.8.0/bin/gradle --console=plain :domain:test --tests 'com.mia.app.domain.technical.*'
~/.local/tools/gradle-9.8.0/bin/gradle --console=plain :data:test --tests 'com.mia.app.data.price.*'
```

## Final report format

1. Files created (with a one-line purpose each).
2. Test command(s) run and the pass/fail counts.
3. Exact asserted fixture values (so the orchestrator can cross-check).
4. Anything you could not verify, and anything you needed outside your file ownership.
5. Deviations from this brief, with justification.