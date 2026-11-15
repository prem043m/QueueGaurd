package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Represents an organization's business queue configuration.
 */
@Entity
@Table(name = "queue_configurations")
public class QueueConfiguration {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;
    
    @Column(name = "name", nullable = false, length = 255)
    private String name;
    
    @Column(name = "description", length = 1000)
    private String description;
    
    @Column(name = "queue_code", nullable = false, length = 50)
    private String queueCode;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "queue_type", nullable = false, length = 20)
    private QueueType queueType = QueueType.GENERIC;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "ordering_policy", nullable = false, length = 20)
    private OrderingPolicy orderingPolicy = OrderingPolicy.FIFO;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QueueStatus status = QueueStatus.DRAFT;
    
    @Column(name = "maximum_capacity")
    private Integer maximumCapacity;
    
    @Column(name = "priority_enabled", nullable = false)
    private boolean priorityEnabled = false;
    
    @Column(name = "estimated_service_duration_seconds")
    private Integer estimatedServiceDurationSeconds;
    
    @Column(name = "created_by", nullable = false)
    private Long createdBy;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
    
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
    
    @PreUpdate
    void touched() {
        updatedAt = Instant.now();
    }
    
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
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getQueueCode() {
        return queueCode;
    }
    
    public void setQueueCode(String queueCode) {
        this.queueCode = queueCode;
    }
    
    public QueueType getQueueType() {
        return queueType;
    }
    
    public void setQueueType(QueueType queueType) {
        this.queueType = queueType;
    }
    
    public OrderingPolicy getOrderingPolicy() {
        return orderingPolicy;
    }
    
    public void setOrderingPolicy(OrderingPolicy orderingPolicy) {
        this.orderingPolicy = orderingPolicy;
    }
    
    public QueueStatus getStatus() {
        return status;
    }
    
    public void setStatus(QueueStatus status) {
        this.status = status;
    }
    
    public Integer getMaximumCapacity() {
        return maximumCapacity;
    }
    
    public void setMaximumCapacity(Integer maximumCapacity) {
        this.maximumCapacity = maximumCapacity;
    }
    
    public boolean isPriorityEnabled() {
        return priorityEnabled;
    }
    
    public void setPriorityEnabled(boolean priorityEnabled) {
        this.priorityEnabled = priorityEnabled;
    }
    
    public Integer getEstimatedServiceDurationSeconds() {
        return estimatedServiceDurationSeconds;
    }
    
    public void setEstimatedServiceDurationSeconds(Integer estimatedServiceDurationSeconds) {
        this.estimatedServiceDurationSeconds = estimatedServiceDurationSeconds;
    }
    
    public Long getCreatedBy() {
        return createdBy;
    }
    
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public Long getVersion() {
        return version;
    }
    
    public void setVersion(Long version) {
        this.version = version;
    }
    
    // Business methods
    
    public boolean isOpen() {
        return status == QueueStatus.OPEN;
    }
    
    public boolean isClosed() {
        return status == QueueStatus.CLOSED || status == QueueStatus.ARCHIVED;
    }
    
    public boolean isPaused() {
        return status == QueueStatus.PAUSED;
    }
    
    public boolean canAcceptRegistrations() {
        return isOpen() && !isPaused();
    }
}
