package com.fxlock.dashboard;

import java.time.OffsetDateTime;

public record FundingResult(String transactionRef, String operation, String walletId, String currency,
        String amount, String balanceBefore, String balanceAfter, OffsetDateTime completedAt,
        String idempotencyKey, String correlationId) {}
