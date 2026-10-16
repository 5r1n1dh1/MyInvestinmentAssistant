# Live source probe — measured findings

Evidence from GitHub Actions run **38949742442** (2026-10-16), triggered via
`.github/workflows/verify-live.yml` on demand. Raw report artifact:
`live-probe-report`. This file records the *conclusions* — several of which
reverse what the raw probe appeared to say.

Every probe ran from GitHub's network. A failure here is a fact about this
network, not a verdict on the source. The app's own fallbacks are documented
in [DATA-NOTES.md](DATA-NOTES.md).

---

## 1. The price layer is healthy

| Source | Result |
|---|---|
| `archives.nseindia.com` bhavcopy (app's primary) | **200** · 205,668 B |
| `archives.nseindia.com` EQUITY_L (symbol master) | **200** · 183,984 B |
| `archives.nseindia.com` legacy delivery file | **200** · 397,333 B |

The app's mirror choice is vindicated rather than merely tolerated.
`nsearchives.nseindia.com` — the *documented* primary — fails from GitHub's
runners with `HTTP/2 stream not closed cleanly: INTERNAL_ERROR` on every
attempt. So the fallback is not a convenience; from this network it is the
only host that answers.

## 2. Yahoo answers — and that unlocks the benchmark series

| Step | Result |
|---|---|
| `fc.yahoo.com` cookie seed | 404 + cookie (the documented good case) |
| `query1.finance.yahoo.com/v1/test/getcrumb` | **200** — crumb obtained |
| `v8/finance/chart/RELIANCE.NS` with cookie + crumb | **200** · 1,666 B |

**This is the finding that matters most for W3.** The Yahoo path was recorded
in [VERIFICATION.md](VERIFICATION.md) as a gate never executed. It executes.

Consequence: the NSE index-CSV route is dead — all three candidate shapes 404
(`ind_close_all` on `archives`, on `nsearchives`, and on the `products` path) —
but the benchmark series does **not** need it. Yahoo serves `^NSEI` (NIFTY 50),
`^NSEMDCP50` (MIDCAP 50) and `^NSEMDCP150` on the same endpoint that just
answered 200 for an equity. Relative strength stops being permanently
unscored. Until W3 lands, the engine's honest `benchmarkCandles = null` path
remains correct — this finding says that path has an exit, not that it was wrong.

## 3. Bank ratios are NOT sourceable from screener.in — promotion rejected

The probe greps the HDFCBANK page for nine bank-ratio labels and reports:

```
✅ Gross NPA   (2 occurrences)     ✅ Net NPA  (1)     ✅ CASA  (1)
❌ Net Interest Margin   ❌ Provision Coverage   ❌ GNPA   ❌ NNPA   ❌ Stage 2
```

**Read that table and you would conclude three RatioIds should be promoted
from `NOT_SOURCED` to `AVAILABLE`. That conclusion is wrong**, and the probe's
own numbers give it away: one occurrence each, on a 218 KB page.

Direct inspection of the fetched page:

- Those labels appear inside **60 `highlight-cell` elements** — screener.in's
  paywall. The label renders; the value cell is empty and is followed by a
  `Login` button.
- The standalone ratio table carries the *rows* with no values at all:
  `<tr class=""><td class="text border-right">Gross NPA ratio<br>…Standalone data</span></td></tr>`
- For contrast, a ratio the app already sources renders a number:
  `<span class="name">ROCE</span> … <span class="number">7.02</span>`

**Verdict:** NIM / GNPA / NNPA / CASA / PCR stay `NOT_SOURCED`. The labels are
present; the values are behind a login the app does not have and will not
acquire (plan.md §3 — no credentials, no paid data). Recording a ratio we
cannot see would be exactly the fabrication the honesty contract forbids.

This converts gap **B4** from "blocked pending a re-check" to **closed by
evidence**, and it fixes the failure mode the probe itself had: a label grep is
not a value parse. Any future promotion must pin a **golden fixture** and
assert a parsed number, not a string match.

The honest alternative — and what W6 teaches instead — is *where to verify*
each ratio yourself: the bank's annual report, and the RBI's Financial
Stability Report for the system-wide picture.

## 4. Corporate events remain unsourced

Announcements, bulk deals, block deals and the ASM/GSM lists all 404 on the
archives host; the `www.nseindia.com` variants fail with the same HTTP/2
error as `nsearchives`. Gap **B3** stays open as Class-B: the app flags
corporate actions it detects from *price discontinuity* and tells the user
where to confirm, rather than asserting an event it never saw.

---

## Summary for the plan

| Question | Answer | Consequence |
|---|---|---|
| Index series for relative strength | Yahoo `^NSEI` answers 200 | W3 unblocked; NSE CSV route dropped |
| Yahoo P3 gate | **executed, passes** | history backfill is viable |
| Bank ratios on screener.in | labels only, values paywalled | W4 promotion **rejected**; B4 closed by evidence |
| Corporate events | 404 from this network | B3 stays Class-B (where-to-verify) |
| Bhavcopy / EQUITY_L / delivery | 200 on the archives mirror | primary host choice vindicated |

*Re-run the probe any time with `gh workflow run verify-live.yml`. It is
on-demand by owner decision — no cron, no scheduled minutes.*
