#!/usr/bin/env bash
set -euo pipefail

# UD-711 + UD-712: provider contract test harness
#
# Runs provider-level smoke + live round-trip against SFTP / WebDAV / S3
# (MinIO) / rclone containers started by docker-compose.providers.yml.
# Companion to test-matrix.sh (which only covers localfs).
#
# Coverage:
#   - Adapter registration (provider info <type>)
#   - Config + profile discovery (status --all)
#   - Live auth + read-only API (quota)
#   - Full round-trip (local -> sync --upload-only -> side-channel verify)
#
# The round-trip step writes a unique marker file into each profile's
# sync_root, runs `unidrive sync --upload-only`, then retrieves the file
# via a provider-specific side-channel client (sftp / curl / mc / rclone)
# and compares content. This validates upload semantics + server-side
# persistence, not just adapter construction.
#
# Env vars set by docker-compose.providers.yml:
#   SFTP_HOST, SFTP_PORT, SFTP_USER, SFTP_PASS
#   WEBDAV_URL, WEBDAV_USER, WEBDAV_PASS
#   S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY, S3_REGION
#   RCLONE_REMOTE, RCLONE_BUCKET

JAR="/opt/unidrive/unidrive.jar"
JAVA="java --enable-native-access=ALL-UNNAMED ${JAVA_OPTS:-} -jar ${JAR}"

CONFIG_DIR="/tmp/unidrive-config"
RCLONE_CONFIG="/home/testuser/.config/rclone/rclone.conf"
mkdir -p "${CONFIG_DIR}"

PASS=0
FAIL=0

pass() { echo "  ✔ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ✗ $1: $2"; FAIL=$((FAIL + 1)); }

# Bash builtin /dev/tcp — no nc/curl dependency inside the unidrive image.
wait_for_port() {
    local host="$1" port="$2" timeout="${3:-60}"
    for _ in $(seq 1 "${timeout}"); do
        if (exec 3<>/dev/tcp/"${host}"/"${port}") 2>/dev/null; then
            exec 3<&-
            return 0
        fi
        sleep 1
    done
    echo "timed out waiting for ${host}:${port}" >&2
    return 1
}

# ── config ─────────────────────────────────────────────────────────────────

write_config() {
    mkdir -p /home/testuser/sftp-local /home/testuser/webdav-local \
             /home/testuser/s3-local /home/testuser/rclone-local
    cat > "${CONFIG_DIR}/config.toml" <<TOML
[providers.sftp-local]
type = "sftp"
sync_root = "/home/testuser/sftp-local"
host = "${SFTP_HOST}"
port = ${SFTP_PORT}
user = "${SFTP_USER}"
password = "${SFTP_PASS}"
remote_path = "/upload"

[providers.webdav-local]
type = "webdav"
sync_root = "/home/testuser/webdav-local"
url = "${WEBDAV_URL}"
user = "${WEBDAV_USER}"
password = "${WEBDAV_PASS}"

[providers.s3-local]
type = "s3"
sync_root = "/home/testuser/s3-local"
bucket = "${S3_BUCKET}"
region = "${S3_REGION}"
endpoint = "${S3_ENDPOINT}"
access_key_id = "${S3_ACCESS_KEY}"
secret_access_key = "${S3_SECRET_KEY}"

[providers.rclone-local]
type = "rclone"
sync_root = "/home/testuser/rclone-local"
rclone_remote = "${RCLONE_REMOTE}"
rclone_path = "${RCLONE_BUCKET}"
rclone_config = "${RCLONE_CONFIG}"
TOML
}

write_rclone_config() {
    mkdir -p "$(dirname "${RCLONE_CONFIG}")"
    # Provider = Minio triggers path-style addressing + relaxed checksum
    # semantics that match MinIO's behaviour.
    cat > "${RCLONE_CONFIG}" <<EOF
[${RCLONE_REMOTE}]
type = s3
provider = Minio
access_key_id = ${S3_ACCESS_KEY}
secret_access_key = ${S3_SECRET_KEY}
endpoint = ${S3_ENDPOINT}
region = ${S3_REGION}
EOF
}

setup_mc() {
    mc --config-dir /tmp/mc alias set local \
        "${S3_ENDPOINT}" "${S3_ACCESS_KEY}" "${S3_SECRET_KEY}" >/dev/null
}

# ── per-provider smoke ─────────────────────────────────────────────────────

# Every provider adapter should answer `provider info <type>` without
# hitting the network — validates the adapter is registered at all.
# All provider descriptors include a `Tier:` row (see ProviderCommand.kt),
# which is a stable contract-level marker across adapters.
test_provider_info() {
    local type="$1"
    if ${JAVA} -c "${CONFIG_DIR}" provider info "${type}" 2>&1 | grep -q "Tier:"; then
        pass "provider info ${type}"
    else
        fail "provider info ${type}" "no Tier: row in descriptor"
    fi
}

# `status --all` enumerates configured profiles. No network I/O; proves
# config parse + profile-discovery path.
test_status_shows_profile() {
    local profile="$1"
    if ${JAVA} -c "${CONFIG_DIR}" status --all 2>&1 | grep -q "${profile}"; then
        pass "status --all shows ${profile}"
    else
        fail "status --all shows ${profile}" "profile missing from status output"
    fi
}

# `quota` is the first command that reaches the live provider — it auths
# and hits a read-only API endpoint on each adapter. Success here means
# the adapter could build a client, authenticate, and receive a response.
test_quota() {
    local profile="$1"
    local OUTPUT
    OUTPUT=$(${JAVA} -c "${CONFIG_DIR}" -p "${profile}" quota 2>&1 || true)
    if echo "${OUTPUT}" | grep -qiE "total|used|free|remaining|bytes|unlimited|quota"; then
        pass "${profile} quota reaches server"
    else
        fail "${profile} quota" "$(echo "${OUTPUT}" | head -2 | tr '\n' ' ')"
    fi
}

# ── round-trip side-channel verifiers ──────────────────────────────────────
#
# Each function is invoked with the uploaded filename and must print the
# file's content to stdout, exit 0 on success, non-zero if the file is
# absent or auth fails. We never rely on the unidrive CLI itself to
# verify — the whole point is a second, independent reading path.

verify_sftp() {
    local fname="$1"
    # sftp -b silently forces BatchMode=yes which disables password auth;
    # sshpass can only feed the password when sftp reads commands from
    # stdin in interactive mode. Heredoc on stdin keeps both working.
    sshpass -p "${SFTP_PASS}" sftp -q \
        -o StrictHostKeyChecking=accept-new \
        -o UserKnownHostsFile=/home/testuser/.ssh/known_hosts \
        -P "${SFTP_PORT}" \
        "${SFTP_USER}@${SFTP_HOST}" <<SFTP_CMDS >/dev/null 2>&1
get /upload/${fname} /tmp/verify-sftp.txt
bye
SFTP_CMDS
    cat /tmp/verify-sftp.txt
}

verify_webdav() {
    local fname="$1"
    curl -fsS -u "${WEBDAV_USER}:${WEBDAV_PASS}" "${WEBDAV_URL}${fname}"
}

verify_s3() {
    local fname="$1"
    mc --config-dir /tmp/mc cat "local/${S3_BUCKET}/${fname}"
}

verify_rclone() {
    local fname="$1"
    rclone --config "${RCLONE_CONFIG}" \
        cat "${RCLONE_REMOTE}:${RCLONE_BUCKET}/${fname}"
}

# Round-trip template. Writes a unique marker locally, runs
# `sync --upload-only` (deterministic direction), then compares the marker
# against what the side-channel reads back. Pure byte equality — no
# tolerance for "probably worked" fuzziness.
roundtrip() {
    local profile="$1" local_dir="$2" verifier_fn="$3"
    local fname="roundtrip-${profile}.txt"
    local marker="unidrive-${profile}-$$-${RANDOM}-$(date +%s)"

    mkdir -p "${local_dir}"
    printf '%s' "${marker}" > "${local_dir}/${fname}"

    if ! ${JAVA} -c "${CONFIG_DIR}" -p "${profile}" sync --upload-only \
            > "/tmp/sync-${profile}.log" 2>&1; then
        fail "${profile} sync --upload-only" \
             "$(tail -3 "/tmp/sync-${profile}.log" | tr '\n' ' ' | head -c 200)"
        return
    fi

    local actual
    actual=$("${verifier_fn}" "${fname}" 2>/dev/null || true)
    if [[ "${actual}" == "${marker}" ]]; then
        pass "${profile} round-trip verified via side-channel"
    else
        fail "${profile} round-trip content mismatch" \
             "expected '${marker}', got '$(printf '%s' "${actual}" | head -c 80)'"
    fi
}

# ── main ───────────────────────────────────────────────────────────────────

main() {
    echo "═══════════════════════════════════════════════════"
    echo "  UniDrive Provider Contract Harness"
    echo "═══════════════════════════════════════════════════"
    echo "  SFTP:   ${SFTP_HOST}:${SFTP_PORT} (${SFTP_USER})"
    echo "  WebDAV: ${WEBDAV_URL} (${WEBDAV_USER})"
    echo "  S3:     ${S3_ENDPOINT} (bucket=${S3_BUCKET})"
    echo "  rclone: remote=${RCLONE_REMOTE} bucket=${RCLONE_BUCKET}"
    echo ""

    echo "=== Waiting for services ==="
    wait_for_port "${SFTP_HOST}" "${SFTP_PORT}" || { fail "sftp readiness" "port ${SFTP_HOST}:${SFTP_PORT} never opened"; exit 1; }
    pass "sftp listening"
    wait_for_port webdav 80 || { fail "webdav readiness" "port 80 never opened"; exit 1; }
    pass "webdav listening"
    wait_for_port minio 9000 || { fail "minio readiness" "port 9000 never opened"; exit 1; }
    pass "minio listening"
    echo ""

    # SftpApiService rejects connections when known_hosts is missing or empty
    # (fail-closed — see SftpApiService.kt). Seed it from the live server key
    # before we invoke any SFTP command.
    echo "=== Seeding SFTP known_hosts ==="
    if ssh-keyscan -p "${SFTP_PORT}" -T 10 "${SFTP_HOST}" > /home/testuser/.ssh/known_hosts 2>/dev/null \
        && [[ -s /home/testuser/.ssh/known_hosts ]]; then
        pass "ssh-keyscan wrote $(wc -l < /home/testuser/.ssh/known_hosts) host-key line(s)"
    else
        fail "ssh-keyscan" "no host keys harvested from ${SFTP_HOST}:${SFTP_PORT}"
        exit 1
    fi
    echo ""

    write_config
    write_rclone_config
    setup_mc

    echo "=== Adapter Registration ==="
    for t in sftp webdav s3 rclone; do test_provider_info "${t}"; done
    echo ""

    echo "=== Config + Profile Discovery ==="
    for p in sftp-local webdav-local s3-local rclone-local; do
        test_status_shows_profile "${p}"
    done
    echo ""

    echo "=== Live Provider Auth + Quota ==="
    for p in sftp-local webdav-local s3-local rclone-local; do test_quota "${p}"; done
    echo ""

    echo "=== Upload Round-Trip ==="
    roundtrip sftp-local   /home/testuser/sftp-local   verify_sftp
    roundtrip webdav-local /home/testuser/webdav-local verify_webdav
    roundtrip s3-local     /home/testuser/s3-local     verify_s3
    roundtrip rclone-local /home/testuser/rclone-local verify_rclone

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
