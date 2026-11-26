package com.queueguard.qms.service;

import com.queueguard.qms.service.OutboxEventLifecycleService.FailureDisposition;
import com.queueguard.qms.service.QmsEventMetrics.OutboxOutcome;
import com.queueguard.common.observability.TraceContextHttpClient;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "queueguard.outbox", name = "enabled", havingValue = "true")
public class OutboxPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventLifecycleService lifecycle;
    private final RedisStreamPublisher streamPublisher;
    private final QmsEventMetrics metrics;
    private final Tracer tracer;

    public OutboxPublisher(
            OutboxEventLifecycleService lifecycle,
            RedisStreamPublisher streamPublisher,
            QmsEventMetrics metrics,
            Tracer tracer) {
        this.lifecycle = lifecycle;
        this.streamPublisher = streamPublisher;
        this.metrics = metrics;
        this.tracer = tracer;
    }

    @Scheduled(fixedDelayString = "${queueguard.outbox.polling-interval-ms:1000}")
    public void publishBatch() {
        List<ClaimedOutboxEvent> events;
        try {
            events = lifecycle.claimBatch();
        } catch (DataAccessException exception) {
            metrics.recordOutbox(OutboxOutcome.CLAIM_FAILURE);
            LOGGER.error("Unable to claim QMS outbox events: {}",
                    exception.getClass().getSimpleName());
            return;
        }

        for (ClaimedOutboxEvent event : events) {
            Timer.Sample sample = metrics.startOutboxTimer();
            try (TraceContextHttpClient.ScopedSpan span =
                         TraceContextHttpClient.startSpan(tracer, "messaging.publish", "redis")) {
                span.tag("messaging.system", "redis");
                span.tag("messaging.message.id", event.id().toString());
                try {
                    streamPublisher.publish(event);
                } catch (DataAccessException redisFailure) {
                    span.tag("queueguard.outcome", "publish_failure");
                    span.error(redisFailure);
                    metrics.recordOutbox(OutboxOutcome.PUBLISH_FAILURE);
                    if (!recordFailure(event, redisFailure)) {
                        return;
                    }
                    continue;
                }
                span.tag("queueguard.outcome", "published");
                metrics.recordOutbox(OutboxOutcome.PUBLISHED);

                try {
                    if (!lifecycle.markPublished(event)) {
                        span.tag("queueguard.outcome", "stale_claim");
                        metrics.recordOutbox(OutboxOutcome.STALE_CLAIM);
                        LOGGER.warn("Outbox event {} was published after its claim expired",
                                event.id());
                    }
                } catch (DataAccessException databaseFailure) {
                    span.tag("queueguard.outcome", "mark_failure");
                    span.error(databaseFailure);
                    metrics.recordOutbox(OutboxOutcome.MARK_FAILURE);
                    LOGGER.error(
                            "Could not mark published outbox event {}; it will be recovered by its lease ({})",
                            event.id(),
                            databaseFailure.getClass().getSimpleName());
                    return;
                }
            } finally {
                metrics.stopOutboxTimer(sample);
            }
        }
    }

    private boolean recordFailure(
            ClaimedOutboxEvent event, DataAccessException redisFailure) {
        try {
            FailureDisposition disposition = lifecycle.recordFailure(event, redisFailure);
            if (disposition == FailureDisposition.EXHAUSTED) {
                metrics.recordOutbox(OutboxOutcome.EXHAUSTED);
                LOGGER.error("Outbox event {} exhausted its publication attempts", event.id());
            } else if (disposition == FailureDisposition.STALE_CLAIM) {
                metrics.recordOutbox(OutboxOutcome.STALE_CLAIM);
                LOGGER.info("Outbox event {} failure belongs to an expired claim", event.id());
            } else {
                metrics.recordOutbox(OutboxOutcome.RETRY_SCHEDULED);
                LOGGER.warn("Redis Stream publication failed for outbox event {} ({})",
                        event.id(), redisFailure.getClass().getSimpleName());
            }
            return true;
        } catch (DataAccessException databaseFailure) {
            LOGGER.error(
                    "Could not schedule retry for outbox event {}; lease recovery will retry it ({})",
                    event.id(),
                    databaseFailure.getClass().getSimpleName());
            return false;
        }
    }
}
