CREATE TABLE fx_customer (
    customer_id VARCHAR2(36 CHAR) PRIMARY KEY,
    display_name VARCHAR2(100 CHAR) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE
        DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE wallet (
    wallet_id VARCHAR2(36 CHAR) PRIMARY KEY,
    customer_id VARCHAR2(36 CHAR) NOT NULL,
    currency_code VARCHAR2(3 CHAR) NOT NULL,
    balance NUMBER(19, 2) DEFAULT 0 NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE
        DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT fk_wallet_customer
        FOREIGN KEY (customer_id)
        REFERENCES fx_customer(customer_id),

    CONSTRAINT uq_wallet_customer_currency
        UNIQUE (customer_id, currency_code),

    CONSTRAINT ck_wallet_currency
        CHECK (currency_code IN ('EUR', 'USD')),

    CONSTRAINT ck_wallet_balance
        CHECK (balance >= 0)
);