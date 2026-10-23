-- Read-only report, for the requested columns in SQL Developer.
-- The application must derive customer identity from login if exposing this
-- table through an API. Do not expose an unrestricted all-customer endpoint.
SELECT
    CUSTOMER_NAME,
    CURRENCY_CODE,
    AMOUNT_CREDITED,
    AMOUNT_DEBITED,
    UPDATED_BALANCE,
    TRANSACTION_DATE,
    WALLET_ID,
    SETTLEMENT_ID
FROM TRANSACTION_HISTORY
WHERE CUSTOMER_ID =
    (SELECT CUSTOMER_ID FROM CUSTOMER WHERE CUSTOMER_REF = 'CUST001')
ORDER BY TRANSACTION_DATE DESC, LEDGER_ID DESC;