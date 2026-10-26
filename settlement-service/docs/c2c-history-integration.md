# Local bidirectional FX, customer transfers and readable history

Reference inspected: `YashRahate3748/FXLock`, branch `transaction-c2c-integration`,
commit `23589d1acbfec3067aa2cbd699be108778921ceb`. Its transfer commit is `1dbb60e`;
the history commit is `23589d1`. Only read-only Git inspection was used. No remote
repository change, commit, push, merge, branch switch or folder replacement was performed.

The local dashboard, registration, verified login, single-use M2→M3 handoff,
funding confirmation, CSRF, recovery/idempotency and coordinated logout remain.
The reference branch's demo sender mapping and Oracle setup script were not imported.

## Resulting behavior

The dashboard's existing **Get a quote** link still opens M2 `/`. That page now
supports EUR→USD and USD→EUR, self-conversion, and another eligible customer's
destination wallet. Source selection contains only the authenticated customer's
active wallets. Destination labels show name, customer reference and currency,
without email, password, token or beneficiary balance. Both participants must have
an active customer/account and the selected wallet must be active in the quoted currency.
The review identifies the wallets recorded in the stored quote. Changing the form's
destination does not silently relabel or redirect an already locked offer.

M2 first validates the original trusted EUR/USD observation and its age/quoting
settings. For USD→EUR it derives `1 / EURUSD` at scale 10 with HALF_EVEN, then uses
the existing spread, target rounding, configured amount limits, confidence and expiry.
Stored quote fields carry the selected direction. M3 settles those stored amounts;
it does not calculate another FX rate.

M3 locks the quote and wallets using the existing ascending wallet-ID convention.
Only source ownership must match the sender; the beneficiary may differ. Customer,
account, wallet state and currency are checked after wallet locks are acquired.
There are no remote requests while those locks are held. Balance/version/time,
settlement, ledger, audit, outbox and idempotency writes remain in the existing transaction.
Target overflow is rejected before mutation. Spring's typed duplicate-key exception
is recognized alongside the existing Oracle unique-violation detection so replay works
with both Oracle and isolated H2 tests. Request hashes and saved result formats are unchanged.

Sender and recipient can read the settlement summary and financial receipt. The
receipt contains **only ledger balances for the viewer's wallets**: sender debit,
recipient credit, or both for self-conversion. A counterparty balance is displayed
as not shared. An unrelated customer receives not found. Existing receipt routes
and response fields remain; quote lookup/acceptance remains sender-owned.

**Transaction history** on the dashboard opens the React ledger page. It reads the
already-installed `TRANSACTION_HISTORY` rows and independently reads `WALLET_FUNDING`
through a `UNION ALL` read model. Each row has its own currency, debit, credit,
recorded balance, time, type and reference. There is no combined currency balance,
fake settlement, ledger entry for funding, or join between unrelated transactions.

FX rows are scoped by `WALLET.CUSTOMER_ID` and matching history ownership, not by
`SETTLEMENT.CUSTOMER_ID`. Thus recipients see incoming credits. Funding rows also
verify wallet/customer ownership. Only completed FX settlements are listed.
History includes owned closed/blocked wallets for historical filtering, even though
those wallets cannot receive a new transfer. Ordering is timestamp descending then
a unique prefixed entry ID; pagination and counting operate over the combined rows.
Amounts are decimal strings and the React ledger formats them without floating-point
conversion. Errors are not replaced with zero or empty results.

## Identity and sessions

M2's registered login still asks M3 to verify the exact password and stores the
returned real customer reference in Spring Security. The integrated `team` and
`oracle-local` paths resolve that reference directly through active
`CUSTOMER`/`CUSTOMER_ACCOUNT` records. They do not translate training usernames.
The isolated H2 `demo` profile retains its separate fixtures; it cannot settle
through the real-account integrated path.

M2 forwards the authenticated principal through the existing backend
`X-Customer-Ref` plus `X-Internal-Token` contract. These values are constructed on
the backend; browser headers/body fields cannot choose the sender. M3 verifies the
service token before resolving the active customer/account, then checks quote/source
ownership during settlement. Internal tokens never enter links or browser storage.

M3 dashboard/history requests use the verified M3 server session created by the
existing state-bound, short-lived single-use code handoff. Direct unauthenticated
history-page navigation records only a whitelisted history return path in that
session. A valid callback returns to history; arbitrary return URLs are rejected.
M2/M3 keep their distinct cookie names. Quote and receipt navigation uses the still
authenticated M2 session. History never requires cross-origin credentialed API calls.

## Page and API routes

| Service | Route | Status |
| --- | --- | --- |
| M2 8082 | `/login`, `/register`, `/logout` | Preserved |
| M2 8082 | `/` | Preserved quote page, extended direction/beneficiary controls |
| M2 8082 | `/quotes`, `/quotes/{id}`, `/quotes/{id}/cancel`, `/quotes/{id}/accept` | Preserved JSON contracts |
| M2 8082 | `/wallets/quote-options` | Preserved wallet-option contract; real eligible C2C destinations added |
| M2 8082 | `/settlement.html?settlementId=...`, `/wallets.html`, their existing assets/APIs | Preserved |
| M3 8083 | `/dashboard/` | Preserved dashboard |
| M3 8083 | `/api/dashboard`, `/api/dashboard/funding`, `/api/dashboard/funding/{key}`, `/api/dashboard/logout` | Preserved |
| M3 8083 | `/dashboard/history/` (also `/dashboard/history`) | New readable React page, narrow index forward |
| M3 8083 | `/api/dashboard/history?walletId=...&page=0&size=20` | New session-authenticated combined read API |
| M2 8082 | `/api/transaction-history`, `/api/transaction-history/accounts` | Reference JSON paths added; not HTML page routes |
| M3 8083 | `/internal/transaction-history`, `/internal/transaction-history/accounts` | New protected backend adapters for those M2 JSON paths |
| M3 8083 | `/api/v1/settlements` and existing lookup/details routes | Preserved protected service contract |

No existing route was renamed or replaced with HTML. There is no global backend SPA
fallback. History and receipt have navigation back to the dashboard; FX history
references link to the existing M2 receipt route. Funding references remain independent.

The reference history response fields are retained: `entries`, `page`, `size`, `total`,
`hasNext`; entry fields include `ledgerId`, `settlementId`, `walletId`, `customerName`,
`currency`, `amountCredited`, `amountDebited`, `updatedBalance`, `transactionDate`.
Combined entries add `entryId`, `transactionType`, `transactionRef`. Funding entries
have null ledger/settlement IDs, and are explicitly typed DEPOSIT/WITHDRAWAL.
Page size is 1–100 and an unowned wallet filter returns not found.

## Existing database and configuration

No migration, trigger, table/column/constraint change, seed operation, balance reset
or synthetic rate insertion was performed against Oracle. Runtime mapping uses:

- `CUSTOMER_ACCOUNT.CUSTOMER_ID` → `CUSTOMER.CUSTOMER_ID`, with `ACCOUNT_STATUS`.
- `WALLET.CUSTOMER_ID`, numeric wallet IDs, `BALANCE NUMBER(19,4)`, status/version/time.
- The existing `TRANSACTION_HISTORY` columns from the reference setup:
  `LEDGER_ID`, `SETTLEMENT_ID`, `CUSTOMER_ID`, `CUSTOMER_NAME`, `WALLET_ID`,
  `CURRENCY_CODE`, `AMOUNT_CREDITED`, `AMOUNT_DEBITED`, `UPDATED_BALANCE`, `TRANSACTION_DATE`.
- Existing independent `WALLET_FUNDING` reference/operation/currency/amount/balances/time.

FX history capture remains the responsibility of the user's **already installed**
`TRG_LEDGER_TRANSACTION_HISTORY` trigger/backfill. The application does not recreate,
disable, repair or replace it. If it is missing or invalid, report the schema issue;
do not rerun the destructive common schema or substitute demonstration data.
The checked-in common/account/funding SQL and reference history definition were inspected.
Live Oracle metadata was not queried because these checks used isolated fixtures.

Use M2 `team` (preferred), M3 `integration,plaintext-account-demo`, and the existing
Oracle/provider settings. `oracle-local` M2 now also uses the protected M3 integration
contract, not M3's standalone synthetic local controller. Keep the same
`FX_INTERNAL_TOKEN` in M2/M3 and matching M1 service credentials in M1/M2. Existing
`FXLOCK_ACCOUNT_SERVICE_URL`, `SETTLEMENT_BASE_URL`, `RATE_BASE_URL`,
`FXLOCK_DASHBOARD_URL` and `FXLOCK_QUOTE_BROWSER_URL` settings are reused.
The existing plaintext password profile is still a disposable local demo restriction.
No password storage or registration/login/logout rule was changed.

## Build and start

Use the existing installed dependencies; no new package or version is required.
From Command Prompt at the repository root:

```bat
npm run build
npm run dev -- --no-build
```

Or run `npm run dev` to build and then start M1/M2/M3/Vite. Eureka stays disabled.
In a PowerShell terminal select `npm.cmd` instead of `npm.ps1`. No execution-policy
change or PowerShell script is required. The native build copies assets only into
M3 `static/dashboard` and packages the existing services with offline Maven.
For STS, run `node scripts/build-dashboard.mjs`, refresh M3's resources, then start
the services with the profiles/settings above. Do not also start duplicate npm services.

For hot reload, sign in on M2, then open `http://localhost:5173/dashboard/` or
`http://localhost:5173/dashboard/history/` in the same browser. Vite proxies
`/api/dashboard` and `/dashboard/login` to M3. Login callbacks land on canonical
M3 routes. Use `localhost` consistently across browser URLs so cookies remain available.

## Manual acceptance on the real configured application

1. Log in at `http://localhost:8082/login` with a registered customer's exact password.
   Confirm the dashboard at `http://localhost:8083/dashboard/`, real account data and wallets.
2. If needed, use the existing password-confirmed **simulated deposit** flow to fund a
   source wallet. Confirm its committed reference and refreshed balance.
3. Select **Get a quote**. On M2 `/`, select EUR→USD. Verify only your EUR wallet is
   offered as source, and choose your own USD wallet. Review the locked amounts.
4. Select USD→EUR and choose another eligible customer's EUR wallet. Verify its name,
   reference and currency, then create/review/accept the quote using the existing flow.
   Expired/untrusted-rate/insufficient-funds rejection must not show success.
5. Open **View settlement details** on M2. Verify the stored direction/amounts and
   your own wallet ledger balances. The other customer's current balance is not shared.
6. Use **Dashboard**, then **Transaction history**. Confirm sender FX debit and any
   simulated funding rows, with explicit currencies, original references and recorded balances.
   Test wallet filters, page sizes, Next/Previous, refresh and empty selections.
7. In a separate browser session, log in as the recipient. Verify their credit appears
   in history even though they did not initiate the settlement. Their receipt should show
   only their owned ledger balances. An unrelated customer cannot read that receipt.
8. Verify self-conversion shows both owned entries, and logout still clears both sessions
   through the existing coordinated forms. Direct history refresh must return to history
   after authentication when a handoff is required.

Read-only Oracle checks are in
`database/test-queries/customer_ledger_history_checks.sql`. Supply bind variables;
the script reads metadata, balances, owned history/funding, participant ledger rows,
and missing/mismatched history diagnostics. It never reads saved password values or repairs data.

## Changed files and verification

Local implementation changes:

- M2 `Models`, `QuoteService`, `QuoteRepository`, `UpstreamConfiguration`: two directions,
  real-account eligibility, owned debit sources, precise pricing and matching settlement responses.
- M2 `PageController`, `QuoteController`, `SettlementPageController`, quote template/header/JS:
  existing quote UI and route extension, dashboard/history navigation and receipt metadata.
- M2 `TransactionHistoryController`: protected-backend forwarding while retaining the reference JSON paths.
- M3 `InternalCustomerResolver`, `WalletRepository`, settlement service/repository/details:
  active real accounts, C2C eligibility, participant authorization and owned balance visibility.
- M3 `IdempotencyRepository`, `GlobalExceptionHandler`: typed unique-conflict recognition and safe history parameter errors.
- M3 `history/LedgerHistoryRepository`, `DashboardHistoryController`, `IntegrationHistoryController`:
  combined read model and authenticated/session or protected-backend adapters.
- M3 dashboard security/browser controller: narrow readable page route and whitelisted handoff return path.
- M3 dashboard/funding ID serialization: canonical decimal identifier strings, preserving API field types.
- Existing receipt HTML/JS: dashboard/history links and explicit private counterparty balances.
- React `HistoryPage`, history API/format/style modules, `App`, Vite config: readable scoped ledger UI
  and dashboard navigation, using existing dependencies and styling.
- Isolated backend/browser/frontend tests, native/legacy build test selectors, and this handover/read-only SQL.

Tests use isolated H2 databases, mock services, fixture HTTP servers and an explicitly supplied cached
Chrome driver. The H2-only trigger models history capture for transaction/rollback tests; it is not
proof of the installed Oracle trigger's behavior. Actual live Oracle schema/trigger compatibility,
live provider availability and the full multi-service browser journey require the manual steps above.
No end-to-end success claim follows solely from compilation or the production build.

### Actual results — 24 October 2026

| Check | Result |
| --- | --- |
| Frontend `npm.cmd test` | 11 passed, including existing funding/recovery checks and history formatting/API failures |
| Frontend production build | Passed with installed Vite 8.3.5; no dependencies installed or upgraded |
| Full M2 run with existing cached Chrome driver | 137 reported: 128 passed, 9 opt-in Oracle tests skipped, no failures |
| Final M2 real-account quote/history API follow-up | 12 passed after filter and reciprocal-limit hardening; backend package built |
| Final quote/wallet Chrome follow-up | 8 passed after adding stored-wallet review labels; backend package rebuilt |
| Final selected isolated M3 suite | 98 passed, none skipped; backend package built |
| Root native `npm.cmd run build` | Passed: React copy plus offline M1/M3/M2 packaging; no services started |
| Packaged M3 assets/classes | New history controllers/read model and referenced React index/JS/CSS present in the jar; copied index hash matches frontend dist |
| Working branch / GitHub | Local branch remains `m1-m2-m3_integration`; no remote writes, commits, merges, pushes or switches |

The repeated targeted runs overlap with the full suite and should not be summed as unique tests.
The 15 Chrome tests in the full M2 run cover the dashboard/funding/history UI (7), quote UI (4),
and registered-login/legacy wallet UI (4). The final eight quote/wallet checks also confirm that
changing a form destination does not change the recipient displayed for an existing stored quote.

The final M3 run includes 12 C2C/combined-history database-fixture tests and seven history API/route
tests, plus the existing funding, authentication, account-profile, CSRF and settlement regressions.
It verifies owned sender debits/recipient credits, participant receipt privacy, both directions,
full-balance/overflow rejection, active account/wallet eligibility, idempotent replay/conflict,
opposite concurrent transfers, rollback on fixture history-trigger failure, and funding/history
pagination/filtering. Existing funding tests also cover simultaneous FX/funding wallet use.

Initial test failures were corrected: non-canonical numeric ID strings in the H2 driver, missing
Spring duplicate-key recognition in the existing settlement idempotency repository, and the
catch-all handler masking an unknown resource's 404 as 500. No final check remains failed.
Selenium emitted non-fatal CDP-version warnings; standard WebDriver checks passed without upgrades.

Not executed: the nine M2 opt-in Oracle cases, M3's legacy real-Oracle `SettlementRollbackTest`,
live Oracle metadata/trigger/backfill verification, live provider calls, or the full real-account
multi-service browser journey. The H2 trigger is a test fixture only. Pagination uses current
committed rows with deterministic ordering; it is not a persistent snapshot across later inserts.
Use Refresh history to see newly committed movements.

Reproducible commands from the repository root (PowerShell uses `npm.cmd`):

```bat
npm.cmd --prefix frontend test
npm.cmd run build -- --test
```

The root `--test` selector explicitly excludes the unsafe real-Oracle M3 test. Optional Chrome
checks require an already-installed compatible driver; do not let Selenium download one:

```powershell
$env:BROWSER_DRIVER_PATH='C:/path/to/existing/chromedriver.exe'
$env:BROWSER='chrome'
Push-Location quote-service
mvn -o '-DrunBrowser=true' '-DrunDashboardBrowser=true' '-Ddashboard.driver=C:/path/to/existing/chromedriver.exe' test
Pop-Location
```
