# Ignore matcher: conformance with git, and the deviations

`org.krost.unidrive.sync.ignore` implements the pattern rules of `.gitignore` as a library: `IgnoreRules` (one parsed
rule file), `IgnoreMatcher` (layers, ancestor rule, `explain`) and a glob matcher for the patterns (written from the `gitignore` documentation and checked against git by the oracle below).
Nothing in the engine calls it yet; the existing `Reconciler.matchesGlob` is untouched.

## The oracle

`git` itself is the specification. `IgnoreOracleTest` runs the corpus in
`core/app/sync/src/test/resources/ignore-oracle/*.cases` (rule files plus path lists, format in `IgnoreCorpus.kt`)
through `git check-ignore --stdin -z -v -n --no-index` in a throwaway repository and compares, for every path, the
winning rule (file, line number, text) with `IgnoreMatcher.explain`. It does this with `core.ignorecase` off and on, and
once more with the NFC option against git run on NFC-normalised inputs.

- Reference: **git 2.55.0**, built from its release tag in CI (`.github/actions/pinned-git`). Zero differences required.
- Drift detector: **git 2.43.0** (Ubuntu 24.04), same corpus, informational. Cases that pin newer behaviour carry
  `min-git:` and are skipped there.
- Locally: any `git` on `PATH` is used and its version is printed. `UNIDRIVE_ORACLE_GIT` selects another binary,
  `UNIDRIVE_ORACLE_GIT_VERSION` fails the run unless it reports exactly that version, `UNIDRIVE_ORACLE_REQUIRED=1`
  turns "no git" from a skip into a failure. With no git the test is skipped and says so on stdout.
- On CI the oracle runs only in the `ignore-oracle` jobs (pinned git). The other legs skip it, because the runner's
  own git moves with the runner image.
- Names git cannot be asked about on Windows (backslash and colon in a pathspec, directory names the file system
  cannot spell) are counted in the summary and covered by the Linux job.

Every example the documentation will advertise (IG-6) must be a case in `09-docs-examples.cases`.

## What is deliberately different from `git check-ignore`

1. **Directories are asked as directories, not with a trailing slash.** `git check-ignore a/` takes the slash as part
   of the name (`a/` then matches the rule `a/*`, and its basename is empty), which no directory walk ever
   produces. The harness therefore creates real directories and asks about them without the slash. The matcher's own
   API takes `isDir`, or a trailing `/` on the path as the same thing.
2. **`IgnoreOptions.normalizeNfc` (off by default).** git compares bytes (outside macOS), so an NFC rule does not
   match an NFD name. UniDrive names are NFC-canonical, so a caller can turn the option on: rule text and queried
   paths are NFC-normalised first. The oracle pins it exactly: results equal git's on NFC-normalised inputs. With
   the option off, NFC and NFD names are different names, as in git.
3. **Paths must be canonical**: relative, `/`-separated, no leading slash, no `.`, `..` or `//` components. git
   normalises its pathspecs; this matcher does not and never sees such paths from the scanner.
4. **Only the rule files you hand over exist.** Not read, by design (epic decisions): `.git/info/exclude`, the global
   excludes file, `core.excludesFile`. No index: git ignores only untracked files, this matcher answers for every
   path, so tracked-file behaviour is not claimed.
5. **`.git` is not special here.** The overridable default for `.git` directories belongs to the layering work.
   (The oracle corpus uses `.hg` where a literal `.git` would make git itself refuse to look inside.)
6. **No limits.** git has none worth naming for patterns; the size and count caps for rule files belong to the
   discovery layer, not to the matcher.

## Where this implementation is git, even where git is surprising

These are not deviations; they are listed because a user will expect otherwise and the answer is "same as git".

- **Bytes, not characters.** `?` and a bracket member are one UTF-8 byte. `caf?` does not match `café`; `caf??`
  does. `[é]` is two byte members. A code-point mode would be a deviation and has not been built.
- **Case folding is ASCII only** with `ignoreCase = true` (`core.ignorecase`): `É` and `é` stay different names. NTFS
  and APFS fold more than that; git does not, so neither does this matcher.
- **`foo**/bar`** is `foo*/bar` (a `**` only means "any directories" when it is a whole path component), and since
  git 2.52 `foobar` no longer matches it. Older git matched; the corpus case has `min-git: 2.52`.
- `[[:upper:]]` with case folding on also matches lowercase letters; `\A` with folding on does not match `a`.
- A trailing backslash is a pattern that never matches. A lone `!` is a negated empty pattern that never matches.
- Trailing spaces are removed unless escaped; tabs are not. A UTF-8 byte-order mark at the start of a file is
  skipped; a CR before the LF is dropped. `#` starts a comment only in column 0.
- A path below an excluded directory stays excluded whatever a later `!` says: `explain` reports the **topmost**
  excluded ancestor's rule, like `check-ignore -v`.

## One change that is not git, but is not observable

`Wildmatch` returns "abort everything" when a `*` is followed by a literal that does not occur in the rest of the
text. It prunes the exponential search of patterns like `*a*a*a*a*b` against a long name without a `b`; the result is
the same, and `IgnoreMatcherTest` pins the run time.
