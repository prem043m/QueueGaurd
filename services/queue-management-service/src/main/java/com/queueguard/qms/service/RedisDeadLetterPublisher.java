package com.queueguard.qms.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RedisDeadLetterPublisher {
    private static final DefaultRedisScript<String> APPEND_ONCE = new DefaultRedisScript<>("""
            local existing = redis.call('GET', KEYS[1])
            if existing then
              return existing
            end
            local id = redis.call('XADD', KEYS[2], '*', unpack(ARGV))
            redis.call('SET', KEYS[1], id)
            return id
            """, String.class);

    private final StringRedisTemplate redis;
    private final OutboxConsumerProperties properties;

    public RedisDeadLetterPublisher(
            StringRedisTemplate redis, OutboxConsumerProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public String publish(
            Map<String, String> originalFields,
            String eventId,
            String sourceMessageId,
            int attemptCount,
            String failureReason) {
        Map<String, String> fields = new LinkedHashMap<>(originalFields);
        fields.put("failureReason", failureReason);
        fields.put("attemptCount", Integer.toString(attemptCount));
        fields.put("failedAt", Instant.now().toString());
        fields.put("sourceStream", properties.streamKey());
        fields.put("sourceMessageId", sourceMessageId);
        fields.put("consumerGroup", properties.groupName());

        String identity = eventId == null || eventId.isBlank()
                ? "invalid-" + sourceMessageId
                : eventId;
        String dedupeKey = properties.deadLetterStreamKey() + ":dedupe:" + identity;
        List<String> arguments = new ArrayList<>(fields.size() * 2);
        fields.forEach((name, value) -> {
            arguments.add(name);
            arguments.add(value);
        });
        return redis.execute(
                APPEND_ONCE,
                List.of(dedupeKey, properties.deadLetterStreamKey()),
                arguments.toArray());
    }
}
