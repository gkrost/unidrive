"""graph_client.py — minimal Microsoft Graph OAuth refresh wrapper.

We do not do the device-code dance here. The assumption is that unidrive
already has a refresh token on disk (acquired via the CLI's `auth`
command); this module exchanges that refresh token for a short-lived
access token, optionally with reduced scope.

Graph endpoints:
- POST https://login.microsoftonline.com/common/oauth2/v2.0/token
  grant_type=refresh_token, refresh_token=..., client_id=..., scope=...

Scope reduction notes (see docs/dev/oauth-test-injection.md):
- Microsoft personal-account refresh tokens are "aggregate" and will
  honour a reduced scope request as long as the reduced scope is a
  subset of the original consent. Work/School tenants may reject
  scope reduction; handle the error by re-requesting with full scope.
- `offline_access` must stay in scope for the refreshed token to itself
  be refreshable.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Optional

import httpx

# Matches OneDriveConfig.DEFAULT_AUTH_ENDPOINT. The CLI uses the
# `/consumers/` tenant (personal Microsoft accounts, not work/school);
# `/common/` tokens are scoped differently and refresh grants between the
# two tenants are not fungible — attempting to redeem a `/consumers/`-minted
# refresh token against `/common/` returns AADSTS70000 "invalid_grant /
# unauthorized or expired scope" even when the scope list is a strict
# subset of the original consent.
TOKEN_ENDPOINT = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"

# The unidrive CLI uses a hardcoded Azure app registration id (see
# core/providers/onedrive/src/main/kotlin/.../OneDriveConfig.kt:
# `DEFAULT_APP_ID = "aa961a73-f4ac-41d9-8150-0a18f330ff6c"`). The CLI does
# not persist this into token.json — it's a compile-time constant. The MCP
# must therefore carry its own copy. If the CLI ever rotates the app id,
# update this constant in lockstep with OneDriveConfig.
DEFAULT_CLIENT_ID = "aa961a73-f4ac-41d9-8150-0a18f330ff6c"

# Default read-only scope. Caller can override.
DEFAULT_READ_SCOPE = "offline_access Files.Read User.Read"
DEFAULT_RW_SCOPE = "offline_access Files.ReadWrite User.Read"


@dataclass
class RefreshResult:
    access_token: str
    refresh_token: str  # possibly rotated
    expires_at_ms: int
    scope_granted: str


class GraphAuthError(RuntimeError):
    """Raised on Graph-level auth failures. Message is safe to log; never
    includes the refresh token itself."""


def refresh(
    client_id: str,
    refresh_token: str,
    scope: str = DEFAULT_READ_SCOPE,
    timeout_s: float = 15.0,
) -> RefreshResult:
    """Exchange a refresh token for a short-lived access token."""
    if not refresh_token:
        raise GraphAuthError("empty refresh_token — need to re-auth via unidrive CLI")
    if not client_id:
        raise GraphAuthError("empty client_id — token.json is missing the app registration id")

    data = {
        "grant_type": "refresh_token",
        "refresh_token": refresh_token,
        "client_id": client_id,
        "scope": scope,
    }
    t0 = time.time()
    with httpx.Client(timeout=timeout_s) as client:
        resp = client.post(TOKEN_ENDPOINT, data=data)
    if resp.status_code != 200:
        # Body is small JSON; safe to include for diagnostics (no secrets in
        # Graph error responses).
        raise GraphAuthError(
            f"Graph token endpoint returned {resp.status_code}: {resp.text[:400]}"
        )
    payload = resp.json()
    access_token = payload.get("access_token", "")
    new_refresh = payload.get("refresh_token", refresh_token)  # may or may not rotate
    expires_in = int(payload.get("expires_in", 3600))
    scope_granted = payload.get("scope", scope)

    # UD-312 guard: Graph occasionally emits an empty or truncated
    # access_token on 2xx under load. Catch that here, don't propagate to
    # the caller. We deliberately do NOT assert JWS compact form — personal
    # Microsoft accounts return OPAQUE bearer tokens (no dots, ~1.5k chars,
    # usually prefixed `EwB…`), while work/school accounts return JWTs.
    # Both are valid; only "empty or tiny" is the failure mode we guard.
    if len(access_token) < 32:
        raise GraphAuthError(
            f"Graph returned a 2xx with a suspiciously short access_token "
            f"(length={len(access_token)}). Likely the empty-token-on-2xx bug "
            "flagged in UD-312. Retry."
        )

    expires_at_ms = int((t0 + expires_in) * 1000)
    return RefreshResult(
        access_token=access_token,
        refresh_token=new_refresh,
        expires_at_ms=expires_at_ms,
        scope_granted=scope_granted,
    )
