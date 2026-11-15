package com.queueguard.qms.repo;

import com.queueguard.qms.domain.TokenSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

/**
 * Repository for TokenSequence entities with atomic sequence generation.
 */
public interface TokenSequenceRepository extends JpaRepository<TokenSequence, Long> {
    
    /**
     * Find token sequence for a specific organization, queue code, and date.
     */
    Optional<TokenSequence> findByOrganizationIdAndQueueCodeAndDatePart(
            Long organizationId, String queueCode, String datePart);
    
    /**
     * Find and lock token sequence for update (pessimistic lock).
     * This ensures atomic sequence increment under concurrent access.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ts FROM TokenSequence ts WHERE ts.organizationId = :orgId " +
           "AND ts.queueCode = :queueCode AND ts.datePart = :datePart")
    Optional<TokenSequence> findByOrganizationIdAndQueueCodeAndDatePartForUpdate(
            @Param("orgId") Long orgId,
            @Param("queueCode") String queueCode,
            @Param("datePart") String datePart);
    
    /**
     * Check if a sequence exists for the given parameters.
     */
    boolean existsByOrganizationIdAndQueueCodeAndDatePart(
            Long organizationId, String queueCode, String datePart);
    
    /**
     * Atomically insert a new sequence row or do nothing if it exists.
     * This handles the first-insert race condition.
     */
    @Modifying
    @Query(value = "INSERT INTO token_sequences (organization_id, queue_code, date_part, last_sequence, created_at, version) " +
           "VALUES (:orgId, :queueCode, :datePart, 0, CURRENT_DATE, 0) " +
           "ON CONFLICT (organization_id, queue_code, date_part) DO NOTHING", nativeQuery = true)
    void insertSequenceIfNotExists(
            @Param("orgId") Long orgId,
            @Param("queueCode") String queueCode,
            @Param("datePart") String datePart);
    
    /**
     * Atomically increment and get the sequence in a single operation.
     * Uses FOR UPDATE to lock the row, then updates with RETURNING.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "SELECT ts FROM TokenSequence ts WHERE ts.organizationId = :orgId " +
           "AND ts.queueCode = :queueCode AND ts.datePart = :datePart")
    Optional<TokenSequence> findByOrganizationIdAndQueueCodeAndDatePartForUpdateWithLock(
            @Param("orgId") Long orgId,
            @Param("queueCode") String queueCode,
            @Param("datePart") String datePart);
    
    /**
     * Increment the sequence atomically using native SQL with RETURNING.
     * Note: This bypasses Hibernate's optimistic locking. Currently not used by TokenGenerator
     * which uses entity-based updates with @Version. Kept for potential future optimization.
     */
    @Modifying
    @Query(value = "UPDATE token_sequences SET last_sequence = last_sequence + 1, version = version + 1 " +
           "WHERE organization_id = :orgId AND queue_code = :queueCode AND date_part = :datePart " +
           "RETURNING last_sequence", nativeQuery = true)
    Integer incrementSequenceAndGet(
            @Param("orgId") Long orgId,
            @Param("queueCode") String queueCode,
            @Param("datePart") String datePart);
}
