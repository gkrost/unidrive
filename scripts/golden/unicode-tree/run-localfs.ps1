<#
.SYNOPSIS
    One hermetic run of the golden unicode tree through the whole chain (client mount, daemon, engine) on an isolated localfs profile.

.DESCRIPTION
    No cloud account, no shared state: the run builds its own config directory, a localfs "remote" directory, the engine's mirror
    directory and a mount root under -Work, starts a daemon and a mount for a profile of its own, copies the golden tree into the mount
    (what Explorer does), waits until the uploads stop changing, and then checks every surface against the manifest:

        provider    the localfs remote directory (what the provider really holds: exact names, sizes, hashes, times)
        mirror      the engine's copy in its sync root (#449)
        mount       the mount's own files (placeholders, hashes: reads hydrate)
        daemon      the daemon's listing (daemon-view.ps1)
        fresh       a new, empty mount root over the same daemon: every placeholder comes from the daemon's listing; names, sizes, hashes

    With -Expected, each result is sorted by compare-expected.ps1 into unexpected / expected / stale. The run ends with one summary table
    (also written to <Work>/SUMMARY.txt); the exit code is 1 when anything is unexpected (with -Expected), 2 when the upload did not settle
    within -TimeoutSec, 0 otherwise.

    It uses the INSTALLED engine and client by default (the launcher in %LOCALAPPDATA%\unidrive, unidrive-win.exe from ~\.unidrive\bin),
    or private builds with -EngineJar and -ClientDir; it never installs or replaces anything. It refuses to start while another
    unidrive mount or daemon is running (a second mount next to a live one is not safe): stop it first, or pass -AllowOtherUnidrive.

    Needs PowerShell 7 and the Windows client (the Cloud Files platform; an interactive user session). ASCII only.

.PARAMETER Work         the run directory; must not exist. Default: ~\unidrive-golden\runs\<timestamp>
.PARAMETER EngineJar    a private engine jar instead of the installed launcher
.PARAMETER ClientDir    a private client build (a folder with unidrive-win.exe) instead of the installed one
.PARAMETER Expected     an expectation file (expected\*.tsv) for compare-expected.ps1
.PARAMETER StableSec    how long the daemon's counts must stay unchanged before the upload counts as settled (default 60)
.PARAMETER TimeoutSec   give up waiting for the upload after this long (default 900)
.PARAMETER Trace        UNIDRIVE_TRACE=1: the client logs raw paths and every callback
.PARAMETER SkipRoundTrip  do not run the fresh-mount step
.PARAMETER KeepRunning  leave the daemon and the last mount running
.EXAMPLE
    pwsh run-localfs.ps1 -Expected expected\golden-unicode-v1@localfs@67a4236+b564eda.tsv
#>
[CmdletBinding()]
param(
    [string]$Work,
    [string]$EngineJar,
    [string]$ClientDir,
    [string]$Expected,
    [int]$StableSec = 60,
    [int]$TimeoutSec = 900,
    [switch]$Trace,
    [switch]$SkipRoundTrip,
    [switch]$KeepRunning,
    [switch]$AllowOtherUnidrive
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required (pwsh).' }
$here = $PSScriptRoot
$pwsh = (Get-Process -Id $PID).Path
$utf8 = [System.Text.UTF8Encoding]::new($false)

function Step([string]$text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function To-Toml([string]$p) { $p.Replace('\', '/') }
function To-Ascii([string]$s) {
    $sb = [System.Text.StringBuilder]::new(); $i = 0
    while ($i -lt $s.Length) { $cp = [char]::ConvertToUtf32($s, $i); if ($cp -ge 0x20 -and $cp -lt 0x7F) { [void]$sb.Append([char]$cp) } else { [void]$sb.Append(('{U+' + $cp.ToString('X4') + '}')) }; $i += if ($cp -gt 0xFFFF) { 2 } else { 1 } }
    $sb.ToString()
}

# ---- preflight ---------------------------------------------------------------------------------------------------------------------
Step 'preflight'
& $pwsh -NoProfile -File (Join-Path $here 'generate.ps1') -Check | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'layout.json and manifest.tsv have drifted apart; fix that before a run.' }
# WMI answers "unknown error" now and then (seen twice in a row): ask up to eight times before giving up
$procs = $null
for ($i = 1; $i -le 8 -and $null -eq $procs; $i++) {
    try { $procs = @(Get-CimInstance Win32_Process -ErrorAction Stop) }
    catch { if ($i -eq 8) { throw "cannot list processes (WMI): $($_.Exception.Message)" }; Start-Sleep -Seconds 3 }
}
$others = @($procs | Where-Object {
        $_.Name -eq 'unidrive-win.exe' -or ($_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'unidrive[^ ]*\.jar' -and $_.CommandLine -match 'daemon run') })
if ($others.Count -gt 0 -and -not $AllowOtherUnidrive) {
    $others | ForEach-Object { Write-Host ("  running: {0} {1}" -f $_.ProcessId, $_.Name) }
    throw 'another unidrive mount or daemon is running; stop it first (unidrive-mount stop) or pass -AllowOtherUnidrive.'
}
if (-not $Work) { $Work = Join-Path $HOME ('unidrive-golden\runs\' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$Work = [IO.Path]::GetFullPath($Work)
if (Test-Path -LiteralPath $Work) { throw "$Work exists; nothing is overwritten." }
$runId = Split-Path -Leaf $Work
$runProfile = 'golden_' + ($runId -replace '[^A-Za-z0-9]', '_')
$cfg = Join-Path $Work 'config'; $remote = Join-Path $Work 'remote'; $mirror = Join-Path $Work 'engine-sync-root'
$staging = Join-Path $Work 'staging'; $out = Join-Path $Work 'out'
foreach ($d in $cfg, (Join-Path $remote '_INBOX'), $mirror, $staging, $out) { [void][IO.Directory]::CreateDirectory($d) }
$mount = Join-Path $Work 'mount'
Write-Host "  work: $Work`n  profile: $runProfile"

# which engine and client
$installedLauncher = Join-Path $env:LOCALAPPDATA 'unidrive\unidrive.ps1'
$installedMountScript = Join-Path $HOME '.unidrive\bin\unidrive-mount.ps1'
if ($EngineJar) {
    $EngineJar = [IO.Path]::GetFullPath($EngineJar)
    if (-not (Test-Path -LiteralPath $EngineJar)) { throw "engine jar not found: $EngineJar" }
    $launcher = Join-Path $Work 'engine-launcher.ps1'
    # the same template and flag list as the Gradle deploy launcher (dist/launcher/)
    $launcherDir = Join-Path $PSScriptRoot '..\..\..\dist\launcher'
    $flags = @(Get-Content -LiteralPath (Join-Path $launcherDir 'jvm-flags.txt') | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ })
    $flagLine = '$javaArgs += @(' + (($flags | ForEach-Object { "'$_'" }) -join ', ') + ')'
    $launcherText = (Get-Content -LiteralPath (Join-Path $launcherDir 'unidrive.ps1.tmpl') -Raw).Replace('@STATIC_FLAGS_PS@', $flagLine).Replace('@JAR@', $EngineJar.Replace("'", "''"))
    [IO.File]::WriteAllText($launcher, $launcherText, $utf8)
} else {
    if (-not (Test-Path -LiteralPath $installedLauncher)) { throw "no installed engine launcher at $installedLauncher (use -EngineJar)" }
    $launcher = $installedLauncher
}
if ($ClientDir) {
    $ClientDir = [IO.Path]::GetFullPath($ClientDir)
    $clientExe = Join-Path $ClientDir 'unidrive-win.exe'
    if (-not (Test-Path -LiteralPath $clientExe)) { throw "unidrive-win.exe not found in $ClientDir" }
    $baseMountScript = if (Test-Path (Join-Path $ClientDir 'unidrive-mount.ps1')) { Join-Path $ClientDir 'unidrive-mount.ps1' } else { $installedMountScript }
} else {
    $clientExe = Join-Path $HOME '.unidrive\bin\unidrive-win.exe'
    if (-not (Test-Path -LiteralPath $clientExe)) { throw "no installed client at $clientExe (use -ClientDir)" }
    $baseMountScript = $installedMountScript
}
if (-not (Test-Path -LiteralPath $baseMountScript)) { throw "unidrive-mount.ps1 not found: $baseMountScript" }
# a copy of the mount script that uses exactly this launcher and this client
$text = [IO.File]::ReadAllText($baseMountScript)
$n1 = [regex]::Matches($text, '(?m)^\$UnidriveWin = .*$').Count; $n2 = [regex]::Matches($text, '(?m)^\$Launcher = .*$').Count
if ($n1 -ne 1 -or $n2 -ne 1) { throw "unidrive-mount.ps1 has an unexpected shape ($n1 / $n2 definitions): update this runner" }
$text = [regex]::Replace($text, '(?m)^\$UnidriveWin = .*$', { "`$UnidriveWin = '" + $clientExe.Replace("'", "''") + "'" })
$text = [regex]::Replace($text, '(?m)^\$Launcher = .*$', { "`$Launcher = '" + $launcher.Replace("'", "''") + "'" })
$mountScript = Join-Path $Work 'unidrive-mount.ps1'
[IO.File]::WriteAllText($mountScript, $text, [System.Text.UTF8Encoding]::new($true))   # BOM: Windows PowerShell 5.1 reads it as UTF-8

# ---- environment -------------------------------------------------------------------------------------------------------------------
$toml = @"
[general]
default_profile = "$runProfile"

[providers.$runProfile]
type = "localfs"
mode = "mount"
root_path = "$(To-Toml $remote)"
sync_root = "$(To-Toml $mirror)"
sync_path = ["/_INBOX"]
"@
[IO.File]::WriteAllText((Join-Path $cfg 'config.toml'), $toml, $utf8)
$savedCfg = $env:UNIDRIVE_CONFIG_DIR; $savedTrace = $env:UNIDRIVE_TRACE
$env:UNIDRIVE_CONFIG_DIR = $cfg
if ($Trace) { $env:UNIDRIVE_TRACE = '1' }

$summary = [System.Collections.Generic.List[object]]::new()
function Mount-Script([string[]]$more) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $mountScript @more -Profile $runProfile -ConfigDir $cfg 2>&1 | ForEach-Object { "$_" } | Out-File -Append -Encoding utf8 (Join-Path $out 'mount-script.log')
    $LASTEXITCODE
}
function Counts() { (& $pwsh -NoProfile -File (Join-Path $here 'probes\daemon-counts.ps1') -Profile $runProfile 2>&1 | Select-Object -Last 1) }
function Run-Verify([string]$name, [string]$root, [string[]]$more) {
    $file = Join-Path $out "$name.out"
    & $pwsh -NoProfile -File (Join-Path $here 'verify.ps1') -Root $root -StrictMtime -MaxLines 100000 @more *> $file
    $last = (Get-Content -LiteralPath $file -Encoding UTF8 | Select-Object -Last 1)
    Write-Host ("  {0,-8} {1}" -f $name, $last)
    $file
}
# How many entries of a mount folder are placeholders (reparse point) and how many are still plain files. After a healthy upload
# every file is a placeholder, except names the client cannot match (non-NFC names, see the expected file). A plain file with an
# NFC name that is still plain means the upload queue is stuck (gkrost/unidrive-windows#115).
function Placeholder-Counts([string]$root) {
    $phF = 0; $plF = 0; $phD = 0; $plD = 0; $stuck = 0; $clashes = 0; $names = [System.Collections.Generic.List[string]]::new()
    foreach ($e in [IO.Directory]::EnumerateFileSystemEntries($root, '*', [IO.SearchOption]::AllDirectories)) {
        $a = [IO.File]::GetAttributes($e); $rp = ($a -band [IO.FileAttributes]::ReparsePoint) -ne 0
        if (($a -band [IO.FileAttributes]::Directory) -ne 0) { if ($rp) { $phD++ } else { $plD++ } }
        elseif ($rp) { $phF++ } else {
            $plF++
            $leaf = [IO.Path]::GetFileName($e)
            # a name with a sibling that is the same name for the cloud (gkrost/unidrive#491) stays plain on purpose once the client
            # refuses such pairs (unidrive-windows#123): that is a clash, not a stuck queue
            $nfc = $leaf.Normalize([Text.NormalizationForm]::FormC)
            $clash = @([IO.Directory]::EnumerateFileSystemEntries([IO.Path]::GetDirectoryName($e)) | Where-Object { $n = [IO.Path]::GetFileName($_); -not [string]::Equals($n, $leaf, [StringComparison]::Ordinal) -and [string]::Equals($n.Normalize([Text.NormalizationForm]::FormC), $nfc, [StringComparison]::Ordinal) }).Count -gt 0
            if ($clash) { $clashes++ }
            elseif ([string]::Equals($leaf, $nfc, [StringComparison]::Ordinal)) { $stuck++; if ($names.Count -lt 12) { $names.Add((To-Ascii $e.Substring($root.Length + 1).Replace('\', '/'))) } }
        }
    }
    [pscustomobject]@{ FilesPlaceholder = $phF; FilesPlain = $plF; DirsPlaceholder = $phD; DirsPlain = $plD; PlainNfc = $stuck; PlainNfcNames = $names; PlainClash = $clashes }
}
function Classify([string]$tool, [string]$file) {
    if (-not $Expected) { return $null }
    $cmp = Join-Path $out ([IO.Path]::GetFileNameWithoutExtension($file) + '.compare.txt')
    & $pwsh -NoProfile -File (Join-Path $here 'compare-expected.ps1') -Expected $Expected -Tool $tool -Findings $file -MaxLines 40 *> $cmp
    $last = (Get-Content -LiteralPath $cmp -Encoding UTF8 | Select-Object -Last 1)
    Write-Host ("           {0}" -f $last)
    $last
}
$exit = 0
try {
    # ---- the tree ------------------------------------------------------------------------------------------------------------------
    Step 'staging tree'
    & $pwsh -NoProfile -File (Join-Path $here 'generate.ps1') -Out $staging | Out-Host
    $golden = Join-Path $staging 'golden-unicode-v1'
    $stagingOut = Run-Verify 'staging' $golden @()

    # ---- start ---------------------------------------------------------------------------------------------------------------------
    Step 'start the daemon and the mount'
    $code = Mount-Script @('start', '-Root', $mount, '-PollInterval', '60', '-TimeoutSec', '120')
    if ($code -ne 0) { Get-Content (Join-Path $out 'mount-script.log') | Select-Object -Last 20 | Out-Host; throw 'the daemon or the mount did not start (see out/mount-script.log)' }

    # ---- the copy ------------------------------------------------------------------------------------------------------------------
    Step 'copy the tree into the mount'
    $t0 = Get-Date
    $errs = @()
    Copy-Item -LiteralPath $golden -Destination (Join-Path $mount '_INBOX\golden-unicode-v1') -Recurse -ErrorAction Continue -ErrorVariable errs
    Write-Host ("  copy returned after {0:n1} s, {1} error(s)" -f ((Get-Date) - $t0).TotalSeconds, $errs.Count)
    $errs | ForEach-Object { Write-Host "  ERR $($_.TargetObject): $($_.Exception.Message)" }

    # ---- wait for the uploads to settle --------------------------------------------------------------------------------------------
    Step "wait until the daemon's counts stay unchanged for $StableSec s (timeout $TimeoutSec s)"
    $prev = ''; $since = Get-Date; $settled = $false; $changedOnce = $false
    while (((Get-Date) - $t0).TotalSeconds -lt $TimeoutSec) {
        $line = Counts
        $key = $line -replace '^\S+\s+', ''
        if ($key -ne $prev) { Write-Host "  $line"; if ($prev -ne '') { $changedOnce = $true }; $prev = $key; $since = Get-Date }
        elseif ($changedOnce -and ((Get-Date) - $since).TotalSeconds -ge $StableSec) { $settled = $true; break }
        Start-Sleep -Seconds 10
    }
    if ($settled) { Write-Host ("  settled {0:n0} s after the copy: {1}" -f (($since - $t0).TotalSeconds), $prev) } else { Write-Host '  NOT SETTLED within the timeout (the queue is stuck or slow)'; $exit = 2 }

    # ---- the surfaces --------------------------------------------------------------------------------------------------------------
    Step 'the mount after the upload'
    $pc = Placeholder-Counts (Join-Path $mount '_INBOX\golden-unicode-v1')
    $mountState = "files: $($pc.FilesPlaceholder) placeholders, $($pc.FilesPlain) plain ($($pc.PlainNfc) of them with an NFC name and no clash, $($pc.PlainClash) in a name clash); folders: $($pc.DirsPlaceholder) placeholders, $($pc.DirsPlain) plain"
    Write-Host "  $mountState"
    if ($pc.PlainNfc -gt 0) {
        Write-Host '  STUCK: files with an NFC name are still plain: the upload queue did not finish them (or the platform never told the client about them):'
        $pc.PlainNfcNames | ForEach-Object { Write-Host "    $_" }
        if ($exit -eq 0) { $exit = 2 }
    }
    Step 'verify the surfaces against the manifest'
    $files = [ordered]@{}
    $files['provider'] = @('verify', (Run-Verify 'provider' (Join-Path $remote '_INBOX\golden-unicode-v1') @()))
    $files['mirror'] = @('verify:mirror', (Run-Verify 'mirror' (Join-Path $mirror '_INBOX\golden-unicode-v1') @()))
    $files['mount'] = @('verify', (Run-Verify 'mount' (Join-Path $mount '_INBOX\golden-unicode-v1') @()))
    $dv = Join-Path $out 'daemon.out'
    & $pwsh -NoProfile -File (Join-Path $here 'daemon-view.ps1') -Profile $runProfile -MaxLines 100000 *> $dv
    Write-Host ("  {0,-8} {1}" -f 'daemon', (Get-Content -LiteralPath $dv -Encoding UTF8 | Select-Object -Last 1))
    $files['daemon'] = @('daemon-view', $dv)

    if (-not $SkipRoundTrip) {
        Step 'round trip: a fresh, empty mount root over the same daemon'
        [void](Mount-Script @('stop', '-KeepDaemon'))
        Rename-Item -LiteralPath $mount -NewName 'mount-first'
        $mount2 = Join-Path $Work 'mount2'
        $code = Mount-Script @('start', '-Root', $mount2, '-PollInterval', '60', '-TimeoutSec', '120')
        if ($code -ne 0) { throw 'the fresh mount did not start (see out/mount-script.log)' }
        $files['fresh'] = @('verify', (Run-Verify 'fresh' (Join-Path $mount2 '_INBOX\golden-unicode-v1') @()))
    }

    # ---- sort the findings -----------------------------------------------------------------------------------------------------------
    Step $(if ($Expected) { "compare with $([IO.Path]::GetFileName($Expected))" } else { 'no -Expected given: findings are not classified' })
    foreach ($k in $files.Keys) {
        $tool = $files[$k][0]; $file = $files[$k][1]
        $res = Classify $tool $file
        $summary.Add([pscustomobject]@{ Surface = $k; Result = (Get-Content -LiteralPath $file -Encoding UTF8 | Select-Object -Last 1); Compare = $res })
        if ($res -match 'unexpected (\d+)' -and [int]$Matches[1] -gt 0 -and $exit -eq 0) { $exit = 1 }
    }
}
finally {
    $env:UNIDRIVE_CONFIG_DIR = $savedCfg; $env:UNIDRIVE_TRACE = $savedTrace
    if (-not $KeepRunning -and (Test-Path -LiteralPath $mountScript)) {
        Step 'stop'
        $env:UNIDRIVE_CONFIG_DIR = $cfg
        [void](Mount-Script @('stop'))
        $env:UNIDRIVE_CONFIG_DIR = $savedCfg
    }
}

# ---- summary -----------------------------------------------------------------------------------------------------------------------
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("golden-unicode-v1 on an isolated localfs profile, run $runId")
$lines.Add("engine : $(if ($EngineJar) { $EngineJar + '  sha256 ' + (Get-FileHash -LiteralPath $EngineJar -Algorithm SHA256).Hash.Substring(0, 16) } else { 'installed, ' + $installedLauncher })")
$lines.Add("client : $clientExe  sha256 $((Get-FileHash -LiteralPath $clientExe -Algorithm SHA256).Hash.Substring(0, 16))")
$lines.Add("upload : $(if ($settled) { 'settled' } else { 'NOT SETTLED' }); counts $prev")
$lines.Add("mount  : $mountState")
foreach ($s in $summary) { $lines.Add(("{0,-9} {1}" -f $s.Surface, $s.Result)); if ($s.Compare) { $lines.Add(("          {0}" -f $s.Compare)) } }
[IO.File]::WriteAllLines((Join-Path $Work 'SUMMARY.txt'), $lines, $utf8)
Step 'summary'
$lines | ForEach-Object { Write-Host $_ }
Write-Host "`nfull outputs: $out"
exit $exit
