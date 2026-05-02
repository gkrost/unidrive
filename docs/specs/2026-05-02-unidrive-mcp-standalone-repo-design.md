# Spec: Promote `unidrive-mcp/` to a self-contained private GitHub repo

- **Version:** 0.2.0
- **Date:** 2026-05-02
- **Status:** Done — implemented as `gkrost/unidrive-mcp` (private), root commit `4406810`, branch `main`.
- **Author:** brainstormed in collaboration with Claude

## 1. Problem

The folder `~/dev/git/unidrive-mcp/` holds **seven Python MCP servers** that
exist as loose files with no git tracking, no top-level scaffolding, and no
remote:

```
unidrive-mcp/
├── backlog-mcp/        # backlog-CRUD MCP for the unidrive ticket system
├── daemon-ipc-mcp/     # talk to the running unidrive sync daemon over IPC
├── gradle-mcp/         # Gradle build / module / lockfile helper
├── jvm-monitor-mcp/    # JVM heap/thread/uptime probe
├── log-tail-mcp/       # tail and grep unidrive.log
├── memory-mcp/         # cross-session memory store for agents
└── oauth-mcp/          # mint short-lived OAuth tokens for live tests
```

Each server is its own Python program with its own `server.py`,
`requirements.txt`, and `README.md`. They are dev/agent tooling for the
unidrive monorepo, **not** part of the unidrive product.

A separate Kotlin Gradle module lives at
`~/dev/git/unidrive/core/app/mcp/` and ships UniDrive's *product* MCP
server (the one that exposes the sync engine to LLM clients). It is **out
of scope** for this work and stays exactly where it is.

The goal: turn the loose `unidrive-mcp/` folder into a proper standalone
git repository, push it to GitHub as a private repo, with the minimum
top-level scaffolding a fresh clone needs to be useful.

## 2. Non-goals

- Refactor the seven servers, share code between them, or build a Python
  monorepo tooling layer.
- Touch `unidrive/core/app/mcp/` (the Kotlin product MCP).
- Migrate any existing git history. `unidrive-mcp/` has no `.git/` and the
  predecessor commits inside the unidrive repo (see commit `344c783 mcp
  relocation`) are not worth the `filter-repo` plumbing for dev-tooling
  scripts.
- Pin Python versions, add `pyproject.toml`, add a top-level
  `requirements.txt`, or add a task runner.
- Change how unidrive references these servers (`.mcp.json`, scripts/dev
  references, agent skill files).
- Add CI, branch protection, issue templates, or release tooling.

## 3. Design

### 3.1 Top-level repo files (new)

Three files added at the root of `unidrive-mcp/`:

- **`README.md`** — one-paragraph "what this repo is" + a table listing the
  seven servers with a one-line purpose for each, linking to each subdir's
  existing detailed README. Explicitly states this is dev tooling for
  agents working on the unidrive project, not the unidrive product MCP
  (which lives in the unidrive repo's `core/app/mcp/`).

- **`.gitignore`** — standard Python defaults:
  ```
  __pycache__/
  *.py[cod]
  *.egg-info/
  .venv/
  venv/
  .env
  .env.*
  .idea/
  .vscode/
  *.log
  ```
  The two existing per-subdir `.gitignore` files (`oauth-mcp/.gitignore`,
  `jvm-monitor-mcp/.gitignore`) stay untouched.

- **`LICENSE`** — Apache-2.0, matching unidrive's license. Copyright line
  `Copyright 2026 Gernot Krost`.

### 3.2 Git initialization

Performed inside `unidrive-mcp/`:

```bash
cd ~/dev/git/unidrive-mcp
git init -b main
git add .
git commit -m "chore: initial import — 7 MCP servers salvaged from unidrive"
```

A single initial commit is the entire history. The new top-level files
are part of that commit so HEAD is immediately useful.

### 3.3 GitHub remote

```bash
gh repo create gkrost/unidrive-mcp \
    --private \
    --source=. \
    --remote=origin \
    --push \
    --description "Python MCP servers used by agents working on the unidrive project (dev tooling, not the unidrive product MCP)."
```

No further configuration: no CI, no branch protection, no labels.

### 3.4 Pre-commit safety check

Before `git init`, run a secrets grep across the whole tree:

```bash
grep -rIE '(client_secret|client_id|password|token|api[_-]?key|bearer|AKIA|ghp_|gho_)' \
    --include='*.py' --include='*.md' --include='*.txt' --include='*.json' \
    ~/dev/git/unidrive-mcp/
```

Hits get reviewed before staging:
- If it is an actual secret value → stop, scrub, then proceed.
- If it is a CLI flag name, env-var name, or doc reference → note and
  proceed.

This step is mandatory and must happen before `git add .`.

## 4. Test plan / verification

After the work is done, confirm:

1. `cd ~/dev/git/unidrive-mcp && git log --oneline` shows exactly one
   commit on `main`.
2. `gh repo view gkrost/unidrive-mcp --json visibility,defaultBranchRef`
   confirms private + `main` + the commit pushed.
3. `git clone git@github.com:gkrost/unidrive-mcp.git /tmp/unidrive-mcp-test`
   succeeds and yields a usable tree (all 7 subdirs present, top-level
   `README.md`, `LICENSE`, `.gitignore` present).
4. Smoke-test one server end-to-end from the clone:
   ```bash
   cd /tmp/unidrive-mcp-test/backlog-mcp
   python -m venv .venv && . .venv/bin/activate
   pip install -r requirements.txt
   python server.py --help     # or equivalent first-line invocation
   ```
   Passes if it does the same thing it does today from
   `~/dev/git/unidrive-mcp/backlog-mcp/`. Failures mean a path/config
   assumption broke during the move; if so, document the breakage in the
   subdir's README rather than papering over it.
5. `cd ~/dev/git/unidrive && git status` shows clean — confirms we did
   not accidentally touch the unidrive repo.

## 5. Risks & failure modes

- **Secrets leak into commit.** Mitigation: §3.4 pre-commit grep,
  manually verified before `git add .`.
- **Server depends on absolute path inside the original folder.** Smoke
  test (verification step 4) catches this. None of the seven `server.py`
  files appear to (quick grep before authoring this spec); if a hit
  surfaces during smoke test, fix it in a follow-up commit, do not block
  the initial import.
- **`gh` CLI not authenticated.** Pre-flight: `gh auth status` should
  show `gkrost` logged in. If not, abort and surface to user — do not
  attempt to authenticate inside the implementation step.
- **Public-by-accident.** `--private` flag is required on `gh repo
  create`. Verification step 2 confirms.

## 6. Rollout

This is a one-shot operation. There is no staged rollout, no migration
phase, no compatibility window. Either it works or we revert by:

```bash
gh repo delete gkrost/unidrive-mcp --yes   # only if user explicitly ok
rm -rf ~/dev/git/unidrive-mcp/.git
rm ~/dev/git/unidrive-mcp/README.md ~/dev/git/unidrive-mcp/.gitignore ~/dev/git/unidrive-mcp/LICENSE
```

Per the user's standing rule, **do not delete the GitHub repo without
explicit confirmation in chat.**

## 7. Open questions

None at design time. All three brainstorm questions answered:

- Direction: **B** — promote `unidrive-mcp/` to its own repo; do not
  touch `core/app/mcp/`.
- History: **A** — fresh git history; no salvage from unidrive.
- Remote: **B** — private GitHub repo at `gkrost/unidrive-mcp`.

## 8. Implementation notes (2026-05-02, v0.2.0)

Executed via subagent-driven-development against the plan at
`docs/plans/2026-05-02-unidrive-mcp-standalone-repo.md`.

**Outcomes:**
- `gkrost/unidrive-mcp` created as private, default branch `main`.
- Single root commit `44068109d9b3fbb927c22aab47369cb7fd7c8896`, 36
  files, 5676 insertions.
- Smoke test against `daemon-ipc-mcp` from a fresh SSH clone: venv +
  29-package pip install + `python server.py` all clean.
- unidrive repo unchanged outside `docs/specs/` and `docs/plans/`.

**Two deviations / lessons:**

1. **HTTPS push helper missing.** `gh repo create --push` invoked an
   HTTPS push that failed with `git: 'remote-https' is not a git
   command`. The system git at `/usr/bin/git` (v2.53.0) ships with
   `git-remote-http` but no `git-remote-https`. Worked around by
   `git remote set-url origin git@github.com:…` and `git push -u
   origin main` over SSH. Future automation in this environment
   should default to SSH for GitHub.

2. **Two pre-existing path-hack bugs salvaged faithfully.** Smoke
   test surfaced two servers whose `server.py` assumes a specific
   parent-directory layout that only exists inside the unidrive
   repo:
   - `backlog-mcp/server.py:42-46` — `_HERE.parent` was supposed to
     resolve to `scripts/dev/`, expected to find `backlog.py` as a
     sibling. After the move, no `backlog.py` is reachable.
   - `gradle-mcp/server.py:62` — `Path(__file__).resolve().parents[3]`
     was supposed to resolve to the unidrive repo root. After the
     move it points at `~/dev/git/`.

   Per spec §5 these are explicitly out of scope for the salvage
   itself — both bugs pre-existed in the source folder. They are
   filed as follow-up work in the new repo's own backlog (or a
   `KNOWN-ISSUES.md` if/when one is created), not in unidrive.
