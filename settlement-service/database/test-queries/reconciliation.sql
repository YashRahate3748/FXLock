-- Read-only checks. Each query reports inconsistencies.

-- 1. Completed settlements need one debit and one credit.
SELECT
    s.settlement_id,
    s.quote_id,
    COUNT(l.ledger_id) AS ledger_count,
    SUM(CASE WHEN l.entry_type = 'DEBIT' THEN 1 ELSE 0 END)
        AS debit_count,
    SUM(CASE WHEN l.entry_type = 'CREDIT' THEN 1 ELSE 0 END)
        AS credit_count
FROM settlement s
LEFT JOIN wallet_ledger l
    ON l.settlement_id = s.settlement_id
WHERE s.status = 'COMPLETED'
GROUP BY s.settlement_id, s.quote_id
HAVING COUNT(l.ledger_id) <> 2
    OR SUM(CASE WHEN l.entry_type = 'DEBIT' THEN 1 ELSE 0 END) <> 1
    OR SUM(CASE WHEN l.entry_type = 'CREDIT' THEN 1 ELSE 0 END) <> 1;


-- 2. Ledger wallets, currencies and amounts must match the settlement.
SELECT
    l.ledger_id,
    l.settlement_id,
    l.entry_type,
    l.wallet_id,
    l.currency_code,
    l.amount
FROM wallet_ledger l
JOIN settlement s
    ON s.settlement_id = l.settlement_id
JOIN fx_quote q
    ON q.quote_id = s.quote_id
JOIN wallet w
    ON w.wallet_id = l.wallet_id
WHERE s.status = 'COMPLETED'
  AND (
      w.customer_id <> s.customer_id
      OR l.currency_code <> w.currency_code
      OR (
          l.entry_type = 'DEBIT'
          AND (
              l.wallet_id <> s.source_wallet_id
              OR l.currency_code <> q.source_currency
              OR l.amount <> s.source_amount
          )
      )
      OR (
          l.entry_type = 'CREDIT'
          AND (
              l.wallet_id <> s.target_wallet_id
              OR l.currency_code <> q.target_currency
              OR l.amount <> s.target_amount
          )
      )
  );


-- 3. Ledger balance changes must equal the debit or credit amount.
SELECT
    ledger_id,
    settlement_id,
    entry_type,
    amount,
    balance_before,
    balance_after
FROM wallet_ledger
WHERE (
    entry_type = 'DEBIT'
    AND balance_after <> balance_before - amount
)
OR (
    entry_type = 'CREDIT'
    AND balance_after <> balance_before + amount
);


-- 4. Completed settlements must match their immutable quote values.
SELECT
    s.settlement_id,
    s.quote_id,
    q.status AS quote_status
FROM settlement s
JOIN fx_quote q
    ON q.quote_id = s.quote_id
WHERE s.status = 'COMPLETED'
  AND (
      q.status <> 'SETTLED'
      OR q.settled_at IS NULL
      OR s.completed_at IS NULL
      OR s.customer_id <> q.customer_id
      OR s.source_wallet_id <> q.source_wallet_id
      OR s.target_wallet_id <> q.target_wallet_id
      OR s.source_amount <> q.source_amount
      OR s.target_amount <> q.target_amount
      OR s.customer_rate <> q.customer_rate
  );


-- 5. Each settled quote must have exactly one completed settlement.
SELECT
    q.quote_id,
    COUNT(s.settlement_id) AS completed_settlements
FROM fx_quote q
LEFT JOIN settlement s
    ON s.quote_id = q.quote_id
   AND s.status = 'COMPLETED'
WHERE q.status = 'SETTLED'
GROUP BY q.quote_id
HAVING COUNT(s.settlement_id) <> 1;


-- 6. Completed settlements need success audit, outbox and idempotency records.
-- Early test settlements created before these features may appear here.
WITH completion_records AS (
    SELECT
        s.settlement_id,
        s.quote_id,
        (
            SELECT COUNT(*)
            FROM audit_event a
            WHERE a.entity_type = 'SETTLEMENT'
              AND a.entity_id = s.settlement_id
              AND a.event_type = 'SETTLEMENT_COMPLETED'
              AND a.event_status = 'SUCCESS'
        ) AS success_audits,
        (
            SELECT COUNT(*)
            FROM outbox_event o
            WHERE o.aggregate_type = 'SETTLEMENT'
              AND o.aggregate_id = s.settlement_id
              AND o.event_type = 'SETTLEMENT_COMPLETED'
              AND o.topic_name = 'fx-settlement-completed'
        ) AS outbox_events,
        (
            SELECT COUNT(*)
            FROM idempotency_record i
            WHERE i.settlement_id = s.settlement_id
              AND i.quote_id = s.quote_id
              AND i.operation_type = 'SETTLE_QUOTE'
              AND i.response_status = 'COMPLETED'
        ) AS idempotency_results
    FROM settlement s
    WHERE s.status = 'COMPLETED'
)
SELECT *
FROM completion_records
WHERE success_audits <> 1
   OR outbox_events <> 1
   OR idempotency_results <> 1;