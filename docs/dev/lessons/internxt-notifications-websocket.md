# Internxt `NOTIFICATIONS_URL` WebSocket — research notes (UD-306)

*Filed 2026-05-15. Pairs with the long-form audit at
[`docs/audits/internxt-websocket-feasibility.md`](../../audits/internxt-websocket-feasibility.md).
This lessons file is the short, agent-facing summary; the audit is the
artefact. Static-analysis only — no live capture was performed.*

## 1. What is `NOTIFICATIONS_URL`?

A socket.io WebSocket endpoint configured in drive-desktop via
`process.env.NOTIFICATIONS_URL` (declared `z.url()` in
`.erb/scripts/validate-process-env.ts:NOTIFICATIONS_URL`). drive-desktop
opens the connection in `src/apps/main/realtime.ts` with
`io(NOTIFICATIONS_URL, { transports: ['websocket'], auth: { token } })`
and subscribes to the `'event'` channel. Frames carry a Zod-validated
envelope `{ email, clientId, userId, event, payload }` where `event` is
one of `FILE_CREATED`, `FILE_UPDATED`, `FOLDER_CREATED`, `FOLDER_UPDATED`,
`ITEMS_TO_TRASH`, plus account-scoped `PLAN_UPDATED` / `WORKSPACE_*`
(server-side `StorageEvents` enum in
`drive-server-wip/src/externals/notifications/storage.notifications.service.ts`).

This is a **different surface** from the REST `/notifications` endpoint
investigated in UD-370 (REST is marketing-only banners; the WebSocket is
a real file-mutation push channel). Full schema, decision tree, and
server topology in
[`docs/audits/internxt-websocket-feasibility.md`](../../audits/internxt-websocket-feasibility.md)
lines 21-79 and 111-159.

## 2. Is unidrive already consuming it?

**No.** Grep evidence (2026-05-15):

- `grep -rni "notification|websocket|NOTIFICATIONS_URL" core/providers/internxt/src/main/kotlin/` returns zero hits.
- The four candidate files (`InternxtConfig.kt`, `InternxtApiService.kt`,
  `InternxtAuthApi.kt`, `AuthService.kt`) have no notification/websocket
  references.
- The only `websocket` hits in the provider tree are transitive Ktor
  dependencies in `core/providers/internxt/gradle.lockfile:72-75`
  (`ktor-websockets-jvm:3.4.3` etc., pulled in by other Ktor modules,
  not used directly).

unidrive's Internxt provider relies entirely on offset-walked
checkpoint polling via `InternxtProvider.delta()` — no push surface.

## 3. Is there an existing related ticket?

- **UD-370** (referenced as "closed 2026-05-03" in the audit and ticket
  body) covers the REST `/notifications` surface and concluded NO-GO
  (marketing-only). The audit lives at
  [`docs/audits/internxt-notifications-feasibility.md`](../../audits/internxt-notifications-feasibility.md).
  Note: UD-370 has no body in `docs/backlog/BACKLOG.md` or `CLOSED.md`
  on this branch — only audit docs reference it by ID. If the ticket
  ever needs a `resolved_by` paper trail beyond the audit, that gap may
  need closing separately.
- **UD-306** (this ticket, body at
  [`docs/backlog/BACKLOG.md:4355-4396`](../../backlog/BACKLOG.md))
  scopes the WebSocket surface specifically.
- No other tickets in `BACKLOG.md` or `CLOSED.md` mention the
  WebSocket surface (`grep -n "UD-370\|websocket\|NOTIFICATIONS_URL"`
  on both files).

UD-306 is **not** a duplicate of UD-370 — different channels, different
verdicts.

## 4. What would unidrive gain by consuming it?

The WebSocket is a real per-user push channel; wins are post-MVP
optimisations, not correctness fixes:

- **Latency**: web-UI / mobile-app edits surface in seconds instead of
  one poll interval.
- **Battery / Internxt-load**: idle clients skip polling while
  connected; the socket itself is the keepalive.
- **Loop elision**: server attaches `clientId` to every frame, so
  unidrive's own writes can be filtered (drive-desktop does this via
  `INTERNXT_CLIENT === 'drive-desktop-windows'` in
  `src/backend/features/remote-notifications/in/process-web-socket-event.ts`).

Crucially, drive-desktop treats frames as a **wake signal, not a source
of truth** — `processWebSocketEvent` only triggers
`updateAllRemoteSync()` → `startSyncByCheckpoint()`. unidrive can adopt
the same shape: debounced re-run of the existing delta walker, payload
discarded. No cache-coherence risk.

## 5. Effort estimate + blockers

**T-shirt size: M.** Reasoning:

- Kotlin socket.io client exists (`io.socket:socket.io-client`) — no
  protocol implementation needed.
- Auth flow reuses the existing JWT (`auth: { token }`).
- The wake-signal-only integration shape means no schema parsing, no
  payload-driven side effects, no new test surface beyond
  connect/reconnect/debounce.

Blockers / unknowns surfaced by the audit
([`internxt-websocket-feasibility.md` §"What couldn't be determined without runtime access"](../../audits/internxt-websocket-feasibility.md)):

1. **Production URL** (`NOTIFICATIONS_URL` value) — build-time injected
   in drive-desktop CI; likely `https://notifications.internxt.com`
   but unconfirmed. 30 sec to confirm via mitmproxy.
2. **socket.io protocol version & namespace** — default `/` assumed.
3. **Server-side coalescing behaviour** — affects unidrive's debounce
   window.
4. **Token refresh on mid-session expiry** — `obtainToken()` is called
   once at connect; reconnect-with-fresh-token behaviour unclear.
5. **Regional fan-out topology** — affects latency expectations.

All five are answerable with ~30 min of live capture against a test
account; none gate the feasibility verdict.

## 6. Recommendation

**Do not close UD-306 as duplicate of UD-370.** Different channels,
different verdicts. The audit concludes the WebSocket is a real change
feed.

Two viable closing paths:

- **(a) File a follow-up implementation ticket** (e.g. "Internxt
  WebSocket wake-signal integration", priority low, effort M,
  DEFER post-MVP) and close UD-306 as resolved-by-audit pointing at
  [`docs/audits/internxt-websocket-feasibility.md`](../../audits/internxt-websocket-feasibility.md).
- **(b) Leave UD-306 open** with the audit attached as the artefact,
  re-tagged "research-done, implementation-deferred", until MVP ships.

Either way: **do not implement before MVP.** The existing offset-walked
checkpoint walk (UD-352a + UD-303) is sufficient for correctness; the
WebSocket is purely a latency/efficiency optimisation. The audit
explicitly recommends DEFER post-MVP and this lessons file concurs.

## Cross-refs

- [`docs/audits/internxt-websocket-feasibility.md`](../../audits/internxt-websocket-feasibility.md) — the full audit (this file's source of truth).
- [`docs/audits/internxt-notifications-feasibility.md`](../../audits/internxt-notifications-feasibility.md) — REST `/notifications` audit (UD-370, NO-GO).
- [`docs/backlog/BACKLOG.md:4355-4396`](../../backlog/BACKLOG.md) — UD-306 ticket body.
- `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/` — current provider tree, no notification consumer.
