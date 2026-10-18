# Accessibility and on-device observability

gap **F3** (silent real-world failures are invisible) and the code-level half of gap **F4** (no
accessibility/TalkBack pass is recorded).

This document records what was changed in code, and — separately and just as importantly — what
**cannot** be closed from a GitHub runner. The split is deliberate: `VERIFICATION.md` and
`ENVIRONMENT.md` both say the P12 low-RAM device test needs real 2 GB-RAM hardware, and a checklist
item marked "passed" that was never run on a device is the fabricated claim this app exists to
avoid.

---

## 1. The decision counter (F3)

### Why it had to be built

gap F3, verbatim: *"Silent real-world failures are invisible. The team found A1 by measurement, not
by monitoring; the next such bug may go unfound."*

A1 is the proof. The projection gate used to be `MatchScorePolicy.PROJECT_THRESHOLD = 70`; the
highest score the app's own detectors produced across 875 settled walk-forward candidates was **68**;
so the app's headline feature — a projected level and its breakout notification — had never executed
once. Nothing caught it, because **a gate nothing can cross and a gate that works produce identical
evidence**: no failing test, no crash, no log line. It was found by re-running the measurement
(`VERIFICATION.md` §2).

plan.md G1 rules out buying observability — no crash SDK, no servers, no third-party telemetry — so
the only affordable substitute is a tally the device keeps about itself.

### What is recorded

| Figure | Where it comes from |
|---|---|
| `evaluated` | one per **kept** match of a **canonical** scan — the candidates the engine had a verdict about |
| `fired` | how many of those the gate admitted (`ProjectionGate.blocker` returned `null`) |
| blocker breakdown | `NO_MEASURED_MOVE` / `DISTANCE_TOO_FAR` / `INSUFFICIENT_HISTORY` / `REGIME_NOT_ALIGNED` |
| `scans` | canonical scans that reached the stage where the gate would be asked |

The gate decides on **measured-move distance**, not score, so the tally records what the gate actually
decided and which condition declined it. `ProjectionGate.blocker` already returns that condition; the
counter stores its `name()` verbatim so the table cannot drift from the rule that produced it.

**What is deliberately not counted**, and why:

- *Candidates suppressed below the render threshold.* They never became a projection decision, and
  counting them would put geometry the reader was never shown into the denominator of a rate the
  reader is being asked to trust.
- *Exploratory Pattern-Explorer window re-scans* (`PatternScanOptions.isCanonicalScan = false`).
  Counting them would let a reader inflate the denominator by tapping chips, and an inflatable
  denominator cannot be alarmed by.

Both exclusions are stated on the screen rather than left implicit.

### The rule the figures are built to

**A counter is only useful if the number is surprising.** So every figure has to be able to answer
three questions, or it is not displayed:

1. what does it mean when it is **zero**?
2. what does it mean when it is **implausibly high**?
3. what does it look like when it is **merely working**?

Thresholds come from measurement, not taste, and are quoted with their sample sizes.

### For each figure: its zero, its high, its working

#### Figure 1 — `evaluated` / `fired` (the headline)

| State | On screen | Threshold and where it comes from |
|---|---|---|
| **zero, no scans** | "not measured yet" — a fresh install. Says what would change it. | — |
| **zero evaluations, scans > 0** | "never asked" — scans ran, no candidate reached the gate. Points at detection or history depth, **not** at the gate. | The two are separated by the `scans` counter. Without it, one number cannot tell them apart. |
| **zero fired, N ≥ 30** | **"not firing"** — the A1 shape, in a full sentence with the count. | `MatchScorePolicy.MIN_HIT_RATE_SAMPLES` (30) is the floor. Below it **no rate is quoted at all** and the screen says why. Reusing the existing constant rather than inventing a second, looser floor for the same honesty problem. |
| **fire rate > 25 %** | "firing more than measured" | `203 / 875 = 23 %` of the app's own settled walk-forward candidates had a level inside the 5 % ceiling — and that is *before* the regime and bar-count conditions cut the field. 23 % is a **ceiling**, not a target. The alarm sits at 25 %, above every figure the measurement produced, so a device behaving like the measured one cannot trip it. |
| **otherwise** | "working" | the 23 % band is quoted back with `n`, so the reader can check the claim against its source |

#### Figure 2 — the blocker breakdown

| Blocker | Zero means | Surprising state | Threshold |
|---|---|---|---|
| `admitted` | nothing has projected — see Figure 1 for which of the four readings that is | zero with N ≥ 30 evaluations | 30 (`MIN_HIT_RATE_SAMPLES`) |
| `DISTANCE_TOO_FAR` | nothing has been stopped for being too far | **rare**, not common | `672 / 875 = 77 %` of measured candidates sat outside the 5 % ceiling, so refusing mostly on distance is the gate *working*. The screen says so at ≥ 60 % of evaluations (the measured 77 % rounded **down**, so "commonest" can never be claimed about a device that actually refused mostly on regime). |
| `NO_MEASURED_MOVE` | no candlestick-class formation has reached the gate | this becoming the **commonest** reason | every candlestick detector (1–5) carries no measured move by spec DD-14, so a common dominance here means the *only* candidates reaching the gate are ones that can never project — the A1 shape wearing a different hat. |
| `REGIME_NOT_ALIGNED` | the regime gate has refused nothing | 100 % of declines with no distance declines | `RANGING` is aligned for a reversal pattern but not for a continuation one, and `UNKNOWN` is never aligned. All declines here means the regime condition alone is closing the gate. |
| `INSUFFICIENT_HISTORY` | — | **any non-zero count at all** | a **tripwire**, not a statistic: `DefaultPatternRepository` refuses a short series *before* a scan starts, so this bucket cannot be produced from the recording point. A non-zero count means that pre-check was bypassed — a bug worth catching. It is displayed rather than omitted precisely because that makes it useful. |

#### Figure 3 — `scans`

| State | On screen |
|---|---|
| zero | "No canonical scan has reached the projection gate on this device yet." |
| > 0 with `evaluated == 0` | the "never asked" reading of Figure 1 |
| > 0 with `evaluated > 0` | counted, with the sentence that it is the denominator |

#### Figures deliberately **not** shown

"average distance from the close" and "scans per day" were both considered and left out. Neither has
a defensible *surprising* state derived from a measurement this repository holds, and the rule at the
top of the section says a figure without one is not displayed.

### It is not telemetry

Local storage only. There is no `upload`, no `report`, no `sync` method on
`DecisionCounterRepository`, and no parameter on it that could carry a symbol, a price or a date out
of the device — the shape is what makes the claim checkable rather than reassuring. The screen says
it in words too: *"These counts live in this app's own database on this phone. Nothing in this
section is uploaded, and there is no server to upload it to — no account, no analytics service, no
crash reporting."*

### The reset, and why it exists

A counter a reader cannot clear becomes a permanent claim about a session they cannot remember.
"0 fired" is a statement about *this install since it began*, and without a reset there is no way to
make the number mean *"since I did the thing that should make it fire"*. A figure that cannot be
cleared is not observability, it is a verdict.

The button states its consequences **before** it is pressed, and the confirmation is a typed
`DecisionCounterReset` rather than an `Int`, because the two interesting outcomes are "there was
nothing to clear" and "that was cleared" — neither is well served by a number the screen has to
interpret. Clearing touches nothing else: tracked symbols, stored bars, detections and the track
record are all outside `decision_counter`.

---

## 2. Accessibility (F4) — the code-level half

### Fixed in this change

| Fix | Where | Why it was a defect |
|---|---|---|
| Company name no longer capped at 2 lines | `ui/search/SearchScreen.kt` | `maxLines = 2` + ellipsis truncated the company name **at exactly the moment it matters**: the 200 % font scale a low-vision reader has asked for. Indian company names run long, so it was already tight at 100 %. The `Column` carries `weight(1f)`, so the text now wraps and the row — which is also the add-to-tracked target — grows instead of shrinking. |
| 48 dp minimum on the module header | `ui/education/EducationScreen.kt` | `titleMedium` (24 sp line) over `bodySmall` (18 sp line) is **42 dp** — six dp short of the accessibility floor. The two lines that make it *look* generous are what makes the arithmetic come out wrong. Six dp is invisible to a sighted finger and is the difference between a tap landing and missing on a budget phone held one-handed. |
| 48 dp minimum on a lesson row | `ui/education/EducationScreen.kt` | same reason; the guarantee is written down rather than left to a font scale that happens to add up today. |
| State is a **word**, never a colour | `ui/settings/DecisionCounter.kt`, `DecisionCounterSection.kt` | This is the standard the Accuracy screen set by keeping `ProvenanceBadge` a plain `Card`. A counter card is exactly where a red/green pill is most tempting — "not firing" and "working" differ only by a tint otherwise, which fails in greyscale, for a colour-blind reader, and on any screen where the palette shifts. The card uses **one** container colour and carries no state tint at all. |
| The whole counter card is one TalkBack node | `ui/settings/DecisionCounterSection.kt` | `clearAndSetSemantics` replaces ~40 nodes of heading, counts, rows and readings with one spoken paragraph. Read linearly, the rows are a list of numbers with no connective tissue — "12", "refused", "30 % of evaluations" — and the sentence that makes them mean anything is `verdictLine()`, which a screen reader would otherwise deliver somewhere in the middle of the list. |
| Nothing in the counter hard-codes an `sp` size | `ui/settings/DecisionCounter.kt`, `DecisionCounterSection.kt` | every `Text` uses a `MaterialTheme.typography` style, which is what scales. Figures are stacked pairs rather than fixed-width columns so a 200 % scale makes the row taller rather than truncating the number. |

### Audited and found already correct — recorded so it is not re-audited

| Check | Result |
|---|---|
| Icon-only buttons | **none exist.** The one glyph-only control in the tree is the education module's chevron (`contentDescription = null`), which is correct: it is decorative, and the clickable `Row` parent carries `"<module>, N lessons, expanded/collapsed"`. The microphone is a labelled `FilledTonalButton` rather than an `IconButton` precisely because `material-icons-extended` is not a dependency — see its own KDoc. |
| Colour as the sole carrier | `RedFlagAccess`'s `severitySummary` renders `"1 high, 2 medium, 1 low"` in words beside the severity disc, so the disc is an accent and not the claim. `ChangeText` already ships `▲`/`▼` plus a spoken "up"/"down" through `clearAndSetSemantics`. `AsOfChip`, `SourceBadge`, `MatchScore` and `StockContextChips` all carry a `contentDescription` that names the state. |
| Touch targets on chips | Material 3 1.3 (BOM 2025.06.00) applies `minimumInteractiveComponentSize()` to `FilterChip`/`AssistChip`, so the 48 dp floor already holds. **Not re-verified on a device** — see the checklist. |
| Hard-coded `sp` in screens | none. The only literal `sp` values in `:app` are in `ui/theme/Type.kt`, which is where they belong. |

### Left alone, and reported rather than fixed

These are inside files owned by other workstreams. They are **not** fixed by this branch:

| Item | Where | Note |
|---|---|---|
| `maxLines = 2` + ellipsis on a stock row | `ui/stocklist/StockListScreen.kt:349` | same defect class as the search row. Reported, not touched. |
| `maxLines = 1` + ellipsis on a match header | `ui/patterns/PatternsScreen.kt:626` | ditto |
| `maxLines = 1` + ellipsis on an explorer chip label | `ui/patterns/PatternExplorerSection.kt:190` | single-word timeframe labels; likely harmless, unverified |
| `maxLines = 1` on chart axis labels | `ui/chart/PriceChart.kt:643,669,707` | deliberate — a fixed 44 dp gutter cannot grow. **Left on purpose**, and it is the one place where a font-scale increase costs legibility, so it is on the manual checklist. |
| `maxLines = 1` on the top app bar title | `MainActivity.kt:180` | longest title is "Tracked symbols"; conventional, and the screen's own heading carries the full name |

---

## 3. What a human must still check on real hardware

**None of the following has been run.** A GitHub runner compiles this code; it is not a phone, and
`ENVIRONMENT.md` records that the P12 low-RAM device test cannot be reproduced here. Every item below
is *unverified*.

### TalkBack, on a real device

- [ ] Settings → Data coverage → Decision counter reads as **one coherent paragraph**, not as a list of loose numbers.
- [ ] Every screen: swipe through with TalkBack and confirm no node is announced as "unlabelled" or as a bare number.
- [ ] The education module chevron is **not** announced (it is decorative and its parent carries the state).
- [ ] `Back` on the top app bar and every chip announce their selected state.
- [ ] Focus order on the counter card reaches the clear button and it is reachable by swipe, not only by Tab.

### Font scaling

- [ ] Set the system font to **200 %** and walk every screen. Record any truncation, overlap, or a control that has grown past its card.
- [ ] Confirm the counter card's breakdown rows wrap rather than clip at 200 %.
- [ ] Confirm the chart axis labels are still legible at 200 % (they are capped to a fixed gutter by design — see above).
- [ ] Confirm `Show layout bounds` / `Show taps` are OFF before recording any judgement about target size.

### Touch targets

- [ ] With a ruler or `adb shell wm density`, confirm the education module header is ≥ 48 dp after the fix.
- [ ] Confirm chips, `AssistChip`s and `TextButton`s meet 48 dp **on this device's Material version** rather than on paper.

### Touch target size vs. reachability on a budget phone

- [ ] The P12 low-RAM device test — **2 GB RAM, cold offline launch, watchlist screen, rotation, dark/light, glass toggle** — is an owner task and has **not** been run. A GitHub runner is not 2 GB-RAM hardware. Nothing in this repository should be read as claiming otherwise.

### Colour independence

- [ ] Switch the device to greyscale (or Android's "Colour correction" / accessibility colour inversion) and confirm the counter card's state is still legible — it should read identically, because the state is a word and the card carries one container colour.
- [ ] Confirm the same for the red-flag severity discs, the `▲`/`▼` change arrows, the as-of chips and the source badges.

### The counter itself, on a device

- [ ] Install, track one symbol with ≥ 60 daily bars, run a scan, and confirm Settings shows a non-zero `evaluated`.
- [ ] Clear the counts and confirm every figure returns to zero and the card reads "not measured yet" rather than "not firing".
- [ ] Clear twice and confirm the second attempt says "there was nothing to clear" and is not styled as an error.
- [ ] Confirm the counts survive an app restart (they are Room rows) and are **not** cleared by "Clear cached data".