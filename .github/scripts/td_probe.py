#!/usr/bin/env python3
"""
Report the cross-source price agreement between two providers.

Lives here rather than as a heredoc inside `verify-live.yml` because a
heredoc body has to sit at column 0 to satisfy bash, and a column-0 line
terminates a YAML block scalar. The result was a workflow file that
looked fine and did not parse. A checked-in script is also easier to read
and to re-run locally.

Two modes:

    td_probe.py rows  <file>        -> "<rows> <first_close> <message>"
    td_probe.py agree <yahoo.json> <twelve.json> <symbol>  -> markdown rows

### Why "agreement" is the thing worth measuring

chart_plan.md §4.0 proposes a second price feed. The stated reason was
throughput, which is real but secondary. The stronger reason is that a
**single feed failing silently is invisible**: if Yahoo returns a
differently-adjusted or wrong series for one symbol, nothing in this
repository would notice — the data would simply be wrong, and every
measurement built on it would be wrong in the same direction.

Two independent providers disagreeing is a *loud* failure. That is the
property worth paying a second feed for.

### Why the tolerance is one rupee, not a percentage

A relative tolerance hides the interesting case. HDFCBANK has a real 1:2
bonus issue in this repository's own fixture, and BAJFINANCE a ~1:10
split; those are exactly the bars where one provider's `adjust` policy
and the other's differ, and they must not be smoothed away by a
percentage threshold.
"""

import datetime
import json
import sys

ONE_RUPEE = 1.0


def _read(path):
    try:
        with open(path) as handle:
            return json.load(handle)
    except Exception:
        return {}


def rows(path):
    """`<n rows> <first close or -> <message or ok>`, for one time_series file."""
    data = _read(path)
    values = data.get("values") or []
    if values:
        first = values[0].get("close", "-")
        print("%d %s ok" % (len(values), first))
        return
    # Twelve Data reports failures as `code`/`message` in a 200 body,
    # which is why an HTTP 200 alone proves nothing about this provider.
    print("0 - %s" % str(data.get("message") or data.get("Information") or "no rows")[:90])
    sys.exit(0)


def _yahoo_by_date(path):
    """`{ISO date: close}` from a Yahoo chart response."""
    out = {}
    try:
        result = _read(path)["chart"]["result"][0]
        stamps = result["timestamp"]
        closes = result["indicators"]["quote"][0]["close"]
        for stamp, close in zip(stamps, closes):
            if close is None:
                continue
            # Timezone-aware rather than the deprecated utcfromtimestamp: Yahoo's
            # daily stamps are midnight UTC, so the date part is stable either
            # way, but a DeprecationWarning in a probe report is noise.
            day = datetime.datetime.fromtimestamp(stamp, datetime.timezone.utc).strftime("%Y-%m-%d")
            out[day] = float(close)
    except Exception:
        pass
    return out


def _twelve_by_date(path):
    """`{ISO date: close}` from a Twelve Data time_series response.

    Twelve Data returns newest-first; both sides are keyed by date, so the
    ordering does not matter and a reversal here would not be caught by
    this function. That is deliberate — comparing by position instead of
    by date is the mistake this module exists to avoid.
    """
    out = {}
    try:
        for value in _read(path).get("values") or []:
            if value.get("close"):
                out[value["datetime"][:10]] = float(value["close"])
    except Exception:
        pass
    return out


def agree(yahoo_path, twelve_path, symbol):
    """Markdown rows comparing the same dates across both feeds."""
    yahoo = _yahoo_by_date(yahoo_path)
    twelve = _twelve_by_date(twelve_path)

    shared = sorted(set(yahoo) & set(twelve))
    if not shared:
        print(
            "| %s | — | — | — | **no overlapping dates** (Yahoo %d rows, Twelve Data %d) |"
            % (symbol, len(yahoo), len(twelve))
        )
        return

    for day in shared[:3]:
        y_value, t_value = yahoo[day], twelve[day]
        diff = abs(y_value - t_value)
        verdict = "**MISMATCH**" if diff > ONE_RUPEE else "agrees"
        print(
            "| %s | %s | %.2f | %.2f | %.2f (%s) |"
            % (symbol, day, y_value, t_value, diff, verdict)
        )


def main():
    if len(sys.argv) < 2:
        print("usage: td_probe.py rows <file> | agree <yahoo> <twelve> <symbol>")
        return 1
    if sys.argv[1] == "rows":
        rows(sys.argv[2])
    elif sys.argv[1] == "agree":
        agree(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        print("unknown mode: %s" % sys.argv[1])
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())