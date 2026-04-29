#!/usr/bin/env bash
# install-mcps.sh — one-shot installer for the Chunk G MCP wave.
#
# Creates a single shared venv under scripts/dev/.venv-mcps/, installs the
# union of all six MCPs' Python dependencies, and emits (or merges) the
# Claude Code MCP config fragment.
#
# Usage:
#   scripts/dev/install-mcps.sh                  # install venv + print fragment
#   scripts/dev/install-mcps.sh --print          # print fragment only (no install)
#   scripts/dev/install-mcps.sh --merge          # install + deep-merge into ~/.claude/claude_desktop_config.json (backup first)
#   scripts/dev/install-mcps.sh --config <path>  # use a non-default config path with --merge
#   scripts/dev/install-mcps.sh --no-install     # skip venv creation + pip
#
# The oauth-mcp (UD-723) keeps its own venv and is NOT touched.
#
# See UD-730.

set -euo pipefail

cd "$(dirname "$0")/../.."
REPO="$(pwd)"

INSTALL=1
MERGE=0
PRINT_ONLY=0
CONFIG_PATH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --print)
      PRINT_ONLY=1
      INSTALL=0
      shift
      ;;
    --merge)
      MERGE=1
      shift
      ;;
    --no-install)
      INSTALL=0
      shift
      ;;
    --config)
      if [[ $# -lt 2 ]]; then
        echo "error: --config needs a path" >&2; exit 2
      fi
      CONFIG_PATH="$2"
      shift 2
      ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "error: unknown arg: $1" >&2
      echo "see --help" >&2
      exit 2
      ;;
  esac
done

VENV="$REPO/scripts/dev/.venv-mcps"
if [[ "$(uname -s)" =~ (MINGW|MSYS|CYGWIN) || "${OS:-}" == "Windows_NT" ]]; then
  PY_IN_VENV="$VENV/Scripts/python.exe"
  IS_WIN=1
else
  PY_IN_VENV="$VENV/bin/python"
  IS_WIN=0
fi

# The six MCPs that came with Chunk G. Order is intentional — same order
# as the fragment emitted below so the `mcpServers` keys read left-to-right
# in one predictable block.
MCPS=(
  "unidrive-backlog:backlog-mcp"
  "unidrive-gradle:gradle-mcp"
  "unidrive-memory:memory-mcp"
  "unidrive-log-tail:log-tail-mcp"
  "unidrive-jvm-monitor:jvm-monitor-mcp"
  "unidrive-daemon-ipc:daemon-ipc-mcp"
)

# ---------- sanity: all six subdirs exist ------------------------------------

missing=()
for m in "${MCPS[@]}"; do
  dir="${m#*:}"
  if [[ ! -f "scripts/dev/$dir/server.py" ]]; then
    missing+=("scripts/dev/$dir/server.py")
  fi
done
if [[ ${#missing[@]} -gt 0 ]]; then
  echo "error: missing MCP server.py files:" >&2
  printf '  %s\n' "${missing[@]}" >&2
  echo "did the Chunk G commits land?" >&2
  exit 2
fi

# ---------- venv + pip --------------------------------------------------------

if [[ $INSTALL -eq 1 ]]; then
  if [[ ! -d "$VENV" ]]; then
    echo "==> creating shared venv at $VENV"
    if command -v py >/dev/null 2>&1; then
      py -m venv "$VENV"
    else
      python3 -m venv "$VENV" 2>/dev/null || python -m venv "$VENV"
    fi
  else
    echo "==> reusing existing venv at $VENV"
  fi

  echo "==> pip install mcp + psutil (union of all six MCPs' deps)"
  "$PY_IN_VENV" -m pip install --quiet --upgrade pip
  "$PY_IN_VENV" -m pip install --quiet "mcp>=1.0.0" "psutil>=5.9.0"
  echo "==> deps installed:"
  "$PY_IN_VENV" -m pip show mcp psutil 2>/dev/null | grep -E '^(Name|Version):' | paste -d' ' - -
fi

# ---------- pick a python ----------------------------------------------------

# Prefer the venv's python once it exists (after --install runs). Otherwise
# fall back to any system python for the fragment-emission step.
pick_python() {
  if [[ -x "$PY_IN_VENV" ]]; then
    echo "$PY_IN_VENV"
  elif command -v py >/dev/null 2>&1; then
    echo "py"
  elif command -v python3 >/dev/null 2>&1; then
    echo "python3"
  elif command -v python >/dev/null 2>&1; then
    echo "python"
  else
    echo "error: no python on PATH" >&2
    exit 3
  fi
}
PY="$(pick_python)"

# ---------- build the JSON fragment ------------------------------------------

REPO_ABS="$REPO"
if [[ $IS_WIN -eq 1 ]]; then
  # Prefer `cygpath -w` which always returns Windows-native form.
  if command -v cygpath >/dev/null 2>&1; then
    REPO_ABS="$(cygpath -w "$REPO")"
  else
    # Fall back to `pwd -W` (MSYS2/git-bash form).
    REPO_ABS="$(cd "$REPO" && pwd -W 2>/dev/null || pwd)"
  fi
fi

FRAGMENT="$(UD730_REPO="$REPO_ABS" UD730_IS_WIN="$IS_WIN" "$PY" - <<'PYEOF'
import json, os
repo = os.environ["UD730_REPO"]
is_win = os.environ["UD730_IS_WIN"] == "1"
sep = "\\" if is_win else "/"
py = sep.join([repo, "scripts", "dev", ".venv-mcps",
               "Scripts" if is_win else "bin",
               "python.exe" if is_win else "python"])
mcps = [
    ("unidrive-backlog",     "backlog-mcp"),
    ("unidrive-gradle",      "gradle-mcp"),
    ("unidrive-memory",      "memory-mcp"),
    ("unidrive-log-tail",    "log-tail-mcp"),
    ("unidrive-jvm-monitor", "jvm-monitor-mcp"),
    ("unidrive-daemon-ipc",  "daemon-ipc-mcp"),
]
entries = {}
for name, dirname in mcps:
    server = sep.join([repo, "scripts", "dev", dirname, "server.py"])
    entries[name] = {"command": py, "args": [server]}
print(json.dumps({"mcpServers": entries}, indent=2))
PYEOF
)"

echo
echo "==> Claude Code MCP config fragment:"
echo "$FRAGMENT"

# ---------- merge into user's config if asked --------------------------------

if [[ $MERGE -eq 1 ]]; then
  if [[ -z "$CONFIG_PATH" ]]; then
    if [[ $IS_WIN -eq 1 ]]; then
      CONFIG_PATH="$APPDATA/Claude/claude_desktop_config.json"
      # Fall back to ~/.claude if Claude Desktop isn't the harness
      [[ -f "$CONFIG_PATH" ]] || CONFIG_PATH="$HOME/.claude/claude_desktop_config.json"
    else
      CONFIG_PATH="$HOME/.claude/claude_desktop_config.json"
    fi
  fi

  echo
  echo "==> merging into $CONFIG_PATH"

  if [[ ! -f "$CONFIG_PATH" ]]; then
    mkdir -p "$(dirname "$CONFIG_PATH")"
    echo '{"mcpServers": {}}' > "$CONFIG_PATH"
    echo "   (created empty config)"
  fi

  BACKUP="$CONFIG_PATH.bak.$(date +%Y%m%d-%H%M%S)"
  cp "$CONFIG_PATH" "$BACKUP"
  echo "   backup: $BACKUP"

  UD730_CFG="$CONFIG_PATH" UD730_FRAGMENT="$FRAGMENT" "$PY" - <<'PYEOF'
import json, os, sys
cfg_path = os.environ["UD730_CFG"]
fragment = json.loads(os.environ["UD730_FRAGMENT"])
with open(cfg_path, encoding="utf-8") as f:
    try:
        cfg = json.load(f)
    except json.JSONDecodeError:
        print("config file is not valid JSON — refusing to merge", file=sys.stderr)
        sys.exit(3)
cfg.setdefault("mcpServers", {})
added, updated = [], []
for k, v in fragment["mcpServers"].items():
    if k in cfg["mcpServers"]:
        if cfg["mcpServers"][k] == v:
            continue
        updated.append(k)
    else:
        added.append(k)
    cfg["mcpServers"][k] = v
with open(cfg_path, "w", encoding="utf-8") as f:
    json.dump(cfg, f, indent=2)
    f.write("\n")
print(f"   added:   {added}")
print(f"   updated: {updated}")
PYEOF

  echo
  echo "==> done. Restart Claude Code to pick up the new MCPs."
  echo "   Uninstall path: remove the entries from $CONFIG_PATH"
  echo "   (or restore from $BACKUP)."
else
  echo
  echo "==> print-only mode. Paste the mcpServers block above into:"
  if [[ $IS_WIN -eq 1 ]]; then
    echo "     %APPDATA%\\Claude\\claude_desktop_config.json"
    echo "   or ~/.claude/claude_desktop_config.json"
  else
    echo "     ~/.claude/claude_desktop_config.json"
  fi
  echo
  echo "   Or re-run with --merge to deep-merge automatically (a backup is written)."
fi
