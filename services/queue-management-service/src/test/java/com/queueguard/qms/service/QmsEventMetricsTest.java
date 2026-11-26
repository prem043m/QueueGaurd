package com.queueguard.qms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.queueguard.qms.service.QmsEventMetrics.InboxOutcome;
import com.queueguard.qms.service.QmsEventMetrics.OutboxOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class QmsEventMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QmsEventMetrics metrics = new QmsEventMetrics(registry);

    @Test
    void recordsOutcomesAndDurationsUsingOnlyBoundedLabels() {
        var outboxSample = metrics.startOutboxTimer();
        metrics.recordOutbox(OutboxOutcome.PUBLISHED);
        metrics.stopOutboxTimer(outboxSample);

        var inboxSample = metrics.startInboxTimer();
        metrics.recordInbox(InboxOutcome.DUPLICATE);
        metrics.stopInboxTimer(inboxSample);
        metrics.recordInboxRetry();

        assertEquals(1.0, registry.get("queueguard.outbox.events")
                .tag("outcome", "published").counter().count());
        assertEquals(1.0, registry.get("queueguard.inbox.events")
                .tag("outcome", "duplicate").counter().count());
        assertEquals(1.0, registry.get("queueguard.inbox.retry.attempts")
                .counter().count());
        assertEquals(1, registry.get("queueguard.outbox.publish.duration").timer().count());
        assertEquals(1, registry.get("queueguard.inbox.processing.duration").timer().count());
        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
                assertEquals("outcome", tag.getKey())));
    }
}
