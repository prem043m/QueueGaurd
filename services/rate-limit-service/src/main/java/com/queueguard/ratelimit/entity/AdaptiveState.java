package com.queueguard.ratelimit.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Single-row state of the adaptive multiplier (the "intelligence" of the limiter). */
@Entity
@Table(name = "adaptive_state")
public class AdaptiveState {

    @Id
    private Long id = 1L;

    /** EWMA-smoothed multiplier in [0.2, 1.5]. */
    @Column(nullable = false)
    private double multiplier = 1.0;

    @Column(nullable = false)
    private double loadScore = 0.0;

    @Column(nullable = false)
    private double targetMultiplier = 1.0;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public double getMultiplier() { return multiplier; }
    public void setMultiplier(double multiplier) { this.multiplier = multiplier; }
    public double getLoadScore() { return loadScore; }
    public void setLoadScore(double loadScore) { this.loadScore = loadScore; }
    public double getTargetMultiplier() { return targetMultiplier; }
    public void setTargetMultiplier(double targetMultiplier) { this.targetMultiplier = targetMultiplier; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
