#!/usr/bin/env bash
# check-no-provider-string-dispatch.sh — fail CI if any of :app:cli,
# :app:mcp, :app:sync introduces a hardcoded provider-name string
# literal in active code.
#
# This is the anti-regression guard for the SPI-contract refactor
# (refactor/provider-spi-contract, 2026-05-02). After that work,
# provider-specific behaviour belongs to the provider via the
# capability methods on ProviderFactory and CloudProvider; the
# consumers in :app:cli, :app:mcp, :app:sync should consult the SPI,
# not dispatch on `provider.id == "foo"`.
#
# Allow-list: lines containing `// allow: <reason>` are ignored.
# Single-line `//` comments and KDoc lines (`*`) are also ignored
# because they are documentation, not dispatch logic.
#
# Use the allow-list marker for deliberate exceptions, e.g. the
# historical "onedrive" default in SyncConfig.kt that UD-008
# (architecture follow-up) will eventually replace with
# ProviderRegistry.defaultProvider().

set -euo pipefail
cd "$(dirname "$0")/../.."

PROVIDER_NAMES='localfs|onedrive|rclone|s3|sftp|webdav|internxt'
SEARCH_PATHS=(
    core/app/cli/src/main
    core/app/mcp/src/main
    core/app/sync/src/main
)

# Filters in order:
#   1. find provider names as quoted string literals
#   2. drop pure-comment lines (// at start of line, possibly indented)
#   3. drop KDoc lines (start with whitespace + *)
#   4. drop trimMargin help-text lines (start with whitespace + |)
#   5. drop explicit allow-listed lines
hits=$(grep -rnE "\"($PROVIDER_NAMES)\"" "${SEARCH_PATHS[@]}" --include='*.kt' \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*//' \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*\*' \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*\|' \
    | grep -v '// allow:' \
    || true)

if [[ -n "$hits" ]]; then
    echo "FAIL: hardcoded provider-name string literal in :app:{cli,mcp,sync}/main:"
    echo "$hits"
    echo
    echo "Either:"
    echo "  - Migrate to the SPI capability (factory.X() / provider.X()), OR"
    echo "  - If the literal is deliberate, append '// allow: <reason>' to the line."
    exit 1
fi

echo "OK: no hardcoded provider-name dispatch in :app:{cli,mcp,sync}/main."
