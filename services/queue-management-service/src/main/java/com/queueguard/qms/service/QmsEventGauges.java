package com.queueguard.qms.service;

import com.queueguard.qms.domain.InboxEventStatus;
import com.queueguard.qms.domain.OutboxEventStatus;
import com.queueguard.qms.repo.InboxEventRepository;
import com.queueguard.qms.repo.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QmsEventGauges {
    private static final List<InboxEventStatus> ACTIVE_INBOX_STATUSES =
            List.of(InboxEventStatus.RECEIVED, InboxEventStatus.PROCESSING,
                    InboxEventStatus.RETRYABLE);

    private final OutboxEventRepository outbox;
    private final InboxEventRepository inbox;
    private final AtomicLong pendingOutbox = new AtomicLong();
    private final AtomicLong oldestPendingOutboxAgeSeconds = new AtomicLong();
    private final AtomicLong pendingInbox = new AtomicLong();

    public QmsEventGauges(
            MeterRegistry registry,
            OutboxEventRepository outbox,
            InboxEventRepository inbox) {
        this.outbox = outbox;
        this.inbox = inbox;
        Gauge.builder("queueguard.outbox.pending", pendingOutbox, AtomicLong::get)
                .register(registry);
        Gauge.builder("queueguard.outbox.oldest.pending.age.seconds",
                        oldestPendingOutboxAgeSeconds, AtomicLong::get)
                .register(registry);
        Gauge.builder("queueguard.inbox.pending", pendingInbox, AtomicLong::get)
                .register(registry);
    }

    @Scheduled(
            fixedDelayString = "${queueguard.observability.event-gauges-refresh-ms:30000}",
            initialDelayString = "${queueguard.observability.event-gauges-refresh-ms:30000}")
    public void refresh() {
        pendingOutbox.set(
                outbox.countByProcessingStatus(OutboxEventStatus.PENDING)
                        + outbox.countByProcessingStatus(OutboxEventStatus.PROCESSING));
        pendingInbox.set(inbox.countByProcessingStatusIn(ACTIVE_INBOX_STATUSES));
        oldestPendingOutboxAgeSeconds.set(outbox
                .findFirstByProcessingStatusOrderByCreatedAtAsc(OutboxEventStatus.PENDING)
                .map(event -> Math.max(0,
                        Duration.between(event.getCreatedAt(), Instant.now()).toSeconds()))
                .orElse(0L));
    }
}
