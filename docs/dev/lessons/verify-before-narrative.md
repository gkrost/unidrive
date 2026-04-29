# Verify distinguishing attributes before building a diagnosis narrative

When a root cause depends on a specific attribute of the user's
environment (PowerShell build flavour, Windows SKU, JDK vendor, shell
packaging, installer variant, locale, even file-system type), get
that attribute confirmed FIRST, then build the narrative — not the
other way round.

## Worked failure (UD-712 session, 2026-04-18)

UD-219 was pinned on "Microsoft-Store-packaged PowerShell 7
AppContainer sandboxing `%APPDATA%`" after assembling strong
circumstantial evidence:

- a `S-1-15-3-*` capability SID on the parent dir's ACL,
- the `Test-Path True` from bash vs `Test-Path False` from the user's
  PS divergence,
- the presence of the Store-packaged OneDrive client.

The ticket was written up confidently, priority bumped to high, a
narrative committed explaining the sandbox behaviour. **One
`(Get-Command pwsh).Source` from the user showed
`C:\Program Files\PowerShell\7\pwsh.exe`** — the MSI install, not the
Store bundle. The whole story fell apart. The `S-1-15-3-*` ACE was
almost certainly deposited by the packaged OneDrive client at some
earlier point, completely orthogonal to the PS7 issue. A second
commit had to walk the ticket back to "observed symptom, deeper
cause still open".

## Why it bit

Three or four pieces of circumstantial evidence consistent with
AppContainer; the one direct attribute that would have distinguished
the hypothesis from alternatives — *which* `pwsh.exe` was running —
went unchecked. The user caught it in one question.

## How to apply

- When a root cause depends on a specific attribute of the user's
  environment, **ASK FIRST** before building the narrative or
  committing the ticket writeup. One probe: `(Get-Command pwsh).Source`,
  `java -version`, `uname -a`, `cat /etc/os-release`,
  `ldd --version`, etc.
- Circumstantial evidence is a hypothesis input, not a hypothesis
  confirmer. ACL entries, SID prefixes, process lineage, error-text
  patterns are all "consistent with, not proof of".
- It is fine (and cheaper) to commit a "symptom observed, root cause
  still open" ticket and wait for the confirming probe than to commit
  a specific hypothesis and have to reverse it.
- Bonus: if a hypothesis has to land in a ticket before confirmation,
  include the **confirming probe** as an action item INSIDE the
  ticket so the next investigator doesn't repeat the mistake.

Worked-example commits in the repo: `a02e55e` (overconfident
hypothesis) → `84d95bf` (walk-back).
