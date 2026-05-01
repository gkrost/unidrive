# BACKLOG_IDEAS_UI.md — UI / Desktop / GUI ideas

This file is the **idea sink** for UI, Desktop, GUI, system-tray, file-manager, and OS-shell-integration work that is **out of scope for the unidrive-cli MVP** per [ADR-0013](../adr/0013-ui-removal.md) ("UI / tray work moved to companion projects").

**Why a separate file:** the main `BACKLOG.md` is the contract for in-tree work that gets implemented and tested under the existing build + CI. UI ideas live here so they don't pollute that contract, but are preserved for future companion projects (`unidrive-android`, hypothetical `unidrive-tray`, `unidrive-shell-extension`, etc.).

**Mechanics:** entries below use the same frontmatter shape as `BACKLOG.md` but are NOT consumed by `scripts/dev/backlog.py` or `scripts/backlog-sync.kts` — `id` fields here are illustrative, not registered. When a companion project picks an item up, it gets a real ID in that project's tracker (or a UD-### in this repo if it ends up touching shared code like `IpcServer`).

Status as of 2026-05-01. Salvaged from `logic-arts-official/unidrive` HEAD `b8e4223` (`WINDOWS-BACKLOG.md`) plus general-backlog UI items.

## Cross-platform tray UI

### W11 — Cumulative totals from status DB in tray popover  ← HIGH

**Problem:** Tray popover shows only last-cycle IPC counts (↓1 ↑0 ⚠0). Users expect total file count, storage used, credential health — matching `unidrive status` output (e.g. "2,337 files, 19 GB, [✔ OK]").

**Data needed per profile:**
- Total files synced, sparse count
- Cloud size, local size
- Credential health (OK / expiring / expired)
- Last sync timestamp
- Per-cycle counts

**Options:**
1. **Read StateDatabase directly** — UI opens `~/.config/unidrive/<profile>/state.db` read-only. No daemon dependency. Risk: file locking contention with running daemon.
2. **Add `status_summary` IPC event** — daemon broadcasts totals after each sync_complete. No DB contention. Requires daemon-side change.
3. **CLI subprocess** — UI runs `unidrive status --json` periodically. Simplest but spawns a JVM each time (~2 s overhead).

**Recommendation:** Option 2. Daemon already has the data; serialise it after each cycle. Spec the event in the restored `IPC-PROTOCOL.md` (UD-763).

**Multi-profile popover layout** (ASCII mockup from old WINDOWS-BACKLOG):

```
┌─────────────────────────────────────┐
│ UniDrive                    ⚙️ ☰   │
├─────────────────────────────────────┤
│ ● OneDrive (gerno@outlook.de)      │
│   2,337 files · 19 GB · ✔ OK       │
│   Idle — last sync: 14:16          │
│   ↓1 ↑0 ⚠0                        │
│   [━━━━━━━━━━━━━━━━━━━━━━━] 100%   │
├─────────────────────────────────────┤
│ ● SFTP (ds418play)                  │
│   841 files · 2.1 GB · ✔ OK        │
│   Idle — last sync: 13:50          │
├─────────────────────────────────────┤
│ ● S3 (backup-bucket)               │
│   Not configured                    │
├─────────────────────────────────────┤
│ 3,178 files total · 21.1 GB        │
│ Quota: 45 GB used / 100 GB         │
└─────────────────────────────────────┘
```

- Header row with settings gear + hamburger
- Per-profile card: status dot, provider+account, totals, last sync, per-cycle counts
- Scrollable if > 4 profiles
- Footer: aggregate totals across all providers
- Progress bar only shown during active sync

**Scope:** M. Needs daemon IPC protocol extension + UI layout rework.

### W12 — Modern theme (FlatLaf or system LAF)  ← HIGH

**Problem:** Default Swing Metal theme is ugly and looks foreign on Windows 11.

**Options:**
1. **FlatLaf** — modern flat LAF for Swing. Dark/light/auto. One dependency. `UIManager.setLookAndFeel(FlatLightLaf())` or `FlatDarkLaf()`. Auto-detects Windows dark mode via registry. [flatlaf.com](https://www.formdev.com/flatlaf/)
2. **System LAF** — `UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())`. Native Windows look but no dark mode support.
3. **Compose Desktop** — replace AWT entirely. Rich UI but major rewrite.

**Recommendation:** FlatLaf — single line change, immediate improvement, dark/light/auto.

```kotlin
// In Main.kt before any UI creation
FlatDarkLaf.setup()  // or FlatLaf.setup() for auto
```

Add dependency: `implementation("com.formdev:flatlaf:3.6")`

Also applies to StatusPopover custom painting — colors should come from LAF theme instead of hardcoded RGB.

**Scope:** S. One dependency + theme setup + popover color refactor.

### Multi-profile/multi-account scaling

When N providers × M accounts are active, the popover needs to scale:
- Group accounts under provider (collapsible per-provider headers)
- Compact mode (2 lines/account) vs expanded (4 lines/account)
- Per-account colour-coding for credential health
- Toggle: "show idle profiles" off by default once a user has >5

## Distribution

### W16 — `jpackage` MSI installer  ← CHOSEN distribution for Windows

Primary distribution artifact for Windows. Bundles JRE — no JDK prerequisite.

```bash
jpackage --input lib/ --main-jar unidrive.jar --main-class org.krost.unidrive.cli.MainKt \
  --name UniDrive --type msi --win-menu --win-shortcut \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --icon resources/unidrive.ico --vendor "Gernot Krost" --app-version 0.2.8
```

**Plan:**
1. Add `jpackage` Gradle task building MSI from shadow JARs (CLI + MCP + UI)
2. Bundle jlink-minimised JRE (~40 MB)
3. MSI installs to `%LOCALAPPDATA%\Programs\UniDrive\`, PATH, Start Menu
4. Post-install: startup entry for UI, register MCP

**Follow-up channels** (after MSI works):

| Channel | Effort | Notes |
|---------|--------|-------|
| GitHub Releases + `install.ps1` | Low | ZIP + PowerShell bootstrap |
| Scoop bucket | Low | JSON manifest → GH Release |
| WinGet manifest | Medium | YAML PR to `microsoft/winget-pkgs` |

**Scope:** L.

## Shell / OS integration

### W14 — Windows Explorer shell integration  ← LOW priority, deferred

- Overlay icons on files/folders (synced ✔, syncing ↻, placeholder ☁, conflict ⚠)
- Context menu: Pin, Free, Sync now, Share, Versions
- Requires COM/DLL shell extension — C++ or C# companion project
- Alternative: CloudAPI (`CfRegisterSyncRoot`) for native Windows placeholder support

**Scope:** XL. Separate project. Deferred until CLI+UI are stable.

### W15 — Sparse file detection on Windows (NTFS)

**Current:** `PlaceholderManager.isSparse()` always returns `false` on Windows.
**Fix:** Use `fsutil sparse queryflag` or `java.nio.file.Files.readAttributes()` with DOS attributes.
Already partially implemented (`fsutil sparse setflag/setrange` for creation) but detection is stubbed.

**Scope:** S.

### Linux file-manager menus  ← already filed in main BACKLOG as UD-760

Right-click → UniDrive → Hydrate / Dehydrate / Pin / Unpin in Nautilus, Nemo, Dolphin. **Not deferred** — this is in `BACKLOG.md` because the CLI commands it shells out to all live in `:app:cli`. Listed here too for completeness of the desktop-integration story.

### Linux file-manager overlay icons  ← XL, future

Comparable to Windows shell-extension overlays:
- Nautilus: requires a libnautilus-extension Python or C extension
- Nemo: same shape, libnemo-extension
- Dolphin: KIO::ThumbCreator subclass

**Scope:** XL per file manager. Probably want to pick one (Nautilus, since GNOME is the largest user base) and decide based on Windows traction whether to do the others.

## MCP integration with desktop AI assistants

### W13 — `mcp install` CLI command

Auto-register MCP server with Claude Desktop / Claude Code:
- Reads Claude config at `%APPDATA%\Claude\claude_desktop_config.json` (Windows) or `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `~/.config/Claude/claude_desktop_config.json` (Linux)
- Merges `unidrive` server entry with JAR path + profile env var
- Also writes to `~/.claude/settings.json` (project-level) or `~/.config/claude-code/settings.json` if `.claude` dir exists

```bash
unidrive mcp install                  # auto-detect Claude flavour
unidrive mcp install --target=desktop # only Claude Desktop
unidrive mcp install --target=code    # only Claude Code
unidrive mcp install --uninstall      # symmetric cleanup
```

**Scope:** S. Relates to general BACKLOG #150 (MCP `--stdio` flag).

## Cross-platform UI ideas (not Windows-specific)

### Status indicator in GNOME / KDE notification area

GNOME Shell uses `libappindicator` or AyatanaAppIndicator (replacement after GNOME removed legacy tray). KDE Plasma has its own SystemTray spec. A cross-platform tray needs to abstract this.

**Recommendation:** `org.kde.StatusNotifierItem` (StatusNotifierWatcher D-Bus interface) — supported by both GNOME (with extension) and KDE natively. Java binding via [SystemTray](https://github.com/dorkbox/SystemTray) library or hand-rolled D-Bus.

### Notify-send progress for long-running sync cycles

Already partially done: `NotifyProgressReporter.kt` exists. But it only emits per-cycle complete events. For cycles that take >30 s, emit interim "X% complete, Y files remaining" notifications via `notify-send --replace-id` so the user has feedback.

### Desktop notification on conflict resolution

When `keep_both` policy resolves a conflict, fire a desktop notification with the file path and a "Show in Files" action. Currently visible only in `unidrive conflicts` output.

## ADR-0013 boundary — what stays out of unidrive-cli

To preserve ADR-0013's separation:

1. **No Swing/AWT/Compose code in `core/`** — UI lives in companion projects.
2. **The IPC contract is the integration point** — see UD-763 for restoring `IPC-PROTOCOL.md`.
3. **`unidrive status --json` and `unidrive status --watch`** — primary read APIs for any external UI to consume without depending on internal Kotlin types.
4. **No daemon-side UI assumptions** — daemon broadcasts events; doesn't care if anyone's listening.

If a UI feature requires a CLI / daemon change, that change goes into the main `BACKLOG.md` as a UD-### ticket; this file just records the UI motivation.

## Provenance

- `WINDOWS-BACKLOG.md` from `logic-arts-official/unidrive`@`b8e4223` (2026-04-16) — items W1–W16
- General-backlog UI references: `#40` (cross-platform UI), `#140` (overlay icons), `#150` (MCP --stdio), `#152` (MCP watch_events), `#93` (SecretService), `#95` (Intune SSO), `#159` (Docker test matrix Windows runner)
- ADR-0013 — UI / tray removal from unidrive-cli core
