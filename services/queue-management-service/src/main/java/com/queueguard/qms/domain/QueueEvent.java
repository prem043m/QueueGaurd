package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Represents a business queue state transition event for auditing.
 */
@Entity
@Table(name = "queue_events")
public class QueueEvent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;
    
    @Column(name = "queue_configuration_id")
    private Long queueConfigurationId;
    
    @Column(name = "queue_entry_id")
    private Long queueEntryId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private QueueEventType eventType;
    
    @Column(name = "actor_user_id")
    private Long actorUserId;
    
    @Column(name = "event_payload", columnDefinition = "TEXT")
    private String eventPayload;
    
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();
    
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
    
    public Long getQueueEntryId() {
        return queueEntryId;
    }
    
    public void setQueueEntryId(Long queueEntryId) {
        this.queueEntryId = queueEntryId;
    }
    
    public QueueEventType getEventType() {
        return eventType;
    }
    
    public void setEventType(QueueEventType eventType) {
        this.eventType = eventType;
    }
    
    public Long getActorUserId() {
        return actorUserId;
    }
    
    public void setActorUserId(Long actorUserId) {
        this.actorUserId = actorUserId;
    }
    
    public String getEventPayload() {
        return eventPayload;
    }
    
    public void setEventPayload(String eventPayload) {
        this.eventPayload = eventPayload;
    }
    
    public Instant getOccurredAt() {
        return occurredAt;
    }
    
    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
