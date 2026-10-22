package com.ofss.fxlock.rate.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
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
import com.ofss.fxlock.rate.enums.ObservationOrder;
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

    private final int maxProviderClockSkewSeconds;

    public RatePersistenceService(
            FxConfigRepository configRepository,
            MarketRateRepository rateRepository,
            RateAnomalyRepository anomalyRepository,
            AuditEventRepository auditRepository,
            OutboxEventRepository outboxRepository,
            TrustedRateCache cache,
            RateValidationService validationService,
            ObjectMapper objectMapper,
            @Value(
                    "${fxlock.rate.max-provider-clock-skew-seconds:30}"
            )
            int maxProviderClockSkewSeconds
    ) {

        this.configRepository = configRepository;
        this.rateRepository = rateRepository;
        this.anomalyRepository = anomalyRepository;
        this.auditRepository = auditRepository;
        this.outboxRepository = outboxRepository;
        this.cache = cache;
        this.validationService = validationService;
        this.objectMapper = objectMapper;
        this.maxProviderClockSkewSeconds =
                maxProviderClockSkewSeconds;
    }

    @Transactional
    public void processProviderResponse(
            TwelveDataResponse response
    ) {

        FxConfig config =
                configRepository
                        .findByCurrencyPair(
                                "EUR/USD"
                        )
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "FX_CONFIG missing for EUR/USD"
                                        )
                        );

        validateStructure(response);

        BigDecimal newRate =
                parseRate(
                        response.rate()
                );

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        OffsetDateTime providerTimestamp =
                parseProviderTimestamp(
                        response.timestamp()
                );
        
        if (!RateValidationService.isProviderObservationFresh(
                providerTimestamp,
                config.getMaxRateAgeSeconds(),
                now.toInstant()
        )) {
            throw new IllegalArgumentException(
                    "Provider observation is missing, future-dated or stale"
            );
        }

        if (validationService
                .isProviderTimestampTooFarInFuture(
                        providerTimestamp,
                        maxProviderClockSkewSeconds
                )) {

            throw new IllegalArgumentException(
                    "Provider timestamp is too far in the future"
            );
        }

        MarketRateSnapshot previousTrusted =
                cache.get()
                        .orElse(null);

        ObservationOrder observationOrder =
                validationService
                        .determineObservationOrder(
                                previousTrusted,
                                providerTimestamp,
                                newRate
                        );

        if (observationOrder
                == ObservationOrder.DUPLICATE) {

            recordDuplicateObservation(
                    previousTrusted
            );

            return;
        }

        if (observationOrder
                != ObservationOrder.NEW) {

            handleRejectedObservation(
                    newRate,
                    providerTimestamp,
                    now,
                    previousTrusted,
                    observationOrder
            );

            return;
        }

        BigDecimal movement =
                previousTrusted == null
                        ? BigDecimal.ZERO
                        : validationService
                                .calculateMovementPercent(
                                        previousTrusted
                                                .getMarketRate(),
                                        newRate
                                );

        boolean suspicious =
                previousTrusted != null
                        && validationService
                                .isSuspicious(
                                        previousTrusted
                                                .getMarketRate(),
                                        newRate,
                                        config
                                );

        String correlationId =
                UUID.randomUUID()
                        .toString();

        MarketRateSnapshot snapshot =
                new MarketRateSnapshot();

        snapshot.setCurrencyPair(
                "EUR/USD"
        );

        snapshot.setProviderName(
                "TWELVE_DATA"
        );

        snapshot.setMarketRate(
                newRate
        );

        snapshot.setProviderTimestamp(
                providerTimestamp
        );

        snapshot.setReceivedAt(
                now
        );

        snapshot.setMovementPercent(
                movement
        );

        snapshot.setCorrelationId(
                correlationId
        );

        snapshot.setCreatedAt(
                now
        );

        if (suspicious) {

            snapshot.setValidationStatus(
                    "SUSPICIOUS"
            );

            snapshot.setTrustedFlag(
                    "N"
            );

            snapshot.setValidationReason(
                    "Movement exceeded configured anomaly threshold"
            );

            MarketRateSnapshot saved =
                    rateRepository.save(
                            snapshot
                    );

            createAnomaly(
                    saved,
                    previousTrusted,
                    movement,
                    config
            );

            createAudit(
                    "RATE_ANOMALY_DETECTED",
                    "MARKET_RATE_SNAPSHOT",
                    saved.getId()
                            .toString(),
                    "SUSPICIOUS",
                    "Rate movement exceeded threshold",
                    correlationId
            );

            createOutbox(
                    "RATE_ANOMALY_DETECTED",
                    "MARKET_RATE_SNAPSHOT",
                    saved.getId()
                            .toString(),
                    "fx-system-events",
                    Map.of(
                            "rateSnapshotId",
                            saved.getId(),

                            "currencyPair",
                            "EUR/USD",

                            "rate",
                            newRate,

                            "movementPercent",
                            movement,

                            "thresholdPercent",
                            config.getAnomalyThresholdPercent()
                    ),
                    correlationId
            );

            return;
        }

        snapshot.setValidationStatus(
                "TRUSTED"
        );

        snapshot.setTrustedFlag(
                "Y"
        );

        snapshot.setValidationReason(
                "Provider rate passed validation"
        );

        MarketRateSnapshot saved =
                rateRepository.save(
                        snapshot
                );

        createAudit(
                "RATE_UPDATED",
                "MARKET_RATE_SNAPSHOT",
                saved.getId()
                        .toString(),
                "SUCCESS",
                "Trusted EUR/USD rate updated",
                correlationId
        );

        createOutbox(
                "RATE_UPDATED",
                "MARKET_RATE_SNAPSHOT",
                saved.getId()
                        .toString(),
                "fx-rate-events",
                Map.of(
                        "rateSnapshotId",
                        saved.getId(),

                        "currencyPair",
                        "EUR/USD",

                        "marketRate",
                        newRate,

                        "observationTime",
                        saved
                                .getEffectiveObservationTime()
                                .toString(),

                        "receivedAt",
                        now.toString()
                ),
                correlationId
        );

        updateCacheAfterCommit(
                saved
        );
    }

    private void validateStructure(
            TwelveDataResponse response
    ) {

        if (response == null) {

            throw new IllegalArgumentException(
                    "Empty provider response"
            );
        }

        if (response.rate() == null
                || response
                        .rate()
                        .isBlank()) {

            throw new IllegalArgumentException(
                    "Provider response missing rate"
            );
        }

        if (response.symbol() == null
                || response
                        .symbol()
                        .isBlank()) {

            throw new IllegalArgumentException(
                    "Provider response missing currency pair"
            );
        }

        if (!"EUR/USD".equalsIgnoreCase(
                response.symbol()
        )) {

            throw new IllegalArgumentException(
                    "Unexpected currency pair: "
                            + response.symbol()
            );
        }
    }

    private BigDecimal parseRate(
            String rate
    ) {

        try {

            BigDecimal parsed =
                    new BigDecimal(
                            rate
                    );

            if (parsed.compareTo(
                    BigDecimal.ZERO
            ) <= 0) {

                throw new IllegalArgumentException(
                        "Provider returned non-positive rate"
                );
            }

            return parsed;

        } catch (NumberFormatException ex) {

            throw new IllegalArgumentException(
                    "Provider returned malformed rate value",
                    ex
            );
        }
    }

    private OffsetDateTime parseProviderTimestamp(
            Long timestamp
    ) {

        if (timestamp == null) {
            return null;
        }

        try {

            return OffsetDateTime
                    .ofInstant(
                            Instant.ofEpochSecond(
                                    timestamp
                            ),
                            ZoneOffset.UTC
                    );

        } catch (Exception ex) {

            throw new IllegalArgumentException(
                    "Provider returned invalid timestamp",
                    ex
            );
        }
    }

    private void recordDuplicateObservation(
            MarketRateSnapshot previousTrusted
    ) {

        String correlationId =
                UUID.randomUUID()
                        .toString();

        createAudit(
                "RATE_OBSERVATION_DUPLICATE",
                "MARKET_RATE_SNAPSHOT",
                previousTrusted == null
                        ? "UNKNOWN"
                        : previousTrusted
                                .getId()
                                .toString(),
                "IGNORED",
                "Duplicate provider observation ignored; trusted observation age was not refreshed",
                correlationId
        );
    }

    private void handleRejectedObservation(
            BigDecimal newRate,
            OffsetDateTime providerTimestamp,
            OffsetDateTime receivedAt,
            MarketRateSnapshot previousTrusted,
            ObservationOrder observationOrder
    ) {

        String correlationId =
                UUID.randomUUID()
                        .toString();

        MarketRateSnapshot snapshot =
                new MarketRateSnapshot();

        snapshot.setCurrencyPair(
                "EUR/USD"
        );

        snapshot.setProviderName(
                "TWELVE_DATA"
        );

        snapshot.setMarketRate(
                newRate
        );

        snapshot.setProviderTimestamp(
                providerTimestamp
        );

        snapshot.setReceivedAt(
                receivedAt
        );

        snapshot.setValidationStatus(
                "INVALID"
        );

        snapshot.setTrustedFlag(
                "N"
        );

        snapshot.setMovementPercent(
                previousTrusted == null
                        ? BigDecimal.ZERO
                        : validationService
                                .calculateMovementPercent(
                                        previousTrusted
                                                .getMarketRate(),
                                        newRate
                                )
        );

        String reason =
                switch (observationOrder) {

                    case OUT_OF_ORDER ->
                            "Provider observation timestamp is older than current trusted observation";

                    case CONFLICTING_DUPLICATE ->
                            "Same provider timestamp returned with a different market rate";

                    case DUPLICATE ->
                            "Duplicate provider observation";

                    default ->
                            "Invalid provider observation ordering";
                };

        snapshot.setValidationReason(
                reason
        );

        snapshot.setCorrelationId(
                correlationId
        );

        snapshot.setCreatedAt(
                receivedAt
        );

        MarketRateSnapshot saved =
                rateRepository.save(
                        snapshot
                );

        createAudit(
                "RATE_OBSERVATION_REJECTED",
                "MARKET_RATE_SNAPSHOT",
                saved.getId()
                        .toString(),
                "REJECTED",
                reason,
                correlationId
        );

        createOutbox(
                "RATE_OBSERVATION_REJECTED",
                "MARKET_RATE_SNAPSHOT",
                saved.getId()
                        .toString(),
                "fx-system-events",
                Map.of(
                        "rateSnapshotId",
                        saved.getId(),

                        "currencyPair",
                        "EUR/USD",

                        "marketRate",
                        newRate,

                        "reason",
                        reason
                ),
                correlationId
        );
    }

    private void createAnomaly(
            MarketRateSnapshot suspicious,
            MarketRateSnapshot previousTrusted,
            BigDecimal movement,
            FxConfig config
    ) {

        RateAnomaly anomaly =
                new RateAnomaly();

        anomaly.setRateSnapshotId(
                suspicious.getId()
        );

        anomaly.setPreviousTrustedRateId(
                previousTrusted == null
                        ? null
                        : previousTrusted
                                .getId()
        );

        anomaly.setMovementPercent(
                movement
        );

        anomaly.setThresholdPercent(
                config.getAnomalyThresholdPercent()
        );

        anomaly.setStatus(
                "OPEN"
        );

        anomaly.setReason(
                "Movement exceeds configured anomaly threshold"
        );

        anomaly.setCreatedAt(
                OffsetDateTime.now(
                        ZoneOffset.UTC
                )
        );

        anomalyRepository.save(
                anomaly
        );
    }

    private void createAudit(
            String eventType,
            String entityType,
            String entityId,
            String status,
            String details,
            String correlationId
    ) {

        AuditEvent event =
                new AuditEvent();

        event.setEventType(
                eventType
        );

        event.setEntityType(
                entityType
        );

        event.setEntityId(
                entityId
        );

        event.setActorType(
                "SYSTEM"
        );

        event.setActorId(
                "rate-service"
        );

        event.setEventStatus(
                status
        );

        event.setDetails(
                details
        );

        event.setCorrelationId(
                correlationId
        );

        event.setCreatedAt(
                OffsetDateTime.now(
                        ZoneOffset.UTC
                )
        );

        auditRepository.save(
                event
        );
    }

    private void createOutbox(
            String eventType,
            String aggregateType,
            String aggregateId,
            String topic,
            Map<String, Object> payload,
            String correlationId
    ) {

        OutboxEvent event =
                new OutboxEvent();

        event.setId(
                UUID.randomUUID()
                        .toString()
        );

        event.setAggregateType(
                aggregateType
        );

        event.setAggregateId(
                aggregateId
        );

        event.setEventType(
                eventType
        );

        event.setTopicName(
                topic
        );

        try {

            event.setPayload(
                    objectMapper
                            .writeValueAsString(
                                    payload
                            )
            );

        } catch (JsonProcessingException ex) {

            throw new IllegalStateException(
                    "Could not serialize outbox payload",
                    ex
            );
        }

        event.setStatus(
                "PENDING"
        );

        event.setRetryCount(
                0
        );

        event.setCreatedAt(
                OffsetDateTime.now(
                        ZoneOffset.UTC
                )
        );

        event.setCorrelationId(
                correlationId
        );

        outboxRepository.save(
                event
        );
    }

    private void updateCacheAfterCommit(
            MarketRateSnapshot snapshot
    ) {

        TransactionSynchronizationManager
                .registerSynchronization(
                        new TransactionSynchronization() {

                            @Override
                            public void afterCommit() {

                                cache.update(
                                        snapshot
                                );
                            }
                        }
                );
    }
}