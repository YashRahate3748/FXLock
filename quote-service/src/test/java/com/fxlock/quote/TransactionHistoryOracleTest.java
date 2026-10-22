package com.fxlock.quote;

import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Disposable financial fixtures: all test writes roll back, never committed as real history. */
@EnabledIfSystemProperty(named="runOracle",matches="true")
@SpringBootTest @ActiveProfiles("oracle-local") @Transactional
class TransactionHistoryOracleTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    long customer,source,target,snapshot;
    String name;
    final OffsetDateTime time=OffsetDateTime.parse("2026-10-21T10:00:00Z");
    @BeforeEach void identities() {
        customer=jdbc.queryForObject("SELECT CUSTOMER_ID FROM CUSTOMER WHERE CUSTOMER_REF='CUST001'",Long.class);
        name=jdbc.queryForObject("SELECT FULL_NAME FROM CUSTOMER WHERE CUSTOMER_ID=?",String.class,customer);
        source=jdbc.queryForObject("SELECT WALLET_ID FROM WALLET WHERE CUSTOMER_ID=? AND CURRENCY_CODE='EUR'",Long.class,customer);
        target=jdbc.queryForObject("SELECT WALLET_ID FROM WALLET WHERE CUSTOMER_ID=? AND CURRENCY_CODE='USD'",Long.class,customer);
        snapshot=jdbc.queryForObject("SELECT MIN(RATE_SNAPSHOT_ID) FROM MARKET_RATE_SNAPSHOT",Long.class);
    }
    String settlement() {
        String quote=UUID.randomUUID().toString(),settlement=UUID.randomUUID().toString();
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
        return settlement;
    }
    long entry(String settlement,boolean debit) {
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps=connection.prepareStatement("""
                INSERT INTO WALLET_LEDGER (SETTLEMENT_ID,WALLET_ID,ENTRY_TYPE,CURRENCY_CODE,AMOUNT,
                BALANCE_BEFORE,BALANCE_AFTER,CREATED_AT) VALUES (?,?,?,?,?,?,?,?)
                """,new String[]{"LEDGER_ID"});
            ps.setString(1,settlement);ps.setLong(2,debit?source:target);ps.setString(3,debit?"DEBIT":"CREDIT");
            ps.setString(4,debit?"EUR":"USD");ps.setBigDecimal(5,new BigDecimal(debit?"1000":"1167.66"));
            ps.setBigDecimal(6,new BigDecimal(debit?"5000":"1000"));
            ps.setBigDecimal(7,new BigDecimal(debit?"4000":"2167.66"));ps.setObject(8,time.plusSeconds(5));return ps;
        },keys);
        return keys.getKey().longValue();
    }
    @Test void recordsCreditAndDebitInSeparateCurrencies() {
        String settlement=settlement();long debit=entry(settlement,true),credit=entry(settlement,false);
        var d=jdbc.queryForMap("SELECT * FROM TRANSACTION_HISTORY WHERE LEDGER_ID=?",debit);
        var c=jdbc.queryForMap("SELECT * FROM TRANSACTION_HISTORY WHERE LEDGER_ID=?",credit);
        assertThat(d.get("CUSTOMER_NAME")).isEqualTo(name);
        assertThat(d.get("CURRENCY_CODE")).isEqualTo("EUR");
        assertThat((BigDecimal)d.get("AMOUNT_DEBITED")).isEqualByComparingTo("1000");
        assertThat((BigDecimal)d.get("AMOUNT_CREDITED")).isZero();
        assertThat((BigDecimal)d.get("UPDATED_BALANCE")).isEqualByComparingTo("4000");
        assertThat(c.get("CURRENCY_CODE")).isEqualTo("USD");
        assertThat((BigDecimal)c.get("AMOUNT_CREDITED")).isEqualByComparingTo("1167.66");
        assertThat((BigDecimal)c.get("AMOUNT_DEBITED")).isZero();
        assertThat((BigDecimal)c.get("UPDATED_BALANCE")).isEqualByComparingTo("2167.66");
        assertThat(jdbc.queryForObject("SELECT TRANSACTION_DATE FROM TRANSACTION_HISTORY WHERE LEDGER_ID=?",OffsetDateTime.class,credit).toInstant())
            .isEqualTo(time.plusSeconds(5).toInstant());
    }
    @Test void usesHistoricalPostEntryBalanceInsteadOfCurrentWalletBalance() {
        BigDecimal current=jdbc.queryForObject("SELECT BALANCE FROM WALLET WHERE WALLET_ID=?",BigDecimal.class,source);
        long ledger=entry(settlement(),true);
        assertThat(jdbc.queryForObject("SELECT UPDATED_BALANCE FROM TRANSACTION_HISTORY WHERE LEDGER_ID=?",BigDecimal.class,ledger))
            .isEqualByComparingTo("4000");
        assertThat(jdbc.queryForObject("SELECT BALANCE FROM WALLET WHERE WALLET_ID=?",BigDecimal.class,source)).isEqualByComparingTo(current);
    }
    @Test void uniqueLedgerConstraintRejectsDuplicateCopy() {
        long ledger=entry(settlement(),true);
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO TRANSACTION_HISTORY (LEDGER_ID,SETTLEMENT_ID,CUSTOMER_ID,CUSTOMER_NAME,WALLET_ID,
            CURRENCY_CODE,AMOUNT_CREDITED,AMOUNT_DEBITED,UPDATED_BALANCE,TRANSACTION_DATE)
            SELECT LEDGER_ID,SETTLEMENT_ID,CUSTOMER_ID,CUSTOMER_NAME,WALLET_ID,CURRENCY_CODE,
            AMOUNT_CREDITED,AMOUNT_DEBITED,UPDATED_BALANCE,TRANSACTION_DATE FROM TRANSACTION_HISTORY WHERE LEDGER_ID=?
            """,ledger)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TRANSACTION_HISTORY WHERE LEDGER_ID=?",Integer.class,ledger)).isEqualTo(1);
    }
    @Test void rejectsRowsWithBothCreditAndDebit() {
        long ledger=entry(settlement(),true);
        assertThatThrownBy(() -> jdbc.update("UPDATE TRANSACTION_HISTORY SET AMOUNT_CREDITED=5 WHERE LEDGER_ID=?",ledger))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void rollbackRemovesBothLedgerAndHistory() {
        var transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Long ledger=transaction.execute(status -> {
            long id=entry(settlement(),true);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TRANSACTION_HISTORY WHERE LEDGER_ID=?",Integer.class,id)).isEqualTo(1);
            status.setRollbackOnly();return id;
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM WALLET_LEDGER WHERE LEDGER_ID=?",Integer.class,ledger)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TRANSACTION_HISTORY WHERE LEDGER_ID=?",Integer.class,ledger)).isZero();
    }
}
