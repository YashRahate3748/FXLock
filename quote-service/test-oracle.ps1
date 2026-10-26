param(
    [switch]$Browser,
    [switch]$Package,
    [string]$CredentialPath = "$env:LOCALAPPDATA\FXLock\common-oracle.credentials.xml",
    [string]$MavenRepository
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $CredentialPath)) { throw 'Save the common FXLOCK credentials first; see docs/05-local-oracle.md.' }
$credential = Import-Clixml -LiteralPath $CredentialPath
$oldUser = $env:ORACLE_USERNAME
$oldPassword = $env:ORACLE_PASSWORD
$oldUrl = $env:ORACLE_JDBC_URL
Push-Location -LiteralPath $PSScriptRoot
try {
    $env:ORACLE_USERNAME = $credential.UserName
    $env:ORACLE_PASSWORD = $credential.GetNetworkCredential().Password
    if (!$env:ORACLE_JDBC_URL) { $env:ORACLE_JDBC_URL = 'jdbc:oracle:thin:@//localhost:1521/freepdb1' }
    $mavenArgs = @('-B','-ntp','-DrunOracle=true')
    if ($Browser) { $mavenArgs += '-DrunBrowser=true' }
    if ($MavenRepository) { $mavenArgs += "-Dmaven.repo.local=$MavenRepository" }
    $mavenArgs += $(if ($Package) { 'package' } else { 'test' })
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Verification failed with exit code $LASTEXITCODE" }
} finally {
    $env:ORACLE_USERNAME = $oldUser
    $env:ORACLE_PASSWORD = $oldPassword
    $env:ORACLE_JDBC_URL = $oldUrl
    Pop-Location
}
