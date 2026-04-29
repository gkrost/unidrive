# Security policy

## Reporting a vulnerability

Email **unidrive@krost.org** with the subject line `[SECURITY] <short summary>`.

Please include:

- a description of the issue,
- a minimal reproduction or proof-of-concept,
- the affected version (`v0.0.1` and any commit SHA you tested against),
- any disclosure deadline you have in mind.

I will credit reporters in the changelog by default unless
you ask otherwise.

PGP is not yet published. If you need encrypted reporting before then, ping
the address above and I will respond with a key out-of-band.

## Scope

In scope:

- the daemon, CLI, and MCP server in [`core/`](core/),
- the build / release tooling in [`scripts/`](scripts/) and
  [`.github/workflows/`](.github/workflows/).

Out of scope (please report to the upstream project):

- third-party providers' cloud services (OneDrive, HiDrive, Internxt, etc.),
- third-party libraries — but please do tell me if a dependency CVE affects
  UniDrive directly.

## Threat model and design choices

The architectural threat model lives in
[`docs/SECURITY.md`](docs/SECURITY.md). It documents the asset inventory,
data-flow diagram with trust boundaries, and the STRIDE table with
file-anchored mitigations. Read that first if you want to understand what
the project considers "in-scope" at the design level.
