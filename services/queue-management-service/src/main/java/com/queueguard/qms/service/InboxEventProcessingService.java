package com.queueguard.qms.service;

import com.queueguard.qms.domain.InboxEvent;
import com.queueguard.qms.domain.InboxEventStatus;
import com.queueguard.qms.repo.InboxEventRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InboxEventProcessingService {
    private final InboxEventRepository repository;
    private final QmsEventHandlerRegistry handlers;
    private final OutboxConsumerProperties properties;

    public InboxEventProcessingService(
            InboxEventRepository repository,
            QmsEventHandlerRegistry handlers,
            OutboxConsumerProperties properties) {
        this.repository = repository;
        this.handlers = handlers;
        this.properties = properties;
    }

    @Transactional
    public ProcessingResult process(ConsumerEvent event, int deliveryAttempt) {
        repository.insertIfAbsent(
                properties.consumerName(),
                event.eventId(),
                event.eventType(),
                event.organizationId(),
                event.aggregateType(),
                event.aggregateId());
        InboxEvent inbox = repository.findForUpdate(
                        properties.consumerName(), event.eventId())
                .orElseThrow(() -> new IllegalStateException(
                        "Inbox record disappeared during processing"));

        if (inbox.getProcessingStatus() == InboxEventStatus.PROCESSED) {
            return new ProcessingResult(Disposition.DUPLICATE,
                    inbox.getAttemptCount(), inbox.getLastError());
        }
        if (inbox.getProcessingStatus() == InboxEventStatus.DEAD_LETTERED) {
            return new ProcessingResult(Disposition.ALREADY_DEAD_LETTERED,
                    inbox.getAttemptCount(), inbox.getLastError());
        }

        int nextAttempt = Math.max(inbox.getAttemptCount() + 1, deliveryAttempt);
        if (nextAttempt > properties.maxAttempts()) {
            return new ProcessingResult(Disposition.EXHAUSTED,
                    inbox.getAttemptCount(), inbox.getLastError());
        }

        inbox.setProcessingStatus(InboxEventStatus.PROCESSING);
        inbox.setAttemptCount(nextAttempt);
        inbox.setProcessedAt(null);
        inbox.setLastError(null);
        repository.saveAndFlush(inbox);

        handlers.requireHandler(event.eventType()).handle(event);

        inbox.setProcessingStatus(InboxEventStatus.PROCESSED);
        inbox.setProcessedAt(Instant.now());
        repository.saveAndFlush(inbox);
        return new ProcessingResult(Disposition.PROCESSED, nextAttempt, null);
    }

    public enum Disposition {
        PROCESSED,
        DUPLICATE,
        ALREADY_DEAD_LETTERED,
        EXHAUSTED
    }

    public record ProcessingResult(Disposition disposition, int attemptCount, String lastError) {
    }
}
