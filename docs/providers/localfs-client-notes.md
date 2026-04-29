# localfs — client notes

Research input for UD-230 (feeding UD-228 cross-provider robustness audit).

## Vendor recommendations

The "vendor" here is the host operating system's filesystem. The relevant
specifications are POSIX (for Linux/macOS), Win32 for Windows, and JDK
NIO's semantics layered on top.

- **POSIX I/O retry**: transient I/O failures (`EINTR`, `EAGAIN`,
  `EIO` on NFS) are the only documented retryable cases. JDK
  `Files.copy` does not retry — the caller must. POSIX `rename(2)` is
  atomic when source and destination are on the same filesystem;
  cross-filesystem `rename` returns `EXDEV` and the caller must fall
  back to copy + unlink.
- **Windows specifics**:
  - `ERROR_SHARING_VIOLATION (32)` — "The process cannot access the
    file because it is being used by another process." Commonly caused
    by antivirus (Defender, third-party), OneDrive Known Folder Move
    (KFM) handlers, Windows Search indexing, or another editor. The
    documented mitigation is a retry loop: Microsoft's own ROBOCOPY
    ships with `/R:n /W:n` retry-count and wait-seconds flags as the
    authoritative pattern.
    ([MS Learn — sharing violation on EXE copy](https://learn.microsoft.com/en-us/troubleshoot/windows-server/performance/copying-exe-files-sharing-violation-error-folder-in-use))
  - `ERROR_FILE_IN_USE (2404)` and `ERROR_ACCESS_DENIED (5)` — same
    retry-with-backoff treatment recommended.
  - `MOVEFILE_REPLACE_EXISTING` is not atomic across volumes.
  - OneDrive KFM can rehome `%USERPROFILE%\Documents`, `Desktop`,
    `Pictures` under `%OneDrive%\` silently; paths captured at config
    time may move. File IDs drift when OneDrive replaces a file with
    a placeholder.
  - Long-path (> 260 chars) requires `\\?\` prefix or the
    `LongPathsEnabled` registry setting; JDK NIO handles some but not
    all cases.
  - `FILE_SHARE_DELETE` must be set on open for a subsequent `MoveFile`
    while handles are open; JDK `Files.copy` doesn't expose this.
- **Linux / ext4 / btrfs / xfs**: `fsync` after rename is the durable
  pattern. `Files.copy` without `COPY_ATTRIBUTES` doesn't preserve
  mtime; without `StandardCopyOption.REPLACE_EXISTING` it fails with
  `FileAlreadyExistsException`.
- **macOS / APFS**: case-insensitive-but-preserving default; syncs
  between Linux (case-sensitive) and macOS can silently merge
  `Foo.txt` and `foo.txt`.
- **Symlinks and hardlinks**: `Files.walk` follows symlinks by default
  and has no loop detection.
- **Antivirus interference**: Defender's real-time protection holds an
  exclusive handle on new files for tens-of-ms to seconds after write;
  immediate read/rename can fail with `ERROR_SHARING_VIOLATION`.
  Retry with 100 ms → 1 s backoff for up to 5–10 attempts is standard
  industry practice.
  ([windowsreport — Excel sharing violation](https://windowsreport.com/excel-sharing-violation-error/))

## What unidrive does today

`core/providers/localfs/src/main/kotlin/org/krost/unidrive/localfs/LocalFsProvider.kt`

- **Path containment (CWE-22)**: `safePath` (`:72-79`) resolves against
  `rootPath`, normalises, and rejects paths that escape. Good.
- **`download` / `upload`** (`:83-94`) = `Files.copy` with
  `REPLACE_EXISTING`. No retry. No `fsync`. No `COPY_ATTRIBUTES`.
- **`move` = `Files.move` with REPLACE_EXISTING** (`:111-117`). No
  `ATOMIC_MOVE` option — cross-filesystem moves degrade silently to
  copy+delete; same-filesystem moves are atomic on POSIX by default.
- **`delete` is recursive** (`:96-103`) via `Files.walk(...).reverseOrder`.
  No symlink-loop protection.
- **`listChildren` / `walkRoot`** use `Files.list` / `Files.walk` —
  symlinks followed, no cycle detection.
- **Delta snapshot** uses size + mtimeMillis (`:158-170`) — doesn't
  notice content changes that preserve mtime (rare but real with
  `touch -r`). Known limitation.
- **Quota** uses `Files.getFileStore(rootPath).usableSpace` (`:187-192`)
  — correct.
- **No Windows-specific retry loops, no antivirus / sharing-violation
  tolerance.**

## Gaps → UD-228

- [ ] **No retry on `ERROR_SHARING_VIOLATION` / `AccessDeniedException`
      on Windows.** Defender / OneDrive / another editor holding a
      file for 200 ms kills the sync. Standard industry pattern:
      retry 5–10× with 100 ms → 1 s backoff.
- [ ] **No `fsync` after upload.** A crash between `Files.copy` return
      and kernel flush can lose the tail of the file (filesystem cache
      not yet committed).
- [ ] **No `ATOMIC_MOVE` attempt with fallback.** `move` across
      filesystems degrades silently; for unidrive's main use case
      (same-tree sync) this is fine, but config-time the user can
      configure two different mounts.
- [ ] **Symlink loop = infinite walk.** `Files.walk` default follows
      symlinks and has no cycle detection. `listAll` will spin.
- [ ] **OneDrive KFM path drift not detected.** Path configured as
      `C:\Users\x\Documents` may silently become
      `C:\Users\x\OneDrive\Documents` between runs.
- [ ] **Long-path (> 260) on Windows not handled explicitly.** Depends
      on JDK NIO's runtime behaviour and whether `LongPathsEnabled` is
      set on the system.
- [ ] **Case-sensitivity mismatch between platforms not guarded.**
      Sync from Linux to macOS/APFS can silently merge names differing
      only in case.
- [ ] **`delete` on a very deep tree is O(depth) recursion via
      `Files.walk().sorted(reverseOrder)`** — loads all paths into
      memory before deleting.
- [ ] **No mtime preservation on `download`/`upload`.** Next delta will
      see the copy as "new" and re-sync.

## Priority for UD-228

**High on Windows, low on Linux.** The Windows sharing-violation retry
gap is the single most likely cause of "mysterious sync failure on my
PC but not my laptop" reports — and it is a two-line fix (retry wrapper
around `Files.copy` / `Files.move` for `AccessDeniedException` +
`FileSystemException` containing `sharing violation`). Symlink-loop
protection matters on all platforms. Everything else is lower-priority
cleanup.
