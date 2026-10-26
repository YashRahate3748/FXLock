package com.fxlock.events;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class AuditConsumer {
 public static final String NAME="fxlock-audit-v1";
 private final JdbcTemplate jdbc; private final ObjectMapper json;
 public AuditConsumer(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}
 @KafkaListener(topics="#{'${fxlock.events.topics}'.split(',')}")
 @Transactional
 public void accept(String value) throws Exception {
  var e=json.readTree(value);
  if(e==null || !e.isObject() || e.path("schemaVersion").asInt()!=1 || !e.has("data"))
   throw new IllegalArgumentException("Unsupported envelope");
  String id=required(e,"eventId",36);UUID.fromString(id);
  String type=required(e,"eventType",100),aggregate=required(e,"aggregateType",100),entity=required(e,"aggregateId",100);
  String correlation=e.path("correlationId").isNull()?null:e.path("correlationId").asText(null);
  if(correlation!=null && correlation.length()>100) throw new IllegalArgumentException("Correlation ID too long");
  try { jdbc.update("INSERT INTO PROCESSED_EVENT (EVENT_ID,CONSUMER_NAME) VALUES (?,?)",id,NAME); }
  catch(DuplicateKeyException duplicate) { return; }
  // Receipt audit is separate from existing producer business audits. Both writes commit together.
  jdbc.update("""
   INSERT INTO AUDIT_EVENT (EVENT_TYPE,ENTITY_TYPE,ENTITY_ID,ACTOR_TYPE,ACTOR_ID,EVENT_STATUS,DETAILS,CORRELATION_ID)
   VALUES (?,?,?,'SYSTEM',?,'CONSUMED',?,?)
   """,type,aggregate,entity,NAME,value,correlation);
 }
 private static String required(com.fasterxml.jackson.databind.JsonNode e,String field,int max) {
  var n=e.get(field);
  if(n==null || !n.isTextual() || n.asText().isBlank() || n.asText().length()>max)
   throw new IllegalArgumentException("Invalid envelope field: "+field);
  return n.asText();
 }
}
