package com.fxlock.events.repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
@Repository
public class OutboxRepository {
 private final JdbcTemplate jdbc;
 public OutboxRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 @Transactional
 public List<Map<String,Object>> claimAndMark(int batchSize) {
  int n=Math.max(1,Math.min(batchSize,100));
  List<Map<String,Object>> rows = jdbc.queryForList(
   "SELECT EVENT_ID,EVENT_TYPE,TOPIC_NAME,PAYLOAD,CORRELATION_ID FROM OUTBOX_EVENT WHERE STATUS='PENDING' AND ROWNUM <= ? FOR UPDATE SKIP LOCKED", n);
  for (Map<String,Object> row : rows) {
   jdbc.update("UPDATE OUTBOX_EVENT SET STATUS='PROCESSING',RETRY_COUNT=RETRY_COUNT+1,LAST_ATTEMPT_AT=SYSTIMESTAMP WHERE EVENT_ID=?", row.get("EVENT_ID"));
  }
  return rows;
 }
 public void markPublished(String id) {
  jdbc.update("UPDATE OUTBOX_EVENT SET STATUS='PUBLISHED',PUBLISHED_AT=SYSTIMESTAMP,LAST_ERROR=NULL WHERE EVENT_ID=?", id);
 }
 public void markFailed(String id, String error) {
  jdbc.update("UPDATE OUTBOX_EVENT SET STATUS='PENDING',LAST_ERROR=? WHERE EVENT_ID=?", clip(error), id);
 }
 public List<Map<String,Object>> history(int limit) {
  int n=Math.max(1,Math.min(limit,200));
  return jdbc.queryForList("SELECT EVENT_ID,AGGREGATE_TYPE,AGGREGATE_ID,EVENT_TYPE,TOPIC_NAME,STATUS,RETRY_COUNT,CREATED_AT,PUBLISHED_AT,LAST_ATTEMPT_AT,LAST_ERROR,CORRELATION_ID FROM OUTBOX_EVENT ORDER BY CREATED_AT DESC FETCH FIRST "+n+" ROWS ONLY");
 }
 private String clip(String s) { return s == null ? "Unknown error" : s.substring(0,Math.min(1900,s.length())); }
}
