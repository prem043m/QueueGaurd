package com.queueguard.qms.repo;

import com.queueguard.qms.domain.QueueEntry;
import com.queueguard.qms.domain.QueueEntryStatus;
import com.queueguard.qms.domain.QueueConfiguration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for QueueEntry entities with custom queries for queue operations.
 */
public interface QueueEntryRepository extends JpaRepository<QueueEntry, Long> {
    
    // Find entries by organization
    List<QueueEntry> findByOrganizationIdOrderByJoinedAtAsc(Long organizationId);
    
    // Find by organization and queue configuration
    List<QueueEntry> findByOrganizationIdAndQueueConfigurationIdOrderByJoinedAtAsc(
            Long organizationId, Long queueConfigurationId);
    
    // Find by organization, queue configuration, and status
    List<QueueEntry> findByOrganizationIdAndQueueConfigurationIdAndStatusOrderByJoinedAtAsc(
            Long organizationId, Long queueConfigurationId, QueueEntryStatus status);
    
    // Find by token number and organization
    Optional<QueueEntry> findByTokenNumberAndOrganizationId(String tokenNumber, Long organizationId);
    
    // Find by participant user and organization
    List<QueueEntry> findByParticipantUserIdAndOrganizationIdOrderByJoinedAtDesc(
            Long participantUserId, Long organizationId);
    
    // Find by participant user
    List<QueueEntry> findByParticipantUserIdOrderByJoinedAtDesc(Long participantUserId);
    
    // Check if token exists for organization
    boolean existsByTokenNumberAndOrganizationId(String tokenNumber, Long organizationId);
    
    // Count entries by queue configuration and status
    long countByQueueConfigurationIdAndStatus(Long queueConfigurationId, QueueEntryStatus status);
    
    // Count waiting entries by queue configuration
    long countByQueueConfigurationIdAndStatusIn(
            Long queueConfigurationId, List<QueueEntryStatus> statuses);
    
    // Find next eligible entry for FIFO ordering with SKIP LOCKED
    // Uses native query to properly implement SELECT FOR UPDATE SKIP LOCKED
    // Only selects WAITING entries - CALLED entries should not be claimed again
    // Note: @Lock annotation removed as native SQL already contains FOR UPDATE SKIP LOCKED
    @Query(value = "SELECT qe.* FROM queue_entries qe WHERE qe.queue_configuration_id = :queueConfigId " +
           "AND qe.organization_id = :orgId " +
           "AND qe.status = 'WAITING' " +
           "ORDER BY qe.joined_at ASC, qe.id ASC " +
           "LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<QueueEntry> findNextFifoEntryForUpdate(
            @Param("orgId") Long orgId, 
            @Param("queueConfigId") Long queueConfigId);
    
    // Find next eligible entry for PRIORITY_FIFO ordering with SKIP LOCKED
    // Uses native query to properly implement SELECT FOR UPDATE SKIP LOCKED
    // Only selects WAITING entries - CALLED entries should not be claimed again
    // Note: @Lock annotation removed as native SQL already contains FOR UPDATE SKIP LOCKED
    @Query(value = "SELECT qe.* FROM queue_entries qe WHERE qe.queue_configuration_id = :queueConfigId " +
           "AND qe.organization_id = :orgId " +
           "AND qe.status = 'WAITING' " +
           "ORDER BY qe.priority DESC, qe.joined_at ASC, qe.id ASC " +
           "LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<QueueEntry> findNextPriorityEntryForUpdate(
            @Param("orgId") Long orgId, 
            @Param("queueConfigId") Long queueConfigId);
    
    // Find entries ahead of a given entry (for position calculation)
    @Query("SELECT COUNT(*) FROM QueueEntry qe WHERE qe.queueConfigurationId = :queueConfigId " +
           "AND qe.organizationId = :orgId " +
           "AND qe.status IN ('WAITING', 'CALLED') " +
           "AND (qe.joinedAt < :joinedAt OR (qe.joinedAt = :joinedAt AND qe.id < :entryId))")
    long countEntriesAheadFifo(
            @Param("orgId") Long orgId, 
            @Param("queueConfigId") Long queueConfigId,
            @Param("joinedAt") Instant joinedAt,
            @Param("entryId") Long entryId);
    
    // Find entries ahead for priority ordering
    @Query("SELECT COUNT(*) FROM QueueEntry qe WHERE qe.queueConfigurationId = :queueConfigId " +
           "AND qe.organizationId = :orgId " +
           "AND qe.status IN ('WAITING', 'CALLED') " +
           "AND (qe.priority > :priority OR " +
           "    (qe.priority = :priority AND qe.joinedAt < :joinedAt) OR " +
           "    (qe.priority = :priority AND qe.joinedAt = :joinedAt AND qe.id < :entryId))")
    long countEntriesAheadPriority(
            @Param("orgId") Long orgId, 
            @Param("queueConfigId") Long queueConfigId,
            @Param("priority") Integer priority,
            @Param("joinedAt") Instant joinedAt,
            @Param("entryId") Long entryId);
    
    // Find entries by queue configuration and status with pagination
    Page<QueueEntry> findByQueueConfigurationIdAndStatusOrderByJoinedAtAsc(
            Long queueConfigurationId, QueueEntryStatus status, Pageable pageable);
    
    // Find entries by queue configuration and status without pagination
    List<QueueEntry> findByQueueConfigurationIdAndStatusOrderByJoinedAtAsc(
            Long queueConfigurationId, QueueEntryStatus status);
    
    // Find all entries by queue configuration with pagination
    Page<QueueEntry> findByQueueConfigurationIdOrderByJoinedAtAsc(
            Long queueConfigurationId, Pageable pageable);
    
    // Find all entries by queue configuration without pagination
    List<QueueEntry> findByQueueConfigurationIdOrderByJoinedAtAsc(Long queueConfigurationId);
    
    // Check if entry belongs to organization
    @Query("SELECT COUNT(*) > 0 FROM QueueEntry qe WHERE qe.id = :entryId AND qe.organizationId = :orgId")
    boolean existsByIdAndOrganizationId(@Param("entryId") Long entryId, @Param("orgId") Long orgId);
    
    // Find entry with lock for update
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT qe FROM QueueEntry qe WHERE qe.id = :entryId AND qe.organizationId = :orgId")
    Optional<QueueEntry> findByIdAndOrganizationIdForUpdate(
            @Param("entryId") Long entryId, @Param("orgId") Long orgId);
    
    // Count total entries by queue configuration
    long countByQueueConfigurationId(Long queueConfigurationId);
    
    // Count by status for statistics
    long countByOrganizationIdAndQueueConfigurationIdAndStatus(
            Long organizationId, Long queueConfigurationId, QueueEntryStatus status);
    
    // Get the next sequence number for token generation (atomic)
    // Uses database-level sequence to ensure uniqueness under concurrency
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(token_number FROM '([^-]+-[^-]+-)(.+)') AS INTEGER)), 0) + 1 " +
           "FROM queue_entries WHERE organization_id = :orgId AND token_number LIKE :datePrefix || '%'", nativeQuery = true)
    int getNextTokenSequenceForDate(@Param("orgId") Long orgId, @Param("datePrefix") String datePrefix);
    
    // Alternative: Get max sequence for a specific queue code and date
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(token_number FROM '([^-]+-[^-]+-)(.+)') AS INTEGER)), 0) + 1 " +
           "FROM queue_entries WHERE organization_id = :orgId AND queue_configuration_id = :queueConfigId " +
           "AND token_number LIKE :queueCode || '-' || :datePart || '%'", nativeQuery = true)
    int getNextTokenSequenceForQueueAndDate(
            @Param("orgId") Long orgId, 
            @Param("queueConfigId") Long queueConfigId,
            @Param("queueCode") String queueCode,
            @Param("datePart") String datePart);
}
