# Never hot-swap a jar on a running JVM (Windows specifically)

> Linux MVP per [ADR-0012](../../adr/0012-linux-mvp-protocol-removal.md) /
> [ADR-0013](../../adr/0013-ui-removal.md). This lesson was written
> against the Windows-deploy path; on Linux, `flock`/`O_EXCL`/inode
> behaviour is friendlier but the principle still applies — kill the
> JVM before overwriting its jar in any environment where you can't
> guarantee no lazy class-loading is mid-flight.

Windows does not serialise jar overwrites against an open JVM file
handle the way you'd hope. On NTFS, an overwrite can succeed (with
`FILE_SHARE_DELETE` semantics), but any lazy class-load from the
already-open file handle then hits corrupted or shifted data.

## Worked failure (UD-222 marathon, 2026-04-19)

A new `unidrive-0.0.0-greenfield.jar` was deployed while a sync
process still held the previous jar loaded. Sync then threw

```
java.lang.NoClassDefFoundError: kotlinx/coroutines/CoroutineExceptionHandlerKt
```

mid-execution. The classloader had attempted to lazy-load a class from
an offset that no longer contained what the JVM expected.

## How to apply

1. Before any `cp build/libs/…jar <deploy-target>`, kill every JVM
   that's holding it. On Windows: `taskkill /F /PID <jvm_pid>`. On
   Linux: `kill -TERM <jvm_pid>` then wait for it to exit before
   overwriting.
2. Enumerate JVMs via `tasklist /FI "IMAGENAME eq java.exe"` (Windows)
   or `pgrep -fa java` (Linux).
3. **Exception:** if no JVM has the jar loaded (sync just exited
   cleanly, no daemon running), overwrite is safe.
4. The `unidrive-watch.cmd` Windows daemon ships with a
   sentinel-file protocol (`%LOCALAPPDATA%\unidrive\stop` — the watch
   loop checks this file on each cycle and exits cleanly). Prefer the
   sentinel over `taskkill` when available.

## Alternative (untried but plausible)

Stage the new jar under a different name (`unidrive-NEW.jar`), update
the launcher to point at the new name, then start the new JVM fresh.
Avoids the ordering constraint entirely. Worth doing if hot-swap
becomes a frequent failure mode.
