#!/usr/bin/env bash
#
# UniDrive end-user installer.
#
# Installs:
#   ~/.local/lib/unidrive/unidrive-<ver>.jar       (fat shadowJar, CLI)
#   ~/.local/bin/unidrive                          (wrapper -> CLI)
#   ~/.config/systemd/user/unidrive.service        (user-mode systemd unit)
#   ~/.config/systemd/user/unidrive@.service       (per-profile template)
#   ~/.config/systemd/user/unidrive-mount@.service (per-profile FUSE mount template)
#   ~/.local/share/unidrive/                       (log dir)
#
# Usage:
#   bash dist/install.sh                # use locally-built JAR from core/app/cli/build/libs
#   bash dist/install.sh <path/to.jar>  # use the given CLI fat JAR (e.g. from a GitHub release)
#
# After install:
#   systemctl --user daemon-reload
#   systemctl --user enable --now unidrive.service
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

INSTALL_LIB="${HOME}/.local/lib/unidrive"
INSTALL_BIN="${HOME}/.local/bin"
LOG_DIR="${HOME}/.local/share/unidrive"
SYSTEMD_DIR="${HOME}/.config/systemd/user"

resolve_jar() {
    local dir="$1"
    local prefix="$2"
    local found=""
    if [[ -d "${dir}" ]]; then
        # shellcheck disable=SC2012  # ls -1 is fine; filenames are project-controlled
        found="$(ls -1 "${dir}"/"${prefix}"-*.jar 2>/dev/null \
            | sort -V \
            | tail -1)"
    fi
    printf '%s' "${found}"
}

CLI_JAR=""

if [[ $# -ge 1 ]]; then
    CLI_JAR="$1"
    if [[ ! -f "${CLI_JAR}" ]]; then
        echo "ERROR: CLI JAR not found at: ${CLI_JAR}" >&2
        exit 1
    fi
else
    CLI_JAR="$(resolve_jar "${REPO_ROOT}/core/app/cli/build/libs" "unidrive")"

    if [[ -z "${CLI_JAR}" ]]; then
        echo "ERROR: unidrive CLI JAR not found in core/app/cli/build/libs/" >&2
        echo "Build it first:" >&2
        echo "  (cd core && ./gradlew :app:cli:shadowJar -q)" >&2
        exit 1
    fi
fi

CLI_BASENAME="$(basename "${CLI_JAR}")"

# Stop an active service before replacing the jar it may be running. Preserve
# whether it was active so an upgrade restarts the newly mode-aware launcher;
# a deliberately stopped service stays stopped.
SERVICE_WAS_ACTIVE=0
if command -v systemctl >/dev/null 2>&1 && systemctl --user is-active --quiet unidrive.service 2>/dev/null; then
    systemctl --user stop unidrive.service
    SERVICE_WAS_ACTIVE=1
fi

echo "Installing UniDrive..."

# JAR
mkdir -p "${INSTALL_LIB}"
# Sweep legacy / stale jars before the copy. Pre-slim builds shipped
# `unidrive-mcp-*.jar` alongside the CLI jar; the MCP module was deleted
# but old artifacts linger from prior installs.
find "${INSTALL_LIB}" -maxdepth 1 -type f -name 'unidrive*.jar' \
    ! -name "${CLI_BASENAME}" -print -delete 2>/dev/null \
  | sed 's|^|  pruned stale jar: |'
cp "${CLI_JAR}" "${INSTALL_LIB}/${CLI_BASENAME}"
echo "  ${INSTALL_LIB}/${CLI_BASENAME}"

# jvm-flags.txt beside the jar: DaemonAutospawn reads the same single source the wrapper above was
# rendered from when it spawns the daemon itself.
cp "${SCRIPT_DIR}/launcher/jvm-flags.txt" "${INSTALL_LIB}/"
echo "  ${INSTALL_LIB}/jvm-flags.txt"

# Coroutine-debug probe jar (gkrost/unidrive#613 ask 1): deployed beside the fat jar so the
# launcher's UNIDRIVE_COROUTINE_DEBUG gate can -javaagent it at daemon JVM start. Optional:
# a tree without the probe jar (an older build, a slim release tarball) installs fine without it.
DEBUG_AGENT_JAR=""
for candidate in "$(dirname "${CLI_JAR}")"/kotlinx-coroutines-core-jvm-*.jar; do
    if [[ -f "${candidate}" ]]; then
        cp "${candidate}" "${INSTALL_LIB}/"
        DEBUG_AGENT_JAR="${INSTALL_LIB}/$(basename "${candidate}")"
        echo "  ${DEBUG_AGENT_JAR}"
        break
    fi
done

# CLI wrapper, rendered from the same template and flag list as the Gradle deploy launcher.
mkdir -p "${INSTALL_BIN}"
STATIC_FLAGS=""
while IFS= read -r flag; do
    flag="${flag%%#*}"; flag="${flag//[[:space:]]/}"
    [[ -n "${flag}" ]] && STATIC_FLAGS+="\"${flag}\" "
done < "${SCRIPT_DIR}/launcher/jvm-flags.txt"
sed -e "s|@STATIC_FLAGS_SH@|${STATIC_FLAGS% }|" -e "s|@JAR@|${INSTALL_LIB}/${CLI_BASENAME}|" \
    -e "s|@COROUTINES_DEBUG_AGENT@|${DEBUG_AGENT_JAR}|" \
    "${SCRIPT_DIR}/launcher/unidrive.sh.tmpl" > "${INSTALL_BIN}/unidrive"
chmod +x "${INSTALL_BIN}/unidrive"
echo "  ${INSTALL_BIN}/unidrive"

# Log directory
mkdir -p "${LOG_DIR}"

# Co-daemon (unidrive-mount Rust binary) — placeholder
# Phase 2 of the sparse-hydration roadmap ships a separate Rust co-daemon
# that mounts the cache as a FUSE filesystem. No release tarball exists
# yet; once one lands on github.com/gkrost/unidrive-mount-linux this
# section will download + SHA256-verify it into ${INSTALL_LIB}.
MOUNT_BIN="${INSTALL_LIB}/unidrive-mount"
if [[ ! -x "${MOUNT_BIN}" ]]; then
    echo ""
    echo "Co-daemon download skipped: the unidrive-mount binary has not yet been released."
    echo "Once the first release tarball lands on github.com/gkrost/unidrive-mount-linux,"
    echo "install.sh will download and verify it. Until then, build manually:"
    echo "  cd <repo>/unidrive-mount-linux && cargo build --release \\"
    echo "    && cp target/release/unidrive-mount ${INSTALL_LIB}/"
    echo ""
fi

# Systemd user unit
mkdir -p "${SYSTEMD_DIR}"
for unit in unidrive.service unidrive@.service unidrive-mount@.service; do
    cp "${SCRIPT_DIR}/${unit}" "${SYSTEMD_DIR}/${unit}"
    echo "  ${SYSTEMD_DIR}/${unit}"
done

if command -v systemctl >/dev/null 2>&1; then
    systemctl --user daemon-reload || true
    if [[ "${SERVICE_WAS_ACTIVE}" == "1" ]]; then
        systemctl --user start unidrive.service
    fi
fi

echo ""
echo "Done. Usage:"
echo "  unidrive --help                                  # CLI help"
echo "  unidrive auth                                    # authenticate first"
echo "  unidrive autostart                              # start the selected profile's mode"
echo "  systemctl --user enable --now unidrive.service   # auto-start on login (default profile)"
echo "  systemctl --user enable --now unidrive@<profile>.service         # ... or one unit per profile"
echo "  systemctl --user enable --now unidrive-mount@<profile>.service   # FUSE mount, see dist/README.md"
echo "  systemctl --user status unidrive.service         # check daemon status"
echo "  journalctl --user -u unidrive.service -f         # follow logs"
