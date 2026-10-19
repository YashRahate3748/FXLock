# Code map

SecurityConfiguration: training session login and CSRF.
QuoteController / PageController / WalletController: API and customer page.
DatabaseConfig: authoritative FX_CONFIG settings, read at quote creation.
QuoteService: wallet checks, fixed pricing, ownership, expiry and acceptance.
Pricing: 10-place rates, cent rounding, hard freshness checks, confidence weights.
QuoteRepository: exact common quote/confidence/audit/outbox columns and numeric
foreign keys. Cancellation locks the shared FX_QUOTE row before updating it.
SharedRateReader: reads M1-owned provider observations without changing time.
UpstreamConfiguration: explicit H2 fixtures, Oracle-local readers, and actual
M1/M3 HTTP contracts. Oracle local mode never writes rate observations.
ProfileGuard: rejects profile combinations that could put demo writers on Oracle.

sql/01_fxlock_schema.sql: exact supplied team Oracle SQL, run only in a fresh
local schema or under an expressly approved reset. Never automatic at startup.
src/main/resources/db/demo-schema.sql: H2 test dialect only; Oracle-specific
function-based index and JSON constraints are covered by direct Oracle tests.

OracleSchemaTest uses the real common Oracle schema and rolls back test writes.
QuoteServiceTest covers business/security ownership and transaction failure.
QuoteApiTest covers login/CSRF/request validation. UpstreamContractTest uses
loopback HTTP responses matching the actual teammate endpoints and payloads.
QuoteBrowserTest covers the rendered customer flow in a disposable H2 database.
