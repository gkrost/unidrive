#!/usr/bin/env bash
# unidrive-jfr.sh — start unidrive (CLI or MCP) wrapped in Java Flight
# Recorder, capturing as much detail as production-safely possible.
#
# Usage:
#   scripts/dev/unidrive-jfr.sh [--mcp] [--duration <N>m] [--out <dir>]
#                               -- <unidrive args>
#
# Examples:
#   # CLI relocate, JFR auto-stops after 60 min
#   scripts/dev/unidrive-jfr.sh --duration 60m -- -p localfs relocate \
#       --from src --to dst
#
#   # CLI sync --watch indefinitely (Ctrl-C stops both unidrive and JFR)
#   scripts/dev/unidrive-jfr.sh -- -p onedrive sync --watch
#
#   # MCP server (stdin/stdout JSON-RPC); JFR runs alongside
#   scripts/dev/unidrive-jfr.sh --mcp
#
# JFR recording lands at:
#   ${OUT_DIR:-$HOME/.local/share/unidrive/jfr}/unidrive-<kind>-<sha>-<timestamp>.jfr
#
# Inspect the file with:
#   jfr print --events CPULoad,GCHeapSummary,JavaMonitorWait <file>
#   or load it into JDK Mission Control (File → Open File).

set -euo pipefail

# ── Defaults ────────────────────────────────────────────────────────────────
MCP=0
DURATION=""
JAR_OVERRIDE=""

# Default JFR output dir tracks the deploy task's per-platform layout:
#   Windows (Git Bash): %LOCALAPPDATA%\unidrive\jfr
#   Linux / macOS:      $HOME/.local/share/unidrive/jfr
if [[ -n "${UNIDRIVE_JFR_DIR:-}" ]]; then
  OUT_DIR="$UNIDRIVE_JFR_DIR"
elif [[ -n "${LOCALAPPDATA:-}" ]]; then
  OUT_DIR="$LOCALAPPDATA/unidrive/jfr"
else
  OUT_DIR="$HOME/.local/share/unidrive/jfr"
fi

# ── Parse args (everything after `--` is passed to unidrive) ────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --mcp)        MCP=1; shift ;;
    --duration)   DURATION="$2"; shift 2 ;;
    --out)        OUT_DIR="$2"; shift 2 ;;
    --jar)        JAR_OVERRIDE="$2"; shift 2 ;;
    --)           shift; break ;;
    -h|--help)
      sed -n '2,22p' "$0"
      exit 0
      ;;
    *)
      # Unknown flag — assume it's a unidrive arg (best-effort
      # passthrough for users who forget the `--`).
      break
      ;;
  esac
done
APP_ARGS=("$@")

# ── Resolve jar ────────────────────────────────────────────────────────────
if [[ "$MCP" -eq 1 ]]; then
  JAR_NAME="unidrive-mcp-0.0.0-greenfield.jar"
  KIND="mcp"
else
  JAR_NAME="unidrive-0.0.0-greenfield.jar"
  KIND="cli"
fi

if [[ -n "$JAR_OVERRIDE" ]]; then
  JAR="$JAR_OVERRIDE"
elif [[ -n "${LOCALAPPDATA:-}" ]]; then
  # Git Bash on Windows
  JAR="$LOCALAPPDATA/unidrive/$JAR_NAME"
else
  # Linux / macOS — match the deployLinux() target dir.
  JAR="$HOME/.local/lib/unidrive/$JAR_NAME"
fi

if [[ ! -f "$JAR" ]]; then
  echo "jar not found: $JAR" >&2
  echo "Run \`./gradlew :app:cli:deploy :app:mcp:deploy\` from core/ first." >&2
  exit 1
fi

# ── Capture build SHA from BuildInfo.class (best-effort) ───────────────────
SHA="unknown"
if command -v unzip >/dev/null 2>&1; then
  TMP_DIR=$(mktemp -d)
  trap "rm -rf '$TMP_DIR'" EXIT
  if unzip -p "$JAR" 'org/krost/unidrive/cli/BuildInfo.class' 2>/dev/null > "$TMP_DIR/BuildInfo.class"; then
    # The COMMIT constant is a UTF-8-encoded short SHA in the constant pool;
    # extract via strings, filter for hex-only tokens of length 7..12.
    SHA=$(strings "$TMP_DIR/BuildInfo.class" 2>/dev/null \
          | grep -oE '\b[a-f0-9]{7,12}\b' \
          | head -1 || echo "unknown")
    [[ -z "$SHA" ]] && SHA="unknown"
  fi
fi

# ── JFR output path ────────────────────────────────────────────────────────
mkdir -p "$OUT_DIR"
TIMESTAMP=$(date +%Y-%m-%d_%H%M%S)
JFR_FILE="$OUT_DIR/unidrive-${KIND}-${SHA}-${TIMESTAMP}.jfr"

cat <<EOF

==> unidrive-jfr.sh
    jar:        $JAR
    sha:        $SHA
    JFR file:   $JFR_FILE
    duration:   ${DURATION:-until-exit}

EOF

# ── JFR settings ────────────────────────────────────────────────────────────
#
# `settings=profile` is JDK's higher-detail preset (~1 % overhead): method
# sampling + allocation profiling + locks. The other options force JFR to
# always emit a final dump on JVM exit even if the recording was running
# at the moment.
JFR_PARAMS="filename=$JFR_FILE,settings=profile,dumponexit=true"
if [[ -n "$DURATION" ]]; then
  JFR_PARAMS="${JFR_PARAMS},duration=${DURATION}"
fi

# ── JVM args ────────────────────────────────────────────────────────────────
# Match the production launcher's args (UD-258 UTF-8 + Ktor FFI access)
# plus JFR-specific options. stackdepth=256 captures deep stacks so async
# coroutine stitching survives in the dump.
JVM_ARGS=(
  -Xmx6g
  -Dstdout.encoding=UTF-8
  -Dstderr.encoding=UTF-8
  --enable-native-access=ALL-UNNAMED
  -XX:FlightRecorderOptions=stackdepth=256,memorysize=64m
  "-XX:StartFlightRecording=$JFR_PARAMS"
  -XX:+HeapDumpOnOutOfMemoryError
  "-XX:HeapDumpPath=$OUT_DIR"
  -jar "$JAR"
)

echo "==> exec: java ${JVM_ARGS[*]} ${APP_ARGS[*]}"
echo

# ── Run ────────────────────────────────────────────────────────────────────
EXIT_CODE=0
java "${JVM_ARGS[@]}" "${APP_ARGS[@]}" || EXIT_CODE=$?

echo
echo "==> JFR recording: $JFR_FILE"
if [[ -f "$JFR_FILE" ]]; then
  SIZE_MB=$(du -m "$JFR_FILE" | cut -f1)
  echo "    size: ${SIZE_MB} MB"
  echo "    inspect: jfr print --events CPULoad,GCHeapSummary,JavaMonitorWait \"$JFR_FILE\""
  echo "    or:      JDK Mission Control — File → Open File"
else
  echo "    (file not produced — likely an early JVM exit before JFR started)"
fi

exit $EXIT_CODE
