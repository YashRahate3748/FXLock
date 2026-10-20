package com.ofss.fxlock.rate.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TrustedRateResponse(

        Long snapshotId,

        String currencyPair,

        BigDecimal marketRate,

        OffsetDateTime observationTime,

        OffsetDateTime receivedAt,

        long ageSeconds,

        boolean fresh,

        boolean quotingEnabled,

        String providerHealth,

        BigDecimal movementPercent,

        BigDecimal freshnessScore,

        BigDecimal anomalyScore,

        BigDecimal providerHealthScore,

        BigDecimal stabilityScore

) {
}