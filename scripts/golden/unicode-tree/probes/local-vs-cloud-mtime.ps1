<#
.SYNOPSIS
    For every uploaded file of the golden folder: the cloud's modification time next to the one of the local placeholder.

.DESCRIPTION
    Ad-hoc probe, read-only. Joins hydration.list (remote_modified_ms, remote_id, pending_upload) with the file system of the mount and
    prints one line per uploaded file, ordered by the cloud time: ORIGINAL when the local file still has the fixed golden time,
    changed otherwise, plus the attributes (ReparsePoint = a placeholder). It showed that the mount's placeholders lose the time
    unevenly (gkrost/unidrive#486). Needs PowerShell 7.
#>
param(
    [Parameter(Mandatory)][string]$Profile,
    [Parameter(Mandatory)][string]$MountRoot,
    [string]$Prefix = '/_INBOX/golden-unicode-v1',
    [string]$GoldenMtime = '2026-01-01T12:00:00Z'
)
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required (pwsh).' }
$sock = Join-Path $env:TEMP "unidrive-ipc\unidrive-$Profile.sock"
$utf8 = [System.Text.UTF8Encoding]::new($false)
$s = [System.Net.Sockets.Socket]::new([System.Net.Sockets.AddressFamily]::Unix, [System.Net.Sockets.SocketType]::Stream, [System.Net.Sockets.ProtocolType]::Unspecified)
$s.Connect([System.Net.Sockets.UnixDomainSocketEndPoint]::new($sock))
$st = [System.Net.Sockets.NetworkStream]::new($s); $rd = [IO.StreamReader]::new($st, $utf8)
function Ask($o) { $b = $utf8.GetBytes(($o | ConvertTo-Json -Compress) + "`n"); $st.Write($b, 0, $b.Length); $st.Flush(); $rd.ReadLine() | ConvertFrom-Json }
$orig = [DateTime]::Parse($GoldenMtime, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AdjustToUniversal)
$rows = [System.Collections.Generic.List[object]]::new()
$stack = [System.Collections.Generic.Stack[string]]::new(); $stack.Push($Prefix)
while ($stack.Count) {
    $p = $stack.Pop(); $r = Ask @{ verb = 'hydration.list'; prefix = $p }
    foreach ($e in $r.entries) {
        if ($e.folder) { $stack.Push($e.path); continue }
        if (-not ($e.remote_id -and -not $e.pending_upload)) { continue }
        $local = [IO.Path]::Combine($MountRoot, $e.path.TrimStart('/').Replace('/', '\'))
        $fi = [IO.FileInfo]::new($local)
        $rows.Add([pscustomobject]@{ Remote = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$e.remote_modified_ms).UtcDateTime; Local = $fi.LastWriteTimeUtc; Attr = $fi.Attributes })
    }
}
$s.Dispose()
$sorted = $rows | Sort-Object Remote
foreach ($r in $sorted) {
    '{0:HH:mm:ss}  local={1:yyyy-MM-dd HH:mm:ss}  {2}  attr={3}' -f $r.Remote, $r.Local, $(if ($r.Local -eq $orig) { 'ORIGINAL' } else { 'changed ' }), $r.Attr
}
$kept = @($sorted | Where-Object { $_.Local -eq $orig }).Count
"uploaded files $($sorted.Count): local time original $kept, changed $($sorted.Count - $kept)"
