# Security Policy

## Reporting a vulnerability

Please report security vulnerabilities privately through GitHub's
**[Report a vulnerability](https://github.com/gkrost/unidrive/security/advisories/new)**
form (the **Security** tab → *Report a vulnerability*). Private vulnerability
reporting is enabled on this repository, so the report and any discussion stay
confidential until a fix is published.

Do **not** open a public issue for a suspected vulnerability, and do not include
credentials, tokens, or other secrets in a report — describe how to reproduce
the problem instead.

## Scope

UniDrive is a cloud-sync engine that authenticates to third-party providers
(OneDrive, Internxt) on the user's behalf and stores their data locally and in
the cloud. Reports that are especially in scope:

- Disclosure or mishandling of provider credentials, OAuth tokens, or refresh
  tokens.
- Data-integrity or data-loss defects in the sync/hydration engine.
- Weaknesses in local-cache handling, the IPC surface between the daemon and
  its clients, or the encryption path for end-to-end-encrypted providers.

## Supported versions

UniDrive has not yet cut a stable release. Security fixes are applied to the
default branch; there are no separately maintained release branches to
backport to. Once releases begin, this section will record which are supported.

## What to expect

After a report is received, we aim to acknowledge it, confirm the issue, and
work on a fix in coordination with the reporter. Credit is offered to reporters
who wish to be named once a fix is published.
