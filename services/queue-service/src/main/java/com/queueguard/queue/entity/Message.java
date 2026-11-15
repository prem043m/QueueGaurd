package com.queueguard.queue.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_msg_queue_status", columnList = "queueName,status"),
        @Index(name = "idx_msg_idem", columnList = "queueName,idempotencyKey", unique = true)
})
public class Message {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 120)
    private String queueName;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    /** 0..9; 9 = most urgent. */
    @Column(nullable = false)
    private int priority = 5;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MessageStatus status = MessageStatus.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(length = 120)
    private String idempotencyKey;

    @Column(length = 120)
    private String claimedBy;

    private Instant claimedAt;

    /** Earliest time the message may be claimed (retry backoff / scheduled enqueue). */
    @Column(nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(columnDefinition = "text")
    private String error;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private Long version;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getQueueName() { return queueName; }
    public void setQueueName(String queueName) { this.queueName = queueName; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public MessageStatus getStatus() { return status; }
    public void setStatus(MessageStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getClaimedBy() { return claimedBy; }
    public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
