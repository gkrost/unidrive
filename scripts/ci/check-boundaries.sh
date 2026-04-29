#!/usr/bin/env bash
# Enforces boundary rule §4.1 of docs/superpowers/specs/2026-04-18-closed-integration-design.md:
# no public module may reference private-repo package names or paths.
set -euo pipefail

cd "$(dirname "$0")/../.."

# Search public tree (core/, scripts/) for references to private
# packages. The benchmark package is the known private package today;
# add more names here as new private modules land. (ui/ + protocol/ +
# shell-win/ are no longer paths to consider — see ADRs 0011-0013.)
FORBIDDEN_PATTERNS=(
    'org\.krost\.unidrive\.benchmark'
    'unidrive-closed'
    'org\.krost\.unidrive\.e2e'
)

failed=0
for pat in "${FORBIDDEN_PATTERNS[@]}"; do
    # Exclude docs/ (specs + plans reference private package names as prose)
    # and this very script.
    matches=$(git grep -l -E "$pat" -- \
        'core/' 'scripts/' \
        ':!scripts/ci/check-boundaries.sh' \
        2>/dev/null || true)
    if [[ -n "$matches" ]]; then
        echo "Boundary violation: public code references '$pat'"
        echo "$matches"
        failed=1
    fi
done

if [[ "$failed" -ne 0 ]]; then
    echo
    echo "Public modules must not reference private-repo packages."
    echo "See docs/superpowers/specs/2026-04-18-closed-integration-design.md §4."
    exit 1
fi
echo "check-boundaries.sh: OK"
