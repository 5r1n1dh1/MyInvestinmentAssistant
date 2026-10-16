# planv2.md — "Fix everything" plan

> **Status:** Plan only — **no code is written yet.** This document is the specification
> the next phase executes against.
> **Predecessor:** `plan.md` (v1, frozen as history) · **Gap register:** `gaps.md`
> **Owner decisions (interview 2026-10-11):** minSdk raised to **31** · repo goes
> **public** (unlimited Actions minutes) · live probes are **on-demand only**
> (`workflow_dispatch`) · education content ships in **English** (Hinglish stays for
> voice search).

---

## 1. Mandate

1. Fix every gap in `gaps.md` — honestly, measurably, test-covered.
2. Rebuild the UI as a beautiful, extremely interactive **glassmorphism** experience,
   medium blur everywhere.
3. **Validate exclusively in GitHub**: build, test, verify. `gh` is authenticated as
   `5r1n1dh1`. **Nothing is built locally** (AGENTS.MD rule 4 — no Gradle, no APK,
   no SDK anywhere on this machine).
4. Spawn parallel subagents per workstream; module boundaries already exist to keep
   them from colliding.

### 1.1 What "fixed" means (three classes — honesty is non-negotiable)

| Class | Meaning | Examples |
|---|---|---|
| **A — code-fixable** | Fixed in code, covered by tests, verified in CI | projection gate, target-distance signal, FII/DII, quarterly card, docs drift |
| **B — source-limited** | Best-effort source probed from GitHub; if unreachable, the app says so and teaches *where to verify* — the existing declared-gap pattern, never a silent blank | promoter pledging, goodwill, bank ratios (pending live re-check), exchange event feeds |
| **C — deferred by design** | Explicitly out of scope with rationale | real-time data, broker integration, LLM features, multi-user |

No gap is "fixed" by weakening a compliance invariant (§7).

### 1.2 Non-goals (carried from plan.md §1.3, still binding)

No buy/sell verdicts · no probability of profit · no price targets · no real-time or
intraday · no accounts/servers · no LLM inference · no advice language anywhere
(`AdviceLanguage.FORBIDDEN` scan stays green — now also over all new education
copy).

---

## 2. Environment & quota discipline

| Resource | Status | Rule |
|---|---|---|
| Repo | `5r1n1dh1/MyInvestinmentAssistant`, currently **private** → **public** (owner decision) | Pre-flight: `gh secret list` + full-history key scan **before** flipping visibility (no secrets exist by design — G1 — but verify) |
| Actions minutes | Unlimited after going public | Still cap every job: `concurrency: cancel-in-progress`, `timeout-minutes`, Gradle cache, path filters so JVM-only PRs skip the APK job |
| API rate limit | 5,000 req/hr authenticated | Probes batch requests; no per-symbol probing loops |
| Artifacts | 500 MB / 90 days | Reports are small markdown; APKs zipped |
| `gh` | Logged in as `5r1n1dh1` | All verification via `gh run list/watch/view --log-failure`, `gh api rate_limit` |

**Pre-flight (Wave 0, before any work):**

```bash
gh secret list                                    # expect empty
git log --all -p | grep -iE 'api[_-]?key|secret|password' # expect no hits
gh repo edit 5r1n1dh1/MyInvestinmentAssistant --visibility public --accept-visibility-risk-consequences
gh api rate_limit --jq '.resources.core.remaining'
```

---

## 3. Workstreams

Each workstream: **one PR**, module-scoped, with its tests. File ownership is
listed so parallel agents cannot collide.

### W1 — Projection gate re-derivation (gaps A1, A2, A3) · P0 · `:domain`

**The bug:** `PROJECT_THRESHOLD = 70` has never been crossed (max observed 68/875).
**The finding:** score correlates *backwards* with outcomes; **target distance**
correlates forward (≤3% → 83% touched within 5 bars vs 10% base rate).

**Fix:**
- New `domain/pattern/ProjectionGate.kt`: projection + alert fires when
  (a) a measured-move level exists, (b) `distance = |level − close| / close ≤
  DISTANCE_MAX` (calibrated, default 5%), (c) `barsSufficient`, (d) `regimeAligned`.
  **Score no longer gates projection** — it remains a display-only shape-fit metric.
- `domain/pattern/ProjectionCalibration.kt`: the distance-bucket × horizon hit
  table **computed from the committed fixture** (17 large caps, 372 bars, walk-forward,
  5-bar stride) — a test must reproduce the table; no hand-typed constants.
- `ProjectionGateSatisfiabilityTest`: asserts ≥ K samples cross the gate on the
  fixture **and** that the end-to-end path fires at least once. This test class is
  the permanent guard against the A1 bug (a gate nothing can satisfy).
- UI ordering switches to **distance-aware** (nearest calibrated level first);
  score chip stays, labelled shape fidelity only.

**Files:** `domain/pattern/{ProjectionGate,ProjectionCalibration,ProjectionGateTest}.kt`,
`domain/pattern/PatternEngine.kt` (gate wiring), `app/.../ui/patterns/*`.
**Acceptance:** gate fires ≥ 1× on fixture; score bucket vs hit-rate table renders
with `n`s; old `PROJECT_THRESHOLD` removed or retired as display-only.

### W2 — Track-record depth (gaps A4, A5, A6, B1-partial) · P1 · `:domain`, `:app`

- `domain/accuracy/Calibration.kt`: per-bucket hit rates (distance × horizon),
  base rates, MFE/MAE distributions computed **from the device's own stored
  outcomes**; until device data accrues, the fixture-derived "published research"
  table is shown, labelled as such.
- **"What fires, what doesn't" panel** in the Patterns tab: per-pattern candidate
  census (from `DetectorStrictnessCensus` data) — "Bull Flag produced candidates on
  4 of 41 tracked large caps; strict by design, here is the per-gate funnel".
- Hit-rate teaching card: why the overall rate is ~16% (measured-move distance vs
  median 5-bar excursion) — rendered, not hidden.
- n=30 gate kept; add "samples until your next reportable rate" counter; per-symbol
  aggregate (n=62) becomes the headline per-stock number, labelled as cross-pattern.

**Files:** `domain/accuracy/*`, `app/.../ui/accuracy/*`, `app/.../ui/patterns/PatternsScreen.kt`.
**Acceptance:** calibration table renders with `n` per cell; census panel shows real
candidate counts; no rate without `n` (existing `HitRateText` guard still green).

### W3 — Market context (gaps B1–B4) · P0 · `:data`, `:domain`, `:android-data`

> **RESOLVED BY PROBE 2026-10-16** (run 38949742442, [docs/LIVE-PROBE.md](docs/LIVE-PROBE.md)).
> The index-source decision below is no longer a question — the probe ran it.

- **Index series — DECIDED: Yahoo first, NSE CSV shapes dropped.** All three NSE index-CSV shapes
  (`ind_close_all` on `archives`, on `nsearchives`, and on the `products` path) return **404**. But
  the Yahoo cookie+crumb handshake **executes**: `fc.yahoo.com` seeds a cookie, `getcrumb` returns a
  crumb, and `v8/finance/chart/RELIANCE.NS` answers **200** on the same cookie+crumb pair. Yahoo
  serves `^NSEI`, `^NSEMDCP50` and `^NSEMDCP150` through that endpoint, so the benchmark series is
  obtainable. Implement `NseIndexSource` as a **Yahoo-backed** `IndexSource` (the NSE CSV shapes stay
  in the candidate constant list, now with a recorded 404 against them, so a future shape that does
  answer is a one-line change). The bundled seed series remains the offline floor.
- **This also closes the P3 gate**, which `docs/VERIFICATION.md` had carried as permanently open.
  Until W3's code lands, `benchmarkCandles = null` remains the *correct* behaviour — this finding
  says the path has an exit, not that the honest fallback was wrong.
- **Breadth, computed on-device from the bhavcopy itself** (free, reliable — and now the
  load-bearing piece, since it needs no external source at all):
  advancers/decliners/unchanged, new 52-week highs/lows, % above 50-DMA →
  `domain/technical/MarketBreadth.kt` + a "Market pulse" card on the watchlist screen.
- **Relative strength vs index** activates the moment an index series loads
  (`benchmarkCandles` wiring in `RealPatternsDataSource` / `DefaultPatternRepository`
  — the seam already exists; the hardcoded `null` is replaced by a repository read).
- **Sector view**: aggregate per-sector stats from the persisted screener.in industry
  taxonomy; sector median cards beside the stock's ratios.
- **Cap-band chips** from the bundled `IndexCohort` CSVs ("NIFTY MIDCAP 150" + a
  "small-caps draw down harder" note).
- **SME liquidity gate**: median daily turnover over the stored window below a
  threshold → "thin trading" warning on the stock header (SME = `SM` series).

**Also measured:** `nsearchives.nseindia.com` — NSE's *documented* primary — fails from GitHub's
runners with `HTTP/2 INTERNAL_ERROR` on every attempt, while `archives.nseindia.com` answers 200.
The app's mirror choice is vindicated; do not "fix" it toward the documented primary.

**Files:** `data/price/{IndexSource,NseIndexSource}.kt`, `domain/technical/MarketBreadth.kt`,
`android-data/.../repo/DefaultPatternRepository.kt`, `app/.../ui/stocklist/*`, `app/.../ui/detail/*`.
**Acceptance:** market pulse card renders from the fixture; RS factor reports
"unscored — no benchmark" when no index loads and a real value when it does; SME
warning fires on a synthetic SME fixture; the index source reports its failure reason
rather than fabricating a series.

### W4 — Fundamentals (gaps C1–C8) · P1 · `:data`, `:domain`, `:app`

> **Bank-ratio and corporate-event questions are now DECIDED by probe** (run 38949742442,
> [docs/LIVE-PROBE.md](docs/LIVE-PROBE.md)). Both Class-B branches below are closed.

- **Bank-ratio live re-check (Class B) — DECIDED: REJECTED. The `RatioId`s stay `NOT_SOURCED`.**
  The probe fetched the robots-permitted `screener.in/company/HDFCBANK/consolidated/` page
  (200, 218,206 bytes) and a naive label grep reported `Gross NPA`, `Net NPA` and `CASA` as
  "present". Direct inspection says otherwise: those labels sit inside **60 paywalled
  `highlight-cell` elements with empty values**, and the standalone ratio rows carry labels with no
  numbers at all — while `ROCE`, which the app already sources, renders a real `7.02`.
  **Do not promote.** No parser work follows; the "where to verify" branch below is the deliverable.
  *The transferable lesson, now recorded in LIVE-PROBE.md §3: a label match is not a value. Any
  future `NOT_SOURCED → AVAILABLE` promotion must pin a golden fixture asserting a parsed number.*
  **Deliverable instead:** every abstaining bank ratio carries a "where to verify" action (bank
  annual report; RBI Financial Stability Report for the system view), and W6's "Reading a bank"
  module teaches it with this fact stated.
- **FII/DII (Class A):** extend `DerivedMetrics` + new `RatioId`s
  (`FII_HOLDING`, `DII_HOLDING`, `FII_TREND`) from the **already-parsed**
  `shareholding` table — data on the floor today, UI missing.
- **Quarterly results card (Class A):** the 13 parsed quarters → QoQ/YoY cards for
  Sales, Operating Profit, EPS + sparkline.
- **Promoter pledging & goodwill (Class B):** keep the honest abstention; add a
  per-flag **"Where to verify"** action (exchange shareholding-pattern disclosures,
  annual report) and an education-hub lesson on reading them.
  **Probe verdict: NSE corporate-announcement endpoints 404 from GitHub's network** (announcements,
  bulk deals, block deals all 404 on the archives host; the `www.nseindia.com` variants fail with
  the same HTTP/2 INTERNAL_ERROR as `nsearchives`). No pledge scanner follows — this is a
  documented gap, and the ASM/GSM lists are unreachable for the same reason.
- **Exchange event overlays (Class B) — DECIDED: not reachable.** Bulk/block deals and ASM/GSM all
  fail from this network (same table as above). Gap **B3** closes as Class-B "where to verify".
- **Corporate-action-adjusted view (Class B/A):** the NSE corporate-actions feed is unreachable
  (404), so this stays flag-only. The app's existing behaviour is already the right one: four real
  corporate actions in the committed fixture (HDFCBANK −50.4%, BAJFINANCE −89.9%, KOTAKBANK −80.3%,
  TATAMOTORS −40.2%) are detected from *price discontinuity* and flagged, never silently adjusted.
  An "adjusted view" toggle would need a feed that answers; do not build one against a 404.
- **Second fundamentals source (Class A, minimal):** `FundamentalsSource` interface
  + manual CSV/JSON import so `divergences()` can become non-empty without a second
  scraper.

**Files:** `data/fundamentals/*`, `domain/ratios/RatioPriority.kt`, `domain/redflag/fundamentals/*`,
`app/.../ui/fundamentals/*`, probe workflow (W8).
**Acceptance:** FII/DII + quarterly cards render on the five golden fixtures; every
abstaining flag carries a "where to verify" action naming the annual report / RBI FSR;
divergence card no longer permanently empty when a second source is imported; **no bank
ratio appears as `AVAILABLE` without a fixture-pinned parsed value.**

### W5 — Technicals (gaps D1–D3) · P1 · `:domain`, `:app`

- **Monthly timeframe:** derived from daily; hard gate ≥ 24 monthly bars (only
  satisfiable with the 500-bar Yahoo history — a bhavcopy-only install reports
  "not enough history", honestly).
- **EMA(20/50) crossover** display, **MFI(14)**, and the **ATR risk line**:
  "a 2% risk stop sits ₹X away (≈ 1.3× ATR)" — technicals teaching position sizing.
- **Delivery %:** stays optional; add a "delivery source status" line under
  Settings → Data coverage.

**Files:** `domain/technical/{Indicators,CandleResampler}.kt`, `app/.../ui/technicals/*`.
**Acceptance:** monthly gate test (24-bar satisfiability); EMA/MFI golden values;
ATR line renders on a fixture with the ₹ distance.

### W6 — Education hub (gaps E1–E7) · P1 · `:domain` (content), `:app` (UI)

All curriculum content lives in `domain/education/` as **data** — JVM-testable and
covered by the compliance scan. Three levels:

- **Beginner:** guided first-look tour (RELIANCE fixture), jargon decoder (EPS, P/E,
  ROCE, NIM, GNPA, CASA, PCR, market cap, FII/DII, delivery %, circuit limit…),
  "two numbers, never one" lesson, pattern school (shape + how to draw + textbook
  association + **invalidation condition**), red-flag school with anonymised real
  examples, money-before-stocks, Indian tax starter (LTCG/STCG/STT — education
  only, "rules change, verify" note), "why did this number disappear?" three-state
  tour.
- **Intermediate:** ratio-reasoning drills (model answers cite the §7.2 order),
  earnings-quality lab (CFO vs PAT, debtor days, inventory days), bank/NBFC reading
  module (with the honest "our sources don't publish these yet" caveat), measurement
  curriculum (n=30, hit rate ≠ win rate, base rates, expectancy, the Indian
  round-trip cost stack), corporate-actions case studies (HDFCBANK 1:2, BAJFINANCE
  ~1:10, Tata Motors demerger — from the app's own fixture), delivery & volume
  analysis, sector lens modules, thesis journaling template, methodology page.
- **Expert:** calibration dashboard (W2's research output), expectancy & sizing lab,
  regime & context analysis (once W3 lands), backtesting literacy (survivorship,
  look-ahead, overfitting, multiple comparisons), forensic red-flag deep dives with
  verification sources, valuation frameworks, statistical honesty (Wilson intervals,
  shrinkage, multiple testing), Indian microstructure, portfolio construction,
  build-your-own screener (filter the watchlist by the app's own ratios).

**Schema additions:**
- `GlossaryEntry.invalidation: String` — "what would prove this pattern wrong"
  (gap E4). **Landed, and stricter than planned** (PR #3): the field is **required with no
  default**, so the compiler forces all 42 rows to state it — the "existing entries get a
  default, a test asserts non-blank" idea below was weaker, because a default is a sentence
  nobody chose. All 42 now carry a specific break-level condition; the 9 evaluated patterns
  name an observable price event. `PatternCatalogTest` asserts the sentences are distinct
  (max reuse ≤ 2 rows), exceed a 40-character substance floor, and pass the banned-language
  sweep.
- `Curriculum.kt`: `Level`, `Module`, `Lesson(title, body, doesNotEstablish,
  relatedPattern/Flag/Ratio ids)`; a test walks every lesson through
  `AdviceLanguage.requireClean` and `HonestyContract.requireNotProbabilityClaim`. **Landed**
  (PR #3) with a registration contract: content files self-install via `Curriculum.register`
  in their own `init`, `register` keys on `Module.id` and **replaces in place**, so a double
  registration cannot duplicate or reorder a row.
  **Known follow-up (not done):** `PatternExplorerSection.kt` renders the other four glossary
  fields but not the new `invalidation` — the dialog needs a "what would prove this wrong"
  row. That is `:app` UI work, not schema work.

**Journaling (gap E5):** Room entity `journal_entry(symbol, thesis, expectation,
falsification, reviewDate, createdAt)` + outcome capture + **personal hit rate**
under the same n=30 honesty gate, shown beside the app's own rate.

**Peer compare (gap E6):** 2–3 symbols side-by-side; cards align by `RatioSlot`
identity, not position (sectors differ).

**Base-rate module (gap E7):** the control-test numbers (everything ~50%) as
committed, test-pinned data — the crown jewel moves from README into the app.

**Files:** `domain/education/**`, `android-data/.../db/{Entities,Daos,Migrations}.kt`,
`app/.../ui/education/**`, `app/.../ui/detail/*` (compare), `app/.../ui/journal/**`.
**Acceptance:** every lesson passes the compliance scan; quizzes score locally;
journal entries persist across process death; personal hit rate withholds below n=30.

### W7 — Glassmorphism UI (user directive) · P0 · `:app` (+ `libs.versions.toml`)

- **minSdk 26 → 31** (owner decision): true `RenderEffect` blur everywhere; the
  tradeoff (dropping API 26–30 devices, including some 2GB phones) is accepted by
  the owner and recorded here. Low-RAM work (W10) still applies — Android 12+
  devices with 2GB exist.
- **Design system** `ui/theme/glass/`: `GlassSurface` composable — medium blur
  (radius ≈ 20 dp, `CLAMP`) behind a translucent fill (≈ 12–18%) + 1 dp hairline
  stroke; glass tokens for cards, sheets, bars, dialogs, navigation; dark-first
  palette (glassmorphism needs content behind it) with a light frosted variant.
- **Extremely interactive:** animated score ring, spring-animated evidence-card
  expansion, chart crosshair + pattern-overlay toggle, bottom-sheet glossaries,
  pull-to-refresh, haptics on key actions, shared-element transitions
  list→detail, staggered list entrances, micro-charts in watchlist rows.
- **Performance (hard rules):** no blur inside `LazyColumn` items (blur fixed
  backgrounds/headers only); blurred bitmaps cached; `ActivityManager.isLowRamDevice`
  → auto-reduced; Settings → "Glass effects: Full / Reduced / Off" (default Auto);
  emulator frame-time regression check in CI.
- **Accessibility:** AA contrast by pairing body text with solid mini-cards over
  glass; TalkBack labels on every interactive glass element; animated surfaces expose
  state descriptions; up/down never by color alone (existing `▲▼` rule holds).
- **Every screen's copy is re-scanned** by the compliance tests (UI strings changed
  in a redesign must not smuggle in advice language).

**Files:** `app/src/main/kotlin/com/mia/app/ui/theme/glass/**`, every screen under
`app/.../ui/**`, `gradle/libs.versions.toml`, `AndroidManifest.xml` (minSdk note).
**Acceptance:** APK builds in CI; glass toggle works; frame-time check green on the
emulator; compliance scan green over the new strings.

### W8 — GitHub CI & live verification · P0 · `.github/workflows/`

- `ci.yml` (push/PR): JVM tests (`:domain`, `:data`) → Android compile → lint →
  `assembleDebug` artifact. Path filters: JVM-only changes skip the SDK job.
  Concurrency cancel + `timeout-minutes` on every job.
- `verify-live.yml` (**`workflow_dispatch` only** — owner decision): probes, in
  order, each writing pass/fail + evidence into one markdown report artifact:
  1. Yahoo cookie+crumb handshake (does the P3 path answer at all?)
  2. screener.in bank ratios on HDFCBANK (decides W4's Class-B promotion)
  3. NSE archive host: index history files, corporate actions, bulk/block deals,
     ASM/GSM lists (decides W3/W4 Class-B integrations)
  4. Legacy delivery file freshness
  The job is read-only against sources, robots-compliant (only the permitted
  screener.in URL shape), and rate-limited.
- `monthly.yml` (cron `0 9 1 * *`): full release assemble + test report upload.
- **Verification commands** (used by every agent and by the owner):
  `gh run list --limit 10` · `gh run watch <id>` · `gh run view --log-failure`
  · `gh run download <id> -n report` · `gh api rate_limit`.

**Acceptance:** a push to a workstream PR goes green end-to-end in GitHub; the
probe report names each endpoint's status with bytes received; no job exceeds its
timeout.

### W9 — Documentation (gap A7, F-adjacent) · P2 · root

- README: "12 patterns" → the real evaluated set (9) + glossary-only rows; minSdk 31;
  public-repo note; new features (market pulse, education hub, journaling).
- `DetectorRegistry` KDoc count fix; `VERIFICATION.md` updated after the first real
  compile (Wave 0) and again after each workstream lands; `plan.md` marked as v1
  history pointing here.

### W10 — Device & accessibility pass (gaps F3, F4) · P2 · CI + checklist

- Emulator smoke suite in CI: cold offline launch, watchlist screen, rotation,
  dark/light, glass toggle. **A real 2GB device cannot be reproduced on a GitHub
  runner — that test stays with the owner** (recorded honestly, not faked).
- Silent-failure counter (Settings → Data coverage): "projection gate evaluated N
  times, fired M" — the next A1-class bug self-reports.
- TalkBack pass checklist; crash-free-session target noted (no crash SDK by design —
  G1 — so the counter is the observability).

---

## 4. Waves, dependencies & subagent roster

```
Wave 0  (1 agent)   M0 baseline: first-ever compile of :app/:android-data in CI,
                    fix fallout. GATE: main green in CI before anything else.
Wave 1  (5 agents)  W1 projection gate · W3 market context · W6 education content
                    (domain data only) · W7 design-system tokens · W9 docs
Wave 2  (5 agents)  W2 accuracy surfacing (needs W1 API) · W4 fundamentals ·
                    W5 technicals · W7 screens part 1 (watchlist, search, detail)
Wave 3  (4 agents)  W6 education UI + journaling · W7 screens part 2 (all remaining) ·
                    W8 CI hardening · W10 emulator suite
Wave 4  (1 agent)   Integration: full CI run, probe report, verification summary,
                    release PR to main.
```

**Collision rules:** W1→W2 is the only hard data dependency (W2 consumes W1's gate
API). W7 touches every UI file — screen work starts only after the domain APIs it
reads are stable (Wave 2+). Each agent works on its own branch, opens its own PR,
and **never pushes to `main` directly**. A merge requires CI green + the compliance
scan green.

**Agent brief template (every subagent receives):**

```
You are working in repo 5r1n1dh1/MyInvestinmentAssistant (branch: <workstream>).
Scope: <W#> — <files you own>. Do NOT touch files outside your scope.
Rules: never run Gradle locally (no SDK here — AGENTS.MD rule 4); every user-facing
string must survive AdviceLanguage.findForbiddenPhrases and HonestyContract;
every new ratio/flag/rate must carry n, an "as of", and a source; a blank must
never read as a value (RatioState model); commit messages state the measurement
behind the change.
Verification: push the branch, open a PR, watch CI with `gh run watch`, paste the
run URL in your final report. If CI is red, fix and re-push.
```

---

## 5. Compliance invariants (non-negotiable, machine-checked)

1. `AdviceLanguage.FORBIDDEN` scan extended to `domain/education/**` and all new
   UI strings — zero hits required.
2. Every hit rate renders with `n` and is withheld below 30 samples.
3. Every projection is labelled pattern geometry; every calibration number is
   labelled a chart event, never a profit.
4. Every fundamentals figure carries source + "as of"; every abstaining rule says
   why and where to verify.
5. The SEBI disclaimer ships on first launch and in Settings — unchanged by the
   redesign (glassmorphism must not dilute it).

---

## 6. Risks & mitigations

| Risk | Mitigation |
|---|---|
| minSdk 31 drop-off vs the 2GB target market | Owner-accepted; low-RAM mode + glass toggle retained; W10 measures on emulator |
| Index endpoints don't answer from GitHub's network | Probe job reports it; RS stays honestly unscored; breadth (from bhavcopy) still ships — the market-pulse card never depends on the index |
| screener.in blocks GitHub runner IPs | Probe reports it; bank ratios stay `NOT_SOURCED`; "where to verify" lesson ships regardless |
| First-ever `:app` compile surfaces a wall of errors (Wave 0) | M0 is a dedicated gate — no workstream starts until main compiles in CI |
| Redesign copy drift into advice language | Compliance scan is a CI test over the final strings, not a review habit |
| Blur cost on low-end Android 12+ devices | No blur in lists; cached bitmaps; auto-reduced on `isLowRamDevice`; frame-time check in CI |
| Education content bloat (scope) | Lessons are data; each level ships in its own PR; quizzes are local-only |

---

## 7. Definition of done

- [ ] `ci.yml` green on `main`: JVM suites, Android compile, lint, APK artifact
- [ ] `verify-live.yml` run manually at least once; report attached to the release PR
- [ ] Projection gate fires ≥ 1× on the fixture (satisfiability test in CI)
- [ ] Target-distance calibration table rendered with `n`s; score no longer gates projection
- [ ] Market pulse card (breadth from bhavcopy) on the watchlist; RS active when an index loads
- [ ] FII/DII + quarterly cards on all five golden fixtures
- [ ] Every abstaining flag carries "where to verify"
- [ ] Education hub: 3 levels, all lessons compliance-scanned, quizzes local
- [ ] Journaling persists; personal hit rate withholds below n=30
- [ ] Peer compare aligns by RatioSlot across sectors
- [ ] Glassmorphism on every screen; toggle works; frame-time check green
- [ ] minSdk 31 in manifest, gradle, README, ENVIRONMENT.md
- [ ] README/VERIFICATION.md/DetectorRegistry docs match reality
- [ ] Emulator smoke suite green; TalkBack checklist done
- [ ] `gh api rate_limit` healthy at close; no job timed out in the final run

---

*Sequence in one line: **compile it in CI first (Wave 0), then fix the gate that
never fired (W1), then context (W3), then fundamentals/technicals (W4/W5), then
education (W6), then glass (W7) — with verification living in GitHub the whole way.***
