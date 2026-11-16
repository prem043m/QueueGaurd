package com.queueguard.qms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;

class ConsumerEventDeserializerTest {
    private final ConsumerEventDeserializer deserializer =
            new ConsumerEventDeserializer(new ObjectMapper());

    @Test
    void parsesPhase37EnvelopeAndRetainsOriginalFields() {
        ConsumerEvent event = deserializer.deserialize(record(Map.of(
                "eventId", "81",
                "eventType", "EVALUATION_SUBMITTED",
                "organizationId", "9",
                "aggregateType", "InterviewEvaluation",
                "aggregateId", "35",
                "payload", "{\"organizationId\":9,\"evaluationId\":35}",
                "createdAt", "2026-11-16T10:00:00Z",
                "idempotencyKey", "evaluation-submitted:9:35")));

        assertEquals("81", event.eventId());
        assertEquals("EVALUATION_SUBMITTED", event.eventType());
        assertEquals(9L, event.organizationId());
        assertEquals(35L, event.aggregateId());
        assertEquals(35L, event.payload().path("evaluationId").asLong());
        assertEquals("1-0", event.streamMessageId());
        assertEquals("evaluation-submitted:9:35", event.idempotencyKey());
    }

    @Test
    void rejectsMalformedIdentityTenantAndPayload() {
        assertThrows(PermanentEventException.class, () -> deserializeWith("eventId", "x"));
        assertThrows(PermanentEventException.class,
                () -> deserializeWith("organizationId", "0"));
        assertThrows(PermanentEventException.class,
                () -> deserializeWith("payload", "{not-json"));
        assertThrows(PermanentEventException.class,
                () -> deserializer.deserialize(record(Map.of())));
    }

    private void deserializeWith(String name, String value) {
        Map<String, String> fields = validFields();
        fields.put(name, value);
        deserializer.deserialize(record(fields));
    }

    private static Map<String, String> validFields() {
        return new java.util.LinkedHashMap<>(Map.of(
                "eventId", "81",
                "eventType", "EVALUATION_SUBMITTED",
                "organizationId", "9",
                "aggregateType", "InterviewEvaluation",
                "aggregateId", "35",
                "payload", "{\"evaluationId\":35}",
                "createdAt", "2026-11-16T10:00:00Z"));
    }

    private static MapRecord<String, String, String> record(Map<String, String> fields) {
        return StreamRecords.newRecord()
                .in("qg:test:outbox")
                .ofMap(fields)
                .withId(RecordId.of("1-0"));
    }
}
