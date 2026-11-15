package com.queueguard.gateway.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Security audit trail: every denied/suspicious request is recorded (gateway's own database). */
@Entity
@Table(name = "audit_log", indexes = @Index(name = "idx_audit_ip_time", columnList = "ip,createdAt"))
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 36)
    private String requestId;

    @Column(nullable = false, length = 64)
    private String ip;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, length = 300)
    private String path;

    @Column(nullable = false)
    private int status;

    @Column(nullable = false, length = 30)
    private String decision;

    @Column(length = 120)
    private String userId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Instant getCreatedAt() { return createdAt; }
}
