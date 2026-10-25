param(
    [string]$SettingsPath = "$env:LOCALAPPDATA\FXLock\application.settings.xml",
    [string]$CredentialPath = "$env:LOCALAPPDATA\FXLock\common-oracle.credentials.xml",
    [string]$DatabaseUrl = 'jdbc:oracle:thin:@//localhost:1521/FREEPDB1'
)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'This configuration helper uses Windows credential encryption.' }

if (Test-Path -LiteralPath $CredentialPath) {
    $credential = Import-Clixml -LiteralPath $CredentialPath
    if ($credential -isnot [System.Management.Automation.PSCredential]) { throw 'Invalid saved Oracle credential.' }
    Write-Host 'Reusing your existing saved Oracle credential.'
} else {
    $credential = Get-Credential -UserName 'FXLOCK' -Message 'Existing shared Oracle application account'
    if ($null -eq $credential) { throw 'Oracle credentials are required.' }
}
$apiKey = Read-Host 'Twelve Data API key (input hidden)' -AsSecureString
if ($apiKey.Length -eq 0) { throw 'The provider API key is required.' }
if ([string]::IsNullOrWhiteSpace($DatabaseUrl)) { throw 'DatabaseUrl is required.' }

$directory = Split-Path -Parent $SettingsPath
if (!(Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null }
[pscustomobject]@{
    DatabaseUrl = $DatabaseUrl
    Credential = $credential
    ProviderApiKey = $apiKey
} | Export-Clixml -LiteralPath $SettingsPath
Write-Host "Settings saved to $SettingsPath. Secrets are encrypted for your Windows user on this computer."
Write-Host 'Run .\run-fxlock.cmd -Build from the repository root.'
