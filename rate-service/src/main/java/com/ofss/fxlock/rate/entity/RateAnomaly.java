package com.ofss.fxlock.rate.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "RATE_ANOMALY")
public class RateAnomaly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ANOMALY_ID")
    private Long id;

    @Column(name = "RATE_SNAPSHOT_ID", nullable = false)
    private Long rateSnapshotId;

    @Column(name = "PREVIOUS_TRUSTED_RATE_ID")
    private Long previousTrustedRateId;

    @Column(name = "MOVEMENT_PERCENT")
    private BigDecimal movementPercent;

    @Column(name = "THRESHOLD_PERCENT")
    private BigDecimal thresholdPercent;

    @Column(name = "STATUS", nullable = false)
    private String status;

    @Column(name = "REASON")
    private String reason;

    @Column(name = "REVIEWED_BY")
    private String reviewedBy;

    @Column(name = "REVIEW_REASON")
    private String reviewReason;

    @Column(name = "REVIEWED_AT")
    private OffsetDateTime reviewedAt;

    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public Long getRateSnapshotId() {
        return rateSnapshotId;
    }

    public String getStatus() {
        return status;
    }

    public void setRateSnapshotId(Long rateSnapshotId) {
        this.rateSnapshotId = rateSnapshotId;
    }

    public void setPreviousTrustedRateId(Long previousTrustedRateId) {
        this.previousTrustedRateId = previousTrustedRateId;
    }

    public void setMovementPercent(BigDecimal movementPercent) {
        this.movementPercent = movementPercent;
    }

    public void setThresholdPercent(BigDecimal thresholdPercent) {
        this.thresholdPercent = thresholdPercent;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
    }

    public void setReviewedAt(OffsetDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}