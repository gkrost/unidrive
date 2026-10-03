<#
.SYNOPSIS
    Compares a directory with the golden manifest and reports every difference.

.DESCRIPTION
    Walks -Root (the folder that holds the golden tree itself, e.g. <mount>\_INBOX\golden-unicode-v1) and compares it with
    manifest.tsv: names (exact code points), sizes, SHA-256 of the contents and modification times.

    Findings, one line each:
      MISSING       in the manifest, not found (not even under another normalisation form)
      EXTRA         found, not in the manifest (desktop.ini and Thumbs.db are ignored)
      FORM          found, but its name has another Unicode normalisation form than the manifest (NFC <-> NFD)
      CASE          found, but only the letter case of the name differs
      SIZE / HASH   content differs
      MTIME         modification time differs (information only, never fails the run)
      KIND          a file where a directory is expected or the other way round

    Reading a file hashes it, which hydrates a cloud placeholder. -SizeOnly skips the hashes (sizes of placeholders are the cloud sizes).
    Exit code 0 when no finding other than MTIME exists, 1 otherwise; with -StrictMtime a modification time that differs counts as a finding too
    (the golden files carry fixed times, so a mount or provider that does not preserve them shows up).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Root,
    [string]$Manifest = (Join-Path $PSScriptRoot 'manifest.tsv'),
    [switch]$SizeOnly,
    [switch]$StrictMtime,
    [int]$MaxLines = 80
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required (pwsh).' }
$Root = [IO.Path]::GetFullPath($Root).TrimEnd('\')
if (-not [IO.Directory]::Exists($Root)) { throw "Not a directory: $Root" }

function Expand-Tokens([string]$s) {
    [regex]::Replace($s, '\{U\+([0-9A-Fa-f]{4,6})\}', { param($m) [char]::ConvertFromUtf32([Convert]::ToInt32($m.Groups[1].Value, 16)) })
}
function To-Ascii([string]$s) {
    $sb = [System.Text.StringBuilder]::new(); $i = 0
    while ($i -lt $s.Length) {
        $cp = [char]::ConvertToUtf32($s, $i)
        if ($cp -ge 0x20 -and $cp -lt 0x7F) { [void]$sb.Append([char]$cp) } else { [void]$sb.Append(('{U+' + $cp.ToString('X4') + '}')) }
        $i += if ($cp -gt 0xFFFF) { 2 } else { 1 }
    }
    $sb.ToString()
}

$expected = [System.Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
foreach ($line in [IO.File]::ReadAllLines($Manifest, [Text.Encoding]::UTF8)) {
    if ($line.StartsWith('#') -or $line.Length -eq 0) { continue }
    $c = $line.Split("`t")
    $expected[(Expand-Tokens $c[5])] = [pscustomobject]@{ Kind = $c[0]; Size = $c[1]; Hash = $c[2]; Mtime = $c[3] }
}

$actual = [System.Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
foreach ($p in [IO.Directory]::EnumerateFileSystemEntries($Root, '*', [IO.SearchOption]::AllDirectories)) {
    $rel = $p.Substring($Root.Length + 1).Replace('\', '/')
    $leaf = $rel.Substring($rel.LastIndexOf('/') + 1)
    if ($leaf -in 'desktop.ini', 'Thumbs.db') { continue }
    $actual[$rel] = [IO.Directory]::Exists($p)
}

$findings = [System.Collections.Generic.List[string]]::new()
$counts = @{ MISSING = 0; EXTRA = 0; FORM = 0; CASE = 0; SIZE = 0; HASH = 0; MTIME = 0; KIND = 0; OK = 0 }
function Add-Finding([string]$kind, [string]$text) { $script:counts[$kind]++; $script:findings.Add("$kind  $text") }

# Matching runs in two passes so that the result does not depend on iteration order: first every exact name claims its entry, then the
# expected names left over may take an unclaimed entry that differs only in normalisation form (FORM) or letter case (CASE). Without
# the first pass one member of an NFC/NFD twin pair (hazards/nfc-nfd-twins) could claim its missing twin's entry as FORM, and the
# missing file would never be reported as MISSING. The fallback maps hold every candidate, not only the last one seen.
function Get-Ordinal([System.Collections.Generic.IEnumerable[string]]$keys) { $a = [string[]]@($keys); [Array]::Sort($a, [StringComparer]::Ordinal); , $a }
$byNfc = [System.Collections.Generic.Dictionary[string, System.Collections.Generic.List[string]]]::new([StringComparer]::Ordinal)
$byFold = [System.Collections.Generic.Dictionary[string, System.Collections.Generic.List[string]]]::new([StringComparer]::Ordinal)
function Add-Candidate($map, [string]$key, [string]$value) { if (-not $map.ContainsKey($key)) { $map[$key] = [System.Collections.Generic.List[string]]::new() }; $map[$key].Add($value) }
foreach ($k in (Get-Ordinal $actual.Keys)) {
    $nfcKey = $k.Normalize([Text.NormalizationForm]::FormC)
    Add-Candidate $byNfc $nfcKey $k
    Add-Candidate $byFold $nfcKey.ToUpperInvariant() $k
}
$claimed = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$match = [System.Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
$sortedExpected = Get-Ordinal $expected.Keys
foreach ($e in $sortedExpected) { if ($actual.ContainsKey($e)) { $match[$e] = $e; [void]$claimed.Add($e) } }
function Get-Unclaimed($map, [string]$key) {
    if (-not $map.ContainsKey($key)) { return $null }
    foreach ($c in $map[$key]) { if (-not $claimed.Contains($c)) { return $c } }
    $null
}

foreach ($e in $sortedExpected) {
    $x = $expected[$e]
    $clean = $true
    if ($match.ContainsKey($e)) { $found = $e }
    else {
        $nfc = $e.Normalize([Text.NormalizationForm]::FormC)
        $found = Get-Unclaimed $byNfc $nfc
        if ($null -ne $found) { $clean = $false; Add-Finding FORM "$(To-Ascii $e)  ->  found as  $(To-Ascii $found)" }
        else {
            $found = Get-Unclaimed $byFold $nfc.ToUpperInvariant()
            if ($null -ne $found) { $clean = $false; Add-Finding CASE "$(To-Ascii $e)  ->  found as  $(To-Ascii $found)" }
        }
        if ($null -eq $found) { Add-Finding MISSING (To-Ascii $e); continue }
        [void]$claimed.Add($found)
    }
    $isDir = $actual[$found]
    if (($x.Kind -eq 'D') -ne $isDir) { Add-Finding KIND "$(To-Ascii $e): expected $($x.Kind), found $(if ($isDir) { 'directory' } else { 'file' })"; continue }
    if ($x.Kind -eq 'D') { if ($clean) { $counts.OK++ }; continue }
    $full = [IO.Path]::Combine($Root, $found.Replace('/', '\'))
    $fi = [IO.FileInfo]::new($full)
    if ([string]$fi.Length -ne $x.Size) { Add-Finding SIZE "$(To-Ascii $e): expected $($x.Size), found $($fi.Length)"; continue }
    if (-not $SizeOnly) {
        try {
            $h = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($h -ne $x.Hash) { Add-Finding HASH "$(To-Ascii $e): expected $($x.Hash.Substring(0, 12)) found $($h.Substring(0, 12))"; continue }
        } catch { Add-Finding HASH "$(To-Ascii $e): cannot read: $($_.Exception.Message)"; continue }
    }
    $mt = $fi.LastWriteTimeUtc.ToString('yyyy-MM-ddTHH:mm:ssZ')
    if ($mt -ne $x.Mtime) { Add-Finding MTIME "$(To-Ascii $e): expected $($x.Mtime), found $mt"; $clean = $false }
    if ($clean) { $counts.OK++ }
}
foreach ($a in (Get-Ordinal $actual.Keys)) { if (-not $claimed.Contains($a) -and -not $expected.ContainsKey($a)) { Add-Finding EXTRA (To-Ascii $a) } }

$findings | Select-Object -First $MaxLines | ForEach-Object { Write-Host $_ }
if ($findings.Count -gt $MaxLines) { Write-Host "... $($findings.Count - $MaxLines) more" }
Write-Host ("expected {0}, found {1}: ok {2}, missing {3}, extra {4}, form {5}, case {6}, size {7}, hash {8}, kind {9}, mtime {10}{11}" -f `
    $expected.Count, $actual.Count, $counts.OK, $counts.MISSING, $counts.EXTRA, $counts.FORM, $counts.CASE, $counts.SIZE, $counts.HASH, $counts.KIND, $counts.MTIME, $(if ($StrictMtime) { "" } else { " (information only; -StrictMtime makes it a finding)" }))
$fail = $counts.MISSING + $counts.EXTRA + $counts.FORM + $counts.CASE + $counts.SIZE + $counts.HASH + $counts.KIND + $(if ($StrictMtime) { $counts.MTIME } else { 0 })
exit $(if ($fail -gt 0) { 1 } else { 0 })
