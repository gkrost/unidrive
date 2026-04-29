---
id: ADR-0010
title: State storage backend — SQLite (status quo) vs filesystem tree
status: proposed
date: 2026-04-19
---

## Context

Per-profile sync state is held today in `state.db` (SQLite, WAL, single file per profile under `$config/<profile>/state.db`). It stores:

| Table | Purpose | Typical row count |
|---|---|---|
| `sync_entries` | one row per remote path (id, hash, size, mtime, flags, last_synced) | 10⁴ – 10⁶ |
| `sync_state` | K/V (`delta_cursor`, `pending_cursor`, `last_full_scan`) | ~3 |
| `pin_rules` | pattern+pinned flag | ~10 |
| `trash_entries` | one row per tombstoned item + retention ts | ~10² |
| `conflict_log` | append-only event log of sync conflicts | ~10² |
| `version_index` | snapshot metadata (per-path `<N>.version` rolls) | ~10³ |

Actual query shapes observed against that schema:

| Query | Shape | Callers |
|---|---|---|
| `getEntry(path)` | path → leaf lookup | every reconcile step |
| `upsertEntry(entry)` | write leaf | every action commit |
| `deleteEntry(path)` | unlink leaf | every DeleteRemote/DeleteLocal |
| `getAllEntries()` | full walk | full-sync delete detection, `unidrive log` |
| `renamePrefix(old, new)` | subtree move | folder-rename apply |
| `getEntryByRemoteId(id)` | reverse hash lookup | rename detection |
| `getEntryCaseInsensitive(path)` | case-folded lookup | case-collision detection |
| `getSyncState(k)` / `setSyncState(k, v)` | K/V | cursor persistence |
| `beginBatch / commitBatch / rollbackBatch` | transaction boundary | Pass 1 sync apply |

The SQLite choice was inherited from the pre-greenfield scaffold; it is also what rclone / OneDrive / Dropbox desktop clients all use for their local state. That is weak evidence — "everyone does it" is path dependence, not a designed decision. The user (Gernot) raised the question: **for a workload whose primary key is a remote filesystem path, are we flattening a natural tree into rows for no better reason than convention?**

This ADR lays out the two viable backend shapes side-by-side so a decision can be made deliberately.

## Decision

**Draft — not yet accepted.** The choice is between:

1. **Status quo: SQLite.** Keep `state.db`.
2. **Filesystem tree.** Replace `state.db` with a hidden `.unidrive-state/` directory whose layout mirrors the remote path tree, one JSON-ish `.meta` sidecar per path, plus small `.idx/` directories for the non-tree reverse indexes.

A third option (hybrid: SQLite for indexes + FS for bulk metadata) is tempting but probably gives the worst of both: still have the DB dependency, still have to maintain sidecar files. Rejected up front.

## Consequences

### Filesystem-tree layout (Option 2), in concrete terms

```
$config/<profile>/state/
├── cursor                                  # delta_cursor + pending_cursor as 2-line file
├── last_full_scan                          # single timestamp file
├── pin_rules                               # TOML, edited by hand or via CLI
├── Pictures/
│   └── 2009-11-15 - clelia/
│       ├── clelia-3-3016.jpg.meta          # JSON: {id,hash,size,mtime,isHydrated,lastSynced,…}
│       └── …
├── Videos/                                 # excluded, so empty / absent
├── .idx/
│   ├── by-remote-id/
│   │   └── ab/                             # git-style hashed prefix
│   │       └── abcdef012345.ref            # one-line: remote path
│   └── by-lc-path/
│       └── pi/
│           └── pictures/2009-11-15 - clelia/clelia-3-3016.jpg.ref
├── .trash/<ts>-<uuid>.meta                 # tombstones with retention_until
├── .conflicts/<ts>-<path-slug>.meta        # append-only log
└── .journal/
    ├── active -> active-20260419-074512    # pending commit dir, atomically renamed on commit
    └── active-20260419-074512/<path>.meta
```

Reverse indexes rebuilt lazily on daemon startup if .idx/ is absent (equivalent to `git fsck`).

### Perf — honest numbers

Measured against UD-712 workload (130,934 OneDrive items, NTFS on a Samsung NVMe, Win 11):

| Operation | SQLite today | FS tree (projected) | Delta |
|---|---|---|---|
| First-sync insert of 130 k entries | ~4 s (WAL batch, one fsync at end) | ~2–4 min (1 ms per create, no per-entry fsync, single fsync at end) | **30–60× slower** |
| Incremental delta apply (100 entries) | ~50 ms | ~100–200 ms | 2–4× slower, invisible |
| `getEntry(path)` cold | ~0.5 ms | ~0.3–1 ms (file stat + read) | comparable |
| `getAllEntries()` for full-sync delete detection | ~300 ms (seq scan) | ~15 s (tree walk of 130 k files) | **50× slower** |
| `getEntryByRemoteId` | microseconds (indexed) | ~0.5 ms (stat sidecar) | comparable |
| `renamePrefix(a, b)` on a 5 k subtree | ~200 ms (UPDATE with LIKE) | ~5 s (fs directory rename is one syscall, but reverse-index rebuild for all children) | **25× slower** |
| On-disk size (130 k entries) | 77 MB | ~280–500 MB (NTFS MFT + cluster overhead) | 3.6–6.5× larger |

The first-sync slowdown (2–4 min) sits on top of an 11-min Graph enumeration that already dominates wall-clock time → total first-sync becomes ~13–15 min instead of ~11–15 min. 1.2–1.4× degradation. **Tolerable.**

`getAllEntries()` at 15 s would be noticed by users running `unidrive log` or `status --all`. Mitigation: daemon keeps a hot in-memory index after first walk; CLI one-shots walk the tree (15 s cold start acceptable for a rare query).

### Positive

- **Inspectability.** `cat .unidrive-state/Pictures/foo/bar.jpg.meta` works. `find . -name '*.meta' -exec jq -r 'select(.isHydrated==false) | .path' {} +` answers "what's not hydrated" from the shell. State becomes diffable, git-trackable, grep-able without sqlite3 tooling.
- **Corruption blast radius.** One damaged `.meta` file = one entry lost (reconciler re-derives on next delta). Today: one damaged `state.db` = entire profile wiped.
- **Structural symmetry with remote.** State tree is the remote tree. `unidrive relocate` between providers becomes "tar | untar" + id-rewrite; today it's a DB-side re-enumeration + insert.
- **No schema migrations.** Adding a field = adding a JSON key. Today: `ALTER TABLE` + migration-number dance + backward-read compat logic.
- **Simpler backup.** `tar .unidrive-state/` vs `sqlite3 .backup` or `VACUUM INTO`.
- **Natural selective-sync restore.** `rm -rf .unidrive-state/Pictures/old-album` then `unidrive sync` re-enumerates just that subtree.
- **No binary DB file in git diffs** for profiles committed to version control (ops/QA workflows).

### Negative / trade-offs

- **Slower first-sync** (above table — +2–4 min on the 130 k workload).
- **Slower bulk enumerations** (`getAllEntries`, `renamePrefix` on large subtrees). Mitigated by in-memory index in the daemon; unmitigated in one-shot CLI.
- **3–6× disk footprint** for state, driven by filesystem per-file overhead, not payload.
- **Cross-entry atomicity** is harder. SQLite gives us a free transaction boundary covering Pass 1; FS requires the `.journal/active-<ts>/` staging + atomic rename-over pattern (maildir / git staging). Works, but is ~300 LOC of careful code vs 1 `beginBatch()`.
- **Per-platform filesystem-semantics drift.** NTFS: 8.3 aliases, case-insensitive-default, 260-char path limit (mitigated with `\\?\` prefix), locked files during watcher. ext4/btrfs: case-sensitive. APFS: case-insensitive-default, Unicode NFD. Each adds edge-case tests and platform-conditional code that SQLite abstracts away.
- **Index invalidation.** Reverse-id and lowercase-path indexes must stay in lockstep with primary tree. Either maintain on every write (doubles I/O), or rebuild at daemon start (cold-start cost). Git chose rebuild; we'd probably want both (write-through + periodic re-verify).
- **NTFS per-directory file count** degrades past ~300 k entries; need hashed subtree structure (as sketched) for drives > ~500 k items. Another layer of code + a path-encoding scheme that users inspecting the tree will find less obvious.
- **Security surface.** One DB file = one ACL check; 130 k sidecars = 130 k ACL checks. Current Vault-wrap-of-state.db for credential confidentiality doesn't trivially apply to a tree.
- **Implementation cost.** `StateStore` interface refactor + two implementations (SQLite legacy, FS new) + dual-mode tests + migration path for existing users. Estimate: 2 engineering weeks + 1 week test stabilisation.

### Neutral

- Mutation semantics of the public `StateStore` API don't change. Reconciler + SyncEngine call sites are unaffected modulo import.
- Observability / logging unchanged (UD-212 MDC profile tag works for both backends).
- Both backends need the same crash-recovery invariant: after a crash, a re-sync converges to a correct state.

## Alternatives considered

- **Status quo (SQLite only).** Pros: proven, fast, ACID free, well-understood. Cons: opaque, no inspection without sqlite3, binary-blob schema migrations, all-or-nothing corruption, structural mismatch with the tree-shaped domain.

- **Filesystem tree (as specified above).** Pros/cons per table above.

- **Hybrid (SQL for indexes + FS for payload).** Rejected — keeps both dependencies, doubles the places state lives, makes corruption recovery harder (which is authoritative when they disagree?).

- **Embedded tree KV (LMDB / RocksDB).** Rejected — adds a native-lib dependency (jna/jni binding per OS) without the inspectability win, and the same "flattened tree" structural complaint applies.

- **Plain JSONL append log + periodic compaction.** Rejected — getEntry becomes O(N) or we're back to indexes, and we'd reinvent SQLite poorly.

## Open questions

1. **What actually breaks in practice?** Before committing 3 weeks of work, write a prototype backend for Reconciler + first-sync path only and measure end-to-end wall-clock on the UD-712 fixture. If the 15 s `getAllEntries` is fine and the 2 min first-sync doesn't trigger user complaints, the ADR can accept.
2. **Migration strategy.** One-shot dump at daemon start (old state.db → new tree), or run-both-in-parallel with divergence detection for a release cycle? (Answer probably depends on how many users already have state.db snapshots they care about.)
3. **Does this simplify or complicate the future CfApi (UD-401/402/403) integration?** CfApi stores per-file reparse-point metadata; that could conceivably move into the sidecar `.meta` files or stay OS-owned. Worth looking at before committing.
4. **Does this simplify or complicate `unidrive relocate`?** The pitch above says yes; the actual CloudRelocator code re-enumerates both ends today, so the simplification is probably real but not as dramatic as the "tar | untar" slogan suggests.

## Related

- Backlog: UD-222 (sparse-placeholder saga surfaced how much state.db is doing), UD-713 (first-sync ETA — any backend-change perf delta shows up here), UD-223 (`?token=latest` fast-bootstrap — same blast-radius on state.db if a user regrets the choice).
- Implementation ticket: to be filed in the UD-8xx range after ADR accepted.
- Supersedes / superseded by: —

## Decision owner

Gernot (project maintainer). Decision blocked on: prototype + measurement per open question 1.
