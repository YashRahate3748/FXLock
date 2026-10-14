package com.fxlock.wallet;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WalletRepository {

    private final JdbcTemplate jdbcTemplate;

    public WalletRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Wallet> findByCustomerId(String customerId) {
        String sql = """
                SELECT wallet_id, customer_id, currency_code, balance
                FROM wallet
                WHERE customer_id = ?
                ORDER BY currency_code
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new Wallet(
                        rs.getString("wallet_id"),
                        rs.getString("customer_id"),
                        rs.getString("currency_code"),
                        rs.getBigDecimal("balance")
                ),
                customerId
        );
    }
}