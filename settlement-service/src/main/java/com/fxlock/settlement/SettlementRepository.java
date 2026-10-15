package com.fxlock.settlement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fxlock.quote.QuoteSnapshot;

@Repository
public class SettlementRepository {

    private final JdbcTemplate jdbcTemplate;

    public SettlementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertCompleted(
            String settlementId,
            QuoteSnapshot quote,
            String correlationId
    ) {
        String sql = """
                INSERT INTO settlement (
                    settlement_id,
                    quote_id,
                    customer_id,
                    source_wallet_id,
                    target_wallet_id,
                    source_amount,
                    target_amount,
                    customer_rate,
                    status,
                    completed_at,
                    correlation_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'COMPLETED',
                        SYSTIMESTAMP, ?)
                """;

        return jdbcTemplate.update(
                sql,
                settlementId,
                quote.quoteId(),
                quote.customerId(),
                quote.sourceWalletId(),
                quote.targetWalletId(),
                quote.sourceAmount(),
                quote.targetAmount(),
                quote.customerRate(),
                correlationId
        );
    }
    
    public java.util.Optional<Long> findActiveCustomerIdByRef(String customerRef) {
        return jdbcTemplate.query(
                """
                SELECT customer_id
                FROM customer
                WHERE customer_ref = ?
                  AND status = 'ACTIVE'
                """,
                (rs, rowNum) -> rs.getLong("customer_id"),
                customerRef
        ).stream().findFirst();
    }

    public java.time.OffsetDateTime currentDatabaseTime() {
        return jdbcTemplate.queryForObject(
                "SELECT SYSTIMESTAMP FROM dual",
                java.time.OffsetDateTime.class
        );
    }
}