param([string]$LocalRepository)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $directSettings=Join-Path $projectRoot 'config\maven-direct.xml'
    $mavenArguments=@('-s',$directSettings,'-gs',$directSettings,'-U','clean','install')
    if($LocalRepository) {$mavenArguments=@("-Dmaven.repo.local=$LocalRepository")+$mavenArguments}
    & mvn @mavenArguments
    if($LASTEXITCODE -ne 0){throw "Maven build failed (exit $LASTEXITCODE). See the FIRST Maven error above."}
} finally {Pop-Location}
