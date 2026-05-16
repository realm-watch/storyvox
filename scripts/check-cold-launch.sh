#!/usr/bin/env bash
# Issue #618 — cold-launch perf guard.
#
# Reads the StartupBenchmark JSON output (produced by
# `:baselineprofile:connectedBenchmarkAndroidTest`) and exits non-zero
# if the median cold-launch time exceeds the thresholds pinned in
# baselineprofile/.../ColdLaunchThresholds.kt.
#
# Usage:
#   scripts/check-cold-launch.sh [report-dir] [form-factor]
#     report-dir   defaults to baselineprofile/build/outputs/connected_android_test_additional_output/benchmarkBenchmark
#     form-factor  one of `tablet`, `phone` (default `tablet`)
#
# Threshold source: the script reads the constants out of
# ColdLaunchThresholds.kt rather than duplicating the numbers, so a
# threshold change is a single-file diff in Kotlin land — the script
# picks it up automatically on the next CI run.
#
# This script is NOT wired into the PR-time android.yml because PRs
# don't have a connected device. It runs on tag pushes only, after
# the benchmark step finishes. Skips quietly (exit 0) when no report
# is present, so a release without an attached device doesn't fail.

set -euo pipefail

REPORT_DIR="${1:-baselineprofile/build/outputs/connected_android_test_additional_output/benchmarkBenchmark}"
FORM_FACTOR="${2:-tablet}"

THRESHOLDS_FILE="baselineprofile/src/main/kotlin/in/jphe/storyvox/baselineprofile/ColdLaunchThresholds.kt"
if [ ! -f "$THRESHOLDS_FILE" ]; then
    echo "check-cold-launch: $THRESHOLDS_FILE not found — has the module moved? exiting OK to avoid blocking unrelated CI." >&2
    exit 0
fi

# Parse the budget constants from the Kotlin file. The format is:
#   const val TABLET_BUDGET_MS: Long = 1_500L
# We strip underscores + the trailing `L` so bash arithmetic handles
# the number cleanly.
parse_budget() {
    local key="$1"
    grep -E "const val ${key}: Long" "$THRESHOLDS_FILE" \
        | head -1 \
        | sed -E 's/.*= *([0-9_]+)L.*/\1/' \
        | tr -d '_'
}

TABLET_BUDGET_MS=$(parse_budget TABLET_BUDGET_MS)
PHONE_BUDGET_MS=$(parse_budget PHONE_BUDGET_MS)

case "$FORM_FACTOR" in
    tablet) BUDGET_MS="$TABLET_BUDGET_MS" ;;
    phone)  BUDGET_MS="$PHONE_BUDGET_MS"  ;;
    *)
        echo "check-cold-launch: unknown form-factor '$FORM_FACTOR' (expected tablet|phone)" >&2
        exit 2
        ;;
esac

if [ ! -d "$REPORT_DIR" ]; then
    echo "check-cold-launch: no benchmark output at $REPORT_DIR — skipping (this is normal on PR / no-device runs)."
    exit 0
fi

# Macrobenchmark writes one .json per @Test method. We care about
# `startupBaselineProfile` — that's the with-profile cold-launch number,
# the one users actually experience.
REPORT_JSON=$(find "$REPORT_DIR" -name 'StartupBenchmark_startupBaselineProfile*.json' | head -1)
if [ -z "$REPORT_JSON" ]; then
    echo "check-cold-launch: no StartupBenchmark report under $REPORT_DIR — skipping."
    exit 0
fi

# Pull the timeToInitialDisplayMs median. Macrobench JSON has a
# `benchmarks[0].metrics.timeToInitialDisplayMs.runs` array of medians
# (one per iteration); we take the middle value of those.
if command -v jq >/dev/null 2>&1; then
    MEDIAN_MS=$(jq -r '
        .benchmarks[0].metrics.timeToInitialDisplayMs.runs
        | sort
        | .[length / 2 | floor]
    ' "$REPORT_JSON")
else
    # jq-less fallback: use Python (always installed on the runner).
    MEDIAN_MS=$(python3 -c "
import json, sys
d = json.load(open('$REPORT_JSON'))
runs = sorted(d['benchmarks'][0]['metrics']['timeToInitialDisplayMs']['runs'])
print(int(runs[len(runs)//2]))
")
fi

# Strip decimal if present (median is an int in ms, but defensive).
MEDIAN_MS=${MEDIAN_MS%.*}

echo "check-cold-launch: form-factor=$FORM_FACTOR  median=${MEDIAN_MS}ms  budget=${BUDGET_MS}ms"
if [ "$MEDIAN_MS" -gt "$BUDGET_MS" ]; then
    echo "::error::Cold-launch median ${MEDIAN_MS}ms exceeded the ${FORM_FACTOR} budget of ${BUDGET_MS}ms (Issue #618)."
    exit 1
fi

echo "check-cold-launch: PASS — cold launch within budget."
exit 0
