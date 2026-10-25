# FXLock M2 quote service

M2 creates immutable EUR/USD quotes and forwards acceptance to M3. Persistence
uses the team's common `FX_QUOTE`, `QUOTE_CONFIDENCE`, `AUDIT_EVENT` and
`OUTBOX_EVENT` tables, with numeric foreign keys to `CUSTOMER`, `WALLET` and
`MARKET_RATE_SNAPSHOT`. No separate M2 database model is used.

To run the complete three-service application, use the repository-root
[`run-fxlock.cmd -Build`](../README.md). It selects M2 `team` and M3 `integration`,
shares datasource and service credentials, and supervises all three processes.

## Common database

`sql/01_fxlock_schema.sql` is an unchanged copy of the team SQL supplied on
19 October 2026. SHA-256:
`52d60c41e79d7e704f86c2f1e2f6be9322e695f82087201c672115e7d43a9619`.

It contains destructive cleanup blocks. Apply it only once to a new, empty
local schema, or as an explicitly approved full reset. Application startup
never executes this Oracle SQL. See [Oracle setup](docs/05-local-oracle.md).

## Run

Java 25 and Maven are required.

```powershell
mvn package
.\run-oracle.ps1
```

The launcher uses the encrypted credentials already stored on this laptop and
activates **only `oracle-local`**. Open http://localhost:8082. Stop another app
using that port first, or run `.\run-oracle.ps1 -Port 8084`.

In every profile, the restored original login is `customer-demo` / `learn-fxlock`, resolved server-side to
`CUSTOMER_REF=CUST001`. `customer-other` maps to `CUST002`. Set `DEMO_PASSWORD`
to override the training password. These are training identities, not a production
identity system. In `team` and `oracle-local`, the main login uses database email
credentials; **Use demo username** opens the separate training login. Standalone
`demo` keeps username login. Enable M3's explicit plaintext demo profile for accounts.
Wallet IDs come from the database; never use `demo-eur` or
`demo-usd`. Discover them using `GET /wallets/quote-options` after login.

**Oracle local mode reads actual stored trusted observations.** The initial
`DEV_SEED` observation becomes stale after the configured age. It is not silently
refreshed. Run M1 for ongoing rates, or manually add a clearly labelled local
fixture using `sql/local-development-rate.sql` when testing. M3 acceptance uses
the existing `SETTLEMENT_BASE_URL` and `CUST001` identity restriction described
below; no false settlement success is returned.

For a disposable H2 demonstration: `.\run-demo.ps1`. It uses the common table
layout in a test dialect and explicit synthetic snapshot fixtures. Its in-memory
data resets on exit. Never combine `demo` with Oracle profiles.

## Browser login and sign-up

For database accounts, start the combined app using
`run-fxlock.cmd -Build -PlaintextAccountDemo` and open http://localhost:8082/login.
The main form in `team`/`oracle-local` asks for Email and Password. **Sign up** asks
for full name, email, password and matching confirmation. Passwords must be nonblank and at most 72 UTF-8 bytes; they are never trimmed. M2 sends one
registration request to M3, which owns customer/account/wallet persistence and stores
the password unchanged in `LOGIN_PASSWORD` in its explicit demo profile. This is
unsafe plaintext storage for throwaway local accounts only; never deploy it.

Spring Security's authentication filter stores M3's verified customer reference as
the session principal, rotates the session ID/CSRF token and preserves normal logout.
Credentials are not retained in the session or repopulated after errors. Duplicate
registration, service unavailability and legacy-password reset requirements are
reported. A registration result is never automatically retried. Invalid email
credentials never fall back to a training user.

**Use demo username** opens a separate form for `customer-demo` / `learn-fxlock`
(`CUST001`) or `customer-other` (`CUST002`). `DEMO_PASSWORD` overrides both synthetic
passwords. Standalone `demo` still uses its original username/password flow.
No CAPTCHA is present. Successful account or demo login opens the existing quote
page at `/`; existing M3 receipt/wallet navigation remains unchanged.

Use `team` with M3 `integration,plaintext-account-demo` for the combined app. The
older `oracle-local` settlement adapter retains its original financial restrictions.
The previously policy-flagged AccountAuthenticationProvider file is not recreated;
the browser bridge uses the installed Spring Security authentication filter.

Tests use mocked/stubbed M3 account endpoints and isolated H2 fixtures. They cover
sign-up fields/validation, exact passwords, duplicate and uncertain outcomes, email
identity, session rotation, CSRF, logout, demo separation and wallet ownership.

## Rules and tests

Pricing limits, spread, expiry and quoting pause are read from `FX_CONFIG`.
`BUSINESS_SPREAD_BPS=20` means market rate multiplied by `(1 - 20/10000)`.
A market rate of 1.17 produces customer rate 1.1676600000 and USD 1167.66 for
EUR 1000.00. Rates fit `NUMBER(20,10)`; money fits `NUMBER(19,4)`. The customer
quote API accepts cents and uses HALF_EVEN rounding. Confidence components are
stored separately, weighted 40/20/20/20, with uppercase HIGH/MEDIUM/LOW labels.
Quote creation, confidence, audit and outbox writes share one transaction.
Cancellation locks the same `FX_QUOTE` row as M3 and sets `CANCELLED_AT`.

```powershell
mvn test
mvn test -DrunBrowser=true
.\test-oracle.ps1
```

Oracle tests require the exact common schema. Test fixtures roll back or are
removed; tests never recreate tables. Full validation:
`.\test-oracle.ps1 -Browser -Package`.

## Teammate integration

`team` uses Oracle and the actual M1 `/api/v1/rates/trusted` Basic-auth contract,
and M3 `POST /api/v1/settlements` with JSON `quoteId` and `Idempotency-Key`.
Connection settings are documented in `docs/02-team-integration-contract.md`.
M2 verifies the M1 snapshot and completed M3 settlement against the common DB.
The M3 local controller still simulates CUST001 for isolated oracle-local runs.
For `team`, run M3 `integration`, which resolves the customer from trusted M2
headers. Live M1/M3 integration remains a separate verification gate.
Kafka publication belongs to M4; M2 only writes transactional outbox records.
