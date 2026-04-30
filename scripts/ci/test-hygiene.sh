#!/usr/bin/env bash
# UD-704-residual: lint test files for the patterns that produced the original
# UD-704 misleading-test bugs.
#
# Two grep rules:
#  1. `if (...!exists...) return` — pre-UD-704 some tests "passed" by
#     short-circuiting whenever the missing precondition meant the assertion
#     would have failed. The test reported green; the contract was unchecked.
#     (The CliSmokeTest assumeTrue conversion in c5bc05d is the canonical fix
#     shape — assumeTrue() skips loudly; `if(...)return` lies silently.)
#  2. `.contains("not yet")` / `.contains("does not support")` — these
#     witness an implementation detail of an "unsupported" message, which
#     ADR-0005 / UD-301 made structurally typed via CapabilityResult.Unsupported.
#     Tests asserting on string content of error messages re-cement
#     implementation rather than capability contract.
#
# Cheap by design — pure grep + exit code so it can run on any host without
# a JVM toolchain. Wired into .github/workflows/build.yml under the
# core / matrix.os = ubuntu-latest job.
#
# Exit codes: 0 = clean, 1 = at least one violation found.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

VIOLATIONS=0

echo "==> Scanning test files for UD-704 anti-patterns..."

# Rule 1: if (... !exists ...) return  inside test files.
# Excludes legitimate non-test usages like StateDatabase precondition checks.
RULE1=$(grep -rEn 'if[[:space:]]*\([^)]*!.*exists.*\)[[:space:]]*return' \
    core/*/src/test core/*/*/src/test 2>/dev/null || true)
if [[ -n "$RULE1" ]]; then
    echo
    echo "VIOLATION: 'if (...!exists...) return' pattern in test files."
    echo "Tests that short-circuit on missing preconditions report green even"
    echo "when their contract was never actually checked. Use assumeTrue()"
    echo "(JUnit) to skip loudly, or restructure the precondition into an"
    echo "explicit fixture setup that always succeeds."
    echo
    echo "$RULE1"
    VIOLATIONS=$((VIOLATIONS + 1))
fi

# Rule 2: .contains("not yet") OR .contains("does not support") in test files.
RULE2=$(grep -rEn '\.contains\("not yet"\)|\.contains\("does not support"\)' \
    core/*/src/test core/*/*/src/test 2>/dev/null || true)
if [[ -n "$RULE2" ]]; then
    echo
    echo "VIOLATION: implementation-detail string match in test assertion."
    echo "ADR-0005 / UD-301 introduced CapabilityResult.Unsupported as the"
    echo "structurally-typed contract for 'this provider doesn't support X'."
    echo "Tests should assert on the type (e.g. result is Unsupported), not"
    echo "on the substring of an error message."
    echo
    echo "$RULE2"
    VIOLATIONS=$((VIOLATIONS + 1))
fi

if [[ $VIOLATIONS -eq 0 ]]; then
    echo "==> Clean: no UD-704 anti-patterns found."
    exit 0
fi

echo
echo "==> $VIOLATIONS rule(s) violated. See above."
exit 1
