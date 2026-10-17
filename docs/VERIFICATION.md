# Verification report

What was proven, what was measured, and what remains unverified. Written so the next person does
not have to re-derive it — and so nobody mistakes a design decision for a tested fact.

Run: `./gradlew :domain:test :data:test` → **1,337 tests, 0 failures**.

---

## 1. Phase gates (plan.md §11)

| Gate | Status | Evidence |
|---|---|---|
| **P2** parse a bhavcopy fixture, assert exact RELIANCE values | ✅ **Passed** | `ClsPric == 1167.70`, `ISIN == INE002A01018`, plus the full 22-field row; 3,712 instruments; `EQ` 2662 / `SM` 389 |
| **P3** fetch 2y for 20 large caps, ≥490 rows | ⚠️ **Not executed** | Yahoo returns 429 from this IP. The handshake, host fallback and 429-retry are proven against a fake transport; live row counts are unverified. See `DATA-NOTES.md` §2 |
| **P7** the score must produce a spread, not cluster | ✅ **Passed** | `PatternDiscriminationGateTest`: 67 scored candidates over 20 real stocks — min 17, max 70, mean 45.9, **sd 12.0, 37 distinct scores**; all three threshold bands populated (≥70: 1, 50–69: 26, <50: 41); within-stock spreads up to 41 points |

### The P7 gate in detail

This is the most important test in the repository, because **a scorer that returns a constant
passes every other test in the suite**. Unit tests assert the scorer does what it was told, and a
constant scorer does that consistently. Only interrogating the score *distribution* catches it.

`PatternDiscriminationGateTest` runs the full pipeline — zigzag, twelve detectors, the four-factor
scorer, the penalty table, the gates — over real NSE data and asserts:

1. enough scored candidates exist for a distribution to mean anything;
2. the score range is wide (min ≥ 25-point spread);
3. the standard deviation is above a floor, so the spread is not one outlier;
4. enough distinct integer values that the 50/70 thresholds do real work;
5. matches are rejected as well as accepted, and some are publishable — a scorer that rejects
   everything or accepts everything is equally useless;
6. every score equals its own weighted `ScoreBreakdown.total`;
7. **within a single stock** scores differ — otherwise a per-symbol offset could fake the whole
   result;
8. the four factors are genuinely distinct numbers — a scorer secretly returning `geometry` would
   pass every spread test.

Two findings came out of writing it:

- The first version sampled only `PatternScanResult.matches`, i.e. survivors of the 50-point
  render gate, and found just **9** — too few to say anything. The engine deliberately retains
  rejected candidates in `suppressed` with their scores, which is both what the UI needs (to
  explain what it hid) and what made this gate possible.
- The first version scanned a 200-bar window and found 36 candidates. Truncating the input
  starves the structural detectors, so the sample was measuring the window rather than the scorer.
  The app feeds the engine 500 bars; the gate now uses the full 372-bar fixture.

---

## 2. Empirical findings that contradict or extend the plan

These came out of building against live sources. Each is a measurement, not an opinion.

### TATAMOTORS is not what the plan says it is

plan.md §9 says `TATAMOTORS` fails on Yahoo and is absent from bhavcopy because it demerged into
"TATAMOTORS (PV) + TMPV". Measured against the live symbol master:

- The successor ticker is **`TMCV`** — Tata Motors Limited, new ISIN `INE1TAE01010`, listed
  2025-11-12.
- **`TMPV` is a different company** (Tata Motors Passenger Vehicles) that inherited the retired
  ISIN `INE155A01022` and has been listed since 1998.
- In our window `TATAMOTORS` trades for the **first 140 of 372** sessions and never returns — it
  `SYMBOL_DISAPPEARED`, it did not "resume".

`resolve("TATAMOTORS")` returns `TMCV` with `TMPV` as an alternative. Mapping to the
passenger-vehicle entity would send a user to the wrong company with full confidence.

### The bhavcopy primary host does not answer from here

`nsearchives.nseindia.com` fails (HTTP/2 error, then timeout). `archives.nseindia.com/content/cm/`
serves a **byte-identical** file. Implemented as primary + mirror fallback, because without the
mirror the price layer does not work from this network at all.

### §3.5's `UNVERIFIED` is now verified

The legacy delivery file returns 200 with `DELIV_PER`, 3,262 of 3,534 rows populated, and its
`EQ`+`SM` symbol set is identical to the filtered bhavcopy set. Shipped null-safe per OQ-2.

### Four ratios the plan assumes exist do not

| Ratio | Measured | Shipped as |
|---|---|---|
| Receivables | absent (0 grep hits) | `Debtor Days` DSO proxy from the `ratios` card |
| Inventory | absent | `Inventory Days` series (RELIANCE 88) |
| Goodwill | absent | declared coverage gap — abstains with a reason |
| Promoter pledge | absent | declared coverage gap — abstains with a reason |
| Gross margin (#7 in §7.2) | no COGS line anywhere | `NOT_SOURCED` |
| EV/EBITDA (#10 in §7.2) | no cash or EV line | `NOT_SOURCED` |

Goodwill and pledging are **implemented and unit-tested against synthetic input**, then abstaining
on every real symbol while saying why. That is the honest shape: a permanent gap that reports
itself, rather than a blank that reads as a value.

**Update 2026-10-16 (live probe).** The bank ratios were still an open question at this point —
`screener.in` publishes NIM / GNPA / CASA / PCR for banks, in principle. The probe settles it: those
labels appear on the page but their **values sit behind a login paywall** (60 empty
`highlight-cell` elements), so they stay `NOT_SOURCED` for banks too. The gap is now closed *by
evidence* rather than left pending, and the honest alternative — the annual report, the RBI
Financial Stability Report — is what the education curriculum teaches instead. See
[LIVE-PROBE.md](LIVE-PROBE.md) §3.

### The §5.1 divergences reproduce exactly

RELIANCE P/E 21.2 vs 21.142 → 0.27% (agreed) · TCS P/E 14.0 vs 15.083 → **7.18%** (conflict) ·
TCS ROE 51.8 vs 47.743 → **8.50%** (conflict) · RELIANCE price 1168.0 vs 1167.7 → 0.03% (agreed).

Divergence is computed against the **lower-precedence** value. That base is the only one that
reproduces all four of the plan's numbers; two alternatives contradict the plan's own table, and
the test pins the alternatives' different results so the choice cannot drift silently.

### The EOD fixture contains four real unadjusted corporate actions

HDFCBANK −50.4% (2025-08-26), BAJFINANCE −89.9% (2025-06-16), KOTAKBANK −80.3% (2026-01-14),
TATAMOTORS −40.2% (2025-10-14). Real events, not fixture bugs — see `DATA-NOTES.md` §6.

This mattered enough to measure rather than assume:

- Chart-pattern detection counts are **identical** with and without those sessions, so the
  structural gates are robust to them.
- Technical red-flag rates were measured over a **rolling 4,407 symbol-session walk**, because a
  point-in-time rate cannot tell a working rule from a broken one: `RSI_OVERSOLD` 3.34%,
  `DISTRIBUTION_DAY` 1.41%, `52-week-low + declining OBV` 6.85% — all plausible.
- **`DEATH_CROSS` fires on 49.83% of rolling observations**, independently reproduced in Python
  at 49.49%. Not a bug: it is a *state*, not an event, and on a downtrending sample half of all
  observations have `SMA50 < SMA200 AND close < SMA200`. Consequence for the UI: the flag leads
  with the **cross date** and sessions elapsed, so a persistent condition is never presented as
  news. A point-in-time rate would have hidden this entirely.

### The projection gate was unreachable, and the score was pointing the wrong way

The app's headline feature — project a measured level, draw it, fire a breakout alert — had
**never executed once**. `MatchScorePolicy.PROJECT_THRESHOLD = 70` gated it, and over 875 settled
walk-forward candidates the highest score ever produced was **68**. No test caught it, because a
gate nothing can cross is indistinguishable from a gate that works.

Worse, the score was not merely useless — it was **inverted**. Hit rate fell as the score rose,
monotonically across all six horizons (50–59 bucket: 35% @h5; 60–69: 30%). The mechanism is not
noise: the score is a proxy for how far away the projected level sits, so a more textbook-ideal
formation is a *bigger* formation that projects a *further*, harder-to-reach level.

**The fix is a gate on distance, calibrated from the fixture** (`ProjectionGate`,
`ProjectionCalibration`; PR #5). Reproduced on every test run from
`fixtures/eod_history.json` (20 symbols × 372 sessions, 5-bar stride, 875 settled candidates):

| Level within | h1 | h5 | h20 | n | control @h5 |
|---|---|---|---|---|---|
| 1 % | 76 % | 90 % | 94 % | 49 | 68 % |
| 2 % | 71 % | 87 % | 92 % | 87 | — |
| 3 % | 63 % | 82 % | 92 % | 121 | — |
| 5 % | 48 % | 65 % | 80 % | 203 | **10 %** |
| 12 % | 28 % | 36 % | 48 % | 531 | — |
| beyond 12 % | 31 % | 31 % | 34 % | 344 | — |

**A base rate is a function of the level's size.** At h5, a 5 %-wide level is touched 10% of the
time with *no pattern involved*; a 1 %-wide level is touched 68%. So the 5 % row is a **6.5× lift**
while the 1 % row is barely an edge at all — and quoting "90% of tight targets get hit" without
that control is how this table gets misused. The gate stops at 5 % precisely because past it the
level is out of reach, and below 2 % chance has already reached it.

**The gate now fires.** 156 of 976 walk-forward candidates produce `Verdict.PROJECT` through the
real `PatternEngine.verdictFor` path (lowest projecting score 50; highest score anywhere, 69).
`ProjectionGateSatisfiabilityTest` exists specifically so this bug class cannot recur silently.

Two corrections the measurement forced:
- **The unfiltered hit rate is 34 %, not the 16.45 %** reported in gap A6. The 16.45% figure comes
  from a *stand-in* detector (last five bars' range) that projects far wider levels than the real
  detectors do. Both are honest numbers about different things; the test asserts "far below 50%".
- **`Match.score` still reaches 99** in stored matches. It is display-only now, and anti-correlated
  with outcomes — see the calibration dashboard lesson in the education curriculum.

### Fixing the dead gate exposed a second one, pointing the opposite way

The engine gate was only half the bug. PR #9 wired the downstream path, and immediately found two
call sites — `RealPatternsDataSource.projections` and `RealTechnicalsDataSource.projectionGates` —
calling `MatchScorePolicy.verdict(score, timeframe, bars, aligned)` **without** the `projectable`
argument. W1 had made that parameter required-with-a-`false`-default precisely so a caller that
does not know about the gate cannot open it — so both screens had been **drawing a projected path
for nothing, at every score, forever**, and compiled cleanly throughout.

So the app had two dead-gate bugs at once, and they failed in **opposite directions**:

| | Symptom | Which screen |
|---|---|---|
| alert path read the retired `PROJECT_THRESHOLD` | projected **nothing**, never alerted | Patterns, worker |
| `verdict()` called without `projectable` | projected **everything**, unearned | Patterns + Technicals |

A suite that passed cleanly through the middle of that change would have caught neither. The
general lesson is the one this repository keeps arriving at: **the tests that matter assert a
property, not a shape.** Nothing asserted "a projected level is drawn only when the gate admits
it", so both directions were free.

Missing gate inputs are now **fail-closed** by construction. `PatternProjection` carries
`lastClose`, `barCount` and `regimeAligned` with defaults `0.0 / 0 / null`, and
`ProjectionGate.blocker` answers `INSUFFICIENT_HISTORY` for `barCount = 0` before it inspects
anything else — so a projection assembled without its inputs cannot project or alert. A missing
input can only ever close the gate, never open it.

### Candlestick patterns were structurally excluded from projecting

`resolveDirection` read the measured move, then the newest pivot's kind. Candlestick formations
have **neither** (no pivot — a 1–3 bar formation; no measured move — spec DD-14), so direction
was `null`, the regime gate returned `null`, and every candlestick match was capped at `RENDER`
regardless of score. Fixed with `PatternIds.DIRECTIONAL_BIAS`: the textbook direction, from the
pattern's semantics rather than its geometry. A chart pattern is deliberately absent from that
table — its direction is *measured*.

### The score weighted total was missing its divisor

`ScoreBreakdown.total` multiplied by the weights but never divided by 100, so **every score
would have been 100** and every gate would have returned `PROJECT`. Caught because the three
hand-computed fixtures in `scoring-spec.md` were impossible to reproduce. Fixed with
`MatchScorePolicy.TOTAL_WEIGHT`; the fixtures now reproduce exactly (74 / 39 / 30 with breakdowns
68-69-98-67, 52-61-50-68, 69-69-31-62).

### The red-flag engine could not report coverage

`RedFlagReport.skipped` was constructed empty, so both flag packages bolted on private skip
channels. `SkippableRedFlagRule` now makes the engine collect reasons itself, and `headerText`
distinguishes "no flags" from "no flags *in the data checked*" — a header that says "all clear"
while half the rules abstained is the failure mode plan.md §12 warns about.

---

## 3. The honesty contract, measured

### The control test reproduces

Next-day direction over the fixture: **2,723 up / 5,565 sessions = 48.93%**. plan.md §6.1
measured ~50% over 20,780 returns across 20 large caps. Same result, independently reproduced.

### A hit rate is not 50%, and should not be presented as one

`RealHistorySimulationTest` over 15 large caps: 945 outcomes recorded, 930 resolved, 153 hits →
**16.45%**, split evenly by direction (up 16.38%, down 16.52%).

That number is correct and is **not** a trading edge. A hit means the measured-move level was
*reached* — a fixed geometric distance — so the hit rate is a monotone function of how far away
that level was. Measured on this fixture by distance: 1% → 33.3%, 2% → 26.5%, 3% → 17.9%, 4% →
13.9%, 5% → 10.0%, 6% → 7.2%, 7% → 4.9%. The simulated moves average 4–5% of price against a
median 5-bar favourable excursion of ~1.8%.

The UI says "this pattern's geometry was reached", never "this pattern works", and never renders a
hit rate without `n`.

### The 30-sample gate binds exactly where D6 predicts

Per symbol **and** pattern yields 20–21 cases per cell, so **all 45 cells are withheld** on 18
months of data for one stock. Per-symbol (62) and per-pattern (310) clear the gate. This is a
property of the available history, asserted as such rather than worked around.

### Compliance is machine-checked, not remembered

`AdviceLanguage.FORBIDDEN` (21 phrases) and `HonestyContract.PROBABILITY_CLAIMS` (9 phrases) are
scanned across the pattern, red-flag, accuracy, fundamentals and UI packages — **zero hits** in
user-facing literals. Compliance tests walk the actual string constants, including the text
rendered into the share image. The only strings in the repository containing a banned phrase are
`domain`'s own **negated** disclosures (`Disclosure.SCORE_MEANING`, `MEASURED_MOVE`, `FIRST_LAUNCH`),
which exist precisely to say what the app is not.

Up/down is never signalled by colour alone: `▲`/`▼` glyphs plus a spoken description
(`ui/common/ChangeText.kt`).

---

## 4. Defects the fixture gates caught

Bugs that a unit test written against the implementation would have shipped:

| Bug | How it was caught |
|---|---|
| `LwPric → LWRPRIC` and `FinInstrmNm → FININSTRNM` (both wrong) | every parser constant is now tested against the real header cell |
| `promoterHolding` returned Mar 2026 instead of Jun 2026 | financial quarters are not monotone in period-end date; the parser used `year*10 + quarter`, fixed to calendar `year*4 + quarter` |
| `sharesOutstanding` divided net worth by face value | golden-value test against RELIANCE's real 13.53bn shares |
| `numberTextsOf` double-reported digits | jsoup's `select("*")` includes the receiver |
| §6.7's near-miss fixture could not reject | its stated −2.70% was actually −4.19%, which passes |
| §7 contained a bar closing above its own high | rebuilt from §6.7's mirror instead |
| §8.7's precondition evaluated to −2.74%, not the stated −3.368% | fixture corrected, bar kept |
| Weekly bar date convention (last session vs Monday) | `CandleResampler` stamps the **last trading day**, so a weekly candle carries the date of the price the user sees |
| Realtime/other spec contradictions (§4.3 S5, §10.3 weights, §3.8 divergence, §17 self-contradiction) | each implemented as written with the discrepancy documented, and the ambiguous fixture reproduced both ways |

---

## 5. Unverified — and what was done instead

### `:app` and `:android-data` have never been compiled — **RESOLVED 2026-10-11**

**This section is the historical record of the gap, and it is now closed.** Kept because the way it
was closed matters: not by finding a local Android SDK, but by fixing the CI workflow that had been
failing silently on every push.

#### Why CI was failing

The JVM suites had been passing on every run for weeks. The Android phase had never started,
because `android-actions/setup-android@v3` runs `sdkmanager "tools"` and **the standalone SDK
`tools` package was removed from Google's repository** — so the action exited 1 before any module
was compiled, on every push since the workflow was added. Three more defects surfaced in sequence,
each only visible once the previous one was fixed:

| Defect | Symptom | Fix |
|---|---|---|
| `setup-android@v3` installs the removed `tools` package | exit 1 at SDK setup, no compile attempted | drop the action; call `sdkmanager` directly |
| the runner's `sdkmanager` is **not on PATH** (it lives at `$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/`) | exit 127, "command not found" | resolve it by path, with a `find` fallback across revisions |
| SDK install was wrapped in `\|\| true` | a missing package would surface later as an unreadable Gradle error | licences stay tolerant; package install is strict |
| PR path detection used `git diff origin/BASE...HEAD` | `fatal: no merge base` — the checkout is a **depth-1 shallow clone of the merge commit**, so a three-dot diff cannot resolve | two-dot diff (`origin/BASE HEAD`); the merge commit's tree *is* base + PR changes |

#### The first green build

Run **38132317377**, 2026-10-11, 3m37s. Tasks that had never executed before, now green:

```
:android-data:compileDebugKotlin     ✓
:android-data:assembleDebug           ✓
:app:compileDebugKotlin               ✓
:app:assembleDebug                    ✓          → APK artifact produced
:domain:test  :data:test              ✓          (JVM suites unchanged, still green)
```

Every "expect the first real build to surface" item in the old list below turned out to be already
correct — no Material 3 argument mismatches, no Hilt graph errors, no `HorizontalDivider` breakage.
That is worth stating plainly: the manual audit that stood in for a build did not miss anything
real, because the code had been written against stable APIs on purpose.

#### CI is now the build gate for the whole repo

`build.yml` is three jobs: `changes` (diff detection, plain `git`, no third-party action) → `jvm`
(the engine suites, always) → `android` (SDK install + `:android-data` tests + compile + APK,
**skipped when a PR touches only `:domain`/`:data`/docs**). An engine-only change verifies in
~3 minutes.

#### And `:android-data`'s tests were never being run at all

Found while fixing the job structure: the `jvm` job passes `-PskipAndroid`, which **excludes**
`:android-data` entirely, and `assembleDebug` compiles the module without executing a single test
in it. So all eight test files under `android-data/src/test` had never been run — including
`DefaultIndexRepositoryTest`, which pins the honest "no index stored, so the benchmark is `null`"
fallback that W3 depends on. **A test nobody runs is a comment.**

Adding `:android-data:testDebugUnitTest` to the `android` job immediately failed to compile —
the first honest signal those files had ever received. The cause was a helper typed to the fake
rather than the interface:

```kotlin
private fun TestScope.repository(dao: FakeIndexDao, source: FakeIndexSource)   // too narrow
```

Two tests deliberately pass an anonymous `IndexSource` that throws, to assert the repository
converts a failing source into a typed `DataFailure` rather than letting an exception escape — and
the helper silently refused them. The production constructor takes `IndexSource`; the helper now
matches it. **The test was right and the helper was wrong**, and it had been "passing" in the only
sense a test nobody runs can pass.

All three jobs now green, including `:android-data:testDebugUnitTest`.

#### The original gap, for the record

**No Android SDK on this machine.** Those two modules were excluded from the build, so the entire
Compose layer, Room schema, Hilt graph and WorkManager wiring was unexecuted.

Substitutes applied:

- **Import audit** — a script strips comments and string literals, indexes every declaration in
  all four modules, then checks that every referenced repo symbol resolves. 78 app + 38
  android-data files, **1,556 references, 0 unresolved**.
- **Compliance scan** over all 78 app files: zero advice-language hits in literals.
- **Manual review** of every unverified API call. The specific items to check first on a real
  build:
  - `@HiltAndroidApp` / `@AndroidEntryPoint` (Hilt 2.57.1) — only the Hilt *plugin* is in the
    local cache, the runtime AAR was never resolved
  - `TopAppBar` guarded with `@OptIn(ExperimentalMaterial3Api::class)`
  - `HorizontalDivider` (Material3 ≥ 1.2; BOM 2025.06.00 → 1.3.x)
  - `collectAsStateWithLifecycle` (needs `androidx-lifecycle-runtime-compose`, now added)
  - `PointerEvent.calculateZoom/calculatePan`, `awaitEachGesture` (stable)
  - `Path.rewind()`, `PathEffect.dashPathEffect` (stable)
- **Deliberately avoided** as version-fragile: `rememberTextMeasurer` (moved between experimental
  and stable), `material-icons-core` (artifact availability has shifted), `FlowRow` / `ListItem`
  / `SegmentedButton` (experimental in M3 1.3), typed `@Serializable` nav routes.
- Five screen files are parse-verified only: `PatternsScreen`, `AccuracyScreen`, `SettingsScreen`,
  `VoiceSearchField`, `ShareBriefButton`. Expect the first real build to surface Material 3
  argument-name mismatches there, nothing deeper.
  **Outcome: none of them did.** All five compiled clean on run 38132317377.

### Known capability gaps, deliberately surfaced rather than faked

| Gap | Behaviour |
|---|---|
| **Clear cache** does nothing | no repository exposes a delete; both entry points return an explicit "nothing was removed, and here is why" |
| **Refresh now** cannot enqueue a one-off | only periodic schedules exist; the UI states the real derived schedule instead of pretending |
| Live WorkManager state unreadable | `:app` cannot name `androidx.work` types, so status is polled from `work_state` and **every status carries a note saying so** |
| Weekly bars read-only | `CandleResampler` would keep `1W` alive on a bhavcopy-only install, but no channel exists to say "derived here, not published by NSE", and `Candle.source` would falsely claim `NSE_BHAVCOPY`. `1W` reports "not enough history" rather than misattribute provenance |
| `divergences()` always empty | one fundamentals source is wired, so there is nothing to disagree with yet |
| `runAttemptCount` always 0 | `work_state` keeps one row per task |
| `lastSettledOn` is `max(projectedOn)` | `PatternOutcome` records no settle date; never later than the truth, so it cannot overstate freshness |

Each is a visible sentence in the UI, not a silent no-op.

### P3 and P4 — P3 **closed by probe**, P4 **partially closed**

- **P3 — CLOSED 2026-10-16.** The gate needed a network Yahoo answers on. The on-demand live probe
  found one: `fc.yahoo.com` seeds a cookie, `v1/test/getcrumb` returns a crumb, and
  `v8/finance/chart/RELIANCE.NS` answers **200** with the cookie+crumb pair. The documented contract
  was correct. See [LIVE-PROBE.md](LIVE-PROBE.md) §2.
- **P4 — the `:app` compile is closed** (run 38132317377). **The P12 low-RAM device test cannot be
  closed here**: it needs 2GB-RAM hardware, and a GitHub runner is not that. Cold offline launch on
  the watchlist screen on real low-RAM hardware stays an owner task — this document should not
  pretend otherwise.

### The probe also overturned its own naive result

The probe greps screener.in's HDFCBANK page for bank-ratio labels and reports GNPA / NNPA / CASA as
"present". Direct inspection says otherwise: those labels sit inside 60 paywalled `highlight-cell`
elements with empty values, and the standalone rows carry labels with no numbers at all — while
ROCE, which the app already sources, renders `7.02`. **The ratios stay `NOT_SOURCED`.** The lesson is
recorded in [LIVE-PROBE.md](LIVE-PROBE.md) §3 because it is the general one: a label match is not a
value, and any future promotion must pin a golden fixture asserting a parsed number.

### The price layer had no write path — found by running the app, not by a test

The most important defect found after the build was handed over, and **no unit test could have
found it**. Every read path was correct; both write paths were unreachable:

```
TechnicalsScreen → storedSeries() → candleDao.recent() → EMPTY
                                 ↑ no writer ever reached this table
```

`refreshMarket()` was called from exactly one place (`EodRefreshWorker`); `backfill()` was called
from **zero** places — implemented, tested, unreachable. So the database filled only if the nightly
job ran, and `RefreshSchedule` targets 19:00 IST. A reader who installed at 10:00 saw *"No D1 bars
for RELIANCE are stored on this device yet"* until evening — and then forever, because one session
a day cannot satisfy plan.md §6.4's 60-bar gate. That is three months.

The `1W` tab had the same defect with **no write path at all**: the bhavcopy publishes daily bars
only, so nothing ever produced weekly rows, and the tab reported "no bars" for a symbol that plainly
had them.

Fixed by adding a real write path plus a **stale-only** freshness check. The rule: fetch when, and
only when, the newest stored bar predates the newest session that should exist. A cache already
covering that date is served with no request, so opening ten stocks costs one fetch rather than ten.
It is wired into Technicals, Patterns and the stock-detail header, and a first-run chain in
`MiaApplication` primes the data instead of waiting for 19:00 IST.

Two deliberate design points:

- **Market-wide, not per symbol.** Every bhavcopy file contains every instrument, so one pass of 60
  sessions gives all ~3,050 symbols the same depth. Fetching per symbol would spend identical
  requests on one stock while 3,000 others gained nothing — the opposite of plan.md §13's CGNAT
  mitigation.
- **`storeHistorySession` is deliberately not `storeMarket`.** The walk calls its store path once per
  session, and `storeMarket` does three things that are right for the newest session and wrong
  sixty times over: it issues a *second* network request for plan.md §3.5's legacy delivery file
  (doubling a 60-session pass to 120 requests for an optional figure), it rewrites the *rolling*
  cache row (so walking backwards would leave that row reporting a months-stale date), and it diffs
  consecutive sessions (walking backwards makes every pair look like a mass disappearance,
  manufacturing hundreds of fake corporate actions). So the walk writes bars and only bars.

`EnsureResult` is a separate type from `DataResult` on purpose. A `DataResult` models "a value or a
typed failure", but a freshness check must stay reportable **even when the fetch failed** — that is
the case where "your 12 stored bars are still being shown unchanged" is the most useful sentence in
the app. Reusing `DataResult` would have forced that to be expressed as a failure.

The honest words are separated too. `NO_DATA_CACHED` conflated four different facts and the tab
rendered all four as one dead end:

| Reason | What the reader is now told |
|---|---|
| `NOT_ATTEMPTED` | "No market data has been downloaded yet" — **and a fetch button**, because one tap fixes it |
| `NO_ROWS_FOR_SYMBOL` | "No published data for this symbol" — **no button**, because re-downloading cannot invent a row |
| `NOT_ENOUGH_HISTORY` | counts attached: "12 of 60 required bars", plus where to get more |
| `SOURCE_UNAVAILABLE` | retry offered, and the existing bars stay on screen |

A button that cannot work teaches the reader to distrust buttons, which is why
`DataFailure.isWorthRetrying` returns `false` for the no-rows case.

### The write path existed but could not write enough — "Not Enough History" on every stock

Adding the write path above fixed *"no data at all"*. It did **not** fix *"Not Enough History"*,
which is what the emulator run then reported for every symbol on both the `1D` and `1W` tabs. Two
independent defects compounded, and both are arithmetic rather than plumbing — which is why neither
showed up as a failing test.

**1. The 280-session default could never execute.** `backfillHistory` clamped its whole request
with `sessions.coerceAtMost(HISTORY_BATCH_LIMIT)`, and `HISTORY_BATCH_LIMIT` was `60`. No caller
could ever fetch more than 60 sessions regardless of what it asked for, so the documented 280 was
dead code. 60 was the worst possible ceiling for two separate reasons:

| Gate (plan.md §6.4) | Needs | A 60-session pass delivers | Verdict |
|---|---|---|---|
| `1D` | 60 daily bars | ~60 — and **59** if one session is skipped | fails permanently |
| `1W` | 40 weekly bars | ~12 weekly bars | can never pass, at all |

The `1D` row is the nastier one: 60 lands *exactly* on the 60-bar gate, so a single holiday, archive
purge or failed request leaves 59 and the tab reports "Not Enough History" with no retry able to
recover. The ceiling is now 280, and the regression guard asserts the property that matters — *the
ceiling alone clears both gates* — rather than the old `ceiling <= default`, which was satisfied by
the buggy value and would not have caught it.

**2. The skip counter was a total, but guarded as if consecutive.** Inside the same walk, one `skipped`
counter was incremented for every unproductive day and compared against a 12-day limit whose KDoc
called it *consecutive*. Reaching 280 sessions means stepping back ~392 calendar days, crossing ~112
weekend days, so a "12 skips total" guard tripped at about **60 fetched sessions** — reproducing
exactly the symptom the deeper default was meant to fix. There are now two counters: `skipped` for
reporting, and `consecutiveSkips` for the give-up guard, reset by every successful fetch **and by
every session found already stored** (a resumed pass re-walks days it has already fetched, so without
that reset it would trip the guard while making good progress).

**3. The first-run chain meant history never ran before 18:00 IST.** `primeFirstRun` enqueued
`EodRefresh → HistoryBackfill` as a chain. `EodRefreshWorker` returns `Result.retry()` while today's
bhavcopy is unpublished (plan.md §3.1: 404 until ~18:00 IST), and a `retry` **fails the whole
chain** — so an install at 10:00 skipped the history walk entirely for the entire day. The chain was
also unnecessary: `backfillHistory` walks *backwards from today*, so its first session is the newest
published one and it covers today's bar by itself. The two are now independent enqueues. That also
fixed a reporting bug, because one chain yields one status: a failing EOD refresh made a *successful*
history walk look like it had never run.

`TradingCalendar.isPossiblyTradingDay` excludes only weekends when no holiday set is passed (and the
walk passes none), so the weekend-only give-up branch is unreachable in practice — a weekend run
tops out at 2 days, never 13. It is retained, and now documented as the defensive branch it is.

### Corporate actions are flagged, never silently adjusted

The stored series is **unadjusted**: plan.md §5.1 makes the NSE bhavcopy canonical and it publishes
raw prices, and plan.md §3.2's Yahoo source is stored the same way. The repository's own committed
fixture (`eod_history.json`, 372 real sessions × 41 NSE large caps) contains four genuine corporate
actions:

| Symbol | Date | Move | Implied ratio |
|---|---|---|---|
| HDFCBANK | 2025-08-26 | −50.4% | ≈1:2 |
| BAJFINANCE | 2025-06-16 | −89.9% | ≈1:10 |
| TATAMOTORS | 2025-10-14 | −40.2% | ≈1:1.7 |
| KOTAKBANK | 2026-01-14 | −80.3% | ≈1:5 |

A bar like that is not a market event, and it silently corrupts every indicator computed over a
window spanning it. The app **cannot fix this without adjusted prices**, and it does not try:
rewriting stored history to look continuous would be fabricating data, and plan.md §5.1's write-once
guarantee means a correction could never be accepted afterwards. So the bars are left exactly as
published and the reader is **told**, per plan.md §1.4 rule 3 and §8.3.

`CorporateActionDetector` (in `:domain`, so it is a plain JVM test) flags a single-bar move of
**±25%** between near-adjacent sessions. The threshold is chosen, not guessed: Indian daily moves of
±10% are ordinary, so anything low enough to catch a 1:1.5 consolidation would fire constantly and
train the reader to ignore it; 25% sits above routine volatility and below the smallest split ratio
in common use (1:2 = 50%). It is symmetric in sign, because a demerger or reverse split can re-rate a
price *upward* and a silently broken series misleads exactly as much as a falling one. Near-adjacency
is required (a 1–4 day gap) because a change across a long gap says nothing about the interval — and
it must over-accept, since Indian markets are shut at weekends, so **every** real split straddles one.

The note reaches the screen below the chart, because its effect *is* the chart. It renders in the
tertiary container rather than the error container: nothing has failed, and a red card would imply
the app is broken rather than that the source is unadjusted.

**Not done, deliberately:** adjusted prices. InvestAssistant gets them from Yahoo `auto_adjust`, but
Yahoo returns HTTP 429 from this machine and from many Indian networks, so no adjusted fetch could be
verified before shipping. Raw-plus-flagging is the honest fallback; adjusted is the later upgrade.

---

## 6. Test inventory

| Area | Tests | Notable |
|---|---|---|
| `domain/technical` | 216 | hand-computed golden values, NaN-free invariants, ADX warm-up, corporate-action detection |
| `domain/pattern` (core) | 108 | zigzag properties, three scoring fixtures, **P7 gate** |
| `domain/pattern/detectors` | 173 | synthetic formations, near-misses, polarity, real-data rates, a pinned flag-rejection census |
| `domain/redflag` | 191 | fires/quiet/cannot-evaluate, sector overrides, evidence completeness |
| `domain/ratios` | 101 | 25+ real company names → sector, §7.2 order verbatim |
| `domain/accuracy` | 76 | gating, idempotency, real-data simulation, compliance |
| `data/price` | 77 | P2 gate, resilience, primary→mirror fallback |
| `data/fundamentals` | 131 | five golden pages, both landmines, robots compliance |
| `data/symbols` | 126 | resolution ladder, TATAMOTORS end-to-end, reconciliation counts |
| `data/history` + `data/aggregate` | 150 | handshake sequencing, five §5.1 rows, 5.00/5.01 boundary |
| **Total** | **1,355** | **0 failures** |

`:android-data`'s 14 tests are **not** in this total, and never have been: `:android-data` has still
never been compiled on this machine (no Android SDK), so its count is a source count, not a run
result. That is also where the two `CandleRepository` threshold guards live — including the
regression guard for the batch ceiling — so those two specific assertions are exactly the ones the
Google AI Studio run has to execute for this fix to be verified. Nothing in `:domain` or `:data`
was modified, so the 1,337 previously-run tests are unaffected by this work.