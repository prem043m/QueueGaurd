package com.queueguard.qms.repo;

import com.queueguard.qms.domain.InboxEvent;
import com.queueguard.qms.domain.InboxEventStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InboxEventRepository extends JpaRepository<InboxEvent, Long> {
    long countByProcessingStatusIn(Collection<InboxEventStatus> statuses);

    @Modifying
    @Query(value = """
            INSERT INTO qms_event_inbox
                (consumer_name, event_id, event_type, organization_id,
                 aggregate_type, aggregate_id, processing_status, received_at,
                 attempt_count)
            VALUES
                (:consumerName, :eventId, :eventType, :organizationId,
                 :aggregateType, :aggregateId, 'RECEIVED', now(), 0)
            ON CONFLICT (consumer_name, event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("organizationId") Long organizationId,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") Long aggregateId);

    @Query(value = """
            SELECT * FROM qms_event_inbox
            WHERE consumer_name = :consumerName AND event_id = :eventId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<InboxEvent> findForUpdate(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId);

    @Modifying
    @Query(value = """
            INSERT INTO qms_event_inbox
                (consumer_name, event_id, event_type, organization_id,
                 aggregate_type, aggregate_id, processing_status, received_at,
                 attempt_count, last_error)
            VALUES
                (:consumerName, :eventId, :eventType, :organizationId,
                 :aggregateType, :aggregateId, 'RETRYABLE', now(), :attemptNumber, :error)
            ON CONFLICT (consumer_name, event_id) DO UPDATE
            SET attempt_count = GREATEST(
                    qms_event_inbox.attempt_count + 1, :attemptNumber),
                processing_status = 'RETRYABLE',
                last_error = :error
            WHERE qms_event_inbox.processing_status
                NOT IN ('PROCESSED', 'DEAD_LETTERED')
            """, nativeQuery = true)
    int recordFailure(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("organizationId") Long organizationId,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") Long aggregateId,
            @Param("attemptNumber") int attemptNumber,
            @Param("error") String error);

    @Query(value = """
            SELECT attempt_count FROM qms_event_inbox
            WHERE consumer_name = :consumerName AND event_id = :eventId
            """, nativeQuery = true)
    Optional<Integer> findAttemptCount(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId);

    @Modifying
    @Query(value = """
            UPDATE qms_event_inbox
            SET processing_status = 'DEAD_LETTERED', last_error = :error
            WHERE consumer_name = :consumerName
              AND event_id = :eventId
              AND processing_status <> 'PROCESSED'
            """, nativeQuery = true)
    int markDeadLettered(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId,
            @Param("error") String error);
}
