-- Run manually against the EXISTING common NUMBER(19,4) wallet schema, after customer_account exists.
-- Do not run V001__create_customers_wallets.sql: it describes a different legacy schema.
-- No CREATE TRIGGER privilege is required. Funding records are append-only through
-- the application; this does not prevent the schema owner from changing them with SQL.
-- If this table and index already exist after an earlier trigger privilege error,
-- do not rerun this script. No additional database change is needed for this version.
CREATE TABLE wallet_funding (
    transaction_ref VARCHAR2(36) NOT NULL,
    customer_id NUMBER NOT NULL,
    wallet_id NUMBER NOT NULL,
    operation VARCHAR2(10) NOT NULL,
    currency_code VARCHAR2(3) NOT NULL,
    amount NUMBER(19,2) NOT NULL,
    balance_before NUMBER(19,4) NOT NULL,
    balance_after NUMBER(19,4) NOT NULL,
    idempotency_key VARCHAR2(200) NOT NULL,
    correlation_id VARCHAR2(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_wallet_funding PRIMARY KEY (transaction_ref),
    CONSTRAINT uq_funding_intent UNIQUE (idempotency_key),
    CONSTRAINT fk_funding_customer FOREIGN KEY (customer_id) REFERENCES customer(customer_id),
    CONSTRAINT fk_funding_wallet FOREIGN KEY (wallet_id) REFERENCES wallet(wallet_id),
    CONSTRAINT fk_funding_intent FOREIGN KEY (idempotency_key) REFERENCES idempotency_record(idempotency_key),
    CONSTRAINT ck_funding_operation CHECK (operation IN ('DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT ck_funding_currency CHECK (currency_code IN ('EUR', 'USD')),
    CONSTRAINT ck_funding_amount CHECK (amount > 0 AND amount <= 999999999999999.99),
    CONSTRAINT ck_funding_before CHECK (balance_before >= 0),
    CONSTRAINT ck_funding_after CHECK (balance_after >= 0),
    CONSTRAINT ck_funding_movement CHECK (
        (operation = 'DEPOSIT' AND balance_after = balance_before + amount) OR
        (operation = 'WITHDRAWAL' AND balance_after = balance_before - amount))
);
CREATE INDEX idx_funding_customer_time ON wallet_funding(customer_id, created_at);

