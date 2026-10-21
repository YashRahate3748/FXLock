package com.fxlock.events.repository;
import com.fxlock.events.model.AuditRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
@Repository
public class AuditRepository {
 private final JdbcTemplate jdbc;
 public AuditRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
 public Number insert(AuditRequest a) {
  KeyHolder key = new GeneratedKeyHolder();
  jdbc.update(connection -> {
   var ps = connection.prepareStatement("INSERT INTO AUDIT_EVENT (EVENT_TYPE,ENTITY_TYPE,ENTITY_ID,ACTOR_TYPE,ACTOR_ID,EVENT_STATUS,DETAILS,CORRELATION_ID) VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
   ps.setString(1,a.eventType()); ps.setString(2,a.entityType()); ps.setString(3,a.entityId());
   ps.setString(4,a.actorType()); ps.setString(5,a.actorId()); ps.setString(6,a.eventStatus());
   ps.setString(7,a.details()); ps.setString(8,a.correlationId()); return ps;
  }, key);
  return key.getKey();
 }
 public List<Map<String,Object>> search(String entityType, String entityId, int limit) {
  int safeLimit = Math.max(1, Math.min(limit, 200));
  String sql = "SELECT AUDIT_ID,EVENT_TYPE,ENTITY_TYPE,ENTITY_ID,ACTOR_TYPE,ACTOR_ID,EVENT_STATUS,DETAILS,CORRELATION_ID,CREATED_AT FROM AUDIT_EVENT WHERE (? IS NULL OR ENTITY_TYPE=?) AND (? IS NULL OR ENTITY_ID=?) ORDER BY CREATED_AT DESC FETCH FIRST " + safeLimit + " ROWS ONLY";
  return jdbc.queryForList(sql, entityType, entityType, entityId, entityId);
 }
}
