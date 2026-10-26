package com.fxlock.events;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class OutboxPublisher {
 private final JdbcTemplate jdbc; private final TransactionTemplate tx;
 private final KafkaTemplate<String,String> kafka; private final EventEnvelope envelope;
 private final int retrySeconds; private final Set<String> topics;
 public OutboxPublisher(JdbcTemplate jdbc,TransactionTemplate tx,KafkaTemplate<String,String> kafka,
   EventEnvelope envelope,@Value("${fxlock.events.retry-seconds:10}") int retrySeconds,
   @Value("#{'${fxlock.events.topics}'.split(',')}") Set<String> topics) {
  this.jdbc=jdbc;this.tx=tx;this.kafka=kafka;this.envelope=envelope;this.retrySeconds=retrySeconds;this.topics=topics;
 }
 public boolean publishOne() {
  return Boolean.TRUE.equals(tx.execute(status -> {
   // Keep the Oracle lock until the broker acknowledges and the database commits.
   // No committed PROCESSING state or lease is needed; a crash releases the lock.
   OutboxRow row=jdbc.query(connection -> {
    var ps=connection.prepareStatement("""
     SELECT EVENT_ID,AGGREGATE_TYPE,AGGREGATE_ID,EVENT_TYPE,TOPIC_NAME,PAYLOAD,CORRELATION_ID,CREATED_AT
     FROM OUTBOX_EVENT WHERE STATUS IN ('PENDING','FAILED')
     AND (LAST_ATTEMPT_AT IS NULL OR LAST_ATTEMPT_AT <= SYSTIMESTAMP - NUMTODSINTERVAL(?, 'SECOND'))
     ORDER BY CREATED_AT,EVENT_ID FOR UPDATE SKIP LOCKED
     """);ps.setInt(1,retrySeconds);ps.setFetchSize(1);return ps;
   },rs -> rs.next()?new OutboxRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
      rs.getString(5),rs.getString(6),rs.getString(7),rs.getObject(8,OffsetDateTime.class)):null);
   if(row==null) return false;
   try {
    String topic=EventEnvelope.topic(row.topic());
    if(!topics.contains(topic)) throw new IllegalArgumentException("Topic is outside the configured event contract");
    kafka.send(topic,row.aggregateId(),envelope.encode(row)).get(12,TimeUnit.SECONDS);
   } catch(Exception failure) {
    if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
    // Store exception type only: broker/JSON exceptions may contain business payloads.
    jdbc.update("""
     UPDATE OUTBOX_EVENT SET STATUS='FAILED',RETRY_COUNT=RETRY_COUNT+1,
     LAST_ATTEMPT_AT=SYSTIMESTAMP,LAST_ERROR=? WHERE EVENT_ID=?
     """,failure.getClass().getSimpleName(),row.id());
    return true;
   }
   // Database failure after Kafka ACK propagates and rolls back. Replay is intentional.
   jdbc.update("""
    UPDATE OUTBOX_EVENT SET STATUS='PUBLISHED',PUBLISHED_AT=SYSTIMESTAMP,
    LAST_ATTEMPT_AT=SYSTIMESTAMP,LAST_ERROR=NULL WHERE EVENT_ID=?
    """,row.id());
   return true;
  }));
 }
}
