# FXLock Settlement Service — Startup and Demo Runbook

## 1. Purpose

This runbook explains how to run and demonstrate M3's wallet and settlement features locally.

The service uses:
- Java 25
- Spring Boot 3.5.16
- Maven
- Oracle with the shared FXLock schema
- Port 8083
- The local Spring profile

Customer identity is currently simulated as CUST001. Quote generation uses SQL fixtures until M2 integration is complete.

## 2. Database prerequisites

Oracle must be running and reachable.

Current local connection:
- Host: localhost
- Port: 1521
- Service name: FREEPDB1
- Application user: fxlock

For the schema definition, refer to:

`database/migrations/01_fxlock_schema.sql`

**Do not rerun this file against an existing development database unless deliberately resetting it. It drops and recreates project tables.**

Fresh-schema installation verification is deferred.

## 3. Configure and build

Open Windows Command Prompt:

```cmd
cd /d C:\fxlock-application\FXLock\settlement-service

java -version
mvn -version
```

Confirm Maven uses Java 25.

Set the required environment variables in the same terminal:

```cmd
set "DB_PASSWORD=YOUR_LOCAL_PASSWORD"
set "SPRING_PROFILES_ACTIVE=local"
set "EUREKA_ENABLED=false"
```

If application.properties uses DB_USERNAME or DB_URL placeholders, also set:

```cmd
set "DB_USERNAME=fxlock"
set "DB_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1"
```

Replace the password locally. Do not commit credentials.

Stop any previously running packaged JAR before rebuilding.

```cmd
mvn clean verify
```

Expected result:

```text
Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The Oracle tests create and clean up isolated test fixtures. Run them against a development database.

Test reports are written under:

`target/surefire-reports/`

## 4. Start the packaged application

Stop any STS-launched Settlement Service so port 8083 is free.

In the same configured terminal:

```cmd
java -jar target\settlement-service-1.jar
```

Expected startup messages include:

```text
Tomcat started on port 8083
Oracle connection verified. Database user: FXLOCK
```

Leave the terminal running.

Open:

```text
http://localhost:8083/wallets.html
```

Click Refresh to confirm the browser can retrieve live wallet balances.

## 5. Available endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /api/v1/wallets | Retrieve the simulated customer's wallets |
| POST | /api/v1/settlements | Accept a quote |
| GET | /api/v1/settlements/{settlementId} | Retrieve an owned settlement |

Acceptance requires the Idempotency-Key header:

```json
{
  "quoteId": "generated-quote-id"
}
```

Unknown or other-customer settlement retrieval returns HTTP 404.

## 6. Successful conversion demo

1. Open the wallet page and note current EUR/USD balances.
2. In SQL Developer, connect through FXLock Local.
3. Execute the entire `database/seed/development_quote.sql` file using F5.
4. Copy the quote ID printed in Script Output.
5. Click Start another acceptance if the page shows a completed or rejected attempt.
6. Paste the quote ID and accept within 60 seconds.
7. Confirm:
   - Conversion completed.
   - A settlement ID is displayed.
   - EUR balance decreased by 100.
   - USD balance increased by 109.78.
8. Click View details to inspect the settlement.

The fixture uses a simulated market rate of 1.1000 and a spread of 20 basis points, producing a customer rate of 1.0978.

Each successful fixture conversion changes the balances cumulatively. Do not expect the original seed balances after repeated demos.

## 7. Idempotency replay demo

Using Git Bash, generate a fresh quote and set:

```bash
QUOTE_ID="PASTE-FRESH-QUOTE-ID"
IDEMPOTENCY_KEY="m3-demo-$(date +%s)-$RANDOM"

curl -i -X POST http://localhost:8083/api/v1/settlements \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d "{\"quoteId\":\"$QUOTE_ID\"}"
```

Repeat only the curl command, keeping both variables unchanged.

Expected:
- Both requests return HTTP 201.
- Both responses contain the same settlement ID.
- Only one conversion occurs.

Do not rerun the date-based key assignment when testing replay.

Reusing the successful key for another quote returns HTTP 409 with code IDEMPOTENCY_CONFLICT.

## 8. Expiry rejection demo

1. Generate a normal quote.
2. Wait more than 60 seconds without accepting it.
3. Submit through the frontend using a new acceptance.

Expected:
- HTTP 409.
- Message: “This quote has expired. Request a new quote.”
- Wallet balances unchanged.
- Rejection audit with reason QUOTE_EXPIRED.
- No completed settlement or completion event for that quote.

The quote may remain OPEN in Oracle; settlement checks EXPIRES_AT directly.

## 9. Insufficient-funds demo

1. Run `database/tests/create_insufficient_funds_quote.sql` using F5.
2. Copy its generated quote ID.
3. Start another acceptance and submit within 60 seconds.

Expected:
- HTTP 409.
- Message: “Your source wallet has insufficient funds.”
- Wallet balances unchanged.
- Rejection audit with reason INSUFFICIENT_FUNDS.

The fixture requests more EUR than the current source balance without modifying the balance itself.

## 10. Lost-response recovery demo

This browser-only simulation hides one successful response after the backend commits.

Start with no unresolved acceptance. In browser DevTools → Console, execute:

```javascript
(() => {
    if (window.__fxlockOriginalFetch) {
        console.log("Simulation already installed.");
        return;
    }

    const originalFetch = window.fetch.bind(window);
    window.__fxlockOriginalFetch = originalFetch;

    let dropNextSuccess = true;

    window.fetch = async function (input, options = {}) {
        const response = await originalFetch(input, options);

        const url = new URL(
            input instanceof Request ? input.url : input,
            window.location.href
        );

        const method = (
            options.method ||
            (input instanceof Request ? input.method : "GET")
        ).toUpperCase();

        if (
            dropNextSuccess &&
            method === "POST" &&
            url.pathname === "/api/v1/settlements" &&
            response.ok
        ) {
            dropNextSuccess = false;

            console.log(
                "Backend committed this settlement:",
                await response.clone().json()
            );

            throw new TypeError(
                "TEST: successful settlement response lost"
            );
        }

        return response;
    };

    console.log("Ready: the next successful response will be lost.");
})();
```

Then:

1. Generate and accept a fresh quote without refreshing first.
2. Confirm the page displays an unconfirmed outcome.
3. Copy the committed settlement ID from the Console.
4. Refresh the page.
5. Confirm the saved acceptance is restored.
6. Click Retry same acceptance.
7. Confirm the same settlement ID is returned.
8. Verify one settlement, two ledger entries and one completion event.

Refreshing removes the temporary interception.

If not refreshing, remove it manually afterward:

```javascript
if (window.__fxlockOriginalFetch) {
    window.fetch = window.__fxlockOriginalFetch;
    delete window.__fxlockOriginalFetch;
}
```

Keep unresolved acceptances in the same tab. Session storage survives refreshes but can be lost when the tab closes.

## 11. Database verification

Replace YOUR_QUOTE_ID and YOUR_SETTLEMENT_ID below.

```sql
SELECT quote_id, status, expires_at, settled_at
FROM fx_quote
WHERE quote_id = 'YOUR_QUOTE_ID';

SELECT settlement_id, status, source_amount, target_amount
FROM settlement
WHERE quote_id = 'YOUR_QUOTE_ID';

SELECT entry_type, currency_code, amount,
       balance_before, balance_after
FROM wallet_ledger
WHERE settlement_id = 'YOUR_SETTLEMENT_ID';

SELECT idempotency_key, response_status, settlement_id
FROM idempotency_record
WHERE quote_id = 'YOUR_QUOTE_ID';

SELECT event_type, event_status
FROM audit_event
WHERE entity_type = 'SETTLEMENT'
  AND entity_id = 'YOUR_SETTLEMENT_ID';

SELECT event_id, topic_name, status
FROM outbox_event
WHERE aggregate_type = 'SETTLEMENT'
  AND aggregate_id = 'YOUR_SETTLEMENT_ID';
```

For a successful acceptance and its replays, expect:

| Record | Count |
|---|---:|
| Completed settlement | 1 |
| Ledger entries | 2 |
| Completed idempotency result | 1 |
| Success audit | 1 |
| Completion outbox | 1 |

PENDING outbox status is expected until M4's publisher processes the event. It does not indicate settlement failure.

To inspect a rejected attempt:

```sql
SELECT event_type, event_status,
       DBMS_LOB.SUBSTR(details, 2000, 1) AS details_text,
       created_at
FROM audit_event
WHERE entity_type = 'QUOTE'
  AND entity_id = 'YOUR_QUOTE_ID'
ORDER BY created_at DESC;
```

## 12. Reconciliation

Execute:

`database/tests/reconciliation.sql`

The six read-only checks report:
- Incorrect ledger entry counts.
- Wallet/currency/amount mismatches.
- Incorrect ledger arithmetic.
- Settlement-to-quote mismatches.
- Settled quotes without exactly one completed settlement.
- Missing or duplicate success audit, outbox or idempotency records.

Zero rows means no discrepancy was found for that check. It can also occur when there are no applicable records.

Two historical development settlements were previously flagged because they predate supporting-record implementation. Investigate new discrepancies separately; do not fabricate events or delete records merely to clear the report.

Full reconciliation against opening wallet balances remains pending.

## 13. Automated test coverage

Run all tests:

```cmd
mvn clean verify
```

Current verified suite:
- 15 Oracle integration test cases.
- 11 HTTP-layer test cases.
- 26 total, with zero failures, errors or skipped tests.

Coverage includes:
- Early and late transaction rollback.
- Concurrent acceptance with different keys.
- Concurrent replay with the same key.
- Competing spends without overspending.
- Expiry during wallet lock waits.
- Settlement retrieval ownership.
- Quote and wallet ownership rejection.
- Blocked wallets, currency mismatch and insufficient funds.
- Conflicting idempotency-key reuse.
- HTTP validation, structured errors and sanitized internal errors.

## 14. Troubleshooting

| Symptom | Action |
|---|---|
| ORA-01017 | Check the current FXLOCK credentials in this terminal; STS launch variables are separate |
| Port 8083 already in use | Stop the existing Settlement Service before starting another |
| Maven cannot delete the JAR | Stop the terminal running that JAR using Ctrl+C, then rebuild |
| Wallet page returns 404 | Verify static/wallets.html is packaged and the correct application is running |
| APIs return 404 | Confirm the local profile is active |
| Quote rejected as expired | Generate a fresh quote and accept within 60 seconds |
| Retry returns idempotency conflict | Check that the original key belongs to the same quote |
| Outbox remains PENDING | M4's event publisher may not be running |
| Generic 500 or network timeout | Treat acceptance as unconfirmed and retry with its original key |

Do not recreate the schema to fix credentials, port conflicts or file locks.

## 15. Shutdown

Press Ctrl+C in the terminal running the JAR and wait for shutdown.

Do not close a browser tab containing an unresolved acceptance without first retaining the quote ID and retry key through an agreed recovery procedure.

## 16. Known limitations and integration work

- Controllers currently simulate customer CUST001.
- Real M2 quote generation is not integrated.
- Audit/event conventions need final confirmation with M4.
- Kafka publication and recovery are M4 integration responsibilities.
- Shared cross-laptop Eureka hosting is deferred; local registration was demonstrated.
- Fresh-schema installation verification is deferred.
- Final integrated acceptance and deployment rehearsal remain pending.