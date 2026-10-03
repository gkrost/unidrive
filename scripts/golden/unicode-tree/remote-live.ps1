<#
.SYNOPSIS
    Compares the provider's own listing (unidrive ls --live, one folder at a time) with the golden manifest.

.DESCRIPTION
    daemon-view.ps1 shows what the daemon's state database says; this script asks the cloud itself, through the engine CLI with --live,
    which bypasses state.db. It is the independent truth for "did the provider keep every name exactly": a provider that normalises
    Unicode, folds case, strips invisible characters, merges twins or drops astral code points shows up here as MISSING / EXTRA / FORM.

    One engine start per directory (about 3 s each). -Subtree limits the walk to one top-level group (for example hazards). File sizes are
    compared where the engine prints them exactly (below 1 KB). Needs PowerShell 7 and the engine launcher; read-only.

    LIMIT: on Windows the engine CLI receives its arguments in the ANSI code page, so a folder whose path contains a character outside it
    cannot be addressed (it arrives as "?"). Such folders are not listed (reported as skipped); the names inside every folder with an
    ASCII-only path are still checked, which covers the top-level groups and all of hazards/*. daemon-view.ps1 covers the rest.

.PARAMETER Profile  engine profile
.PARAMETER Prefix   where the golden folder lives in the cloud, default /_INBOX/golden-unicode-v1
.PARAMETER Subtree  walk only this top-level group of the golden tree
.PARAMETER Launcher the engine launcher, default %LOCALAPPDATA%\unidrive\unidrive.ps1
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Profile,
    [string]$Prefix = '/_INBOX/golden-unicode-v1',
    [string]$Subtree,
    [string]$Manifest = (Join-Path $PSScriptRoot 'manifest.tsv'),
    [string]$Launcher = (Join-Path $env:LOCALAPPDATA 'unidrive\unidrive.ps1'),
    [int]$MaxLines = 80
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required (pwsh).' }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Expand-Tokens([string]$s) { [regex]::Replace($s, '\{U\+([0-9A-Fa-f]{4,6})\}', { param($m) [char]::ConvertFromUtf32([Convert]::ToInt32($m.Groups[1].Value, 16)) }) }
function To-Ascii([string]$s) {
    $sb = [System.Text.StringBuilder]::new(); $i = 0
    while ($i -lt $s.Length) { $cp = [char]::ConvertToUtf32($s, $i); if ($cp -ge 0x20 -and $cp -lt 0x7F) { [void]$sb.Append([char]$cp) } else { [void]$sb.Append(('{U+' + $cp.ToString('X4') + '}')) }; $i += if ($cp -gt 0xFFFF) { 2 } else { 1 } }
    $sb.ToString()
}

$expected = [System.Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
foreach ($line in [IO.File]::ReadAllLines($Manifest, [Text.Encoding]::UTF8)) {
    if ($line.StartsWith('#') -or $line.Length -eq 0) { continue }
    $c = $line.Split("`t"); $expected[(Expand-Tokens $c[5])] = [pscustomobject]@{ Kind = $c[0]; Size = $c[1] }
}

function Get-Listing([string]$cloudPath) {
    $raw = & $Launcher -p $Profile ls --live $cloudPath 2>&1
    $code = $LASTEXITCODE
    $text = @($raw | ForEach-Object { "$_" } | Where-Object { $_ -notmatch '^Picked up JAVA_TOOL_OPTIONS' })
    if ($code -ne 0) { return [pscustomobject]@{ Ok = $false; Error = ($text -join ' | '); Entries = @() } }
    $entries = foreach ($l in $text) {
        if ($l -notmatch '\s+(\d{4}-\d{2}-\d{2}T\S+)$') { continue }
        $rest = $l.Substring(0, $l.Length - $Matches[1].Length).TrimEnd()
        $size = $null
        if ($rest -match '\s{2,}(\d+) B$') { $size = $Matches[1]; $rest = $rest.Substring(0, $rest.Length - $Matches[0].Length) }
        elseif ($rest -match '\s{2,}[\d.,]+ (?:KB|MB|GB|KiB|MiB|GiB)$') { $rest = $rest.Substring(0, $rest.Length - $Matches[0].Length) }
        $isDir = $rest.EndsWith('/')
        [pscustomobject]@{ Name = $(if ($isDir) { $rest.Substring(0, $rest.Length - 1) } else { $rest }); IsDir = $isDir; Size = $size }
    }
    [pscustomobject]@{ Ok = $true; Error = $null; Entries = @($entries) }
}

$startRel = if ($Subtree) { $Subtree } else { '' }
$queue = [System.Collections.Generic.Queue[string]]::new(); $queue.Enqueue($startRel)
$got = [System.Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
$findings = [System.Collections.Generic.List[string]]::new()
$listed = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$skipped = 0
$calls = 0
while ($queue.Count -gt 0) {
    $rel = $queue.Dequeue()
    if ($rel -cmatch '[^\x20-\x7E]') { $skipped++; continue }   # the CLI cannot take this path (see LIMIT)
    $cloud = if ($rel) { "$Prefix/$rel" } else { $Prefix }
    $r = Get-Listing $cloud; $calls++
    if (-not $r.Ok) { $findings.Add("LISTFAIL $(To-Ascii $rel): $($r.Error)"); continue }
    [void]$listed.Add($rel)
    foreach ($e in $r.Entries) {
        $full = if ($rel) { "$rel/$($e.Name)" } else { $e.Name }
        $got[$full] = $e
        if ($e.IsDir) { $queue.Enqueue($full) }
    }
    if ($calls % 25 -eq 0) { Write-Host "  ... $calls folders listed, $($got.Count) entries" }
}

$scope = if ($Subtree) { { param($k) $k -eq $Subtree -or $k.StartsWith("$Subtree/") } } else { { param($k) $true } }
foreach ($k in ($expected.Keys | Sort-Object)) {
    if (-not (& $scope $k)) { continue }
    $parent = if ($k.Contains('/')) { $k.Substring(0, $k.LastIndexOf('/')) } else { '' }
    if (-not $listed.Contains($parent)) { continue }   # its folder could not be listed
    $x = $expected[$k]
    if (-not $got.ContainsKey($k)) {
        $nfc = $k.Normalize([Text.NormalizationForm]::FormC)
        $alt = $got.Keys | Where-Object { $_.Normalize([Text.NormalizationForm]::FormC) -ceq $nfc } | Select-Object -First 1
        if ($alt) { $findings.Add("FORM     $(To-Ascii $k)  ->  provider has  $(To-Ascii $alt)") } else { $findings.Add("MISSING  $(To-Ascii $k)") }
        continue
    }
    $e = $got[$k]
    if (($x.Kind -eq 'D') -ne $e.IsDir) { $findings.Add("KIND     $(To-Ascii $k)"); continue }
    if ($x.Kind -eq 'F' -and $null -ne $e.Size -and $e.Size -ne $x.Size) { $findings.Add("SIZE     $(To-Ascii $k): expected $($x.Size), provider $($e.Size)") }
}
foreach ($k in ($got.Keys | Sort-Object)) { if (-not $expected.ContainsKey($k)) { $findings.Add("EXTRA    $(To-Ascii $k)") } }
$checked = @($expected.Keys | Where-Object { (& $scope $_) -and $listed.Contains($(if ($_.Contains('/')) { $_.Substring(0, $_.LastIndexOf('/')) } else { '' })) }).Count

$findings | Select-Object -First $MaxLines | ForEach-Object { Write-Host $_ }
if ($findings.Count -gt $MaxLines) { Write-Host "... $($findings.Count - $MaxLines) more" }
Write-Host "folders listed $calls, skipped (non-ASCII path) $skipped, entries seen $($got.Count), manifest entries checked $checked; findings $($findings.Count)"
exit $(if ($findings.Count -gt 0) { 1 } else { 0 })
