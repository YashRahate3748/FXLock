# Transaction History — Backend Integration

## Overview

Integrated the Transaction History feature into the `feature-customer-transfer-new` codebase.

The feature records customer wallet transactions in Oracle Database and provides REST APIs to retrieve transaction history.

## Files Added

```text
quote-service/
├── src/main/java/com/fxlock/quote/
│   ├── TransactionHistoryController.java
│   └── TransactionHistoryRepository.java
└── sql/
    ├── 02_transaction_history.sql
    └── 03_read_transaction_history.sql
```

## Database Integration

- `02_transaction_history.sql` creates the `TRANSACTION_HISTORY` table and database trigger.
- The trigger automatically records transaction history when entries are inserted into `WALLET_LEDGER`.
- `03_read_transaction_history.sql` provides a query to view customer transaction history.
- Transaction history includes credited amount, debited amount, currency, updated balance, and transaction date.

## REST APIs

**1. Get Transaction History**

```http
GET http://localhost:8082/api/transaction-history?page=0&size=20
```

Returns paginated transaction history for the authenticated customer.

**2. Get Customer Accounts**

```http
GET http://localhost:8082/api/transaction-history/accounts
```

Returns customer accounts available for transaction-history filtering.

## Verification

- Oracle transaction-history table created.
- Transaction History API tested successfully.
- API returned 7 transaction records with EUR and USD entries.
- Pagination response verified.

**Pending:** End-to-end trigger verification for new settlements and customer-isolation testing.

## Frontend

Frontend will be developed separately using React. The backend exposes REST APIs for integration.

## Branch

`integration-transaction-history`

**Base branch:** `feature-customer-transfer-new`