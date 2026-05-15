#!/usr/bin/env bash
# check-version-drift.sh — cross-repo dependency version drift detector.
#
# Compares dependency versions between the core repo and the unidrive-android
# sibling repo. Tracked deps: kotlin, kotlinx-coroutines, kotlinx-serialization, ktor.
#
# Output policy (per spec §2.3):
# - Drift between two known versions → ::warning::, exit 0 (warn-only this round).
# - Layout/configuration failure → exit non-zero with clear error.
#
# Layout assumed: core repo CWD on script entry; sibling Android repo at ../unidrive-android.
set -euo pipefail

CORE_CATALOG="core/gradle/libs.versions.toml"
ANDROID_REPO="../unidrive-android"
ANDROID_CATALOG="$ANDROID_REPO/gradle/libs.versions.toml"

if [[ ! -f "$CORE_CATALOG" ]]; then
    echo "ERROR: core catalog not found at $CORE_CATALOG (CWD=$(pwd))" >&2
    exit 2
fi
if [[ ! -d "$ANDROID_REPO" ]]; then
    echo "ERROR: sibling Android repo not found at $ANDROID_REPO (CWD=$(pwd))" >&2
    echo "Hint: this script expects core CWD with ../unidrive-android as sibling." >&2
    exit 2
fi
if [[ ! -f "$ANDROID_CATALOG" ]]; then
    echo "ERROR: Android catalog not found at $ANDROID_CATALOG" >&2
    exit 2
fi

# Extract a version from a libs.versions.toml [versions] section.
# $1 = catalog path, $2 = key (e.g. "kotlin")
extract_catalog_version() {
    local catalog="$1" key="$2"
    awk -v k="$key" '
        /^\[versions\]/ { in_section = 1; next }
        /^\[/ { in_section = 0 }
        in_section && $1 == k {
            # Match: kotlin = "2.3.21"
            match($0, /"[^"]+"/)
            if (RSTART > 0) print substr($0, RSTART + 1, RLENGTH - 2)
            exit
        }
    ' "$catalog"
}

# Fallback: grep build.gradle.kts files for a hardcoded dep coordinate.
# $1 = repo root, $2 = artifact id (e.g. "kotlinx-serialization-json")
extract_hardcoded_version() {
    local repo="$1" artifact="$2"
    # Match e.g. implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    # Capture the version after the artifact name. `|| true` so a no-match grep
    # (exit 1) doesn't abort the caller under set -e + pipefail.
    { grep -rohE "${artifact}:[0-9][^\"']+" "$repo" --include="*.gradle.kts" 2>/dev/null || true; } \
        | head -1 \
        | sed -E "s/^${artifact}://"
}

# Map of tracked dep → (catalog key, fallback artifact id).
# Catalog keys differ across the two repos (e.g. core uses "kotlinx-coroutines",
# Android uses "coroutines"). Per-side keys handled below.
declare -A TRACKED=(
    [kotlin]="kotlin|kotlin"
    [kotlinx-coroutines]="kotlinx-coroutines|coroutines"
    [kotlinx-serialization]="kotlinx-serialization|kotlinx-serialization"
    [ktor]="ktor|ktor"
)

drift_count=0
checked_count=0

for dep in "${!TRACKED[@]}"; do
    IFS='|' read -r core_key android_key <<< "${TRACKED[$dep]}"

    core_version=$(extract_catalog_version "$CORE_CATALOG" "$core_key")
    android_version=$(extract_catalog_version "$ANDROID_CATALOG" "$android_key")

    # Fallback to grepping build.gradle.kts files if catalog miss
    if [[ -z "$android_version" ]]; then
        # Map catalog key → artifact id for hardcoded lookup
        case "$dep" in
            kotlinx-serialization) artifact="kotlinx-serialization-json" ;;
            kotlinx-coroutines) artifact="kotlinx-coroutines-core" ;;
            ktor) artifact="ktor-client-core" ;;
            kotlin) artifact="" ;;  # Kotlin version isn't in build.gradle.kts as a coord
            *) artifact="" ;;
        esac
        if [[ -n "$artifact" ]]; then
            android_version=$(extract_hardcoded_version "$ANDROID_REPO" "$artifact")
        fi
    fi

    if [[ -z "$core_version" || -z "$android_version" ]]; then
        echo "::warning::tracked dep '$dep' not found in either catalog or build script — drift detection disabled for this dep"
        continue
    fi

    checked_count=$((checked_count + 1))
    if [[ "$core_version" != "$android_version" ]]; then
        echo "::warning::dependency drift: '$dep' core=$core_version android=$android_version"
        drift_count=$((drift_count + 1))
    fi
done

echo "version-drift check complete: checked=$checked_count drift=$drift_count"
# Warn-only this round.
exit 0
