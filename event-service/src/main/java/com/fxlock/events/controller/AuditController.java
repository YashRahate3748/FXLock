package com.fxlock.events.controller;
import com.fxlock.events.model.AuditRequest;
import com.fxlock.events.repository.AuditRepository;
import com.fxlock.events.repository.OutboxRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/operations")
public class AuditController {
 private final AuditRepository audit; private final OutboxRepository outbox;
 public AuditController(AuditRepository audit, OutboxRepository outbox) { this.audit=audit; this.outbox=outbox; }
 @PostMapping("/audit")
 public Map<String,Object> createAudit(@Valid @RequestBody AuditRequest request) {
  Number id=audit.insert(request);
  return Map.of("auditId", id, "status", "RECORDED");
 }
 @GetMapping("/audit")
 public List<Map<String,Object>> auditHistory(@RequestParam(required=false) String entityType,
   @RequestParam(required=false) String entityId, @RequestParam(defaultValue="50") int limit) {
  return audit.search(entityType, entityId, limit);
 }
 @GetMapping("/events")
 public List<Map<String,Object>> eventHistory(@RequestParam(defaultValue="50") int limit) {
  return outbox.history(limit);
 }
 @GetMapping("/health")
 public Map<String,Object> health() { return Map.of("service","event-service","status","UP"); }
}
