package com.queueguard.qms.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class QmsEventMetrics {
    private final MeterRegistry registry;

    public QmsEventMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordOutbox(OutboxOutcome outcome) {
        Counter.builder("queueguard.outbox.events")
                .tag("outcome", outcome.label)
                .register(registry)
                .increment();
    }

    public Timer.Sample startOutboxTimer() {
        return Timer.start(registry);
    }

    public void stopOutboxTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("queueguard.outbox.publish.duration").register(registry));
    }

    public void recordInbox(InboxOutcome outcome) {
        Counter.builder("queueguard.inbox.events")
                .tag("outcome", outcome.label)
                .register(registry)
                .increment();
    }

    public void recordInboxRetry() {
        Counter.builder("queueguard.inbox.retry.attempts")
                .register(registry)
                .increment();
    }

    public Timer.Sample startInboxTimer() {
        return Timer.start(registry);
    }

    public void stopInboxTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("queueguard.inbox.processing.duration").register(registry));
    }

    public enum OutboxOutcome {
        PUBLISHED("published"),
        PUBLISH_FAILURE("publish_failure"),
        CLAIM_FAILURE("claim_failure"),
        RETRY_SCHEDULED("retry_scheduled"),
        EXHAUSTED("exhausted"),
        STALE_CLAIM("stale_claim"),
        MARK_FAILURE("mark_failure");

        private final String label;

        OutboxOutcome(String label) {
            this.label = label;
        }
    }

    public enum InboxOutcome {
        RECEIVED("received"),
        PROCESSED("processed"),
        DUPLICATE("duplicate"),
        FAILED("failed"),
        RETRY_SCHEDULED("retry_scheduled"),
        EXHAUSTED("exhausted"),
        DEAD_LETTERED("dead_lettered"),
        ALREADY_DEAD_LETTERED("already_dead_lettered");

        private final String label;

        InboxOutcome(String label) {
            this.label = label;
        }
    }
}
