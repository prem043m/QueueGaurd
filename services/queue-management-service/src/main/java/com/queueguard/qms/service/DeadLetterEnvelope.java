package com.queueguard.qms.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DeadLetterEnvelope {
    private DeadLetterEnvelope() {
    }

    public static Map<String, String> create(
            Map<String, String> originalFields,
            String sourceStream,
            String sourceMessageId,
            String consumerGroup,
            int attemptCount,
            String failureReason,
            Instant failedAt) {
        Map<String, String> fields = new LinkedHashMap<>(originalFields);
        fields.put("failureReason", failureReason);
        fields.put("attemptCount", Integer.toString(attemptCount));
        fields.put("failedAt", failedAt.toString());
        fields.put("sourceStream", sourceStream);
        fields.put("sourceMessageId", sourceMessageId);
        fields.put("consumerGroup", consumerGroup);
        return Map.copyOf(fields);
    }
}
