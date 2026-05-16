# UniDrive MCP — operator & LLM user guide

> Canonical "how do I drive unidrive with an LLM" doc. Covers build,
> deploy, profile + auth, MCP-client registration (Claude Code,
> Claude Desktop, generic stdio), smoke test, the 23-tool / 3-resource
> surface, common workflows, and triage. Linux is the supported MVP
> ([ADR-0012](adr/0012-linux-mvp-protocol-removal.md)); Windows is
> best-effort and called out per section.
>
> Not a *developer* guide for modifying the MCP — that lives in
> [`CLAUDE.md`](../CLAUDE.md) + [`docs/AGENT-SYNC.md`](AGENT-SYNC.md).

## 0. Audience and success criteria

Two perspectives, sharply distinct:

- **Operator** (the human): builds the jar, configures a profile, runs
  OAuth, registers the MCP with a client.
- **LLM** (Claude Code, Claude Desktop, Cursor, anything that speaks
  MCP over stdio): drives the running MCP via its 23 tool verbs.

**Done** when:

- The chosen MCP client lists the unidrive server as **✓ Connected**.
- The LLM can call `unidrive_quota` and get bytes back.
- The LLM can call `unidrive_sync` and receive its synchronous summary
  payload (`downloaded`, `uploaded`, `conflicts`, …).

---

# Part A — Operator (one-time setup)

## 1. Prerequisites

- **JDK 21+.** `java -version` must show 21 or higher. Zulu, Temurin,
  GraalVM, OpenJDK all work.
- **A working unidrive checkout.** This guide assumes the project at
  `$UNIDRIVE` (i.e. you ran `git clone`).
- **Linux** (Ubuntu / Fedora / Arch / WSL2). **Windows 11** is
  supported in code but not in MVP scope — see §10.
- **One cloud-provider account.** Supported provider types: `onedrive`,
  `internxt`, `s3`, `sftp`, `webdav`, `hidrive`, `rclone` (proxies to
  any rclone backend), `localfs`. See
  [SPECS.md §3](SPECS.md#3-provider-capability-matrix) for the
  capability matrix.

## 2. Build and deploy the MCP server

From `$UNIDRIVE/core/`:

```bash
./gradlew :app:mcp:deploy
```

This one task ([`core/app/mcp/build.gradle.kts:113`](../core/app/mcp/build.gradle.kts)):

1. Produces the shadow jar
   `core/app/mcp/build/libs/unidrive-mcp-<version>.jar` (~42 MB).
2. Kills any running `java -jar unidrive-mcp-*.jar` process. Required
   on Windows — see [`docs/dev/lessons/jar-hotswap-windows.md`](dev/lessons/jar-hotswap-windows.md)
   for why hot-swapping the jar corrupts a live classloader.
3. Copies the jar to the OS-appropriate location and writes a UTF-8-forcing
   launcher script:

   | OS | Jar | Launcher |
   |---|---|---|
   | Linux | `~/.local/lib/unidrive/unidrive-mcp-<v>.jar` | `~/.local/bin/unidrive-mcp` |
   | Windows | `%LOCALAPPDATA%\unidrive\unidrive-mcp-<v>.jar` | `%USERPROFILE%\.local\bin\unidrive-mcp.cmd` |

4. The launcher pins `-Xmx6g` (6 GiB JVM heap cap, sized for the worst-case
   relocate / scale pass) and forces UTF-8 stdio (UD-258 — required because
   JSON-RPC payloads carry unicode filenames / display names).

Make sure `~/.local/bin` is on your `PATH`. A quick sanity probe:

```bash
echo '' | unidrive-mcp  # exits cleanly after one second; stderr shows the startup banner
```

You should see `unidrive-mcp <version> (<git-sha>) starting (built …)`.
If the build is dirty you'll also get a `Build was made from a dirty
git worktree …` WARN — harmless, just informational.

## 3. Configure a profile (one-time, per cloud account)

The MCP server is **single-profile by design** (see [SPECS.md §2.2
profile selection](SPECS.md#22-surface-b--mcp-json-rpc-20-over-stdio)).
It needs `-p <profile>` at startup; that profile must already exist in
the config TOML.

Two ways to bootstrap:

### 3.1 Via the CLI's interactive wizard (recommended for humans)

```bash
./gradlew :app:cli:deploy   # if you haven't already
unidrive profile add
```

The wizard ([`ProfileAddCommand.kt`](../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/ProfileCommand.kt))
prompts in order:

1. Provider type (numbered selection from `onedrive`, `internxt`, `s3`,
   `sftp`, `webdav`, `hidrive`, `rclone`, `localfs`).
2. Profile name (default = the type).
3. Sync root path.
4. Provider-specific credentials (SPI-driven via `factory.credentialPrompts()`).

A TTY is required — running `unidrive profile add` without an
interactive terminal errors out. Pure-script bootstrapping is best
done via the MCP tool `unidrive_profile_add` (see §7) or by editing
`config.toml` directly.

### 3.2 By editing the config TOML

Schema: [`docs/config-schema/config.example.toml`](config-schema/config.example.toml).
Persistent state for the profile lives at
`~/.config/unidrive/<profile>/` (Linux) — OAuth token, state DB,
conflict log.

## 4. Authenticate the profile

Two paths, chosen by provider:

### 4.1 Interactive (OneDrive, Internxt, Google Drive via rclone)

**Option A — via the CLI before MCP registration:**

```bash
unidrive -p onedrive-personal auth                    # browser flow (default)
unidrive -p onedrive-personal auth --device-code      # device-code flow (OneDrive)
```

The CLI's `auth` is a single, blocking command ([`AuthCommand.kt`](../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/AuthCommand.kt))
— no two-phase begin/complete. It calls the provider's
`authenticate(...)` and prints `Authenticated to <displayName>` on
success. Profile selection is the top-level `-p/--provider` flag, not
a subcommand option.

**Option B — let the LLM do it once registered.** The MCP exposes the
two-phase `unidrive_auth_begin` / `unidrive_auth_complete` pair, which
is **MCP-only** (the CLI is one-shot). Since [UD-014](backlog/CLOSED.md#ud-014)
the dance is provider-agnostic — works for any provider whose
`ProviderFactory.supportsInteractiveAuth()` returns true (see
[`AuthTool.kt:42`](../core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/AuthTool.kt)).
A typical LLM-driven flow:

1. LLM calls `unidrive_auth_begin` → gets back `continuation_handle`,
   `verification_url`, `user_code`.
2. LLM tells the user to visit the URL and enter the code.
3. LLM polls `unidrive_auth_complete(continuation_handle=…)`. Status
   moves `pending` → `ok` (token persisted to `<profile>/token.json`,
   POSIX `0600`) or `failed` (with a structured `error`).

### 4.2 Non-interactive (S3, SFTP, WebDAV, HiDrive, rclone)

Drop the secret into the profile TOML (or `~/.config/unidrive/secrets/<profile>`
for provider-specific shapes). Per-provider field set documented in
[`docs/config-schema/`](config-schema/).

### 4.3 Verify the auth worked

The CLI doesn't have an `identity` subcommand — `unidrive_identity` is
MCP-only. From the shell, the cheapest token-exercising probe is
quota:

```bash
unidrive -p onedrive-personal quota
```

A successful response (numbers in bytes) proves the token works
end-to-end. Once the MCP is registered, the same information surfaces
via the `unidrive_identity` and `unidrive_quota` tools.

## 5. Register the MCP with your LLM client

### 5.1 Claude Code

```bash
claude mcp add unidrive \
  --scope user \
  --command "$(which java)" \
  -- --enable-native-access=ALL-UNNAMED \
     -jar "$HOME/.local/lib/unidrive/unidrive-mcp-0.0.1.jar" \
     -p onedrive-personal
```

Verify:

```bash
claude mcp list | grep unidrive
# unidrive: ... - ✓ Connected
```

If you see `✗ Failed to connect`, jump to §10 — the canonical probe is
`claude --debug-file /tmp/c.log mcp get unidrive` which surfaces the
underlying JSON-RPC error.

### 5.2 Claude Desktop (Linux)

Edit `~/.config/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "unidrive": {
      "command": "/usr/lib/jvm/zulu-21/bin/java",
      "args": [
        "--enable-native-access=ALL-UNNAMED",
        "-jar", "/home/USER/.local/lib/unidrive/unidrive-mcp-0.0.1.jar",
        "-p", "onedrive-personal"
      ],
      "env": {}
    }
  }
}
```

Substitute `USER`, the jar version, and the `java` path (`which java`).
Restart Claude Desktop. The 23 tools appear in the slash-command picker.

### 5.3 Claude Desktop (Windows)

Same JSON shape, file at `%APPDATA%\Claude\claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "unidrive": {
      "command": "C:\\Program Files\\Zulu\\zulu-21\\bin\\java.exe",
      "args": [
        "--enable-native-access=ALL-UNNAMED",
        "-jar", "C:\\Users\\USER\\AppData\\Local\\unidrive\\unidrive-mcp-0.0.1.jar",
        "-p", "onedrive-personal"
      ]
    }
  }
}
```

Note the doubled backslashes (JSON-escaped). Restart Claude Desktop.

### 5.4 Any other stdio MCP client (Cursor, mcp-cli, custom)

The command line is:

```
java --enable-native-access=ALL-UNNAMED \
     -jar <path-to>/unidrive-mcp-<v>.jar \
     -p <profile>
```

The server speaks newline-delimited JSON-RPC 2.0 with MCP protocol
version `2024-11-05` but **accepts any client-announced version** since
[UD-758](backlog/CLOSED.md#ud-758); the server downgrades and emits a
WARN. Clients on the 2025-* revs (Claude Code 2.1.143+) work without
configuration.

### 5.5 One MCP registration = one profile

If you have multiple cloud accounts, register a separate MCP per
profile (e.g. `unidrive-onedrive`, `unidrive-internxt`) — each spawns
its own JVM (~120 MB RSS idle). Cross-profile orchestration happens at
the LLM level, not inside one MCP process. See [SPECS.md §2.2](SPECS.md#22-surface-b--mcp-json-rpc-20-over-stdio)
for the design rationale.

---

# Part B — LLM user (every prompt)

## 6. Smoke test

The fastest end-to-end proof, as a literal Claude prompt:

> *"Use the unidrive MCP to tell me my OneDrive storage quota."*

The LLM calls `unidrive_quota` and returns `used / total / remaining`
in bytes. Three things confirmed at once: JVM started, profile
resolved, token works, protocol-version handshake completed.

## 7. The tool surface — 23 verbs, grouped by intent

Authoritative source: [`Main.kt:36-62`](../core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Main.kt#L36).
Also catalogued in [SPECS.md §2.2](SPECS.md#22-surface-b--mcp-json-rpc-20-over-stdio).

| Group | Tool | Purpose |
|---|---|---|
| **Inspect** | `unidrive_status` | Reconciler summary: pending items, recent failures, last sync timestamp |
| | `unidrive_quota` | Bytes used / total / remaining for the active profile |
| | `unidrive_ls` | List remote folder contents (paged) |
| | `unidrive_conflicts` | Open sync conflicts requiring a decision |
| | `unidrive_identity` | userPrincipalName / email / bucket-owner — who-am-I |
| | `unidrive_versions` | Per-file version history (provider-dependent) |
| | `unidrive_profile_list` | All configured profiles in `config.toml` |
| **Mutate (data)** | `unidrive_sync` | Trigger one reconcile pass (with optional `--sync-path` filter) |
| | `unidrive_get` | Materialise a placeholder / fetch a file to local |
| | `unidrive_free` | Evict a local file back to placeholder (regain disk) |
| | `unidrive_pin` | Pin a path to "always keep local" |
| | `unidrive_share` | Create a share link (provider-specific knobs) |
| | `unidrive_relocate` | Move a sub-tree to a new local root (long-running; emits `relocate_*` events) |
| | `unidrive_trash` | Soft-delete (provider trash; reversible) |
| | `unidrive_backup` | One-shot backup snapshot |
| **Lifecycle** | `unidrive_auth_begin` | Start provider-agnostic interactive OAuth |
| | `unidrive_auth_complete` | Poll for OAuth completion |
| | `unidrive_logout` | Forget the persisted token |
| | `unidrive_profile_add` | Create a profile (config.toml stanza + state dir) |
| | `unidrive_profile_remove` | Delete a profile + its state DB |
| | `unidrive_profile_set` | Update profile settings |
| | `unidrive_config` | Read / write top-level config knobs |
| **Stream** | `unidrive_watch_events` | Poll the **separately-running** `unidrive sync` daemon's `IpcProgressReporter` events — `sync_started`, `scan_progress`, `action_progress`, `transfer_progress`, `sync_complete`, etc. (full shape in [SPECS.md §2.1](SPECS.md#21-surface-a--daemon--consumer-push-only-event-stream)). Returns `status: daemon_not_running` if there's no daemon. **Does not see** events from an MCP-invoked `unidrive_sync` — that path is synchronous and returns a summary directly (see §9.1). |

## 8. The resource surface — 3 URIs

The MCP also publishes three discoverable resources (read-only) via
the standard `resources/list` + `resources/read` MCP methods. Useful
when the LLM wants to inspect state without invoking a verb. Source:
[`Resources.kt:12`](../core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/Resources.kt#L12).

| URI | MIME | Content |
|---|---|---|
| `unidrive://config` | `application/toml` | The active `config.toml` (verbatim) |
| `unidrive://conflicts` | `application/x-ndjson` | Last 100 entries of `<profile>/conflicts.jsonl` |
| `unidrive://profiles` | `application/json` | `[{name, type, syncRoot}]` array of configured profiles |

## 9. Common LLM workflows

These are example prompts that exercise the right tool sequences. The
LLM normally figures the call graph from tool descriptions; operators
find concrete prompts useful when validating wiring end-to-end.

| Goal | Prompt shape | Tool sequence |
|---|---|---|
| "Am I out of cloud storage?" | "Quota check; warn if <10% free" | `unidrive_quota` |
| "Free 5 GB locally" | "Free the 20 largest pinned files older than 30 days" | `unidrive_ls` → `unidrive_free` (loop) |
| "Resolve all conflicts" | "List conflicts, take the newest-mtime side for each" | `unidrive_conflicts` → `unidrive_sync` |
| "Make this folder a share link" | "Share `~/cloud/onedrive/Reports/Q3` read-only" | `unidrive_share` |
| "Track a long sync" | "Run a full sync and tell me when it's done" | `unidrive_sync` (blocks; returns summary on completion). To see per-action progress live, start `unidrive -p <profile> sync --watch` in a separate shell and call `unidrive_watch_events` against that daemon. |
| "Onboard a new account" | "Add a profile, walk me through auth, then initial sync" | `unidrive_profile_add` → `unidrive_auth_begin` → wait → `unidrive_auth_complete` → `unidrive_sync` → `unidrive_watch_events` |
| "Why did sync fail last night?" | "Show recent conflicts and the last sync error" | `unidrive_conflicts` + read `unidrive://conflicts` resource |

## 9.1 Error semantics the LLM should expect

- **Per-tool errors come back as `result.content[0].text` with
  `isError: true`** — not as a JSON-RPC error frame. The LLM should
  read the text, decide, and try a recovery action (re-auth, narrower
  sync-path, ask the user).
- **The `profile` arg is validated, not routed.** Passing
  `"profile": "<other>"` to a tool returns a JSON-RPC `-32602
  INVALID_PARAMS` error with structured `error.data` containing
  `requestedProfile`, `activeProfile`, `configuredProfiles`,
  `supportedProviderTypes` — the LLM can read `error.data` to
  reformulate. Routing to a different profile requires a separate MCP
  registration (one profile = one process; see §5.5).
- **`unidrive_sync` is synchronous.** The MCP runs
  `engine.syncOnce(...)` inside `runBlocking` ([`SyncTool.kt:98`](../core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/SyncTool.kt))
  and only returns once the Gather → Reconcile → Apply cycle has
  finished. The response payload is the summary
  (`downloaded`, `uploaded`, `conflicts`, `totalActions`, `durationMs`).
  The LLM should expect a tool-call duration in the tens of seconds to
  many minutes depending on the workload — the JSON-RPC `id` will
  resolve when the sync completes, not when it begins.
- **`unidrive_watch_events` watches the separate daemon, not the
  MCP-invoked sync.** If you want live per-action progress, start a
  separate daemon (`unidrive -p <profile> sync --watch` in another
  shell) — the MCP can then surface its `IpcProgressReporter` events
  via `unidrive_watch_events`. With no daemon running, the tool
  returns `status: daemon_not_running` and an empty event list.
- **Token-refresh failures surface as 401-class tool errors.** Recovery
  path is `unidrive_auth_begin` again; the persisted token is
  replaced atomically.

---

# Part C — Operator triage

## 10. Troubleshooting

| Symptom | Probe | Fix |
|---|---|---|
| `claude mcp list` says `✗ Failed to connect` | `claude --debug-file /tmp/c.log mcp get unidrive` and read `/tmp/c.log` | The real JSON-RPC error is in the debug log. Pattern documented in [`docs/dev/lessons/mcp-protocol-version-negotiation.md`](dev/lessons/mcp-protocol-version-negotiation.md) (the UD-758 trap — server hard-rejecting newer protocol versions). |
| MCP starts but every tool errors `Profile mismatch: ...` | The `-p` value at registration time vs the `profile` arg in tool calls — see error's `error.data.configuredProfiles` for the canonical list | Re-register with the correct profile name, or register a second MCP under a different name for the other profile |
| Sync hangs / no events | `bash scripts/dev/log-watch.sh --anomalies` — tails the daemon log and surfaces 429 storms, malformed JWTs, retry exhaustion, TLS handshake flakes | Skill: `unidrive-log-anomalies`, or see [`CLAUDE.md`](../CLAUDE.md) §"Before you read unidrive.log" |
| JVM won't restart after redeploy | A previous instance held the jar — Windows classloader corruption | `:app:mcp:deploy` kills running instances automatically (UD-712). If you bypassed it: `Get-Process java \| Where { $_.CommandLine -like '*unidrive-mcp*' } \| Stop-Process` (Windows) / `pkill -f unidrive-mcp` (Linux). Full rationale in [`docs/dev/lessons/jar-hotswap-windows.md`](dev/lessons/jar-hotswap-windows.md). |
| Token expired during a long sync | Tool errors with `401` / `refresh_token expired` | `unidrive_auth_begin` again; in-flight sync resumes from the snapshot DB on the next `unidrive_sync` |
| Want to see what the MCP itself is logging | `tail -F ~/.local/share/unidrive/unidrive-mcp.log` (Linux) or `%LOCALAPPDATA%\unidrive\unidrive-mcp.log` (Windows) | Rolls at 10 MB, keeps 5 days / 50 MB cap — see [`logback.xml`](../core/app/mcp/src/main/resources/logback.xml) |

### 10.1 Advanced: Docker MCP integration harness

For deeper verification of the MCP wire surface, the project ships a
Docker test harness that drives the shadow jar end-to-end against a
seeded `localfs` profile — `docker-compose.mcp.yml` ([SPECS.md §7.2](SPECS.md#72-integration-tests),
[UD-815](backlog/CLOSED.md#ud-815)). 9 tests, ~30 s, requires only
`docker compose`. Run from `core/`:

```bash
cd core/docker
docker compose -f docker-compose.mcp.yml up --build --abort-on-container-exit
```

## 11. Constraints and gotchas

- **One profile per MCP instance.** Cross-profile copies need
  orchestration at the LLM level — there's no cross-profile verb
  inside a single MCP. See §5.5.
- **Internxt streaming decrypt is still in flight.** Large-file
  downloads buffer to disk; expect `unidrive_get` on a 10 GB Internxt
  file to need ~10 GB free in `$TMPDIR`. Tracked under [UD-300..307](backlog/BACKLOG.md)
  ([UD-300](backlog/BACKLOG.md#ud-300) is the streaming-decrypt
  anchor).
- **Windows is supported in code but not MVP.** [ADR-0012](adr/0012-linux-mvp-protocol-removal.md)
  scoped MVP to Linux; this guide includes Windows paths because the
  MCP runs there and many maintainers develop on Win. Gotchas
  documented inline; see also `~/.claude/.../memory/feedback_afunix_windows.md`
  territory for socket / path issues if you hit them.
- **Daemon log can grow to 10+ MB.** Filter before reading — the
  `unidrive-log-anomalies` skill or `scripts/dev/log-watch.sh
  --anomalies` saves a lot of context on triage.
- **Tokens at `<profile>/token.json`, POSIX `0600`.** No DPAPI /
  Keychain integration yet — scoped out by [ADR-0011](adr/0011-shell-win-removal.md).
  Protect with filesystem perms.
- **`-Xmx6g` heap cap.** Set by the launcher script. The relocate /
  scale-test workloads need it; idle RSS is ~120 MB.

## 12. Related docs and tickets

- [SPECS.md §2.2](SPECS.md#22-surface-b--mcp-json-rpc-20-over-stdio)
  — normative MCP surface description.
- [ARCHITECTURE.md](ARCHITECTURE.md) — system-tier diagram and module
  graph.
- [`docs/dev/lessons/mcp-protocol-version-negotiation.md`](dev/lessons/mcp-protocol-version-negotiation.md)
  — UD-758 lesson (why this guide tells you to use `--debug-file`).
- [`docs/dev/lessons/jar-hotswap-windows.md`](dev/lessons/jar-hotswap-windows.md)
  — UD-712 lesson (why `:app:mcp:deploy` kills running JVMs).
- [`docs/CHANGELOG.md`](CHANGELOG.md) — release-level surface changes.
- [`CLAUDE.md`](../CLAUDE.md) — agent-level conventions (skills,
  log-watch, ticket etiquette).
- [UD-014](backlog/CLOSED.md#ud-014) — made interactive auth
  provider-agnostic (the MCP-driven flow described in §4.1B).
- [UD-758](backlog/CLOSED.md#ud-758) — protocol-version negotiation
  fix.
- [UD-708](backlog/CLOSED.md#ud-708) — anchor ticket for this guide.

The Python helper-MCPs at `~/dev/git/unidrive-mcp/` (7 small Python
servers for backlog, log-tail, gradle, JFR, OAuth, memory, daemon-IPC
helpers) are a **separate artifact** — different repo, different
purpose, no overlap with the Kotlin MCP described here. See
[`docs/plans/2026-05-02-unidrive-mcp-standalone-repo.md`](plans/2026-05-02-unidrive-mcp-standalone-repo.md)
if you're looking for those.
