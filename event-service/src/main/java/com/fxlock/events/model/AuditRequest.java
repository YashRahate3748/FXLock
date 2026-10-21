package com.fxlock.events.model;
import jakarta.validation.constraints.NotBlank;
public record AuditRequest(@NotBlank String eventType, @NotBlank String entityType, String entityId,
                           String actorType, String actorId, String eventStatus, String details,
                           String correlationId) {}
