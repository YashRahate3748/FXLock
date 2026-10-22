# FXLock M2 quote service

M2 creates immutable EUR/USD quotes and forwards acceptance to M3. Persistence
uses the team's common `FX_QUOTE`, `QUOTE_CONFIDENCE`, `AUDIT_EVENT` and
`OUTBOX_EVENT` tables, with numeric foreign keys to `CUSTOMER`, `WALLET` and
`MARKET_RATE_SNAPSHOT`. No separate M2 database model is used.

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

Training login: `customer-demo` / `learn-fxlock`, resolved server-side to
`CUSTOMER_REF=CUST001`. `customer-other` maps to `CUST002`. Set `DEMO_PASSWORD`
to override the training password. These are training identities, not a production
identity system. Wallet IDs come from the database; never use `demo-eur` or
`demo-usd`. Discover them using `GET /wallets/quote-options` after login.

**Oracle local mode reads actual stored trusted observations.** The initial
`DEV_SEED` observation becomes stale after the configured age. It is not silently
refreshed. Run M1 for ongoing rates, or manually add a clearly labelled local
fixture using `sql/local-development-rate.sql` when testing. M3 acceptance is
unavailable in this mode; no false settlement success is returned.

For a disposable H2 demonstration: `.\run-demo.ps1`. It uses the common table
layout in a test dialect and explicit synthetic snapshot fixtures. Its in-memory
data resets on exit. Never combine `demo` with Oracle profiles.

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
The current M3 local controller only simulates CUST001; M2 refuses other customers
in that mode. Live M1/M3 integration remains a separate verification gate.
Kafka publication belongs to M4; M2 only writes transactional outbox records.
