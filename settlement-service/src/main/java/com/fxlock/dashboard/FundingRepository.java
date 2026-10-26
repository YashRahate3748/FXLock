package com.fxlock.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** Funding history is insert/read only through the app; schema-owner SQL is not restricted here. */
@Repository
public class FundingRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public FundingRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public Optional<FundingResult> begin(String key, String hash, long customerId) {
        try {
            one(jdbc.update("""
                    INSERT INTO idempotency_record (idempotency_key, request_hash, operation_type)
                    VALUES (?, ?, 'SIMULATED_FUNDING')
                    """, key, hash));
            return Optional.empty();
        } catch (DuplicateKeyException duplicate) {
            var hashes = jdbc.query("SELECT request_hash FROM idempotency_record WHERE idempotency_key = ?",
                    (rs, n) -> rs.getString(1), key);
            if (hashes.size() != 1 || !hash.equals(hashes.getFirst())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            }
            return Optional.of(find(key, customerId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "FUNDING_PENDING")));
        }
    }

    public Optional<FundingResult> find(String key, long customerId) {
        return jdbc.query("""
                SELECT * FROM wallet_funding WHERE idempotency_key = ? AND customer_id = ?
                """, (rs, n) -> new FundingResult(rs.getString("transaction_ref"), rs.getString("operation"),
                        rs.getBigDecimal("wallet_id").toBigIntegerExact().toString(), rs.getString("currency_code"),
                        rs.getBigDecimal("amount").toPlainString(), rs.getBigDecimal("balance_before").toPlainString(),
                        rs.getBigDecimal("balance_after").toPlainString(),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getString("idempotency_key"),
                        rs.getString("correlation_id")), key, customerId).stream().findFirst();
    }

    public FundingResult record(long customerId, long walletId, String operation, String currency,
            BigDecimal amount, BigDecimal before, BigDecimal after, String key) {
        String ref = UUID.randomUUID().toString();
        String correlation = UUID.randomUUID().toString();
        one(jdbc.update("""
                INSERT INTO wallet_funding (transaction_ref, customer_id, wallet_id, operation, currency_code,
                    amount, balance_before, balance_after, idempotency_key, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ref, customerId, walletId, operation, currency, amount, before, after, key, correlation));
        FundingResult result = find(key, customerId).orElseThrow();
        String eventType = "SIMULATED_FUNDING_" + operation + "_COMPLETED";
        String eventId = UUID.randomUUID().toString();
        String payload;
        try {
            payload = json.writeValueAsString(Map.of("eventId", eventId, "eventType", eventType,
                    "schemaVersion", 1, "simulated", true, "customerId", customerId,
                    "correlationId", correlation, "funding", result));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize funding event"); }
        one(jdbc.update("""
                INSERT INTO audit_event (event_type, entity_type, entity_id, actor_type, actor_id,
                    event_status, details, correlation_id)
                VALUES (?, 'WALLET_FUNDING', ?, 'CUSTOMER', ?, 'SUCCESS', ?, ?)
                """, eventType, ref, Long.toString(customerId), payload, correlation));
        one(jdbc.update("""
                INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id, event_type,
                    topic_name, payload, status, correlation_id)
                VALUES (?, 'WALLET_FUNDING', ?, ?, 'fx-simulated-funding-completed', ?, 'PENDING', ?)
                """, eventId, ref, eventType, payload, correlation));
        one(jdbc.update("""
                UPDATE idempotency_record SET response_status = 'COMPLETED', response_code = 'FUNDING_COMPLETED',
                    updated_at = SYSTIMESTAMP WHERE idempotency_key = ?
                """, key));
        return result;
    }

    static void one(int rows) {
        if (rows != 1) throw new IllegalStateException("Funding write did not affect exactly one row");
    }
}
