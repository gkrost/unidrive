# Virtual-file layer on Linux — research brief (investigator 1)

## 1. TL;DR

- **FUSE is the right answer**, but the unidrive JVM process must NOT be the FUSE server. Run a small native co-daemon (Rust preferred, Go acceptable) that speaks `/dev/fuse` and IPCs the JVM engine over a local socket. The JVM stays the source of truth for `state.db`; the co-daemon is a thin protocol translator.
- **Kernel floor: 6.9 for `FUSE_PASSTHROUGH`, 6.14 for FUSE-over-io_uring.** libfuse 3.16+. Both available on Debian Trixie / Ubuntu 24.10+. The no-passthrough fallback path is where every previous Linux cloud-sync project bled out (mmap, video scrubbing, large-read perf).
- **Placeholders live in the FUSE layer as synthetic inodes**, not on-disk files. Size comes from `state.db` `remoteSize`. The current `PlaceholderManager.dehydrate` sparse-file approach becomes a cache-layer concern (post-hydration eviction), not the primary mechanism. The "199,702 0-byte files on disk" problem disappears.
- **Hydrated files become real files on a backing cache FS**, exposed to apps via `FUSE_PASSTHROUGH` ioctl so reads/writes/mmap cost ~zero after first lookup. Same architectural shape as Windows CloudFiles (reparse points + sparse files + filter driver), restated for Linux.
- **Reject every non-FUSE alternative.** libprojfs archived 2024-07. xattr+LD_PRELOAD doesn't intercept the kernel's `open()`. Sparse-file stubs silently read NUL bytes on a non-cooperating reader (already burned twice: UD-712 on NTFS, UD-209a on Linux JDK 21 jbrsdk). GVFS is URI-only — Baloo/Tracker/`find` don't see GVFS mounts.

## 2. Decision rubric

1. **Correctness at 200k+ files.** readdir+getattr storms from Dolphin/Nautilus/Baloo are the load profile that kills naive designs. `readdirplus` + long metadata TTL non-negotiable.
2. **Hydrate-on-access transparency.** Double-click a placeholder → image viewer sees bytes. No custom protocol. Only FUSE delivers this on Linux.
3. **JVM integration risk.** Engine is large Kotlin/coroutines. We are not rewriting in Rust. Integration boundary must be IPC, not in-process JNI/FFM with blocking `read()` on `/dev/fuse`.
4. **Cross-platform reuse.** Windows CloudFiles and Android DocumentsProvider need the same engine primitives (lookup, hydrate-by-path, change-notify). The seam must not be FUSE-shaped.
5. **Maintenance cost.** One developer, ADD. No half-maintained binding can be load-bearing.
6. **Failure-mode containment.** A crash in the placeholder layer must not corrupt `state.db` or wedge the mount. IPC boundary buys a kill-and-restart story the in-JVM bindings can't.

## 3. Options matrix

| Option | JVM integration | Scale ceiling | Kernel/libfuse floor | Prior art | Maint. cost | Fatal flaws |
|---|---|---|---|---|---|---|
| **Rust co-daemon + libfuse3 + IPC** | clean (IPC) | 1M+ w/ readdirplus | 6.9 / libfuse 3.16 | gocryptfs (Go); fuser crate users | medium | bus factor on co-daemon author |
| **Go co-daemon + cgofuse + IPC** | clean (IPC) | same | same | rclone mount | medium | GC pause under load (unproven at scale); cgo cross-compile pain |
| **jnr-fuse in-process** | same process | ~50k before readdir tax bites | libfuse 2/3 legacy | Cryptomator (vault scale), Alluxio | low to write, high to debug | last release 0.5.8 Sep-2024; partial FUSE 3; no PASSTHROUGH; threading "all yours" |
| **jfuse (Cryptomator/FFM)** | same process | similar | JDK 25 + libfuse 3.x | Cryptomator | low to write, medium to debug | still beta (0.8.0-beta1 Jan-2026); JDK 25 floor aggressive; no PASSTHROUGH |
| **libprojfs** | C bridge | unproven | n/a | VFSForGit Linux | n/a | **archived 2024-07** |
| **xattr stub + LD_PRELOAD shim** | none | n/a | any | nothing serious | n/a | LD_PRELOAD only catches dynamic-libc clients; KIO/syscalls bypass; not a real placeholder |
| **Sparse-file stubs UI-visible (status quo grown)** | already wired | ~10k before metadata thrash | any | self | low (sunk) | `cat` on placeholder reads NUL = silent corruption; already burned twice |
| **No FS — CLI + completion + picker** | trivial | infinite | none | self today | low | Dolphin/Nautilus users expect a folder; non-starter for the Linux UI surface multi-platform.md commits to |

## 4. FUSE deep-dive

**Protocol mechanics that matter:**

- **readdirplus is mandatory.** Naive readdir returns names; the kernel then issues N `getattr` round-trips. At 199k files this is fatal. libfuse 3 + kernel adaptive readdirplus (LWN 532705): a 20k-file crawl drops 16s → 4s purely from this. At our scale it's the difference between Dolphin opening the folder and Dolphin appearing to hang.
- **Inode/attr cache TTL.** `entry_timeout` / `attr_timeout` in `fuse_config`. unidrive owns metadata (state.db authoritative). Set to 30–300s; invalidate explicitly via `fuse_lowlevel_notify_inval_entry` when sync sees remote changes. Default 1s burns CPU re-stating placeholders during desktop indexing.
- **`FUSE_PASSTHROUGH` (6.9+).** After hydration, the daemon issues `FUSE_DEV_IOC_BACKING_OPEN` and hands the kernel a backing FD. From then on `read`/`write`/`splice`/`mmap` go straight to the backing FS at native speed — no userspace round-trip. This is the feature that makes mmap-heavy clients (image viewers, video scrubbing) viable. Kernel doc notes `CAP_SYS_ADMIN` required for the daemon — see open question on systemd shape.
- **FUSE-over-io_uring (6.14+, merged 2025-03-24).** Transparent to libfuse-based daemons. 1.7–3.6× read speedup depending on workload. Free win.
- **mmap caveats.** Without PASSTHROUGH, mmap on FUSE has fiddly writeback semantics (gcsfuse spells out the keep-FD/msync/munmap/close dance). With PASSTHROUGH mmap goes to the backing FS — gone.
- **Threading.** Multi-threaded libfuse loop, one queue per CPU (FUSE_DEV_IOC_CLONE). FUSE workers do non-blocking IPC to the JVM; the request is suspended on the libfuse side and resumed on any thread when the JVM answers. NEVER block a FUSE worker on a network download — libfuse explicitly supports suspend/resume. This is how rclone does it.
- **fsync/fdatasync.** No-op on placeholders. Under PASSTHROUGH kernel flushes the backing FD. No special handling.
- **What breaks.** `posix_fadvise(WILLNEED)` from a placeholder hydrates via the read path — fine but slow. `fallocate(PUNCH_HOLE)` on a placeholder: refuse (`EOPNOTSUPP`). `flock`/`lockf` on placeholder: refuse until hydrated.

**Why a co-daemon, not in-JVM:**

1. **Crash isolation.** FUSE-server crash leaves the mount `Transport endpoint is not connected`. If the JVM is the FUSE server, every OOM/JIT-pause/coroutine-deadlock corrupts the user's view. Co-daemon: restart it, remount, JVM never blinks.
2. **Lifecycle.** Mount must outlive `systemctl --user restart unidrive.service` (Dolphin tabs still open). Independent systemd unit is the only clean answer.
3. **`CAP_SYS_ADMIN` for PASSTHROUGH.** Don't want it on the JVM; tiny audited co-daemon can have it.
4. **JVM bindings are blocking.** jnr-fuse / jfuse expose `/dev/fuse` as blocking syscalls. Dedicating a thread per FUSE worker works for Cryptomator (one vault, low concurrency) but not for Baloo+Nautilus+`find` on a 199k-file mount.
5. **Bus factor.** jnr-fuse: one maintainer, last release 0.5.8 Sep-2024, FUSE 3 partial. jfuse: one maintainer, still beta. Rust + libfuse3 (`fuser` crate) has dozens of production tenants.

## 5. Prior art notes

- **rclone mount.** The architectural model. Native Go, cgofuse, sparse files in VFS cache, `--vfs-cache-mode full` for serious workloads. Forum scars: eviction races above 100k files; sparse requirement bites users on FAT/exFAT cache dirs. **Takeaway:** cache-dir FS must be ext4/btrfs/xfs; document it.
- **Cryptomator / gocryptfs.** Both prove libfuse-based FS at 100k-file scale in real use. Cryptomator's switch jnr-fuse → jfuse was painful enough that they call out "API design and performance we wanted to beat." **Takeaway:** Java FUSE bindings have a half-life; Cryptomator was big enough to write their own. We aren't.
- **Nextcloud desktop.** Linux VFS is "rudimentary" — `.nextcloud` suffix rename trick, no FUSE. 3.14.0 crashes when VFS enabled. Issue #3668 open 5+ years. **Takeaway:** the rename approach is what you ship when you can't fund FUSE. We can do better.
- **kDrive (Infomaniak).** Lite Sync (on-demand) is Windows/macOS only on the Linux client. Explicit gap, 2025. **Takeaway:** commercial vendor with a Linux client has not solved this.
- **Dropbox.** Smart Sync Windows/macOS only; Linux client is regular sync. **Takeaway:** ditto.
- **Insync.** Selective sync, no on-demand placeholders on Linux. **Takeaway:** ditto.
- **iCloud-linux community project.** FUSE + Python + local mirror. Sub-toy scale. **Takeaway:** confirms FUSE shape; Python wrong language.
- **VFSForGit / libprojfs.** Microsoft's ProjFS-on-Linux. Archived 2024-07. **Takeaway:** kernel-side projection isn't coming as a separate facility; Linux took the FUSE+passthrough path instead.
- **GVFS.** GNOME's URI-based VFS (`onedrive://` etc.). Not a real mount — Baloo, Tracker, `find`, command-line tools don't see GVFS. **Takeaway:** complement, not substitute.

## 6. Cross-platform fit

The `core/` abstraction that earns its keep is **not** "VirtualFilesystem" — it's the **hydration SPI** that FUSE / CloudFiles / DocumentsProvider all call into.

Shape (sketch only):

```
core/app/hydration/
  HydrationOrchestrator   # ensure(remote_path) -> Path, idempotent, dedup concurrent
  PlaceholderCatalog      # query state.db for "what should the tree look like"
  ChangeNotifier          # emits invalidate(remote_path) when sync sees remote change
```

Each surface (Linux FUSE co-daemon, Windows CloudFiles handler, Android DocumentsProvider):
1. Receives lookup/open from its platform.
2. Calls `PlaceholderCatalog.lookup(path)` for tree shape.
3. Calls `HydrationOrchestrator.ensure(path)` on read intent.
4. Subscribes to `ChangeNotifier` to invalidate platform metadata cache.

What does NOT belong in `core/`: any FUSE type, any CfApi struct, any AndroidX class. SPI is pure Kotlin + `java.nio.file.Path`. This is consistent with `multi-platform.md` and `core-app-contract.md` — engine emits structured outcomes; surfaces render. The FUSE co-daemon lives outside this repo (or in a sibling `unidrive-linux-ui/`), consumes the engine via IPC.

The Linux choice does not box Windows or Android in: CloudFiles' `CfHydratePlaceholder` callback and Android's `openDocument` both fit the same SPI signature.

## 7. Open questions

- **`CAP_SYS_ADMIN` deployment.** PASSTHROUGH needs it. Options: (a) system systemd unit with `AmbientCapabilities`, breaking current user-systemd pattern; (b) setuid helper for the ioctl; (c) skip passthrough, accept userspace-round-trip reads (kills mmap, kills video scrubbing). User decides.
- **IPC protocol.** Current CLI-style request/response IPC is wrong shape for FUSE workloads (need streaming + notify). Does the engine grow an event surface, or does the co-daemon embed a read-only SQLite handle on `state.db`? Reader-of-state.db is simpler but couples co-daemon to schema.
- **Cache FS choice.** ext4/btrfs/xfs all support sparse + reflinks + FUSE backing FDs. btrfs reflinks would make hydrate-on-copy O(1) but adds a btrfs-required dep. Post-MVP.
- **Mount per profile?** unidrive has multiple profiles; each has its own `sync_root`. Suggests one mount per profile. Confirm.
- **Co-daemon language: Rust vs Go.** Rust = no GC, smaller binary, harder reviewer pool. Go = cgofuse exists (rclone uses it), GC pause unproven at our scale but rclone tolerates it. User's bias matters.
- **`unidrive get --recursive` interim mitigation.** BACKLOG mentions it. Single-file change to `GetCommand.kt` folder branch. Ship first? User decides priority.

## 8. References

- Kernel FUSE passthrough doc: https://docs.kernel.org/next/filesystems/fuse-passthrough.html
- Kernel FUSE-over-io_uring doc: https://docs.kernel.org/next/filesystems/fuse-io-uring.html
- Phoronix: FUSE+io_uring in Linux 6.14 (merge confirmed 2025-03-24): https://www.phoronix.com/news/Linux-6.14-FUSE
- Phoronix: FUSE Passthrough in 6.9: https://www.phoronix.com/news/FUSE-Passthrough-In-6.9-Next
- libfuse: https://github.com/libfuse/libfuse
- jnr-fuse 0.5.8 (Sep-2024): https://github.com/SerCeMan/jnr-fuse
- jfuse 0.7.3 (Apr-2025), 0.8.0-beta1 (Jan-2026): https://github.com/cryptomator/jfuse
- libprojfs (archived Jul-2024): https://github.com/github/libprojfs
- rclone mount docs (VFS cache modes, sparse): https://rclone.org/commands/rclone_mount/
- Nextcloud "VFS on Linux" issue #3668: https://github.com/nextcloud/desktop/issues/3668
- gcsfuse semantics (mmap, sparse): https://github.com/googlecloudplatform/gcsfuse/blob/master/docs/semantics.md
- Windows CloudFiles API portal: https://learn.microsoft.com/en-us/windows/win32/cfapi/cloud-files-api-portal
- USENIX FAST'17 "To FUSE or Not to FUSE": https://www.usenix.org/system/files/conference/fast17/fast17-vangoor.pdf
- LWN: Adaptive readdirplus support: https://lwn.net/Articles/532705/
- John Millikin: The FUSE Protocol (threading, FUSE_DEV_IOC_CLONE): https://john-millikin.com/the-fuse-protocol
- unidrive `PlaceholderManager.kt` (existing dehydrate/sparse): `/home/gernot/dev/git/unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/PlaceholderManager.kt`
- unidrive `multi-platform.md` ADR: `/home/gernot/dev/git/unidrive/docs/adr/multi-platform.md`
- unidrive `SyncEntry.kt` (isHydrated, remoteSize): `/home/gernot/dev/git/unidrive/core/app/sync/src/main/kotlin/org/krost/unidrive/sync/model/SyncEntry.kt`
