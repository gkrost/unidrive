#!/usr/bin/env bash
#
# UniDrive end-user uninstaller. Mirror of install.sh.
#
# Removes:
#   ~/.local/bin/unidrive
#   ~/.local/lib/unidrive/               (JARs)
#   ~/.config/systemd/user/unidrive.service, unidrive@.service, unidrive-mount@.service
#
# Keeps (remove manually if you want a full wipe):
#   ~/.config/unidrive/        (config + OAuth tokens)
#   ~/.local/share/unidrive/   (logs)
#
set -euo pipefail

echo "Uninstalling UniDrive..."

# Stop and disable every unidrive unit (single, per-profile and mount instances) if systemctl is around
if command -v systemctl >/dev/null 2>&1; then
    while read -r unit _; do
        if [[ "${unit}" == unidrive*.service ]]; then
            systemctl --user stop "${unit}"
            echo "  Stopped ${unit}"
        fi
    done < <(systemctl --user list-units --state=active --no-legend --plain 'unidrive*.service' 2>/dev/null || true)
    while read -r unit _; do
        if [[ "${unit}" == unidrive*.service ]]; then
            systemctl --user disable "${unit}"
            echo "  Disabled ${unit}"
        fi
    done < <(systemctl --user list-unit-files --state=enabled --no-legend --plain 'unidrive*.service' 2>/dev/null || true)
fi

# Remove files
rm -f "${HOME}/.config/systemd/user/unidrive.service" \
    "${HOME}/.config/systemd/user/unidrive@.service" \
    "${HOME}/.config/systemd/user/unidrive-mount@.service"
rm -f "${HOME}/.local/bin/unidrive"
rm -rf "${HOME}/.local/lib/unidrive"

if command -v systemctl >/dev/null 2>&1; then
    systemctl --user daemon-reload || true
fi

echo ""
echo "Removed:"
echo "  ~/.local/bin/unidrive"
echo "  ~/.local/lib/unidrive/"
echo "  ~/.config/systemd/user/unidrive.service, unidrive@.service, unidrive-mount@.service"
echo ""
echo "Kept (remove manually if desired):"
echo "  ~/.config/unidrive/        (config + tokens)"
echo "  ~/.local/share/unidrive/   (logs)"
