#!/bin/bash
#
# OnTask root hosts daemon (ported from BlockMe's BlockmeRootDaemon.sh).
#
# Usage: ontask-daemon.sh <port> <staged_hosts_file> <target_hosts_file>
# Tell it to apply the staged file:   echo "copy" | nc 127.0.0.1 <port>
#
# Differences from BlockMe: listens on loopback only, validates the staged file before copying it,
# and is meant to be installed (by install.sh) at a root-owned path so the user cannot edit what root runs.
# Set ONTASK_DAEMON_SKIP_DNS=1 (tests) to skip flushing the DNS cache.

set -u

if [ $# -ne 3 ]; then
  echo "usage: $0 <listen_port> <staged_hosts_file> <target_hosts_file>" >&2
  exit 1
fi

PORT="$1"; STAGED="$2"; TARGET="$3"
MAX_BYTES=$((8 * 1024 * 1024))

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*"; }

# A hosts file is: comments, blank lines, and "<ip> <name> [<name>...]" lines. Anything else is refused.
valid_hosts() {
  local f="$1"
  [ -f "$f" ] || return 1
  [ "$(wc -c < "$f")" -le "$MAX_BYTES" ] || return 1
  ! grep -qvE '^[[:space:]]*(#.*)?$|^[[:space:]]*([0-9]{1,3}(\.[0-9]{1,3}){3}|[0-9a-fA-F:]+)[[:space:]]+[^[:space:]#]+([[:space:]]+[^[:space:]#]+)*[[:space:]]*(#.*)?$' "$f"
}

apply() {
  if ! valid_hosts "$STAGED"; then
    log "refusing to apply: $STAGED is missing or not a valid hosts file"
    return 1
  fi
  local tmp="${TARGET}.ontask.tmp"
  cp "$STAGED" "$tmp" && chmod 644 "$tmp" && mv "$tmp" "$TARGET" || { log "copy failed"; return 1; }
  if [ "${ONTASK_DAEMON_SKIP_DNS:-0}" != "1" ]; then
    dscacheutil -flushcache
    killall -HUP mDNSResponder 2>/dev/null
  fi
  log "applied $STAGED -> $TARGET"
}

log "OnTask daemon started on 127.0.0.1:$PORT"
while true; do
  message=$(nc -l 127.0.0.1 "$PORT")
  case "$message" in
    copy) apply ;;
    quit) log "quit requested"; exit 0 ;;
    *) log "unknown message ignored" ;;
  esac
done
