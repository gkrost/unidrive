<#
.SYNOPSIS
    For every uploaded file of the golden folder: the cloud's modification time next to the one of the local placeholder.

.DESCRIPTION
    Ad-hoc probe, read-only. Joins hydration.list (remote_modified_ms, remote_id, pending_upload) with the file system of the mount and
    prints one line per uploaded file, ordered by the cloud time: ORIGINAL when the local file still has the fixed golden time,
    changed otherwise, plus the attributes (ReparsePoint = a placeholder). It showed that the mount's placeholders lose the time
    unevenly (gkrost/unidrive#486). Authenticates with the profile's read token (ipc-auth.ps1; -ConfigDir overrides where the
    profile's config folder is). Needs PowerShell 7.
#>
param(
    [Parameter(Mandatory)][string]$Profile,
    [Parameter(Mandatory)][string]$MountRoot,
    [string]$Prefix = '/_INBOX/golden-unicode-v1',
    [string]$GoldenMtime = '2026-01-01T12:00:00Z',
    [string]$ConfigDir
)
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required (pwsh).' }
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'ipc-auth.ps1')
$utf8 = [System.Text.UTF8Encoding]::new($false)
$conn = Connect-UnidriveIpc -Profile $Profile -Scope read -ConfigDir $ConfigDir
$s = $conn.Socket; $st = $conn.Stream; $rd = $conn.Reader
function Ask($o) { $b = $utf8.GetBytes(($o | ConvertTo-Json -Compress) + "`n"); $st.Write($b, 0, $b.Length); $st.Flush(); $rd.ReadLine() | ConvertFrom-Json }
$parse = { param($t) [DateTime]::Parse($t, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AdjustToUniversal) }
$orig = & $parse $GoldenMtime
# each file's own golden time (hazards/timestamps carries others), keyed by the NFC form the daemon answers with (state.db is NFC);
# the local file is opened under its manifest name, which may be NFD
$manifestPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'manifest.tsv'
$golden = [System.Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
foreach ($line in [IO.File]::ReadAllLines($manifestPath, [Text.Encoding]::UTF8)) {
    if ($line.StartsWith('#') -or -not $line.StartsWith("F`t")) { continue }
    $c = $line.Split("`t")
    $name = [regex]::Replace($c[5], '\{U\+([0-9A-Fa-f]{4,6})\}', { param($m) [char]::ConvertFromUtf32([Convert]::ToInt32($m.Groups[1].Value, 16)) })
    $key = "$Prefix/$name".Normalize([Text.NormalizationForm]::FormC)
    if (-not $golden.ContainsKey($key)) { $golden[$key] = [pscustomobject]@{ Name = $name; Mtime = & $parse $c[3] } }
}
$rows = [System.Collections.Generic.List[object]]::new()
$seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$stack = [System.Collections.Generic.Stack[string]]::new(); $stack.Push($Prefix)
while ($stack.Count) {
    $p = $stack.Pop(); $r = Ask @{ verb = 'hydration.list'; prefix = $p }
    foreach ($e in $r.entries) {
        if (-not $seen.Add($e.path)) { continue }   # a grandchild leaking into a listing must not count twice
        if ($e.folder) { $stack.Push($e.path); continue }
        if (-not ($e.remote_id -and -not $e.pending_upload)) { continue }
        $g = $golden[$e.path]
        $rel = if ($g) { $g.Name } else { $e.path.Substring($Prefix.Length + 1) }
        $fi = [IO.FileInfo]::new([IO.Path]::Combine($MountRoot, $Prefix.TrimStart('/').Replace('/', '\'), $rel.Replace('/', '\')))
        $want = if ($g) { $g.Mtime } else { $orig }
        $rows.Add([pscustomobject]@{ Remote = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$e.remote_modified_ms).UtcDateTime; Local = $fi.LastWriteTimeUtc; Want = $want; Exists = $fi.Exists; Attr = $fi.Attributes })
    }
}
$s.Dispose()
$sorted = $rows | Sort-Object Remote
foreach ($r in $sorted) {
    $state = if (-not $r.Exists) { 'NO FILE ' } elseif ($r.Local -eq $r.Want) { 'ORIGINAL' } else { 'changed ' }
    '{0:HH:mm:ss}  local={1:yyyy-MM-dd HH:mm:ss}  {2}  attr={3}' -f $r.Remote, $r.Local, $state, $r.Attr
}
$kept = @($sorted | Where-Object { $_.Exists -and $_.Local -eq $_.Want }).Count
$absent = @($sorted | Where-Object { -not $_.Exists }).Count
"uploaded files $($sorted.Count): local time original $kept, changed $($sorted.Count - $kept - $absent), local file not found $absent"
