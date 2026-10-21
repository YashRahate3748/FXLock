package com.fxlock.events.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component
public class EventConsumer {
 private final JdbcTemplate jdbc; private final ObjectMapper mapper;
 @Value("${spring.application.name:event-service}") private String consumerName;
 public EventConsumer(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
 @KafkaListener(topics={"fx-rate-updated","fx-settlement-completed","fx-system-alert"}, groupId="${spring.kafka.consumer.group-id:fxlock-event-service}")
 public void consume(String message) throws Exception {
  JsonNode event=mapper.readTree(message);
  if (!event.hasNonNull("eventId")) throw new IllegalArgumentException("Event is missing eventId");
  String id=event.get("eventId").asText();
  try {
   jdbc.update("INSERT INTO PROCESSED_EVENT (EVENT_ID,CONSUMER_NAME) VALUES (?,?)", id, consumerName);
  } catch (DuplicateKeyException duplicate) {
   return; // same event delivered again; do not repeat this consumer's effect
  }
  // Add business side-effects here only after defining them with the team.
  // This consumer currently records durable deduplication and writes a processing audit.
  jdbc.update("INSERT INTO AUDIT_EVENT (EVENT_TYPE,ENTITY_TYPE,ENTITY_ID,ACTOR_TYPE,ACTOR_ID,EVENT_STATUS,DETAILS,CORRELATION_ID) VALUES (?,?,?,?,?,?,?,?)",
    "KAFKA_EVENT_CONSUMED", "EVENT", id, "SERVICE", consumerName, "SUCCESS",
    message.length() > 3500 ? message.substring(0,3500) : message,
    event.hasNonNull("correlationId") ? event.get("correlationId").asText() : null);
 }
}
