"""smoke.py — verify the OAuth refresh flow WITHOUT the MCP harness.

Run this first on the user's native machine to confirm the token.json
is readable and a refresh succeeds. If this works, the MCP will work.
If this fails, debug here (simpler environment) rather than inside the
MCP stdio loop.

    python scripts/dev/oauth-mcp/smoke.py <profile>

Prints: profile, scope granted, new expiry (epoch-ms), and the first
16 chars of the access_token as a sanity check. Never prints the
refresh token or the full access token.
"""

from __future__ import annotations

import sys
import time

from graph_client import DEFAULT_CLIENT_ID, DEFAULT_READ_SCOPE, GraphAuthError, refresh
from token_store import load


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: python smoke.py <profile>", file=sys.stderr)
        return 2

    profile = sys.argv[1]
    try:
        stored = load(profile)
    except FileNotFoundError as e:
        print(f"FAIL: {e}")
        return 1

    client_id = stored.client_id or DEFAULT_CLIENT_ID
    # AAD refuses refresh-time scope narrowing unless the narrower scope was
    # also consented. Replay the stored scope verbatim; scope_granted in the
    # result is what the caller should trust.
    requested_scope = stored.scope or DEFAULT_READ_SCOPE
    print(f"profile:          {profile}")
    print(f"client_id:        {client_id[:8]}…  {'(fallback)' if not stored.client_id else '(from token.json)'}")
    print(f"has_refresh:      {bool(stored.refresh_token)}")
    print(f"stored_scope:     {requested_scope}")
    print(f"current_expiry:   {stored.expires_at_ms} "
          f"({'expired' if stored.expires_at_ms < time.time()*1000 else 'valid'})")

    try:
        result = refresh(
            client_id=client_id,
            refresh_token=stored.refresh_token,
            scope=requested_scope,
        )
    except GraphAuthError as e:
        print(f"FAIL (refresh): {e}")
        return 1

    print(f"scope_granted:    {result.scope_granted}")
    print(f"new_expiry:       {result.expires_at_ms}")
    print(f"access_token[:16]: {result.access_token[:16]}…")
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
