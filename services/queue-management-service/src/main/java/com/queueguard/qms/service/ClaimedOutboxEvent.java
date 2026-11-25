package com.queueguard.qms.service;

import java.time.Instant;
import java.util.UUID;

public record ClaimedOutboxEvent(
        Long id,
        Long organizationId,
        String aggregateType,
        Long aggregateId,
        String eventType,
        String payload,
        Instant createdAt,
        String idempotencyKey,
        UUID claimToken,
        int attemptNumber,
        String traceparent,
        String tracestate) {
    public ClaimedOutboxEvent(
            Long id,
            Long organizationId,
            String aggregateType,
            Long aggregateId,
            String eventType,
            String payload,
            Instant createdAt,
            String idempotencyKey,
            UUID claimToken,
            int attemptNumber) {
        this(id, organizationId, aggregateType, aggregateId, eventType, payload,
                createdAt, idempotencyKey, claimToken, attemptNumber, null, null);
    }
}
