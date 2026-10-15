package com.fxlock.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxlock.settlement.SettlementResponse;

@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public int insertSettlementCompleted(
            SettlementResponse response,
            Long customerId,
            String correlationId
    ) {
        String eventId = UUID.randomUUID().toString();
        String payload;

        try {
            payload = objectMapper.writeValueAsString(
                    Map.of(
                            "eventId", eventId,
                            "eventType", "SETTLEMENT_COMPLETED",
                            "schemaVersion", 1,
                            "occurredAt", Instant.now().toString(),
                            "aggregateType", "SETTLEMENT",
                            "aggregateId", response.settlementId(),
                            "correlationId", correlationId,
                            "data", Map.of(
                                    "customerId", customerId,
                                    "settlement", response
                            )
                    )
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Cannot serialize settlement event",
                    exception
            );
        }

        return jdbcTemplate.update(
                """
                INSERT INTO outbox_event (
                    event_id,
                    aggregate_type,
                    aggregate_id,
                    event_type,
                    topic_name,
                    payload,
                    status,
                    correlation_id
                )
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """,
                eventId,
                "SETTLEMENT",
                response.settlementId(),
                "SETTLEMENT_COMPLETED",
                "fx-settlement-completed",
                payload,
                correlationId
        );
    }
}