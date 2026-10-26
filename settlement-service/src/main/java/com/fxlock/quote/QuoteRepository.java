package com.fxlock.quote;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QuoteRepository {

    private final JdbcTemplate jdbcTemplate;

    public QuoteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<QuoteSnapshot> findByIdForUpdate(String quoteId) {
        String sql = """
                SELECT quote_id,
                       customer_id,
                       source_wallet_id,
                       target_wallet_id,
                       source_currency,
                       target_currency,
                       source_amount,
                       target_amount,
                       customer_rate,
                       status,
                       expires_at
                FROM fx_quote
                WHERE quote_id = ?
                FOR UPDATE
                """;

        List<QuoteSnapshot> quotes = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new QuoteSnapshot(
                        rs.getString("quote_id"),
                        rs.getLong("customer_id"),
                        rs.getLong("source_wallet_id"),
                        rs.getLong("target_wallet_id"),
                        rs.getString("source_currency"),
                        rs.getString("target_currency"),
                        rs.getBigDecimal("source_amount"),
                        rs.getBigDecimal("target_amount"),
                        rs.getBigDecimal("customer_rate"),
                        rs.getString("status"),
                        rs.getObject(
                                "expires_at",
                                java.time.OffsetDateTime.class
                        )
                ),
                quoteId
        );

        return quotes.stream().findFirst();
    }

    public int markSettled(String quoteId) {
        String sql = """
                UPDATE fx_quote
                SET status = 'SETTLED',
                    settled_at = SYSTIMESTAMP
                WHERE quote_id = ?
                  AND status = 'OPEN'
                """;

        return jdbcTemplate.update(sql, quoteId);
    }
}