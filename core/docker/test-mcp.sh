#!/usr/bin/env bash
set -euo pipefail

# UD-815: MCP JSON-RPC end-to-end harness.
#
# Boots the deployed MCP shadow jar against a seeded localfs profile,
# speaks JSON-RPC over stdio, and asserts the core tool surface. Shares
# `Dockerfile.test` with the CLI harnesses; compose overrides ENTRYPOINT
# to pick this script up.
#
# Shape mirrors test-matrix.sh (config seeding) + test-providers.sh
# (banner + pass/fail counter discipline). The interesting bit is the
# long-lived subprocess: we back stdin with a FIFO so we can stream
# requests sequentially and block-read one response line per request
# from the jar's stdout via a background file tail.

MCP_JAR_GLOB="/opt/unidrive/unidrive-mcp-*.jar"
# Glob resolution — fail fast if the Dockerfile COPY stanza regresses.
MCP_JAR=$(ls -1 ${MCP_JAR_GLOB} 2>/dev/null | head -1 || true)
if [[ -z "${MCP_JAR}" || ! -f "${MCP_JAR}" ]]; then
    echo "FATAL: MCP shadow jar not found at ${MCP_JAR_GLOB}" >&2
    exit 2
fi

# Tool count pinned to the HEAD `tools` list in app/mcp/Main.kt.
# Listed there: status, sync, get, free, pin, conflicts, ls, config,
# trash, versions, share, relocate, watchEvents, quota, backup — 15
# tools. We assert >= so adding a tool does not silently break CI, only
# removing one does.
EXPECTED_TOOL_COUNT=15

SOURCE="/tmp/source"
CONFIG_DIR="/tmp/unidrive-config"
PROFILE="localfs-mcp"
FIFO="/tmp/mcp-stdin.fifo"
OUT="/tmp/mcp-stdout.log"
ERR="/tmp/mcp-stderr.log"

PASS=0
FAIL=0

pass() { echo "  ✔ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ✗ $1: $2"; FAIL=$((FAIL + 1)); }

# ── config seeding (same pattern as test-matrix.sh) ───────────────────────

seed_profile() {
    mkdir -p "${CONFIG_DIR}" "${SOURCE}/docs"
    echo "Hello, MCP" > "${SOURCE}/hello.txt"
    echo "# Report" > "${SOURCE}/docs/report.md"

    cat > "${CONFIG_DIR}/config.toml" <<TOML
[providers.${PROFILE}]
type = "localfs"
root_path = "${SOURCE}"
TOML
}

# ── subprocess lifecycle ──────────────────────────────────────────────────

start_mcp() {
    rm -f "${FIFO}" "${OUT}" "${ERR}"
    mkfifo "${FIFO}"
    touch "${OUT}" "${ERR}"

    # Open the FIFO read-write on fd 9 — non-blocking (unlike `9>FIFO`,
    # which would deadlock waiting for a reader). Fd 9 stays open for
    # the life of the script so every request we write is seen by the
    # reader side, and java only sees EOF when we explicitly close 9.
    exec 9<>"${FIFO}"

    # Java reads from the FIFO, writes JSON-RPC responses to OUT, logs
    # to ERR. Background — we communicate via the files.
    java --enable-native-access=ALL-UNNAMED -jar "${MCP_JAR}" \
        -c "${CONFIG_DIR}" -p "${PROFILE}" \
        <"${FIFO}" >"${OUT}" 2>"${ERR}" &
    MCP_PID=$!

    # Give the JVM 3 s to come up; bail early if it dies before the
    # first request (config parse error, missing class, etc.).
    for _ in $(seq 1 30); do
        if ! kill -0 "${MCP_PID}" 2>/dev/null; then
            echo "FATAL: MCP process exited before first request:" >&2
            sed -e 's/^/  /' "${ERR}" >&2 || true
            exit 2
        fi
        sleep 0.1
    done
}

stop_mcp() {
    exec 9>&-  # close stdin → clean shutdown via EOF on reader loop
    if [[ -n "${MCP_PID:-}" ]]; then
        # Give the JVM a moment to drain stdout, then force.
        for _ in $(seq 1 20); do
            kill -0 "${MCP_PID}" 2>/dev/null || break
            sleep 0.1
        done
        kill -0 "${MCP_PID}" 2>/dev/null && kill -TERM "${MCP_PID}" 2>/dev/null || true
        wait "${MCP_PID}" 2>/dev/null || true
    fi
}

trap stop_mcp EXIT

# Send a JSON-RPC request, block until a response line with the matching
# id shows up in OUT. jq handles framing — one request per line.
#
# Args: $1 = id, $2 = method, $3 = params (JSON object or null)
# Prints the response JSON to stdout on success, exits non-zero on timeout.
rpc() {
    local id="$1" method="$2" params="${3:-null}"
    local req
    req=$(jq -cn --arg m "${method}" --argjson i "${id}" --argjson p "${params}" \
        '{jsonrpc:"2.0", id:$i, method:$m, params:$p}')

    # Mark our starting offset so we can read only new lines afterwards.
    local before_lines
    before_lines=$(wc -l < "${OUT}" 2>/dev/null || echo 0)

    # Write the request to the FIFO via the already-open fd 9.
    printf '%s\n' "${req}" >&9

    # Poll for a line whose id matches ours. 20 s ceiling — quota can be
    # slow on cold-start providers.
    local deadline=$(( $(date +%s) + 20 ))
    while (( $(date +%s) < deadline )); do
        # Read any new lines since `before_lines`; match on id.
        local resp
        resp=$(tail -n +$((before_lines + 1)) "${OUT}" 2>/dev/null \
            | jq -c --argjson i "${id}" 'select(.id == $i)' 2>/dev/null \
            | head -1 || true)
        if [[ -n "${resp}" ]]; then
            printf '%s\n' "${resp}"
            return 0
        fi
        sleep 0.1
    done
    echo "FATAL: RPC timeout (id=${id}, method=${method})" >&2
    sed -e 's/^/  stderr: /' "${ERR}" >&2 || true
    return 1
}

# ── assertions ────────────────────────────────────────────────────────────

test_initialize() {
    local resp
    resp=$(rpc 1 initialize '{"protocolVersion":"2024-11-05","clientInfo":{"name":"test-mcp","version":"0"}}') || {
        fail "initialize" "no response"
        return
    }
    local name version
    name=$(jq -r '.result.serverInfo.name // empty' <<<"${resp}")
    version=$(jq -r '.result.serverInfo.version // empty' <<<"${resp}")
    if [[ "${name}" != "unidrive-mcp" ]]; then
        fail "initialize serverInfo.name" "got '${name}', want 'unidrive-mcp'"
        return
    fi
    if [[ -z "${version}" ]]; then
        fail "initialize serverInfo.version" "empty"
        return
    fi
    pass "initialize serverInfo.name == unidrive-mcp"
    pass "initialize serverInfo.version non-empty (${version})"
}

test_tools_list() {
    local resp
    resp=$(rpc 2 tools/list '{}') || {
        fail "tools/list" "no response"
        return
    }
    local count
    count=$(jq -r '.result.tools | length // 0' <<<"${resp}")
    if [[ "${count}" -ge "${EXPECTED_TOOL_COUNT}" ]]; then
        pass "tools/list returned ${count} tools (>= ${EXPECTED_TOOL_COUNT})"
    else
        fail "tools/list count" "got ${count}, want >= ${EXPECTED_TOOL_COUNT}"
    fi

    # Spot-check the three tools we call by name below — saves us from a
    # confusing "unknown tool" failure three steps later.
    for name in unidrive_status unidrive_quota unidrive_ls; do
        if jq -e --arg n "${name}" '.result.tools[] | select(.name == $n)' <<<"${resp}" >/dev/null; then
            pass "tools/list includes ${name}"
        else
            fail "tools/list ${name}" "missing"
        fi
    done
}

# tools/call returns `{content:[{type:"text", text:"<JSON>"}], isError:bool}`.
# Unwrap the inner JSON text and run `check_fn` against it.
call_tool() {
    local id="$1" tool_name="$2" args="$3"
    local params
    params=$(jq -cn --arg n "${tool_name}" --argjson a "${args}" '{name:$n, arguments:$a}')
    rpc "${id}" tools/call "${params}"
}

unwrap_text() {
    jq -r '.result.content[0].text // empty'
}

is_error() {
    jq -r '.result.isError // false'
}

test_unidrive_status() {
    local resp
    resp=$(call_tool 3 unidrive_status '{}') || {
        fail "unidrive_status" "no response"
        return
    }
    if [[ "$(is_error <<<"${resp}")" == "true" ]]; then
        fail "unidrive_status" "isError=true ($(unwrap_text <<<"${resp}" | head -c 120))"
        return
    fi
    local body
    body=$(unwrap_text <<<"${resp}")
    if ! jq -e '.' <<<"${body}" >/dev/null 2>&1; then
        fail "unidrive_status" "content[0].text not JSON"
        return
    fi
    local missing=""
    for k in profile providerType syncRoot files credentialHealth; do
        if ! jq -e --arg k "${k}" 'has($k)' <<<"${body}" >/dev/null; then
            missing+=" ${k}"
        fi
    done
    if [[ -n "${missing}" ]]; then
        fail "unidrive_status keys" "missing:${missing}"
        return
    fi
    local profile provider
    profile=$(jq -r '.profile' <<<"${body}")
    provider=$(jq -r '.providerType' <<<"${body}")
    if [[ "${profile}" != "${PROFILE}" ]]; then
        fail "unidrive_status.profile" "got '${profile}', want '${PROFILE}'"
        return
    fi
    if [[ "${provider}" != "localfs" ]]; then
        fail "unidrive_status.providerType" "got '${provider}', want 'localfs'"
        return
    fi
    pass "unidrive_status has profile/providerType/syncRoot/files/credentialHealth"
}

test_unidrive_quota() {
    local resp
    resp=$(call_tool 4 unidrive_quota '{}') || {
        fail "unidrive_quota" "no response"
        return
    }
    if [[ "$(is_error <<<"${resp}")" == "true" ]]; then
        fail "unidrive_quota" "isError=true ($(unwrap_text <<<"${resp}" | head -c 120))"
        return
    fi
    local body used total remaining
    body=$(unwrap_text <<<"${resp}")
    used=$(jq -r '.used // empty' <<<"${body}")
    total=$(jq -r '.total // empty' <<<"${body}")
    remaining=$(jq -r '.remaining // empty' <<<"${body}")
    # Numeric + non-negative. localfs returns real filesystem quotas;
    # used can be 0 in a fresh tmpfs but all three must be numbers.
    for v in "${used}" "${total}" "${remaining}"; do
        if ! [[ "${v}" =~ ^-?[0-9]+$ ]]; then
            fail "unidrive_quota numeric" "used=${used} total=${total} remaining=${remaining}"
            return
        fi
    done
    if (( used < 0 || total < 0 || remaining < 0 )); then
        fail "unidrive_quota non-negative" "used=${used} total=${total} remaining=${remaining}"
        return
    fi
    pass "unidrive_quota returned numeric non-negative bytes (total=${total})"
}

test_unidrive_ls() {
    local resp
    resp=$(call_tool 5 unidrive_ls '{"path":"/"}') || {
        fail "unidrive_ls" "no response"
        return
    }
    if [[ "$(is_error <<<"${resp}")" == "true" ]]; then
        fail "unidrive_ls" "isError=true ($(unwrap_text <<<"${resp}" | head -c 120))"
        return
    fi
    # The tool reads from the local state database. On a profile that
    # has never synced, the DB is empty and `[]` is a valid success —
    # success is defined as "no isError + parseable JSON array".
    local body
    body=$(unwrap_text <<<"${resp}")
    if ! jq -e 'type == "array"' <<<"${body}" >/dev/null 2>&1; then
        fail "unidrive_ls" "content[0].text not a JSON array: $(head -c 120 <<<"${body}")"
        return
    fi
    pass "unidrive_ls / returned JSON array"
}

# ── main ──────────────────────────────────────────────────────────────────

main() {
    echo "═══════════════════════════════════════════════════"
    echo "  UniDrive MCP JSON-RPC Harness"
    echo "═══════════════════════════════════════════════════"
    echo "  jar:     ${MCP_JAR}"
    echo "  profile: ${PROFILE} (localfs, root=${SOURCE})"
    echo ""

    seed_profile
    start_mcp

    echo "=== initialize ==="
    test_initialize
    echo ""

    echo "=== tools/list ==="
    test_tools_list
    echo ""

    echo "=== tools/call ==="
    test_unidrive_status
    test_unidrive_quota
    test_unidrive_ls

    echo ""
    echo "═══════════════════════════════════════════════════"
    echo "  Results: ${PASS} passed, ${FAIL} failed"
    echo "═══════════════════════════════════════════════════"

    if [[ ${FAIL} -gt 0 ]]; then
        exit 1
    fi
    exit 0
}

main "$@"
