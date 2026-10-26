param([switch]$Test)
$ErrorActionPreference = 'Stop'
if (!(Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven must be on PATH.' }
$mavenVersion = (& mvn -version | Out-String)
if ($LASTEXITCODE -ne 0 -or $mavenVersion -notmatch 'Java version: 25(?:\.|,)') {
    throw 'Maven must run with Java 25. Check JAVA_HOME and PATH.'
}

$tests = @{
    'rate-service' = 'RateValidationServiceTest,TrustedRateCacheTest'
    'settlement-service' = 'SettlementApiTest,IntegrationSettlementApiTest,InternalCustomerResolverTest,LoginVerificationServiceTest,AccountPasswordProfileTest,PlaintextAccountServiceTest,PlaintextAccountApiTest,FundingTransactionTest,CustomerTransferHistoryTest,HistoryApiTest,DashboardApiTest,DashboardBrowserFlowTest'
}
if ($Test) {
    Push-Location -LiteralPath (Join-Path $PSScriptRoot 'frontend')
    try {
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw 'Frontend checks failed.' }
    } finally { Pop-Location }
}
& (Join-Path $PSScriptRoot 'frontend/build-dashboard.ps1')
foreach ($service in @('rate-service', 'settlement-service', 'quote-service')) {
    Push-Location -LiteralPath (Join-Path $PSScriptRoot $service)
    try {
        Write-Host "Building $service ..."
        # The existing M1 context and M3 rollback tests contact Oracle; never run them implicitly.
        if (!$Test) { & mvn '-DskipTests' package }
        elseif ($tests.ContainsKey($service)) { & mvn "-Dtest=$($tests[$service])" package }
        else { & mvn package }
        if ($LASTEXITCODE -ne 0) { throw "Maven failed for $service. No services were started." }
    } finally { Pop-Location }
}
Write-Host 'All three application jars are built.'
