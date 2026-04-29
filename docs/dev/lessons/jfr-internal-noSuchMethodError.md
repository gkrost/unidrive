# JFR `JavaErrorThrow` events include JVM-internal `NoSuchMethodError`s that are never propagated

**Surfaced 2026-04-29 during UD-280 investigation.**

## Problem

A JFR (`-XX:StartFlightRecording=settings=profile`) capture of a
unidrive run will show dozens of `jdk.JavaErrorThrow` events with
`thrownClass = java.lang.NoSuchMethodError`. The first instinct —
"dependency drift! some library was compiled against a different
version!" — is wrong. The vast majority of these are JVM-internal
control flow that never reaches application code.

## Why it bites

JFR's `jdk.JavaErrorThrow` event captures **every** thrown exception
including those caught + handled inside the JVM's own internal code
paths. The JVM uses thrown-and-caught exceptions as a control-flow
mechanism in three places that show up loudly:

1. **MethodHandle resolution** — `MethodHandleNatives.resolve` tries
   each candidate `Holder` method signature, catches `NoSuchMethodError`
   if the specific arity/types don't match, and falls back to the next
   candidate. Every `invokedynamic` / `LambdaMetafactory` call site
   that hasn't been linked yet triggers a few of these. Filtered by
   `thrownClass = java.lang.invoke.DirectMethodHandle$Holder`,
   `java.lang.invoke.Invokers$Holder`, or
   `java.lang.invoke.DelegatingMethodHandle$Holder`.

2. **Array static initialisers** — `ObjectStreamClass.hasStaticInitializer`
   probes for `<clinit>()V` on a class to decide whether serialization
   needs to call it. Array types like `[Ljava/lang/Object;` don't have
   a clinit, so the lookup throws — caught, used as the negative
   answer. Filtered by `message = "static [...]<clinit>()V"`.

3. **Reflection lookups under `getMethod` / `getDeclaredMethod`** —
   when a library does runtime feature detection ("does this JDK have
   method X?") with try/catch around `Class.getMethod`, JFR captures
   the negative answers as `NoSuchMethodError`. These ARE
   application-code throws (the stack trace shows the library), but
   they're intentional probes, not failures.

## How to triage

```bash
# Count: anything > 0 is normal.
jfr print --events jdk.JavaErrorThrow recording.jfr | grep -c NoSuchMethodError

# Show only the messages — JVM-internal patterns to filter out:
jfr print --events jdk.JavaErrorThrow recording.jfr \
  | grep "message = " \
  | grep -v 'DirectMethodHandle\|Invokers\|DelegatingMethodHandle' \
  | grep -v 'static \[L.*<clinit>'
```

If the filtered set is **empty**, you have only JVM internals — close
the investigation. If non-empty, look at each remaining message: it's
either a real app-level missing-method (rare; would also crash the
app, not just emit a JFR event) or a library doing intentional feature
detection (common; check the stack trace's caller frame).

## Defensive checklist

- Don't open dependency-drift tickets from a raw `JavaErrorThrow`
  count without filtering JVM internals first.
- When writing a "no new NoSuchMethodErrors in fresh JFR" acceptance
  criterion, scope it to **non-`java.lang.invoke.*`** + **non-array
  clinit** events — otherwise it's untestable.
- The JFR `--events jdk.JavaErrorThrow` output stack-trace's first
  non-JDK frame is the right starting point — frames inside
  `java.lang.invoke.*` or `java.io.ObjectStreamClass` are JVM
  infrastructure, not your code.

## Related

- UD-280 — original ticket investigating "40 NoSuchMethodErrors in a
  minute". Closed after this lesson confirmed all events were
  `DirectMethodHandle$Holder` / `Invokers$Holder` resolution and array
  clinit probes.
- [verify-before-narrative.md](verify-before-narrative.md) — sibling
  lesson: confirm the distinguishing attribute before building the
  diagnosis. Here the distinguishing attribute is "what class is
  thrownClass on, and where in the stack trace is the first non-JDK
  frame?"
