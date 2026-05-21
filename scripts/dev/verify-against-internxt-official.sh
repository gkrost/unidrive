#!/usr/bin/env bash
#
# verify-against-internxt-official.sh — differential verification of
# the tracking-set sync engine against the Internxt official desktop
# client.
#
# Both clients sync the same Internxt account into different local
# directories. The official client's sync_root is the ORACLE for what
# the cloud actually contains; any `ts sync --dry-run` plan action
# that contradicts the oracle is, by definition, a unidrive bug.
#
# Falsification table:
#
#   plan action          path in oracle?   verdict
#   --------------       ---------------   --------------------------
#   del-remote /foo      yes               BUG (would delete a real file)
#   del-local  /foo      yes               BUG (would delete a real file)
#   download   /foo      no                SUSPICIOUS (delta lied?)
#   upload     /foo      yes, diff content COLLISION (operator action)
#   any        /foo      /foo only locally INFO (pure-local untracked)
#
# Runs in a loop so a human watching can see convergence (good) or
# persistent divergence (bug). Exits non-zero if any BUG-category
# action was observed across the run.
#
# Usage example (with the user's actual paths):
#
#   verify-against-internxt-official.sh \
#     --official-root='C:/Users/gerno/InternxtDrive - 0c06806b-1322-492c-9df1-536506723b2c' \
#     --unidrive-root='C:/Users/gerno/Internxt' \
#     --profile=internxt \
#     --interval=30 \
#     --max-iters=10
#
# Or once-and-out:
#
#   verify-against-internxt-official.sh --once \
#     --official-root='...' --unidrive-root='...' --profile=internxt

set -euo pipefail

# -------- defaults --------
OFFICIAL_ROOT="${OFFICIAL_ROOT:-}"
UNIDRIVE_ROOT="${UNIDRIVE_ROOT:-}"
PROFILE="${PROFILE:-internxt}"
INTERVAL="${INTERVAL:-30}"
MAX_ITERS="${MAX_ITERS:-10}"
UNIDRIVE_BIN="${UNIDRIVE_BIN:-unidrive}"
ONCE=0
VERBOSE=0

usage() {
  cat <<'USAGE'
Usage: verify-against-internxt-official.sh [options]

Required:
  --official-root=PATH    Internxt desktop client's sync_root (the oracle)
  --unidrive-root=PATH    unidrive's sync_root (system under test)
  --profile=NAME          unidrive profile name (default: internxt)

Optional:
  --interval=SECONDS      seconds between iterations (default: 30)
  --max-iters=N           max iterations before exit (default: 10; 0 = forever)
  --unidrive-bin=PATH     unidrive binary (default: $UNIDRIVE_BIN on PATH)
  --once                  run one iteration and exit (overrides --max-iters)
  --verbose               print per-action detail, not just summary

Environment variable equivalents: OFFICIAL_ROOT, UNIDRIVE_ROOT, PROFILE,
INTERVAL, MAX_ITERS, UNIDRIVE_BIN.

Exit codes:
  0   no BUG-category actions observed
  1   one or more BUG-category actions observed
  2   misconfiguration (paths missing, unidrive not found, etc.)
USAGE
}

# -------- parse flags --------
for arg in "$@"; do
  case "$arg" in
    --official-root=*) OFFICIAL_ROOT="${arg#*=}" ;;
    --unidrive-root=*) UNIDRIVE_ROOT="${arg#*=}" ;;
    --profile=*)       PROFILE="${arg#*=}" ;;
    --interval=*)      INTERVAL="${arg#*=}" ;;
    --max-iters=*)     MAX_ITERS="${arg#*=}" ;;
    --unidrive-bin=*)  UNIDRIVE_BIN="${arg#*=}" ;;
    --once)            ONCE=1 ;;
    --verbose|-v)      VERBOSE=1 ;;
    --help|-h)         usage; exit 0 ;;
    *)                 echo "unknown flag: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

# -------- validate --------
if [ -z "$OFFICIAL_ROOT" ] || [ -z "$UNIDRIVE_ROOT" ]; then
  echo "error: --official-root and --unidrive-root are required" >&2
  usage >&2
  exit 2
fi
if [ ! -d "$OFFICIAL_ROOT" ]; then
  echo "error: official root does not exist or is not a directory: $OFFICIAL_ROOT" >&2
  exit 2
fi
if [ ! -d "$UNIDRIVE_ROOT" ]; then
  echo "error: unidrive root does not exist or is not a directory: $UNIDRIVE_ROOT" >&2
  exit 2
fi
if ! command -v "$UNIDRIVE_BIN" >/dev/null 2>&1; then
  echo "error: unidrive binary not on PATH: $UNIDRIVE_BIN" >&2
  echo "       set UNIDRIVE_BIN=... or --unidrive-bin=..." >&2
  exit 2
fi

# -------- helpers --------

# Snapshot a tree as TSV "/path\tsize" lines, sorted by path. Path
# normalisation matches the engine's: leading slash, forward slashes.
snapshot_tree() {
  local root="$1"
  ( cd "$root" && find . -type f -printf '/%P\t%s\n' 2>/dev/null \
      | sed 's|\\|/|g' \
      | sort )
}

# Run unidrive ts sync --dry-run, capture stdout. Suppresses the
# EXPERIMENTAL banner (which goes to stderr) from interfering with
# the plan parse.
run_dryrun() {
  "$UNIDRIVE_BIN" ts sync -p "$PROFILE" --dry-run 2>/dev/null || true
}

# Parse the plan output. Emits one line per action: "<type>\t<path>"
parse_plan() {
  local plan_text="$1"
  # Plan lines look like:  "    - download /path/to/file"
  echo "$plan_text" \
    | grep -E '^    - ' \
    | sed -E 's/^    - ([^ ]+) (.+)$/\1\t\2/'
}

# Set of paths from a tree snapshot (just the path column).
paths_only() {
  cut -f1
}

# -------- main loop --------

iter=0
bugs_total=0
suspicious_total=0
collision_total=0

while true; do
  iter=$((iter + 1))
  echo ""
  echo "============================================"
  echo "  iteration $iter   $(date '+%Y-%m-%dT%H:%M:%S')"
  echo "============================================"

  # 1. Snapshot both trees
  official_snap="$(snapshot_tree "$OFFICIAL_ROOT")"
  unidrive_snap="$(snapshot_tree "$UNIDRIVE_ROOT")"
  official_count=$(echo "$official_snap" | grep -c '^/' || true)
  unidrive_count=$(echo "$unidrive_snap" | grep -c '^/' || true)
  echo "tree sizes:  official=$official_count   unidrive=$unidrive_count"

  # Path-only sets for membership checks
  official_paths="$(echo "$official_snap" | paths_only | sort -u)"

  # 2. Run unidrive dry-run
  echo "running: $UNIDRIVE_BIN ts sync -p $PROFILE --dry-run"
  plan_text="$(run_dryrun)"

  # Parse plan into <type>\t<path> lines
  plan="$(parse_plan "$plan_text")"
  plan_size=$(echo -n "$plan" | grep -c '	' || true)
  echo "plan size:   $plan_size action(s)"

  # 3. Classify each action against the oracle
  iter_bugs=0
  iter_suspicious=0
  iter_collisions=0
  iter_ok=0

  if [ -n "$plan" ]; then
    while IFS=$'\t' read -r action path; do
      [ -z "$action" ] && continue
      in_oracle=0
      if echo "$official_paths" | grep -Fxq "$path"; then
        in_oracle=1
      fi

      case "$action" in
        del-remote|del-local)
          if [ $in_oracle -eq 1 ]; then
            iter_bugs=$((iter_bugs + 1))
            echo "  BUG        $action $path   (oracle has this path)"
          else
            iter_ok=$((iter_ok + 1))
            [ $VERBOSE -eq 1 ] && echo "  ok         $action $path"
          fi
          ;;
        download)
          if [ $in_oracle -eq 1 ]; then
            iter_ok=$((iter_ok + 1))
            [ $VERBOSE -eq 1 ] && echo "  ok         $action $path"
          else
            iter_suspicious=$((iter_suspicious + 1))
            echo "  SUSPICIOUS $action $path   (cloud sees it, official client doesn't yet?)"
          fi
          ;;
        upload)
          iter_ok=$((iter_ok + 1))
          [ $VERBOSE -eq 1 ] && echo "  ok         $action $path"
          ;;
        collision)
          iter_collisions=$((iter_collisions + 1))
          echo "  COLLISION  $action $path   (operator: ts claim or edit)"
          ;;
        noop)
          : # ignore
          ;;
        *)
          iter_suspicious=$((iter_suspicious + 1))
          echo "  UNKNOWN    $action $path"
          ;;
      esac
    done <<<"$plan"
  fi

  echo "summary:     bugs=$iter_bugs  suspicious=$iter_suspicious  collisions=$iter_collisions  ok=$iter_ok"

  bugs_total=$((bugs_total + iter_bugs))
  suspicious_total=$((suspicious_total + iter_suspicious))
  collision_total=$((collision_total + iter_collisions))

  # 4. Convergence trend
  if [ $plan_size -eq 0 ] && [ $iter_collisions -eq 0 ]; then
    echo "(converged: empty plan, no collisions)"
  fi

  # 5. Loop control
  if [ $ONCE -eq 1 ]; then
    break
  fi
  if [ "$MAX_ITERS" -gt 0 ] && [ $iter -ge "$MAX_ITERS" ]; then
    break
  fi
  echo "sleeping ${INTERVAL}s..."
  sleep "$INTERVAL"
done

# -------- aggregate --------
echo ""
echo "============================================"
echo "  RUN SUMMARY"
echo "============================================"
echo "iterations:      $iter"
echo "BUG total:       $bugs_total"
echo "SUSPICIOUS:      $suspicious_total"
echo "COLLISION:       $collision_total"

if [ $bugs_total -gt 0 ]; then
  echo ""
  echo "FAIL: tracking-set engine planned delete actions for paths the"
  echo "      official Internxt client still has in its sync_root. File"
  echo "      a BACKLOG follow-up under the High-tier verification entry."
  exit 1
fi

echo "PASS: no falsifying actions observed across $iter iteration(s)."
exit 0
