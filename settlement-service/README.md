# FXLock — Settlement Service (M3)

This service owns wallets and settlement for FXLock. The current milestone provides a working wallet balance API backed by local Oracle. Settlement execution is still to be implemented.

## Current progress

- Spring Boot application starts on port **8083**.
- JDBC connectivity to Oracle is verified using the dedicated `FXLOCK` account.
- Customer and wallet tables are created with development seed data.
- `GET /api/v1/wallets` returns the simulated customer's EUR/USD wallets.
- The wallet controller runs only with the **local** Spring profile.

Quote acceptance, atomic settlement, ledger, idempotency, audit/outbox writes, Eureka registration, and frontend screens are not implemented yet.

## Technology and project configuration

| Item | Current configuration |
| --- | --- |
| Java | JDK 25 |
| Build | Maven |
| Spring Boot | 3.5.16 (pending team alignment) |
| Project coordinates | `com.fxlock:settlement-service:1` |
| Base package | `com.fxlock` |
| Database access | Spring JDBC / JdbcTemplate |
| Driver | Oracle `ojdbc17`, managed by Spring Boot |
| Configuration file | `src/main/resources/application.properties` |
| Service port | 8083 |
| Database | Local Oracle; current service name `FREEPDB1` |

## Database schema and setup

**Teammates: refer to `database/migration/` for the current local schema.** The definitions are provisional and must be reconciled with the team contract before integration. The scripts are executed manually in SQL Developer; this folder is not automatically processed by Spring Boot.

Expected SQL files:

- `database/migration/V001__create_customers_wallets.sql`
- `database/seed/development_customers_wallets.sql`

If your local project currently uses `database/migrations/`, rename that folder to `database/migration/` before committing so these paths match the repository.

1. Connect to your local Oracle pluggable database using a dedicated application account with table-creation privileges and tablespace quota.
2. Run the migration script once in SQL Developer using **F5 — Run Script**.
3. Run the seed script once and commit the inserts.
4. Verify the rows with `SELECT * FROM wallet ORDER BY currency_code;`.

These initial scripts are not rerunnable without handling existing objects/data. Do not execute them against an existing shared schema without coordination.

### Current tables

| Table | Key fields and constraints |
| --- | --- |
| `fx_customer` | `customer_id VARCHAR2(36 CHAR)` primary key; required `display_name VARCHAR2(100 CHAR)`; `created_at TIMESTAMP WITH TIME ZONE` |
| `wallet` | `wallet_id VARCHAR2(36 CHAR)` primary key; required customer foreign key; `currency_code VARCHAR2(3 CHAR)`; `balance NUMBER(19,2)`; creation timestamp |

Wallet constraints enforce one wallet per customer/currency, currencies limited to EUR/USD, and nonnegative balances. Java uses `BigDecimal` for monetary values.

### Development fixtures

| Customer ID | Wallet ID | Currency | Initial balance |
| --- | --- | --- | ---: |
| `CUSTOMER_001` | `WALLET_EUR_001` | EUR | 1000.00 |
| `CUSTOMER_001` | `WALLET_USD_001` | USD | 200.00 |

## Run locally

Set these environment variables in your STS/Eclipse launch configuration or shell:

| Variable | Example / meaning |
| --- | --- |
| `DB_URL` | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` |
| `DB_USERNAME` | `fxlock` |
| `DB_PASSWORD` | Your local application-user password |
| `SPRING_PROFILES_ACTIVE` | `local` |

Use the following committed configuration, without real credentials:

```properties
spring.application.name=settlement-service
server.port=8083

spring.datasource.url=${DB_URL}
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
spring.datasource.driver-class-name=oracle.jdbc.OracleDriver

spring.sql.init.mode=never
```

Run `com.fxlock.SettlementServiceApplication` from STS/Eclipse with JDK 25 and those variables. Alternatively, with Maven installed:

```bash
mvn spring-boot:run
```

The current `DatabaseConnectionCheck` runner queries Oracle at startup. A failed database login will stop startup; a successful check logs the connected database user.

`localhost` refers to the machine running this service. For a shared database, change `DB_URL` to the shared host/service and ensure network access and permissions are configured.

## Wallet API contract

### GET `/api/v1/wallets`

Local example:

```bash
curl http://localhost:8083/api/v1/wallets
```

Expected successful response: HTTP 200 and a JSON array ordered by currency.

```json
[
  {
    "walletId": "WALLET_EUR_001",
    "customerId": "CUSTOMER_001",
    "currencyCode": "EUR",
    "balance": 1000
  },
  {
    "walletId": "WALLET_USD_001",
    "customerId": "CUSTOMER_001",
    "currencyCode": "USD",
    "balance": 200
  }
]
```

Trailing zeros may be absent from JSON numeric balances. An empty wallet query returns `[]`.

**Current identity limitation:** the local-profile controller hardcodes `CUSTOMER_001`. There is no implemented authentication/authorization flow. The endpoint does not accept an arbitrary customer ID. With the local profile disabled, this controller is not registered. Replace the simulated identity with the team-agreed mechanism before integrated use.

## Integration notes

### M2 — Quote Service and shared UI

- The shared UI can call the wallet endpoint to display current balances; no settlement POST endpoint exists yet.
- Agree quote table/column names, ID types, customer ownership, stored amounts, currencies, expiry timestamp, status transitions, and settlement API errors before finalizing settlement.
- Settlement will use the stored quote amounts without fetching a new rate or recalculating spread.
- The planned shared-Oracle design requires M3 to lock the quote and update its settlement status in the same transaction as wallet changes. Agree this table access explicitly.
- Prefer server-side calls from the shared MVC frontend initially; direct browser calls across service origins require an agreed CORS configuration.

### M1 — Rate Service

- Rate validation and freshness belong to quote issuance.
- Planned settlement uses an unexpired stored quote and does not depend on a live rate request.

### M4 — Event Service and infrastructure

- Agree audit/outbox tables and event-envelope fields before implementing event writes.
- Planned settlement writes success audit and `fx-settlement-completed` outbox data in its own Oracle transaction; Event Service publishes afterward.
- Eureka registration and Spring Cloud dependency versions still need team agreement. Service discovery is not active in this milestone.

### Database compatibility

Separate local Oracle databases do not synchronize data. Use the same versioned schema definitions for development. During integration, Quote and Settlement must access the same persisted quote and wallet records in the shared Oracle environment under the current design.

Coordinate schema changes through reviewed migration scripts. Do not independently recreate shared tables or reset another member's fixtures.

## Verification completed

Manual checks completed on the developer's machine:

- Spring Boot startup on port 8083.
- Oracle startup query reporting user `FXLOCK`.
- EUR/USD seed rows displayed in SQL Developer.
- Wallet endpoint returning the two expected JSON records.

Automated settlement, rollback, concurrency, and idempotency tests are pending. This milestone is a development wallet API, not completed settlement functionality.


### Frontend
- Added wallet balances, quote acceptance and settlement lookup using separate HTML, CSS and JavaScript files.
- Acceptance preserves the quote ID and idempotency key for retries and recovery after page refresh.
- Prevents repeated submissions while a request is in progress.
- Displays specific rejection messages, including expired quotes and insufficient funds.
- Refreshes wallet balances after acceptance attempts.

### API error handling
- Standardized error responses with `timestamp`, `status`, `code`, `message` and `path`.
- Added handling for invalid request fields, malformed JSON and missing headers.
- Keeps internal exception details out of client responses.
- Treats network/server failures as uncertain acceptance outcomes and retains the original retry key.

### Automated tests
- **22 tests passed, with zero failures, errors or skipped tests.**
- Includes 12 Oracle integration tests covering rollback, concurrency, idempotency, ownership and financial rejection scenarios.
- Includes 10 HTTP-layer tests covering controller responses, request validation and structured errors.

### Manual verification
- Simulated loss of a successful settlement response.
- Retried using the saved key and received the original settlement ID.
- Verified exactly one completed settlement, two ledger entries and one completion outbox event.
- Completed `mvn clean verify` and verified packaged application startup outside STS.

### Reconciliation
- Added read-only checks in `database/tests/reconciliation.sql`.
- Checks ledger counts/arithmetic, settlement-to-quote consistency and supporting audit/outbox/idempotency records.
- A recent settlement passed all six checks.
- Two historical development settlements remain flagged because they predate audit/outbox or idempotency implementation.
- Full opening-balance reconciliation remains pending.

