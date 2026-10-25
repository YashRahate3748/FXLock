# Current shared database and API contracts

## Shared database

Canonical source: sql/01_fxlock_schema.sql, unchanged from the supplied team SQL.
CUSTOMER_ID, WALLET_ID and RATE_SNAPSHOT_ID are numeric. Resolve customer identity
on the server, never from request JSON. Training aliases customer-demo and
customer-other resolve to CUSTOMER_REF CUST001 and CUST002 respectively.

M2 writes FX_QUOTE and QUOTE_CONFIDENCE, plus transactional AUDIT_EVENT and
OUTBOX_EVENT records. It reads CUSTOMER, WALLET, FX_CONFIG, MARKET_RATE_SNAPSHOT,
RATE_ANOMALY and committed SETTLEMENT results. It never changes wallet balances.
Both wallets currently belong to the authenticated customer, matching M3's local
service checks. Cross-customer beneficiaries require an agreed M3 policy change.

Rate precision is 10 fractional digits, amount storage has 4 fractional digits,
and the customer API currently accepts 2. Pricing: market*(1-spreadBps/10000),
HALF_EVEN. Limits, quote duration, max rate age and anomaly threshold come from
FX_CONFIG. Rate age must be non-negative and strictly less than the maximum.
The provider observation timestamp is checked; a new HTTP fetch cannot make an
old observation fresh. Missing provider time fails closed.

Confidence has separate numeric component columns, a weighted total and uppercase
HIGH/MEDIUM/LOW. Local mode derives inputs from stored rate metadata; team mode
uses M1 components, capped by current locally verified freshness/anomaly status.
Weights: freshness 40%, anomaly/provider/stability 20% each.

Outbox topic default is fx-quote-events. M4 must confirm routing and deploy the
publisher; an inserted PENDING row is not evidence of Kafka delivery.

## M1 adapter

GET /api/v1/rates/trusted with HTTP Basic service credentials. Response fields
include numeric snapshotId, currencyPair, marketRate, receivedAt, fresh,
quotingEnabled, providerHealth, movementPercent and four confidence scores.
M2 reads the referenced Oracle snapshot and checks its provider observation,
trusted flag and rate against the response. It never treats receivedAt as a
replacement for a missing/old provider observation.

## M3 adapter

POST /api/v1/settlements, application/json {"quoteId":"UUID"}, Idempotency-Key.
Response has settlementId, quoteId, status, sourceCurrency/sourceAmount and
targetCurrency/targetAmount. completedAt is read from the matching committed
SETTLEMENT row. Errors preserve M3's code and HTTP status. Uncertain retries
keep the same key.

For M2 `team`, M3 runs `integration`. M2 supplies `X-Internal-Token` and
`X-Customer-Ref` derived from the authenticated username (`customer-demo` maps to
`CUST001`, `customer-other` to `CUST002`), never browser-supplied
identity headers. M3 authenticates the service token and resolves an active
customer before delegating to its existing settlement coordinator. The receipt
is served through M2, including its authenticated GET details API. No token is
returned in browser content. M2's main integrated login sends the email and exact
password to `/internal/accounts/verify` on M3. Only the returned verified customer
reference becomes the session identity. Sign-up sends full name, email and password
to `/internal/accounts/register` after validating password confirmation. M3 alone
persists accounts and zero-balance wallets. It stores the exact demonstration
password in `LOGIN_PASSWORD` only under `integration,plaintext-account-demo`.
This plaintext mode is unsafe for real users or deployment. CSRF remains enabled.
Demo username login is explicitly separate and is never a fallback after M3 failure.

The M3 receipt's `/wallets.html` link is preserved. M2 routes the existing M3
wallet HTML/CSS/JavaScript and forwards its wallet balance, settlement lookup and
acceptance API requests using the session's customer identity. M3's integration
wallet endpoint resolves that identity before calling its existing read-only
wallet service. Browser acceptance posts still require M2's CSRF token; the
internal service token stays on the backend.

The standalone M3 `local` controller still hardcodes CUST001, and M2's isolated
`oracle-local` adapter retains its matching restriction. Do not combine M3's
`local` and `integration` profiles. The root launcher uses loopback bindings and
is intended for local development.

## Team profile settings

ORACLE_JDBC_URL, ORACLE_USERNAME, ORACLE_PASSWORD: same schema used by M1/M3.
RATE_BASE_URL=http://localhost:8081
SETTLEMENT_BASE_URL=http://localhost:8083
FXLOCK_SERVICE_USERNAME=fxlock-service
FXLOCK_SERVICE_PASSWORD=<local secret, never commit>
FX_INTERNAL_TOKEN=<same shared secret as M3>
FXLOCK_ACCOUNT_SERVICE_URL=http://localhost:8083

Launch with --spring.profiles.active=team, never together with demo/oracle-local.
Use direct service URLs until M4's Eureka setup and registration are available.
The M3 service must run its integration profile. Live teammate processes are not part
of the isolated adapter tests and must be verified before claiming full integration.
