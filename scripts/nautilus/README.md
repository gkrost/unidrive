# UniDrive file-manager context menus

Right-click integration for **Nautilus** (GNOME Files), **Nemo** (Cinnamon),
and **Dolphin** (KDE). Adds four actions to file/folder context menus:

| Action    | CLI subcommand          | Effect                              |
|-----------|-------------------------|-------------------------------------|
| Hydrate   | `unidrive get <path>`   | Download placeholder content        |
| Dehydrate | `unidrive free <path>`  | Free local disk; keep placeholder   |
| Pin       | `unidrive pin <path>`   | Add eager-download rule             |
| Unpin     | `unidrive unpin <path>` | Remove eager-download rule          |

## How it works

`unidrive-menu.sh` is the dispatcher. It parses `~/.config/unidrive/config.toml`,
maps the selected file's absolute path to its `(profile, remote_path)` tuple
via the matching `sync_root`, and invokes `unidrive -p <profile> <subcmd>
<remote-path>`.

The dispatcher looks for the CLI in this order:

1. `unidrive` on `$PATH`
2. `$UNIDRIVE_JAR` environment variable
3. `~/.local/share/unidrive/unidrive.jar`, `~/.local/lib/unidrive/unidrive.jar`,
   `/usr/local/lib/unidrive/unidrive.jar`, `/opt/unidrive/unidrive.jar`
4. Repo-local `core/app/cli/build/libs/unidrive.jar` (dev installs)

Selection is read from `$NAUTILUS_SCRIPT_SELECTED_FILE_PATHS`,
`$NEMO_SCRIPT_SELECTED_FILE_PATHS`, or Dolphin's positional `%F` arguments.

## Install

    bash scripts/install-menus.sh

Restart your file manager afterwards. Auto-detects which FMs are installed.

## Uninstall

    rm -rf ~/.local/share/nautilus/scripts/UniDrive
    rm -rf ~/.local/share/nemo/scripts/UniDrive
    rm -f  ~/.local/share/kio/servicemenus/unidrive.desktop
    rm -f  ~/.local/share/kservices5/ServiceMenus/unidrive.desktop
