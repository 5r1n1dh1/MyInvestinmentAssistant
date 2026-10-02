# Match Score Specification (0–100)

> **Status:** normative for `GeometryScorer` + `MatchScorePolicy` gating (plan.md §6.3, §6.4).
> Companion to `patterns-spec.md`, which defines every measure named here.
> **Non-negotiable:** the score measures **geometric fit to the textbook template**. It is never a
> probability of profit and never a target price (plan.md §6.1, §1.4).

---

## 1. The four factors

```
score = round( 0.40 · G  +  0.25 · S  +  0.20 · C  +  0.15 · X )   clamped to [0, 100]
        \_______/     \_______/     \_______/     \_______/
         GEOMETRY      STRUCTURE    CONFIRMATION     CONTEXT
         40 pts         25 pts        20 pts         15 pts
```
`G, S, C, X ∈ [0, 100]` are the four factor scores. The weights are
`MatchScorePolicy.GEOMETRY_WEIGHT / STRUCTURE_WEIGHT / CONFIRMATION_WEIGHT / CONTEXT_WEIGHT`
`[repo]` and **must not** be read from anywhere else.

`ScoreBreakdown.total` `[repo]` computes
`(geometry·40 + structure·25 + confirmation·20 + context·15) / 100`, truncated then clamped.
`scoring-spec.md` therefore specifies each factor as an integer 0–100 and the arithmetic follows.

### 1.1 Which measures go where

| Factor | Question it answers | Measures (see `patterns-spec.md` §) |
|--------|--------------------|--------------------------------------|
| **GEOMETRY (40)** | *Does the shape match the textbook template?* | `symmetry`, `headDominance` / `troughDepthAtr`, `engulf`, `bodyRatio`, `wickRatio`, `bodyFrac`, `starRatio`, `pen`, `parallelDev`, flag `depth`, `polePct`, `resTolPct`, `monoScore`, `necklineTilt` |
| **STRUCTURE (25)** | *Is it a clean, well-formed structure in clean data?* | pivot cleanliness (`residMax`, `residMean`), pivot-line `r²`, `spacingScore`, `trendlineFitRSquared` `[repo]`, duration fit, touch count, `EXTREMITY_RANK`, confluence |
| **CONFIRMATION (20)** | *Did the book say to look for it, and did we see it?* | breakout close quality (`breakoutDepth`), `rvolBreakout`, `deliveryVsAvg`, `obvSlope`, RSI divergence, follow-through, retest held, volume contraction |
| **CONTEXT (15)** | *Does the surrounding market support it?* | prior trend (`priorTrendPct`), ADX regime, relative strength vs index, HTF agreement, market breadth (`marketWeak`) |

**The split rule:** *a measure about the shape itself is GEOMETRY; a measure about the lines drawn
through it is STRUCTURE; a measure about what happened at or after the trigger is CONFIRMATION; a
measure about the world outside the formation is CONTEXT.* If a measure cannot be placed by that rule,
it does not enter the score — it enters the narrative text.

---

## 2. Normalisation primitives

Four primitives. Every raw measure is passed through exactly one of them. No prose, no judgement.

### 2.1 `LIN_LINEAR(v, lo, hi)`
Linear interpolation, clamped at both ends. Used when **both** ends of the range are meaningful and the
mid-point should score mid.

```
LIN_LINEAR(v, lo, hi) = 100 · (clamp(v, lo, hi) − lo) / (hi − lo)          hi > lo
```

### 2.2 `LIN_DECAY(v, perfect, floor)`
Linear **decay** from `perfect` at `v = perfect` to `floor` at `v = floor`. Used where `v` is a
*violation* (bigger is worse) or a ratio where 1.0 is perfect and 0.5 is unacceptable.

```
LIN_DECAY(v, perfect, floor) = 100 · (clamp(v, floor, perfect) − floor) / (perfect − floor)
```

### 2.3 `STEP(v, hiValue, hiScore, loValue, loScore)`
Two-point step. Used where the book states a binary ("third touch validates it", "close, not high").

```
STEP(v, hiValue, hiScore, loValue, loScore) =
    hiScore  if v ≥ hiValue
    loScore  otherwise
```

### 2.4 `RATIO_SATURATING(v, k)`
Saturating ratio for quantities that grow without bound but saturate at 1:
`RATIO_SATURATING(v, k) = 100 · min(1, v / k)`.
Used for volume multiples (`k = 3.0`) and penetration (`k = 1.0`).

### 2.5 Clamp and rounding
- Every factor is `clamp(x, 0, 100)` then `roundToInt()` (half-up) **once**, at the end of the
  factor's weighted sum. Never round intermediate terms.
- `score = clamp(round(0.40·G + 0.25·S + 0.20·C + 0.15·X), 0, 100)` after penalties (§4).

---

## 3. GEOMETRY — 40 points

Sum of the measures below; each is 0–100; the factor is their **weighted mean**, weights normalised to
sum to 1. Weights are shared by all 12 detectors except where a row is marked *pattern-specific*.

| # | Measure | Raw measure `v` | Normalisation | Weight | Applies to |
|---|---------|-----------------|---------------|--------|-----------|
| G1 | **Symmetry** | `sym(a,b)` of the pattern's symmetric pair (H&S shoulders; double-top peaks; triangle touch spread) | `LIN_LINEAR(v, 0.80, 1.00)` | **0.20** | 6, 7, 8, 9, 10\*, 11\* |
| G2 | **Extremity dominance** | H&S/inv-H&S: `(head − max shoulder)/ref`. Double top/bottom: `sym` already covers it → use `troughDepthAtr = (max peak − trough)/(2·ATR)` | `LIN_LINEAR(v, 0.10, 0.60)` | **0.15** | 6, 7 |
| | | Double top/bottom (replaces G2): `troughDepthAtr` | `LIN_LINEAR(v, 2.0, 6.0)` | **0.15** | 8, 9 |
| G3 | **Proportionality / body ratio** | detector-specific, see §3.1 | per §3.1 | **0.20** | 1, 2, 5, 6–12 |
| G4 | **Symmetry in time** | `sym(gap(shoulder1,trough1), gap(trough2,shoulder2))` | `LIN_LINEAR(v, 0.50, 1.00)` | **0.10** | 6, 7 |
| G5 | **Line tilt** | `\|necklineTilt\|` (%) | `LIN_DECAY(v, 0.0, 10.0)` | **0.10** | 6, 7 |
| G6 | **Boundary flatness / monotonicity** | `resTolPct` (triangles) or `monoScore` (all) | `LIN_DECAY(resTolPct, 0.0, 3.0)` ; `LIN_LINEAR(monoScore, 0.60, 1.00)` | **0.15** | 6, 7, 10, 11, 12 |
| G7 | **Channel conformity** | `parallelDev` | `LIN_DECAY(v, 0.0, 0.75)` | **0.20** | 12 |
| G8 | **Pole/formation proportion** | flag: `depth`; triangles: `ref / ATR` | flag `LIN_LINEAR(depth, 0.05, 0.45)`; triangles `LIN_LINEAR(ref/ATR, 3.0, 10.0)` | **0.25** | 12 / 10, 11 |

\* For triangles, G1 uses `sym` of the **first and last touch** on the flat side, normalised against the
tolerance band rather than against 1.0:
`triSym = 1 − resTolPct / FLAT_BAND_PCT`, then `LIN_LINEAR(triSym, 0.0, 1.0)`.

### 3.1 G3 — proportionality, per detector

| Detector | Raw measure `v` | Normalisation | Rationale |
|----------|-----------------|---------------|-----------|
| 1 Bullish Engulfing | `engulf = (close[n] − open[n−1]) / body[n−1]` | `RATIO_SATURATING(v, 1.0)` | Deeper engulfment ⇒ a truer "complete engulfment" `[Ch.17-2]` |
| 2 Bearish Engulfing | `engulf = (open[n−1] − close[n]) / body[n−1]` | `RATIO_SATURATING(v, 1.0)` | mirror |
| 3 Hammer | `wickRatio = lowerWick / max(body, 1e-3·low)` | `RATIO_SATURATING(v, 4.0)` | A 4:1 shadow-to-body is the book's "long lower wick, small body" fully realised |
| 4 Shooting Star | `wickRatio = upperWick / max(body, 1e-3·high)` | `RATIO_SATURATING(v, 4.0)` | mirror |
| 5 Star family | `starRatio = body[n−1] / min(body[n−2], body[n])` | `LIN_DECAY(v, 0.05, 0.50)` | Smaller middle body ⇒ a purer "hesitation" candle `[Ch.17-3 §Candle 2]` |
| 5 Star family | `pen` (penetration) | `LIN_LINEAR(v, 0.30, 0.90)` | "Closes well into the body of Candle 1" |
| 6 / 7 H&S | `headDominance` | `LIN_LINEAR(v, 0.10, 0.50)` | duplicate of G2 at half weight, to weight the head twice |
| 8 / 9 Double top/bottom | `peakSym = sym(P1,P2)` | `LIN_LINEAR(v, 0.94, 1.00)` | the near-equality of the two attempts |
| 10 Ascending Triangle | `risePct = (L_m − L_0)/L_0 × 100` | `LIN_LINEAR(v, 2.0, 15.0)` | "Each correction ends higher. Demand increases." `[Ch.18-4]` |
| 11 Descending Triangle | `fallPct = (H_0 − H_m)/H_0 × 100` | `LIN_LINEAR(v, 2.0, 15.0)` | mirror |
| 12 Flag | `polePct` | `RATIO_SATURATING(v, 0.25)` | a 25 % pole is a full-strength institutional move |

### 3.2 GEOMETRY worked micro-example (detector 6, the §12.7 fixture)

```
symShoulder   = 0.99130   → G1 = LIN_LINEAR(0.99130, 0.80, 1.00) = 95.65
headDominance = 0.26400   → G2 = LIN_LINEAR(0.26400, 0.10, 0.60) = 32.80
headDominance = 0.26400   → G3 = LIN_LINEAR(0.26400, 0.10, 0.50) = 41.00
timeSym       = 0.88889   → G4 = LIN_LINEAR(0.88889, 0.50, 1.00) = 77.78
|neckTilt|    = 4.000 %   → G5 = LIN_DECAY(4.000, 0.0, 10.0)   = 60.00
monoScore     = 1.00000   → G6 = LIN_LINEAR(1.00000, 0.60, 1.00) = 100.00

GEOMETRY = round( (95.65·0.20 + 32.80·0.15 + 41.00·0.20 + 77.78·0.10
                  + 60.00·0.10 + 100.00·0.15) / 0.90 )
        = round( (19.130 + 4.920 + 8.200 + 7.778 + 6.000 + 15.000) / 0.90 )
        = round( 61.028 / 0.90 ) = round(67.81) = 68
```

---

## 4. STRUCTURE — 25 points

| # | Measure | Raw measure `v` | Normalisation | Weight | Applies to |
|---|---------|-----------------|---------------|--------|-----------|
| S1 | **Pivot cleanliness** | `residMax` (§3.3 of patterns-spec) over the pivots that define the boundary | `LIN_DECAY(v, 0.0, 0.15)` | **0.25** | 6–12 |
| S2 | **Trendline-fit R² (closes)** | `IndicatorSnapshot.trendlineFitRSquared` `[repo]` | `LIN_LINEAR(v, 0.30, 0.95)` | **0.20** | all 12 |
| S3 | **Boundary-line R²** | `r2` of the 3-point neckline/channel fit, or **0.60** when only 2 points exist (DD-7) | `LIN_LINEAR(v, 0.40, 0.98)` | **0.15** | 6–12 |
| S4 | **Pivot spacing** | `spacingScore` (below) | `LIN_LINEAR(v, 0.0, 1.00)` | **0.15** | 6–12 |
| S5 | **Duration fit** | `durationScore` (below) | `LIN_LINEAR(v, 0.0, 1.00)` | **0.15** | 6–12 |
| S6 | **Touch count** | number of same-side touches on the flat boundary, `m` | `STEP(m, 3, 100, 2, 60)` | **0.10** | 10, 11 |

Weights sum to **1.00**.

### 4.1 `spacingScore`
```
spacingScore = Π over legs of  legScore(g),   legScore(g) = clamp(1 − |g − gIdeal| / gTol, 0, 1)
```
with `gIdeal` and `gTol` **per pattern**, from `patterns-spec.md`:

| Pattern | `gIdeal` (D1 / W1) | `gTol` (D1 / W1) |
|---------|------------------|------------------|
| H&S / inv-H&S — each of 4 legs | 15 / 8 | 12 / 7 |
| Double top / bottom — separation | 30 / 15 | 25 / 12 |
| Triangle — per leg | 12 / 6 | 10 / 5 |
| Flag — pole | 24 / 12 | 16 / 8 |
| Flag — per flag leg | 5 / 3 | 4 / 2 |

`[derived]` throughout. Reasoning: `gIdeal` is the mid-point of the pattern's legal spacing window in
`patterns-spec.md` §21 (e.g. H&S leg ∈ [3,40] → but the *ideal* is not the midpoint 21.5; it is 15,
because the minimum bar requirement plus a two-week swing is what a human eye reads as a "reaction"),
and `gTol` is the window's usable half-width.

### 4.2 `durationScore`
```
durationScore = LIN_LINEAR( span / spanIdeal, 0.30, 2.00 )
```
with `spanIdeal` per pattern = the `MAX` spacing constant's total (D1):

| Pattern | `spanIdeal` bars (D1 / W1) |
|---------|---------------------------|
| H&S / inv-H&S | 68 / 34 |
| Double top / bottom | 42 / 21 |
| Triangle | 65 / 32 |
| Flag (pole + flag) | 44 / 22 |

`[derived]`. "The longer a pattern develops, the more significant it often becomes … a rectangle
lasting five days is generally less important than a rectangle lasting six months"
`[Ch.18-1 §Duration Matters]` — this is that sentence, made linear and bounded, so that a 5-day
formation scores 0 and a very old one saturates at 1.0 rather than dominating.

### 4.3 Candlestick structure (detectors 1–5)

Candlestick patterns have no pivots and therefore no `residMax` / spacing / duration / touch measures.
For them **S1, S3, S4, S5, S6 are replaced**, keeping the weights identical:

| Slot | Candlestick measure | Normalisation |
|------|--------------------|---------------|
| S1 (0.25) | **Body significance** — `bodyFrac[signalBar]` | `LIN_LINEAR(v, 0.10, 0.60)` |
| S3 (0.15) | **Wick/body separation** — `max(upperFrac,lowerFrac) − bodyFrac` | `LIN_LINEAR(v, 0.10, 0.55)` |
| S4 (0.15) | **Context window cleanliness** — `1 − stdev(close over LOOKBACK)/mean(close over LOOKBACK)`, i.e. the coefficient of variation of the preceding `LOOKBACK` closes | `LIN_LINEAR(v, 0.02, 0.12)` |
| S5 (0.15) | **Signal-bar range vs ATR** — `range[signalBar] / ATR14` | `LIN_DECAY(v, 4.0, 0.6)` (a 4-ATR bar is the book's "massive battle, high uncertainty" `[Ch.17-1 §Example 3]` and should not score as *clean*) |
| S6 (0.10) | **Two/three-bar consistency** — `1` if the signal bar(s) sit at the extreme of the context window, else `0` | binary |

`[derived]` throughout. The **Context window cleanliness** row is the book directly: "Candlestick
patterns become less reliable" in noisy, high-CV conditions `[Ch.17-4 §Highly Volatile Markets]`, and
"Does the market close near the day's high?" `[Ch.18-5 §Understanding Breakouts]` is the
signal-bar-range row.

---

## 5. CONFIRMATION — 20 points

### 5.1 Chart patterns (6–12)

| # | Measure | Raw `v` | Normalisation | Weight |
|---|---------|---------|---------------|--------|
| C1 | **Breakout close quality** — `breakoutDepthPct` (§3.7 patterns-spec) | | `RATIO_SATURATING(v, 2.0)` | **0.25** |
| C2 | **Volume expansion** — `rvolBreakout` | | `RATIO_SATURATING(v, 3.0)` | **0.25** |
| C3 | **Follow-through** — close in the breakout direction on the next 1–2 bars | `0, 0.5, 1.0` | identity | **0.10** |
| C4 | **Retest held** — throwback/pullback to the broken level that did not close back through | `1` yes / `0` no / `0.5` not yet | identity | **0.10** |
| C5 | **OBV** — sign and magnitude of `obvSlope` over 5 bars to the breakout | `sign` | `1` agreeing, `0.5` flat, `0` disagreeing | **0.10** |
| C6 | **Delivery %** — `deliveryVsAvg(breakoutBar)` | | `RATIO_SATURATING(v, 1.50)`; **0 if `deliveryPercent` is `null`** | **0.10** |
| C7 | **Divergence** — `gapRsi` (§3.8 patterns-spec) | | `LIN_LINEAR(v, 0.0, 8.0)` | **0.10** |

Weights sum to **1.00**.

- **C1 at `FORMING`:** `v = 0`, and C3/C4 are `0` / `0.5`. A `FORMING` pattern is therefore capped at
  roughly `C ≤ 45`, i.e. ≤ 9 of 20 points, before penalties. Combined with DD-5 this means a `FORMING`
  match can still reach 70 if its geometry is exceptional — that is acceptable and intended; DD-5's cap
  is the hard gate.
- **C6 with no delivery data:** score **0** (not neutral), and the UI must say
  *"Delivery % unavailable — confirmation partially unscored"* `[repo] Candle.deliveryPercent` is
  nullable and plan.md §3.5 marks the legacy feed `UNVERIFIED`.
- **C7 when no divergence exists:** `v = 0`. The book treats divergence as *expected* at a head or a
  second peak `[Ch.18-2 §Stage 3]`, `[Ch.18-3 §Volume Behavior]`, so absence must cost points, and
  `patterns-spec.md` `NO_DIVERGENCE` also subtracts.

### 5.2 Candlestick patterns (1–5)

| # | Measure | Normalisation | Weight |
|---|---------|---------------|--------|
| K1 | **Volume on the signal bar** — `rvol[signalBar]` | `RATIO_SATURATING(v, 3.0)` | **0.35** |
| K2 | **Next-bar confirmation** — the book's own follow-through rule per pattern (§8.3 `I1`, §6.3 `C2`) | `1` yes / `0` no | **0.25** |
| K3 | **Structure confirmation** — the market-structure break in §6.5 `C3` within 5 bars | `1` / `0` | **0.15** |
| K4 | **RSI** — `rsi[signalBar]` against the appropriate band, or a divergence | overbought-side pattern: `LIN_LINEAR(v, 70, 88)`; oversold-side: `LIN_LINEAR(v, 42, 26)`; divergence: `1` / `0.5` / `0` | **0.15** |
| K5 | **Delivery %** — `deliveryVsAvg(signalBar)` | `RATIO_SATURATING(v, 1.50)`; **0 if null** | **0.10** |

Weights sum to **1.00**. `K1` is weighted highest because the book says it most often: "Candlestick
patterns represent changes in buying and selling pressure" and "Waiting for confirmation often improves
decision quality" `[Ch.17-2 §Key Takeaways]`, and `[Ch.11 §Layer 3]`'s whole worked example is volume.

---

## 6. CONTEXT — 15 points

| # | Measure | Raw `v` | Normalisation | Weight |
|---|---------|---------|---------------|--------|
| X1 | **Prior trend alignment** — `priorTrendPct` measured in the pattern's direction | | `LIN_LINEAR(v, 0.0, 12.0)` | **0.35** |
| X2 | **ADX regime** — `adx[triggerBar]` | | trending patterns: `LIN_LINEAR(v, 20, 40)`; reversal patterns: `LIN_LINEAR(40 − v, 0, 20)` | **0.25** |
| X3 | **DI alignment** — `+DI > −DI` (or the mirror) at the trigger bar | | `1` / `0` | **0.10** |
| X4 | **Relative strength** — `normalisedRsLine` 3-month and 6-month change `[repo]` | | `RATIO_SATURATING(v, 0.15)` in the pattern's direction | **0.15** |
| X5 | **Higher-timeframe agreement** — the other timeframe's regime | | `1` agreeing / `0.5` unknown / `0` contradicting | **0.10** |
| X6 | **Market breadth** — `marketWeak` (patterns-spec §19.2) | | `1` if not weak / `0` if weak | **0.05** |

Weights sum to **1.00**.

- **X2 asymmetry is the book's own asymmetry** `[Ch.19-4 §Market Regimes]`: Regime 1 "Strong Trending
  Market — breakouts work"; Regime 3 "Range-Bound Market — breakouts fail frequently". A **continuation**
  pattern therefore wants ADX up; a **reversal** pattern wants ADX *down* (a reversal is a failure of
  trend), which is also consistent with `[Ch.18-2]`'s H&S being a top in an uptrend and
  `[Ch.20 §Regime 3]`'s "detecting reversals near range boundaries" `[Ch.17-4 §Ranging Markets]`.
- **X4 when no benchmark is loaded:** `relativeStrength` is `null` `[repo]` ⇒ `X4 = 0.5` (neutral), and
  the UI must not claim relative strength it did not compute.
- **X1 for detectors 6–9 is the same `priorTrendPct` that gated the match.** That is deliberate double
  counting: a perfect prior trend earns structure *and* context, which is what
  `[Ch.18-1 §The Importance of Trend Before the Pattern]` argues for. It also means a reversal pattern
  can never max out CONTEXT once the prior trend has decayed — which is correct, because by then the
  pattern is failing (see `FAILED_PATTERN`).
- **Flags (12) get `X1 = 100` automatically** — the pole *is* the prior trend, so
  `X1 = 100` and the weight is redistributed across X2–X6 (renormalised to sum to 1). See §6.1.

### 6.1 Weight renormalisation

If a factor has a measure that does not apply to the pattern, that measure's weight is **redistributed
proportionally** across the remaining measures of that factor, which are then renormalised to 1. If
**every** measure of a factor is inapplicable, the factor is set to `50` (neutral) and the UI must not
display it as a real reading. As of v1 this never happens: detectors 1–5 have §4.3 slots and 6–12 have
§3 slots.

---

## 7. Penalty table

Penalties are ids from `patterns-spec.md` §19. Each has a fixed point value and a hard cap.

### 7.1 Values

| Penalty id | Points | Notes |
|-----------|--------|-------|
| `WEAK_VOLUME` | **−6** | |
| `BULL_TRAP` / `BEAR_TRAP` | **−12** | The book devotes a full section to it and warns the failure is often stronger than the original pattern `[Ch.18-5 §Why False Breakouts Occur]` |
| `FAILED_RETEST` | **−8** | |
| `FAILED_PATTERN` | **−18** | The book's explicit "failed H&S" case `[Ch.18-2 §Failed Head & Shoulders]` |
| `FAILED_CONTINUATION` | **−18** | `[Ch.18-4 §Failed Continuation Patterns]` |
| `STRUCTURE_CONTRADICTS` | **−14** | "Never allow a candlestick to override market structure" `[Ch.17-4 §Layer 4]` |
| `CTX_MISMATCH` | **−10** (×2 weight for detectors 1–5 = **−20**) | The book's headline "context is king" claim `[Ch.17-3]`, `[Ch.17-4 §Layer 1]` |
| `HTF_CONFLICT` | **−5** | |
| `VOLATILE_MARKET` | **−5** | |
| `LOW_LIQUIDITY` | **−8** | |
| `VOLUME_CONTRACTION_ABSENT` | **−6** | |
| `NO_DIVERGENCE` | **−6** | |
| `NO_PRIOR_TREND` | **−10** | (only reachable if the gate is relaxed) |
| `RANGING_REGIME` | **−8** | |
| `EXHAUSTION` | **−6** | |
| `INTRADAY_SPIKE_ONLY` | **−10** | |
| `STALE_PATTERN` | **−6** | |
| `NO_CONFIRMATION` | **−8** | |
| `NO_FOLLOW_THROUGH` | **−5** | |
| `WRONG_LOCATION` | **−12** | |

`[derived]` for every point value; the book supplies the *categories*, never the magnitudes. The
magnitudes are chosen so that (a) any single book-named failure mode can move a match from `PROJECT`
to `RENDER` but never by more than one tier, and (b) two independent failures are enough to suppress.

### 7.2 Aggregation and caps

```
rawPenalty = Σ (points_i × multiplicity_i)
multiplicity: a penalty fires at most ONCE per match, except:
   CTX_MISMATCH      — ×2 for detectors 1–5 (patterns-spec §19.1)
   BULL/BEAR_TRAP    — once per trigger event, max 2 events

totalPenalty = min(rawPenalty, 30)          // HARD CAP
score        = clamp(round(0.40·G + 0.25·S + 0.20·C + 0.15·X) − totalPenalty, 0, 100)
```

**Guarantees:**
- `score ≥ 0` always (the `clamp`). Satisfies plan.md §12's property test.
- **No match can ever be lifted back above `SUPPRESS_THRESHOLD` (50) by a later factor**: the factor sum
  is computed first, then penalties subtract, and the clamp only ever lowers.
- A `FORMING` match with `FAILED_PATTERN` scores `≤ 82 − 18 = 64` → at most `RENDER`. A `FORMING` match
  with `FAILED_PATTERN` + `WEAK_VOLUME` scores `≤ 58` → still `RENDER`. Adding `STRUCTURE_CONTRADICTS`
  gives `≤ 44` → **SUPPRESSED**. That is the intended behaviour.

---

## 8. Gate logic

`MatchScorePolicy.verdict(score, timeframe, barCount, regimeAligned)` `[repo]` is the single gate.

```
if (!isSufficientBars(timeframe, barCount))  → NOT_ENOUGH_HISTORY
if (score >= 70 && regimeAligned == true)     → PROJECT
if (score >= 50)                              → RENDER
else                                           → SUPPRESS
```

### 8.1 "Not enough history" behaviour

| Condition | Behaviour |
|-----------|-----------|
| `barCount < 60` (D1) or `< 40` (W1) | `Verdict.NOT_ENOUGH_HISTORY`. **No score is emitted at all.** The UI shows *"Not enough history — need 60 daily bars, have N."* plan.md §6.4: "the app must say *not enough history* rather than emit a low-confidence score." |
| A detector's own `minBars` unmet | The detector returns **no match**. Never a `Match` with a low score. |
| RSI / ADX / OBV warm-up incomplete | The corresponding CONFIRMATION or CONTEXT measure is `0` (or `0.5` for X4), **and** `ScoreBreakdown` must expose which measures were unavailable so the UI can say so. Never silently drop the factor to 0 without a label. |
| `deliveryPercent == null` for the whole series | C6/K5 = 0 with the label *"Delivery % unavailable"*. |
| `relativeStrength == null` | X4 = 0.5 with the label *"No index benchmark"*. |

### 8.2 Projected outputs

| Verdict | Measured move | Projected path | Breakout alert |
|---------|---------------|----------------|----------------|
| `PROJECT` | rendered, labelled `MatchScoreLabels.PROJECTION_LABEL` = **"Pattern geometry"** | rendered (dashed, distinct from price) | scheduled |
| `RENDER` | **not rendered** | not rendered | not scheduled |
| `SUPPRESS` | nothing rendered | nothing | nothing |
| `NOT_ENOUGH_HISTORY` | nothing; "not enough history" shown instead | nothing | nothing |
| `FORMING` state (DD-5) | never, regardless of verdict | never | the alert **is** still scheduled for the `BreakoutTrigger`, because the trigger is what the user is waiting for |

plan.md §6.6/§1.4 rule 2: measured-move projections must be labelled *pattern geometry*, never a
target price. The literal strings are in `MatchScoreLabels` `[repo]`.

---

## 9. Discrimination requirement (plan.md §11 P7 gate)

> "score discrimination test — the 0–100 score must produce a **spread**, not cluster near a constant.
> If it does not discriminate, the scorer is noise and P8 must not start." (plan.md §11)

### 9.1 The test

Run all 12 detectors over `domain/src/test/resources/fixtures/eod_history.json` (~40 large caps,
multi-month daily OHLCV) and over the W1 series derived from it.

| Statistic | Required |
|-----------|----------|
| **Distinct integer scores** | `≥ 45` of the 101 possible values must be occupied |
| **Standard deviation** | `σ ≥ 18.0` |
| **Inter-quartile range** | `IQR ≥ 20.0` |
| **Mode share** | the single most common score must be `≤ 6 %` of all matches |
| **Bucket occupancy** | each of the three bands `<50 / 50–69 / ≥70` must hold `≥ 10 %` of matches (if a band is empty, the scorer cannot be doing its job — `<50` is where suppression lives) |
| **Per-pattern spread** | for each of the 12 detectors with `≥ 30` matches: `σ ≥ 12.0` |
| **Determinism** | running the test twice must give byte-identical scores (plan.md §4.3 purity) |

### 9.2 Expected spread

| Band | Expected share of matches | Meaning |
|------|--------------------------|---------|
| `< 50` (suppressed) | **55 – 75 %** | The book is blunt: "Many beginners identify every three-peak formation as a Head & Shoulders" `[Ch.18-2]`. Most raw candidate formations must die. |
| `50 – 69` (render only) | **20 – 35 %** | |
| `≥ 70` (project) | **5 – 15 %** | A projection-worthy pattern should be uncommon. If more than 15 % of matches project, the scorer is not discriminating. |
| **Expected σ** | **19 – 27** | |
| **Expected median** | **42 – 55** | |

### 9.3 Anti-clustering mechanism

If σ < 18, apply these **in order**, one at a time, and re-measure:

1. **Check `G` first.** With only 2-point necks and 3-point triangles, `r²` is either 1.0 or
   degenerate; §3's `LIN_LINEAR(v, lo, hi)` ranges were chosen so that textbook values land in
   70–100 and hard values in 0–40. If `G` has σ < 15, the `lo`/`hi` bands are too wide — tighten them
   toward the midpoint of the band rather than widening the weights.
2. **Check `C`.** The common failure is `rvol` ≈ 1.0 for most stocks most of the time, which makes K1/C2
   cluster at `RATIO_SATURATING(1.0, 3.0) = 33`. If `σ(C) < 8`, replace the raw volume multiple with
   the **stock's own** percentile: `volumePercentile[j] = rank of volume[j] within the trailing 120
   bars`, then `C2 = RATIO_SATURATING(percentile / 0.95, 1.0)`. This is still deterministic and still
   matches `[Ch.21 §21.5]`'s principle ("breakout volume exceeds recent averages") while being
   cross-sectionally comparable. **This is the recommended first fix.**
3. **Check `S2`.** `trendlineFitRSquared` over 30 bars of closes is high (≈0.8–0.95) for nearly every
   liquid stock, so it contributes almost nothing to variance. If `σ(S) < 8`, replace `S2` with
   `1 − |slope| × bars / (ATR/price × 10)` — a *trendliness* measure that is low for a flat stock and
   high for a trending one. `[repo] TrendlineFit.slope` already exists.
4. **Never** fix clustering by widening the normalisation ranges. That trades discrimination for
   inoffensiveness.

### 9.4 The test is a gate, not a report

If steps 1–3 all fail to reach σ ≥ 18, **stop** and do not start P8. Raise it with the owner. A scorer
that cannot discriminate is worse than no scorer: it manufactures false confidence, which is the exact
failure mode plan.md §6.1 exists to prevent.

---

## 10. Worked score examples — three synthetic pivot sequences

These are the fixtures an implementer must reproduce exactly. All on `D1`, all with `barCount = 500`
so `isSufficientBars` passes.

Common context for all three (given to the scorer, not varied):
```
LOOKBACK prior-trend window = 20 bars
S1 context windows:
  EX-A  close[38] = 1,010.00  (used for +13.861 % prior trend)
  EX-B  close[38] =   995.00
  EX-C  close[38] = 1,010.00
S2 IndicatorSnapshot.trendlineFitRSquared (30-bar close regression):
  EX-A  0.910        EX-B  0.905        EX-C  0.880
adx[triggerBar]     =  31.0 / 30.0 / 34.0
di+[triggerBar] = 28.0 / 27.0 / 30.0 ; di− = 14.0 / 15.0 / 13.0
relativeStrength    : 3-month change  +0.06 / +0.05 / +0.04
                      6-month change  +0.14 / +0.12 / +0.10
W1 regime           = TRENDING_DOWN / TRENDING_DOWN / TRENDING_DOWN
marketWeak          = false / false / false
deliveryPercent     = present / present / present
```

---

### 10.1 EXAMPLE A — textbook-perfect H&S

Pivot sequence (this is `patterns-spec.md` §12.7):

| role | legIndex | bar | price | kind |
|------|----------|-----|-------|------|
| `L0` | 18 | 40 | 950 | LOW |
| `LS` | 19 | 58 | 1,150 | HIGH |
| `T1` | 20 | 74 | 1,000 | LOW |
| `H`  | 21 | 92 | 1,200 | HIGH |
| `T2` | 22 | 108 | 1,020 | LOW |
| `RS` | 23 | 126 | 1,140 | HIGH |
| — | 24 | 136 | 995 | LOW |

Breakout bar = **136**, close = **995.00**.
`neckline.slopePerBar = 0.588235`, `neckline.at(92) = 1010.588`, `ref = 189.412`.
`rvol[136] = 3.161`, `deliveryVsAvg[136] = 1.24`, `obvSlope(5 bars to 136) = −412,000`.

**GEOMETRY**
```
G1 symShoulder   = 0.991304 → LIN_LINEAR(0.991304, 0.80, 1.00) = 95.652   ×0.20
G2 headDominance = 0.263931 → LIN_LINEAR(0.263931, 0.10, 0.60) = 32.786   ×0.15
G3 headDominance = 0.263931 → LIN_LINEAR(0.263931, 0.10, 0.50) = 40.986   ×0.20
G4 timeSym       = 0.888889 → LIN_LINEAR(0.888889, 0.50, 1.00) = 77.778   ×0.10
G5 |neckTilt|    = 4.000 %  → LIN_DECAY(4.000, 0.0, 10.0)    = 60.000   ×0.10
G6 monoScore     = 1.000000 → LIN_LINEAR(1.000000, 0.60, 1.00) = 100.000 ×0.15
sum = 19.1304 + 4.9179 + 8.1972 + 7.7778 + 6.0000 + 15.0000 = 61.0233
G = round(61.0233 / 0.90) = round(67.803) = 68
```

**STRUCTURE** (weights 0.25/0.20/0.15/0.15/0.15/0.10, sum 1.00)
```
S1 residMax = 0.0 (both neckline points are ON the line; no third touch)
          → LIN_DECAY(0.0, 0.0, 0.15) = 100.000                       ×0.25
S2 trendlineFitRSquared = 0.910 → LIN_LINEAR(0.910, 0.30, 0.95) = 93.846 ×0.20
S3 boundary r² = 0.60 (DD-7, 2-point neckline) → LIN_LINEAR(0.60, 0.40, 0.98) = 34.483 ×0.15
S4 spacing: gIdeal 15, gTol 12 (D1). legs = 16, 18, 16, 18
          legScore(16) = 1 − |16−15|/12 = 0.91667
          legScore(18) = 1 − |18−15|/12 = 0.75000
          Π = 0.91667² × 0.75000² = 0.840278 × 0.562500 = 0.472656
          → LIN_LINEAR(0.472656, 0.0, 1.0) = 47.266                     ×0.15
S5 duration: span = 126 − 58 = 68 ; spanIdeal 68
          v = 68/68 = 1.000 → LIN_LINEAR(1.0, 0.30, 2.0) = 41.176      ×0.15
S6 touch count: N/A (not a triangle) → weight redistributed over S1–S5
          redistributed: S1 .2778, S2 .2222, S3 .1667, S4 .1667, S5 .1667
S = round(100.000·0.2778 + 93.846·0.2222 + 34.483·0.1667
           + 47.266·0.1667 + 41.176·0.1667)
   = round(27.780 + 20.855 + 5.748 + 7.878 + 6.865) = round(69.126) = 69
```

**CONFIRMATION** (weights 0.25/0.25/0.10/0.10/0.10/0.10/0.10)
```
C1 breakDepth = (1036.470 − 995.00)/1036.470 × 100 = 4.000 %
          → RATIO_SATURATING(4.000, 2.0) = 100.000                     ×0.25
C2 rvolBreakout = 3.161 → RATIO_SATURATING(3.161, 3.0) = 100.000       ×0.25
C3 followThrough: close[137] = 988.00 < 995.00 (DOWN) → 1              ×0.10
C4 retest held: close[140] = 1018.20, still below neckline(140)=1039.06
          → the retest held → 1                                          ×0.10
C5 obvSlope < 0, agrees with DOWN → 1                                   ×0.10
C6 deliveryVsAvg = 1.24 → RATIO_SATURATING(1.24, 1.50) = 82.667        ×0.10
C7 gapRsi = |63.8 − 71.4| = 7.60 → LIN_LINEAR(7.60, 0.0, 8.0) = 95.000 ×0.10
C = round(25 + 25 + 10 + 10 + 10 + 8.267 + 9.500) = round(97.767) = 98
```

**CONTEXT** (weights 0.35/0.25/0.10/0.15/0.10/0.05)
```
X1 priorTrendPct = +13.861 % → LIN_LINEAR(13.861, 0.0, 12.0) = 100.000  ×0.35
X2 reversal → LIN_LINEAR(40 − 31.0, 0, 20) = LIN_LINEAR(9.0,0,20) = 45.000 ×0.25
X3 di+ 28.0 > di− 14.0 and direction is DOWN → 0 (DI misaligned)         ×0.10
X4 relStrength: direction DOWN, so use −(3m) = −0.06
      → RATIO_SATURATING(0.06, 0.15) = 40.000                            ×0.15
X5 W1 TRENDING_DOWN agrees with DOWN → 1                                 ×0.10
X6 not marketWeak → 1                                                   ×0.05
X = round(35 + 11.250 + 0 + 6.000 + 10 + 5) = round(67.250) = 67
```

**PENALTIES**
```
WEAK_VOLUME    no  (rvol 3.161)
BULL_TRAP      no  (close 137 = 988 still below neckline)
FAILED_RETEST  no  (retest held)
FAILED_PATTERN no  (no new high above 1200 within 10 bars)
STRUCTURE_CONTRADICTS  no  (bars 137–141 make lower lows)
HTF_CONFLICT   no  (W1 is TRENDING_DOWN, agreeing)
VOLATILE_MARKET no
LOW_LIQUIDITY  no
NO_DIVERGENCE  no  (gapRsi 7.60)
NO_PRIOR_TREND no
RANGING_REGIME no  (adx 31.0 ≥ 20)
INTRADAY_SPIKE_ONLY no (closed below)
STALE_PATTERN  no
rawPenalty = 0   →   totalPenalty = 0
```

**TOTAL**
```
pre  = 0.40·68 + 0.25·69 + 0.20·98 + 0.15·67
     = 27.2 + 17.25 + 19.6 + 10.05 = 74.10
score = clamp(round(74.10) − 0, 0, 100) = 74
barsSufficient = (500 ≥ 60) ✔ ; regimeAligned(DOWN) = (TRENDING_DOWN == TRENDING_DOWN) = true
VERDICT = PROJECT
measuredMove: height = 189.412 ; triggerLevel = 1036.470 ; projectedLevel = 995.00 − 189.412 = 805.588
```

---

### 10.2 EXAMPLE B — ambiguous / near-miss H&S

Same five roles, but with a **flat neckline, mismatched shoulders, a shallow head and a thick
shoulder**:

| role | legIndex | bar | price | kind |
|------|----------|-----|-------|------|
| `LS` | 19 | 58 | 1,140 | HIGH |
| `T1` | 20 | 72 | 1,000 | LOW |
| `H`  | 21 | 84 | 1,060 | HIGH |
| `T2` | 22 | 98 | 1,005 | LOW |
| `RS` | 23 | 126 | 1,020 | HIGH |

```
symShoulder   = 1020/1140 = 0.894737   → G1 = LIN_LINEAR(0.894737, 0.80, 1.00) = 47.368
neckline.slopePerBar = 5/26 = 0.192308 ; neckline.at(84) = 1000 + 0.192308×12 = 1002.308
ref = 1060 − 1002.308 = 57.692
headDominance = (1060 − max(1140,1020))/57.692 = (1060 − 1140)/57.692 = −1.3869
   ⇒ NEGATIVE. The head is BELOW the left shoulder: the roles are wrong for an H&S.
   ⇒ Detector 6 REJECTS (S1: H must exceed both shoulders). This is the strongest possible
     near-miss and must produce NO match.
```
To keep the example scoreable, lower `LS` to **1,050** and keep `RS = 1,020`:
```
symShoulder   = 1020/1050 = 0.971429 → G1 = LIN_LINEAR(0.971429, 0.80, 1.00) = 85.714
headDominance = (1060 − 1050)/57.692 = 0.173077
              → G2 = LIN_LINEAR(0.173077, 0.10, 0.60) = 14.615
              → G3 = LIN_LINEAR(0.173077, 0.10, 0.50) = 18.308
timeSym: gaps 14 and 28 → sym = 14/28 = 0.500000 → G4 = LIN_LINEAR(0.5, 0.5, 1.0) = 0.000
neckTilt = 0.192308 × (126−58) / 1000 × 100 = +1.3077 % → G5 = LIN_DECAY(1.3077,0,10) = 86.923
monoScore = 1.0 → G6 = 100.000
sum = 85.714·0.20 + 14.615·0.15 + 18.308·0.20 + 0.000·0.10 + 86.923·0.10 + 100.000·0.15
    = 17.1428 + 2.1923 + 3.6616 + 0 + 8.6923 + 15.0000 = 46.6890
G = round(46.6890 / 0.90) = round(51.877) = 52
```
```
S1 residMax = 0.0 → 100.000  (weight redistributed to .2778)
S2 r² = 0.905 → LIN_LINEAR(0.905, 0.30, 0.95) = 93.077  (weight .2222)
S3 2-point neckline → 0.60 → 34.483                              (weight .1667)
S4 spacing: gIdeal 15, gTol 12. legs 14, 12, 14, 28
   legScore(14) = 1 − 1/12 = 0.91667 ; legScore(12) = 1 − 3/12 = 0.75
   legScore(28) = 1 − 13/12 = −0.08333 → clamped 0
   Π = 0.91667 × 0.75 × 0.91667 × 0 = 0
   → LIN_LINEAR(0, 0, 1) = 0.000                                      (weight .1667)
S5 span = 126 − 58 = 68 ; v = 1.0 → 41.176                            (weight .1667)
S = round(27.780 + 20.684 + 5.748 + 0 + 6.865) = round(61.077) = 61
```
```
C1 breakDepth: breakout bar 132, close = 998.00 ; neckline.at(132) = 1000 + 0.192308×60 = 1011.538
        breakDepth = (1011.538 − 998.00)/1011.538 × 100 = 1.3381 %
        → RATIO_SATURATING(1.3381, 2.0) = 66.907                         ×0.25
C2 rvol[132] = 1.72 → RATIO_SATURATING(1.72, 3.0) = 57.333            ×0.25
C3 followThrough: close[133] = 1002.50 > 998.00 → 0                     ×0.10
C4 retest: close[135] = 1009.00, neckline.at(135) = 1011.154
        → still below, retest held → 1                                   ×0.10
C5 obvSlope(5 to 132) = +88,000 → disagrees with DOWN → 0               ×0.10
C6 deliveryVsAvg = 0.92 → RATIO_SATURATING(0.92, 1.50) = 61.333         ×0.10
C7 gapRsi = |RSI(58)=66.0 − RSI(84)=63.5| = 2.5 → LIN_LINEAR(2.5,0,8) = 31.250 ×0.10
C = round(16.727 + 14.333 + 0 + 10 + 0 + 6.133 + 3.125) = round(50.318) = 50
```
```
X1 priorTrendPct = +13.861 → 100.000   ×0.35
X2 LIN_LINEAR(40 − 30.0, 0, 20) = 50.000  ×0.25
X3 di+ 27 > di− 15 but direction DOWN → 0  ×0.10
X4 −0.05 → RATIO_SATURATING(0.05, 0.15) = 33.333  ×0.15
X5 W1 TRENDING_DOWN agrees → 1                ×0.10
X6 1                                        ×0.05
X = round(35 + 12.5 + 0 + 5.0 + 10 + 5) = round(67.5) = 68
```
```
PENALTIES
  WEAK_VOLUME  −6    (rvol 1.72, and the book: "A neckline break on weak volume deserves caution")
  NO_DIVERGENCE −6   (gapRsi 2.5 < 3.0)
  NO_FOLLOW_THROUGH −5 (close[133] above the breakout close)
  rawPenalty = 17 ; totalPenalty = min(17, 30) = 17

TOTAL
pre = 0.40·52 + 0.25·61 + 0.20·50 + 0.15·68
    = 20.8 + 15.25 + 10.0 + 10.2 = 56.25
score = clamp(round(56.25) − 17, 0, 100) = 56 − 17 = 39
barsSufficient ✔ ; regimeAligned(DOWN) = true
VERDICT = SUPPRESS
```
**This is the important negative fixture:** a formation that *looks* like an H&S and passes every shape
rule at 0.90 tolerance scores 39 and is never shown. If an implementation renders this one, its scorer
has a clustering/weighting bug.

---

### 10.3 EXAMPLE C — near-miss double top that survives

Two peaks that are 2 % apart, a shallow trough, **no volume expansion, and a bear trap**:

| role | legIndex | bar | price | kind |
|------|----------|-----|-------|------|
| `P1` | 19 | 24 | 1,200 | HIGH |
| `V`  | 20 | 48 | 1,140 | LOW |
| `P2` | 21 | 66 | 1,176 | HIGH |

```
P3 priorTrendPct(24) = +20.000 %  ✔
S1 sym(1200, 1176) = 0.980000  ✔ ≥ 0.97   (borderline: it passes by exactly 0.01)
S2 (1200 − 1140) = 60 ; ATR14(48) = 18.0 ; 2·ATR = 36 ; 60 ≥ 36  ✔
S3 separation = 42  ✔
ref = 60.00
```
**GEOMETRY** (detector 8 uses G1 symmetry, G2 troughDepthAtr, G3 peakSym, G5 necklineTilt is N/A → 0
weight redistributed, G6 monoScore)
```
G1 sym(peaks)       = 0.980000 → LIN_LINEAR(0.98, 0.80, 1.00) = 90.000   ×0.20
G2 troughDepthAtr  = 60/(2×18) = 1.66667 → LIN_LINEAR(1.66667, 2.0, 6.0) = 0.000 ×0.15
G3 peakSym         = 0.980000 → LIN_LINEAR(0.98, 0.94, 1.00) = 66.667  ×0.20
G4 timeSym         = N/A for a double top → redistributed
G5 necklineTilt    = N/A (horizontal confirmation level)                → redistributed
G6 monoScore       = 1.0 → 100.000                                     ×0.15
   remaining weights on G1 and G3: original 0.20/0.20 → share 0.50/0.50
G = round(90.000·0.3333 + 0.000·0.1667 + 66.667·0.3333 + 100.000·0.1667)
  = round(29.997 + 0 + 22.221 + 16.667) = round(68.885) = 69
```
**STRUCTURE**
```
S1 residMax = 0.0 → 100.000                                              ×0.2778
S2 r² = 0.880 → LIN_LINEAR(0.880, 0.30, 0.95) = 89.231                  ×0.2222
S3 2-point confirmation line → 0.60 → 34.483                            ×0.1667
S4 spacing: gIdeal 30, gTol 25 (D1). gap = 42
   legScore = 1 − |42−30|/25 = 1 − 0.48 = 0.52000
   → LIN_LINEAR(0.52, 0, 1) = 52.000                                     ×0.1667
S5 span = 66 − 24 = 42 ; spanIdeal 42 → v = 1.0
   → LIN_LINEAR(1.0, 0.30, 2.0) = 41.176                                  ×0.1667
S = round(27.780 + 19.829 + 5.748 + 8.667 + 6.865) = round(68.889) = 69
```
**CONFIRMATION**
```
C1 breakDepth: breakdown bar 78, close = 1,128.00 ; trigger = 1,140.00
        breakDepth = (1140 − 1128)/1140 × 100 = 1.05263 %
        → RATIO_SATURATING(1.05263, 2.0) = 52.632                        ×0.25
C2 rvol[78] = 1.21 → RATIO_SATURATING(1.21, 3.0) = 40.333               ×0.25
C3 followThrough: close[79] = 1,134.00 > 1,128.00 → 0 (DID NOT continue down) ×0.10
C4 retest: close[81] = 1,146.00 > trigger 1,140.00 → the retest FAILED to hold → 0 ×0.10
C5 obvSlope(5 to 78) = +210,000 → disagrees with DOWN → 0               ×0.10
C6 deliveryVsAvg = 0.71 → RATIO_SATURATING(0.71, 1.50) = 47.333         ×0.10
C7 gapRsi = |RSI(24)=68.2 − RSI(66)=66.0| = 2.2 → LIN_LINEAR(2.2,0,8) = 27.500 ×0.10
C = round(13.158 + 10.083 + 0 + 0 + 0 + 4.733 + 2.750) = round(30.724) = 31
```
**CONTEXT**
```
X1 +20.000 → LIN_LINEAR(20.0, 0.0, 12.0) = 100.000                       ×0.35
X2 LIN_LINEAR(40 − 34.0, 0, 20) = 30.000                                ×0.25
X3 di+ 30 > di− 13 but direction DOWN → 0                                ×0.10
X4 −0.04 → RATIO_SATURATING(0.04, 0.15) = 26.667                         ×0.15
X5 W1 TRENDING_DOWN agrees → 1                                            ×0.10
X6 1                                                                    ×0.05
X = round(35 + 7.5 + 0 + 4.0 + 10 + 5) = round(61.5) = 62
```
**PENALTIES**
```
BEAR_TRAP          −12  (close[78]=1128 below 1140, then close[81]=1146 back above → bear trap)
WEAK_VOLUME        −6   (rvol 1.21)
NO_DIVERGENCE      −6   (gapRsi 2.2 < 3.0)
FAILED_RETEST      −8   (the retest to 1140 closed back above)
NO_FOLLOW_THROUGH  −5   (close[79] rose)
rawPenalty = 37 ; totalPenalty = min(37, 30) = 30        ← CAP BINDS

TOTAL
pre = 0.40·69 + 0.25·69 + 0.20·31 + 0.15·62
    = 27.6 + 17.25 + 6.2 + 9.3 = 60.35
score = clamp(round(60.35) − 30, 0, 100) = 60 − 30 = 30
VERDICT = SUPPRESS
```
**This is the cap fixture:** without the cap the score would have been `60 − 37 = 23`; with the cap it
is `30`. Both suppress, which is the point — the cap changes the *reported* number, not the verdict, so
it can never be used to launder a failed pattern back into view.

---

### 10.4 Summary of the three fixtures

| | A (perfect) | B (ambiguous) | C (near-miss) |
|---|---|---|---|
| GEOMETRY | 68 | 52 | 69 |
| STRUCTURE | 69 | 61 | 69 |
| CONFIRMATION | 98 | 50 | 31 |
| CONTEXT | 67 | 68 | 62 |
| pre-penalty | 74.10 | 56.25 | 60.35 |
| rawPenalty | 0 | 17 | 37 |
| totalPenalty (capped 30) | 0 | 17 | 30 |
| **score** | **74** | **39** | **30** |
| **verdict** | PROJECT | SUPPRESS | SUPPRESS |

Spread across the three: `30 → 74`, a range of 44 points. This is the discrimination the P7 gate is
looking for, at the level of a single stock.

---

## 11. Constants to place in `TechnicalPolicy` (or a new `PatternScoringPolicy`)

Every `LIN_*` anchor, weight and penalty value in this file is a named constant. Do not inline them.

```kotlin
object PatternScoringPolicy {
    // geometry anchors
    const val GEOM_SYM_LO = 0.80;  const val GEOM_SYM_HI = 1.00
    const val GEOM_DOM_LO = 0.10;  const val GEOM_DOM_HI = 0.60   // G2 for H&S
    const val GEOM_DOM3_LO = 0.10; const val GEOM_DOM3_HI = 0.50  // G3 for H&S
    const val GEOM_DOM_DT_LO = 2.0; const val GEOM_DOM_DT_HI = 6.0 // G2 for double top/bottom (ATR units)
    const val GEOM_TIMESYM_LO = 0.50; const val GEOM_TIMESYM_HI = 1.00
    const val GEOM_TILT_PERFECT = 0.0; const val GEOM_TILT_FLOOR = 10.0
    const val GEOM_MONO_LO = 0.60; const val GEOM_MONO_HI = 1.00
    const val GEOM_PARALLEL_FLOOR = 0.0; const val GEOM_PARALLEL_MAX = 0.75
    const val GEOM_FLAGDEPTH_LO = 0.05; const val GEOM_FLAGDEPTH_HI = 0.45
    const val GEOM_POLE_SAT = 0.25
    const val GEOM_TRI_REF_LO = 3.0; const val GEOM_TRI_REF_HI = 10.0   // ref/ATR
    const val GEOM_WICK_SAT = 4.0
    const val GEOM_STARRATIO_PERFECT = 0.05; const val GEOM_STARRATIO_FLOOR = 0.50
    const val GEOM_PEN_LO = 0.30; const val GEOM_PEN_HI = 0.90
    const val GEOM_TRI_RISE_LO = 2.0; const val GEOM_TRI_RISE_HI = 15.0
    const val NEUTRAL_BOUNDARY_R2 = 0.60   // DD-7

    // structure anchors
    const val STRUCT_RESID_FLOOR = 0.15
    const val STRUCT_R2_LO = 0.30; const val STRUCT_R2_HI = 0.95
    const val STRUCT_BOUNDARY_R2_LO = 0.40; const val STRUCT_BOUNDARY_R2_HI = 0.98
    const val STRUCT_DUR_LO = 0.30; const val STRUCT_DUR_HI = 2.00
    const val STRUCT_CV_LO = 0.02; const val STRUCT_CV_HI = 0.12
    const val STRUCT_RANGEAT_PERFECT = 4.0; const val STRUCT_RANGEAT_FLOOR = 0.6

    // confirmation anchors
    const val CONF_BREAKDEPTH_SAT = 2.0
    const val CONF_RVOL_SAT = 3.0
    const val CONF_DELIVERY_SAT = 1.50
    const val CONF_RSI_GAP_HI = 8.0

    // context anchors
    const val CTX_PRIOR_LO = 0.0; const val CTX_PRIOR_HI = 12.0
    const val CTX_ADX_LO = 20.0; const val CTX_ADX_HI = 40.0
    const val CTX_RS_SAT = 0.15

    // penalty table
    const val PEN_WEAK_VOLUME = 6
    const val PEN_TRAP = 12
    const val PEN_FAILED_RETEST = 8
    const val PEN_FAILED_PATTERN = 18
    const val PEN_FAILED_CONTINUATION = 18
    const val PEN_STRUCTURE_CONTRADICTS = 14
    const val PEN_CTX_MISMATCH = 10
    const val PEN_CTX_MISMATCH_CS = 20      // candlesticks, ×2
    const val PEN_HTF_CONFLICT = 5
    const val PEN_VOLATILE = 5
    const val PEN_LOW_LIQUIDITY = 8
    const val PEN_VOLUME_CONTRACTION_ABSENT = 6
    const val PEN_NO_DIVERGENCE = 6
    const val PEN_NO_PRIOR_TREND = 10
    const val PEN_RANGING = 8
    const val PEN_EXHAUSTION = 6
    const val PEN_INTRADAY_SPIKE = 10
    const val PEN_STALE = 6
    const val PEN_NO_CONFIRMATION = 8
    const val PEN_NO_FOLLOW_THROUGH = 5
    const val PEN_WRONG_LOCATION = 12
    const val PENALTY_CAP = 30

    // discrimination gate (plan.md §11 P7)
    const val DISCRIM_MIN_DISTINCT_SCORES = 45
    const val DISCRIM_MIN_STDDEV = 18.0
    const val DISCRIM_MIN_IQR = 20.0
    const val DISCRIM_MAX_MODE_SHARE = 0.06
    const val DISCRIM_MIN_BAND_SHARE = 0.10
    const val DISCRIM_MIN_PER_PATTERN_STDDEV = 12.0
}
```

---

## 12. Open items for the owner

| # | Item |
|---|-------|
| **S1** | plan.md §6.4 makes `regimeAligned` a hard gate on `PROJECT`. `TechnicalAdx.isRegimeAligned` returns `false` in `TrendRegime.RANGING`, which blocks continuation patterns in exactly the ranging market where `[Ch.17-4 §Ranging Markets]` says reversal patterns at range boundaries are most useful. **Recommendation:** leave as specified (it is a locked rule) but review for detectors 8–11 after the P7 discrimination run. |
| **S2** | The book's H&S/double-top measured moves subtract from the **breakout close**, giving `projected ≠ 2·boundary − extreme`. Following the book literally means the rendered line differs from what charting platforms draw. `patterns-spec.md` §23 C7 flags it; no change proposed. |
| **S3** | DD-5 caps `FORMING` matches at `RENDER`. This means the app never shows a projection for an untriggered pattern, which is arguably the point of the `directional forecast` output in plan.md §6.6 item 2. Confirm intent. |
| **S4** | §9.3 step 2 replaces raw volume multiples with the stock's own 120-bar percentile if clustering persists. That is a deviation from the book's plain "exceeds recent averages" `[Ch.21 §21.5]`. Flagged so the change is a decision, not a silent one. |