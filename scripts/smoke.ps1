# smoke.ps1 — Windows variant of the v0.0.1 core-only smoke test.
# Mirrors scripts/smoke.sh. See docs/CHANGELOG.md [0.0.1-mvp] for criteria.

$ErrorActionPreference = 'Stop'

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root

$LogDir = New-Item -ItemType Directory -Path (Join-Path $env:TEMP ("unidrive-smoke-" + [Guid]::NewGuid())) -Force
$SyncRoot = New-Item -ItemType Directory -Path (Join-Path $env:TEMP ("unidrive-sync-" + [Guid]::NewGuid())) -Force
$Profile = 'smoke'

Write-Host "[smoke] root=$Root"
Write-Host "[smoke] sync_root=$SyncRoot"
Write-Host "[smoke] log_dir=$LogDir"

Write-Host "[smoke] step 1/6: build core"
Push-Location core
& .\gradlew.bat :app:cli:shadowJar :app:mcp:shadowJar --no-daemon *> (Join-Path $LogDir 'build.log')
if ($LASTEXITCODE -ne 0) { throw "core build failed" }
Pop-Location

$CliJar = Get-ChildItem -Path core\app\cli\build\libs -Filter '*.jar' -ErrorAction Stop | Select-Object -First 1
$McpJar = Get-ChildItem -Path core\app\mcp\build\libs -Filter '*.jar' -ErrorAction Stop | Select-Object -First 1

Write-Host "[smoke] step 2/6: seed sync root"
New-Item -ItemType Directory -Path (Join-Path $SyncRoot 'src') -Force | Out-Null
Set-Content -Path (Join-Path $SyncRoot 'src\hello.txt') -Value 'hello world'

Write-Host "[smoke] step 3/6: CLI init profile ($Profile)"
& java -jar $CliJar.FullName profile create $Profile --provider localfs --root $SyncRoot.FullName *> (Join-Path $LogDir 'cli-init.log')

Write-Host "[smoke] step 4/6: CLI sync"
& java -jar $CliJar.FullName -p $Profile sync *> (Join-Path $LogDir 'cli-sync.log')
if ($LASTEXITCODE -ne 0) { throw "cli sync failed" }

Write-Host "[smoke] step 5/6: MCP ls tool roundtrip"
$McpIn = Join-Path $LogDir 'mcp-in.jsonl'
@'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","clientInfo":{"name":"smoke","version":"0"},"capabilities":{}}}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ls","arguments":{"profile":"smoke","path":"/"}}}
'@ | Set-Content -Path $McpIn

Get-Content $McpIn | & java -jar $McpJar.FullName *> (Join-Path $LogDir 'mcp-out.jsonl')
if (-not (Select-String -Path (Join-Path $LogDir 'mcp-out.jsonl') -Pattern '"id":3' -Quiet)) {
    throw "MCP: no id=3 response"
}

Write-Host "[smoke] step 6/6: backlog-sync dry run"
& kotlinc -script scripts\backlog-sync.kts *> (Join-Path $LogDir 'backlog.log')
if ($LASTEXITCODE -ne 0) { throw "backlog-sync failed — see $LogDir\backlog.log" }

Write-Host "[smoke] PASS — logs in $LogDir"
