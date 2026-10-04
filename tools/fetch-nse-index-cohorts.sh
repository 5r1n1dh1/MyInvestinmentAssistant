#!/usr/bin/env bash
# Refresh the bundled NSE index constituent lists.
#
# NSE publishes index membership as plain CSV under nsearchives, with no API key:
#
#   https://nsearchives.nseindia.com/content/indices/ind_nifty50list.csv
#   https://nsearchives.nseindia.com/content/indices/ind_niftymidcap150list.csv
#   https://nsearchives.nseindia.com/content/indices/ind_niftysmallcap250list.csv
#
# Each row is `Company Name,Industry,Symbol,Series,ISIN Code`.
#
# ## Why this is a script and not a runtime fetch
# The app is 100% on-device with no server, and index membership changes **quarterly**. Committing
# the files with a fetch date keeps the cohort table reproducible: a backtest table built on
# "NIFTY 50 as of 2026-10-04" can be reproduced, while one built on a live fetch silently changes
# meaning every time NSE revises an index and the numbers on screen stay put.
#
# The app *can* also fetch at runtime (the "Update cohorts" button) — but it then shows the vintage
# it actually got, which is the only way a live list can be reported honestly.
#
# ## Why the headers are required
# NSE's edge answers a bare `curl` with a 403. A browser User-Agent and a Referer are both needed.
# Verified working from this machine on 2026-10-04.
#
# Usage:  ./tools/fetch-nse-index-cohorts.sh
# Then:   update BUNDLED_AS_OF in IndexCohort.kt to the date printed below.

set -euo pipefail

DEST="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/data/src/main/resources/assets/indices"
UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

declare -A LISTS=(
  [nifty50]="ind_nifty50list"
  [nifty_midcap150]="ind_niftymidcap150list"
  [nifty_smallcap250]="ind_niftysmallcap250list"
)

mkdir -p "$DEST"

echo "Fetching NSE index constituent lists -> $DEST"
for asset in "${!LISTS[@]}"; do
  name="${LISTS[$asset]}"
  url="https://nsearchives.nseindia.com/content/indices/${name}.csv"
  tmp="$(mktemp)"

  code=$(curl -sS -o "$tmp" -w '%{http_code}' --max-time 60 \
    -A "$UA" \
    -H "Referer: https://www.nseindia.com/" \
    -H "Accept: */*" \
    "$url")

  if [ "$code" != "200" ]; then
    echo "  FAIL $name: HTTP $code" >&2
    rm -f "$tmp"
    exit 1
  fi

  # The header is the only thing that proves this is a constituent list and not an HTML error page
  # served with a 200. NSE does both.
  header=$(head -1 "$tmp")
  case "$header" in
    *"Symbol"*) ;;
    *)
      echo "  FAIL $name: response is not a constituent CSV (first line: ${header:0:80})" >&2
      rm -f "$tmp"
      exit 1
      ;;
  esac

  rows=$(($(wc -l < "$tmp") - 1))
  cp "$tmp" "$DEST/$asset.csv"
  rm -f "$tmp"
  printf '  ok   %-20s %3s rows\n' "$asset" "$rows"
done

echo
echo "Now set BUNDLED_AS_OF in data/.../symbols/IndexCohort.kt to: $(date -u +%Y-%m-%d)"
echo "and update expectedSize in IndexCohort if NSE has revised a list's size."
