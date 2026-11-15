package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Represents assignment of a queue entry to an operator or service point.
 */
@Entity
@Table(name = "queue_assignments")
public class QueueAssignment {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;
    
    @Column(name = "queue_entry_id", nullable = false)
    private Long queueEntryId;
    
    @Column(name = "operator_user_id", nullable = false)
    private Long operatorUserId;
    
    @Column(name = "service_point", length = 100)
    private String servicePoint;
    
    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt = Instant.now();
    
    @Column(name = "released_at")
    private Instant releasedAt;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QueueAssignmentStatus status = QueueAssignmentStatus.ASSIGNED;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    
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
    
    public Long getQueueEntryId() {
        return queueEntryId;
    }
    
    public void setQueueEntryId(Long queueEntryId) {
        this.queueEntryId = queueEntryId;
    }
    
    public Long getOperatorUserId() {
        return operatorUserId;
    }
    
    public void setOperatorUserId(Long operatorUserId) {
        this.operatorUserId = operatorUserId;
    }
    
    public String getServicePoint() {
        return servicePoint;
    }
    
    public void setServicePoint(String servicePoint) {
        this.servicePoint = servicePoint;
    }
    
    public Instant getAssignedAt() {
        return assignedAt;
    }
    
    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }
    
    public Instant getReleasedAt() {
        return releasedAt;
    }
    
    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }
    
    public QueueAssignmentStatus getStatus() {
        return status;
    }
    
    public void setStatus(QueueAssignmentStatus status) {
        this.status = status;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Long getVersion() {
        return version;
    }
    
    public void setVersion(Long version) {
        this.version = version;
    }
    
    // Business methods
    
    public boolean isActive() {
        return status == QueueAssignmentStatus.ASSIGNED || status == QueueAssignmentStatus.ACTIVE;
    }
    
    public boolean isReleased() {
        return status == QueueAssignmentStatus.RELEASED || status == QueueAssignmentStatus.COMPLETED;
    }
}
