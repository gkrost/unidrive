#!/usr/bin/env bash
set -euo pipefail

# Set up config directory from mounted credentials
CONFIG_DIR="/tmp/.config/unidrive"
mkdir -p "$CONFIG_DIR"

# Symlink config.toml if mounted
if [[ -f /creds/config.toml ]]; then
    ln -sf /creds/config.toml "$CONFIG_DIR/config.toml"
fi

# Symlink each provider credentials directory
for dir in /creds/*/; do
    [[ -d "$dir" ]] || continue
    provider="$(basename "$dir")"
    mkdir -p "$CONFIG_DIR/$provider"
    for f in "$dir"*; do
        [[ -f "$f" ]] || continue
        ln -sf "$f" "$CONFIG_DIR/$provider/$(basename "$f")"
    done
done

exec /opt/e2e-360/bin/e2e-360 "$@"
