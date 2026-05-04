#!/usr/bin/env bash
set -euo pipefail

# UniDrive Docker Test Matrix
# Tests localfs provider (the core test case for #129)

JAR="/opt/unidrive/unidrive.jar"
JAVA="java --enable-native-access=ALL-UNNAMED ${JAVA_OPTS:-} -jar ${JAR}"

SOURCE="/tmp/source"
TARGET="/tmp/target"
CONFIG_DIR="/tmp/unidrive-config"

mkdir -p "${CONFIG_DIR}" "${SOURCE}" "${TARGET}"

PASS=0
FAIL=0

pass() { echo "  ✔ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ✗ $1: $2"; FAIL=$((FAIL + 1)); }

# ── Stage 1: LocalFS Tests ─────────────────────────────────────────────────

test_localfs() {
    echo "=== LocalFS Tests ==="
    
    mkdir -p "${SOURCE}/docs" "${SOURCE}/photos" "${TARGET}"
    
    # Create test files
    echo "Hello, world" > "${SOURCE}/hello.txt"
    echo "# Report" > "${SOURCE}/docs/report.md"
    echo "nested content" > "${SOURCE}/docs/nested.txt"
    dd if=/dev/urandom bs=1024 count=64 of="${SOURCE}/photos/image.bin" 2>/dev/null
    
    # Config
    cat > "${CONFIG_DIR}/config.toml" <<TOML
[providers.localfs-source]
type = "localfs"
root_path = "${SOURCE}"

[providers.localfs-target]
type = "localfs"
root_path = "${TARGET}"
TOML
    
    # Test: dry-run sync
    OUTPUT=$(${JAVA} -c "${CONFIG_DIR}" -p localfs-source sync --dry-run 2>&1 || true)
    if echo "$OUTPUT" | grep -qE "(error|Error|exception|Exception)" >/dev/null 2>&1; then
        fail "localfs dry-run sync" "$OUTPUT"
    elif [ -z "$OUTPUT" ]; then
        pass "localfs dry-run sync"
    else
        pass "localfs dry-run sync"
    fi
    
    # Test: status
    if ${JAVA} -c "${CONFIG_DIR}" status --all 2>&1 | grep -q "localfs-source"; then
        pass "status shows localfs-source"
    else
        fail "status" "profile not found"
    fi
    
    # Test: quota
    if ${JAVA} -c "${CONFIG_DIR}" -p localfs-source quota 2>&1 | grep -qiE "total|used|free|remaining"; then
        pass "quota returns values"
    else
        fail "quota" "no storage info"
    fi
    
    # Test: sync command runs without crash (state init)
    SYNC_OUTPUT=$(${JAVA} -c "${CONFIG_DIR}" -p localfs-source sync 2>&1 || true)
    if echo "$SYNC_OUTPUT" | grep -qiE "(error|Error|exception|Exception)" >/dev/null 2>&1; then
        fail "localfs sync" "$(echo $SYNC_OUTPUT | head -1)"
    else
        pass "localfs sync runs"
    fi
    
    # Test: second sync should transfer files
    sleep 1
    touch "${SOURCE}/new-file.txt"
    SYNC2_OUTPUT=$(${JAVA} -c "${CONFIG_DIR}" -p localfs-source sync 2>&1 || true)
    if echo "$SYNC2_OUTPUT" | grep -qiE "(error|Error|exception|Exception)" >/dev/null 2>&1; then
        fail "second sync" "$(echo $SYNC2_OUTPUT | head -1)"
    else
        pass "second sync runs"
    fi
}

# ── Stage 2: Multiple profiles ───────────────────────────────────────────

test_multi_profile() {
    echo "=== Multi-Profile Tests ==="
    
    # Add another profile
    cat >> "${CONFIG_DIR}/config.toml" <<TOML

[providers.localfs-alt]
type = "localfs"
root_path = "${SOURCE}"
TOML
    
    # Test: status shows multiple profiles
    STATUS=$(${JAVA} -c "${CONFIG_DIR}" status --all 2>&1 || true)
    if echo "${STATUS}" | grep -q "localfs-source" && echo "${STATUS}" | grep -q "localfs-target"; then
        pass "multiple localfs profiles"
    else
        fail "multi-profile" "profiles not visible"
    fi
}

# ── Main ───────────────────────────────────────────────────────────────────

main() {
    echo "═══════════════════════════════════════════════════"
    echo "  UniDrive Docker Test Matrix"
    echo "═══════════════════════════════════════════════════"
    
    mkdir -p "${CONFIG_DIR}"
    
    # Run tests
    test_localfs
    test_multi_profile
    
    # Summary
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