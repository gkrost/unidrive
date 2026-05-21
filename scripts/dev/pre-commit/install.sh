#!/usr/bin/env bash
# install.sh — point git at the tracked hook dir in scripts/dev/pre-commit/.
#
# Run once per clone. Sets core.hooksPath to scripts/dev/pre-commit/hooks,
# which symlinks to the checked-in scripts.
#
# Uninstall: `git config --unset core.hooksPath`.

set -euo pipefail

REPO="$(git rev-parse --show-toplevel)"
cd "$REPO"

HOOK_DIR="scripts/dev/pre-commit/hooks"
mkdir -p "$HOOK_DIR"

# commit-msg runs after the message is composed — gives us the full
# subject to parse. pre-commit runs before the message exists.
cat > "$HOOK_DIR/commit-msg" <<'EOF'
#!/usr/bin/env bash
exec "$(git rev-parse --show-toplevel)/scripts/dev/pre-commit/scope-check.sh" "$@"
EOF
chmod +x "$HOOK_DIR/commit-msg"

git config core.hooksPath "$HOOK_DIR"

echo "installed: core.hooksPath = $HOOK_DIR"
echo "  commit-msg → scope-check.sh"
echo
echo "Bypass on a single commit: git commit --no-verify"
echo "Uninstall:                 git config --unset core.hooksPath"
