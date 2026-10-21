package com.fxlock.events.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxlock.events.model.EventEnvelope;
import com.fxlock.events.repository.OutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
@Component
public class OutboxPublisher {
 private final OutboxRepository outbox; private final KafkaTemplate<String,String> kafka; private final ObjectMapper mapper;
 @Value("${fxlock.outbox.batch-size:25}") private int batchSize;
 public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String,String> kafka, ObjectMapper mapper) { this.outbox=outbox; this.kafka=kafka; this.mapper=mapper; }
 @Scheduled(fixedDelayString="${fxlock.outbox.poll-ms:1000}")
 public void publishPending() {
  for (Map<String,Object> row : outbox.claimAndMark(batchSize)) {
   String id=String.valueOf(row.get("EVENT_ID"));
   try {
    String payload=String.valueOf(row.get("PAYLOAD"));
    // Keep existing payload if already a full event envelope; otherwise wrap it.
    JsonNode node=mapper.readTree(payload);
    String envelope;
    if (node.hasNonNull("eventId") && node.hasNonNull("eventType")) envelope=payload;
    else envelope=mapper.writeValueAsString(new EventEnvelope(id, String.valueOf(row.get("EVENT_TYPE")),
       "UNKNOWN", id, Instant.now(), stringOrNull(row.get("CORRELATION_ID")), node));
    kafka.send(String.valueOf(row.get("TOPIC_NAME")), id, envelope).get();
    // Mark published only after Kafka acknowledges the send.
    outbox.markPublished(id);
   } catch (Exception ex) {
    outbox.markFailed(id, ex.getMessage());
   }
  }
 }
 private String stringOrNull(Object o) { return o == null ? null : String.valueOf(o); }
}
