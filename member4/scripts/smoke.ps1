param([string]$EventUrl='http://localhost:8084',[string]$EurekaUrl='http://localhost:8761')
$ErrorActionPreference='Stop'
if (!$env:OPS_PASSWORD) {throw 'Set OPS_USER and OPS_PASSWORD first'}
$username=if($env:OPS_USER){$env:OPS_USER}else{'ops'}
$basic=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${username}:$env:OPS_PASSWORD"))
$headers=@{Authorization="Basic $basic"}
$health=Invoke-RestMethod "$EventUrl/actuator/health"
if($health.status -ne 'UP'){throw 'Event service health is not UP'}
$registry=Invoke-RestMethod "$EurekaUrl/eureka/apps/EVENT-SERVICE" -Headers @{Accept='application/json'}
if (!($registry.application.instance | Where-Object {$_.status -eq 'UP'})){throw 'No UP EVENT-SERVICE registration'}
$audit=Invoke-RestMethod "$EventUrl/api/audit?size=1" -Headers $headers
$outbox=Invoke-RestMethod "$EventUrl/api/outbox?size=1" -Headers $headers
$screen=Invoke-WebRequest "$EventUrl/" -Headers $headers
if($screen.Content -notmatch 'FX operations'){throw 'Operations UI missing'}
New-Item -ItemType Directory -Force evidence | Out-Null
@{timestamp=(Get-Date).ToUniversalTime().ToString('o');health=$health;registry=$registry;screenStatus=$screen.StatusCode;auditSample=$audit;outboxSample=$outbox} |
  ConvertTo-Json -Depth 12 | Set-Content evidence/smoke.json
Write-Output 'Smoke checks passed. Evidence: evidence/smoke.json'
