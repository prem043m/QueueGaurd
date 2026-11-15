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
import java.util.UUID;

/**
 * Repository for OutboxEvent entities (transactional outbox pattern).
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE (
                (processing_status = 'PENDING'
                    AND next_attempt_at <= :now AND retry_count < :maxAttempts)
                OR
                (processing_status = 'PROCESSING'
                    AND claimed_at <= :leaseExpiredAt AND retry_count < :maxAttempts)
            )
            AND NOT EXISTS (
                SELECT 1
                FROM outbox_events earlier
                WHERE earlier.organization_id = outbox_events.organization_id
                  AND earlier.aggregate_type = outbox_events.aggregate_type
                  AND earlier.aggregate_id = outbox_events.aggregate_id
                  AND (earlier.created_at, earlier.id)
                      < (outbox_events.created_at, outbox_events.id)
                  AND earlier.processing_status IN ('PENDING', 'PROCESSING')
            )
            ORDER BY created_at ASC, id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockClaimableEvents(
            @Param("now") Instant now,
            @Param("leaseExpiredAt") Instant leaseExpiredAt,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET processing_status = 'FAILED',
                last_error = 'Outbox publication attempts exhausted',
                claimed_at = NULL,
                claim_token = NULL,
                updated_at = :now,
                version = version + 1
            WHERE retry_count >= :maxAttempts
              AND (
                processing_status = 'PENDING'
                OR (processing_status = 'PROCESSING' AND claimed_at <= :leaseExpiredAt)
              )
            """, nativeQuery = true)
    int failExhaustedEvents(
            @Param("maxAttempts") int maxAttempts,
            @Param("leaseExpiredAt") Instant leaseExpiredAt,
            @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET processing_status = 'PUBLISHED',
                published_at = :publishedAt,
                last_error = NULL,
                claimed_at = NULL,
                claim_token = NULL,
                updated_at = :publishedAt,
                version = version + 1
            WHERE id = :id
              AND processing_status = 'PROCESSING'
              AND claim_token = :claimToken
            """, nativeQuery = true)
    int markClaimedAsPublished(
            @Param("id") Long id,
            @Param("claimToken") UUID claimToken,
            @Param("publishedAt") Instant publishedAt);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET processing_status =
                    CASE WHEN :attemptNumber >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                next_attempt_at =
                    CASE WHEN :attemptNumber >= :maxAttempts THEN next_attempt_at ELSE :nextAttemptAt END,
                last_error = :error,
                claimed_at = NULL,
                claim_token = NULL,
                updated_at = :updatedAt,
                version = version + 1
            WHERE id = :id
              AND processing_status = 'PROCESSING'
              AND claim_token = :claimToken
            """, nativeQuery = true)
    int recordPublicationFailure(
            @Param("id") Long id,
            @Param("claimToken") UUID claimToken,
            @Param("attemptNumber") int attemptNumber,
            @Param("maxAttempts") int maxAttempts,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("error") String error,
            @Param("updatedAt") Instant updatedAt);
    
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
    
    // Count pending events
    long countByProcessingStatus(OutboxEventStatus status);
    
    // Find oldest pending event
    Optional<OutboxEvent> findFirstByProcessingStatusOrderByCreatedAtAsc(OutboxEventStatus status);
}
