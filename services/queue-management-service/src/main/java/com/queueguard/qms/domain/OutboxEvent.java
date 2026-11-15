package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Transactional outbox event for reliable event publishing.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;
    
    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;
    
    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;
    
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;
    
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    
    @Column(name = "published_at")
    private Instant publishedAt;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private OutboxEventStatus processingStatus = OutboxEventStatus.PENDING;
    
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;
    
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
    
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;
    
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
    
    // Getters and Setters
    
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public Long getOrganizationId() {
        return organizationId;
    }
    
    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }
    
    public String getAggregateType() {
        return aggregateType;
    }
    
    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }
    
    public Long getAggregateId() {
        return aggregateId;
    }
    
    public void setAggregateId(Long aggregateId) {
        this.aggregateId = aggregateId;
    }
    
    public String getEventType() {
        return eventType;
    }
    
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }
    
    public String getPayload() {
        return payload;
    }
    
    public void setPayload(String payload) {
        this.payload = payload;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getPublishedAt() {
        return publishedAt;
    }
    
    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
    
    public OutboxEventStatus getProcessingStatus() {
        return processingStatus;
    }
    
    public void setProcessingStatus(OutboxEventStatus processingStatus) {
        this.processingStatus = processingStatus;
    }
    
    public Integer getRetryCount() {
        return retryCount;
    }
    
    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }
    
    public String getLastError() {
        return lastError;
    }
    
    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
    
    public String getIdempotencyKey() {
        return idempotencyKey;
    }
    
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
    
    public Long getVersion() {
        return version;
    }
    
    public void setVersion(Long version) {
        this.version = version;
    }
    
    // Business methods
    
    public boolean isPending() {
        return processingStatus == OutboxEventStatus.PENDING;
    }
    
    public boolean isPublished() {
        return processingStatus == OutboxEventStatus.PUBLISHED;
    }
    
    public boolean isFailed() {
        return processingStatus == OutboxEventStatus.FAILED;
    }
}
