package com.ofss.fxlock.rate.service;

import com.ofss.fxlock.rate.dto.QuotingStatusResponse;
import com.ofss.fxlock.rate.dto.RiskReviewRequest;
import com.ofss.fxlock.rate.entity.*;
import com.ofss.fxlock.rate.exception.ResourceNotFoundException;
import com.ofss.fxlock.rate.repository.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class RiskControlService {

    private final RateAnomalyRepository anomalyRepository;
    private final MarketRateRepository rateRepository;
    private final FxConfigRepository configRepository;
    private final AuditEventRepository auditRepository;
    private final TrustedRateCache cache;

    public RiskControlService(
            RateAnomalyRepository anomalyRepository,
            MarketRateRepository rateRepository,
            FxConfigRepository configRepository,
            AuditEventRepository auditRepository,
            TrustedRateCache cache
    ) {
        this.anomalyRepository = anomalyRepository;
        this.rateRepository = rateRepository;
        this.configRepository = configRepository;
        this.auditRepository = auditRepository;
        this.cache = cache;
    }

    @Transactional
    public void approveAnomaly(
            Long anomalyId,
            RiskReviewRequest request,
            Authentication authentication
    ) {

        RateAnomaly anomaly =
                anomalyRepository
                        .findById(anomalyId)
                        .orElseThrow(
                            () -> new ResourceNotFoundException(
                                "Anomaly not found"
                            )
                        );

        if (!"OPEN".equals(anomaly.getStatus()) &&
            !"ACKNOWLEDGED".equals(anomaly.getStatus())) {

            throw new IllegalStateException(
                    "Anomaly already reviewed"
            );
        }

        MarketRateSnapshot candidate =
                rateRepository
                        .findById(
                            anomaly.getRateSnapshotId()
                        )
                        .orElseThrow(
                            () -> new ResourceNotFoundException(
                                "Candidate rate not found"
                            )
                        );

        FxConfig config =
                configRepository
                        .findByCurrencyPair("EUR/USD")
                        .orElseThrow();

        long ageSeconds =
                Duration.between(
                        candidate
                            .getReceivedAt()
                            .toInstant(),
                        Instant.now()
                ).getSeconds();

        if (ageSeconds >
            config.getMaxRateAgeSeconds()) {

            throw new IllegalStateException(
                    "Cannot approve a stale rate"
            );
        }

        candidate.setValidationStatus("TRUSTED");
        candidate.setTrustedFlag("Y");
        candidate.setValidationReason(
                "Approved by Risk Analyst: "
                + request.reason()
        );

        rateRepository.save(candidate);

        anomaly.setStatus("APPROVED");
        anomaly.setReviewedBy(
                authentication.getName()
        );
        anomaly.setReviewReason(
                request.reason()
        );
        anomaly.setReviewedAt(
                OffsetDateTime.now(
                    ZoneOffset.UTC
                )
        );

        anomalyRepository.save(anomaly);

        audit(
                "RATE_ANOMALY_APPROVED",
                candidate.getId().toString(),
                authentication.getName(),
                request.reason()
        );

        TransactionSynchronizationManager
                .registerSynchronization(
                    new TransactionSynchronization() {

                        @Override
                        public void afterCommit() {
                            cache.update(candidate);
                        }
                    }
                );
    }

    @Transactional
    public void rejectAnomaly(
            Long anomalyId,
            RiskReviewRequest request,
            Authentication authentication
    ) {

        RateAnomaly anomaly =
                anomalyRepository
                        .findById(anomalyId)
                        .orElseThrow(
                            () -> new ResourceNotFoundException(
                                "Anomaly not found"
                            )
                        );

        anomaly.setStatus("REJECTED");
        anomaly.setReviewedBy(
                authentication.getName()
        );
        anomaly.setReviewReason(
                request.reason()
        );
        anomaly.setReviewedAt(
                OffsetDateTime.now(
                    ZoneOffset.UTC
                )
        );

        anomalyRepository.save(anomaly);

        audit(
                "RATE_ANOMALY_REJECTED",
                anomalyId.toString(),
                authentication.getName(),
                request.reason()
        );
    }

    @Transactional
    public QuotingStatusResponse setQuoting(
            boolean enabled,
            Authentication authentication
    ) {

        FxConfig config =
                configRepository
                        .findByCurrencyPair("EUR/USD")
                        .orElseThrow();

        config.setQuotingEnabled(
                enabled ? "Y" : "N"
        );

        config.setUpdatedAt(
                OffsetDateTime.now(
                    ZoneOffset.UTC
                )
        );

        configRepository.save(config);

        audit(
                enabled
                    ? "QUOTING_ENABLED"
                    : "QUOTING_DISABLED",
                config.getId().toString(),
                authentication.getName(),
                "Risk Analyst changed quote generation status"
        );

        return new QuotingStatusResponse(enabled);
    }

    private void audit(
            String eventType,
            String entityId,
            String actor,
            String details
    ) {

        AuditEvent event =
                new AuditEvent();

        event.setEventType(eventType);
        event.setEntityType("RISK_CONTROL");
        event.setEntityId(entityId);
        event.setActorType("RISK_ANALYST");
        event.setActorId(actor);
        event.setEventStatus("SUCCESS");
        event.setDetails(details);
        event.setCreatedAt(
                OffsetDateTime.now(
                    ZoneOffset.UTC
                )
        );

        auditRepository.save(event);
    }
}