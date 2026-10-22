# Transaction history table

This feature adds a physical Oracle table, TRANSACTION_HISTORY, to the common
FXLOCK schema. It is an additive migration, not a replacement for WALLET_LEDGER.
The team's original sql/01_fxlock_schema.sql remains unchanged.

Requested columns:

| Column | Meaning |
| --- | --- |
| CUSTOMER_NAME | Wallet owner's full name captured when the entry is recorded |
| AMOUNT_CREDITED | Ledger amount for CREDIT entries; otherwise zero |
| AMOUNT_DEBITED | Ledger amount for DEBIT entries; otherwise zero |
| UPDATED_BALANCE | Ledger BALANCE_AFTER, not the current WALLET balance |

CURRENCY_CODE is essential: EUR and USD are separate balances. Supporting IDs
link each history row to its customer, wallet, settlement and unique ledger
entry. TRANSACTION_DATE copies the ledger timestamp. All amounts use NUMBER(19,4),
and timestamps use TIMESTAMP WITH TIME ZONE, matching the team rules.

## Installation

In a controlled local setup, connect as FXLOCK and execute
sql/02_transaction_history.sql using Run Script (F5). Run it once, after the
common schema exists. The schema owner requires CREATE TABLE, sufficient quota,
CREATE SEQUENCE for the identity generator, and CREATE TRIGGER; a DBA can apply it
for the owner instead. The script does not
drop tables or reset data. Coordinate this migration with M3 before using it in
a shared deployment: it adds an insert trigger to M3's ledger table.

Oracle DDL commits independently. If setup stops, inspect the created objects
and USER_ERRORS rather than blindly rerunning or dropping the common schema.
The supplied original full-reset script predates this extension; do not run it
over this installation as an upgrade or partial reset.

## How rows arrive

1. M3 inserts a WALLET_LEDGER entry during settlement.
2. TRG_LEDGER_TRANSACTION_HISTORY inserts one corresponding history row.
3. The trigger is part of that same transaction. A rollback removes both writes.
4. The migration backfills ledger entries that already exist without duplicates.

The ledger remains authoritative; M2 must not insert fabricated history records
or write the same history again in Java. A trigger error propagates to the ledger
insert, so a successful settlement cannot silently omit its history. Keep ledger
entries immutable. The history foreign key uses ON DELETE CASCADE only so an
explicit ledger cleanup cannot leave an orphaned copy; ordinary runtime must not
delete financial history. Deleting the table copy alone would lose that report
entry until a controlled backfill; do not manually edit these derived rows.

One conversion normally produces two rows: an EUR debit and a USD credit. For
example, a simulated EUR wallet going 5000 -> 4000 has AMOUNT_DEBITED=1000,
AMOUNT_CREDITED=0 and UPDATED_BALANCE=4000. Its USD wallet going 1000 -> 2167.66
has AMOUNT_CREDITED=1167.66, AMOUNT_DEBITED=0 and UPDATED_BALANCE=2167.66.
These are explanatory values, not inserted demonstration transactions.

## View in SQL Developer

Expand the FXLOCK connection, right-click Tables and Refresh, then open
TRANSACTION_HISTORY and select Data. Remove any table-name filter if necessary.
Run sql/03_read_transaction_history.sql for the customer-scoped report.

An empty WALLET_LEDGER means an empty TRANSACTION_HISTORY. Creating or cancelling
a quote does not move money and therefore creates no transaction-history row.
An existing seed balance is not a transaction and is not copied as one.

The customer webpage is described in docs/07-transaction-history-ui.md. The Oracle
tests exercise credit/debit mapping, historical balance, uniqueness and rollback
using disposable rows that roll back; no fake settlement is committed to the
user's database.
