package com.fxlock.settlement;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fxlock.settlement.SettlementDetailsResponse.LedgerEntry;

@Service
public class SettlementDetailsService {

    private final JdbcTemplate jdbcTemplate;

    public SettlementDetailsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public SettlementDetailsResponse getDetails(
            String settlementId,
            String customerRef) {

        // Resolve the receipt only through the current customer's records.
        List<SettlementDetailsResponse> receipts =
                jdbcTemplate.query(
                        """
                        SELECT
                            s.settlement_id,
                            s.quote_id,
                            s.status,
                            s.completed_at,
                            q.source_currency,
                            s.source_amount,
                            q.target_currency,
                            s.target_amount,
                            q.market_rate,
                            s.customer_rate,
                            q.applied_spread_bps,
                            q.created_at AS quote_created_at,
                            q.expires_at AS quote_expires_at
                        FROM settlement s
                        JOIN customer c
                            ON c.customer_id = s.customer_id
                        JOIN fx_quote q
                            ON q.quote_id = s.quote_id
                           AND q.customer_id = s.customer_id
                        WHERE s.settlement_id = ?
                          AND c.customer_ref = ?
                          AND c.status = 'ACTIVE'
                        """,
                        (rs, rowNum) -> new SettlementDetailsResponse(
                                rs.getString("settlement_id"),
                                rs.getString("quote_id"),
                                rs.getString("status"),
                                rs.getObject(
                                        "completed_at",
                                        OffsetDateTime.class),
                                rs.getString("source_currency"),
                                rs.getBigDecimal("source_amount"),
                                rs.getString("target_currency"),
                                rs.getBigDecimal("target_amount"),
                                rs.getBigDecimal("market_rate"),
                                rs.getBigDecimal("customer_rate"),
                                rs.getBigDecimal("applied_spread_bps"),
                                rs.getObject(
                                        "quote_created_at",
                                        OffsetDateTime.class),
                                rs.getObject(
                                        "quote_expires_at",
                                        OffsetDateTime.class),
                                List.<LedgerEntry>of()),
                        settlementId,
                        customerRef);

        if (receipts.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "SETTLEMENT_NOT_FOUND");
        }

        SettlementDetailsResponse receipt = receipts.getFirst();

        List<LedgerEntry> entries = jdbcTemplate.query(
                """
                SELECT
                    l.ledger_id,
                    l.wallet_id,
                    l.entry_type,
                    l.currency_code,
                    l.amount,
                    l.balance_before,
                    l.balance_after,
                    w.balance AS current_wallet_balance,
                    l.created_at
                FROM wallet_ledger l
                JOIN settlement s
                    ON s.settlement_id = l.settlement_id
                JOIN customer c
                    ON c.customer_id = s.customer_id
                JOIN wallet w
                    ON w.wallet_id = l.wallet_id
                   AND w.customer_id = c.customer_id
                WHERE s.settlement_id = ?
                  AND c.customer_ref = ?
                  AND c.status = 'ACTIVE'
                ORDER BY
                    CASE l.entry_type
                        WHEN 'DEBIT' THEN 0
                        ELSE 1
                    END,
                    l.ledger_id
                """,
                (rs, rowNum) -> new LedgerEntry(
                        rs.getLong("ledger_id"),
                        rs.getLong("wallet_id"),
                        rs.getString("entry_type"),
                        rs.getString("currency_code"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("balance_before"),
                        rs.getBigDecimal("balance_after"),
                        rs.getBigDecimal("current_wallet_balance"),
                        rs.getObject(
                                "created_at",
                                OffsetDateTime.class)),
                settlementId,
                customerRef);

        return new SettlementDetailsResponse(
                receipt.settlementId(),
                receipt.quoteId(),
                receipt.status(),
                receipt.completedAt(),
                receipt.sourceCurrency(),
                receipt.sourceAmount(),
                receipt.targetCurrency(),
                receipt.targetAmount(),
                receipt.marketRateWhenQuoted(),
                receipt.lockedCustomerRate(),
                receipt.appliedSpreadBps(),
                receipt.quoteCreatedAt(),
                receipt.quoteExpiresAt(),
                List.copyOf(entries));
    }
}