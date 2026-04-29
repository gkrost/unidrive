#!/usr/bin/env bash
# install.sh — copy repo hooks into .git/hooks and make them executable.
set -euo pipefail
cd "$(dirname "$0")/../.."

if [ ! -d .git ]; then
    echo "install.sh: no .git directory at repo root; nothing to do." >&2
    exit 1
fi

cp scripts/hooks/pre-push .git/hooks/pre-push
chmod +x .git/hooks/pre-push
echo "installed: .git/hooks/pre-push"
