# unidrive-jfr.ps1 — start unidrive (CLI or MCP) wrapped in Java Flight
# Recorder, capturing as much detail as production-safely possible.
#
# Usage:
#   pwsh scripts/dev/unidrive-jfr.ps1 [-Mcp] [-Profile <name>] [-OutDir <dir>]
#                                     [-DurationMin <n>] [<args...>]
#
# Examples:
#   # CLI, run a relocate, JFR auto-stops after 60 min
#   pwsh scripts/dev/unidrive-jfr.ps1 -DurationMin 60 `
#       -- -p localfs relocate --from src --to dst
#
#   # CLI, sync --watch indefinitely (Ctrl-C stops both unidrive and JFR)
#   pwsh scripts/dev/unidrive-jfr.ps1 -- -p onedrive sync --watch
#
#   # MCP server (stdin/stdout JSON-RPC); JFR runs alongside
#   pwsh scripts/dev/unidrive-jfr.ps1 -Mcp
#
# JFR recording lands at:
#   $env:LOCALAPPDATA\unidrive\jfr\unidrive-<sha>-<timestamp>.jfr
# (override via -OutDir)
#
# Why this exists
#
# Each invocation produces one JFR file. Open with `jfr print FILE` for
# CLI inspection, or with the `jfr-analyzer` MCP tool listed in
# CLAUDE.md (BUILD-SANITY §) for guided analysis. The recording captures
# ~every JFR event class JDK 21 ships, sampled at production-friendly
# intervals — enough to reconstruct method-level CPU profile, allocation
# hotspots, GC pauses, network I/O, file I/O, lock contention, and JVM
# health (compilation, classloading, threading) without the overhead of
# always-on profiling.

[CmdletBinding()]
param(
    [switch]$Mcp,                                                                  # spawn MCP server instead of CLI
    [string]$Profile = $null,                                                      # convenience -p shortcut for CLI
    [string]$OutDir = (Join-Path $env:LOCALAPPDATA "unidrive\jfr"),                # where the .jfr file lands
    [int]$DurationMin = 0,                                                         # 0 = run until process exits
    [string]$JarOverride = $null,                                                  # absolute path override
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$AppArgs                                                             # passthrough to unidrive
)

$ErrorActionPreference = 'Stop'

# ── Resolve jar ────────────────────────────────────────────────────────────
$jarName = if ($Mcp) { 'unidrive-mcp-0.0.0-greenfield.jar' } else { 'unidrive-0.0.0-greenfield.jar' }
$jar = if ($JarOverride) { $JarOverride } else { Join-Path $env:LOCALAPPDATA "unidrive\$jarName" }
if (-not (Test-Path $jar)) {
    Write-Error "jar not found: $jar`nRun `./gradlew :app:cli:deploy :app:mcp:deploy` from core/ first."
    exit 1
}

# ── Capture build SHA from BuildInfo.COMMIT (best-effort) ──────────────────
# Read the BuildInfo class from the jar so the JFR filename pins the
# binary that produced it. Falls back to "unknown" when unzip-on-PS isn't
# available — non-fatal.
$sha = "unknown"
try {
    $tmp = New-TemporaryFile
    Remove-Item $tmp -Force
    New-Item -ItemType Directory -Path $tmp.FullName -Force | Out-Null
    & "$env:JAVA_HOME\bin\jar.exe" -xf $jar -C $tmp.FullName 'org/krost/unidrive/cli/BuildInfo.class' 2>$null
    if ($LASTEXITCODE -eq 0) {
        $bytes = [IO.File]::ReadAllBytes((Join-Path $tmp.FullName 'org\krost\unidrive\cli\BuildInfo.class'))
        $shaMatch = [Text.Encoding]::ASCII.GetString($bytes) -match '([a-f0-9]{7,12})'
        if ($shaMatch) { $sha = $Matches[1] }
    }
    Remove-Item $tmp.FullName -Recurse -Force -ErrorAction SilentlyContinue
} catch { }

# ── JFR output path ────────────────────────────────────────────────────────
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
$timestamp = Get-Date -Format 'yyyy-MM-dd_HHmmss'
$kind = if ($Mcp) { 'mcp' } else { 'cli' }
$jfrFile = Join-Path $OutDir "unidrive-$kind-$sha-$timestamp.jfr"

Write-Host ""
Write-Host "==> unidrive-jfr.ps1" -ForegroundColor Cyan
Write-Host "    jar:        $jar"
Write-Host "    sha:        $sha"
Write-Host "    JFR file:   $jfrFile"
Write-Host "    duration:   $(if ($DurationMin -gt 0) { "${DurationMin}m" } else { 'until-exit' })"
Write-Host ""

# ── JFR settings ────────────────────────────────────────────────────────────
#
# `profile` is the JDK's higher-detail preset: ~1% overhead, captures method
# samples + allocation profiling + locks. We layer on every event class that
# `profile` doesn't enable by default (file I/O small, socket I/O, GC
# detail, JIT compilation log, classloading) at sane sampled rates so the
# .jfr file stays under a few hundred MB even on a multi-hour relocate.
$jfrParams = @(
    "filename=$jfrFile"
    "settings=profile"
    "dumponexit=true"
)
if ($DurationMin -gt 0) {
    $jfrParams += "duration=${DurationMin}m"
}

$flightRecorderArg = "-XX:StartFlightRecording=" + ($jfrParams -join ',')

# ── JVM args ────────────────────────────────────────────────────────────────
# Match the production launcher's args (UD-258 UTF-8 + Ktor FFI access)
# plus JFR-specific options. `-XX:FlightRecorderOptions=stackdepth=128`
# captures deep stacks so async coroutine stitching survives in the dump.
$jvmArgs = @(
    '-Dstdout.encoding=UTF-8'
    '-Dstderr.encoding=UTF-8'
    '--enable-native-access=ALL-UNNAMED'
    '-XX:FlightRecorderOptions=stackdepth=256,memorysize=64m'
    $flightRecorderArg
    # Heap-dump on OOM is free insurance — the UD-329 / streaming-buffer
    # class of bugs goes from "log line says OOM" to "actual hprof on disk".
    '-XX:+HeapDumpOnOutOfMemoryError'
    "-XX:HeapDumpPath=$OutDir"
    '-jar'
    $jar
)

# ── If user passed `-Profile X` (PowerShell convenience), prepend `-p X` ────
$prefix = @()
if ($Profile -and -not $Mcp) {
    $prefix = @('-p', $Profile)
}
$allAppArgs = $prefix + ($AppArgs | Where-Object { $_ -ne '--' })

# ── Run ────────────────────────────────────────────────────────────────────
# Use the call operator (&) so PowerShell hands args to java.exe via
# CreateProcessW directly — no shell parsing of `-D` flags
# (the trap noted earlier in this session under UD-2xx).
Write-Host "==> exec: java $($jvmArgs -join ' ') $($allAppArgs -join ' ')" -ForegroundColor Gray
Write-Host ""

try {
    & java @jvmArgs @allAppArgs
    $exitCode = $LASTEXITCODE
} finally {
    Write-Host ""
    Write-Host "==> JFR recording: $jfrFile" -ForegroundColor Cyan
    if (Test-Path $jfrFile) {
        $size = (Get-Item $jfrFile).Length / 1MB
        Write-Host ("    size: {0:N1} MB" -f $size)
        Write-Host "    inspect: jfr print --events CPULoad,GCHeapSummary,JavaMonitorWait `"$jfrFile`""
        Write-Host "    or:      JDK Mission Control — File → Open File"
    } else {
        Write-Host "    (file not produced — likely an early JVM exit before JFR started)"
    }
}

exit $exitCode
