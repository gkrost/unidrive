#!/usr/bin/env bash
# backlog-sync.sh — CI wrapper. Fails the build on orphan / stale-closed UD refs.
set -euo pipefail
cd "$(dirname "$0")/../.."
exec kotlinc -script scripts/backlog-sync.kts
