# MDC inside suspend functions — the propagation trap

Learned during UD-254 / UD-255 (2026-04-20).

`org.slf4j.MDC` is **thread-local**. `MDC.put("scan", id)` inside a
suspend function works on the current thread until the first
suspension point. After resume on a different dispatcher thread, MDC
is empty unless the coroutine's context includes a `MDCContext`
element that snapshotted the MDC at creation time and re-applies it
on every resume.

## Three consequences worth pinning

1. **Calling `MDC.put` inside a suspend function after the outer
   coroutine has been started with `MDCContext()` does NOT update the
   snapshot.** On the next suspension the outer snapshot is
   re-applied, silently wiping keys you added inside.

2. **Ktor's `HttpClient` plugins run on Ktor's dispatcher.** MDC set
   in an `onRequest { }` hook does not propagate into the caller's
   coroutine scope after the response. This is why the
   [`RequestIdPlugin`](../../../core/app/core/src/main/kotlin/org/krost/unidrive/http/RequestIdPlugin.kt)
   does NOT use MDC — it puts the id into a request attribute and
   logs it in message text instead.

3. **For scan-id-style MDC that needs to cover nested suspend work
   (UD-254's `scan=<id>`),** the working pattern is:

   ```kotlin
   suspend fun syncOnce(...) {
       val scanId = uuid8()
       val priorMdc = MDC.get("scan")
       MDC.put("scan", scanId)                    // outer thread
       try {
           doWork()                                // provider calls inherit
       } finally {
           if (priorMdc == null) MDC.remove("scan")
           else MDC.put("scan", priorMdc)
       }
   }
   ```

   This works ONLY because the existing watch-loop caller is
   `runBlocking(MDCContext())` and sync work happens to stay on
   `main`. A new `withContext(Dispatchers.IO)` wrapper anywhere
   downstream would silently break the propagation.

4. **Testing MDC across suspensions is fragile.**
   `kotlinx-coroutines-test`'s `runTest` uses a controlled
   dispatcher; it happens to preserve MDC set inside for the same
   dispatcher, but don't rely on that matching production.

## Short checklist for future log-context additions

- If the key only needs to be on a few banner log lines, just
  `MDC.put` + `try/finally` — don't fight the coroutine plumbing.
- If the key needs to be on lines emitted deep inside suspend calls,
  require the caller to wrap work in `withContext(MDCContext())`
  AFTER the put (so the snapshot includes the new key). Document
  this precondition on the call site.
- If it's a cross-coroutine correlation (HTTP request ↔ response ↔
  caller WARN), put the id in the log MESSAGE, not MDC. Grepping
  `req=<id>` or `scan=<id>` across log files is equivalent and
  doesn't care about dispatcher boundaries.
