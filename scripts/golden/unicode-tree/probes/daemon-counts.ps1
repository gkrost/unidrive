<#
.SYNOPSIS
    One line: how many directories and files of the golden folder the daemon knows, how many are uploaded, pending, in error.

.DESCRIPTION
    Ad-hoc probe, read-only (hydration.list over one IPC connection). Used for the upload progress curve of a run.
    Authenticates with the profile's read token (ipc-auth.ps1; -ConfigDir overrides where the profile's config folder is).
    Needs PowerShell 7.
#>
param(
    [Parameter(Mandatory)][string]$Profile,
    [string]$Prefix = '/_INBOX/golden-unicode-v1',
    [string]$Manifest = (Join-Path (Split-Path -Parent $PSScriptRoot) 'manifest.tsv'),
    [string]$ConfigDir
)
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required (pwsh).' }
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'ipc-auth.ps1')
$utf8 = [System.Text.UTF8Encoding]::new($false)
$conn = Connect-UnidriveIpc -Profile $Profile -Scope read -ConfigDir $ConfigDir
$s = $conn.Socket; $st = $conn.Stream; $rd = $conn.Reader
function Ask($o) { $b = $utf8.GetBytes(($o | ConvertTo-Json -Compress) + "`n"); $st.Write($b, 0, $b.Length); $st.Flush(); $rd.ReadLine() | ConvertFrom-Json }
$totalDirs = 0; $totalFiles = 0
foreach ($line in [IO.File]::ReadAllLines($Manifest, [Text.Encoding]::UTF8)) {
    if ($line.StartsWith('#') -or $line.Length -eq 0) { continue }
    if ($line.StartsWith("D`t")) { $totalDirs++ } else { $totalFiles++ }
}
$stack = [System.Collections.Generic.Stack[string]]::new(); $stack.Push($Prefix)
$dirs = 0; $files = 0; $done = 0; $pend = 0; $err = 0; $leaked = 0
while ($stack.Count) {
    $p = $stack.Pop(); $r = Ask @{ verb = 'hydration.list'; prefix = $p }
    foreach ($e in $r.entries) {
        # a grandchild that leaks into a listing (engine fault below names outside the BMP) would be counted twice
        if (-not [string]::Equals($e.path.Substring(0, $e.path.LastIndexOf('/')), $p, [StringComparison]::Ordinal)) { $leaked++; continue }
        if ($e.folder) { $dirs++; $stack.Push($e.path) }
        else { $files++; if ($e.remote_id -and -not $e.pending_upload) { $done++ }; if ($e.pending_upload) { $pend++ }; if ($e.error) { $err++ } }
    }
}
'{0}  dirs {1}/{2}  files {3}/{4} (uploaded {5}, pending {6}, error {7}; leaked listing entries skipped {8})' -f (Get-Date -Format 'HH:mm:ss'), $dirs, $totalDirs, $files, $totalFiles, $done, $pend, $err, $leaked
$s.Dispose()
