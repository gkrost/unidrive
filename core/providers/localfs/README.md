# localfs provider

A no-auth `CloudProvider` over one local directory (the profile's `root_path`), for offline development and tests. It needs no network and no credentials, and `authenticate()` always succeeds, so a daemon on a `localfs` profile passes the auth-before-bind gate and binds its IPC socket without a cloud round-trip. It is not a primary-account provider.

The "vendor" here is the host filesystem seen through JDK NIO, so the facts below are filesystem rules, not API limits.

## Behaviour

- **Paths.** Remote paths are POSIX-style with a leading slash and `/` is the root. A path that resolves outside the root is rejected, lexically and through symlinks (the real path of the deepest existing ancestor must stay inside the real root). Enumeration skips entries whose real path leaves the root, and delete removes a symlink as the link, never descending into it.
- **Identity.** An item's id is its remote path, so a rename changes its identity.
- **Delta.** Every `delta` call is a full walk of the root (`deltaIsFullListing`), reported `complete = true`; the cursor is a wall-clock stamp and is ignored. Deletions therefore arrive only by absence, which the engine's absence sweep handles.
- **Capabilities.** `Delta`, `QuotaExact`, `VerifyItem`. There is no content hash (`hashAlgorithm()` is null, so the reconciler falls back to size), and `existingRemoteId` and `ifMatchETag` are ignored: a filesystem copy has no conditional write. Quota is the file store of the root (or of its nearest existing ancestor).

## Host filesystem facts

- Upload and download copy with `REPLACE_EXISTING` and `COPY_ATTRIBUTES` (the source's modification time is kept on every platform; the JDK would otherwise stamp the time of the copy on Linux) and then `force` the target to disk. Move uses `REPLACE_EXISTING` only, no `ATOMIC_MOVE`: a move that would have to relocate the entries of a non-empty directory (across file stores) fails with an `IOException`. ([`Files`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html))
- On Windows a file that another process holds open without sharing cannot be opened: the error is `ERROR_SHARING_VIOLATION` (32), "The process cannot access the file because it is being used by another process" ([system error codes](https://learn.microsoft.com/en-us/windows/win32/debug/system-error-codes--0-499-)). The provider does not retry host I/O errors, so such a transfer fails with that message.
- Case rules are the host's: on a case-insensitive volume `Foo` and `foo` are one item. The provider does not guard this; the engine's reconciler refuses case collisions.
