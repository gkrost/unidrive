# Independent mirror and mount profiles

> **Status: Accepted 2026-10-05, implementation in progress ([#560](https://github.com/gkrost/unidrive/issues/560)).** The Decision section describes the target. The engine and Windows client now require an explicit profile mode and refuse unsupported modeless profiles before mutable work. The mirror-only #459 cache-presence delete guard is retired: mirror profiles no longer share mount cache bytes. `cache_backed` remains until its mount-side uses are removed in a separate U8 change. Other coordinated behavior is retired only by its corresponding units.

## Context

A profile today can be served two ways at once, and the two are partly coordinated:

- **Mirror:** `unidrive sync` reconciles a local `sync_root` with the cloud.
- **Mount:** `unidrive daemon run` serves the hydration IPC verbs to a platform client (FUSE on Linux, CfAPI on Windows), which shows the cloud as on-demand files and writes back through `hydration.*`.

On 2026-10-01 the owner decided that both folders stay and are coordinated ([unidrive-windows#84](https://github.com/gkrost/unidrive-windows/issues/84)): #141 would make `sync` a client of the daemon, and the bytes would be kept in step. Parts of that landed:

- #478 (for #449, #450): the mount serves reads from the `sync_root` copy and mirrors what it writes into the `sync_root`; a delete or rename through the mount drops or moves the `sync_root` copy.
- #459: the Reconciler suppressed a remote delete while a hydration cache copy existed (retired after U6 mode isolation and the pre-mutation mode gate).
- #510 (for #504): the daemon rescans the `sync_root` at start and on a timer and uploads what appears there.

The result, verified on main in #560 section 1:

- **Code.** Mount-only operations live inside `SyncEngine`; `unidrive sync` also constructs a hydration server and registers every `hydration.*` verb; `refresh.run` without a connected mount client falls back to a reset and a mirror reconcile; late-bound lambdas couple the two directions.
- **State.** `sync_entries.is_hydrated` means "real bytes in the `sync_root`" for the mirror and "a cache copy exists" for the mount. One pending-upload predicate serves both writers. `cache_backed` (#449) exists only so the mirror can tell mount rows apart.
- **Bytes.** A file can live in three places (the platform view, the hydration cache, the `sync_root`). The `sync_root` is never downloaded into and never propagates a delete; it has become a half-coordinated second working folder.
- **Docs.** The mode-mutex spec closes coexistence (NG2), the daemon spec makes `sync` and `daemon` mutually exclusive (I2), and `ipc-write-protocol.md` §8 names the mount as the single writer, while #84 and #141 call for coexistence and the code already writes into and uploads from the `sync_root`.

Two options were weighed in #560 section 3: keep the coordinated folders and finish #141 (Option 1), or give each profile one mode (Option 2).

## Decision

**Option 2. A profile is either a mirror or a mount, never both. Someone who wants both on one cloud account uses two profiles; they meet only in the cloud.**

- A profile has an explicit `mode = mirror | mount`, chosen when the profile is created and fixed afterwards. There is no command to switch a profile's mode; someone who wants the other mode creates a second profile. (A switch for advanced users is deferred, see Re-opening criteria.) Profiles that exist before the cutover get their mode once, through the legacy conversion under Migration.
- Each profile owns its own state database, local bytes, cache and staging, IPC endpoint, process lock and lifecycle. Two profiles on the same account exchange changes through the provider, like any two independent clients.
- **Mirror profiles** run `unidrive sync`. They do not construct the hydration runtime and have no cache semantics.
- **Mount profiles** run `unidrive daemon run`, which serves `hydration.*` to the platform client. They have no `sync_root` scan, no mirror writes and no `sync_root`-to-cache fallback. `refresh.run` on a mount profile always enumerates, with or without a connected client.
- A command for the wrong mode is refused before any provider write or mutable startup recovery, with a message that says what to run instead. One process lock per profile stays.
- Mode and capabilities are reported in status and at startup, so an incompatible engine/client pair fails clearly before it writes.
- In code, mirror reconciliation and mount operations become two front-ends (`SyncEngine`, `MountEngine`) over a small shared core (working name `app:engine-core`: guarded remote operations, transfer accounting, enumeration, state repositories, path and scope rules). The extraction itself changes no behaviour.

## Consequences

Costs, and the conditions under which the cutover may ship (#560 sections 3 and 4):

- **Duplicate work.** Two profiles on one account download and store their bytes separately, and changes propagate eventually, not at once. No latency is promised until discovery is defined: tested poll/notification defaults and reconnect catch-up (#463) ship with the cutover, not after it.
- **Independent writers can conflict.** Provider capability limits, base-token handling, conflict recovery and delete-versus-edit behaviour must be specified and tested. Internxt checks metadata tokens but has no atomic If-Match-style replace, so two profiles keep a check-to-write window; lossless simultaneous edits are not promised.
- **Shared account limits.** Throttling, retries and token refresh across two profiles on one account must be validated. The per-process transfer cap is not account-wide protection; either an account-wide budget is chosen or a tested independent-client limit is documented.
- **Legacy boundary before cutover.** The owner decision is no legacy support or migration until first LTS. Modeless profiles are refused before mutable work. Each obsolete bridge can therefore be removed separately once its owning mode and live safety invariant are proven; `cache_backed` and the remaining cross-mode hooks still require their own gates and changes.
- **Clients and packaging.** The write verbs stay as they are, but client startup, recovery, status, banner text and test expectations change. The shipped autostarts (`dist/unidrive.service`, #325; the Windows logon autostart, #506) start `sync --watch`; they are replaced per profile mode, without leaving both launchers active.

### Migration

This is a one-time conversion of the profiles that exist in the coordinated state before the cutover (legacy profiles), not a general mode switch. It must exist because the cutover removes the bridges that keep those profiles safe today: a legacy profile is converted, or refused before any mutation, before those bridges go.

Existing rows cannot prove which front-end wrote them, so there is no automatic "mirror unless mount-written rows exist" rule and no upload on first start. Conversion is:

- **Explicit.** The owner picks the destination mode for each legacy profile; no ordinary startup assigns one. A read-only dry-run inventory of a stopped profile (config, state, cache and staging, `sync_root`, client-side recovery files) comes first and reports unknowns as unknown, not as clean.
- **Journalled.** Writers are quiesced, an idempotent journal records phases, the original configuration and schema, and a file manifest. Every unique local version is preserved; an interrupted conversion resumes without duplicate uploads, and the new mode is published only after the state and byte checks pass. Older incompatible binaries are refused against converted state.
- **Free of implicit cloud writes.** No conflict uploads, deletions or remote reset happen because a process starts or a conversion runs.

**Shipped: the inventory.** `unidrive -p <profile> migrate inventory [--json]` is the dry-run step. It refuses while `sync` or `daemon` holds the profile lock and, while it runs, holds a shared lock that keeps them from starting; it reads `state.db` from a `VACUUM INTO` copy taken through a read-only connection, and never writes, moves or uploads anything. It reports the config, the rows by status, `local:` rows, pending uploads under both predicates (`pendingUploadPaths()` and the mount's and the mirror's subsets), refused and failed uploads, the `cache_backed` distribution and tombstones; it classifies every local path as in both copies (identical or different), in the `sync_root` only, in the cache only, a row without a local copy, or a file without a row (excluded and out-of-scope files named separately); and it lists Internxt upload staging and the Windows client's recovery files by name and size. A copy counts as matching the cloud only by content hash (the provider's content hash, or the `local_hash` of the bytes last exchanged); Internxt's `remote_hash` is a version token and is never compared. Everything else is reported as unknown and at risk. The conversion itself is not built yet.

#### The old `sync_root` after a conversion to mount

Under the coordinated model the `sync_root` of a mount profile was never a full copy: it holds what earlier `sync` runs left plus what the mount mirrored into it (#478). Left in place after the conversion it would look like a synced folder while nothing syncs it, so an edit there would never reach the cloud. Its only value is the files that exist nowhere else. The conversion therefore ends with an explicit choice, made by the owner per profile, after every file in it has been compared with the cloud by content hash (an unavailable or metadata-only comparison counts as *differs*):

- **Retire (default).** Files identical to the cloud go to the operating system's trash or recycle bin, so they stay recoverable. Files that differ or exist only locally move to a quarantine folder next to the profile's state, with a manifest. One explicit command uploads them as conflict copies; nothing uploads them automatically. Afterwards the folder is gone.
- **Adopt as a new mirror profile.** The folder becomes the `sync_root` of a new mirror profile on the same account. This is ordinary profile creation over an existing folder, with a baseline check so that files missing from the folder are not read as deletes. It is not a mode switch of the converted profile.

Until the owner chooses, the folder stays untouched, a marker in it and the profile's status say it is not synced, and the conversion counts as unfinished.

## What this supersedes

- **[unidrive-windows#84](https://github.com/gkrost/unidrive-windows/issues/84)** ("both folders stay, coordinated", 2026-10-01). Superseded by this decision; its coordination stays in place until the cutover.
- **#141** (`sync` as a client of the daemon). Replaced by the Option 2 hosting contract (#560 unit 4); it stays open until that lands and is then closed as superseded, not as delivered.
- **[`mount-sync-mode-mutex-design.md`](../dev/specs/mount-sync-mode-mutex-design.md), in part.** One mode per profile stays (G1, NG2 per profile). First-writer-wins (G3) and "no profile-level mode flag" (NG3) are replaced by the explicit `mode`; coexistence of a mirror and a mount on one account is supported, as two profiles.
- **[`unidrive-daemon-design.md`](../dev/specs/unidrive-daemon-design.md), in part.** I2 (`sync` ⇄ `daemon` exclusion per profile) becomes a consequence of the profile's mode rather than of which process starts first; discovery defaults follow #463.
- **[`ipc-write-protocol.md`](../dev/specs/ipc-write-protocol.md) §8, in part.** The mount's co-client is the single local writer of a mount profile only after the cutover; today the daemon also writes into and uploads from the `sync_root` (#478, #510). Other profiles on the same account are independent cloud-side writers.

## Re-opening criteria

If two profiles on one account cannot be made safe enough for the providers in use (conflict rate, throttling, or the cost of duplicate storage), the coordinated model of #84 (Option 1 in #560) is the fallback. Re-opening needs a recorded owner decision on #560 or its successor; until then, units already merged keep their behaviour-preserving guarantees.

A command that switches an existing profile's mode (for advanced users) is deferred as P3 ([#564](https://github.com/gkrost/unidrive/issues/564)); if it is ever built, it reuses the legacy conversion's machinery and rules.
