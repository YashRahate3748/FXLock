function Assert-FxlockPortAvailable {
    param([int]$Port)
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    try {
        $listener.Server.ExclusiveAddressUse = $true
        $listener.Start()
    } catch {
        throw "Port $Port is already in use or cannot be bound. Stop the existing service before launching FXLock."
    } finally { $listener.Stop() }
}

function Start-FxlockProcess {
    param([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$WorkingDirectory,
          [string]$LogDirectory, [System.Collections.IList]$Processes)
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $LogDirectory "$Name.out.log") `
        -RedirectStandardError (Join-Path $LogDirectory "$Name.err.log")
    $Processes.Add($process) | Out-Null
    return $process
}

function Wait-FxlockReady {
    param([string]$Name, [string]$Url, [hashtable]$Headers = @{},
          [System.Collections.IList]$Processes, [int]$TimeoutSeconds = 120)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        foreach ($process in $Processes) {
            if ($process.HasExited) { throw "A service exited during $Name startup. Check the run log directory." }
        }
        try {
            $response = Invoke-RestMethod -Uri $Url -Headers $Headers -TimeoutSec 2 -ErrorAction Stop
            if ($response.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name was not ready within $TimeoutSeconds seconds. Check its logs, database settings and required schema."
}

function Stop-FxlockProcesses {
    param([System.Collections.IList]$Processes)
    for ($index = $Processes.Count - 1; $index -ge 0; $index--) {
        $process = $Processes[$index]
        try {
            if (!$process.HasExited) {
                $process.Kill()
                $process.WaitForExit(5000) | Out-Null
            }
        } catch { Write-Warning 'A process could not be stopped. Check the service logs.' }
        finally { $process.Dispose() }
    }
}
