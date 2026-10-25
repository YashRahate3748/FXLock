$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath $PSScriptRoot
try {
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Dashboard build failed; no assets were copied.' }
    $dashboardTarget = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../settlement-service/src/main/resources/static/dashboard'))
    $expectedTarget = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../settlement-service/src/main/resources/static/dashboard'))
    if ($dashboardTarget -ne $expectedTarget -or (Split-Path $dashboardTarget -Leaf) -ne 'dashboard') { throw 'Unexpected dashboard destination.' }
    New-Item -ItemType Directory -Path $dashboardTarget -Force | Out-Null
    # Only this script's asset directory and index are replaced; other M3 static files are untouched.
    $assetTarget = Join-Path $dashboardTarget 'assets'
    if (Test-Path -LiteralPath $assetTarget) {
        $resolvedAssets = [IO.Path]::GetFullPath($assetTarget)
        if ($resolvedAssets -ne (Join-Path $expectedTarget 'assets')) { throw 'Unexpected asset path.' }
        Remove-Item -LiteralPath $resolvedAssets -Recurse -Force
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'dist/index.html') -Destination $dashboardTarget -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'dist/assets') -Destination $dashboardTarget -Recurse -Force
} finally { Pop-Location }
