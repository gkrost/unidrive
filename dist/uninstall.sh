#!/usr/bin/env bash
#
# UniDrive end-user uninstaller. Mirror of install.sh.
#
# Removes:
#   ~/.local/bin/unidrive
#   ~/.local/bin/unidrive-mcp           (if present)
#   ~/.local/lib/unidrive/               (JARs)
#   ~/.config/systemd/user/unidrive.service
#
# Keeps (remove manually if you want a full wipe):
#   ~/.config/unidrive/        (config + OAuth tokens)
#   ~/.local/share/unidrive/   (logs)
#
set -euo pipefail

echo "Uninstalling UniDrive..."

# Stop and disable service if systemctl is around
if command -v systemctl >/dev/null 2>&1; then
    if systemctl --user is-active --quiet unidrive.service 2>/dev/null; then
        systemctl --user stop unidrive.service
        echo "  Stopped unidrive.service"
    fi
    if systemctl --user is-enabled --quiet unidrive.service 2>/dev/null; then
        systemctl --user disable unidrive.service
        echo "  Disabled unidrive.service"
    fi
fi

# Remove files
rm -f "${HOME}/.config/systemd/user/unidrive.service"
rm -f "${HOME}/.local/bin/unidrive"
rm -f "${HOME}/.local/bin/unidrive-mcp"
rm -rf "${HOME}/.local/lib/unidrive"

if command -v systemctl >/dev/null 2>&1; then
    systemctl --user daemon-reload || true
fi

echo ""
echo "Removed:"
echo "  ~/.local/bin/unidrive"
echo "  ~/.local/bin/unidrive-mcp (if present)"
echo "  ~/.local/lib/unidrive/"
echo "  ~/.config/systemd/user/unidrive.service"
echo ""
echo "Kept (remove manually if desired):"
echo "  ~/.config/unidrive/        (config + tokens)"
echo "  ~/.local/share/unidrive/   (logs)"
