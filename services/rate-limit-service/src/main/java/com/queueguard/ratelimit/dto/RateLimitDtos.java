package com.queueguard.ratelimit.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class RateLimitDtos {

    private RateLimitDtos() {
    }

    public record CheckRequest(
            @NotBlank @Size(max = 64) String ip,
            @Size(max = 120) String userId,
            @Size(max = 300) String path,
            Long cost) {
    }

    public record CheckDecision(boolean allowed, String ip, String userId,
                                double limitPerSec, double burst, double remaining,
                                long retryAfterMs, double multiplier, boolean banned, String message) {
    }

    public record LoadReport(Double loadScore, Long queueDepth, Long inFlight,
                             @DecimalMin("0.0") @DecimalMax("1.0") Double errorRate, Long p95LatencyMs) {
    }

    public record AdaptiveView(double multiplier, double targetMultiplier, double loadScore,
                               double baseLimitPerSec, double effectiveLimitPerSec, String updatedAt) {
    }

    public record BanView(String ip, int strikes, String bannedUntil, String reason, String updatedAt) {
    }

    public record IpStateView(String ip, int strikes, boolean banned, String bannedUntil,
                              double tokensRemaining, String multiplier) {
    }

    public record BanListResponse(int activeBans, List<BanView> bans) {
    }
}
