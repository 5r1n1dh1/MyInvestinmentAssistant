# My Investment Assistant

A **free, fully on-device Android app** that helps an Indian retail investor decide, after market
close, whether to look at a stock the next morning.

No servers, no API keys, no subscriptions, no accounts. Everything is computed locally.

```
EOD → open app → search a stock → read Fundamentals + Technicals + Patterns tabs
    → see red flags with their evidence → check the app's own track record → decide → act
```

---

## What it does, and what it deliberately refuses to do

It is **educational end-of-day analytics**. It is not a trading app, not a strategy tester, and not
an adviser. Specifically:

| It does | It does not |
|---|---|
| Detects 12 textbook chart/candlestick patterns and scores geometric fit | Say "buy", "sell", or give a target price |
| Shows every red flag with the numbers behind it | Render a probability of profit |
| Derives Indian-sector-aware ratios with an explicit priority order | Show a stale P/E as if it were live |
| Project measured-move **pattern geometry** | Call that geometry a forecast |
| Track its own hit rate per stock **and** per pattern | Present that hit rate without `n`, or below 30 samples |
| Work offline, on a 2GB-RAM phone | Need a backend, or a broker account |

### The one design decision that shapes the whole app

A control test over 20,780 next-day returns across 20 large caps found **every** signal at ~50%:
RSI(14) at 50.24%, 200-day momentum at 50.17%, z-score vs MA50 at 49.81%. So no score can be
"foolproof", and the app never pretends otherwise.

It therefore keeps **two separate numbers and never combines them**:

| | Meaning | Shown next to |
|---|---|---|
| **Match score** (0–100) | Geometric fidelity to the textbook template | The detected pattern |
| **Hit rate** (%) | How often that pattern's geometry was reached, on this stock, from tracked history | The track record — always with `n`, never below 30 samples |

A 70/100 match score and a 55% hit rate are answers to different questions. Rendering them as one
number would be the single most misleading thing this app could do.

Measured, not asserted: next-day direction over the fixture is 48.93%, reproducing the plan's
~50%. And a "hit" is the measured-move *level being reached* — a fixed geometric distance — so
hit rate declines monotonically with that distance (1% → 33%, 5% → 10%). It is a chart event, not
a profit, and the UI says exactly that.

---

## Sources — all free, all verified

| Source | Role | Verified |
|---|---|---|
| [NSE EOD bhavcopy](https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_YYYYMMDD_F_0000.csv.zip) | Official end-of-day prices, **whole market in one fetch** | 3,712 instruments, no auth |
| Yahoo Finance | Longer history (500 daily / 261 weekly bars) | **429-blocked from this network** — see below |
| screener.in | Indian fundamentals | 5 golden pages committed |
| NSE `EQUITY_L.csv` | Symbol master + ISIN | 2,593 rows, bundled for offline search |

Full measurements, parser landmines, and everything we could not verify: **[docs/DATA-NOTES.md](docs/DATA-NOTES.md)**.

---

## Architecture

```
:domain        pure Kotlin/JVM    pattern engine · indicators · red flags · ratios · accuracy
:data          pure Kotlin/JVM    parsers · aggregation (network behind an injected transport)
:android-data  Android library    Room · Hilt · OkHttp · WorkManager
:app           Android app        Compose UI · ViewModels · navigation
```

Boundaries are **compile-time enforced**, not conventions:

- `:domain` imports neither `:data` nor Android, so every pattern, indicator and red flag is a pure
  function unit-tested on the JVM with no Robolectric.
- `:data` reaches the network only through `HttpTransport`, so the Yahoo cookie+crumb handshake is
  testable with a fake.
- `:app` never imports `:data`; each screen declares a narrow interface it owns.

That is what made this buildable in parallel, and it is why the engine's 853 tests run without an
Android SDK.

### The detector ↔ scorer seam

Detectors never compute a score. They emit **weighted measures**; the scorer turns measures into a
breakdown (geometry 40 / structure 25 / confirmation 20 / context 15). Every pattern is therefore
comparable, and a new pattern is mechanical to add:

```kotlin
interface PatternDetector {
    val name: String; val category: Category; val minBars: Int
    fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match>
}
```

Pivots come from **one** shared zigzag pass (plan.md §6.2) — the single biggest performance win, and
the reason a full scan is fast enough to run on-device.

### 13 detectors, 14 evaluated rows, 9 with a track record

These are three different counts and conflating them is how this section used to mislead.

| Count | What it is |
|---|---|
| **13** | detector instances wired in `DetectorRegistry.all()` |
| **14** | evaluated pattern rows — `FlagDetector` emits both a bull and a bear flag from one pass |
| **9** | patterns that can actually enter the track record |

The nine with live measurements: **Rising Wedge, Head & Shoulders, Inverse Head & Shoulders, Double
Top, Double Bottom, Ascending Triangle, Descending Triangle, Bull Flag, Bear Flag.**

The five candlestick detectors still run on every scan — but a candle formation has no measured
move, and `OutcomeTracker` refuses any match that lacks one, so their matches can never reach the
accuracy ledger. The catalogue lists them as **glossary-only**: teaching rows, not scored rows. That
is a labelling decision, not a broken detector, and it is argued in full in `PatternCatalogData.kt`.

A separate measured fact worth knowing: of the patterns once presented as equally live, **8 of 14
never produce a candidate on real data** (commit `fedd20f`). Every one of those zero counts is a
strictness gate doing its job, and the Patterns tab now reports the per-gate census rather than
quietly showing an empty list.

**Read that sentence next to the census panel and you will find two numbers that look like a
contradiction.** They are not, and the distinction is the whole point. "Never produce a candidate"
counts **matches a scan emits**; the census counts **windows that reached the shape gate and then
survived every later gate**. Measured, they differ sharply: Descending Triangle and Bull Flag emit
**no match at all** on a full-series pass, while the census counts **23** and **150** surviving
windows for them respectively. Both are true — one describes what a reader sees on the chart, the
other describes where the candidates were lost. A panel that quoted the window counts under the
label "candidates" would be exactly the mislabelling this app keeps refusing to make, which is why
the census column is named `survivingWindows` and is never presented as a match count.

Rules, tolerances and failure-mode penalties were extracted from the project's investing textbook
into **[docs/spec/](docs/spec/)** (`patterns-spec.md`, `scoring-spec.md`, `redflags-spec.md`) — so
detector logic comes from a source rather than from invention, and ~60 constants the book left as
prose are marked `[derived]` so a reviewer can argue with them.

---

## Building it

Everything is built and tested in **GitHub Actions** — this repository's working rule is that no
build runs on the authoring machine. See [AGENTS.MD](AGENTS.MD) rule 4.

```bash
# locally you only ever need this (and the wrapper verifies the toolchain):
./gradlew :domain:test :data:test -PskipAndroid   # engine suites, no Android SDK needed
```

Pushing or opening a PR runs three CI jobs:

| Job | Runs | Purpose |
|---|---|---|
| `changes` | always | diffs the PR against base with plain `git` (no third-party action) |
| `jvm` | always | `:domain:test :data:test -PskipAndroid` — the cheapest real signal |
| `android` | only when `:app` / `:android-data` / gradle files changed | SDK install, compile, APK |

So an engine-only change is verified in about three minutes; a UI change pays the full SDK cost.

There is also an on-demand probe for the free data sources:
`gh workflow run verify-live.yml`. It has no schedule — it costs nothing until someone asks for it.
Latest findings: **[docs/LIVE-PROBE.md](docs/LIVE-PROBE.md)**.

See **[docs/ENVIRONMENT.md](docs/ENVIRONMENT.md)** for the toolchain and setup.

---

## Red flags: evidence, never assertion

Every flag is a deterministic threshold check that must carry the numbers behind it — the data
class refuses to construct a flag with empty evidence. Wording is factual:

> "Debt/Equity rose from 0.45 to 1.95 over 3 years"

never *"This company is risky"*.

Three states are kept distinct, because a blank that looks like a value is how an app loses trust:

| State | Rendered as |
|---|---|
| Available | the figure, with source + "as of" date |
| Not applicable for this sector | *"Not applicable for Banking"* (§7.3 overrides) |
| Not available from our sources | *"Not available from our sources"* + the reason |

Checks that our sources genuinely cannot support — **goodwill**, **promoter pledging** — ship
implemented and unit-tested against synthetic input, then abstain on every real symbol *while
saying why*. A permanent gap that reports itself beats a blank that reads as a value.

---

## Scheduling

```
15:30 IST   market close
~18:00      bhavcopy published
19:00       WorkManager: fetch bhavcopy → replace market cache
19:05       rescan the tracked symbols → projections + breakout alerts
```

Exponential backoff (min 30 min), `CONNECTED` + `requiresBatteryNotLow`, last success/failure
persisted. Only the **watchlist** is scanned nightly; everything else runs on demand.

### Data arrives without waiting for 19:00

The schedule above alone left a fresh install useless: nothing is fetched until the 19:00 run, so
every tab reported "no bars stored" for the rest of the day. Three things changed:

| | Before | Now |
|---|---|---|
| Opening a stock | reads the cache only | **fetches if the cache is stale** — never if it already covers the newest session |
| First run | waits for 19:00 IST | primes in the background on launch |
| Settings → Refresh | re-planned the schedule | enqueues a real one-off run |

A nightly refresh alone adds **one bar a day**, which can never reach the 60-bar detection gate, so
there is also a bounded **history walk** that pulls older sessions from the same archive — resumable,
and shared across every symbol, since each file contains the whole market.

---

## Repo layout

```
design/plan.md           the specification this was built from
design/planv2.md         the remediation plan, and what it closed / could not close
design/gaps.md           the expert gap review that started all of it
chart_plan.md            the chart range / pattern / measured-score plan
docs/CONTRACTS.md        module boundaries + the rules every contributor follows
docs/DATA-NOTES.md       measured behaviour of every source, and what is unverifiable here
docs/VERIFICATION.md     what was proven, defects caught, and the open gaps
docs/ENVIRONMENT.md      toolchain, build, CI, how to add a pattern
docs/spec/               detector + scoring + red-flag rules extracted from the textbook
domain/  data/           pure-JVM engine and parsers (1,337 tests)
android-data/  app/      Android persistence, DI, workers and Compose UI
.github/workflows/       APK build on every push; the JVM suites run first, before the SDK install
```

---

## Building an APK

Push to `main` and [the workflow](.github/workflows/build.yml) builds it; download the artifact
from the run summary. Or locally, with an SDK installed:

```bash
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/
```

Release signing is optional by design — with no keystore configured, the release build is signed
with the debug key and still installs. This app has no keys and no build-time configuration
(plan.md G1), so nothing about it should require a secret to produce an APK.

---

## Regulatory posture

SEBI's Investment Adviser boundary is a hard constraint, enforced in code and covered by tests that
scan the app's own strings for advice language. The app ships the disclaimer on first launch and in
Settings, labels every projection as **pattern geometry**, and shows a source and "as of" date on
every fundamentals figure.

`domain/compliance/Disclosure.kt` is the single source of that wording, so no screen can drift from
it.

---

## Status

| Phase | State |
|---|---|
| P0 scaffold · P1 symbol book · P2 bhavcopy · P5 fundamentals | ✅ complete, fixture-tested |
| P3 history · P7 pattern engine · P8 all 13 detectors · P6 red flags · P9 accuracy | ✅ complete, JVM-tested, **P7 and P3 gates both passed** |
| P4 technicals UI · P10 workers | ✅ **compiled in CI** (run 38132317377, 2026-10-11) |
| P11 voice search + share-as-image | ✅ **compiled in CI** |
| `:app` + `:android-data` first compile | ✅ 2026-10-11 — the first build of these modules in any environment |

**Both open items from the last review are now closed by measurement.** The P3 gate executes —
the Yahoo cookie+crumb handshake answers 200 and the chart API returns data
([docs/LIVE-PROBE.md](docs/LIVE-PROBE.md)). The Android modules compile in CI on every push and
pull request, which is now the build gate for the whole repository.