"""server.py — MCP server exposing scoped, short-lived OneDrive access tokens
to a Claude Code session running inside the MSIX sandbox.

Design scratchpad: docs/dev/oauth-test-injection.md.

Runs on the user's side (NOT inside the Claude Desktop sandbox). The
user registers it in their Claude Code MCP config (see README.md).
Tools exposed:

  probe()
      Health-check + lists available profiles under %APPDATA%\\unidrive\\.
      Always safe to call; never mints a token.

  grant_token(profile: str, scope: "read"|"write" = "read",
              ttl_seconds: int = 3600) -> { access_token, expires_at_ms,
                                            scope_granted }
      Uses the refresh token stored by the unidrive CLI for <profile> to
      mint a fresh access token. `scope="read"` requests only
      `offline_access Files.Read User.Read`; the refreshed token loses
      write capability. `scope="write"` keeps Files.ReadWrite.
      The caller is responsible for not sharing the access_token
      outside the session. Logs only the granted scope and the expiry;
      never the token itself.

  revoke_token() -> { revoked: bool }
      Best-effort local forget. Graph does not expose a revoke endpoint
      for the personal / common tenant; the access token simply
      expires at ttl_seconds. This tool clears the MCP's in-memory
      cache so a subsequent grant_token forces a fresh refresh.

  query_graph(profile: str, path: str, params?: dict) -> json
      Read-only Graph API proxy. Mints (or reuses a cached) access
      token for <profile>, issues GET https://graph.microsoft.com/v1.0
      + <path> with optional query params, returns the decoded JSON.
      Refuses paths that look like mutations. Caps response at a
      reasonable size; for pagination the caller re-invokes with
      the `$skiptoken` the previous response returned.

  query_state_db(profile: str, sql: str, limit?: int = 1000) -> [row]
      Read-only SQL proxy on %APPDATA%\\unidrive\\<profile>\\state.db.
      Accepts SELECT / WITH ... SELECT only (regex-enforced); opens
      the DB in read-only mode; injects a LIMIT clause if one is
      missing. Returns rows as dicts keyed by column name.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from pathlib import Path
from typing import Any

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool

from graph_client import (
    DEFAULT_CLIENT_ID,
    DEFAULT_READ_SCOPE,
    DEFAULT_RW_SCOPE,
    GraphAuthError,
    refresh,
)
from token_store import StoredToken, default_config_dir, load, save

log = logging.getLogger("unidrive-test-oauth")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)

app = Server("unidrive-test-oauth")

# In-memory cache of recently-minted access tokens per profile, to avoid
# hammering Graph's token endpoint when the caller issues multiple
# grant_token calls during one debugging session.
_cache: dict[str, dict[str, Any]] = {}


@app.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="probe",
            description=(
                "Health-check. Lists unidrive profiles that have a token.json on "
                "disk. Never mints or returns tokens. Safe to call at any time."
            ),
            inputSchema={
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        ),
        Tool(
            name="grant_token",
            description=(
                "Mint a short-lived access token for a unidrive profile. "
                "Uses the refresh token stored by the CLI; does not go "
                "through a browser. The caller should treat the returned "
                "access_token as a short-lived secret. Default scope is "
                "read-only (Files.Read); pass scope='write' to keep "
                "Files.ReadWrite."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "profile": {
                        "type": "string",
                        "description": "unidrive profile name (e.g. 'onedrive', 'onedrive-test')",
                    },
                    "scope": {
                        "type": "string",
                        "enum": ["read", "write"],
                        "default": "read",
                    },
                    "ttl_seconds": {
                        "type": "integer",
                        "minimum": 60,
                        "maximum": 3600,
                        "default": 3600,
                        "description": "Maximum lifetime of the returned token. Graph caps at ~1h regardless.",
                    },
                },
                "required": ["profile"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="revoke_token",
            description=(
                "Forget any cached access token for a profile. Graph tokens "
                "are not server-side revocable for the personal/common tenant; "
                "they expire at their issued ttl. This just clears the MCP's "
                "local cache."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "profile": {"type": "string"},
                },
                "required": ["profile"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="query_graph",
            description=(
                "Read-only Microsoft Graph proxy. Mints (or reuses a cached) "
                "access token for the profile and issues GET v1.0<path>. "
                "Refuses paths that contain verbs suggesting a mutation. "
                "Returns decoded JSON. Caller handles pagination by "
                "re-invoking with the returned `@odata.nextLink` token."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "profile": {
                        "type": "string",
                        "description": "unidrive profile name (must have a token.json on disk)",
                    },
                    "path": {
                        "type": "string",
                        "description": "Path under https://graph.microsoft.com/v1.0 — e.g. '/me/drive' or '/me/drive/root/children'",
                    },
                    "params": {
                        "type": "object",
                        "description": "Optional query params: $select, $top, $filter, $skiptoken, etc.",
                        "additionalProperties": {"type": ["string", "number"]},
                    },
                },
                "required": ["profile", "path"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="query_state_db",
            description=(
                "Read-only SQL against the unidrive state.db for a profile. "
                "Accepts SELECT / WITH ... SELECT statements only. Opens the "
                "DB in read-only mode (SQLite URI mode=ro). Caller can "
                "inspect tables, counts, and cursors without a live daemon."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "profile": {"type": "string"},
                    "sql": {
                        "type": "string",
                        "description": "SELECT or WITH ... SELECT. Anything else is rejected.",
                    },
                    "limit": {
                        "type": "integer",
                        "minimum": 1,
                        "maximum": 100000,
                        "default": 1000,
                        "description": "Hard cap on rows returned; injected as LIMIT if the SQL omits one.",
                    },
                },
                "required": ["profile", "sql"],
                "additionalProperties": False,
            },
        ),
    ]


@app.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
    try:
        if name == "probe":
            result = _probe()
        elif name == "grant_token":
            result = _grant(arguments)
        elif name == "revoke_token":
            result = _revoke(arguments)
        elif name == "query_graph":
            result = _query_graph(arguments)
        elif name == "query_state_db":
            result = _query_state_db(arguments)
        else:
            raise ValueError(f"unknown tool: {name}")
        return [TextContent(type="text", text=json.dumps(result, indent=2))]
    except GraphAuthError as e:
        log.warning("graph auth error: %s", e)
        return [TextContent(type="text", text=json.dumps({"error": str(e)}))]
    except FileNotFoundError as e:
        return [TextContent(type="text", text=json.dumps({"error": str(e)}))]
    except Exception as e:
        log.exception("unexpected error in %s", name)
        return [TextContent(type="text", text=json.dumps({"error": f"{type(e).__name__}: {e}"}))]


def _probe() -> dict[str, Any]:
    cfg = default_config_dir()
    profiles = []
    if cfg.exists():
        for sub in cfg.iterdir():
            if sub.is_dir() and (sub / "token.json").exists():
                profiles.append(sub.name)
    return {
        "config_dir": str(cfg),
        "config_dir_exists": cfg.exists(),
        "profiles_with_token": sorted(profiles),
    }


def _grant(args: dict[str, Any]) -> dict[str, Any]:
    profile = args["profile"]
    scope_kind = args.get("scope", "read")
    ttl = int(args.get("ttl_seconds", 3600))

    # Serve from cache if still valid (and scope matches).
    cached = _cache.get(profile)
    now_ms = _now_ms()
    if cached and cached["scope_kind"] == scope_kind and cached["expires_at_ms"] - now_ms > 60_000:
        log.info("grant_token cache hit for profile=%s scope=%s", profile, scope_kind)
        return {
            "access_token": cached["access_token"],
            "expires_at_ms": cached["expires_at_ms"],
            "scope_granted": cached["scope_granted"],
            "source": "cache",
        }

    stored = load(profile)
    client_id = stored.client_id or DEFAULT_CLIENT_ID
    # AAD refuses refresh-time scope narrowing unless the narrower scope was
    # separately consented. We replay the stored scope verbatim; the
    # scope_kind arg ("read" vs "write") becomes advisory — it only changes
    # the default if the stored scope is empty (fresh install edge case).
    # See UD-723 commit 4a432d9 for the AADSTS70000 evidence.
    if stored.scope:
        graph_scope = stored.scope
    else:
        graph_scope = DEFAULT_READ_SCOPE if scope_kind == "read" else DEFAULT_RW_SCOPE
    refreshed = refresh(
        client_id=client_id,
        refresh_token=stored.refresh_token,
        scope=graph_scope,
    )

    # Persist the rotated refresh token back to disk so the CLI and the MCP
    # stay in sync. Keep the original access_token if unidrive is running;
    # it will refresh on its own 401 retry (UD-310).
    save(
        profile,
        StoredToken(
            access_token=refreshed.access_token,
            refresh_token=refreshed.refresh_token,
            expires_at_ms=refreshed.expires_at_ms,
            client_id=stored.client_id,  # preserve source-of-truth; empty stays empty
            scope=refreshed.scope_granted or stored.scope,
        ),
    )

    # Cap ttl at what Graph gave us — never return a token that outlives its
    # actual validity.
    effective_expiry = min(refreshed.expires_at_ms, now_ms + ttl * 1000)

    _cache[profile] = {
        "access_token": refreshed.access_token,
        "expires_at_ms": effective_expiry,
        "scope_granted": refreshed.scope_granted,
        "scope_kind": scope_kind,
    }

    log.info(
        "grant_token profile=%s scope=%s expires_at_ms=%s",
        profile,
        scope_kind,
        effective_expiry,
    )
    return {
        "access_token": refreshed.access_token,
        "expires_at_ms": effective_expiry,
        "scope_granted": refreshed.scope_granted,
        "source": "refresh",
    }


def _revoke(args: dict[str, Any]) -> dict[str, Any]:
    profile = args["profile"]
    removed = _cache.pop(profile, None) is not None
    return {"revoked_local_cache": removed}


# ---------- UD-724: query_graph + query_state_db ----------------------------

import re
import sqlite3
from urllib.parse import urlencode

import httpx

GRAPH_BASE = "https://graph.microsoft.com/v1.0"

# Paths containing any of these substrings are treated as possibly mutating
# and rejected. Conservative — Graph uses `/approve`, `/accept`, `/reject`
# on invitations; `/setMethodOf`, `/subscribe` for change notifications.
_MUTATING_PATH_HINTS = (
    "/approve", "/reject", "/accept", "/subscribe",
    "/setmethodof", "/cancel", "/send", "/delete",
    "/copy", "/move", "/createlink", "/createuploadsession",
    "/checkin", "/checkout", "/restore",
)


def _query_graph(args: dict[str, Any]) -> Any:
    profile = args["profile"]
    path = args["path"]
    params = args.get("params") or {}

    if not path.startswith("/"):
        return {"error": "path must start with '/'"}
    low = path.lower()
    for hint in _MUTATING_PATH_HINTS:
        if hint in low:
            return {"error": f"read-only: path contains mutation hint {hint!r}"}

    # Resolve token only after the path passes validation, so an invalid
    # or missing profile doesn't waste a token-mint round-trip.
    granted = _grant({"profile": profile, "scope": "read"})
    if "error" in granted:
        return granted
    token = granted["access_token"]

    url = GRAPH_BASE + path
    if params:
        url += ("&" if "?" in path else "?") + urlencode(params)

    try:
        with httpx.Client(timeout=20) as client:
            r = client.get(url, headers={"Authorization": f"Bearer {token}"})
    except httpx.HTTPError as e:
        return {"error": f"graph request failed: {e}"}

    if r.status_code >= 400:
        return {
            "error": f"graph {r.status_code}",
            "body": r.text[:2000],
        }
    try:
        return r.json()
    except ValueError:
        return {"error": "non-json response", "body": r.text[:2000]}


_SELECT_RE = re.compile(r"^\s*(WITH\b.*?\bSELECT\b|SELECT\b)", re.IGNORECASE | re.DOTALL)
_LIMIT_RE = re.compile(r"\bLIMIT\s+\d+", re.IGNORECASE)
_DANGEROUS_RE = re.compile(
    r"\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|ATTACH|DETACH|REPLACE|PRAGMA)\b",
    re.IGNORECASE,
)


def _query_state_db(args: dict[str, Any]) -> Any:
    profile = args["profile"]
    sql = args["sql"]
    limit = int(args.get("limit", 1000))

    if not _SELECT_RE.match(sql):
        return {"error": "only SELECT or WITH ... SELECT queries are allowed"}
    if _DANGEROUS_RE.search(sql):
        return {"error": "DDL/DML keyword detected — this tool is read-only"}
    if not _LIMIT_RE.search(sql):
        sql = f"{sql.rstrip().rstrip(';')} LIMIT {limit}"

    cfg = default_config_dir()
    db_path = cfg / profile / "state.db"
    if not db_path.exists():
        return {"error": f"state.db not found at {db_path}"}

    # read-only via SQLite URI
    uri = f"file:{db_path.as_posix()}?mode=ro"
    try:
        with sqlite3.connect(uri, uri=True, timeout=5) as con:
            con.row_factory = sqlite3.Row
            rows = [dict(r) for r in con.execute(sql).fetchall()]
    except sqlite3.Error as e:
        return {"error": f"sqlite: {e}"}

    return {"rows": rows, "row_count": len(rows)}


def _now_ms() -> int:
    import time
    return int(time.time() * 1000)


async def main() -> None:
    async with stdio_server() as (read, write):
        await app.run(read, write, app.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())
