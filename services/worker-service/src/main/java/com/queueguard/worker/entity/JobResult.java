package com.queueguard.worker.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Durable record of every processed job (the worker's own database). */
@Entity
@Table(name = "job_results", indexes = @Index(name = "idx_job_msg", columnList = "messageId"))
public class JobResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String messageId;

    @Column(nullable = false, length = 120)
    private String queueName;

    @Column(nullable = false, length = 120)
    private String workerId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private long durationMs;

    @Column(columnDefinition = "text")
    private String error;

    @Column(nullable = false)
    private Instant processedAt = Instant.now();

    public Long getId() { return id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getQueueName() { return queueName; }
    public void setQueueName(String queueName) { this.queueName = queueName; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getProcessedAt() { return processedAt; }
}
