package com.fxlock.events;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
@Component
public class EventEnvelope {
 private final ObjectMapper json;
 public EventEnvelope(ObjectMapper json) { this.json=json; }
 public String encode(OutboxRow row) throws Exception {
  var original=json.readTree(row.payload());
  if(original==null || !original.isObject()) throw new IllegalArgumentException("Payload must be a JSON object");
  ObjectNode event=json.createObjectNode();
  event.put("schemaVersion",1);event.put("eventId",row.id());event.put("eventType",row.eventType());
  event.put("aggregateType",row.aggregateType());event.put("aggregateId",row.aggregateId());
  event.put("occurredAt",row.createdAt().toInstant().toString());event.put("correlationId",row.correlationId());
  // Preserve every producer field, including legacy M1 and funding payloads.
  event.set("data",original);
  return json.writeValueAsString(event);
 }
 public static String topic(String stored) {
  return switch(stored) { case "fx-rate-events" -> "fx-rate-updated";
   case "fx-system-events" -> "fx-system-alert"; default -> stored; };
 }
}
