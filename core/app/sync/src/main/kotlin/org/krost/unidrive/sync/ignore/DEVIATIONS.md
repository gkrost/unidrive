# Ignore matcher: conformance with git, and the deviations

`org.krost.unidrive.sync.ignore` implements the pattern rules of `.gitignore` as a library: `IgnoreRules` (one parsed
rule file), `IgnoreMatcher` (layers, ancestor rule, `explain`), `GlobPattern` (one compiled pattern) and
`IgnoreTree` (discovery of the rule files of a sync root, layering with the defaults, caching; its precedence is in
`docs/dev/specs/ignore-layering-design.md`). It is a clean-room implementation: written from the published `gitignore`
documentation and glob(7), with `git check-ignore` used only as a black box (the oracle below). The mount's upload
path asks it (`UploadIgnore`, see the layering document); the existing `Reconciler.matchesGlob` is untouched and keeps
serving `exclude_patterns`.

## The oracle

`git` itself is the specification. `IgnoreOracleTest` runs the corpus in
`core/app/sync/src/test/resources/ignore-oracle/*.cases` (rule files plus path lists, format in `IgnoreCorpus.kt`)
through `git check-ignore --stdin -z -v -n --no-index` in a throwaway repository and compares, for every path, the
winning rule (file, line number, text) with `IgnoreMatcher.explain`. It does this with `core.ignorecase` off and on, and
once more with the NFC option against git run on NFC-normalised inputs. Every case is also run through `IgnoreTree`
(the files found by discovery from an in-memory source, with no content defaults, since git has none): once as
`.gitignore` files with the opt-in on, once renamed to `.unidrive.ignore`. Both must give git's answer, apart from the
file name. `11-discovery.cases` holds the nested-file cases: rule files inside excluded directories, rule files that
are themselves ignored, re-includes across files.

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
5. **`.git` is not special in the matcher.** The overridable default for `.git` lives in `IgnoreTree` (below). (The
   oracle corpus uses `.hg` where a literal `.git` would make git itself refuse to look inside.)
6. **No limits in the matcher.** git has none worth naming for patterns; the size cap for rule files is
   `IgnoreTree`'s (below).

## What `IgnoreTree` adds on top of git, deliberately

The layers are specified in `docs/dev/specs/ignore-layering-design.md`; these are the points where the result differs
from `git check-ignore` on the same tree.

1. **Content defaults** (`IgnoreDefaults.CONTENT`): `.git`, `.svn`, `.hg`, `.bzr` and desktop, OS and editor junk are
   excluded with no rule file at all. git excludes nothing by default (it skips its own `.git` by other means). They are
   the lowest layer, so any rule re-includes them (`!.git`). The oracle runs with them switched off.
2. **`.gitignore` is opt-in** per account. With the opt-in off, `.gitignore` files are not even probed.
3. **`.unidrive.ignore`** is read in every directory, with git's per-directory semantics. In one directory it beats
   `.gitignore`; a deeper `.gitignore` beats a shallower `.unidrive.ignore` (deepest wins, whatever the file name).
4. **Account rules** sit between the defaults and the rule files: git's nearest equivalent, `core.excludesFile`, is
   not read (decision 4 above).
5. **Operational rules** (`/.unidrive-trash`, `/.unidrive-versions`) cannot be overridden by any rule.
6. **An unreadable or oversized rule file holds back its directory's contents** (fail closed): a rule that is not
   applied could upload what the user meant to keep local. This is a policy of our own, not taken from git. A
   symbolic link is not followed and counts as absent, as `gitignore(5)` documents for `.gitignore`.
7. **Rule files are decoded as UTF-8.** Bytes that are not UTF-8 become U+FFFD; git compares the raw bytes.
8. **Rule-file lookup follows the file system**: whether `Sub/.unidrive.ignore` is found for `sub/x` is the file
   system's answer (yes on NTFS, no on a case-sensitive one). The oracle's in-memory source is case-sensitive, so the
   corpus never spells a rule-file directory in two cases.

## Where this implementation is git, even where git is surprising

These are not deviations; they are listed because a user will expect otherwise and the answer is "same as git".

- **Bytes, not characters.** `?` and a bracket member are one UTF-8 byte. `caf?` does not match `café`; `caf??`
  does. `[é]` is two byte members. A code-point mode would be a deviation and has not been built.
- **Case folding is ASCII only** with `ignoreCase = true` (`core.ignorecase`): `É` and `é` stay different names. NTFS
  and APFS fold more than that; git does not, so neither does this matcher.
- **`foo**/bar`** is `foo*/bar` (a `**` only means "any directories" when it is a whole path component), and since
  git 2.52 `foobar` no longer matches it. Older git matched; the corpus case has `min-git: 2.52`.
- **Case folding is uneven** (observed, pinned by the corpus): with `ignoreCase` an unescaped letter matches either
  case, but an escaped letter and a single bracket member are compared as written with the lower-cased name, so `\A`
  and `[A]` match nothing at all (`\a` and `[a]` match both cases). A range also takes the other case (`[A-C]`
  matches `b`), and `[[:upper:]]` / `[[:lower:]]` both match every ASCII letter.
- A reversed range (`[z-a]`) is its first endpoint alone. An unterminated bracket or an unknown class name
  (`[[:nosuchclass:]]`) makes the pattern match nothing. A `[:` with no `:]` before the next `]` is not a class but
  plain members.
- `**` as a whole component before an *escaped* slash (`a/**\/b`) spans directories but needs at least one
  (`a/x/y/b` yes, `a/b` no); an escaped slash also starts a component for a following `**`. Before any other
  character a `**` is a plain `*`.
- A trailing backslash is a pattern that never matches. A lone `!` is a negated empty pattern that never matches.
- Trailing spaces are removed unless escaped; tabs are not. A UTF-8 byte-order mark at the start of a file is
  skipped; a CR before the LF is dropped. `#` starts a comment only in column 0.
- A path below an excluded directory stays excluded whatever a later `!` says: `explain` reports the **topmost**
  excluded ancestor's rule, like `check-ignore -v`.

## How it is matched (not observable, but pinned)

A pattern is compiled once into byte tokens. Literal names, `*.ext`, `name*`, `pre*post` and `dir/` + `**` are
matched directly; a leading `**/` before a slash-free rest is matched against the last component only (the same
result, cheaper). Everything else runs as a nondeterministic automaton over the name's bytes, so a pattern like
`*a*a*a*a*b` against a long name without a `b` costs (name length x pattern length), never exponential time;
`IgnoreMatcherTest` pins the run time and `IgnoreBenchmarkTest` the throughput.

Layers: the matcher tries rule files deepest directory first (equal depth: the caller's order, earlier wins); the
first file with a matching rule decides, and within it the last matching rule.
