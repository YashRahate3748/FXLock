package com.fxlock.quote;

import java.sql.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.fxlock.quote.Models.*;

@Repository
public class QuoteRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public QuoteRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    // Server-side training aliases. Request JSON cannot choose the customer.
    public long customerId(String principal) {
        String ref=switch(principal) { case "customer-demo" -> "CUST001"; case "customer-other" -> "CUST002"; default -> principal; };
        var ids=jdbc.queryForList("SELECT CUSTOMER_ID FROM CUSTOMER WHERE CUSTOMER_REF=? AND STATUS='ACTIVE'",Long.class,ref);
        if (ids.size()!=1) throw new QuoteException(403,"CUSTOMER_UNAVAILABLE","No active customer is mapped to this login.");
        return ids.getFirst();
    }
    public List<WalletOption> wallets(String principal) {
        return jdbc.query("""
            SELECT WALLET_ID,CURRENCY_CODE FROM WALLET
            WHERE CUSTOMER_ID=? AND STATUS='ACTIVE' ORDER BY WALLET_ID
            """,(rs,n) -> new WalletOption(rs.getString("WALLET_ID"),"My "+rs.getString("CURRENCY_CODE")+" wallet",
                rs.getString("CURRENCY_CODE"),"EUR".equals(rs.getString("CURRENCY_CODE"))),customerId(principal));
    }
    public void insert(Quote q) {
        jdbc.update("""
            INSERT INTO FX_QUOTE (QUOTE_ID,CUSTOMER_ID,SOURCE_WALLET_ID,TARGET_WALLET_ID,
            RATE_SNAPSHOT_ID,SOURCE_CURRENCY,TARGET_CURRENCY,SOURCE_AMOUNT,MARKET_RATE,CUSTOMER_RATE,
            APPLIED_SPREAD_BPS,TARGET_AMOUNT,STATUS,CREATED_AT,EXPIRES_AT,CORRELATION_ID)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,q.quoteId(),Long.valueOf(q.customerId()),Long.valueOf(q.sourceWalletId()),Long.valueOf(q.targetWalletId()),
            Long.valueOf(q.snapshotId()),q.sourceCurrency(),q.targetCurrency(),q.sourceAmount(),q.marketRate(),q.offeredRate(),
            q.spreadBps(),q.targetAmount(),q.status(),utc(q.createdAt()),utc(q.expiresAt()),q.quoteId());
        var c=q.confidence();
        jdbc.update("""
            INSERT INTO QUOTE_CONFIDENCE (QUOTE_ID,FRESHNESS_SCORE,ANOMALY_SCORE,PROVIDER_HEALTH_SCORE,
            STABILITY_SCORE,TOTAL_SCORE,CONFIDENCE_LEVEL,CALCULATION_DETAILS,CREATED_AT)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,q.quoteId(),c.freshnessScore(),c.anomalyScore(),c.providerHealthScore(),c.stabilityScore(),
            c.score(),c.label(),encode(c.reasons()),utc(q.createdAt()));
    }
    private static final String SELECT="""
        SELECT Q.*,R.PROVIDER_TIMESTAMP,R.RECEIVED_AT,C.FRESHNESS_SCORE,C.ANOMALY_SCORE,
        C.PROVIDER_HEALTH_SCORE,C.STABILITY_SCORE,C.TOTAL_SCORE,C.CONFIDENCE_LEVEL,C.CALCULATION_DETAILS
        FROM FX_QUOTE Q JOIN MARKET_RATE_SNAPSHOT R ON R.RATE_SNAPSHOT_ID=Q.RATE_SNAPSHOT_ID
        JOIN QUOTE_CONFIDENCE C ON C.QUOTE_ID=Q.QUOTE_ID
        WHERE Q.QUOTE_ID=? AND Q.CUSTOMER_ID=?
        """;
    public Optional<Quote> owned(String id,String customer) {
        return jdbc.query(SELECT,this::map,id,customerId(customer)).stream().findFirst();
    }
    public Optional<Quote> lockOwned(String id,String customer) {
        long customerId=customerId(customer);
        var ids=jdbc.queryForList("SELECT QUOTE_ID FROM FX_QUOTE WHERE QUOTE_ID=? AND CUSTOMER_ID=? FOR UPDATE",String.class,id,customerId);
        return ids.isEmpty()?Optional.empty():jdbc.query(SELECT,this::map,id,customerId).stream().findFirst();
    }
    public void cancel(String id,Instant now) {
        jdbc.update("UPDATE FX_QUOTE SET STATUS='CANCELLED',CANCELLED_AT=? WHERE QUOTE_ID=? AND STATUS='OPEN'",utc(now),id);
    }
    public void audit(Quote q,String action,Instant now) {
        jdbc.update("""
            INSERT INTO AUDIT_EVENT (EVENT_TYPE,ENTITY_TYPE,ENTITY_ID,ACTOR_TYPE,ACTOR_ID,EVENT_STATUS,
            DETAILS,CORRELATION_ID,CREATED_AT) VALUES (?,'QUOTE',?,'CUSTOMER',?,'SUCCESS',?,?,?)
            """,action,q.quoteId(),q.customerId(),encode(Map.of("quoteId",q.quoteId(),"eventType",action)),q.quoteId(),utc(now));
        String eventId=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO OUTBOX_EVENT (EVENT_ID,AGGREGATE_TYPE,AGGREGATE_ID,EVENT_TYPE,TOPIC_NAME,
            PAYLOAD,STATUS,CORRELATION_ID,CREATED_AT) VALUES (?,'QUOTE',?,?,?,?,'PENDING',?,?)
            """,eventId,q.quoteId(),action,"fx-quote-events",encode(Map.of("eventId",eventId,"eventType",action,
            "schemaVersion",1,"occurredAt",now.toString(),"aggregateType","QUOTE","aggregateId",q.quoteId(),
            "correlationId",q.quoteId(),"data",Map.of("customerId",q.customerId(),"quoteId",q.quoteId()))),q.quoteId(),utc(now));
    }
    public Instant settlementCompletedAt(String id,String quoteId,String customer) {
        return jdbc.query("""
            SELECT COMPLETED_AT FROM SETTLEMENT WHERE SETTLEMENT_ID=? AND QUOTE_ID=?
            AND CUSTOMER_ID=? AND STATUS='COMPLETED'
            """,(rs,n) -> instant(rs,"COMPLETED_AT"),id,quoteId,customerId(customer)).stream().findFirst()
            .orElseThrow(() -> new QuoteException(502,"SETTLEMENT_RESPONSE_INVALID","No matching committed settlement exists in Oracle."));
    }
    private Quote map(ResultSet rs,int row) throws SQLException {
        var confidence=new Confidence(rs.getBigDecimal("TOTAL_SCORE"),rs.getString("CONFIDENCE_LEVEL"),
            reasons(rs.getString("CALCULATION_DETAILS")),rs.getBigDecimal("FRESHNESS_SCORE"),rs.getBigDecimal("ANOMALY_SCORE"),
            rs.getBigDecimal("PROVIDER_HEALTH_SCORE"),rs.getBigDecimal("STABILITY_SCORE"));
        var observed=rs.getObject("PROVIDER_TIMESTAMP",OffsetDateTime.class);
        return new Quote(rs.getString("QUOTE_ID"),rs.getString("CUSTOMER_ID"),rs.getString("SOURCE_WALLET_ID"),rs.getString("TARGET_WALLET_ID"),
            rs.getString("SOURCE_CURRENCY"),rs.getString("TARGET_CURRENCY"),rs.getString("RATE_SNAPSHOT_ID"),
            observed==null?instant(rs,"RECEIVED_AT"):observed.toInstant(),rs.getBigDecimal("MARKET_RATE"),rs.getBigDecimal("CUSTOMER_RATE").setScale(10),
            rs.getBigDecimal("SOURCE_AMOUNT").setScale(2),rs.getBigDecimal("TARGET_AMOUNT").setScale(2),rs.getBigDecimal("APPLIED_SPREAD_BPS"),confidence,
            instant(rs,"CREATED_AT"),instant(rs,"EXPIRES_AT"),rs.getString("STATUS"));
    }
    private static OffsetDateTime utc(Instant time) { return time.atOffset(ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs,String column) throws SQLException {
        var value=rs.getObject(column,OffsetDateTime.class);
        if(value==null) throw new SQLException("Missing timestamp: "+column);
        return value.toInstant();
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("Cannot serialize quote event",e); } }
    private List<String> reasons(String value) {
        try { return json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}); }
        catch(Exception e) { throw new IllegalStateException("Cannot read stored confidence details",e); }
    }
}
