#!/bin/bash
#
# OnTask root hosts daemon.
#
# Usage: ontask-daemon.sh <port> <staged_hosts_file> <target_hosts_file> [<app_bundle> <agent_plist>]
#        ontask-daemon.sh --strip <target_hosts_file>     (remove OnTask's sections, keep everything else)
# Tell it to apply the staged file:   echo "copy" | nc 127.0.0.1 <port>
#
# Listens on loopback only, validates the staged file before copying it,
# and is meant to be installed (by install.sh) at a root-owned path so the user cannot edit what root runs.
#
# With <app_bundle>, it also watches that OnTask is still installed. Once the app has been gone for a day, it
# cleans up after it: OnTask's lines leave the hosts file, the login item (<agent_plist>) goes, and the daemon
# removes itself. The day's wait keeps "delete the app" from being a quick way around blocking (and covers updates).
#
# Environment (tests): ONTASK_DAEMON_SKIP_DNS=1 skips flushing the DNS cache; ONTASK_DAEMON_CHECK_SEC and
# ONTASK_DAEMON_GRACE_SEC change the watch interval (300) and the wait (86400); ONTASK_DAEMON_PREFIX is where its
# own files live (default /) and, when set, launchctl is skipped.

set -u

LABEL="dev.ontask.daemon"
MAX_BYTES=$((8 * 1024 * 1024))

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*"; }

# Removes everything between OnTask's begin/end markers (both sections), keeping the rest of the file.
strip_hosts() {
  local f="$1" tmp="$1.ontask.tmp"
  [ -f "$f" ] || return 0
  awk '/^# --- OnTask (ad blocking )?begin ---$/ { skip = 1; next }
       /^# --- OnTask (ad blocking )?end ---$/   { skip = 0; next }
       !skip' "$f" | cat -s > "$tmp" && chmod 644 "$tmp" && mv "$tmp" "$f"
}

flush_dns() {
  if [ "${ONTASK_DAEMON_SKIP_DNS:-0}" != "1" ]; then
    dscacheutil -flushcache
    killall -HUP mDNSResponder 2>/dev/null
  fi
  return 0
}

if [ "${1:-}" = "--strip" ]; then
  [ $# -eq 2 ] || { echo "usage: $0 --strip <target_hosts_file>" >&2; exit 1; }
  strip_hosts "$2" && flush_dns
  exit $?
fi

if [ $# -ne 3 ] && [ $# -ne 5 ]; then
  echo "usage: $0 <listen_port> <staged_hosts_file> <target_hosts_file> [<app_bundle> <agent_plist>]" >&2
  exit 1
fi

PORT="$1"; STAGED="$2"; TARGET="$3"; APP="${4:-}"; AGENT="${5:-}"
PREFIX="${ONTASK_DAEMON_PREFIX:-}"
MAIN=$$

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
  flush_dns
  log "applied $STAGED -> $TARGET"
}

# Stops the listener and the watcher (only this daemon's processes, never its parent's process group).
stop_all() { pkill -P "$MAIN" 2>/dev/null; kill "$MAIN" 2>/dev/null; exit 0; }

# OnTask was deleted without its Uninstall button: undo what it left behind, then remove this daemon.
clean_up_after_app() {
  log "$APP has been gone for a day: removing OnTask's blocking and this helper"
  strip_hosts "$TARGET"; flush_dns
  if [ -n "$AGENT" ] && [ -f "$AGENT" ]; then
    local uid; uid=$(stat -f %u "$AGENT" 2>/dev/null)
    rm -f "$AGENT"
    [ -z "$PREFIX" ] && [ -n "$uid" ] && launchctl bootout "gui/$uid/dev.ontask.app" 2>/dev/null
  fi
  rm -f "$PREFIX/Library/PrivilegedHelperTools/$LABEL.sh" "$PREFIX/Library/LaunchDaemons/$LABEL.plist"
  log "removed"
  [ -z "$PREFIX" ] && launchctl bootout "system/$LABEL" 2>/dev/null # stops this daemon for good
  stop_all
}

watch_app() {
  local every="${ONTASK_DAEMON_CHECK_SEC:-300}" grace="${ONTASK_DAEMON_GRACE_SEC:-86400}" since=""
  while sleep "$every"; do
    if [ -d "$APP" ]; then since=""; continue; fi
    [ -n "$since" ] || { since=$(date +%s); log "$APP is missing; cleaning up if it's still gone in ${grace}s"; }
    [ $(( $(date +%s) - since )) -ge "$grace" ] && clean_up_after_app
  done
}

log "OnTask daemon started on 127.0.0.1:$PORT"
[ -f "$STAGED" ] && apply # catch up on anything staged while the daemon wasn't running
[ -n "$APP" ] && watch_app &
while true; do
  message=$(nc -l 127.0.0.1 "$PORT")
  case "$message" in
    copy) apply ;;
    "") ;; # a connection without a message (OnTask checking the port)
    quit) log "quit requested"; stop_all ;;
    *) log "unknown message ignored" ;;
  esac
done
