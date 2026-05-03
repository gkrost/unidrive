# CLI Extension SPI

> Target audience: developers building private / downstream / commercial
> modules that attach subcommands to the `unidrive` CLI without forking
> the public repo.

## What the SPI is

The public CLI exposes a tiny service-provider interface (SPI) at
`org.krost.unidrive.cli.ext`. Implementations discovered via
`java.util.ServiceLoader` at startup can:

- Attach new picocli subcommands under any existing parent command.
- Read profile, config, provider, and version state through the
  read-only `CliServices` facade.
- Use the supplied `Formatter` for terminal-safe coloured output.

Extensions MAY depend on any `@PublicApi`-annotated type in
`org.krost.unidrive.cli.ext`. Extensions MUST NOT depend on any other
type inside `org.krost.unidrive.cli`. The public build graph never
references extensions.

## Lifecycle

1. The public CLI starts, constructs the root `CommandLine`.
2. `CliExtensionLoader.loadInto(root, services)` runs.
3. For each extension found on the classpath: the loader constructs a
   fresh `CliExtensionRegistrarImpl`, calls `extension.register(reg)`,
   catches and logs any throwable.
4. picocli parses args and dispatches.

Constructors should be empty — all setup happens in `register()`.
ServiceLoader caches instances for the JVM lifetime.

## Minimal extension — example

```kotlin
// build.gradle.kts (extension module)
dependencies {
    // Public dependencies only.
    implementation("org.krost.unidrive:core:...")
    implementation("org.krost.unidrive:cli:...")
}
```

```kotlin
// src/main/kotlin/com/example/HelloCommand.kt
package com.example

import org.krost.unidrive.cli.ext.CliServices
import picocli.CommandLine.Command

@Command(name = "hello", description = ["Say hello."])
class HelloCommand(private val services: CliServices) : Runnable {
    override fun run() {
        println(services.formatter.bold("Hello from ${services.unidriveVersion}"))
    }
}
```

```kotlin
// src/main/kotlin/com/example/HelloExtension.kt
package com.example

import org.krost.unidrive.cli.ext.CliExtension
import org.krost.unidrive.cli.ext.CliExtensionRegistrar

class HelloExtension : CliExtension {
    override val id = "hello"
    override fun register(r: CliExtensionRegistrar) {
        r.addSubcommand("", HelloCommand(r.services))
    }
}
```

```
# src/main/resources/META-INF/services/org.krost.unidrive.cli.ext.CliExtension
com.example.HelloExtension
```

Drop the resulting jar on the CLI classpath. `unidrive hello` works
immediately; without the jar, `unidrive hello` reports `unknown command`.

## Constraints and guarantees

| Guarantee | Detail |
|---|---|
| **Additive SPI stability** | `CliServices` grows only; method signatures never change within v1. Breaking changes move to `v2` package. |
| **Crash isolation** | A throwing `register()` does not bring down the CLI — the loader logs to stderr and continues. |
| **Collision detection** | Two extensions registering the same command name under the same parent fail loud at startup with both extension ids named. |
| **No premium strings in public** | The public CLI never mentions "premium", "pro", "upgrade", or paywalling. Extensions are simply present or not. |
| **Parent-command scope (v1)** | `addSubcommand` takes a direct top-level parent name or the empty string for root. Deeper nesting is out of scope for v1. |

## Known limitations

- **Single-profile-per-JVM (UD-211).** `CliServices` methods that take a
  `profileName` parameter (`resolveProfile`, `createProvider`,
  `loadSyncConfig`, `isProviderAuthenticated`) mutate `Main.provider`
  under the hood. `Main` memoises profile resolution in a private cache
  that is not invalidated on mutation, so the SECOND call with a DIFFERENT
  profile name returns stale data from the first. Extensions that want
  to iterate over multiple profiles must spawn a fresh JVM per profile
  until UD-211 lands a cache-bust hook.

## Versioning policy

- `CliServices` v1 is the current line. Adding methods requires a
  BACKLOG item and changelog entry.
- Breaking changes (method removed or signature altered) require a new
  package `org.krost.unidrive.cli.ext.v2` and a deprecation cycle of
  at least one release.

## Testing your extension

See `core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ext/fixtures/DummyExtension.kt`
for the minimal testable shape. Construct a fake `CliServices` in-test;
register the extension against a synthetic `CommandLine`; assert the
subcommand is reachable.

## Reference

- SPI source: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ext/`
- Test fixture: `core/app/cli/src/test/kotlin/org/krost/unidrive/cli/ext/fixtures/DummyExtension.kt`
- This document IS the spec — there is no separate spec file. The previous
  cross-reference to `docs/specs/relocate-v1-sprint-plan.md` was a misfile
  (relocate-v1 is the relocate-operation contract, not the SPI extensibility
  spec). UD-771 doc-sweep, 2026-05-03.
