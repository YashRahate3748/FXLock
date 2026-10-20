# FXLock Rate Service (M1)

## Overview

`rate-service` is the M1 microservice for the FXLock training project.

Its responsibility is to retrieve EUR/USD market data from Twelve Data, validate the provider response, maintain the latest trusted FX rate, detect suspicious movements, enforce freshness rules, expose the trusted rate to other FXLock services, and record audit/outbox events.

The service does **not** create customer quotes or perform settlement.

---

## Technology Stack

- Java 25
- Spring Boot 3.5.16
- Spring Web
- Spring Data JPA
- Spring Validation
- Spring Security
- Spring Boot Actuator
- Oracle Database
- Twelve Data Forex API
- Maven
- Spring Tool Suite (STS)

---

## Service Details

**Application name**

```text
rate-service
```

**Default port**

```text
8081
```

**Base URL**

```text
http://localhost:8081
```

**Main Java package**

```text
com.ofss.fxlock.rate
```

---

# 1. M1 Responsibilities

The Rate Service handles:

- periodic EUR/USD retrieval from Twelve Data
- provider response validation
- trusted in-memory FX-rate cache
- Oracle rate snapshots
- 15-second provider polling (polling frequency is independent of provider update frequency)
- 90-second maximum age of the last accepted trusted snapshot
- provider-health tracking
- rate-movement calculation
- anomaly detection
- suspicious-rate quarantine
- quoting enable/disable control
- anomaly approval/rejection
- audit events
- transactional outbox event creation
- basic role-based security
- health endpoint
- restart-time cache restoration
- duplicate provider-observation detection
- out-of-order provider-observation rejection
- conflicting duplicate detection
- configurable Twelve Data connect/read timeouts
- API rate-limit backoff
- deterministic development-only anomaly testing

The M1 service does **not** own:

- customer quote creation
- quote pricing/locking
- wallet settlement
- ledger updates
- Kafka publication
- React UI

Those belong to other project roles/components.

---

# 2. High-Level Flow

```text
Twelve Data
     |
     v
Poll Twelve Data for EUR/USD every 15 sec
     |
     v
Validate response
     |
     v
Compare against previous trusted rate
     |
     +------ suspicious ------+
     |                        |
     |                        v
     |                  Save suspicious snapshot
     |                  Create anomaly record
     |                  Write audit event
     |                  Write outbox event
     |                  DO NOT replace trusted cache
     |
     v
Trusted rate
     |
     v
Store Oracle snapshot
     |
     v
Write audit event
     |
     v
Write outbox event
     |
     v
Oracle COMMIT
     |
     v
Update in-memory trusted cache
     |
     v
Expose to Quote Service
```

---

# 3. Important Timing Rules

Current training configuration:

```properties
fx.rate.fetch-interval=15s
fx.rate.max-age=90s
fx.quote.validity=60s
fx.spread=0.0020
fx.min-amount=10
fx.max-amount=100000
fx.anomaly-threshold-percent=1.00
```

These values have different meanings.

### 15 seconds — provider refresh interval

The Rate Service attempts to fetch a new EUR/USD rate from Twelve Data approximately every 15 seconds.

Example:

```text
10:00:00 -> fetch
10:00:15 -> fetch
10:00:30 -> fetch
10:00:45 -> fetch
```

If Twelve Data returns a genuinely newer provider observation and it passes validation/anomaly checks, it becomes the newest trusted rate. Repeated responses are not automatically treated as fresh data.

### 90 seconds — maximum trusted-rate age

A trusted rate can only be used for **new quote creation** while it is at most 90 seconds old.

This mainly protects the application during:

- provider outages
- timeouts
- HTTP 429 responses
- invalid provider responses
- suspicious provider rates

The 90-second value is a maximum fallback age. It is **not** the normal refresh interval.

### Polling frequency vs provider update frequency

FXLock polls Twelve Data approximately every 15 seconds, but Twelve Data may return the same latest market observation more than once. Therefore:

```text
poll frequency != provider update frequency
```

The service evaluates the provider timestamp before accepting a response as new market data.

- same provider timestamp + same rate -> `DUPLICATE`, ignored
- older provider timestamp -> `OUT_OF_ORDER`, rejected
- same provider timestamp + different rate -> `CONFLICTING_DUPLICATE`, rejected
- newer provider timestamp -> continue with validation/anomaly checks

Duplicate or old observations do **not** replace the trusted cache and do **not** reset the freshness clock.

### 60 seconds — quote validity

The 60-second timer starts when the Quote Service creates a quote.

Once a quote is created, it stores the exact rate promised to the customer.

The quote remains valid until its own expiry even if:

- the market changes
- the cached rate is later replaced
- the cached rate later becomes older than 90 seconds

Settlement uses the stored quote rate, not a newly fetched market rate.

---

# 4. Timestamp Semantics

The Rate Service stores two timestamps for market observations:

```text
PROVIDER_TIMESTAMP
RECEIVED_AT
```

`PROVIDER_TIMESTAMP` is the observation time reported by Twelve Data. It is used for duplicate detection, out-of-order detection, and observation ordering.

`RECEIVED_AT` is when FXLock accepted the observation. The 90-second freshness calculation uses the `RECEIVED_AT` value of the **last accepted trusted snapshot**. Because duplicate/out-of-order responses never replace that snapshot, repeated stale provider data cannot keep the trusted cache fresh indefinitely.

Backend timestamps are stored in UTC/GMT (`Z` / `+00:00`). This avoids timezone ambiguity between machines and services. UI clients can convert timestamps to local time for display.

---

# 5. Business Spread

Current training setting:

```properties
fx.spread=0.0020
```

For the current implementation, this is treated as an absolute spread example.

Example:

```text
Market rate   = 1.1700
Spread        = 0.0020
Customer rate = 1.1680
```

For EUR 1,000:

```text
1000 x 1.1680 = USD 1168
```

The spread provides a small pricing/risk buffer during the short quote-lock period.

The Rate Service stores market data and configuration, while the Quote Service applies the spread when creating customer quotes.

---

# 6. Transaction Limits

Current training values:

```properties
fx.min-amount=10
fx.max-amount=100000
```

These represent the allowed quote range for the training prototype.

Example:

```text
EUR 5       -> reject
EUR 10      -> allowed
EUR 100000  -> allowed
EUR 150000  -> reject
```

The Quote Service is responsible for enforcing transaction amount limits during quote creation.

---

# 7. Anomaly Detection

Current training threshold:

```properties
fx.anomaly-threshold-percent=1.00
```

Every new provider rate is compared with the previous trusted rate.

Formula:

```text
movementPercent =

ABS(newRate - previousTrustedRate)
----------------------------------
previousTrustedRate

x 100
```

Example:

```text
Previous trusted rate = 1.1700
New rate              = 1.1720
```

Movement:

```text
0.0020 / 1.1700 x 100 ~= 0.171%
```

Since:

```text
0.171% < 1.00%
```

the rate may remain trusted.

Example suspicious rate:

```text
Previous trusted rate = 1.1700
New rate              = 1.3000
```

The movement is far above 1%, so the new rate is marked:

```text
SUSPICIOUS
TRUSTED_FLAG = N
```

The previous trusted cache remains unchanged while it is still fresh.

---

# 8. First Trusted Baseline

If the application has no previous trusted rate, the first valid real provider rate becomes the initial trusted baseline.

Do **not** keep a hard-coded development seed market rate in the normal runtime database once live Twelve Data integration is being used, because a stale fake baseline can make real rates look suspicious.

---

# 9. Project Structure

```text
rate-service/
|
|-- pom.xml
|-- README.md
|
|-- src/
    |
    |-- main/
    |   |
    |   |-- java/
    |   |   `-- com/ofss/fxlock/rate/
    |   |       |
    |   |       |-- RateServiceApplication.java
    |   |       |
    |   |       |-- client/
    |   |       |   `-- TwelveDataClient.java
    |   |       |
    |   |       |-- config/
    |   |       |   |-- SecurityConfig.java
    |   |       |   `-- TwelveDataProperties.java
    |   |       |
    |   |       |-- controller/
    |   |       |   |-- RateController.java
    |   |       |   |-- RiskController.java
    |   |       |   `-- TestRateController.java
    |   |       |
    |   |       |-- dto/
    |   |       |   |-- ApiError.java
    |   |       |   |-- TrustedRateResponse.java
    |   |       |   |-- TwelveDataResponse.java
    |   |       |   |-- RiskReviewRequest.java
    |   |       |   |-- QuotingStatusResponse.java
    |   |       |   `-- TestRateRequest.java
    |   |       |
    |   |       |-- entity/
    |   |       |   |-- FxConfig.java
    |   |       |   |-- MarketRateSnapshot.java
    |   |       |   |-- RateAnomaly.java
    |   |       |   |-- AuditEvent.java
    |   |       |   `-- OutboxEvent.java
    |   |       |
    |   |       |-- enums/
    |   |       |   |-- ProviderHealth.java
    |   |       |   |-- RateValidationStatus.java
    |   |       |   `-- AnomalyStatus.java
    |   |       |
    |   |       |-- exception/
    |   |       |   |-- GlobalExceptionHandler.java
    |   |       |   |-- RateUnavailableException.java
    |   |       |   `-- ResourceNotFoundException.java
    |   |       |
    |   |       |-- repository/
    |   |       |   |-- FxConfigRepository.java
    |   |       |   |-- MarketRateRepository.java
    |   |       |   |-- RateAnomalyRepository.java
    |   |       |   |-- AuditEventRepository.java
    |   |       |   `-- OutboxEventRepository.java
    |   |       |
    |   |       |-- scheduler/
    |   |       |   `-- RateFetchScheduler.java
    |   |       |
    |   |       `-- service/
    |   |           |-- TrustedRateCache.java
    |   |           |-- RateValidationService.java
    |   |           |-- RateIngestionService.java
    |   |           |-- RatePersistenceService.java
    |   |           |-- RateQueryService.java
    |   |           |-- RiskControlService.java
    |   |           |-- ProviderStateService.java
    |   |           `-- CacheRestoreService.java
    |   |
    |   `-- resources/
    |       `-- application.yml
    |
    `-- test/
        `-- java/
            `-- com/ofss/fxlock/rate/
                |-- RateValidationServiceTest.java
                `-- TrustedRateCacheTest.java
```

---

# 10. Maven / Java Baseline

The project is standardized on:

```text
Java 25
Spring Boot 3.5.16
```

The service follows the same dependency baseline used by the team.

Important dependencies:

- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-validation`
- `spring-boot-starter-security`
- `spring-boot-starter-actuator`
- Oracle JDBC driver
- `spring-boot-starter-test`
- `spring-security-test`

---

# 11. Required Environment Variables

The following values must be configured locally.

```text
FXLOCK_DB_URL
FXLOCK_DB_USERNAME
FXLOCK_DB_PASSWORD

TWELVE_DATA_API_KEY

FXLOCK_SERVICE_USERNAME
FXLOCK_SERVICE_PASSWORD

FXLOCK_RISK_USERNAME
FXLOCK_RISK_PASSWORD
```

Example non-secret values:

```text
FXLOCK_DB_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1
FXLOCK_DB_USERNAME=FXLOCK
FXLOCK_SERVICE_USERNAME=fxlock-service
FXLOCK_RISK_USERNAME=risk-analyst
```

Do not commit:

- Oracle passwords
- Twelve Data API keys
- security passwords
- `.env` files containing secrets
- screenshots exposing secrets

---

# 12. STS Environment Setup

In Spring Tool Suite:

```text
Run
-> Run Configurations
-> Spring Boot App
-> rate-service
-> Environment
```

Add the environment variables there.

For normal runtime, do not enable the `dev` profile.

For development-only fake-rate testing, temporarily add:

```text
SPRING_PROFILES_ACTIVE=dev
```

Remove it after anomaly testing is complete.

---

# 13. Oracle Database

The Rate Service primarily uses these tables:

```text
FX_CONFIG
MARKET_RATE_SNAPSHOT
RATE_ANOMALY
AUDIT_EVENT
OUTBOX_EVENT
```

Oracle is the durable source of truth.

The in-memory trusted-rate cache is only an optimization for fast access.

---

# 14. `FX_CONFIG`

Contains configuration such as:

- currency pair
- fetch interval
- maximum rate age
- quote validity
- business spread
- transaction limits
- anomaly threshold
- quoting enabled/disabled

---

# 15. `MARKET_RATE_SNAPSHOT`

Stores every relevant provider observation.

Typical columns include:

```text
RATE_SNAPSHOT_ID
CURRENCY_PAIR
PROVIDER_NAME
MARKET_RATE
PROVIDER_TIMESTAMP
RECEIVED_AT
VALIDATION_STATUS
TRUSTED_FLAG
MOVEMENT_PERCENT
VALIDATION_REASON
```

Possible validation states:

```text
TRUSTED
SUSPICIOUS
INVALID
```

---

# 16. `RATE_ANOMALY`

Stores suspicious-rate review information.

Typical fields:

```text
ANOMALY_ID
RATE_SNAPSHOT_ID
PREVIOUS_TRUSTED_RATE_ID
MOVEMENT_PERCENT
THRESHOLD_PERCENT
STATUS
REASON
REVIEWED_BY
REVIEW_REASON
REVIEWED_AT
```

Possible states:

```text
OPEN
ACKNOWLEDGED
APPROVED
REJECTED
```

---

# 17. `AUDIT_EVENT`

Stores operational/audit events such as:

```text
RATE_UPDATED
RATE_ANOMALY_DETECTED
RATE_ANOMALY_APPROVED
RATE_ANOMALY_REJECTED
PROVIDER_UNAVAILABLE
QUOTING_ENABLED
QUOTING_DISABLED
RATE_OBSERVATION_DUPLICATE
RATE_OBSERVATION_REJECTED
```

---

# 18. `OUTBOX_EVENT`

Stores events that are intended for Kafka publication.

The Rate Service creates outbox records but does not itself require Kafka to be available.

Typical event types:

```text
RATE_UPDATED
RATE_ANOMALY_DETECTED
PROVIDER_UNAVAILABLE
```

Typical topics:

```text
fx-rate-events
fx-system-events
```

New records are normally:

```text
PENDING
```

M4 can later publish them to Kafka.

---

# 19. Trusted In-Memory Cache

The service keeps the latest trusted rate in memory.

Important behavior:

- valid trusted provider rate -> cache updates
- suspicious rate -> cache does not update
- invalid rate -> cache does not update
- provider failure -> cache remains unchanged
- stale cache -> no new quote rate is exposed
- application restart -> cache is restored from the latest trusted Oracle snapshot

The original `RECEIVED_AT` timestamp is preserved during restart restoration.

Restarting the application must not make an old market rate fresh again.

---

# 20. Provider Failure Behavior

If Twelve Data becomes unavailable:

```text
Last trusted rate exists
       |
       v
Is it <= 90 sec old?
       |
   +---+---+
   |       |
  YES      NO
   |       |
   v       v
Continue   RATE_UNAVAILABLE
```

The provider state may become:

```text
UP
DEGRADED
DOWN
```

A provider outage does not immediately invalidate an already fresh cached rate.

---

# 21. Security

The current training implementation uses HTTP Basic authentication.

Roles:

```text
SERVICE
RISK_ANALYST
```

## SERVICE role

Intended for backend service-to-service integration.

Can access:

```text
/api/v1/rates/**
```

Cannot access:

```text
/api/v1/risk/**
```

## RISK_ANALYST role

Can access:

```text
/api/v1/rates/**
/api/v1/risk/**
```

The test endpoint is restricted to development mode and should be protected with the Risk Analyst role.

---

# 22. API Endpoints

## 21.1 Health Check

```http
GET /actuator/health
```

Authentication:

```text
Not required
```

Example response:

```json
{
  "status": "UP"
}
```

---

## 21.2 Get Trusted EUR/USD Rate

```http
GET /api/v1/rates/trusted
```

Authentication:

```text
SERVICE or RISK_ANALYST
```

Example response:

```json
{
  "snapshotId": 520,
  "currencyPair": "EUR/USD",
  "marketRate": 1.12544,
  "observationTime": "2026-10-16T09:19:00Z",
  "receivedAt": "2026-10-16T09:19:52Z",
  "ageSeconds": 5,
  "fresh": true,
  "quotingEnabled": true,
  "providerHealth": "UP",
  "movementPercent": 0.0,
  "freshnessScore": 94.44,
  "anomalyScore": 100,
  "providerHealthScore": 100,
  "stabilityScore": 100
}
```

If no fresh trusted rate exists:

```http
503 Service Unavailable
```

Example:

```json
{
  "service": "rate-service",
  "errorCode": "RATE_UNAVAILABLE",
  "message": "Trusted FX rate is stale",
  "path": "/api/v1/rates/trusted"
}
```

Another possible response:

```json
{
  "service": "rate-service",
  "errorCode": "RATE_UNAVAILABLE",
  "message": "No trusted rate is available",
  "path": "/api/v1/rates/trusted"
}
```

---

## 21.3 Disable Quote Generation

```http
POST /api/v1/risk/quoting/disable
```

Authentication:

```text
RISK_ANALYST
```

Expected response:

```json
{
  "quotingEnabled": false
}
```

---

## 21.4 Enable Quote Generation

```http
POST /api/v1/risk/quoting/enable
```

Authentication:

```text
RISK_ANALYST
```

Expected response:

```json
{
  "quotingEnabled": true
}
```

---

## 21.5 Approve Suspicious Rate

```http
POST /api/v1/risk/anomalies/{anomalyId}/approve
```

Authentication:

```text
RISK_ANALYST
```

Body:

```json
{
  "reason": "Verified genuine market movement"
}
```

A suspicious candidate can only be promoted while it is still within the configured freshness window.

If the candidate is stale, the service returns a conflict response such as:

```json
{
  "errorCode": "INVALID_STATE",
  "message": "Cannot approve a stale rate"
}
```

This stale-approval rule is an implementation decision for the training prototype.

---

## 21.6 Reject Suspicious Rate

```http
POST /api/v1/risk/anomalies/{anomalyId}/reject
```

Authentication:

```text
RISK_ANALYST
```

Body:

```json
{
  "reason": "Provider value rejected during review"
}
```

---

# 23. Development-Only Fake Rate Injection

This endpoint exists only for deterministic anomaly testing.

Enable it by setting:

```text
SPRING_PROFILES_ACTIVE=dev
```

Endpoint:

```http
POST /api/v1/test/rates
```

Authentication:

```text
RISK_ANALYST
```

Body:

```json
{
  "rate": 1.3000
}
```

Expected behavior:

```text
Current trusted rate
       |
       v
Inject 1.3000
       |
       v
Movement exceeds threshold
       |
       v
Snapshot = SUSPICIOUS
TRUSTED_FLAG = N
       |
       v
RATE_ANOMALY row created
       |
       v
Audit event created
       |
       v
Outbox event created
       |
       v
Previous trusted cache remains unchanged
```

After testing, remove:

```text
SPRING_PROFILES_ACTIVE=dev
```

from the normal runtime configuration.

---

# 24. Safe Local Test Reset

For local development only, rate-test data can be cleaned with:

```sql
DELETE FROM RATE_ANOMALY;
DELETE FROM OUTBOX_EVENT;
DELETE FROM AUDIT_EVENT;
DELETE FROM MARKET_RATE_SNAPSHOT;
COMMIT;
```

After restart, the first valid live Twelve Data rate can establish a new trusted baseline.

Do not use this kind of cleanup in a shared integration/demo database if history must be preserved.

---

# 25. Normal Rate Test

Run:

```sql
SELECT
    RATE_SNAPSHOT_ID,
    CURRENCY_PAIR,
    PROVIDER_NAME,
    MARKET_RATE,
    RECEIVED_AT,
    VALIDATION_STATUS,
    TRUSTED_FLAG,
    MOVEMENT_PERCENT
FROM MARKET_RATE_SNAPSHOT
ORDER BY RATE_SNAPSHOT_ID DESC;
```

Expected live pattern:

```text
EUR/USD
TWELVE_DATA
TRUSTED
Y
```

Rows should generally appear approximately every 15 seconds while the provider is available.

---

# 26. Anomaly Test Procedure

1. Start application normally.
2. Confirm a real Twelve Data rate is `TRUSTED`.
3. Enable `SPRING_PROFILES_ACTIVE=dev`.
4. Restart application.
5. Use Risk Analyst Basic Auth.
6. POST a fake rate such as `1.3000`.
7. Verify the fake rate is `SUSPICIOUS`.
8. Verify `TRUSTED_FLAG = N`.
9. Verify `RATE_ANOMALY` contains an `OPEN` record.
10. Call `/api/v1/rates/trusted`.
11. Confirm it still returns the previous genuine trusted rate.
12. Verify audit event.
13. Verify pending outbox event.
14. Reject the fake anomaly.
15. Remove the `dev` profile from normal runtime.

---

# 27. Duplicate and Out-of-Order Observation Tests

The provider may return the same observation repeatedly even though FXLock polls every 15 seconds. This is expected.

### Duplicate observation

If the provider returns the same `PROVIDER_TIMESTAMP` and same rate as the current trusted observation:

```text
DUPLICATE -> IGNORED
```

Expected behavior:

- trusted cache is unchanged
- trusted snapshot `RECEIVED_AT` is unchanged
- freshness age continues increasing
- audit contains `RATE_OBSERVATION_DUPLICATE`

### Out-of-order observation

If the provider returns an older `PROVIDER_TIMESTAMP` than the current trusted observation:

```text
OUT_OF_ORDER -> INVALID / rejected
```

Expected behavior:

- trusted cache is unchanged
- snapshot is not trusted
- audit contains `RATE_OBSERVATION_REJECTED`

### Conflicting duplicate

If the provider returns the same provider timestamp but a different rate:

```text
CONFLICTING_DUPLICATE -> INVALID / rejected
```

This protects FXLock from inconsistent provider data.

---

# 28. Stale Rate Test

The normal scheduler can refresh the rate before it becomes stale, so for this test temporarily slow/disable provider fetching.

Example:

```properties
fxlock.rate.scheduler-delay-ms=9999999
```

Then:

1. Have a valid trusted rate stored.
2. Stop new provider refreshes.
3. Wait until the trusted rate becomes older than 90 seconds.
4. Call:

```http
GET /api/v1/rates/trusted
```

Expected:

```http
503 Service Unavailable
```

Expected error:

```text
Trusted FX rate is stale
```

After the test, restore:

```properties
fxlock.rate.scheduler-delay-ms=15000
```

---

# 29. Restart / Cache Restore Test

To clearly observe cache restoration:

1. Temporarily slow the scheduler.
2. Let a trusted rate exist in Oracle.
3. Note its `RATE_SNAPSHOT_ID` and `RECEIVED_AT`.
4. Stop the application.
5. Wait around 30 seconds.
6. Restart the application.
7. Immediately call `/api/v1/rates/trusted`.

Expected:

- same restored snapshot ID
- same original `RECEIVED_AT`
- age reflects the real elapsed time
- age does not reset to zero just because the app restarted

This confirms that `CacheRestoreService` restores the existing trusted rate rather than inventing a new timestamp.

---

# 30. Security Tests

## No authentication

```http
GET /api/v1/rates/trusted
```

Expected:

```http
401 Unauthorized
```

## SERVICE user accessing trusted rate

Expected:

```http
200 OK
```

if a fresh trusted rate exists.

## SERVICE user accessing Risk API

```http
POST /api/v1/risk/quoting/disable
```

Expected:

```http
403 Forbidden
```

## RISK_ANALYST accessing Risk API

Expected:

```http
200 OK
```

or:

```http
204 No Content
```

depending on the endpoint.

---

# 31. Verification SQL

## Latest trusted/suspicious rates

```sql
SELECT
    RATE_SNAPSHOT_ID,
    CURRENCY_PAIR,
    PROVIDER_NAME,
    MARKET_RATE,
    RECEIVED_AT,
    VALIDATION_STATUS,
    TRUSTED_FLAG,
    MOVEMENT_PERCENT
FROM MARKET_RATE_SNAPSHOT
ORDER BY RATE_SNAPSHOT_ID DESC;
```

## Latest anomalies

```sql
SELECT
    ANOMALY_ID,
    RATE_SNAPSHOT_ID,
    PREVIOUS_TRUSTED_RATE_ID,
    MOVEMENT_PERCENT,
    THRESHOLD_PERCENT,
    STATUS,
    REASON,
    REVIEWED_BY,
    REVIEW_REASON,
    REVIEWED_AT
FROM RATE_ANOMALY
ORDER BY ANOMALY_ID DESC;
```

## Latest audit events

```sql
SELECT
    AUDIT_ID,
    EVENT_TYPE,
    ENTITY_TYPE,
    ENTITY_ID,
    EVENT_STATUS,
    ACTOR_TYPE,
    ACTOR_ID,
    CREATED_AT
FROM AUDIT_EVENT
ORDER BY AUDIT_ID DESC;
```

## Latest outbox events

```sql
SELECT
    EVENT_ID,
    EVENT_TYPE,
    TOPIC_NAME,
    STATUS,
    RETRY_COUNT,
    CREATED_AT
FROM OUTBOX_EVENT
ORDER BY CREATED_AT DESC;
```

---

# 32. Integration Contract for Quote Service

The main M1 -> M2 integration endpoint is:

```http
GET /api/v1/rates/trusted
```

The Quote Service should consume this endpoint.

The Quote Service must **not** call Twelve Data directly.

The Rate Service decides whether a usable trusted market rate exists.

If the Rate Service returns:

```http
200 OK
```

M2 may proceed with quote logic.

If the Rate Service returns:

```http
503 Service Unavailable
```

M2 should not create a new quote.

---

# 33. Integration Notes for M2

M2 should use these response fields:

```text
snapshotId
currencyPair
marketRate
observationTime
receivedAt
ageSeconds
fresh
quotingEnabled
providerHealth
movementPercent
freshnessScore
anomalyScore
providerHealthScore
stabilityScore
```

The exact market rate used for quote creation should be persisted in the quote so that settlement does not need a fresh provider call later.

---

# 34. Integration Notes for M4

M1 creates outbox events such as:

```text
RATE_UPDATED
RATE_ANOMALY_DETECTED
PROVIDER_UNAVAILABLE
```

Typical topics:

```text
fx-rate-events
fx-system-events
```

M4 can consume/process `OUTBOX_EVENT` rows with:

```text
STATUS = PENDING
```

and publish them to Kafka.

The Rate Service should not depend on Kafka availability for storing valid market data.

---

# 35. Application Configuration

Typical `application.yml` structure:

```yaml
server:
  port: 8081

spring:
  application:
    name: rate-service

  datasource:
    url: ${FXLOCK_DB_URL:jdbc:oracle:thin:@//localhost:1521/FREEPDB1}
    username: ${FXLOCK_DB_USERNAME:FXLOCK}
    password: ${FXLOCK_DB_PASSWORD}
    driver-class-name: oracle.jdbc.OracleDriver

  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        format_sql: true
```

Secrets should remain environment variables.

---

# 36. Build and Run

From the service folder:

```bash
mvn clean test
mvn spring-boot:run
```

Or run `RateServiceApplication` directly from STS.

Expected startup result:

```text
Started RateServiceApplication
```

---

# 37. Build Verification

Before pushing changes:

```bash
mvn clean test
```

Expected:

```text
BUILD SUCCESS
```

Optionally:

```bash
mvn clean package
```

Expected:

```text
BUILD SUCCESS
```

---

# 38. Git Branch

M1 development branch:

```text
feature-rate-service
```

Recommended flow:

```text
feature-rate-service
        |
        v
Pull Request
        |
        v
develop / integration branch
        |
        v
final main branch
```

---

# 39. Git Safety Checklist

Before pushing:

```bash
git status
git diff
```

Check that no secrets are present.

Useful checks:

```bash
git grep "TWELVE_DATA_API_KEY"
git grep "FXLOCK_DB_PASSWORD"
git grep "FXLOCK_SERVICE_PASSWORD"
git grep "FXLOCK_RISK_PASSWORD"
```

Seeing placeholders such as:

```text
${TWELVE_DATA_API_KEY}
```

is fine.

Real secret values must not be committed.

---

# 40. Recommended `.gitignore`

```gitignore
target/

.classpath
.project
.settings/

.idea/

*.log

.env
application-local.properties
```

---

# 41. Suggested Commit

```bash
git add rate-service
git commit -m "feat(rate): harden market data validation and freshness handling"
git push -u origin feature-rate-service
```

---

# 42. Suggested Pull Request Title

```text
M1: Implement FXLock Rate and Risk Service
```

Suggested summary:

```text
Implements the M1 backend for FXLock:
- Twelve Data integration
- scheduled rate retrieval
- Oracle rate persistence
- trusted in-memory cache
- freshness enforcement
- provider health
- anomaly detection
- suspicious-rate quarantine
- anomaly review
- quote-generation controls
- audit/outbox events
- basic security
- health endpoint
- automated tests
```

---

# 43. Completed M1 Test Checklist

The following scenarios should be verified before integration:

```text
[ ] Application starts successfully

[ ] Oracle connection works

[ ] /actuator/health = UP

[ ] API key comes from environment variable

[ ] DB password comes from environment variable

[ ] no secrets committed to Git

[ ] unauthenticated /rates/trusted request = 401

[ ] SERVICE can access /rates/trusted

[ ] SERVICE cannot access /risk/**

[ ] RISK_ANALYST can access /risk/**

[ ] Twelve Data EUR/USD request works

[ ] scheduler polls approximately every 15 seconds

[ ] duplicate provider observations do not refresh trusted cache age

[ ] out-of-order provider observations are rejected

[ ] conflicting duplicate observations are rejected

[ ] configurable connect/read timeouts are active

[ ] HTTP 429 backoff behavior is configured

[ ] valid live rate becomes TRUSTED

[ ] trusted snapshot is saved in Oracle

[ ] trusted in-memory cache is updated

[ ] /api/v1/rates/trusted returns latest trusted rate

[ ] rate older than 90 seconds becomes unavailable

[ ] provider outage retains fresh previous trusted rate

[ ] provider failure is audited

[ ] suspicious rate is stored with TRUSTED_FLAG = N

[ ] suspicious rate does not replace trusted cache

[ ] RATE_ANOMALY row is created

[ ] anomaly audit event is created

[ ] anomaly outbox event is created

[ ] Risk Analyst can approve fresh anomaly

[ ] stale anomaly approval is rejected

[ ] Risk Analyst can reject anomaly

[ ] quoting can be disabled

[ ] quoting can be re-enabled

[ ] application restart restores trusted rate from Oracle

[ ] restart does not reset original RECEIVED_AT

[ ] JUnit tests pass

[ ] Maven build succeeds
```

---

# 44. Important Design Rules

1. Quote Service must not call Twelve Data directly.
2. Suspicious rates must not automatically replace the trusted rate.
3. A stale trusted rate must not be used for new quotes.
4. Restarting the service must not make an old rate fresh.
5. The API key must never be committed to Git.
6. Financial/rate arithmetic must use `BigDecimal`, not `float` or `double`.
7. Audit and outbox records should be persisted for important rate/risk actions.
8. Test-only endpoints must not be enabled accidentally in the normal integrated runtime.
9. Oracle remains the durable source of truth.
10. M1 creates outbox events; M4 owns Kafka publication.
11. Provider polling frequency must not be confused with provider update frequency.
12. Duplicate and out-of-order provider observations must never refresh the trusted cache age.
13. Backend timestamps remain UTC/GMT; UI may convert them for display.

---

# 45. Owner

```text
M1 - Yash
FXLock Rate Service
```

---

# 46. Current Integration Status

At the end of M1 implementation, the expected integration point is:

```text
Quote Service
     |
     | Basic Auth / service credentials
     |
     v
GET /api/v1/rates/trusted
     |
     v
Rate Service
     |
     +--> trusted in-memory cache
     |
     +--> Oracle
     |
     +--> Twelve Data scheduler
```

Once M2 can successfully consume the trusted-rate response and create a quote from it, the M1 -> M2 integration contract is considered validated.

Eureka/service-discovery integration is intentionally pending until the team member responsible for the shared discovery setup publishes the agreed configuration/dependency baseline.

---

# 47. Summary

The FXLock Rate Service is responsible for making sure the rest of the application never depends directly on an unsafe or stale external FX rate.

Its core responsibility can be summarized as:

```text
Fetch
-> Validate
-> Compare
-> Trust or Quarantine
-> Persist
-> Cache
-> Expose
-> Audit
-> Emit Outbox Event
```

This keeps market-data handling isolated, testable, observable, and safe for the rest of the FXLock quote-to-settlement workflow.
