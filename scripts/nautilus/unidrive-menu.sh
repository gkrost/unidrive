#!/usr/bin/env bash
# UniDrive file-manager context-menu dispatcher.
# Called by thin wrapper scripts (one per action) that Nautilus/Nemo discover
# under ~/.local/share/{nautilus,nemo}/scripts/UniDrive/.
#
# Nautilus passes selected files in $NAUTILUS_SCRIPT_SELECTED_FILE_PATHS
# (newline-separated absolute paths). Nemo uses $NEMO_SCRIPT_SELECTED_FILE_PATHS.
# Dolphin ServiceMenus pass files as positional arguments (%F in Exec=).
set -euo pipefail

ACTION="${1:?Usage: unidrive-menu.sh <hydrate|dehydrate|pin|unpin>}"
shift

# ── Resolve selected files (Nautilus, Nemo, or positional args from Dolphin) ─

SELECTED="${NAUTILUS_SCRIPT_SELECTED_FILE_PATHS:-${NEMO_SCRIPT_SELECTED_FILE_PATHS:-}}"

if [[ -z "$SELECTED" && $# -gt 0 ]]; then
    SELECTED="$(printf '%s\n' "$@")"
fi

if [[ -z "$SELECTED" ]]; then
    notify-send --app-name=UniDrive "UniDrive" "No files selected." 2>/dev/null || true
    exit 1
fi

# ── Locate the unidrive CLI ──────────────────────────────────────────────────
# Prefer a `unidrive` binary on PATH (e.g. shipped install wrapper); otherwise
# fall back to `java -jar <fat-jar>` from well-known build/install locations.

UNIDRIVE_CMD=()
if command -v unidrive &>/dev/null; then
    UNIDRIVE_CMD=(unidrive)
else
    CANDIDATE_JARS=(
        "${UNIDRIVE_JAR:-}"
        "$HOME/.local/share/unidrive/unidrive.jar"
        "$HOME/.local/lib/unidrive/unidrive.jar"
        "/usr/local/lib/unidrive/unidrive.jar"
        "/opt/unidrive/unidrive.jar"
    )
    # Also probe the in-repo build output (useful for dev installs).
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    for rel in \
        "../../core/app/cli/build/libs/unidrive.jar" \
        "../../../core/app/cli/build/libs/unidrive.jar"
    do
        CANDIDATE_JARS+=("$SCRIPT_DIR/$rel")
    done

    JAR=""
    for cand in "${CANDIDATE_JARS[@]}"; do
        [[ -n "$cand" && -f "$cand" ]] || continue
        JAR="$cand"
        break
    done

    if [[ -z "$JAR" ]]; then
        notify-send --app-name=UniDrive "UniDrive" \
            "Cannot find unidrive CLI. Install it or set UNIDRIVE_JAR." 2>/dev/null || true
        echo "unidrive CLI not found; tried PATH and: ${CANDIDATE_JARS[*]}" >&2
        exit 1
    fi

    UNIDRIVE_CMD=(java -jar "$JAR")
fi

# ── Locate config ────────────────────────────────────────────────────────────

CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/unidrive"
CONFIG="$CONFIG_DIR/config.toml"

# ── Match file to profile + compute remote path ─────────────────────────────
# Reads all [providers.<name>] sections from config.toml, collects their
# sync_root (falling back to [general].sync_root, then to a per-type default).
# Returns: PROFILE_NAME and REMOTE_PATH as global variables.

PROFILE_NAME=""
REMOTE_PATH=""

resolve_profile_and_path() {
    local abs_file
    abs_file="$(realpath -- "$1")"

    if [[ -f "$CONFIG" ]]; then
        local current_profile="" current_type="" current_root="" general_root=""

        while IFS= read -r line; do
            # Strip inline comments and trailing whitespace
            line="${line%%#*}"
            line="${line%"${line##*[![:space:]]}"}"

            # [general]
            if [[ "$line" =~ ^\[general\]$ ]]; then
                current_profile=""
                current_type=""
                continue
            fi

            # [providers.<name>]
            if [[ "$line" =~ ^\[providers\.([^]]+)\]$ ]]; then
                current_profile="${BASH_REMATCH[1]}"
                current_type=""
                current_root=""
                continue
            fi

            # Any other section header resets context
            if [[ "$line" =~ ^\[.*\]$ ]]; then
                current_profile=""
                current_type=""
                current_root=""
                continue
            fi

            # key = "value" (double-quoted)
            local key="" val=""
            if [[ "$line" =~ ^([a-z_]+)[[:space:]]*=[[:space:]]*\"(.*)\"$ ]]; then
                key="${BASH_REMATCH[1]}"
                val="${BASH_REMATCH[2]}"
            # key = 'value' (single-quoted)
            elif [[ "$line" =~ ^([a-z_]+)[[:space:]]*=[[:space:]]*\'(.*)\'$ ]]; then
                key="${BASH_REMATCH[1]}"
                val="${BASH_REMATCH[2]}"
            fi

            [[ -z "$key" ]] && continue

            # Expand ~ at start of value
            val="${val/#\~/$HOME}"

            if [[ -z "$current_profile" ]]; then
                # We're in [general]
                if [[ "$key" == "sync_root" ]]; then
                    general_root="$val"
                fi
            else
                # We're in [providers.<name>]
                case "$key" in
                    type)      current_type="$val" ;;
                    sync_root) current_root="$val" ;;
                esac

                # Determine effective root for this profile
                local eff_root="${current_root:-${general_root:-}}"
                if [[ -z "$eff_root" ]]; then
                    local eff_type="${current_type:-$current_profile}"
                    case "$eff_type" in
                        onedrive) eff_root="$HOME/OneDrive" ;;
                        *)        eff_root="$HOME/${eff_type^}" ;;  # capitalise first char
                    esac
                fi

                eff_root="$(realpath -- "$eff_root" 2>/dev/null || echo "$eff_root")"

                if [[ "$abs_file" == "$eff_root"/* ]]; then
                    PROFILE_NAME="$current_profile"
                    REMOTE_PATH="/${abs_file#"$eff_root"/}"
                    return 0
                fi
            fi
        done < "$CONFIG"

        # Fallback: check general sync_root with default profile
        if [[ -n "$general_root" ]]; then
            general_root="$(realpath -- "$general_root" 2>/dev/null || echo "$general_root")"
            if [[ "$abs_file" == "$general_root"/* ]]; then
                PROFILE_NAME="onedrive"
                REMOTE_PATH="/${abs_file#"$general_root"/}"
                return 0
            fi
        fi
    fi

    # Last resort: well-known default roots
    local -A defaults=( [OneDrive]=onedrive )
    for dir in "${!defaults[@]}"; do
        local root
        root="$(realpath -- "$HOME/$dir" 2>/dev/null || echo "$HOME/$dir")"
        if [[ "$abs_file" == "$root"/* ]]; then
            PROFILE_NAME="${defaults[$dir]}"
            REMOTE_PATH="/${abs_file#"$root"/}"
            return 0
        fi
    done

    return 1
}

# ── Execute ──────────────────────────────────────────────────────────────────
# CLI subcommands as of UD-760 salvage (cross-checked against
# core/app/cli/src/main/kotlin/org/krost/unidrive/cli/):
#   hydrate   -> `unidrive -p <profile> get   <remote-path>`
#   dehydrate -> `unidrive -p <profile> free  <remote-path>`
#   pin       -> `unidrive -p <profile> pin   <remote-path>`
#   unpin     -> `unidrive -p <profile> unpin <remote-path>`

OK=0
FAIL=0

while IFS= read -r file; do
    [[ -z "$file" ]] && continue

    if ! resolve_profile_and_path "$file"; then
        echo "Not in a sync root: $file" >&2
        FAIL=$((FAIL + 1))
        continue
    fi

    PROFILE_FLAG=(-p "$PROFILE_NAME")

    case "$ACTION" in
        hydrate)   "${UNIDRIVE_CMD[@]}" "${PROFILE_FLAG[@]}" get   "$REMOTE_PATH" && OK=$((OK + 1)) || FAIL=$((FAIL + 1)) ;;
        dehydrate) "${UNIDRIVE_CMD[@]}" "${PROFILE_FLAG[@]}" free  "$REMOTE_PATH" && OK=$((OK + 1)) || FAIL=$((FAIL + 1)) ;;
        pin)       "${UNIDRIVE_CMD[@]}" "${PROFILE_FLAG[@]}" pin   "$REMOTE_PATH" && OK=$((OK + 1)) || FAIL=$((FAIL + 1)) ;;
        unpin)     "${UNIDRIVE_CMD[@]}" "${PROFILE_FLAG[@]}" unpin "$REMOTE_PATH" && OK=$((OK + 1)) || FAIL=$((FAIL + 1)) ;;
        *)
            echo "Unknown action: $ACTION" >&2
            exit 1
            ;;
    esac
done <<< "$SELECTED"

# ── Desktop notification ─────────────────────────────────────────────────────

if command -v notify-send &>/dev/null; then
    if [[ "$FAIL" -eq 0 ]]; then
        notify-send --app-name=UniDrive "UniDrive" "$ACTION: $OK file(s) done"
    else
        notify-send --app-name=UniDrive "UniDrive" "$ACTION: $OK ok, $FAIL failed"
    fi
fi
