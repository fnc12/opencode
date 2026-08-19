#!/usr/bin/env bash
#
# One-command local test + coverage runner for the mobile clients + relay.
# No CI needed — run this before every push. Prints a combined coverage summary.
#
# Usage:
#   packages/mobile-test.sh            # everything: android + ios + relay
#   packages/mobile-test.sh android    # just android unit tests + kover
#   packages/mobile-test.sh ios        # just ios unit tests + xccov
#   packages/mobile-test.sh relay      # just the go relay tests
#   packages/mobile-test.sh snapshot   # record/verify screenshot tests
#
# iOS runs on a 26.x-runtime simulator (the 18.3.1 sim crashes the test host
# with "Mach error -308 server died" against SDK 26.2 — see mobile-feature-progress).

set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_SIM_NAME="${IOS_SIM_NAME:-iPhone 17}"   # any booted 26.x sim; override via env
FAIL=0

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
red()  { printf '\033[1;31m%s\033[0m\n' "$1"; }
grn()  { printf '\033[1;32m%s\033[0m\n' "$1"; }

run_android() {
  bold "── Android: unit tests + Kover coverage ──"
  ( cd "$ROOT/android" && ./gradlew :app:testDebugUnitTest :app:koverXmlReportDebug ) || { red "Android tests FAILED"; FAIL=1; return; }
  local xml="$ROOT/android/app/build/reports/kover/reportDebug.xml"
  [ -f "$xml" ] && python3 "$ROOT/android/scripts/kover-summary.py" "$xml"
}

run_ios() {
  bold "── iOS: unit tests + code coverage ──"
  local sim
  sim=$(xcrun simctl list devices available 2>/dev/null | grep -E "$IOS_SIM_NAME \(" | head -1 | grep -oE '[0-9A-F-]{36}')
  [ -z "$sim" ] && { red "no available '$IOS_SIM_NAME' simulator"; FAIL=1; return; }
  xcrun simctl boot "$sim" 2>/dev/null || true
  local result="$ROOT/ios/.coverage.xcresult"
  rm -rf "$result"
  ( cd "$ROOT/ios" && xcodebuild test -project OpenCode.xcodeproj -scheme OpenCode \
      -sdk iphonesimulator -destination "id=$sim" -only-testing:OpenCodeTests \
      -enableCodeCoverage YES -resultBundlePath "$result" -quiet ) \
    || { red "iOS tests FAILED"; FAIL=1; return; }
  xcrun xccov view --report --only-targets "$result" 2>/dev/null | grep -iE "OpenCode\.app" || true
}

run_relay() {
  bold "── Relay: go tests + coverage ──"
  ( cd "$ROOT/relay" && go test -cover ./... ) || { red "Relay tests FAILED"; FAIL=1; }
}

run_snapshot() {
  bold "── Android: Paparazzi screenshot verify ──"
  ( cd "$ROOT/android" && ./gradlew :app:verifyPaparazziDebug ) || { red "Android snapshots FAILED (run recordPaparazziDebug to update)"; FAIL=1; }
  bold "── iOS: snapshot tests are part of OpenCodeTests (run: ios) ──"
}

case "${1:-all}" in
  android)  run_android ;;
  ios)      run_ios ;;
  relay)    run_relay ;;
  snapshot) run_snapshot ;;
  all)      run_relay; run_android; run_ios ;;
  *) echo "unknown target: $1"; exit 2 ;;
esac

echo
[ "$FAIL" -eq 0 ] && { grn "ALL GREEN"; exit 0; } || { red "SOME TESTS FAILED"; exit 1; }
