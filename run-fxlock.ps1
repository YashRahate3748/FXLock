param(
    [switch]$Build,
    [switch]$Check,
    [switch]$PlaintextAccountDemo,
    [string]$SettingsPath = "$env:LOCALAPPDATA\FXLock\application.settings.xml",
    [string]$CredentialPath = "$env:LOCALAPPDATA\FXLock\common-oracle.credentials.xml",
    [ValidateRange(1, 65535)][int]$RatePort = 8081,
    [ValidateRange(1, 65535)][int]$QuotePort = 8082,
    [ValidateRange(1, 65535)][int]$SettlementPort = 8083,
    [ValidateRange(1, 600)][int]$StartupTimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\Fxlock.Processes.ps1')

function New-FxlockSecret {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

function Get-FxlockValue {
    param([string[]]$Names, [string]$Default)
    foreach ($name in $Names) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if (![string]::IsNullOrWhiteSpace($value)) { return $value }
    }
    return $Default
}

if ($env:OS -ne 'Windows_NT') { throw 'Use this launcher on Windows with PowerShell 5.1 or newer.' }
$java = (Get-Command java -ErrorAction Stop).Source
$version = (& $java --version | Out-String)
if ($version -notmatch '^(?:openjdk|java) 25(?:\.|\s)') { throw 'FXLock requires Java 25 on PATH.' }
if (@($RatePort, $QuotePort, $SettlementPort | Select-Object -Unique).Count -ne 3) { throw 'Service ports must be distinct.' }

$settings = $null
$credential = $null
if (Test-Path -LiteralPath $SettingsPath) {
    $settings = Import-Clixml -LiteralPath $SettingsPath
    $credential = $settings.Credential
} elseif (Test-Path -LiteralPath $CredentialPath) {
    $credential = Import-Clixml -LiteralPath $CredentialPath
}
if ($null -ne $credential -and $credential -isnot [System.Management.Automation.PSCredential]) {
    throw 'Invalid saved Oracle credential. Run configure-fxlock.ps1 again.'
}
$savedPassword = if ($credential) { $credential.GetNetworkCredential().Password } else { '' }
$savedUsername = if ($credential) { $credential.UserName } else { 'FXLOCK' }
$savedUrl = if ($settings) { $settings.DatabaseUrl } else { 'jdbc:oracle:thin:@//localhost:1521/FREEPDB1' }
$savedKey = if ($settings -and $settings.ProviderApiKey -is [System.Security.SecureString]) {
    [System.Net.NetworkCredential]::new('', $settings.ProviderApiKey).Password
} else { '' }

$databaseUrl = Get-FxlockValue -Names @('ORACLE_JDBC_URL', 'FXLOCK_DB_URL', 'DB_URL') -Default $savedUrl
$databaseUsername = Get-FxlockValue -Names @('ORACLE_USERNAME', 'FXLOCK_DB_USERNAME', 'DB_USERNAME') -Default $savedUsername
$databasePassword = Get-FxlockValue -Names @('ORACLE_PASSWORD', 'FXLOCK_DB_PASSWORD', 'DB_PASSWORD') -Default $savedPassword
$apiKey = Get-FxlockValue -Names @('TWELVE_DATA_API_KEY') -Default $savedKey
if ([string]::IsNullOrWhiteSpace($databasePassword) -or [string]::IsNullOrWhiteSpace($apiKey)) {
    throw 'Oracle credentials and TWELVE_DATA_API_KEY are required. Run .\configure-fxlock.cmd once or supply environment variables.'
}
if ($databaseUrl -notmatch '^jdbc:oracle:thin:') { throw 'Use the existing shared Oracle JDBC URL.' }

foreach ($port in @($RatePort, $QuotePort, $SettlementPort)) { Assert-FxlockPortAvailable -Port $port }
if ($Build) { & (Join-Path $PSScriptRoot 'build-fxlock.ps1') }
$jars = @{}
foreach ($service in @('rate-service', 'settlement-service', 'quote-service')) {
    [xml]$pom = Get-Content -LiteralPath (Join-Path $PSScriptRoot "$service\pom.xml") -Raw
    $jar = Join-Path $PSScriptRoot "$service\target\$($pom.project.artifactId)-$($pom.project.version).jar"
    if (!(Test-Path -LiteralPath $jar)) { throw "Missing $service jar. Run .\run-fxlock.cmd -Build." }
    $jars[$service] = $jar
}
if ($Check) {
    Write-Host 'Preflight passed: Java 25, settings, distinct available ports and all three jars. No services or database connections were started.'
    return
}

$environment = @{
    # Spring's standard overrides force every service onto the SAME existing database.
    SPRING_DATASOURCE_URL = $databaseUrl
    SPRING_DATASOURCE_USERNAME = $databaseUsername
    SPRING_DATASOURCE_PASSWORD = $databasePassword
    ORACLE_JDBC_URL = $databaseUrl
    ORACLE_USERNAME = $databaseUsername
    ORACLE_PASSWORD = $databasePassword
    FXLOCK_DB_URL = $databaseUrl
    FXLOCK_DB_USERNAME = $databaseUsername
    FXLOCK_DB_PASSWORD = $databasePassword
    DB_PASSWORD = $databasePassword
    TWELVE_DATA_API_KEY = $apiKey
    FX_INTERNAL_TOKEN = (Get-FxlockValue -Names @('FX_INTERNAL_TOKEN') -Default (New-FxlockSecret))
    FXLOCK_SERVICE_USERNAME = (Get-FxlockValue -Names @('FXLOCK_SERVICE_USERNAME') -Default 'fxlock-service')
    FXLOCK_SERVICE_PASSWORD = (Get-FxlockValue -Names @('FXLOCK_SERVICE_PASSWORD') -Default (New-FxlockSecret))
    FXLOCK_RISK_USERNAME = (Get-FxlockValue -Names @('FXLOCK_RISK_USERNAME') -Default 'risk-analyst')
    FXLOCK_RISK_PASSWORD = (Get-FxlockValue -Names @('FXLOCK_RISK_PASSWORD') -Default (New-FxlockSecret))
    RATE_BASE_URL = "http://127.0.0.1:$RatePort"
    SETTLEMENT_BASE_URL = "http://127.0.0.1:$SettlementPort"
    FXLOCK_ACCOUNT_SERVICE_URL = "http://127.0.0.1:$SettlementPort"
}
$previous = @{}
$processes = [System.Collections.ArrayList]::new()
$logDirectory = Join-Path $PSScriptRoot ("target\runs\" + [DateTime]::Now.ToString('yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
Write-Host "Logs: $logDirectory"
try {
    foreach ($name in $environment.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $environment[$name], 'Process')
    }
    $services = @(
        @{ Name = 'settlement-service'; Profile = $(if ($PlaintextAccountDemo) { 'integration,plaintext-account-demo' } else { 'integration' }); Port = $SettlementPort; Health = "http://127.0.0.1:$SettlementPort/internal/health"; Headers = @{ 'X-Internal-Token' = $environment.FX_INTERNAL_TOKEN } },
        @{ Name = 'rate-service'; Profile = 'default'; Port = $RatePort; Health = "http://127.0.0.1:$RatePort/actuator/health"; Headers = @{} },
        @{ Name = 'quote-service'; Profile = 'team'; Port = $QuotePort; Health = "http://127.0.0.1:$QuotePort/actuator/health"; Headers = @{} }
    )
    foreach ($service in $services) {
        Write-Host "Starting $($service.Name) on port $($service.Port) ..."
        $arguments = @('-jar', ('"' + $jars[$service.Name] + '"'), "--spring.profiles.active=$($service.Profile)",
            "--server.port=$($service.Port)", '--server.address=127.0.0.1', '--eureka.client.enabled=false',
            '--spring.sql.init.mode=never', '--spring.jpa.hibernate.ddl-auto=validate')
        Start-FxlockProcess -Name $service.Name -Executable $java -Arguments $arguments `
            -WorkingDirectory (Join-Path $PSScriptRoot $service.Name) -LogDirectory $logDirectory -Processes $processes | Out-Null
        Wait-FxlockReady -Name $service.Name -Url $service.Health -Headers $service.Headers `
            -Processes $processes -TimeoutSeconds $StartupTimeoutSeconds
    }
    Write-Host "FXLock is ready: http://localhost:$QuotePort/login"
    Write-Host 'Press Ctrl+C here to stop all three services.'
    while ($true) {
        foreach ($process in $processes) {
            if ($process.HasExited) { throw 'A service exited. Stopping the application. Check the run logs.' }
        }
        Start-Sleep -Seconds 1
    }
} finally {
    Stop-FxlockProcesses -Processes $processes
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Write-Host 'FXLock services stopped.'
}
