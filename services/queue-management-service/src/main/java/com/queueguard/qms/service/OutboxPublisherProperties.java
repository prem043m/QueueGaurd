package com.queueguard.qms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "queueguard.outbox")
public record OutboxPublisherProperties(
        boolean enabled,
        String streamKey,
        int batchSize,
        long pollingIntervalMs,
        int maxAttempts,
        long initialRetryDelayMs,
        long maxRetryDelayMs,
        long leaseDurationMs) {

    public OutboxPublisherProperties {
        if (streamKey == null || streamKey.isBlank()
                || batchSize < 1
                || pollingIntervalMs < 1
                || maxAttempts < 1
                || initialRetryDelayMs < 1
                || maxRetryDelayMs < initialRetryDelayMs
                || leaseDurationMs < 1) {
            throw new IllegalArgumentException("Invalid transactional outbox publisher settings");
        }
    }

    public long retryDelayMillis(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Attempt number must be positive");
        }
        long delay = initialRetryDelayMs;
        for (int attempt = 1; attempt < attemptNumber && delay < maxRetryDelayMs; attempt++) {
            delay = delay > maxRetryDelayMs / 2
                    ? maxRetryDelayMs
                    : Math.min(delay * 2, maxRetryDelayMs);
        }
        return delay;
    }
}
