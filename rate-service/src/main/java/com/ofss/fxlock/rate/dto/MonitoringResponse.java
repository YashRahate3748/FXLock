package com.ofss.fxlock.rate.dto;

import java.time.OffsetDateTime;
import java.util.List;

public final class MonitoringResponse {

    private MonitoringResponse() {
    }

    // Decimal values are strings to preserve database precision in JSON.
    public record RatePoint(
            Long snapshotId,
            String currencyPair,
            String marketRate,
            OffsetDateTime observationTime,
            OffsetDateTime receivedAt,
            String validationStatus,
            String movementPercent
    ) {
    }

    public record Status(
            OffsetDateTime serverTime,
            RatePoint lastTrustedRate,
            boolean fresh,
            boolean quotingEnabled,
            boolean quoteAvailable,
            String unavailableReason,
            String providerHealth,
            OffsetDateTime lastProviderSuccess,
            OffsetDateTime lastProviderFailure,
            OffsetDateTime nextAllowedFetch,
            int maxRateAgeSeconds,
            String anomalyThresholdPercent,
            long pollIntervalMs
    ) {
    }

    public record History(
            List<RatePoint> items,
            boolean truncated,
            int limit
    ) {
    }

    public record Anomaly(
            Long anomalyId,
            String status,
            String reason,
            String movementPercent,
            String thresholdPercent,
            OffsetDateTime createdAt,
            String reviewedBy,
            String reviewReason,
            OffsetDateTime reviewedAt,
            RatePoint candidate,
            RatePoint previousTrusted,
            boolean canApprove,
            boolean canReject,
            String approvalBlockedReason
    ) {
    }

    public record AnomalyPage(
            List<Anomaly> items,
            long totalElements,
            int totalPages,
            int page
    ) {
    }
}