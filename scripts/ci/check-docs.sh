#!/usr/bin/env bash
# check-docs.sh — lightweight doc-drift checker (UD-762).
#
# Salvaged from logic-arts-official/unidrive HEAD b8e4223 (2026-04-16) and
# adapted to the current monorepo layout (core/* + docs/*). Performs cheap
# grep/regex consistency checks between source files and human-authored
# documentation. Designed to run in <1s with no Gradle invocation, so it's
# suitable for a pre-commit hook or a CI lint step.
#
# Complements (does not replace) the heavier checks under scripts/dev/
# (backlog-sync.kts, ktlint, MCP servers).
#
# Exit codes:
#   0 — no drift detected
#   1 — one or more drift findings
#   2 — script wiring error (missing input file, etc.)
#
# Run from the repo root:
#   bash scripts/ci/check-docs.sh

set -euo pipefail

# Resolve repo root from script location so the script works from any CWD.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

ERRORS=0
err() {
    echo "FAIL: $*" >&2
    ERRORS=$((ERRORS + 1))
}

# Required inputs — bail with exit 2 if any are missing.
require_file() {
    if [[ ! -f "$1" ]]; then
        echo "ERROR: required file not found: $1" >&2
        exit 2
    fi
}

CATALOG="core/gradle/libs.versions.toml"
SETTINGS="core/settings.gradle.kts"
CLI_MAIN="core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt"
SYNC_ACTION="core/app/sync/src/main/kotlin/org/krost/unidrive/sync/model/SyncAction.kt"
ARCH_DOC="docs/ARCHITECTURE.md"
SPECS_DOC="docs/SPECS.md"
AGENT_SYNC_DOC="docs/AGENT-SYNC.md"
BACKLOG_DOC="docs/backlog/BACKLOG.md"

for f in "$CATALOG" "$SETTINGS" "$CLI_MAIN" "$SYNC_ACTION" "$ARCH_DOC" "$SPECS_DOC" "$AGENT_SYNC_DOC" "$BACKLOG_DOC"; do
    require_file "$f"
done

# Helper: read a version from libs.versions.toml [versions] section.
catalog_version() {
    local key="$1"
    awk -v k="$key" '
        /^\[versions\]/ { in_section = 1; next }
        /^\[/ { in_section = 0 }
        in_section && $1 == k {
            match($0, /"[^"]+"/)
            if (RSTART > 0) print substr($0, RSTART + 1, RLENGTH - 2)
            exit
        }
    ' "$CATALOG"
}

# 1. Kotlin version in libs.versions.toml vs SPECS.md toolchain table.
CATALOG_KOTLIN="$(catalog_version kotlin)"
SPECS_KOTLIN="$(grep -oE 'Kotlin \| [0-9]+\.[0-9]+\.[0-9]+' "$SPECS_DOC" \
    | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || true)"
if [[ -n "$CATALOG_KOTLIN" && -n "$SPECS_KOTLIN" && "$CATALOG_KOTLIN" != "$SPECS_KOTLIN" ]]; then
    err "Kotlin version: $CATALOG=$CATALOG_KOTLIN, $SPECS_DOC=$SPECS_KOTLIN"
fi

# 2. Logback version in libs.versions.toml vs ARCHITECTURE.md.
CATALOG_LOGBACK="$(catalog_version logback)"
ARCH_LOGBACK="$(grep -oE 'Logback [0-9]+\.[0-9]+\.[0-9]+' "$ARCH_DOC" \
    | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || true)"
if [[ -n "$CATALOG_LOGBACK" && -n "$ARCH_LOGBACK" && "$CATALOG_LOGBACK" != "$ARCH_LOGBACK" ]]; then
    err "Logback version: $CATALOG=$CATALOG_LOGBACK, $ARCH_DOC=$ARCH_LOGBACK"
fi

# 3. Module count in settings.gradle.kts vs SPECS.md "(N Gradle modules)" claim.
# settings.gradle.kts lists modules as quoted "app:..." / "providers:..." entries.
SETTINGS_MODULES="$(grep -oE '"(app|providers):[a-z0-9-]+"' "$SETTINGS" | sort -u | wc -l)"
SPECS_MODULE_COUNT="$(grep -oE '\([0-9]+ Gradle modules\)' "$SPECS_DOC" \
    | head -1 | grep -oE '[0-9]+' || true)"
if [[ -n "$SPECS_MODULE_COUNT" && "$SETTINGS_MODULES" -ne "$SPECS_MODULE_COUNT" ]]; then
    err "Module count: $SETTINGS lists $SETTINGS_MODULES, $SPECS_DOC says $SPECS_MODULE_COUNT"
fi

# 4. CLI subcommand count: picocli `*Command::class` entries in Main.kt vs
# SPECS.md "N subcommands" claim. We count entries inside the `subcommands = [...]`
# block, not stray references elsewhere in the file.
CLI_SUBCMD_COUNT="$(awk '
    /^[[:space:]]*subcommands = \[/ { in_block = 1; next }
    in_block && /^[[:space:]]*\],/ { exit }
    in_block && /Command::class,/ { count++ }
    END { print count + 0 }
' "$CLI_MAIN")"
SPECS_SUBCMD_COUNT="$(grep -oE '[0-9]+ subcommands' "$SPECS_DOC" \
    | head -1 | grep -oE '[0-9]+' || true)"
if [[ -n "$SPECS_SUBCMD_COUNT" && "$CLI_SUBCMD_COUNT" -ne "$SPECS_SUBCMD_COUNT" ]]; then
    err "CLI subcommand count: $CLI_MAIN has $CLI_SUBCMD_COUNT, $SPECS_DOC says $SPECS_SUBCMD_COUNT"
fi

# 5. SyncAction sealed-subtypes count vs any "N actions" / "N cases" claim in
# SPECS.md or ARCHITECTURE.md. Counts top-level `data class` / `object`
# declarations in the sealed file.
ACTION_COUNT="$(grep -cE '^[[:space:]]*(data class|object) [A-Z]' "$SYNC_ACTION" || true)"
DOC_ACTION_COUNT="$(grep -hoE '[0-9]+ (SyncAction|sealed) ' "$SPECS_DOC" "$ARCH_DOC" 2>/dev/null \
    | head -1 | grep -oE '[0-9]+' || true)"
if [[ -n "$DOC_ACTION_COUNT" && "$ACTION_COUNT" -ne "$DOC_ACTION_COUNT" ]]; then
    err "SyncAction subtypes: code has $ACTION_COUNT, docs say $DOC_ACTION_COUNT"
fi

# 6. Provider modules in settings.gradle.kts ↔ SPECS.md provider table.
# Cross-check: every providers/* module in settings should be mentioned in the
# SPECS.md module table; every provider mentioned in the SPECS.md module table
# should exist as a module.
SETTINGS_PROVIDERS="$(grep -oE '"providers:[a-z0-9-]+"' "$SETTINGS" \
    | sed -E 's/"providers:(.*)"/\1/' | sort -u)"
SPECS_PROVIDERS="$(grep -oE '`providers/[a-z0-9-]+`' "$SPECS_DOC" \
    | sed -E 's,`providers/(.*)`,\1,' | sort -u)"
MISSING_IN_DOCS="$(comm -23 <(echo "$SETTINGS_PROVIDERS") <(echo "$SPECS_PROVIDERS") || true)"
MISSING_IN_CODE="$(comm -13 <(echo "$SETTINGS_PROVIDERS") <(echo "$SPECS_PROVIDERS") || true)"
if [[ -n "$MISSING_IN_DOCS" ]]; then
    err "Provider modules in $SETTINGS but not in $SPECS_DOC: $(echo "$MISSING_IN_DOCS" | tr '\n' ' ')"
fi
if [[ -n "$MISSING_IN_CODE" ]]; then
    err "Provider modules in $SPECS_DOC but not in $SETTINGS: $(echo "$MISSING_IN_CODE" | tr '\n' ' ')"
fi

# 7. AGENT-SYNC.md ID-range table vs BACKLOG.md frontmatter.
# Parse the ID-range table to derive {range -> allowed categories}. Then for
# every open ticket in BACKLOG.md, verify its `category:` matches one of the
# categories permitted by its ID's range. The reserved 500-series and
# 600-series buckets are allowed to host *any* category per the rebinding
# precedent in AGENT-SYNC.md ("Repurpose retired ranges").
#
# (Inline range references intentionally avoid the "UD-NNN" form because
# backlog-sync.kts scans this file for UD-XXX strings and would flag them as
# orphan refs. We name the buckets by their leading digit instead.)
declare -A RANGE_CATS=(
    [0]="architecture"
    [1]="security"
    [2]="core"
    [3]="providers"
    [4]="cli shell-win"
    [5]=""        # 500-bucket reserved (was ui) — accept any
    [6]=""        # 600-bucket reserved (was protocol) — accept any
    [7]="tooling docs"
    [8]="tests"
    [9]="experimental"
)

# Extract (id, category) pairs from BACKLOG.md frontmatter blocks.
# A frontmatter block is `---\nid: UD-XXX\n...\ncategory: foo\n...\n---`.
# Section separators (consecutive `---` around `## Header` lines) also use `---`
# but don't carry id/category. We sidestep the toggle problem by emitting on
# every `---` (and at EOF) but only when we have both an id and a category
# since the previous reset — section separators never accumulate either, so
# they're naturally skipped.
backlog_pairs() {
    awk '
        /^---$/ {
            if (id != "" && cat != "") print id " " cat
            id = ""; cat = ""
            next
        }
        /^id:[[:space:]]+UD-[0-9]+[a-z]?[[:space:]]*$/ {
            id = $2
        }
        /^category:[[:space:]]+/ {
            cat = $2
        }
        END {
            if (id != "" && cat != "") print id " " cat
        }
    ' "$BACKLOG_DOC"
}

OUT_OF_RANGE=""
while IFS=' ' read -r udid cat; do
    [[ -z "${udid:-}" ]] && continue
    # Strip optional trailing letter suffix and leading "UD-" then take leading digits.
    num="${udid#UD-}"
    num="${num%%[a-z]}"
    bucket="${num:0:1}"  # first digit -> 0..9 range bucket
    # AGENT-SYNC.md range mapping: 001-099 -> bucket 0, 100-199 -> bucket 1, etc.
    allowed="${RANGE_CATS[$bucket]:-}"
    if [[ -z "$allowed" ]]; then
        # Reserved range, accept any category.
        continue
    fi
    # Check membership.
    match=0
    for a in $allowed; do
        if [[ "$a" == "$cat" ]]; then
            match=1
            break
        fi
    done
    if [[ "$match" -eq 0 ]]; then
        OUT_OF_RANGE+="$udid(category=$cat, range allows: $allowed)"$'\n'
    fi
done <<< "$(backlog_pairs)"

if [[ -n "$OUT_OF_RANGE" ]]; then
    # Drift summary — print the count and the first 5 examples to keep CI logs short.
    count="$(printf "%s" "$OUT_OF_RANGE" | wc -l)"
    err "BACKLOG.md has $count IDs whose category does not match the AGENT-SYNC.md range table (first 5 below):"
    printf "%s" "$OUT_OF_RANGE" | head -5 | sed 's/^/    /' >&2
fi

echo "---"
if [[ "$ERRORS" -gt 0 ]]; then
    echo "$ERRORS doc-code inconsistency(ies) found." >&2
    exit 1
else
    echo "All doc-code checks passed."
fi
