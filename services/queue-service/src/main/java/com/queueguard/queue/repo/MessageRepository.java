package com.queueguard.queue.repo;

import com.queueguard.queue.entity.Message;
import com.queueguard.queue.entity.MessageStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, String> {

    Optional<Message> findByQueueNameAndIdempotencyKey(String queueName, String idempotencyKey);

    long countByQueueNameAndStatus(String queueName, MessageStatus status);

    List<Message> findByQueueNameAndStatusOrderByCreatedAtDesc(String queueName, MessageStatus status, Pageable pageable);

    List<Message> findByStatusAndClaimedAtBefore(MessageStatus status, Instant cutoff, Pageable pageable);

    List<Message> findByStatus(MessageStatus status, Pageable pageable);

    @Query("SELECT m.status, COUNT(m) FROM Message m WHERE m.queueName = :queue GROUP BY m.status")
    List<Object[]> countGroupedByStatus(@Param("queue") String queue);

    /** Atomic claim: only succeeds when the row is still PENDING and ready. */
    @Modifying
    @Query("UPDATE Message m SET m.status = :to, m.claimedBy = :workerId, m.claimedAt = :now, " +
           "m.updatedAt = :now, m.version = m.version + 1 " +
           "WHERE m.id = :id AND m.status = :from AND m.nextAttemptAt <= :now")
    int claimOne(@Param("id") String id,
                 @Param("workerId") String workerId,
                 @Param("now") Instant now,
                 @Param("from") MessageStatus from,
                 @Param("to") MessageStatus to);

    /** Generic guarded state transition (ack / nack / requeue / dead-letter). */
    @Modifying
    @Query("UPDATE Message m SET m.status = :to, m.claimedBy = null, m.claimedAt = null, " +
           "m.attempts = m.attempts + :attemptDelta, m.nextAttemptAt = :nextAt, " +
           "m.error = :error, m.updatedAt = :now, m.version = m.version + 1 " +
           "WHERE m.id = :id AND m.status = :from")
    int transition(@Param("id") String id,
                   @Param("from") MessageStatus from,
                   @Param("to") MessageStatus to,
                   @Param("attemptDelta") int attemptDelta,
                   @Param("nextAt") Instant nextAt,
                   @Param("error") String error,
                   @Param("now") Instant now);
}
