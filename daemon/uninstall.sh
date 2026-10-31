#!/bin/bash
# Removes the OnTask hosts daemon. Usage: uninstall.sh [--target /etc/hosts] [--prefix <dir>] [--dry-run]
# --target <file>   also remove OnTask's sections from that hosts file (your own entries stay)
set -eu
PREFIX=""; DRY=0; TARGET=""
while [ $# -gt 0 ]; do
  case "$1" in --target) TARGET="$2"; shift 2 ;; --prefix) PREFIX="$2"; shift 2 ;; --dry-run) DRY=1; shift ;; *) echo "unknown option $1" >&2; exit 2 ;; esac
done
HERE="$(cd "$(dirname "$0")" && pwd)"
LABEL="dev.ontask.daemon"
BIN="$PREFIX/Library/PrivilegedHelperTools/$LABEL.sh"
PLIST="$PREFIX/Library/LaunchDaemons/$LABEL.plist"
run() { if [ "$DRY" = 1 ]; then echo "[dry-run] $*"; else "$@"; fi; }
if [ -z "$PREFIX" ]; then run launchctl bootout system "$PLIST" 2>/dev/null || true; fi
run rm -f "$BIN" "$PLIST"
[ -z "$TARGET" ] || run /bin/bash "$HERE/ontask-daemon.sh" --strip "$TARGET"
echo "removed $LABEL"
