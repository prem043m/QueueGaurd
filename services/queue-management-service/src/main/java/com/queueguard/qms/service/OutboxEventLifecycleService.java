package com.queueguard.qms.service;

import com.queueguard.qms.domain.OutboxEvent;
import com.queueguard.qms.domain.OutboxEventStatus;
import com.queueguard.qms.repo.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxEventLifecycleService {
    private final OutboxEventRepository repository;
    private final OutboxPublisherProperties properties;

    public OutboxEventLifecycleService(
            OutboxEventRepository repository,
            OutboxPublisherProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public List<ClaimedOutboxEvent> claimBatch() {
        Instant now = Instant.now();
        Instant leaseExpiredAt = now.minusMillis(properties.leaseDurationMs());
        repository.failExhaustedEvents(
                properties.maxAttempts(), leaseExpiredAt, now);

        List<OutboxEvent> events = repository.lockClaimableEvents(
                now, leaseExpiredAt, properties.maxAttempts(), properties.batchSize());
        for (OutboxEvent event : events) {
            event.setProcessingStatus(OutboxEventStatus.PROCESSING);
            event.setRetryCount(event.getRetryCount() + 1);
            event.setClaimedAt(now);
            event.setClaimToken(UUID.randomUUID());
            event.setUpdatedAt(now);
        }
        repository.saveAllAndFlush(events);
        return events.stream().map(OutboxEventLifecycleService::claimedView).toList();
    }

    @Transactional
    public boolean markPublished(ClaimedOutboxEvent event) {
        return repository.markClaimedAsPublished(
                event.id(), event.claimToken(), Instant.now()) == 1;
    }

    @Transactional
    public FailureDisposition recordFailure(
            ClaimedOutboxEvent event, DataAccessException exception) {
        Instant now = Instant.now();
        boolean exhausted = event.attemptNumber() >= properties.maxAttempts();
        Instant nextAttemptAt = now.plusMillis(
                properties.retryDelayMillis(event.attemptNumber()));
        int updated = repository.recordPublicationFailure(
                event.id(),
                event.claimToken(),
                event.attemptNumber(),
                properties.maxAttempts(),
                nextAttemptAt,
                describe(exception),
                now);
        if (updated == 0) {
            return FailureDisposition.STALE_CLAIM;
        }
        return exhausted ? FailureDisposition.EXHAUSTED : FailureDisposition.RETRY_SCHEDULED;
    }

    private static ClaimedOutboxEvent claimedView(OutboxEvent event) {
        return new ClaimedOutboxEvent(
                event.getId(),
                event.getOrganizationId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType(),
                event.getPayload(),
                event.getCreatedAt(),
                event.getIdempotencyKey(),
                event.getClaimToken(),
                event.getRetryCount(),
                event.getTraceparent(),
                event.getTracestate());
    }

    private static String describe(DataAccessException exception) {
        String detail = exception.getMostSpecificCause().getMessage();
        String description = exception.getClass().getSimpleName();
        if (detail == null || detail.isBlank()) {
            return description;
        }
        String normalized = detail.replaceAll("[\\r\\n\\t]+", " ").trim();
        return (description + ": " + normalized)
                .substring(0, Math.min(2000, description.length() + 2 + normalized.length()));
    }

    public enum FailureDisposition {
        RETRY_SCHEDULED,
        EXHAUSTED,
        STALE_CLAIM
    }
}
