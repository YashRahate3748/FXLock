-- Read-only: run against the existing shared FXLOCK schema. No CREATE/DROP/DELETE.
SELECT table_name,column_name,data_type,data_length,nullable
FROM user_tab_columns
WHERE table_name IN ('OUTBOX_EVENT','PROCESSED_EVENT','AUDIT_EVENT')
ORDER BY table_name,column_id;

SELECT status,COUNT(*) FROM outbox_event GROUP BY status;
SELECT event_id,event_type,topic_name,status,retry_count,last_error,last_attempt_at,published_at
FROM outbox_event ORDER BY created_at DESC FETCH FIRST 30 ROWS ONLY;
SELECT consumer_name,COUNT(*) FROM processed_event GROUP BY consumer_name;
SELECT audit_id,event_type,entity_type,entity_id,actor_id,event_status,correlation_id
FROM audit_event ORDER BY audit_id DESC FETCH FIRST 30 ROWS ONLY;

-- Every published event should eventually have a receipt for this consumer.
SELECT o.event_id,o.event_type FROM outbox_event o
WHERE o.status='PUBLISHED' AND NOT EXISTS
 (SELECT 1 FROM processed_event p WHERE p.event_id=o.event_id AND p.consumer_name='fxlock-audit-v1');

-- Old publisher state: the new worker never commits PROCESSING.
SELECT event_id,last_attempt_at FROM outbox_event WHERE status='PROCESSING';
