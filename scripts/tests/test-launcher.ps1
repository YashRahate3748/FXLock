$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $root 'scripts\Fxlock.Processes.ps1')

foreach ($file in @('configure-fxlock.ps1', 'build-fxlock.ps1', 'run-fxlock.ps1',
                    'scripts\Fxlock.Processes.ps1', 'scripts\tests\health-fixture.ps1')) {
    $parseErrors = $null
    $tokens = $null
    [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $root $file), [ref]$tokens, [ref]$parseErrors) | Out-Null
    if ($parseErrors.Count -gt 0) { throw "$file has syntax errors: $parseErrors" }
}

# Only a disposable local HTTP fixture is launched. No Java services, provider calls or database writes.
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$rejected = $false
try { Assert-FxlockPortAvailable -Port $port } catch { $rejected = $true }
finally { $listener.Stop() }
if (!$rejected) { throw 'An occupied port was not rejected.' }
Assert-FxlockPortAvailable -Port $port

$processes = [System.Collections.ArrayList]::new()
$logs = Join-Path $root ('target\launcher-tests\' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $logs -Force | Out-Null
$executable = (Get-Process -Id $PID).Path
$fixture = Join-Path $PSScriptRoot 'health-fixture.ps1'
try {
    $process = Start-FxlockProcess -Name 'fixture' -Executable $executable `
        -Arguments @('-NoProfile', '-File', ('"' + $fixture + '"'), '-Port', $port) `
        -WorkingDirectory $root -LogDirectory $logs -Processes $processes
    $fixtureId = $process.Id
    Wait-FxlockReady -Name 'fixture' -Url "http://127.0.0.1:$port/" -Processes $processes -TimeoutSeconds 15
    $timedOut = $false
    try {
        Wait-FxlockReady -Name 'unavailable fixture' -Url 'http://127.0.0.1:1/' -Processes $processes -TimeoutSeconds 1
    } catch { $timedOut = $_.Exception.Message -like '*not ready*' }
    if (!$timedOut) { throw 'The readiness timeout was not reported.' }
    $process.Kill()
    $process.WaitForExit(5000) | Out-Null
    $exited = $false
    try { Wait-FxlockReady -Name 'exited fixture' -Url "http://127.0.0.1:$port/" -Processes $processes -TimeoutSeconds 1 }
    catch { $exited = $_.Exception.Message -like '*exited*' }
    if (!$exited) { throw 'An exited service was not detected.' }
} finally { Stop-FxlockProcesses -Processes $processes }
if (Get-Process -Id $fixtureId -ErrorAction SilentlyContinue) { throw 'The fixture process was not stopped.' }

# Confirm cleanup also terminates a still-running process.
$processes = [System.Collections.ArrayList]::new()
try {
    $running = Start-FxlockProcess -Name 'cleanup-fixture' -Executable $executable `
        -Arguments @('-NoProfile', '-Command', 'Start-Sleep -Seconds 30') `
        -WorkingDirectory $root -LogDirectory $logs -Processes $processes
    $runningId = $running.Id
} finally { Stop-FxlockProcesses -Processes $processes }
if (Get-Process -Id $runningId -ErrorAction SilentlyContinue) { throw 'Cleanup left a running process.' }
Write-Host 'Launcher checks passed: parsing, port conflicts, readiness, timeout, early exit and process cleanup.'
