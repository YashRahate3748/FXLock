# FXLock application

Run M1 (rates), M2 (browser quotes), M3 (accounts and settlement), and the existing
React/Vite frontend with one root `npm run dev` command. Node 22+, Java 25, Maven,
the installed frontend dependencies, existing shared Oracle schema, and a Twelve
Data API key are required. Keep Oracle running as you do today. The npm launcher
uses native Node/Java/Maven tools, never invokes PowerShell or changes execution
policy, and does not install packages, run migrations, reset balances, or start
Eureka. Direct backend URLs are used and discovery is explicitly disabled.

## One-time setup

From `C:\fxlock-application\FXLock`:

No root `npm install` is needed: the launcher uses Node's built-in modules and
reuses `frontend/node_modules`. It prompts for the Oracle JDBC URL and username
(with local defaults), the existing Oracle password and the Twelve Data API key
when they are absent from the environment. Secret input is hidden and is not
saved to files, command arguments, shell history or launcher logs. Enter the
credentials again on the next run, or supply them using your approved environment
configuration. The native launcher does not decrypt or modify the older
PowerShell-encrypted `application.settings.xml` file. The legacy configuration
scripts remain available for separately approved PowerShell use and are not
invoked by npm.

Your existing account and wallet-funding tables must already exist; the launcher
does not create them. Keep the table and index already created by V002; no trigger
privilege is required. See [dashboard setup](settlement-service/docs/customer-dashboard.md).

## Start and stop

Use a **Command Prompt** terminal in VS Code:

```bat
cd /d C:\fxlock-application\FXLock
npm run dev
```

In a PowerShell terminal use `npm.cmd run dev` to select the native npm executable
instead of `npm.ps1`; this does not change execution policy. Your organization
must still permit the installed Node, Java, Maven and CMD executables.

The command checks Java/Maven versions and occupied ports, builds React, copies
assets only into M3's dedicated dashboard folder, and packages all three services
using Maven offline with tests skipped. It then starts M3 using
`integration,plaintext-account-demo`, M1 using its normal provider configuration,
M2 using `team`, and Vite. Readiness checks run in that order. Maven never downloads
missing dependencies automatically; if cached artifacts are absent, the build
stops and prints the exact command for you to run manually through your approved
download process. No service starts after a build failure.

Once ready, open **http://localhost:8082/login**. Use the email and exact password stored for your
account, or click **Sign up** and enter name, email, password and confirmation.
Successful registration returns to login. M3 stores the submitted password unchanged
in `CUSTOMER_ACCOUNT.LOGIN_PASSWORD` only in the explicit unsafe local demo profile.
Use throwaway passwords only. Existing BCrypt rows require an explicit reset.
**Use demo username** remains available for `customer-demo` / `learn-fxlock` and
`customer-other`; `DEMO_PASSWORD` overrides those synthetic users' password.
Registered login opens the authenticated M3 dashboard at
**http://localhost:8083/dashboard/**. Get a quote returns to M2 without another
login, preserving the existing settlement receipt flow. For React hot reload,
open **http://localhost:5173/dashboard/** in the same browser after signing in.
Use `localhost` consistently so the distinct M2/M3 session cookies work across
ports. Vite proxies dashboard API requests to M3; internal tokens remain on the
backend and no unrestricted CORS is added.

The receipt's **View wallets** link opens `/wallets.html`, the existing M3 wallet
page. Its HTML, CSS, JavaScript and wallet/settlement data remain owned by M3;
M2 routes requests through the signed-in browser session. Refresh, settlement
lookup and acceptance work on that page with the existing customer checks and
CSRF protection. Acceptance state is stored separately for each customer.

To reuse the existing backend jars and hosted assets while Vite serves frontend changes:

```bat
npm run dev -- --no-build
```

Use the normal `npm run dev` after changing Java files. For build-only use
`npm run build`; for build plus isolated checks use `npm run build -- --test`.
The existing `run-fxlock.cmd` and `build-fxlock.cmd` now invoke the native Node
launcher too. The previous `-Build`, `-PlaintextAccountDemo`, `-Check` and `-Test`
flags are supported for those wrappers; custom ports use environment variables.

Keep the launcher terminal open. **Ctrl+C stops all four processes started by
that launcher.** A startup failure or unexpected service exit also cleans them
up. It refuses occupied ports and never terminates separately started services.
Stop IDE-started instances before using it. Logs are written to
`target\dev-runs\<run>\<service>.out.log` and `.err.log`, with known credentials
redacted. Optional environment variables are `FXLOCK_RATE_PORT` (8081),
`FXLOCK_QUOTE_PORT` (8082), `FXLOCK_SETTLEMENT_PORT` (8083),
`FXLOCK_FRONTEND_PORT` (5173), and `FXLOCK_STARTUP_TIMEOUT_SECONDS` (120).

The launcher forces one shared datasource into all processes and provides M2's
M1 service credentials and M3 internal token automatically. These service secrets
are randomly generated per run unless supplied in the environment. They are
passed in process environments, not command-line arguments or log messages.
M2 owns the browser account forms, Spring Security session and CSRF
protection. This local dev command explicitly selects the existing
`plaintext-account-demo` profile. It enables intentionally unsafe plaintext
storage in `LOGIN_PASSWORD`, for throwaway local passwords only; never deploy it.
Outside the permitted demonstration profile, account password operations remain disabled. See the
[demo setup and reset limitations](settlement-service/docs/plaintext-account-demo.md).
M2 forwards account registration and credential checks to M3 using its backend token.
M3 owns all account persistence and password comparison.

For environment-only setup, supply `TWELVE_DATA_API_KEY`, `ORACLE_PASSWORD`, and
optionally `ORACLE_JDBC_URL`/`ORACLE_USERNAME`. The existing M1 `FXLOCK_DB_*` and
M3 `DB_*` names are accepted as aliases; `ORACLE_*` takes precedence. The standard
`SPRING_DATASOURCE_*` variables are also accepted as fallback aliases. Optional `FX_INTERNAL_TOKEN`,
`FXLOCK_SERVICE_USERNAME`/`FXLOCK_SERVICE_PASSWORD`, and
`FXLOCK_RISK_USERNAME`/`FXLOCK_RISK_PASSWORD` override generated values. Supply
a risk password if you need to use M1's risk-management APIs yourself.

## Compatibility and financial behavior

The existing quote page now supports EUR→USD, USD→EUR and eligible customer-to-customer
FX transfers. Dashboard **Transaction history** opens the readable ledger at
`http://localhost:8083/dashboard/history/`, combining owned settlement movements and
independent simulated funding records. See the
[C2C/history handover](settlement-service/docs/c2c-history-integration.md) for routes,
identity/receipt privacy, read-only SQL checks and verification scope.

M2 uses M1's trusted-rate API and verifies the referenced snapshot in the same
Oracle schema. Registered emails authenticate through M3 and use its returned
customer reference. Demo users have a separate explicit login route; email rejection
or M3 unavailability never falls back to demo authentication. In `team`, M2 maps
`customer-demo` to `CUST001` and `customer-other`
to `CUST002`, then passes that
customer reference and the internal service token to M3; M3 resolves the
active customer and delegates to its existing settlement coordinator. Quote
ownership, wallet ownership, expiry, balance checks, transactions, ledger entries
and idempotency remain in the existing financial services.

M3's `local` profile still simulates CUST001 for standalone development. Never
combine it with `integration`; startup rejects that combination. M2's
`oracle-local` adapter now uses the same protected real-account M3 contract as `team`.
The shared launcher selects
`team`/`integration` so database accounts use their returned customer identities
and demo usernames use their explicit training mappings.

New accounts have **zero-balance wallets**. Use the dashboard's simulated funding
dialogs before settling a conversion; the launcher never funds accounts automatically. M1 also needs a fresh,
trusted provider rate with quoting enabled. Successful process health checks do
not guarantee provider quota, valid API credentials or an available quote.

## Verification without live data

```bat
npm run test:launcher
npm run build -- --test
npm run dev -- --check
```

The build selects M1 unit tests, isolated M3 funding/HTTP/resolver tests and M2's isolated suite.
It excludes M1's Oracle/provider context test and M3's real-database rollback
suite. The normal build skips tests because the legacy M1/M3 default test suites
can contact Oracle. `--check` validates local settings, available ports, jars and frontend assets
without launching services or connecting to the database. Launcher tests use a
disposable Node HTTP/process fixtures only. Live end-to-end testing is a separate manual
step against your configured Oracle and provider; no automated funding occurs.
