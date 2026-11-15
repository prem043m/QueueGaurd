package com.queueguard.qms.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * Database sequence tracker for token generation.
 * Uses optimistic locking to ensure unique token sequences under concurrent registration.
 * 
 * This entity tracks the last used sequence number for each queue code per date.
 * The combination of (organization_id, queue_code, date) is unique.
 */
@Entity
@Table(name = "token_sequences")
public class TokenSequence {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;
    
    @Column(name = "queue_code", nullable = false, length = 50)
    private String queueCode;
    
    @Column(name = "date_part", nullable = false, length = 8)
    private String datePart;
    
    @Column(name = "last_sequence", nullable = false)
    private Integer lastSequence = 0;
    
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
    
    @Column(name = "created_at", nullable = false)
    private LocalDate createdAt = LocalDate.now();
    
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
    
    public String getQueueCode() {
        return queueCode;
    }
    
    public void setQueueCode(String queueCode) {
        this.queueCode = queueCode;
    }
    
    public String getDatePart() {
        return datePart;
    }
    
    public void setDatePart(String datePart) {
        this.datePart = datePart;
    }
    
    public Integer getLastSequence() {
        return lastSequence;
    }
    
    public void setLastSequence(Integer lastSequence) {
        this.lastSequence = lastSequence;
    }
    
    public Long getVersion() {
        return version;
    }
    
    public void setVersion(Long version) {
        this.version = version;
    }
    
    public LocalDate getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDate createdAt) {
        this.createdAt = createdAt;
    }
    
    /**
     * Increment and return the next sequence number.
     * This method should be called within a transaction with the sequence row locked.
     */
    public int next() {
        this.lastSequence = this.lastSequence + 1;
        return this.lastSequence;
    }
    
    /**
     * Create a unique key for this sequence.
     */
    public String getSequenceKey() {
        return organizationId + "-" + queueCode + "-" + datePart;
    }
}
