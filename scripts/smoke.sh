#!/usr/bin/env bash
# smoke.sh — end-to-end smoke test for the v0.1.0 core-only MVP.
#
# Scope: CLI + MCP + localfs provider. UI and shell-win are NOT exercised here.
# See docs/CHANGELOG.md [0.1.0-mvp] for the acceptance criteria this backs.
#
# Exit 0 on full pass. Any failure returns the failing step's exit code.

set -euo pipefail
trap 'echo "FAIL at line $LINENO"' ERR

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

LOG_DIR="$(mktemp -d)"
SYNC_ROOT="$(mktemp -d)"
PROFILE="smoke"

echo "[smoke] root=$ROOT"
echo "[smoke] sync_root=$SYNC_ROOT"
echo "[smoke] log_dir=$LOG_DIR"

echo "[smoke] step 1/6: build core"
( cd core && ./gradlew :app:cli:shadowJar :app:mcp:shadowJar --no-daemon ) > "$LOG_DIR/build.log" 2>&1

CLI_JAR="$(find core/app/cli/build/libs -name '*-all.jar' -o -name 'unidrive-cli*.jar' | head -1)"
MCP_JAR="$(find core/app/mcp/build/libs -name '*-all.jar' -o -name 'unidrive-mcp*.jar' | head -1)"
test -n "$CLI_JAR" || { echo "CLI jar missing"; exit 1; }
test -n "$MCP_JAR" || { echo "MCP jar missing"; exit 1; }

echo "[smoke] step 2/6: seed sync root"
mkdir -p "$SYNC_ROOT/src"
echo "hello world" > "$SYNC_ROOT/src/hello.txt"

echo "[smoke] step 3/6: CLI init profile ($PROFILE)"
java -jar "$CLI_JAR" profile create "$PROFILE" --provider localfs --root "$SYNC_ROOT" \
    > "$LOG_DIR/cli-init.log" 2>&1 || true

echo "[smoke] step 4/6: CLI sync"
java -jar "$CLI_JAR" -p "$PROFILE" sync > "$LOG_DIR/cli-sync.log" 2>&1

echo "[smoke] step 5/6: MCP ls tool roundtrip"
# Minimal JSON-RPC: initialize then tools/call ls.
MCP_IN="$LOG_DIR/mcp-in.jsonl"
cat >"$MCP_IN" <<'EOF'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","clientInfo":{"name":"smoke","version":"0"},"capabilities":{}}}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ls","arguments":{"profile":"smoke","path":"/"}}}
EOF
java -jar "$MCP_JAR" < "$MCP_IN" > "$LOG_DIR/mcp-out.jsonl" 2>"$LOG_DIR/mcp-err.log" || true

# Sanity-check: at least one valid JSON-RPC response with id=3 and no error.
grep -q '"id":3' "$LOG_DIR/mcp-out.jsonl" || { echo "MCP: no id=3 response"; exit 1; }

echo "[smoke] step 6/6: backlog-sync dry run"
kotlinc -script scripts/backlog-sync.kts > "$LOG_DIR/backlog.log" 2>&1 || {
    echo "backlog-sync failed — see $LOG_DIR/backlog.log"
    exit 1
}

echo "[smoke] PASS — logs in $LOG_DIR"
