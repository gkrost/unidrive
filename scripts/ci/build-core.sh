#!/usr/bin/env bash
# build-core.sh — host-neutral CI fragment.
# Translate into the chosen CI system once UD-701 lands.
set -euo pipefail
cd "$(dirname "$0")/../.."
cd core
./gradlew build test --no-daemon
