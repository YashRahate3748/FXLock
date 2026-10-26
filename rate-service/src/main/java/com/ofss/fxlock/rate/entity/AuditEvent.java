package com.ofss.fxlock.rate.entity;
import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "AUDIT_EVENT")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AUDIT_ID")
    private Long id;

    @Column(name = "EVENT_TYPE", nullable = false)
    private String eventType;

    @Column(name = "ENTITY_TYPE", nullable = false)
    private String entityType;

    @Column(name = "ENTITY_ID")
    private String entityId;

    @Column(name = "ACTOR_TYPE")
    private String actorType;

    @Column(name = "ACTOR_ID")
    private String actorId;

    @Column(name = "EVENT_STATUS")
    private String eventStatus;

    @Lob
    @Column(name = "DETAILS")
    private String details;

    @Column(name = "CORRELATION_ID")
    private String correlationId;

    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public void setEventStatus(String eventStatus) {
        this.eventStatus = eventStatus;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}