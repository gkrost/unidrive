#!/usr/bin/env bash
# smoke.sh — build + boot sanity for the unidrive CLI.
#
# Verifies the shadow JAR builds and runs. Does not exercise live providers —
# end-to-end smoke against Internxt + OneDrive lives in the per-provider
# integration tests (`UNIDRIVE_INTEGRATION_TESTS=true ./gradlew check`),
# which require OAuth credentials.
#
# Exit 0 on full pass. Any failure returns the failing step's exit code.

set -euo pipefail
trap 'echo "FAIL at line $LINENO"' ERR

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

LOG_DIR="$(mktemp -d)"
echo "[smoke] root=$ROOT"
echo "[smoke] log_dir=$LOG_DIR"

echo "[smoke] step 1/3: ./gradlew check"
( cd core && ./gradlew check --no-daemon ) > "$LOG_DIR/check.log" 2>&1

echo "[smoke] step 2/3: build CLI shadow JAR"
( cd core && ./gradlew :app:cli:shadowJar --no-daemon ) > "$LOG_DIR/build.log" 2>&1

CLI_JAR="$(find core/app/cli/build/libs -name '*-all.jar' -o -name 'unidrive-*.jar' | head -1)"
test -n "$CLI_JAR" || { echo "CLI jar missing under core/app/cli/build/libs/"; exit 1; }

echo "[smoke] step 3/3: CLI boots and reports a version"
java -jar "$CLI_JAR" --version > "$LOG_DIR/version.log" 2>&1
grep -q . "$LOG_DIR/version.log" || { echo "--version produced no output"; cat "$LOG_DIR/version.log"; exit 1; }

echo "[smoke] PASS — logs in $LOG_DIR"
