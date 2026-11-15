package com.queueguard.qms.repo;

import com.queueguard.qms.domain.QueueConfiguration;
import com.queueguard.qms.domain.QueueStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/**
 * Repository for QueueConfiguration entities.
 */
public interface QueueConfigurationRepository extends JpaRepository<QueueConfiguration, Long> {
    
    List<QueueConfiguration> findByOrganizationIdOrderByNameAsc(Long organizationId);
    
    List<QueueConfiguration> findByOrganizationIdAndStatusOrderByNameAsc(
            Long organizationId, QueueStatus status);
    
    Optional<QueueConfiguration> findByOrganizationIdAndQueueCode(Long organizationId, String queueCode);
    
    boolean existsByOrganizationIdAndQueueCode(Long organizationId, String queueCode);
    
    @Query("SELECT COUNT(*) FROM QueueConfiguration qc WHERE qc.organizationId = :orgId")
    long countByOrganization(@Param("orgId") Long orgId);
    
    @Query("SELECT qc FROM QueueConfiguration qc WHERE qc.organizationId = :orgId AND qc.status = 'OPEN'")
    List<QueueConfiguration> findOpenQueuesByOrganization(@Param("orgId") Long orgId);
    
    @Query("SELECT qc FROM QueueConfiguration qc WHERE qc.organizationId = :orgId AND qc.id = :queueId")
    Optional<QueueConfiguration> findByOrganizationAndId(@Param("orgId") Long orgId, @Param("queueId") Long queueId);
    
    // Find and lock configuration for update (used for capacity enforcement)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT qc FROM QueueConfiguration qc WHERE qc.id = :queueId AND qc.organizationId = :orgId")
    Optional<QueueConfiguration> findByOrganizationAndIdForUpdate(
            @Param("orgId") Long orgId, @Param("queueId") Long queueId);
}
