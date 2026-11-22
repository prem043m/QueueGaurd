package com.queueguard.qms.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

@Component
public class ConsumerEventDeserializer {
    private final ObjectMapper objectMapper;

    public ConsumerEventDeserializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ConsumerEvent deserialize(MapRecord<String, ?, ?> record) {
        Map<String, String> fields = stringFields(record.getValue());
        String eventId = required(fields, "eventId", 100);
        if (!eventId.matches("[1-9][0-9]*")) {
            throw new PermanentEventException("Invalid eventId");
        }
        String eventType = required(fields, "eventType", 100);
        String organization = required(fields, "organizationId", 20);
        String aggregateType = required(fields, "aggregateType", 100);
        String aggregate = required(fields, "aggregateId", 20);
        String createdAt = required(fields, "createdAt", 64);
        String payloadValue = required(fields, "payload", 100_000);

        JsonNode payload;
        try {
            payload = objectMapper.readTree(payloadValue);
        } catch (JsonProcessingException exception) {
            throw new PermanentEventException("Invalid event payload JSON", exception);
        }
        if (payload == null) {
            throw new PermanentEventException("Event payload is empty");
        }

        return new ConsumerEvent(
                record.getId().getValue(),
                eventId,
                eventType,
                positiveLong(organization, "organizationId"),
                aggregateType,
                positiveLong(aggregate, "aggregateId"),
                payload,
                parseInstant(createdAt),
                optional(fields, "idempotencyKey", 100),
                Map.copyOf(fields));
    }

    private static Map<String, String> stringFields(Map<?, ?> rawFields) {
        Map<String, String> fields = new LinkedHashMap<>();
        rawFields.forEach((key, value) -> {
            if (!(key instanceof String fieldName) || !(value instanceof String fieldValue)) {
                throw new PermanentEventException("Event fields must be strings");
            }
            fields.put(fieldName, fieldValue);
        });
        return fields;
    }

    private static String required(Map<String, String> fields, String name, int maxLength) {
        String value = fields.get(name);
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new PermanentEventException("Missing or invalid " + name);
        }
        return value;
    }

    private static String optional(Map<String, String> fields, String name, int maxLength) {
        String value = fields.get(name);
        if (value != null && (value.isBlank() || value.length() > maxLength)) {
            throw new PermanentEventException("Invalid " + name);
        }
        return value;
    }

    private static Long positiveLong(String value, String field) {
        try {
            long number = Long.parseLong(value);
            if (number <= 0) {
                throw new PermanentEventException("Invalid " + field);
            }
            return number;
        } catch (NumberFormatException exception) {
            throw new PermanentEventException("Invalid " + field, exception);
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new PermanentEventException("Invalid createdAt", exception);
        }
    }
}
