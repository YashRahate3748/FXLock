package com.example.contract;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Instantiate in EACH producer service with its local JdbcTemplate and ObjectMapper.
 * Call inside the SAME local database transaction as the business update. */
public class OutboxAppender {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public OutboxAppender(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    public void append(EventEnvelope event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox append requires a business transaction");
        String body = mapper.writeValueAsString(event);
        if (body.length() > 200_000) throw new IllegalArgumentException("Event exceeds 200000 characters");
        jdbc.update("""
            INSERT INTO fx_outbox(event_id,topic,event_key,envelope,status,attempts,next_attempt_at,created_at)
            VALUES(?,?,?,?,'PENDING',0,SYSTIMESTAMP,SYSTIMESTAMP)
            """, event.eventId().toString(), event.eventType(), event.aggregateId(), body);
    }
}
