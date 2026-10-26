param([string]$Username=$env:M4_OPS_USERNAME,[string]$Password=$env:M4_OPS_PASSWORD)
$ErrorActionPreference='Stop'
if(!$Username) {$Username='fxlock-ops'}
if(!$Password) {throw 'Set M4_OPS_PASSWORD before running the smoke test'}
$auth=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Username}:${Password}"))
$headers=@{Authorization="Basic $auth"}
Invoke-RestMethod http://localhost:8084/actuator/health
Invoke-RestMethod http://localhost:8084/api/v1/operations/summary -Headers $headers
Invoke-RestMethod http://localhost:8084/api/v1/operations/outbox?limit=10 -Headers $headers
Invoke-RestMethod http://localhost:8084/api/v1/operations/audit?limit=10 -Headers $headers
$registry=Invoke-RestMethod http://localhost:8761/eureka/apps -Headers @{Accept='application/json'}
$names=@($registry.applications.application | ForEach-Object {$_.name})
foreach($name in @('RATE-SERVICE','QUOTE-SERVICE','SETTLEMENT-SERVICE','EVENT-SERVICE')) {
 if($names -notcontains $name) {throw "Missing from Eureka: $name. Allow up to 90 seconds after startup."}
}
'All four services are registered; operations APIs responded.'
