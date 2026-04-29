---
id: ADR-0011
title: Remove the shell-win Windows Explorer extension tier
status: accepted
date: 2026-04-28
supersedes_in_part: ADR-0001
related: ADR-0003, ADR-0006
amended_by: ADR-0012, ADR-0013
---

## Context

UniDrive shipped its v0.0.1 first-public-preview tag on 2026-04-28 with
three tiers per [ADR-0001](0001-monorepo-layout.md): `core/` (Kotlin/JVM
daemon), `ui/` (Swing tray), and `shell-win/` (a C++/MSVC Cloud Files API
DLL that hooked into Windows Explorer for native overlay icons,
context-menu entries, and on-access placeholder hydration).

`shell-win/` was structurally complete (18 source/test files, building
green via CMake + MSVC + CTest, with a named-pipe contract to the
daemon's [NamedPipeServer.kt](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/NamedPipeServer.kt))
but **functionally a stub**: every CFAPI request handler in the daemon
returned an error or a no-op acknowledgement, and the DLL itself never
moved beyond skeleton implementations of the five Cf* callbacks. The
v0.3.0 milestone was 18+ months out at the rate the rest of the project
was advancing, and the value proposition (overlay icons in Explorer)
was easier to defer than to finish.

## Decision

Remove `shell-win/` from the monorepo, effective v0.0.1.

What goes:

1. The `shell-win/` directory and all 18 files (CMake project, C++
   sources, tests, resources).
2. The `shell-win` job in [.github/workflows/build.yml](../../.github/workflows/build.yml).
3. The two C++ Semgrep rules in `.semgrep/rules/cpp/winapi-security.yaml`
   (`winapi-null-deref-createfile`, `missing-cfapi-ack`) — they only
   matched `shell-win/` content.
4. The `shell-win` row in the layout table in
   [README.md](../../README.md), the `shell-win/` references in
   [ARCHITECTURE.md](../ARCHITECTURE.md) and
   [docs/ARCHITECTURE.md §Platform support](../ARCHITECTURE.md#platform-support),
   the `shell-win` mention in
   [CLAUDE.md](../../CLAUDE.md), and the `shell-win` path entry in
   [scripts/ci/check-boundaries.sh](../../scripts/ci/check-boundaries.sh).
5. The CMake / C++20 / MSVC 2022 toolchain claim in
   [ADR-0006](0006-toolchain.md) — those were exclusively shell-win
   dependencies.

What stays:

1. **`core/app/sync/NamedPipeServer.kt`** and `PipeSecurity.kt`. Per
   [ADR-0003](0003-ipc-unification.md), the named pipe is the planned
   v0.2.0 transport for `ui/` on Windows; the server is currently
   consumer-less but architecturally load-bearing.
2. The `protocol/schema/v1/` envelope, surface A (daemon → UI events),
   and the `state` request/response schema in surface B. The four
   CFAPI-specific surface-B ops (`fetch`, `dehydrate`, `cleanup`,
   `notify_register`) are now consumer-less and **flagged for
   deletion in v0.0.2** unless a non-CFAPI consumer materialises in
   the meantime. They cost nothing to keep at the schema level (no
   code maintenance), so the deletion is a doc + INDEX cleanup,
   not an urgent one.
3. All shell-win backlog items (UD-401 pipe-based hydration,
   UD-402 hydration via temp file, UD-403 placeholder lifecycle,
   UD-810 shell-win ↔ daemon IPC integration) move from
   `BACKLOG.md` to `CLOSED.md` with `resolved_by:` pointing at
   this ADR. Re-opening them requires a new ADR overturning this
   decision.

## Consequences

### Positive

- The CI matrix loses the Windows-MSVC job. CI runs ~50 % faster
  end-to-end (no MSVC toolchain provisioning, no CMake configure +
  CTest pass on Windows runner).
- The C++ toolchain assumption goes away. New contributors only need
  JDK 21 + Gradle to build everything in tree.
- The "v0.3.0 preview" framing in the README, which set unrealistic
  expectations for Windows Explorer integration, is replaced with an
  honest "two tiers" model that matches what actually ships.
- The named-pipe transport that was carrying both shell-win and
  future-UI traffic now has a single use case (UI on Windows), which
  simplifies the security model — the SDDL DACL only has to admit one
  client process pattern.

### Negative / trade-offs

- **No native Windows Explorer integration.** Overlay icons for
  synced/syncing/error states, context-menu items (Pin, Free, Sync
  now), and OS-level placeholder hydration via CFAPI are off the
  table. Power users on Windows get the CLI + tray UI; they do not
  get the Explorer experience that competitors like InSync or
  Mountain Duck offer.
- **No on-access hydration on Windows.** Files synced under the v0.1.0
  daemon will be either fully hydrated or absent — there is no
  "click to hydrate" placeholder behaviour without CFAPI. The sync
  engine's existing `--download-only` and selective-sync paths still
  work; they just lack the OS-level lazy-load.
- **`NamedPipeServer.kt` is dead weight today.** The pipe binds, the
  validator runs, the rate limiter is enforced, but no client
  connects. This costs ~600 lines of Kotlin + a daemon thread on
  Windows. Acceptable because v0.2.0 will reactivate it from `ui/`.

### Neutral

- The protocol/schema/v1/ files for `fetch` / `dehydrate` /
  `cleanup` / `notify_register` are kept until v0.0.2 simply because
  removing them touches schemas + fixtures + INDEX.md + any
  contract test that loads them. One follow-up commit is cheaper
  than a one-shot mass-delete that risks missing references.

## Alternatives considered

- **Defer `shell-win/` to v0.3.0 and ship v0.0.1 with it as
  status: preview.** Rejected: the README "v0.3.0 preview" claim was
  misleading — the implementation was a skeleton that returned
  errors for every callback. Shipping a public preview tag with a
  visibly-broken tier hurts trust more than it helps.
- **Keep shell-win/ but move it to a separate `unidrive-shell-win`
  repo.** Rejected: the original sibling-repo split was the problem
  [ADR-0001](0001-monorepo-layout.md) consolidated to fix; recreating
  it now would be a regression. If shell-tier work resumes, it
  re-enters the monorepo as `shell-win/` again.
- **Pivot to FUSE on Linux + FileProviderExtension on macOS first.**
  Rejected for v0.0.x: the Linux/macOS shell-tier surfaces are
  bigger projects than the Windows one was, and the marketing brief
  ([docs/internal/competitive-brief-2026-04.md](../internal/competitive-brief-2026-04.md))
  doesn't depend on shell-tier integration to differentiate. Tray
  UI + CLI carry the v0.1.0 / v0.2.0 milestones.

## Re-opening criteria

This decision is reversible. Concrete signals that would justify
re-importing a shell tier:

1. A funded roadmap commitment to native Explorer integration with
   identified developer capacity (≥ 6 weeks of dedicated C++/MSVC
   work, including overlay icons + context menu + CFAPI lifecycle).
2. A specific paying customer or partner who needs CFAPI semantics
   and is willing to underwrite the work.
3. A change in the platform landscape (e.g. a cross-platform
   abstraction over CFAPI / FileProviderExtension / FUSE that
   collapses three tiers into one).

In any of those cases: open a new ADR (`0012-shell-tier-revival.md`
or similar), supersede this one, re-import the shell-win tree from
git history, and re-instate the CI job.

## Related

- Backlog: [UD-401](../backlog/CLOSED.md#ud-401),
  [UD-402](../backlog/CLOSED.md#ud-402),
  [UD-403](../backlog/CLOSED.md#ud-403),
  [UD-810](../backlog/CLOSED.md#ud-810) — closed by this ADR.
- Supersedes-in-part: [ADR-0001](0001-monorepo-layout.md).
- Affects: [ADR-0003](0003-ipc-unification.md) (shell-win is no
  longer a client of the named pipe; UI remains the v0.2.0 target),
  [ADR-0006](0006-toolchain.md) (C++/MSVC dependency dropped).
