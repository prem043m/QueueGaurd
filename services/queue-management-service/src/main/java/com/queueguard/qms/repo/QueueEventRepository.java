package com.queueguard.qms.repo;

import com.queueguard.qms.domain.QueueEvent;
import com.queueguard.qms.domain.QueueEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Repository for QueueEvent entities (audit trail).
 */
public interface QueueEventRepository extends JpaRepository<QueueEvent, Long> {
    
    // Find events by organization
    List<QueueEvent> findByOrganizationIdOrderByOccurredAtDesc(Long organizationId);
    
    // Find events by queue configuration
    List<QueueEvent> findByQueueConfigurationIdOrderByOccurredAtDesc(Long queueConfigurationId);
    
    // Find events by queue entry
    List<QueueEvent> findByQueueEntryIdOrderByOccurredAtDesc(Long queueEntryId);
    
    // Find events by organization and queue configuration
    List<QueueEvent> findByOrganizationIdAndQueueConfigurationIdOrderByOccurredAtDesc(
            Long organizationId, Long queueConfigurationId);
    
    // Find events by organization and time range
    List<QueueEvent> findByOrganizationIdAndOccurredAtBetweenOrderByOccurredAtDesc(
            Long organizationId, Instant start, Instant end);
    
    // Find events by type
    List<QueueEvent> findByEventTypeOrderByOccurredAtDesc(QueueEventType eventType);
    
    // Find events by organization and type
    List<QueueEvent> findByOrganizationIdAndEventTypeOrderByOccurredAtDesc(
            Long organizationId, QueueEventType eventType);
    
    // Count events by organization
    long countByOrganizationId(Long organizationId);
    
    // Count events by queue configuration
    long countByQueueConfigurationId(Long queueConfigurationId);
}
