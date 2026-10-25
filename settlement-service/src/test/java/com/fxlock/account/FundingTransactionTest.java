package com.fxlock.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxlock.audit.AuditRepository;
import com.fxlock.dashboard.*;
import com.fxlock.idempotency.IdempotencyRepository;
import com.fxlock.ledger.LedgerRepository;
import com.fxlock.outbox.OutboxRepository;
import com.fxlock.quote.QuoteRepository;
import com.fxlock.settlement.*;
import com.fxlock.wallet.WalletRepository;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

/** Every connection is an isolated in-memory fixture; no application datasource is loaded. */
class FundingTransactionTest {
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    FundingService funding;
    DashboardAccounts accounts;
    FundingRepository records;

    @Configuration
    @EnableTransactionManagement
    static class Fixture {
        @Bean DataSource datasource() {
            var source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            return source;
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean DashboardAccounts accounts(JdbcTemplate jdbc) { return new DashboardAccounts(jdbc, new PlaintextDemoAccountPasswords(), 3, 1); }
        @Bean WalletRepository wallets(JdbcTemplate jdbc) { return new WalletRepository(jdbc); }
        @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean FundingRepository records(JdbcTemplate jdbc, ObjectMapper json) { return new FundingRepository(jdbc, json); }
        @Bean FundingService funding(DashboardAccounts accounts, WalletRepository wallets, FundingRepository records) {
            return new FundingService(accounts, wallets, records, new BigDecimal("1000000.00"));
        }
        @Bean SettlementService settlement(JdbcTemplate jdbc, WalletRepository wallets, ObjectMapper json) {
            return new SettlementService(new QuoteRepository(jdbc), wallets, new SettlementRepository(jdbc),
                    new LedgerRepository(jdbc), new IdempotencyRepository(jdbc, json), new AuditRepository(jdbc, json), new OutboxRepository(jdbc, json));
        }
    }

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Fixture.class);
        jdbc = context.getBean(JdbcTemplate.class);
        funding = context.getBean(FundingService.class);
        accounts = context.getBean(DashboardAccounts.class);
        records = context.getBean(FundingRepository.class);
        String[] schema = {
            "CREATE TABLE customer (customer_id NUMBER PRIMARY KEY, customer_ref VARCHAR2(50), full_name VARCHAR2(150), status VARCHAR2(20))",
            "CREATE TABLE customer_account (customer_id NUMBER PRIMARY KEY REFERENCES customer, email VARCHAR2(200), login_password VARCHAR2(255), account_status VARCHAR2(20), created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP)",
            "CREATE TABLE wallet (wallet_id NUMBER PRIMARY KEY, customer_id NUMBER REFERENCES customer, currency_code VARCHAR2(3), balance NUMBER(19,4) CHECK(balance >= 0), status VARCHAR2(20), version_no NUMBER DEFAULT 0, updated_at TIMESTAMP WITH TIME ZONE)",
            "CREATE TABLE idempotency_record (idempotency_key VARCHAR2(200) PRIMARY KEY, request_hash VARCHAR2(128), operation_type VARCHAR2(50), response_status VARCHAR2(30), response_code VARCHAR2(100), response_body CLOB, quote_id VARCHAR2(36), settlement_id VARCHAR2(36), updated_at TIMESTAMP WITH TIME ZONE)",
            "CREATE TABLE wallet_funding (transaction_ref VARCHAR2(36) PRIMARY KEY, customer_id NUMBER REFERENCES customer, wallet_id NUMBER REFERENCES wallet, operation VARCHAR2(10), currency_code VARCHAR2(3), amount NUMBER(19,2) CHECK(amount > 0), balance_before NUMBER(19,4), balance_after NUMBER(19,4), idempotency_key VARCHAR2(200) UNIQUE REFERENCES idempotency_record, correlation_id VARCHAR2(100), created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP)",
            "CREATE TABLE audit_event (event_type VARCHAR2(100), entity_type VARCHAR2(100), entity_id VARCHAR2(100), actor_type VARCHAR2(50), actor_id VARCHAR2(100), event_status VARCHAR2(30), details CLOB, correlation_id VARCHAR2(100))",
            "CREATE TABLE outbox_event (event_id VARCHAR2(36), aggregate_type VARCHAR2(100), aggregate_id VARCHAR2(100), event_type VARCHAR2(100), topic_name VARCHAR2(200), payload CLOB, status VARCHAR2(20), correlation_id VARCHAR2(100))",
            "CREATE TABLE fx_quote (quote_id VARCHAR2(36) PRIMARY KEY, customer_id NUMBER, source_wallet_id NUMBER, target_wallet_id NUMBER, source_currency VARCHAR2(3), target_currency VARCHAR2(3), source_amount NUMBER(19,4), target_amount NUMBER(19,4), customer_rate NUMBER(20,10), status VARCHAR2(20), expires_at TIMESTAMP WITH TIME ZONE, settled_at TIMESTAMP WITH TIME ZONE, market_rate NUMBER(20,10) DEFAULT 1.17, applied_spread_bps NUMBER(10,4) DEFAULT 20, created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP)",
            "CREATE TABLE settlement (settlement_id VARCHAR2(36) PRIMARY KEY, quote_id VARCHAR2(36), customer_id NUMBER, source_wallet_id NUMBER, target_wallet_id NUMBER, source_amount NUMBER(19,4), target_amount NUMBER(19,4), customer_rate NUMBER(20,10), status VARCHAR2(20), completed_at TIMESTAMP WITH TIME ZONE, correlation_id VARCHAR2(100))",
            "CREATE TABLE wallet_ledger (settlement_id VARCHAR2(36), wallet_id NUMBER, entry_type VARCHAR2(10), currency_code VARCHAR2(3), amount NUMBER(19,4), balance_before NUMBER(19,4), balance_after NUMBER(19,4), ledger_id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP)"
        };
        for (String sql : schema) jdbc.execute(sql);
        jdbc.update("INSERT INTO customer VALUES (1, 'fixture-a', 'Customer A', 'ACTIVE'), (2, 'fixture-b', 'Customer B', 'ACTIVE')");
        jdbc.update("INSERT INTO customer_account(customer_id,email,login_password,account_status) VALUES (1, 'a@example.test', ' x ', 'ACTIVE'), (2, 'b@example.test', 'y', 'ACTIVE')");
        jdbc.update("INSERT INTO wallet(wallet_id,customer_id,currency_code,balance,status) VALUES (10,1,'EUR',100,'ACTIVE'), (11,1,'USD',0,'ACTIVE'), (20,2,'EUR',50,'ACTIVE')");
    }

    @AfterEach void cleanup() { context.close(); }
    String key() { return UUID.randomUUID().toString(); }
    FundingRequest intent(String operation, String wallet, String amount) { return new FundingRequest(operation, wallet, amount, null); }
    FundingResult apply(String key, String operation, String amount) { return funding.complete("fixture-a", key, intent(operation, "10", amount)); }
    BigDecimal balance() { return jdbc.queryForObject("SELECT balance FROM wallet WHERE wallet_id=10", BigDecimal.class); }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    @Test void depositWithdrawalAndFullBalancePreserveStorageScaleAndVersion() {
        jdbc.update("UPDATE wallet SET balance=100.1234 WHERE wallet_id=10");
        var result = apply(key(), "DEPOSIT", "1.20");
        assertThat(result.balanceAfter()).isEqualTo("101.3234");
        apply(key(), "WITHDRAWAL", "101.32");
        assertThat(balance()).isEqualByComparingTo("0.0034");
        jdbc.update("UPDATE wallet SET balance=100 WHERE wallet_id=10");
        apply(key(), "WITHDRAWAL", "100.00");
        assertThat(balance()).isZero();
        assertThat(count("wallet_funding")).isEqualTo(3);
        assertThat(count("audit_event")).isEqualTo(3);
        assertThat(count("outbox_event")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT version_no FROM wallet WHERE wallet_id=10", Long.class)).isEqualTo(3);
    }

    @ParameterizedTest @ValueSource(strings={"0", "-1", "0.00", "1.001", "1.000", "1e3", "NaN", " 1", "1000000.01", "9999999999999999", ""})
    void invalidAmountsNeverWrite(String amount) {
        assertThatThrownBy(() -> apply(key(), "DEPOSIT", amount)).isInstanceOf(ResponseStatusException.class);
        assertThat(balance()).isEqualByComparingTo("100");
        assertThat(count("idempotency_record")).isZero();
    }

    @Test void ownershipAndInactiveStatesAreRecheckedInsideTransaction() {
        assertThatThrownBy(() -> funding.complete("fixture-a", key(), intent("DEPOSIT", "20", "1"))).hasMessageContaining("WALLET_UNAVAILABLE");
        assertThatThrownBy(() -> funding.complete("fixture-a", key(), intent("DEPOSIT", "99", "1"))).hasMessageContaining("WALLET_UNAVAILABLE");
        for (String status : new String[]{"BLOCKED", "CLOSED", "INACTIVE"}) {
            jdbc.update("UPDATE wallet SET status=? WHERE wallet_id=10", status);
            assertThatThrownBy(() -> apply(key(), "DEPOSIT", "1")).hasMessageContaining("WALLET_UNAVAILABLE");
        }
        jdbc.update("UPDATE wallet SET status='ACTIVE' WHERE wallet_id=10");
        jdbc.update("UPDATE customer SET status='INACTIVE' WHERE customer_id=1");
        assertThatThrownBy(() -> apply(key(), "DEPOSIT", "1")).hasMessageContaining("ACCOUNT_NOT_ACTIVE");
        jdbc.update("UPDATE customer SET status='ACTIVE' WHERE customer_id=1");
        jdbc.update("UPDATE customer_account SET account_status='DISABLED' WHERE customer_id=1");
        assertThatThrownBy(() -> apply(key(), "DEPOSIT", "1")).hasMessageContaining("ACCOUNT_NOT_ACTIVE");
        assertThat(count("wallet_funding")).isZero();
    }

    @Test void insufficientFundsAndOverflowNeverWrite() {
        assertThatThrownBy(() -> apply(key(), "WITHDRAWAL", "100.01")).hasMessageContaining("INSUFFICIENT_FUNDS");
        jdbc.update("UPDATE wallet SET balance=999999999999999.9999 WHERE wallet_id=10");
        assertThatThrownBy(() -> apply(key(), "DEPOSIT", "0.01")).hasMessageContaining("BALANCE_LIMIT");
        assertThat(count("wallet_funding")).isZero();
    }

    @Test void recordAuditAndOutboxFailuresRollbackAllWrites() {
        for (String table : new String[]{"wallet_funding", "audit_event", "outbox_event"}) {
            jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT fail_write CHECK (1=0)");
            assertThatThrownBy(() -> apply(key(), "DEPOSIT", "10")).isInstanceOf(RuntimeException.class);
            assertThat(balance()).isEqualByComparingTo("100");
            assertThat(count("wallet_funding")).isZero();
            assertThat(count("idempotency_record")).isZero();
            assertThat(count("audit_event")).isZero();
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT fail_write");
        }
    }

    @Test void lostResponseAndLaterBalanceChangesDoNotInvalidateReplay() {
        String original = key();
        var first = apply(original, "WITHDRAWAL", "100");
        assertThat(records.find(original, 1).orElseThrow()).isEqualTo(first);
        assertThat(records.find(original, 2)).isEmpty();
        apply(key(), "DEPOSIT", "1");
        assertThat(apply(original, "WITHDRAWAL", "100.00")).isEqualTo(first);
        assertThat(balance()).isEqualByComparingTo("1");
        assertThat(count("wallet_funding")).isEqualTo(2);
        assertThatThrownBy(() -> apply(original, "DEPOSIT", "100")).hasMessageContaining("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> apply(original, "WITHDRAWAL", "99")).hasMessageContaining("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> funding.complete("fixture-a", original, intent("WITHDRAWAL", "11", "100"))).hasMessageContaining("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> funding.complete("fixture-b", original, intent("WITHDRAWAL", "20", "100"))).hasMessageContaining("IDEMPOTENCY_CONFLICT");
    }

    @Test void exactCurrentPasswordAndTemporaryThrottle() throws Exception {
        accounts.confirm("fixture-a", " x ");
        for (String password : new String[]{null, "x", "wrong"}) {
            assertThatThrownBy(() -> accounts.confirm("fixture-a", password)).hasMessageContaining("CONFIRMATION_REJECTED");
        }
        assertThatThrownBy(() -> accounts.confirm("fixture-a", " x ")).hasMessageContaining("PASSWORD_THROTTLED");
        accounts.confirm("fixture-b", "y");
        Thread.sleep(1100);
        accounts.confirm("fixture-a", " x ");
    }

    @Test void concurrentSameKeyAndDifferentCustomerKeysAreSafe() throws Exception {
        String original = key();
        var results = concurrent(() -> apply(original, "DEPOSIT", "10"), () -> apply(original, "DEPOSIT", "10"));
        assertThat(results[0]).isEqualTo(results[1]);
        assertThat(balance()).isEqualByComparingTo("110");
        assertThat(count("wallet_funding")).isEqualTo(1);
        var conflict = concurrent(() -> apply(key(), "WITHDRAWAL", "80"), () -> apply(key(), "WITHDRAWAL", "80"));
        assertThat(java.util.Arrays.stream(conflict).filter(v -> v instanceof FundingResult).count()).isEqualTo(1);
        assertThat(balance()).isEqualByComparingTo("30");
    }

    @Test void fundingAndActualFxSettlementCoordinateOnTheSameWallet() throws Exception {
        jdbc.update("INSERT INTO fx_quote(quote_id,customer_id,source_wallet_id,target_wallet_id,source_currency,target_currency,source_amount,target_amount,customer_rate,status,expires_at) VALUES ('quote',1,10,11,'EUR','USD',80,90,1.125,'OPEN',DATEADD('MINUTE',5,SYSTIMESTAMP))");
        var settlement = context.getBean(SettlementService.class);
        Object[] results = concurrent(() -> apply(key(), "WITHDRAWAL", "80"), () -> settlement.settle("quote", "fixture-a", key(), "fixture-correlation"));
        assertThat(java.util.Arrays.stream(results).filter(v -> v instanceof ResponseStatusException).count()).isEqualTo(1);
        assertThat(balance()).isEqualByComparingTo("20");
        assertThat(count("wallet_funding") + count("settlement")).isEqualTo(1);
    }

    @Test void databaseUniqueKeyAlsoCoordinatesDifferentCustomers() throws Exception {
        String shared = key();
        Object[] results = concurrent(() -> apply(shared, "DEPOSIT", "10"),
                () -> funding.complete("fixture-b", shared, intent("DEPOSIT", "20", "10")));
        assertThat(java.util.Arrays.stream(results).filter(v -> v instanceof FundingResult).count()).isEqualTo(1);
        assertThat(java.util.Arrays.stream(results).filter(v -> v instanceof ResponseStatusException).count()).isEqualTo(1);
        assertThat(count("wallet_funding")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(balance) FROM wallet", BigDecimal.class)).isEqualByComparingTo("160");
    }

    @Test void missingKeyOperationWalletAndAmountAreRejected() {
        assertThatThrownBy(() -> apply(null, "DEPOSIT", "1")).hasMessageContaining("INVALID_IDEMPOTENCY_KEY");
        assertThatThrownBy(() -> apply("short", "DEPOSIT", "1")).hasMessageContaining("INVALID_IDEMPOTENCY_KEY");
        assertThatThrownBy(() -> apply(key(), "CONVERSION", "1")).hasMessageContaining("INVALID_OPERATION");
        assertThatThrownBy(() -> funding.complete("fixture-a", key(), intent("DEPOSIT", "not-an-id", "1"))).hasMessageContaining("INVALID_WALLET");
        assertThatThrownBy(() -> apply(key(), "DEPOSIT", null)).hasMessageContaining("INVALID_AMOUNT");
        assertThat(count("idempotency_record")).isZero();
    }

    Object[] concurrent(Callable<?> first, Callable<?> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Object> a = () -> { gate.await(); try { return first.call(); } catch (ResponseStatusException e) { return e; } };
            Callable<Object> b = () -> { gate.await(); try { return second.call(); } catch (ResponseStatusException e) { return e; } };
            var left = executor.submit(a); var right = executor.submit(b); gate.countDown();
            return new Object[]{left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS)};
        }
    }
}
