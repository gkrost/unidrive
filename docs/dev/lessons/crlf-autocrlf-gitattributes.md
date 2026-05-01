# CRLF / `core.autocrlf` vs `.gitattributes` — phantom diffs on Windows

## Problem

On Windows, `git status` shows a file (commonly `core/gradlew.bat`) as
modified relative to HEAD, but `cmp <(git show HEAD:core/gradlew.bat)
core/gradlew.bat` reports the bytes are identical. `git checkout --
<path>` "fixes" it briefly, then the modification flag returns the
moment any other git operation runs.

## Why it bites

The repo ships `.gitattributes` with two layered rules:

```
* text=auto eol=lf
*.bat text eol=crlf
```

Translated: "store every text file as LF in the blob; check out as
LF on POSIX. Exception: `*.bat` checks out as CRLF on every
platform."

What the rules *don't* control: the developer's global
`core.autocrlf`. On Windows, the default is `core.autocrlf=true`
(set by Git for Windows installer). That setting tells Git to
auto-convert LF→CRLF on checkout and CRLF→LF on add for every
text file, *bypassing* the `.gitattributes` directive when the two
disagree.

When a file was committed before `.gitattributes` was added (or by
a Windows user with `core.autocrlf=false` at the time), the blob
in HEAD holds CRLF bytes — non-normalized. The `.gitattributes`
rule says the blob *should* hold LF. So on every checkout:

- The renormalized representation Git computes from the working
  tree (LF blob, CRLF working tree) hashes to a different SHA
  than the historical CRLF-stored blob.
- `git status` compares the two SHAs, sees a difference, marks
  the file modified.
- The on-disk bytes are unchanged — `cmp` confirms.

This is a **stat-cache phantom diff**, not a content change.

## How to apply

**On the affected machine, once:**

```bash
git config --local core.autocrlf false
git config --local core.eol native
git add --renormalize .
# Inspect with: git diff --cached --stat
# Commit any blobs Git restages — those are real normalisations
# that the historical blob never received.
```

After this, fresh checkouts on Windows produce CRLF for `*.bat`
(per `eol=crlf`) and LF for everything else (per `eol=lf`),
without Git fighting itself.

**For new contributors,** prefer `core.autocrlf=false` + trust
the `.gitattributes`. Don't rely on `core.autocrlf=true`'s
default-best-effort behaviour — it loses to explicit per-file
rules in confusing ways.

## Defensive checklist

- If `git status` shows a file modified that you didn't touch,
  always run `cmp <(git show HEAD:path) path` before committing.
  If `cmp` is silent, it's a phantom diff.
- Don't use `git commit -a` after unexpected `M` flags — you'll
  bake the renormalisation into a commit you didn't intend.
  Stage explicitly.
- The `.bat`/`.cmd`/`.ps1` exemption (CRLF working tree) is
  load-bearing on Windows — `cmd.exe` and PowerShell can refuse
  to parse LF-only scripts. Don't override.
- If you change `.gitattributes`, follow up with `git add
  --renormalize .` and a dedicated `chore: normalise blobs per
  .gitattributes` commit. Otherwise the next contributor inherits
  the same phantom diffs.

## References

- `.gitattributes` in this repo — the layered policy.
- 2026-05-01 session — first time `core/gradlew.bat` was
  permanently normalised. Commit `b81b06b`.
- Git docs: [`gitattributes(5)`](https://git-scm.com/docs/gitattributes#_end-of-line_conversion).
