#!/bin/bash
#
# Installs the OnTask hosts daemon as a root LaunchDaemon. Needs sudo (the app asks via the macOS admin prompt).
#
#   install.sh [--port 12901] [--staged <file>] [--target /etc/hosts] [--prefix <dir>] [--dry-run]
#
# --prefix <dir>   install into <dir> instead of / and skip chown/launchctl (for testing)
# --dry-run        print what would happen; change nothing

set -eu

PORT=12901; STAGED=""; TARGET="/etc/hosts"; PREFIX=""; DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --staged) STAGED="$2"; shift 2 ;;
    --target) TARGET="$2"; shift 2 ;;
    --prefix) PREFIX="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
[ -n "$STAGED" ] || { echo "--staged <file> is required" >&2; exit 2; }

HERE="$(cd "$(dirname "$0")" && pwd)"
LABEL="dev.ontask.daemon"
BIN_DIR="$PREFIX/Library/PrivilegedHelperTools"
BIN="$BIN_DIR/$LABEL.sh"
PLIST="$PREFIX/Library/LaunchDaemons/$LABEL.plist"

run() { if [ "$DRY" = 1 ]; then echo "[dry-run] $*"; else "$@"; fi; }

run mkdir -p "$BIN_DIR" "$(dirname "$PLIST")"
run cp "$HERE/ontask-daemon.sh" "$BIN"
run chmod 755 "$BIN"

if [ "$DRY" = 1 ]; then
  echo "[dry-run] would write $PLIST (port $PORT, staged $STAGED, target $TARGET)"
else
  cat > "$PLIST" <<PLIST_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>/Library/PrivilegedHelperTools/$LABEL.sh</string>
        <string>$PORT</string>
        <string>$STAGED</string>
        <string>$TARGET</string>
    </array>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>
    <key>StandardErrorPath</key><string>/var/log/ontask-daemon.log</string>
    <key>StandardOutPath</key><string>/var/log/ontask-daemon.log</string>
    <key>ProcessType</key><string>Background</string>
</dict>
</plist>
PLIST_EOF
fi

if [ -z "$PREFIX" ]; then
  run chown root:wheel "$BIN" "$PLIST"
  run chmod 644 "$PLIST"
  run launchctl bootout system "$PLIST" 2>/dev/null || true
  run launchctl bootstrap system "$PLIST"
fi
echo "installed $LABEL (port $PORT)"
