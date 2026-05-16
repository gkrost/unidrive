# MCP server must answer `initialize` with what it supports — never error

When implementing an MCP server, the `initialize` handler MUST respond
with a `protocolVersion` the server speaks, regardless of what the
client proposed. The client (not the server) decides whether to abort
on a version mismatch. Replying with a JSON-RPC error short-circuits
the spec's negotiation contract and breaks any client on a newer
protocol revision.

> If the server supports the requested protocol version, it MUST respond
> with the same version. Otherwise, the server MUST respond with another
> protocol version it supports.
> — [MCP lifecycle spec](https://modelcontextprotocol.io/specification/2024-11-05/basic/lifecycle#version-negotiation)

## Worked failure (UD-758, 2026-05-16)

`McpServer.handleInitialize` had a hard equality check:

```kotlin
if (clientVersion != null && clientVersion != "2024-11-05") {
    send(buildErrorResponse(id, ErrorCodes.INVALID_PARAMS,
        "Unsupported protocol version: $clientVersion. Server supports 2024-11-05"))
    return
}
```

Claude Code 2.1.143 announces `"2025-11-25"` in its `initialize` frame.
The server returned `-32602 INVALID_PARAMS`; Claude Code logged
`Connection failed: ... Unsupported protocol version: 2025-11-25` and
dropped the stdio pipe. `claude mcp list` reported the server as
`✗ Failed to connect` with no further detail. The bug was invisible
through the manual-probe path because copy-paste reproductions used
`"2024-11-05"` (the one version the server happened to accept).

The same root cause would have broken Claude Desktop on the 2025-* revs,
Cursor, mcp-cli, and anything else that tracked the spec since
2024-11-05. Fix: always respond with the server-supported version and
emit a single WARN for operator visibility when the client asked for
something else.

Worked-example commits: `f1fe643` (fix) → PR
[#38](https://github.com/gkrost/unidrive/pull/38).

## Why it bit

The spec wording is "the server MUST respond with another protocol
version it supports" — it doesn't permit rejecting the handshake. But
reading the bare wire format in isolation, "reject what you don't
understand" looks defensive. Without an integration test driving a
*newer* client version, the strict check went unnoticed for ~6 months
between the 2024-11-05 spec release and the 2025-11-25 client adoption.

## How to apply

- **Never error from `handleInitialize`.** Even if the client asked for
  a version you don't speak, respond with one you do. The client is
  responsible for the "is that acceptable?" decision.
- **When adding any version-negotiated surface** (MCP capabilities,
  new tool schemas, new IPC envelope ops), pick the same shape:
  accept any incoming value, respond with what the server actually
  speaks, log a WARN on divergence for operator visibility.
- **Add at least one test that drives the handshake with a version the
  server does NOT support** and asserts a successful result. Pinning
  only the matching-version case will not catch this.
- **`claude mcp list` reports "Failed to connect" without explaining
  why.** When triaging an MCP no-show, drop straight to
  `claude --debug-file <path> mcp get <name>` — the debug log carries
  the actual JSON-RPC error.
- **Generalisable principle (Postel's Robustness Principle):** "Be
  conservative in what you send, liberal in what you accept." For
  unidrive specifically this matters anywhere the daemon parses
  externally-authored frames (MCP `initialize`, future capability
  negotiation, third-party tray clients on the IPC socket).

## Defensive checklist

- [ ] `handleInitialize` returns `result`, not `error`, on any input
      where the message itself parsed.
- [ ] The server's supported version lives in a single named constant
      (`SUPPORTED_PROTOCOL_VERSION` in `JsonRpc.kt`), not duplicated
      across the handler and the result builder.
- [ ] Tests cover at least one mismatched-version request and assert a
      `result` payload with the server's version.
- [ ] When bumping the server's supported version, decide whether to
      *replace* or *add* the constant — and update the test for the
      previous version accordingly.
