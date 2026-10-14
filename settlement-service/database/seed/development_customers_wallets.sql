INSERT INTO fx_customer (customer_id, display_name)
VALUES ('CUSTOMER_001', 'Demo Customer');

INSERT INTO wallet (
    wallet_id, customer_id, currency_code, balance
)
VALUES (
    'WALLET_EUR_001', 'CUSTOMER_001', 'EUR', 1000.00
);

INSERT INTO wallet (
    wallet_id, customer_id, currency_code, balance
)
VALUES (
    'WALLET_USD_001', 'CUSTOMER_001', 'USD', 200.00
);

COMMIT;