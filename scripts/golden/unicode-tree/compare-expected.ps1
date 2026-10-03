<#
.SYNOPSIS
    Sorts the findings of verify.ps1, daemon-view.ps1 or remote-live.ps1 into unexpected, expected (a known issue) and stale
    expectations, against an expectation file of known deviations.

.DESCRIPTION
    The tools stay strict and version-agnostic: every difference from the manifest is a finding. What is known and accepted for a
    given engine and client lives next to the results, in expected/<suite-id>@<engine-sha>+<client-sha>.tsv, one rule per line:

        tool <TAB> class <TAB> path <TAB> issue <TAB> note

      tool    verify, daemon-view or remote-live (the tool whose finding the rule covers), optionally with a surface:
              verify:mirror applies only to a run compared with -Tool verify:mirror; plain verify applies to every surface
      class   the finding's first word: MISSING, EXTRA, FORM, CASE, SIZE, HASH, MTIME, KIND, ERROR, PENDING, NOREMOTE, NFCMERGE,
              LEAK, LISTFAIL
      path    the path below the golden root as the tools print it (non-ASCII as {U+XXXX}); * matches any run of characters,
              everything else literally (names contain [ ] and other wildcard characters of other glob dialects)
      issue   where the deviation is tracked, e.g. gkrost/unidrive#486; required: an accepted deviation without an issue is not one
      note    free text

    Lines starting with # and empty lines are ignored. Each finding line is matched against the rules of its tool, ordinally.
    Output: UNEXPECTED lines (these fail the run), EXPECTED counts per rule with its issue, and STALE rules that matched nothing in
    this run (the issue may be fixed: the rule should go). Exit code 1 when anything is unexpected (or, with -FailOnStale, stale).

    Feed it the tool's output; findings that the tool cut off with -MaxLines are not seen, so run the tool with -MaxLines 100000.
    Needs PowerShell 7; ASCII only.

.EXAMPLE
    pwsh verify.ps1 -Root <mount>\_INBOX\golden-unicode-v1 -StrictMtime -MaxLines 100000 > verify.out
    pwsh compare-expected.ps1 -Expected expected\golden-unicode-v1@67a4236+b564eda.tsv -Tool verify -Findings verify.out
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Expected,
    [Parameter(Mandatory)][ValidatePattern('^(verify|daemon-view|remote-live)(:[a-z0-9-]+)?$')][string]$Tool,
    [Parameter(Mandatory)][string]$Findings,
    [string]$Root = 'golden-unicode-v1',
    [switch]$FailOnStale,
    [int]$MaxLines = 80
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required (pwsh).' }

$classes = 'MISSING', 'EXTRA', 'FORM', 'CASE', 'SIZE', 'HASH', 'MTIME', 'KIND', 'ERROR', 'PENDING', 'NOREMOTE', 'NFCMERGE', 'LEAK', 'LISTFAIL'

# ---- the rules -------------------------------------------------------------------------------------------------------------------
$rules = [System.Collections.Generic.List[object]]::new()
$n = 0
foreach ($line in [IO.File]::ReadAllLines($Expected, [Text.Encoding]::UTF8)) {
    $n++
    if ($line.Length -eq 0 -or $line.StartsWith('#')) { continue }
    $c = $line.Split("`t")
    if ($c.Count -lt 4) { throw "$($Expected):$n needs tool, class, path and issue, tab-separated" }
    if ($c[0] -cnotmatch '^(verify|daemon-view|remote-live)(:[a-z0-9-]+)?$') { throw "$($Expected):$n unknown tool '$($c[0])'" }
    if ($c[1] -cnotin $classes) { throw "$($Expected):$n unknown class '$($c[1])'" }
    if ($c[3].Trim().Length -eq 0) { throw "$($Expected):$n has no issue: an accepted deviation needs one" }
    # * is the only wildcard; everything else is literal (Regex.Escape), matched ordinally and in full
    $pattern = '^' + ([regex]::Escape($c[2]) -replace '\\\*', '.*') + '$'
    $rules.Add([pscustomobject]@{
        Line = $n; Tool = $c[0]; Class = $c[1]; Path = $c[2]; Issue = $c[3]; Note = $(if ($c.Count -gt 4) { $c[4] } else { '' })
        Regex = [regex]::new($pattern, [Text.RegularExpressions.RegexOptions]::CultureInvariant); Hits = 0
    })
}

# ---- the findings ----------------------------------------------------------------------------------------------------------------
# Every tool prints "<CLASS> <spaces> <path>" and then, depending on the class, "  ->  ...", ": ..." or "  listed under  ...". The
# daemon's paths carry the cloud prefix; the path is taken from the golden root on.
function Get-FindingPath([string]$rest) {
    foreach ($sep in '  ->  ', '  listed under  ') { $i = $rest.IndexOf($sep, [StringComparison]::Ordinal); if ($i -ge 0) { $rest = $rest.Substring(0, $i) } }
    $i = $rest.IndexOf(': ', [StringComparison]::Ordinal); if ($i -ge 0) { $rest = $rest.Substring(0, $i) }
    $marker = "/$Root/"
    $i = $rest.IndexOf($marker, [StringComparison]::Ordinal)
    if ($i -ge 0) { $rest = $rest.Substring($i + $marker.Length) }
    $rest.Trim()
}

# The rules for this run: those of the tool itself (a rule for verify covers every surface) and those of this surface
# (verify:mirror applies only when -Tool verify:mirror), so a deviation of one surface cannot hide the same finding on another.
$base = $Tool.Split(':')[0]
$mine = @($rules | Where-Object { $_.Tool -ceq $Tool -or $_.Tool -ceq $base })
$unexpected = [System.Collections.Generic.List[string]]::new()
$seen = 0
foreach ($line in [IO.File]::ReadAllLines($Findings, [Text.Encoding]::UTF8)) {
    $m = [regex]::Match($line, '^([A-Z]+)\s+(.*)$')
    if (-not $m.Success -or $m.Groups[1].Value -cnotin $classes) { continue }   # summaries, progress lines
    $seen++
    $class = $m.Groups[1].Value
    $path = Get-FindingPath $m.Groups[2].Value
    $rule = $null
    foreach ($r in $mine) { if ( $r.Class -ceq $class -and $r.Regex.IsMatch($path)) { $rule = $r; break } }
    if ($rule) { $rule.Hits++ } else { $unexpected.Add($line) }
}

$used = @($mine | Where-Object Hits -gt 0)   # not $expected: PowerShell names are case-insensitive, and $Expected is the [string] parameter
$stale = @($mine | Where-Object Hits -eq 0)
$unexpected | Select-Object -First $MaxLines | ForEach-Object { Write-Host "UNEXPECTED  $_" }
if ($unexpected.Count -gt $MaxLines) { Write-Host "... $($unexpected.Count - $MaxLines) more unexpected" }
foreach ($r in $used) { Write-Host ("EXPECTED    {0,5} x {1} {2}  ({3}{4})" -f $r.Hits, $r.Class, $r.Path, $r.Issue, $(if ($r.Note) { "; $($r.Note)" } else { '' })) }
foreach ($r in $stale) { Write-Host ("STALE       {0} {1}  ({2}): no finding matched; fixed? (line {3})" -f $r.Class, $r.Path, $r.Issue, $r.Line) }
Write-Host ("{0} findings: unexpected {1}, expected {2} (by {3} rules), stale rules {4}" -f $seen, $unexpected.Count, ($seen - $unexpected.Count), $used.Count, $stale.Count)
$fail = $unexpected.Count -gt 0 -or ($FailOnStale -and $stale.Count -gt 0)
exit $(if ($fail) { 1 } else { 0 })
