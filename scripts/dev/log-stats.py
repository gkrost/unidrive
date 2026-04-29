#!/usr/bin/env python3
"""
log-stats.py — derive throughput / latency / concurrency stats from
unidrive logs.

Key observations baked in:
  * WebDavApiService only logs the START of each Upload (no completion
    line, no elapsed). We approximate per-upload duration by the gap to
    the NEXT Upload start on the same worker thread. Last upload per
    thread is dropped from the per-upload distribution.
  * RequestId fires only for the OneDrive Graph stack and gives a
    proper "→ req=ID GET ..." / "← req=ID STATUS (Nms)" pair. That
    yields real TTFB-like latency.

Usage: pass any number of log files as args (or globs in the shell).
"""
import re
import sys
import statistics
from collections import defaultdict, Counter
from datetime import datetime
from pathlib import Path


TS_RE = re.compile(r'^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ')
THREAD_RE = re.compile(r'\[([A-Za-z0-9_-]+(?:-worker-\d+)?)\]')
UPLOAD_RE = re.compile(
    r'\[(DefaultDispatcher-worker-\d+|main)\] '
    r'o\.k\.unidrive\.webdav\.WebDavApiService - Upload: (.+?) \((\d+) bytes\)$'
)
REQ_OPEN_RE = re.compile(
    r'org\.krost\.unidrive\.http\.RequestId - → req=([0-9a-f]+) (\w+) (https?://\S+)'
)
REQ_CLOSE_RE = re.compile(
    r'org\.krost\.unidrive\.http\.RequestId - ← req=([0-9a-f]+) (\d{3})\b.*?\((\d+)ms\)'
)
MDC_TUPLE_RE = re.compile(r'\[([0-9a-f?]+)\] \[([a-z*]+)\] \[([0-9a-f?-]+)\]')


def parse_ts(line):
    m = TS_RE.match(line)
    if not m:
        return None
    return datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f")


def percentiles(xs, ps=(0.5, 0.9, 0.95, 0.99, 1.0)):
    if not xs:
        return {p: None for p in ps}
    xs_sorted = sorted(xs)
    out = {}
    n = len(xs_sorted)
    for p in ps:
        idx = min(int(p * n), n - 1)
        out[p] = xs_sorted[idx]
    return out


def fmt_bytes(n):
    if n is None:
        return "—"
    for u in ("B", "KiB", "MiB", "GiB", "TiB"):
        if abs(n) < 1024:
            return f"{n:.2f} {u}"
        n /= 1024
    return f"{n:.2f} PiB"


def fmt_rate(bps):
    if bps is None:
        return "—"
    return fmt_bytes(bps) + "/s"


def main(paths):
    uploads_by_thread = defaultdict(list)   # thread -> [(ts, bytes, path)]
    upload_size_total = 0
    upload_count = 0

    req_open = {}                           # req_id -> (ts, method, host)
    req_completions = []                    # list of (ts, status, ms, host)
    status_counter = Counter()

    minute_bucket_bytes = defaultdict(int)  # YYYY-MM-DD HH:MM -> bytes (uploads)
    minute_bucket_count = defaultdict(int)
    mdc_missing = 0
    total_lines = 0
    first_ts = None
    last_ts = None

    log_levels = Counter()

    for p in paths:
        with open(p, 'r', encoding='utf-8', errors='replace') as f:
            for line in f:
                total_lines += 1
                ts = parse_ts(line)
                if ts is None:
                    continue
                if first_ts is None or ts < first_ts:
                    first_ts = ts
                if last_ts is None or ts > last_ts:
                    last_ts = ts

                # log level
                lvl_m = re.search(r' (TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\s+\[', line)
                if lvl_m:
                    log_levels[lvl_m.group(1)] += 1

                # MDC missing
                mdc = MDC_TUPLE_RE.search(line)
                if mdc and ('?' in mdc.group(1) or '?' in mdc.group(3)):
                    mdc_missing += 1

                # WebDAV uploads
                m = UPLOAD_RE.search(line)
                if m:
                    thread, path, sz = m.group(1), m.group(2), int(m.group(3))
                    uploads_by_thread[thread].append((ts, sz, path))
                    upload_size_total += sz
                    upload_count += 1
                    bucket = ts.strftime("%Y-%m-%d %H:%M")
                    minute_bucket_bytes[bucket] += sz
                    minute_bucket_count[bucket] += 1
                    continue

                # Graph request open
                m = REQ_OPEN_RE.search(line)
                if m:
                    rid = m.group(1)
                    host = m.group(3).split('/')[2]
                    req_open[rid] = (ts, m.group(2), host)
                    continue

                # Graph request close
                m = REQ_CLOSE_RE.search(line)
                if m:
                    rid, status, ms = m.group(1), int(m.group(2)), int(m.group(3))
                    status_counter[status] += 1
                    open_info = req_open.pop(rid, None)
                    host = open_info[2] if open_info else "?"
                    req_completions.append((ts, status, ms, host))
                    continue

    # ---- Per-upload duration via per-thread gap ----
    # On a single coroutine worker, the next "Upload:" start tells us
    # the previous upload (PUT request + server-side write) is done and
    # the next chunk has been pulled off the queue.
    per_upload = []   # (size, gap_seconds, mibps)
    for thread, items in uploads_by_thread.items():
        items.sort()
        for i in range(len(items) - 1):
            ts0, sz0, _ = items[i]
            ts1, _, _ = items[i + 1]
            gap = (ts1 - ts0).total_seconds()
            if gap <= 0:
                continue
            per_upload.append((sz0, gap, sz0 / gap if gap > 0 else 0))

    # ---- Concurrency over time: count of distinct active threads ----
    # An "active interval" for thread on upload i runs [items[i].ts, items[i+1].ts).
    # Active workers at sample time = #threads with active interval covering it.
    # For a pragmatic snapshot: per-minute MAX concurrency = max distinct
    # workers seen with any Upload event in that minute.
    workers_in_minute = defaultdict(set)
    for thread, items in uploads_by_thread.items():
        for ts, _, _ in items:
            workers_in_minute[ts.strftime("%Y-%m-%d %H:%M")].add(thread)
    max_workers_per_min = {m: len(s) for m, s in workers_in_minute.items()}

    # ---- Output ----
    print(f"=== files: {len(paths)}; total lines: {total_lines:,} ===")
    print(f"window: {first_ts} → {last_ts}  ({(last_ts - first_ts).total_seconds() / 60:.1f} min)")
    print(f"log levels: {dict(log_levels)}")
    print(f"MDC-missing lines: {mdc_missing:,}  ({100 * mdc_missing / max(total_lines, 1):.1f}% of all lines)")
    print()

    # Upload aggregate
    print(f"=== WebDAV uploads (start events) ===")
    print(f"  count: {upload_count:,}")
    print(f"  total bytes: {fmt_bytes(upload_size_total)}")
    if first_ts and last_ts and upload_count:
        wall = (last_ts - first_ts).total_seconds()
        print(f"  aggregate wall throughput: {fmt_rate(upload_size_total / wall)} (over full {wall/60:.1f} min window)")

    sizes = [it[1] for items in uploads_by_thread.values() for it in items]
    sp = percentiles(sizes)
    print(f"  size percentiles: p50={fmt_bytes(sp[0.5])}  p90={fmt_bytes(sp[0.9])}  p95={fmt_bytes(sp[0.95])}  p99={fmt_bytes(sp[0.99])}  max={fmt_bytes(sp[1.0])}")

    # Per-upload derived from per-thread gaps
    if per_upload:
        gaps = [g for _, g, _ in per_upload]
        rates = [r for _, _, r in per_upload]
        gp = percentiles(gaps)
        rp = percentiles(rates)
        print()
        print(f"=== Per-upload duration (derived from per-thread gap, n={len(per_upload):,}) ===")
        print(f"  duration percentiles: p50={gp[0.5]:.3f}s  p90={gp[0.9]:.3f}s  p95={gp[0.95]:.3f}s  p99={gp[0.99]:.3f}s  max={gp[1.0]:.2f}s")
        print(f"  per-upload throughput percentiles:")
        print(f"    p50={fmt_rate(rp[0.5])}  p90={fmt_rate(rp[0.9])}  p95={fmt_rate(rp[0.95])}  p99={fmt_rate(rp[0.99])}  max={fmt_rate(rp[1.0])}")
        slow = sorted(per_upload, key=lambda x: x[2])[:5]
        fast = sorted(per_upload, key=lambda x: -x[2])[:5]
        print(f"  slowest 5 (size, gap, rate):")
        for sz, g, r in slow:
            print(f"    {fmt_bytes(sz):>12}  in {g:7.2f}s = {fmt_rate(r)}")
        print(f"  fastest 5:")
        for sz, g, r in fast:
            print(f"    {fmt_bytes(sz):>12}  in {g:7.2f}s = {fmt_rate(r)}")

    # Concurrency
    if max_workers_per_min:
        ws = sorted(max_workers_per_min.values())
        print()
        print(f"=== Per-minute parallelism (distinct WebDAV worker threads/min, n={len(max_workers_per_min)} minutes) ===")
        cp = percentiles(ws)
        print(f"  threads/min: p50={cp[0.5]}  p90={cp[0.9]}  p95={cp[0.95]}  p99={cp[0.99]}  max={cp[1.0]}")
        # max minute
        peak = max(max_workers_per_min.items(), key=lambda kv: kv[1])
        print(f"  peak minute: {peak[0]}  with {peak[1]} workers,  {minute_bucket_count[peak[0]]} uploads,  {fmt_bytes(minute_bucket_bytes[peak[0]])}")

    # Per-minute throughput peaks
    if minute_bucket_bytes:
        print()
        print(f"=== Per-minute upload throughput (top 10) ===")
        top10 = sorted(minute_bucket_bytes.items(), key=lambda kv: -kv[1])[:10]
        for m, b in top10:
            mib_per_s = b / 60
            print(f"  {m}  {fmt_bytes(b):>10}  ({fmt_rate(mib_per_s):>14}  avg over the minute)  uploads={minute_bucket_count[m]}")
        print()
        print(f"=== Per-minute upload throughput (bottom 10, where any uploads happened) ===")
        bot10 = sorted(minute_bucket_bytes.items(), key=lambda kv: kv[1])[:10]
        for m, b in bot10:
            print(f"  {m}  {fmt_bytes(b):>10}  uploads={minute_bucket_count[m]}")

    # Graph latency
    if req_completions:
        print()
        print(f"=== OneDrive Graph request latency (RequestId logger) ===")
        print(f"  count: {len(req_completions):,}")
        print(f"  status codes: {dict(status_counter)}")
        ms = [c[2] for c in req_completions]
        mp = percentiles(ms)
        print(f"  latency ms: p50={mp[0.5]}  p90={mp[0.9]}  p95={mp[0.95]}  p99={mp[0.99]}  max={mp[1.0]}")
        # per-host
        by_host = defaultdict(list)
        for _, _, ms_, host in req_completions:
            by_host[host].append(ms_)
        for host, lst in by_host.items():
            hp = percentiles(lst)
            print(f"    host {host}: n={len(lst)}  p50={hp[0.5]}ms p95={hp[0.95]}ms max={hp[1.0]}ms")

    # Pending request opens (no matching response logged)
    if req_open:
        print()
        print(f"=== Unmatched Graph requests (open without ←) ===")
        print(f"  {len(req_open)} requests have no logged response.")
        for rid, (ts, method, host) in list(req_open.items())[:10]:
            print(f"    {ts}  req={rid}  {method}  {host}")


if __name__ == "__main__":
    paths = [Path(p) for p in sys.argv[1:]]
    if not paths:
        print(__doc__)
        sys.exit(2)
    main(paths)
