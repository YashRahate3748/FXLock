SET SERVEROUTPUT ON;

DECLARE
    v_customer_id CUSTOMER.CUSTOMER_ID%TYPE;
    v_source_wallet_id WALLET.WALLET_ID%TYPE;
    v_target_wallet_id WALLET.WALLET_ID%TYPE;
    v_balance WALLET.BALANCE%TYPE;
    v_rate_snapshot_id MARKET_RATE_SNAPSHOT.RATE_SNAPSHOT_ID%TYPE;

    v_source_amount FX_QUOTE.SOURCE_AMOUNT%TYPE;
    v_target_amount FX_QUOTE.TARGET_AMOUNT%TYPE;

    v_raw_id VARCHAR2(32);
    v_quote_id FX_QUOTE.QUOTE_ID%TYPE;
    v_created_at TIMESTAMP WITH TIME ZONE;
BEGIN
    SELECT customer_id
    INTO v_customer_id
    FROM customer
    WHERE customer_ref = 'CUST001'
      AND status = 'ACTIVE';

    SELECT wallet_id, balance
    INTO v_source_wallet_id, v_balance
    FROM wallet
    WHERE customer_id = v_customer_id
      AND currency_code = 'EUR'
      AND status = 'ACTIVE';

    SELECT wallet_id
    INTO v_target_wallet_id
    FROM wallet
    WHERE customer_id = v_customer_id
      AND currency_code = 'USD'
      AND status = 'ACTIVE';

    -- Exceed the current EUR balance without changing it.
    v_source_amount := v_balance + 100;
    v_target_amount := ROUND(v_source_amount * 1.0978, 2);

    INSERT INTO market_rate_snapshot (
        currency_pair,
        provider_name,
        market_rate,
        provider_timestamp,
        received_at,
        validation_status,
        trusted_flag,
        movement_percent,
        validation_reason
    )
    VALUES (
        'EUR/USD',
        'DEV_SETTLEMENT_FIXTURE',
        1.1000,
        SYSTIMESTAMP,
        SYSTIMESTAMP,
        'TRUSTED',
        'Y',
        0,
        'Local insufficient-funds test fixture'
    )
    RETURNING rate_snapshot_id INTO v_rate_snapshot_id;

    v_raw_id := LOWER(RAWTOHEX(SYS_GUID()));

    v_quote_id :=
        SUBSTR(v_raw_id, 1, 8) || '-' ||
        SUBSTR(v_raw_id, 9, 4) || '-' ||
        SUBSTR(v_raw_id, 13, 4) || '-' ||
        SUBSTR(v_raw_id, 17, 4) || '-' ||
        SUBSTR(v_raw_id, 21, 12);

    v_created_at := SYSTIMESTAMP;

    INSERT INTO fx_quote (
        quote_id,
        customer_id,
        source_wallet_id,
        target_wallet_id,
        rate_snapshot_id,
        source_currency,
        target_currency,
        source_amount,
        market_rate,
        customer_rate,
        applied_spread_bps,
        target_amount,
        status,
        created_at,
        expires_at,
        correlation_id
    )
    VALUES (
        v_quote_id,
        v_customer_id,
        v_source_wallet_id,
        v_target_wallet_id,
        v_rate_snapshot_id,
        'EUR',
        'USD',
        v_source_amount,
        1.1000,
        1.0978,
        20,
        v_target_amount,
        'OPEN',
        v_created_at,
        v_created_at + INTERVAL '60' SECOND,
        'LOCAL-M3-' || v_quote_id
    );

    COMMIT;

    DBMS_OUTPUT.PUT_LINE('Quote ID: ' || v_quote_id);
    DBMS_OUTPUT.PUT_LINE('Current EUR balance: ' || v_balance);
    DBMS_OUTPUT.PUT_LINE('Requested EUR amount: ' || v_source_amount);
    DBMS_OUTPUT.PUT_LINE('Accept within 60 seconds to test insufficient funds.');

EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        RAISE;
END;
/