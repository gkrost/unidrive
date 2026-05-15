#!/usr/bin/env bash
# Install UniDrive context-menu entries for Nautilus (GNOME Files), Nemo
# (Cinnamon), and Dolphin (KDE).
#
# Idempotent: re-running overwrites existing entries. Auto-detects which file
# managers are present; missing FMs are silently skipped.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$SCRIPT_DIR/nautilus"

if [[ ! -d "$SRC" ]]; then
    echo "Source directory not found: $SRC" >&2
    exit 1
fi

ACTIONS=(
    "UniDrive — Hydrate"
    "UniDrive — Dehydrate"
    "UniDrive — Pin"
    "UniDrive — Unpin"
)

# ── Nautilus (GNOME Files) ───────────────────────────────────────────────────

NAUTILUS_DIR="$HOME/.local/share/nautilus/scripts/UniDrive"
echo "Installing Nautilus scripts to $NAUTILUS_DIR ..."
mkdir -p "$NAUTILUS_DIR"
cp "$SRC/unidrive-menu.sh" "$NAUTILUS_DIR/"
chmod +x "$NAUTILUS_DIR/unidrive-menu.sh"

for action in "${ACTIONS[@]}"; do
    cp "$SRC/$action" "$NAUTILUS_DIR/"
    chmod +x "$NAUTILUS_DIR/$action"
done
echo "  Done. Right-click -> Scripts -> UniDrive."

# ── Nemo (Cinnamon) ─────────────────────────────────────────────────────────

if command -v nemo &>/dev/null || [[ -d "$HOME/.local/share/nemo" ]]; then
    NEMO_DIR="$HOME/.local/share/nemo/scripts/UniDrive"
    echo "Installing Nemo scripts to $NEMO_DIR ..."
    mkdir -p "$NEMO_DIR"
    cp "$NAUTILUS_DIR"/* "$NEMO_DIR/"
    chmod +x "$NEMO_DIR"/*
    echo "  Done. Right-click -> Scripts -> UniDrive."
fi

# ── Dolphin (KDE) ───────────────────────────────────────────────────────────
# KDE5 used ~/.local/share/kservices5/ServiceMenus, KDE Plasma 6 uses
# ~/.local/share/kio/servicemenus. We write both when present.

if command -v dolphin &>/dev/null \
    || [[ -d "$HOME/.local/share/kservices5" ]] \
    || [[ -d "$HOME/.local/share/kio" ]]; then

    write_dolphin_menu() {
        local target_dir="$1"
        mkdir -p "$target_dir"
        cat > "$target_dir/unidrive.desktop" <<DESKTOP
[Desktop Entry]
Type=Service
X-KDE-ServiceTypes=KonqPopupMenu/Plugin
MimeType=all/allfiles;inode/directory;
Actions=hydrate;dehydrate;pin;unpin;

[Desktop Action hydrate]
Name=UniDrive \u2014 Hydrate
Exec=$NAUTILUS_DIR/unidrive-menu.sh hydrate %F
Icon=cloud-download

[Desktop Action dehydrate]
Name=UniDrive \u2014 Dehydrate
Exec=$NAUTILUS_DIR/unidrive-menu.sh dehydrate %F
Icon=cloud-upload

[Desktop Action pin]
Name=UniDrive \u2014 Pin
Exec=$NAUTILUS_DIR/unidrive-menu.sh pin %F
Icon=pin

[Desktop Action unpin]
Name=UniDrive \u2014 Unpin
Exec=$NAUTILUS_DIR/unidrive-menu.sh unpin %F
Icon=unpin
DESKTOP
    }

    XDG_DATA="${XDG_DATA_HOME:-$HOME/.local/share}"

    KIO_DIR="$XDG_DATA/kio/servicemenus"
    echo "Installing Dolphin (Plasma 6) service menu to $KIO_DIR ..."
    write_dolphin_menu "$KIO_DIR"
    echo "  Done."

    if [[ -d "$XDG_DATA/kservices5" ]]; then
        KSVC_DIR="$XDG_DATA/kservices5/ServiceMenus"
        echo "Installing Dolphin (KDE5) service menu to $KSVC_DIR ..."
        write_dolphin_menu "$KSVC_DIR"
        echo "  Done."
    fi

    echo "  Right-click -> UniDrive actions."
fi

echo ""
echo "Installation complete. Restart your file manager to pick up the new entries."
