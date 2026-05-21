---
name: unidrive-log-anomalies
description: Scan the live unidrive daemon log for 429 storms, retry-exhaustion, permanent failures, TLS handshake errors, malformed JWT events, and other anomalies that the user probably wants flagged without asking. Run this at session start if the sync daemon is currently running, and whenever the user reports something going wrong with sync. Wraps scripts/dev/log-watch.sh with a curated follow-up for each surfaced issue.
---

# unidrive-log-anomalies

Proactive counterpart to `scripts/dev/log-watch.sh`. Where the shell script
is an on-demand query, this skill is the "should I look at the log right
now?" decision plus the follow-up plan for whatever shows up.

## When to invoke

**At session start**, unconditionally (the check is cheap — one summary pass
over the log):

```bash
scripts/dev/log-watch.sh --summary
```

Also invoke when:
- the user reports "sync is slow" / "X isn't happening" / "it's stuck";
- a commit lands that touches `core/providers/onedrive/` or `core/app/sync/`;
- a long-running sync is in flight and you're about to edit related code;
- you're about to start or stop the daemon.

## What to do with the output

The summary tallies WARN, ERROR, 429 throttle hits, permanent failures
(attempt 5/5), malformed JWT events, and TLS handshake terminations. Use
this rubric:

| Signal | Follow-up |
|---|---|
| `429 throttle hits` >> historical baseline | Check whether `UD-232 throttle storm` lines are firing (`grep "UD-232 throttle storm"`). If not, ThrottleBudget may not be engaged on the running jar — verify the deployed jar's mtime vs the HEAD commit. |
| `attempt 5/5 (fatal)` > 0 | Files gave up mid-retry. File location: `grep -B1 "attempt 5/5" ...`. If `retryAfterSeconds` > 300, server asked for longer than the 300s cap allows — UD-232b follow-up territory. |
| `JWT malformed` > 0 | [UD-312](docs/backlog/BACKLOG.md) is live. Capture the timestamp + surrounding context and append to the ticket. |
| `TLS handshake` > 0 | [UD-311](docs/backlog/BACKLOG.md). Same — capture. |
| `ERROR` lines about `Authentication` | Token refresh race (UD-310 era). Cross-check against most recent auth commit. |

## After the summary

If anything non-zero beyond baseline:

```bash
scripts/dev/log-watch.sh --anomalies | head -30
```

Read the first 30 anomaly lines for concrete addresses. Then decide: fix,
file a ticket via `python scripts/dev/backlog.py file ...`, or append a
note to an existing ticket.

## Baseline for comparison (2026-04-19 session snapshot)

| Metric | 346 GB / 130k-item sync run |
|---|---|
| WARN lines | 5,680 / 8,666 total lines |
| 429 throttle hits | 2,083 |
| Download failed | 3,327 |
| attempt 5/5 (fatal) | 60 |
| JWT malformed | 1 |
| TLS handshake | 2 |

Numbers substantially higher than these on a sync of similar scope → regression.

## Never run

- Don't follow (`tail -F`) the log from the skill; the user's session has
  limited attention bandwidth. Follow is reserved for `scripts/dev/log-watch.sh`
  with `Monitor` when the user explicitly wants streaming.
- Don't `tail` the log full raw. The summary and anomalies filters are
  there to keep context usage bounded.
