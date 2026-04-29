#!/usr/bin/env bash
# gitleaks.sh — host-neutral CI fragment.
# Scans the working tree for leaked secrets using the repo's .gitleaks.toml.
# Translate into the chosen CI system once UD-701 lands.
set -euo pipefail
cd "$(dirname "$0")/../.."
exec gitleaks detect \
    --source . \
    --config .gitleaks.toml \
    --redact \
    --verbose \
    --no-banner
