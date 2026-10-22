# Transaction history in the customer application

## Open the feature

1. Run the quote-service from this updated integration checkout (Java 25).
2. Build using `mvn package`. To include Oracle and browser checks, use
   `.\test-oracle.ps1 -Browser -Package` with the encrypted credential file.
3. Run `.\run-oracle.ps1` and open http://localhost:8082/transactions.
4. Sign in with the existing training identity `customer-demo` / `learn-fxlock`.
5. Click **Transaction history** in the top navigation. Select your EUR or USD
   wallet, or select all your accounts. Click **Refresh history** after settlement.

If using STS, import the `quote-service` folder from this updated checkout as an
Existing Maven Project. Stop the older QuoteApplication before starting this
version on the same port. Select profile `oracle-local` and supply the same
Oracle environment settings as before. The PowerShell launcher supplies those
settings from the existing encrypted credential file automatically.

The additional SQL migration is already installed on this laptop. For another
team database, have its owner apply `sql/02_transaction_history.sql` once after
the common schema, coordinating with M3. Do not reset or recreate the database.

## What the user sees

Customer name, account, currency, amount credited, amount debited, historical
balance after that transaction, date/time and settlement reference. Newest rows
appear first. The page shows 20 entries at a time and retains its account filter
in the URL across reloads. The table scrolls horizontally on small screens.

Each row is one wallet movement, not one quote. A completed EUR-to-USD conversion
normally creates an EUR debit row and a USD credit row. EUR and USD balances
are separate; there is no combined balance across currencies.

A quote creation, cancellation or expiry does not create history. An existing
opening balance is not a transaction. If there are no completed ledger entries,
the page says **No transactions yet**. No sample transactions are inserted into
the user's Oracle database to make the screen appear populated.

## Data flow

M3 settles the quote and writes WALLET_LEDGER in its transaction.
The existing TRG_LEDGER_TRANSACTION_HISTORY trigger copies each entry to
TRANSACTION_HISTORY. If settlement rolls back, the history row also rolls back.
The logged-in user opens /transactions; history.js requests owned accounts and
history. TransactionHistoryController passes the authenticated principal to
TransactionHistoryRepository, which filters the shared database by customer
and optionally wallet. The browser renders the returned rows as text.

The page reads completed SETTLEMENT records only. UPDATED_BALANCE is the balance
stored with each historical entry, not the wallet's balance today. Amounts are
serialized as exact decimal strings; formatting never converts money to a
JavaScript floating-point number. Customer names are rendered using textContent.

## Endpoints

| Method | Route | Purpose |
| --- | --- | --- |
| GET | /transactions | Authenticated customer page |
| GET | /api/transaction-history/accounts | Own accounts, including closed ones |
| GET | /api/transaction-history?walletId=1&page=0&size=20 | Own paginated history |

walletId is optional; page starts at zero; size must be 1 through 100.
The server obtains customer identity from the login, never a customerId query
parameter. Missing and foreign wallet IDs produce the same 404 response.
History API requests without a login return 401; browser navigation prompts login.
Session-expiry and unavailable-service messages are displayed on the page.
These are read-only GET requests; they do not require a CSRF header.

## Verification

Run `.\test-oracle.ps1 -Browser -Package` for the complete suite. Results are
recorded in verification/transaction-history-ui-tests.txt. Checks cover Oracle
trigger mapping and rollback, account ownership, historical balance, pagination,
empty history, exact decimal strings, browser navigation, saved filters and
mobile table layout. Oracle fixture transactions roll back. Browser sample rows
exist only in an isolated in-memory test database.

Screenshots in verification/transaction-history-test-data.png and
verification/transaction-history-mobile-test-data.png show synthetic test rows;
they are examples of the finished display, not evidence of a live M3 payment.

## Integration and Git state

Based on teammate branch m1-m2-m3_integration at
 a6eda0c784b4f36acba378c03665de015aeaabaa, in a separate local checkout on
feature-transaction-history. The earlier M2 checkout is preserved.
The existing settlement confirmation and link to the M3 screen are retained;
confirmation also offers View transaction history. The publication target is the existing M2 branch, feature-quote-service.
Do not merge or push these changes into main. Live M1/M3 runtime configuration and an actual
end-to-end settlement remain separate from this page's verification.

## STS project name

Import using Maven > Existing Maven Projects. If quote-service already exists,
expand Advanced and set Name template to quote-service-history before Finish.
This workspace display name is separate from the Maven artifact and Git branch.
