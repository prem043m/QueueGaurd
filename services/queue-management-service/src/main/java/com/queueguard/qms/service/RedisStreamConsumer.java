package com.queueguard.qms.service;

import com.queueguard.qms.service.InboxEventProcessingService.Disposition;
import com.queueguard.qms.service.InboxEventProcessingService.ProcessingResult;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
@ConditionalOnProperty(prefix = "queueguard.consumer", name = "enabled", havingValue = "true")
public class RedisStreamConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisStreamConsumer.class);

    private final StringRedisTemplate redis;
    private final OutboxConsumerProperties properties;
    private final ConsumerEventDeserializer deserializer;
    private final InboxEventProcessingService processing;
    private final InboxEventFailureService failures;
    private final RedisDeadLetterPublisher deadLetterPublisher;
    private final String consumerIdentity;
    private final AtomicBoolean groupReady = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();

    public RedisStreamConsumer(
            StringRedisTemplate redis,
            OutboxConsumerProperties properties,
            ConsumerEventDeserializer deserializer,
            InboxEventProcessingService processing,
            InboxEventFailureService failures,
            RedisDeadLetterPublisher deadLetterPublisher) {
        this.redis = redis;
        this.properties = properties;
        this.deserializer = deserializer;
        this.processing = processing;
        this.failures = failures;
        this.deadLetterPublisher = deadLetterPublisher;
        this.consumerIdentity = properties.uniqueConsumerIdentity();
    }

    @Scheduled(fixedDelayString = "${queueguard.consumer.polling-interval-ms:1000}")
    public void pollNewMessages() {
        if (stopping.get() || !ensureGroup()) {
            return;
        }
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(properties.groupName(), consumerIdentity),
                    StreamReadOptions.empty()
                            .count(properties.batchSize())
                            .block(Duration.ofMillis(properties.blockingTimeoutMs())),
                    StreamOffset.create(properties.streamKey(), ReadOffset.lastConsumed()));
            if (records != null) {
                for (MapRecord<String, Object, Object> record : records) {
                    if (stopping.get()) {
                        return;
                    }
                    process(record, 1);
                }
            }
        } catch (DataAccessException exception) {
            logRedisFailure("read", exception);
        }
    }

    @Scheduled(fixedDelayString = "${queueguard.consumer.pending-recovery-interval-ms:5000}")
    public void recoverPendingMessages() {
        if (stopping.get() || !ensureGroup()) {
            return;
        }
        try {
            PendingMessages pending = redis.opsForStream().pending(
                    properties.streamKey(),
                    properties.groupName(),
                    Range.unbounded(),
                    Math.max(100, properties.batchSize() * 10L));
            int recovered = 0;
            for (PendingMessage message : pending) {
                if (stopping.get() || recovered >= properties.batchSize()) {
                    return;
                }
                int priorDeliveries = (int) Math.min(Integer.MAX_VALUE,
                        Math.max(1L, message.getTotalDeliveryCount()));
                Duration idleThreshold = Duration.ofMillis(
                        properties.retryDelayMillis(priorDeliveries));
                List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                        properties.streamKey(),
                        properties.groupName(),
                        consumerIdentity,
                        idleThreshold,
                        message.getId());
                for (MapRecord<String, Object, Object> record : claimed) {
                    recovered++;
                    process(record, priorDeliveries + 1);
                }
            }
        } catch (DataAccessException exception) {
            logRedisFailure("pending-message recovery", exception);
        }
    }

    @PreDestroy
    public void stop() {
        stopping.set(true);
    }

    String consumerIdentity() {
        return consumerIdentity;
    }

    private boolean ensureGroup() {
        if (groupReady.get()) {
            return true;
        }
        synchronized (groupReady) {
            if (groupReady.get()) {
                return true;
            }
            try {
                redis.opsForStream().createGroup(
                        properties.streamKey(),
                        ReadOffset.from("0-0"),
                        properties.groupName());
                groupReady.set(true);
                return true;
            } catch (DataAccessException exception) {
                if (hasBusyGroupMessage(exception)) {
                    groupReady.set(true);
                    return true;
                }
                logRedisFailure("consumer-group creation", exception);
                return false;
            }
        }
    }

    private void process(MapRecord<String, Object, Object> record, int deliveryAttempt) {
        if (stopping.get()) {
            return;
        }
        ConsumerEvent event;
        try {
            event = deserializer.deserialize(record);
        } catch (PermanentEventException exception) {
            deadLetterAndAcknowledge(
                    record, null, deliveryAttempt, safeFailure(exception));
            return;
        }

        ProcessingResult result;
        try {
            result = processing.process(event, deliveryAttempt);
        } catch (RuntimeException exception) {
            if (ConsumerFailureClassifier.classify(exception)
                    == ConsumerFailureClassifier.FailureKind.PERMANENT) {
            int attempts = recordFailure(event, deliveryAttempt, exception);
                deadLetterAndAcknowledge(
                        record, event, Math.max(deliveryAttempt, attempts),
                        safeFailure(exception));
            } else {
                int attempts = recordFailure(event, deliveryAttempt, exception);
                if (attempts >= properties.maxAttempts()
                        || (attempts < 0 && deliveryAttempt >= properties.maxAttempts())) {
                    deadLetterAndAcknowledge(
                            record, event, Math.max(deliveryAttempt, attempts),
                            safeFailure(exception));
                }
            }
            return;
        }

        if (result.disposition() == Disposition.EXHAUSTED) {
            deadLetterAndAcknowledge(
                    record, event, result.attemptCount(),
                    safeFailureMessage(result.lastError()));
        } else if (result.disposition() == Disposition.ALREADY_DEAD_LETTERED) {
            acknowledge(record);
        } else {
            acknowledge(record);
        }
    }

    private int recordFailure(
            ConsumerEvent event, int deliveryAttempt, RuntimeException exception) {
        try {
            return failures.recordFailure(
                    event, deliveryAttempt, safeFailure(exception));
        } catch (DataAccessException failure) {
            LOGGER.error("Could not persist inbox failure for event {} ({})",
                    event.eventId(), failure.getClass().getSimpleName());
            return -1;
        }
    }

    private void deadLetterAndAcknowledge(
            MapRecord<String, Object, Object> record,
            ConsumerEvent event,
            int attemptCount,
            String reason) {
        if (stopping.get()) {
            return;
        }
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        record.getValue().forEach((key, value) ->
                fields.put(String.valueOf(key), String.valueOf(value)));
        String eventId = event == null ? fields.get("eventId") : event.eventId();
        try {
            deadLetterPublisher.publish(
                    fields, eventId, record.getId().getValue(),
                    Math.max(attemptCount, 1), reason);
        } catch (DataAccessException exception) {
            logRedisFailure("dead-letter publication", exception);
            return;
        }

        if (event != null) {
            try {
                failures.markDeadLettered(event, reason);
            } catch (DataAccessException exception) {
                LOGGER.error("Could not mark inbox event {} dead-lettered ({})",
                        event.eventId(), exception.getClass().getSimpleName());
                return;
            }
        }
        acknowledge(record);
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        if (stopping.get()) {
            return;
        }
        try {
            redis.opsForStream().acknowledge(
                    properties.groupName(), consumerIdentity, record.getId());
        } catch (DataAccessException exception) {
            logRedisFailure("acknowledgement", exception);
        }
    }

    private void logRedisFailure(String operation, DataAccessException exception) {
        LOGGER.warn("Redis Stream {} failed ({})",
                operation, exception.getClass().getSimpleName());
    }

    private static boolean hasBusyGroupMessage(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null
                    && cause.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    private static String safeFailure(Throwable exception) {
        if (exception == null) {
            return "processing failed";
        }
        return "processing failed (" + exception.getClass().getSimpleName() + ")";
    }

    private static String safeFailureMessage(String message) {
        return message == null || message.isBlank() ? "processing attempts exhausted" : message;
    }
}
