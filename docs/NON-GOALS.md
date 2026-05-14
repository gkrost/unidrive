# Non-goals

unidrive is deliberately narrow. This page lists what it does **not**
aim to be. A non-goal is not a permanent ban — most have re-opening
criteria documented in the linked ADRs — but it is the current
shipping posture, and feature requests that fight a non-goal need to
move the non-goal first.

## unidrive is not a backup tool

We sync deltas; we do not snapshot history-aware archives. Use
`restic`, `borg`, `duplicacy`, or your cloud provider's
built-in versioning for that.

`unidrive backup add` exists as a one-way-replication shortcut. That
is not a backup tool in the snapshot sense — it is a sync
configuration aimed at "this folder gets pushed up and is never
pulled back."

## unidrive does not run on Windows or macOS in v0.1.0

Linux only. Authority: [ADR-0012](adr/0012-linux-mvp-protocol-removal.md)
("Linux-only MVP and removal of the protocol/ contract directory").
Re-opening criteria are listed in the ADR itself and require concrete
demand + capacity to do the platform properly (CI runners, packaging,
end-user docs).

Both platforms are post-v0.3.0 candidates per the
[roadmap](ROADMAP.md). Windows-specific code (`shell-win/`,
`NamedPipeServer.kt`, `PipeSecurity.kt`) was deliberately removed in
ADR-0011 + ADR-0012; reintroducing them is a deliberate decision, not
a small follow-up.

## unidrive does not ship a system-tray UI in core

The `ui/` Compose-Multiplatform tray was removed by
[ADR-0013](adr/0013-ui-removal.md). The daemon's NDJSON event stream
over UDS is the public contract — third-party trays, system-tray
applets, or status widgets that consume it are explicitly welcome.

If you build one, file an issue so we can link it from the README.
The `IpcProgressReporter.kt` event shape is the contract for that
integration.

## unidrive does not implement provider-specific features that don't generalise

The `CloudProvider` interface is deliberately minimal. Provider
quirks live behind capabilities — see
[ADR-0005](adr/0005-provider-capability-contract.md). Examples of
features that have not been generalised and so do not ship:

- OneDrive **shared notebooks** (a Microsoft Graph affordance with no
  cross-provider analogue).
- Dropbox **Paper documents** (Dropbox-specific document model).
- iCloud **Sidecar** (an Apple-platform-only affordance).

A feature joins the surface when at least two providers can implement
it under one capability. Single-provider features can be added later
as opt-in capabilities, but they do not gate v0.1.0.

## unidrive does not auto-merge document conflicts

We surface conflicts (`unidrive conflicts`) and offer two policies:
`keep_both` (rename the local file with a conflict marker and keep
both copies) and `last_writer_wins`. We do not auto-merge document
contents — that is a different product, and any merge logic worth
having is application-specific (think: Word documents, source code,
Markdown, spreadsheets).

If you need three-way merge for files, plug a separate tool into the
`keep_both` output. We won't try to ship one.

## unidrive does not replace native cloud storage clients

OneDrive's official client, Dropbox's, Google Drive's all have
features we will not replicate:

- Cloud-only file streams (CFAPI on Windows, FileProvider on macOS).
- In-document collaboration awareness.
- Office / Google Docs / iWork live-edit integration.

unidrive aims to be the **best** Linux sync client for the providers
it supports, not a parity-with-native-client product. On platforms
where a native client exists and works, prefer it.

## unidrive does not aim to be embedded in another product

The CLI + daemon + MCP server are the supported surfaces. Embedding
`:app:sync` or `:app:core` as a library inside a different
application is not a goal — those modules' APIs are not maintained
for external consumers, and may change without notice between
patch versions.

If you have a use-case that requires embedding, file an issue. We
might add it to the roadmap; we won't refactor speculatively.

## Re-opening a non-goal

Each non-goal above either has an explicit re-opening criteria block
in the linked ADR, or can be re-opened by:

1. Filing a BACKLOG ticket that names the non-goal it's moving.
2. Writing an ADR that supersedes the non-goal (or, for non-goals
   not currently rooted in an ADR, writing the first one).
3. Demonstrating either funded effort or a community owner for the
   ongoing maintenance cost.

The bar is "this is now in scope," not "we will accept a PR." A
non-goal that hasn't moved is still a non-goal even if you've
written code that violates it.
