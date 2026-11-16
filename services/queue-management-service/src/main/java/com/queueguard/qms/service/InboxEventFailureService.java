package com.queueguard.qms.service;

import com.queueguard.qms.repo.InboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InboxEventFailureService {
    private final InboxEventRepository repository;
    private final OutboxConsumerProperties properties;

    public InboxEventFailureService(
            InboxEventRepository repository,
            OutboxConsumerProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(ConsumerEvent event, int deliveryAttempt, String error) {
        repository.recordFailure(
                properties.consumerName(),
                event.eventId(),
                event.eventType(),
                event.organizationId(),
                event.aggregateType(),
                event.aggregateId(),
                Math.max(deliveryAttempt, 1),
                error);
        return repository.findAttemptCount(properties.consumerName(), event.eventId())
                .orElse(deliveryAttempt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDeadLettered(ConsumerEvent event, String error) {
        repository.markDeadLettered(
                properties.consumerName(), event.eventId(), error);
    }
}
