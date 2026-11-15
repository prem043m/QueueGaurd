package com.queueguard.monitor.entity;

import jakarta.persistence.*;
import java.time.Instant;

public final class MonitorEntities {

    private MonitorEntities() {
    }

    @Entity(name = "HealthCheck")
    @Table(name = "health_checks", indexes = @Index(name = "idx_hc_svc_time", columnList = "service,checkedAt"))
    public static class HealthCheck {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false, length = 60)
        private String service;

        @Column(nullable = false, length = 16)
        private String status;

        @Column(nullable = false)
        private long latencyMs;

        @Column(nullable = false)
        private Instant checkedAt = Instant.now();

        public Long getId() { return id; }
        public String getService() { return service; }
        public void setService(String service) { this.service = service; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
        public Instant getCheckedAt() { return checkedAt; }
        public void setCheckedAt(Instant checkedAt) { this.checkedAt = checkedAt; }
    }

    @Entity(name = "MetricSample")
    @Table(name = "metric_samples", indexes = @Index(name = "idx_ms_svc_time", columnList = "service,receivedAt"))
    public static class MetricSample {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false, length = 60)
        private String service;

        private Long processed;
        private Long failed;
        private Long inFlight;
        private Long queueDepth;

        @Column(nullable = false)
        private Instant receivedAt = Instant.now();

        public Long getId() { return id; }
        public String getService() { return service; }
        public void setService(String service) { this.service = service; }
        public Long getProcessed() { return processed; }
        public void setProcessed(Long processed) { this.processed = processed; }
        public Long getFailed() { return failed; }
        public void setFailed(Long failed) { this.failed = failed; }
        public Long getInFlight() { return inFlight; }
        public void setInFlight(Long inFlight) { this.inFlight = inFlight; }
        public Long getQueueDepth() { return queueDepth; }
        public void setQueueDepth(Long queueDepth) { this.queueDepth = queueDepth; }
        public Instant getReceivedAt() { return receivedAt; }
    }

    @Entity(name = "LoadSnapshot")
    @Table(name = "load_snapshots")
    public static class LoadSnapshot {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false)
        private double loadScore;

        @Column(nullable = false)
        private long queueDepth;

        @Column(nullable = false)
        private long inFlight;

        @Column(nullable = false)
        private double errorRate;

        @Column(nullable = false)
        private long p95LatencyMs;

        @Column(nullable = false)
        private Instant createdAt = Instant.now();

        public Long getId() { return id; }
        public double getLoadScore() { return loadScore; }
        public void setLoadScore(double loadScore) { this.loadScore = loadScore; }
        public long getQueueDepth() { return queueDepth; }
        public void setQueueDepth(long queueDepth) { this.queueDepth = queueDepth; }
        public long getInFlight() { return inFlight; }
        public void setInFlight(long inFlight) { this.inFlight = inFlight; }
        public double getErrorRate() { return errorRate; }
        public void setErrorRate(double errorRate) { this.errorRate = errorRate; }
        public long getP95LatencyMs() { return p95LatencyMs; }
        public void setP95LatencyMs(long p95LatencyMs) { this.p95LatencyMs = p95LatencyMs; }
        public Instant getCreatedAt() { return createdAt; }
    }
}
