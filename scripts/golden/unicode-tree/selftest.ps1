<#
.SYNOPSIS
    Self-test of generate.ps1 and verify.ps1: builds the tree, damages a copy in one way per case and checks that verify.ps1 names the
    damage correctly.

.DESCRIPTION
    Every case gets a fresh tree under -Work (default: a new folder in %TEMP%), one mutation, and a verify.ps1 -StrictMtime run whose
    counts must equal the expected ones. The cases cover every finding class and the NFC/NFD twin pairs of hazards/nfc-nfd-twins in both
    directions (deleting either twin must give MISSING, never FORM). Exit code 0 when every case passes. Needs PowerShell 7; the source
    is ASCII only. Nothing outside -Work is written.
#>
[CmdletBinding()]
param([string]$Work = (Join-Path ([IO.Path]::GetTempPath()) ('golden-selftest-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))))
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required (pwsh).' }
if (Test-Path -LiteralPath $Work) { throw "$Work already exists" }

function U([string]$s) { [regex]::Replace($s, '\{U\+([0-9A-Fa-f]{4,6})\}', { param($m) [char]::ConvertFromUtf32([Convert]::ToInt32($m.Groups[1].Value, 16)) }) }
function First-File([string]$dir, [scriptblock]$where = { $true }) {
    $f = Get-ChildItem -LiteralPath $dir -Recurse -File | Where-Object $where | Sort-Object { $_.FullName } | Select-Object -First 1
    if (-not $f) { throw "no file in $dir" }
    $f
}
$sizes = 'binary/sizes'

# name = mutation, expected counts (unlisted counts must be 0)
$cases = [ordered]@{
    'unchanged'        = @{ Do = { param($r) }; Want = @{} }
    'nfc-to-nfd'       = @{ Do = { param($r) $f = First-File (Join-Path $r 'european-latin') { [string]::CompareOrdinal($_.Name, $_.Name.Normalize([Text.NormalizationForm]::FormD)) -ne 0 }; [IO.File]::Move($f.FullName, (Join-Path $f.DirectoryName $f.Name.Normalize([Text.NormalizationForm]::FormD))) }; Want = @{ FORM = 1 } }
    'truncate'         = @{ Do = { param($r) $f = First-File (Join-Path $r $sizes) { $_.Length -gt 100 }; $s = [IO.File]::Open($f.FullName, 'Open'); $s.SetLength(10); $s.Close() }; Want = @{ SIZE = 1 } }
    'content'          = @{ Do = { param($r) $f = First-File (Join-Path $r $sizes) { $_.Length -gt 100 }; $t = $f.LastWriteTimeUtc; $b = [IO.File]::ReadAllBytes($f.FullName); $b[5] = $b[5] -bxor 0xFF; [IO.File]::WriteAllBytes($f.FullName, $b); [IO.File]::SetLastWriteTimeUtc($f.FullName, $t) }; Want = @{ HASH = 1 } }
    'mtime'            = @{ Do = { param($r) $f = First-File (Join-Path $r $sizes); [IO.File]::SetLastWriteTimeUtc($f.FullName, [DateTime]::UtcNow) }; Want = @{ MTIME = 1 } }
    'extra'            = @{ Do = { param($r) [IO.File]::WriteAllText((Join-Path $r 'cats/extra.txt'), 'x') }; Want = @{ EXTRA = 1 } }
    'delete'           = @{ Do = { param($r) Remove-Item -LiteralPath (First-File (Join-Path $r $sizes)).FullName }; Want = @{ MISSING = 1 } }
    'case'             = @{ Do = { param($r) $d = Join-Path $r 'hazards/case-fold'; [IO.File]::Move((Join-Path $d 'STRASSE.txt'), (Join-Path $d 'tmp.txt')); [IO.File]::Move((Join-Path $d 'tmp.txt'), (Join-Path $d 'strasse.txt')) }; Want = @{ CASE = 1 } }
    'dir-to-file'      = @{ Do = { param($r) $d = Join-Path $r 'hazards/empty-things'; Remove-Item -LiteralPath $d -Recurse -Force; [IO.File]::WriteAllText($d, 'x') }; Want = @{ KIND = 1; MISSING = 7 } }
    'twin-del-nfc'     = @{ Do = { param($r) Remove-Item -LiteralPath (Join-Path $r (U 'hazards/nfc-nfd-twins/caf{U+00E9}.txt')) }; Want = @{ MISSING = 1 } }
    'twin-del-nfd'     = @{ Do = { param($r) Remove-Item -LiteralPath (Join-Path $r (U 'hazards/nfc-nfd-twins/cafe{U+0301}.txt')) }; Want = @{ MISSING = 1 } }
    'twin-del-ang-nfd' = @{ Do = { param($r) Remove-Item -LiteralPath (Join-Path $r (U 'hazards/nfc-nfd-twins/A{U+030A}ngstro{U+0308}m.txt')) }; Want = @{ MISSING = 1 } }
    'twin-nfd-to-nfc'  = @{ Do = { param($r) $d = Join-Path $r 'hazards/nfc-nfd-twins'; Remove-Item -LiteralPath (Join-Path $d (U 'caf{U+00E9}.txt')); [IO.File]::Move((Join-Path $d (U 'cafe{U+0301}.txt')), (Join-Path $d (U 'caf{U+00E9}.txt'))) }; Want = @{ MISSING = 1; SIZE = 1 } }
    'sigma-del'        = @{ Do = { param($r) Remove-Item -LiteralPath (Join-Path $r (U 'hazards/case-fold/{U+03A3}.txt')) }; Want = @{ MISSING = 1 } }
}

$failed = 0
foreach ($name in $cases.Keys) {
    $out = Join-Path $Work $name
    pwsh -NoProfile -File (Join-Path $PSScriptRoot 'generate.ps1') -Out $out 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "generate.ps1 failed for $name" }
    $root = Join-Path $out 'golden-unicode-v1'
    & $cases[$name].Do $root
    $lines = @(pwsh -NoProfile -File (Join-Path $PSScriptRoot 'verify.ps1') -Root $root -StrictMtime -MaxLines 0)
    $code = $LASTEXITCODE
    $summary = $lines[-1]
    $got = @{}
    foreach ($k in 'missing', 'extra', 'form', 'case', 'size', 'hash', 'kind', 'mtime') {
        if ($summary -match "\b$k (\d+)") { $got[$k.ToUpperInvariant()] = [int]$Matches[1] }
    }
    $want = $cases[$name].Want
    $bad = @(foreach ($k in $got.Keys) { $w = if ($want.ContainsKey($k)) { $want[$k] } else { 0 }; if ($got[$k] -ne $w) { "$k $($got[$k]) (want $w)" } })
    $wantCode = if ($want.Count -eq 0) { 0 } else { 1 }
    if ($code -ne $wantCode) { $bad += "exit $code (want $wantCode)" }
    if ($bad.Count) { $failed++; Write-Host "FAIL  $name  $($bad -join ', ')" } else { Write-Host "ok    $name" }
}
Remove-Item -LiteralPath $Work -Recurse -Force
Write-Host "$($cases.Count - $failed) of $($cases.Count) cases passed"
exit $(if ($failed) { 1 } else { 0 })
