package com.ofss.fxlock.rate.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "FX_CONFIG")
public class FxConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CONFIG_ID")
    private Long id;

    @Column(name = "CURRENCY_PAIR", nullable = false)
    private String currencyPair;

    @Column(name = "BASE_CURRENCY", nullable = false)
    private String baseCurrency;

    @Column(name = "QUOTE_CURRENCY", nullable = false)
    private String quoteCurrency;

    @Column(name = "RATE_FETCH_INTERVAL_SECONDS", nullable = false)
    private Integer rateFetchIntervalSeconds;

    @Column(name = "MAX_RATE_AGE_SECONDS", nullable = false)
    private Integer maxRateAgeSeconds;

    @Column(name = "QUOTE_VALIDITY_SECONDS", nullable = false)
    private Integer quoteValiditySeconds;

    @Column(name = "BUSINESS_SPREAD_BPS", nullable = false)
    private BigDecimal businessSpreadBps;

    @Column(name = "MIN_TRANSACTION_AMOUNT", nullable = false)
    private BigDecimal minTransactionAmount;

    @Column(name = "MAX_TRANSACTION_AMOUNT", nullable = false)
    private BigDecimal maxTransactionAmount;

    @Column(name = "ANOMALY_THRESHOLD_PERCENT", nullable = false)
    private BigDecimal anomalyThresholdPercent;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "QUOTING_ENABLED", nullable = false, length = 1)
    private String quotingEnabled;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ACTIVE_FLAG", nullable = false, length = 1)
    private String activeFlag;

    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private OffsetDateTime updatedAt;

    public boolean isQuotingEnabled() {
        return "Y".equalsIgnoreCase(quotingEnabled);
    }

    public Long getId() {
        return id;
    }

    public String getCurrencyPair() {
        return currencyPair;
    }

    public Integer getRateFetchIntervalSeconds() {
        return rateFetchIntervalSeconds;
    }

    public Integer getMaxRateAgeSeconds() {
        return maxRateAgeSeconds;
    }

    public Integer getQuoteValiditySeconds() {
        return quoteValiditySeconds;
    }

    public BigDecimal getBusinessSpreadBps() {
        return businessSpreadBps;
    }

    public BigDecimal getMinTransactionAmount() {
        return minTransactionAmount;
    }

    public BigDecimal getMaxTransactionAmount() {
        return maxTransactionAmount;
    }

    public BigDecimal getAnomalyThresholdPercent() {
        return anomalyThresholdPercent;
    }

    public String getQuotingEnabled() {
        return quotingEnabled;
    }

    public void setQuotingEnabled(String quotingEnabled) {
        this.quotingEnabled = quotingEnabled;
    }

    public String getActiveFlag() {
        return activeFlag;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}