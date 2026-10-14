package com.fxlock.wallet;

import java.math.BigDecimal;

public record Wallet(
        String walletId,
        String customerId,
        String currencyCode,
        BigDecimal balance
) {}