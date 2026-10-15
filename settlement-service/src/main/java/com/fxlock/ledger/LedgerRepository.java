package com.fxlock.ledger;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbcTemplate;

    public LedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertEntry(
            String settlementId,
            Long walletId,
            String entryType,
            String currencyCode,
            BigDecimal amount,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter
    ) {
        String sql = """
                INSERT INTO wallet_ledger (
                    settlement_id,
                    wallet_id,
                    entry_type,
                    currency_code,
                    amount,
                    balance_before,
                    balance_after
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        return jdbcTemplate.update(
                sql,
                settlementId,
                walletId,
                entryType,
                currencyCode,
                amount,
                balanceBefore,
                balanceAfter
        );
    }
}