# UD-306 — Internxt WebSocket `NOTIFICATIONS_URL` feasibility

*2026-05-04 — static-analysis only, no live capture, no runtime needed.*

## TL;DR

**REAL change feed.** The WebSocket fanned out via `process.env.NOTIFICATIONS_URL` carries genuine file-mutation events (`FILE_CREATED`, `FILE_UPDATED`, `FOLDER_CREATED`, `FOLDER_UPDATED`, `ITEMS_TO_TRASH`, plus account-scoped `PLAN_UPDATED` / `WORKSPACE_*`). drive-desktop's handler treats unknown / cross-client events as a wake signal and unconditionally calls `updateAllRemoteSync()`, which runs `startSyncByCheckpoint()` against every active worker. This is a different surface from the REST `/notifications` endpoint disproved in UD-370 — it is a real per-user push channel. The pre-existing audit `internxt-notifications-feasibility.md` is correct for the REST surface but did not cover the WebSocket; this doc is the companion.

## Endpoint URL

Configured at runtime via `process.env.NOTIFICATIONS_URL`. The variable is declared `z.url()` (required, must parse as URL) in `.erb/scripts/validate-process-env.ts`:

```ts
NOTIFICATIONS_URL: z.url(),
```

The drive-desktop public repo does not commit a default value. The hostname (`notifications.internxt.com` is the most likely production value based on Internxt's naming convention for `BRIDGE_URL`, `DRIVE_URL`, `PAYMENTS_URL`) cannot be confirmed from public source alone — it is supplied at build time by the CI pipeline. Live capture (mitmproxy / Electron devtools) would resolve this in seconds; static analysis cannot.

## Subscription code (drive-desktop)

`src/apps/main/realtime.ts` — full file is short enough to summarise verbatim. Connection:

```ts
socket = io(process.env.NOTIFICATIONS_URL, {
  transports: ['websocket'],
  auth: { token: obtainToken() },
  withCredentials: true,
});
```

Message dispatch on the `'event'` channel:

```ts
socket.on('event', (data) => {
  void RemoteNotificationsModule.processWebSocketEvent({ data });
});
```

Other listeners are infrastructure: `'open'` (extracts `INGRESSCOOKIE` from `pollComplete` polling responses for sticky sessions), `'connect'` / `'disconnect'` / `'connect_error'` (status logging + `WEBSOCKET_CONNECTION_ERROR` issue surface).

`RemoteNotificationsModule` is a one-line re-export at `src/backend/features/remote-notifications/remote-notifications.module.ts`:

```ts
export const RemoteNotificationsModule = {
  processWebSocketEvent,
};
```

## Handler (drive-desktop)

`src/backend/features/remote-notifications/in/process-web-socket-event.ts` — the entire handler:

```ts
export async function processWebSocketEvent({ data }: { data: unknown }) {
  const parsedEventData = await NOTIFICATION_SCHEMA.safeParseAsync(data);

  if (parsedEventData.success && parsedEventData.data.clientId === INTERNXT_CLIENT) {
    const event = parsedEventData.data;
    logger.debug({
      msg: 'Local notification received',
      event: event.event,
      clientId: event.clientId,
      payload: event.payload,
    });
  } else {
    logger.debug({ msg: 'Remote notification received', data });
    await updateAllRemoteSync();
  }
}
```

`INTERNXT_CLIENT === 'drive-desktop-windows'` (from `src/core/utils/utils.ts`).

Decision tree:

1. **Schema valid AND clientId is *this* client (`drive-desktop-windows`)** → log only, no sync. The event was caused by *this* client's own mutation looping back; ignore it (already reflected in local state).
2. **Anything else** (schema invalid, OR schema valid but `clientId === 'drive-web'` / other) → call `updateAllRemoteSync()`.

`updateAllRemoteSync` (`src/apps/main/remote-sync/handlers.ts`) iterates every worker and runs `updateRemoteSync({ ctx })`, which short-circuits if status is already `SYNCING`, otherwise sets `SYNCING` → `await startSyncByCheckpoint({ ctx })` → sets `SYNCED` → refreshes placeholders. **The WebSocket frame's payload is never used as authoritative content**; it only triggers an incremental checkpoint walk.

The unit test `process-web-socket-event.test.ts` confirms all three branches exactly. Notably the third case — `clientId: 'drive-web'` — is the user editing files via the web UI; the desktop app picks up the change without polling.

## Event schema (from drive-desktop)

`src/apps/main/notification-schema.ts` is a Zod discriminated union of three event families. Quoting key parts (full schema is short, in `notification-schema.ts`):

```ts
const ITEMS_TO_TRASH = EVENT.extend({
  event: z.literal('ITEMS_TO_TRASH'),
  payload: z.array(z.object({
    type: z.union([z.literal('file'), z.literal('folder')]),
    uuid: z.string(),
  })),
});

const FILE_EVENT = EVENT.extend({
  event: z.enum(['FILE_CREATED', 'FILE_UPDATED']),
  payload: FILE_DTO,   // full file: id, uuid, fileId, name, size, bucket, folderUuid, encryptVersion, plainName, status...
});

const FOLDER_EVENT = EVENT.extend({
  event: z.enum(['FOLDER_CREATED', 'FOLDER_UPDATED']),
  payload: z.object({ id: z.number(), uuid: z.string(), name: z.string(), plainName: z.string() }),
});
```

Common envelope: `{ email, clientId, userId, event, payload }`. `clientId` is one of `'drive-desktop-windows' | 'drive-web'` per the schema (the `drive-web` literal is hardcoded in the union). The desktop's loop-detection works only because the server faithfully attaches the originating client.

Event types declared by the schema are a subset of what the server emits — the server-side enum (next section) is the authoritative list.

## Server-side gateway (drive-server-wip)

**No public socket.io gateway in drive-server-wip itself.** GitHub code search for `WebSocketGateway`, `socket.io`, and `gateway` in `internxt/drive-server-wip` returned zero hits. The fan-out service is hosted separately and is not in the public repo set inspected here.

What drive-server-wip *does* contain is the event **producer**: `src/externals/notifications/storage.notifications.service.ts`. It exposes the canonical event enum:

```ts
enum StorageEvents {
  FILE_CREATED, FOLDER_CREATED,
  FILE_UPDATED, FOLDER_UPDATED,
  ITEMS_TO_TRASH,
  FILE_DELETED, FOLDER_DELETED,
  PLAN_UPDATED,
  WORKSPACE_JOINED, WORKSPACE_LEFT,
}
```

Each per-event method (`fileCreated`, `fileUpdated`, etc.) constructs a `NotificationEvent` and calls `notificationService.add(event)`, which is a thin wrapper over NestJS `EventEmitter2.emit`. The cross-process bridge is `src/externals/notifications/listeners/notification.listener.ts`:

```ts
@OnEvent('notification.*')
async handleNotificationEvent(event: NotificationEvent) {
  const apiNotificationURL = this.configService.get('apis.notifications.url');
  const headers = { 'X-API-KEY': this.configService.get('apis.notifications.key') };
  const eventData = {
    event: event.event, payload: event.payload,
    email: event.email, clientId: event.clientId, userId: event.userId,
  };
  await this.http.post(apiNotificationURL, eventData, { headers });
}
```

So the topology is:

```
mutation in drive-server-wip
  -> StorageNotificationService.fileCreated(...)
  -> NotificationService.add(...)               (EventEmitter2.emit, in-process)
  -> NotificationListener @OnEvent('notification.*')
  -> HTTP POST to apis.notifications.url        (separate microservice, not public)
  -> [closed-source notifications service]
  -> socket.io fan-out per-user
  -> drive-desktop realtime.ts subscribes
  -> processWebSocketEvent -> updateAllRemoteSync -> startSyncByCheckpoint
```

The `eventData` shape posted by the listener is **byte-identical** to the desktop's `NOTIFICATION_SCHEMA` envelope (same five keys), which is the strongest possible static-analysis evidence that the closed-source notifications service is a transparent proxy: receive HTTP from drive-server-wip, fan out via socket.io, no transformation. The schema is thus public on both endpoints; only the bridge is private.

There is **no `FILE_DELETED` or `FOLDER_DELETED` branch in the desktop schema**, even though the server emits them. This is not a bug on the desktop side — the schema's `safeParseAsync` will fail for `FILE_DELETED`, the `success === false` branch fires, and `updateAllRemoteSync()` runs anyway. The desktop deliberately does not need to know every event shape; any unrecognised payload still triggers a re-sync.

## Implication for unidrive

This is a real, useful surface. The fit with unidrive's existing architecture is excellent because **the desktop client itself uses the WebSocket as a wake signal, not a source of truth** — exactly the integration model UD-306's "if real" branch described.

Sketch (one paragraph, no implementation):

A new `org.krost.unidrive.providers.internxt.NotificationsClient` would establish a socket.io-client connection (a Kotlin client like `io.socket:socket.io-client` works) to the configured `NOTIFICATIONS_URL` with the same `auth: { token }` shape, listen on the `'event'` channel, and emit a single coarse-grained `RemoteChangeHint` to the sync engine — analogous to a coalesced timer tick — without parsing the payload as authoritative. The sync engine would consume the hint by debouncing (so a burst of 50 file mutations doesn't trigger 50 walks) and then running the existing `startSyncByCheckpoint` equivalent path (`InternxtProvider.delta()` against the last checkpoint). No code change to the delta walker, no payload-based shortcuts, no risk of stale-cache divergence. The `clientId` loopback elision is straightforward: emit our own `clientId` (e.g. `'drive-desktop-unidrive'`) on every mutation HTTP call to drive-server-wip, then drop frames whose `clientId` matches. **DEFER post-MVP**: the existing offset-walked checkpoint walk (UD-352a + UD-303 tiebreak) is sufficient for correctness; this is purely a latency / battery / Internxt-load optimisation. File a follow-up implementation ticket but do not slot it into the MVP.

## Recommended next step

**File a new follow-up implementation ticket** (e.g. `UD-XXX: Internxt WebSocket wake-signal integration`, priority **low**, effort **M**, deferred to post-MVP). Close UD-306 as resolved with this audit doc as the `resolved_by` artefact (no commit-level fix; closing on documentation outcome is fine for research-only tickets).

Do **not** close UD-306 as a duplicate of UD-370 — the conclusions are different. UD-370 disproved one channel; UD-306 surfaces a real second channel that genuinely could feed sync wake-ups.

## Maintenance / license / recency

- `internxt/drive-desktop` — MIT, default branch `main`, 209 stars, last push 2026-05-04 (today). Last commit `42ad6d7` on `main`. Actively maintained.
- `internxt/drive-server-wip` — AGPL-3.0, default branch `master`, 17 stars, last push 2026-04-30 (4 days ago). Last commit `3b12986`. Actively maintained.
- The **closed-source notifications fan-out service** is not in the public repo set. Its existence is inferred from `apis.notifications.url` config in drive-server-wip and from the `NOTIFICATIONS_URL` socket.io endpoint on drive-desktop. The schema on both ends is identical, which strongly implies pass-through.
- The closed-source `@internxt/drive-desktop-core` package referenced in the original UD-306 ticket was NOT a barrier to this audit — the message handling is fully implemented in the open `drive-desktop` repo at `src/backend/features/remote-notifications/`. The ticket's premise that the schema "lives in a closed-source package" is incorrect; only the fan-out *service* is closed.

## What couldn't be determined without runtime access

- The **literal value** of `NOTIFICATIONS_URL` in production. Build-time injection. Likely `https://notifications.internxt.com` based on Internxt's naming convention for sibling envvars but unconfirmed.
- The exact **socket.io protocol version** and namespace (default `/` vs custom). The client uses `transports: ['websocket']` only (skipping long-polling for the actual events but still using polling for `INGRESSCOOKIE` extraction during handshake).
- The **rate / debounce behaviour on the server side** — does the notifications service coalesce N mutations from the same user into one frame, or one frame per mutation? Affects how aggressive unidrive's debounce needs to be.
- **Reconnect / backoff policy** of socket.io in this configuration (defaults are okay-ish but not enumerated).
- **Token lifetime / refresh integration**: `obtainToken()` is called once at connect time; if the token expires mid-session, does the server send a `connect_error` and the client reconnect with a fresh token? Unclear from realtime.ts alone — would need to read `obtainToken` and the auth refresh path. Not a blocker for the feasibility verdict.
- Whether the closed-source fan-out service is multi-region or single-instance — affects latency expectations for European vs US users.

All five gaps are answerable with a 30-minute live capture (mitmproxy + a real Internxt account) once an implementation ticket is opened. None of them affect the verdict.

## Cross-refs

- UD-370 (CLOSED 2026-05-03) — REST `/notifications` is marketing-only.
- `docs/audits/internxt-notifications-feasibility.md` — REST audit, see-also section added there.
- drive-desktop `src/apps/main/realtime.ts` — subscription site (`io(...)` + `socket.on('event', ...)`).
- drive-desktop `src/backend/features/remote-notifications/in/process-web-socket-event.ts` — handler (calls `updateAllRemoteSync`).
- drive-desktop `src/backend/features/remote-notifications/in/process-web-socket-event.test.ts` — three-branch unit test.
- drive-desktop `src/apps/main/notification-schema.ts` — Zod schema for the message envelope.
- drive-desktop `src/core/utils/utils.ts` — `INTERNXT_CLIENT = 'drive-desktop-windows'`.
- drive-desktop `.erb/scripts/validate-process-env.ts` — `NOTIFICATIONS_URL` declared as required URL.
- drive-server-wip `src/externals/notifications/storage.notifications.service.ts` — full `StorageEvents` enum (10 event types).
- drive-server-wip `src/externals/notifications/listeners/notification.listener.ts` — `@OnEvent('notification.*')` -> HTTP POST to private fan-out service.
- drive-server-wip `src/externals/notifications/notification.service.ts` — thin `EventEmitter2.emit` wrapper.
