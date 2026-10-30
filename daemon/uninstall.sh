#!/bin/bash
# Removes the OnTask hosts daemon. Usage: uninstall.sh [--prefix <dir>] [--dry-run]
set -eu
PREFIX=""; DRY=0
while [ $# -gt 0 ]; do
  case "$1" in --prefix) PREFIX="$2"; shift 2 ;; --dry-run) DRY=1; shift ;; *) echo "unknown option $1" >&2; exit 2 ;; esac
done
LABEL="dev.ontask.daemon"
BIN="$PREFIX/Library/PrivilegedHelperTools/$LABEL.sh"
PLIST="$PREFIX/Library/LaunchDaemons/$LABEL.plist"
run() { if [ "$DRY" = 1 ]; then echo "[dry-run] $*"; else "$@"; fi; }
if [ -z "$PREFIX" ]; then run launchctl bootout system "$PLIST" 2>/dev/null || true; fi
run rm -f "$BIN" "$PLIST"
echo "removed $LABEL"
