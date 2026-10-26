-- LOCAL DEVELOPMENT ONLY: a synthetic training observation, not a market fetch.
-- Run manually as FXLOCK in your own local FREEPDB1 while M1 is not running.
-- Never run automatically or on a shared/live feed. Existing observations stay immutable.
INSERT INTO MARKET_RATE_SNAPSHOT
(CURRENCY_PAIR,PROVIDER_NAME,MARKET_RATE,PROVIDER_TIMESTAMP,RECEIVED_AT,
 VALIDATION_STATUS,TRUSTED_FLAG,MOVEMENT_PERCENT,VALIDATION_REASON)
VALUES ('EUR/USD','DEV_SEED',1.170000,SYSTIMESTAMP,SYSTIMESTAMP,
 'TRUSTED','Y',0,'Manually requested local development fixture');
COMMIT;
