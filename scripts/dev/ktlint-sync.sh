#!/usr/bin/env bash
# ktlint-sync.sh — run ktlintFormat across every Gradle composite, then
# regenerate baselines atomically and show the resulting diff.
#
# Used after non-trivial .kt edits near files with existing baseline
# entries (line-number-anchored — see memory feedback_ktlint_baseline_drift).
#
# Usage:
#   scripts/dev/ktlint-sync.sh [--check-only] [--module <gradle-path>]
#
# Flags:
#   --check-only           Run ktlintCheck without mutating any files.
#   --module <path>        Scope to a single Gradle module. The path is the
#                          composite-relative Gradle path with a leading
#                          colon, e.g. --module :app:cli (inside core/) or
#                          --module :desktop (inside ui/). Only the composite
#                          that contains the module is visited; all others
#                          are skipped. Without this flag every composite
#                          runs unscoped (backwards-compatible default).
#
# Examples:
#   scripts/dev/ktlint-sync.sh                       # wide sweep (default)
#   scripts/dev/ktlint-sync.sh --module :app:cli     # only core/app/cli
#   scripts/dev/ktlint-sync.sh --check-only --module :app:cli

set -euo pipefail

cd "$(dirname "$0")/../.."

CHECK_ONLY=0
MODULE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check-only)
      CHECK_ONLY=1
      shift
      ;;
    --module)
      if [[ $# -lt 2 ]]; then
        echo "error: --module requires an argument (e.g. --module :app:cli)" >&2
        exit 2
      fi
      MODULE="$2"
      shift 2
      ;;
    --module=*)
      MODULE="${1#--module=}"
      shift
      ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      echo "usage: scripts/dev/ktlint-sync.sh [--check-only] [--module <gradle-path>]" >&2
      exit 2
      ;;
  esac
done

# Normalise module to have a leading colon if caller omitted it.
if [[ -n "$MODULE" && "${MODULE:0:1}" != ":" ]]; then
  MODULE=":$MODULE"
fi

# Both composites have ktlint wired in.
COMPOSITES=(core ui)

# When --module is set, locate the composite that owns it by translating the
# Gradle path ":a:b:c" to the on-disk directory "a/b/c" under each composite.
# First match wins. This sidesteps having to parse settings.gradle.kts dialect
# variations (multi-arg include(...) in core/, single-module root in ui/).
SCOPED_COMPOSITE=""
if [[ -n "$MODULE" ]]; then
  # ":app:cli" -> "app/cli"
  MODULE_REL="${MODULE#:}"
  MODULE_REL="${MODULE_REL//://}"
  for c in "${COMPOSITES[@]}"; do
    if [[ -d "$c/$MODULE_REL" ]]; then
      SCOPED_COMPOSITE="$c"
      break
    fi
  done
  if [[ -z "$SCOPED_COMPOSITE" ]]; then
    echo "error: module '$MODULE' not found — no directory matches '$MODULE_REL' under any composite" >&2
    echo "       searched: ${COMPOSITES[*]}" >&2
    exit 2
  fi
  echo "==> scoped run: composite=$SCOPED_COMPOSITE module=$MODULE"

  # R3 from UD-728: tree-state pre-flight. When scoped to a module, refuse
  # to sweep if there are already-modified files outside that module —
  # those would get pulled into the caller's next commit by ktlintFormat.
  # This catches halted-agent WIP leakage (memory feedback_halted_agent_leaks).
  SCOPE_PREFIX="$SCOPED_COMPOSITE/$MODULE_REL/"
  LEAKED="$(git status --short -- . |
    awk '{print $NF}' |
    grep -v "^$SCOPE_PREFIX" |
    grep -E '\.(kt|kts)$' || true)"
  if [[ -n "$LEAKED" ]]; then
    echo "error: unexpected uncommitted .kt/.kts files outside $MODULE:" >&2
    while IFS= read -r f; do echo "  $f" >&2; done <<< "$LEAKED"
    echo "  Resolve (commit / restore / stash) before running a scoped ktlint sweep." >&2
    echo "  Bypass with KTLINT_SYNC_SKIP_PREFLIGHT=1 if this is intentional." >&2
    if [[ -z "${KTLINT_SYNC_SKIP_PREFLIGHT:-}" ]]; then
      exit 3
    fi
    echo "  KTLINT_SYNC_SKIP_PREFLIGHT set — continuing anyway." >&2
  fi
fi

for c in "${COMPOSITES[@]}"; do
  if [[ ! -d "$c" ]]; then
    echo "skip: $c/ not present" >&2
    continue
  fi
  if [[ -n "$SCOPED_COMPOSITE" && "$c" != "$SCOPED_COMPOSITE" ]]; then
    echo "skip: $c/ (out of scope for --module $MODULE)" >&2
    continue
  fi

  # Build module-prefixed task names when scoped, otherwise the bare task
  # (which hits every subproject inside the composite).
  if [[ -n "$SCOPED_COMPOSITE" ]]; then
    FMT_TASK="${MODULE}:ktlintFormat"
    CHK_TASK="${MODULE}:ktlintCheck"
    BASE_TASK="${MODULE}:ktlintGenerateBaseline"
  else
    FMT_TASK="ktlintFormat"
    CHK_TASK="ktlintCheck"
    BASE_TASK="ktlintGenerateBaseline"
  fi

  (
    cd "$c"
    if [[ $CHECK_ONLY -eq 1 ]]; then
      echo "==> $c: $CHK_TASK"
      ./gradlew "$CHK_TASK" --no-daemon 2>&1 | tail -20
    else
      echo "==> $c: $FMT_TASK"
      ./gradlew "$FMT_TASK" --no-daemon 2>&1 | tail -10 || true
      echo "==> $c: $CHK_TASK (after format)"
      if ! ./gradlew "$CHK_TASK" --no-daemon 2>&1 | tail -10; then
        echo "!! $c still has violations after ktlintFormat — running $BASE_TASK" >&2
        ./gradlew "$BASE_TASK" --no-daemon 2>&1 | tail -10 || true
      fi
    fi
  )
done

echo
echo "==> baseline + source changes:"
git diff --stat -- '**/config/ktlint/baseline.xml' '**/*.kt'
