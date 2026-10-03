<#
.SYNOPSIS
    Builds the golden unicode tree from layout.json and writes its manifest.

.DESCRIPTION
    layout.json is the pin of the layout; this script turns it into a directory tree (names, contents and modification times are fully
    deterministic) and into manifest.tsv, one row per directory and file:

        kind  size  sha256  mtime_utc  form  path_ascii  path

    form is the Unicode normalisation form of the path (NFC, NFD or other); path_ascii escapes every non-ASCII character as {U+XXXX}
    so the manifest survives any tool that mangles Unicode. manifest.sha256 holds the SHA-256 of manifest.tsv: that single value
    identifies the whole layout.

    The script needs PowerShell 7 (the source is ASCII only; every special character in layout.json is a literal or a {U+XXXX} token).

.PARAMETER Out
    Directory that receives the tree: <Out>/<id>. It must not exist yet (nothing is ever overwritten).

.PARAMETER Check
    Do not create a tree; build the manifest in memory and compare it to the committed manifest.tsv. Exit code 1 on a difference.

.PARAMETER UpdateManifest
    Write manifest.tsv and manifest.sha256 from the layout (after a deliberate layout change; bump the id too). Without it the
    committed manifest is never written, and -Out refuses to build a tree from a layout that no longer matches it.

.EXAMPLE
    pwsh scripts/golden/unicode-tree/generate.ps1 -Out C:\Users\me\unidrive-golden
    pwsh scripts/golden/unicode-tree/generate.ps1 -Check
    pwsh scripts/golden/unicode-tree/generate.ps1 -UpdateManifest
#>
[CmdletBinding()]
param(
    [string]$Out,
    [string]$Layout = (Join-Path $PSScriptRoot 'layout.json'),
    [string]$ManifestPath = (Join-Path $PSScriptRoot 'manifest.tsv'),
    [switch]$Check,
    [switch]$UpdateManifest
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required (pwsh).' }
if (-not $Check -and -not $Out -and -not $UpdateManifest) { throw 'Give -Out <directory>, -Check or -UpdateManifest.' }

$utf8 = [System.Text.UTF8Encoding]::new($false)
$spec = Get-Content -LiteralPath $Layout -Raw -Encoding UTF8 | ConvertFrom-Json
$id = $spec.id
$maxSegments = [int]$spec.maxSegments
$defaultMtime = [DateTime]::Parse('2026-01-01T12:00:00Z', [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AdjustToUniversal)

function Expand-Tokens([string]$s) {
    $s = [regex]::Replace($s, '\{U\+([0-9A-Fa-f]{4,6})\}', { param($m) [char]::ConvertFromUtf32([Convert]::ToInt32($m.Groups[1].Value, 16)) })
    [regex]::Replace($s, '\{rep:([^:}]+):(\d+)\}', { param($m) $m.Groups[1].Value * [int]$m.Groups[2].Value })
}

function To-Ascii([string]$s) {
    $sb = [System.Text.StringBuilder]::new()
    $i = 0
    while ($i -lt $s.Length) {
        $cp = [char]::ConvertToUtf32($s, $i)
        if ($cp -ge 0x20 -and $cp -lt 0x7F) { [void]$sb.Append([char]$cp) } else { [void]$sb.Append(('{U+' + $cp.ToString('X4') + '}')) }
        $i += if ($cp -gt 0xFFFF) { 2 } else { 1 }
    }
    $sb.ToString()
}

function Get-Form([string]$s) {
    if ($s.IsNormalized([Text.NormalizationForm]::FormC) -and $s.IsNormalized([Text.NormalizationForm]::FormD)) { return 'both' }
    if ($s.IsNormalized([Text.NormalizationForm]::FormC)) { return 'NFC' }
    if ($s.IsNormalized([Text.NormalizationForm]::FormD)) { return 'NFD' }
    'other'
}

function Apply-Form([string]$name, $nf) {
    if ($nf -eq 'NFC') { return $name.Normalize([Text.NormalizationForm]::FormC) }
    if ($nf -eq 'NFD') { return $name.Normalize([Text.NormalizationForm]::FormD) }
    $name
}

function New-Bytes([long]$count, [string]$seed, [bool]$zeros) {
    $buf = [byte[]]::new($count)
    $pos = 0; $i = 0
    while ($pos -lt $count) {
        $h = [Security.Cryptography.SHA256]::HashData($utf8.GetBytes("$seed|$i"))
        $n = [Math]::Min(32, $count - $pos)
        [Array]::Copy($h, 0, $buf, $pos, $n)
        $pos += $n; $i++
    }
    if ($zeros) { for ($k = 0; $k -lt $count; $k += 2) { $buf[$k] = 0 } }
    , $buf
}

function Encode-Text($f, [string]$rel) {
    $body = [string]$f.text
    if (-not $f.raw) { $body += "`n" }
    if (-not $f.plain) { $body += "$id | $rel`n" }
    if ($f.eol -eq 'crlf') { $body = $body.Replace("`n", "`r`n") }
    switch ($f.enc) {
        'utf8bom' { return [byte[]]([byte[]](0xEF, 0xBB, 0xBF) + $utf8.GetBytes($body)) }
        'utf16le' { return [byte[]]([byte[]](0xFF, 0xFE) + [Text.Encoding]::Unicode.GetBytes($body)) }
        'utf16be' { return [byte[]]([byte[]](0xFE, 0xFF) + [Text.Encoding]::BigEndianUnicode.GetBytes($body)) }
        'latin1' { return [Text.Encoding]::GetEncoding(28591).GetBytes($body) }
        default { return $utf8.GetBytes($body) }
    }
}

# ---- plan: every directory and file, in memory ------------------------------------------------------------------------------------
$dirs = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$files = [System.Collections.Generic.List[object]]::new()

function Add-Dir([string[]]$parts) {
    for ($n = 1; $n -le $parts.Length; $n++) { [void]$dirs.Add(($parts[0..($n - 1)] -join '/')) }
}

foreach ($node in $spec.nodes) {
    # @() keeps a one-segment directory an array: without it "$dirParts + $name" would concatenate two strings
    $dirParts = [string[]]@($node.dir | ForEach-Object { Expand-Tokens $_ })
    $dirParts = for ($k = 0; $k -lt $dirParts.Count; $k++) { Apply-Form $dirParts[$k] $null }
    Add-Dir $dirParts
    foreach ($e in @($node.emptyDirs)) {
        if ($null -eq $e) { continue }
        Add-Dir ($dirParts + (Expand-Tokens $e))
    }
    foreach ($f in @($node.files)) {
        if ($null -eq $f) { continue }
        $name = Apply-Form (Expand-Tokens $f.name) $f.nf
        $rel = (($dirParts + $name) -join '/')
        $files.Add([pscustomobject]@{ Spec = $f; Rel = $rel; Name = $name })
    }
}

# every path must respect the segment limit
foreach ($p in @($dirs) + @($files | ForEach-Object { $_.Rel })) {
    $segs = ($p -split '/').Count
    if ($segs -gt $maxSegments) { throw "Path has $segs segments (max $maxSegments): $(To-Ascii $p)" }
}
# names that fold together are deliberate hazards (hazards/case-fold); the real merge test is the Exists check when writing
$seen = [System.Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
foreach ($p in (@($dirs) + @($files | ForEach-Object { $_.Rel }))) {
    $key = $p.ToUpperInvariant()
    if ($seen.ContainsKey($key) -and -not [string]::Equals($seen[$key], $p, [StringComparison]::Ordinal)) { Write-Warning "Names equal under invariant upper-casing (intended, a case-folding hazard; NTFS keeps them apart, other file systems may not): $(To-Ascii $p) vs $(To-Ascii $seen[$key])" }
    $seen[$key] = $p
}
$once = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($x in $files) { if (-not $once.Add($x.Rel)) { throw "The same file path twice: $(To-Ascii $x.Rel)" } }

# ---- contents, hashes, manifest ---------------------------------------------------------------------------------------------------
$rows = [System.Collections.Generic.List[object]]::new()
foreach ($d in $dirs) {
    $rows.Add([pscustomobject]@{ Kind = 'D'; Size = '-'; Hash = '-'; Mtime = '-'; Path = $d; Data = $null; MtimeValue = $null })
}
foreach ($x in $files) {
    $f = $x.Spec
    if ($null -ne $f.bytes) {
        $seed = if ($f.seed) { [string]$f.seed } else { "$id|$($x.Rel)" }
        $data = New-Bytes ([long]$f.bytes) $seed ([bool]$f.zeros)
    } else {
        $data = Encode-Text $f $x.Rel
    }
    if ($data.Length -gt 1000000) { throw "File larger than 1,000,000 bytes: $(To-Ascii $x.Rel)" }
    $mt = if ($f.mtime) { [DateTime]::Parse([string]$f.mtime, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AdjustToUniversal) } else { $defaultMtime }
    $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($data)).ToLowerInvariant()
    $rows.Add([pscustomobject]@{ Kind = 'F'; Size = $data.Length; Hash = $hash; Mtime = $mt.ToString('yyyy-MM-ddTHH:mm:ssZ'); Path = $x.Rel; Data = $data; MtimeValue = $mt })
}
$sorted = [System.Collections.Generic.List[object]]::new($rows)
$sorted.Sort([Comparison[object]]{ param($a, $b) [string]::CompareOrdinal($a.Path, $b.Path) })   # UTF-16 code unit order
$sb = [System.Text.StringBuilder]::new()
[void]$sb.Append("#$id manifest: kind, size, sha256, mtime_utc, form, path_ascii, path (UTF-8, LF)`n")
foreach ($r in $sorted) {
    [void]$sb.Append(("{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}`n" -f $r.Kind, $r.Size, $r.Hash, $r.Mtime, (Get-Form $r.Path), (To-Ascii $r.Path), $r.Path))
}
$manifest = $sb.ToString()
$manifestBytes = $utf8.GetBytes($manifest)
$manifestHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($manifestBytes)).ToLowerInvariant()
$nFiles = ($rows | Where-Object Kind -eq 'F').Count
$nDirs = ($rows | Where-Object Kind -eq 'D').Count
$total = ($rows | Where-Object Kind -eq 'F' | Measure-Object -Property Size -Sum).Sum

$committed = if (Test-Path -LiteralPath $ManifestPath) { [IO.File]::ReadAllBytes($ManifestPath) } else { [byte[]]@() }
$committedHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($committed)).ToLowerInvariant()
Write-Host "layout: $nDirs directories, $nFiles files, $total bytes; manifest sha256 $manifestHash"
if ($UpdateManifest) {
    [IO.File]::WriteAllBytes($ManifestPath, $manifestBytes)
    [IO.File]::WriteAllText((Join-Path (Split-Path -Parent $ManifestPath) 'manifest.sha256'), "$manifestHash  manifest.tsv`n", $utf8)
    Write-Host "wrote $ManifestPath (was $committedHash)"
    if (-not $Out) { exit 0 }
} elseif ($committedHash -ne $manifestHash) {
    # a tree built from a drifted layout would be verified against a manifest it does not match
    Write-Host "DIFFERS from $ManifestPath ($committedHash); after a deliberate layout change run -UpdateManifest"
    exit 1
}
if ($Check) { Write-Host 'matches the committed manifest'; exit 0 }

$root = Join-Path $Out $id
if (Test-Path -LiteralPath $root) { throw "$root already exists; nothing is overwritten. Remove it or pick another -Out." }
[void][IO.Directory]::CreateDirectory($root)
foreach ($r in ($sorted | Where-Object Kind -eq 'D')) {
    [void][IO.Directory]::CreateDirectory([IO.Path]::Combine($root, $r.Path.Replace('/', [IO.Path]::DirectorySeparatorChar)))
}
foreach ($r in ($sorted | Where-Object Kind -eq 'F')) {
    $full = [IO.Path]::Combine($root, $r.Path.Replace('/', [IO.Path]::DirectorySeparatorChar))
    if ([IO.File]::Exists($full)) { throw "Collision on the file system (a name that the file system treats as equal): $(To-Ascii $r.Path)" }
    [IO.File]::WriteAllBytes($full, $r.Data)
    [IO.File]::SetLastWriteTimeUtc($full, $r.MtimeValue)
}
Write-Host "created $root"
