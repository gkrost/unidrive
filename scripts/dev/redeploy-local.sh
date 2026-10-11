#!/usr/bin/env bash
#
# redeploy-local.sh — rebuild + redeploy unidrive (JVM CLI) and the
# unidrive-mount-linux Rust co-daemon to the local ~/.local install.
#
# Deploys whatever branch each repo is CURRENTLY checked out on — be on the
# branch you want deployed (normally main) before running.
#
# Safe under a running daemon/mount: a running JVM holds its jar open, so we
# unlink the old jar and copy a fresh inode rather than overwriting in place.
# Overwriting in place (what the gradle `:app:cli:deploy` task does) can
# corrupt a running JVM's lazy class-loading — see AGENTS.md "never hot-swap
# a .jar on a running JVM". A running daemon keeps the OLD code until you
# restart it.
#
# Warns up front when a unidrive FUSE mount or a daemon is running: restarting the daemon under a
# live mount tears its IPC socket and in-flight FUSE operations fail with EIO. The script itself never
# restarts the daemon or unmounts anything.
#
# Usage: redeploy-local.sh [--check-running]
#   --check-running   only report a live mount / daemon (exit 0), build and deploy nothing
#
# Env:
#   UNIDRIVE_MOUNT_REPO   path to the unidrive-mount-linux repo
#                         (default: <this repo>/../unidrive-mount-linux)
#
set -euo pipefail

check_only=0

case "${1:-}" in
  -h | --help)
    sed -n '2,26p' "$0"
    exit 0
    ;;
  "") ;;
  --check-running) check_only=1 ;;
  *)
    printf 'unknown argument: %s (try --help)\n' "$1" >&2
    exit 2
    ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MOUNT_REPO="${UNIDRIVE_MOUNT_REPO:-$(cd "${REPO_ROOT}/.." && pwd)/unidrive-mount-linux}"
LIB_DIR="${HOME}/.local/lib/unidrive"

log() { printf '[redeploy] %s\n' "$*"; }
die() {
  printf '[redeploy] ERROR: %s\n' "$*" >&2
  exit 1
}

# Active unidrive FUSE mounts (source "unidrive") and running daemons / co-daemons.
warn_if_running() {
  local mounts daemons codaemons
  mounts="$(findmnt -rn -t fuse,fuse.unidrive -S unidrive -o TARGET 2>/dev/null || true)"
  daemons="$(pgrep -f 'unidrive[^ ]*\.jar.* (daemon run|autostart)' 2>/dev/null || true)"
  codaemons="$(pgrep -x unidrive-mount 2>/dev/null || true)"
  if [[ -n "${mounts}" ]]; then
    log "WARNING: active unidrive FUSE mount(s): $(tr '\n' ' ' <<<"${mounts}")"
  fi
  if [[ -n "${daemons}" || -n "${codaemons}" ]]; then
    log "WARNING: unidrive daemon/co-daemon running (pids: $(tr '\n' ' ' <<<"${daemons} ${codaemons}"))"
  fi
  if [[ -n "${mounts}" || -n "${daemons}" || -n "${codaemons}" ]]; then
    log "WARNING: restarting the daemon under a live mount fails in-flight FUSE operations with EIO;"
    log "WARNING: finish or stop work on the mount first. This script does not restart anything itself."
  fi
}

warn_if_running
if [[ "${check_only}" -eq 1 ]]; then
  exit 0
fi

# --- unidrive JVM CLI ---
ud_branch="$(git -C "${REPO_ROOT}" rev-parse --abbrev-ref HEAD)"
ud_commit="$(git -C "${REPO_ROOT}" rev-parse --short HEAD)"
log "unidrive: building :app:cli:shadowJar from '${ud_branch}' (${ud_commit})"
if ! (cd "${REPO_ROOT}/core" && ./gradlew :app:cli:shadowJar --console=plain) >/tmp/redeploy-ud.log 2>&1; then
  tail -25 /tmp/redeploy-ud.log >&2
  die "unidrive build failed (full log: /tmp/redeploy-ud.log)"
fi

shopt -s nullglob
jars=("${REPO_ROOT}"/core/app/cli/build/libs/unidrive-*.jar)
shopt -u nullglob
[[ ${#jars[@]} -gt 0 ]] || die "no shadowJar found under core/app/cli/build/libs"
jar="${jars[-1]}"

mkdir -p "${LIB_DIR}"
target="${LIB_DIR}/$(basename "${jar}")"
if pgrep -f "${target}" >/dev/null 2>&1; then
  log "note: a JVM is running ${target##*/}; it keeps the OLD code until restarted (safe: unlink-then-copy)."
fi
rm -f "${target}"
cp "${jar}" "${target}"
log "unidrive: deployed ${target} ($(stat -c%s "${target}") bytes)"
# DaemonAutospawn reads the launchers' static JVM flags from jvm-flags.txt beside the jar; without it a
# spawned daemon starts with only the heap cap.
cp "${REPO_ROOT}/dist/launcher/jvm-flags.txt" "${LIB_DIR}/jvm-flags.txt"
log "unidrive: deployed ${LIB_DIR}/jvm-flags.txt"

# --- unidrive-mount-linux Rust co-daemon ---
if [[ -e "${MOUNT_REPO}/.git" ]]; then # -e not -d: a git worktree's .git is a file, not a dir
  m_branch="$(git -C "${MOUNT_REPO}" rev-parse --abbrev-ref HEAD)"
  m_commit="$(git -C "${MOUNT_REPO}" rev-parse --short HEAD)"
  log "unidrive-mount: cargo build --release from '${m_branch}' (${m_commit})"
  if ! (cd "${MOUNT_REPO}" && cargo build --release) >/tmp/redeploy-mount.log 2>&1; then
    tail -25 /tmp/redeploy-mount.log >&2
    die "unidrive-mount build failed (full log: /tmp/redeploy-mount.log)"
  fi
  mbin="${MOUNT_REPO}/target/release/unidrive-mount"
  [[ -f "${mbin}" ]] || die "no unidrive-mount binary at ${mbin}"
  rm -f "${LIB_DIR}/unidrive-mount"
  cp "${mbin}" "${LIB_DIR}/unidrive-mount"
  log "unidrive-mount: deployed ${LIB_DIR}/unidrive-mount ($(stat -c%s "${LIB_DIR}/unidrive-mount") bytes)"
else
  log "unidrive-mount: repo not found at ${MOUNT_REPO} (set UNIDRIVE_MOUNT_REPO); skipped."
fi

log "done — deployed: $("${HOME}/.local/bin/unidrive" --version 2>/dev/null | head -1)"
log "restart any running daemon/mount to pick up the new code."
