package com.queueguard.qms.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisStreamPublisher {
    private final StringRedisTemplate redis;
    private final OutboxPublisherProperties properties;

    public RedisStreamPublisher(
            StringRedisTemplate redis, OutboxPublisherProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public RecordId publish(ClaimedOutboxEvent event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventId", event.id().toString());
        fields.put("eventType", event.eventType());
        fields.put("organizationId", event.organizationId().toString());
        fields.put("aggregateType", event.aggregateType());
        fields.put("aggregateId", event.aggregateId().toString());
        fields.put("payload", event.payload() == null ? "null" : event.payload());
        fields.put("createdAt", event.createdAt().toString());
        if (event.idempotencyKey() != null) {
            fields.put("idempotencyKey", event.idempotencyKey());
        }
        if (event.traceparent() != null) {
            fields.put("traceparent", event.traceparent());
        }
        if (event.tracestate() != null) {
            fields.put("tracestate", event.tracestate());
        }

        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(properties.streamKey())
                .ofMap(fields);
        return redis.opsForStream().add(record);
    }
}
