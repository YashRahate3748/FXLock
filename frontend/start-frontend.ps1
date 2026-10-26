param([switch]$Install)
$ErrorActionPreference='Stop'
Push-Location -LiteralPath $PSScriptRoot
try {
 $nodeCommand=Get-Command node -ErrorAction SilentlyContinue
 $nodePath=if($nodeCommand){$nodeCommand.Source}else{Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'}
 if(!(Test-Path -LiteralPath $nodePath)){throw 'Install Node.js first, then reopen PowerShell.'}
 if($Install -or !(Test-Path -LiteralPath 'node_modules\vite\bin\vite.js')) {
  $pnpmCommand=Get-Command pnpm -ErrorAction SilentlyContinue
  $bundledPnpm=Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\bin\fallback\pnpm.cmd'
  if($pnpmCommand){& $pnpmCommand.Source install --frozen-lockfile --ignore-scripts}
  elseif(Test-Path -LiteralPath $bundledPnpm){& $bundledPnpm install --frozen-lockfile --ignore-scripts}
  else {& npm ci}
  if($LASTEXITCODE -ne 0){throw 'Dependency installation failed.'}
 }
 Write-Host 'Open http://localhost:5173 - keep this window open. Start Quote Service on 8082 separately.'
 & $nodePath 'node_modules\vite\bin\vite.js' '--host' '127.0.0.1'
} finally {Pop-Location}
