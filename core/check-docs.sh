#!/usr/bin/env bash
set -euo pipefail

ERRORS=0
err() { echo "FAIL: $*" >&2; ERRORS=$((ERRORS + 1)); }

# Helper: read version from libs.versions.toml
catalog_version() {
    grep "^$1 = " gradle/libs.versions.toml | head -1 | grep -oP '"[0-9]+\.[0-9]+[^"]*"' | tr -d '"'
}

# 1. Kotlin version in libs.versions.toml vs ARCHITECTURE.md
CATALOG_KOTLIN=$(catalog_version kotlin)
ARCH_KOTLIN=$(grep -oP 'Kotlin [0-9]+\.[0-9]+\.[0-9]+' ARCHITECTURE.md | head -1 | grep -oP '[0-9]+\.[0-9]+\.[0-9]+')
if [[ -n "$CATALOG_KOTLIN" && -n "$ARCH_KOTLIN" && "$CATALOG_KOTLIN" != "$ARCH_KOTLIN" ]]; then
    err "Kotlin version: libs.versions.toml=$CATALOG_KOTLIN, ARCHITECTURE.md=$ARCH_KOTLIN"
fi

# 2. Module count in CLAUDE.md vs settings.gradle.kts
SETTINGS_MODULES=$(grep -oP '"[^"]*"' settings.gradle.kts | grep -c ':')
CLAUDE_MODULE_WORD=$(grep -oP '\b[A-Z][a-z]+ Gradle modules' CLAUDE.md | head -1 | awk '{print $1}')
declare -A WORD_TO_NUM=([Eight]=8 [Nine]=9 [Ten]=10 [Eleven]=11 [Twelve]=12 [Thirteen]=13)
CLAUDE_MODULE_COUNT=${WORD_TO_NUM[$CLAUDE_MODULE_WORD]:-0}
if [[ "$CLAUDE_MODULE_COUNT" -gt 0 && "$SETTINGS_MODULES" -ne "$CLAUDE_MODULE_COUNT" ]]; then
    err "CLAUDE.md says '$CLAUDE_MODULE_WORD Gradle modules' ($CLAUDE_MODULE_COUNT) but settings.gradle.kts has $SETTINGS_MODULES"
fi

# 3. CLI subcommands in @Command annotation vs CLAUDE.md list
MAIN_SUBS=$(grep -oP '\w+Command::class' app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt | sed 's/Command::class//' | tr '[:upper:]' '[:lower:]' | sort)
CLAUDE_SUBS=$(grep 'PicoCLI subcommands' CLAUDE.md | grep -oP '`[a-z]+`' | tr -d '`' | sort -u)
MISSING_IN_DOCS=$(comm -23 <(echo "$MAIN_SUBS") <(echo "$CLAUDE_SUBS"))
if [[ -n "$MISSING_IN_DOCS" ]]; then
    err "CLI subcommands in code but not in CLAUDE.md: $MISSING_IN_DOCS"
fi

# 4. SyncAction sealed subtypes count
ACTION_COUNT=$(grep -cE 'data class|object ' app/sync/src/main/kotlin/org/krost/unidrive/sync/model/SyncAction.kt 2>/dev/null || echo 0)
CLAUDE_ACTION_COUNT=$(grep -oP '\d+ cases' CLAUDE.md | head -1 | grep -oP '\d+')
if [[ -n "$CLAUDE_ACTION_COUNT" && "$ACTION_COUNT" -ne "$CLAUDE_ACTION_COUNT" ]]; then
    err "SyncAction subtypes: code=$ACTION_COUNT, CLAUDE.md says $CLAUDE_ACTION_COUNT"
fi

# 5. Built-in provider modules in settings.gradle.kts vs CLAUDE.md
BUILT_IN=$(grep -oP 'providers:[a-z0-9]+' settings.gradle.kts | sed 's/providers://' | sort)
DOC_TYPES=$(grep -oP 'providers/(onedrive|rclone|s3|sftp|webdav|hidrive|internxt|localfs)' CLAUDE.md | grep -oP 'onedrive|rclone|s3|sftp|webdav|hidrive|internxt|localfs' | sort -u || true)
if [[ -n "$BUILT_IN" && -n "$DOC_TYPES" ]]; then
    MISSING=$(comm -23 <(echo "$BUILT_IN") <(echo "$DOC_TYPES"))
    if [[ -n "$MISSING" ]]; then
        err "Provider modules in settings.gradle.kts but not in CLAUDE.md: $MISSING"
    fi
fi

# 6. Logback version in libs.versions.toml vs ARCHITECTURE.md
CATALOG_LOGBACK=$(catalog_version logback)
ARCH_LOGBACK=$(grep -oP 'Logback [0-9]+\.[0-9]+\.[0-9]+' ARCHITECTURE.md | head -1 | grep -oP '[0-9]+\.[0-9]+\.[0-9]+' || true)
if [[ -n "$CATALOG_LOGBACK" && -n "$ARCH_LOGBACK" && "$CATALOG_LOGBACK" != "$ARCH_LOGBACK" ]]; then
    err "Logback version: libs.versions.toml=$CATALOG_LOGBACK, ARCHITECTURE.md=$ARCH_LOGBACK"
fi

echo "---"
if [[ "$ERRORS" -gt 0 ]]; then
    echo "$ERRORS doc-code inconsistency(ies) found."
    exit 1
else
    echo "All doc-code checks passed."
fi
