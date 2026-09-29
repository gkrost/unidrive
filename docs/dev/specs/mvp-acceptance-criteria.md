# MVP acceptance criteria — the release gate, per surface

**Purpose.** Replace "green by user's review and decision" with a mechanical checklist that is identical for every MVP surface. A surface ships when — and only when — its checklist is walked clean. Adoption tracked in #342; the audit instrument is `unidrive verify` (#341).

**Surfaces under this gate (per #290 decision):**

| Surface | Scope | Gate instance |
|---|---|---|
| Core / CLI | `unidrive` daemon + CLI sync, legacy `SyncEngine` (engine decision #343) | this repo, Phase I of `mvp-release-sequence.md` |
| Linux | core + `unidrive-mount-linux` FUSE mount | this repo + mount-linux smoke set |
| Windows | core + `unidrive-windows` **read-only** CfAPI mount + CLI sync | unidrive-windows#9 |

## The six criteria

1. **Zero open `sev:critical` / `kind:data-loss` issues** touching the surface.
2. **Smoke suite green.** 5+5+2 for core; each platform tier gets its own smoke set (per `docs/adr/multi-platform.md` — defined when the tier reaches its gate, not speculatively).
3. **Unattended soak: 14 days** on a real account, on the surface's target machine: no manual intervention, no unexplained log errors, and convergence verified by `unidrive verify` on a daily schedule (exit 0 every run, or each divergence triaged to an issue).
4. **Crash-injection matrix.** kill -9 (Windows: TerminateProcess) at each defined point — mid-download, mid-upload, mid-apply, mid-hydrate, mid-enumerate — leaves no data loss and self-heals on the next pass. Each point is a scripted scenario, re-runnable.
5. **Scale envelope.** Proposed defaults (owner-tunable, set once before the first gate): 100k files / 500 GiB profile — steady-state incremental poll ≤ 5 min; no full content re-read per pass; RSS ≤ 2 GiB; mount `ls` responsive during a running sync pass.
6. **The release decision is the checklist walkthrough** — the owner walks criteria 1–5 for the surface and records the result on the surface's gate issue. Nothing else is required; nothing less suffices.

## Soak protocol

- One profile per provider on the surface's machine (test accounts; the primary Internxt profile stays read-mostly — no destructive test target).
- Daemon runs as the platform intends (systemd --user on Linux; login-start on Windows).
- Daily: `unidrive verify` (criterion 3) + log triage (`log-watch.sh --summary` or platform equivalent).
- Any intervention, unexplained error, or divergence resets that surface's soak clock after the cause is fixed.

## Notes

- Criteria 1–2 are continuously checkable; 3–5 are per-gate campaigns. Soaks for an earlier surface keep running in the background while the next surface is worked interactively — overlap is intended.
- Efficiency findings gate via criterion 5, not via taste (#316, #317 are the current known blockers for the envelope above).
