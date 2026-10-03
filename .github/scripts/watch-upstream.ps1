# Local watchdog for this fork's sync: the one thing GitHub's own scheduler cannot do.
#
# Not part of CI. Nothing in .github/workflows calls this file, and it never writes to the
# repositories -- it reads upstream once a minute and, only when something about upstream has
# actually changed, asks GitHub to run the sync workflow. It exists because the sync's own
# `schedule:` cannot go below every 5 minutes, and since 2026-08-26 the platform has been
# delivering no scheduled runs here at all, which left the backup updating only when somebody
# dispatched a run by hand -- and left anything upstream posted and deleted in between
# invisible. Detection is this script's job; the recording stays in the workflow, where it has
# always been, so there is still exactly one thing that writes to the repositories.
#
# What it looks at: upstream's main commit, the numbers and timestamps of its issues and pull
# requests, its releases, and its tags. Any difference from the last look -- including a number
# that disappeared, which is what a deletion looks like from outside -- counts as a change.
# Nothing is dispatched twice for the same shape of upstream, and nothing is dispatched while a
# run is already going, so a busy upstream settles into "one run at a time, as fast as the runs
# themselves finish" instead of a queue of them, and an idle one asks for a handful a day.
#
# Its memory lives in %LOCALAPPDATA%\forbric-upstream-watch, never in the working clone, so it
# cannot dirty a checkout or be confused by a branch someone is on. A read that fails for an
# ordinary reason (no network, gh logged out, GitHub down) is logged and retried on the next
# tick, and never dispatches on a shape it could not read. A read that answers 404 is different
# and dispatches: a repository that has stopped existing is the change this fork was built to
# survive, not a hiccup, and the run it asks for is the one that raises the alert issue.
#
# Install and remove it with install-watch-task.ps1 next to this file, which registers it as a
# Windows scheduled task (once a minute is the smallest Windows repeats, and it is why the task
# exists rather than a tighter cron): it runs only while the user is signed in, so a machine
# that is asleep simply stops watching until it wakes.

[CmdletBinding()]
param(
    # Print the decision without dispatching anything.
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$Upstream = 'Ray-T-r/Minecraft-Forbric-mod-loader'
$Fork = 'xianaldai/Minecraft-Forbric-mod-loader-backup-for-s-t-author-who-is-f-king-traffic'
$Workflow = 'sync-upstream.yml'

# Upstream changing more often than this is upstream having a bad day; the sync should absorb
# that, not a dispatch per minute. Also the ceiling on the damage a bug in this probe could do.
$MinSeconds = 90
# With nothing changing at all, ask for a run this often: upstream can always change something
# this probe does not look at, and a run that finds nothing new writes nothing.
$HeartbeatHours = 6
# And a hard cap for the day, insurance against this probe being the broken thing rather than
# upstream being busy. Not the real limiter: that is the in-flight check below, which cannot be
# tuned into a runaway because it only ever dispatches into an idle window.
$MaxPerDay = 300

$StateDir = Join-Path $env:LOCALAPPDATA 'forbric-upstream-watch'
$StatePath = Join-Path $StateDir 'watch-state.json'
$LogPath = Join-Path $StateDir 'watch.log'

New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
if (-not (Test-Path -LiteralPath $LogPath)) { New-Item -ItemType File -Path $LogPath | Out-Null }

$Gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $Gh) { $Gh = 'C:\Program Files\GitHub CLI\gh.exe' }

function Write-WatchLog([string]$Message) {
    $line = '{0}  {1}' -f [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'), $Message
    Add-Content -LiteralPath $LogPath -Value $line -Encoding utf8
    # The log is only ever read by a person wondering what happened, so keep its tail.
    if ((Get-Item -LiteralPath $LogPath).Length -gt 512kb) {
        Set-Content -LiteralPath $LogPath -Value (Get-Content -LiteralPath $LogPath -Tail 2000) `
            -Encoding utf8
    }
}

function Invoke-Gh([string[]]$Arguments) {
    $out = & $Gh @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("gh {0} -> exit {1}: {2}" -f ($Arguments -join ' '), $LASTEXITCODE,
               ($out -join ' '))
    }
    return @($out)
}

# One hash over everything about upstream worth noticing. Per-feature lines rather than counts,
# because a count cannot tell an edit from an addition and cannot see a deletion at all.
function Get-UpstreamShape {
    $parts = New-Object System.Collections.Generic.List[string]

    # @() at the call site on purpose: PowerShell unrolls a one-element return into a plain
    # string, and indexing that would hand back its first character.
    $head = @(Invoke-Gh @('api', "repos/$Upstream/commits/main", '--jq', '.sha'))[0]
    $parts.Add('main ' + $head.Trim())

    # @tsv rather than jq's usual string interpolation, and not for looks: Windows PowerShell
    # 5.1 passes an argument with embedded double quotes through to the process unescaped, so
    # '.[] | "\(.number) \(.updated_at)"' reaches gh split in two and gh answers "accepts 1
    # arg(s), received 2". These filters contain no quote at all.
    $sources = @(
        @{ Label = 'issues'; Path = "repos/$Upstream/issues?state=all"; Filter = '.[] | [.number, .updated_at] | @tsv' },
        @{ Label = 'pulls'; Path = "repos/$Upstream/pulls?state=all"; Filter = '.[] | [.number, .updated_at] | @tsv' },
        @{ Label = 'releases'; Path = "repos/$Upstream/releases"; Filter = '.[] | [.tag_name, .updated_at] | @tsv' },
        @{ Label = 'tags'; Path = "repos/$Upstream/tags"; Filter = '.[] | [.name, .commit.sha] | @tsv' }
    )
    $summary = New-Object System.Collections.Generic.List[string]
    foreach ($source in $sources) {
        $lines = @(Invoke-Gh @('api', '--paginate', $source.Path, '--jq', $source.Filter) |
                   Where-Object { $_ -ne '' } | Sort-Object)
        foreach ($line in $lines) { $parts.Add($source.Label + ' ' + $line) }
        $summary.Add(('{0} {1}' -f $lines.Count, $source.Label))
    }

    $sha1 = [System.Security.Cryptography.SHA1]::Create()
    try {
        $hash = $sha1.ComputeHash([System.Text.Encoding]::UTF8.GetBytes(($parts -join "`n")))
    } finally {
        $sha1.Dispose()
    }
    return [pscustomobject]@{
        Sig = (($hash | ForEach-Object { $_.ToString('x2') }) -join '')
        Summary = ('main {0}, {1}' -f $head.Trim().Substring(0, 8), ($summary -join ', '))
    }
}

function Get-LastDispatch([object]$state) {
    if (-not $state -or -not $state.dispatched_at) { return $null }
    # Windows PowerShell turns an ISO string into a DateTime on the way in; keep both roads open.
    if ($state.dispatched_at -is [datetime]) { return $state.dispatched_at.ToUniversalTime() }
    return [datetime]::Parse($state.dispatched_at).ToUniversalTime()
}

$now = [DateTime]::UtcNow
$today = $now.ToString('yyyy-MM-dd')

$state = $null
if (Test-Path -LiteralPath $StatePath) {
    try { $state = Get-Content -Raw -LiteralPath $StatePath | ConvertFrom-Json } catch { $state = $null }
}

$gone = $false
try {
    $shape = Get-UpstreamShape
} catch {
    $message = $_.Exception.Message
    if ($message -match 'HTTP 404|HTTP 410|Not Found') {
        # Deleted, renamed, or made private. That is not a read that failed -- it is the one
        # change this fork exists to notice, and a watcher that only logged it would leave the
        # backup silent through the exact event the user built it for. Dispatch (under the same
        # guards); the sync job raises the alert issue and never touches main. The signature is
        # a word rather than a hash so that upstream coming back also reads as a change.
        $gone = $true
        $shape = [pscustomobject]@{ Sig = 'upstream-not-visible'
                                    Summary = 'upstream is not visible' }
    } else {
        # Network, auth, GitHub down: not a reason to guess, and not a reason to shout once a
        # minute either. The position goes in the log -- a watchdog that breaks quietly is
        # worse than none.
        Write-WatchLog ("could not read upstream: {0} [{1}] {2} {3}" -f $message,
                        ($_.InvocationInfo.PositionMessage -replace '\s+', ' ').Trim(),
                        $_.Exception.GetType().FullName,
                        ($_.ScriptStackTrace -replace '\s+', ' ').Trim())
        exit 0
    }
}

$last = Get-LastDispatch $state
$reason = $null
if ($gone) {
    $reason = 'upstream is not visible'
} elseif (-not $state -or -not $state.sig) {
    $reason = 'first look'
} elseif ($shape.Sig -ne $state.sig) {
    $reason = 'upstream changed'
} elseif ($last -and ($now - $last).TotalHours -ge $HeartbeatHours) {
    $reason = 'heartbeat'
}

$dayCount = if ($state -and $state.day -eq $today) { [int]$state.day_count } else { 0 }

if (-not $reason) {
    if (-not $state -or $state.day -ne $today) {
        Write-WatchLog ('watching; nothing has changed upstream ({0})' -f $shape.Summary)
    }
    exit 0
}

if ($last -and ($now - $last).TotalSeconds -lt $MinSeconds) {
    Write-WatchLog ('{0}, but the last dispatch was {1:N0}s ago; waiting for the next tick' -f
                    $reason, ($now - $last).TotalSeconds)
    exit 0
}

# A run already in flight is reading the same upstream: it will have picked up everything that
# changed while it was starting, so a second dispatch now would only queue another run behind
# the workflow's concurrency group. Skipping costs nothing, because the next tick sees the same
# difference and dispatches once the run has finished. This is what keeps a busy upstream from
# turning into a stack of runs.
$latest = @((Invoke-Gh @('run', 'list', '--repo', $Fork, '--workflow', $Workflow, '--limit', '1',
                         '--json', 'databaseId,status')) | ConvertFrom-Json)[0]
if ($latest -and 'queued', 'in_progress', 'waiting', 'requested', 'pending' -contains $latest.status) {
    Write-WatchLog ('{0}, but run {1} is still {2}; waiting for it' -f
                    $reason, $latest.databaseId, $latest.status)
    exit 0
}

if ($dayCount -ge $MaxPerDay) {
    Write-WatchLog ('{0}, but {1} dispatches already today; not asking for more' -f
                    $reason, $dayCount)
    exit 0
}

if ($DryRun) {
    Write-WatchLog ('dry run: would dispatch the sync ({0}: {1})' -f $reason, $shape.Summary)
    exit 0
}

try {
    Invoke-Gh @('workflow', 'run', $Workflow, '--repo', $Fork) | Out-Null
} catch {
    Write-WatchLog ('could not dispatch the sync: {0}' -f $_.Exception.Message)
    exit 0
}

$newState = [ordered]@{
    sig = $shape.Sig
    dispatched_at = $now.ToString('yyyy-MM-ddTHH:mm:ssZ')
    summary = $shape.Summary
    day = $today
    day_count = $dayCount + 1
}
$newState | ConvertTo-Json | Set-Content -LiteralPath $StatePath -Encoding utf8
Write-WatchLog ('dispatched the sync ({0}: {1})' -f $reason, $shape.Summary)
