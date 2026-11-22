package com.queueguard.qms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxConsumerPropertiesTest {
    @Test
    void validatesSettingsAndGeneratesDistinctInstanceIdentities() {
        OutboxConsumerProperties properties = properties();

        assertNotEquals(properties.uniqueConsumerIdentity(),
                properties.uniqueConsumerIdentity());
        assertEquals(1000, properties.retryDelayMillis(1));
        assertEquals(2000, properties.retryDelayMillis(2));
        assertEquals(3000, properties.retryDelayMillis(5));
        assertThrows(IllegalArgumentException.class, () -> new OutboxConsumerProperties(
                false, " ", "group", "consumer", "instance", 10, 10,
                100, 100, 100, 3, 1000, 500, "dlq"));
    }

    @Test
    void deadLetterEnvelopePreservesSourceAndAddsFailureMetadata() {
        Map<String, String> envelope = DeadLetterEnvelope.create(
                Map.of("eventId", "701", "eventType", "EVALUATION_SUBMITTED"),
                "qg:qms:outbox",
                "1720000000000-0",
                "qg:qms:domain-events-v1",
                4,
                "processing failed (DataAccessException)",
                Instant.parse("2026-11-16T10:00:00Z"));

        assertEquals("701", envelope.get("eventId"));
        assertEquals("4", envelope.get("attemptCount"));
        assertEquals("1720000000000-0", envelope.get("sourceMessageId"));
        assertEquals("qg:qms:outbox", envelope.get("sourceStream"));
        assertEquals("qg:qms:domain-events-v1", envelope.get("consumerGroup"));
        assertEquals("2026-11-16T10:00:00Z", envelope.get("failedAt"));
    }

    private static OutboxConsumerProperties properties() {
        return new OutboxConsumerProperties(
                false, "stream", "group", "consumer", "instance", 10, 20,
                100, 100, 1000, 5, 1000, 3000, "dlq");
    }
}
