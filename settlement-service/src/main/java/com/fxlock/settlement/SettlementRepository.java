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
    
    public java.util.Optional<SettlementResponse> findOwnedById(
            String settlementId,
            String customerRef
    ) {
        String sql = """
                SELECT s.settlement_id,
                       s.quote_id,
                       s.status,
                       q.source_currency,
                       s.source_amount,
                       q.target_currency,
                       s.target_amount
                FROM settlement s
                JOIN customer c ON (c.customer_id=s.customer_id OR EXISTS (
                    SELECT 1 FROM wallet_ledger l JOIN wallet w ON w.wallet_id=l.wallet_id
                    WHERE l.settlement_id=s.settlement_id AND w.customer_id=c.customer_id AND s.status='COMPLETED'))
                JOIN customer_account a ON a.customer_id=c.customer_id AND a.account_status='ACTIVE'
                JOIN fx_quote q ON q.quote_id = s.quote_id
                WHERE s.settlement_id = ?
                  AND c.customer_ref = ?
                  AND c.status = 'ACTIVE'
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SettlementResponse(
                        rs.getString("settlement_id"),
                        rs.getString("quote_id"),
                        rs.getString("status"),
                        rs.getString("source_currency"),
                        rs.getBigDecimal("source_amount"),
                        rs.getString("target_currency"),
                        rs.getBigDecimal("target_amount")
                ),
                settlementId,
                customerRef
        ).stream().findFirst();
    }
    
    public java.util.Optional<Long> findActiveCustomerIdByRef(String customerRef) {
        return jdbcTemplate.query(
                """
                SELECT c.customer_id
                FROM customer c JOIN customer_account a ON a.customer_id=c.customer_id
                WHERE c.customer_ref = ?
                  AND c.status = 'ACTIVE' AND a.account_status='ACTIVE'
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
