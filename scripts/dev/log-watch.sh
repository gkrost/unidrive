#!/usr/bin/env bash
# log-watch.sh — tail unidrive.log with WARN/ERROR grouping and 429-storm
# detection. Replaces "read the log, squint, grep manually" with one command
# I can run on a schedule.
#
# Usage:
#   scripts/dev/log-watch.sh              # follow the live log
#   scripts/dev/log-watch.sh --last 500   # print last N lines once
#   scripts/dev/log-watch.sh --summary    # stats only (non-follow)
#   scripts/dev/log-watch.sh --anomalies  # non-follow, only non-boring lines
#
# Log path defaults to %LOCALAPPDATA%\unidrive\unidrive.log but can be
# overridden via UNIDRIVE_LOG.

set -euo pipefail

LOG="${UNIDRIVE_LOG:-$HOME/AppData/Local/unidrive/unidrive.log}"

if [[ ! -f "$LOG" ]]; then
  echo "log not found: $LOG" >&2
  exit 1
fi

MODE=follow
LAST_N=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --last) MODE=last; LAST_N="$2"; shift 2 ;;
    --summary) MODE=summary; shift ;;
    --anomalies) MODE=anomalies; shift ;;
    -h|--help)
      sed -n '2,14p' "$0"
      exit 0
      ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

# Patterns we consider noise at DEBUG (IPC disconnects, delta progress, etc.)
NOISE='o.k.u.onedrive.GraphApiService - Delta: |IPC: dropping dead client|IPC: client connected'

anomalies() {
  grep -vE "$NOISE" "$1" | grep -E 'WARN|ERROR|FATAL|Exception|throttled|Download failed|handshake|JWT'
}

summary() {
  echo "=== log summary ($LOG) ==="
  echo "total lines:       $(wc -l < "$LOG")"
  echo "WARN lines:        $(grep -c ' WARN ' "$LOG" || true)"
  echo "ERROR lines:       $(grep -c ' ERROR ' "$LOG" || true)"
  echo "429 throttle hits: $(grep -c 'Got 429' "$LOG" || true)"
  echo "Download failed:   $(grep -c 'Download failed' "$LOG" || true)"
  echo "attempt 5/5 (fatal): $(grep -c 'attempt 5/5' "$LOG" || true)"
  echo "JWT malformed:     $(grep -c 'JWT is not well formed' "$LOG" || true)"
  echo "TLS handshake:     $(grep -c 'terminated the handshake' "$LOG" || true)"
  echo
  echo "=== retryAfterSeconds distribution (top 10) ==="
  grep -oE 'retryAfterSeconds":[0-9]+' "$LOG" | sort | uniq -c | sort -rn | head -10 || true
}

case "$MODE" in
  follow)
    echo "following $LOG (Ctrl-C to stop) — showing anomalies only" >&2
    tail -F "$LOG" | grep --line-buffered -vE "$NOISE" | grep --line-buffered -E 'WARN|ERROR|FATAL|Exception|throttled|Download failed|handshake|JWT'
    ;;
  last)
    tail -n "$LAST_N" "$LOG"
    ;;
  summary)
    summary
    ;;
  anomalies)
    anomalies "$LOG"
    ;;
esac
