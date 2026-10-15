package com.fxlock.settlement;

import java.math.BigDecimal;

public record SettlementResponse(
        String settlementId,
        String quoteId,
        String status,
        String sourceCurrency,
        BigDecimal sourceAmount,
        String targetCurrency,
        BigDecimal targetAmount
) {}