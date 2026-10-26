param(
    [int]$Port = 8082,
    [string]$CredentialPath = "$env:LOCALAPPDATA\FXLock\common-oracle.credentials.xml"
)
$ErrorActionPreference = 'Stop'
$jar = Join-Path $PSScriptRoot 'target\quote-service-0.1.0-SNAPSHOT.jar'
if (!(Test-Path $jar)) { throw 'Build the project first using mvn package or build.ps1.' }
if (!(Test-Path $CredentialPath)) {
    throw 'Oracle credentials are missing. Follow docs/05-local-oracle.md to save them securely.'
}
$credential = Import-Clixml -LiteralPath $CredentialPath
if ($credential -isnot [System.Management.Automation.PSCredential]) { throw 'Invalid credential file.' }
$oldPassword = $env:ORACLE_PASSWORD
$oldUsername = $env:ORACLE_USERNAME
try {
    $env:ORACLE_USERNAME = $credential.UserName
    $env:ORACLE_PASSWORD = $credential.GetNetworkCredential().Password
    & java -jar $jar '--spring.profiles.active=oracle-local' "--server.port=$Port"
    if ($LASTEXITCODE -ne 0) { throw "Application exited with code $LASTEXITCODE" }
} finally {
    $env:ORACLE_PASSWORD = $oldPassword
    $env:ORACLE_USERNAME = $oldUsername
}
