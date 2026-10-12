# Ignore files: discovery, layering and caching

`org.krost.unidrive.sync.ignore.IgnoreTree` puts together the ignore rules of one sync root. The pattern matcher
underneath (`IgnoreMatcher`) and its conformance with git are described in
`core/app/sync/src/main/kotlin/org/krost/unidrive/sync/ignore/DEVIATIONS.md`. This document fixes which rules apply to
a path and which one wins, and where the engine asks. The mount's upload path asks `IgnoreTree` (see "The upload
path" below); the mirror's scan and plan do not yet.

## What ignoring means (owner decisions)

- An ignored path gets **no upload**. Nothing is deleted in the cloud, and a cloud item that becomes ignored stays
  visible (marked as ignored here). Ignore rules are never applied to remote listings.
- `.git` is excluded by default, and a rule overrides the default (`!.git`).
- `.gitignore` files are read only when the account opts in (`IgnoreSettings.honorGitignore`, off by default).
- An ignored file in a mount root stays where it is, across remounts and redeploys. It is a local-only item, never a
  "leftover". `IgnoreTree` only answers the question; every consumer that cleans up or moves local items must ask it.

## Precedence

Highest first. The first layer that has a matching rule decides; within that layer, the last matching rule decides.

| # | Layer | Source (`explain` reports) | Overridable |
|---|---|---|---|
| 1 | Operational rules: `/.unidrive-trash`, `/.unidrive-versions` (the engine's own folders, at the sync root only) | `operational` | No. Checked before everything else. |
| 2 | An unreadable or oversized rule file: everything below its directory is held back | that file, line 0 | Fixing the file |
| 3 | Rule files, the **deepest directory first**. In one directory `.unidrive.ignore` comes before `.gitignore` | the file (root-relative path) and line | By a deeper file |
| 4 | Account rules (`IgnoreSettings.accountRules`, git syntax, applied at the sync root) | `account` and line | By any rule file |
| 5 | Content defaults (`IgnoreDefaults.CONTENT`, git syntax) | `default` and line | By anything above |

A rule file applies to paths **below** its own directory, and its anchored patterns (`/x`, `a/b`) are relative to that
directory, as in git. Account rules and content defaults behave like a rule file at the sync root that loses to the
root's own files.

**The re-include limit (git's).** A path below an excluded directory is excluded, whatever a rule says about the path
itself. `explain` then reports the topmost excluded ancestor and its rule, like `git check-ignore -v`. Consequences:

- `!.git/config` alone does not bring back `.git/config`: `.git` stays excluded. `!.git` (or `!.git/`) re-includes
  the directory, and then its contents are no longer excluded.
- A rule file inside an excluded directory is never read: nothing it says could apply.
- A rule file is read even when a rule ignores the rule file itself (git does the same; the oracle pins it).

### The `.git` default, with the advertised overrides

| Rule | Where | Effect |
|---|---|---|
| (none) | | `.git` (directory, or the `.git` file of a worktree or submodule) is excluded at any depth |
| `!.git` or `!.git/` or `!**/.git` | the root `.unidrive.ignore` | every repository's `.git` is synchronised |
| `!.git` | `work/proj/.unidrive.ignore` | `work/proj/.git` and any repository below `work/proj` are synchronised, nothing else |
| `!.git/config` | anywhere | no effect: `.git` stays excluded |
| `!.unidrive-trash` | anywhere | no effect: operational |

Each line is a test in `IgnoreTreeTest`.

## Discovery

- For a path, the rule files of every ancestor directory are read, top-down, stopping at the first excluded
  directory. The walk API is `explainEntry(path, isDir)`: the verdict for one entry whose ancestors are known not to
  be excluded, which is what a scanner that does not descend into excluded directories asks. `explain(path)` answers
  for any path, ancestors included.
- Rule files are read through an `IgnoreFileSource`. `FileSystemIgnoreSource` reads the local file system. It never
  follows a symbolic link (git does not follow a symlinked `.gitignore` either), and it does not read a file whose real
  path is outside the root. A directory with the rule file's name is no rule file. The mount front-end needs a source
  of its own: reading a rule file through a cloud placeholder must not hydrate it.
- Limits: a rule file over `maxRuleFileBytes` (1 MiB by default) is not read.
- **Problems fail closed.** A rule file that exists but cannot be read (permissions, I/O error, over the limit) holds
  back everything below its directory: an ignore rule that is not applied could upload something the user meant to keep
  local, and holding back only delays uploads. A file that is not followed (a symbolic link, outside the root) counts
  as absent, as in git. Either way the problem is reported once per state of the file (`onProblem`), and again only
  after the file changed.
- Text is UTF-8 (a byte-order mark is skipped). Invalid patterns do not exist in git's syntax: a pattern git cannot
  use (an unterminated bracket, a trailing backslash) matches nothing, here as in git.

## Caching and invalidation

- A directory's rule files are **probed once per scan** (size and modification time, the stamp) and **parsed once per
  change of the stamp**. A directory without rule files costs one probe per file name per scan and no memory beyond its
  node.
- The layers below a directory (its "chain") are built once and shared by all its entries. A directory without rule
  files shares its parent's chain. When a rule file changes, the chains below it are rebuilt at their next use.
- `revalidate()` starts a new scan: every stamp is checked again at its next use. The local scan calls it once at the
  start. `invalidate(dir)` re-reads one directory's files even when the stamp is unchanged (an edit within the clock's
  resolution that keeps the size); a file watcher calls it for a changed rule file. `invalidateAll()` forgets
  everything.
- Removing a rule file makes its paths eligible again at the next scan. Nothing is deleted anywhere.
- Thread-safe: one tree per sync root, shared by the scanner and the upload path.

Benchmark (`IgnoreBenchmarkTest.oneMillionPathsThroughDiscoveryWithPruning`): one million entries of a synthetic
monorepo, with a root `.unidrive.ignore`, one `.unidrive.ignore` per project and the content defaults. The walk prunes
on `explainEntry`. The report gives the first scan, a second after `revalidate()` with nothing changed (no file parsed
again), a third after one project file changed (exactly one parsed again), and the same rules in one flat matcher as
the baseline.

- **The cache is capped** (`IgnoreSettings.maxCachedDirs`, 20,000 directories): past it the least recently used
  directories are forgotten, down to seven eighths of the cap, and probed and parsed again at their next use. A
  held-back directory is never forgotten, so the count `daemon.status` reports does not drift down.

## The upload path

`UploadIgnore` is the tree as the upload path asks it: with the engine's logical paths (`/a/b`), revalidated at most
every 5 s (so an edited, added or removed rule file applies without a restart), with case folding where the file
system folds case (Windows, macOS) and NFC names.

**The account's keys.** `honor_gitignore` (default off) and `ignore_rules` (git syntax, the account layer) in the
profile's section of `config.toml`.

**`exclude_patterns` stay as they are.** They keep their glob syntax and meaning (`Reconciler.matchesGlob`) and are
not translated: they are asked beside the ignore rules, and no rule file overrides them. Only the built-in default
list (`SyncConfig.DEFAULT_EXCLUDE_PATTERNS`) gives way to its git-syntax twin, the content defaults, where the ignore
rules are on: that is what makes `.git` overridable. A one-time `migrate` of a profile's patterns into `ignore_rules`,
with a dry run that shows the differences, is a later item.

**The mount** (`RemoteOperationGuard.isExcludedPath`, the keep-local rule): an ignored path is treated as an excluded
one, everywhere the mount asks.

- A write through the mount is accepted and kept in the cache, never uploaded; the reply and the events say
  `excluded`. `hydration.list` flags the row `excluded` (computed when listed, so a rule change shows at the next
  listing without rewriting rows). A cloud item below an ignored folder stays listed and downloads as before.
- A local-only ignored file is not a leftover: the start-up replay does not take it, the cache eviction never touches
  it (it has no cloud copy), `daemon.status` does not count it as pending, and it stays in place across restarts.
- A file the cloud already holds and that is edited while it is ignored keeps the cloud's version as its baseline: its
  cache copy is not evicted, and the edit uploads at the next start once the path is no longer ignored. Holding back
  only delays.
- Rule files are read from the hydration cache (`CacheIgnoreSource`), never downloaded for it. A rule file the cloud
  holds that the cache does not (never opened here) holds its folder back until it is opened; a rule file's cache copy
  is never evicted. A rule file written through the mount, or downloaded, applies to the next decision.
- A rename of a synced file onto an ignored name is refused (`excluded`), as for an excluded name: it would take the
  file out of sync for good.

**Never silent.** A held-back folder is logged as a WARN (at most once per rule file every 15 minutes), counted in
`daemon.status` (`ignore.held_back`), and `ignore.explain` names its rule file and the reason.

**`ignore.explain`** (a read verb; fixture `ipc-contract/ignore.explain.ndjson`) answers why a path is or is not
uploaded, from the daemon's own rules: the deciding layer, rule file, line and pattern, the path it matched, the reason
of a held-back folder, and the first matching `exclude_patterns` entry. An IPC verb rather than a CLI command: the
answer must be the one the upload path acts on, from the same rule files read at the same moment, and a front-end
needs it while the daemon runs. A mirror daemon answers `wrong_mode` until the mirror's upload planning asks the rules.

## Not decided here

- The mirror's scan and upload planning (`LocalScanner`, `Reconciler`) move to the rules in a later change, with the
  same keys and the same `exclude_patterns` rule.
- Live reload by a file watcher, a rules snapshot for clients and the `unidrive ignore check` command are later
  items of the ignore epic.
