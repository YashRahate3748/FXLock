package com.fxlock.quote;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic fixtures only. Never called by application code or committed to the user's Oracle database. */
final class HistoryFixtures {
    record Fixture(String settlement,long source,long target) {}
    static Fixture conversion(JdbcTemplate jdbc,String reference) {
        long customer=jdbc.queryForObject("SELECT CUSTOMER_ID FROM CUSTOMER WHERE CUSTOMER_REF=?",Long.class,reference);
        long source=jdbc.queryForObject("SELECT WALLET_ID FROM WALLET WHERE CUSTOMER_ID=? AND CURRENCY_CODE='EUR'",Long.class,customer);
        long target=jdbc.queryForObject("SELECT WALLET_ID FROM WALLET WHERE CUSTOMER_ID=? AND CURRENCY_CODE='USD'",Long.class,customer);
        long snapshot=jdbc.queryForObject("SELECT MIN(RATE_SNAPSHOT_ID) FROM MARKET_RATE_SNAPSHOT",Long.class);
        String quote=UUID.randomUUID().toString(),settlement=UUID.randomUUID().toString();
        var time=OffsetDateTime.parse("2026-10-22T10:00:00Z");
        jdbc.update("""
            INSERT INTO FX_QUOTE (QUOTE_ID,CUSTOMER_ID,SOURCE_WALLET_ID,TARGET_WALLET_ID,RATE_SNAPSHOT_ID,
            SOURCE_CURRENCY,TARGET_CURRENCY,SOURCE_AMOUNT,MARKET_RATE,CUSTOMER_RATE,APPLIED_SPREAD_BPS,
            TARGET_AMOUNT,STATUS,CREATED_AT,EXPIRES_AT,SETTLED_AT)
            VALUES (?,?,?,?,?,'EUR','USD',1000,1.17,1.16766,20,1167.66,'SETTLED',?,?,?)
            """,quote,customer,source,target,snapshot,time,time.plusSeconds(60),time.plusSeconds(5));
        jdbc.update("""
            INSERT INTO SETTLEMENT (SETTLEMENT_ID,QUOTE_ID,CUSTOMER_ID,SOURCE_WALLET_ID,TARGET_WALLET_ID,
            SOURCE_AMOUNT,TARGET_AMOUNT,CUSTOMER_RATE,STATUS,COMPLETED_AT)
            VALUES (?,?,?,?,?,1000,1167.66,1.16766,'COMPLETED',?)
            """,settlement,quote,customer,source,target,time.plusSeconds(5));
        jdbc.update("""
            INSERT INTO WALLET_LEDGER (SETTLEMENT_ID,WALLET_ID,ENTRY_TYPE,CURRENCY_CODE,AMOUNT,BALANCE_BEFORE,BALANCE_AFTER,CREATED_AT)
            VALUES (?,?,'DEBIT','EUR',1000,5000,4000,?)
            """,settlement,source,time.plusSeconds(5));
        jdbc.update("""
            INSERT INTO WALLET_LEDGER (SETTLEMENT_ID,WALLET_ID,ENTRY_TYPE,CURRENCY_CODE,AMOUNT,BALANCE_BEFORE,BALANCE_AFTER,CREATED_AT)
            VALUES (?,?,'CREDIT','USD',1167.66,1000,2167.66,?)
            """,settlement,target,time.plusSeconds(5));
        // H2 has no Oracle trigger. Oracle already populated these; NOT EXISTS avoids duplicate test rows.
        jdbc.update("""
            INSERT INTO TRANSACTION_HISTORY (LEDGER_ID,SETTLEMENT_ID,CUSTOMER_ID,CUSTOMER_NAME,WALLET_ID,
            CURRENCY_CODE,AMOUNT_CREDITED,AMOUNT_DEBITED,UPDATED_BALANCE,TRANSACTION_DATE)
            SELECT L.LEDGER_ID,L.SETTLEMENT_ID,W.CUSTOMER_ID,C.FULL_NAME,L.WALLET_ID,L.CURRENCY_CODE,
            CASE WHEN L.ENTRY_TYPE='CREDIT' THEN L.AMOUNT ELSE 0 END,
            CASE WHEN L.ENTRY_TYPE='DEBIT' THEN L.AMOUNT ELSE 0 END,L.BALANCE_AFTER,L.CREATED_AT
            FROM WALLET_LEDGER L JOIN WALLET W ON W.WALLET_ID=L.WALLET_ID
            JOIN CUSTOMER C ON C.CUSTOMER_ID=W.CUSTOMER_ID WHERE L.SETTLEMENT_ID=?
            AND NOT EXISTS (SELECT 1 FROM TRANSACTION_HISTORY H WHERE H.LEDGER_ID=L.LEDGER_ID)
            """,settlement);
        return new Fixture(settlement,source,target);
    }
}
