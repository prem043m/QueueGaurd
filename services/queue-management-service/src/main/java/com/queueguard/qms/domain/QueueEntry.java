package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Represents one person waiting for service in a business queue.
 */
@Entity
@Table(name = "queue_entries",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_queue_entry_org_id_config",
                columnNames = {"organization_id", "id", "queue_configuration_id"}))
public class QueueEntry {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;
    
    @Column(name = "queue_configuration_id", nullable = false)
    private Long queueConfigurationId;
    
    @Column(name = "token_number", nullable = false, length = 50)
    private String tokenNumber;
    
    @Column(name = "external_reference", length = 255)
    private String externalReference;
    
    @Column(name = "participant_user_id")
    private Long participantUserId;
    
    @Column(name = "participant_name", nullable = false, length = 255)
    private String participantName;
    
    @Column(name = "participant_email", length = 255)
    private String participantEmail;
    
    @Column(name = "priority")
    private Integer priority = 0;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QueueEntryStatus status = QueueEntryStatus.WAITING;
    
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();
    
    @Column(name = "called_at")
    private Instant calledAt;
    
    @Column(name = "service_started_at")
    private Instant serviceStartedAt;
    
    @Column(name = "completed_at")
    private Instant completedAt;
    
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    
    @Column(name = "estimated_wait_seconds")
    private Integer estimatedWaitSeconds;
    
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
    
    public Long getQueueConfigurationId() {
        return queueConfigurationId;
    }
    
    public void setQueueConfigurationId(Long queueConfigurationId) {
        this.queueConfigurationId = queueConfigurationId;
    }
    
    public String getTokenNumber() {
        return tokenNumber;
    }
    
    public void setTokenNumber(String tokenNumber) {
        this.tokenNumber = tokenNumber;
    }
    
    public String getExternalReference() {
        return externalReference;
    }
    
    public void setExternalReference(String externalReference) {
        this.externalReference = externalReference;
    }
    
    public Long getParticipantUserId() {
        return participantUserId;
    }
    
    public void setParticipantUserId(Long participantUserId) {
        this.participantUserId = participantUserId;
    }
    
    public String getParticipantName() {
        return participantName;
    }
    
    public void setParticipantName(String participantName) {
        this.participantName = participantName;
    }
    
    public String getParticipantEmail() {
        return participantEmail;
    }
    
    public void setParticipantEmail(String participantEmail) {
        this.participantEmail = participantEmail;
    }
    
    public Integer getPriority() {
        return priority;
    }
    
    public void setPriority(Integer priority) {
        this.priority = priority;
    }
    
    public QueueEntryStatus getStatus() {
        return status;
    }
    
    public void setStatus(QueueEntryStatus status) {
        this.status = status;
    }
    
    public Instant getJoinedAt() {
        return joinedAt;
    }
    
    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }
    
    public Instant getCalledAt() {
        return calledAt;
    }
    
    public void setCalledAt(Instant calledAt) {
        this.calledAt = calledAt;
    }
    
    public Instant getServiceStartedAt() {
        return serviceStartedAt;
    }
    
    public void setServiceStartedAt(Instant serviceStartedAt) {
        this.serviceStartedAt = serviceStartedAt;
    }
    
    public Instant getCompletedAt() {
        return completedAt;
    }
    
    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
    
    public Instant getCancelledAt() {
        return cancelledAt;
    }
    
    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }
    
    public Integer getEstimatedWaitSeconds() {
        return estimatedWaitSeconds;
    }
    
    public void setEstimatedWaitSeconds(Integer estimatedWaitSeconds) {
        this.estimatedWaitSeconds = estimatedWaitSeconds;
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
    
    public boolean isWaiting() {
        return status == QueueEntryStatus.WAITING;
    }
    
    public boolean isCalled() {
        return status == QueueEntryStatus.CALLED;
    }
    
    public boolean isInService() {
        return status == QueueEntryStatus.IN_SERVICE;
    }
    
    public boolean isCompleted() {
        return status == QueueEntryStatus.COMPLETED;
    }
    
    public boolean isCancelled() {
        return status == QueueEntryStatus.CANCELLED;
    }
    
    public boolean isNoShow() {
        return status == QueueEntryStatus.NO_SHOW;
    }
    
    public boolean isSkipped() {
        return status == QueueEntryStatus.SKIPPED;
    }
    
    public boolean isTerminal() {
        return isCompleted() || isCancelled() || isNoShow() || isSkipped();
    }
    
    public boolean isEligibleForCall() {
        return isWaiting() || isCalled();
    }
    
    public boolean isActive() {
        return isCalled() || isInService();
    }
}
