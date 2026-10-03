<#
.SYNOPSIS
    Self-test of generate.ps1, verify.ps1 and compare-expected.ps1: builds the tree, damages a copy in one way per case and checks
    that verify.ps1 names the damage correctly; then feeds compare-expected.ps1 made-up findings and expectation files.

.DESCRIPTION
    Every case gets a fresh tree under -Work (default: a new folder in %TEMP%), one mutation, and a verify.ps1 -StrictMtime run whose
    counts must equal the expected ones. The cases cover every finding class and the NFC/NFD twin pairs of hazards/nfc-nfd-twins in both
    directions (deleting either twin must give MISSING, never FORM). Exit code 0 when every case passes. Needs PowerShell 7; the source
    is ASCII only. Nothing outside -Work is written. -Only verify or -Only compare runs one half (the compare half takes seconds).
#>
[CmdletBinding()]
param(
    [string]$Work = (Join-Path ([IO.Path]::GetTempPath()) ('golden-selftest-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))),
    [ValidateSet('all', 'verify', 'compare')][string]$Only = 'all'
)
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
$total = 0
[void][IO.Directory]::CreateDirectory($Work)
if ($Only -eq 'compare') { $cases = [ordered]@{} }
foreach ($name in $cases.Keys) {
    $total++
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

# ---- compare-expected.ps1: made-up findings in the tools' own line formats -------------------------------------------------------
$T = "`t"
$rulesOk = @(
    '# a comment and an empty line are ignored', '',
    "verify${T}MTIME${T}*${T}gkrost/unidrive#486${T}times",
    "verify${T}SIZE${T}cats/x [final].txt${T}gkrost/unidrive#1${T}brackets are literal",
    "verify${T}MISSING${T}Docs/*${T}gkrost/unidrive#2${T}case-sensitive",
    "daemon-view${T}NFCMERGE${T}hazards/nfc-nfd-twins/*${T}gkrost/unidrive#491",
    "daemon-view${T}LEAK${T}african/*${T}gkrost/unidrive#489",
    "daemon-view${T}ERROR${T}binary/sizes/b0000000.bin${T}gkrost/unidrive#485",
    "verify:mirror${T}MISSING${T}empty/*${T}gkrost/unidrive#500${T}only on the mirror"
)
$verifyOut = @(
    'MTIME  binary/sizes/b0000000.bin: expected 2026-01-01T12:00:00Z, found 2026-10-03T06:46:35Z',
    'MTIME  african/a.txt: expected 2026-01-01T12:00:00Z, found 2026-10-03T06:34:36Z',
    'SIZE  cats/x [final].txt: expected 10, found 9',
    'expected 539, found 539: ok 536, missing 0, extra 0, form 0, case 0, size 1, hash 0, kind 0, mtime 2'
)
$daemonOut = @(
    '09:31:35  entries 138/534, pending 2, error 2, no remote id 2, leaked 1',
    'LEAK     /_INBOX/golden-unicode-v1/african/{U+1E900}/{U+1E901}/f.txt  listed under  /_INBOX/golden-unicode-v1/african/{U+1E900}',
    'NFCMERGE /_INBOX/golden-unicode-v1/hazards/nfc-nfd-twins/cafe{U+0301}.txt  ->  one row with  /_INBOX/golden-unicode-v1/hazards/nfc-nfd-twins/caf{U+00E9}.txt',
    'MISSING  /_INBOX/golden-unicode-v1/cats/{U+1F431}',
    'findings: 3'
)
$compareCases = [ordered]@{
    'all-expected'        = @{ Rules = $rulesOk; Tool = 'verify'; Out = $verifyOut; Exit = 0; Want = 'unexpected 0, expected 3 (by 2 rules), stale rules 1' }
    'one-unexpected'      = @{ Rules = $rulesOk; Tool = 'verify'; Out = $verifyOut + 'HASH  cats/y.txt: expected aaaa found bbbb'; Exit = 1; Want = 'unexpected 1,' }
    'brackets-literal'    = @{ Rules = $rulesOk; Tool = 'verify'; Out = @('SIZE  cats/x f.txt: expected 10, found 9'); Exit = 1; Want = 'unexpected 1,' }
    'case-sensitive'      = @{ Rules = $rulesOk; Tool = 'verify'; Out = @('MISSING  docs/a.txt', 'MISSING  Docs/a.txt'); Exit = 1; Want = 'unexpected 1, expected 1' }
    'other-tool-ignored'  = @{ Rules = $rulesOk; Tool = 'remote-live'; Out = @('MTIME  a.txt: x'); Exit = 1; Want = 'unexpected 1, expected 0 (by 0 rules), stale rules 0' }
    'daemon-prefix-stale' = @{ Rules = $rulesOk; Tool = 'daemon-view'; Out = $daemonOut; Exit = 1; Want = 'unexpected 1, expected 2 (by 2 rules), stale rules 1' }
    'fail-on-stale'       = @{ Rules = $rulesOk; Tool = 'verify'; Out = $verifyOut; Exit = 1; Want = 'stale rules 1'; Extra = @('-FailOnStale') }
    'rule-without-issue'  = @{ Rules = @("verify${T}MTIME${T}*${T} "); Tool = 'verify'; Out = $verifyOut; Exit = 1; Want = 'has no issue' }
    'surface-applies'     = @{ Rules = $rulesOk; Tool = 'verify:mirror'; Out = @('MISSING  empty/dir'); Exit = 0; Want = 'unexpected 0, expected 1' }
    'surface-not-other'   = @{ Rules = $rulesOk; Tool = 'verify:mount'; Out = @('MISSING  empty/dir'); Exit = 1; Want = 'unexpected 1,' }
    'unknown-class'       = @{ Rules = @("verify${T}TIME${T}*${T}gkrost/unidrive#486"); Tool = 'verify'; Out = $verifyOut; Exit = 1; Want = "unknown class" }
}
if ($Only -eq 'verify') { $compareCases = [ordered]@{} }
$utf8 = [System.Text.UTF8Encoding]::new($false)
foreach ($name in $compareCases.Keys) {
    $total++
    $c = $compareCases[$name]
    $exp = Join-Path $Work "$name.tsv"; $fin = Join-Path $Work "$name.out"
    [IO.File]::WriteAllLines($exp, [string[]]$c.Rules, $utf8)
    [IO.File]::WriteAllLines($fin, [string[]]$c.Out, $utf8)
    [string[]]$extra = if ($c.Extra) { $c.Extra } else { @() }   # typed: a one-element array in a hashtable comes back as a string
    $text = (pwsh -NoProfile -File (Join-Path $PSScriptRoot 'compare-expected.ps1') -Expected $exp -Tool $c.Tool -Findings $fin $extra 2>&1 | ForEach-Object { "$_" }) -join "`n"
    $code = $LASTEXITCODE
    $bad = @()
    if ($code -ne $c.Exit) { $bad += "exit $code (want $($c.Exit))" }
    if (-not $text.Contains($c.Want, [StringComparison]::Ordinal)) { $bad += "output lacks '$($c.Want)'" }
    if ($bad.Count) { $failed++; Write-Host "FAIL  compare/$name  $($bad -join ', ')"; Write-Host $text } else { Write-Host "ok    compare/$name" }
}

Remove-Item -LiteralPath $Work -Recurse -Force
Write-Host "$($total - $failed) of $total cases passed"
exit $(if ($failed) { 1 } else { 0 })
