# M2: from setup to a saved quote

1. M1 saves a trusted rate observation in MARKET_RATE_SNAPSHOT.
2. The customer logs in. The server maps the training login to CUSTOMER_REF.
3. M2 lists that customer's active numeric WALLET IDs.
4. POST /quotes validates the amount, wallet ownership, currency and rate age.
5. M2 reads the shared FX_CONFIG, calculates a fixed customer rate and amount.
6. In one transaction it inserts FX_QUOTE, QUOTE_CONFIDENCE, AUDIT_EVENT and
   OUTBOX_EVENT. If any insert fails, all four roll back.
7. GET /quotes/{id} reads the stored amounts, with ownership enforcement. It does
   not reprice the quote. OPEN quotes past their deadline are reported EXPIRED.
8. POST /quotes/{id}/cancel locks the shared quote row, checks expiry and updates
   STATUS plus CANCELLED_AT, with transactional audit/outbox.
9. Acceptance forwards the quote ID and the same retry key to M3. Only M3 changes
   balances, ledger, settlement records and the SETTLED quote status atomically.

Read README.md first, then docs/05-local-oracle.md for laptop setup. Build with
mvn package. Start Oracle mode with run-oracle.ps1 and follow the Insomnia steps
in docs/03-testing-and-handover.md. Do not mix demo and Oracle profiles.

The team SQL file is the database contract. H2 tests are convenient, but direct
Oracle tests are necessary to verify Oracle constraints and rollback behavior.
