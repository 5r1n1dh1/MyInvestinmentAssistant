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

### `:app` and `:android-data` have never been compiled

**No Android SDK on this machine.** Those two modules are excluded from the build, so the entire
Compose layer, Room schema, Hilt graph and WorkManager wiring is unexecuted.

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

### P3 and P4 remain open

- **P3** — needs a network Yahoo answers on. The code is written to the documented contract.
- **P4** — `:app` compile, plus the P12 low-RAM device test (2GB-RAM hardware, cold offline
  launch on the watchlist screen).

---

## 6. Test inventory

| Area | Tests | Notable |
|---|---|---|
| `domain/technical` | 198 | hand-computed golden values, NaN-free invariants, ADX warm-up |
| `domain/pattern` (core) | 108 | zigzag properties, three scoring fixtures, **P7 gate** |
| `domain/pattern/detectors` | 173 | synthetic formations, near-misses, polarity, real-data rates, a pinned flag-rejection census |
| `domain/redflag` | 191 | fires/quiet/cannot-evaluate, sector overrides, evidence completeness |
| `domain/ratios` | 101 | 25+ real company names → sector, §7.2 order verbatim |
| `domain/accuracy` | 76 | gating, idempotency, real-data simulation, compliance |
| `data/price` | 77 | P2 gate, resilience, primary→mirror fallback |
| `data/fundamentals` | 131 | five golden pages, both landmines, robots compliance |
| `data/symbols` | 126 | resolution ladder, TATAMOTORS end-to-end, reconciliation counts |
| `data/history` + `data/aggregate` | 150 | handshake sequencing, five §5.1 rows, 5.00/5.01 boundary |
| **Total** | **1,337** | **0 failures** |