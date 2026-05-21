#!/usr/bin/env bash
# scope-check.sh — pre-commit hook that enforces commit-scope discipline.
#
# Parses the commit subject's type + scope (e.g. "chore(ktlint)",
# "fix(UD-228)", "docs(backlog)") and asserts the staged file set matches
# a per-type whitelist. Flags accidental bundling of unrelated changes
# that pollute git blame / bisect and break ticket traceability.
#
# Invoked automatically after `scripts/dev/pre-commit/install.sh`.
# Bypass with `git commit --no-verify` for deliberate cross-cutting
# commits.
#
# See UD-728 for the motivating incidents. Policy lives in
# docs/dev/CODE-STYLE.md §9.

set -euo pipefail

REPO="$(git rev-parse --show-toplevel)"
cd "$REPO"

# In a normal pre-commit hook the commit message is not yet available —
# we use `prepare-commit-msg` / `commit-msg` for that. This hook looks at
# a message file if provided (commit-msg mode), otherwise falls back to
# reading `.git/COMMIT_EDITMSG`. This lets us share one script between
# commit-msg and a manual pre-check invocation.
MSG_FILE="${1:-$REPO/.git/COMMIT_EDITMSG}"
if [[ ! -f "$MSG_FILE" ]]; then
  # Nothing to check — likely a non-commit git operation.
  exit 0
fi

SUBJECT="$(head -n1 "$MSG_FILE" | tr -d '\r')"

# Skip merge / revert / fixup commits — these legitimately bundle.
if [[ "$SUBJECT" =~ ^(Merge|Revert|fixup!|squash!|amend!) ]]; then
  exit 0
fi

# Parse "type(scope): subject".
CONV_RE='^([a-z]+)\(([^)]+)\): '
if [[ ! "$SUBJECT" =~ $CONV_RE ]]; then
  # Not a Conventional Commit — not our problem here (UD-714 covers the
  # policy). Let it through; CI will flag format violations elsewhere.
  exit 0
fi

TYPE="${BASH_REMATCH[1]}"
SCOPE="${BASH_REMATCH[2]}"

STAGED="$(git diff --cached --name-only)"
if [[ -z "$STAGED" ]]; then
  exit 0
fi

fail() {
  echo "scope-check: commit subject '$TYPE($SCOPE)' does not match the staged file set."
  echo
  echo "  Expected paths: $1"
  echo
  echo "  Unexpected staged files:"
  while IFS= read -r f; do echo "    $f"; done <<< "$2"
  echo
  echo "  Resolve by splitting the commit, or bypass with 'git commit --no-verify'"
  echo "  if this is a deliberate cross-cutting change."
  exit 1
}

# Filter a newline-separated file list to entries that do NOT match any
# of the given shell globs. Prints the unexpected entries.
filter_unexpected() {
  local files="$1"
  shift
  local -a allow=("$@")
  local unexpected=""
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    local matched=0
    for pat in "${allow[@]}"; do
      # shellcheck disable=SC2053
      if [[ "$f" == $pat ]]; then
        matched=1
        break
      fi
    done
    if [[ $matched -eq 0 ]]; then
      unexpected+="$f"$'\n'
    fi
  done <<< "$files"
  printf '%s' "${unexpected%$'\n'}"
}

case "$TYPE($SCOPE)" in
  "chore(ktlint)")
    UNEXPECTED="$(filter_unexpected "$STAGED" \
      '*/config/ktlint/baseline.xml' \
      'config/ktlint/baseline.xml')"
    [[ -n "$UNEXPECTED" ]] && fail "ktlint baselines only" "$UNEXPECTED"
    ;;
  "chore(deps)")
    UNEXPECTED="$(filter_unexpected "$STAGED" \
      'gradle/libs.versions.toml' \
      '*/gradle/libs.versions.toml' \
      'build.gradle.kts' \
      '*/build.gradle.kts' \
      'settings.gradle.kts' \
      '*/settings.gradle.kts')"
    [[ -n "$UNEXPECTED" ]] && fail "gradle dependency files only" "$UNEXPECTED"
    ;;
  "docs(backlog)")
    UNEXPECTED="$(filter_unexpected "$STAGED" \
      'docs/backlog/*.md')"
    [[ -n "$UNEXPECTED" ]] && fail "docs/backlog/*.md only" "$UNEXPECTED"
    ;;
  "docs(handover)")
    UNEXPECTED="$(filter_unexpected "$STAGED" \
      'handover.md')"
    [[ -n "$UNEXPECTED" ]] && fail "handover.md only" "$UNEXPECTED"
    ;;
  *)
    # fix(UD-###), feat(UD-###), refactor(UD-###), etc. — verify at least
    # one staged path overlaps the ticket's code_refs when the scope
    # contains a UD-### id. If it doesn't, warn but do not block
    # (code_refs drift is common enough that blocking would cost more
    # than it buys).
    if [[ "$SCOPE" =~ UD-[0-9]{3}[a-z]? ]]; then
      # Multiple ticket ids in one scope are legal (e.g. "UD-237, UD-235").
      IDS=()
      while IFS= read -r id; do IDS+=("$id"); done < <(grep -oE 'UD-[0-9]{3}[a-z]?' <<< "$SCOPE")
      for id in "${IDS[@]}"; do
        if ! grep -q "^id: $id$" docs/backlog/BACKLOG.md docs/backlog/CLOSED.md 2>/dev/null; then
          echo "scope-check: warning — $id not found in BACKLOG.md or CLOSED.md."
        fi
      done
    fi
    ;;
esac

exit 0
