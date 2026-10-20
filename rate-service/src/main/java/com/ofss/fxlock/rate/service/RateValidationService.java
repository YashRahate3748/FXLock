package com.ofss.fxlock.rate.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;

import com.ofss.fxlock.rate.entity.FxConfig;
import com.ofss.fxlock.rate.entity.MarketRateSnapshot;
import com.ofss.fxlock.rate.enums.ObservationOrder;

@Service
public class RateValidationService {

    private static final BigDecimal ONE_HUNDRED =
            new BigDecimal("100");

    public BigDecimal calculateMovementPercent(
            BigDecimal previousRate,
            BigDecimal newRate
    ) {

        if (previousRate == null
                || previousRate.compareTo(
                        BigDecimal.ZERO
                ) <= 0) {

            return BigDecimal.ZERO;
        }

        return newRate
                .subtract(previousRate)
                .abs()
                .divide(
                        previousRate,
                        new MathContext(
                                12,
                                RoundingMode.HALF_UP
                        )
                )
                .multiply(ONE_HUNDRED)
                .setScale(
                        8,
                        RoundingMode.HALF_UP
                );
    }

    public boolean isSuspicious(
            BigDecimal previousRate,
            BigDecimal newRate,
            FxConfig config
    ) {

        BigDecimal movement =
                calculateMovementPercent(
                        previousRate,
                        newRate
                );

        return movement.compareTo(
                config.getAnomalyThresholdPercent()
        ) > 0;
    }

    public boolean isFresh(
            MarketRateSnapshot snapshot,
            FxConfig config
    ) {

        if (snapshot == null
                || snapshot.getReceivedAt() == null) {
            return false;
        }

        long ageSeconds =
                Duration.between(
                        snapshot.getReceivedAt().toInstant(),
                        Instant.now()
                ).getSeconds();

        ageSeconds = Math.max(0, ageSeconds);

        return ageSeconds
                <= config.getMaxRateAgeSeconds();
    }

    public ObservationOrder determineObservationOrder(
            MarketRateSnapshot previousTrusted,
            OffsetDateTime newProviderTimestamp,
            BigDecimal newRate
    ) {

        if (previousTrusted == null) {
            return ObservationOrder.NEW;
        }

        OffsetDateTime previousTimestamp =
                previousTrusted
                        .getProviderTimestamp();

        if (previousTimestamp == null
                || newProviderTimestamp == null) {

            return ObservationOrder.NEW;
        }

        if (newProviderTimestamp.isBefore(
                previousTimestamp
        )) {

            return ObservationOrder.OUT_OF_ORDER;
        }

        if (newProviderTimestamp.isEqual(
                previousTimestamp
        )) {

            if (newRate.compareTo(
                    previousTrusted.getMarketRate()
            ) == 0) {

                return ObservationOrder.DUPLICATE;
            }

            return ObservationOrder
                    .CONFLICTING_DUPLICATE;
        }

        return ObservationOrder.NEW;
    }

    public boolean isProviderTimestampTooFarInFuture(
            OffsetDateTime providerTimestamp,
            int allowedClockSkewSeconds
    ) {

        if (providerTimestamp == null) {
            return false;
        }

        OffsetDateTime maximumAllowed =
                OffsetDateTime.now()
                        .plusSeconds(
                                allowedClockSkewSeconds
                        );

        return providerTimestamp.isAfter(
                maximumAllowed
        );
    }
}