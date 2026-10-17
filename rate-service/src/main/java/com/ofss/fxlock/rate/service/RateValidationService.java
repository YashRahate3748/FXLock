package com.ofss.fxlock.rate.service;

import com.ofss.fxlock.rate.entity.FxConfig;
import com.ofss.fxlock.rate.entity.MarketRateSnapshot;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

@Service
public class RateValidationService {

    private static final BigDecimal ONE_HUNDRED =
            new BigDecimal("100");

    public BigDecimal calculateMovementPercent(
            BigDecimal previousRate,
            BigDecimal newRate
    ) {

        if (previousRate == null ||
            previousRate.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return newRate
                .subtract(previousRate)
                .abs()
                .divide(
                        previousRate,
                        new MathContext(12, RoundingMode.HALF_UP)
                )
                .multiply(ONE_HUNDRED)
                .setScale(8, RoundingMode.HALF_UP);
    }

    public boolean isSuspicious(
            BigDecimal previousRate,
            BigDecimal newRate,
            FxConfig config
    ) {

        BigDecimal movement =
                calculateMovementPercent(previousRate, newRate);

        return movement.compareTo(
                config.getAnomalyThresholdPercent()
        ) > 0;
    }

    public boolean isFresh(
            MarketRateSnapshot snapshot,
            FxConfig config
    ) {

        long ageSeconds =
                java.time.Duration.between(
                        snapshot.getReceivedAt().toInstant(),
                        java.time.Instant.now()
                ).getSeconds();

        return ageSeconds >= 0 &&
               ageSeconds <= config.getMaxRateAgeSeconds();
    }
}