# `:app:mcp` — UniDrive MCP server

Kotlin/JVM Model Context Protocol server. Speaks newline-delimited
JSON-RPC 2.0 over stdio, exposes the 23 `unidrive_*` tool verbs and 3
`unidrive://` resources documented in
[`docs/SPECS.md §2.2`](../../../docs/SPECS.md#22-surface-b--mcp-json-rpc-20-over-stdio).

## Build

From `core/`:

```bash
./gradlew :app:mcp:shadowJar    # produces build/libs/unidrive-mcp-<v>.jar
./gradlew :app:mcp:deploy       # build + copy to OS-appropriate install location + launcher
```

`deploy` also kills any running `java -jar unidrive-mcp-*.jar` process
before overwriting (UD-712 — see
[`docs/dev/lessons/jar-hotswap-windows.md`](../../../docs/dev/lessons/jar-hotswap-windows.md)).

## Install / operate

The end-to-end walkthrough — building, deploying, configuring a
profile, OAuth, and wiring the server into Claude Code / Claude
Desktop / any stdio MCP client — lives in
[`docs/MCP-USER-GUIDE.md`](../../../docs/MCP-USER-GUIDE.md).

## Develop / modify

See [`CLAUDE.md`](../../../CLAUDE.md) and
[`docs/AGENT-SYNC.md`](../../../docs/AGENT-SYNC.md) for the
backlog / lessons / ktlint conventions.
