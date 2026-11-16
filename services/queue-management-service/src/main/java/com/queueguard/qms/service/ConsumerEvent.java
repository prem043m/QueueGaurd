package com.queueguard.qms.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

public record ConsumerEvent(
        String streamMessageId,
        String eventId,
        String eventType,
        Long organizationId,
        String aggregateType,
        Long aggregateId,
        JsonNode payload,
        Instant createdAt,
        String idempotencyKey,
        Map<String, String> originalFields) {
}
