package com.fxlock.quote;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fxlock.quote.Models.*;

/** Opt-in: runs against the exact common Oracle DDL. Each test rolls back its fixture and quote writes. */
@EnabledIfSystemProperty(named="runOracle",matches="true")
@SpringBootTest @ActiveProfiles("oracle-local") @Transactional
class OracleSchemaTest {
    @Autowired QuoteService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired DatabaseConfig config;
    @MockitoBean Clock clock;
    @MockitoSpyBean QuoteRepository repository;
    Instant now;
    long snapshot;
    String source,target;
    @BeforeEach void setup() {
        now=Instant.now().plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.MICROS); when(clock.instant()).thenReturn(now);
        source=jdbc.queryForObject("SELECT W.WALLET_ID FROM WALLET W JOIN CUSTOMER C ON C.CUSTOMER_ID=W.CUSTOMER_ID WHERE C.CUSTOMER_REF='CUST001' AND W.CURRENCY_CODE='EUR'",String.class);
        target=jdbc.queryForObject("SELECT W.WALLET_ID FROM WALLET W JOIN CUSTOMER C ON C.CUSTOMER_ID=W.CUSTOMER_ID WHERE C.CUSTOMER_REF='CUST001' AND W.CURRENCY_CODE='USD'",String.class);
        var keys=new GeneratedKeyHolder();
        jdbc.update(c -> {
            var ps=c.prepareStatement("""
                INSERT INTO MARKET_RATE_SNAPSHOT (CURRENCY_PAIR,PROVIDER_NAME,MARKET_RATE,PROVIDER_TIMESTAMP,
                RECEIVED_AT,VALIDATION_STATUS,TRUSTED_FLAG,MOVEMENT_PERCENT) VALUES ('EUR/USD','M2_ORACLE_TEST',1.17,?,?,'TRUSTED','Y',0.02)
                """,new String[]{"RATE_SNAPSHOT_ID"});
            ps.setObject(1,now.minusSeconds(5).atOffset(ZoneOffset.UTC)); ps.setObject(2,now.atOffset(ZoneOffset.UTC)); return ps;
        },keys);
        snapshot=keys.getKey().longValue();
    }
    Quote create() { return service.create(new CreateQuoteRequest("EUR","USD",new BigDecimal("1000.00"),source,target),"customer-demo"); }
    @Test void exactSharedTablesAndNumericForeignKeysExist() {
        var tables=jdbc.queryForList("SELECT TABLE_NAME FROM USER_TABLES",String.class);
        assertThat(tables).containsExactlyInAnyOrder("CUSTOMER","WALLET","FX_CONFIG","MARKET_RATE_SNAPSHOT","RATE_ANOMALY",
            "FX_QUOTE","QUOTE_CONFIDENCE","SETTLEMENT","WALLET_LEDGER","IDEMPOTENCY_RECORD","AUDIT_EVENT","OUTBOX_EVENT","PROCESSED_EVENT","TRANSACTION_HISTORY");
        assertThat(jdbc.queryForObject("SELECT DATA_TYPE FROM USER_TAB_COLUMNS WHERE TABLE_NAME='FX_QUOTE' AND COLUMN_NAME='CUSTOMER_ID'",String.class)).isEqualTo("NUMBER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE TABLE_NAME='FX_QUOTE' AND CONSTRAINT_TYPE='R' AND STATUS='ENABLED'",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT UNIQUENESS FROM USER_INDEXES WHERE INDEX_NAME='UQ_ONE_COMPLETED_SETTLEMENT'",String.class)).isEqualTo("UNIQUE");
    }
    @Test void savesQuoteConfidenceAuditAndOutboxInCommonTables() {
        var q=create();
        assertThat(q.snapshotId()).isEqualTo(Long.toString(snapshot));
        assertThat(service.get(q.quoteId(),"customer-demo").targetAmount()).isEqualByComparingTo("1167.66");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM QUOTE_CONFIDENCE WHERE QUOTE_ID=? AND CONFIDENCE_LEVEL='HIGH'",Integer.class,q.quoteId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENT WHERE ENTITY_ID=? AND EVENT_TYPE='QUOTE_CREATED'",Integer.class,q.quoteId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM OUTBOX_EVENT WHERE AGGREGATE_ID=? AND STATUS='PENDING' AND PAYLOAD IS JSON",Integer.class,q.quoteId())).isEqualTo(1);
        assertThat(q.confidence().score()).isEqualByComparingTo(q.confidence().freshnessScore().multiply(new BigDecimal("0.4"))
            .add(q.confidence().anomalyScore().multiply(new BigDecimal("0.2"))).add(q.confidence().providerHealthScore().multiply(new BigDecimal("0.2")))
            .add(q.confidence().stabilityScore().multiply(new BigDecimal("0.2"))).setScale(2,java.math.RoundingMode.HALF_EVEN));
    }
    @Test void commonConfigurationControlsPricingAndLifetime() {
        jdbc.update("UPDATE FX_CONFIG SET BUSINESS_SPREAD_BPS=30,QUOTE_VALIDITY_SECONDS=45 WHERE CURRENCY_PAIR='EUR/USD'");
        var q=create(); assertThat(q.offeredRate()).isEqualByComparingTo("1.16649");
        assertThat(q.expiresAt()).isEqualTo(now.plusSeconds(45));
    }
    @Test void sharedPauseRejectsNewQuotes() {
        jdbc.update("UPDATE FX_CONFIG SET QUOTING_ENABLED='N' WHERE CURRENCY_PAIR='EUR/USD'");
        assertThatThrownBy(this::create).isInstanceOfSatisfying(QuoteException.class,e -> assertThat(e.code()).isEqualTo("RATE_UNAVAILABLE"));
    }
    @Test void cancellationUpdatesTheSameM3RowAndTimestamp() {
        var q=create(); service.cancel(q.quoteId(),"customer-demo");
        assertThat(jdbc.queryForObject("SELECT STATUS FROM FX_QUOTE WHERE QUOTE_ID=?",String.class,q.quoteId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT CANCELLED_AT FROM FX_QUOTE WHERE QUOTE_ID=?",OffsetDateTime.class,q.quoteId()).toInstant()).isEqualTo(now);
    }
    @Test void customerCannotUseAnotherCustomersNumericWallet() {
        var other=jdbc.queryForObject("SELECT W.WALLET_ID FROM WALLET W JOIN CUSTOMER C ON C.CUSTOMER_ID=W.CUSTOMER_ID WHERE C.CUSTOMER_REF='CUST002' AND W.CURRENCY_CODE='EUR'",String.class);
        assertThatThrownBy(() -> service.create(new CreateQuoteRequest("EUR","USD",BigDecimal.TEN,other,target),"customer-demo"))
            .isInstanceOfSatisfying(QuoteException.class,e -> assertThat(e.code()).isEqualTo("INVALID_WALLETS"));
    }
    @Test void storedSnapshotExpiresWithoutTimestampRefresh() {
        when(clock.instant()).thenReturn(now.plusSeconds(90));
        assertThatThrownBy(this::create).isInstanceOfSatisfying(QuoteException.class,e -> assertThat(e.code()).isEqualTo("RATE_STALE"));
        assertThat(jdbc.queryForObject("SELECT PROVIDER_TIMESTAMP FROM MARKET_RATE_SNAPSHOT WHERE RATE_SNAPSHOT_ID=?",OffsetDateTime.class,snapshot).toInstant()).isEqualTo(now.minusSeconds(5));
    }
    @Test void foreignKeyRejectsAQuoteWithInventedSnapshotId() {
        var q=create();
        assertThatThrownBy(() -> jdbc.update("UPDATE FX_QUOTE SET RATE_SNAPSHOT_ID=-123 WHERE QUOTE_ID=?",q.quoteId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void lateAuditFailureRollsBackAllFourQuoteWritesInOracle() {
        var id=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(invocation -> {
            id.set(((Quote)invocation.getArgument(0)).quoteId());
            invocation.callRealMethod();
            throw new IllegalStateException("Injected failure after audit and outbox inserts");
        }).when(repository).audit(any(),eq("QUOTE_CREATED"),any());
        try {
            assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FX_QUOTE WHERE QUOTE_ID=?",Integer.class,id.get())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM QUOTE_CONFIDENCE WHERE QUOTE_ID=?",Integer.class,id.get())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENT WHERE ENTITY_ID=?",Integer.class,id.get())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM OUTBOX_EVENT WHERE AGGREGATE_ID=?",Integer.class,id.get())).isZero();
        } finally {
            jdbc.update("DELETE FROM MARKET_RATE_SNAPSHOT WHERE RATE_SNAPSHOT_ID=?",snapshot);
        }
    }
}
