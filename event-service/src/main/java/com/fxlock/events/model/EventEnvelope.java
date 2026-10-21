package com.fxlock.events.model;
import java.time.Instant;
public record EventEnvelope(String eventId, String eventType, String aggregateType, String aggregateId,
                            Instant occurredAt, String correlationId, Object data) {}
