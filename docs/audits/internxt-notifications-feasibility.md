# UD-370 Phase 0 — Internxt `/notifications` feasibility

*2026-05-03 — pure-spec analysis, no live calls.*

## Verdict

**NO-GO.** `/notifications` is an account-level marketing/promotional channel, not a file-mutation event feed. UD-370 closed as invalid.

## Evidence

Source: `https://api.internxt.com/drive/swagger-ui-init.js`, embedded OpenAPI 3.0.0 spec.

### Endpoints

```
GET    /notifications              — Get user notifications (one-shot retrieval)
POST   /notifications              — Create a new notification
PATCH  /notifications/{id}/expire  — Mark notification expired
POST   /users/notification-token   — Register a device push token
```

### `NotificationWithStatusDto` (the GET response item)

```json
{
  "id":        "123e4567-e89b-12d3-a456-426614174000",
  "link":      "https://internxt.com/promotions/black-friday",
  "message":   "Black Friday Sale - 50% off all plans!",
  "expiresAt": "2024-12-31T23:59:59.000Z",
  "createdAt": "..."
}
```

The example payload is literally a Black Friday promotional banner. The schema has **no file UUID, no folder UUID, no event type, no resource path, no mutation kind**. There is no field that could carry "file X was modified at T".

### `RegisterNotificationTokenDto` (the push-token registration)

```json
{
  "token": "0f8fad5b-d9cb-469f-a165-70867728950e",
  "type":  "macos"
}
```

`type` enumerates device platforms (`macos`, presumably also `ios`/`android`/`windows`). This is a **push channel for marketing pings to end-user devices** — same shape as APNs/FCM device-token registration.

## Conclusion

The hypothesis behind UD-370 — that `/notifications` could feed real-time file-mutation events to drive `delta()` invalidation — is **disproved by the spec itself**. No further reconnaissance needed (cross-checking the desktop client would only confirm what the schema already states).

**Implication for delta() efficiency:** Internxt does **not** publish a public real-time change feed. The cost-of-`delta()` debate (UD-362 sync-path narrowing, UD-363 `/folders/{uuid}/tree` atomic walk) remains the right path forward — there is no shortcut via push.

If Internxt ever ships a real change feed (likely under a new tag like `webhooks` or `events`), this audit can be reopened. As of the 2026-05-03 spec snapshot, no such surface exists.

## Audit-doc cross-reference

The capability matrix in [internxt-api-vs-spi.md](internxt-api-vs-spi.md) line 148 noted `/notifications` as `◯ Available-unused` with the speculation *"Could feed real-time invalidation instead of polling delta()."* That speculation is now retired; the line should be updated to reflect the marketing-only purpose so future agents don't re-file the same hypothesis.
