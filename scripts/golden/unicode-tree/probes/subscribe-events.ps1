<#
.SYNOPSIS
    Prints the daemon's hydration event stream with timestamps and flags every line that is not valid JSON.

.DESCRIPTION
    Ad-hoc probe, read-only: connects to the daemon's IPC socket, sends hydration.subscribe and prints what arrives for -Seconds.
    It is how the malformed `uploading` event (gkrost/unidrive#484) was found: the Windows client resets its stream on such a
    line, so a probe that parses the same lines shows what the client sees. Authenticates with the profile's read token
    (ipc-auth.ps1; -ConfigDir overrides where the profile's config folder is). Needs PowerShell 7.
#>
param(
    [Parameter(Mandatory)][string]$Profile,
    [int]$Seconds = 60,
    [string]$ConfigDir
)
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required (pwsh).' }
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'ipc-auth.ps1')
$utf8 = [System.Text.UTF8Encoding]::new($false)
$conn = Connect-UnidriveIpc -Profile $Profile -Scope read -ConfigDir $ConfigDir
$s = $conn.Socket; $stream = $conn.Stream; $reader = $conn.Reader
$b = $utf8.GetBytes('{"verb":"hydration.subscribe"}' + "`n"); $stream.Write($b, 0, $b.Length); $stream.Flush()
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
