# Chart patterns: range selection, five pattern families, and a measured formation score

> **Status:** plan, **owner decisions recorded 2026-10-18**. Nothing implemented yet.
> **Against:** `main` @ `cc5e649`.
> **Companions:** `planv2.md` (shipped work), `docs/VERIFICATION.md` (measured evidence),
> `docs/LIVE-PROBE.md` (live-source behaviour).

---

## 0. Decisions taken (owner, 2026-10-18)

These were open in the first draft. They are now settled and the rest of the plan follows from them.

| # | Decision | Answer |
|---|---|---|
| 1 | What "pattern probability" means | **A measured conditional frequency with `n`, a confidence interval and the base rate it beats.** Not a forecast. |
| 2 | Maximum chart range | **6 months (~126 bars)** — raised from an initial 2-month cap so Cup & Handle fits |
| 3 | Does range drive detection? | **Yes** — now bounded at 126 bars, so it is safe |
| 4 | Training universe | **451 index constituents** (NIFTY 50 + MIDCAP 150 + SMALLCAP 250) |
| 5 | If the model fails the bar | **Publish the negative result** in `docs/VERIFICATION.md`; ship phases 1–2 regardless |
| 6 | Timeframes | **EOD-only** — decision D1 holds, no intraday |

### Decision 1, stated plainly

A literal *"pattern probability: 73%"* cannot ship. `HonestyContract.PROBABILITY_CLAIMS` rejects
`% chance`, `probability of rise`, `odds of rise` and similar, machine-checked by
`requireNotProbabilityClaim` on every render path. The chosen alternative is not a consolation
prize — it is a **stronger** claim, because it carries its own denominator and the comparison it
must be read against:

> *Formations like this one — measured level within 3 % of the close — touched that level within
> 5 bars **82 %** of the time. **n=121.** 95 % CI 76–87 %. Base rate for a level that far away: **10 %**.*

That is the shape `ProjectionCalibration` already produces and already tests, so it inherits a
measured implementation rather than needing a new one.

### Decision 2 and why it helped

Cup & Handle cannot fit in the originally-stated 2-month cap: its rim runs 2–5 months (**42–105
bars**) plus a 1–3 week handle. Raising the cap to 6 months gives **126 bars**, which fits.

The useful side effect: **the chart no longer needs Yahoo at all.** 126 daily sessions is six
months of bhavcopy, which the app already stores. The Yahoo dependency drops out of the chart path
entirely and survives only for the `^NSEI` benchmark and the Kaggle training fetch. Fewer moving
parts, one fewer rate-limited source, one fewer failure mode on the screen.

The honest limit: **126 bars is what the device has stored.** A fresh install has one session, so
the long end of the selector is unavailable until history accumulates — and the UI says which
range is actually servable rather than offering a 6-month button that draws 40 bars.

---

## 1. What I verified before writing this

Every number is measured. Reproduce with §9.

| Question | Answer | How |
|---|---|---|
| Long history for Indian equities? | **Yes — 2,473 daily rows over 10 years** for `RELIANCE.NS`, 2016-10-17 → 2026-10-16 | cookie+crumb, `range=10y&interval=1d` |
| Benchmark? | **Yes — `^NSEI`, 1,237 rows / 5y** | same endpoint |
| Currency / exchange | **INR / NSE** | `meta.currency`, `meta.fullExchangeName` |
| App-side Yahoo infra? | **Exists** — `YahooCrumbHandshake`, `YahooEndpoints`, `YahooInterval`, `YahooIndexSource` | `data/src/main/.../history/` |
| Is 6 months of daily data available without Yahoo? | **Yes** — bhavcopy, already stored | 126 sessions |
| Trading universe | **451** index constituents (50 + 150 + 251), of 2,593 `EQUITY_L` rows | bundled assets |
| Cup & Handle detector? | **Does not exist** — glossary-only | `DetectorRegistry.kt` |
| `KAGGLE_API_TOKEN`? | **Present**, added 2026-10-18 | `gh secret list` |
| Python tooling? | **None** — no `requirements.txt`, no notebook | repo root |

**Reusable as-is**

- `ZigzagPivots.compute` — one shared pivot pass for all detectors. The ML feature pipeline must
  reproduce it **exactly**; see the parity gate in §7.
- `ChartContext` — the indicator snapshot a detector consumes. `DoubleTopDetector` is the reference
  shape for Cup & Handle.
- `ChartState.idealPattern` — an idealised shape already renders over the series, so "plot the
  pattern" is mostly emitting the right pivots, not new drawing code.
- `ProjectionGate` + `ProjectionCalibration` — the measured baseline the model must beat (§3.4).

**Genuinely new:** the Cup & Handle detector, the range selector, Python tooling, the label/feature
pipeline.

---

## 2. Phase 1 — time range selection

### 2.1 The ranges (owner decision 2)

| Label | Bars (D1) | Approx. calendar | Source |
|---|---|---|---|
| 1W | 5 | 1 week | bhavcopy |
| 15D | 15 | 3 weeks | bhavcopy |
| 1M | 22 | 1 month | bhavcopy |
| 2M | 44 | 2 months | bhavcopy |
| 3M | 66 | 1 quarter | bhavcopy |
| 6M | 126 | 2 quarters | bhavcopy |

**No Yahoo on this path.** All six are satisfiable from stored daily bars. Yahoo stays for `^NSEI`
and for the Kaggle fetch only.

A range longer than what is stored is **disabled with its reason shown**, not silently truncated.
"6M" on a device with 40 sessions reads *"6 months — needs 86 more sessions before this device can
show it"*, because a selector that offers a range and then quietly draws less is the exact failure
mode this repo keeps guarding against.

### 2.2 Detection follows the range (owner decision 3)

Detectors see the full selected range. At the 6-month cap that is at most 126 bars — bounded, which
is what makes this safe. The earlier version of this plan warned that widening the window makes
detectors *noisier*; with a hard 126-bar ceiling the exposure is bounded, but the mitigation still
applies:

> **Report the candidate count with the range.** 126 bars yields many more pivot candidates than 22.
> "Found nothing on this stock" and "found nothing because you gave it six months" must not look the
> same.

`docs/VERIFICATION.md` records that **8 of 14 patterns never fire** — every one a strictness gate.
Per-detector span caps (`ChartPatternSupport.spanBars`) are the right mechanism; verify each holds
at 126 bars rather than assuming.

### 2.3 Acceptance

- All six ranges render, state their bar count, and state their source.
- An unservable range is disabled with the shortfall stated, never silently truncated.
- Range survives rotation and tab switches (`rememberSaveable`, keyed by symbol).
- Candidate counts are shown alongside the range.

---

## 3. Phase 2 — the five pattern families

### 3.1 Status

| Family | Detector | `minBars` | State |
|---|---|---|---|
| Head & Shoulders | `HeadAndShouldersDetector` | 20 | ✅ live, measured |
| Inverse H&S | `InverseHeadAndShouldersDetector` | 20 | ✅ live, measured |
| Double Top | `DoubleTopDetector` | 15 | ✅ live, measured |
| Double Bottom | `DoubleBottomDetector` | 15 | ✅ live, measured |
| Flags (bull & bear) | `FlagDetector` (emits both) | 10 | ✅ live, measured |
| **Cup and Handle** | — | — | ❌ **does not exist** — glossary-only |

Five of six are implemented and measured. **Cup & Handle is new detector work** and is the hardest
of the six: it is the pattern most sensitive to parameter choice and the one most likely to spam.

At the 126-bar cap it fits: rim ≤105 bars + handle ≤15 bars. Budget it separately, and treat
*"fires on 1 of 41 large caps"* as a **success**, consistent with how this repo treats its other
strict detectors.

Suggested geometry, all to be argued from `docs/spec/patterns-spec.md` rather than invented:
depth 12–33 % of rim width · rim duration 2–5 months · handle retraces ≤50 % of the depth · handle
sits in the upper half of the rim · breakout preferably on above-average volume.

### 3.2 What the UI shows per formation (owner decision 1)

Two outputs, kept visually distinct:

- **A quality score that orders formations.** "Cleaner than the other four on this stock." A
  ranking, not a prediction. It must be a **new** feature set — the existing match score is
  anti-correlated with outcomes (§3.4), so reusing it would repeat that bug.
- **The conditional frequency**, rendered exactly as in §0, with `n`, CI and base rate.

Never a per-formation point estimate. `HonestyContract.requireHitRateSampleSize` enforces `n=` on
every rate, and a bare "73 % for this formation" is precisely what that rule exists to prevent —
the `RSI_OVERSOLD` shape, which fires on 3.34 % of observations and looks entirely plausible.

### 3.3 Where the frequency comes from

Device-stored settled outcomes where there are enough, the published fixture measurement otherwise —
**labelled which way, always.** `InsightProvenance` is a required constructor field precisely so no
render path can omit the label. Below n=30 the figure is withheld with the count, as everywhere else.

### 3.4 The bar the model must clear

Written down **before** the model exists:

| Baseline | Value | Source |
|---|---|---|
| Unfiltered level-touch rate, real detectors | **34 %** (n=875) | `docs/VERIFICATION.md`, corrected from 16.45 % |
| ≤3 % distance, h5 | **82 %** (n=121) | `ProjectionCalibration` |
| ≤1 % distance, h5 | 90 % (n=49) — but control is **68 %** | distance barely helps there |
| 5 % distance, h5 | **65 %** vs a **10 %** control = 6.5× | the real edge |

**If the model does not beat "distance alone" out-of-sample, it does not ship.** That predictor is
one line of code, so beating it is the minimum bar for a thousand lines of pipeline.

---

## 4. Phase 3 — the Kaggle pipeline

### 4.1 Data (owner decision 4)

- **Source:** yfinance — the same Yahoo endpoints the app already speaks, so both fetchers agree.
- **Universe:** the **451 index constituents** as `.NS`. Not all 2,593 `EQUITY_L` rows: thinner names
  return nothing, and delisted entries in that file 404.
- **Benchmark:** `^NSEI`.
- **Window:** `10y`, `interval=1d`, adjusted → ≈ 451 × 2,473 ≈ **1.1 M rows**.
- **Snapshot the date.** The artifact records the snapshot date and the constituent list; a model is
  only reproducible against the data it was trained on.

### 4.2 Survivorship bias — disclosed, not fixed

yfinance serves **only currently-listed symbols**, so the training set is survivors-only. This is the
single largest threat to anything it produces and it is not fixable with free data.

The repo already teaches this twice — `docs/VERIFICATION.md` on the committed fixture ("the fixture's
41 symbols are all survivors") and the education curriculum's backtesting-literacy module. It goes
into the artifact metadata **and** into the UI wherever a model-derived figure appears.

### 4.3 Labels — one definition, shared with the app

*Did the formation's measured level get touched within h bars?* Sourced from
`ProjectionCalibration` so Python and Kotlin cannot disagree about what "touched" means. Horizons
h1…h20 as that file already uses.

### 4.4 Features — derived from the app's own geometry

So the model scores *describable shape*, not a black box.

| Family | Source |
|---|---|
| Leg geometry | pivot-to-pivot % returns, symmetry, depth, rim/target ratio |
| Time structure | leg durations in bars, total span, retracement ratios |
| Confluence | touch count, breakout depth, retest count (`ChartPatternSupport`) |
| Volume | volume ratio vs 20-bar, OBV slope, delivery % |
| Context | ADX regime, % above SMA50, relative strength vs `^NSEI`, ATR% |
| Proximity | `|level − close| / close` — **the known strongest feature, so it must be in the set** |

### 4.5 Validation — walk-forward, never random

A random split leaks the future into training and reports a number nothing reproduces. Same approach
as `ScoreDiscriminationMeasurement`: rolling windows, **purged at the boundary** so a formation's
20-bar forward window never straddles the split.

Report **per fold**, not pooled. A model that wins fold 1 and loses fold 5 has beaten nothing.

### 4.6 Model and artifact

Preference order, and the reasons are about honesty rather than novelty:

1. **Logistic regression** — coefficients as a Kotlin `const`, no runtime dependency, hardest to
   overfit. If a linear model cannot beat "distance ≤ 3 %", nothing more complex will.
2. **Shallow gradient-boosted trees** — exported as explicit trees evaluated by hand-written Kotlin.
   Portable, inspectable, no TFLite/ONNX added to a 26 MB APK.
3. **Not now** — neural nets, on-device training, any runtime framework.

Committed with the model: the snapshot date, the constituent list, per-fold metrics, and the
baseline it beat — or failed to.

**CI job** (on-demand + nightly): re-evaluate the committed artifact against current data and
**fail if it no longer clears the baseline.** A decayed model must stop rendering.

---

## 5. Phase 4 — on-device integration

1. Evaluate the artifact in `:domain`, so it is JVM-testable and CI-verified.
2. Per formation: idealised shape over the series, measured-move level and distance, and the
   conditional frequency with `n` / CI / base rate — device-stored where available, published
   otherwise, labelled which.
3. `AdviceLanguage` + `HonestyContract` on every string, as everywhere else.
4. Missing or stale artifact → the frequency section is **absent**, not defaulted.

---

## 6. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| **Python/Kotlin pivot divergence** | **High, and the most dangerous** | parity harness asserting identical pivots on the committed fixture; CI fails on divergence |
| **Model does not beat the distance baseline** | **Likely** | §8 — publish the null |
| Yahoo 429s during training | High — documented common from Indian networks (gap F2) | backoff, committed snapshot cache, resumable fetch |
| Cup & Handle spams | High | span caps, candidate counts reported with the range |
| Survivorship bias | Certain, unfixable | disclosed in artifact **and** UI |
| Class imbalance (34 % positive) | Certain | precision/recall + confusion matrix, never accuracy alone |
| Multiple comparisons | High | state how many candidates were tried, as the repo does for detector strictness |

---

## 7. Phasing and gates

| Phase | Deliverable | Gate |
|---|---|---|
| **0** | yfinance access, snapshot committed, **parity harness** | Python and Kotlin pivots identical on the fixture |
| **1** | Range selector (1W–6M) | all six render with bar count + source; unservable ranges disabled with a reason |
| **2** | Cup & Handle detector | fires on a synthetic fixture; stays inside its census budget at 126 bars |
| **3** | Kaggle pipeline + model | **clears the distance baseline out-of-sample across folds, or does not ship** |
| **4** | On-device scoring + UI | every string passes compliance; `n` and CI present |

**Phase 1 ships value on its own** and depends on none of the ML work. Start it regardless.

---

## 8. If the model fails (owner decision 5)

This is a real possibility and the plan does not pretend otherwise. The repo's record argues for
caution: the match score is **anti-correlated** with outcomes, 8 of 14 patterns never fire, and the
control test found every conventional signal sitting on a coin flip.

If it fails the gate:

1. **Do not ship the score.** Render nothing rather than something that looks authoritative.
2. **Publish the negative result** in `docs/VERIFICATION.md` with per-fold numbers. A measured null
   is a real result, and this repository values those over silence.
3. **Ship phases 1 and 2 regardless** — they are independent of the ML entirely.
4. Record what was tried and how many variants, so the next attempt does not repeat it.

---

## 9. Reproducing §1

```bash
curl -sS -A "Mozilla/5.0" -c /tmp/yc.txt "https://fc.yahoo.com" -o /dev/null
CRUMB=$(curl -sS -A "Mozilla/5.0" -b /tmp/yc.txt \
  "https://query1.finance.yahoo.com/v1/test/getcrumb")
curl -sS -A "Mozilla/5.0" -b /tmp/yc.txt \
  "https://query1.finance.yahoo.com/v8/finance/chart/RELIANCE.NS?range=10y&interval=1d&crumb=$CRUMB"
# → 200, 2,473 rows, 2016-10-17 → 2026-10-16, meta.currency=INR

wc -l data/src/main/resources/assets/indices/*.csv \
      data/src/main/resources/assets/EQUITY_L.csv
gh secret list | grep KAGGLE_API_TOKEN
```

---

## 10. Still open

1. **Cup & Handle geometry constants** — the depth/duration/retracement figures in §3.1 are the
   textbook defaults and should be argued against `docs/spec/patterns-spec.md` during Phase 2, not
   adopted unexamined.
2. **How often the Kaggle job runs.** Nightly costs quota and will hit 429s; weekly with a manual
   dispatch may be better. Cheap to decide at Phase 3.
3. **Whether the quality score ships at all if the model passes** — it is useful for *ordering* even
   when it does not predict. Worth revisiting once phase 3 produces per-fold numbers.