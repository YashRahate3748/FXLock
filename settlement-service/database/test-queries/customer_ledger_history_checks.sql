-- Read-only operator checks. Run in the application's existing Oracle schema.
-- Supply :customer_ref and :settlement_id as bind variables. No setup/repair is performed.

SELECT USER AS connected_schema FROM dual;

SELECT table_name, column_name, data_type, data_precision, data_scale, nullable
FROM user_tab_columns
WHERE table_name IN ('CUSTOMER', 'CUSTOMER_ACCOUNT', 'WALLET', 'WALLET_LEDGER',
                     'SETTLEMENT', 'TRANSACTION_HISTORY', 'WALLET_FUNDING')
ORDER BY table_name, column_id;

SELECT t.trigger_name, t.status AS enabled_status, o.status AS compilation_status
FROM user_triggers t JOIN user_objects o
  ON o.object_name=t.trigger_name AND o.object_type='TRIGGER'
WHERE t.trigger_name='TRG_LEDGER_TRANSACTION_HISTORY';

SELECT line, position, text FROM user_errors
WHERE name='TRG_LEDGER_TRANSACTION_HISTORY' AND type='TRIGGER'
ORDER BY sequence;

-- A customer reference resolves through CUSTOMER_ACCOUNT; never select credential values.
SELECT c.customer_ref, c.full_name, c.status AS customer_status, a.account_status,
       w.wallet_id, w.currency_code, w.balance, w.status AS wallet_status, w.version_no
FROM customer c JOIN customer_account a ON a.customer_id=c.customer_id
JOIN wallet w ON w.customer_id=c.customer_id
WHERE c.customer_ref=:customer_ref
ORDER BY w.currency_code, w.wallet_id;

-- Wallet ownership, rather than the initiating settlement customer, scopes FX history.
SELECT h.transaction_date, h.settlement_id, h.ledger_id, h.wallet_id, w.currency_code,
       h.amount_debited, h.amount_credited, h.updated_balance
FROM transaction_history h JOIN wallet w ON w.wallet_id=h.wallet_id
JOIN customer c ON c.customer_id=w.customer_id
JOIN settlement s ON s.settlement_id=h.settlement_id
WHERE c.customer_ref=:customer_ref AND h.customer_id=w.customer_id
  AND h.currency_code=w.currency_code AND s.status='COMPLETED'
ORDER BY h.transaction_date DESC, h.ledger_id DESC;

-- Funding is independent of settlement; do not join it to a quote or ledger record.
SELECT f.created_at, f.transaction_ref, f.operation, f.wallet_id, w.currency_code,
       f.amount, f.balance_before, f.balance_after, f.idempotency_key
FROM wallet_funding f JOIN wallet w ON w.wallet_id=f.wallet_id
JOIN customer c ON c.customer_id=w.customer_id
WHERE c.customer_ref=:customer_ref AND f.customer_id=w.customer_id
ORDER BY f.created_at DESC, f.transaction_ref DESC;

-- Inspect both participants' durable ledger rows for one operator-selected settlement.
SELECT l.settlement_id, l.ledger_id, c.customer_ref, l.wallet_id, l.entry_type,
       l.currency_code, l.amount, l.balance_before, l.balance_after
FROM wallet_ledger l JOIN wallet w ON w.wallet_id=l.wallet_id
JOIN customer c ON c.customer_id=w.customer_id
WHERE l.settlement_id=:settlement_id
ORDER BY l.ledger_id;

-- Missing history rows can indicate incomplete backfill or an absent/invalid existing trigger.
-- Report these to the operator; the application never repairs the database automatically.
SELECT l.ledger_id, l.settlement_id, l.wallet_id
FROM wallet_ledger l JOIN settlement s ON s.settlement_id=l.settlement_id
LEFT JOIN transaction_history h ON h.ledger_id=l.ledger_id
WHERE s.status='COMPLETED' AND h.ledger_id IS NULL;

SELECT h.ledger_id, h.customer_id AS history_customer, w.customer_id AS wallet_owner
FROM transaction_history h JOIN wallet w ON w.wallet_id=h.wallet_id
WHERE h.customer_id<>w.customer_id;
