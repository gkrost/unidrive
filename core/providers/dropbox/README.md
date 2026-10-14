# Dropbox provider

Talks to the Dropbox HTTP API v2 directly over Ktor (no SDK) through `DropboxApi`. That class handles the bearer token, a forced refresh with one retry on 401, Dropbox's own wait on 429 and 503 (`Retry-After`, or `retry_after` in the body) on the shared `HttpRetryBudget`, and the `Dropbox-API-Arg` header, with every character above 0x7E escaped as `\uXXXX`. Behaviour notes taken from Cyberduck's `dropbox/` module are behaviour only. Cyberduck is GPL-3.0, and no code was taken from it.

## Profile

```toml
[providers.dropbox]
type = "dropbox"
sync_root = "~/Dropbox"
client_id = "<app key>"
```

unidrive does not ship a Dropbox app yet, so a profile names one:
- **App settings:** in the Dropbox App Console, create an app with scoped access (full Dropbox).
- **Permissions:** `files.metadata.read/write`, `files.content.read/write`, `sharing.read/write` and `account_info.read`.
- **Redirect URI:** `http://localhost:8080/callback`.
- **App key:** goes in `client_id` (the OAuth client id is what Dropbox calls the app key), or in `UNIDRIVE_DROPBOX_CLIENT_ID`. The app is a public client, so there is no secret.
- **Port:** `UNIDRIVE_DROPBOX_OAUTH_PORT` moves the callback to another port, which must be registered as well.

## Sign-in

`unidrive -p <profile> auth` opens the browser for the authorization-code grant:
- **The grant:** PKCE with `token_access_type=offline`. The browser comes back to the loopback callback.
- **The token:** `token.json` in the profile folder keeps a short-lived access token and the refresh token. A refresh answer carries no new refresh token, so the old one is kept.
- **Device code:** Dropbox has none.
- **Not covered:** `auth begin` / `auth complete` (the front-end flow that runs in separate processes) is not supported yet. The loopback listener cannot outlive `auth begin`.

## API surface used

| Endpoint | Used by | Notes |
|---|---|---|
| `users/get_current_account` | first call | When `root_namespace_id` is not `home_namespace_id` (a team space), every later call sends `Dropbox-API-Path-Root` with the root, so team folders are visible next to the member folder. |
| `files/list_folder` (+ `/continue`) | `delta`, `listChildren` | Recursive for `delta`. `include_deleted` is always true, because a cursor keeps its arguments and one made without deletions never reports one. `include_non_downloadable_files` is false. Pages of a listing that began without a cursor leave out deleted entries, which are history, not changes. A `reset` answer is `DeltaCursorExpiredException`. |
| `files/list_folder/get_latest_cursor` | `deltaFromLatest` | The fast bootstrap. |
| `files/list_folder/longpoll` (notify host, no token) | `onRemoteChangeHint` | Polls the cursor of the last finished walk. After a hint it waits for the next walk's cursor (60 s at most) and honours `backoff`. |
| `files/get_metadata` | `getMetadata`, `verifyItemExists`, the write guard | |
| `files/download` | `download`, `downloadById` | `id:` addressing. The bytes go to a staging sibling, moved over the target only when complete. `path/not_found` is `PermanentDownloadFailureException`. |
| `files/upload`; `upload_session/start`, `append_v2`, `finish` | `upload` | One call up to 128 MiB, above that 16 MiB appends. `client_modified` is the local mtime in whole seconds. |
| `files/delete_v2` | `delete`, `deleteById` | `path_lookup/not_found` counts as done. |
| `files/create_folder_v2` | `createFolder` | `path/conflict/folder` answers the existing folder. |
| `files/move_v2` | `move`, `moveById` | A rename that changes only the case goes through a temporary name. |
| `users/get_space_usage` | `quota` | |
| `sharing/create_shared_link_with_settings`, `list_shared_links`, `revoke_shared_link` | shares | Expiry and password need a paid plan. The share id is the link URL. |

## Conditional writes

The engine's write token for a file is the content hash it last saw (`remote_hash`). A guarded upload or delete:
- reads the file's metadata;
- refuses with `RemoteConflictException` when the hash moved on or the file is gone (a guarded delete of a gone file is done);
- otherwise writes against that metadata's `rev`: `update` mode with `strict_conflict` for an upload, `parent_rev` for a delete. A change that lands in between is refused by Dropbox itself, and that refusal is a conflict too.

## Hash

`HashAlgorithm.DropboxContentHash`: SHA-256 over the SHA-256s of 4 MiB blocks, as lowercase hex. `HashVerifier.computeDropboxContentHash` was written from Dropbox's published definition.

## Names and case

Dropbox compares paths without case:
- **Keys and display:** `path_lower` is the key. An item's display path is its parent's display path plus its own `name`, because `path_display` is only right in its last component.
- **Deleted entries** carry no id. They get the id last seen at their path in this process, or an empty one.
- **Names Dropbox never syncs** are refused before any request with `PermanentUploadFailureException`: Office lock and temp files (`~$*`, `.~*`, `~*.tmp`), `desktop.ini`, `thumbs.db` and `.DS_Store`.

## Not yet

- **Resumable upload sessions:** an interrupted session starts over.
- **Front-end sign-in:** `auth begin` / `auth complete`.
- **Live tests:** a test against a real account.
