package com.queueguard.qms.service;

import com.queueguard.qms.service.OutboxEventLifecycleService.FailureDisposition;
import java.util.List;
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

    public OutboxPublisher(
            OutboxEventLifecycleService lifecycle,
            RedisStreamPublisher streamPublisher) {
        this.lifecycle = lifecycle;
        this.streamPublisher = streamPublisher;
    }

    @Scheduled(fixedDelayString = "${queueguard.outbox.polling-interval-ms:1000}")
    public void publishBatch() {
        List<ClaimedOutboxEvent> events;
        try {
            events = lifecycle.claimBatch();
        } catch (DataAccessException exception) {
            LOGGER.error("Unable to claim QMS outbox events: {}",
                    exception.getClass().getSimpleName());
            return;
        }

        for (ClaimedOutboxEvent event : events) {
            try {
                streamPublisher.publish(event);
            } catch (DataAccessException redisFailure) {
                if (!recordFailure(event, redisFailure)) {
                    return;
                }
                continue;
            }

            try {
                if (!lifecycle.markPublished(event)) {
                    LOGGER.warn("Outbox event {} was published after its claim expired",
                            event.id());
                }
            } catch (DataAccessException databaseFailure) {
                LOGGER.error(
                        "Could not mark published outbox event {}; it will be recovered by its lease ({})",
                        event.id(),
                        databaseFailure.getClass().getSimpleName());
                return;
            }
        }
    }

    private boolean recordFailure(
            ClaimedOutboxEvent event, DataAccessException redisFailure) {
        try {
            FailureDisposition disposition = lifecycle.recordFailure(event, redisFailure);
            if (disposition == FailureDisposition.EXHAUSTED) {
                LOGGER.error("Outbox event {} exhausted its publication attempts", event.id());
            } else if (disposition == FailureDisposition.STALE_CLAIM) {
                LOGGER.info("Outbox event {} failure belongs to an expired claim", event.id());
            } else {
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
