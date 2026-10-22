param([string]$BaseUrl = 'http://localhost:8082', [string]$Username = 'customer-demo', [string]$Password = 'learn-fxlock')
$ErrorActionPreference = 'Stop'
# Run against demo after starting the application. Creates and cancels one quote.
$login = Invoke-WebRequest "$BaseUrl/login" -SessionVariable m2Session
$csrf = [regex]::Match($login.Content, 'name="_csrf"[^>]*value="([^"]+)"').Groups[1].Value
if (-not $csrf) { throw 'Could not find the login CSRF token.' }
$null = Invoke-WebRequest "$BaseUrl/login" -WebSession $m2Session -Method Post -Body @{username=$Username;password=$Password;_csrf=$csrf}
$page = Invoke-WebRequest "$BaseUrl/" -WebSession $m2Session
$token = [regex]::Match($page.Content, 'name="csrf-token"[^>]*content="([^"]+)"').Groups[1].Value
$header = [regex]::Match($page.Content, 'name="csrf-header"[^>]*content="([^"]+)"').Groups[1].Value
if (-not $token -or -not $header) { throw 'Login failed or page CSRF token was not found.' }
$headers = @{}; $headers[$header] = $token
$wallets = Invoke-RestMethod "$BaseUrl/wallets/quote-options" -WebSession $m2Session
$source = $wallets | Where-Object { $_.currency -eq 'EUR' -and $_.canDebit } | Select-Object -First 1
$target = $wallets | Where-Object { $_.currency -eq 'USD' } | Select-Object -First 1
if (-not $source -or -not $target) { throw 'No eligible wallet pair was supplied.' }
$body = @{sourceCurrency='EUR';targetCurrency='USD';sourceAmount='1000.00';sourceWalletId=$source.walletId;targetWalletId=$target.walletId} | ConvertTo-Json
$created = Invoke-RestMethod "$BaseUrl/quotes" -Method Post -WebSession $m2Session -Headers $headers -ContentType 'application/json' -Body $body
$id = $created.quote.quoteId
$loaded = Invoke-RestMethod "$BaseUrl/quotes/$id" -WebSession $m2Session
if ($loaded.quote.targetAmount -ne $created.quote.targetAmount) { throw 'Stored amount changed.' }
$cancelled = Invoke-RestMethod "$BaseUrl/quotes/$id/cancel" -Method Post -WebSession $m2Session -Headers $headers
if ($cancelled.quote.status -ne 'CANCELLED') { throw 'Cancellation was not confirmed.' }
Write-Output "PASS: logged in, created quote $id, reloaded exact amount, and cancelled."
