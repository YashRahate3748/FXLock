param([switch]Browser)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if ($Browser) { & mvn '-DrunBrowser=true' test }
else { & mvn test }
exit $LASTEXITCODE
