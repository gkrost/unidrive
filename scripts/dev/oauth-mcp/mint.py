"""mint.py — print ONLY an access token to stdout, nothing else.

Silent-by-design so callers can do:

    export UNIDRIVE_TEST_ACCESS_TOKEN=$(python mint.py onedrive-test)

without polluting the shell output. Any errors go to stderr; exit code
signals success/failure. On success stdout contains exactly one line:
the access token, no trailing newline after it? — actually a trailing
newline is fine, `$(...)` strips it.

For chatty diagnostics use smoke.py instead.
"""

from __future__ import annotations

import sys

from graph_client import DEFAULT_CLIENT_ID, GraphAuthError, refresh
from token_store import load


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: python mint.py <profile>", file=sys.stderr)
        return 2

    profile = sys.argv[1]
    try:
        stored = load(profile)
    except FileNotFoundError as e:
        print(f"mint.py: {e}", file=sys.stderr)
        return 1

    client_id = stored.client_id or DEFAULT_CLIENT_ID
    scope = stored.scope
    if not scope:
        print("mint.py: token.json has no `scope` field — cannot replay.", file=sys.stderr)
        return 1

    try:
        result = refresh(
            client_id=client_id,
            refresh_token=stored.refresh_token,
            scope=scope,
        )
    except GraphAuthError as e:
        print(f"mint.py (refresh): {e}", file=sys.stderr)
        return 1

    sys.stdout.write(result.access_token)
    return 0


if __name__ == "__main__":
    sys.exit(main())
