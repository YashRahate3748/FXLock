package com.ofss.fxlock.rate.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "MARKET_RATE_SNAPSHOT")
public class MarketRateSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RATE_SNAPSHOT_ID")
    private Long id;

    @Column(name = "CURRENCY_PAIR", nullable = false)
    private String currencyPair;

    @Column(name = "PROVIDER_NAME", nullable = false)
    private String providerName;

    @Column(
            name = "MARKET_RATE",
            nullable = false,
            precision = 20,
            scale = 10
    )
    private BigDecimal marketRate;

    @Column(name = "PROVIDER_TIMESTAMP")
    private OffsetDateTime providerTimestamp;

    @Column(name = "RECEIVED_AT", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "VALIDATION_STATUS", nullable = false)
    private String validationStatus;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(
            name = "TRUSTED_FLAG",
            nullable = false,
            length = 1
    )
    private String trustedFlag;

    @Column(
            name = "MOVEMENT_PERCENT",
            precision = 12,
            scale = 8
    )
    private BigDecimal movementPercent;

    @Column(name = "VALIDATION_REASON")
    private String validationReason;

    @Column(name = "CORRELATION_ID")
    private String correlationId;

    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    public OffsetDateTime getEffectiveObservationTime() {

        return providerTimestamp != null
                ? providerTimestamp
                : receivedAt;
    }

    public boolean isTrusted() {
        return "Y".equalsIgnoreCase(trustedFlag);
    }

    public Long getId() {
        return id;
    }

    public String getCurrencyPair() {
        return currencyPair;
    }

    public String getProviderName() {
        return providerName;
    }

    public BigDecimal getMarketRate() {
        return marketRate;
    }

    public OffsetDateTime getProviderTimestamp() {
        return providerTimestamp;
    }

    public OffsetDateTime getReceivedAt() {
        return receivedAt;
    }

    public String getValidationStatus() {
        return validationStatus;
    }

    public String getTrustedFlag() {
        return trustedFlag;
    }

    public BigDecimal getMovementPercent() {
        return movementPercent;
    }

    public String getValidationReason() {
        return validationReason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCurrencyPair(String currencyPair) {
        this.currencyPair = currencyPair;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public void setMarketRate(BigDecimal marketRate) {
        this.marketRate = marketRate;
    }

    public void setProviderTimestamp(
            OffsetDateTime providerTimestamp
    ) {
        this.providerTimestamp = providerTimestamp;
    }

    public void setReceivedAt(
            OffsetDateTime receivedAt
    ) {
        this.receivedAt = receivedAt;
    }

    public void setValidationStatus(
            String validationStatus
    ) {
        this.validationStatus = validationStatus;
    }

    public void setTrustedFlag(
            String trustedFlag
    ) {
        this.trustedFlag = trustedFlag;
    }

    public void setMovementPercent(
            BigDecimal movementPercent
    ) {
        this.movementPercent = movementPercent;
    }

    public void setValidationReason(
            String validationReason
    ) {
        this.validationReason = validationReason;
    }

    public void setCorrelationId(
            String correlationId
    ) {
        this.correlationId = correlationId;
    }

    public void setCreatedAt(
            OffsetDateTime createdAt
    ) {
        this.createdAt = createdAt;
    }
}