package com.ofss.fxlock.rate.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ofss.fxlock.rate.client.TwelveDataClient;
import com.ofss.fxlock.rate.dto.TwelveDataResponse;
import com.ofss.fxlock.rate.entity.AuditEvent;
import com.ofss.fxlock.rate.entity.OutboxEvent;
import com.ofss.fxlock.rate.repository.AuditEventRepository;
import com.ofss.fxlock.rate.repository.OutboxEventRepository;

@Service
public class RateIngestionService {

    private final TwelveDataClient twelveDataClient;
    private final RatePersistenceService persistenceService;
    private final ProviderStateService providerStateService;

    private final AuditEventRepository auditRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public RateIngestionService(
            TwelveDataClient twelveDataClient,
            RatePersistenceService persistenceService,
            ProviderStateService providerStateService,
            AuditEventRepository auditRepository,
            OutboxEventRepository outboxRepository,
            ObjectMapper objectMapper) {

        this.twelveDataClient = twelveDataClient;
        this.persistenceService = persistenceService;
        this.providerStateService =
                providerStateService;
        this.auditRepository = auditRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void fetchAndProcess() {

        try {

            TwelveDataResponse response =
                    twelveDataClient.fetchRate();

            persistenceService
                    .processProviderResponse(response);

            providerStateService.markSuccess();

        } catch (Exception ex) {

            providerStateService.markFailure();

            recordProviderFailure(
                    ex.getMessage()
            );
        }
    }

    @Transactional
    public void recordProviderFailure(
            String message) {

        String correlationId =
                UUID.randomUUID().toString();

        AuditEvent audit =
                new AuditEvent();

        audit.setEventType(
                "PROVIDER_UNAVAILABLE"
        );

        audit.setEntityType(
                "FX_PROVIDER"
        );

        audit.setEntityId(
                "TWELVE_DATA"
        );

        audit.setActorType(
                "SYSTEM"
        );

        audit.setActorId(
                "rate-service"
        );

        audit.setEventStatus(
                "FAILED"
        );

        audit.setDetails(
                safeMessage(message)
        );

        audit.setCorrelationId(
                correlationId
        );

        audit.setCreatedAt(
                OffsetDateTime.now(
                        ZoneOffset.UTC
                )
        );

        auditRepository.save(audit);

        OutboxEvent outbox =
                new OutboxEvent();

        outbox.setId(
                UUID.randomUUID().toString()
        );

        outbox.setAggregateType(
                "FX_PROVIDER"
        );

        outbox.setAggregateId(
                "TWELVE_DATA"
        );

        outbox.setEventType(
                "PROVIDER_UNAVAILABLE"
        );

        outbox.setTopicName(
                "fx-system-events"
        );

        try {

            outbox.setPayload(
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "provider",
                                    "TWELVE_DATA",
                                    "status",
                                    "DEGRADED"
                            )
                    )
            );

        } catch (JsonProcessingException ex) {

            throw new IllegalStateException(
                    "Could not serialize payload",
                    ex
            );
        }

        outbox.setStatus(
                "PENDING"
        );

        outbox.setRetryCount(0);

        outbox.setCreatedAt(
                OffsetDateTime.now(
                        ZoneOffset.UTC
                )
        );

        outbox.setCorrelationId(
                correlationId
        );

        outboxRepository.save(outbox);
    }

    private String safeMessage(
            String message) {

        if (message == null) {
            return "Provider request failed";
        }

        return message.length() > 500
                ? message.substring(0, 500)
                : message;
    }
}