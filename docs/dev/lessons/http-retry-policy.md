# HTTP retry policy — canonical matrix (UD-207)

## What

unidrive's per-provider HTTP clients (OneDrive `GraphApiService`, WebDAV
`WebDavApiService`, plus the rclone / Internxt / S3 / SFTP / HiDrive adapters
under UD-330) all retry transient failures using the same canonical matrix.
The pacing / circuit-breaker layer that backs every provider's retry loop
lives in
[`HttpRetryBudget.kt`](../../../core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt).

| Class                     | HTTP status / signal           | Retry? | Cap                    | Backoff               | Notes              |
|---------------------------|--------------------------------|--------|------------------------|-----------------------|--------------------|
| Success                   | 2xx                            | n/a    | n/a                    | n/a                   |                    |
| Permanent client error    | 4xx (except 408, 429)          | No     | n/a                    | n/a                   | Fail fast          |
| Timeout                   | 408                            | Yes    | budget.maxRetries      | exp + jitter          |                    |
| Throttle                  | 429                            | Yes    | budget.maxRetries      | honor Retry-After (cap = budget.maxRetryAfter) | UD-232 alignment |
| Server error              | 5xx                            | Yes    | budget.maxRetries      | exp + jitter          |                    |
| Network error             | (no status)                    | Yes    | budget.maxRetries      | exp + jitter          |                    |
| Unknown exception         | (caller-classified `Unknown`)  | Yes    | min(3, budget.maxRetries) | exp + jitter       | The lower cap is deliberate |

`exp + jitter` = exponential backoff with full jitter, base = 1 s,
cap = `budget.maxRetryAfter`.

## Why this exact shape

The shape is lifted verbatim from drive-desktop's `client-wrapper.service.ts`,
which is the cleanest retry-policy doc-comment across the three Internxt
repos. It encodes three pieces of operational knowledge unidrive earned the
hard way: (1) 4xx is terminal — retrying a 401 / 403 / 404 / 409 / 410 burns
budget without any chance of success and masks the real bug, with the two
narrow exceptions of 408 (timeout) and 429 (throttle) that are *transport*
signals not application errors; (2) 5xx and network errors are always
worth another attempt because the server or the wire has a transient state,
which UD-712 confirmed under live OneDrive load (a single retry recovered
~97 % of mid-body connection-reset cases); (3) "unknown" exceptions get a
lower cap on purpose — if we don't recognise the failure class we cannot
distinguish "transient" from "permanent + slow", so we cap at 3 to bound
worst-case wait without giving up immediately.

## How to add an exception (you can't without filing a ticket)

The matrix is the single source of truth across all unidrive providers.
Per-provider divergence is sometimes legitimate (WebDAV also retries 425
Too Early because DSM's mod_dav emits it under load), but every divergence
must be (a) filed as its own backlog ticket, (b) reflected in the provider's
`docs/providers/*-robustness.md`, and (c) explained in the per-provider
KDoc on the `withRetry` helper. Silent rewrites of `HttpRetryBudget` or any
provider's retry loop without a corresponding update to this matrix are
forbidden — they create the "what's the retry policy?" confusion this
ticket exists to eliminate.

If you find yourself wanting a new row or a different cap for one
provider, open a ticket referencing this doc and UD-228 / UD-232 / UD-330
before changing code.

## Cross-references

- [`HttpRetryBudget.kt`](../../../core/app/core/src/main/kotlin/org/krost/unidrive/http/HttpRetryBudget.kt) — matrix table is duplicated in the class KDoc.
- [`HttpRetryBudgetMatrixTest.kt`](../../../core/app/core/src/test/kotlin/org/krost/unidrive/http/HttpRetryBudgetMatrixTest.kt) — locks each row as a contract.
- UD-228 — the budget itself.
- UD-232 — throttle / Retry-After alignment.
- UD-330 — cross-provider rollout of the shared classifier.
- drive-desktop `src/infra/drive-server-wip/in/client-wrapper.service.ts` — quotation source for the matrix.
