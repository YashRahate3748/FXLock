package com.fxlock.quote;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record QuoteSnapshot(
        String quoteId,
        Long customerId,
        Long sourceWalletId,
        Long targetWalletId,
        String sourceCurrency,
        String targetCurrency,
        BigDecimal sourceAmount,
        BigDecimal targetAmount,
        BigDecimal customerRate,
        String status,
        OffsetDateTime expiresAt
) {}