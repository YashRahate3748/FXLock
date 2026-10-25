package com.example.contract;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record EventEnvelope(UUID eventId, int schemaVersion, String eventType,
        String aggregateId, String source, String correlationId, Instant occurredAt, JsonNode payload) {
    public static final Set<String> TOPICS = Set.of("fx-rate-updated", "fx-settlement-completed", "fx-system-alert");
    public EventEnvelope {
        if (eventId == null || occurredAt == null || schemaVersion != 1 || eventType == null || !TOPICS.contains(eventType)
                || blank(aggregateId) || blank(source) || blank(correlationId) || payload == null || !payload.isObject())
            throw new IllegalArgumentException("Invalid v1 event envelope");
        if (aggregateId.length() > 200 || source.length() > 100 || correlationId.length() > 100)
            throw new IllegalArgumentException("Event metadata too long");
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
