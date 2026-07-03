#!/bin/sh
# Shubat connector installer. Downloads the connector, installs it as a
# per-user service (launchd on macOS, systemd --user on Linux), redeems the
# claim code, and prints the pairing link for the mobile app.
#
# Served personalized by the relay at /i/<claim-code>; RELAY_BASE and CLAIM_CODE
# are injected above this line. Run manually with those two as env vars.
set -eu

: "${RELAY_BASE:?RELAY_BASE not set}"
: "${CLAIM_CODE:?CLAIM_CODE not set}"

info() { printf '\033[1;36m==>\033[0m %s\n' "$1"; }
err()  { printf '\033[1;31mError:\033[0m %s\n' "$1" >&2; exit 1; }

# --- detect platform -------------------------------------------------------
os=$(uname -s)
arch=$(uname -m)
case "$os" in
  Darwin) os=darwin ;;
  Linux)  os=linux ;;
  *) err "unsupported OS: $os" ;;
esac
case "$arch" in
  arm64|aarch64) arch=arm64 ;;
  x86_64|amd64)  arch=amd64 ;;
  *) err "unsupported arch: $arch" ;;
esac

relay_ws=$(printf '%s' "$RELAY_BASE" | sed -e 's|^http|ws|')/connector
dir="$HOME/.shubat"
bin="$dir/connector"
mkdir -p "$dir"

# --- download binary -------------------------------------------------------
info "Downloading connector ($os-$arch)…"
url="$RELAY_BASE/dl/connector-$os-$arch"
if command -v curl >/dev/null 2>&1; then
  curl -fsSL "$url" -o "$bin" || err "download failed: $url"
else
  wget -qO "$bin" "$url" || err "download failed: $url"
fi
chmod +x "$bin"

# --- collect the local OpenCode server details -----------------------------
OPENCODE_URL="${OPENCODE_URL:-http://127.0.0.1:4096}"
if [ -z "${OPENCODE_PASSWORD:-}" ] && [ -r /dev/tty ]; then
  printf 'OpenCode server URL [%s]: ' "$OPENCODE_URL" > /dev/tty
  read -r reply < /dev/tty || reply=""
  [ -n "$reply" ] && OPENCODE_URL="$reply"
  printf 'OpenCode password (blank if none): ' > /dev/tty
  stty -echo 2>/dev/null || true
  read -r OPENCODE_PASSWORD < /dev/tty || OPENCODE_PASSWORD=""
  stty echo 2>/dev/null || true
  printf '\n' > /dev/tty
fi
OPENCODE_PASSWORD="${OPENCODE_PASSWORD:-}"

# --- install as a service --------------------------------------------------
if [ "$os" = darwin ]; then
  label="org.shubat.connector"
  plist="$HOME/Library/LaunchAgents/$label.plist"
  mkdir -p "$HOME/Library/LaunchAgents"
  cat > "$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$label</string>
  <key>ProgramArguments</key><array><string>$bin</string></array>
  <key>EnvironmentVariables</key><dict>
    <key>SHUBAT_CONFIG_DIR</key><string>$dir</string>
    <key>RELAY_URL</key><string>$relay_ws</string>
    <key>CLAIM_CODE</key><string>$CLAIM_CODE</string>
    <key>OPENCODE_URL</key><string>$OPENCODE_URL</string>
    <key>OPENCODE_PASSWORD</key><string>$OPENCODE_PASSWORD</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardErrorPath</key><string>$dir/connector.log</string>
  <key>StandardOutPath</key><string>$dir/connector.log</string>
</dict></plist>
PLIST
  launchctl unload "$plist" 2>/dev/null || true
  launchctl load "$plist"
  info "Installed launchd service $label"
else
  unit_dir="$HOME/.config/systemd/user"
  mkdir -p "$unit_dir"
  cat > "$unit_dir/shubat-connector.service" <<UNIT
[Unit]
Description=Shubat connector (OpenCode Remote)
After=network-online.target

[Service]
ExecStart=$bin
Restart=always
RestartSec=3
Environment=SHUBAT_CONFIG_DIR=$dir
Environment=RELAY_URL=$relay_ws
Environment=CLAIM_CODE=$CLAIM_CODE
Environment=OPENCODE_URL=$OPENCODE_URL
Environment=OPENCODE_PASSWORD=$OPENCODE_PASSWORD

[Install]
WantedBy=default.target
UNIT
  systemctl --user daemon-reload
  systemctl --user enable --now shubat-connector.service
  loginctl enable-linger "$(id -un)" 2>/dev/null || true
  info "Installed systemd --user service shubat-connector"
fi

# --- surface the pairing link ----------------------------------------------
pairing="$dir/pairing.txt"
info "Starting up — waiting for the tunnel to come online…"
i=0
while [ $i -lt 20 ]; do
  [ -f "$pairing" ] && break
  i=$((i + 1)); sleep 1
done

printf '\n'
if [ -f "$pairing" ]; then
  link=$(cat "$pairing")
  info "Connector is running. Pair your phone:"
  printf '\n  1. Open the Shubat app → Add server → Paste link\n  2. Paste:\n\n     %s\n\n' "$link"
  if command -v qrencode >/dev/null 2>&1; then
    qrencode -t ANSIUTF8 "$link"
  else
    printf '  (install "qrencode" to show a scannable QR here)\n'
  fi
else
  err "connector did not produce a pairing link — check $dir/connector.log"
fi
