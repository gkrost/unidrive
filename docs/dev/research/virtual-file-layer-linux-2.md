# Virtual-file layer on Linux — research brief (investigator #2)

Scope: how should the unidrive daemon present a 600 GiB / 200k-file cloud account
as a browsable folder under Dolphin / Nautilus without materialising every byte
on disk, given a Kotlin/JVM engine, an existing `state.db`-based hydration model
(`PlaceholderManager`, `GetCommand`, `FreeCommand`, `SweepCommand`), and a
planned Windows (Cloud Files API) + Android (SAF / DocumentsProvider) future.

## 1. TL;DR

- **FUSE is the right answer for Linux. Nothing else closes the UX gap.** Sparse
  files, xattr tagging, and shell-completion tricks all leave one of:
  no-discoverability, no-transparency, or no-hydrate-on-open. FUSE is the only
  Linux primitive that makes "the file is there until you open it" actually true
  to every program that uses `open(2)`.
- **Run FUSE out of a separate process, not inside the JVM.** The FUSE userspace
  daemon should be a small native (Rust or C) co-process talking gRPC/UDS to the
  Kotlin core. Reasons: kernel-blocking semantics, JVM startup latency on
  callback paths, GC pause sensitivity, `CAP_SYS_ADMIN` requirement for
  passthrough, and per-AGENTS.md the platform surface lives outside `core/app/`
  anyway. jnr-fuse / jfuse exist and work, but they paint the cross-platform
  story into a corner (jfuse needs JDK 25; jnr-fuse threading is questionable
  at 200k-file scale).
- **Adopt FUSE_PASSTHROUGH (kernel 6.9+) as the hydration completion path.**
  Once a file is fully downloaded, register it as a passthrough backing file —
  kernel handles `read` / `write` / `mmap` / `splice` directly, bypassing the
  daemon. This is the only credible answer to video scrubbing, image viewers,
  and `mmap()` workloads at the scales the user already has.
- **Keep `state.db` as the source of truth.** FUSE serves `getattr` and
  `readdir` from a metadata cache populated by `state.db`; `open(2)` triggers
  hydration via the existing `GetCommand` code path; hydrated files are
  swapped to passthrough. The placeholder layer adds a kernel-visible surface;
  it does not replace the model.
- **Cross-platform abstraction earns its keep.** The only durable shared
  contract is *metadata + open(path) → ByteStream + hydrate(path) → CompletionState*.
  Beyond that, Linux/Windows/Android diverge violently and any forced
  uniformity will leak.

## 2. Decision rubric

| Criterion | Weight | Why |
|---|---|---|
| Transparent to all apps (`ls`, Dolphin, mpv, GIMP) | High | The whole point. xattr / suffix-extension solutions fail this. |
| Scale to 200k files / single mount | High | Live profile is already 199,702. |
| Hydrate-on-open works under `mmap` | High | Image viewers (GIMP, image preview), video scrubbing. |
| JVM integration cost | Medium | Engine is Kotlin. Bridge cost is a feature spec. |
| Stability under network failures | High | Cloud-backed FS that wedges the desktop is a regression. |
| Maintenance burden (libfuse 2 vs 3, kernel versions) | Medium | Solo dev. Less code beats more. |
| Forward fit Windows CfApi + Android SAF | Medium | Future surfaces matter, but not at the cost of Linux today. |
| Recovery from desync (DB ↔ disk drift) | Medium | `state.db` already has the model; don't multiply truth-sources. |

## 3. Options matrix

| Option | Summary | JVM story | Scale ceiling | Kernel / lib floor | Prior art | Maint cost | Fatal flaws |
|---|---|---|---|---|---|---|---|
| **A. FUSE 3, native co-daemon (Rust/C), passthrough on hydrate** | Native daemon talks gRPC/UDS to Kotlin core. Placeholders served by daemon; hydrated files via `FUSE_PASSTHROUGH`. | Loose coupling, no JVM in FUSE callback path. | 200k+ trivially; metadata cache from `state.db`. | Linux ≥ 6.9 for passthrough; libfuse 3.16+ recommended. | rclone (Go), gcsfuse (Go), s3fs (C++). | Medium — one extra process + IPC schema. | Two-language repo (mitigated by separate platform-tier crate, AGENTS.md aligned). |
| **B. FUSE 3, in-JVM via jfuse (FFM)** | Cryptomator's FFM bindings, no native co-process. | Tight; same JVM. | Probably OK; threading not documented. | JDK 25 (jfuse 0.8). libfuse 3.x. | Cryptomator vault mount. | Medium — but the JVM hosts the FUSE callback loop. | JDK 25 floor; GC pauses become FS hiccups; passthrough requires CAP_SYS_ADMIN which is awkward inside the user JVM. |
| **C. FUSE 3, in-JVM via jnr-fuse (JNR)** | Mature-ish JNR bindings (last release Sept 2024). | Tight. | OK for low-fanout; not stress-tested at 200k. | JDK 11+. libfuse 2 primarily; libfuse 3 spotty. | Alluxio-Fuse, Cryptomator (legacy). | Medium-high — JNR-FFI tax. | Same JVM-in-callback issues as B; libfuse 3 / passthrough / io_uring story unclear. |
| **D. libprojfs (GitHub's projected-FS port of VFSForGit)** | Stackable Linux FS aimed at Git's virtual checkout, callback-driven hydration. | Bridge from JVM via gRPC like A. | Unknown at 200k files. | Linux 4.x+. | VFSForGit only. | High — upstream "not for production." | "Rapid development, may change significantly" — not a stable foundation. |
| **E. Sparse files + xattr "cloud-only" tag** | Punch holes; mark with `user.unidrive.placeholder=1`; trigger hydrate on open via `fanotify` / `inotify`. | Pure JVM. | Excellent at metadata. | Linux native xattr + fanotify. | None at scale. | Low. | Hydration is not transparent — `read(2)` on a sparse hole returns zeros, not a block until download. Image viewers see corrupt files. `fanotify` open intercept can't *delay* the syscall to fetch bytes first. |
| **F. Filename-suffix placeholders (`foo.mp4.unidrive`)** | Empty 0-byte stub at suffixed path; user renames or right-clicks to hydrate. | Pure JVM. Status quo +. | Excellent. | None. | Nextcloud Linux client (`.nextcloud` / `*_virtual`); OCFFS. | Very low. | Not transparent. Apps that open by path miss the file. Two namespaces (hydrated and stub) leak everywhere. |
| **G. Lazy-hydrate via shell completion / `fzf` picker, empty `sync_root`** | Don't mount at all. `unidrive get` + good completion. | Pure JVM. | Infinite. | None. | rclone CLI flows. | Lowest. | Loses Dolphin/Nautilus integration — *the* goal of the BACKLOG entry. Not a solution, an avoidance. |
| **H. virtio-fs** | Virtio-fs is a virtualization-only sibling of FUSE — host-to-guest. | N/A. | N/A. | N/A. | KVM/QEMU. | N/A. | Not applicable; the user is not running unidrive inside a VM for the sake of mounting. |
| **I. NFS loopback (rclone serve nfs)** | Run a userspace NFS server, mount loopback. | Pure JVM (Java NIO). | Reasonable. | Linux nfs-utils. | rclone. | Medium. | NFSv3 caching semantics + UID mapping + idmap pain. Worse stability than FUSE on disconnect. Excluded primarily because Dolphin/Nautilus integration is weaker. |

**Recommendation: A.** B/C are credible alternatives if the user prefers a
pure-JVM repo (B preferred over C only once JDK 25 is acceptable; otherwise
neither). E/F/G are deferred mitigations, not the BACKLOG closure.

## 4. FUSE deep-dive

**Protocol versioning.** libfuse 3.x is the target. libfuse 2 is legacy and
also distro-removed in newer Ubuntu (kdrive's `libfuse2` saga is the cautionary
tale). Pin a libfuse 3 floor (3.16+ for stable passthrough; 3.17 for cleaner
io_uring story).

**Kernel features that matter:**

- **FUSE_PASSTHROUGH** (kernel ≥ 6.9, mainlined March 2024). After hydration the
  daemon issues `FUSE_DEV_IOC_BACKING_OPEN`, gets a `backing_id`, and sets
  `FOPEN_PASSTHROUGH` on subsequent opens. Kernel then services
  `read`/`write`/`mmap`/`splice` directly against the backing file. This is
  what makes mmap-heavy workloads (video, large image preview) tractable.
  Requires `CAP_SYS_ADMIN` for the daemon — fine for a systemd `--user` service
  with `AmbientCapabilities=CAP_SYS_ADMIN`, awkward inside a user JVM.
- **FUSE-over-io_uring** (kernel ≥ 6.14). Transparent to the userspace daemon
  (just compile against a libfuse build with `uring` support; enable via
  `/sys/module/fuse/parameters/enable_uring`). Big throughput win once we get
  to multi-stream hydration. Adopt when distros catch up; not a blocker.
- **readdirplus.** Negotiate `FUSE_DO_READDIRPLUS` so directory listings return
  attributes in one round-trip. Critical at 200k files. Without it Dolphin's
  initial `ls` of a 50k-file folder will cost ~50k FUSE getattr round-trips.
- **`FUSE_CAP_DIRECT_IO_ALLOW_MMAP`** for hydration-in-progress files (avoids
  the "no MAP_SHARED on direct-IO" trap).

**Threading model recommendation.** Use `fuse_loop_mt` with worker count tuned
to ~4×CPU. Make FUSE callbacks `O(1)` against a `state.db`-derived in-memory
metadata cache; never block a FUSE thread on a network call. A request that
needs the network is registered as pending and the calling syscall is blocked
via standard FUSE semantics (the kernel parks the process on the FUSE
request); the daemon completes asynchronously and replies. This is how
`gcsfuse` and `rclone mount` both work.

**mmap caveats.**

- Pre-hydration mmap is essentially impossible to serve well from userspace.
  Either hydrate-on-mmap (blocking the syscall until full download — bad for
  GB-class files) or refuse `mmap` until hydrated. Recommendation: refuse with
  `EINVAL` on placeholders, return a clear log line, expose the policy in
  `unidrive status`. Once hydrated → passthrough → mmap is native.
- `mmap`+truncate races during hydration are a known FUSE footgun. The
  passthrough flip *after* the file is complete sidesteps this.

**Failure modes that bit other people:** uninterruptible-sleep hangs when the
network drops mid-read (sshfs / rclone). The `/sys/fs/fuse/connections/*/abort`
escape hatch must be wired up; surfaced via `unidrive umount --force`. Treat
the FUSE daemon's liveness as part of `unidrive status`.

## 5. Prior art notes

- **rclone mount** (Go, FUSE 3). VFS cache modes `off / minimal / writes /
  full`. "full" uses sparse files in `--cache-dir` to mirror partial
  downloads — close to what we want for the hydrated case before passthrough
  was an option. Lessons: cache-size overruns are a constant support burden;
  attr-timeout pitfalls; "two rclone instances on overlapping remotes corrupts
  data." Stability concerns when used as a desktop mount: documented as not
  100% reliable for critical operations. **Takeaway:** the architecture works;
  pin caveats: single mount per provider, cache-size cap honored, attr-timeout
  > 0.
- **gcsfuse** (Go, FUSE 3). Designed for very large flat buckets, big focus on
  metadata caching (`--metadata-cache-ttl-secs=-1`, list caching). Recommends
  `ls -R` pre-warm for large dirs. **Takeaway:** at 200k+ files, the design
  centre-of-gravity shifts from data to metadata; `state.db`-backed metadata
  cache is exactly the right shape.
- **Nextcloud desktop client** on Linux. Uses `.nextcloud` / `*_virtual`
  filename suffix as the "placeholder" — option F above. The FUSE alternative
  has been an open issue since 2021 and was repeatedly deferred pending KDE/
  GNOME RFCs. Flatpak distribution breaks the FUSE path entirely. AppImage
  works only with libfuse2. **Takeaway:** suffix-based placeholders are a
  semantic compromise; the project that did them most prominently has spent
  4+ years trying to escape them. Don't repeat their detour.
- **OCFFS** (ownCloud FUSE filesystem). External project, not officially
  shipped. Suffix-rename triggers hydrate. Confirms F is doable but cosmetic.
- **Insync / Google Drive native / Dropbox** on Linux. None expose a virtual
  file layer; all sync-then-store. Their Linux clients are explicit
  second-class citizens vs Windows/macOS. **Takeaway:** the market gap unidrive
  is filling is real precisely because no one shipped this on Linux at
  consumer scale.
- **kDrive** (Infomaniak). Lite Sync (their virtual files) is documented as
  *not available on Linux*. Confirms degree of difficulty.
- **Cryptomator** (jfuse / jnr-fuse). Production JVM FUSE consumer. Their
  workload is encrypted-vault-in-cloud-backed-folder — much smaller scale per
  mount than 200k files, but the JVM-bindings code path is real and shipping.
  Their migration *from* jnr-fuse *to* jfuse (FFM/JEP-454) signals where the
  JVM-side ecosystem is heading.
- **libprojfs** (GitHub). Microsoft's projected-FS spirit on Linux for
  VFSForGit. Last meaningful activity stale; "not for production." Skip.
- **Alluxio-Fuse**. JVM-based FUSE for distributed-storage cache. Different
  domain but proves JVM can drive FUSE at scale.

## 6. Cross-platform fit

The temptation is to define a `VirtualFileSurface` interface in `core/` that
Linux/Windows/Android all implement. Resist. The three platforms differ on
*who owns the inode/handle namespace*:

- **Linux/FUSE:** the userspace daemon answers every `getattr`. We own
  namespace and lifetime.
- **Windows/CfApi:** NTFS owns the inode; we get hydration callbacks. The
  placeholder lives in the OS, not in our process.
- **Android/SAF:** there is no inode at all; clients pull metadata via
  `Cursor`s and content URIs.

The durable shared contract is *narrower* than a "virtual filesystem":

```
core/ exposes:
  fun listChildren(path): List<EntryMeta>            // already exists via state.db
  fun openForRead(path): suspend (range) -> ByteFlow // hydrate primitive
  fun hydrate(path, listener): Job                   // existing GetCommand logic
  fun dehydrate(path)                                // existing FreeCommand
  val hydrationState: Flow<PathStateChange>          // for status overlay
```

Each platform tier wraps this with its native placeholder API. Linux's FUSE
co-daemon, Windows's CfApi callbacks, Android's `DocumentsProvider.openDocument`
all bottom out in `openForRead`. **Do not** abstract "placeholder" as a
core-level concept — the existing `isHydrated` boolean in `StateDatabase` is
the right level of abstraction; the surfaces translate it. Lock down the
metadata schema (a `EntryMeta` POJO + Flow of changes) and let surfaces
diverge.

Concrete implication for the FUSE work: build it as a separate Cargo crate (or
Gradle module under a future `platforms/linux-fuse/`), one binary, IPC over
UDS to the daemon's existing IPC server (`IpcServer.kt`). No code in
`core/app/` needs to change beyond exposing `openForRead` and a hydration
event Flow on the existing IPC.

## 7. Open questions

1. **Native co-daemon language: Rust or C?** Rust gives us
   `fuser`/`fuse-backend-rs` and a memory-safe story; C gives us libfuse
   examples verbatim and minimal toolchain. Recommend Rust unless the user
   wants to keep the dist toolchain minimal.
2. **Single mount or one-per-profile?** The repo supports multiple cloud
   profiles. One FUSE mount per profile is simplest; a unified mount with
   provider-prefixed top-level dirs is friendlier but couples profiles.
3. **CAP_SYS_ADMIN for passthrough.** Acceptable as
   `AmbientCapabilities=CAP_SYS_ADMIN` on a systemd-user service? Or do we run
   the FUSE co-daemon as a system service with a UDS auth handshake? Both
   work; the first is simpler.
4. **Read-ahead policy.** Should `open(path)` for a placeholder always trigger
   a full download (current `unidrive get` semantics) or stream
   range-by-range? Sequential video playback wants range; image viewers want
   full-file. Probably: start with full hydrate; add ranged hydrate when a
   benchmark says it's needed.
5. **Coexistence with current `PlaceholderManager` sparse-stub behaviour.**
   When FUSE is mounted, the on-disk `sync_root` is the FUSE mount itself —
   not the underlying storage. The "hydrated" files live behind the mount in
   a cache dir. The `isSparse` stub-detection logic in `LocalScanner` is
   moot under FUSE. Need a config flag (`mount_mode = fuse | direct`) that
   disables the sparse-stub code path when FUSE is active.
6. **Conflict with the tracking-set engine.** Tracking-set predicate over
   deletion assumes the local FS is authoritative for "has the user seen this
   path?" Under FUSE, *every* path is visible by default. Re-read
   `multi-platform.md` consequence #2 — the tracking-set engine likely needs
   a "visible-via-placeholder ≠ crossed-sync-boundary" distinction.
7. **Smoke test target.** What's the FUSE-surface smoke set look like? Per
   AGENTS.md each surface gets its own smoke set; defer until working code.

## 8. References

- libfuse: <https://github.com/libfuse/libfuse> — reference impl, `passthrough_hp.cc`
- FUSE passthrough kernel docs: <https://docs.kernel.org/6.16/filesystems/fuse-passthrough.html>
- FUSE-over-io_uring kernel docs: <https://docs.kernel.org/next/filesystems/fuse-io-uring.html>
- FUSE-over-io_uring writeup (kernel 6.14): <https://luis.camandro.org/2025-06-14-fuse-over-io_uring.html>
- jfuse (Cryptomator, FFM/JEP-454, JDK 25): <https://github.com/cryptomator/jfuse> — latest stable 0.7.3, 0.8.0-beta1
- jnr-fuse: <https://github.com/SerCeMan/jnr-fuse> — 0.5.8 (Sept 2024); known users Alluxio, Cryptomator (legacy), mux2fs, Google Cloud Healthcare DICOM
- rclone mount: <https://rclone.org/commands/rclone_mount/> — VFS cache modes, sparse file behaviour, stability caveats
- gcsfuse perf tuning: <https://cloud.google.com/storage/docs/gcsfuse-performance>
- libfuse filesystems wiki: <https://github.com/libfuse/libfuse/wiki/filesystems>
- libprojfs: <https://github.com/github/libprojfs> — "not for production"
- Nextcloud VFS-on-Linux issue: <https://github.com/nextcloud/desktop/issues/3668>, blog: <https://nextcloud.com/blog/nextcloud-desktop-client-3-2-with-status-feature-and-virtual-files-available-now/>
- OCFFS: <https://github.com/jnweiger/OCFFS>
- kDrive Lite Sync (no Linux support): <https://www.infomaniak.com/en/support/faq/2456/access-kdrive-files-locally-and-online>
- Windows CfApi sync-engine guide: <https://learn.microsoft.com/en-us/windows/win32/cfApi/build-a-cloud-file-sync-engine>
- Android DocumentsProvider: <https://developer.android.com/guide/topics/providers/create-document-provider>
- FUSE recovery via `/sys/fs/fuse/connections/*/abort`: <https://docs.kernel.org/6.1/filesystems/fuse.html>
- unidrive primary anchors read: `core/app/sync/.../PlaceholderManager.kt`, `core/app/cli/GetCommand.kt`, `core/app/cli/SweepCommand.kt`, `core/app/cli/FreeCommand.kt`, `docs/adr/multi-platform.md`
