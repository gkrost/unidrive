<#
.SYNOPSIS
    Compares what the unidrive daemon knows (hydration.list over its IPC socket) with the golden manifest.

.DESCRIPTION
    Lists the golden folder recursively through the daemon and checks every manifest entry: present, uploaded (remote_id set),
    no pending upload, no error, same size. This is the cloud-side view of the tree; verify.ps1 is the local-side view.
    Read-only: it only sends hydration.list, over a connection authenticated with the profile's read token (ipc-auth.ps1).
    Needs PowerShell 7.

.PARAMETER Profile   engine profile whose daemon to ask
.PARAMETER Prefix    where the golden folder lives in the cloud view, default /_INBOX/golden-unicode-v1
.PARAMETER Watch     repeat every 10 s until nothing is pending (or -TimeoutSec runs out)
.PARAMETER ConfigDir the engine's config folder (default: resolved like the engine does)
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Profile,
    [string]$Prefix = '/_INBOX/golden-unicode-v1',
    [string]$Manifest = (Join-Path $PSScriptRoot 'manifest.tsv'),
    [switch]$Watch,
    [int]$TimeoutSec = 600,
    [int]$MaxLines = 60,
    [string]$ConfigDir
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required (pwsh).' }
. (Join-Path $PSScriptRoot 'ipc-auth.ps1')
$utf8 = [System.Text.UTF8Encoding]::new($false)

function Expand-Tokens([string]$s) { [regex]::Replace($s, '\{U\+([0-9A-Fa-f]{4,6})\}', { param($m) [char]::ConvertFromUtf32([Convert]::ToInt32($m.Groups[1].Value, 16)) }) }
function To-Ascii([string]$s) {
    $sb = [System.Text.StringBuilder]::new(); $i = 0
    while ($i -lt $s.Length) { $cp = [char]::ConvertToUtf32($s, $i); if ($cp -ge 0x20 -and $cp -lt 0x7F) { [void]$sb.Append([char]$cp) } else { [void]$sb.Append(('{U+' + $cp.ToString('X4') + '}')) }; $i += if ($cp -gt 0xFFFF) { 2 } else { 1 } }
    $sb.ToString()
}

# One connection for the whole walk: the daemon accepts at most 10 clients at a time and releases a closed one asynchronously, so a
# connection per directory runs into that limit on a large tree.
$script:conn = $null
function Connect-Ipc {
    if ($script:conn) { $script:conn.Socket.Dispose(); $script:conn = $null }
    # Reads the token afresh at every (re)connect: a restarted daemon has new tokens.
    $script:conn = Connect-UnidriveIpc -Profile $Profile -Scope read -ConfigDir $ConfigDir
}
function Invoke-Ipc([hashtable]$request) {
    $bytes = $utf8.GetBytes(($request | ConvertTo-Json -Compress -Depth 5) + "`n")
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            if (-not $script:conn) { Connect-Ipc }
            $script:conn.Stream.Write($bytes, 0, $bytes.Length); $script:conn.Stream.Flush()
            $line = $script:conn.Reader.ReadLine()
            if ($null -eq $line) { throw "daemon closed the connection" }
            return ($line | ConvertFrom-Json)
        } catch {
            if ($script:conn) { $script:conn.Socket.Dispose(); $script:conn = $null }
            if ($attempt -eq 3) { throw }
            Start-Sleep -Seconds 2
        }
    }
}

# hydration.list answers the direct children of a prefix. An entry whose parent is not the listed prefix is an engine fault (a
# grandchild leaking into the listing of a folder whose path has code points outside the BMP); it is reported as LEAK and not counted,
# so it cannot show up twice.
$script:leaks = [System.Collections.Generic.List[string]]::new()
function Get-Tree([string]$prefix) {
    $out = [System.Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    $stack = [System.Collections.Generic.Stack[string]]::new(); $stack.Push($prefix)
    while ($stack.Count -gt 0) {
        $p = $stack.Pop()
        $r = Invoke-Ipc @{ verb = 'hydration.list'; prefix = $p }
        if (-not $r.ok) { throw "hydration.list $(To-Ascii $p) failed: $($r.error)" }
        foreach ($e in $r.entries) {
            $parent = $e.path.Substring(0, $e.path.LastIndexOf('/'))
            if (-not [string]::Equals($parent, $p, [StringComparison]::Ordinal)) { $script:leaks.Add("$(To-Ascii $e.path)  listed under  $(To-Ascii $p)"); continue }
            $out[$e.path] = $e
            if ($e.folder) { $stack.Push($e.path) }
        }
    }
    , @($out.Values)
}

# state.db keys every path in NFC (gkrost/unidrive#171), so the daemon can only ever answer the NFC form of a name. Each manifest
# entry is looked up by its NFC form; an NFD name that collapses onto another entry's NFC form (hazards/nfc-nfd-twins) can never have
# a row of its own and is reported as NFCMERGE, not as MISSING.
$expected = [System.Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
$merged = [System.Collections.Generic.List[string]]::new()
$manifestRows = [System.Collections.Generic.List[object]]::new()
foreach ($line in [IO.File]::ReadAllLines($Manifest, [Text.Encoding]::UTF8)) {
    if ($line.StartsWith('#') -or $line.Length -eq 0) { continue }
    $c = $line.Split("`t"); $manifestRows.Add([pscustomobject]@{ Path = $Prefix + '/' + (Expand-Tokens $c[5]); Kind = $c[0]; Size = $c[1] })
}
foreach ($r in $manifestRows) {   # NFC names first, so the NFC member of a twin pair owns the key
    if ([string]::Equals($r.Path, $r.Path.Normalize([Text.NormalizationForm]::FormC), [StringComparison]::Ordinal)) { $expected[$r.Path] = $r }
}
foreach ($r in $manifestRows) {
    $key = $r.Path.Normalize([Text.NormalizationForm]::FormC)
    if ([string]::Equals($r.Path, $key, [StringComparison]::Ordinal)) { continue }
    if ($expected.ContainsKey($key)) { $merged.Add("NFCMERGE $(To-Ascii $r.Path)  ->  one row with  $(To-Ascii $key)") } else { $expected[$key] = $r }
}
function Get-Ordinal([System.Collections.Generic.IEnumerable[string]]$keys) { $a = [string[]]@($keys); [Array]::Sort($a, [StringComparer]::Ordinal); , $a }

$deadline = (Get-Date).AddSeconds($TimeoutSec)
do {
    $script:leaks.Clear()
    $entries = Get-Tree $Prefix
    $got = [System.Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($e in $entries) { $got[$e.path] = $e }
    $pending = @($entries | Where-Object { $_.pending_upload }).Count
    $errors = @($entries | Where-Object { $_.error }).Count
    $unsynced = @($entries | Where-Object { -not $_.folder -and -not $_.remote_id }).Count
    Write-Host ("{0}  entries {1}/{2}, pending {3}, error {4}, no remote id {5}, leaked {6}" -f (Get-Date -Format 'HH:mm:ss'), $entries.Count, $expected.Count, $pending, $errors, $unsynced, $script:leaks.Count)
    if (-not $Watch) { break }
    if ($pending -eq 0 -and $entries.Count -ge $expected.Count) { break }
    Start-Sleep -Seconds 10
} while ((Get-Date) -lt $deadline)

$f = [System.Collections.Generic.List[string]]::new()
foreach ($l in $script:leaks) { $f.Add("LEAK     $l") }
foreach ($m in $merged) { $f.Add($m) }
foreach ($k in (Get-Ordinal $expected.Keys)) {
    $x = $expected[$k]
    if (-not $got.ContainsKey($k)) { $f.Add("MISSING  $(To-Ascii $x.Path)"); continue }
    $e = $got[$k]
    if ($x.Kind -eq 'F') {
        if ([string]$e.size -ne $x.Size) { $f.Add("SIZE     $(To-Ascii $k): expected $($x.Size), daemon says $($e.size)") }
        if ($e.error) { $f.Add("ERROR    $(To-Ascii $k)") }
        elseif ($e.pending_upload) { $f.Add("PENDING  $(To-Ascii $k)") }
        elseif (-not $e.remote_id) { $f.Add("NOREMOTE $(To-Ascii $k)") }
    }
}
foreach ($k in (Get-Ordinal $got.Keys)) { if (-not $expected.ContainsKey($k)) { $f.Add("EXTRA    $(To-Ascii $k)") } }
$f | Select-Object -First $MaxLines | ForEach-Object { Write-Host $_ }
if ($f.Count -gt $MaxLines) { Write-Host "... $($f.Count - $MaxLines) more" }
Write-Host "findings: $($f.Count)"
exit $(if ($f.Count -gt 0) { 1 } else { 0 })
