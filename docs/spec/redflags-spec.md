# Red Flags Specification

> **Status:** normative for `domain/redflag/RedFlagEngine` (plan.md §8).
> **Source:** the investing textbook, `/home/srinidhi/Documents/AI-Invest/Part 2 - Fundamental Analysis`
> (chapters cited per flag) plus Part 3 for the technical flags.
> **Availability of inputs** was verified against the **real captured fixtures** in
> `data/src/test/resources/fixtures/{RELIANCE,TCS,INFY,HDFCBANK,TITAN}.html` (see §2). Where an input
> cannot be obtained, the flag is specified as **SUPPRESSED** — never faked.

---

## 1. Non-negotiable rules

1. **Factual wording only.** plan.md §8.3: *"Receivables grew 34% while revenue grew 9%."* Never
   *"This company is risky."* Every title string is a sentence containing **numbers**.
2. **Every flag carries evidence.** `RedFlag.evidence` is `List<Evidence>` and `RedFlag.init`
   `require(evidence.isNotEmpty())` `[repo]`. Each `Evidence` is `label` + `value` + `asOf` + `source`.
   **Every evidence item must show an as-of date** (plan.md §1.4 rule 3). `Evidence.value` must be the
   **number actually used in the comparison**, never a rounded or re-derived variant.
3. **Every flag shows its threshold.** `RedFlag.threshold` is a human string, e.g.
   `"Interest coverage < 2.0× (FY2026 = 1.4×)"`.
4. **Suppress rather than fake.** If a required input is `null`, the flag does not fire and no evidence
   is invented. A `null` is `Evidence` with `value = "not available from our sources"` only if the
   flag is *inherently* limited (see §2.3) — never as a substitute for a measurement.
5. **A red flag is not proof of fraud.** `[Ch.11-4 §Red Flag 7 area / Chapter 7 §Red flags]`:
   *"A red flag is not proof of fraud, but multiple red flags together increase risk."* The UI must
   show the **count**, never a single composite "risk score".
6. **Sector overrides are mandatory** (§6). Default `Sector.OTHER` and never guess (plan.md §7.1).

---

## 2. Data availability — verified against the real fixtures

### 2.1 What screener.in actually gives us

Parsed from the five captured `.html` fixtures. Row labels are the **exact** strings to match
(plan.md §3.3: parse by element ID, never by position; value span class is `"nowrap value"` with a
child `<span class="number">`).

**Card `top-ratios`** (latest values only, no history):

| Label | Present |
|-------|---------|
| `Market Cap`, `Current Price`, `High / Low`, `Stock P/E`, `Book Value`, `Dividend Yield`, `ROCE`, `ROE`, `Face Value` | ✔ all nine |

**Card `profit-loss`** — 13 columns (12 fiscal years Mar-2015…Mar-2026 **+ 1 TTM column**):
`Sales +`, `Expenses +`, `Operating Profit`, `OPM %`, `Other Income +`, `Interest`, `Depreciation`,
`Profit before tax`, `Tax %`, `Net Profit +`, `EPS in Rs`, `Dividend Payout %`

**Card `balance-sheet`** — 12 columns (Mar-2015…Mar-2026, **no TTM**):
`Equity Capital`, `Reserves`, `Borrowings +`, `Other Liabilities +`, `Total Liabilities`,
`Fixed Assets +`, `CWIP`, `Investments`, `Other Assets +`, `Total Assets`

**Card `cash-flow`** — 12 columns:
`Cash from Operating Activity +`, `Cash from Investing Activity +`, `Cash from Financing Activity +`,
`Net Cash Flow`, `Free Cash Flow`, `CFO/OP`

**Card `ratios`** — 12 columns:
`Debtor Days`, `Inventory Days`, `Days Payable`, `Cash Conversion Cycle`, `Working Capital Days`,
`ROCE %`
— **except for financials**, where the card contains only `ROE %`. Verified: `HDFCBANK.html` `ratios`
card = `['ROE %']`.

**Card `shareholding`** — two sub-tables (pattern + trend):
`Promoters +`, `FIIs +`, `DIIs +`, `Government +`, `Public +`, `Others +`, `No. of Shareholders`

**Card `quarters`** — 13 quarters: `Sales`, `Expenses`, `Operating Profit`, `OPM %`, `Other Income`,
`Interest`, `Depreciation`, `EPS` `[repo] QuarterRecord`

### 2.2 Field → flag map. **This corrects plan.md §8.**

| Field | Derivable? | From | Flags it enables |
|-------|-------------|------|------------------|
| `Equity Capital`, `Reserves` | **YES** | balance-sheet | F6 |
| `Borrowings` | **YES** | balance-sheet | F6 |
| ⇒ `Debt/Equity = Borrowings / (Equity Capital + Reserves)` | **YES** | derived | **F6** — plan.md says "no D/E" ⇒ **plan.md is wrong** |
| `Interest` (annual + 13 quarters) | **YES** | profit-loss, quarters | F7 |
| `Operating Profit`, `Depreciation` | **YES** | profit-loss, quarters | ⇒ `EBIT = Operating Profit + Depreciation` |
| ⇒ `Interest Coverage = EBIT / Interest` | **YES** | derived | **F7** — obtainable, not "missing" |
| `Other Income`, `Net Profit` | **YES** | profit-loss | F4 |
| ⇒ `Other income as % of PAT` | **YES** | derived | F4 |
| `Inventory Days` (12-year series) | **YES** | `ratios` card | **F3** — plan.md says "no inventory" ⇒ **plan.md is wrong** |
| `Debtor Days` (12-year series) | **YES** | `ratios` card | **F2 proxy** (see §3.2) |
| `Cash from Operating Activity`, `Net Profit` | **YES** | cash-flow, profit-loss | F1 |
| `Free Cash Flow`, `Market Cap` | **YES** | cash-flow, top-ratios | F10 |
| `ROCE %` series / `ROE %` series | **YES** | `ratios` card | F8 |
| `Stock P/E`, `EPS in Rs` (12y) + our cached closes | **YES** | top-ratios, profit-loss, candles | F9 |
| `Borrowings`, `Operating Profit` ⇒ `EBITDA`; `Borrowings`, `Cash`, `Market Cap` ⇒ `EV` | **YES** | derived | EV/EBITDA for plan.md §7.2 #10 is **obtainable** (with a caveat, §6.3) |
| **`Goodwill`** | **NO** | — a `grep -i goodwill` over all five fixtures returns **zero hits**; goodwill is collapsed inside `Fixed Assets +` behind a `+` drill-down that is **not** rendered on the company page | **F8 suppressed** |
| **`Trade receivables` (₹)** | **NO** | — `grep -i receivab` returns **zero hits** | F2 must use `Debtor Days` (F2-proxy) |
| **`Promoter pledging`** | **NO** | — `grep -i pledg` returns **zero hits** across all five fixtures | **F9 suppressed** |
| **Sector-median P/E** | **NO** | — requires sector listing pages, which are not in the source set (plan.md §3.3 parses only `/company/{SYMBOL}/consolidated/`) | F8 must use an absolute + own-history test |
| **`Promoter holding` history** | **PARTIAL** | shareholding card gives a **current** pattern and a "trend" sub-table | plan.md §7.2 #11 — usable but not per-year |
| `Delivery %` | **PARTIAL** | legacy `sec_bhavdata` CSV, plan.md §3.5 marks availability `UNVERIFIED` | F15 fires only when coverage ≥ 75 % `[repo] TechnicalPolicy.DELIVERY_COVERAGE_REQUIREMENT` |

### 2.3 The two permanently-suppressed flags

Both are on plan.md §8's list and **cannot be computed**. They ship as a declared capability gap, not
as an approximation:

| Plan.md flag | Status | What the UI shows instead |
|--------------|--------|---------------------------|
| `Goodwill > 20% of total assets` | **SUPPRESSED (no input)** | Nothing. Do **not** show a flag, and do **not** substitute "Fixed Assets > X %" — goodwill is a subset of fixed assets and the substitution would be a lie. Optionally a static line in Settings → "Data coverage" listing suppressed checks. |
| `Promoter pledging` | **SUPPRESSED (no input)** | Nothing. The shareholding card exposes promoter *holding* but not *pledge*. |

---

## 3. Earnings-quality flags

### 3.1 F1 — `CFO_NEGATIVE_WHILE_PAT_POSITIVE`

| Field | Value |
|-------|-------|
| **Threshold** | `CFO < 0` **and** `PAT > 0`, for `≥ 2` consecutive fiscal years |
| **Window** | the 2 most recent complete fiscal years **that both have data**. If only 1 year has data → no flag (`null` evidence, never a single-year flag) |
| **Severity** | `HIGH` if 3+ consecutive years; `MEDIUM` if exactly 2 |
| **Data needed** | `cashFlow` → `Cash from Operating Activity`; `profitLoss` → `Net Profit` |
| **Book** | `[Ch.11-4 §Red Flag 4: Declining Operating Cash Flow]` — *"One year may not indicate a problem. Several consecutive years require explanation."* `[Ch.11-4 §The Golden Rule]` — *"Profits must eventually become cash."* |
| **False-positive warning** | The book itself sets the 2-year rule; do not shorten it. Additionally, a genuine one-year negative can follow a large capex or working-capital build — which is exactly why the book says "require explanation" and not "sell". |

**Wording template**
> `"{symbol} reported profit in {n} consecutive years while operating cash flow was negative in {m} of them. Latest: PAT ₹{pat} Cr vs CFO ₹{cfo} Cr ({fy})."`

**Evidence** (each with `asOf` = fiscal-year end date, `source` = `SCREENER_IN`)
1. `Operating cash flow (FY2025)` → `₹-12,400 Cr`
2. `Profit after tax (FY2025)` → `₹8,910 Cr`
3. `Operating cash flow (FY2026)` → `₹-3,180 Cr`
4. `Profit after tax (FY2026)` → `₹11,240 Cr`
5. `Consecutive years meeting the condition` → `2`
6. `Threshold` → `CFO < 0 while PAT > 0, 2+ consecutive years`

**Threshold string**
`"Operating cash flow negative while profit positive, 2+ consecutive years"`

---

### 3.2 F2 — `RECEIVABLES_GROWING_FASTER_THAN_REVENUE`

**Two variants. Use whichever the data supports; never guess.**

| Variant | Fires when | Severity |
|---------|-----------|----------|
| **F2-proxy** (always available) | `Debtor Days` (DSO) rises by `≥ 10` days YoY | `LOW` ≥10 d, `MEDIUM` ≥25 d, `HIGH` ≥45 d |
| **F2-direct** (only if a full balance sheet ever becomes available) | `gReceivables − gRevenue > 10 pp` | `LOW` >10 pp, `MEDIUM` >25 pp, `HIGH` >40 pp |

| Field | Value |
|-------|-------|
| **Threshold (proxy)** | `DSO_t − DSO_{t−1} ≥ 10` days |
| **Window** | the two most recent fiscal years present in the `ratios` card |
| **Data needed** | `ratios` card → `Debtor Days` |
| **Book** | DSO formula `= Average Trade Receivables / Revenue × 365` `[Ch.13-R1 §10.12]`; *"Increasing DSO over several years may indicate: weak collections, customer stress, aggressive credit policies, revenue recognition concerns"* `[same]`. The direct flag is `[Ch.11-4 §Red Flag 2]`: *"Revenue grows by 10%. Receivables grow by 45%."*, with the book's own normal/warning pair being `12% / 14%` = **Normal** and `12% / 65%` = **Potential warning sign** `[same §Example]` |
| **False-positive warning** | *"Seasonality and industry norms should always be considered."* `[Ch.13-R1 §10.12]` — **this is the book's own warning.** Consequence: do **not** fire on a single-year 10-day move for a sector with structurally long credit terms. **`Sector.OTHER` suppresses F2-proxy entirely** (§6.2) because we cannot check industry norms. |

**Threshold justification `[derived]`:** plan.md §8 states `>10pp gap`; the book gives no pp number but
shows 2 pp = Normal and 53 pp = warning `[Ch.11-4 §Example]`. The 10-day DSO proxy is chosen to be the
*same idea* in the only unit we have: 10 days of DSO ≈ 2.7 % of revenue turning into receivables.
The 25 pp / 40 pp severity bands interpolate the book's `14 % (normal) → 65 % (warning)` pair.

**Wording template (proxy — the only one that can ship)**
> `"Days sales outstanding rose from {dso_prev} to {dso_curr} days in {fy} ({delta:+d} days) while revenue {rev_note}."`

`rev_note` is one of, chosen deterministically from `profit-loss`:
- if `gRevenue > 0`: `"grew {gRevenue:.0f}%"`
- if `gRevenue ≤ 0`: `"fell {abs:.0f}%"`
- if `gRevenue` unavailable: `""` (omit the clause entirely — never emit a dangling "while revenue")

**Evidence**
1. `Days sales outstanding (FY2026)` → `44 days` · asOf `2026-03-31`
2. `Days sales outstanding (FY2025)` → `31 days` · asOf `2025-03-31`
3. `Change` → `+13 days`
4. `Revenue growth (FY2026)` → `+8.2 %` · asOf `2026-03-31`
5. `Sector` → `Unresolved (sector-specific norms not applied)` — **required**, so the user can see why
   the flag may be a false positive
6. `Threshold` → `Days sales outstanding rising ≥ 10 days year on year`

**Threshold string**
`"Days sales outstanding rising ≥ 10 days year on year"`

---

### 3.3 F3 — `INVENTORY_DAYS_RISING`

| Field | Value |
|-------|-------|
| **Threshold** | `Inventory Days_t − Inventory Days_{t−1} ≥ 10` days → `LOW`; `≥ 25` → `MEDIUM`; `≥ 45` → `HIGH` |
| **Window** | two most recent fiscal years in the `ratios` card |
| **Data needed** | `ratios` card → `Inventory Days` |
| **Book** | DIO `= Average Inventory / COGS × 365` `[Ch.13-R1 §10.13]`; *"Increasing inventory days may suggest: slowing demand, overstocking, obsolete inventory, poor inventory management."* The growth-gap form is `[Ch.11-4 §Red Flag 3]`: *"Sales increase 8%. Inventory increases 40%."* |
| **False-positive warning** | *"Some industries naturally require higher inventory levels than others."* `[Ch.13-R1 §10.13]` — **the book's own warning.** Same treatment as F2: **suppress for `Sector.OTHER`** (§6.2). Note also that `Cash Conversion Cycle` is available and is a **better** normalised measure — if `Inventory Days` rises **while** `Days Payable` is flat or falling, that is materially worse; if `Days Payable` rises in step, it is supplier financing. Add that as an extra evidence row, never as a separate flag. |

`[derived]` 10 / 25 / 45 days: the same rationale as F2's bands; the book gives growth percentages, not
days, and the book's own TITAN fixture shows a healthy `169 → 222` day range over 12 years
(`TITAN.html`), i.e. a ±5-day annual wobble is normal for a jewellery/watch business — hence 10 days as
the floor.

**Wording template**
> `"Inventory days rose from {prev} to {curr} days in {fy} ({delta:+d} days) while days payable moved from {dp_prev} to {dp_curr}."`

**Evidence**: `Inventory Days (FY2026)` → `222 days` · `Inventory Days (FY2025)` → `217 days` ·
`Change` → `+5 days` · `Days payable (FY2026)` → `15 days` · `Days payable (FY2025)` → `15 days` ·
`Sector` → `Unresolved (sector-specific norms not applied)` · `Threshold`

**Threshold string**
`"Inventory days rising ≥ 10 days year on year"`

---

### 3.4 F4 — `OTHER_INCOME_AS_PCT_OF_PAT`

| Field | Value |
|-------|-------|
| **Formula** | `otherIncomePct = Other Income / Net Profit × 100` for the latest fiscal year with both values |
| **Threshold** | `> 30 %` → `MEDIUM`; `> 50 %` → `HIGH`. Never `LOW`. |
| **Window** | latest fiscal year (single-year test — the book frames it as a *level*, not a change) |
| **Data needed** | `profit-loss` → `Other Income +`, `Net Profit +` |
| **Book** | `[Ch.11-3 §9.68 Other Income]`: *"Recurring operating profit is generally more valuable than profit driven by volatile or one-time other income."* Worked example: **PAT ₹1,000 Cr, Other Income ₹600 Cr → "Investors should investigate whether the underlying operating business is actually strong."** `[Ch.11-4 §Red Flag 5: Frequent One-Time Gains]` adds *"Recurring 'non-recurring' income is a classic warning sign."* |
| **False-positive warning** | The book's example is **60 %**, not 30 %. 30 % is `[derived]`, set because RELIANCE — a large, high-quality Indian conglomerate — sits at **23.1 %** on the live fixture (`20,393 / 88,167`, FY2026 TTM column), so 30 % is above the normal range for a good business while still well below the book's 60 % example. **The flag measures the LEVEL of other income, not its quality**: a company with 25 % other income that is recurring bank interest in a NBFC is fine; one with 25 % that is fair-value gains is not. The UI must therefore say *"share of profit from non-operating income"*, never *"other income is bad"*. |

**Sector override:** for `BANKING`, `NBFC` and `INSURANCE`, other income is a normal structural
component. **Suppress** (§6.2).

**Wording template**
> `"Other income was ₹{oi} Cr against profit after tax of ₹{pat} Cr in {fy} — {pct:.0f}% of profit came from items outside operations."`

**Evidence**: `Other income (FY2026)` → `₹20,393 Cr` · `Profit after tax (FY2026)` → `₹88,167 Cr` ·
`Other income as % of PAT` → `23.1 %` · `Operating profit (FY2026)` → `₹183,561 Cr` ·
`Operating profit as % of PAT` → `208.2 %` · `Threshold`

**Threshold string**
`"Other income exceeds 30% of profit after tax"`

---

## 4. Balance-sheet flags

### 4.1 F5 / F6 numbering

plan.md §8 lists four balance-sheet flags. This spec numbers them `RF-BS-1 … RF-BS-4` to avoid
colliding with the earnings-quality ids.

### 4.2 `RF-BS-1` — `DEBT_TO_EQUITY_SPIKE`

| Field | Value |
|-------|-------|
| **Formula** | `debtToEquity(t) = Borrowings(t) / (Equity Capital(t) + Reserves(t))` |
| **Threshold (spike)** | `ΔdebtToEquity ≥ +0.50` **absolute**, **or** `debtToEquity(t) / debtToEquity(t−1) ≥ 1.50` with `debtToEquity(t−1) ≥ 0.20` |
| **Threshold (level, additional)** | `debtToEquity(t) > 2.0` fires regardless of change |
| **Window** | latest fiscal year vs the immediately preceding one (both must be present) |
| **Severity** | `MEDIUM` if the spike fires but the level stays `< 1.0`; `HIGH` if the level also crosses `> 2.0` |
| **Data needed** | `balance-sheet` → `Borrowings +`, `Equity Capital`, `Reserves` |
| **Book** | D/E bands `[Ch.17-R5 §Interpretation]` — **use these verbatim as the level evidence:** `<0.3` very conservative · `0.3–1.0` comfortable · `1–2` high · `>2` **potentially risky**. Industry table `[same §Industry Differences]`: Software near zero · FMCG low · Pharmaceuticals low · Utilities moderate–high · Telecom high · Infrastructure high · Airlines high. |
| **False-positive warning** | **Two, both the book's own.** (a) *"High debt is **not automatically** a red flag. Debt is often acceptable if: cash flows are stable, revenue is predictable, interest rates are manageable, assets have long useful lives, returns exceed borrowing costs."* `[Ch.17-R5 §When High Debt is Acceptable]` (b) *"Never compare debt ratios across unrelated industries."* `[same]` ⇒ **For `Sector.OTHER`, the *level* check (`> 2.0`) is suppressed** and only the *spike* check fires; for `TELECOM`, `POWER`, `RENEWABLE`, `AVIATION`, `CAPITAL_GOODS`, `REALTY` the level check uses the sector band, not 2.0 (§6.1). |

`[derived]` 0.50 absolute / 1.50 relative: the book's four bands are 0.3 / 1.0 / 2.0, so a jump of half a
band (0.5) or of 50 % is the smallest move that visibly crosses a band.

**Wording template**
> `"Debt/equity rose from {de_prev} to {de_curr} between {fy_prev} and {fy} — from the {band_prev} band to the {band_curr} band."`

**Evidence**: `Borrowings (FY2026)` → `₹402,962 Cr` · `Shareholders' equity (FY2026)` → `₹904,030 Cr`
(= Equity Capital ₹13,532 + Reserves ₹890,498) · `Debt/equity (FY2026)` → `0.45` ·
`Debt/equity (FY2025)` → `0.43` · `Book band (FY2026)` → `0.3–1.0 — comfortable` ·
`Industry benchmark (sector)` → `Unresolved (bands not applied)` · `Threshold`

**Threshold string**
`"Debt/equity up ≥ 0.50 absolute or ≥ 50% relative year on year; level bands: <0.3 / 0.3–1.0 / 1–2 / >2"`

---

### 4.3 `RF-BS-2` — `INTEREST_COVERAGE_BELOW_2`

| Field | Value |
|-------|-------|
| **Formula** | `interestCoverage = EBIT / Interest`, where **`EBIT = Operating Profit + Depreciation`** (Indian GAAP presentation, from the `profit-loss` card). The book defines `Interest Coverage = EBIT / Interest Expense` `[Ch.17-R5 §Interest Coverage Ratio]`. |
| **Threshold** | `< 2.0` → `MEDIUM`; `< 1.0` → `HIGH` (the book's *"Below 1 → Serious financial distress"*) |
| **Window** | latest fiscal year with `Operating Profit`, `Depreciation` and `Interest` all present |
| **Data needed** | `profit-loss` → `Operating Profit`, `Depreciation`, `Interest` |
| **Book bands (verbatim)** | `[Ch.17-R5 §Interpretation]` — `Below 1` serious financial distress · `1–2` weak · `2–5` acceptable · `Above 5` strong. **plan.md §8's "below 2.0" is exactly the boundary between "weak" and "acceptable"** ⇒ plan.md and the book agree; no conflict. |
| **False-positive warning** | *"These are general guidelines."* `[Ch.17-R5 §Interpretation]` and *"industry context is essential"* `[same §Debt-to-EBITDA interpretation]`. Also `[Ch.17-R5 §Debt Maturity Matters]`: two companies with the same Debt-to-EBITDA differ enormously by maturity — we cannot see maturities, so **the evidence must say so**. |
| **Definition caveat (must be shown)** | `EBIT` here **excludes** other income, which understates coverage for companies with large recurring non-operating income (banks, NBFCs, investment companies). The evidence must include the alternative: `EBIT including other income` and its coverage, so the user can judge. **For `Sector.BANKING` the flag is suppressed** — for a bank, interest is a cost of goods sold, not a financing cost, and `Interest / Interest Expense` is meaningless `[plan.md §7.3]`. |

**Wording template**
> `"Operating profit covered interest {ratio}× in {fy} (EBIT ₹{ebit} Cr against interest ₹{int} Cr)."` 
> plus, when it applies: `"Including other income the figure is {ratio2}×."`

**Evidence**: `Operating profit (FY2026)` → `₹183,561 Cr` · `Depreciation (FY2026)` → `₹58,946 Cr` ·
`EBIT (FY2026)` → `₹242,507 Cr` · `Interest (FY2026)` → `₹28,362 Cr` ·
`Interest coverage (FY2026)` → `8.55×` · `Interest coverage incl. other income` → `9.27×` ·
`Book band` → `Above 5 — strong` · `Debt maturity` → `Not available from our sources` · `Threshold`

**Threshold string**
`"Interest coverage below 2.0× (below 1.0× is severe)"`

---

### 4.4 `RF-BS-3` — `GOODWILL_ABOVE_20PCT_ASSETS` — **SUPPRESSED**

See §2.3. `grep -i goodwill` over all five fixtures returns zero hits; goodwill sits inside the
`Fixed Assets +` drill-down that the company page does not render. **No evidence may be substituted.**
Ship the class, return `null` from `evaluate()`, and list it in Settings → Data coverage.

### 4.5 `RF-BS-4` — `PROMOTER_PLEDGING` — **SUPPRESSED**

See §2.3. `grep -i pledg` returns zero hits. The shareholding card exposes promoter **holding**, which
is a *different* number and must never be presented as pledge. **No flag fires.**
`[repo] CashFlowDerived.promoterPledgePercent` exists and stays `null`.

---

## 5. Valuation-trap flags

### 5.1 `RF-VT-1` — `LOW_PE_WITH_FALLING_RETURNS` (value trap)

| Field | Value |
|-------|-------|
| **Threshold** | **Both** must hold on the latest fiscal year: (a) `PE < PE_BAND[sector]` — absolute band, **not** a sector median; (b) `profitabilityTrend ≤ 0` |
| **`PE_BAND` (absolute, `[derived]`)** | `BANKING` 12 · `NBFC` 10 · `IT` 20 · `PHARMA` 25 · `FMCG` 35 · `TELECOM` 15 · `METALS` 12 · `CEMENT` 18 · `POWER` 18 · `RENEWABLE` 30 · `REALTY` 20 · `AVIATION` 30 · `DURABLES` 25 · `RETAIL` 40 · `LOGISTICS` 30 · `CAPITAL_GOODS` 30 · `CHEMICALS` 18 · `AUTO` 20 · `INSURANCE` 15 · `DEFENCE` 45 · `RAILWAY` 22 · `OTHER` **suppressed** |
| **`profitabilityTrend`** | slope of the `ratios` card's profitability series over the last **3** fiscal years, in **percentage points per year**: `ROCE %` for all sectors; `ROE %` for `BANKING`, `NBFC`, `INSURANCE`. Fires when `trend ≤ −1.0 pp/yr` |
| **Severity** | `MEDIUM` if `trend ≤ −1.0`; `HIGH` if additionally the latest value is `≤ 0` (loss-making) |
| **Data needed** | `top-ratios` → `Stock P/E`; `ratios` card → `ROCE %` or `ROE %` series |
| **Book** | `[Ch.48 §Mistake 1 — The Value Trap]`: *"A Value Trap is a stock that appears inexpensive but continues to destroy shareholder value."* Warning signs **verbatim**: declining revenue · declining margins · **falling ROE** · increasing debt · negative free cash flow · poor capital allocation · frequent equity dilution · corporate governance concerns. *"A low valuation should trigger **investigation**, not immediate buying."* |
| **False-positive warning** | *"A low P/E is not automatically cheap"* is the book's position `[Ch.41 §Understanding the Meaning]` — a low P/E can be justified by a 40 % growth rate. **The flag therefore requires the profitability decline too, never P/E alone.** plan.md §8 says *"P/E below sector **median** AND ROE declining"* — **the sector median is not obtainable** (§2.2). **Contradiction with plan.md, flagged not resolved:** recommend the absolute bands above, and add the individual book warning signs as separate evidence rows. |

`[derived]` `PE_BAND`: the book's own industry comparison table `[Ch.47 §Industry Comparison Table]`
names the *right multiple* per industry (Banks P/B, IT P/E, FMCG P/E, Cement EV/EBITDA …) but gives
**no P/E numbers**. The bands above are ordered from that table's structure plus the book's sector
list; they are a starting point, **must be reviewed by the owner against a real sample of 30 stocks per
sector before shipping**, and must be a single named constant table so that tuning is one edit.
For `Sector.OTHER` the flag is **suppressed** because the book's instruction is *"Never compare debt
ratios across unrelated industries"* `[Ch.17-R5]` read as a general principle.

**Wording template**
> `"P/E of {pe}× is below the {sector} band of {band}×, while {metric} fell from {r_prev}% to {r_curr}% over three years."`

**Evidence**: `Stock P/E` → `14.0×` · asOf `2026-10-01` (the scrape date, not a fiscal date — the UI
must use the fetch date because `top-ratios` is a live value) · `ROCE % (FY2024)` → `23 %` ·
`ROCE % (FY2025)` → `19 %` · `ROCE % (FY2026)` → `21 %` · `ROCE 3-yr trend` → `−1.0 pp/yr` ·
`Sector band used` → `IT: < 20×` · `Source of P/E` → `screener.in top-ratios` · `Threshold`

**Threshold string**
`"P/E below sector band while ROCE/ROE declines ≥ 1.0 pp per year over 3 years"`

---

### 5.2 `RF-VT-2` — `PE_RISING_WHILE_EARNINGS_FALL`

| Field | Value |
|-------|-------|
| **Threshold** | Over the last **4** quarters: `EPS_ttm(t) < EPS_ttm(t−4)` (earnings falling) **and** `PE(t) > PE(t−4)` (multiple rising). Additional severity: `PE(t) ≥ 1.25 × PE(t−4)` → `HIGH` |
| **`EPS_ttm` computation** | `EPS_ttm(i) = Σ EPS over the four quarters ending at quarter `i`` from the `quarters` card (13 quarters available). Requires `|EPS| > 0` in all four quarters; if any EPS is `≤ 0` the ratio test is undefined and the flag is **not** computed (it moves to `RF-VT-3`). |
| **`PE(i)` computation** | `PE(i) = closeAt(quarterEndDate(i)) / EPS_ttm(i)`, where `closeAt` is our cached **W1** close for the week containing the quarter-end date `[repo] Candle`. Falls back to D1 close. If no cached close within ±10 trading days → the point is `null` and the flag is suppressed. |
| **Window** | needs `≥ 9` quarters of `EPS` and cached closes; otherwise suppress |
| **Severity** | `MEDIUM` base; `HIGH` at `≥ 1.25×` |
| **Data needed** | `quarters` → `EPS`; our own candle cache |
| **Book** | `[Ch.48 §Mistake 2 — The Growth Trap]` is the mirror error; the relevant warning is *"Historical P/E is meaningless when earnings are at a cyclical peak or trough"* in spirit — the book's concrete illustration of multiple expansion into falling earnings is `[Ch.48 §Common Mistakes §Buying value traps]` complement. `[Ch.48]` also warns that *"high growth justifies any valuation"* is equally dangerous. |
| **False-positive warning** | **The single biggest false positive here is a cyclical earnings trough.** A steel stock at ₹8 EPS and 9× P/E becomes 5× P/E when EPS recovers to ₹14 — P/E *fell*, so no flag; but the mirror case (EPS falling from a peak) fires on every commodity at the top of a cycle. **Mitigation `[derived]`:** suppress when `Sector ∈ {METALS, CEMENT, CHEMICALS, POWER, AVIATION, CAPITAL_GOODS}` **and** the 4-quarter EPS change is `> −40 %` (a trough, not a fall). The evidence must always show the **absolute** EPS values, so the user can see the base. |

**Wording template**
> `"Trailing EPS fell from ₹{eps_prev} to ₹{eps_curr} between {d_prev} and {d_curr}, while P/E on the same earnings rose from {pe_prev}× to {pe_curr}×."`

**Evidence**: `EPS (TTM, {d_prev})` → `₹22.40` · `EPS (TTM, {d_curr})` → `₹18.10` ·
`P/E on TTM earnings ({d_prev})` → `11.8×` · `P/E on TTM earnings ({d_curr})` → `14.9×` ·
`Change in P/E` → `+3.1×` · `Change in EPS` → `−19.2 %` · `Earnings base` → `quarterly EPS series, 13 quarters` ·
`Cyclicality check` → `Sector not in cyclical set — flag evaluated as normal` · `Threshold`

**Threshold string**
`"P/E rising over 4 quarters while trailing EPS falls"`

---

### 5.3 `RF-VT-3` — `NEGATIVE_FCF_YIELD`

| Field | Value |
|-------|-------|
| **Formula** | `fcfYield = Free Cash Flow(latest FY) / Market Cap × 100` `[Ch.46 §FCF Yield — Equity Perspective]` — *"The first is more common among equity investors."* |
| **Threshold** | `fcfYield < 0` for `≥ 2` **consecutive** fiscal years → `MEDIUM`; `≥ 3` consecutive → `HIGH`. A single negative year does **not** fire. |
| **Window** | the last 2–3 fiscal years in the `cash-flow` card |
| **Data needed** | `cash-flow` → `Free Cash Flow`; `top-ratios` → `Market Cap` |
| **Book** | `[Ch.46 §Negative Free Cash Flow]`: *"Young growth companies often report negative FCF. This is **not automatically** a concern. The key question becomes: **Why** is FCF negative? If cash is being invested in: new factories, distribution networks, product development — future value may increase. **However, persistent negative FCF without improving economics is a warning sign.**"* That sentence is the whole rule: **persist = ≥ 2 years**, and *"improving economics"* is proxied by `CFO/OP ≥ 100 %`. |
| **False-positive warning** | `[Ch.46 §Limitations of FCF Yield]`: *"Can fluctuate due to temporary capital expenditure. Negative for many high-growth businesses. Requires understanding of maintenance vs growth CapEx. One year's FCF may not represent normal business conditions."* We cannot see maintenance-vs-growth capex. **Mitigation `[derived]`:** add an evidence row `CFO/OP`; when `CFO/OP ≥ 100 %` for the same year, downgrade severity by one level and add the evidence note *"operating cash flow covered operating profit — the deficit is capital expenditure, not profitability."* Also note `[Ch.46 §Industries Where FCF Yield is Especially Useful]` lists *mature* tech, consumer, healthcare, asset-light — i.e. the book says the metric is **least** meaningful for capital-hungry businesses, which is exactly where it fires. |
| **Growth-business carve-out** | For `Sector ∈ {RENEWABLE, TELECOM, CAPITAL_GOODS, AVIATION, LOGISTICS}`, cap severity at `MEDIUM` and label the flag *"persistent negative free cash flow"*, never *"cash-burning"*. |

**Wording template**
> `"Free cash flow was negative in {n} of the last {m} fiscal years (latest ₹{fcf} Cr against a market capitalisation of ₹{mcap} Cr — an FCF yield of {y:.1f}%)."`

**Evidence**: `Free cash flow (FY2026)` → `₹-1,240 Cr` · `Free cash flow (FY2025)` → `₹-860 Cr` ·
`Market capitalisation` → `₹1,58,020 Cr` · asOf `2026-10-01` · `FCF yield (latest FY)` → `−0.08 %` ·
`CFO/OP (FY2026)` → `118 %` · `Consecutive negative years` → `2` ·
`Capex split (maintenance vs growth)` → `Not available from our sources` · `Threshold`

**Threshold string**
`"Negative free cash flow in 2+ consecutive fiscal years"`

---

## 6. Sector overrides (binding)

### 6.1 Sector bands and suppressions

| Sector | `PE_BAND` (§5.1) | D/E level band for `RF-BS-1` | Suppressed flags |
|--------|------------------|------------------------------|------------------|
| `BANKING` | 12 | n/a (suppressed) | `RF-BS-1` (level), `RF-BS-2`, `RF-VT-3` (capital-agnostic: **keep** FCF), F4 |
| `NBFC` | 10 | 3.0 | `RF-BS-2`, F4 |
| `INSURANCE` | 15 | 1.0 | `RF-BS-2`, F4 |
| `IT` | 20 | 0.5 | — |
| `PHARMA` | 25 | 0.5 | — |
| `FMCG` | 35 | 0.8 | — |
| `AUTO` | 20 | 1.5 | — |
| `METALS` | 12 | 1.5 | F2/F3 keep, F2-proxy suppressed (§6.2) |
| `CEMENT` | 18 | 1.2 | F2-proxy suppressed |
| `POWER` | 18 | 2.0 | F2-proxy suppressed |
| `RENEWABLE` | 30 | 2.5 | F2-proxy suppressed, `RF-VT-3` capped |
| `DEFENCE` | 45 | 0.5 | — |
| `RAILWAY` | 22 | 2.0 | F2-proxy suppressed |
| `REALTY` | 20 | 2.0 | F2-proxy suppressed |
| `TELECOM` | 15 | 2.5 | F4, `RF-VT-3` capped |
| `AVIATION` | 30 | 2.5 | F2-proxy suppressed, `RF-VT-3` capped |
| `DURABLES` | 25 | 1.0 | — |
| `RETAIL` | 40 | 1.0 | — |
| `LOGISTICS` | 30 | 1.5 | `RF-VT-3` capped |
| `CAPITAL_GOODS` | 30 | 2.0 | `RF-VT-3` capped |
| `CHEMICALS` | 18 | 1.5 | F2-proxy suppressed |
| `OTHER` | **suppressed** | **suppressed** | `RF-VT-1` (level+trend both), `RF-BS-1` (level only — spike still fires), F2-proxy, F3, F4 |

`[derived]` for every band. Rationale in one line: the book's own D/E industry table
`[Ch.17-R5 §Industry Differences]` gives the *ordering* (Software near zero → Airlines high), and the
bands interpolate it; the P/E bands follow `[Ch.47 §Industry Comparison Table]`'s choice of multiple.
**All bands must be reviewed against real data before shipping.**

### 6.2 The `Sector.OTHER` rule — important

When the sector is unresolved we must not assert an industry-norm-dependent flag. This is the book's
instruction generalised: *"Never compare debt ratios across unrelated industries"* `[Ch.17-R5]`,
*"Seasonality and industry norms should always be considered"* `[Ch.13-R1 §10.12]`, *"Some industries
naturally require higher inventory levels than others"* `[Ch.13-R1 §10.13]`, and *"In small caps volume
can be manipulated"* `[Ch.21 §21.20]`.

Therefore, for `Sector.OTHER`:
- **F2-proxy, F3 are suppressed.** Not lowered — suppressed.
- **Every surviving flag must carry an evidence row** `Sector` → `"Unresolved (industry norms not applied)"`.
- The **Fundamentals tab must not display a green tick** for a suppressed check. Show
  `"Not evaluated — sector unresolved"`.

### 6.3 EV/EBITDA — note for plan.md §7.2 #10

`EV/EBITDA` is **derivable**: `EBITDA = Operating Profit + Depreciation`;
`EV = Market Cap + Borrowings − Cash`. **Cash is not a separate line** in the collapsed balance sheet —
it sits inside `Other Assets +`. Therefore **`EV/EBITDA` is NOT computable to a defensible precision** and
plan.md §8's "no EV/EBITDA" is **correct**. Two partial substitutes exist and must be labelled as such,
never as "EV/EBITDA":
- `Borrowings / EBITDA` — the book's own ratio `[Ch.17-R5 §Debt-to-EBITDA]`, bands
  `<1` excellent · `1–2` healthy · `2–3` moderate · `>4` elevated risk · `>5` often high risk. **This one
  ships** and is a genuinely useful addition to §4.
- `Market Cap / (Market Cap + Borrowings)` — capital-structure share, clearly labelled.

---

## 7. Technical flags

All five are computed by `IndicatorSnapshot` `[repo]`. Thresholds come from plan.md §8.2; the book
supplies the thresholds.

### 7.1 `RF-T-1` — `DEATH_CROSS`

| Field | Value |
|-------|-------|
| **Condition** | `SMA50 < SMA200` **and** `close < SMA200` `[repo] IndicatorSnapshot.isDeathCrossCondition()` |
| **Extra (not required)** | report `deathCrossBarIndex` `[repo]` and its date — *"When did it happen?"* is what the user actually needs |
| **Severity** | `HIGH` if the cross is `≤ 5` trading days old; `MEDIUM` otherwise |
| **Book** | Death Cross = "50 DMA crosses below 200 DMA", bearish `[Ch.22 §22.9]` |
| **False-positive warning** | **`[Ch.22 §22.10 Limitations of Moving Average Signals]` is explicit and must be rendered as a caveat line: "Lagging indicators. They react after price movement. Problems: late entries, false signals in sideways markets."** `[Ch.22 §22.35 §Mistake 4]`: *"Over-optimizing settings."* We use the canonical 50/200 and do not offer alternatives. |

**Wording template**
> `"The 50-day average is below the 200-day average, and the last close of ₹{close} is below the 200-day average of ₹{sma200}."`

**Evidence**: `Close (last bar)` → `₹1,167.70` · asOf `2026-10-01` · `SMA 50` → `₹1,205.40` ·
`SMA 200` → `₹1,263.90` · `Close − SMA 200` → `−7.6 %` · `Cross date` → `2026-08-19` ·
`Caveat` → `Moving averages are lagging; they react after price moves.` · `Threshold`

**Threshold string**
`"Close below the 200-DMA and SMA50 < SMA200"`

---

### 7.2 `RF-T-2` — `RSI_OVERBOUGHT` / `RSI_OVERSOLD`

| Field | Value |
|-------|-------|
| **Condition** | `rsi > 70` (overbought) or `rsi < 30` (oversold) `[repo] TechnicalPolicy.RSI_OVERBOUGHT / RSI_OVERSOLD` |
| **Severity** | `LOW` by default; `MEDIUM` if the reading has persisted `≥ 5` consecutive bars |
| **Window** | latest bar; persistence counted backwards |
| **Book** | `[Ch.22 §22.16]`: `>70` overbought, `<30` oversold — **and the book's own correction, which the UI must display verbatim**: *"A strong stock can remain RSI > 70 for months. **This is not automatically bearish.**"* and *"Professional interpretation is different."* `[Ch.22 §22.35 §Mistake 1]`: *"Buying only because RSI is low."* |
| **False-positive warning** | **The strongest in this document.** The book directly contradicts the naive reading of the flag. The wording template must therefore be about the *reading*, not about what to do: **never** *"RSI is overbought — expect a fall."* Always *"RSI is at 74, in the book's overbought band. A strong stock can remain in this band for months."* |

**Wording template (overbought)**
> `"RSI(14) is {rsi:.0f}, in the overbought band above 70. The book notes a strong stock can stay in this band for months; this is a momentum reading, not a signal."`

**Wording template (oversold)**
> `"RSI(14) is {rsi:.0f}, in the oversold band below 30. Oversold describes momentum, not a reversal."`

**Evidence**: `RSI (14)` → `74.2` · asOf `2026-10-01` · `Consecutive bars above 70` → `3` ·
`52-week high` → `₹1,612` · `Close` → `₹1,167.70` · `Band` → `Overbought (>70)` ·
`Caveat` → `RSI is a momentum reading, not a reversal signal.` · `Threshold`

**Threshold string**
`"RSI(14) above 70 (overbought) or below 30 (oversold)"`

---

### 7.3 `RF-T-3` — `DISTRIBUTION_DAY` (volume spike on a down day)

| Field | Value |
|-------|-------|
| **Condition** | `close < open` **on the same bar** **and** `volume / volumeSma20 ≥ 2.5` `[repo] IndicatorSnapshot.isDistributionDay`, `[repo] TechnicalPolicy.VOLUME_SPIKE_MULTIPLE = 2.5` |
| **Severity** | `MEDIUM`; `HIGH` if `ratio ≥ 4.0` |
| **Window** | latest bar only (an EOD app, plan.md G3) |
| **Book** | "Falling + Rising" in price/volume = "Strong selling pressure" `[Ch.21-6 §Combining Price and Volume]`. `[Ch.21 §21.17 §Distribution Analysis §2]`: *"High volume without price progress — Important warning. Large selling is absorbing buying."* `[Ch.22 §22.21]`: VWAP — price below VWAP = sellers controlling. |
| **False-positive warning** | **`[Ch.21 §21.27 §Mistake 1]` must be shown: "Assuming high volume always means buying. **Wrong.** High volume means: high participation. **Direction requires context.**"** And `[Ch.21 §21.21 VSA]`: *"A large candle with high volume could mean buying OR selling. Need context. Large up candle + high volume: near support → bullish; near resistance → could be distribution."* Also `[Ch.21 §21.22]`: volume spikes are caused by "quarterly earnings, large orders, regulatory approvals, mergers or acquisitions, index inclusion, major news" — i.e. **an ex-results or index-rebalance day can produce a 5× volume on a down day legitimately.** ⇒ **Mitigation `[derived]`:** if the bar falls within ±3 trading days of a results date we cannot see, we cannot exclude this; the evidence must therefore include `Range vs ATR` so the user can judge magnitude, and the wording must not assert distribution. |
| **Do not** | do **not** extend this to "high volume on an up day = accumulation". The book says direction requires context `[Ch.21 §21.27]`. An up-day volume spike is **not** a red flag. |

**Wording template**
> `"Volume was {ratio:.1f}× the 20-bar average of {avg} shares on a bar that closed lower (₹{open} → ₹{close}). High volume means high participation; direction requires context."`

**Evidence**: `Volume (last bar)` → `16,771,221 shares` · asOf `2026-10-01` ·
`20-bar average volume` → `6,420,000 shares` · `Volume ratio` → `2.61×` ·
`Open` → `₹1,180.10` · `Close` → `₹1,167.70` · `Change` → `−1.05 %` ·
`Range ÷ ATR(14)` → `1.8×` · `Threshold`

**Threshold string**
`"Volume ≥ 2.5× the 20-bar average on a bar that closed lower"`

---

### 7.4 `RF-T-4` — `AT_52WEEK_LOW_WITH_DECLINING_OBV`

| Field | Value |
|-------|-------|
| **Condition** | `close ≤ 52-week low` `[repo] fiftyTwoWeekLow` **and** `obvSlope < 0` `[repo]` |
| **Severity** | `MEDIUM`; `HIGH` if the close is within 2 % of the 52-week low |
| **Window** | 52 W1-equivalent window; `TechnicalPolicy.WEEKS_PER_YEAR = 52` `[repo]` |
| **Book** | `[Ch.22 §22.29 OBV]`: *"If price closes higher add volume; if lower subtract volume."* `[Ch.22 §22.29]`: *"Price flat, OBV increasing → possible accumulation."* The mirror — price at a low **and** OBV falling — is the book's "momentum weakening + volume leaving" combination `[Ch.19-4 §Trend Exhaustion]`: *"Momentum slows. Volume declines."* |
| **False-positive warning** | **OBV is a cumulative level, so its raw value is scale-dependent and meaningless across stocks; a *slope* is required** `[repo] obvSlope` already regresses over `TechnicalPolicy.SMA_FAST = 20` bars, which is the correct fix. Second: *"Volume is not direction — a falling OBV during a range can mean profit-taking, not distribution"* `[Ch.21 §21.27 §Mistake 3]`. Third: for a stock with a corporate action in the window, the price series is distorted (plan.md §9) — if `SymbolResolver` flagged a corporate action for the window, **suppress this flag** and say so. |

**Wording template**
> `"The last close of ₹{close} is at the 52-week low of ₹{low}, and on-balance volume has been falling over the last 20 bars."`

**Evidence**: `Close (last bar)` → `₹116.40` · asOf `2026-10-01` · `52-week low` → `₹116.40` ·
`52-week high` → `₹214.80` · `Position in 52-week range` → `0.0 %` ·
`OBV slope (20 bars)` → `−184,500 shares/bar` · `Threshold`

**Threshold string**
`"Close at the 52-week low with a declining 20-bar OBV slope"`

---

### 7.5 `RF-T-5` — `DELIVERY_COLLAPSE`

| Field | Value |
|-------|-------|
| **Condition** | `[repo] IndicatorSnapshot.isDeliveryCollapsingFlag` = `DELIVERY_COLLAPSE_RATIO = 0.40` (latest delivery % `≤ 40 %` of its own 20-bar average) **for `DELIVERY_COLLAPSE_CONSECUTIVE_BARS = 2` consecutive bars**, with `DELIVERY_COVERAGE_REQUIREMENT = 0.75` of the window populated |
| **Severity** | `MEDIUM`. If delivery % was **also** above its 20-bar average on the 5 bars before the collapse and price fell, `HIGH` |
| **Window** | latest 20 bars (D1 only — the legacy feed is daily) |
| **Data needed** | `Candle.deliveryPercent` `[repo]` from the legacy `sec_bhavdata` file, plan.md §3.5 (`UNVERIFIED` availability) |
| **Book** | `Delivery % = Delivery Qty / Total Traded Qty × 100` `[Ch.21 §21.18]`. *"High delivery + rising price → usually bullish, possible long-term accumulation. High delivery + falling price → can indicate selling pressure."* `[Ch.21 §21.19]` |
| **False-positive warning** | **The book says this explicitly, twice.** (a) *"However, delivery data should not be interpreted in isolation. A high delivery percentage can arise for many reasons and does not automatically imply accumulation by informed investors. **Likewise, a low delivery percentage is not necessarily bearish.**"* `[Ch.21-6 §Delivery Volume in the Indian Market]` (b) *"Delivery volume is a useful data point in Indian markets but should always be interpreted alongside price action and broader market context."* `[Ch.21-6 §Common Volume Mistakes]` and `"Relying solely on delivery percentage to infer institutional buying."` (c) *"In small caps volume can be manipulated."* `[Ch.21 §21.20]` ⇒ **suppress for stocks below a liquidity floor and state it.** (d) **The source is `UNVERIFIED`** (plan.md §3.5, OQ-2) ⇒ if `deliveryPercent` coverage `< 75 %`, the flag **must not fire**, and the UI must show *"Delivery % unavailable for this stock."* |
| **Additional constraint** | The **W1** timeframe has no delivery data. The flag is **D1 only**. On a W1 view the technicals tab must show nothing rather than an aggregate. |

**Wording template**
> `"Delivery was {cur:.1f}% on the last {n} sessions, against a 20-session average of {avg:.1f}% — {ratio:.0%} of normal participation."`

**Evidence**: `Delivery % (last bar)` → `21.4 %` · asOf `2026-10-01` ·
`20-bar average delivery %` → `48.7 %` · `Ratio to average` → `0.44` ·
`Consecutive collapsing bars` → `3` · `Traded quantity (last bar)` → `16,771,221 shares` ·
`Delivery quantity (last bar)` → `3,589,001 shares` · `Window coverage` → `18 of 20 bars (90 %)` ·
`Threshold`

**Threshold string**
`"Delivery % at or below 40% of its own 20-bar average for 2 consecutive sessions"`

---

## 8. Flag summary

| Id | Group | Tab | Fires from | Suppressed for `OTHER` |
|----|-------|-----|-----------|------------------------|
| `CFO_NEGATIVE_WHILE_PAT_POSITIVE` | `EARNINGS_QUALITY` | FUNDAMENTALS | ✔ | no |
| `RECEIVABLES_GROWING_FASTER_THAN_REVENUE` | `EARNINGS_QUALITY` | FUNDAMENTALS | ✔ (DSO proxy) | **yes** |
| `INVENTORY_DAYS_RISING` | `EARNINGS_QUALITY` | FUNDAMENTALS | ✔ | **yes** |
| `OTHER_INCOME_AS_PCT_OF_PAT` | `EARNINGS_QUALITY` | FUNDAMENTALS | ✔ | no (sector list suppresses) |
| `DEBT_TO_EQUITY_SPIKE` | `BALANCE_SHEET` | FUNDAMENTALS | ✔ | level check only |
| `INTEREST_COVERAGE_BELOW_2` | `BALANCE_SHEET` | FUNDAMENTALS | ✔ | no |
| `GOODWILL_ABOVE_20PCT_ASSETS` | `BALANCE_SHEET` | FUNDAMENTALS | ✘ **no input** | — |
| `PROMOTER_PLEDGING` | `BALANCE_SHEET` | FUNDAMENTALS | ✘ **no input** | — |
| `LOW_PE_WITH_FALLING_RETURNS` | `VALUATION_TRAP` | FUNDAMENTALS | ✔ | **yes** |
| `PE_RISING_WHILE_EARNINGS_FALL` | `VALUATION_TRAP` | FUNDAMENTALS | ✔ | no (cyclical carve-out) |
| `NEGATIVE_FCF_YIELD` | `VALUATION_TRAP` | FUNDAMENTALS | ✔ | no (severity cap) |
| `DEBT_TO_EBITDA_ELEVATED` | `BALANCE_SHEET` | FUNDAMENTALS | ✔ *(new, §6.3)* | level check only |
| `DEATH_CROSS` | `TECHNICAL` | TECHNICALS | ✔ | no |
| `RSI_OVERBOUGHT_OR_OVERSOLD` | `TECHNICAL` | TECHNICALS | ✔ | no |
| `DISTRIBUTION_DAY` | `TECHNICAL` | TECHNICALS | ✔ | no |
| `AT_52WEEK_LOW_WITH_DECLINING_OBV` | `TECHNICAL` | TECHNICALS | ✔ | no |
| `DELIVERY_COLLAPSE` | `TECHNICAL` | TECHNICALS | ✔ (coverage-gated) | no |

**15 flags fire. 2 are permanently suppressed for lack of input.**

---

## 9. Implementation contract

```kotlin
interface RedFlagCheck {
    val id: String
    val group: FlagGroup
    val tab: FlagTab
    /**
     * @return null when the flag cannot be evaluated (missing input, sector unresolved,
     *   below coverage floor). **Never** return a flag with fabricated evidence.
     */
    fun evaluate(snapshot: FundamentalsSnapshot, indicators: IndicatorSnapshot?): RedFlag?
}
```

- `RedFlag` is `[repo]` and already enforces non-empty `evidence` in `init`.
- `Evidence.asOf` is mandatory for every row whose value comes from a statement (the fiscal-year-end
  date) or from a candle (the bar date). `Evidence.source` is `SCREENER_IN` for screener.in rows,
  `NSE_BHAVCOPY` for bhavcopy candles, `YAHOO` for history, `DERIVED` for computed ratios.
- A parser failure must surface as `ParserHealth` `[repo]`, **not** as an empty flag list
  (plan.md §12: "Silent-empty is indistinguishable from 'no red flags' — the most dangerous possible
  failure"). `RedFlagEngine.run()` returns `List<RedFlag>` **plus** the `ParserHealth`; if
  `!health.ok` and `sectionsMissing` includes `balance-sheet`, `profit-loss` or `cash-flow`, the UI
  must show *"Fundamentals could not be read — red flags are incomplete"*, never an empty banner.
- Flags are **deterministic** (plan.md §8: "No AI, no inference") and unit-testable on the JVM with
  the five HTML fixtures as golden files.

---

## 10. Contradictions with `plan.md` — flagged, not silently resolved

| # | plan.md §8 says | Reality | Recommendation |
|---|------------------|---------|----------------|
| **R1** | "no receivables/inventory/goodwill lines, no D/E, no EV/EBITDA" | **Receivables:** not present as ₹, but `Debtor Days` **is** present (12-year series). **Inventory:** `Inventory Days` **is** present (12-year series). **Goodwill:** genuinely absent. **D/E:** derivable from `Borrowings / (Equity Capital + Reserves)`. **EV/EBITDA:** EV needs cash, which is not a separate line ⇒ genuinely not computable. | **Correct plan.md.** Enable F2 (DSO proxy), F3 (Inventory Days) and `RF-BS-1` (D/E). Keep goodwill and EV/EBITDA suppressed. Add `DEBT_TO_EBITDA_ELEVATED` instead of EV/EBITDA (§6.3). |
| **R2** | "P/E below **sector median** AND ROE declining" | A sector median P/E needs sector listing pages, which are **not** in the source set (plan.md §3.3 parses only `/company/{SYMBOL}/consolidated/`; §3.6 forbids search and pagination, and there is no sector-page fixture). | Replace with the **absolute `PE_BAND`** table (§5.1), which needs no extra fetch and no robots.txt question. Flagged: the bands are `[derived]` and must be reviewed against real data. |
| **R3** | "ROE declining" | `ROE` appears in `top-ratios` as a **single latest value** for non-financials; the **12-year series** in the `ratios` card is `ROCE %` (or `ROE %` for financials). | Use the **series**: `ROCE %` generally, `ROE %` for `BANKING`/`NBFC`/`INSURANCE`. This is also what plan.md §7.2 ranks first ("ROCE … hard to manipulate", ranked above ROE). Consistent, but worth stating. |
| **R4** | "Other income as % of PAT exceeding **a sector threshold**" | The book gives **no** number (§3.4 above). | Use 30 % / 50 % `[derived]`, with sector suppression for `BANKING`/`NBFC`/`INSURANCE`. Single constant table. |
| **R5** | "Interest coverage below 2.0" | Agrees with the book exactly (boundary between "weak" and "acceptable"). **No conflict.** | Keep 2.0; add `HIGH` below 1.0 from the book's own table. |
| **R6** | "Debt/Equity spiking YoY" — no threshold given | Book gives four **level** bands, not a spike definition. | Spike = `+0.50` absolute or `≥ +50 %` relative `[derived]`; level bands verbatim from the book. |
| **R7** | "Inventory days rising sharply YoY" — no threshold | Book gives no number. | 10 / 25 / 45 days `[derived]`. Calibrate against TITAN's real 169→222-day range. |
| **R8** | "Receivables growing materially faster than revenue (>10pp gap)" | The book expresses it in %, not pp, and shows `12%→14%` as Normal and `12%→65%` as warning. | 10 pp is plan.md's own number and is used as the `LOW` band; add 25 pp (`MEDIUM`) and 40 pp (`HIGH`). The ₹-based form needs receivables we do not have ⇒ DSO proxy. |
| **R9** | "52-week low + declining OBV" | Agrees. `TechnicalPolicy.WEEKS_PER_YEAR = 52` is a bar-count definition, not a calendar one — consistent with plan.md §5/§10 which stores exchange sessions only. **No conflict**, but the UI should say "last 52 sessions", not "52 weeks", or a user comparing with their broker will be confused. | Change the visible label to *"last 52 sessions (≈ 1 year)"*. |
| **R10** | "Delivery % collapsing vs 20-day average" | Book defines delivery % but sets **no** collapse threshold. | `[repo] DELIVERY_COLLAPSE_RATIO = 0.40`, 2 consecutive bars, 75 % coverage. Note the book's own warning that low delivery is **not** necessarily bearish, which caps severity at `MEDIUM`. |

---

## 11. Wording rules the UI must enforce

1. **Numbers, always.** A title without a number is a bare assertion → forbidden (plan.md §8.3).
2. **No verbs of advice.** Banned: *buy, sell, avoid, exit, entry, target, guarantee, will, should,
   risk, safe, dangerous, overvalued, cheap.* `HonestyContract` / `AdviceLanguage` `[repo]` is the
   enforcement point.
3. **No "this company is X".** The title names **what was observed**, never what it implies.
4. **Always one comparative.** A single value is not a flag. Every title contains either a change, a
   ratio to a stated reference, or a crossing of a stated band.
5. **Every flag must be able to answer "how do you know?"** with the `evidence` list, in one tap
   (plan.md §8.3).
6. **Show the count and the individual flags — never a composite risk score** (§1 rule 5).
7. **Caveats are content, not fine print.** For `RF-T-1`, `RF-T-2`, `RF-T-3`, `RF-T-5` the book's own
   caveat sentence is a first-class `evidence` row (`Evidence.label = "Caveat"`), not a tooltip.
8. **Suppressed ≠ clean.** A suppressed check renders as *"Not evaluated — <reason>"*, never as
   absence. Absence is only for a check that ran and did not trip.
9. **Every fundamentals figure shows `asOf`** (plan.md §1.4 rule 3). `Stock P/E` and `Market Cap` come
   from `top-ratios`, which is a **live scrape value** — their `asOf` is the **fetch date**, not a
   fiscal date, and the UI must not imply otherwise.

---

## 12. Test fixtures to encode

Golden-file assertions from the real captures (`data/src/test/resources/fixtures/`):

| Fixture | Asserted values |
|---------|-----------------|
| `RELIANCE.html` | `Stock P/E == 21.2` · `ROE == 8.91` · `ROCE == 10.3` · `Market Cap == 15,80,194` Cr · `Current Price == 1,168` · `Book Value == 668` · FY2026 `Borrowings == 402,962` · `Equity Capital == 13,532` · `Reserves == 890,498` ⇒ D/E `== 0.4458` · FY2026 `Operating Profit == 183,561` · `Depreciation == 58,946` · `Interest == 28,362` ⇒ IC `== 8.550` · `Other Income == 20,393` · `Net Profit == 88,167` ⇒ other income/PAT `== 23.13 %` · `CFO == 192,113` · `FCF == 70,023` · `CFO/OP == 113 %` · `Debtor Days` FY2026 `== 20` (FY2025 `== 16`) · `Inventory Days` FY2026 `== 88` |
| `TCS.html` | `Stock P/E == 14.0` (plan.md §5.1 flags a 7.18 % Yahoo conflict) |
| `HDFCBANK.html` | `ratios` card contains **only** `ROE %` ⇒ `RF-BS-2` must be suppressed for `Sector.BANKING`, and `ROE` series is available |
| `TITAN.html` | `Inventory Days` FY2015 `== 169`, FY2026 `== 222` ⇒ a 12-year range of 53 days; used to calibrate `RF-T`'s 10-day floor |
| all five | `grep -i goodwill\|pledg\|receivab` → **zero hits** ⇒ the three suppressed flags' unit tests assert `evaluate() == null` |

**Property tests:** `evaluate()` never returns a `RedFlag` whose `evidence` is empty (enforced by the
data class `[repo]`); never returns a flag for a suppressed input; every `Evidence.asOf` is non-null for
statement- and candle-derived rows; and running `run()` twice on the same fixture yields an identical
list (determinism, plan.md §8).