# Dependency Locking

Enabled via `dependencyLocking { lockAllConfigurations() }` in the `core/` composite (UD‑100). Lockfiles (`gradle.lockfile` and `settings‑gradle.lockfile`) are committed to the repository; CI verifies them on every build via `./gradlew dependencies -q`.

## Purpose

- **Reproducible builds** – identical dependency graph across all environments (developer machines, CI).
- **Security scanning** – provides a deterministic input for Trivy/Grype (UD‑108 follow‑up).
- **Dependabot pinning** – allows Dependabot to propose version bumps without silently picking newer transitive versions.

## Lockfiles

The `core/` composite has two lockfiles:

- `gradle.lockfile` – locks the runtime/compile/test configurations of every subproject.
- `settings‑gradle.lockfile` – locks the buildscript classpath of the root project.

Both are version‑controlled and must be updated when dependency versions change.

## Regenerating lockfiles

When you modify a version in `core/gradle/libs.versions.toml` (or any direct `build.gradle.kts` dependency), you must refresh the lockfiles:

```bash
cd core && ./gradlew dependencies --write-locks
```

The command will:

1. Re‑resolve all configurations (including buildscript) for the composite.
2. Overwrite the existing lockfiles with the new resolved versions.
3. Keep unchanged dependencies pinned to the same version (no churn).

Commit the updated lockfiles together with the version change.

## CI enforcement

The GitHub Actions workflow (`build.yml`) runs `./gradlew dependencies -q` in the `core` job. If a lockfile is out of sync with the current dependency declarations, the build fails.

## Common pitfalls

- **Ignoring buildscript** – the `settings‑gradle.lockfile` locks the Gradle plugins (ktlint, shadow, etc.). Forgetting to update it can lead to CI failures when the plugin version changes.
- **Transitive drift** – locking prevents “dependency‑of‑a‑dependency” from shifting silently. If you need to accept a newer transitive version (e.g., for a security fix), you must explicitly upgrade the direct dependency that brings it in, then regenerate the lockfiles.

## Disabling locking (temporary)

For debugging, you can temporarily disable locking by commenting out the `dependencyLocking` block in the root `build.gradle.kts`. Do not commit this change.

---

*Part of UD‑100. See [BACKLOG.md](../backlog/BACKLOG.md#ud‑100) for the original acceptance criteria.*