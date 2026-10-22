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

The current M3 local controller hardcodes CUST001 and has no authenticated
multi-customer contract. M2 blocks other customers from this adapter. Do not
expose this development integration as a production payment service.

## Team profile settings

ORACLE_JDBC_URL, ORACLE_USERNAME, ORACLE_PASSWORD: same schema used by M1/M3.
RATE_BASE_URL=http://localhost:8081
SETTLEMENT_BASE_URL=http://localhost:8083
FXLOCK_SERVICE_USERNAME=fxlock-service
FXLOCK_SERVICE_PASSWORD=<local secret, never commit>

Launch with --spring.profiles.active=team, never together with demo/oracle-local.
Use direct service URLs until M4's Eureka setup and registration are available.
The M3 service must run its local profile. Live teammate processes are not part
of the isolated adapter tests and must be verified before claiming full integration.
