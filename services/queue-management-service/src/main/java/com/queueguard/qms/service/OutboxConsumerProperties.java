package com.queueguard.qms.service;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "queueguard.consumer")
public record OutboxConsumerProperties(
        boolean enabled,
        String streamKey,
        String groupName,
        String consumerName,
        String consumerIdentity,
        int batchSize,
        long blockingTimeoutMs,
        long pollingIntervalMs,
        long pendingRecoveryIntervalMs,
        long minimumPendingIdleMs,
        int maxAttempts,
        long initialRetryDelayMs,
        long maxRetryDelayMs,
        String deadLetterStreamKey) {

    public OutboxConsumerProperties {
        if (blank(streamKey) || blank(groupName) || blank(consumerName)
                || blank(consumerIdentity) || blank(deadLetterStreamKey)
                || batchSize < 1 || blockingTimeoutMs < 1 || pollingIntervalMs < 1
                || pendingRecoveryIntervalMs < 1 || minimumPendingIdleMs < 1
                || maxAttempts < 1 || initialRetryDelayMs < 1
                || maxRetryDelayMs < initialRetryDelayMs) {
            throw new IllegalArgumentException("Invalid QMS stream consumer settings");
        }
    }

    public String uniqueConsumerIdentity() {
        return consumerIdentity + "-" + UUID.randomUUID();
    }

    public long retryDelayMillis(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Attempt number must be positive");
        }
        long delay = Math.max(minimumPendingIdleMs, initialRetryDelayMs);
        for (int attempt = 1; attempt < attemptNumber && delay < maxRetryDelayMs; attempt++) {
            delay = delay > maxRetryDelayMs / 2
                    ? maxRetryDelayMs
                    : Math.min(delay * 2, maxRetryDelayMs);
        }
        return Math.min(delay, maxRetryDelayMs);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
