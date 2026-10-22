package com.fxlock.wallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WalletRepository {

    private final JdbcTemplate jdbcTemplate;

    public WalletRepository(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Wallet> findByCustomerRef(
            String customerRef
    ) {

        String sql = """
                SELECT
                    w.wallet_id,
                    w.customer_id,
                    w.currency_code,
                    w.balance
                FROM wallet w
                JOIN customer c
                    ON c.customer_id = w.customer_id
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

    /*
     * Must be called inside the settlement transaction.
     *
     * Customer status is loaded together with the wallet
     * so settlement can validate both sender and beneficiary.
     */
    public Optional<LockedWallet> findByIdForUpdate(
            Long walletId
    ) {

        String sql = """
                SELECT
                    w.wallet_id,
                    w.customer_id,
                    w.currency_code,
                    w.balance,
                    w.status AS wallet_status,
                    c.status AS customer_status
                FROM wallet w
                JOIN customer c
                    ON c.customer_id = w.customer_id
                WHERE w.wallet_id = ?
                FOR UPDATE
                """;

        List<LockedWallet> wallets =
                jdbcTemplate.query(
                        sql,
                        (rs, rowNum) ->
                                new LockedWallet(
                                        rs.getLong(
                                                "wallet_id"
                                        ),
                                        rs.getLong(
                                                "customer_id"
                                        ),
                                        rs.getString(
                                                "currency_code"
                                        ),
                                        rs.getBigDecimal(
                                                "balance"
                                        ),
                                        rs.getString(
                                                "wallet_status"
                                        ),
                                        rs.getString(
                                                "customer_status"
                                        )
                                ),
                        walletId
                );

        return wallets.stream().findFirst();
    }

    public int debit(
            Long walletId,
            BigDecimal amount
    ) {

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

        return jdbcTemplate.update(
                sql,
                amount,
                walletId,
                amount
        );
    }

    public int credit(
            Long walletId,
            BigDecimal amount
    ) {

        requirePositiveAmount(amount);

        String sql = """
                UPDATE wallet
                SET balance = balance + ?,
                    version_no = version_no + 1,
                    updated_at = SYSTIMESTAMP
                WHERE wallet_id = ?
                  AND status = 'ACTIVE'
                """;

        return jdbcTemplate.update(
                sql,
                amount,
                walletId
        );
    }

    private void requirePositiveAmount(
            BigDecimal amount
    ) {

        if (amount == null
                || amount.signum() <= 0) {

            throw new IllegalArgumentException(
                    "Wallet update amount must be positive"
            );
        }

        amount.setScale(
                4,
                java.math.RoundingMode.UNNECESSARY
        );
    }

    public record LockedWallet(
            Long walletId,
            Long customerId,
            String currencyCode,
            BigDecimal balance,
            String status,
            String customerStatus
    ) {}
}