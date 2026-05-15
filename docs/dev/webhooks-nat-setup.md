# OneDrive Webhooks — Lifecycle & NAT Setup

> Salvage of the pre-greenfield `docs/WEBHOOKS.md`, rewritten against
> the current code. Source files cited inline.

Webhook ("subscription") support is OneDrive-only. S3, SFTP, WebDAV,
and Rclone do not have push-notification protocols in scope. See
[SPECS.md §6 Webhook flow](../SPECS.md#6-webhook-flow) for the
intent-level summary; this doc covers the operator-facing detail —
config keys, tunneling recipes, and the renewal scheduler.

## Quick status

- **Auto-renewal is implemented** (UD-303, closed). The
  `SubscriptionRenewalScheduler` fires exactly one `renew` per
  `(expiry − 24h)` window per profile; no more per-cycle Graph hits.
- **NAT traversal is operator-provided.** unidrive does not embed a
  tunnel — you bring ngrok, cloudflared, serveo, or a reverse proxy.
- **Live tests are out-of-tree.** They need a real Microsoft tenant +
  a public URL; not part of any CI run.

## Config keys

Per-profile `config.toml` (see
[`OneDriveConfig.kt:6-15`](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveConfig.kt#L6)
and the `RawProvider` block in
[`SyncConfig.kt:211-214`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt#L211)):

| Key | Type | Default | Effect |
|-----|------|---------|--------|
| `webhook` | bool | `false` | Master switch. When true and `--watch` is set, daemon arms `SubscriptionRenewalScheduler`. |
| `webhook_url` | string | none | Public HTTPS URL Graph will POST notifications to. Mandatory when `webhook = true`; absent → `ensureSubscription` no-ops. |
| `webhook_port` | int | `8081` | Local port for the listener (if you run one in-process). |

There is no `webhook = true` without `webhook_url` — the renewer logs
the gap and skips the Graph call rather than half-arming the loop.

## Lifecycle (current code)

The CLI's `SyncCommand` is the orchestrator:

1. On `unidrive sync --watch`, if `rpConfig.webhook == true`, build a
   `SubscriptionRenewalScheduler` bound to the watch-loop scope —
   [`SyncCommand.kt:404-413`](../../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt#L404).
2. Call `ensureSubscription()` once at start, then again after each
   poll cycle —
   [`SyncCommand.kt:417-445`](../../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt#L417).
3. `ensureSubscription()` —
   [`SyncCommand.kt:541-597`](../../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt#L541)
   — has four branches:
   - **Fast path:** `scheduler.isScheduledAndValid(profile)` → return
     immediately (no Graph call, no store read).
   - **Still valid (>24h left):** arm the scheduler, return.
   - **Expires in 1–24h:** call `renewSubscription`. On success, save
     the new expiry and arm the scheduler. On failure, delete the
     stale record and fall through to create.
   - **Expired or missing:** call `createSubscription`. Save the
     server-returned expiry, arm the scheduler.

The scheduler itself is small —
[`SubscriptionRenewalScheduler.kt:31-120`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SubscriptionRenewalScheduler.kt#L31):

- `schedule(profile)` cancels any prior job, computes
  `fireAt = expiresAt − 24h`, launches a `delay(...)` coroutine in
  the caller's scope.
- `isScheduledAndValid(profile)` returns true iff the job is active
  AND the persisted expiry has more than 24h left.
- `cancel(profile)` / `cancelAll()` for shutdown.

State is persisted in `SubscriptionStore` (SQLite, table
`webhook_subscriptions`, columns `profile / sub_id / expires_at`) —
[`SubscriptionStore.kt:30-39`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SubscriptionStore.kt#L30).

## Graph API calls

Three methods on `GraphApiService`
([`GraphApiService.kt:909-968`](../../core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/GraphApiService.kt#L909)):

- `createSubscription(notificationUrl, expirationDateTime)` →
  `POST /v1.0/subscriptions` with body
  `{changeType: "updated", notificationUrl, resource: "/me/drive/root", expirationDateTime}`.
- `renewSubscription(subscriptionId, expirationDateTime)` →
  `PATCH /v1.0/subscriptions/{id}` with body `{expirationDateTime}`.
- `deleteSubscription(subscriptionId)` →
  `DELETE /v1.0/subscriptions/{id}`.

The Graph hard cap is **4230 minutes (~3 days)** per subscription;
the code requests `now + 3 days` and reads the actual expiry from the
server response.

## NAT setup recipes

`notificationUrl` must be reachable from Microsoft's network. If your
daemon runs on a NAT-ed laptop or homelab box, pick one:

### ngrok (quick)

```
ngrok http 8081
# copy the https://*.ngrok-free.app URL into webhook_url
```

Free tier rotates the hostname on every restart — re-edit
`config.toml` and let `ensureSubscription` rebuild on the next cycle.

### cloudflared quick tunnel

```
cloudflared tunnel --url http://localhost:8081
```

Same flavor as ngrok; ephemeral `*.trycloudflare.com` hostname.

### cloudflared named tunnel (stable)

```
cloudflared tunnel login
cloudflared tunnel create unidrive-webhooks
cloudflared tunnel route dns unidrive-webhooks webhooks.example.com
# edit ~/.cloudflared/config.yml to map ingress to localhost:8081
cloudflared tunnel run unidrive-webhooks
```

Hostname is stable across restarts. Good fit for an always-on
homelab.

### serveo.net

```
ssh -R 80:localhost:8081 serveo.net
# serveo prints the assigned URL
```

No account needed; free; ephemeral.

### Production reverse proxy

A VPS with a public IP fronting your daemon through nginx /
Caddy / Traefik over TLS. `webhook_url` is your stable HTTPS hostname.

## Security notes

- Graph requires HTTPS. HTTP `notificationUrl` is rejected at
  `createSubscription` time.
- Graph posts an initial **validation token** to the URL during
  subscription creation. The current code does not stand up its own
  HTTP server to echo that token back — operators using bare-tunnel
  setups need a tiny listener (or a tunnel that auto-echoes) until
  the in-process listener lands.
- `webhook_url` is the only secret that need not be a secret — Graph
  doesn't authenticate inbound POSTs cryptographically. Treat the
  receiving endpoint as untrusted: verify `clientState` if you wire
  one through, and treat the notification as a hint to re-delta
  rather than an authoritative event payload.

## Troubleshooting

- **`Failed to create subscription: ... validation request timed
  out`** — Graph couldn't reach `notificationUrl`. Check the tunnel
  is up; `curl -v $WEBHOOK_URL` from a non-local network.
- **`Webhook subscription renewal failed`** WARN line, then a fresh
  create succeeds — Graph returned 404 for the prior `sub_id`. The
  code already handles this: the stale row is deleted and a new
  subscription is created on the same cycle. No operator action.
- **Renewer fires but Graph 401s** — the OAuth token expired between
  the scheduler arming the delay and the delay completing. The
  renewer logs `Scheduled webhook renewal failed for profile {}:`
  and falls through to the next per-cycle `ensureSubscription`,
  which goes through the token refresh path first.

## See also

- [SPECS.md §6 Webhook flow](../SPECS.md#6-webhook-flow) — normative summary.
- [`SubscriptionRenewalScheduler.kt`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SubscriptionRenewalScheduler.kt) — the per-profile renewal coroutine.
- [`SubscriptionStore.kt`](../../core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SubscriptionStore.kt) — SQLite persistence schema.
- UD-303 (CLOSED) — the auto-renewal implementation ticket.
