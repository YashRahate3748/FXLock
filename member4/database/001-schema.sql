-- Run as the fx application user in FREEPDB1. Oracle 19c+; no automatic destructive initialization.
CREATE TABLE fx_outbox (
 event_id VARCHAR2(36) PRIMARY KEY,
 topic VARCHAR2(80) NOT NULL CHECK(topic IN ('fx-rate-updated','fx-settlement-completed','fx-system-alert')),
 event_key VARCHAR2(200) NOT NULL,
 envelope CLOB NOT NULL CHECK(envelope IS JSON),
 status VARCHAR2(12) DEFAULT 'PENDING' NOT NULL CHECK(status IN ('PENDING','IN_FLIGHT','PUBLISHED')),
 attempts NUMBER(10) DEFAULT 0 NOT NULL,
 next_attempt_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
 lease_until TIMESTAMP WITH TIME ZONE,
 claim_token VARCHAR2(36),
 last_error VARCHAR2(2000),
 created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
 published_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX ix_outbox_due ON fx_outbox(status,next_attempt_at,lease_until);
CREATE TABLE fx_inbox (
 consumer_name VARCHAR2(100) NOT NULL,
 event_id VARCHAR2(36) NOT NULL,
 body_hash VARCHAR2(64) NOT NULL,
 processed_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
 CONSTRAINT pk_fx_inbox PRIMARY KEY(consumer_name,event_id)
);
CREATE TABLE fx_audit (
 event_id VARCHAR2(36) PRIMARY KEY,
 event_type VARCHAR2(80) NOT NULL,
 aggregate_id VARCHAR2(200) NOT NULL,
 source VARCHAR2(100) NOT NULL,
 correlation_id VARCHAR2(100) NOT NULL,
 occurred_at TIMESTAMP NOT NULL,
 received_at TIMESTAMP WITH TIME ZONE NOT NULL,
 kafka_partition NUMBER(10) NOT NULL,
 kafka_offset NUMBER(19) NOT NULL,
 envelope CLOB NOT NULL CHECK(envelope IS JSON)
);
CREATE INDEX ix_audit_history ON fx_audit(aggregate_id,received_at);
