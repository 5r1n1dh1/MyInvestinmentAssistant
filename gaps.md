# Expert review — gaps & suggestions

Reviewer stance: an Indian-market investor with 20+ years of cycle experience, asked to be
**maximally honest**. Everything below is grounded in the repo's own measurements
(`docs/VERIFICATION.md`, `docs/DATA-NOTES.md`, the measurement files under `:domain`),
not impression. Where I could not verify something, it says so.

Reviewed: `plan.md`, `README.md`, `docs/*`, `:domain` (engine), `:data` (parsers),
`:android-data` (persistence/workers), `:app` (Compose UI).

---

## 0. What is genuinely strong (credit, then the gaps)

This app's honesty architecture is better than 95% of retail "analysis" apps, Indian or
otherwise:

- **Two numbers never combined** (match score vs hit rate), each with a stated meaning —
  `domain/compliance/Disclosure.kt`.
- **The 50% control test is reproduced in-repo** and drives the product's core claim.
- **Red flags carry mandatory evidence**; wording is factual, never verdicts
  (`FundamentalsFlagIds`, `docs/spec/redflags-spec.md`).
- **Three-state availability model** (`AVAILABLE` / `SUPPRESSED_FOR_SECTOR` /
  `NOT_SOURCED`) with compile-adjacent invariants — a blank can never masquerade as a value.
- **SEBI boundary is machine-checked**: a test scans every user-facing literal for advice
  language. Zero hits.
- **Self-measurement culture**: the team found and published that the projection gate is
  unreachable and that the score runs *backwards* (§A1/A2). Almost no project does this.
- **Corporate actions flagged, never silently adjusted** — the honest fallback.
- **robots.txt compliance is enforced by test** on the one permitted screener.in URL shape.

The gaps below are what remains between that standard and what an expert Indian investor
would trust with real money.

---

## 1. Gap register

Severity: **P0** = misleads or dead-ends the core flow · **P1** = materially weakens a
decision · **P2** = quality/completeness.

### A. Product integrity (measured, must be confronted)

| # | Gap | Evidence | Impact |
|---|-----|----------|--------|
| A1 **P0** | **The headline feature has never executed.** `PROJECT_THRESHOLD = 70` is unreachable: over 875 walk-forward candidates the highest score ever observed is **68**. The "project a level + fire an alert" path — the app's core loop — is dead code in practice. | commit `0fd2869`, `ScoreDiscriminationMeasurement.kt` | Every "detect → decide" flow a new user expects ends at "render, no projection". The breakout-alert machinery (`PatternScanWorker`, `BreakoutAlertDispatcher`) fires on nothing. |
| A2 **P0** | **The match score sorts in the wrong direction.** Higher score → *lower* hit rate, monotonic across all six horizons (50–59 bucket: 35% @h5; 60–69: 30%). The score is a proxy for *target distance*, pointed the wrong way. Any UI that ranks or filters by score actively hides the better setups. | commit `0fd2869` | The app's primary number is anti-correlated with outcomes. Until surfaced and re-derived, "score ≥ 70" is not just dead — it is misleading-by-construction if it ever fires. |
| A3 **P1** | **The one measured real signal is not in the product.** Target within 3% is touched **83% of the time within 5 bars vs a 10% base rate**; 87% of touches land on the *next* bar; median MAE before touch is 0.7–0.9%. This is the app's actual finding and it lives only in a measurement file. | commits `0fd2869`, `ec04991` | The differentiator an expert would pay for ("where do tight measured-moves occur, and how often are they reached") is invisible. |
| A4 **P1** | **The flagship differentiator (D6: per stock *and* per pattern) is empty for a year+.** With 18 months of data, every symbol×pattern cell is below the 30-sample gate and is withheld. Only per-symbol (n=62) and per-pattern (n=310) aggregate. | `docs/VERIFICATION.md` §3 | The Accuracy tab's most specific table — the one that answers "does *this* pattern work on *this* stock*" — shows nothing on a real device. |
| A5 **P1** | **8 of 14 evaluated patterns never produce a candidate** on real data (Bear Flag, Bull Flag, Descending Triangle, Inverse H&S, + the 4 retired candlesticks). All are *correctly strict* (measured per-gate censuses) — but the user experience is "usually nothing here", and the README still advertises "12 textbook patterns". | commit `fedd20f`, `DetectorStrictnessCensus.kt` | Expectation mismatch: search a large cap, see nothing, conclude the app is broken. Strictness is principled; *communicating* it is not yet a product feature. |
| A6 **P2** | **Overall hit rate is 16.45%** — honest, but the app never teaches *why* it is low (measured-move levels are 4–5% of price against a ~1.8% median 5-bar excursion). A number without its explanation reads as "the app is bad at its job". | `docs/VERIFICATION.md` §3 | Trust erosion risk; the measurement is fine, the pedagogy is missing. |
| A7 **P2** | **Documentation drift**: README and `DetectorRegistry` KDoc still say "twelve patterns"; the catalog evaluates **9** and the candlestick family is retired to glossary-only (detectors still run, but their matches can never enter the track record — `measuredMove == null` is refused by `OutcomeTracker`). | `PatternCatalogData.kt` (candlestick note), README "The 12 patterns" | A reviewer reading the README overestimates what the app detects; a subtle semantic (candlesticks run but are untrackable) is undocumented in user-facing copy. |

### B. Market context — the biggest *investor* gap

| # | Gap | Evidence | Impact |
|---|-----|----------|--------|
| B1 **P0** | **No index data is ever loaded.** `benchmarkCandles = null` is hardcoded in `RealPatternsDataSource` and `DefaultPatternRepository`. The Context factor (15% of score) includes "relative strength vs index" (plan §6.3) — it is *permanently unscored*. No Nifty/Sensex level, no market regime, no breadth, no India VIX anywhere. | grep across `:app`, `:android-data`, `:data` | An EOD decision app with no market context is like a weather app with no sky: the single best predictor of whether an individual setup matters (market regime) is structurally absent, and the app says so only in transparency notes. |
| B2 **P1** | **No sector view.** Sector resolution exists *only* to drive ratio overrides. No sector-index performance, no rotation, no peer comparison within a sector (beyond median P/E). | `SectorResolver`, no sector-index code | Indian retail allocates by sector narrative; the app cannot answer "is the whole sector weak or just this stock?" |
| B3 **P1** | **Cap-band cohorts are bundled but not surfaced.** `IndexCohort` (NIFTY 50 / MIDCAP 150 / SMALLCAP 250 CSVs) exists; there is no "this is a small-cap — drawdowns behave differently" signal in the UI. | `data/symbols/IndexCohort.kt`, no UI usage | Large/mid/small-cap behaviour is the first-order risk classifier in India; a 2024–25 small-cap drawdown is a different animal. |
| B4 **P1** | **SME series (`SM`, 389 instruments) passes the bhavcopy filter with no warning.** SMEs are illiquid, circuit-hit prone, and a trap for exactly the retail user this app serves. No minimum-liquidity gate, no "thin trading" notice. | plan §3.1 filter `EQ+SM` | The app can show a beautiful pattern on a ₹2-cr-ADV SME with no caution. |

### C. Fundamentals data (Indian-market-specific)

| # | Gap | Evidence | Impact |
|---|-----|----------|--------|
| C1 **P1** | **The most important Indian red flags are unsourceable: promoter pledging and goodwill.** Pledging was at the centre of nearly every Indian blow-up (IL&FS, DHFL, Yes Bank, ADAG, Zee, Manpasand). The app implements the checks, then abstains on every real symbol. | `DerivedMetrics.promoterPledgePercent` (always null), `FundamentalsFlagIds` | The #1 question an Indian investor asks — "have promoters pledged shares?" — has no answer. |
| C2 **P1** | **All banking/NBFC key ratios are `NOT_SOURCED`:** NIM, GNPA/NNPA, CASA, PCR, stage-2, cost of funds. Financials are ~35% of Nifty; the app cannot show the numbers that matter most for its largest sector. | `RatioPriority.kt` banking/NBFC overrides | A bank screen that hides asset quality is a screen that can't be trusted for banks. (Note: screener.in's *live* pages now publish several bank ratios — the HDFCBANK fixture may be stale; worth one re-verification before accepting this as permanent.) |
| C3 **P1** | **FII/DII holdings are parsed but never surfaced.** The `shareholding` table is captured; only promoter % is used. FII/DII trend is a standard Indian-market signal. | `DerivedMetrics`, `RatioMapper` — no FII/DII `RatioId` exists | Free, already-fetched data sitting unused. |
| C4 **P1** | **13 quarters of P&L are parsed but there is no quarterly trend card.** Indian retail lives on the quarterly results rhythm; the Fundamentals tab shows annual ratios only. | `quarters` parser (13 quarters), `FundamentalsScreen` sections | No QoQ/YoY revenue/EPS trend — the most-viewed fundamental view in India is missing. |
| C5 **P2** | **Gross margin, EV/EBITDA, current ratio permanently `NOT_SOURCED`** (no COGS/EV/cash lines on the source page). Accepted, but no alternate path is attempted (e.g., deriving a crude EV from market cap + debt − cash if the balance-sheet block ever carries them). | `RatioId.NEVER_SOURCED` | Valuation lens is thinner than the plan promised. |
| C6 **P2** | **Single fundamentals source.** No divergence (feature permanently empty), no fallback if screener.in rate-limits from Indian CGNAT IPs — the same risk acknowledged for Yahoo (plan §13) but unmitigated here. | plan §5.1, `divergences() always empty` | One site's DOM change or block = the entire fundamentals tab. |
| C7 **P2** | **No exchange-published event overlays**: bulk/block deals, promoter transactions, F&O ban list, ASM/GSM surveillance, trade-to-trade — all free from NSE, all routine risk inputs for Indian retail. | no code anywhere | Missing the free, official signals that precede most Indian retail blow-ups. |
| C8 **P2** | **Unadjusted prices corrupt indicators around corporate actions** (BAJFINANCE −90% destroys the 200-DMA for weeks). Honest flag, but no adjusted *view* is offered even though Yahoo's `adjclose`/`events.splits` schema is known. | `CorporateActionDetector`, VERIFICATION §5 | Every long-term chart in the app carries a landmine the user must mentally remove. |

### D. Technicals

| # | Gap | Evidence | Impact |
|---|-----|----------|--------|
| D1 **P1** | **No monthly timeframe.** 1D/1W only; an *investment* assistant gives a long-term investor nothing beyond ~18 months of weekly bars. | plan D1, `Timeframe` | The stated user ("invest the next day" is trading; "investment" is multi-year) is only half-served. |
| D2 **P2** | **Indicator set has no EMA crossover, no MFI, no ATR-based risk framing.** ATR is computed but not surfaced as "a 2% risk stop is ₹X away at this ATR" — the one place technicals *educate* about position sizing. | `domain/technical/Indicators.kt` | Missed teaching moment at the exact point of decision. |
| D3 **P2** | **Delivery % depends on the legacy NSE file**, whose recent-date availability the repo itself flags as fragile. If it lapses, the delivery-collapse flag abstains (honest) but the Volume confirmation factor loses an input silently. | `LegacyDeliveryParser`, DATA-NOTES §3 | A key Indian-cash-market signal has a single, fragile source. |

### E. Education & UX (the core of this review's ask)

| # | Gap | Evidence | Impact |
|---|-----|----------|--------|
| E1 **P1** | **No structured curriculum.** Per-pattern glossaries are excellent (`GlossaryEntry` with mandatory `doesNotPredict`), but there is no beginner/intermediate/expert path, no ratio explainer ("what is ROCE, what is good"), no jargon decoder beyond patterns. | `PatternCatalogSpec`, no education module | The app's stated purpose ("educational") is currently a disclaimer + glossary, not education. |
| E2 **P1** | **Zero Indian taxation content** despite plan goal G4 ("Indian tax rules"). No LTCG/STCG, STT, holding-period basics anywhere (grep: zero hits outside "Tax %" P&L rows). | grep `LTCG\|STCG\|capital gains` | For an Indian *investment* app, tax literacy is table stakes for education (and the most common beginner mistake — ignoring the 1-year LTCG boundary). |
| E3 **P1** | **No risk-management education**: position sizing, concentration, horizon-matching, emergency-fund-first. The app can show you a pattern; it never asks whether you should risk 2% or 20% on it. | no code/copy | The most important determinant of retail outcomes is unaddressed. |
| E4 **P2** | **No "how to be wrong" content**: each pattern should teach its invalidation condition ("a flag that fails here means…"). `doesNotPredict` covers the disclaimer, not the lesson. | `GlossaryEntry` schema | Education stops at description. |
| E5 **P2** | **No journaling / decision log.** The app tracks *its own* pattern outcomes automatically (D6), but the user's *decisions* are unrecorded — the loop that would actually teach a user ("my thesis → outcome → what I learned") is absent. | no journal entity | The app's best idea (track record) is only half-generalised: to the app, not to the user. |
| E6 **P2** | **No peer comparison** ("why is TCS P/E 14 and Titan P/E 68.7?" — the single best teaching moment in the whole app, unexploited). | no compare feature | Missing the most natural ratio-education vehicle. |
| E7 **P2** | **Base-rate intuition is not taught in-app.** The 50% control test is the app's crown jewel and lives only in README/plan. An in-app "why nobody can predict tomorrow" module would be the strongest trust-builder the product has. | README §"one design decision" | The evidence for the app's honesty is invisible to the people it protects. |

### F. Process & delivery

| # | Gap | Evidence | Impact |
|---|-----|----------|--------|
| F1 **P0** | **`:app` and `:android-data` have never been compiled.** The entire UI — 78 files, Compose, Room schema, Hilt graph, WorkManager wiring — is unexecuted. Five screen files are parse-verified only. | VERIFICATION §5 | Everything above is, until one real build, a very well-tested hypothesis. |
| F2 **P1** | **P3 gate unexecuted**: the Yahoo history path is proven only against a fake transport; live row counts are unverified, and Yahoo 429s are common from Indian networks. If Yahoo fails in production, only the ~18-month bhavcopy walk remains (sufficient for the 60-bar gate — but the app should *say* that). | DATA-NOTES §2, §7 | History depth for new symbols is a maybe. |
| F3 **P2** | **No crash/observability** (by design — no servers). Consequence: silent real-world failures (like A1, which *no test could catch* because it's a satisfiability problem, not a logic error) are invisible. | architecture | The team found A1 by measurement, not by monitoring; the next such bug may go unfound. |
| F4 **P2** | **Low-RAM (2GB) device test (P12) never run**, and no accessibility/TalkBack pass is recorded — on a market where budget phones dominate. | plan §12, ENVIRONMENT.md | The target device is untested. |

---

## 2. Suggestions (prioritized)

**P0 — do before the next user sees the app**

1. **Fix the projection gate from the measurement, not from intuition.** Either re-derive
   the threshold from the observed score distribution (e.g., project when a *measured-move
   level* exists and is within N% of price — the variable that actually predicts touches),
   or replace the score gate with a distance-aware gate and *retire the 70*. Then add a
   gate-satisfiability test (assert the threshold is reachable on the fixture) so this bug
   class cannot return. *(Turns the biggest defect into the actual differentiator.)*
2. **Surface target distance as a first-class, honestly-labelled metric** — "the pattern's
   measured level is 2.4% away; on this app's tracked history, levels this close were
   reached 83% of the time within 5 bars (n=87), against a 10% base rate" — always with
   `n`, always labelled a chart event, never a profit. This is the app's real research
   output; it should be the product.
3. **Load an index series.** NSE publishes index EOD data on the same archive host that
   already works as the bhavcopy mirror; even a bundled weekly Nifty series would activate
   the Context factor's relative strength and enable a market-regime strip. If it cannot be
   sourced, *remove* "relative strength" from the documented score composition rather than
   scoring it as permanently-null.
4. **Compile `:app`/`:android-data` once in Google AI Studio** and run the 5 parse-only
   screens first. Every other item on this page is secondary to one real build.

**P1 — the next release**

5. **Re-verify bank ratios against live screener.in** (NIM/GNPA/CASA/PCR appear on today's
   bank pages); if present, promote them from `NOT_SOURCED` to `AVAILABLE` — the single
   highest-leverage fundamentals fix for the Indian market.
6. **Surface FII/DII** from the already-parsed shareholding table, and add the **quarterly
   results card** (sales/OP/EPS, QoQ + YoY) from the already-parsed 13 quarters. Both are
   data-on-the-floor, UI-missing.
7. **Add a "what fires, what doesn't" panel** to the Patterns tab: per-pattern candidate
   census on real data ("Bull Flag produced candidates on 4 of 41 tracked large caps — the
   detector is strict by design; here is the per-gate funnel"). Converts A5 from a defect
   into a trust feature.
8. **Education hub, three levels** (§3 below) — the user's explicit ask; starts entirely
   from content the repo already has (measurements, glossaries, ratio rationale strings).
9. **SME/liquidity gate**: warn or filter `SM` instruments below an ADV threshold; show
   cap-band (large/mid/small) from the bundled `IndexCohort` CSVs.
10. **Promoter-pledging verification path**: even if screener.in lacks it, teach *where to
    verify* (exchange shareholding-pattern filings, BSE/NSE disclosures) — and consider
    parsing NSE's free corporate-announcements feed for pledge disclosures.

**P2 — quality**

11. **Corporate-action-adjusted view toggle** (splits/bonuses from NSE's free corporate
    actions feed; demergers flagged as unadjustable). Keep raw as default.
12. **Monthly timeframe** and an ATR-based risk framing line on the Technicals tab.
13. **Journaling**: "my thesis / what would change my mind / review date" attached to the
    share brief; the user's own hit rate becomes a taught metric beside the app's.
14. **Second fundamentals source** (even a manual CSV import) so `divergences()` stops
    being permanently empty and the tab survives a screener.in block.
15. **Exchange event overlays**: bulk/block deals, promoter transactions, F&O ban, ASM/GSM
    — all free NSE endpoints, all high-signal for Indian retail.
16. **Peer compare** (2–3 stocks side by side, ratio cards aligned) — the best ratio
    teacher available.
17. **Tax & cost literacy module** (education only): LTCG/STCG, STT, holding period, and
    the round-trip cost stack that eats a 50% edge (STT + GST + brokerage + slippage).
18. **Fix doc drift**: README "12 patterns" → the real evaluated set (9) + glossary-only
    rows; `DetectorRegistry` KDoc count.
19. **Run the P12 low-RAM and a TalkBack pass**; add a silent-failure counter
    (e.g., "projection gate evaluated N times, fired 0" — visible in Settings → Data
    coverage) so the next A1-class bug self-reports.

---

## 3. New education ideas — three levels

Design constraints inherited from the app (non-negotiable): no advice language, no
probability claims, every number with `n` and an "as of", Hinglish where it helps, and
every lesson names what it does **not** establish. The app already has the raw material:
measured findings (50% control test, distance curve, MAE/MFE), glossary entries with
`doesNotPredict`, ratio `rationale` strings, and real fixtures.

### Level 1 — Beginner ("before your first trade")

1. **Guided first-look tour** on a sample stock (RELIANCE fixture): walk one stock end to
   end — "this is a price, this is a ratio, this is a red flag, this is what none of them
   can tell you."
2. **Jargon decoder (EN + Hinglish)**: EPS, P/E, ROCE, ROE, NIM, GNPA, CASA, PCR, market
   cap, large/mid/small cap, FII/DII, delivery %, 52-week high/low, circuit limit, SME,
   bull/bear market — each with "what it is / why it matters / what it can't tell you".
3. **"Two numbers, never one" lesson**: match score vs hit rate with the app's own worked
   examples; includes the 50% control test as the story "why no app can predict tomorrow"
   (E7).
4. **Pattern school**: for each of the 9 evaluated patterns — shape, how to draw it, what
   textbooks associate it with, what it does *not* predict, and its **invalidation**
   condition ("what would prove this pattern wrong"). Uses the authored `PatternShape`
   overlays as the illustration.
5. **Red flag school**: each flag gets a 3-line lesson with an anonymised real example and
   "what this does not prove" — e.g., a distribution day can also be an index-rebalance
   day (the app already says this in evidence strings; turn it into a lesson).
6. **Money before stocks**: emergency fund, never borrowed money, diversification
   (per-stock and sector caps), horizon matching (goal < 3 years ≠ equity), and the honest
   index-fund comparison ("most active investors underperform the index — here is the
   data").
7. **Indian tax starter** (education, not advice): holding period, LTCG vs STCG, STT, why
   the 1-year line matters — with the mandatory "verify current rates, rules change" note.
8. **"Why did this number disappear?" tour**: teaches the three-state availability model
   using the user's own screen ("D/E is missing for this bank because deposits *are* the
   business"). Turns every suppressed ratio into a micro-lesson.

### Level 2 — Intermediate ("reason, don't follow")

1. **Ratio reasoning drills**: three real ratio cards (from app data), question: "which
   business is higher quality and why?" Model answers cite the §7.2 priority order
   (ROCE → margin → leverage → earnings quality) — teaching the *order* is the lesson.
2. **Earnings-quality lab**: CFO vs PAT over 5 years, debtor days, inventory days, other
   income % — the app's own red-flag rules become the curriculum: "find the company where
   profit exists but cash doesn't."
3. **Bank/NBFC reading module**: NIM, GNPA/NNPA, CASA, PCR, stage-2, cost of funds —
   taught *with the honest caveat* that the app's sources don't publish them yet, plus
   where to find them (annual report, RBI financial stability report).
4. **The measurement curriculum**: what `n=30` means; hit rate ≠ win rate; base rates;
   expectancy = win% × avg win − loss% × avg loss; the Indian round-trip cost stack
   (~0.5–1%) and why it eats a 50% edge entirely.
5. **Corporate-actions case studies** from the app's own fixture: HDFCBANK 1:2,
   BAJFINANCE ~1:10, KOTAKBANK ~1:5, the Tata Motors demerger (TMCV/TMPV) — "why your
   chart shows a −90% day that was not a crash", and how to read split-adjusted vs raw.
6. **Delivery & volume analysis**: what delivery % means in the Indian cash market, how to
   read a distribution day, why volume confirms a breakout — all tied to the flags the app
   already computes.
7. **Sector lens modules**: reading an IT company (TCV, attrition, constant-currency
   growth), FMCG (volume vs price, working-capital days), pharma (R&D %, USFDA, product
   concentration) — the §7.3 overrides become interactive lessons.
8. **Thesis journaling template** (E5): "my thesis in 3 lines / what I expect / what would
   prove me wrong / review date" — attached to the share brief; after the window closes,
   the app asks "what actually happened?" and builds the user's *own* hit rate beside the
   app's.
9. **Methodology page**: where every number comes from, what the app cannot see, why Yahoo
   is flaky, what robots.txt compliance means, how staleness is shown. Teaches source
   criticism using itself as the example.

### Level 3 — Expert ("edge cases and the math")

1. **Calibration dashboard** (the repo's research, surfaced): hit rate by score bucket
   *including the backwards slope*, by target distance (83% @ ≤3% vs 10% base), by
   horizon (h1–h20), MFE/MAE distributions, expectancy by stop distance — all with `n`.
   This is the app's genuine research output and its best expert feature.
2. **Expectancy & sizing lab**: compute expectancy for a given stop/target from the app's
   measured MFE/MAE; cost-adjusted with the Indian cost stack; ATR-based risk units; a
   fractional-Kelly explainer with the honesty that most sizing heuristics are fragile.
3. **Regime & context analysis** (once B1 lands): ADX regime alignment, RS vs Nifty,
   drawdown vs trending regimes, sector relative rotation, and **market breadth computed
   on-device from the bhavcopy itself** (advancers/decliners — one CSV already contains
   the whole market).
4. **Backtesting literacy**: why this app is *not* a backtester; survivorship bias (the
   41-symbol fixture is survivors), look-ahead bias, overfitting, and the
   multiple-comparisons problem of 9 patterns × 41 stocks; how to read "n=310" correctly;
   what walk-forward means.
5. **Forensic red-flag deep dives**: receivables/revenue divergence, other-income %,
   capitalised interest, related-party transactions, auditor changes, promoter-pledging
   mechanics — and *where to verify each* in exchange filings (the app teaches the source,
   since it cannot fetch it).
6. **Valuation frameworks**: when P/E lies (cyclicals at peak earnings), the P/B–ROE
   linkage, EV/EBITDA theory (and why it is `NOT_SOURCED` here), reverse-DCF mental
   model, Graham's principles, Fisher's 15 points — all framed as education with the
   SEBI disclaimer.
7. **Statistical honesty**: Wilson confidence intervals on hit rates, Bayesian shrinkage
   for small `n`, multiple-testing correction, statistical vs economic significance — the
   antidote to "83%!".
8. **Indian market microstructure**: impact cost, ADV/liquidity screens, F&O influence on
   the underlying, cash–F&O basis, OI data sources, GSM/ASM mechanics, circuit limits,
   trade-to-trade.
9. **Portfolio construction**: correlation with short Indian histories, sector caps,
   drawdown budgeting, rebalancing rules, tax-aware rebalancing (harvesting LTCG within
   the ₹1.25 lakh annual exemption) — clearly labelled education, not advice.
10. **Build-your-own screener**: sort/filter the watchlist by the app's own ratios
    (ROCE > 20%, D/E < 0.5, FCF/PAT > 1) — teaching fundamentals through the act of
    screening, with every filter explaining what it selects *for and against*.

---

## 4. What I would measure next (research agenda)

1. **Gate satisfiability as a permanent test**: for every threshold that gates a user-visible
   feature, assert on the fixture that at least one sample crosses it. A1 was invisible to
   938 tests; a satisfiability test would have caught it.
2. **Score re-derivation**: replace the geometric-fit score's role in projection with the
   measured predictor (target distance), and re-run the walk-forward to confirm the
   distance curve holds out-of-sample.
3. **Live Yahoo probe from an Indian network** (P3 gate) and a live screener.in bank-ratio
   re-check (C2) — two measurements that would either promote real features or retire
   documented promises.
4. **Fire-rate census per pattern, on-device, over time** — the "what fires" panel (P1-7),
   computed from real usage rather than the one fixture.
5. **User-decision outcomes** (once journaling exists): does the app's track record, shown
   honestly, change user behaviour? That is the only metric that ultimately matters, and it
   is the one no competitor measures.

---

*Bottom line: the engine is honest and well-tested; the product around it is not yet
compiled, its headline gate has never fired, its primary score correlates backwards with
outcomes, and its education layer is a glossary rather than a curriculum. The measured
findings in this repo (the distance curve, the base rates, the cost drag) are worth more
than most of the pattern library — surface them, teach them, and fix the gate that silences
them.*
