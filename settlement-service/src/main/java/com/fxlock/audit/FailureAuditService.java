package com.fxlock.audit;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class FailureAuditService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FailureAuditService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(
            String quoteId,
            String customerRef,
            String reasonCode,
            boolean rejected,
            String correlationId
    ) {
        String details;

        try {
            details = objectMapper.writeValueAsString(
                    Map.of(
                            "quoteId", quoteId,
                            "customerRef", customerRef,
                            "reasonCode", reasonCode,
                            "attemptOutcome",
                            rejected ? "REJECTED" : "UNCONFIRMED"
                    )
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Cannot serialize failure audit",
                    exception
            );
        }

        int inserted = jdbcTemplate.update(
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
                rejected
                        ? "SETTLEMENT_ATTEMPT_REJECTED"
                        : "SETTLEMENT_ATTEMPT_ERROR",
                "QUOTE",
                quoteId,
                "SIMULATED_CUSTOMER",
                customerRef,
                rejected ? "REJECTED" : "ERROR",
                details,
                correlationId
        );

        if (inserted != 1) {
            throw new IllegalStateException(
                    "Failure audit must insert one row"
            );
        }
    }
}