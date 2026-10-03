# Data notes — what the sources actually contain

Everything here was measured against live responses while building the app, not inferred from
documentation. Each claim carries the observation that produced it. Where something could not
be verified from this machine it says so plainly rather than being quoted as fact.

Fixtures referenced throughout are committed under `data/src/test/resources/fixtures/` and
`domain/src/test/resources/fixtures/`, so every parser test runs offline against the exact bytes
the sources returned.

---

## 1. NSE EOD bhavcopy — primary price source (plan.md §3.1)

```
https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_YYYYMMDD_F_0000.csv.zip
```

No auth, no key, no cookie. One fetch is the whole market.

### The mirror is not optional

**The documented primary host does not answer from every network.** Measured 2026-10-02:

| Host | Result |
|---|---|
| `nsearchives.nseindia.com` | HTTP/2 `INTERNAL_ERROR`, then a 40 s timeout — 0 bytes |
| `archives.nseindia.com/content/cm/…` | **200, 208,712 bytes**, byte-identical zip |

`NseBhavcopySource` therefore tries `nsearchives` first and falls back to `archives`. The
fallback is not defensive padding: without it the price layer does not work at all from here.

A 404 is *not* short-circuited. `NOT_PUBLISHED` is only concluded when **every** candidate host
answers 404/410 — declaring "not published" from one host could silently drop a trading day for
the cost of one extra request per day.

### Verified contents (2026-10-01)

| Field | Value |
|---|---|
| Rows | 3,712 instruments |
| Filtered (`EQ` + `SM`) | 3,051 |
| RELIANCE `ClsPric` | `1167.70` |
| RELIANCE `ISIN` | `INE002A01018` |
| RELIANCE `TtlTradgVol` | `16,771,221` |
| RELIANCE `TtlTrfVal` | `19,674,608,846.20` |

Series distribution: `EQ` 2662, `SM` 389, `BE` 246, `ST` 87, `GS` 55, `GB` 44 — 50 distinct
codes in all. Only `EQ` and `SM` are kept (plan.md §3.1).

Two header typos were caught by the fixture gate and are now permanently guarded by a test that
compares every parser constant against the real header cell: `LwPric → LWPRIC` and
`FinInstrmNm → **FININSTRMNM**` (the `m` survives — `FININSTRNM` is wrong).

### Retention

Bhavcopy keeps roughly 18 months (2026 ✓, 2025 ✓, 2024 partial, 2023 ✗). It cannot rebuild a
one-year history alone, which is why `HistoryFallbackPlanner` and `YahooHistorySource` exist.

---

## 2. Yahoo Finance — history backfill (plan.md §3.2)

### Blocked from this machine, and that is a finding

`https://query1.finance.yahoo.com/v1/test/getcrumb` returns **429** even after the documented
cookie handshake with a browser User-Agent, and `fc.yahoo.com` returns 404 while still setting
its `d=` cookie. The crumb charset allow-list therefore fails closed with a clear message rather
than sending a malformed request.

**What this means for the build:** the P3 gate — *fetch 2 y for 20 large caps, assert ≥490 rows*
— **was not executed.** `YahooHistorySource` implements the documented handshake and is proven
against a fake transport (sequence, host fallback, 429 → invalidate → single retry), but the
live row counts are unverified. The 500-daily / 261-weekly shapes are plan.md §14's figures,
reproduced by a documented generator, not a live capture.

Unknowns that need one live sample: whether Yahoo accepts the real cookie+crumb sequence, the
actual cookie names, and whether Yahoo stamps weekly bars on the week start or its last session.

### The alternative that did work

NSE's archive serves the *same* official EOD OHLCV, so the real-data tests use it instead of
Yahoo. See §5.

---

## 3. Legacy delivery-percentage file (plan.md §3.5)

`Availability UNVERIFIED in the plan — now verified.` Measured 2026-10-02:

```
https://archives.nseindia.com/products/content/sec_bhavdata_full_01102026.csv  → 200, 401,205 bytes
```

3,534 rows; 3,262 carry a numeric `DELIV_PER`. RELIANCE 61.24, HDFCBANK 63.31, INFY 51.96,
TCS 54.90, 20MICRONS 46.10. The `EQ`+`SM` symbol set is identical to the filtered bhavcopy set
(3,051 both directions), so delivery attaches to every retained instrument.

**Header landmine confirmed:** every column name has a leading space —
`SYMBOL, SERIES, DATE1, …`, and dates are `01-Oct-2026`. `row["CLOSE_PRICE"]` throws; the parser
normalises headers.

It stays **optional**: the price layer never depends on it. `Candle.deliveryPercent` is `null`
when it is missing, and the delivery-collapse red flag abstains with a stated reason instead of
guessing.

---

## 4. screener.in — fundamentals (plan.md §3.3)

`/company/{SYMBOL}/consolidated/` → 200, 218–242 KB. Requires
`Referer: https://www.screener.in/`. Five golden pages are committed: RELIANCE, TCS, HDFCBANK,
INFY, TITAN.

### Both parser landmines are real

The value span's class is `"nowrap value"`, **not** `"value"`, and the digits sit in a child
`<span class="number">`. Matching is by class **substring**, so `"value nowrap"` also works.
`profit-loss` has a different DOM shape from the other tables and needs its own parser.

### What the page publishes (all five fixtures)

| | RELIANCE | TCS | HDFCBANK | INFY | TITAN |
|---|---|---|---|---|---|
| Market cap (₹ Cr) | 1,580,194 | 750,753 | 1,111,989 | 420,032 | 400,898 |
| P/E | 21.2 | 14.0 | 14.1 | 13.5 | 68.7 |
| ROCE % | 10.3 | 63.0 | 7.02 | 40.0 | 20.5 |
| ROE % | 8.91 | 51.8 | 13.6 | 31.9 | 37.7 |
| Book value (₹) | 668 | 296 | 390 | 225 | 177 |
| `quarters` rows | 13 | 13 | 13 | 13 | 13 |

Plan.md §14's "163–169 `info` fields" is **not reproducible** on today's pages: they carry 9
fields in `#company-info` and 9 in `#top-ratios`. The parser captures whatever block is published
and ignores additions gracefully.

The §5.1 conflict measurements reproduce exactly: RELIANCE P/E 21.2 vs 21.142 → **0.27%**
(agreed), TCS P/E 14.0 vs 15.083 → **7.18%** (conflict), TCS ROE 51.8 vs 47.743 → **8.50%**
(conflict).

### Three ratios the plan assumes exist and do not

Measured across all five fixtures with `grep`, counting occurrences of `receivab`, `goodwill`,
`pledg`, `Gross Profit`, `Cost of Goods`, `EBITDA`:

| Ratio | Verdict | How the app behaves |
|---|---|---|
| Trade receivables | **absent** from the balance sheet | substituted by the `ratios` card's 12-year **Debtor Days** series as a DSO proxy |
| Inventory | **absent** | `ratios` card **Inventory Days** series (RELIANCE Mar 2026 = 88) |
| Goodwill | **absent** | flag implemented, **always abstains**, and says why |
| Promoter pledge | **absent** | flag implemented, **always abstains**, and says why |
| Gross margin | **absent** — no COGS line anywhere | `RatioState.NOT_SOURCED`, ranked #7 in plan §7.2 with no source |
| EV/EBITDA | **absent** — no cash or EV line | `NOT_SOURCED`. Note `EV / EBITDA` appears in RELIANCE.html as an AJAX *chart button*, not a published figure — a grep hit there is not data |

So plan.md §8.1's receivables-vs-revenue and inventory-days flags ship (via day-count proxies),
while goodwill and pledging ship as **declared coverage gaps**: implemented, unit-tested against
synthetic input, and reported as "not evaluable from our sources" on every real symbol. The
alternative — substituting another line — would misstate the ratio.

### robots.txt (plan.md §3.6, binding)

Disallowed: `/*?q=`, `/*?page=`, `/*?sort=`, `/*?limit=`, `/user/*`,
`/company/source/quarter/*`. The `Raw PDF` row is deliberately **not** fetched because it links
into the disallowed `/company/source/quarter/*` path. Search is local (the bundled
`EQUITY_L.csv`), which is both compliant and instant. A test asserts the fetcher can only build
the one permitted URL shape.

---

## 5. NSE symbol master (plan.md §3.4)

`EQUITY_L.csv` → 200, 2,593 rows, bundled in `data/src/main/resources/assets/`. Headers have
leading spaces: `SYMBOL, NAME OF COMPANY, SERIES, …`.

TATAMOTORS is the interesting row, and the plan's account of it needed correcting:

- The master now lists **`TMCV`** (Tata Motors Limited, `INE1TAE01010`, listed **2025-11-12**) and
  **`TMPV`** (Tata Motors Passenger Vehicles, `INE155A01022`, listed 1998-07-22).
- `TATAMOTORS` appears in the bhavcopy for only the **first 140 of 372** sessions in our window
  and never returns — it `SYMBOL_DISAPPEARED` rather than "resumed".
- `TATAMOTORSPV` has **0 rows** anywhere.
- So `resolve("TATAMOTORS")` returns **`TMCV`**, with `TMPV` offered as an alternative. `TMPV` is
  a *different company* that inherited the retired ISIN; treating the passenger-vehicle entity as
  the successor would send a user to the wrong company with full confidence.

`resolve()` never returns zero results silently: the ladder is exact → normalised → corporate-
action alias → ISIN lookup → fuzzy edit distance → explicit unresolved, each step reporting which
rung fired.

Reconciling the master against a day's bhavcopy (unfiltered): 2,313 matched, 280 master-only
(`BE` 248 + `BZ` 26 + `EQ` 6), 738 bhavcopy-only (`EQ` 349 + `SM` 389 — SMEs exist only in the
bhavcopy, never in the master). **0** ISIN mismatches on this date.

---

## 6. The committed EOD history fixture

`domain/src/test/resources/fixtures/eod_history.json` — 1.09 MB, 372 real sessions
(2025-04-01 → 2026-10-01) × 41 symbols, assembled from the NSE archive because Yahoo is blocked.

Schema: `{"source", "note", "seriesFilter", "symbols": {"RELIANCE": [{"d","o","h","l","c","v"}]}}`.

39 symbols carry the full 372 bars; `TATAMOTORS` has 140 and `TATAMOTORSPV` has 0 — which is
exactly why those two are the resolver's fixtures.

### ⚠️ It contains four real, unadjusted corporate actions

| Symbol | Date | Move | Almost certainly |
|---|---|---|---|
| HDFCBANK | 2025-08-26 | 1964.1 → 973.4 (−50.4%) | 1:2 split or bonus |
| BAJFINANCE | 2025-06-16 | 9331 → 938 (−89.9%) | ~1:10 split |
| KOTAKBANK | 2026-01-14 | 2132.6 → 421 (−80.3%) | ~1:5 split |
| TATAMOTORS | 2025-10-14 | 660.75 → 395.45 (−40.2%) | demerger-era consolidation |

These are **real market events, not fixture bugs**, and they are load-bearing for honesty:

- A −90% bar destroys a 200-day average for weeks afterwards and will trip RSI, trend and
  volume rules that have nothing to do with the business.
- The chart-pattern agent measured detection counts both raw and with those sessions removed and
  found them **identical** — the structural gates are robust to it.
- The technical red-flag agent measured each rule's fire rate over a rolling 4,407
  symbol-session walk, because a point-in-time rate cannot distinguish a working rule from a
  broken one: `RSI_OVERSOLD` 3.34%, `DISTRIBUTION_DAY` 1.41%, `52-week-low + OBV` 6.85% — all
  plausible. **`DEATH_CROSS` at 49.83%** is not a bug: it is a *state*, not an event, and the
  measurement was independently reproduced in Python at 49.49%. The UI handles this by leading
  with the cross date and sessions elapsed, so a persistent condition is not presented as news.

Yahoo supplies `adjclose` and a `events.splits` payload, so the app has a path to adjusted
prices; the committed fixture is raw on purpose, because a fixture that silently hid four real
corporate actions would be a fixture that taught the tests to be comfortable with them.

---

## 7. What could not be verified from this machine

| Claim | Status |
|---|---|
| Yahoo cookie+crumb against the live service | **Unverified** — 429 from this IP; proven against a fake transport only |
| P3 gate: ≥490 rows for 20 large caps | **Not executed** — needs a network Yahoo answers on |
| Bhavcopy 404-before-publication timing | Partially — 18:32 IST on 2026-10-02 gave 404 for today, 200 for 2026-10-01, consistent with plan.md §3.1 |
| Weekly bar stamping convention (week start vs last session) | **Unverified** — no live Yahoo sample |
| Split payload shape (`numerator`/`denominator`) | From the documented schema, not a capture |
| Android compile of `:app` / `:android-data` | **Not possible here** — no Android SDK; see `docs/ENVIRONMENT.md` |