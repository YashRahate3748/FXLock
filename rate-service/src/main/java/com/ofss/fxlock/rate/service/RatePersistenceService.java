package com.ofss.fxlock.rate.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ofss.fxlock.rate.dto.TwelveDataResponse;
import com.ofss.fxlock.rate.entity.AuditEvent;
import com.ofss.fxlock.rate.entity.FxConfig;
import com.ofss.fxlock.rate.entity.MarketRateSnapshot;
import com.ofss.fxlock.rate.entity.OutboxEvent;
import com.ofss.fxlock.rate.entity.RateAnomaly;
import com.ofss.fxlock.rate.repository.AuditEventRepository;
import com.ofss.fxlock.rate.repository.FxConfigRepository;
import com.ofss.fxlock.rate.repository.MarketRateRepository;
import com.ofss.fxlock.rate.repository.OutboxEventRepository;
import com.ofss.fxlock.rate.repository.RateAnomalyRepository;

@Service
public class RatePersistenceService {

    private final FxConfigRepository configRepository;
    private final MarketRateRepository rateRepository;
    private final RateAnomalyRepository anomalyRepository;
    private final AuditEventRepository auditRepository;
    private final OutboxEventRepository outboxRepository;

    private final TrustedRateCache cache;
    private final RateValidationService validationService;
    private final ObjectMapper objectMapper;

    public RatePersistenceService(
            FxConfigRepository configRepository,
            MarketRateRepository rateRepository,
            RateAnomalyRepository anomalyRepository,
            AuditEventRepository auditRepository,
            OutboxEventRepository outboxRepository,
            TrustedRateCache cache,
            RateValidationService validationService,
            ObjectMapper objectMapper) {

        this.configRepository = configRepository;
        this.rateRepository = rateRepository;
        this.anomalyRepository = anomalyRepository;
        this.auditRepository = auditRepository;
        this.outboxRepository = outboxRepository;
        this.cache = cache;
        this.validationService = validationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void processProviderResponse(TwelveDataResponse response) {

        FxConfig config = configRepository
                .findByCurrencyPair("EUR/USD")
                .orElseThrow(() ->
                        new IllegalStateException(
                                "FX_CONFIG missing for EUR/USD"
                        )
                );

        validateStructure(response);

        BigDecimal newRate = new BigDecimal(response.rate());

        if (newRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Provider returned non-positive rate"
            );
        }

        OffsetDateTime now =
                OffsetDateTime.now(ZoneOffset.UTC);

        OffsetDateTime providerTimestamp =
                response.timestamp() == null
                        ? null
                        : OffsetDateTime.ofInstant(
                                Instant.ofEpochSecond(
                                        response.timestamp()
                                ),
                                ZoneOffset.UTC
                        );

        MarketRateSnapshot previousTrusted =
                cache.get().orElse(null);

        BigDecimal movement =
                previousTrusted == null
                        ? BigDecimal.ZERO
                        : validationService
                                .calculateMovementPercent(
                                        previousTrusted.getMarketRate(),
                                        newRate
                                );

        boolean suspicious =
                previousTrusted != null
                        && validationService.isSuspicious(
                                previousTrusted.getMarketRate(),
                                newRate,
                                config
                        );

        String correlationId =
                UUID.randomUUID().toString();

        MarketRateSnapshot snapshot =
                new MarketRateSnapshot();

        snapshot.setCurrencyPair("EUR/USD");
        snapshot.setProviderName("TWELVE_DATA");
        snapshot.setMarketRate(newRate);
        snapshot.setProviderTimestamp(providerTimestamp);
        snapshot.setReceivedAt(now);
        snapshot.setMovementPercent(movement);
        snapshot.setCorrelationId(correlationId);
        snapshot.setCreatedAt(now);

        if (suspicious) {

            snapshot.setValidationStatus("SUSPICIOUS");
            snapshot.setTrustedFlag("N");
            snapshot.setValidationReason(
                    "Movement exceeded configured threshold"
            );

            MarketRateSnapshot saved =
                    rateRepository.save(snapshot);

            createAnomaly(
                    saved,
                    previousTrusted,
                    movement,
                    config
            );

            createAudit(
                    "RATE_ANOMALY_DETECTED",
                    "MARKET_RATE_SNAPSHOT",
                    saved.getId().toString(),
                    "SUSPICIOUS",
                    "Rate movement exceeded threshold",
                    correlationId
            );

            createOutbox(
                    "RATE_ANOMALY_DETECTED",
                    "MARKET_RATE_SNAPSHOT",
                    saved.getId().toString(),
                    "fx-system-events",
                    Map.of(
                            "rateSnapshotId", saved.getId(),
                            "currencyPair", "EUR/USD",
                            "rate", newRate,
                            "movementPercent", movement,
                            "thresholdPercent",
                            config.getAnomalyThresholdPercent()
                    ),
                    correlationId
            );

            return;
        }

        snapshot.setValidationStatus("TRUSTED");
        snapshot.setTrustedFlag("Y");
        snapshot.setValidationReason(
                "Provider rate passed validation"
        );

        MarketRateSnapshot saved =
                rateRepository.save(snapshot);

        createAudit(
                "RATE_UPDATED",
                "MARKET_RATE_SNAPSHOT",
                saved.getId().toString(),
                "SUCCESS",
                "Trusted EUR/USD rate updated",
                correlationId
        );

        createOutbox(
                "RATE_UPDATED",
                "MARKET_RATE_SNAPSHOT",
                saved.getId().toString(),
                "fx-rate-events",
                Map.of(
                        "rateSnapshotId", saved.getId(),
                        "currencyPair", "EUR/USD",
                        "marketRate", newRate,
                        "receivedAt", now.toString()
                ),
                correlationId
        );

        updateCacheAfterCommit(saved);
    }

    private void validateStructure(
            TwelveDataResponse response) {

        if (response == null) {
            throw new IllegalArgumentException(
                    "Empty provider response"
            );
        }

        if (response.rate() == null
                || response.rate().isBlank()) {

            throw new IllegalArgumentException(
                    "Provider response missing rate"
            );
        }

        if (response.symbol() != null
                && !"EUR/USD".equalsIgnoreCase(
                        response.symbol()
                )) {

            throw new IllegalArgumentException(
                    "Unexpected currency pair: "
                            + response.symbol()
            );
        }
    }

    private void createAnomaly(
            MarketRateSnapshot suspicious,
            MarketRateSnapshot previousTrusted,
            BigDecimal movement,
            FxConfig config) {

        RateAnomaly anomaly =
                new RateAnomaly();

        anomaly.setRateSnapshotId(
                suspicious.getId()
        );

        anomaly.setPreviousTrustedRateId(
                previousTrusted == null
                        ? null
                        : previousTrusted.getId()
        );

        anomaly.setMovementPercent(movement);

        anomaly.setThresholdPercent(
                config.getAnomalyThresholdPercent()
        );

        anomaly.setStatus("OPEN");

        anomaly.setReason(
                "Movement exceeds configured anomaly threshold"
        );

        anomaly.setCreatedAt(
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        anomalyRepository.save(anomaly);
    }

    private void createAudit(
            String eventType,
            String entityType,
            String entityId,
            String status,
            String details,
            String correlationId) {

        AuditEvent event =
                new AuditEvent();

        event.setEventType(eventType);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setActorType("SYSTEM");
        event.setActorId("rate-service");
        event.setEventStatus(status);
        event.setDetails(details);
        event.setCorrelationId(correlationId);

        event.setCreatedAt(
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        auditRepository.save(event);
    }

    private void createOutbox(
            String eventType,
            String aggregateType,
            String aggregateId,
            String topic,
            Map<String, Object> payload,
            String correlationId) {

        OutboxEvent event =
                new OutboxEvent();

        event.setId(
                UUID.randomUUID().toString()
        );

        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setTopicName(topic);

        try {

            event.setPayload(
                    objectMapper.writeValueAsString(
                            payload
                    )
            );

        } catch (JsonProcessingException ex) {

            throw new IllegalStateException(
                    "Could not serialize outbox payload",
                    ex
            );
        }

        event.setStatus("PENDING");
        event.setRetryCount(0);

        event.setCreatedAt(
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        event.setCorrelationId(correlationId);

        outboxRepository.save(event);
    }

    private void updateCacheAfterCommit(
            MarketRateSnapshot snapshot) {

        TransactionSynchronizationManager
                .registerSynchronization(
                        new TransactionSynchronization() {

                            @Override
                            public void afterCommit() {
                                cache.update(snapshot);
                            }
                        }
                );
    }
}