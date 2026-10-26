package com.fxlock.events;
import java.util.*;
import java.sql.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/operations")
public class OperationsController {
 private final JdbcTemplate jdbc;
 public OperationsController(JdbcTemplate jdbc) {this.jdbc=jdbc;}
 @GetMapping("/audit")
 public List<Map<String,Object>> audit(@RequestParam(defaultValue="100") int limit,
   @RequestParam(required=false) String entityType,@RequestParam(required=false) String entityId) {
  return jdbc.query("""
   SELECT AUDIT_ID,EVENT_TYPE,ENTITY_TYPE,ENTITY_ID,ACTOR_TYPE,ACTOR_ID,EVENT_STATUS,DETAILS,CORRELATION_ID,CREATED_AT
   FROM AUDIT_EVENT WHERE (? IS NULL OR ENTITY_TYPE=?) AND (? IS NULL OR ENTITY_ID=?)
   ORDER BY AUDIT_ID DESC FETCH FIRST ? ROWS ONLY
   """,(rs,n)->map(rs),entityType,entityType,entityId,entityId,limit(limit));
 }
 @GetMapping("/outbox")
 public List<Map<String,Object>> outbox(@RequestParam(defaultValue="100") int limit) {
  return jdbc.query("""
   SELECT EVENT_ID,AGGREGATE_TYPE,AGGREGATE_ID,EVENT_TYPE,TOPIC_NAME,STATUS,RETRY_COUNT,
   CREATED_AT,PUBLISHED_AT,LAST_ATTEMPT_AT,LAST_ERROR,CORRELATION_ID
   FROM OUTBOX_EVENT ORDER BY CREATED_AT DESC,EVENT_ID FETCH FIRST ? ROWS ONLY
   """,(rs,n)->map(rs),limit(limit));
 }
 @GetMapping("/summary") public List<Map<String,Object>> summary() {
  return jdbc.query("SELECT STATUS,COUNT(*) AS EVENT_COUNT FROM OUTBOX_EVENT GROUP BY STATUS",(rs,n)->map(rs));
 }
 private static int limit(int n) {return Math.max(1,Math.min(n,500));}
 private static Map<String,Object> map(ResultSet rs) throws SQLException {
  Map<String,Object> m=new LinkedHashMap<>();var md=rs.getMetaData();
  for(int i=1;i<=md.getColumnCount();i++) {
   Object value=rs.getObject(i);
   if(value instanceof Clob clob) value=clob.getSubString(1,(int)clob.length());
   else if(value!=null && (md.getColumnType(i)==Types.TIMESTAMP_WITH_TIMEZONE || md.getColumnType(i)==-101))
    value=rs.getObject(i,java.time.OffsetDateTime.class).toString();
   m.put(md.getColumnLabel(i),value);
  } return m;
 }
}
