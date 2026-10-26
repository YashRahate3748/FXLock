package com.fxlock.wallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WalletRepository {

    private final JdbcTemplate jdbcTemplate;

    public WalletRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Used by the existing wallet balance API.
    public List<Wallet> findByCustomerRef(String customerRef) {
        String sql = """
                SELECT w.wallet_id,
                       w.customer_id,
                       w.currency_code,
                       w.balance
                FROM wallet w
                JOIN customer c ON c.customer_id = w.customer_id
                WHERE c.customer_ref = ?
                ORDER BY w.currency_code
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new Wallet(
                        rs.getLong("wallet_id"),
                        rs.getLong("customer_id"),
                        rs.getString("currency_code"),
                        rs.getBigDecimal("balance")
                ),
                customerRef
        );
    }

    // Must be called inside the settlement transaction.
    public Optional<LockedWallet> findByIdForUpdate(Long walletId) {
        String sql = """
                SELECT w.wallet_id,
                       w.customer_id,
                       w.currency_code,
                       w.balance,
                       w.status
                FROM wallet w
                WHERE w.wallet_id = ?
                FOR UPDATE
                """;

        List<LockedWallet> wallets = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new LockedWallet(
                        rs.getLong("wallet_id"),
                        rs.getLong("customer_id"),
                        rs.getString("currency_code"),
                        rs.getBigDecimal("balance"),
                        rs.getString("status")
                ),
                walletId
        );

        return wallets.stream().findFirst();
    }

    public int debit(Long walletId, BigDecimal amount) {
        requirePositiveAmount(amount);

        String sql = """
                UPDATE wallet
                SET balance = balance - ?,
                    version_no = version_no + 1,
                    updated_at = SYSTIMESTAMP
                WHERE wallet_id = ?
                  AND status = 'ACTIVE'
                  AND balance >= ?
                """;

        return jdbcTemplate.update(sql, amount, walletId, amount);
    }

    /** Recheck both participants after acquiring the wallet locks, without locking customer rows. */
    public boolean activeAccount(Long customerId) {
        Integer count=jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM customer c
                JOIN customer_account a ON a.customer_id=c.customer_id
                WHERE c.customer_id=? AND c.status='ACTIVE' AND a.account_status='ACTIVE'
                """, Integer.class, customerId);
        return count != null && count == 1;
    }

    public int credit(Long walletId, BigDecimal amount) {
        requirePositiveAmount(amount);

        String sql = """
                UPDATE wallet
                SET balance = balance + ?,
                    version_no = version_no + 1,
                    updated_at = SYSTIMESTAMP
                WHERE wallet_id = ?
                  AND status = 'ACTIVE'
                """;

        return jdbcTemplate.update(sql, amount, walletId);
    }

    private void requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Wallet update amount must be positive"
            );
        }

        // Reject amounts requiring rounding beyond the schema's scale.
        amount.setScale(4, java.math.RoundingMode.UNNECESSARY);
    }

    public record LockedWallet(
            Long walletId,
            Long customerId,
            String currencyCode,
            BigDecimal balance,
            String status
    ) {}
}
