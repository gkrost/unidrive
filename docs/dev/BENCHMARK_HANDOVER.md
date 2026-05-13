# Benchmark Handover Spec — For Qwen / OpenCode

**Goal:** Run frequent, small benchmark tests across all authenticated providers. Maximize test frequency, minimize time per run. Build up a longitudinal dataset for the performance observatory.

## Strategy: Small but frequent

Instead of full 5-iteration × 5-size runs, use:
- **2 iterations** (enough for a median)
- **2 sizes** (1KB for latency, 10MB for throughput)
- Run across **all authenticated providers** sequentially
- Target: complete a full round in **< 5 minutes**

## Commands

```bash
# Quick benchmark — all providers, 2 iterations, 2 sizes
java --enable-native-access=ALL-UNNAMED -jar cli/build/libs/unidrive-0.0.1.jar \
  -p <profile> provider benchmark --iterations 2 --sizes 1KB,10MB

# Run for each authenticated profile:
for p in onedrive-gmail internxt-test scaleway-par koofr-webdav b2-unencrypted b2-encrypted sftp-krost sftp-localhost synology-webdav; do
  echo "=== $p ==="
  java --enable-native-access=ALL-UNNAMED -jar cli/build/libs/unidrive-0.0.1.jar \
    -p "$p" provider benchmark --iterations 2 --sizes 1KB,10MB 2>&1 | tail -5
  echo ""
done

# View ranking after
java --enable-native-access=ALL-UNNAMED -jar cli/build/libs/unidrive-0.0.1.jar \
  -p onedrive-gmail provider benchmark --results

# Export JSON for analysis
java --enable-native-access=ALL-UNNAMED -jar cli/build/libs/unidrive-0.0.1.jar \
  -p onedrive-gmail provider benchmark --results --json > observatory-$(date +%Y%m%d).json
```

## What to look for

After each run, check:
1. **Error rate** — any provider showing errors? Note the provider and error message.
2. **Throughput anomalies** — >20% deviation from previous run means network conditions changed or provider had an issue.
3. **TTFB spikes** — sudden increase suggests DNS/routing changes.
4. **Throttling** — if ERR% > 0, check if it's 429 (rate limited) or a real error.

## When to run

- **Daily** at different times (morning, afternoon, evening) to capture time-of-day variation
- **After provider changes** (new account, changed plan, different region)
- **After UniDrive code changes** (verify no performance regression)

## Authenticated profiles available

| Profile | Type | Location | Notes |
|---------|------|----------|-------|
| onedrive-gmail | onedrive | USA/EU | Microsoft Graph API |
| internxt-test | internxt | EU (Spain) | Client-side encrypted |
| scaleway-par | s3 | Paris, FR | 75GB free trial (expires Jul 2026) |
| koofr-webdav | webdav | Slovenia | 10GB free forever |
| b2-unencrypted | s3 | USA | Backblaze, no SSE |
| b2-encrypted | s3 | USA | Backblaze, SSE enabled |
| sftp-krost | sftp | DE (Hetzner) | Port 22222, key auth |
| sftp-localhost | sftp | Loopback | Protocol baseline |
| synology-webdav | webdav | LAN | DS418play, HTTP 5005 |

## Build before running

```bash
cd /home/gernot/dev/git/unidrive
./gradlew :cli:shadowJar -q
```

## Results database

SQLite at `~/.local/share/unidrive/benchmarks.db`. Each run appends — never deletes old data. The database grows but slowly (~1KB per run × 9 providers = ~9KB per full round).

## Reporting findings

After running, note:
- Date/time
- Any errors or anomalies
- Any provider that significantly changed performance
- Network conditions (if known)

Add findings to `docs/BACKLOG.md` if they reveal bugs, or to the observatory dataset if they're performance data.
