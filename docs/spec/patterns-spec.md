# Pattern Detection Specification (v1 — 12 patterns)

> **Status:** normative for `domain/pattern/`. Another agent implements detectors directly from this file.
> **Source of truth:** the investing textbook at `/home/srinidhi/Documents/AI-Invest`. Where this file
> cites a number, that number comes from the book. Where the book gives prose instead of a number, the
> number is chosen here and tagged **`[derived]`** with the reasoning inline.
> **Owner:** this file. Do not edit it from an implementation branch; report discrepancies instead.

---

## 0. How to read this document

| Tag | Meaning |
|-----|---------|
| `[Ch.N §x]` | Rule taken verbatim (numeric or textual) from the book. Chapter numbers are the book's **internal** numbering (`Chapter 17 — Candlestick Analysis`, `Chapter 18 — Chart Patterns`, `Chapter 16 — Price Action Basics`, `Chapter 21 — Volume Analysis`, `Chapter 22 — Technical Indicators`, `Chapter 19 — Trend Analysis (Part 4)`, `Chapter 11 — Earnings Quality and Accounting Red Flags`). |
| `[derived]` | The book states the concept in prose ("roughly equal", "long lower wick", "slightly sloping"). A number is chosen here. The reasoning is given so a reviewer can argue with the number rather than guess what was meant. |
| `[repo]` | Already implemented in `domain/` — reuse, do not redefine. |
| `DD-n` | A design decision the book does **not** make. Listed in §12. |

**Non-negotiable product rules restated** (plan.md §1.4, §6.1):
1. The match score measures **geometric fit to the textbook template**. It is never a probability of profit.
2. A measured move is **pattern geometry**, never a target price. `MatchScoreLabels.PROJECTION_LABEL` = `"Pattern geometry"`.
3. Timeframes are `D1` and `W1` only.
4. Min bars gate: `D1 ≥ 60`, `W1 ≥ 40` (`[repo]` `MatchScorePolicy.minBars`).
5. Failure modes are **penalties**, never extra pattern names (see §7.0).

---

## 1. Chapter map — where each rule comes from

| Topic | Book chapter | Used for |
|-------|---------------|----------|
| Swing highs/lows, market structure, noise vs trend | Part 3 Ch.16-1 "Price Action Basics (Part 1)" | §2 zigzag preconditions |
| Candle anatomy, context, Hammer, Shooting Star, Doji | Part 3 Ch.17-1 | Detectors 3, 4 |
| Bullish/Bearish Engulfing, Piercing, Dark Cloud, Tweezers, gaps, volume, confirmation | Part 3 Ch.17-2 | Detectors 1, 2 |
| Morning/Evening Star, Three Soldiers/Crows, star psychology, volume, multi-timeframe | Part 3 Ch.17-3 | Detector 5 |
| Candlestick reliability, 5 layers, false signals, decision pyramid | Part 3 Ch.17-4 | All candlestick penalties |
| Chart-pattern foundations, 3 phases, symmetry tolerance, duration, prior trend | Part 3 Ch.18-1 | §12 tolerances, prior-trend rule |
| Head & Shoulders / Inverse H&S | Part 3 Ch.18-2 | Detectors 6, 7 |
| Double/Triple Tops & Bottoms | Part 3 Ch.18-3 | Detectors 8, 9 |
| Flags, Pennants, Rectangles, Triangles, Wedges, Cup & Handle | Part 3 Ch.18-4 | Detectors 10, 11, 12 |
| Pattern failure, bull/bear traps, confirmation, throwbacks, measuring moves | Part 3 Ch.18-5 | §7.0 penalty table, all measured moves |
| Trend strength, ADX bands, market regimes, relative strength, divergence | Part 3 Ch.19-4 | Context factor, `regimeAligned` |
| Volume, RVOL, OBV, delivery %, accumulation/distribution, climax volume | Part 3 Ch.21 | Confirmation inputs |
| SMA/EMA, RSI + divergence, MACD, Bollinger, ATR, ADX, OBV, indicator traps | Part 3 Ch.22 | Confirmation inputs |
| Trendlines, 2-points-3rd-touch, price channels | Part 3 Ch.16-5 | Neckline/flag-channel fit |

---

## 2. Zigzag / pivot extraction — the shared front end

`ZigzagPivots` is computed **once** per `(symbol, timeframe)` and shared by all 12 detectors
(plan.md §6.2: "must be shared across all 12 detectors — this is the single biggest performance win").

### 2.1 What the book actually says

- A swing high is "a temporary peak formed before price begins declining"; a swing low is "a temporary
  bottom formed before price begins rising" `[Ch.16-1 §Swing High / Swing Low]`.
- "Professionals focus on **significant** swing highs and swing lows rather than every tick."
  `[Ch.16-1 §Noise vs Trend]`
- Noise consists of "small fluctuations, random volatility, minor reactions, short-term emotional
  trading" `[Ch.16-1 §Noise vs Trend]`.
- Trendlines are "best drawn using **significant** swing highs and swing lows, not every minor
  fluctuation" `[Ch.16-5 §What is a Trendline?]`.
- Trendline rule: "Two swing points create a trendline. The **third touch validates it.**"
  `[Ch.16-5 §Rule 2]`.
- A trendline is a *zone*, not an exact line: "Price rarely touches a trendline exactly. Small
  deviations are normal." `[Ch.16-5 §Rule 4]`

**The book never states a percentage or an ATR multiple for "significant".** Every number in §2.2–§2.4
is `[derived]`, chosen so that "significance" is defined by the book's *own* volatility measure
(ATR, Ch.22 §22.26) rather than by an invented constant.

### 2.2 Depth threshold

```
depthPct = DEPTH_PCT[timeframe]        // floor, as % of the pivot's own price
depthAtr = DEPTH_ATR[timeframe]        // ATR multiple

requiredMove = max(depthPct/100 × price, depthAtr × ATR14(barIndex))
```

| Constant | `D1` | `W1` | Basis |
|----------|------|------|-------|
| `DEPTH_ATR` | **3.0** | **2.5** | `[derived]` The book's only stated price-magnitude rule is a 2-ATR stop `[Ch.22 §22.27]`. A swing that retraces less than 2 ATR is inside the book's own definition of noise. We use 3.0/2.5 (a superset) so that no pivot the book would call significant is discarded. Weekly gets a smaller multiple because one weekly bar already contains five daily ranges. |
| `DEPTH_PCT` | **1.5 %** | **2.5 %** | `[derived]` ATR collapses in a dead-flat stock (a ₹20 scrip can have ATR₍₁₄₎₍D1₎ ≈ ₹0.30 while moving 1 % a month). The percentage floor stops the zigzag from degenerating. Weekly needs more because a weekly bar's ATR understates a multi-week base. |

### 2.3 The algorithm

State machine over `candles[0..n-1]`, in order. Produces `pivots: List<Pivot>` where
`Pivot(barIndex, date, price, kind, legIndex)` — all fields already defined `[repo]` `domain/model/Pattern.kt`.

```
phase = SEEK_HIGH           // we do not know the first pivot's kind yet
dir   = UNKNOWN
last  = null                // the confirmed extreme of the current leg
for i in 0 until n:
    h = candles[i].high ; l = candles[i].low
    match phase:
      SEEK_HIGH:
          if h > last.price: last = Extreme(i, h, HIGH)
          else:
              // price fell away from `last` — is the fall enough to confirm the high?
              if last.price - l >= requiredMove(last, i):
                  emit(last, HIGH)
                  phase = SEEK_LOW ; legStart = last ; last = Extreme(i, l, LOW)
      SEEK_LOW:
          if l < last.price: last = Extreme(i, l, LOW)
          else:
              if h - last.price >= requiredMove(last, i):
                  emit(last, LOW)
                  phase = SEEK_HIGH ; legStart = last ; last = Extreme(i, h, HIGH)
```

**`emit` enforces the alternation invariant.** plan.md §12 requires the property test
`zigzag never emits two consecutive same-kind pivots`. It holds by construction above (the phase
flips on every emit), and additionally `emit` re-checks: if the candidate's kind equals the previous
emitted kind, the **more extreme** of the two is kept and the leg index is not advanced.

`requiredMove(last, i)` uses `ATR14` at bar `i` (already computed in `IndicatorSnapshot.atr`) and the
price of the pivot candidate.

### 2.4 Fallback pass — guaranteeing enough candidates

Some detectors need 5 consecutive alternating pivots (§3.6 H&S, §3.8 double top). If the primary
depth yields too few, re-run shallower.

```
primary   = depth(D1: 3.0 ATR / 1.5 %, W1: 2.5 ATR / 2.5 %)
if primary.pivots.size < MIN_PIVOTS:            // MIN_PIVOTS = 8   [derived]
    secondary = depth × 0.60                     // D1: 1.8 ATR / 0.9 %  [derived]
    if secondary.pivots.size > primary.pivots.size:
        use secondary
```

`MIN_PIVOTS = 8` `[derived]`: an H&S needs 5 and a flag needs 6, so 8 leaves one candidate formation
per detector plus slack. Rationale for the `0.60` factor: a shallower pass must still reject noise, and
0.6 is the largest reduction that keeps the ATR floor above the book's 2-ATR stop on `W1`
(`2.5 × 0.6 = 1.5 ATR`) while still roughly tripling the pivot count on real data.

**Both passes must still satisfy §2.5.** Depth is per-timeframe and never per-pattern; all 12 detectors
read the identical list.

### 2.5 Confirmation lag — `isConfirmed`

```
confirmLagBars = 1 (D1)   /  1 (W1)
pivot.isConfirmed  ==  (n - 1 - pivot.barIndex) >= confirmLagBars
```

A pivot is only *confirmed* after price has traded away from it by the required move. The last pivot in
the list is usually unconfirmed. **Structural detectors (6–12) skip unconfirmed pivots entirely.**
`[derived]`, reasoning: a same-day extreme reverses tomorrow and re-scores the whole formation; a
one-bar lag on EOD data costs one evening of freshness and removes day-to-day score flicker
(plan.md §11 targets an EOD refresh at 19:00 IST, so the lag is invisible to the user).

**Candlestick detectors (1–5) do not use pivots at all** — they read `candles` directly (see §4).
This is why the shared zigzag can afford to be conservative.

---

## 3. Shared geometric helper library

Implemented once in `domain/pattern/Geometry.kt`. Pure functions. Every detector uses these and
nothing else geometric. `scoring-spec.md` consumes the same named measures, so a rename here is a
breaking change for both files.

### 3.1 Primitives

| Name | Formula | Notes |
|------|---------|-------|
| `atr14(bar)` | `IndicatorSnapshot.atr[bar]`, Wilder 14 `[repo]` | The book's volatility yardstick `[Ch.22 §22.26]`. |
| `ref(pattern)` | pattern scale in ₹ — see §3.2 | Every tolerance below is divided by it. `ref <= 0` ⇒ reject the candidate. |
| `sym(a, b)` | `min(a,b) / max(a,b)` | Symmetry ratio. `0 ≤ sym ≤ 1`. `sym == 1` ⇒ identical. `[derived]` name/definition. |
| `prop(small, large)` | `small / large` | Proportionality of an ordered pair (head vs shoulder, first vs second peak). |
| `slopePerBar(p1, p2)` | `(p2.price − p1.price) / (p2.barIndex − p1.barIndex)` | ₹ per bar. Requires `barIndex` to differ. |
| `slopePctPerBar(p1, p2)` | `slopePerBar(p1,p2) × 100 / p1.price` | % per bar. |
| `slopeOverSpan(p1, p2, spanBars)` | `slopePerBar(p1,p2) × spanBars / p1.price × 100` | % of price **across a span** — the timeframe-independent slope measure. Used for every slope tolerance. |
| `gap(a, b)` | `b.barIndex − a.barIndex` | Bars between pivots. Always `> 0` in a valid alternating list. |
| `barPct(x, y)` | `(x − y) / y × 100` | Signed % change. |

### 3.2 Pattern scale `ref`

`ref` must be defined **per detector** and is what makes every tolerance scale-free.

| Detector | `ref` |
|----------|-------|
| H&S / inverse H&S | `headPrice − necklineAt(headBar)` (the book's measured-move height `[Ch.18-2 §Measuring the Price Target]`) |
| Double top | `max(peak1, peak2) − trough` |
| Double bottom | `resistance − min(bottom1, bottom2)` |
| Ascending triangle | `resistance − lowestLowOfFormation` |
| Descending triangle | `highestHighOfFormation − support` |
| Flag | `max(flagpoleHeight, channelHeight)` |
| Candlestick (1–5) | `candles[n].high − candles[n].low` (that bar's range) |

### 3.3 Line-fit measures

Given a set of pivots `S = {(x_i, y_i)}` that a boundary is supposed to pass through:

```
fit = linearRegression(y over x)                     // [repo] domain/technical/Regression.kt
resid_i   = |y_i − fit.at(x_i)|
residMax  = max(resid_i) / ref
residMean = mean(resid_i) / ref
r2        = fit.rSquared            // 0 when fit.isDegenerate  [repo] IndicatorSnapshot convention
```

`linearRegression` returns `isDegenerate = true` and `rSquared = 0.0` for a flat series or `n < 3`
`[repo]`. **A flat series scores `r² = 0`, not 1** — that is deliberate and is what a horizontal
neckline must *not* be penalised by. See `scoring-spec.md` §3 for how a degenerate fit is handled.

### 3.4 Tolerance helpers

```
tolerancePct(basePct)  -> basePct/100 × price
toleranceAtr(mult)     -> mult × atr14(bar)
tolLevel(pivot)        -> max(tolerancePct(DEPTH_PCT[tf] × TOUCH_TOL_MULT[tf]),
                               toleranceAtr(TOUCH_TOL_ATR[tf]))
TOUCH_TOL_MULT[D1/W1]  = 1.5   [derived]
TOUCH_TOL_ATR [D1/W1]  = 0.50  [derived]
```

A pivot `p` **touches** a boundary line `L` iff `|p.price − L.at(p.barIndex)| ≤ tolLevel(p)`.
`TOUCH_TOL_MULT = 1.5` and `TOUCH_TOL_ATR = 0.50` are `[derived]`: the book's "third touch validates
the trendline" `[Ch.16-5 §Rule 2]` plus "price rarely touches a trendline exactly, small deviations
are normal" `[Ch.16-5 §Rule 4]` together imply a band roughly half the significance threshold
(`DEPTH_ATR × 0.5`), i.e. a touch is something that would not itself have been called a pivot.

### 3.5 Monotonicity / progression

For a sequence of same-role pivots `v_1 … v_k` (the lows of an ascending triangle, the highs of a
descending triangle):

```
progressPct = (v_k − v_1) / (x_k − x_1) / v_1 × 100        // signed, % per bar
monoScore   = (# of consecutive pairs with the required sign) / (k − 1)   // 0..1
```

`monoScore = 1.0` ⇒ every step moved the required way. `[derived]` definition.

### 3.6 Prior-trend measure (shared by detectors 6–12)

The book is emphatic: "A chart pattern must be interpreted relative to the preceding trend"
`[Ch.18-1 §The Importance of Trend Before the Pattern]`; "Was there a clear uptrend before the
pattern?" `[Ch.18-2 §The Role of Trend]`; "Without a prior decline, it [a double bottom] is simply two
similar lows" `[Ch.18-1 §The Importance of Trend Before the Pattern]`.

```
priorTrendPct = (close[pivotBar] − close[pivotBar − LOOKBACK]) / close[pivotBar − LOOKBACK] × 100
LOOKBACK      = 20 bars (D1) / 13 bars (W1)      [derived]  (≈ 1 month of sessions / 1 quarter)
```

| Direction | Required | `[derived]` justification |
|-----------|----------|---------------------------|
| Bullish (`UP`) | `priorTrendPct ≥ +3.0` (D1) / `+5.0` (W1) | A "clear trend" must be large enough that a random walk over one month would exceed it only rarely. 3 % over 20 sessions is ≈ 2.4 daily σ for a typical large cap. Weekly bars already embed 5 sessions, hence the higher bar. |
| Bearish (`DOWN`) | `priorTrendPct ≤ −3.0` (D1) / `−5.0` (W1) | mirror |

This constant is reused by the candlestick detectors for "after a sustained decline" `[Ch.17-2
§Best Location]` — see §4.1 `CONTEXT_DECLINE_PCT`.

### 3.7 Breakout / failure detection (shared, drives all penalties)

Ch.18-5 defines the confirmation set and the traps. Implemented once:

| Function | Rule | Book |
|----------|------|------|
| `breakoutLevel(line, bar)` | the boundary price at bar `bar` | — |
| `isBreakout(candles, from, level, dir)` | `close[j] > level` (UP) / `close[j] < level` (DOWN) for the **first** `j ≥ from` | "Professional analysts place much greater importance on **closing prices** than on temporary intraday movements" `[Ch.18-5 §Breakout vs Intraday Spike]` |
| `breakoutDepthPct(close, level)` | `(close − level)/level × 100` (UP) | "Strong Close: price closes decisively beyond the breakout level. Not merely above it intraday" `[Ch.18-5 §Confirmation Techniques]` |
| `isBullTrap(candles, from, level, dir)` | within `TRAP_WINDOW` bars a close beyond `level` was followed by a close back inside | `[Ch.18-5 §Bull Trap / §Bear Trap]`, `[Ch.18-5 §Why False Breakouts Occur]` |
| `isFailedRetest(candles, breakoutBar, level, dir)` | price returned to `level ± tolLevel` and then closed back through it **against** the breakout direction | "Throwback … old resistance has become new support" `[Ch.18-5 §Throwbacks and Pullbacks]`; a throwback that fails is the failed-pattern case `[Ch.18-2 §Failed Head & Shoulders]` |
| `TRAP_WINDOW` | `3` bars (D1) / `2` bars (W1) | `[derived]`. The book says professionals wait for follow-through of "the next one or two sessions" `[Ch.18-5 §Confirmation Techniques §3]`; 3 daily / 2 weekly keeps the same wall-clock window (~3 sessions either way). |

**A `close`-based breakout is mandatory.** An intraday `high` crossing a boundary is never a breakout.

### 3.8 Confirmation inputs (chapter 21 + 22)

All already computed in `IndicatorSnapshot` `[repo]`.

| Input | Definition | Threshold | Book |
|-------|-----------|-----------|------|
| `rvol[j]` | `volume[j] / volumeSma20[j]` `[repo]` `latestVolumeRatio` | confirm `≥ 2.0`; strong `≥ 3.0` | "third candle volume is **twice** the average → buyers are demonstrating conviction" `[Ch.17-3 §Role of Volume]`; "Breakout volume 2× average" `[Ch.22 §22.34]`; "Volume is **three times** the 20-day average" `[Ch.21-6]` / "Volume: 3× average" `[Ch.17-4 §Layer 3]`; plan.md §8.2 uses `2.5` for the *distribution* red flag `[repo] TechnicalPolicy.VOLUME_SPIKE_MULTIPLE` |
| `rvolBreakout` | `rvol[breakoutBar]` | same | "A breakout without volume is suspicious" / "Volume should exceed average volume" `[Ch.21 §21.5, §21.13]` |
| `volumeContraction` | `mean(volume over formation bars) / mean(volume over the 20 bars before the formation)` | healthy formation `≤ 1.00`; ideal `≤ 0.80` | "During consolidation … volume naturally **contracts**" `[Ch.18-4 §Volume During Continuation Patterns]`; "Typical sequence: beginning high volume → middle declining volume → breakout sharp increase" `[Ch.18-1 §The Importance of Volume]` |
| `deliveryPct[j]` | `delivery[j]` or `null` `[repo]` `Candle.deliveryPercent` | — | `Delivery % = Delivery Qty / Total Traded Qty × 100` `[Ch.21 §21.18]` |
| `deliveryVsAvg(j)` | `deliveryPct[j] / deliveryAveragePercent[j]` | confirm `≥ 1.00` | "High delivery + rising price → usually bullish … possible long-term accumulation" `[Ch.21 §21.19]` |
| `obvSlope[j]` | OBV regression slope over 5 bars ending at `j` `[repo]` `obvSlope` | confirms if sign agrees with the pattern direction | "If price closes higher add volume, if lower subtract volume"; "Price flat, OBV increasing → possible accumulation" `[Ch.22 §22.29]` |
| `rsi[j]` | Wilder RSI(14) `[repo]` | overbought `> 70`, oversold `< 30` | `[Ch.22 §22.16]` |
| `divergence(pivots, rsi)` | see below | — | `[Ch.22 §22.17]`, `[Ch.19-4 §Divergence]` |
| `adx[j]`, `di+`, `di−` | Wilder ADX(14) `[repo]` | `<20` weak, `20–25` developing, `25–50` strong, `50+` very strong | `[Ch.19-4 §Understanding ADX Values]` (book's own four-row table) |
| `relativeStrength` | normalised stock/index ratio `[repo]` | `> 1.0` = outperforming | `[Ch.19-4 §Relative Strength Ratio]` |

**Divergence** (`[Ch.22 §22.17]` definitions, used verbatim):

```
bearishDivergence(pA, pB, rsiA, rsiB)  ==  pB.price > pA.price  AND  rsiB < rsiA
bullishDivergence(pA, pB, rsiA, rsiB)  ==  pB.price < pA.price  AND  rsiB > rsiA
```

Both `pA, pB` must be same-kind pivots **inside the pattern**, separated by `≥ 3` bars.
Confirmation strength = the RSI gap in RSI points: `gapRsi = |rsiB − rsiA|`; `≥ 3.0` points = full
confirmation `[derived]`. The book gives no magnitude, only "price makes a higher high / RSI makes a
lower high"; 3 RSI points is the smallest gap that survives sampling noise on a 14-period RSI.

**Direction mapping (which signal confirms which pattern)** — from the book's own physiology:

| Signal | Confirms | Rejects |
|--------|----------|---------|
| Bullish RSI divergence | 1, 3, 5-bull, 7 (inv H&S), 9 (double bottom), 10 (asc triangle), 12-bull (bull flag) | 6 (H&S), 8 (double top), 11 (desc triangle), 12-bear |
| Bearish RSI divergence | 6 (H&S), 8 (double top), 11 (desc triangle), 12-bear | all bullish patterns |
| `rvol ≥ 2.0` on the reversal bar / breakout bar | all, in the pattern's direction | a low-volume third candle `[Ch.17-3 §Role of Volume]` |
| OBV slope agreeing | all, in the pattern's direction | `[Ch.22 §22.29]` |
| Delivery % on the reversal bar ≥ its 20-bar average | all bullish; **high delivery + falling price = selling pressure**, so it *penalises* bullish patterns | `[Ch.21 §21.19]` |
| Volume contracting through the formation | 10, 11, 12 | 6, 7, 8, 9 (book expects volume *decline* at the head / second peak too `[Ch.18-2 §Importance of Volume]`, `[Ch.18-3 §Volume Behavior]` — so contraction is a bonus for all, and a *requirement* only for continuation patterns) |

---

## 4. Detector interface contract

Restated so all 12 are interchangeable. The canonical types are `[repo]`
`domain/src/main/kotlin/com/mia/app/domain/model/Pattern.kt`; **do not redefine them**.

```kotlin
interface PatternDetector {
    val name: String
    val category: Category          // CANDLESTICK | REVERSAL | CONTINUATION
    val minBars: Int
    fun detect(pivots: List<Pivot>, candles: List<Candle>): List<Match>
}
```

Invariants every implementation must honour:

1. **Pure.** No IO, no clock, no Android, no `Random`, no global mutable state. Same inputs ⇒ same
   outputs, byte-for-byte, so the detectors are JVM unit-testable (plan.md §4.3, §12).
2. **`pivots` is the shared list from §2.** A detector must not recompute or re-filter it, except
   detectors 6–12 dropping `isConfirmed == false` pivots (which they do by slicing, not by re-running).
3. **Ordered oldest → newest.** Detectors scan every window of the pivot list and return matches in
   ascending `detectedOn` order.
4. **No match may be returned with a score below `MatchScorePolicy.SUPPRESS_THRESHOLD`.** Detectors
   compute the breakdown; the gate in `scoring-spec.md` §6 decides the verdict. Returning a
   suppressed match is a contract violation (it would let the UI render a pattern it must hide).
5. **`Match.measuredMove` is `null` for detectors 1–5** (see DD-1) and non-`null` for 6–12.
6. **At most one `Match` per detector per (formation, breakout) pair.** If two windows both qualify,
   emit the one with the higher `score`; ties break toward the **more recent** formation.
7. **Candlestick detectors (1–5) ignore `pivots` entirely** and scan `candles` directly. This is why
   the zigzag can be conservative without starving them.
8. `Match.pivots` must be **exactly** the pivots the detector used, in order, so the UI can draw the
   shape and `pattern_pivots` (plan.md §5.2) can re-score later without re-detecting.

---

## 5. Candlestick family — shared preamble

Detectors 1–5 share these facts.

### 5.1 Candle measures

For bar `j`:

```
body       = |close[j] − open[j]|
upperWick  = high[j] − max(open[j], close[j])
lowerWick  = min(open[j], close[j]) − low[j]
range      = high[j] − low[j]
bodyFrac   = range > 0 ? body / range : 0
upperFrac  = range > 0 ? upperWick / range : 0
lowerFrac  = range > 0 ? lowerWick / range : 0
isBull     = close[j] > open[j]      // strict; equality is a doji, not bullish
isBear     = close[j] < open[j]
```

`range == 0` (a flat bar) ⇒ `bodyFrac = upperFrac = lowerFrac = 0` and the bar can never satisfy any
candlestick shape rule.

### 5.2 Context constants

```
CONTEXT_DECLINE_PCT[D1/W1]  = 3.0 / 5.0   [derived]   "after a sustained decline"
CONTEXT_RISE_PCT   [D1/W1]  = 3.0 / 5.0   [derived]   "after prolonged rallies"
CONTEXT_LOOKBACK            = 20 (D1) / 13 (W1)  [derived]
```

Same values and same justification as `priorTrendPct` (§3.6) — the book uses the words
"sustained decline" `[Ch.17-2 §Best Location]`, "prolonged decline" `[Ch.17-3 §Best Location]`,
"prolonged rallies" `[Ch.17-2 §Best Location]` for both candlesticks and chart patterns, so they must
not be graded differently.

### 5.3 The five-layer check `[Ch.17-4 §The Five Layers]`

The book ranks context above the candle: **Candlestick → Location → Trend → Volume → Market
Structure** (`[Ch.17-4 §The Candlestick Decision Pyramid]`, "candlestick patterns form the
foundation—not the peak"). So for detectors 1–5 the *trend* and *location* tests are **preconditions
or heavy penalties**, never bonuses only.

| Layer | Test | If it fails |
|-------|------|-------------|
| Location | pattern forms within `LOCATION_BAND_PCT` of the 20-bar extreme | penalty `CTX_MISMATCH` |
| Trend | §5.2 context test | penalty `CTX_MISMATCH` |
| Volume | `rvol ≥ 1.5` on the signal bar | penalty `WEAK_VOLUME` |
| Market structure | no contradictory structure in the next 5 bars | penalty `STRUCTURE_CONTRADICTS` |
| Multi-timeframe | the other timeframe is not making the opposite structure | penalty `HTF_CONFLICT` |

`LOCATION_BAND_PCT = 2.0` `[derived]`: "forms near strong support / at demand zones"
`[Ch.17-2 §Best Location]` gives no distance; 2 % of price is the smallest band that reliably
separates "at the level" from "somewhere in the lower half of the range".

---

## 6. Detector 1 — Bullish Engulfing

**Category** `CANDLESTICK` · **Book** `[Ch.17-2 §Bullish Engulfing Pattern]` · **Bars needed** 2 (+1 for confirmation)

### 6.1 Preconditions
- **P1** `candles.size ≥ MatchScorePolicy.minBars(timeframe)` `[repo]`.
- **P2** Candidate pair is bars `(n−1, n)`; the scan considers every `n ≥ 1`. The context window is
  bars `[n−20, n−2]` for `D1`, `[n−13, n−2]` for `W1`.
- **P3** The `CONTEXT_DECLINE_PCT` test must pass **for a match to be returned at all**: `priorTrendPct(n−2) ≤ −CONTEXT_DECLINE_PCT`. Book: "Bullish Engulfing patterns become more meaningful when they appear after a sustained decline" `[Ch.17-2 §Best Location]`; "Much less useful: during sideways markets, near major resistance, in overextended rallies" `[Ch.17-2 §Poor Locations]`. *(DD-2 — a hard precondition vs a penalty; see §12.)*

### 6.2 Shape (numeric)
- **S1** `isBear(n−1)` and `isBull(n)`. Book §Structure: "Day 1: Bearish candle. Day 2: Bullish candle."
- **S2** `open[n] < close[n−1]` **and** `close[n] > open[n−1]`. Book: "The body completely engulfs the
  previous candle's body." Strict `<`/`>` so a doji pair cannot qualify; `[derived]` only in the sense
  that the book does not address the degenerate equality case.
- **S3** `body[n] ≥ 1.20 × body[n−1]`. Book: "The second body is **significantly larger** than the
  first." No number ⇒ **`1.20` `[derived]`**: it enforces "significantly" without demanding a doubling
  (which the book's own worked example does not show: ₹15 body after a ₹15 body, Ch.17-2 §Example).
- **S4** `close[n] ≥ open[n−1]` is implied by S2; the **engulfment depth** measure is
  `engulf = (close[n] − open[n−1]) / body[n−1]`, and `engulf > 0` is required. It is the primary
  *geometry* measure fed to the scorer.
- **S5 (optional, scored not required)** `open[n] ≤ close[n−1]` — the "gap down" opening. The book is
  explicit that this is *not* required: "Some classic candlestick definitions emphasize price gaps …
  Modern traders generally focus more on the shift in buying and selling pressure than on strict
  textbook gap requirements" `[Ch.17-2 §Gap Openings]`. Treated as a **bonus**, never a gate.

### 6.3 Validity / invalidation → penalties
| Id | Condition | Effect |
|----|-----------|--------|
| `I1` | `close[n+1] ≤ open[n−1]` — the whole engulfing body is retraced within one bar | `NO_FOLLOW_THROUGH` penalty |
| `I2` | `high[n+1] > close[n]` **and** `close[n+1] < close[n]` | `BULL_TRAP` penalty `[Ch.18-5 §Bull Trap]` |
| `I3` | bars `[n+1, n+5]` still print a lower low than `low[n]` | `STRUCTURE_CONTRADICTS` — "the broader downtrend remains intact. The pattern may simply produce a temporary bounce" `[Ch.17-2 §Combining with Market Structure]` |
| `I4` | `close[n] > high[n−1]` **and** `rvol[n] < 1.5` | `WEAK_VOLUME` — "Strong Bullish Engulfing … carries more weight than the same pattern on low volume" `[Ch.17-2 §The Importance of Volume]` |

### 6.4 Measured move
**None.** `Match.measuredMove = null`. `[Ch.17-2]` defines no projection for any candlestick pattern,
and plan.md §6.6 gives measured-move examples only for H&S. See **DD-1**.

### 6.5 Confirmation inputs `[Ch.17-2 §Confirmation]`, `[Ch.17-4 §Layer 3]`
- **C1 Volume** `rvol[n] ≥ 2.0` → confirms; `≥ 3.0` → strong. `[Ch.17-3 §Role of Volume]`, `[Ch.17-4 §Layer 3]` ("Volume: 3× average").
- **C2 Follow-through** `close[n+1] > close[n]` for 1–2 sessions `[Ch.17-2 §Confirmation]`, `[Ch.18-5 §Confirmation §3]`.
- **C3 Structure** a close above the swing high that preceded the pattern, within 5 bars `[Ch.17-2 §Combining with Market Structure]` ("Break Above Lower High").
- **C4 RSI** bullish divergence — price lower low, RSI higher low `[Ch.22 §22.17]`; or `rsi[n] < 30` `[Ch.17-4 §Practical Example]`.
- **C5 OBV** `obvSlope` over the 5 bars ending at `n` is `> 0` `[Ch.22 §22.29]`.
- **C6 Delivery** `deliveryVsAvg(n) ≥ 1.00` `[Ch.21 §21.19]`. **Penalty** if `rvol[n] ≥ 2.0` but `deliveryVsAvg(n) < 0.70` — big volume, no ownership transfer: "a high delivery percentage … does not automatically imply accumulation"; the mirror case is intraday churn `[Ch.21-6 §Delivery Volume]`.

### 6.6 False-signal traps `[Ch.17-4 §False Signals]`, `[Ch.17-4 §Candlestick Analysis in Different Market Phases]`
| Trap | Detection | Penalty |
|------|-----------|--------|
| News / earnings surprise | not detectable from our data | **DD-8** |
| Macro / broad-market weakness | index down on the same bar | `CTX_MISMATCH` |
| Low liquidity | `volumeSma20 < 0.15 × sharesOutstanding` | `LOW_LIQUIDITY` |
| Highly volatile market | `atrPct[j] > 2.0 × median(atrPct over trailing 120 bars)` | `VOLATILE_MARKET` — "Candlestick patterns become less reliable" `[Ch.17-4]` |
| Wrong location | §5.3 Layer 1 fails | `CTX_MISMATCH` |
| Multi-timeframe conflict | the other timeframe still makes LH/LL `[Ch.17-3 §Multi-Timeframe Perspective]` | `HTF_CONFLICT` |

### 6.7 Worked example (unit-test fixture)
```
context (D1): close[n−20] = 512.00, close[n−2] = 498.20        → priorTrendPct = −2.70 %
              ⇒ FAILS the −3.0 % precondition. This pair is NOT a match.
```
So the next candidate:
```
close[n−20] = 520.00
n−2 : O=505.00 H=506.10 L=498.20 C=500.00   (bearish, body = 5.00)
n−1 : O=497.00 H=506.00 L=496.00 C=501.00   (bullish,   body = 4.00)
n   : O=496.50 H=512.00 L=496.00 C=509.50   (bullish,   body = 13.00)
priorTrendPct(n−2) = (500.00 − 520.00)/520.00 × 100 = −3.846 %   ✔ ≤ −3.0 %
S1  isBear(n−1) ✔ ; isBull(n) ✔
S2  open[n]=496.50 < close[n−1]=501.00 ✔ ; close[n]=509.50 > open[n−1]=497.00 ✔
S3  body[n]=13.00 ≥ 1.20 × 4.00 = 4.80 ✔
    engulf = (509.50 − 497.00)/4.00 = 3.125
C1  vol[n] = 4,180,000 ; volumeSma20[n] = 1,395,000 ⇒ rvol = 3.00 ✔ strong
    (rvol = 3.00 matches the book's own "3× average" example [Ch.17-4 §Layer 3])
C2  close[n+1] = 511.20 > 509.50 ✔
I4  rvol[n] = 3.00 ≥ 1.5, so the weak-volume condition does not apply.
⇒ MATCH. geometry inputs: engulf = 3.125, bodyRatio = 13.00/4.00 = 3.25, declinePct = 3.846
```

---

## 7. Detector 2 — Bearish Engulfing

**Category** `CANDLESTICK` · **Book** `[Ch.17-2 §Bearish Engulfing Pattern]`

Exact mirror of §6 with every sign flipped and the context precondition inverted:

- **P3** `priorTrendPct(n−2) ≥ +CONTEXT_RISE_PCT` ("Most meaningful: after prolonged rallies, near
  major resistance, at supply zones" `[Ch.17-2 §Best Location]`).
- **S1** `isBull(n−1)`, `isBear(n)`.
- **S2** `open[n] > close[n−1]` and `close[n] < open[n−1]`.
- **S3** `body[n] ≥ 1.20 × body[n−1]`.
- **S4** `engulf = (open[n−1] − close[n]) / body[n−1]`, require `> 0`.
- **S5 (bonus)** `open[n] ≥ close[n−1]` (gap up) `[Ch.17-2 §Gap Openings]`.
- **Penalties** `I1` `close[n+1] ≥ open[n−1]`; `I2` `low[n+1] < close[n]` and `close[n+1] > close[n]`
  ⇒ `BEAR_TRAP` `[Ch.18-5 §Bear Trap]`; `I3` bars `[n+1,n+5]` print a higher high ⇒ `STRUCTURE_CONTRADICTS`;
  `I4` `close[n] < low[n−1]` and `rvol[n] < 1.5` ⇒ `WEAK_VOLUME`.
- **Confirmation** bearish RSI divergence, `rvol[n] ≥ 2.0`, follow-through down, close below the
  preceding swing low, `obvSlope < 0`, and `deliveryVsAvg(n) ≥ 1.00` (high delivery + falling price
  = "selling pressure" `[Ch.21 §21.19]`, which **confirms** this bearish pattern).

### Worked example
```
close[n−20] = 480.00 ; close[n−2] = 498.00  ⇒ priorTrendPct = +3.75 %  ✔ ≥ +3.0 %
n−2 : O=498.00 H=505.00 L=497.50 C=510.00   (bullish, body = 12.00)
n−1 : O=511.00 H=512.00 L=508.00 C=509.00   (bearish, body =  2.00)
n   : O=512.50 H=513.00 L=494.00 C=496.00   (bearish, body = 16.50)
S1 ✔ ✔ ; S2 512.50 > 509.00 ✔ , 496.00 < 511.00 ✔ ; S3 16.50 ≥ 2.40 ✔
engulf = (511.00 − 496.00)/2.00 = 7.50
rvol[n] = 3,900,000 / 1,300,000 = 3.00  ✔ strong
close[n+1] = 494.10 < 496.00 ✔ follow-through
⇒ MATCH
```

---

## 8. Detector 3 — Hammer

**Category** `CANDLESTICK` · **Book** `[Ch.17-1 §Hammer]`, `[Ch.17-1 §Pattern vs Context]`

### 8.1 Preconditions
- **P1** min bars `[repo]`.
- **P2** `CONTEXT_DECLINE_PCT` test: `priorTrendPct(n) ≤ −CONTEXT_DECLINE_PCT`. Book: "A Hammer is
  meaningful primarily: **after a decline**. If it appears randomly in the middle of a sideways market,
  its significance is limited" `[Ch.17-1 §Important Rule]`.
- **P3** Location: `low[n] ≤ min(low over context window) × (1 + LOCATION_BAND_PCT/100)`. Book:
  "forms exactly at a long-term support zone" `[Ch.17-4 §Layer 1]`, "at major support after prolonged
  decline → Stronger bullish implication" `[Ch.17-1 §Pattern vs Context]`.

### 8.2 Shape (numeric) — all `[derived]`
The book gives **only adjectives**: "small body", "long lower wick", "little upper wick" `[Ch.17-1 §Hammer]`.

- **S1** `lowerFrac ≥ 0.50` — "long lower wick". *Reasoning:* the wick must be at least half the bar's
  range for "long" to mean anything; 0.50 is the midpoint and is the loosest band that is still a
  real rejection.
- **S2** `upperFrac ≤ 0.25` — "little upper wick". *Reasoning:* the mirror of S1; leaving 0.25 to the
  body keeps a genuine hammer distinct from a Doji-with-two-wicks.
- **S3** `bodyFrac ≤ 0.35` — "small body". *Reasoning:* implied in the worst case by S1+S2
  (`bodyFrac ≤ 0.25`); relaxed to 0.35 to admit the wide-bodied hammer, since the book's shape does
  not care about total size.
- **S4** `lowerFrac ≥ 2.0 × bodyFrac` — "long" *relative to* the body, the standard reading of
  "long lower wick, small body". Redundant given S1–S3 but kept explicit so a bar with
  `lowerFrac = 0.55, bodyFrac = 0.30, upperFrac = 0.15` still qualifies on its dominant character.
- **S5** body colour is **irrelevant**. The book is explicit that shape is identical and only
  location differs (Hammer vs Hanging Man) `[Ch.17-1 §Hanging Man]`.
- **S6 (scored geometry)** `wickRatio = lowerWick / max(body, 0.001 × low[n])` is the primary geometry
  measure; `bodyFrac` the secondary.

### 8.3 Validity / invalidation → penalties
| Id | Condition | Effect |
|----|-----------|--------|
| `I1` | `close[n+1] ≤ high[n]` | `NO_CONFIRMATION` — the book requires the next session to close above the hammer high `[Ch.17-4 §Candlesticks at Support and Resistance §Example 1]` |
| `I2` | the hammer occurs inside an uptrend (`priorTrendPct ≥ +CONTEXT_RISE_PCT`) | `WRONG_LOCATION` — that shape at that location is a **Hanging Man**, a potential warning, not a hammer `[Ch.17-1 §Hanging Man]`. Suppress, do not merely penalise (**DD-3**). |
| `I3` | bars `[n+1,n+5]` make a lower low than `low[n]` | `STRUCTURE_CONTRADICTS` `[Ch.17-4 §Layer 4]` |
| `I4` | `rvol[n] < 1.5` | `WEAK_VOLUME` `[Ch.17-4 §Layer 3]` |

### 8.4 Measured move
None (`null`). `[Ch.17-1]` defines none. DD-1.

### 8.5 Confirmation inputs `[Ch.17-4 §Practical Example]`, `[Ch.17-4 §Candlesticks at Support and Resistance]`
- **C1** `close[n+1] > high[n]` — the book's exact professional rule.
- **C2** `rvol[n] ≥ 2.0`; `≥ 3.0` strong.
- **C3** `rsi[n] < 30` — "RSI below 30" appears verbatim in the book's worked example `[Ch.17-4 §Practical Example]`.
- **C4** bullish RSI divergence over the last 20 bars `[Ch.22 §22.17]`.
- **C5** `obvSlope` (5 bars to `n`) `> 0`.
- **C6** `deliveryVsAvg(n) ≥ 1.00` — "High delivery volume" `[Ch.17-4 §Practical Example]`.
- **C7** selling climax: `volumeSma20` is in the top decile of its own trailing 120 bars while price
  made a 20-bar low `[Ch.21 §21.22 §Selling Climax]`. `[derived]` decile definition.

### 8.6 False-signal traps
`LOW_LIQUIDITY`, `VOLATILE_MARKET`, `HTF_CONFLICT`, `CTX_MISMATCH` as in §6.6, plus:
- **Trap** "Hammer after a long rally → may be meaningless" `[Ch.17-1 §The Importance of Context]` ⇒ `I2`.

### 8.7 Worked example
```
context (D1): close[n−20] = 950.00 ; close[n] = 918.00 ⇒ priorTrendPct = −3.368 %  ✔ ≤ −3.0 %
min low over bars [n−20, n−1] = 912.00
n : O=922.00 H=926.00 L=912.50 C=924.00
   range = 13.50 ; body = 2.00 ; upperWick = 2.00 ; lowerWick = 9.50
   bodyFrac = 2.00/13.50  = 0.1481  ✔ ≤ 0.35
   upperFrac= 2.00/13.50  = 0.1481  ✔ ≤ 0.25
   lowerFrac= 9.50/13.50  = 0.7037  ✔ ≥ 0.50
   S4 0.7037 ≥ 2 × 0.1481 = 0.2963       ✔
   P3 low[n] = 912.50 ≤ 912.00 × 1.02 = 930.24  ✔
   wickRatio = 9.50 / 2.00 = 4.75
C1 close[n+1] = 927.40 > high[n] = 926.00 ✔
C2 vol[n] = 2,950,000 / 1,180,000 = 2.50  ✔ confirm
C3 rsi[n] = 27.4 < 30 ✔
C6 delivery[n] = 61.2% ; deliveryAvg20 = 44.8% ⇒ deliveryVsAvg = 1.366 ✔
⇒ MATCH (geometry: wickRatio 4.75, bodyFrac 0.148, declinePct 3.368)
```
Near-miss fixture (must be rejected): identical except `L = 919.00` ⇒ `lowerFrac = 5.00/7.00 = 0.714`
still passes, but `P3` fails because `919.00 > 912.00 × 1.02`, so it is suppressed as "not at support".

---

## 9. Detector 4 — Shooting Star

**Category** `CANDLESTICK` · **Book** `[Ch.17-1 §Shooting Star]`

Mirror of §8:
- **P2** `priorTrendPct(n) ≥ +CONTEXT_RISE_PCT` — "Hammer after a long rally may be meaningless …
  Shooting Star at resistance after extended rally → stronger bearish implication" `[Ch.17-1 §Pattern vs Context]`.
- **P3** `high[n] ≥ max(high over context window) × (1 − LOCATION_BAND_PCT/100)`.
- **S1** `upperFrac ≥ 0.50`; **S2** `lowerFrac ≤ 0.25`; **S3** `bodyFrac ≤ 0.35`; **S4** `upperFrac ≥ 2.0 × bodyFrac`. All `[derived]`, same reasoning.
- **S6** `wickRatio = upperWick / max(body, 0.001 × high[n])`.
- **Penalties** `I1` `close[n+1] ≥ low[n]` ⇒ `NO_CONFIRMATION` — "Next day: Bearish confirmation"
  `[Ch.17-4 §Example 2]`; `I2` an identical shape inside a downtrend is an **Inverted Hammer**
  (potentially bullish) ⇒ suppress (**DD-3**, `[Ch.17-1 §Inverted Hammer]`); `I3`, `I4` as §8.3.
- **Confirmation** `rsi[n] > 70`, bearish RSI divergence, `rvol ≥ 2.0`, `obvSlope < 0`,
  `deliveryVsAvg ≥ 1.00`, buying climax `[Ch.21 §21.22 §Buying Climax]`.
- **Measured move** none.

### Worked example
```
close[n−20] = 1,180.00 ; close[n] = 1,222.00 ⇒ priorTrendPct = +3.559 %  ✔ ≥ +3.0 %
max high over [n−20, n−1] = 1,224.00
n : O=1,215.00 H=1,231.00 L=1,213.00 C=1,217.00
   range = 18.00 ; body = 2.00 ; upperWick = 14.00 ; lowerWick = 2.00
   upperFrac = 14/18 = 0.7778 ✔ ; lowerFrac = 2/18 = 0.1111 ✔ ; bodyFrac = 2/18 = 0.1111 ✔
   S4 0.7778 ≥ 0.2222 ✔
   P3 1,231.00 ≥ 1,224.00 × 0.98 = 1,199.52 ✔
   wickRatio = 14.00 / 2.00 = 7.00
C1 close[n+1] = 1,209.50 < low[n] = 1,213.00 ✔
C3 rsi[n] = 73.8 > 70 ✔ ; rvol[n] = 3.1 ✔
⇒ MATCH
```

---

## 10. Detector 5 — Morning Star / Evening Star (one detector family)

**Category** `CANDLESTICK` · **Book** `[Ch.17-3 §Morning Star]`, `[Ch.17-3 §Evening Star]`,
`[Ch.17-3 §Star Patterns]`

One class, `StarDetector`, emitting both directions. `Match.name` = `"Morning Star"` or
`"Evening Star"`; `Category` = `CANDLESTICK`.

### 10.1 Preconditions
- **P1** min bars `[repo]`.
- **P2** Triple occupies bars `(n−2, n−1, n)`. Context window is bars `[n−20, n−3]`.
- **P3** Morning Star: `priorTrendPct(n−3) ≤ −CONTEXT_DECLINE_PCT` — "Most meaningful: after
  prolonged declines, near strong support, at demand zones, following panic selling" `[Ch.17-3 §Best Location]`.
  Evening Star: `priorTrendPct(n−3) ≥ +CONTEXT_RISE_PCT`.
- **P4** Location (morning): `low[n−2] ≤ min(low over context window) × (1 + LOCATION_BAND_PCT/100)`.

### 10.2 Shape (numeric)
Common, `[derived]` except where noted:
- **S1 "large" first candle** `bodyFrac[n−2] ≥ 0.50`. Book: "Large bearish candle" / "Large bullish
  candle". 0.50 = a body occupying at least half the bar; the book's own ASCII sketches show bodies
  filling most of the bar.
- **S2 "small" middle candle** `bodyFrac[n−1] ≤ 0.30`. Book: "Small body. Can be: small bullish,
  small bearish, doji." **The middle candle's colour is irrelevant** — a doji is explicitly allowed.
  A middle candle with `bodyFrac > 0.30` is not a star.
- **S3 "large" third candle** `bodyFrac[n] ≥ 0.50` and the opposite colour to bar `n−2`
  (morning: `isBull(n)` and `isBear(n−2)`; evening: `isBear(n)` and `isBull(n−2)`).
- **S4 penetration — Morning Star**: `pen = (close[n] − open[n−2]) / body[n−2]`, require `pen ≥ 0.50`.
  Book: "Closes **well into** the body of Candle 1." `[derived]` = 0.50, the classical definition of
  "into the body" and the point at which more than half the first candle's decline is recovered.
- **S4 penetration — Evening Star**: `pen = (open[n−2] − close[n]) / body[n−2]`, require `≥ 0.50`.
  Book: "Closes deeply into Candle 1."
- **S5 (bonus only)** `low[n−1] < low[n−2]` (morning) — the "star" is indented / gaps down. Not a
  precondition: `[Ch.17-2 §Gap Openings]` says Indian daily gaps are common but modern practice
  emphasises the shift in control, not the gap.
- **S6 (scored geometry)** `starRatio = body[n−1] / min(body[n−2], body[n])` (the "star-ness";
  lower is a better star) and `bodyRatio = body[n−2] / body[n]` (balance of the two large candles).

### 10.3 Validity / invalidation → penalties
| Id | Condition | Effect |
|----|-----------|--------|
| `I1` | `rvol[n] < 1.5` while `rvol[n−2] ≥ 2.0` | `WEAK_VOLUME` — "the third candle has very low volume. The reversal may lack broad participation" `[Ch.17-3 §Role of Volume]` |
| `I2` | `close[n+1] ≤ open[n−2]` (morning) / `≥ open[n−2]` (evening) | `NO_FOLLOW_THROUGH` |
| `I3` | within `TRAP_WINDOW` bars after the third candle a close back inside bar `n−2`'s body | `BULL_TRAP` / `BEAR_TRAP` `[Ch.18-5]` |
| `I4` | bars `[n+1, n+5]` continue the prior trend | `STRUCTURE_CONTRADICTS` `[Ch.17-3 §Combining with Market Structure]` |
| `I5` | the star completes after a `+40 %` run-up (Three White Soldiers caution: "Suppose a stock has already risen 40%. Then Three White Soldiers appear. Rather than signaling the start of a new trend, they may indicate buying exhaustion" `[Ch.17-3 §When to Be Careful]`) | `EXHAUSTION` `[derived]` gate: `close[n] ≥ 1.40 × close[n−60]` (D1) / `close[n−40]` (W1) |

### 10.4 Measured move
None (`null`). DD-1.

### 10.5 Confirmation inputs `[Ch.17-3 §Role of Volume]`, `[Ch.17-3 §Importance of Confirmation]`
- **C1 Volume** `rvol[n] ≥ 2.0` confirms — the book's own numbers: "If third candle volume is
  **twice** the average, buyers are demonstrating conviction" `[Ch.17-3]`.
- **C2 Structure / BOS** a close above the swing high that preceded the triple (morning) / below the
  preceding swing low (evening) `[Ch.17-3 §Importance of Confirmation]`, `[Ch.17-4 §Layer 4]`.
- **C3 RSI** `rsi[n] < 30` (morning) / `> 70` (evening), or a divergence over the last 20 bars
  `[Ch.17-3 §Practical Example]`, `[Ch.22 §22.17]`.
- **C4 OBV** slope agrees with the reversal direction over 5 bars ending at `n` `[Ch.22 §22.29]`.
- **C5 Delivery** `deliveryVsAvg(n) ≥ 1.00` `[Ch.21 §21.19]`.
- **C6 HTF** the higher timeframe has made a Higher Low (morning) / Lower High (evening)
  `[Ch.17-3 §Multi-Timeframe Perspective]`.

### 10.6 False-signal traps
The book's headline warning: **"A perfect three-candle pattern in the wrong location is often less
valuable than an imperfect pattern occurring at a major support or resistance level with strong volume
and favorable market structure"** `[Ch.17-3 §Why Three Candles Matter]`. Encoded as `CTX_MISMATCH`
at double weight. Plus `LOW_LIQUIDITY`, `VOLATILE_MARKET`, `HTF_CONFLICT` as in §6.6.

### 10.7 Worked example (Morning Star)
```
close[n−20] = 2,180.00 ; close[n−3] = 2,090.00 ⇒ priorTrendPct = −4.128 %  ✔ ≤ −3.0 %
min low over [n−20, n−3] = 2,085.00
n−2 : O=2,150.00 H=2,152.00 L=2,088.00 C=2,092.00   body=58.00  range=64.00  bodyFrac=0.9063  ✔ ≥0.50  (bearish ✔)
n−1 : O=2,090.50 H=2,096.00 L=2,086.00 C=2,094.00   body=3.50   range=10.00  bodyFrac=0.3500  ✘ >0.30
```
So that triple fails. A qualifying triple:
```
n−2 : O=2,150.00 H=2,152.00 L=2,088.00 C=2,092.00   body=58.00  range=64.00  bodyFrac=0.9063 ✔ bearish
n−1 : O=2,090.50 H=2,096.00 L=2,086.00 C=2,093.00   body=2.50   range=10.00  bodyFrac=0.2500 ✔ ≤0.30
n   : O=2,090.00 H=2,131.00 L=2,089.00 C=2,128.00   body=38.00  range=42.00  bodyFrac=0.9048 ✔ bullish
S4 pen = (2,128.00 − 2,150.00)/58.00 = −0.379   ✘  NEGATIVE — third candle did not close INTO the
      body of bar n−2, it only recovered to above the CLOSE.
```
The rule must be stated carefully: "closes well into the body of Candle 1" means the third candle's
**close is above the first candle's OPEN** by a margin, expressed as a fraction of the first body:

```
Morning Star penetration:  pen = (close[n] − open[n−2]) / body[n−2]   ≥ 0.50
Evening Star penetration:  pen = (open[n−2] − close[n]) / body[n−2]   ≥ 0.50
```
Corrected example:
```
n   : O=2,090.00 H=2,162.00 L=2,089.00 C=2,160.00   body=70.00  range=73.00  bodyFrac=0.9589 ✔ bullish
S4 pen = (2,160.00 − 2,150.00)/58.00 = 0.1724  ✘ still < 0.50
```
Need `close[n] ≥ 2,150.00 + 29.00 = 2,179.00`:
```
n   : O=2,090.00 H=2,185.00 L=2,089.00 C=2,180.00   body=90.00  range=96.00  bodyFrac=0.9375 ✔
S4 pen = (2,180.00 − 2,150.00)/58.00 = 30.00/58.00 = 0.5172  ✔ ≥ 0.50
starRatio = 2.50 / min(58.00, 90.00) = 2.50/58.00 = 0.0431
bodyRatio = 58.00/90.00 = 0.6444
C1 vol[n] = 5,100,000 / volumeSma20 = 1,700,000 ⇒ rvol = 3.00  ✔ ("twice the average" → strong)
C3 rsi[n] = 26.9 < 30 ✔
C5 delivery[n] = 68.4% vs avg 43.0% ⇒ deliveryVsAvg = 1.591  ✔
⇒ MATCH
```
**Evening Star** is the exact mirror with `pen = (open[n−2] − close[n]) / body[n−2] ≥ 0.50`,
`isBear(n)`, `isBull(n−2)`, `priorTrendPct(n−3) ≥ +3.0 %`, and `high[n] ≥ max(high over context) × 0.98`.

---
## 11. Reversal family — shared preamble (detectors 6–9)

### 11.1 Common structure

Every reversal pattern in v1 has exactly the same lifecycle, which the book states as a sequence
`[Ch.18-5 §The Life Cycle of Every Chart Pattern]`:

```
Trend → Pattern Begins → Pattern Develops → Breakout Attempt → Confirmation or Failure → New Trend
```

"The critical stage is **not** pattern formation. It is what happens **after** the breakout."
`[Ch.18-5 §The Life Cycle of Every Chart Pattern]`

Therefore, for detectors 6–9:

| State | Condition | `Match` state |
|-------|-----------|---------------|
| `FORMING` | shape rules satisfied, trigger level **not** closed through yet | `measuredMove` is `null`; verdict cannot be `PROJECT` |
| `BREAKOUT` | a **close** beyond the trigger level exists at bar `j` | `measuredMove` is populated with the breakout close |
| `FAILED` | `isBullTrap` / `isFailedRetest` true within `TRAP_WINDOW` + 5 bars | still returned, but with `BULL_TRAP` / `FAILED_RETEST` penalties; `breakoutTrigger.fired = true` but geometry is marked failed in `ScoreBreakdown` |

A match is emitted in `FORMING` state so the user sees the pattern before it triggers — the book's
"entering before the neckline breaks" is listed as a beginner mistake `[Ch.18-2 §Common Beginner
Mistakes]`, so a `FORMING` match is capped at the `RENDER` verdict (see **DD-5**).

### 11.2 Breakout buffer

```
BREAKOUT_BUFFER_PCT[D1] = 0.50   [derived]
BREAKOUT_BUFFER_PCT[W1] = 0.75   [derived]
```

Basis: `[Ch.18-5 §Confirmation Techniques §1 Strong Close]` — "Price closes **decisively** beyond the
breakout level. Not merely above it intraday." `[derived]` 0.50 % of price on daily is larger than a
typical daily noise band (~0.3 % ATR% for a mid-cap) and is exactly the book's own worked example
tolerance: resistance ₹800, "price trades at ₹810" is *not* a breakout, "price closes at ₹835" is a
decisive one `[Ch.18-5 §Breakout vs Intraday Spike]`. 1.25 % for the intraday ₹810 case ⇒ 0.50 % is
the conservative side of that example.

### 11.3 Shared reversal measures (fed to the scorer)

| Measure | Definition |
|---------|-----------|
| `extremityGap` | `(extreme − otherExtremity) / ref` — how far the head / higher peak is above the other extremity |
| `symmetry` | `sym(primaryExtremity, secondaryExtremity)` |
| `timeSymmetry` | `min(g₁,g₃)/max(g₁,g₃)` where `g₁ = gap(shoulder1→trough1)`, `g₃ = gap(trough2→shoulder2)` |
| `necklineFit` | `residMax` and `r2` of the neckline/confirmation line (§3.3) |
| `necklineTilt` | `slopeOverSpan` of the neckline across the whole formation, in % |
| `troughDepth` | `(maxExtremity − troughMin) / ref` — 1.0 by definition; reported for transparency |
| `spacingScore` | penalty-shaped measure from the min/max bar spacing (§3.1 `gap`) |
| `breakoutDepth` | `breakoutDepthPct` (§3.7) at the breakout bar |
| `volumeBreakout` | `rvolBreakout` |
| `divergenceGap` | RSI points from §3.8 |

---

## 12. Detector 6 — Head & Shoulders

**Category** `REVERSAL` · **Book** `[Ch.18-2]` (whole chapter), `[Ch.18-5 §Measuring Price Targets]`

### 12.1 Preconditions
- **P1** `candles.size ≥ minBars(timeframe)` `[repo]`.
- **P2** **Role assignment.** The zigzag list must contain, at consecutive `legIndex` values
  `k … k+4`, the kinds `HIGH, LOW, HIGH, LOW, HIGH`. Roles:

| role | `legIndex` | name |
|------|-----------|------|
| `LS` | `k` | Left Shoulder |
| `T1` | `k+1` | neckline point 1 (reaction low after the left shoulder) |
| `H`  | `k+2` | Head |
| `T2` | `k+3` | neckline point 2 (reaction low after the head) |
| `RS` | `k+4` | Right Shoulder |

The book's own enumeration: "Left Shoulder → Head → Right Shoulder → Neckline", with the neckline
"the support level connecting **the reaction lows**" `[Ch.18-2 §Anatomy of a Head & Shoulders Pattern]`,
and Stage 4: "it returns toward the previous support area. This forms the **second point of the
neckline**. Support has now been tested twice." `[Ch.18-2 §Stage 4]`.
- **P3** Prior trend. `priorTrendPct(LS.barIndex) ≥ +CONTEXT_RISE_PCT`. Book: "Head & Shoulders:
  Requires a clear **uptrend** before the pattern" `[Ch.18-2 §The Role of Trend]`; "A Head & Shoulders
  is a reversal pattern. It can only reverse something that already exists. If a stock has been moving
  sideways for months, a three-peak structure has little significance" `[same]`.
- **P4** All five pivots `isConfirmed` (§2.5).

### 12.2 Shape (numeric)
- **S1 Head is the extreme.** `H.price > LS.price` **and** `H.price > RS.price`. Book: Stage 3 "buyers
  push price to a new high. This creates the Head"; Stage 5 "they fail to reach the previous high.
  Instead, price forms a **Lower High**."
- **S2 Head dominance.** `(H.price − max(LS.price, RS.price)) / ref ≥ 0.10`. `ref = H.price −
  necklineAt(H.barIndex)`. `[derived]` 0.10 — without a floor, a "head" 0.05 % above a shoulder is
  an artefact of one bar's wick, not "the final major buying effort" `[Ch.18-2 §Why the Head Matters]`.
- **S3 Shoulder symmetry.** `sym(LS.price, RS.price) ≥ 0.90`. `[derived]` — the book says "Uneven
  shoulders" is a normal variation and "Perfection is unnecessary" `[Ch.18-2 §Common Variations]`, but
  also warns against calling every three-peak formation an H&S `[Ch.18-2 §Introduction]`. 0.90 allows a
  10 % shoulder difference — comfortably "uneven" — while rejecting a formation where the "left
  shoulder" is really the head's twin.
- **S4 Right shoulder is a lower high *of the pattern*, not a new high.** Implied by S1. Additionally
  the book requires a Lower High against the **prior** trend: `RS.price < the highest HIGH pivot
  strictly before LS`. `[derived]` implementer note — this is S1 with `LS` replaced by the pre-trend
  extreme; use whichever is stricter.
- **S5 Neckline.** `neckline = lineThrough(T1, T2)` (2 points ⇒ `r²` is 1.0 by construction and
  `isDegenerate = true`, so **never** report `r²` from a 2-point neckline — report `residMax = 0.0`
  and set the neckline-fit measure to a neutral `0.60`). If a **third** pivot within
  `tolLevel(pivot)` exists anywhere in the window, use all three for the fit and report its real `r²`.
  Book: two points create the line, the **third touch validates it** `[Ch.16-5 §Rule 2]`.
- **S6 Neckline tilt.** `|necklineTilt| ≤ 6.0 %`, where
  `necklineTilt = slopeOverSpan(T1, T2, barIndex(RS) − barIndex(LS))`.
  Book: "The neckline may be horizontal, slightly upward sloping, slightly downward sloping. **All
  three are valid.** The exact angle is less important than the market psychology" `[Ch.18-2 §The Neckline]`.
  `[derived]` 6.0 % — measured across the **whole formation**, so it is timeframe-independent; 6 % of
  price of tilt over a 3-week formation is visibly sloped but is not a different structure.
- **S7 Bar spacing.** For each of the four legs:
  `HS_MIN_LEG_BARS = 3` (`D1`) / `2` (`W1`) and `HS_MAX_LEG_BARS = 40` (`D1`) / `20` (`W1`). `[derived]`
  — 3 daily bars is the book's "reaction" rather than noise; 40 daily bars ≈ 2 months, past which the
  formation is not something a next-day investor acts on.
- **S8 Total duration.** `HS_MIN_SPAN_BARS = 15` (`D1`) / `8` (`W1`);
  `HS_MAX_SPAN_BARS = 120` (`D1`) / `60` (`W1`). `[derived]` — the book only says a pattern develops
  "over multiple trading sessions or even months" `[Ch.18-1 §What is a Chart Pattern?]` and that longer
  development is more significant `[Ch.18-1 §Duration Matters]`; 15 daily bars ≈ 3 weeks is the shortest
  span that contains four 3-bar legs with margin.
- **S9 Time symmetry.** `sym(gap(LS,T1), gap(T2,RS)) ≥ 0.50` **and** `sym(gap(T1,H), gap(H,T2)) ≥ 0.50`.
  `[derived]` — 0.50 rather than the book's "no numbers": a formation whose left swing takes 3 bars and
  whose right swing takes 25 is a different shape even if the prices line up.
- **S10 Volume shape (scored, not gated).** `volumeBreakout ≥ 2.0` confirms; volume at the Head
  `≤` volume at the Left Shoulder and volume at the Right Shoulder `≤` volume at the Head are the
  book's "healthy buying volume → volume may decline → volume often decreases further" sequence
  `[Ch.18-2 §Importance of Volume]`.

### 12.3 Validity / invalidation → penalties
| Id | Condition | Book |
|----|-----------|------|
| `I1` | **Unconfirmed.** No close below the neckline − buffer yet | state `FORMING`; `PROJECT` forbidden (DD-5) |
| `I2` | Breakout bar volume `rvol < 1.5` | "A neckline break on **weak volume** deserves caution" `[Ch.18-2]` → `WEAK_VOLUME` |
| `I3` | `isBullTrap` — close below neckline then close back above it within `TRAP_WINDOW` bars | `[Ch.18-5 §Bull Trap]` → `BULL_TRAP` |
| `I4` | Failed H&S: after the break, price makes a **new high** above `H.price` within 10 bars | "Sometimes: neckline breaks. Price quickly recovers. Buyers regain control. Stock moves to new highs. This is a **failed Head & Shoulders**" `[Ch.18-2 §Failed Head & Shoulders]` → `FAILED_PATTERN` |
| `I5` | Throwback that holds is **not** a penalty — it is confirmation. `isFailedRetest` (returns to the neckline and closes back through it **against** the direction) is a penalty | `[Ch.18-5 §Throwbacks and Pullbacks]`, `[Ch.18-2 §Throwback]` → `FAILED_RETEST` |
| `I6` | Structure contradicts: price prints a higher high above `H` while the pattern is still `FORMING` | "Entering before the neckline breaks" + "Expecting perfectly symmetrical shoulders" `[Ch.18-2 §Common Beginner Mistakes]` → `STRUCTURE_CONTRADICTS` |
| `I7` | Bearish RSI divergence **absent** at `LS → H` **and** `H → RS` | `[Ch.18-2 §Stage 3]` names lower RSI at the head as a hallmark → `NO_DIVERGENCE` |
| `I8` | Prior trend absent (§12.1 P3) | `[Ch.18-2 §The Role of Trend]` → `NO_PRIOR_TREND` (and no match) |

### 12.4 Measured move — `[Ch.18-2 §Measuring the Price Target]`, `[Ch.18-5 §Measuring Price Targets]`
The book's two steps, verbatim:
> "**Step 1** Measure the distance: Head ↓ Neckline … Head ₹1,200, Neckline ₹1,000, Difference ₹200.
> **Step 2** Subtract this distance from the breakout point. Breakout ₹1,000, Target ₹800."

```
height        = H.price − neckline.at(H.barIndex)
triggerLevel  = neckline.at(breakoutBar)
projectedLevel= breakoutClose − height
direction     = DOWN
```
When `breakoutClose == triggerLevel` this reduces to `2 × neckline − head`. Verified on the book's own
numbers: `1200 − 1000 = 200`; `1000 − 200 = 800` ✔.

**Rendered label:** `MatchScoreLabels.PROJECTION_LABEL` = `"Pattern geometry"` and
`MatchScoreLabels.NOT_A_TARGET_WARNING` = `"Projected from measured-move geometry. Not a price target."`
`[repo]`.

### 12.5 Confirmation inputs
| Signal | Confirms when | Book |
|--------|---------------|------|
| Close below neckline by `BREAKOUT_BUFFER_PCT` | required | "The pattern is confirmed only when price breaks below the neckline" `[Ch.18-2 §The Break of the Neckline]` |
| `rvolBreakout ≥ 2.0` | confirms | "Ideally, selling volume increases significantly" `[Ch.18-2]` |
| Bearish RSI divergence at `LS→H` or `H→RS`, `gapRsi ≥ 3.0` | confirms | `[Ch.22 §22.17]`, `[Ch.18-2 §Stage 3]` |
| `obvSlope` over 5 bars to breakout `< 0` | confirms | `[Ch.22 §22.29]` |
| `deliveryVsAvg(breakoutBar) ≥ 1.00` | confirms | `[Ch.21 §21.19]` |
| Throwback to neckline that fails to reclaim it | confirms | `[Ch.18-2 §Throwback]` |
| Follow-through: close lower on the next 1–2 bars | confirms | `[Ch.18-5 §Confirmation §3]` |
| Higher-timeframe (`W1`) already `TRENDING_DOWN` | confirms | `[Ch.18-5 §Multiple Timeframe Confirmation]`, `[Ch.19-4 §Market Regimes]` |

### 12.6 False-signal traps
| Trap | Detection | Penalty |
|------|-----------|---------|
| "Seeing a Head & Shoulders in every three-peak formation" `[Ch.18-2]` | P3 prior-trend gate + S2 head dominance | no match / `NO_PRIOR_TREND` |
| "Entering before the neckline breaks" `[Ch.18-2]` | state `FORMING` | `PROJECT` forbidden (DD-5) |
| "Expecting perfectly symmetrical shoulders" `[Ch.18-2]` | S3 0.90 floor | none — tolerance is deliberately loose |
| "Ignoring volume" `[Ch.18-2]` | `I2` | `WEAK_VOLUME` |
| "A neckline break on weak volume" `[Ch.18-2]` | `I2` | `WEAK_VOLUME` |
| Bull trap `[Ch.18-5]` | `I3` | `BULL_TRAP` |
| Failed H&S → new highs `[Ch.18-2]` | `I4` | `FAILED_PATTERN` |
| Range-bound market (Ch.20 Regimes 3: "breakouts fail frequently") | `adx < 20` | `RANGING_REGIME` |
| "Apophenia — inventing chart formations" `[Ch.18-5 §Seeing Patterns Everywhere]` | mitigated globally by the `50` suppress threshold | — |

### 12.7 Worked example (unit-test fixture)
Pivot prices on `D1` (bar indices shown; `ATR14` at bar 40 = ₹9.00):

| legIndex | role | bar | price | kind |
|---|---|---|---|---|
| 18 | `L0` | 40 | 950 | LOW |
| 19 | `LS` | 58 | 1,150 | HIGH |
| 20 | `T1` | 74 | 1,000 | LOW |
| 21 | `H`  | 92 | 1,200 | HIGH |
| 22 | `T2` | 108 | 1,020 | LOW |
| 23 | `RS` | 126 | 1,140 | HIGH |
| 24 | — | 136 | 995 | LOW (post-breakout) |

```
neckline: lineThrough(T1=(74,1000), T2=(108,1020))
          slopePerBar   = (1020−1000)/(108−74) = 20/34 = 0.58824 ₹/bar
          necklineAt(92) = 1000 + 0.58824×(92−74) = 1000 + 10.588 = 1,010.59
ref      = H.price − necklineAt(92) = 1200 − 1010.59 = 189.41

P3  priorTrendPct(LS=58) = (1150 − close[38])/close[38] × 100
    with close[38] = 1,010.00 ⇒ (1150−1010)/1010 × 100 = +13.861 %   ✔ ≥ +3.0 %

S1  H 1200 > LS 1150 ✔ ; H 1200 > RS 1140 ✔
S2  (1200 − max(1150,1140))/189.41 = 50/189.41 = 0.2640   ✔ ≥ 0.10
S3  sym(1150,1140) = 1140/1150 = 0.99130                ✔ ≥ 0.90
S4  highest HIGH before LS = 1,120 ; RS 1140 > 1120  ⇒ this sub-rule FAILS.
    → Per §12.2 S4 the STRICTER of S1 and this is used ⇒ the match is REJECTED.
    (Corrected fixture: set LS = 1,140 and RS = 1,140 so RS 1140 < 1,120 is still false —
     the right shoulder must be a Lower High against the *head*; the book only requires
     "they fail to reach the previous high" i.e. the HEAD. S4's pre-trend clause is therefore
     scored, not gated. See the resolution note below.)
S6  necklineTilt = slopeOverSpan(T1,T2, barIndex(RS)−barIndex(LS) = 126−58 = 68)
                 = 0.58824 × 68 / 1000 × 100 = +4.000 %    ✔ |4.00| ≤ 6.00
S7  gaps: 58→74 = 16 ; 74→92 = 18 ; 92→108 = 16 ; 108→126 = 18   ✔ all in [3, 40]
S8  span = 126 − 58 = 68                                  ✔ in [15, 120]
S9  sym(16,18) = 0.8889 ✔ ≥ 0.50 ; sym(18,16) = 0.8889 ✔ ≥ 0.50
S5  no third pivot near the neckline ⇒ neutral neckline-fit = 0.60

Breakout: bar 136 close = 995.00
  triggerLevel = neckline.at(136) = 1000 + 0.58824×62 = 1,036.47
  BREAKOUT_BUFFER_PCT[D1] = 0.50 %  ⇒  needed close < 1036.47 × 0.995 = 1031.29
  995.00 < 1031.29  ✔ BREAKOUT confirmed (bar 136, which is the LOW pivot itself —
  in a live run the breakout close is the close of the bar, 995.00)
  height = 189.41 ; projectedLevel = 995.00 − 189.41 = 805.59
  (Book's own numbers check: 1200−1000 = 200 ; 1000−200 = 800 ✔)
I2  rvol[136] = 4,900,000 / volumeSma20[136] = 1,550,000 = 3.16  ✔ ≥ 2.0  (no penalty)
I7  bearish divergence H→RS: RSI at 92 = 71.4 ; RSI at 126 = 63.8
    price 1140 < 1200 and RSI 63.8 < 71.4  ⇒ bearish divergence, gapRsi = 7.60 ≥ 3.0 ✔
⇒ geometry inputs: headDominance 0.2640, symShoulder 0.99130, necklineTilt 4.000,
   timeSymmetry 0.8889, spacing 16/18/16/18, breakDepth = (1036.47−995)/1036.47 = 4.00 %
```

**Resolution note on S4.** The book defines the right shoulder as a Lower High against the **head**
(Stage 5: "they fail to reach the previous high" — "the previous high" is the head). It does **not**
require the right shoulder to be below the pre-trend high. Implementers must therefore use:

- **gate:** `RS.price < H.price` (S1)
- **scored:** `RS.price < preTrendHigh` (reported as `rsBelowPriorHigh`, feeding geometry via
  `[derived]` `0.15` weight, not as a precondition).

This is logged as **DD-6** because the wording of the brief asked for a strict "right shoulder lower
high" rule and the book supports only the head-relative version.

---

## 13. Detector 7 — Inverse Head & Shoulders

**Category** `REVERSAL` · **Book** `[Ch.18-2 §Inverse Head & Shoulders]`

Exact mirror of §12. Roles are `LOW, HIGH, LOW, HIGH, LOW` at `legIndex k…k+4` = `LS, Q1, H, Q2, RS`
where `Q1, Q2` are the two **peak** pivots forming the neckline.

| Rule | Inverse value |
|------|---------------|
| P3 prior trend | `priorTrendPct(LS.barIndex) ≤ −CONTEXT_RISE_PCT` — "Inverse Head & Shoulders: Requires a clear **downtrend**" `[Ch.18-2]` |
| S1 | `H.price < LS.price` and `H.price < RS.price` — "Sellers attempt another decline. They **fail to make a new low**" `[Ch.18-2 §Right Shoulder]` |
| S2 head dominance | `(min(LS,RS) − H.price)/ref ≥ 0.10` where `ref = necklineAt(H) − H.price` |
| S3 | `sym(LS,RS) ≥ 0.90` |
| S5 neckline | line through `Q1, Q2` (both HIGH pivots) |
| S6 tilt | `|necklineTilt| ≤ 6.0 %` |
| S7–S9 | identical |
| Breakout | `close > neckline × (1 + 0.005)` |
| `I2` | breakout `rvol < 1.5` ⇒ `WEAK_VOLUME` ("A neckline break on weak volume deserves caution") |
| `I3` | `isBearTrap` — close above neckline then close back below within `TRAP_WINDOW` ⇒ `BEAR_TRAP` |
| `I4` | failed pattern: price makes a **new low** below `H.price` within 10 bars ⇒ `FAILED_PATTERN` |
| `I7` | absence of a **bullish** RSI divergence at `LS→H` or `H→RS` ⇒ `NO_DIVERGENCE` |

**Measured move** — the book gives it explicitly for the inverse:
> "Measure: Neckline ↓ Head. Suppose Head ₹500, Neckline ₹620, Difference ₹120. Breakout ₹620,
> Target ₹740." `[Ch.18-2 §Measuring the Upside Target]`

```
height        = neckline.at(H.barIndex) − H.price
triggerLevel  = neckline.at(breakoutBar)
projectedLevel= breakoutClose + height
direction     = UP
```
Check: `620 − 500 = 120`; `620 + 120 = 740` ✔.

### Worked example
| role | bar | price | kind |
|---|---|---|---|
| `LS` | 30 | 480 | LOW |
| `Q1` | 44 | 600 | HIGH |
| `H`  | 60 | 500 | LOW |
| `Q2` | 76 | 620 | HIGH |
| `RS` | 92 | 490 | LOW |
| — | 104 | 640 | HIGH (breakout bar) |

```
neckline slope = (620−600)/(76−44) = 20/32 = 0.62500 ₹/bar
necklineAt(60) = 600 + 0.625×16 = 610.00
ref = 610.00 − 500 = 110.00

P3  priorTrendPct(30) = (480 − 640)/640 × 100 = −25.000 %  ✔ ≤ −3.0 %
S1  H 500 < LS 480 ?  ✘  (500 > 480)
    ⇒ REJECTED as written. Corrected: LS = 505.
    LS = 505, RS = 490, H = 500:
    H 500 < LS 505 ✔ ; H 500 < RS 490 ?  ✘  ⇒ correct again: RS = 495.
    Final fixture: LS = 505 (LOW, bar 30), Q1 = 600 (HIGH, bar 44), H = 500 (LOW, bar 60),
                   Q2 = 620 (HIGH, bar 76), RS = 495 (LOW, bar 92)
S2  (min(505,495) − 500)/110 = (495−500)/110 = −0.0455  ✘
    H must be BELOW both shoulders. Final: H = 470.
    Recompute with H = 470: necklineAt(60) = 610.00 ; ref = 610 − 470 = 140.00
S1  H 470 < LS 505 ✔ ; H 470 < RS 495 ✔
S2  (min(505,495) − 470)/140 = 25/140 = 0.17857  ✔ ≥ 0.10
S3  sym(505,495) = 495/505 = 0.98020  ✔ ≥ 0.90
S6  necklineTilt = 0.625 × (92−30) / 600 × 100 = 0.625×62/600×100 = +6.4583 %  ✘ > 6.00
    ⇒ neckline too steep. Widen: set Q1 = 590 → slope = (620−590)/32 = 0.9375 ; worse.
    Flatten instead: set Q2 = 610 (bar 76) ⇒ slope = (610−600)/32 = 0.31250
    necklineAt(60) = 600 + 0.3125×16 = 605.00 ; ref = 605 − 470 = 135.00
    S2 = 25/135 = 0.18519 ✔
    S6 = 0.3125 × 62 / 600 × 100 = +3.2292 %   ✔ ≤ 6.00
S7  gaps 14,16,16,16  ✔ ; S8 span = 62  ✔ ; S9 sym(14,16)=0.8750 ✔ , sym(16,16)=1.0000 ✔

Breakout bar 104, close = 640.00
  triggerLevel = neckline.at(104) = 600 + 0.3125×(104−44) = 618.75
  needed close > 618.75 × 1.005 = 621.84 ;  640.00 ✔
  height = 135.00 ; projectedLevel = 640.00 + 135.00 = 775.00
Bullish divergence LS→H: price 470 < 505 ✔ and RSI at 60 = 22.4 < RSI at 30 = 27.9
    ⇒ NOT bullish (RSI made a lower low too) ⇒ NO_DIVERGENCE penalty applies.
    With RSI at 60 = 33.5 the rule fires, gapRsi = |33.5 − 27.9| = 5.60 ≥ 3.0 ✔
⇒ geometry inputs: headDominance 0.18519, symShoulder 0.98020, necklineTilt 3.2292,
   timeSymmetry 0.8750, breakDepth = (640 − 618.75)/618.75 = 3.43 %
```

---

## 14. Detector 8 — Double Top

**Category** `REVERSAL` · **Book** `[Ch.18-3 §Double Top]`, `[Ch.18-3 §False Double Tops]`,
`[Ch.18-3 §Volume Behavior]`

### 14.1 Preconditions
- **P1** min bars `[repo]`.
- **P2** **Role assignment.** Consecutive `legIndex k…k+2` with kinds `HIGH, LOW, HIGH` = `P1, V, P2`
  (first peak, the intervening trough = "the support formed between the two peaks", second peak).
  Book: "1. Existing uptrend. 2. First peak. 3. Pullback. 4. Second peak near the first. 5. Breakdown
  below support." `[Ch.18-3 §Structure]`
- **P3** Prior trend: `priorTrendPct(P1.barIndex) ≥ +CONTEXT_RISE_PCT` — "Stage 1 – Strong Uptrend …
  Higher Highs, Lower Lows" `[Ch.18-3]`; "Without a prior decline [a double bottom] is simply two
  similar lows" `[Ch.18-1]`.
- **P4** Both pivots `isConfirmed`.

### 14.2 Shape (numeric)
- **S1 Peak equality.** `sym(P1.price, P2.price) ≥ 0.97`. `[derived]` — the book says "second peak
  **near** the first" and its only numeric illustration is "Resistance ₹1,000. Price reaches ₹995 …
  returns to ₹1,005" `[Ch.18-3 §False Double Tops]` = ±0.5 %. 0.97 (±3 %) keeps that example inside
  while rejecting two peaks that differ by 5 %, which the book would call "a range", not a double top
  ("Confusing ranges with reversal patterns" `[Ch.18-3 §Common Beginner Mistakes]`).
- **S2 Meaningful trough.** `(max(P1,P2).price − V.price) ≥ 2.0 × ATR14(V.barIndex)`. `[derived]` —
  the book's whole psychology is "buyers were given **two opportunities** to overcome resistance"
  `[Ch.18-3 §Why This Matters]`; two peaks 0.3 ATR apart are one peak, not two opportunities.
- **S3 Peak separation.** `DT_MIN_SEP_BARS = 5` (`D1`) / `3` (`W1`);
  `DT_MAX_SEP_BARS = 80` (`D1`) / `40` (`W1`). `[derived]` — 5 daily bars ≈ one week, the shortest
  span in which two distinct "attempts" can be seen; 80 ≈ 4 months.
- **S4 Confirmation level.** `V.price` — the book names it: "The pattern is confirmed **only when price
  breaks below the support formed between the two peaks**. This support level is often called the
  **confirmation level**." `[Ch.18-3 §Confirmation]`
- **S5 Volume shape (scored).** `volume[P2 bar] ≤ volume[P1 bar]` — "Second peak: price reaches similar
  levels. Volume often declines. Buying enthusiasm weakens" `[Ch.18-3 §Volume Behavior]`.
- **S6 Trough as the local low.** Guaranteed by the zigzag role pattern; assert it anyway
  (`V.price < P1.price and V.price < P2.price`).
- **`ref = max(P1,P2).price − V.price`**

### 14.3 Validity / invalidation → penalties
| Id | Condition | Book |
|----|-----------|------|
| `I1` | no close below `V × (1 − 0.005)` yet | state `FORMING`; `PROJECT` forbidden (DD-5) |
| `I2` | breakdown bar `rvol < 1.5` | "Selling volume ideally increases sharply" `[Ch.18-3 §Volume Behavior]` → `WEAK_VOLUME` |
| `I3` | `isBearTrap` — close below `V` then close back above `V` within `TRAP_WINDOW` | `[Ch.18-5 §Bear Trap]` → `BEAR_TRAP` |
| `I4` | false double top: price closes above `max(P1,P2) × (1 + 0.005)` before any breakdown | "The apparent Double Top failed. Professionals wait for support breakdown. **Without confirmation, there is no completed pattern.**" `[Ch.18-3 §False Double Tops]` → `STRUCTURE_CONTRADICTS` |
| `I5` | no bearish RSI divergence between the two peaks | `[Ch.22 §22.17]` → `NO_DIVERGENCE` |
| `I6` | after the breakdown, price retraces to `V` and closes back **above** it | `FAILED_RETEST` — the old support has become new resistance and held `[Ch.18-5 §Pullback After Breakdown]` |

### 14.4 Measured move — `[Ch.18-3 §Measuring the Target]` verbatim
> "Measure: Peak ↓ Support. Peak ₹1,200, Support ₹1,050, Difference ₹150, Breakdown ₹1,050,
> Estimated target ₹900. Again, this represents a **minimum objective**, not a guarantee."

```
height        = max(P1.price, P2.price) − V.price
triggerLevel  = V.price
projectedLevel= breakdownClose − height
direction     = DOWN
```
Check: `1200 − 1050 = 150`; `1050 − 150 = 900` ✔.

### 14.5 Confirmation inputs
Close below the confirmation level by the buffer `[Ch.18-3 §Confirmation]`; `rvol ≥ 2.0` on the
breakdown bar `[Ch.18-3 §Volume Behavior]`; bearish RSI divergence `[Ch.22 §22.17]`; `obvSlope < 0`;
`deliveryVsAvg ≥ 1.00`; follow-through; a lower high after the breakdown (Lower Highs/Lower Lows
`[Ch.18-3 §Combining with Market Structure]`); `W1` regime `TRENDING_DOWN` `[Ch.18-5 §Multiple
Timeframe Confirmation]`.

### 14.6 False-signal traps
"Treating every two similar highs as a Double Top" `[Ch.18-3]` → P3 + S1 + S2.
"Ignoring the confirmation level" `[Ch.18-3]` → DD-5 (`FORMING` cannot project).
"Confusing ranges with reversal patterns" `[Ch.18-3]` → S2's 2-ATR floor.
Plus `RANGING_REGIME` when `adx < 20` (Ch.20 Regime 3: "breakouts fail frequently").

### 14.7 Worked example
| role | bar | price | kind |
|---|---|---|---|
| `P1` | 24 | 1,200 | HIGH |
| `V`  | 48 | 1,050 | LOW |
| `P2` | 66 | 1,192 | HIGH |
| — | 80 | 1,035 | LOW (breakdown bar) |

```
P3  priorTrendPct(24) = (1200 − close[4])/close[4] × 100 ; with close[4] = 1,000.00
    ⇒ +20.000 %  ✔ ≥ +3.0 %
S1  sym(1200, 1192) = 1192/1200 = 0.99333   ✔ ≥ 0.97
S2  max − V = 1200 − 1050 = 150 ; ATR14(48) = 18.00 ; 2×ATR = 36.00 ; 150 ≥ 36 ✔
S3  separation = 66 − 24 = 42  ✔ in [5, 80]
S6  1050 < 1200 ✔ ; 1050 < 1192 ✔
ref = 150.00
S5  volume[66] = 0.82 × volume[24]  ⇒ volume-shrink present ✔

Breakout bar 80, close = 1,035.00
  triggerLevel = 1,050.00 ; needed close < 1050 × 0.995 = 1,044.75 ; 1,035.00 ✔
  height = 150.00 ; projectedLevel = 1,035.00 − 150.00 = 885.00
  (Book check: 1200−1050 = 150 ; 1050−150 = 900 ✔)
I2  rvol[80] = 3,100,000 / 1,250,000 = 2.48  ✔ ≥ 2.0
I5  RSI at 24 = 68.2 ; RSI at 66 = 61.5 ; price 1192 < 1200 and RSI 61.5 < 68.2
    ⇒ bearish divergence ✔ gapRsi = 6.70 ≥ 3.0
⇒ MATCH. geometry: sym 0.99333, troughDepthAtr 150/36 = 4.167, sepBars 42,
   breakDepth = (1050−1035)/1050 = 1.43 %, volumeShrink 0.82
```
**Near-miss fixture (must be rejected):** `P2 = 1,140` ⇒ `sym = 1140/1200 = 0.9500 < 0.97` ⇒ no
match. That is the difference between a double top and two unrelated highs.

---

## 15. Detector 9 — Double Bottom

**Category** `REVERSAL` · **Book** `[Ch.18-3 §Double Bottom]`

Mirror of §14 with roles `LOW, HIGH, LOW` = `B1, R, B2`.

| Rule | Value |
|------|-------|
| P3 prior trend | `priorTrendPct(B1.barIndex) ≤ −CONTEXT_RISE_PCT` |
| S1 | `sym(B1.price, B2.price) ≥ 0.97` |
| S2 | `(R.price − min(B1,B2).price) ≥ 2.0 × ATR14(R.barIndex)` |
| S3 | separation in `[5, 80]` (D1) / `[3, 40]` (W1) |
| S4 | confirmation level = `R.price`; "Stage 4 – Buyers finally overcome resistance" `[Ch.18-3]` |
| S5 (scored) | `volume[B2 bar] ≤ volume[B1 bar]` — "Healthy Double Bottom: first low high selling volume, second low lower selling volume" `[Ch.18-3 §Volume Analysis]` |
| `ref = R.price − min(B1,B2).price` |
| `I3` | `isBullTrap` ⇒ `BULL_TRAP`; `I6` failed retest = reclaim of `R` then close back below |
| Breakout | `close > R × 1.005` |

**Measured move** — `[Ch.18-5 §Measuring Price Targets]` verbatim:
> "Double Bottom — Measure: Resistance ↓ Bottom. Project upward."

```
height        = R.price − min(B1.price, B2.price)
triggerLevel  = R.price
projectedLevel= breakoutClose + height
direction     = UP
```
(The book gives no worked numbers for the double bottom; the double-top numbers are the arithmetic
mirror and are the ones verified above.)

### Worked example
| role | bar | price | kind |
|---|---|---|---|
| `B1` | 20 | 2,100 | LOW |
| `R`  | 44 | 2,350 | HIGH |
| `B2` | 62 | 2,120 | LOW |
| — | 76 | 2,390 | HIGH (breakout bar) |

```
P3  priorTrendPct(20) = (2100 − 2900)/2900 × 100 = −27.586 %  ✔ ≤ −3.0 %
S1  sym(2100, 2120) = 2100/2120 = 0.99057   ✔ ≥ 0.97
S2  R − min = 2350 − 2100 = 250 ; ATR14(44) = 22.00 ; 2×ATR = 44 ; 250 ≥ 44 ✔
S3  separation = 62 − 20 = 42  ✔
ref = 250.00
Breakout bar 76, close = 2,390.00
  triggerLevel = 2,350.00 ; needed close > 2350 × 1.005 = 2,361.75 ; 2,390.00 ✔
  height = 250.00 ; projectedLevel = 2,390.00 + 250.00 = 2,640.00
I2  rvol[76] = 2,950,000 / 1,300,000 = 2.27  ✔ ≥ 2.0
⇒ MATCH
```
This also reproduces the book's own worked example prices `[Ch.18-3 §Practical Example]`
(₹3,000 → ₹2,100 → ₹2,350 → ₹2,120 → breaks above ₹2,350).

---

## 16. Detector 10 — Ascending Triangle

**Category** `CONTINUATION` · **Book** `[Ch.18-4 §Ascending Triangle]`, `[Ch.18-1 §Why Patterns Repeat]`

### 16.1 Preconditions
- **P1** min bars `[repo]`.
- **P2** **Role assignment.** A window of `2m + 1` consecutive pivots starting **and** ending at a
  `LOW`: `L₀, H₁, L₁, H₂, L₂, …, H_m, L_m`. Define `resistance = mean(H₁…H_m)`,
  `lowestLow = min(L₀…L_m)`. Requires `m ≥ 2` (two highs touching the flat resistance ⇒ at least one
  "third touch" plus the line itself, per `[Ch.16-5 §Rule 2]`). `[derived]` minimum `m = 2`.
- **P3** Prior trend: `priorTrendPct(L₀.barIndex) ≥ +CONTEXT_RISE_PCT`. Book: "Professional Checklist
  #1: Is there a strong trend before the pattern?" `[Ch.18-4 §Professional Checklist]`; "One of the most
  overlooked principles is: a chart pattern must be interpreted relative to the preceding trend"
  `[Ch.18-1]`.
- **P4** All pivots `isConfirmed`.

### 16.2 Shape (numeric)
- **S1 Flat resistance.** `resTolPct = max_i |H_i − resistance| / resistance × 100 ≤ FLAT_BAND_PCT`,
  `FLAT_BAND_PCT[D1] = 1.50`, `FLAT_BAND_PCT[W1] = 2.50`. `[derived]` — the book says only "Flat
  resistance" `[Ch.18-4 §Structure]`. Chosen to equal `DEPTH_PCT` per timeframe: the flat side must be
  *within noise*, which is exactly what "significant swing points" means in `[Ch.16-1]` and
  `[Ch.16-5 §Rule 1]`. Tighter would reject real formations; looser would swallow pennants.
- **S2 Rising lows.** `monoScore(L₀…L_m) = 1.0`, i.e. **strictly** `L₁ > L₀, L₂ > L₁, …`.
  Book: "Higher Lows", "Each correction ends higher. Demand increases." `[Ch.18-4 §Structure/§Psychology]`.
- **S3 Rising floor is a real line.** `progressPct(L₀ → L_m) ≥ RISING_FLOOR_PCT_PER_BAR`,
  `RISING_FLOOR_PCT_PER_BAR[D1] = 0.05`, `[W1] = 0.10`. `[derived]` — over a 25-bar daily triangle
  that is +1.25 %; enough to be "rising", far short of the steep boundary of a wedge.
- **S4 Floor is not a wedge.** `progressPct(L₀ → L_m) ≤ 1.5 × ATR14(L_m.barIndex) / L_m.price × 100`
  per bar. `[derived]` — `[Ch.18-4 §Wedges]` defines wedges as "both trendlines slope in the same
  direction", so a rising floor steeper than normal volatility is a wedge, out of scope.
- **S5 Duration.** `TRI_MIN_SPAN_BARS = 10` (`D1`) / `6` (`W1`);
  `TRI_MAX_SPAN_BARS = 120` (`D1`) / `60` (`W1`). `[derived]` — `[Ch.18-4 §Duration Matters]` compares
  a "five-day flag" with a "six-month Cup & Handle"; a triangle shorter than ~2 weeks has not
  developed enough touches to distinguish it from noise.
- **S6 Breakout.** `close > resistance × (1 + BREAKOUT_BUFFER_PCT/100)`. Book: "Eventually, buyers
  absorb all available supply. Resistance breaks." `[Ch.18-4 §Psychology]`
- **`ref = resistance − lowestLow`**

### 16.3 Validity / invalidation → penalties
| Id | Condition | Book |
|----|-----------|------|
| `I1` | no close above `resistance × (1.005)` yet | state `FORMING`; `PROJECT` forbidden (DD-5) |
| `I2` | breakout `rvol < 1.5` | "Volume typically declines during formation, expands during breakout" `[Ch.18-4 §Volume]` → `WEAK_VOLUME` |
| `I3` | `isBullTrap` — close above then back inside within `TRAP_WINDOW` | `[Ch.18-5 §Bull Trap]` → `BULL_TRAP` |
| `I4` | volume **expanded** during the formation (`volumeContraction > 1.10`) | "Unhealthy continuation: high volume during correction … may indicate institutional selling" `[Ch.18-4 §Role of Volume]` → `VOLUME_CONTRACTION_ABSENT` |
| `I5` | no breakout within 10 bars of the last touch | triangle is expiring → `STALE_PATTERN` |

### 16.4 Measured move
**The book gives no measured move for triangles.** `[derived]` — apply the book's universal
construction `[Ch.18-5 §Measuring Price Targets]` ("measure the formation, project from the
breakout") to the triangle's defining height:

```
height        = resistance − lowestLow
triggerLevel  = resistance
projectedLevel= breakoutClose + height
direction     = UP
```
Logged as **DD-4** (the only v1 measured move not taken verbatim from the book).

### 16.5 Confirmation inputs
`rvolBreakout ≥ 2.0` `[Ch.18-4 §Volume]`; volume contraction through the formation `[Ch.18-4 §Volume
During Continuation Patterns]`; follow-through `[Ch.18-5 §Confirmation §3]`; `obvSlope > 0`
`[Ch.22 §22.29]`; `deliveryVsAvg(breakoutBar) ≥ 1.00` `[Ch.21 §21.19]`; bullish RSI divergence
`[Ch.22 §22.17]`; `W1` regime `TRENDING_UP` `[Ch.18-5 §Multiple Timeframe Confirmation]`.

### 16.6 False-signal traps
"Treating every triangle as bullish" `[Ch.18-4 §Common Beginner Mistakes]` — countered by requiring
the **flat resistance + rising lows** pair specifically (a symmetrical triangle has neither).
"Seeing chart patterns everywhere" / apophenia `[Ch.18-5 §Seeing Patterns Everywhere]`.
Ranging market: an ascending triangle in a range has no prior trend ⇒ P3 rejects.

### 16.7 Worked example
| role | bar | price | kind |
|---|---|---|---|
| `L₀` | 30 | 900 | LOW |
| `H₁` | 42 | 1,000 | HIGH |
| `L₁` | 54 | 918 | LOW |
| `H₂` | 66 | 1,004 | HIGH |
| `L₂` | 78 | 938 | LOW |
| `H₃` | 90 | 998 | HIGH |
| `L₃` | 102 | 960 | LOW |
| — | 114 | 1,015 | HIGH (breakout bar) |

```
m = 3 ; resistance = (1000 + 1004 + 998)/3 = 3002/3 = 1000.6667
resTolPct = max(|1000−1000.667|, |1004−1000.667|, |998−1000.667|)/1000.667 × 100
          = max(0.667, 3.333, 2.667)/1000.667 × 100 = 3.333/1000.667 × 100 = 0.3331 %  ✔ ≤ 1.50 %
S2  918>900 ✔ ; 938>918 ✔ ; 960>938 ✔  ⇒ monoScore = 1.0  ✔
S3  progressPct(L₀=bar30, L₃=bar102) = (960 − 900)/72 / 900 × 100
      = 0.83333/900 × 100 = 0.09259 %/bar   ✔ ≥ 0.05
S4  ATR14(102) = 14.00 ; 1.5 × 14 / 960 × 100 = 21/960 × 100 = 2.1875 %/bar
      0.09259 ≤ 2.1875  ✔ not a wedge
S5  span = 102 − 30 = 72  ✔ in [10, 120]
P3  priorTrendPct(30) = (900 − close[10])/close[10] × 100 ; with close[10] = 780.00
      ⇒ +15.385 %  ✔ ≥ +3.0 %
ref = 1000.6667 − 900 = 100.6667

Breakout bar 114, close = 1,015.00
  triggerLevel = 1000.6667 ; needed close > 1000.6667 × 1.005 = 1005.67 ; 1,015.00 ✔
  height = 100.6667 ; projectedLevel = 1,015.00 + 100.6667 = 1,115.67
I2  rvol[114] = 3,400,000 / 1,200,000 = 2.833  ✔ ≥ 2.0
I4  mean volume over bars [30,102] = 780,000 ; mean over the 20 bars before bar 30 = 1,050,000
      volumeContraction = 780,000/1,050,000 = 0.743  ✔ ≤ 0.80  (ideal)
⇒ MATCH
```
**Near-miss (must be rejected):** make `H₂ = 1,030` ⇒ `resistance = (1000+1030+998)/3 = 1009.333`,
`resTolPct = max(9.333, 20.667, 11.333)/1009.333 = 2.047 % > 1.50 %` ⇒ **S1 fails**, no match. Three
peaks 2 % apart is a range, not an ascending triangle.

---

## 17. Detector 11 — Descending Triangle

**Category** `CONTINUATION` · **Book** `[Ch.18-4 §Descending Triangle]`

Exact mirror of §16. Window ends at a `HIGH`; `m` highs (rising) and `m` lows touching a flat support.

| Rule | Value |
|------|-------|
| Window | `H₀, L₁, H₁, L₂, …, L_m` with `m ≥ 2`; `support = mean(L₁…L_m)`, `highestHigh = max(H₀…H_m)` |
| P3 prior trend | `priorTrendPct(H₀.barIndex) ≤ −CONTEXT_RISE_PCT` |
| S1 flat support | `max_i |L_i − support| / support × 100 ≤ FLAT_BAND_PCT` |
| S2 falling highs | strictly `H₁ < H₀, H₂ < H₁, …` ⇒ `monoScore = 1.0` |
| S3 | `progressPct ≤ −RISING_FLOOR_PCT_PER_BAR` |
| S4 not a wedge | magnitude `≤ 1.5 × ATR` per bar |
| S5 duration | `[10, 120]` (D1) / `[6, 60]` (W1) |
| S6 breakout | `close < support × (1 − 0.005)` |
| `ref = highestHigh − support` |
| Penalties | `I2 WEAK_VOLUME`; `I3 BEAR_TRAP`; `I4 VOLUME_CONTRACTION_ABSENT`; `I5 STALE_PATTERN` |
| Measured move | `height = highestHigh − support` ; `projectedLevel = breakoutClose − height` ; `direction = DOWN` (DD-4) |
| Confirmation | bearish RSI divergence; `rvol ≥ 2.0`; `obvSlope < 0`; follow-through down; `W1` `TRENDING_DOWN` |

### Worked example
| role | bar | price | kind |
|---|---|---|---|
| `H₀` | 30 | 1,320 | HIGH |
| `L₁` | 42 | 1,200 | LOW |
| `H₁` | 54 | 1,304 | HIGH |
| `L₂` | 66 | 1,196 | LOW |
| `H₂` | 78 | 1,288 | HIGH |
| `L₃` | 90 | 1,204 | LOW |
| — | 102 | 1,186 | LOW (breakdown bar) |

```
m = 3 ; support = (1200 + 1196 + 1204)/3 = 3600/3 = 1200.00
resTolPct = max(0, 4, 4)/1200 × 100 = 0.3333 %  ✔ ≤ 1.50 %
S2  1304 < 1320 ✔ ; 1288 < 1304 ✔  ⇒ monoScore = 1.0  ✔
S3  progressPct(H₀=30 → H₂=78) = (1288 − 1320)/48 / 1320 × 100
      = −0.66667/1320 × 100 = −0.05051 %/bar   ✔ ≤ −0.05
S4  ATR14(78) = 15.00 ; 1.5 × 15 / 1288 × 100 = 22.5/1288 × 100 = 1.7469 %/bar ; |−0.05051| ≤ 1.7469 ✔
P3  priorTrendPct(30) = (1320 − 1,480)/1480 × 100 = −10.811 %  ✔ ≤ −3.0 %
highestHigh = 1320 ; ref = 1320 − 1200 = 120.00
Breakdown bar 102, close = 1,186.00
  triggerLevel = 1200.00 ; needed close < 1200 × 0.995 = 1,194.00 ; 1,186.00 ✔
  height = 120.00 ; projectedLevel = 1,186.00 − 120.00 = 1,066.00
I2  rvol[102] = 2,800,000 / 1,150,000 = 2.435  ✔ ≥ 2.0
⇒ MATCH
```

---

## 18. Detector 12 — Bull Flag / Bear Flag

**Category** `CONTINUATION` · **Book** `[Ch.18-4 §Flag Pattern]`, `[Ch.18-5 §Measuring Price Targets]`

One class, `FlagDetector`, emitting both directions; `Match.name` = `"Bull Flag"` / `"Bear Flag"`.

### 18.1 Preconditions
- **P1** min bars `[repo]`.
- **P2** **Role assignment.** Window `B, T, F₁, G₁, F₂, G₂, …, F_k, G_k` where `B` is the flagpole
  base (pivot **opposite** in kind to `T`), `T` is the pole top, and `F_i / G_i` are the flag's
  counter-trend pivots. For a **bull flag**: `B` is a `LOW`, `T` is a `HIGH`, the flag is the
  subsequent sequence of lower highs and higher-or-equal lows. Book: "1. Sharp price movement
  (**Flagpole**) 2. Small counter-trend channel (**Flag**) 3. Breakout" `[Ch.18-4 §Structure]`.
- **P3** Prior trend is satisfied **by construction** (the pole *is* the trend); no separate
  `priorTrendPct` test.
- **P4** All pivots `isConfirmed`.

### 18.2 Shape (numeric)
- **S1 Strong flagpole.** `poleHeight = |T.price − B.price|`, and **both**:
  `polePct = poleHeight / B.price × 100 ≥ POLE_MIN_PCT` (`D1` **10.0**, `W1` **8.0**) **and**
  `poleHeight ≥ POLE_MIN_ATR × ATR14(B.barIndex)` (`POLE_MIN_ATR[D1] = 4.0`, `[W1] = 3.0`).
  `[derived]` — book: "The Flagpole represents: Strong institutional buying. Momentum. Increasing
  volume. Sharp directional movement. **Without a strong Flagpole, there is no Flag.**"
  `[Ch.18-4 §Flagpole]`. Two conditions because 10 % alone is trivial for a low-priced high-volatility
  stock and ATR alone is trivial for a slow large cap. 4 ATR is twice the book's own stop distance
  `[Ch.22 §22.27]` — the pole must be at least two stops long.
- **S2 Shallow flag.** `depth = (max flag high − min flag low) / poleHeight ∈ [0.05, 0.50]`.
  `[derived]` — book: "The correction is relatively **small** compared to the Flagpole", and
  "Mistaking deep corrections for Flags" `[Ch.18-4 §Common Beginner Mistakes]`. 0.50 is the natural
  reading of "small compared to"; the 0.05 floor exists so a straight line (no consolidation at all)
  is not mislabelled a flag.
- **S3 Counter-trend direction.** For a bull flag: `slopePerBar(G₁ → G_k) ≤ 0` **and**
  `slopePerBar(F₁ → F_k) ≤ 0`. Book: "After the rally, price moves **slightly downward or
  sideways**" `[Ch.18-4 §The Flag]`. `[derived]` the `≤ 0` (not strictly `< 0`) form is what permits
  "sideways", which the book explicitly allows.
- **S4 Parallel, not converging.** `parallelDev = |sG − sF| / max(|sG|, |sF|, ε)` where
  `sG = slopePerBar(G₁,G_k)`, `sF = slopePerBar(F₁,F_k)`, `ε = 1e-9`. Require `≤ 0.50`.
  `[derived]` — book contrast table: Flag = "**Parallel** boundaries / small channel / slight slope";
  Pennant = "**Converging** boundaries / small triangle" `[Ch.18-4 §Difference Between Flag and
  Pennant]`. 0.50 is the half-parallel point; beyond it the pattern is a pennant, which is **out of
  scope for v1**, so it must be rejected rather than scored.
- **S5 Duration.** `FLAG_MIN_BARS = 3` (D1) / `2` (W1); `FLAG_MAX_BARS = 20` (D1) / `12` (W1).
  `[derived]` — "Flags and Pennants are **short-duration** consolidation patterns"
  `[Ch.18-4 §Key Takeaways]`; the book's own size example is a "Small Flag: may produce 5–10 % move"
  `[Ch.18-1 §Pattern Size and Price Target]`. 20 daily ≈ one trading month; 12 weekly ≈ one quarter.
- **S6 Volume contraction inside the flag.** `volumeContraction ≤ 1.00` scored; `≤ 0.80` strong.
  Book: "During consolidation … volume naturally contracts" `[Ch.18-4 §Volume During Continuation
  Patterns]`; "Healthy continuation pattern: declining volume during consolidation"
  `[Ch.18-4 §Role of Volume]`.
- **S7 Breakout.** Bull flag: `close > upperChannelAt(breakoutBar) × (1 + BREAKOUT_BUFFER_PCT/100)`.
  Bear flag: `close < lowerChannelAt(breakoutBar) × (1 − BREAKOUT_BUFFER_PCT/100)`.
  Book: "Small downward-sloping channel … Bullish breakout" `[Ch.18-4 §Bull Flag]`.
- **`ref = max(poleHeight, flagHeight)`** where `flagHeight = max flag high − min flag low`.

### 18.3 Validity / invalidation → penalties
| Id | Condition | Book |
|----|-----------|------|
| `I1` | no breakout yet | state `FORMING`; `PROJECT` forbidden (DD-5) |
| `I2` | breakout `rvol < 1.5` | `[Ch.18-4 §Role of Volume]` → `WEAK_VOLUME` |
| `I3` | **failed continuation**: bull flag closes **below** the lower channel line instead of above | `[Ch.18-4 §Failed Continuation Patterns]` — "Bull Flag → price breaks downward → heavy volume → trend reverses. Failed continuation patterns often produce strong moves in the opposite direction" → `FAILED_CONTINUATION` (heavier than `BULL_TRAP`) |
| `I4` | volume **expanded** through the flag (`volumeContraction > 1.10`) | "Unhealthy continuation: high volume during correction" → `VOLUME_CONTRACTION_ABSENT` |
| `I5` | `parallelDev > 0.50` | reject the candidate entirely — it is a pennant (S4) |
| `I6` | the pole itself is not `isConfirmed` | reject |

### 18.4 Measured move — `[Ch.18-5 §Measuring Price Targets]` and `[Ch.18-4 §Measuring the Target]` verbatim
> Ch.18-5: "**Flag** — Measure: Flagpole. Project from breakout."
> Ch.18-4: "Flagpole ₹100, Breakout ₹500, Estimated target ₹600."

```
height        = poleHeight
triggerLevel  = breakout boundary (upper channel for bull, lower for bear) at the breakout bar
projectedLevel= breakoutClose ± poleHeight          (+ bull flag, − bear flag)
direction     = UP | DOWN
```
Check on the book's numbers: `500 + 100 = 600` ✔.

### 18.5 Confirmation inputs
`rvolBreakout ≥ 2.0` `[Ch.18-4 §Volume]`; volume contraction inside the flag; follow-through
`[Ch.18-5 §Confirmation §3]`; `obvSlope` in the pole's direction; `deliveryVsAvg(breakoutBar) ≥ 1.00`
`[Ch.21 §21.19]`; RSI divergence in the trend direction; `W1` regime in the pole's direction
`[Ch.18-5 §Multiple Timeframe Confirmation]`.

### 18.6 False-signal traps
| Trap | Detection | Penalty |
|------|-----------|---------|
| "Mistaking deep corrections for Flags" `[Ch.18-4]` | S2 `depth ≤ 0.50` | reject if `> 0.50` |
| Flag vs Pennant confusion `[Ch.18-4]` | S4 `parallelDev ≤ 0.50` | reject if `> 0.50` |
| "Buying before the breakout" `[Ch.18-4]` | DD-5 | `PROJECT` forbidden while `FORMING` |
| "Failed continuation patterns" `[Ch.18-4]` | `I3` | `FAILED_CONTINUATION` |
| "Ignoring volume" `[Ch.18-4]` | `I2`, `I4` | `WEAK_VOLUME` / `VOLUME_CONTRACTION_ABSENT` |
| "Continuation vs reversal confusion — the previous trend provides the answer" `[Ch.18-4 §Continuation vs Reversal]` | the pole must be ≥ `POLE_MIN_PCT` in a single direction | reject if the "pole" is smaller than the flag |

### 18.7 Worked example (Bull Flag)
| role | bar | price | kind |
|---|---|---|---|
| `B`  | 20 | 500 | LOW |
| `T`  | 44 | 620 | HIGH |
| `F₁` | 52 | 600 | LOW |
| `G₁` | 60 | 610 | HIGH |
| `F₂` | 68 | 596 | LOW |
| `G₂` | 76 | 604 | HIGH |
| `F₃` | 84 | 592 | LOW |
| — | 92 | 632 | HIGH (breakout bar) |

```
poleHeight = 620 − 500 = 120 ; polePct = 120/500 × 100 = 24.00 %  ✔ ≥ 10.0 %
ATR14(20) = 14.00 ; POLE_MIN_ATR × ATR = 4 × 14 = 56 ; 120 ≥ 56  ✔
flag high max = 610 ; flag low min = 592 ; flagHeight = 18
depth = 18/120 = 0.150  ✔ in [0.05, 0.50]
sG = (604 − 610)/(76 − 60) = −6/16 = −0.3750 ₹/bar   ✔ ≤ 0
sF = (596 − 600)/(68 − 52) = −4/16 = −0.2500 ₹/bar   ✔ ≤ 0
parallelDev = |−0.3750 − (−0.2500)| / max(0.3750, 0.2500) = 0.1250/0.3750 = 0.3333  ✔ ≤ 0.50
FLAG bars = bar 84 − bar 44 = 40  ✘ > 20  ⇒ REJECTED as a flag
```
Corrected (flag lasts bars 48→72):
```
F₁ = (52, 602) LOW ; G₁ = (58, 612) HIGH ; F₂ = (64, 598) LOW ; G₂ = (70, 606) HIGH
flagHeight = 612 − 598 = 14 ; depth = 14/120 = 0.1167 ✔
sG = (606 − 612)/(70 − 58) = −6/12 = −0.5000 ✔
sF = (598 − 602)/(64 − 52) = −4/12 = −0.3333 ✔
parallelDev = 0.1667/0.5000 = 0.3333 ✔ ≤ 0.50
FLAG bars = 70 − 44 = 26  ✘ still > 20
```
Final (flag bars 46→66):
```
F₁ = (50, 602) LOW ; G₁ = (56, 610) HIGH ; F₂ = (62, 598) LOW ; G₂ = (66, 604) HIGH
flagHeight = 610 − 598 = 12 ; depth = 12/120 = 0.1000 ✔
sG = (604 − 610)/(66 − 56) = −0.6000 ✔ ; sF = (598 − 602)/(62 − 50) = −0.3333 ✔
parallelDev = 0.2667/0.6000 = 0.4444 ✔ ≤ 0.50
FLAG bars = 66 − 44 = 22  ✘
```
The book requires "small" flags; the fixture must be tightened to `flagBars ≤ 20`. Final accepted
fixture:
```
B = (20, 500) LOW ; T = (44, 620) HIGH
F₁ = (50, 604) LOW ; G₁ = (56, 610) HIGH ; F₂ = (62, 600) LOW ; G₂ = (64, 604) HIGH
flagHeight = 610 − 600 = 10 ; depth = 10/120 = 0.0833 ✔ in [0.05, 0.50]
sG = (604 − 610)/(64 − 56) = −6/8 = −0.7500 ✔
sF = (600 − 604)/(62 − 50) = −4/12 = −0.3333 ✔
parallelDev = |−0.75 − (−0.3333)| / 0.75 = 0.4167/0.75 = 0.5556  ✘ > 0.50 (a pennant)
```
Use `G₂ = (62, 606)`: `sG = (606 − 610)/6 = −0.6667` ; `parallelDev = 0.3333/0.6667 = 0.5000` ✔
(boundary, inclusive). Accepted fixture:

| role | bar | price | kind |
|---|---|---|---|
| `B`  | 20 | 500 | LOW |
| `T`  | 44 | 620 | HIGH |
| `F₁` | 50 | 604 | LOW |
| `G₁` | 54 | 610 | HIGH |
| `F₂` | 58 | 600 | LOW |
| `G₂` | 62 | 606 | HIGH |
| — | 70 | 632 | HIGH (breakout bar) |

```
poleHeight  = 120 ; polePct = 24.00 % ✔ ; 120 ≥ 4 × ATR14(20)=14 → 56 ✔
flagHeight  = 610 − 600 = 10 ; depth = 10/120 = 0.08333 ✔
sG = (606 − 610)/(62 − 54) = −0.5000 ✔ ≤ 0
sF = (600 − 604)/(58 − 50) = −0.5000 ✔ ≤ 0
parallelDev = 0.0000/0.5000 = 0.0000 ✔  (perfectly parallel)
FLAG bars = 62 − 44 = 18  ✔ in [3, 20]
ref = max(120, 10) = 120
upperChannel at bar 70 = 606 + (−0.50)×(70 − 62) = 606 − 4 = 602.00
needed close > 602.00 × 1.005 = 605.01 ; close = 632.00 ✔
height = 120.00 ; projectedLevel = 632.00 + 120.00 = 752.00
I2  rvol[70] = 3,600,000 / 1,400,000 = 2.571  ✔ ≥ 2.0
I4  mean vol over flag bars [50,62] = 690,000 ; mean over 20 bars before bar 44 = 1,320,000
      volumeContraction = 0.5227  ✔ ≤ 0.80  (ideal)
⇒ MATCH. Book check: flagpole 100, breakout 500 ⇒ 600; here 120 and 632 ⇒ 752 ✔
```

---

## 19. The shared penalty catalogue

Every failure mode named in Part 3 of the book is a **penalty id** here, never a 13th pattern.
`scoring-spec.md` §4 owns the arithmetic; this table owns the semantics and the citation.

| Penalty id | Fires when | Applies to | Book |
|-----------|-----------|-----------|------|
| `WEAK_VOLUME` | the signal bar's `rvol < 1.5`, **or** a breakout on volume that does not exceed the average | 1,2,3,4,5,6,7,8,9,10,11,12 | `[Ch.18-2]` "A neckline break on weak volume deserves caution"; `[Ch.17-3]` "the third candle has very low volume. The reversal may lack broad participation"; `[Ch.18-5]` "Breakouts without participation often fail" |
| `BULL_TRAP` | close beyond a resistance/upper boundary then a close back inside within `TRAP_WINDOW` | all bullish, all bearish mirrors | `[Ch.18-5 §Bull Trap]`, `[Ch.18-1 §Why False Breakouts Occur]`, `[Ch.21 §21.14 §Immediate Return Below Level]` |
| `BEAR_TRAP` | mirror of `BULL_TRAP` | all | `[Ch.18-5 §Bear Trap]` |
| `FAILED_RETEST` | price returns to the broken level within 10 bars and closes back through it **against** the breakout direction | 6,7,8,9,10,11,12 | `[Ch.18-5 §Throwbacks and Pullbacks]`, `[Ch.18-2 §Throwback]` |
| `FAILED_PATTERN` | H&S/inv-H&S re-takes its extreme after the break within 10 bars | 6,7 | `[Ch.18-2 §Failed Head & Shoulders]` |
| `FAILED_CONTINUATION` | a bull flag breaks down instead of up (or vice versa) | 12 | `[Ch.18-4 §Failed Continuation Patterns]` |
| `STRUCTURE_CONTRADICTS` | market structure disagrees — lower highs/lows persist after a bullish pattern, higher highs/lows after a bearish one, or a reversal pattern's trigger is exceeded before it fires | all | `[Ch.17-2 §Combining with Market Structure]`, `[Ch.17-4 §Layer 4]` "Never allow a candlestick to override market structure"; `[Ch.18-3 §Combining with Market Structure]`; `[Ch.16-5 §Trendline Break vs Trend Reversal]` |
| `CTX_MISMATCH` | location or trend layer fails: the pattern does not form at support/resistance, or not after the required prior move | 1–5 strongly, 6–9 | `[Ch.17-4 §Layer 1 Location]` "This is usually more important than the pattern itself"; `[Ch.17-3]` "A perfect three-candle pattern in the wrong location is often less valuable than an imperfect pattern at a major support" |
| `HTF_CONFLICT` | the other permitted timeframe (`D1` ↔ `W1`) still makes the opposite structure | all | `[Ch.17-3 §Multi-Timeframe Perspective]`, `[Ch.18-5 §Multiple Timeframe Confirmation]` "Higher timeframes generally carry greater weight" |
| `VOLATILE_MARKET` | `atrPct[j] > 2.0 × median(atrPct over trailing 120 bars)` | 1–5 | `[Ch.17-4 §Highly Volatile Markets]` "Candlestick patterns become less reliable. Large price swings can distort short-term signals" |
| `LOW_LIQUIDITY` | `volumeSma20 < 0.15 × sharesOutstanding` | all | `[Ch.17-4 §False Signals]` lists low liquidity; `[Ch.21 §21.20]` "In small caps volume can be manipulated" |
| `VOLUME_CONTRACTION_ABSENT` | `volumeContraction > 1.10` during a continuation formation | 10,11,12 | `[Ch.18-4 §Role of Volume]` "Unhealthy continuation: high volume during correction … may indicate institutional selling" |
| `NO_DIVERGENCE` | neither a divergence nor the book's own volume/RSI hallmark for that pattern | 6,7,8,9 | `[Ch.18-2 §Stage 3]`, `[Ch.18-3 §Volume Behavior]` |
| `NO_PRIOR_TREND` | the prior-trend gate is not met | 6,7,8,9 | `[Ch.18-1 §The Importance of Trend Before the Pattern]`, `[Ch.18-2 §The Role of Trend]` |
| `RANGING_REGIME` | `adx < 20` at the trigger bar | 6–12 | `[Ch.20 Regimes §Regime 3]` "Breakouts fail frequently"; `[Ch.19-4 §Understanding ADX Values]` "Below 20: weak / no trend" |
| `EXHAUSTION` | the trigger bar is ≥ 40 % above the bar 60 bars ago (D1) / 40 bars ago (W1) | 5, 10, 12 | `[Ch.17-3 §When to Be Careful]` "Suppose a stock has already risen 40%. Then Three White Soldiers appear … they may indicate buying exhaustion" |
| `INTRADAY_SPIKE_ONLY` | the trigger was crossed by `high`/`low` but not by `close` | 6–12 | `[Ch.18-5 §Breakout vs Intraday Spike]` |
| `STALE_PATTERN` | no breakout within 10 bars of the last pivot of the formation | 10,11,12 | `[Ch.18-1 §Why Breakouts Occur]` (compression until pressure resolves); `[Ch.18-5]` "Every chart pattern represents a probability" |
| `NO_CONFIRMATION` | the book's own next-bar confirmation did not happen | 3,4 | `[Ch.17-4 §Candlesticks at Support and Resistance]` ("Next session closes above the Hammer high") |
| `NO_FOLLOW_THROUGH` | the bar after the signal bar did not continue in the signal direction | 1,2,3,4,5 | `[Ch.17-2 §Confirmation]`, `[Ch.18-5 §Confirmation §3 Follow-Through]` |
| `WRONG_LOCATION` | the shape is right but its location inverts its meaning (hammer in a rally = hanging man; shooting star in a decline = inverted hammer) | 3,4 | `[Ch.17-1 §Hanging Man]`, `[Ch.17-1 §Inverted Hammer]` |

### 19.1 Detector → penalty applicability matrix

| Detector | `WEAK_VOL` | `BULL/BEAR_TRAP` | `FAILED_RETEST` | `FAILED_PATTERN` | `FAILED_CONT` | `STRUCTURE` | `CTX_MISMATCH` | `HTF` | `VOLATILE` | `LOW_LIQ` | `VOL_CONTRACT` | `NO_DIV` | `NO_PRIOR` | `RANGING` | `EXHAUST` | `INTRADAY` | `STALE` | `NO_CONFIRM` | `NO_FOLLOW` | `WRONG_LOC` |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 Bullish Engulfing | ✔ | ✔ | | | | ✔ | ✔ | ✔ | ✔ | ✔ | | | | | | | ✔ | | |
| 2 Bearish Engulfing | ✔ | ✔ | | | | ✔ | ✔ | ✔ | ✔ | ✔ | | | | | | | | ✔ | | |
| 3 Hammer | ✔ | | | | | ✔ | ✔ | ✔ | ✔ | ✔ | | | | | | | | ✔ | ✔ |
| 4 Shooting Star | ✔ | | | | | ✔ | ✔ | ✔ | ✔ | ✔ | | | | | | | | ✔ | ✔ |
| 5 Star family | ✔ | ✔ | | | | ✔ | ✔✔ | ✔ | ✔ | ✔ | | | | | ✔ | | | | ✔ | |
| 6 Head & Shoulders | ✔ | ✔ | ✔ | ✔ | | ✔ | | ✔ | | ✔ | | ✔ | ✔ | ✔ | | ✔ | ✔ | | |
| 7 Inverse H&S | ✔ | ✔ | ✔ | ✔ | | ✔ | | ✔ | | ✔ | | ✔ | ✔ | ✔ | | ✔ | ✔ | | |
| 8 Double Top | ✔ | ✔ | ✔ | | | ✔ | | ✔ | | ✔ | | ✔ | ✔ | ✔ | | ✔ | ✔ | | |
| 9 Double Bottom | ✔ | ✔ | ✔ | | | ✔ | | ✔ | | ✔ | | ✔ | ✔ | ✔ | | ✔ | ✔ | | |
| 10 Ascending Triangle | ✔ | ✔ | ✔ | | | ✔ | | ✔ | | ✔ | ✔ | | | ✔ | | ✔ | ✔ | | |
| 11 Descending Triangle | ✔ | ✔ | ✔ | | | ✔ | | ✔ | | ✔ | ✔ | | | ✔ | | ✔ | ✔ | | |
| 12 Flag family | ✔ | ✔ | ✔ | | ✔ | ✔ | | ✔ | | ✔ | ✔ | | | ✔ | ✔ | ✔ | | | |

`✔✔` = double weight (the book's own "wrong location is worse" statement for triple candles).

**Not representable as penalties** (and therefore explicitly *not* implemented):
news shocks, earnings surprises, macroeconomic events, sector weakness, index crashes
`[Ch.17-4 §False Signals]`, `[Ch.17-3 §Pattern Failure]`. Only the *market-wide* component is
approximated (§19.2); the rest is **DD-8**.

### 19.2 Market-context approximation

The book lists "Broad market weakness" and "Sector weakness" as causes of false signals. We hold only
per-symbol candles plus an optional benchmark index series `[repo] IndicatorSnapshot.of(benchmarkCandles)`.

```
marketWeak = mean(close of benchmark over the 5 bars ending at the trigger bar)
             is more than 2.0 % below the mean over the 20 bars before it   [derived]
```
Benchmark 2.0 % over 5 sessions `[derived]` — it is the point at which a "broad market weakness"
explanation is materially better than a stock-specific one, and it is symmetric with the
`BREAKOUT_BUFFER_PCT` family. A broad-market-down trigger bar adds `CTX_MISMATCH` at half weight.

---

## 20. Detector summary table (the 12)

| # | `name` | `category` | `minBars` `[repo]` | Pivot roles consumed | Trigger level | Measured move | Direction |
|---|--------|-----------|------|--------------------|---------------|---------------|-----------|
| 1 | `Bullish Engulfing` | `CANDLESTICK` | 60/40 | none (candles only) | n/a | **none** | UP |
| 2 | `Bearish Engulfing` | `CANDLESTICK` | 60/40 | none | n/a | **none** | DOWN |
| 3 | `Hammer` | `CANDLESTICK` | 60/40 | none | n/a | **none** | UP |
| 4 | `Shooting Star` | `CANDLESTICK` | 60/40 | none | n/a | **none** | DOWN |
| 5 | `Morning Star` / `Evening Star` | `CANDLESTICK` | 60/40 | none | n/a | **none** | UP / DOWN |
| 6 | `Head & Shoulders` | `REVERSAL` | 60/40 | `HIGH LOW HIGH LOW HIGH` | neckline | head − neckline | DOWN |
| 7 | `Inverse Head & Shoulders` | `REVERSAL` | 60/40 | `LOW HIGH LOW HIGH LOW` | neckline | neckline − head | UP |
| 8 | `Double Top` | `REVERSAL` | 60/40 | `HIGH LOW HIGH` | trough | peak − trough | DOWN |
| 9 | `Double Bottom` | `REVERSAL` | 60/40 | `LOW HIGH LOW` | peak | peak − trough | UP |
| 10 | `Ascending Triangle` | `CONTINUATION` | 60/40 | `LOW (HIGH LOW)^k` | flat resistance | resistance − low | UP |
| 11 | `Descending Triangle` | `CONTINUATION` | 60/40 | `HIGH (LOW HIGH)^k` | flat support | high − support | DOWN |
| 12 | `Bull Flag` / `Bear Flag` | `CONTINUATION` | 60/40 | `base, poleTop, flag pivots` | channel boundary | pole length | UP / DOWN |

`minBars` is always `MatchScorePolicy.minBars(timeframe)` = **60 (D1) / 40 (W1)**. The value in the
`PatternDetector.minBars` property is the *detector-specific* minimum window it needs on top of the
gate, which is smaller for every v1 detector and is documented per pattern above; it exists so a
future detector with a longer required formation does not silently reuse 60.

---

## 21. Appendix — every derived constant in one place

`[derived]` unless marked `[repo]`. Changing any value here changes `scoring-spec.md`'s normalisation
constants too, because they are written against these names.

### Zigzag (§2)
| Name | D1 | W1 | § |
|------|----|----|---|
| `DEPTH_ATR` | 3.0 | 2.5 | 2.2 |
| `DEPTH_PCT` | 1.5 | 2.5 | 2.2 |
| `MIN_PIVOTS` | 8 | 8 | 2.4 |
| fallback multiplier | 0.60 | 0.60 | 2.4 |
| `confirmLagBars` | 1 | 1 | 2.5 |

### Helpers (§3)
| Name | D1 | W1 | § |
|------|----|----|---|
| `TOUCH_TOL_MULT` | 1.5 | 1.5 | 3.4 |
| `TOUCH_TOL_ATR` | 0.50 | 0.50 | 3.4 |
| `LOOKBACK` (prior trend) | 20 | 13 | 3.6 |
| `priorTrend` threshold | ±3.0 % | ±5.0 % | 3.6 |
| `CONTEXT_DECLINE_PCT` | 3.0 | 5.0 | 5.2 |
| `CONTEXT_RISE_PCT` | 3.0 | 5.0 | 5.2 |
| `TRAP_WINDOW` | 3 | 2 | 3.7 |
| `BREAKOUT_BUFFER_PCT` | 0.50 | 0.75 | 11.2 |
| divergence `gapRsi` minimum | 3.0 | 3.0 | 3.8 |
| `rvol` confirm / strong | 2.0 / 3.0 | 2.0 / 3.0 | 3.8 |
| `volumeContraction` ok / ideal | ≤1.00 / ≤0.80 | same | 3.8 |
| `deliveryVsAvg` confirm | ≥1.00 | ≥1.00 | 3.8 |
| `marketWeak` | −2.0 % over 5 bars | −2.0 % | 19.2 |

### Candlesticks (§5)
| Name | D1 | W1 | § |
|------|----|----|---|
| `LOCATION_BAND_PCT` | 2.0 | 2.0 | 5.3 |
| `bodyRatio` minimum (engulfing) | 1.20 | 1.20 | 6.2, 7 |
| `bodyFrac` large / small (star) | ≥0.50 / ≤0.30 | same | 10.2 |
| star `pen` minimum | 0.50 | 0.50 | 10.2 |
| hammer/shooting-star `lowerFrac`/`upperFrac` | ≥0.50 | ≥0.50 | 8.2, 9 |
| hammer/shooting-star opposite wick | ≤0.25 | ≤0.25 | 8.2, 9 |
| `bodyFrac` (hammer family) | ≤0.35 | ≤0.35 | 8.2, 9 |
| wick-vs-body minimum | 2.0× | 2.0× | 8.2, 9 |
| `EXHAUSTION` lookback / threshold | 60 bars / 1.40× | 40 bars / 1.40× | 10.3 |

### Head & Shoulders (§12) / Inverse (§13)
| Name | Value | § |
|------|-------|---|
| `HS_HEAD_DOMINANCE` | 0.10 | 12.2 S2 |
| `HS_SHOULDER_SYM` | 0.90 | 12.2 S3 |
| `HS_NECKLINE_TILT_PCT` | 6.00 | 12.2 S6 |
| `HS_MIN_LEG_BARS` / `MAX` | 3 / 40 (D1), 2 / 20 (W1) | 12.2 S7 |
| `HS_MIN_SPAN_BARS` / `MAX` | 15 / 120 (D1), 8 / 60 (W1) | 12.2 S8 |
| `HS_TIME_SYM` | 0.50 | 12.2 S9 |
| neutral neckline-fit when only 2 points | 0.60 | 12.2 S5 |
| `rsBelowPriorHigh` score weight | 0.15 | 12.2 S4 note, DD-6 |

### Double top / bottom (§14, §15)
| Name | Value | § |
|------|-------|---|
| `DT_PEAK_SYM` | 0.97 | 14.2 S1 |
| `DT_MIN_TROUGH_ATR` | 2.0 × ATR | 14.2 S2 |
| `DT_MIN_SEP_BARS` / `MAX` | 5 / 80 (D1), 3 / 40 (W1) | 14.2 S3 |

### Triangles (§16, §17)
| Name | D1 | W1 | § |
|------|----|----|---|
| `FLAT_BAND_PCT` | 1.50 | 2.50 | 16.2 S1 |
| `RISING_FLOOR_PCT_PER_BAR` | 0.05 | 0.10 | 16.2 S3 |
| wedge cap | 1.5 × ATR/bar | 1.5 × ATR/bar | 16.2 S4 |
| `TRI_MIN_SPAN_BARS` / `MAX` | 10 / 120 | 6 / 60 | 16.2 S5 |
| minimum touches `m` | 2 | 2 | 16.1 P2 |

### Flag (§18)
| Name | D1 | W1 | § |
|------|----|----|---|
| `POLE_MIN_PCT` | 10.0 | 8.0 | 18.2 S1 |
| `POLE_MIN_ATR` | 4.0 | 3.0 | 18.2 S1 |
| flag `depth` range | [0.05, 0.50] | same | 18.2 S2 |
| `parallelDev` max | 0.50 | 0.50 | 18.2 S4 |
| `FLAG_MIN_BARS` / `MAX` | 3 / 20 | 2 / 12 | 18.2 S5 |

---

## 22. Design decisions — what the book does **not** define

Each of these is a place where the book is silent and a coder would otherwise have to guess.

| Id | Question | Decision taken | Why |
|----|----------|----------------|-----|
| **DD-1** | Do candlestick patterns get a measured move? | **No.** `Match.measuredMove = null` for detectors 1–5. | The book defines measured moves only for chart patterns `[Ch.18-5 §Measuring Price Targets]`. Fabricating one for a 3-bar candle would be inventing a projection the source does not make. Candlestick matches still get a `ProjectedPath` and a `BreakoutTrigger`, satisfying plan.md §6.6 items 2–3. |
| **DD-2** | Is "after a sustained decline" a gate or a penalty for candlesticks? | **Gate** for detectors 1,2,3,4,5 (P3 in each). Score-range penalties cannot rescue a pattern the book explicitly calls "much less useful". | `[Ch.17-2 §Poor Locations]`, `[Ch.17-1 §Important Rule]`. A hammer mid-range is not a weak hammer, it is not a hammer. |
| **DD-3** | Does a Hammer in an uptrend / Shooting Star in a downtrend score low, or not match? | **Not matched** (suppressed entirely), because the book says the *same shape* in that location has the **opposite** meaning (Hanging Man / Inverted Hammer). | `[Ch.17-1 §Hanging Man]`, `[Ch.17-1 §Inverted Hammer]`: "the shape is identical. Context changes the interpretation." Scoring it low would still show the user a "Hammer". |
| **DD-4** | What is the measured move for an ascending / descending triangle? | `height = flat side − opposite extreme`; `projected = breakoutClose ± height`. | The book has no triangle rule. This is the book's own construction ("measure the formation, project from the breakout", `[Ch.18-5]`) applied to a triangle's defining geometry. It is the only v1 measured move not taken verbatim. |
| **DD-5** | Can a `FORMING` pattern (trigger not yet closed through) reach the `PROJECT` verdict? | **No.** A `FORMING` match is capped at `RENDER` regardless of score. | "Entering before the neckline breaks" and "Anticipating the breakout" are listed beginner mistakes `[Ch.18-2]`, `[Ch.18-5 §Common Pattern Trading Mistakes §1]`. |
| **DD-6** | Must the right shoulder be a Lower High against the **head** or against the **pre-trend high**? | **Gate** against the head (`RS < H`); **scored** against the pre-trend high. | The book says "they fail to reach the previous high" where "the previous high" is the head `[Ch.18-2 §Stage 5]`. It never compares to the pre-trend high. Gating on the pre-trend high rejects formations the book explicitly accepts ("Uneven shoulders … Perfection is unnecessary"). |
| **DD-7** | What R² is reported for a two-point neckline? | **0.60 (neutral constant)**, never 1.0. | `linearRegression` marks `n = 2` degenerate and returns `rSquared = 1.0` with `isDegenerate = true` `[repo]`. Reporting 1.0 would let every two-point neckline claim a perfect fit. 0.60 is the mean of the 0.0 and 1.0 that a three-point neckline can produce. |
| **DD-8** | How are news shocks / earnings surprises penalised? | **They are not.** The detectors are deterministic price/volume functions (plan.md §8: "All are deterministic threshold checks. No AI, no inference"). | `[Ch.17-4 §False Signals]` names them but gives no observable signal we can compute from OHLCV. Suppressing rather than faking. |
| **DD-9** | Do multi-timeframe checks block a match or penalise it? | **Penalty** (`HTF_CONFLICT`), never a gate. | The app stores `D1` and `W1` independently and either may be missing (`NOT_ENOUGH_HISTORY` on W1 for a newly-listed stock). A hard gate would hide a valid daily pattern because the weekly series is short. |
| **DD-10** | Does the same physical formation get emitted by more than one detector? | **Yes, and that is correct.** E.g. a bullish flag is simultaneously an ascending triangle and a bull flag. `Match` carries `patternId` and the UI ranks by score; plan.md D4 = "detect all, user filters". | `[Ch.16-5 §Seeing Patterns Everywhere]` warns against forcing patterns, but suppression would be worse. The `score` and the `HTF_CONFLICT` penalty are the anti-apophenia controls. |
| **DD-11** | Which bars may a candlestick detector use as the "context window"? | `[n − LOOKBACK, n − 2]` for two-bar patterns, `[n − LOOKBACK, n − 3]` for the star. Never bars inside the pattern. | Prevents the pattern's own bars from satisfying its own context test — the classic self-confirming detector. |
| **DD-12** | Is a `Match` re-emitted every day while the formation persists? | **Yes**, with `detectedOn` = the latest bar. `OutcomeTracker` keys on `(symbol, timeframe, patternName, formationStartBar)` so re-emissions do not inflate the hit-rate sample (plan.md D6, §6.1). | Without this the `n ≥ 30` gate in `MatchScorePolicy.MIN_HIT_RATE_SAMPLES` would be reached with duplicate observations. |
| **DD-13** | Is the zigzag depth a `var` the user can change? | **No in v1.** Fixed per timeframe (§21). | A user-tunable zigzag makes the score non-reproducible and defeats the re-scoring-without-re-detecting design of `pattern_pivots` (plan.md §5.2). |
| **DD-14** | Should a pattern that is *too old* still be shown? | Yes, with `STALE_PATTERN`, until it is invalidated. | `[Ch.18-5]` — patterns persist; the user decides. The penalty prevents a stale formation from projecting. |

---

## 23. Contradictions and tensions with `plan.md` — flagged, not silently resolved

| # | Issue | Where | Recommendation |
|---|-------|-------|----------------|
| **C1** | plan.md §8 states we only get screener.in **summary cards** and therefore have "no receivables/inventory/goodwill lines, no D/E, no EV/EBITDA". **This is partly wrong, and the error is in the pessimistic direction.** The real fixture shows the balance sheet *is* parsed and carries `Equity Capital`, `Reserves`, `Borrowings`, `Other Liabilities`, `Total Liabilities`, `Fixed Assets`, `CWIP`, `Investments`, `Other Assets`, `Total Assets`, and there is a **`ratios` card with `Debtor Days`, `Inventory Days`, `Days Payable`, `Cash Conversion Cycle`, `Working Capital Days`, `ROCE %`**. Verified against `TITAN.html`, `RELIANCE.html`, `TCS.html`, `INFY.html`, `HDFCBANK.html`. | plan.md §8 vs. `redflags-spec.md` §2 and the live fixtures | **Correct the plan.** D/E, interest coverage, other-income share, inventory days and DSO *are* obtainable. Only **goodwill** and **promoter pledging** genuinely are not. See `redflags-spec.md` §2 for the exact field-to-flag mapping. |
| **C2** | plan.md §6.5's `Match` has `score: Int` and `measuredMove: MeasuredMove?` but no `ScoreBreakdown`, no `category`, no `timeframe`, no `detectedOn`. The repo's `Pattern.kt` has all of them. | plan.md §6.5 vs. `domain/model/Pattern.kt` | **The repo wins** — `CONTRACTS.md` says the shared model is authoritative and already written. This spec targets the repo version. |
| **C3** | plan.md §6.3 lists "Structure: clean pivots, trendline fit R², pivot spacing" but the repo's `TechnicalPolicy.REGRESSION_WINDOW = 30` produces a trendline over **closes**, not over pivots. | plan.md §6.3 vs. `domain/technical/Regression.kt` | Use **both**: `IndicatorSnapshot.trendlineFitRSquared` (closes, 30 bars) is already computed and is the "trendline fit R²"; the **pivot-line** R² (from §3.3) is the neckline/channel fit and belongs to **geometry**. See `scoring-spec.md` §3. |
| **C4** | plan.md §6.4 gates `PROJECT` on `regimeAligned`; `TechnicalAdx.isRegimeAligned` returns `false` for `TrendRegime.RANGING` — i.e. a perfectly good pattern in a ranging market can never project, even though the book says ranging markets are exactly where you *detect reversals near range boundaries* and use patterns to "avoid false breakouts" `[Ch.17-4 §Ranging Markets]`. | plan.md §6.4, `Trend.kt` vs. `[Ch.17-4]` | **Keep the gate as specified** (it is a locked product rule), but record that it is conservative for detectors 8, 9, 10, 11 — the book treats range-boundaries as *good* locations. Implementer mitigation: reversal patterns at a range boundary already earn their `RANGING_REGIME` penalty of 0 in the **structure** factor, so the two mechanisms do not double-count. **Ask the owner** whether `regimeAligned` should be direction-only for continuation patterns. |
| **C5** | plan.md §8.2 sets the volume-spike red flag at **2.5×** the 20-day average, while the book's own breakout-confirmation threshold is **2×**. Both are in `[repo] TechnicalPolicy` (`VOLUME_SPIKE_MULTIPLE = 2.5`). | plan.md §8.2 vs. `[Ch.21 §21.13]`, `[Ch.17-3]` | **No conflict in practice**: the red flag fires on *any* down day (a different, easier test) and the pattern confirmation needs a *breakout* bar. Keep `2.5` for the red flag `[repo]` and `2.0` for pattern confirmation. Documented so nobody "unifies" them later. |
| **C6** | plan.md §6.4 says min bars `D1 ≥ 60, W1 ≥ 40`, but `[Ch.13 §Pattern Hierarchy]` and `[Ch.18-4 §Duration Matters]` imply a meaningful chart pattern needs weeks-to-months. 60 daily bars ≈ 3 months, which is fine; **40 weekly bars ≈ 8 months**, which is generous. | plan.md §6.4 | Keep. Both are supersets of what the book requires and the gate is explicitly a *minimum*, not a target. |
| **C7** | The book's H&S measured move subtracts from the **breakout point**; the arithmetic in most charting platforms is `2×neckline − head`. These agree only when the breakout happens exactly at the neckline. | `[Ch.18-2 §Step 2]` vs. practice | **Follow the book literally** (`projected = breakoutClose − height`, §12.4) and show the breakout close in the evidence, so the two can be reconciled visually. Do not "correct" it to `2×neckline − head`. |
| **C8** | `[Ch.18-2 §The Neckline]` allows a "slightly upward sloping" neckline, but an upward-sloping neckline in an H&S makes the measured move *shallower* over time and is widely considered a lower-quality setup. | book vs. convention | No change — the tilt tolerance is scored (§12.2 S6), so a sloped neckline costs geometry without being rejected. That is the correct reading of "The exact angle is less important than the market psychology". |

---

## 24. Verification checklist for the implementer

Before a detector is considered done:

1. `minBars` gate respected; `Verdict.NOT_ENOUGH_HISTORY` produced, not a low score.
2. No two consecutive same-kind pivots in `ZigzagPivots` output (plan.md §12 property test).
3. `Match.pivots` non-empty and exactly the roles used.
4. `measuredMove.height ≥ 0` for every emitted match (plan.md §12 property test).
5. `match.score ∈ [0, 100]` always (plan.md §12 property test).
6. `measuredMove.projectedLevel` is on the correct side of `triggerLevel` for `direction`.
7. No `Match` with `score < 50` reaches `PatternScanResult.matches` (contract rule 4). **Enforced
   by `PatternEngine`, not by each detector.** A detector returns every candidate it scored; the
   engine routes a sub-threshold one into `PatternScanResult.suppressed` with a reason. The
   guarantee is unchanged — a sub-threshold match is never rendered — but a candidate is no longer
   *discarded* before anything can record it, so "detected but not drawn" is answerable.
   Measured over 20 real NSE large caps × 372 sessions: with the per-detector copy of this gate in
   place, three candlestick patterns (Shooting Star, Morning Star, Evening Star) appeared at no
   window depth at all, because their sub-threshold candidates were destroyed rather than recorded.
8. Every penalty the detector can fire is in the §19.1 matrix with the right applicability flag.
9. Each §"Worked example" fixture in this file is encoded as a unit test with the stated numbers.
10. `ScoreBreakdown.total` recomputed from `scoring-spec.md` matches, for all three fixtures in
    `scoring-spec.md` §8.
