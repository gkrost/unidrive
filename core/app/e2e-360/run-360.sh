#!/usr/bin/env bash
set -euo pipefail

# Run UniDrive 360° E2E tests inside Docker.
#
# Usage:
#   ./e2e-360/run-360.sh groundtruth -p internxt-test --sync-root /sync --profile dev
#   ./e2e-360/run-360.sh generate --profile dev --output /sync/golden
#
# Credentials are mounted read-only from ~/.config/unidrive.
# The container gets a fresh /sync volume each run (true isolation).

IMAGE="unidrive-360"
CREDS_DIR="${UNIDRIVE_CONFIG_DIR:-$HOME/.config/unidrive}"

if [[ ! -d "$CREDS_DIR" ]]; then
    echo "Error: credentials directory not found: $CREDS_DIR" >&2
    echo "Set UNIDRIVE_CONFIG_DIR or run 'unidrive -p <provider> auth' first." >&2
    exit 1
fi

# Build image if not present or if --build is passed
if [[ "${1:-}" == "--build" ]]; then
    shift
    docker build -t "$IMAGE" -f core/app/e2e-360/Dockerfile .
elif ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "Image $IMAGE not found, building..."
    docker build -t "$IMAGE" -f core/app/e2e-360/Dockerfile .
fi

exec docker run --rm \
    --security-opt no-new-privileges:true \
    --memory=2g \
    --cpus=2 \
    --pids-limit=256 \
    --log-driver=json-file \
    --log-opt max-size=10m \
    --log-opt max-file=3 \
    -v "$CREDS_DIR:/creds:ro" \
    "$IMAGE" "$@"
