# Tests and handover

Run mvn test for fast business/API/adapter checks. Browser and Oracle suites are
opt-in; skipped optional tests do not mean those integrations were verified.
Run test-oracle.ps1 -Browser -Package for the full local suite, including the exact
Oracle schema, numeric foreign keys, config changes, confidence, cancellation,
unchanged timestamps and late-failure rollback of quote/confidence/audit/outbox.

## Insomnia against Oracle

1. Start run-oracle.ps1 (default port 8082; use -Port 8084 if occupied).
2. GET /actuator/health should return 200 and UP.
3. GET /login; copy hidden _csrf. POST /login as Form URL Encoded using
   username=customer-demo, password=learn-fxlock and _csrf. Keep cookies enabled.
4. GET /; copy csrf-token from the authenticated HTML. Use X-CSRF-TOKEN on writes.
5. GET /wallets/quote-options. Use the returned numeric EUR and USD wallet IDs.
6. POST /quotes as JSON with sourceCurrency EUR, targetCurrency USD,
   sourceAmount "1000.00" and those sourceWalletId/targetWalletId values.
7. Expect 201 only when a trusted provider observation is fresh and quoting is
   enabled. RATE_STALE/503 after the development seed expires is correct.
8. GET /quotes/{id}: stored values must match. After configured expiry it should
   report EXPIRED. Other customers must not read or accept it.
9. Create a fresh quote and POST /quotes/{id}/cancel before expiry. Verify
   FX_QUOTE.CANCELLED_AT, QUOTE_CONFIDENCE, AUDIT_EVENT and OUTBOX_EVENT in Oracle.
10. Restart the application and retrieve the same record again.

In oracle-local, accepting returns a clear unavailable error because M3 is not
connected. Team settlement needs live M1/M3, a fresh quote, funds and the same
shared schema. Verify retries/concurrency with M3's Oracle suite before handover.

Capture evidence without cookies, credentials or CSRF tokens. Commit source,
team SQL, documentation and test summaries; exclude target/, data/ and credentials.
Push only feature-quote-service. Do not merge or push to main without the user's
explicit instruction. See verification/ for the actual recorded validation.
