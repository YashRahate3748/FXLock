package com.fxlock.events;
import java.time.OffsetDateTime;
public record OutboxRow(String id,String aggregateType,String aggregateId,String eventType,
 String topic,String payload,String correlationId,OffsetDateTime createdAt) {}
