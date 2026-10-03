# Register (or remove) the local watchdog that keeps this fork's sync within a minute of
# upstream. Companion to watch-upstream.ps1 -- read the header there for why it exists at all.
#
#   powershell -ExecutionPolicy Bypass -File .github\scripts\install-watch-task.ps1
#   powershell -ExecutionPolicy Bypass -File .github\scripts\install-watch-task.ps1 -Remove
#
# The task runs wscript.exe on a one-line launcher instead of powershell.exe directly: a task
# that runs in the logged-on user's session gets a console window for every run, and at one run
# a minute that is a window flashing on the desktop 1440 times a day. WScript.Shell.Run with a
# hidden window has none. Nothing here needs a password or an elevated prompt: the task runs as
# the signed-in user, which is also the only account that has gh authenticated.

[CmdletBinding()]
param(
    # Unregister the task instead of creating it. The log and state in %LOCALAPPDATA% stay.
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'

$TaskName = 'Forbric fork sync watchdog'
$Watcher = Join-Path $PSScriptRoot 'watch-upstream.ps1'
$StateDir = Join-Path $env:LOCALAPPDATA 'forbric-upstream-watch'
$Launcher = Join-Path $StateDir 'run-watch-hidden.vbs'

if ($Remove) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Output ("removed the task '{0}'; its log and state are still in {1}" -f $TaskName, $StateDir)
    exit 0
}

if (-not (Test-Path -LiteralPath $Watcher)) { throw "cannot find $Watcher" }
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null

# 0 = hidden window, False = do not wait for it.
$body = 'CreateObject("WScript.Shell").Run "powershell.exe -NoProfile -ExecutionPolicy ' +
        'Bypass -File ""' + $Watcher + '""", 0, False'
Set-Content -LiteralPath $Launcher -Value $body -Encoding ascii

$action = New-ScheduledTaskAction -Execute (Join-Path $env:SystemRoot 'System32\wscript.exe') `
    -Argument ('"' + $Launcher + '"')
# A minute is the smallest Windows repeats a task. MaxValue is deliberate: without an explicit
# duration, a once-trigger with a repetition interval repeats only for its interval and stops.
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date) `
    -RepetitionInterval (New-TimeSpan -Minutes 1) `
    -RepetitionDuration ([TimeSpan]::MaxValue)
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Settings $settings -Force `
    -Description ("Asks GitHub to run this fork's upstream sync when upstream has changed. " +
                  "LOCAL machine only -- a stand-in for the schedule trigger GitHub is not " +
                  'delivering.') | Out-Null

$task = Get-ScheduledTask -TaskName $TaskName
$info = Get-ScheduledTaskInfo -TaskName $TaskName
Write-Output ("task '{0}': {1}" -f $task.TaskName, $task.State)
Write-Output ("  next run: {0}" -f $info.NextRunTime)
Write-Output ("  log:      {0}" -f (Join-Path $StateDir 'watch.log'))
Write-Output ("  remove:   powershell -ExecutionPolicy Bypass -File `"{0}`" -Remove" -f $PSCommandPath)
