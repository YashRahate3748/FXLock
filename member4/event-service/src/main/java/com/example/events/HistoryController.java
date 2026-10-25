package com.example.events;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api")
public class HistoryController {
    private final JdbcTemplate jdbc;
    public HistoryController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static int limit(int n) { return Math.max(1,Math.min(200,n)); }
    @GetMapping("/audit")
    public List<Map<String,Object>> audit(@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int size,
                                         @RequestParam(required=false) String aggregateId) {
        return jdbc.queryForList("""
            SELECT event_id,event_type,aggregate_id,source,correlation_id,occurred_at,received_at,kafka_partition,kafka_offset
            FROM fx_audit WHERE (? IS NULL OR aggregate_id=?) ORDER BY received_at DESC,event_id
            OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
            """,aggregateId,aggregateId,Math.max(0,offset),limit(size));
    }
    @GetMapping("/outbox")
    public List<Map<String,Object>> outbox(@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int size,
                                          @RequestParam(required=false) String status) {
        return jdbc.queryForList("""
            SELECT event_id,topic,event_key,status,attempts,next_attempt_at,lease_until,last_error,created_at,published_at
            FROM fx_outbox WHERE (? IS NULL OR status=?) ORDER BY created_at DESC,event_id
            OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
            """,status,status,Math.max(0,offset),limit(size));
    }
    @GetMapping("/audit/{id}")
    public Map<String,String> detail(@PathVariable String id) {
        var body=jdbc.query("SELECT envelope FROM fx_audit WHERE event_id=?",(rs,n)->rs.getString(1),id);
        if(body.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return Map.of("envelope",body.getFirst());
    }
}
