package com.fxlock.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonFormat;

public final class Models {
    private Models() {}
    public record CreateQuoteRequest(
        @NotBlank @Pattern(regexp="EUR") String sourceCurrency,
        @NotBlank @Pattern(regexp="USD") String targetCurrency,
        @NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=10, fraction=2) BigDecimal sourceAmount,
        @NotBlank @Size(max=80) String sourceWalletId,
        @NotBlank @Size(max=80) String targetWalletId
    ) {}
    // All fields must be supplied by M1. observedAt is the provider observation, not the fetch time.
    public record ConfidenceInputs(BigDecimal freshness,BigDecimal anomaly,BigDecimal provider,BigDecimal stability) {}
    public record TrustedRate(String snapshotId, String sourceCurrency, String targetCurrency,
        BigDecimal rate, Instant observedAt, Boolean trusted, Boolean quotingEnabled,
        Boolean unresolvedAnomaly, Boolean providerHealthy, BigDecimal relativeMovement, ConfidenceInputs inputs) {
        public TrustedRate(String snapshotId,String sourceCurrency,String targetCurrency,BigDecimal rate,Instant observedAt,
            Boolean trusted,Boolean quotingEnabled,Boolean unresolvedAnomaly,Boolean providerHealthy,BigDecimal relativeMovement) {
            this(snapshotId,sourceCurrency,targetCurrency,rate,observedAt,trusted,quotingEnabled,unresolvedAnomaly,providerHealthy,relativeMovement,null);
        }
    }
    public record Confidence(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal score,
        String label, List<String> reasons, BigDecimal freshnessScore, BigDecimal anomalyScore,
        BigDecimal providerHealthScore, BigDecimal stabilityScore) {}
    public record Quote(String quoteId, String customerId, String sourceWalletId, String targetWalletId,
        String sourceCurrency, String targetCurrency, String snapshotId, Instant observedAt,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal marketRate,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal offeredRate,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal sourceAmount,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal targetAmount,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal spreadBps,
        Confidence confidence, Instant createdAt, Instant expiresAt, String status) {}
    public record QuoteView(Quote quote, Instant serverTime, boolean demoMode) {}
    public record SettlementResult(String settlementId, String quoteId, String status,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal sourceAmount,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal targetAmount, Instant completedAt) {}
    public record ApiError(String code, String message) {}
    public record WalletOption(String walletId, String label, String currency, boolean canDebit) {}
}
