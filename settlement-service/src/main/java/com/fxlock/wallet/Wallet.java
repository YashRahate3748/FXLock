package com.fxlock.wallet;

import java.math.BigDecimal;

public record Wallet(
        Long walletId,
        Long customerId,
        String currencyCode,
        BigDecimal balance
) {}