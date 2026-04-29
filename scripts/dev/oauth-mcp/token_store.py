r"""token_store.py — read/write unidrive's existing token.json.

We deliberately reuse the unidrive CLI's token file instead of storing a
second refresh token under a new path. The user already authenticated
via the CLI's device-code flow; the MCP wraps that existing auth state
so no parallel "test tenant" setup is required.

Layout (from core/providers/onedrive/src/main/kotlin/.../TokenManager.kt):

    %APPDATA%\unidrive\<profile>\token.json

    {
      "accessToken": "<opaque>",
      "refreshToken": "<opaque>",
      "expiresAt": <epoch-ms>,
      "clientId": "<azure-app-registration-id>",
      ...
    }

Never log tokens. Never persist tokens outside this file.
"""

from __future__ import annotations

import json
import os
import stat
from pathlib import Path
from typing import Optional

import dataclasses


@dataclasses.dataclass
class StoredToken:
    access_token: str
    refresh_token: str
    expires_at_ms: int
    # The CLI's token.json does not carry the Azure app id — it's a compile-
    # time constant in OneDriveConfig. We keep this field for the MCP but
    # populate it from graph_client.DEFAULT_CLIENT_ID when loading. Callers
    # that persist (via save()) should pass whatever client_id they used.
    client_id: str = ""
    # The scope the refresh token was minted with. Microsoft AAD requires
    # any refresh-grant scope request to be a strict substring-subset of
    # the original consent, and scope names do not decompose (requesting
    # `Files.Read` against a `Files.ReadWrite.All` consent fails with
    # invalid_grant). Read-only reduction therefore requires separate
    # up-front consent, not refresh-time narrowing. Store the minted scope
    # so the MCP can replay it exactly.
    scope: str = ""


def default_config_dir() -> Path:
    r"""Resolve %APPDATA%\unidrive on Windows, $HOME/.config/unidrive elsewhere."""
    appdata = os.environ.get("APPDATA")
    if appdata:
        return Path(appdata) / "unidrive"
    return Path.home() / ".config" / "unidrive"


def profile_token_path(profile: str, config_dir: Optional[Path] = None) -> Path:
    return (config_dir or default_config_dir()) / profile / "token.json"


def load(profile: str, config_dir: Optional[Path] = None) -> StoredToken:
    path = profile_token_path(profile, config_dir)
    if not path.exists():
        raise FileNotFoundError(
            f"no token.json for profile '{profile}' at {path}. "
            f"Run `unidrive -p {profile} auth` first."
        )
    raw = json.loads(path.read_text(encoding="utf-8"))
    # `clientId` is absent from the CLI's token.json (compile-time constant).
    # Callers should fall back to graph_client.DEFAULT_CLIENT_ID when the
    # returned value is empty. Keeping the attribute on the dataclass lets
    # test fixtures inject a different app id without reopening the file.
    return StoredToken(
        access_token=raw["accessToken"],
        refresh_token=raw["refreshToken"],
        expires_at_ms=int(raw["expiresAt"]),
        client_id=raw.get("clientId", ""),
        scope=raw.get("scope", ""),
    )


def save(profile: str, token: StoredToken, config_dir: Optional[Path] = None) -> None:
    """Persist a refreshed token atomically. Match unidrive's field names so
    the CLI can continue using the same file."""
    path = profile_token_path(profile, config_dir)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    payload = {
        "accessToken": token.access_token,
        "refreshToken": token.refresh_token,
        "expiresAt": token.expires_at_ms,
        "clientId": token.client_id,
        "scope": token.scope,
        "tokenType": "Bearer",  # match CLI's shape; the value is fixed in v2 flows
    }
    tmp.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    os.replace(tmp, path)
    # Best-effort: restrict to owner read/write. On Windows NTFS this is a
    # no-op at the ACL level; set_posix_mode still works as signalling.
    try:
        os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        pass
