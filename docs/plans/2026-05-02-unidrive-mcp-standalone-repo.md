# unidrive-mcp Standalone Repo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote `~/dev/git/unidrive-mcp/` from a loose folder of seven Python MCP servers to a self-contained git repository pushed to `github.com/gkrost/unidrive-mcp` (private), with minimum top-level scaffolding.

**Architecture:** No code changes. Add three top-level files (README, .gitignore, LICENSE), `git init -b main`, single initial commit, `gh repo create --private --push`. Per-server contents stay byte-identical.

**Tech Stack:** git, gh CLI, plain text. No build system, no language runtime touched.

**Spec:** [`docs/specs/2026-05-02-unidrive-mcp-standalone-repo-design.md`](../specs/2026-05-02-unidrive-mcp-standalone-repo-design.md) v0.1.0.

---

## File Structure

All paths are inside `~/dev/git/unidrive-mcp/` unless prefixed `unidrive/`.

| Path | Action | Purpose |
|---|---|---|
| `README.md` | Create | Top-level repo intro + table of 7 servers |
| `.gitignore` | Create | Python defaults |
| `LICENSE` | Create | Apache-2.0 |
| `.git/` | Create (`git init`) | Fresh history |
| `*-mcp/` (7 dirs) | **Untouched** | Per-server content stays byte-identical |
| `unidrive/docs/specs/2026-05-02-...md` | **Untouched** | Already committed in `81f1f09` |

The seven per-server subdirectories are explicitly **not modified** by any task. Their existing per-subdir READMEs, gitignores, requirements.txt, and server.py files are committed exactly as they are on disk today.

---

## Task 1: Pre-flight safety checks

**Files:** none modified. Read-only verification.

- [ ] **Step 1: Verify the unidrive-mcp folder exists and contains the 7 expected servers**

Run:
```bash
ls -1 ~/dev/git/unidrive-mcp/
```
Expected output (exactly these 7 dirs, in any order):
```
backlog-mcp
daemon-ipc-mcp
gradle-mcp
jvm-monitor-mcp
log-tail-mcp
memory-mcp
oauth-mcp
```
If anything else is present (extra dirs, dotfiles, a stray `.git/`), STOP and surface to user before proceeding.

- [ ] **Step 2: Verify there is no existing `.git/` to be overwritten**

Run:
```bash
test -d ~/dev/git/unidrive-mcp/.git && echo "EXISTS — STOP" || echo "ok, no .git/"
```
Expected: `ok, no .git/`. If "EXISTS — STOP", the folder is already a repo; surface to user before proceeding.

- [ ] **Step 3: Verify `gh` CLI is authenticated as gkrost**

Run:
```bash
gh auth status
```
Expected: shows `Logged in to github.com account gkrost` (or equivalent). If not logged in, STOP and surface to user — do NOT attempt `gh auth login` from inside the implementation.

- [ ] **Step 4: Verify the target repo name is not already taken on GitHub**

Run:
```bash
gh repo view gkrost/unidrive-mcp 2>&1 | head -3
```
Expected: error containing "Could not resolve to a Repository" or similar "not found". If the repo already exists, STOP and surface to user — do NOT overwrite.

- [ ] **Step 5: Run the secrets grep across the whole tree**

Run:
```bash
grep -rIE '(client_secret|client_id|password|token|api[_-]?key|bearer|AKIA|ghp_|gho_)' \
    --include='*.py' --include='*.md' --include='*.txt' --include='*.json' \
    ~/dev/git/unidrive-mcp/
```
Examine every hit. Classify each as one of:
- **Real secret value** — STOP, surface to user, do not proceed.
- **CLI flag name / env-var name / doc reference / function parameter** — record the line, proceed.

Record the classification for each hit in the task notes (or chat). Proceed only if every hit is classified non-secret.

- [ ] **Step 6: No commit (preflight only)**

This task makes no changes; nothing to commit.

---

## Task 2: Author top-level `LICENSE`

**Files:**
- Create: `~/dev/git/unidrive-mcp/LICENSE`

- [ ] **Step 1: Verify the unidrive repo's LICENSE is Apache-2.0 (sanity check that we're matching it)**

Run:
```bash
head -1 ~/dev/git/unidrive/LICENSE
```
Expected: a line containing `Apache License`.

- [ ] **Step 2: Copy the unidrive LICENSE verbatim to the new repo**

Run:
```bash
cp ~/dev/git/unidrive/LICENSE ~/dev/git/unidrive-mcp/LICENSE
```

- [ ] **Step 3: Verify the copy succeeded**

Run:
```bash
diff ~/dev/git/unidrive/LICENSE ~/dev/git/unidrive-mcp/LICENSE && echo OK
```
Expected: `OK` (no diff output before it).

- [ ] **Step 4: No commit yet**

We commit everything in Task 5 as a single initial commit.

---

## Task 3: Author top-level `.gitignore`

**Files:**
- Create: `~/dev/git/unidrive-mcp/.gitignore`

- [ ] **Step 1: Write the file**

Write the following content to `~/dev/git/unidrive-mcp/.gitignore`:

```
# Python
__pycache__/
*.py[cod]
*.egg-info/
.venv/
venv/

# Environment
.env
.env.*

# Editor
.idea/
.vscode/

# Logs
*.log
```

- [ ] **Step 2: Verify**

Run:
```bash
cat ~/dev/git/unidrive-mcp/.gitignore
```
Expected: the exact content above.

- [ ] **Step 3: Confirm per-subdir `.gitignore` files are untouched**

Run:
```bash
cat ~/dev/git/unidrive-mcp/oauth-mcp/.gitignore
cat ~/dev/git/unidrive-mcp/jvm-monitor-mcp/.gitignore
```
Expected:
- `oauth-mcp/.gitignore` contains `.token-cache.json` plus `.venv/`, `__pycache__/`, `*.pyc`.
- `jvm-monitor-mcp/.gitignore` contains `.venv/`, `__pycache__/`, `*.pyc`.

These two files **stay as they are** — the top-level `.gitignore` does not replace them, only complements them.

- [ ] **Step 4: No commit yet**

---

## Task 4: Author top-level `README.md`

**Files:**
- Create: `~/dev/git/unidrive-mcp/README.md`

- [ ] **Step 1: Write the file**

Write the following exact content to `~/dev/git/unidrive-mcp/README.md`:

```markdown
# unidrive-mcp

Python MCP servers used by agents working on the
[unidrive](https://github.com/gkrost/unidrive) project. Dev tooling, not
the unidrive product MCP.

> The unidrive product's own MCP server (the one that exposes UniDrive's
> sync engine to LLM clients) lives in the unidrive repo at
> `core/app/mcp/`. It is a Kotlin Gradle module, unrelated to this repo
> beyond the shared name fragment.

## Servers

| Directory | Purpose |
|---|---|
| [`backlog-mcp/`](backlog-mcp/) | CRUD over the unidrive backlog (BACKLOG.md / CLOSED.md). |
| [`daemon-ipc-mcp/`](daemon-ipc-mcp/) | Talk to a running unidrive sync daemon over its IPC socket. |
| [`gradle-mcp/`](gradle-mcp/) | Inspect / drive the unidrive Gradle composite build. |
| [`jvm-monitor-mcp/`](jvm-monitor-mcp/) | Probe heap, threads, and uptime of a running JVM. |
| [`log-tail-mcp/`](log-tail-mcp/) | Tail and grep `unidrive.log` with anomaly summaries. |
| [`memory-mcp/`](memory-mcp/) | Cross-session memory store for agents. |
| [`oauth-mcp/`](oauth-mcp/) | Mint short-lived OAuth tokens for live integration tests. |

Each subdirectory has its own `README.md` with usage details,
`requirements.txt` for its dependencies, and a self-contained
`server.py`. There is intentionally no top-level dependency list or
task runner — the servers run standalone.

## Quick start

Pick a server, set up its venv, run it:

```bash
cd backlog-mcp
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
python server.py
```

Point your MCP client at the resulting stdio process. The per-subdir
README documents the exact tool surface.

## License

Apache-2.0. See [LICENSE](LICENSE).
```

- [ ] **Step 2: Verify**

Run:
```bash
head -3 ~/dev/git/unidrive-mcp/README.md
```
Expected first line: `# unidrive-mcp`.

- [ ] **Step 3: No commit yet**

---

## Task 5: `git init` and initial commit

**Files:**
- Create: `~/dev/git/unidrive-mcp/.git/` (via `git init`)

- [ ] **Step 1: `cd` to the target dir**

Run:
```bash
cd ~/dev/git/unidrive-mcp
```

- [ ] **Step 2: `git init` with `main` as the default branch**

Run:
```bash
git init -b main
```
Expected output: `Initialized empty Git repository in /home/gernot/dev/git/unidrive-mcp/.git/`.

- [ ] **Step 3: Stage everything**

Run:
```bash
git add .
```

- [ ] **Step 4: Verify the staged file list**

Run:
```bash
git status --short
```
Expected: every file listed with `A` prefix (added). Spot-check that:
- `LICENSE`, `README.md`, `.gitignore` are listed at top-level.
- Each `*-mcp/` subdir's `server.py`, `requirements.txt`, `README.md` are listed.
- **No** `__pycache__/`, `.venv/`, `.token-cache.json`, or `*.log` are listed (the gitignores should exclude them — if any appear, STOP and investigate).

- [ ] **Step 5: Create the initial commit**

Run:
```bash
git commit -m "chore: initial import — 7 MCP servers salvaged from unidrive

backlog-mcp, daemon-ipc-mcp, gradle-mcp, jvm-monitor-mcp,
log-tail-mcp, memory-mcp, oauth-mcp.

Per-server content is byte-identical to the source folder. New
top-level scaffolding: README.md, .gitignore, LICENSE (Apache-2.0).

See unidrive/docs/specs/2026-05-02-unidrive-mcp-standalone-repo-design.md
for design rationale."
```

- [ ] **Step 6: Verify exactly one commit on `main`**

Run:
```bash
git log --oneline
git branch --show-current
```
Expected:
- `git log --oneline` shows exactly one line.
- `git branch --show-current` shows `main`.

---

## Task 6: Push to GitHub as private repo

**Files:** none modified locally beyond `.git/config` (remote added).

- [ ] **Step 1: Create the private repo on GitHub and push in one step**

Run (from inside `~/dev/git/unidrive-mcp/`):
```bash
gh repo create gkrost/unidrive-mcp \
    --private \
    --source=. \
    --remote=origin \
    --push \
    --description "Python MCP servers used by agents working on the unidrive project (dev tooling, not the unidrive product MCP)."
```
Expected: a line confirming creation, then a push log ending in something like `branch 'main' set up to track 'origin/main' from 'origin'.`

- [ ] **Step 2: Confirm the repo is private**

Run:
```bash
gh repo view gkrost/unidrive-mcp --json visibility,defaultBranchRef,description
```
Expected: JSON containing `"visibility":"PRIVATE"`, `"defaultBranchRef":{"name":"main"}`, and the description we set.

- [ ] **Step 3: Confirm exactly one commit pushed**

Run:
```bash
gh api repos/gkrost/unidrive-mcp/commits --jq 'length'
```
Expected: `1`.

---

## Task 7: End-to-end verification from a fresh clone

**Files:** none modified. Read-only smoke test against a temporary clone.

- [ ] **Step 1: Clone into `/tmp`**

Run:
```bash
rm -rf /tmp/unidrive-mcp-test
git clone git@github.com:gkrost/unidrive-mcp.git /tmp/unidrive-mcp-test
```
Expected: clone succeeds, no errors.

- [ ] **Step 2: Verify the tree matches the source**

Run:
```bash
diff -rq ~/dev/git/unidrive-mcp /tmp/unidrive-mcp-test 2>&1 \
    | grep -v '\.git' | grep -v '__pycache__' | grep -v '\.venv'
```
Expected: empty output (every non-ignored file matches byte-for-byte).

- [ ] **Step 3: Smoke-test one server**

Run:
```bash
cd /tmp/unidrive-mcp-test/backlog-mcp
python3 -m venv .venv
. .venv/bin/activate
pip install --quiet -r requirements.txt
python server.py --help 2>&1 | head -20 || \
    timeout 2 python server.py < /dev/null 2>&1 | head -20
deactivate
```
Expected: either `--help` output **or** the server starts (and `timeout 2` kills it — exit code 124 is fine). What we're proving is that it imports cleanly and starts. A `ModuleNotFoundError`, `SyntaxError`, or `FileNotFoundError` for an absolute path is a real failure — record and surface to user.

- [ ] **Step 4: Confirm the unidrive repo is unchanged**

Run:
```bash
cd ~/dev/git/unidrive
git status --short
git log --oneline -3
```
Expected:
- `git status --short` shows clean (or only the spec/this plan files we created in unidrive — nothing else).
- The top of `git log` is the spec commit `81f1f09` (or this plan's commit if added on top).

- [ ] **Step 5: Clean up the test clone**

Run:
```bash
rm -rf /tmp/unidrive-mcp-test
```

- [ ] **Step 6: No commit**

This task is verification only.

---

## Task 8: Document completion in unidrive

**Files:**
- Modify: `~/dev/git/unidrive/docs/specs/2026-05-02-unidrive-mcp-standalone-repo-design.md` (status update)

- [ ] **Step 1: Update spec status from "Draft" to "Done"**

Edit `~/dev/git/unidrive/docs/specs/2026-05-02-unidrive-mcp-standalone-repo-design.md`:

Change the line:
```
- **Status:** Draft → awaiting user review
```
to:
```
- **Status:** Done (implemented 2026-05-02 — gkrost/unidrive-mcp)
```

- [ ] **Step 2: Verify**

Run:
```bash
grep '^- \*\*Status:\*\*' ~/dev/git/unidrive/docs/specs/2026-05-02-unidrive-mcp-standalone-repo-design.md
```
Expected: the new "Done" line.

- [ ] **Step 3: Commit in the unidrive repo**

Run:
```bash
cd ~/dev/git/unidrive
git add docs/specs/2026-05-02-unidrive-mcp-standalone-repo-design.md
git commit -m "wip(docs): mark unidrive-mcp standalone repo spec as Done

Implementation complete. Repo lives at github.com/gkrost/unidrive-mcp
(private), single initial-import commit on main."
```

- [ ] **Step 4: Verify**

Run:
```bash
git log --oneline -3
```
Expected: top commit is the one we just made.

---

## Done criteria

All of the following must be true:

1. `gh repo view gkrost/unidrive-mcp --json visibility` reports `"PRIVATE"`.
2. `gh api repos/gkrost/unidrive-mcp/commits --jq 'length'` reports `1`.
3. `cd ~/dev/git/unidrive-mcp && git log --oneline | wc -l` reports `1`.
4. `~/dev/git/unidrive-mcp/{README.md,LICENSE,.gitignore}` all exist.
5. The seven `*-mcp/` subdirs are byte-identical to their pre-task state (Task 7 step 2 verified this against the clone).
6. The unidrive repo has one new commit marking the spec Done; no other unidrive files changed.
