package com.fxlock.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

public class EventReliabilityTest {
 JdbcTemplate jdbc;TransactionTemplate tx;ObjectMapper json=new ObjectMapper();
 KafkaTemplate<String,String> kafka;OutboxPublisher publisher;AuditConsumer consumer;
 @BeforeEach @SuppressWarnings("unchecked") void setup() {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=Oracle;DB_CLOSE_DELAY=-1","sa","");
  jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  // H2 function for the Oracle retry interval; real SKIP LOCKED coverage is a separate Oracle test.
  jdbc.execute("CREATE ALIAS NUMTODSINTERVAL FOR 'com.fxlock.events.EventReliabilityTest.interval'");
  jdbc.execute("""
   CREATE TABLE OUTBOX_EVENT (EVENT_ID VARCHAR2(36) PRIMARY KEY,AGGREGATE_TYPE VARCHAR2(100),
   AGGREGATE_ID VARCHAR2(100),EVENT_TYPE VARCHAR2(100),TOPIC_NAME VARCHAR2(200),PAYLOAD CLOB,
   CORRELATION_ID VARCHAR2(100),CREATED_AT TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   STATUS VARCHAR2(20) DEFAULT 'PENDING',RETRY_COUNT NUMBER DEFAULT 0,
   LAST_ATTEMPT_AT TIMESTAMP WITH TIME ZONE,PUBLISHED_AT TIMESTAMP WITH TIME ZONE,LAST_ERROR VARCHAR2(2000))
   """);
  jdbc.execute("CREATE TABLE PROCESSED_EVENT (EVENT_ID VARCHAR2(36),CONSUMER_NAME VARCHAR2(100),PRIMARY KEY(EVENT_ID,CONSUMER_NAME))");
  jdbc.execute("CREATE TABLE AUDIT_EVENT (EVENT_TYPE VARCHAR2(100),ENTITY_TYPE VARCHAR2(100),ENTITY_ID VARCHAR2(100),ACTOR_TYPE VARCHAR2(50),ACTOR_ID VARCHAR2(100),EVENT_STATUS VARCHAR2(30),DETAILS CLOB,CORRELATION_ID VARCHAR2(100))");
  kafka=mock(KafkaTemplate.class);publisher=new OutboxPublisher(jdbc,tx,kafka,new EventEnvelope(json),0,Set.of("fx-rate-updated"));
  consumer=new AuditConsumer(jdbc,json);
 }
public static java.time.Duration interval(int seconds, String unit) {
    return java.time.Duration.ofSeconds(seconds);
}
 String insert() {
  String id=UUID.randomUUID().toString();
  jdbc.update("INSERT INTO OUTBOX_EVENT (EVENT_ID,AGGREGATE_TYPE,AGGREGATE_ID,EVENT_TYPE,TOPIC_NAME,PAYLOAD) VALUES (?,'RATE','17','RATE_UPDATED','fx-rate-events','{\"marketRate\":1.17}')",id);
  return id;
 }
 @Test void brokerFailureThenRecovery() {
  String id=insert();
  when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("offline")));
  assertThat(publisher.publishOne()).isTrue();
  assertThat(jdbc.queryForObject("SELECT STATUS FROM OUTBOX_EVENT",String.class)).isEqualTo("FAILED");
  assertThat(jdbc.queryForObject("SELECT RETRY_COUNT FROM OUTBOX_EVENT",Integer.class)).isEqualTo(1);
  when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
  publisher.publishOne();
  assertThat(jdbc.queryForObject("SELECT STATUS FROM OUTBOX_EVENT WHERE EVENT_ID=?",String.class,id)).isEqualTo("PUBLISHED");
  verify(kafka,times(2)).send(eq("fx-rate-updated"),eq("17"),anyString());
 }
 String message(String id) throws Exception {
  return new EventEnvelope(json).encode(new OutboxRow(id,"RATE","17","RATE_UPDATED","fx-rate-events","{\"rate\":1.17}",null,OffsetDateTime.now()));
 }
 void consume(String message) {
  tx.executeWithoutResult(s -> { try {consumer.accept(message);} catch(Exception e) {throw new RuntimeException(e);} });
 }
 @Test void duplicateDeliveryCreatesOneReceipt() throws Exception {
  String msg=message(UUID.randomUUID().toString());consume(msg);consume(msg);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENT",Integer.class)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PROCESSED_EVENT",Integer.class)).isEqualTo(1);
 }
 @Test void auditFailureRollsBackDedupSoReplayCanSucceed() throws Exception {
  String msg=message(UUID.randomUUID().toString());
  jdbc.execute("ALTER TABLE AUDIT_EVENT ADD CONSTRAINT REJECT_AUDIT CHECK(EVENT_STATUS <> 'CONSUMED')");
  assertThatThrownBy(()->consume(msg)).isInstanceOf(RuntimeException.class);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PROCESSED_EVENT",Integer.class)).isZero();
  jdbc.execute("ALTER TABLE AUDIT_EVENT DROP CONSTRAINT REJECT_AUDIT");consume(msg);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENT",Integer.class)).isEqualTo(1);
 }
 @Test void publisherCrashAfterAckReplaysSameId() throws Exception {
  String id=insert();
  when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
  jdbc.execute("ALTER TABLE OUTBOX_EVENT ADD CONSTRAINT REJECT_MARK CHECK(STATUS <> 'PUBLISHED')");
  assertThatThrownBy(()->publisher.publishOne()).isInstanceOf(RuntimeException.class);
  assertThat(jdbc.queryForObject("SELECT STATUS FROM OUTBOX_EVENT",String.class)).isEqualTo("PENDING");
  jdbc.execute("ALTER TABLE OUTBOX_EVENT DROP CONSTRAINT REJECT_MARK");publisher.publishOne();
  var captor=org.mockito.ArgumentCaptor.forClass(String.class);
  verify(kafka,times(2)).send(eq("fx-rate-updated"),eq("17"),captor.capture());
  assertThat(captor.getAllValues().get(0)).isEqualTo(captor.getAllValues().get(1));
  assertThat(json.readTree(captor.getValue()).path("eventId").asText()).isEqualTo(id);
 }
 @Test void legacyPayloadIsPreserved() throws Exception {
  var e=json.readTree(message(UUID.randomUUID().toString()));
  assertThat(e.path("data").path("rate").decimalValue()).isEqualByComparingTo("1.17");
 }
}
