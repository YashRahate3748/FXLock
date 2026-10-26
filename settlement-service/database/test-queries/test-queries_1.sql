SELECT
    c.customer_id,
    c.customer_ref,
    c.full_name,
    w.wallet_id,
    w.currency_code,
    w.balance
FROM customer c
JOIN wallet w
    ON w.customer_id = c.customer_id
ORDER BY c.customer_ref, w.currency_code;

-- Verify current balances
SELECT w.currency_code, w.balance
FROM wallet w
JOIN customer c ON c.customer_id = w.customer_id
WHERE c.customer_ref = 'CUST001'
ORDER BY w.currency_code;

-- Verify quote status
SELECT quote_id, status, settled_at
FROM fx_quote
WHERE quote_id = '465a8ab9-4ed4-4654-ac85-ea39ca5d33e5';

-- Verify the two ledger entries
SELECT entry_type, currency_code, amount,
       balance_before, balance_after
FROM wallet_ledger
WHERE settlement_id = 'ef721d96-7366-4d02-963d-7854cd570f58';

-- Check the saved idempotency result.
SELECT idempotency_key,
       quote_id,
       settlement_id,
       response_status,
       response_code
FROM idempotency_record
WHERE quote_id = '8047730d-5321-4cd5-8379-48c5b5d794e7';

-- Check that exactly one conversion completed.
SELECT COUNT(*) AS completed_settlements
FROM settlement
WHERE quote_id = '8047730d-5321-4cd5-8379-48c5b5d794e7'
  AND status = 'COMPLETED';

-- Check that the conversion has exactly two ledger entries.
SELECT COUNT(*) AS ledger_entries
FROM wallet_ledger
WHERE settlement_id IN (
    SELECT settlement_id
    FROM settlement
    WHERE quote_id = '8047730d-5321-4cd5-8379-48c5b5d794e7'
      AND status = 'COMPLETED'
);

SELECT
    event_type,
    entity_id,
    actor_id,
    event_status,
    correlation_id
FROM audit_event
WHERE entity_type = 'SETTLEMENT'
  AND entity_id = '1796d145-c449-448e-bc4d-d8ff0d60e601'
  AND event_type = 'SETTLEMENT_COMPLETED';


SELECT
    event_id,
    aggregate_id,
    topic_name,
    status,
    retry_count,
    correlation_id
FROM outbox_event
WHERE aggregate_type = 'SETTLEMENT'
  AND aggregate_id = '1796d145-c449-448e-bc4d-d8ff0d60e601';
  
  
SELECT idempotency_key, settlement_id, response_status
FROM idempotency_record
WHERE quote_id = '071afdb8-1da7-48a6-aa35-e296cfc28af6';


SELECT COUNT(*) AS ledger_entries
FROM wallet_ledger
WHERE settlement_id = '1796d145-c449-448e-bc4d-d8ff0d60e601';

SELECT COUNT(*) AS success_audits
FROM audit_event
WHERE entity_type = 'SETTLEMENT'
  AND entity_id = '1796d145-c449-448e-bc4d-d8ff0d60e601'
  AND event_type = 'SETTLEMENT_COMPLETED';

SELECT COUNT(*) AS outbox_events
FROM outbox_event
WHERE aggregate_type = 'SETTLEMENT'
  AND aggregate_id = '1796d145-c449-448e-bc4d-d8ff0d60e601';
  
SELECT event_type, event_status, details, created_at
FROM audit_event
WHERE entity_type = 'QUOTE'
  AND entity_id = '3742c1cb-cf91-428c-90dd-c33de0243f8d'
ORDER BY created_at DESC;