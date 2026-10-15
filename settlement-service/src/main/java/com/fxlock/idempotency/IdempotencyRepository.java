package com.fxlock.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxlock.settlement.SettlementResponse;

@Repository
public class IdempotencyRepository {

    private static final String OPERATION = "SETTLE_QUOTE";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // Must run inside the settlement transaction.
    public Optional<SettlementResponse> beginOrReplay(
            String key,
            Long customerId,
            String quoteId
    ) {
        String requestHash = hashRequest(customerId, quoteId);

        try {
            // Claim the key BEFORE locking the quote or wallets.
            jdbcTemplate.update(
                    """
                    INSERT INTO idempotency_record (
                        idempotency_key,
                        request_hash,
                        operation_type,
                        response_status
                    )
                    VALUES (?, ?, ?, 'IN_PROGRESS')
                    """,
                    key,
                    requestHash,
                    OPERATION
            );

            return Optional.empty();

        } catch (DataIntegrityViolationException exception) {
            if (!isUniqueViolation(exception)) {
                throw exception;
            }
        }

        // An existing key belongs to an already committed transaction.
        StoredResult stored = jdbcTemplate.queryForObject(
                """
                SELECT request_hash,
                       operation_type,
                       response_status,
                       response_body
                FROM idempotency_record
                WHERE idempotency_key = ?
                """,
                (rs, rowNum) -> new StoredResult(
                        rs.getString("request_hash"),
                        rs.getString("operation_type"),
                        rs.getString("response_status"),
                        rs.getString("response_body")
                ),
                key
        );

        if (stored == null) {
            throw new IllegalStateException(
                    "Existing idempotency record was not found"
            );
        }

        if (!OPERATION.equals(stored.operationType())
                || !requestHash.equals(stored.requestHash())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT"
            );
        }

        if (!"COMPLETED".equals(stored.responseStatus())
                || stored.responseBody() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_RESULT_UNAVAILABLE"
            );
        }

        try {
            return Optional.of(objectMapper.readValue(
                    stored.responseBody(),
                    SettlementResponse.class
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Cannot read stored settlement response",
                    exception
            );
        }
    }

    public void complete(
            String key,
            SettlementResponse response
    ) {
        String responseJson;

        try {
            responseJson = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Cannot store settlement response",
                    exception
            );
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE idempotency_record
                SET quote_id = ?,
                    settlement_id = ?,
                    response_status = 'COMPLETED',
                    response_code = '201',
                    response_body = ?,
                    updated_at = SYSTIMESTAMP
                WHERE idempotency_key = ?
                  AND response_status = 'IN_PROGRESS'
                """,
                response.quoteId(),
                response.settlementId(),
                responseJson,
                key
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Idempotency completion must update one row"
            );
        }
    }

    private String hashRequest(Long customerId, String quoteId) {
        String requestIdentity =
                OPERATION + "\n" + customerId + "\n" + quoteId;

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(requestIdentity.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean isUniqueViolation(Throwable exception) {
        for (Throwable cause = exception;
                cause != null;
                cause = cause.getCause()) {

            if (cause instanceof SQLException sqlException
                    && sqlException.getErrorCode() == 1) {
                return true;
            }
        }

        return false;
    }

    private record StoredResult(
            String requestHash,
            String operationType,
            String responseStatus,
            String responseBody
    ) {}
}