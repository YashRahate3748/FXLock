$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
& mvn package
exit $LASTEXITCODE
