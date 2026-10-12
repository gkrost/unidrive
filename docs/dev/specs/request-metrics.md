# Request-attempt metrics

What the engine records about its provider HTTP requests, where it keeps it, and how a front-end reads it
(`metrics.query`). Scope: the Ktor clients of the OneDrive and Internxt providers, in a **mount** daemon. A CLI
run and a mirror daemon record nothing and pay nothing.

The log lines of the request-id plugin (`→ req=…`, `← req=…`) are unchanged. The metrics are a second consumer of
the same plugin, kept in the profile's `telemetry.db`.

## What is recorded: one row per request attempt

An *attempt* is one request handed to the HTTP engine. A redirect hop, a provider-level retry and a token-refresh
replay are each their own attempt.

| Field | Unit | Definition |
|---|---|---|
| `ts` | epoch ms | When the attempt was handed to the engine. |
| `profile`, `provider` | text | The profile and the provider id (`onedrive`, `internxt`). |
| `method` | text | The HTTP method. |
| `endpoint_class` | text | Method and templated path, for example `GET /drive/folders/content/{id}/files`. See below. |
| `outcome` | text | The class the attempt ended in. See below. |
| `headers_ms` | ms or null | From handing the request to the engine until the response **headers** arrived. Null when no response arrived. This is header time, not first-body-byte time; the engine gives no earlier or later boundary. |
| `total_ms` | ms | Until the response body was consumed, or the call failed or was cancelled. Includes `headers_ms`. A response that is never consumed records nothing until it is. |
| `bytes_up` | bytes or null | The request body's declared length. Null for a streamed body without one, or when no response arrived. |
| `bytes_down` | bytes or null | The response's declared `Content-Length`. Null for a streamed or chunked body, and for an attempt whose body did not arrive whole. Ktor has no public byte counter and teeing the body to count it would cost a copy, so this is a declared length, not a count. |
| `retry_no` | count | How many failed attempts of the same method and URL ended within the previous ten minutes without a success in between. 0 for a first attempt. Providers retry above the HTTP client, so this is inferred from the request, not passed down. |
| `throttled` | flag | The answer was a 429 or carried a `Retry-After` header. |

Not recorded and reported unavailable: DNS, connect and TLS phase timings (the engine does not expose them).
No URL, query value, token, name, path or account data is stored.

### Endpoint classes

`EndpointClassifier` builds the class from an allow-list per provider: a path segment survives only if it is a
word of the API (`drive`, `items`, `children`, ...); every other segment is `{id}`. Graph's name addressing
(`root:/Folder/file:/content`) becomes `root:{path}:/content`. The query string is never read. A host that is not
the provider's API (a pre-signed upload or download URL, a tenant's own host) is `external` and its path is not
templated. A new endpoint therefore shows up as `{id}` until its words are added, never as a leaked identifier.

### Outcomes

`2xx`, `3xx`, `401_403`, `4xx` (other), `429`, `5xx`, `other` (1xx and unknown statuses), and the transport
classes `timeout`, `reset`, `tls`, `dns`, `connect`, `io` (anything else), `cancelled`. A reset or timeout after the
headers is a transport class with a non-null `headers_ms`: the attempt did not deliver what its status promised.
A 503 with `Retry-After` is `5xx` with `throttled`.

"Request failure share" computed from these is exactly that: a share of attempts. It is not provider outage
duration, and a period with no requests is not downtime.

## Roll-ups

One row per minute (UTC, by the attempt's start) and endpoint class: `attempts`, per-outcome counts (which add up
to `attempts`), `throttled`, `retries` (attempts with `retry_no` > 0), header and total p50/p95, and the sums of
`bytes_up` and `bytes_down` over the attempts that know them. Percentiles are nearest-rank: the value at rank
ceil(q * n) of the sorted values. A header percentile is null when no attempt of the minute got a response; a byte
sum is null when no attempt knew the value. Nothing is ever a zero standing in for unknown.

The writer thread recomputes the minutes that received attempts every five seconds (idempotent, from the raw
rows) and again when a query asks, so the open minute is included. The roll-up of a minute that is not over reads
`partial: true`. A crash leaves minutes unrolled; the next open rolls them up.

## Storage, retention and the shared budget

The tables `request_attempts` and `request_rollups` live in the profile's `telemetry.db`, schema step 2 (a file at
step 1 gains them and keeps its `transfers`, which this feature never touches). Writing uses the history's pattern:
a bounded queue (20 000 attempts), one writer thread, batches in one transaction. A full queue or a failed write
drops the attempts and counts them; the request never waits. A gap is marked in the file (`metrics_incomplete_since`).

| Data | Kept | Why |
|---|---|---|
| Raw attempts | 24 hours | The bulkiest data, and the roll-ups carry what a front-end shows. |
| Per-minute roll-ups | 30 days | About 100 bytes per active minute and class. |
| Transfer history | 90 days (existing) | Unchanged. |

All of it shares the history's byte budget (200 MB by default, `HistoryConfig.maxBytes`). When the file is over the
budget the order of loss is: raw attempts (oldest tenth at a time), then roll-ups, then transfer rows. Metrics give
way to the transfer history, never the other way round. The age windows and the budget are applied at start,
every ten minutes and whenever the history's own retention runs.

## `metrics.query` (READ)

Request: `{"verb":"metrics.query", "from_ms":?, "to_ms":?, "endpoint_class":?, "limit":?}`.

- `[from_ms, to_ms)` in epoch ms; default the last hour including the open minute.
- `endpoint_class`: one class exactly as `endpoint_classes` lists it. Absent: all.
- `limit`: 1 to 5000, default 1000, oldest first.
- Refusals: `invalid_range`, `invalid_limit`, `invalid_endpoint_class`; `unavailable` when the file cannot be read.

Reply:

```
{"ok":true,"recording":true,"from_ms":…,"to_ms":…,"step_ms":60000,
 "has_data":true,"truncated":false,"incomplete_since_ms":null,"oldest_minute_ms":…,
 "endpoint_classes":["GET /drive/files", …],
 "series":[{"minute_ms":…,"partial":false,"provider":"internxt","endpoint_class":"GET /drive/files",
            "attempts":4,"outcomes":{"2xx":2,"429":1,"reset":1},"throttled":1,"retries":1,
            "headers_ms":{"p50":40,"p95":60},"total_ms":{"p50":120,"p95":500},
            "bytes_up":null,"bytes_down":5000}]}
```

- `recording:false`: this daemon records nothing (mirror mode, or the history could not be opened). The series is
  empty.
- `has_data:false` with `series:[]`: no attempt was recorded in the range. That is not "zero requests" and not
  "healthy"; `oldest_minute_ms` says where the data begins (null: the file has none).
- `incomplete_since_ms`: attempts were lost from this time on (null: no gap known).
- Percentiles are per minute and cannot be merged across minutes; a client that aggregates weights by `attempts`
  and says so.

Fixtures: `ipc-contract/metrics.query.ndjson` (replayed against a live daemon: the empty and refusal shapes) and
`ipc-contract/sessions/metrics-query-seeded.ndjson` (the data-bearing shape, replayed against a handler over a
seeded file).
