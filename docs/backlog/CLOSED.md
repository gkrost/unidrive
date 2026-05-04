# Closed items — append-only archive

> When closing a backlog item, **move** the full frontmatter block from [BACKLOG.md](BACKLOG.md) to the bottom of this file and add `status: closed`, `closed: YYYY-MM-DD`, and optional `resolved_by:`. Never edit entries above the latest. See [AGENT-SYNC.md](../AGENT-SYNC.md).

<!-- First closed entry will be appended below this marker. -->

---
id: UD-229
title: Research — Microsoft Graph API feature table: what unidrive uses vs ignores
category: docs
priority: low
effort: M
code_refs:
  - core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive
status: closed
closed: 2026-05-04
resolved_by: commit 50e0e91. Doc was largely already-written from a prior pass; refreshed all stale GraphApiService.kt:NNN line refs (file grew ~140 lines), fixed throttle-budget constants (5 min / 15 min, not 2 min / 10 min as previously stated), added See-also block linking ADR-0005 + SPECS.md per ticket's companion-cross-reference ask. Two tables (A: 10 used + secondary list, B: 10 v0.2.0+ candidates with S/M/L tiers + deferred list) intact.
opened: 2026-04-19
---
Produce two tables in `docs/providers/onedrive-graph-coverage.md`:

**Table A — Top 10 Graph features unidrive DOES use** — e.g. `/me/drive/root/delta` (delta enum), `/me/drive/items/{id}/content` (download), upload session, webhook subscriptions, `/createLink` sharing, etc. Per row: endpoint, why unidrive calls it, file:line of the caller, known quirks (UD-307-style ZWJ rejections, CDN 429 patterns, Retry-After header habits).

**Table B — Top 10 Graph features unidrive DOES NOT use** — candidates worth evaluating for v0.2.0+. Ideas:
  * **Drive-level search** (`/me/drive/search(q='...')`) — would let `unidrive search` skip full enumeration.
  * **Item preview** (`/items/{id}/preview`) — tray hover thumbnails / MCP previews.
  * **OneNote scope** — currently invisible to unidrive.
  * **Shared items `/me/drive/sharedWithMe`** — partially wired via UD-301 deltaWithShared, but explicit listings would help UX.
  * **Sensitivity labels / retention** — compliance features relevant for enterprise pitches.
  * **Thumbnail sizes** — cheaper than download for tray previews.
  * **Item versioning** `/items/{id}/versions` — unidrive has its own `.unidrive-versions/` scheme; overlap.
  * **Long-running operations** (`/async-monitor`) — we probably want these for large moves.
  * **Audit logs** (enterprise tenants) — relevant for UD-113 audit trail.
  * **Batch requests** (`/$batch`) — up to 20 ops in one HTTP call, reduces throttle pressure.

Each row: endpoint, what it does, cost (RPS impact, throttle class), unidrive-side integration cost (S/M/L), priority.

Companion cross-reference to ADR-0005 (Provider capability contract).
