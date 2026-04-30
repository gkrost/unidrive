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
#   scripts/dev/log-watch.sh --json       # machine-readable summary (skill / CI)
#
# `--summary` and `--anomalies` and `--json` work across all rolled
# files for today (`unidrive.log` + `unidrive.YYYY-MM-DD.N.log`); the
# legacy live-tail (`follow`) and `--last` only operate on the live tail.
#
# Override the live-log path with UNIDRIVE_LOG, the rolled-log glob with
# UNIDRIVE_LOG_GLOB.

set -euo pipefail

LOG="${UNIDRIVE_LOG:-$HOME/AppData/Local/unidrive/unidrive.log}"
# UD-282-style log analysis (proposal 2026-04-29): glob today's rolled
# set so summary/anomalies don't miss WARNs that already rolled out of
# the live tail. The glob deliberately matches `unidrive.log` plus any
# `unidrive.YYYY-MM-DD.N.log` rotated siblings; the MCP server's separate
# `unidrive-mcp.log` is included if it exists in the same dir.
LOG_GLOB="${UNIDRIVE_LOG_GLOB:-$(dirname "$LOG")/unidrive*.log}"

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
    --json) MODE=json; shift ;;
    -h|--help)
      sed -n '2,22p' "$0"
      exit 0
      ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

# Patterns we consider noise at DEBUG (IPC disconnects, delta progress, etc.)
NOISE='o.k.u.onedrive.GraphApiService - Delta: |IPC: dropping dead client|IPC: client connected'

# Resolve the rolled-log set once. `compgen -G` works in bash without
# external tools and never errors when the glob doesn't match (set -u
# safe). We `tr` to normalise newlines so each file path is one line for
# downstream loops.
all_logs() {
  # shellcheck disable=SC2086  # glob expansion is the point
  compgen -G "$LOG_GLOB" 2>/dev/null | sort -u || true
}

count_pattern() {
  # $1 = grep regex, $2 = files (newline-separated). Use sum of counts so
  # the figure aggregates across rolled siblings.
  local pattern="$1"; shift
  local files="$1"
  if [[ -z "$files" ]]; then echo 0; return; fi
  echo "$files" | xargs -d '\n' grep -hcE "$pattern" 2>/dev/null \
    | awk '{ s += $1 } END { print s+0 }'
}

anomalies() {
  local files="$1"
  if [[ -z "$files" ]]; then return; fi
  echo "$files" | xargs -d '\n' grep -hvE "$NOISE" 2>/dev/null \
    | grep -E 'WARN|ERROR|FATAL|Exception|throttled|Download failed|handshake|JWT|Can.t create an array of size' \
    || true
}

# Scan-boundary stats (Layer 1 §"Summarise scan boundaries"): match
# `Scan started`/`Scan ended` lines from SyncEngine, count starts vs
# ends, surface aborted scans (start with no matching end).
scan_summary() {
  local files="$1"
  if [[ -z "$files" ]]; then echo "  (no log files)"; return; fi
  local starts ends
  starts=$(echo "$files" | xargs -d '\n' grep -hcE 'SyncEngine - Scan started' 2>/dev/null | awk '{s+=$1} END {print s+0}')
  ends=$(echo "$files" | xargs -d '\n' grep -hcE 'SyncEngine - Scan ended' 2>/dev/null | awk '{s+=$1} END {print s+0}')
  echo "  scan started:   $starts"
  echo "  scan ended:     $ends"
  if (( starts > ends )); then
    echo "  scan aborted:   $((starts - ends))"
  fi
}

summary() {
  local files
  files=$(all_logs)
  local nfiles
  nfiles=$(echo "$files" | grep -c .)

  echo "=== log summary (${nfiles} file(s) under $LOG_GLOB) ==="
  for f in $files; do
    printf "  %s — %s lines\n" "$(basename "$f")" "$(wc -l < "$f")"
  done
  echo
  echo "=== anomaly counts (across all files) ==="
  echo "  WARN lines:                   $(count_pattern ' WARN ' "$files")"
  echo "  ERROR lines:                  $(count_pattern ' ERROR ' "$files")"
  echo "  429 throttle hits:            $(count_pattern 'Got 429' "$files")"
  echo "  Download failed:              $(count_pattern 'Download failed' "$files")"
  echo "  Upload failed:                $(count_pattern 'Upload failed' "$files")"
  echo "  PROPFIND failed:              $(count_pattern 'PROPFIND failed' "$files")"
  echo "  MKCOL failed:                 $(count_pattern 'MKCOL failed' "$files")"
  echo "  attempt 5/5 (retry-fatal):    $(count_pattern 'attempt 5/5' "$files")"
  echo "  JWT malformed:                $(count_pattern 'JWT is not well formed' "$files")"
  echo "  TLS handshake fail:           $(count_pattern 'terminated the handshake' "$files")"
  echo "  4xx/5xx RequestId responses:  $(count_pattern '← req=.* \[[45][0-9][0-9]\]' "$files")"
  echo "  array-of-size (UD-329 class): $(count_pattern 'Can.t create an array of size' "$files")"
  echo "  MDC missing (\\[?\\?\\?\\?\\?\\?\\?\\]):     $(count_pattern '\[\?\?\?\?\?\?\?\]' "$files")"
  echo "  Connection reset / aborted:   $(count_pattern 'Connection (reset|aborted)|Verbindung wurde' "$files")"
  echo "  net_retry_reset (UD-278):     $(count_pattern 'I/O error \(attempt' "$files")"
  echo
  echo "=== sync scan boundaries ==="
  scan_summary "$files"
  echo
  echo "=== retryAfterSeconds distribution (top 10) ==="
  if [[ -n "$files" ]]; then
    echo "$files" | xargs -d '\n' grep -hoE 'retryAfterSeconds":[0-9]+' 2>/dev/null \
      | sort | uniq -c | sort -rn | head -10 || true
  fi
}

# JSON output for machine consumers (skill, CI hook). Stable schema —
# adding fields is non-breaking; renaming is.
summary_json() {
  local files; files=$(all_logs)
  local warns errors throttle415 dlfail ulfail propfail mkcolfail attempt55 jwt tls req4xx5xx arraysize mdcmissing connreset
  warns=$(count_pattern ' WARN ' "$files")
  errors=$(count_pattern ' ERROR ' "$files")
  throttle415=$(count_pattern 'Got 429' "$files")
  dlfail=$(count_pattern 'Download failed' "$files")
  ulfail=$(count_pattern 'Upload failed' "$files")
  propfail=$(count_pattern 'PROPFIND failed' "$files")
  mkcolfail=$(count_pattern 'MKCOL failed' "$files")
  attempt55=$(count_pattern 'attempt 5/5' "$files")
  jwt=$(count_pattern 'JWT is not well formed' "$files")
  tls=$(count_pattern 'terminated the handshake' "$files")
  req4xx5xx=$(count_pattern '← req=.* \[[45][0-9][0-9]\]' "$files")
  arraysize=$(count_pattern 'Can.t create an array of size' "$files")
  mdcmissing=$(count_pattern '\[\?\?\?\?\?\?\?\]' "$files")
  connreset=$(count_pattern 'Connection (reset|aborted)|Verbindung wurde' "$files")
  netretryreset=$(count_pattern 'I/O error \(attempt' "$files")

  cat <<EOF
{
  "logs": [$(echo "$files" | sed 's/.*/"&"/' | paste -sd,)],
  "warn_lines": $warns,
  "error_lines": $errors,
  "throttle_429": $throttle415,
  "download_failed": $dlfail,
  "upload_failed": $ulfail,
  "propfind_failed": $propfail,
  "mkcol_failed": $mkcolfail,
  "retry_attempt_5_of_5": $attempt55,
  "jwt_malformed": $jwt,
  "tls_handshake_failed": $tls,
  "request_4xx_5xx": $req4xx5xx,
  "array_of_size_oom": $arraysize,
  "mdc_missing": $mdcmissing,
  "connection_reset_or_aborted": $connreset,
  "net_retry_reset": $netretryreset
}
EOF
}

case "$MODE" in
  follow)
    echo "following $LOG (Ctrl-C to stop) — showing anomalies only" >&2
    tail -F "$LOG" \
      | grep --line-buffered -vE "$NOISE" \
      | grep --line-buffered -E 'WARN|ERROR|FATAL|Exception|throttled|Download failed|handshake|JWT|Can.t create an array of size'
    ;;
  last)
    tail -n "$LAST_N" "$LOG"
    ;;
  summary)
    summary
    ;;
  anomalies)
    anomalies "$(all_logs)"
    ;;
  json)
    summary_json
    ;;
esac
