# UI / Tray work — extracted from unidrive-cli (out of scope here)

This file holds backlog material that targets unidrive's GUI surface
(system-tray icon, popup menu, desktop sync window, etc.). The
`unidrive-cli` repo is the **CLI + sync engine + provider adapters**
only; the UI is a separate concern that belongs in a sibling repo
when it's actively built.

The two open tickets below were filed here historically when the UI
was part of the same monorepo. Closing them in this repo's backlog
to keep the queue scoped to CLI work; preserving the full ticket
bodies here so when a UI repo materialises, the design context isn't
lost.

## Closed in unidrive-cli on 2026-04-30

- [UD-504](#ud-504--tray-icon--status-surface--evaluation--requirements-doc--polish) (medium / M)
- [UD-233](#ud-233--brainstorm--tray-popup--desktop-sync-window-ux-multi-account-activity-actions) (medium / M)

## Historical UI tickets (closed before 2026-04-30, in `docs/backlog/CLOSED.md`)

The following UI tickets were closed in this repo before this split.
They're listed here so the UI repo can pick up the design lineage:

| Ticket | Title |
|---|---|
| UD-501 | Replace manual TOML parsing in TrayManager with a real parser |
| UD-502 | UI snapshot / integration tests |
| UD-503 | Migrate UI IPC from UDS to unified pipe/UDS-per-OS (ADR-0003) |
| UD-213 | Tray popup lacks multi-provider + multi-account layout |
| UD-215 | Tray icon activity indicator — surface in-flight work (sync / upload / download / error) |
| UD-217 | Tray popup UI/UX — brainstorm + wireframe doc for multi-provider/multi-account |
| UD-221 | BUG — tray "Open sync folder" opens an arbitrary profile's folder, no multi-account picker |

The closed bodies remain in [`docs/backlog/CLOSED.md`](docs/backlog/CLOSED.md)
for now; they can be rehomed alongside the UI repo when one exists.

## Cross-cutting CLI tickets that the UI consumes

The CLI also exposes data that any future UI would surface. Tracking
them here as integration touch-points:

- **UD-111** (closed 2026-04-30): structured `RefreshFailure` record on
  OneDrive's `TokenManager` — the UI's "please re-authenticate" prompt
  reads this.
- **UD-214** (closed 2026-04-30): offline-friendly quota cache — the
  tray's quota bar reads `sync_state["quota_*"]` and displays "as of T"
  when the network is down.
- **UD-261** (closed): `status` CLOUD column uses authoritative
  `quota.used` for QuotaExact providers — the tray should mirror this.
- **UD-294** (closed): MDC propagation — the tray's "View log" action
  filters by profile, which only works when the log lines carry the
  `[<sha>] [<profile>] [<scan>]` triplet.
- **UD-733** (closed): build-SHA + dirty banner — the tray's "About"
  dialog should mention dirty builds the same way the CLI startup
  banner does.

---

## UD-504 — Tray icon / status surface — evaluation + requirements doc + polish

**Original metadata:** category=ui, priority=medium, effort=M,
opened=2026-04-19, chunk=ipc-ui, code_refs=ui/src/main/kotlin/org/krost/unidrive/ui/TrayManager.kt

**First field-observation (2026-04-19, during UD-222 live run):** when the download process died with an auth failure (UD-310), the tray surfaced the error end-to-end for the first time. The good:
  - Red dot icon switched on (first time user has seen the red state in the field).
  - Popup showed "onedrive" + truncated error message ("Authentication failed (401): {\"error\":{\"co...").
  - Menu item mirrored the same truncated error.
  - Menu had a "Sync now: onedrive" + "Open folder: onedrive" pair next to the failing profile, plus a generic "Open sync folder" / "View log" / "Quit" block.

What worked. What needs polish:
  1. **Time lag.** Red state arrived with visible latency after the CLI had already logged the 401. Measure: how is the tray learning of the state change (polling state.db? IPC event?) and what's its tick rate? Document the expected latency upper bound (e.g. "within 5 s of a daemon-side state change" — requires the daemon to push over pipe/UDS, not just the tray polling the DB).
  2. **Truncated error body is hostile.** The popup shows ~60 chars of raw Graph JSON ("Authentication failed (401): {\"error\":{\"co..."). A human-first message ("Microsoft OneDrive sign-in expired — click to re-authenticate") would ship more signal in fewer pixels. Raw details should move behind an expand/details click.
  3. **Multi-account UX is ambiguous.** The screenshot shows only ONE profile ("onedrive") despite 4 being configured; on single-error the menu shows "Sync now: onedrive" specifically. Does the tray already aggregate per-profile status? Or does it pick one profile and rely on UD-213 to fix the multi-account layout? Cross-reference with UD-213 (tray layout), UD-221 (Open folder picks arbitrary profile), UD-218 (status --all multi-account — now closed). A single source of truth for "what the tray shows" is needed.
  4. **Icon state vocabulary is undefined.** Red means "one or more profiles are in error" — but what distinguishes (a) auth expired, (b) network offline, (c) disk full, (d) sync conflict pending? Today's binary ok/error is the minimum viable. A third state (yellow/warning for "degraded but making progress" — e.g. some files failed UD-309 mismatch but sync continues) would match the mental model users already have from Dropbox / OneDrive's own tray.
  5. **Resolution affordance.** Menu shows the error but no one-click action to resolve. Ideally: red + auth error → "Re-authenticate onedrive…" menu item that runs `unidrive -p onedrive auth` and closes on success. "Quit" next to a half-broken sync is the wrong default.

**Deliverables for this ticket:**
  1. `docs/ui/tray-states.md` — state diagram mapping (profile status) → (icon colour, popup wording, menu items, recommended user action). Start from observed states in field use + the scenarios above; document the latency contract.
  2. `docs/ui/tray-popup-wireframes.md` (see UD-216) — 3 alternative layouts for N providers × M accounts, informed by (3).
  3. Polish pass on TrayManager.kt after docs are signed off: human-first error messages, multi-account layout, "Re-authenticate…" affordance, latency instrumentation (log the daemon→tray propagation delay so we can measure).

**Related:** UD-213 (multi-provider tray layout), UD-216 (tray wireframes companion), UD-221 (folder-picker bug), UD-111 (token refresh telemetry — tray should show this), UD-310 (the auth failure that surfaced this ticket), UD-222 (the sync run that produced the auth failure).

---

## UD-233 — Brainstorm — tray popup + desktop sync window UX (multi-account, activity, actions)

**Original metadata:** category=ui, priority=medium, effort=M,
opened=2026-04-19, chunk=ipc-ui, code_refs=ui/src/main/kotlin/org/krost/unidrive/ui/TrayManager.kt

The current tray popup is functional but minimal: icon (static), profile name, sync status string, and 4 menu items (Sync now / Open folder / View log / Quit). With the full product vision (N providers × M accounts, real-time sync activity, conflict resolution, quota display) the current shape doesn't scale. This ticket is a design brainstorm and wireframe doc before UD-213 / UD-217 implement anything.

### What the popup should surface

**Minimum useful state (every account, at a glance):**
  * Provider icon + account display name / UPN
  * Sync state: idle / scanning / downloading N of M / uploading / error / throttled
  * Last sync timestamp ("2 min ago") — already partly in UD-214
  * Quota bar: used / total with colour (green → amber → red at 80 / 95 %)
  * A single action button per account: "Sync now" if idle, "View errors" if failed, "Re-auth" if token expired

**Nice to have:**
  * Transfer rate + ETA during active sync (feeds from `onTransferProgress` / `onActionProgress`)
  * Throttle indicator: show when unidrive is in backoff ("Waiting — MS throttle, 47 s")  — connects to UD-232
  * Conflict badge: "3 conflicts" with one-click to open resolution UI
  * Per-file activity ticker: last 3 files transferred (like a mini log)

### Desktop sync window (separate window, not tray popup)

Rationale: the tray popup is inherently transient (closes on click-away). Operators running a 130 k-file initial sync need a persistent window they can leave open and check back on. Options:

  1. **Borderless overlay** (à la Dropbox): always-on-top, docked to screen edge, auto-hides when not in focus. Shows live transfer list + quota + speed graph.
  2. **Regular JFrame window** ("UniDrive Activity"): standard title bar, minimisable. Easier to implement (Swing), less polished.
  3. **System notification + detail sheet**: tray popup triggers a native Windows notification; click opens a separate detail JFrame. Native feel, but two-step UX.

Recommendation before committing: wireframe all three and show to at least one non-technical user.

### What actions should be reachable

From popup (fast, one or two clicks):
  * Sync now / Pause sync / Resume
  * Open sync folder (per-account)
  * Open log file
  * Re-authenticate (triggers device-code flow — see UD-216)
  * Quit (with "sync in progress" warning if active)

From desktop window (deeper):
  * Per-file conflict resolution (show local vs remote, pick winner)
  * Sweep --null-bytes trigger (UD-226) with progress display
  * Quota detail: breakdown by folder
  * Transfer history: last N completed files with size, duration, speed
  * Error list: permanent failures with per-file retry button

### Icon states (UD-889 / UD-221 adjacent)

4 minimum: `idle` (static), `busy` (animated spin or pulse), `error` (red badge), `throttled` (amber hourglass). The throttled state is new and specifically useful given UD-232 — user sees "why is it slow?" without opening the log.

### Questions to decide before implementation

  1. One popup for all accounts or tabbed/scrollable list?
  2. Does "Sync now" sync ALL accounts or just the focused one?
  3. Should the desktop window be opt-in (setting) or always available?
  4. Windows 11 tray behaviour: does the icon appear in the overflow chevron by default? Should unidrive pin itself?
  5. Dark mode / light mode — Swing doesn't auto-follow; need FlatLaf or manual theme wiring.
  6. Accessibility: keyboard navigation for popup (Tab / Enter / Escape)?

**Related:** UD-213 (tray layout), UD-215 (activity feed), UD-217 (wireframe doc), UD-221 (open-folder bug), UD-214 (last-sync timestamp), UD-216 (auth MCP/CLI gap), UD-232 (throttle state → UI indicator).

---

## When the UI repo is created

Recommended migration steps:

1. Create `unidrive-ui` (or similar) sibling repo.
2. Move `ui/src/main/kotlin/org/krost/unidrive/ui/` (referenced in code_refs above) — note this directory does not exist in unidrive-cli today, so the tray code lives elsewhere already (or hasn't been written).
3. Move this `UI_UD.md` and the closed UI tickets from `docs/backlog/CLOSED.md` into the new repo's backlog.
4. Replace this file with a one-line pointer in unidrive-cli's README:
   `> UI / tray work lives in <unidrive-ui repo URL>.`
