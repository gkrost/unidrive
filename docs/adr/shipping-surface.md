# Shipping surface

> **Status: Superseded by [`multi-platform.md`](multi-platform.md).** The slim shipping surface remains accurate for the Linux daemon as it stands today, but the framing that no further surfaces will ship has been replaced. Re-opening criterion (a) — a funded effort with ongoing maintenance — is what motivated the supersede. Body kept as historical record.

## Context

Earlier iterations carried several surfaces that were dropped on the way to the slim experiment:

- `shell-win/` — Windows Explorer overlay icons + CFAPI placeholders.
- `ui/` — Compose-Multiplatform tray + settings UI.
- `protocol/` — JSON-Schema NDJSON envelope contract + golden fixtures.
- Windows named-pipe transport — `NamedPipeServer.kt`, `PipeSecurity.kt`, `NdjsonValidator.kt`.
- Several provider adapters: `s3`, `sftp`, `webdav`, `rclone`, `localfs`.
- An MCP server module, an end-to-end test harness, a benchmark runner, a full-CLI bundler, and an Xtra E2EE wrapper for non-encrypted providers.

The slim branch ships the smallest surface that still does what unidrive promised: sync two specific providers over Linux.

## Decision

What ships:

| Component | Path | What it is |
|---|---|---|
| CLI | `core/app/cli/` | The `unidrive` command-line tool. Sole user-facing surface. |
| Sync engine | `core/app/sync/` | Reconciliation engine, state DB, local watcher, IPC reporter. |
| Provider core | `core/app/core/` | Cross-provider primitives: `ProviderRegistry`, SPI, HTTP utilities, auth helpers. |
| Config | `core/app/config/` | TOML config + profile management. |
| Internxt provider | `core/providers/internxt/` | |
| OneDrive provider | `core/providers/onedrive/` | |

What does **not** ship: everything in the Context list above. AGENTS.md's "Don't reintroduce removed providers, modules, scripts, or workflows" is the binding rule.

IPC: one Unix-domain socket per profile state directory; one client family (the CLI). Third-party consumers may subscribe to the NDJSON event stream — there is no policy against it, but no formal contract layer is maintained for them either.

## Re-opening criteria

For any retired surface — a third provider, an MCP server, a tray UI, a non-Linux platform — bring back means: (a) a funded effort that includes ongoing maintenance, (b) a specific paying customer or partner whose use-case requires it, or (c) a community contributor who proposes to own the platform-tier code. None of these signals exist today.
