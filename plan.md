# Indian EOD Stock Analysis App — Implementation Plan

> **v1 — superseded by [planv2.md](planv2.md) (2026-10-11).** Kept as the historical specification
> the v1 engine was actually built from. Its locked owner decisions (§2) still hold; do not
> re-litigate them without the owner. Where v2 changed direction — the projection gate, minSdk, the
> detector/track-record split — planv2.md and the commit messages win.
>
> **Status:** Ready for subagent implementation
> **Owner decisions captured:** see §2. Do not re-litigate these.
> **All empirical claims in this document were verified by live testing** (see §14 for the evidence log). Where something is unverified, it is marked `UNVERIFIED` and must be treated as a task, not a fact.

---

## 1. The Idea & Goal

### 1.1 What the user wants

A **free, fully on-device Android app** that helps an Indian retail investor decide
**after market close whether to invest the next day**.

Core user flow:

```
EOD → open app → search a stock → read Fundamentals + Technicals tabs
    → see red flags → get a pattern match score → decide → act next morning
```

### 1.2 Goals

| # | Goal | Why it matters |
|---|---|---|
| G1 | Zero cost to build and run | No servers, no API keys, no subscriptions |
| G2 | 100% on-device computation | Works offline, no backend, no per-user infra cost |
| G3 | EOD-oriented, not intraday | Matches real retail behaviour; unlocks free official data |
| G4 | Indian-market specific | Sector-aware ratios, Indian tax rules, NSE symbols |
| G5 | Honest metrics | Match score is a *shape-fit* measure, never a profit probability |
| G6 | Ships as v1 | Pattern recognition + fundamentals, no AI backend |

### 1.3 Non-goals (explicit)

- **NOT** a real-time trading app. No live prices, no intraday alerts.
- **NOT** an intraday pattern detector. Timeframes are `1D` and `1W` only.
- **NOT** a buy/sell recommendation engine. No "BUY", "SELL", "TARGET ₹X" verdicts.
- **NOT** a backtester or strategy tester.
- **NOT** using an LLM or any AI inference in v1. All computation is deterministic Kotlin.
- **NOT** multi-user / no accounts / no sync.

### 1.4 Regulatory posture (hard constraint)

SEBI's Investment Adviser boundary. The app is **educational EOD analysis**, not advice.

**Mandatory rules, enforced in code review:**
1. Never render a probability of profit, or words like "will rise", "sure shot", "guaranteed".
2. Never render a price target derived from a forecast. Measured-move projections must be
   labelled as *pattern geometry*, not as a target price.
3. Always display a source + "as of" date for every fundamentals figure.
4. Ship the SEBI disclaimer on first launch and in Settings.
5. Play Store listing must be positioned as education/analytics, not financial advice.

---

## 2. Decisions Already Made (locked)

These came from a structured interview. **Do not change them without asking the user.**

| ID | Decision | Rationale |
|----|----------|-----------|
| D1 | Timeframes = **`1D` and `1W` only**. Intraday removed. | Free tier caps intraday at ~100 bars of 15m — too few for chart patterns. Reliability > feature count. |
| D2 | Projection = **directional forecast + breakout alert** (both) | Distinct outputs, must not be conflated |
| D3 | Match score uses **all metrics that measurably help**, with a *"foolproof"* intent | Interpreted honestly in §6: score measures shape fit, not outcome certainty |
| D4 | Pattern dropdown = **detect all, user filters** | User filters the ranked result list |
| D5 | v1 pattern set = **~12 core patterns** | From the user's book, Part 3 |
| D6 | Learning loop = **track accuracy per stock AND per pattern** | Differentiator; no competitor has this |
| D7 | Data sources = **NSE bhavcopy (official EOD) + Yahoo (history) + screener.in (fundamentals)** | All free, all verified |
| D8 | Architecture = **fully on-device, no backend** | Zero cost, works offline |

---

## 3. Verified Data Sources

All tested live. **Treat these as the contract.**

### 3.1 PRIMARY — NSE EOD Bhavcopy (official, free, no auth)

```
https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_YYYYMMDD_F_0000.csv.zip
```

- **Auth: none.** No key, no cookie, no crumb.
- Returns `200` + a ZIP containing one CSV.
- ~3,712 instruments, ~208KB per day.
- **This is the single most important source in the project.** One fetch = whole market.

Relevant columns (verified names, exact casing):

| Column | Example | Notes |
|--------|---------|-------|
| `TradDt` | `2026-10-01` | ISO date |
| `BizDt` | `2026-10-01` | |
| `Sgmt` | `CM` | Capital Market |
| `FinInstrmTp` | `STK` | |
| `FinInstrmId` | `2885` | NSE internal id |
| `ISIN` | `INE002A01018` | **Stable identity key** |
| `TckrSymb` | `RELIANCE` | Primary lookup key |
| `SctySrs` | `EQ` | **Series — filter on this** |
| `FinInstrmNm` | `RELIANCE INDUSTRIES LTD` | |
| `OpnPric` / `HghPric` / `LwPric` / `ClsPric` | `1180.10` / `1183.90` / `1160.80` / `1167.70` | OHLC |
| `PrvsClsgPric` | `1187.00` | |
| `SttlmPric` | `1167.21` | |
| `TtlTradgVol` | `16771221` | Shares |
| `TtlTrfVal` | `19674608846.20` | Turnover, **rupees** |
| `TtlNbOfTxsExctd` | `279428` | Trade count |
| `NewBrdLotQty` | `1` | Lot size |
| `SsnId` | `F1` | |
| `Rmks`, `Rsvd1..4` | *(empty)* | Ignore |

**Series distribution (verified):** `EQ` 2662, `SM` 389, `BE` 246, `ST` 87, `GS` 55, `GB` 44.
→ **Keep `EQ` + `SM`. Drop everything else.**

**Timing (verified):** today's file returns `404` at 18:32 IST; yesterday's returns `200`.
→ Expect publication ~17:30–19:00 IST. Schedule refresh at **19:00 IST**.

**Retention (verified by sampling):** 2026 ✓, 2025 ✓, 2024 partial, 2023 ✗, 2021 ✗.
→ Roughly **18 months**. Cannot rebuild 1-year history from this alone. See §3.2.

### 3.2 PRIMARY — Yahoo Finance (history backfill)

Used **once per symbol** to obtain 500+ daily bars, then cached forever.

```
1d, period=2y  → 500 rows
1wk, period=5y → 261 rows
```

**Critical gotcha — raw HTTP returns `429`.** The cookie+crumb handshake is mandatory:

```
1. GET https://fc.yahoo.com                     → sets session cookie
2. GET https://query1.finance.yahoo.com/v1/test/getcrumb
3. GET .../v8/finance/chart/{SYMBOL}.NS?range=2y&interval=1d
   with BOTH the session cookie and ?crumb={crumb}
4. Fall back between query1 and query2 hosts
```

Verified: skipping the handshake yields `429 Too Many Requests` on every request.

Symbol mapping: `{SYMBOL}.NS` for NSE.

### 3.3 PRIMARY — screener.in (Indian fundamentals)

```
https://www.screener.in/company/{SYMBOL}/consolidated/
```

Returns `200` + ~231KB server-rendered HTML. Requires a `Referer: https://www.screener.in/`
header. No auth.

**Parse by element ID, never by position:**

| ID | Contents |
|----|----------|
| `top-ratios` | Market Cap, Current Price, High/Low, Stock P/E, Book Value, Dividend Yield, ROCE, ROE, Face Value |
| `quarters` | **13 quarters** of Sales, Expenses, Operating Profit, OPM%, Other Income, Interest, Depreciation, EPS |
| `profit-loss` | Annual P&L (nested markup — parser differs from the others) |
| `balance-sheet` | Annual BS, 10 years |
| `cash-flow` | CFO, CFI, CFF, Net Cash Flow, **Free Cash Flow**, **CFO/OP** |
| `shareholding` | Promoter / FII / DII patterns |

**Two parser landmines (both hit during testing):**

1. Value span class is `"nowrap value"`, **not** `"value"`. Digits are nested in a child
   `<span class="number">`. Match by class **substring** and extract the inner `number` span.
2. The `profit-loss` table has a different DOM shape from the others. It needs its own parser.

Also available: `https://www.screener.in/api/company/search/?q={q}` — **BUT see §3.6, this path
is disallowed by robots.txt. Do not use it.**

### 3.4 SUPPLEMENTARY — NSE symbol master (identity resolution)

```
https://archives.nseindia.com/content/equities/EQUITY_L.csv
→ 200, 183KB, header:
  SYMBOL, NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, ISIN NUMBER, FACE VALUE
```

**Contains ISIN — this is the fix for the TATAMOTORS class of failure (§4.2).**
Bundle this into assets at build time; reconcile against bhavcopy daily.

Trading calendar: `https://www.nseindia.com/api/holiday-master?type=trading` → `200`, 34KB.

### 3.5 SUPPLEMENTARY — legacy bhavcopy (delivery percentage)

```
https://archives.nseindia.com/products/content/sec_bhavdata_full_DDMMYYYY.csv
```

Columns: `SYMBOL, SERIES, DATE1, PREV_CLOSE, OPEN_PRICE, HIGH_PRICE, LOW_PRICE, LAST_PRICE,
CLOSE_PRICE, AVG_PRICE, TTL_TRD_QNTY, TURNOVER_LACS, NO_OF_TRADES, DELIV_QTY, DELIV_PER`

**`DELIV_PER` is delivery percentage** — directly useful for the Volume confirmation factor
(§6.3) and for the `1D` delivery red flag.

⚠️ **Header landmine: every column name has a leading space.** `SYMBOL, SERIES, DATE1, ...`
→ `row["CLOSE_PRICE"]` throws. Use `skipInitialSpace = true` or `.trim()` every header.

Availability of this legacy path for recent dates is `UNVERIFIED` (tests were inconclusive
because of the header bug). Treat as optional; the primary bhavcopy must not depend on it.

### 3.6 robots.txt compliance (binding)

```
User-agent: *
Disallow: /user/*
Disallow: /*?q=          ← SEARCH is disallowed
Disallow: /*?sort=
Disallow: /*?limit=
Disallow: /*?page=       ← PAGINATION is disallowed
Disallow: /company/source/quarter/*
```

**Permitted:** `/company/{SYMBOL}/consolidated/` — this is what we parse.
**Forbidden:** screener.in search endpoint, and any pagination. Never page through results.

Symbol search MUST use the bundled local `EQUITY_L.csv` (which is also faster and offline).

### 3.7 Sources deliberately NOT used

| Source | Reason |
|--------|--------|
| NSE/BSE **real-time** APIs | `403` Akamai; paid + licensed; **redistribution to app users requires a signed agreement** |
| Alpha Vantage / FMP / TwelveData | Need keys; thin free tiers; Indian coverage unverified |
| Broker APIs (Zerodha/Upstox/Dhan) | Free but require the user's brokerage account + OAuth. Defer to v2. |
| Trendlyne / Moneycontrol / Stooq | Connection failed / 0 bytes / no Indian coverage |
| stockanalysis.com | Scrape-of-a-scrape, Indian fundamentals depth unverified |

**Roadmap note:** bhavcopy is undocumented and unversioned. Keep it behind
`PriceSource` so a silent format change is a one-file fix.

---

## 4. Architecture

### 4.1 Stack

| Concern | Choice |
|---------|--------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3, single activity, Navigation Compose |
| DI | Hilt |
| DB | Room (KSP2) |
| HTTP | OkHttp + Retrofit + kotlinx.serialization |
| Scheduling | WorkManager |
| Charts | Vico, or custom Compose Canvas (decision open — §11 OQ-3) |
| JSON parse | kotlinx.serialization + a lightweight HTML parser (jsoup) |

Version pinning is intentionally loose — resolve current stable at implementation time.
Known constraints: Room 2.7.x requires **Kotlin 2.0+ and KSP2**.

### 4.2 Package layout

```
com.mia.app/
├─ data/
│  ├─ symbols/     SymbolBook, SymbolResolver, CorporateActionTracker
│  ├─ price/       PriceSource, NseBhavcopySource, YahooHistorySource, PriceAggregator
│  ├─ fundamentals/ FundamentalsSource, ScreenerInSource, RatioMapper, StatementParser
│  └─ db/          Room entities, DAOs, migrations
├─ domain/
│  ├─ ratios/      SectorResolver, RatioPriority, RatioComputation
│  ├─ redflag/     RedFlagEngine, each flag as a class
│  ├─ technical/   Indicators (RSI/MACD/SMA/ATR/BB/OBV/ADX)
│  ├─ pattern/     ZigzagPivots, detectors/, GeometryScorer, MatchScore, MeasuredMove
│  └─ accuracy/    OutcomeTracker, HitRateCalculator
├─ ui/
│  ├─ search/  stocklist/  stockdetail/  fundamentals/  technicals/  patterns/  accuracy/
└─ work/       EodRefreshWorker, PatternScanWorker
```

### 4.3 Module boundaries (enforce these)

1. **`domain/` must not import `data/`.** Domain takes plain Kotlin data classes.
2. **`ui/` must not import `data/` directly.** Goes through `domain/` only.
3. **`domain/pattern`, `domain/technical`, `domain/redflag` are pure functions.**
   No network, no Room, no Android framework → unit-testable on the JVM with no Robolectric.

This boundary is what makes the work parallelizable across subagents.

---

## 5. Data Models (Kotlin)

```kotlin
data class Candle(
    val symbol: String,
    val timeframe: Timeframe,     // D1, W1
    val date: LocalDate,
    val open: Double, val high: Double, val low: Double, val close: Double,
    val volume: Long,
    val deliveryPercent: Double?, // from legacy bhavcopy; null if unavailable
    val source: Source            // NSE_BHAVCOPY | YAHOO
)

enum class Sector { BANKING, NBFC, IT, PHARMA, FMCG, AUTO, INSURANCE, METALS, CEMENT,
                    POWER, RENEWABLE, DEFENCE, RAILWAY, REALTY, TELECOM, AVIATION,
                    DURABLES, RETAIL, LOGISTICS, CAPITAL_GOODS, CHEMICALS, OTHER }

data class Ratios(
    val symbol: String,
    val sector: Sector,
    val asOf: LocalDate,
    val source: Source,
    val confidence: Confidence,   // AGREED | SINGLE | CONFLICT | STALE
    // Core, per §7 priority order
    val roce: Double?, val roe: Double?, val debtToEquity: Double?,
    val interestCoverage: Double?, val operatingMargin: Double?,
    val fcf: Double?, val pat: Double?, val cfoToOp: Double?,
    val revenueCagr3y: Double?, val revenueGrowth: Double?,
    val grossMargin: Double?, val pe: Double?, val pb: Double?,
    val evToEbitda: Double?, val dividendYield: Double?,
    val promoterHolding: Double?, val marketCap: Double?
)

data class Merged<T : Any>(val value: T, val source: Source,
                           val confidence: Confidence, val asOf: LocalDate)
```

### 5.1 Aggregation rule (derived from measurement — do not simplify)

Sources **agree on price but disagree on fundamentals.** Measured deltas:

| Field | screener.in | Yahoo | Delta |
|-------|-------------|-------|-------|
| RELIANCE Price | 1168.0 | 1167.7 | 0.03% ✓ |
| RELIANCE P/E | 21.2 | 21.142 | 0.27% ✓ |
| RELIANCE ROE | 8.91 | **null** | Yahoo gap |
| TCS P/E | 14.0 | 15.083 | **7.18% conflict** |
| TCS ROE | 51.8 | 47.743 | **8.50% conflict** |

Therefore:
- **Merge per field, not per source.** Never pick one winning source wholesale.
- Precedence: `price/OHLCV → NSE` (then Yahoo); `PE/ROE/ROCE/BookValue → screener.in`;
  `quarterly P&L, CFO/FCF/CFO-OP, shareholding → screener.in` (Yahoo has none).
- **On conflict (>5% divergence): show the primary value, render the other greyed beneath it
  with a divergence chip.** Do NOT average. Do NOT silently pick one.

### 5.2 Room entities

```
candles(symbol, timeframe, date)        PK(symbol,timeframe,date)  — 500 rows, write-once
symbols(symbol, name, isin, series)     PK(isin)
ratios(symbol, asOf)                    PK(symbol, asOf)
quarters(symbol, year, quarter)         PK(symbol, year, quarter)
patterns(id, symbol, timeframe, name, score, detectedOn)   FK(symbol)
pattern_pivots(patternId, index, price, kind)
outcomes(patternId, projectedHigh, actualHigh, actualLow, hit)
bhavcopy_cache(date)                    single-row rolling cache of all 3,712 symbols
work_state(taskId, lastSuccess, lastError)
```

`pattern_pivots` is normalised rather than a JSON blob so patterns can be re-scored
if the scorer changes, without re-detecting.

---

## 6. Pattern Detection & Scoring

### 6.1 The honesty contract (non-negotiable)

A control test on **20,780 next-day returns across 20 large caps** showed:

```
base rate  always up   : 50.10%
base rate  always down : 49.90%
RSI(14)          corr=-0.0033   dir. accuracy 50.24%
momentum 200d    corr=-0.0024   dir. accuracy 50.17%
z-score vs MA50  corr=-0.0109   dir. accuracy 49.81%
next-day move: median 0.73%, mean 0.99%
```

Everything is at 50%. **No score can be "foolproof."** The app must therefore keep two
separate numbers and never conflate them:

| Label | Meaning | Where shown |
|-------|---------|-------------|
| **Match score (0–100)** | Geometric fidelity to the textbook template | Next to the detected pattern |
| **Hit rate (%)** | How often this pattern actually played out, on this stock, from tracked history | Only after ≥30 samples, always with `n` |

**Hard rule:** match score must never be rendered as "80% chance of rise."
At 50.5% accuracy, costs (STT + GST + brokerage + slippage) exceed the edge entirely.
This is the product's core differentiator: the app shows its own track record.

### 6.2 Detection pipeline

```
Candles(500)
   → ZigzagPivots          (fractal/zigzag, depth configurable per timeframe)
   → Detector[12 patterns] run in parallel over the same pivot list
   → GeometryScorer        → 0–100 fit
   → ContextualScorer      → regime, relative strength
   → Merge + threshold
```

`ZigzagPivots` must be **shared** across all 12 detectors — this is the single biggest
performance win and the reason detection is fast enough to run on-device.

### 6.3 Score composition

| Factor | Weight | What it measures |
|--------|--------|------------------|
| Geometry | 40% | symmetry, shoulder/flag proportionality, slope consistency |
| Structure | 25% | clean pivots, trendline fit R², pivot spacing |
| Confirmation | 20% | volume expansion on breakout, RSI divergence, retest held, delivery % |
| Context | 15% | ADX regime alignment, relative strength vs index |

### 6.4 Thresholds

```
score >= 70 AND barsSufficient AND regimeAligned  →  project + schedule alert
50  <= score < 70                                  →  render, no projection
score < 50                                         →  suppress entirely
```

`barsSufficient` is a **hard gate**. Minimum bars: 1D ≥ 60, 1W ≥ 40.
If unmet, the app must say *"not enough history"* rather than emit a low-confidence score.
Never show a pattern with an unqualified confidence number when the sample is thin.

### 6.5 The 12 v1 patterns

```
Candlestick (5)    bullish engulfing, bearish engulfing, hammer,
                   shooting star, morning/evening star
Reversal (4)       head & shoulders, inverse head & shoulders,
                   double top, double bottom
Continuation (3)   ascending triangle, descending triangle, bull/bear flag
```

Each detector implements a common interface so adding patterns later is mechanical:

```kotlin
interface PatternDetector {
    val name: String
    val category: Category
    val minBars: Int
    fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match>
}
data class Match(val patternId: Long, val name: String, val score: Int,
                 val pivots: List<Pivot>, val measuredMove: MeasuredMove?)
```

### 6.6 Projections (D2 — both outputs)

1. **Measured move** — drawn on the chart. E.g. H&S measured move = neckline-to-head height,
   projected from breakout point. **Label it "pattern geometry", never "target price".**
2. **Directional forecast** — projected path for the next 1–5 bars. Rendered as a dashed
   projection, visually distinct from actual price.
3. **Breakout alert** — fires when the trigger condition is met (e.g. close above neckline).
   Distinct from the projection; never merged into it.

---

## 7. Fundamentals Tab

### 7.1 Sector resolution

Sector drives which ratios matter. Resolve from screener.in's sector page or a bundled map.
**Default to `OTHER` and show a generic list if unresolved — never guess.**

### 7.2 Priority order (descending)

Rationale: rank by *how much the ratio changes your conclusion about the business*.

| # | Ratio | Rationale |
|---|-------|-----------|
| 1 | **ROCE** | Best single measure of business quality; hard to manipulate |
| 2 | Operating margin + 5y trend | Pricing power and cost control |
| 3 | Debt/Equity + interest coverage | Survival |
| 4 | FCF vs PAT | #1 earnings-quality tell |
| 5 | Revenue CAGR (3–5y) | Trajectory |
| 6 | ROE | Duplicated leverage → deliberately ranked below ROCE |
| 7 | Gross margin trend | Input-cost pressure |
| 8 | P/E vs sector median | Valuation; a low P/E often signals a problem |
| 9 | P/B | Pairs with ROE; asset-heavy only |
| 10 | EV/EBITDA | Capital-structure neutral |
| 11 | Promoter holding trend | Capital-allocation confidence |
| 12 | Dividend yield + payout | Discipline |

### 7.3 Sector overrides (mandatory — a single global list misleads on banks)

| Sector | Replace/augment with |
|--------|----------------------|
| BANKING | ROE, NIM, GNPA/NNPA, CASA ratio, PCR — **drop D/E and current ratio** (meaningless) |
| NBFC | Asset quality, stage-2 GNPA, cost of funds, leverage |
| IT | TCS, attrition, constant-currency growth, revenue visibility |
| PHARMA | R&D as % of sales, USFDA compliance, product concentration |
| FMCG | Volume vs price growth split, gross margin, WC days |

**Evidence:** HDFCBANK returns `null` for `debtToEquity` and `currentRatio` from Yahoo — these
are simply not meaningful for banks. The override table is what prevents a misleading blank.

---

## 8. Red Flags

All are **deterministic threshold checks**. No AI, no inference.

### 8.1 Fundamentals

```
EARNINGS QUALITY
  CFO negative while PAT positive, 2+ consecutive years
  Trade receivables growing materially faster than revenue (>10pp gap)
  Inventory days rising sharply YoY
  Other income as % of PAT exceeding a sector threshold

BALANCE SHEET
  Debt/Equity spiking YoY
  Interest coverage below 2.0
  Goodwill > 20% of total assets
  Promoter pledging (if available from shareholding data)

VALUATION TRAPS
  P/E below sector median AND ROE declining  → value trap (book: Part 9)
  P/E rising while earnings fall
  Negative FCF yield
```

### 8.2 Technicals

```
  Close below 200-DMA AND SMA50 < SMA200        (death cross)
  RSI > 70 (overbought) / < 30 (oversold)
  Volume spike > 2.5x 20d average on a down day (distribution)
  Price at 52-week low with declining OBV
  Delivery % collapsing vs 20-day average
```

### 8.3 UX requirement

Red flags surface **at the top of both tabs**, not buried in a section. Each must be
tappable to show its **evidence** (the actual numbers), never a bare assertion.

Wording must be factual: *"Receivables grew 34% while revenue grew 9%."*
Never *"This company is risky."*

---

## 9. Symbol Resolution (build this FIRST)

**Why first:** search that returns a dead symbol makes everything else untrustworthy.
Testing showed **1 of 30 large caps (3.3%) fails on Yahoo** — `TATAMOTORS` returns zero rows
and is absent from official NSE bhavcopy, because it demerged into TATAMOTORS (PV) + TMPV.
This is a corporate action, not a provider bug.

Requirements:
1. Bundle `EQUITY_L.csv` (with ISIN) into `assets/` at build time.
2. Reconcile against daily bhavcopy; flag symbols present in one source but not the other.
3. Detect corporate actions from ISIN changes and surface them in the UI.
4. **Never return zero results silently** — always resolve to the nearest valid symbol and
   tell the user what happened.
5. Search locally (offline, instant, robots.txt-compliant).

---

## 10. Scheduling & Freshness

```
15:30 IST   market close
~18:00 IST  bhavcopy published
19:00 IST   WorkManager: fetch bhavcopy → parse → replace market cache
19:05 IST   WorkManager: rescan user's tracked symbols → generate alerts
```

- Constraints: `NetworkType.CONNECTED`, `requiresBatteryNotLow`, periodic ~24h with
  an initial delay so the run lands near 19:00 IST.
- Backoff: exponential, min 30 min. Store last success/failure in `work_state`.
- **Staleness must be visible in the UI.** Fundamentals can lag a quarter. A stale P/E shown
  as live is the fastest way to lose trust. Show "as of <date>" on every fundamentals figure.

---

## 11. Build Order (each phase independently shippable)

| Phase | Deliverable | Depends on |
|-------|-------------|-----------|
| **P0** | Project scaffold: Gradle (Kotlin 2.x, KSP2), Compose, Hilt, Room, version catalog | — |
| **P1** | **Symbol book + resolver** (§9). Bundled `EQUITY_L.csv`, ISIN-keyed Room, offline search | P0 |
| **P2** | **Bhavcopy parser** (§3.1): zip → CSV → `List<Candle>`, header normalisation, `EQ`/`SM` filter. Unit tests against saved fixture files | P1 |
| **P3** | **History fetch** (§3.2): Yahoo cookie+crumb, query1/query2 fallback, 500 daily + 261 weekly bars, cached write-once | P2 |
| **P4** | **Technicals tab**: indicators + charts + 1D/1W switch | P3 |
| **P5** | **Fundamentals** (§3.3): screener.in parser, ratio mapping, sector resolution, priority list | P1 |
| **P6** | **Red flags** (§8) — engine + UI, banner on both tabs | P4, P5 |
| **P7** | **Pattern engine** (§6): `ZigzagPivots` + 5 candlestick detectors + scoring | P4 |
| **P8** | 4 reversal + 3 continuation detectors, measured move, projections | P7 |
| **P9** | **Accuracy tracking** (§6.1, D6): `OutcomeTracker`, hit-rate table per stock+pattern | P8 |
| **P10** | WorkManager EOD refresh (§10), alerts | P6, P8 |
| **P11** | Hinglish voice search, share-brief-as-image, Play Store prep | P10 |

**Phase gates (must pass before proceeding):**
- P2: parse a saved bhavcopy fixture and assert exact field values for RELIANCE
  (`ClsPric == 1167.70`, `ISIN == INE002A01018`).
- P3: fetch 2y for 20 large caps; assert ≥490 rows and 0 unexpected failures.
- P7: score discrimination test — the 0–100 score must produce a **spread**, not cluster near
  a constant. If it does not discriminate, the scorer is noise and P8 must not start.

### 11.1 Open questions (ask the user before proceeding)

- **OQ-1:** Vico vs custom Compose Canvas for charts?
- **OQ-2:** Ship `DELIV_PER` (§3.5) or drop it given unverified availability?
- **OQ-3:** Monorepo single-module, or multi-module to enforce §4.3 boundaries at compile time?
- **OQ-4:** Do patterns run on all watchlist symbols nightly, or only on demand?

---

## 12. Testing Strategy

- **Unit tests for all of `domain/`** — pure JVM, no Android deps.
  Golden-file tests: save real RELIANCE/TCS/INFY JSON+HTML fixtures, assert parsed values.
- **Parser resilience tests**: malformed HTML, missing fields, changed class names.
  A parser must return a **health flag**, not an empty list. Silent-empty is indistinguishable
  from "no red flags" — the most dangerous possible failure.
- **Property tests**: zigzag never emits consecutive same-direction pivots; score always in
  [0,100]; measured move height always ≥ 0.
- **Score discrimination test** (P7 gate): verify score variance across real stocks.
- **Low-RAM device test**: target 2GB RAM devices. Many Indian users have budget hardware.
  Lazy-load briefs; verify a cold offline launch on a portfolio screen.

---

## 13. Known Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Bhavcopy format/URL changes silently | Total price-layer failure | Behind `PriceSource`; fixture-based tests |
| Yahoo 429 / crumb changes | History fetch dies | Handshake + query1/query2 fallback + cache write-once |
| CGNAT: many users share one carrier IP | Source sees 1 abusive scraper | Mitigated by design: 1 fetch/day for the **whole market**, not per symbol |
| Corporate actions break symbol mapping | User sees blank screen | ISIN-keyed resolver, §9 |
| Pattern scores look authoritative but are 50% edge | Credibility + regulatory risk | Two-number design, §6.1; show `n` with every hit rate |
| Misleading blank ratios (e.g. bank D/E) | User distrusts app | Sector overrides, §7.3 |
| HTML parser brittleness | Silent empty screens | Health flags, substring class matching, fixtures |

---

## 14. Evidence Log

Every empirical claim above, with the observation that produced it:

| Claim | Evidence |
|-------|----------|
| NSE EOD is free, no-auth | `GET .../BhavCopy_..._20261001_F_0000.csv.zip` → `200`, 208712 bytes, starts `PK` |
| Bhavcopy contains whole market | 3,712 rows; RELIANCE row: `ClsPric=1167.70`, `ISIN=INE002A01018`, `TtlTradgVol=16771221` |
| NSE official == Yahoo | RELIANCE close 1167.70 (NSE) vs 1167.7 (Yahoo) |
| Real-time NSE/BSE is blocked | `quote-equity` → `403`; `StockReachGraph` → `403` (Akamai `errors.edgesuite.net`) |
| Yahoo needs cookie+crumb | Bare `chart` and `getcrumb` → `429 Too Many Requests` |
| screener.in fundamentals complete | 163–169 `info` fields; RELIANCE ROCE 10.3%, ROE 8.91%, P/E 21.2, BV ₹668 |
| screener.in has 13 quarters | `quarters` table → 13 quarter headers |
| screener.in parser landmine | class is `"nowrap value"`, digits in child `<span class="number">` |
| Legacy bhavcopy header spaces | Raw header: `SYMBOL, SERIES, DATE1, PREV_CLOSE, ...` |
| TATAMOTORS failure | `TATAMOTORS.NS` → 0 rows; absent from bhavcopy; demerger. 1/30 = 3.3% |
| Equity master available | `EQUITY_L.csv` → `200`, 183140 bytes, includes ISIN column |
| Bhavcopy timing | 18:32 IST: today `404`, yesterday `200` |
| Bhavcopy retention | 2026 ✓, 2025 ✓, 2024 partial (1/3), 2023 ✗, 2021 ✗ |
| Historical bars available | 1d/2y → 500; 1wk/5y → 261 (RELIANCE, TCS, INFY, HDFCBANK) |
| No daily edge | 20,780 next-day returns, 20 large caps: all signals ~50% accuracy, corr ≈ 0 |
| robots.txt | `/*?q=`, `/*?page=`, `/*?sort=` disallowed; `/company/*` not |
| Series distribution | EQ 2662, SM 389, BE 246, ST 87, GS 55, GB 44 |

---

## 15. Provenance of the Domain Knowledge

The 12 patterns, the ratio priority order (§7.2), the sector overrides (§7.3), the red flags
(§8), and the scoring philosophy derive from a 275,000-word investing textbook at
`/home/srinidhi/Documents/AI-Invest` (Parts 2, 3, 7, 10, 11 — especially "Chapter 47 — Choosing
the Right Valuation Multiple", "Chapter 48 — Common Valuation Mistakes", "Chapter 16 — Pattern
Failure, False Breakouts", and "Chapter 23 — Volume Analysis").

**The book is the specification. When implementation questions arise about *why* a ratio is
ranked where it is, or how a pattern should be scored, consult it rather than inventing
logic.** Note `Part 10 - Practical Investing/Chapter 3 - Writing thesis.md` is empty (0 bytes)
and `Part 7 - Indian Sectors/Chapter 9` duplicates `Chapter 1` — both are known gaps.
