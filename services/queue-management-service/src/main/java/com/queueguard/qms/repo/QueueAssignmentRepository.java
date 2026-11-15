package com.queueguard.qms.repo;

import com.queueguard.qms.domain.QueueAssignment;
import com.queueguard.qms.domain.QueueAssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for QueueAssignment entities.
 */
public interface QueueAssignmentRepository extends JpaRepository<QueueAssignment, Long> {
    
    // Find assignments by queue entry
    List<QueueAssignment> findByQueueEntryIdOrderByAssignedAtDesc(Long queueEntryId);
    
    // Find by organization
    List<QueueAssignment> findByOrganizationIdOrderByAssignedAtDesc(Long organizationId);
    
    // Find by operator
    List<QueueAssignment> findByOperatorUserIdOrderByAssignedAtDesc(Long operatorUserId);
    
    // Find by organization and operator
    List<QueueAssignment> findByOrganizationIdAndOperatorUserIdOrderByAssignedAtDesc(
            Long organizationId, Long operatorUserId);
    
    // Find active assignment for a queue entry
    Optional<QueueAssignment> findByQueueEntryIdAndStatusIn(
            Long queueEntryId, List<QueueAssignmentStatus> activeStatuses);
    
    // Find active assignments by operator
    List<QueueAssignment> findByOperatorUserIdAndStatusInOrderByAssignedAtDesc(
            Long operatorUserId, List<QueueAssignmentStatus> activeStatuses);
    
    // Find by queue configuration
    List<QueueAssignment> findByQueueEntryIdIn(List<Long> queueEntryIds);
    
    // Check if operator has active assignments
    @Query("SELECT COUNT(*) > 0 FROM QueueAssignment qa WHERE qa.operatorUserId = :operatorId " +
           "AND qa.organizationId = :orgId " +
           "AND qa.status IN ('ASSIGNED', 'ACTIVE')")
    boolean hasActiveAssignments(@Param("orgId") Long orgId, @Param("operatorId") Long operatorId);
    
    // Count active assignments for operator in organization
    long countByOrganizationIdAndOperatorUserIdAndStatusIn(
            Long organizationId, Long operatorUserId, List<QueueAssignmentStatus> activeStatuses);
    
    // Check if entry has active assignment
    @Query("SELECT COUNT(*) > 0 FROM QueueAssignment qa WHERE qa.queueEntryId = :entryId " +
           "AND qa.status IN ('ASSIGNED', 'ACTIVE')")
    boolean existsActiveAssignmentByQueueEntryId(@Param("entryId") Long entryId);
}
