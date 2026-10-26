# Customer dashboard and simulated wallet funding

The existing React project serves the customer dashboard at `http://localhost:8083/dashboard/`.
M2's existing quote page remains `http://localhost:8082/`. Registration, login, quotes,
review, acceptance and settlement receipt pages retain their existing layouts and business logic.
Funding affects simulated wallet balances only. There is no bank, card, payment collection or payout integration.

## Manual database preparation

No migration, seed script, balance reset or real-database test is run automatically.

1. Use the **already installed common schema** described in `quote-service/sql/01_fxlock_schema.sql`:
   `CUSTOMER`, numeric wallet/customer IDs, `WALLET.BALANCE NUMBER(19,4)`, `VERSION_NO`,
   `STATUS`, `IDEMPOTENCY_RECORD`, `AUDIT_EVENT` and `OUTBOX_EVENT` must already exist.
   **Do not rerun that common schema script: it contains destructive cleanup.**
2. If `CUSTOMER_ACCOUNT` already exists, leave it unchanged. Otherwise, review and run the existing
   `settlement-service/database/migrations/create_customer_account.sql` once, after `CUSTOMER` exists.
3. Review and run the new `settlement-service/database/migrations/V002__wallet_funding.sql` once.
   It only creates `WALLET_FUNDING` and its index. It links funding to existing
   customers, wallets and unique idempotency keys. It does not alter existing objects or records.

`V001__create_customers_wallets.sql` describes an incompatible legacy schema with string IDs and
`FX_CUSTOMER`. Do not run it for this application. Verify the installed schema before applying V002:

```sql
SELECT table_name, column_name, data_type, data_precision, data_scale
FROM user_tab_columns
WHERE table_name IN ('CUSTOMER','CUSTOMER_ACCOUNT','WALLET','IDEMPOTENCY_RECORD')
ORDER BY table_name, column_id;
```

### Using the existing database privileges

The migration no longer creates `TRG_FUNDING_IMMUTABLE` and does not require `CREATE TRIGGER`.
If an earlier execution reported that `WALLET_FUNDING` and `IDX_FUNDING_CUSTOMER_TIME` were created,
followed by `ORA-01031` for the trigger, keep the table and index. Do not rerun the migration or
recreate those objects. No additional database change is needed for this version, and no privilege
grant is requested. An existing successfully created trigger, if any, is left untouched.

Funding history is append-only through the application's supported paths: the repository inserts
and reads financial records, and the dashboard exposes no update/delete operation for them.
Wallet balance changes, financial inserts, audit/outbox writes and idempotency completion remain
one transaction. Matching retries read the original record instead of editing it.

This is application-level immutability, not database-enforced immutability. The runtime connection
uses the `FXLOCK` schema owner, which can still update/delete records using direct SQL or other code.
Existing constraints still validate relationships and financial values but cannot prohibit every
change or deletion. Strong database enforcement would need an approved database control such as
the previously proposed trigger or a separate runtime identity with restricted privileges. Neither
is assumed available in this local demonstration.

## Authentication and configuration

Run M2 with its existing `team` (or supported `oracle-local`) profile, and M3 with
`integration,plaintext-account-demo`. Preserve your existing database and service configuration.
Both services need the same configured `FX_INTERNAL_TOKEN`; never place it in browser code or URLs.

The local implementation uses `CUSTOMER_ACCOUNT.LOGIN_PASSWORD`, **not `PASSWORD_HASH`**.
Funding calls the existing profile-selected `AccountPasswords` verifier. It does not change stored
passwords, apply registration complexity rules or trim submitted passwords. The plaintext verifier
remains deliberately restricted to the explicit integration demonstration profile and refuses
`local`, `prod` and `production`. Use disposable demonstration passwords only. This task does not
make that existing credential storage production-ready; password operations remain disabled outside
the supported profile.

Additional settings (defaults shown; no secret values):

| Environment variable | Service | Default / purpose |
| --- | --- | --- |
| `FXLOCK_DASHBOARD_URL` | M2 | `http://localhost:8083`, browser callback base |
| `FXLOCK_QUOTE_BROWSER_URL` | M3 | `http://localhost:8082`, browser navigation and backend redemption base |
| `FXLOCK_FUNDING_MAX_AMOUNT` | M3 | `1000000.00`, maximum per funding operation |
| `FXLOCK_FUNDING_PASSWORD_ATTEMPTS` | M3 | `5`, incorrect confirmations per customer per window |
| `FXLOCK_FUNDING_PASSWORD_WINDOW_SECONDS` | M3 | `60`, fixed window, 1–3600 seconds |

Existing `FXLOCK_ACCOUNT_SERVICE_URL` points M2's verification calls to M3. Existing Oracle and
rate/settlement environment settings still apply. When deploying behind HTTPS, configure secure
session cookies and trusted service URLs for both services; the current plaintext account profile
is strictly a local demonstration and cannot be used as a production authentication configuration.

After M3 verifies a registered customer's login, M2 redirects through `/dashboard/start` to
M3 `/dashboard/login`. M3 creates a cryptographically random state in its server session and
redirects to authenticated M2 `/dashboard/handoff`. M2 issues a 256-bit random code, bound to
that state, verified principal and originating live M2 session. Codes expire after 60 seconds and
are removed on redemption. M3 validates its state (maximum 120 seconds), redeems the code with
a backend POST to M2 `/internal/dashboard/redeem` authenticated by the internal token, checks the
active account and rotates its session ID. The final redirect to `/dashboard/` removes the code
and state from the visible URL. No credentials or internal tokens appear in those URLs.
Handoff grants are held in M2 memory; a restart invalidates outstanding codes.

Cookie names are `FXLOCK_M2_SESSION` and `FXLOCK_M3_SESSION`, avoiding localhost port collisions.
M3's cookie is HttpOnly, SameSite=Lax, with a 30-minute session timeout. M2 remains authenticated
when the customer follows **Get a quote** to its original `/` route. Dashboard APIs use only the
verified M3 server session; customer references supplied by the browser cannot select an identity.
Dashboard mutation APIs require the synchronizer CSRF token returned by `GET /api/dashboard`,
in `X-CSRF-TOKEN`. No credentialed CORS is added. Existing M3 internal APIs retain their own
backend token authentication.

Logout is a coordinated browser sequence: the dashboard's CSRF-protected POST invalidates M3,
then opens M2's **Log out of FXLock** form. Submit it to invalidate M2, then submit M3's
**Finish logout** form to clear any remaining M3 session and return to M2 login. Existing M2
logout also routes through this M3 finish step. These forms require CSRF and explicit submission;
visiting a GET link alone does not log the customer out. No full-logout success message is shown
while the sequence is incomplete. If either service is unavailable, finish logout when it returns.

## Funding and recovery

New M3 endpoints:

| Method / route | Purpose |
| --- | --- |
| `GET /api/dashboard` | Safe account DTO, real wallet statuses/balances, CSRF and quote URL |
| `POST /api/dashboard/funding` | Password-confirmed simulated DEPOSIT or WITHDRAWAL |
| `GET /api/dashboard/funding/{key}` | Authenticated customer-owned committed outcome |
| `POST /api/dashboard/logout` | Start coordinated logout |

Funding JSON contains `operation`, `walletId`, `amount` (decimal string) and `password`.
The request requires an `Idempotency-Key` header (16–100 URL-safe letters/digits/underscore/hyphen).
Passwords are write-only DTO fields with redacted string representations. They never enter funding,
audit, outbox, idempotency fingerprints/results or browser storage.

Each submission, including replay, confirms the current password before opening the wallet transaction.
Incorrect attempts are limited per authenticated customer in M3 memory, using a fixed expiry that
blocked requests cannot extend. Other customers are unaffected. Restarting M3 clears this throttle;
multi-instance deployment needs a shared rate limiter. The map is bounded and fails closed at capacity.

The Oracle transaction locks the current customer/account, claims the unique existing idempotency key,
then locks the wallet through the same `WalletRepository.findByIdForUpdate` used by FX settlement.
It rechecks activity, ownership, currency, balance and numeric limits, updates balance/version/time,
inserts the application-level append-only funding record, audit and distinct simulated-funding outbox event, and completes
the idempotency record. Required write failures roll back everything. There are no remote calls while
wallet locks are held. Existing settlement-only ledger entries and quotes are untouched.

Only positive EUR/USD inputs with at most two decimal places are accepted; excess precision, including
`1.000`, is rejected. Existing four-place wallet balances are retained. BigDecimal and Oracle NUMBER
perform financial arithmetic. A wallet's four-place residual cannot be withdrawn using an input
with more than two decimals. The wallet maximum is `999999999999999.9999`, matching NUMBER(19,4).

The database unique key serializes concurrent identical submissions. The fingerprint includes the
funding domain, customer, operation, wallet and canonical decimal amount. Changed intents conflict.
Valid replay reads the original funding result before checking a current balance, so a later withdrawal
cannot invalidate an earlier confirmed result. Lookup is customer-scoped and never mutates.

Before submission, React saves only an allowlisted non-sensitive intent and original key in
customer-scoped `sessionStorage`. It clears the password input immediately upon sending and after
failure. A timeout, network loss or 5xx result is **uncertain**, with no optimistic balance change.
The customer can cancel the dialog, reload, reopen **Recover funding request**, and **Check outcome**.
A 404 only means no committed result is visible yet; an in-flight request can still finish. A mutation
resubmission requires a fresh password and retains the same key and intent. Never replace that key
to recover an uncertain result. Confirmed rejection preserves editable wallet/amount fields.
Session storage survives reloads in the same tab, but closing the tab or manually clearing storage
can remove recovery details. Durable funding records remain in Oracle for investigation by reference.

Dialog Cancel/Escape are available before submission and after a response. While a request is
processing they are disabled because closing a dialog cannot cancel an already submitted transaction.
Native modal dialog focus trapping, initial wallet focus and focus return support keyboard use.

## Build and development

The root npm launcher now builds and starts M1, M2, M3 and Vite using Node, Java and Maven directly.
It does not invoke PowerShell scripts or change execution policy. Eureka remains disabled.
The original PowerShell script was blocked and was not executed through a policy override.
The native Node build/copy implementation has now generated the assets and packaged them into M3.
Use an organization-approved terminal and installed executables.

Use installed packages and Java 25. No new npm dependencies are required. M3 isolated tests reuse the
H2 test engine already present for M2; all Maven commands below use offline mode and cannot download.

From the repository root in Command Prompt, build production assets and all three backend jars:

```bat
cd /d C:\fxlock-application\FXLock
npm run build
```

`scripts/build-dashboard.mjs` invokes the existing frontend build with Vite base `/dashboard/` and copies only
`index.html` and `assets/` to M3 `src/main/resources/static/dashboard/`. It leaves other static files
and APIs alone. `/dashboard/` forwards to that index; there is no global SPA fallback. Refresh and
direct navigation to `/dashboard/` work. Assets are built before M3 is packaged. Old hashed assets
are preserved; no directory deletion is needed. `npm run build -- --test` also runs the isolated
checks. Missing Maven dependencies stop the offline build and must be downloaded manually.

To start the entire application:

```bat
npm run dev
```

This prompts for missing Oracle connection settings, the existing Oracle password and the Twelve
Data API key. Secret input is hidden and not saved. Environment variables can provide the same
settings; the older PowerShell-encrypted settings file is not read. The existing local demo password
profile is selected explicitly, so use throwaway customer passwords. Keep Oracle running separately.
Ctrl+C stops only the three Java services and Vite started by this launcher. Logs are under
`target/dev-runs/`; known secret values are redacted. There is no root npm dependency installation.

In PowerShell use `npm.cmd run dev`, selecting the native executable instead of `npm.ps1`.
For faster frontend-only iterations with already built jars/assets, use
`npm run dev -- --no-build`. `npm run dev -- --check` checks tools, configuration, available ports,
jars and assets without starting services or contacting Oracle/provider endpoints.

1. Let the root command start M1 at 8081, M2 at 8082, M3 at 8083 and Vite at 5173,
   with the reviewed migration already applied manually. Eureka is not started.
2. Sign in at `http://localhost:8082/login` with a registered customer, following the redirect to
   the authenticated `http://localhost:8083/dashboard/`.
3. In the **same browser**, open `http://localhost:5173/dashboard/`. Use `localhost` consistently,
   not `127.0.0.1`: browser cookies are host-scoped and shared across these ports.
4. Vite proxies `/api/dashboard` to M3 8083. The M3 cookie accompanies same-origin requests to Vite;
   the proxy forwards it, and mutations send the M3 CSRF header. No browser CORS configuration is needed.
5. Login/handoff/logout remain on their backend URLs. After a frontend reload the session remains
   valid. After re-login, reopen the Vite URL manually. Get a quote always navigates to M2.

Checks:

```powershell
Push-Location .\frontend
npm.cmd test
npm.cmd run build
Pop-Location
Push-Location .\settlement-service
mvn -o '-Dtest=FundingTransactionTest,DashboardApiTest,DashboardBrowserFlowTest,SettlementApiTest,IntegrationSettlementApiTest,InternalCustomerResolverTest,LoginVerificationServiceTest,AccountPasswordProfileTest,PlaintextAccountServiceTest,PlaintextAccountApiTest' test
Pop-Location
Push-Location .\quote-service
mvn -o test
# Optional UI tests: supply an existing compatible driver. No driver download is permitted.
mvn -o '-Dtest=DashboardUiBrowserTest' '-DrunDashboardBrowser=true' '-Ddashboard.driver=C:/path/to/chromedriver.exe' test
Pop-Location
```

Do not run M3's unfiltered `mvn test`: the existing `SettlementRollbackTest` uses a real Oracle
datasource. New tests load isolated in-memory databases, mocks or HTTP fixtures only. The H2
transaction tests verify service locking/rollback behavior, but are not proof of Oracle-specific
locking or migration behavior; Oracle validation requires a separately provisioned disposable
schema approved by the operator. Existing optional browser and Oracle tests retain their opt-in gates.

## Manual browser acceptance

1. Open `http://localhost:8082/login`; register via the existing page if needed, then log in using
   the exact stored password. Expect `http://localhost:8083/dashboard/` with name, email, reference,
   account status, registration date and real balances. Verify an unauthenticated incognito API call
   is rejected and another customer's wallet ID is rejected on a password-confirmed funding request.
2. Select **Deposit money**. Check the simulation notice, active owned wallets and balance labels.
   Change wallet and verify its currency updates beside Amount. Test Cancel and Escape first.
3. Enter a positive two-place amount and an incorrect current password. Expect rejection, unchanged
   balances, preserved wallet/amount and an empty password field. Enter the exact correct password
   and Complete once. Expect the operation, amount, currency and transaction reference only after
   commit, followed by refreshed balances.
4. Select **Withdraw money**. Verify successful withdrawal, a full two-place balance withdrawal,
   insufficient funds, zero/negative values, three decimals and configured-limit rejection. Do not
   expect external money collection or payout.
5. For an uncertain response, retain the request key; reopen recovery and check the owned outcome.
   Resubmit only with a fresh password and the original key. Verify exactly one funding record.
6. Select **Get a quote**. Expect M2 `http://localhost:8082/` without another login. Follow existing
   quote creation → review → acceptance → settlement → settlement details. Pricing/expiry and
   settlement calculations follow existing code and require the configured rate service to be ready.
7. Return to the dashboard and refresh to see settled balances. Choose Logout, submit **Log out of
   FXLock** on M2, then **Finish logout** on M3. Verify dashboard APIs and M2 quote pages require
   authentication afterward. Stop before calling this complete if either service is unavailable.

## Read-only SQL verification

Use bind variables for the customer's actual reference and transaction reference. Never select
`LOGIN_PASSWORD` when checking dashboard or funding data.

```sql
SELECT c.customer_ref, w.wallet_id, w.currency_code, w.balance, w.status,
       w.version_no, w.updated_at
FROM customer c JOIN wallet w ON w.customer_id = c.customer_id
WHERE c.customer_ref = :customer_ref
ORDER BY w.currency_code;

SELECT f.transaction_ref, c.customer_ref, f.wallet_id, f.operation,
       f.currency_code, f.amount, f.balance_before, f.balance_after,
       f.created_at, f.idempotency_key, f.correlation_id
FROM wallet_funding f JOIN customer c ON c.customer_id = f.customer_id
WHERE c.customer_ref = :customer_ref
ORDER BY f.created_at DESC;

SELECT f.transaction_ref, i.operation_type, i.response_status, i.response_code
FROM wallet_funding f JOIN idempotency_record i ON i.idempotency_key = f.idempotency_key
WHERE f.transaction_ref = :transaction_ref;

SELECT event_type, entity_id, event_status, correlation_id, created_at
FROM audit_event WHERE entity_type = 'WALLET_FUNDING' AND entity_id = :transaction_ref;

SELECT event_id, event_type, status, correlation_id, created_at
FROM outbox_event WHERE aggregate_type = 'WALLET_FUNDING' AND aggregate_id = :transaction_ref;
```

The new outbox events use `SIMULATED_FUNDING_DEPOSIT_COMPLETED` or
`SIMULATED_FUNDING_WITHDRAWAL_COMPLETED` and topic `fx-simulated-funding-completed`. Configuring any
downstream publisher/consumer is outside this task; funding commits do not depend on Kafka availability.

## Files changed for this task

Existing unrelated local edits were preserved. Only the following additions/edits belong to this task:

| Files | Reason |
| --- | --- |
| M3 `account/DashboardAccounts.java` | Safe account reads, current-password confirmation and temporary per-customer throttling using the existing verifier |
| M3 `dashboard/DashboardSecurity.java`, `DashboardBrowserController.java`, `DashboardController.java` | Scoped session/CSRF protection, browser handoff, safe dashboard APIs, outcome lookup and logout |
| M3 `dashboard/FundingRequest.java`, `FundingResult.java`, `FundingService.java`, `FundingRepository.java` | Redacted DTOs, financial validation, shared wallet locks, transactional records, audit/outbox and idempotency |
| M3 `exception/GlobalExceptionHandler.java` | Safe funding error messages and redacted unexpected dashboard failures |
| M3 `application.properties` | Distinct cookie, dashboard URL, funding limit and throttle configuration |
| M3 `pom.xml` | Test-only H2 dependency, reusing M2's already cached version |
| `database/migrations/V002__wallet_funding.sql` | New financial record table, constraints and index; application-level append-only history without a trigger privilege requirement |
| M3 tests `account/FundingTransactionTest.java`, `dashboard/DashboardApiTest.java`, `DashboardBrowserFlowTest.java` | Isolated rollback, concurrency, password, authentication, CSRF, account/wallet isolation and recovery coverage |
| M2 `DashboardHandoffController.java`, `M3BrowserLogin.java`, `SecurityConfiguration.java`, `application.yml` | Necessary login navigation, protected one-time backend handoff, distinct cookie and coordinated logout |
| M2 tests `AccountPageTest.java`, `DashboardHandoffTest.java`, `DashboardUiBrowserTest.java`, `WalletBrowserTest.java` | Updated redirect expectations and existing isolated wallet browser fixture, secure handoff checks and production-bundle browser tests against HTTP fixtures |
| Frontend `src/App.jsx`, `FundingDialog.jsx`, `api.js`, `funding.js`, `index.css` | Customer dashboard, accessible dialogs, API calls, credential-free recovery and scoped existing green application styling |
| Frontend `src/funding.test.js`, `package.json` | Dependency-free Node tests and test script; package versions and lockfile are unchanged |
| Frontend `vite.config.js`, `build-dashboard.ps1`; root `build-fxlock.ps1` | Dashboard base path, development API proxy, dedicated asset-copy build step and existing build integration |
| Root `package.json`, `scripts/dev.mjs`, `scripts/dev-tools.mjs`, `scripts/build-dashboard.mjs`, `run-fxlock.cmd`, `build-fxlock.cmd` | Native npm startup/build, hidden credential prompts, offline packaging, readiness checks and owned-process cleanup without invoking PowerShell or Eureka |
| `scripts/tests/dev.test.mjs`, `scripts/tests/dev-fixture.mjs` | Isolated native launcher/process and asset-copy verification |
| This handover | Setup, migration review, configuration, commands, browser steps, SQL and limitations |

The React production bundle is generated in `frontend/dist`. The native build has copied it into
`settlement-service/src/main/resources/static/dashboard` and repackaged M3 successfully. No frontend
assets were copied into other static locations. A live browser check still requires the configured
services and Oracle database to be running.

## Verification actually executed (23 October 2026)

| Check | Actual result |
| --- | --- |
| `npm.cmd test` | 7 passed |
| `npm.cmd run build` | Passed with installed Vite 8.3.5; latest bundle generated in `frontend/dist` |
| M3 selected isolated suite and `mvn -o ... package` | 78 tests passed; executable backend jar built with Java 25 |
| M2 full offline suite with dashboard browser tests enabled | 121 reported, 106 passed and 15 existing opt-in tests skipped; no failures |
| M2 authentication follow-up and offline package | 13 passed; executable jar built |
| Final dashboard Chrome tests against rebuilt React bundle | 5 passed, none skipped; used existing cached driver and fixture HTTP server |
| Existing wallet Chrome regressions with updated registered-login fixture | 3 passed, none skipped; used H2, HTTP fixtures and cached driver |
| Production asset-copy script | Blocked by PowerShell execution policy; stopped without bypass |
| `git diff --check` | Reported pre-existing whitespace in the existing account-error cases of `GlobalExceptionHandler.java`; those unrelated lines were preserved |

The Chrome tests cover dialog focus, active-wallet selection, immediate currency labels, zero balance
display, Cancel/Escape, incorrect passwords and password clearing, successful deposit/full withdrawal,
completion messages, balance refresh, explicit refresh errors, repeated-click prevention, original-key
resubmission, reload/outcome recovery, quote navigation and logout navigation. Selenium emitted
non-fatal CDP-version warnings; these tests use standard WebDriver and passed without installing or
upgrading packages.

Not executed: Oracle migrations against a database, real-Oracle rollback/concurrency tests,
the existing opt-in `OracleSchemaTest` and `QuoteBrowserTest` suites, live development proxy login, or a real-customer browser
journey on 8082/8083. No real database was modified, no payment provider was contacted, no dependency
was downloaded/installed, and no commits, pushes, branch switches or policy changes were made.
The native-launcher follow-up on 24 October 2026 passed 11 isolated launcher tests and the root
`npm.cmd run build`: React built, the dedicated assets were copied, and all three backend jars
were packaged with offline Maven. M3's packaged `/dashboard/index.html` and referenced JS/CSS assets
were confirmed in the jar. No live Java service, database migration, provider call or policy change
was performed for this follow-up. Existing backend behavior tests were not repeated for these
launcher-only changes. Eureka was excluded as requested.
