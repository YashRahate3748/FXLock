$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
& mvn spring-boot:run '-Dspring-boot.run.profiles=demo'
exit $LASTEXITCODE
