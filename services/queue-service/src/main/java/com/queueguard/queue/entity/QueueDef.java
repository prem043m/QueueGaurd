package com.queueguard.queue.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "queue_defs", indexes = @Index(name = "idx_queue_name", columnList = "name", unique = true))
public class QueueDef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    /** Maximum messages concurrently in-flight (CLAIMED) for this queue. */
    @Column(nullable = false)
    private int maxConcurrency = 32;

    /** Attempts before a message is moved to the dead-letter queue. */
    @Column(nullable = false)
    private int maxAttempts = 5;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Instant getCreatedAt() { return createdAt; }
}
