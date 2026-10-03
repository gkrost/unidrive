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

$byNfc = [System.Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal); $byFold = [System.Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
foreach ($k in $actual.Keys) { $byNfc[$k.Normalize([Text.NormalizationForm]::FormC)] = $k; $byFold[$k.Normalize([Text.NormalizationForm]::FormC).ToUpperInvariant()] = $k }
$claimed = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)

foreach ($e in ($expected.Keys | Sort-Object)) {
    $x = $expected[$e]
    $found = $null
    if ($actual.ContainsKey($e)) { $found = $e }
    else {
        $nfc = $e.Normalize([Text.NormalizationForm]::FormC)
        if ($byNfc.ContainsKey($nfc) -and -not $claimed.Contains($byNfc[$nfc])) { $found = $byNfc[$nfc]; Add-Finding FORM "$(To-Ascii $e)  ->  found as  $(To-Ascii $found)" }
        elseif ($byFold.ContainsKey($nfc.ToUpperInvariant()) -and -not $claimed.Contains($byFold[$nfc.ToUpperInvariant()])) {
            $found = $byFold[$nfc.ToUpperInvariant()]; Add-Finding CASE "$(To-Ascii $e)  ->  found as  $(To-Ascii $found)"
        }
    }
    if ($null -eq $found) { Add-Finding MISSING (To-Ascii $e); continue }
    [void]$claimed.Add($found)
    $isDir = $actual[$found]
    if (($x.Kind -eq 'D') -ne $isDir) { Add-Finding KIND "$(To-Ascii $e): expected $($x.Kind), found $(if ($isDir) { 'directory' } else { 'file' })"; continue }
    if ($x.Kind -eq 'D') { $counts.OK++; continue }
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
    if ($mt -ne $x.Mtime) { Add-Finding MTIME "$(To-Ascii $e): expected $($x.Mtime), found $mt" }
    $counts.OK++
}
foreach ($a in ($actual.Keys | Sort-Object)) { if (-not $claimed.Contains($a) -and -not $expected.ContainsKey($a)) { Add-Finding EXTRA (To-Ascii $a) } }

$findings | Select-Object -First $MaxLines | ForEach-Object { Write-Host $_ }
if ($findings.Count -gt $MaxLines) { Write-Host "... $($findings.Count - $MaxLines) more" }
Write-Host ("expected {0}, found {1}: ok {2}, missing {3}, extra {4}, form {5}, case {6}, size {7}, hash {8}, kind {9}, mtime {10}{11}" -f `
    $expected.Count, $actual.Count, $counts.OK, $counts.MISSING, $counts.EXTRA, $counts.FORM, $counts.CASE, $counts.SIZE, $counts.HASH, $counts.KIND, $counts.MTIME, $(if ($StrictMtime) { "" } else { " (information only; -StrictMtime makes it a finding)" }))
$fail = $counts.MISSING + $counts.EXTRA + $counts.FORM + $counts.CASE + $counts.SIZE + $counts.HASH + $counts.KIND + $(if ($StrictMtime) { $counts.MTIME } else { 0 })
exit $(if ($fail -gt 0) { 1 } else { 0 })
