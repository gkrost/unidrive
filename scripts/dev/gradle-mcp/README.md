# unidrive-gradle MCP

Targeted Gradle invocations for the unidrive repo, exposed as MCP tools so
an agent session can run module-scoped tests, incremental builds, and the
`ktlint-sync` workflow without having to compose the right `cd core &&
./gradlew …` invocation each time. (Single composite today; the
multi-composite scaffolding remains in case future tiers re-introduce one.)

**Ticket:** UD-718.
**Template:** [`scripts/dev/oauth-mcp/server.py`](../oauth-mcp/server.py) — same stdio-MCP shape.

## Why it exists

Every test iteration from an agent session today costs ~3 tool calls:
`cd` into the right composite, run `./gradlew …`, scrape stdout for the
failure summary. This MCP collapses the loop to one call with structured
JSON output (JUnit XML parsed, failure stack traces truncated at the
first 20 lines).

Runs on the user's native side — same rationale as the oauth-mcp: the
MSIX sandbox doesn't expose the JVM toolchain.

## Tools

### `test(module, test_class?, test_method?)`

```json
test(module=":app:cli", test_class="org.krost.unidrive.cli.ConfigLoaderTest")
  -> {
    "module": ":app:cli",
    "composite": "core",
    "passed": 12,
    "failed": 1,
    "skipped": 0,
    "duration_s": 14.83,
    "failure_details": [
      { "test_name": "…ConfigLoaderTest.rejects_missing_provider",
        "message": "expected IllegalStateException, got null",
        "stack_trace": "<20 lines>" }
    ]
  }
```

Maps to `./gradlew :<module>:test [--tests <class>[.<method>]] --no-daemon`
under the composite that owns the module. Parses every
`TEST-*.xml` under `<composite>/<module-path>/build/test-results/test/`.
If Gradle failed before producing XML (compile error), the result carries
`stderr_tail` so the agent isn't blind.

### `build(modules=["*"], skip_lint=False)`

```json
build(modules=["*"])                 // fan out across all core/ modules
build(modules=[":app:cli"])          // build one module
build(modules=["*"], skip_lint=true) // compile-only inner loop
```

`skip_lint=true` appends
`-x ktlintCheck -x ktlintMainSourceSetCheck -x ktlintTestSourceSetCheck`.
The returned `failed` list contains `{target, gradle_exit_code,
duration_s, log_tail}` for every failing target.

### `ktlint_sync()`

Shells out to `scripts/dev/ktlint-sync.sh` (unscoped — wide sweep) and
classifies the resulting `git status --short` diff into
`formatted_files: […]` (`.kt`/`.kts`) and `baseline_regen: […]`
(`baseline.xml`). Does NOT re-implement the bash script. If you want
module-scoped sync, call the script directly with `--module :app:cli` —
intentionally out of scope here (one tool, one behaviour).

## Composite lookup

The mapping lives in [`composite_map.py`](composite_map.py). It's
hand-maintained from `core/settings.gradle.kts`:

| Composite | Modules |
|-----------|---------|
| `core/`   | `:app:core`, `:app:sync`, `:app:xtra`, `:app:cli`, `:app:mcp`, `:providers:hidrive`, `:providers:internxt`, `:providers:localfs`, `:providers:onedrive`, `:providers:rclone`, `:providers:s3`, `:providers:sftp`, `:providers:webdav` |

If `settings.gradle.kts` changes, update the lists in `composite_map.py`.
A fast audit: `python server.py --list-modules` prints the registry
without starting the MCP.

## Setup (one-time, on the user's machine)

1. Install dependencies into a dedicated venv:
   ```powershell
   cd scripts\dev\gradle-mcp
   py -m venv .venv
   .\.venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   ```

2. Sanity-check the module registry without touching Gradle:
   ```powershell
   python server.py --list-modules
   ```
   Should print one section per composite, matching the table above.

3. Register the MCP in your Claude Code config. Add to
   `~/.claude/claude_desktop_config.json` (or the equivalent Claude Code
   settings file):
   ```json
   {
     "mcpServers": {
       "unidrive-gradle": {
         "command": "C:\\Users\\<you>\\dev\\git\\unidrive\\scripts\\dev\\gradle-mcp\\.venv\\Scripts\\python.exe",
         "args": ["C:\\Users\\<you>\\dev\\git\\unidrive\\scripts\\dev\\gradle-mcp\\server.py"]
       }
     }
   }
   ```
   Restart Claude Code. The tools should appear as
   `mcp__unidrive-gradle__test`, `mcp__unidrive-gradle__build`, and
   `mcp__unidrive-gradle__ktlint_sync`.

## Windows notes

- The server detects `os.name == "nt"` and invokes `gradlew.bat` under
  `shell=True`. On POSIX it uses `./gradlew` with `shell=False` and an
  explicit argv.
- `ktlint_sync()` invokes `bash scripts/dev/ktlint-sync.sh`. Git for
  Windows ships `bash.exe` on PATH — a precondition for the repo to
  build at all, so no fallback.
- All subprocess output is captured `text=True, encoding="utf-8",
  errors="replace"` to survive stray UTF-16 BOMs from the Gradle
  wrapper under some shells.

## What this deliberately does NOT do

- **No OAuth-gated integration tests.** Those need
  `UNIDRIVE_INTEGRATION_TESTS=true` + a token from
  `unidrive-test-oauth`. Compose the two MCPs at the call site; the
  `test` tool here makes no assumption about env.
- **No daemon management.** Each invocation runs with `--no-daemon` so
  the agent sees honest wall-clock timing. If the user wants a warm
  daemon, run `./gradlew` manually once before the session.
- **No `--continuous` / live reload.** Out of scope per the ticket
  non-goals.
- **No module-scoped ktlint_sync.** The bash script supports
  `--module :app:cli`; the MCP does not surface that flag to keep the
  tool signature trivial. Drop to bash for that case.

## Known limitations

- The JUnit XML parser assumes the Gradle `test` task wrote to
  `build/test-results/test/`. If a module's build.gradle.kts renames
  the task or results dir, the parser will see zero tests and the
  `gradle_exit_code` will be the only signal of what happened.
- `build(modules=["*"])` serialises composites instead of running them
  in parallel. Simpler error reporting, but slower than a hand-written
  parallel shell loop. Acceptable because cold-start Gradle dominates
  in either case.
- `ktlint_sync()` classifies the post-run `git status` diff without
  checkpointing pre-run dirty state perfectly. If the worktree was
  already dirty with unrelated `.kt` edits, those show up in
  `formatted_files` as well. The bash script itself prints a
  `git diff --stat` at the end for ground truth.

See also: [`docs/dev/TOOLS.md`](../../../docs/dev/TOOLS.md),
[`scripts/dev/oauth-mcp/README.md`](../oauth-mcp/README.md) (sibling MCP).
