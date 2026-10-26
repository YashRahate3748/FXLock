package com.fxlock.settlement;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record SettlementDetailsResponse(
        String settlementId,
        String quoteId,
        String status,
        OffsetDateTime completedAt,
        String sourceCurrency,
        BigDecimal sourceAmount,
        String targetCurrency,
        BigDecimal targetAmount,
        BigDecimal marketRateWhenQuoted,
        BigDecimal lockedCustomerRate,
        BigDecimal appliedSpreadBps,
        OffsetDateTime quoteCreatedAt,
        OffsetDateTime quoteExpiresAt,
        List<LedgerEntry> ledgerEntries) {

    public record LedgerEntry(
            Long ledgerId,
            Long walletId,
            String entryType,
            String currencyCode,
            BigDecimal amount,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter,
            BigDecimal currentWalletBalance,
            OffsetDateTime recordedAt) {
    }
}