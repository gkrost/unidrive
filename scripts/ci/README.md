# CI fragments

Shell fragments invoked by CI and usable standalone for local reproduction. CI host is **GitHub Actions** per [ADR-0009](../../docs/adr/0009-ci-host.md); see [.github/workflows/build.yml](../../.github/workflows/build.yml) for the authoritative pipeline definition. The fragments are deliberately host-neutral shell so they can be invoked from a developer laptop to reproduce CI results — this is a developer-experience property, not a portability guarantee.

## Pipelines (target shape)

| Pipeline | Runs | Blocking |
|----------|------|----------|
| `build-core.sh` | `cd core && ./gradlew build test` on Linux | yes |
| `backlog-sync.sh` | `kotlinc -script scripts/backlog-sync.kts` | yes (prevents orphan refs) |
| `gitleaks.sh` | `gitleaks detect --source . --config .gitleaks.toml` against the working tree | yes |
| `trivy.sh` | trivy on gradle lockfile + container images | warn until UD-108 complete |
| `semgrep.sh` | semgrep on Kotlin | warn until UD-109 complete |

> The `build-shell.sh`, `protocol-verify.sh`, and `build-ui.sh` pipelines
> were retired with [ADR-0011](../../docs/adr/0011-shell-win-removal.md)
> (no `shell-win/` to build),
> [ADR-0012](../../docs/adr/0012-linux-mvp-protocol-removal.md)
> (no `protocol/` to validate), and
> [ADR-0013](../../docs/adr/0013-ui-removal.md)
> (no `ui/` to build).

## Local shortcuts

Run the equivalent locally with:

```bash
scripts/ci/build-core.sh
scripts/ci/backlog-sync.sh
```

All fragments assume the monorepo root as CWD.
