package com.queueguard.qms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.queueguard.qms.domain.InboxEventStatus;
import com.queueguard.qms.domain.OutboxEvent;
import com.queueguard.qms.domain.OutboxEventStatus;
import com.queueguard.qms.repo.InboxEventRepository;
import com.queueguard.qms.repo.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QmsEventGaugesTest {
    @Test
    void refreshesPendingCountsAndOldestPendingAgeFromIndexedStateQueries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        InboxEventRepository inbox = mock(InboxEventRepository.class);
        when(outbox.countByProcessingStatus(OutboxEventStatus.PENDING)).thenReturn(2L);
        when(outbox.countByProcessingStatus(OutboxEventStatus.PROCESSING)).thenReturn(3L);
        OutboxEvent oldest = new OutboxEvent();
        oldest.setCreatedAt(Instant.now().minusSeconds(45));
        when(outbox.findFirstByProcessingStatusOrderByCreatedAtAsc(
                OutboxEventStatus.PENDING)).thenReturn(Optional.of(oldest));
        when(inbox.countByProcessingStatusIn(List.of(
                InboxEventStatus.RECEIVED,
                InboxEventStatus.PROCESSING,
                InboxEventStatus.RETRYABLE))).thenReturn(4L);

        QmsEventGauges gauges = new QmsEventGauges(registry, outbox, inbox);
        gauges.refresh();

        assertEquals(5.0, registry.get("queueguard.outbox.pending").gauge().value());
        assertEquals(4.0, registry.get("queueguard.inbox.pending").gauge().value());
        assertEquals(45.0, registry.get("queueguard.outbox.oldest.pending.age.seconds")
                .gauge().value(), 2.0);
    }
}
