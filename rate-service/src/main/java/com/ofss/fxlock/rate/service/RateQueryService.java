package com.ofss.fxlock.rate.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;

import com.ofss.fxlock.rate.dto.TrustedRateResponse;
import com.ofss.fxlock.rate.entity.FxConfig;
import com.ofss.fxlock.rate.entity.MarketRateSnapshot;
import com.ofss.fxlock.rate.exception.RateUnavailableException;
import com.ofss.fxlock.rate.repository.FxConfigRepository;

@Service
public class RateQueryService {

    private static final BigDecimal HUNDRED =
            new BigDecimal("100");

    private final TrustedRateCache cache;

    private final FxConfigRepository
            configRepository;

    private final ProviderStateService
            providerStateService;

    public RateQueryService(
            TrustedRateCache cache,
            FxConfigRepository configRepository,
            ProviderStateService providerStateService
    ) {

        this.cache = cache;
        this.configRepository =
                configRepository;
        this.providerStateService =
                providerStateService;
    }

    public TrustedRateResponse getTrustedRate() {

        FxConfig config =
                configRepository
                        .findByCurrencyPair(
                                "EUR/USD"
                        )
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "EUR/USD configuration missing"
                                        )
                        );

        MarketRateSnapshot rate =
                cache.get()
                        .orElseThrow(
                                () ->
                                        new RateUnavailableException(
                                                "No trusted rate is available"
                                        )
                        );

        OffsetDateTime observationTime =
                rate.getProviderTimestamp();

        Instant now = Instant.now();
        
        org.slf4j.LoggerFactory
        .getLogger(RateQueryService.class)
        .info(
                "Trusted-rate check: snapshotId={}, providerTime={}, "
                        + "receivedAt={}, checkedAt={}, maxAgeSeconds={}",
                rate.getId(),
                rate.getProviderTimestamp(),
                rate.getReceivedAt(),
                now,
                config.getMaxRateAgeSeconds()
        );

        boolean fresh =
                RateValidationService.isProviderObservationFresh(
                        observationTime,
                        config.getMaxRateAgeSeconds(),
                        now
                );

        if (!fresh) {
            throw new RateUnavailableException(
                    "Trusted FX rate has a missing, future or stale provider timestamp"
            );
        }

        long ageSeconds = Duration.between(
                observationTime.toInstant(),
                now
        ).getSeconds();

        if (!config.isQuotingEnabled()) {

            throw new RateUnavailableException(
                    "Quote generation is disabled"
            );
        }

        BigDecimal freshnessScore =
                calculateFreshnessScore(
                        ageSeconds,
                        config.getMaxRateAgeSeconds()
                );

        BigDecimal anomalyScore =
                HUNDRED;

        BigDecimal providerScore =
                switch (
                        providerStateService
                                .getHealth()
                ) {

                    case UP ->
                            new BigDecimal("100");

                    case DEGRADED ->
                            new BigDecimal("50");

                    case DOWN ->
                            BigDecimal.ZERO;
                };

        BigDecimal stabilityScore =
                calculateStabilityScore(
                        rate.getMovementPercent(),
                        config.getAnomalyThresholdPercent()
                );

        return new TrustedRateResponse(
                rate.getId(),
                "EUR/USD",
                rate.getMarketRate(),
                observationTime,
                rate.getReceivedAt(),
                ageSeconds,
                true,
                true,
                providerStateService
                        .getHealth()
                        .name(),
                rate.getMovementPercent(),
                freshnessScore,
                anomalyScore,
                providerScore,
                stabilityScore
        );
    }

    private BigDecimal calculateFreshnessScore(
            long ageSeconds,
            int maxAge
    ) {

        if (ageSeconds >= maxAge) {

            return BigDecimal.ZERO
                    .setScale(
                            2,
                            RoundingMode.HALF_UP
                    );
        }

        BigDecimal age =
                BigDecimal.valueOf(
                        ageSeconds
                );

        BigDecimal maximum =
                BigDecimal.valueOf(
                        maxAge
                );

        return HUNDRED
                .multiply(
                        BigDecimal.ONE
                                .subtract(
                                        age.divide(
                                                maximum,
                                                6,
                                                RoundingMode.HALF_UP
                                        )
                                )
                )
                .max(
                        BigDecimal.ZERO
                )
                .setScale(
                        2,
                        RoundingMode.HALF_UP
                );
    }

    private BigDecimal calculateStabilityScore(
            BigDecimal movement,
            BigDecimal threshold
    ) {

        if (movement == null
                || movement.compareTo(
                        BigDecimal.ZERO
                ) <= 0) {

            return HUNDRED
                    .setScale(
                            2,
                            RoundingMode.HALF_UP
                    );
        }

        if (threshold == null
                || threshold.compareTo(
                        BigDecimal.ZERO
                ) <= 0) {

            return BigDecimal.ZERO
                    .setScale(
                            2,
                            RoundingMode.HALF_UP
                    );
        }

        if (movement.compareTo(
                threshold
        ) >= 0) {

            return BigDecimal.ZERO
                    .setScale(
                            2,
                            RoundingMode.HALF_UP
                    );
        }

        return HUNDRED
                .multiply(
                        BigDecimal.ONE
                                .subtract(
                                        movement.divide(
                                                threshold,
                                                6,
                                                RoundingMode.HALF_UP
                                        )
                                )
                )
                .max(
                        BigDecimal.ZERO
                )
                .setScale(
                        2,
                        RoundingMode.HALF_UP
                );
    }
}