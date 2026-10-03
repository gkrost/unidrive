<#
.SYNOPSIS
    Prints the daemon's hydration event stream with timestamps and flags every line that is not valid JSON.

.DESCRIPTION
    Ad-hoc probe, read-only: connects to the daemon's IPC socket, sends hydration.subscribe and prints what arrives for -Seconds.
    It is how the malformed `uploading` event (gkrost/unidrive#484) was found: the Windows client resets its stream on such a
    line, so a probe that parses the same lines shows what the client sees. Needs PowerShell 7.
#>
param(
    [Parameter(Mandatory)][string]$Profile,
    [int]$Seconds = 60
)
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required (pwsh).' }
$sock = Join-Path $env:TEMP "unidrive-ipc\unidrive-$Profile.sock"
$utf8 = [System.Text.UTF8Encoding]::new($false)
$s = [System.Net.Sockets.Socket]::new([System.Net.Sockets.AddressFamily]::Unix, [System.Net.Sockets.SocketType]::Stream, [System.Net.Sockets.ProtocolType]::Unspecified)
$s.Connect([System.Net.Sockets.UnixDomainSocketEndPoint]::new($sock))
$stream = [System.Net.Sockets.NetworkStream]::new($s)
$b = $utf8.GetBytes('{"verb":"hydration.subscribe"}' + "`n"); $stream.Write($b, 0, $b.Length); $stream.Flush()
$reader = [IO.StreamReader]::new($stream, $utf8)
$end = (Get-Date).AddSeconds($Seconds)
$n = 0; $bad = 0; $kinds = @{}; $task = $null
while ((Get-Date) -lt $end) {
    if ($null -eq $task) { $task = $reader.ReadLineAsync() }
    if (-not $task.Wait(2000)) { continue }
    $line = $task.Result; $task = $null
    if ($null -eq $line) { 'stream closed by the daemon'; break }
    $n++
    $stamp = Get-Date -Format 'HH:mm:ss.f'
    try {
        $o = $line | ConvertFrom-Json
        $k = if ($o.event) { $o.event } else { 'reply' }
        $kinds[$k] = 1 + [int]$kinds[$k]
        "$stamp  $($line.Substring(0, [Math]::Min(170, $line.Length)))"
    } catch {
        $bad++
        "$stamp  UNPARSEABLE ($($line.Length) chars): $($line.Substring(0, [Math]::Min(300, $line.Length)))"
    }
}
"lines $n, unparseable $bad"
$kinds.GetEnumerator() | Sort-Object Name | ForEach-Object { '  {0,-18} {1}' -f $_.Name, $_.Value }
$s.Dispose()
exit $(if ($bad -gt 0) { 1 } else { 0 })
