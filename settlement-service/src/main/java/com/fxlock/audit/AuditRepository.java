package com.fxlock.audit;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxlock.settlement.SettlementResponse;

@Repository
public class AuditRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditRepository(
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
        String details;

        try {
            details = objectMapper.writeValueAsString(
                    Map.of(
                            "customerId", customerId,
                            "settlement", response
                    )
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Cannot serialize settlement audit",
                    exception
            );
        }

        return jdbcTemplate.update(
                """
                INSERT INTO audit_event (
                    event_type,
                    entity_type,
                    entity_id,
                    actor_type,
                    actor_id,
                    event_status,
                    details,
                    correlation_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "SETTLEMENT_COMPLETED",
                "SETTLEMENT",
                response.settlementId(),
                "CUSTOMER",
                customerId.toString(),
                "SUCCESS",
                details,
                correlationId
        );
    }
}