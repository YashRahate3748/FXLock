# Disposable plaintext account demonstration

**UNSAFE: passwords are intentionally stored as plaintext. Use only throwaway
passwords and disposable demonstration accounts. This mode is unsuitable for real
users or deployment. Database readers and backups can see the saved passwords.**

## Enable explicitly

From the repository root, with your existing Oracle/M1 configuration:

```powershell
.\run-fxlock.cmd -Build -PlaintextAccountDemo
```

The switch activates M3 profiles `integration,plaintext-account-demo`. M2 stays on
`team` and M1 retains its normal profile. Subsequent runs still need
`-PlaintextAccountDemo`; it is not saved as a default. When launching M3 separately,
use `--spring.profiles.active=integration,plaintext-account-demo` with the existing
`DB_PASSWORD`/datasource settings and `FX_INTERNAL_TOKEN`. The demo profile binds M3
to loopback and suppresses JDBC parameter/web request debug logging. Do not enable
password/body/driver tracing when using this intentionally unsafe demonstration.

No BCrypt, hashing, encryption, decryption, trimming or Unicode normalization is
used by M3's account password storage or comparison. Registration stores the exact
submitted string in `CUSTOMER_ACCOUNT.LOGIN_PASSWORD`; verification compares the
exact string. Name/email normalization and all existing password length validation
reject blank passwords and values exceeding 72 UTF-8 bytes; there is no minimum character count.
The account column remains `VARCHAR2(255 CHAR) NOT NULL` in the creation script.

Without `plaintext-account-demo`, M3 account registration and verification reject
requests with `503 ACCOUNT_PASSWORD_MODE_DISABLED` before reading/writing credentials.
There is no BCrypt fallback. Combining the demo profile with `local`, `prod` or
`production`, or omitting `integration`, fails startup. This is an opt-in local
prototype, not a production account authentication implementation.

The existing `X-Internal-Token` checks remain mandatory for both endpoints. M2's
backend AccountClient still sends passwords unchanged, verifies response identity
fields and does not automatically retry registration. M3 returns only customer
reference/name/email, never the stored password. Request/credential string
representations are redacted. Account error logging omits exception text and causes
that could contain credentials. Existing customer/account ACTIVE checks remain.

## Existing database values and manual reset limitations

The database column is assumed to have already been renamed from `PASSWORD_HASH`
to `LOGIN_PASSWORD`, as supplied in the task. Runtime queries, mappings, health
column checks and the existing creation script now use `LOGIN_PASSWORD`. No SQL,
migration, account reset, account deletion or existing wallet update was executed.
Do not rerun the creation script against an existing table.

Existing BCrypt values cannot be reversed. Demo verification detects BCrypt-formatted
stored values and returns `409 ACCOUNT_PASSWORD_RESET_REQUIRED`, even if that entire
stored value is submitted as the password. Inactive accounts still return a generic
credential rejection. BCrypt-formatted strings are also rejected as new demonstration
passwords so a newly registered account is not mistaken for a legacy hashed account.

No password-reset endpoint or automatic conversion is included. The account owner
must explicitly choose a new throwaway password and authorize a separate reset of
that account's `LOGIN_PASSWORD`. Nothing assigns or guesses replacement passwords.
Other account columns, customer records and wallet balances must remain intact.

No real database was queried to enumerate affected accounts. If desired, run this
read-only query yourself in SQL Developer. It lists identifiers only, never passwords:

```sql
SELECT a.account_id, c.customer_ref, a.email
FROM customer_account a
JOIN customer c ON c.customer_id = a.customer_id
WHERE REGEXP_LIKE(a.login_password, '^\$2[abxy]?\$[0-9]{2}\$[./A-Za-z0-9]{53}$');
```

Every returned account needs an explicit reset before this plaintext mode can use
it. Plaintext rows from this demonstration are not suitable for any secure account
implementation without a separately planned credential migration/reset.

## Browser account testing

M2 `team` and `oracle-local` now offer database Email/Password login and Sign up.
The policy-flagged AccountAuthenticationProvider file remains removed; the browser
bridge uses Spring Security's authentication filter. M2 keeps passwords out of the
session and derives its principal only from M3's verified customer reference.
The existing layouts and financial services are preserved. No CAPTCHA is added.

1. Stop the existing launcher and run
   `.\run-fxlock.cmd -Build -PlaintextAccountDemo` from the repository root.
2. Open `http://localhost:8082/login`. Existing demonstration accounts with actual
   plaintext `LOGIN_PASSWORD` values can sign in using their email and exact password.
   Existing BCrypt-formatted rows require an explicit reset; never enter the hash.
3. Click **Sign up**. Enter your name, a unique throwaway email, a throwaway password
   that is nonblank (at most 72 UTF-8 bytes), and the same password as confirmation.
   Submit once. M3 creates the customer/account and zero-balance EUR/USD wallets in
   its existing transaction. Successful registration redirects to login.
4. Sign in with that email/password. Expect the existing quote page at `/`. Open
   `/wallets.html` on M2 to see the M3-owned wallet page for this customer. A new
   account starts at zero; sign-up does not fund it or change another user's balance.
5. Sign out using the quote page's Sign out button. Log in as another account and
   verify only that account's wallets appear. An incorrect password must show an
   error; it must not authenticate as a demo user. Password fields clear on errors.
6. Test unmatched confirmation, missing name and duplicate email: the form must
   display an error and never repopulate either password. If a registration result
   is uncertain, try signing in before submitting again; there is no automatic retry.
7. **Use demo username** remains a separate option for the original training users.
   Standalone M2 `demo` retains its old username login. CSRF, session ID rotation,
   logout, quote generation and existing settlement navigation are preserved.

Without the demo-profile flag, M3's account password endpoints remain disabled;
M2 reports account-service unavailability. No dashboard/deposit/withdrawal password
confirmation exists in this checkout. M1 still needs fresh provider observations
for quote creation; provider-network failures are independent of account login.

For actual plaintext registration/verification, use an API client against M3:

1. Supply your own known `FX_INTERNAL_TOKEN` before starting the launcher if you need
   to call its backend-only endpoints manually. Do not put that token in a URL or HTML.
2. POST `http://localhost:8083/internal/accounts/register` with header
   `X-Internal-Token` and JSON fields `fullName`, a unique throwaway `email`, and your
   chosen throwaway `password`. Expect 201. This deliberately creates a new demo
   account and its zero-balance EUR/USD wallets using the existing registration flow.
3. POST `/internal/accounts/verify` with the same header and JSON `email`/`password`.
   Expect 200 and customerRef/fullName/email only. Changing the password or removing
   significant spaces must return 401. A legacy BCrypt row returns reset-required.
4. Repeating an existing registration must still return REGISTRATION_CONFLICT.
   Do not automatically retry a registration if its result is uncertain.

Existing settlement, wallet ownership, session authentication, CSRF, ledger and
idempotency rules are not changed. Live M1 quote availability still depends on a
fresh provider rate and the existing network configuration.

## Automated checks

The configured build includes isolated profile, service, HTTP redaction and account
client tests; no real datasource or live provider is used:

```powershell
.\build-fxlock.cmd -Test
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\tests\test-launcher.ps1
```

The legacy M3 full test suite contains real Oracle tests; do not run it implicitly.
The root build selects only isolated M3 tests. Password fixtures are disposable.
Actual Oracle DDL compatibility and the real browser/API journey require separate
manual checking; no real accounts have been inspected or reset automatically.

## Recorded verification and changed files

`build-fxlock.cmd -Test` completed successfully using the configured Java 25/Maven
setup: M1 3 passed, M3 45 passed, M2 92 passed with 14 opt-in browser/Oracle tests
skipped. All three JARs were packaged. Launcher parsing/readiness/cleanup fixture
tests passed. Those initial checks were isolated; see the browser reconnection verification below.
Eighteen wallet/settlement/ledger/audit/outbox/idempotency source files matched the
earlier checkpoint byte-for-byte. No hashing API or PASSWORD_HASH query remains in
M3 production/test account code. No real SQL or migration was executed.

Changed files for this request:

- `account/AccountConfiguration.java`, new `AccountPasswords.java` and
  `PlaintextDemoAccountPasswords.java`: explicit plaintext opt-in and disabled default.
- `account/RegistrationService.java`, `LoginVerificationService.java`: renamed column,
  exact storage/comparison, active checks and redacted credential representation.
- `integration/IntegrationHealthController.java`: verifies the renamed column exists.
- `exception/GlobalExceptionHandler.java`: safe mode/reset errors and account failure
  logging that omits exception contents.
- `application-plaintext-account-demo.properties`: loopback binding and safe logging.
- `pom.xml`: removed M3's BCrypt/crypto dependency.
- `database/migrations/create_customer_account.sql`: only renamed the password column
  in the existing creation script; its size/constraints are unchanged. Never rerun
  it against the existing table.
- `account/LoginVerificationServiceTest.java`, new `AccountPasswordProfileTest.java`,
  `PlaintextAccountServiceTest.java`, `PlaintextAccountApiTest.java`, and
  `integration/IntegrationSettlementApiTest.java`: isolated fixtures/profile/redaction
  coverage and renamed health-query expectation.
- M2 `AccountClient.java` / `AccountClientTest.java`: preserve backend calls and
  distinguish reset-required responses without exposing saved values.
- Root `run-fxlock.ps1`, `build-fxlock.ps1`: opt-in launcher flag and isolated test list.
- Root/M3 READMEs and this guide: unsafe-demo limits, profile and manual test steps.

The initial password-storage task did not change page templates or browser security.
The subsequent browser reconnection updates the account templates and login filter
only; financial service implementations and real data remain untouched.

## Browser reconnection verification

The browser account reconnection was packaged successfully with
`build-fxlock.cmd -Test`: M1 3 passed, M3 45 passed, M2 96 passed with 15 opt-in
checks skipped. Separately, `mvn -DrunBrowser=true -Dtest=WalletBrowserTest,QuoteBrowserTest test`
passed all 6 headless Chrome tests using the cached local driver, H2 and a local
M3 account/financial fixture. No Oracle accounts, balances or live providers were
used. This verifies the browser bridge; real Oracle execution remains a manual step.

The added M2 `M3BrowserLogin.java` configures Spring Security's authentication filter;
it is not the removed AccountAuthenticationProvider implementation. The converter
accepts email/password, calls M3, then constructs the session identity only from
M3's verified response. The session contains no submitted password. Default email
login, explicit demo login, CSRF, session rotation, logout, validation, signup errors,
and existing quote/wallet navigation have regression coverage. Eighteen financial
source files still match the earlier checkpoint byte-for-byte.

Browser-specific files changed: M2 SecurityConfiguration, AccountPageController,
login.html and register.html; AccountPageTest, SettlementPageTest and WalletBrowserTest;
and the root/M2/M3 instructions. No schema, backend password-storage or financial
implementation changes were required for the browser reconnection.
