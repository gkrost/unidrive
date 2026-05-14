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
# Allow-list:
#   - lines containing `// allow: <reason>` (same-line marker), OR
#   - lines IMMEDIATELY preceded by a line whose only content is a
#     `// allow: <reason>` comment (preceding-line marker, useful when
#     the same-line position would conflict with ktlint formatters).
#
# Single-line `//` comments, KDoc lines (`*`), and `trimMargin` raw-
# string content lines (`|`) are also ignored — those are
# documentation, not dispatch logic.
#
# Use the allow-list marker for deliberate exceptions, e.g. the
# historical "onedrive" default in SyncConfig.kt that UD-012
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

# Pass 1: collect candidate hits, dropping comments / KDoc / trimMargin /
# same-line `// allow:` markers.
candidates=$(
    grep -rnE "\"($PROVIDER_NAMES)\"" "${SEARCH_PATHS[@]}" --include='*.kt' \
        | grep -vE '^[^:]+:[0-9]+:[[:space:]]*//' \
        | grep -vE '^[^:]+:[0-9]+:[[:space:]]*\*' \
        | grep -vE '^[^:]+:[0-9]+:[[:space:]]*\|' \
        | grep -v '// allow:' \
        || true
)

# Pass 2: for each candidate, also accept a preceding-line `// allow:`
# marker. The preceding line must be a stand-alone comment whose
# trimmed content starts with `// allow:`.
filtered=""
while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    file="${line%%:*}"
    rest="${line#*:}"
    lineno="${rest%%:*}"
    if [[ "$lineno" -gt 1 ]]; then
        prev_line="$(sed -n "$((lineno - 1))p" "$file")"
        prev_trimmed="$(echo "$prev_line" | sed -E 's/^[[:space:]]+//')"
        if [[ "$prev_trimmed" == "// allow:"* ]]; then
            continue
        fi
    fi
    filtered+="$line"$'\n'
done <<< "$candidates"

filtered="${filtered%$'\n'}"

if [[ -n "$filtered" ]]; then
    echo "FAIL: hardcoded provider-name string literal in :app:{cli,mcp,sync}/main:"
    echo "$filtered"
    echo
    echo "Either:"
    echo "  - Migrate to the SPI capability (factory.X() / provider.X()), OR"
    echo "  - If the literal is deliberate, mark it with '// allow: <reason>'"
    echo "    on the same line OR on the line immediately above."
    exit 1
fi

echo "OK: no hardcoded provider-name dispatch in :app:{cli,mcp,sync}/main."
