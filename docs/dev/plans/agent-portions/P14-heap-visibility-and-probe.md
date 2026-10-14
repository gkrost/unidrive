# P14 Heap of a full enumeration: a health line and a repeatable probe

| | |
|---|---|
| Issues | none filed yet: file one before starting ("daemon: periodic health line and a heap probe for a full enumeration"); related gkrost/unidrive#638 |
| Labels | enhancement, kind:observability, kind:efficiency |
| Size | S-M |
| Priority | rank 14 of 54 (first-sync group), wave 1 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | P42 (logging config), P15 |
| Status check | verified on main a0c6c4d3, 2026-10-14: no health line, no memory test exists (`ManagementFactory` is used only in `CliEncoding.kt`) |

## Problem
- [measured] Internxt daemon JVM during the capture run (`proc-unidrive.csv`, pid 32280, working set): 72 MB at 17:22:13, 326 MB at 17:24, 432 MB at 17:27 (141,000 items), 1,068 MB from 17:39 (about 190,000 items) to 17:54 (251,000). Private bytes 461 MB at 17:31:15 and 1,048 MB at 17:33:37, around the resume at 17:31:40 that reloaded 141,000 staged rows ([inferred]: the resumed rows are held twice).
- [code] Flags (`dist/launcher/jvm-flags.txt`): `-Xms64m`, `-Xmx` from `UNIDRIVE_XMX` (default 2g), `-XX:+ExitOnOutOfMemoryError`, `-XX:+HeapDumpOnOutOfMemoryError`. A status-filtered scan of the account lists about 387,000 live items (316,000 files, 71,000 folders); a status-less scan would return about 856,000 rows including tombstones, but the fallback of PR #880 drops non-live rows before staging, so the resident set is of the live count.
- [unverified] A working set is not the live heap: nobody has a heap histogram of a long run. Whether 387,000 live items fit in 2 GB, during the resume and during assembly, is the most important unmeasured risk (comparison report section 3.3). An OutOfMemoryError ends the daemon with exit code 3 mid-scan.
- The official client logs a health line (heap, RSS, event-loop lag) every minute; the engine has nothing, so the heap risk is invisible until it bites.

## Scope
- Change:
  1. A daemon health line at INFO every 60 s in `unidrive.log`: heap used / committed / max (`MemoryMXBean`), GC count and time (`GarbageCollectorMXBean`), thread count, enumeration state and items, upload queue depth. New small class in `core/app/cli` started from `DaemonRuntime` (late-bound references are explicit since the wiring refactor; add one line, do not restructure). No paths, no names.
  2. An opt-in heap probe (a test tagged so that `check` skips it, or a Gradle task `heapProbe`): a fake provider returns N items in cursor-listing-sized pages through `RemoteGather` / `RemoteEnumeration` into a temp `state.db`; variants: fresh run, run resumed from N/2 staged rows. It prints retained heap after a forced GC and bytes per item at 100k / 200k / 400k.
- Must not change: the JVM flags, any production behaviour of the enumeration, the log format of existing lines.
- Out of scope: reducing memory (P15), JFR.

## Acceptance criteria
1. While a daemon runs, one health line per 60 s appears; the interval is a constructor parameter and a unit test with a fake clock/scheduler sees exactly one line per interval and none after shutdown.
2. The line carries the fields above in `key=value` form (grep-able by `scripts/dev/log-watch.sh`; add the pattern to its summary).
3. `heapProbe` (or the tagged test) runs on the Linux CI leg only when asked, prints the table and fails if the resumed variant retains more than 1.5 times the fresh variant (it is about 2 times today, [inferred]); the thresholds are constants with a comment.
4. The numbers of one run on a developer machine for 100k/200k/400k items are pasted in the PR body, with the JDK and `-Xmx` used.
5. No new dependency; no change in `check` run time above 2 s.
Tests: new `HealthLineTest` (`app/cli`, use the virtual-time pattern of `EnumeratePollerTest`); the probe next to `RemoteEnumerationTest` (`app/engine-core`) with the in-repo fakes (`FakeCloudProvider` from `app/sync` testFixtures, or a local paged provider like the one in `InternxtCursorListingTest`).
Commands: `cd core && ./gradlew :app:cli:test --tests '*HealthLineTest' && ./gradlew :app:engine-core:test --tests '*RemoteEnumerationTest'`.

## Constraints
- Create your own worktree from origin/main (`git fetch origin`, then `git worktree add ../wt-<slug> -b <branch> origin/main`); never work in a shared checkout or in a worktree another session owns.
- Never `git stash`. Merge main into the branch (no rebase, no force-push).
- One branch and one DRAFT pull request for this portion; the owner merges. Check `gh pr list --state open` for a PR on the same files first.
- Claim each issue with a comment ("starting, branch ...") before editing, and report the PR on the issue.
- Commit messages describe the change; engine commits carry no ticket id in the subject, the PR body names the issues. End every commit with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and the PR body with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Line endings follow `.gitattributes` (LF; only `*.bat`, `*.cmd`, `*.ps1` are CRLF): keep each file's endings. `core/gradlew.bat` always shows an EOL-only change in a Windows checkout: never commit it.
- detekt must pass against the per-module baselines (never regenerate a baseline; there is no ktlint in the build) and add no compiler warnings.
- Run only the touched test classes locally (`cd core && ./gradlew :app:<module>:test --tests '<class>'`); CI runs `./gradlew check` on the PR (read it with `gh pr checks`).
- Public repository: no private paths, account names or addresses in code, tests, commits or PRs. No live account, no deploy, no running `unidrive`/`java` process touched; use fakes, `localfs` or temp dirs.

## Open decisions for the owner
1. Probe as an opt-in Gradle task or as a tagged JUnit test? Proposal: the Gradle task, so `check` stays unchanged.
2. Health interval 60 s and INFO level, or DEBUG only? Proposal: INFO, one short line.

## How to verify live
Owner, once, on the Windows machine: start the Internxt profile, then from a shell run `jcmd <pid> GC.class_histogram` at about 100,000 and at 250,000 items and `jcmd <pid> GC.heap_info`; attach both to the issue. Run the daemon with `UNIDRIVE_XMX` at 1g to see whether an OOM (exit code 3, heap dump next to the CWD) appears earlier.
