package com.ofss.fxlock.rate.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "OUTBOX_EVENT")
public class OutboxEvent {

    @Id
    @Column(name = "EVENT_ID")
    private String id;

    @Column(name = "AGGREGATE_TYPE", nullable = false)
    private String aggregateType;

    @Column(name = "AGGREGATE_ID", nullable = false)
    private String aggregateId;

    @Column(name = "EVENT_TYPE", nullable = false)
    private String eventType;

    @Column(name = "TOPIC_NAME", nullable = false)
    private String topicName;

    @Lob
    @Column(name = "PAYLOAD", nullable = false)
    private String payload;

    @Column(name = "STATUS", nullable = false)
    private String status;

    @Column(name = "RETRY_COUNT", nullable = false)
    private Integer retryCount;

    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "CORRELATION_ID")
    private String correlationId;

    public void setId(String id) {
        this.id = id;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}