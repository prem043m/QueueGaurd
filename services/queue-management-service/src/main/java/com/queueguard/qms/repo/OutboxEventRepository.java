package com.queueguard.qms.repo;

import com.queueguard.qms.domain.OutboxEvent;
import com.queueguard.qms.domain.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for OutboxEvent entities (transactional outbox pattern).
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    
    // Find pending events
    List<OutboxEvent> findByProcessingStatusAndPublishedAtIsNullOrderByCreatedAtAsc(
            OutboxEventStatus status);
    
    // Find pending events by organization
    List<OutboxEvent> findByOrganizationIdAndProcessingStatusOrderByCreatedAtAsc(
            Long organizationId, OutboxEventStatus status);
    
    // Find failed events for retry
    List<OutboxEvent> findByProcessingStatusAndRetryCountLessThanOrderByCreatedAtAsc(
            OutboxEventStatus status, Integer maxRetries);
    
    // Find by aggregate type and ID
    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
            String aggregateType, Long aggregateId);
    
    // Find by idempotency key
    Optional<OutboxEvent> findByIdempotencyKey(String idempotencyKey);
    
    // Mark event as published
    // Note: Bulk update bypasses optimistic locking. For outbox events, this is acceptable
    // as they are typically updated by a single publisher process. Version conflicts are
    // extremely unlikely but would result in lost updates if they occur.
    @Modifying
    @Query("UPDATE OutboxEvent oe SET oe.processingStatus = 'PUBLISHED', oe.publishedAt = :publishedAt, " +
           "oe.version = oe.version + 1 " +
           "WHERE oe.id = :id AND oe.processingStatus = 'PENDING'")
    int markAsPublished(@Param("id") Long id, @Param("publishedAt") Instant publishedAt);
    
    // Mark event as failed with error
    // Note: Bulk update bypasses optimistic locking. See note above.
    @Modifying
    @Query("UPDATE OutboxEvent oe SET oe.processingStatus = 'FAILED', oe.retryCount = oe.retryCount + 1, " +
           "oe.lastError = :error, oe.version = oe.version + 1 " +
           "WHERE oe.id = :id AND oe.processingStatus = 'PENDING'")
    int markAsFailed(@Param("id") Long id, @Param("error") String error);
    
    // Reset failed events for retry
    // Note: Bulk update bypasses optimistic locking. See note above.
    @Modifying
    @Query("UPDATE OutboxEvent oe SET oe.processingStatus = 'PENDING', oe.lastError = NULL, " +
           "oe.version = oe.version + 1 " +
           "WHERE oe.processingStatus = 'FAILED' AND oe.retryCount < :maxRetries")
    int resetFailedEvents(@Param("maxRetries") Integer maxRetries);
    
    // Count pending events
    long countByProcessingStatus(OutboxEventStatus status);
    
    // Find oldest pending event
    Optional<OutboxEvent> findFirstByProcessingStatusOrderByCreatedAtAsc(OutboxEventStatus status);
}
