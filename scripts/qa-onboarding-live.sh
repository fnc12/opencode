#!/usr/bin/env bash
# Live onboarding smoke test — real relay, real connector, real OpenCode. NO mocks.
#
# Given a claim code (minted for an account via the web onboarding — pay, or a
# free first-100 signup, then Connect), this stands up an ISOLATED connector on a
# host that runs OpenCode, has it claim the code and register with the relay,
# then verifies that an app-facing request reaches that OpenCode through the relay
# (and that a wrong tunnel token is rejected). It never touches an existing
# connector: it uses a temporary SHUBAT_CONFIG_DIR and kills its process after.
#
# Usage:
#   scripts/qa-onboarding-live.sh <CLAIM_CODE> [RELAY_BASE] [SSH_HOST]
# Defaults: RELAY_BASE=https://relay.shubat.org  SSH_HOST=casablanca
#
# The host must already run OpenCode and have a connector binary + connector.env
# at ~/shubat-connector/ (for the binary, RELAY_URL, OPENCODE_URL/PASSWORD).
# To get a CLAIM_CODE end to end:
#   1) POST <RELAY_BASE>/login with your email, open the magic link, then
#      /account -> Connect (or subscribe), which shows `curl .../i/<CODE> | sh`.
#   2) pass <CODE> here.
set -euo pipefail

CODE="${1:?usage: qa-onboarding-live.sh <CLAIM_CODE> [RELAY_BASE] [SSH_HOST]}"
RELAY_BASE="${2:-https://relay.shubat.org}"
HOST="${3:-casablanca}"

echo "== live onboarding check: code=$CODE relay=$RELAY_BASE host=$HOST =="

ssh -o ConnectTimeout=15 "$HOST" "bash -s" <<REMOTE
set -euo pipefail
ENV=~/shubat-connector/connector.env
BIN=~/shubat-connector/connector
RELAY_URL=\$(grep '^RELAY_URL=' "\$ENV" | cut -d= -f2-)
OCURL=\$(grep '^OPENCODE_URL=' "\$ENV" | cut -d= -f2-)
OCPW=\$(grep '^OPENCODE_PASSWORD=' "\$ENV" | cut -d= -f2- || true)
DIR=\$(mktemp -d)
trap 'kill \$PID 2>/dev/null || true; rm -rf "\$DIR"' EXIT

SHUBAT_CONFIG_DIR=\$DIR RELAY_URL=\$RELAY_URL CLAIM_CODE=$CODE OPENCODE_URL=\$OCURL OPENCODE_PASSWORD=\$OCPW \
  nohup "\$BIN" > "\$DIR/log" 2>&1 &
PID=\$!

# Wait for the connector to claim + register (persists tunnelId/token).
for i in \$(seq 1 25); do
  sleep 1
  if [ -f "\$DIR/connector.json" ] && python3 -c "import json,sys;d=json.load(open('\$DIR/connector.json'));sys.exit(0 if d.get('tunnelId') and d.get('token') else 1)" 2>/dev/null; then
    break
  fi
done
ID=\$(python3 -c "import json;print(json.load(open('\$DIR/connector.json'))['tunnelId'])" 2>/dev/null || true)
TOKEN=\$(python3 -c "import json;print(json.load(open('\$DIR/connector.json'))['token'])" 2>/dev/null || true)
if [ -z "\$ID" ] || [ -z "\$TOKEN" ]; then
  echo "FAIL: connector did not claim/register"; grep -iE 'error|claim' "\$DIR/log" | tail -5; exit 1
fi
echo "connector registered: tunnel=\$ID"
sleep 1

OK=\$(curl -s -m8 -o /dev/null -w '%{http_code}' -H "X-Tunnel-Token: \$TOKEN" "$RELAY_BASE/t/\$ID/global/health")
BAD=\$(curl -s -m8 -o /dev/null -w '%{http_code}' -H "X-Tunnel-Token: nope" "$RELAY_BASE/t/\$ID/global/health")
echo "reach OpenCode via relay: \$OK (want 200) | wrong token: \$BAD (want 401)"
[ "\$OK" = "200" ] && [ "\$BAD" = "401" ] || { echo "FAIL: reachability/auth"; exit 1; }
echo "PASS: real onboarding path reaches real OpenCode through the real relay"
REMOTE
